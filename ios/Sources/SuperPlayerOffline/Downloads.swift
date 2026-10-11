// Copyright 2026 The SuperPlayer Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import AVFoundation
import Foundation
import SuperPlayerCore

/// A store of downloads in a directory the consumer named — the Kotlin `Downloads`, over a
/// background `AVAssetDownloadURLSession` whose identifier is the store's (`docs/ios-plan.md` §3.7).
///
/// ```swift
/// let downloads = try Downloads(
///     configuration: .init(directory: downloadsDirectory, sessionIdentifier: "app.downloads"))
/// downloads.addListener(self)
/// downloads.enqueue(request, audioLanguages: ["en"], subtitleLanguages: [])
///
/// var player = SuperPlayer.Configuration()
/// player.offlineContent = downloads   // a downloaded request plays from its package
/// ```
///
/// **What it does with a request** (ADR-0013 rules 8 and 12, read for iOS): it reads the manifest
/// with an `AVURLAsset`; refuses live content (``DownloadRefusal/liveContent``); chooses one
/// variant under ``PlaybackDecision/download`` and the languages asked for that the content carries
/// (`DownloadSelection`); and hands the system a download task configured with them. The finished
/// `.movpkg` is moved into the directory and *is* the pin — there is no streaming cache on iOS to
/// pin it in (§3.4) — and a player configured with this store opens it with no network
/// (``SuperPlayer/Configuration/offlineContent``), while an unfinished one plays from its task's
/// asset.
///
/// **What lands in the directory** is the store's index, `downloads.db` and SQLite's journal beside
/// it, and `packages/`, one package per download. Nothing else: the system writes a task's package
/// where it chooses until the task completes, and the store moves it in then.
///
/// **What the app wires** is one hand-off. The system relaunches an app in the background for a
/// session's events and hands it a completion handler; the app opens its store and passes them on,
/// which no library can do from outside the app delegate:
///
/// ```swift
/// func application(_ application: UIApplication,
///                  handleEventsForBackgroundURLSession identifier: String,
///                  completionHandler: @escaping () -> Void) {
///     _ = downloads.handleEventsForBackgroundURLSession(identifier, completionHandler: completionHandler)
/// }
/// ```
///
/// No service, no notification and no scheduler exist here: the background session *is* the
/// scheduler, and survival across process death and network loss is the platform's promise — kept
/// by re-attaching to the session by identifier when the store opens, and tested on a device (#436).
///
/// **One store per session identifier for the life of the process.** A background session's
/// identifier names one session; two stores on it would be two delegates for one stream of events,
/// and a session made again on an identifier whose last session is still being invalidated is one
/// Apple documents as unsupported. On the main actor, as every call and callback is — the index's
/// writes included, one row per whole percent, which is a few small writes a download and not
/// worth a thread of their own.
///
/// **Not on tvOS**, whose SDK has no `AVAssetDownloadURLSession`: there ``init(configuration:)``
/// throws ``DownloadsError/unavailableOnThisPlatform``, as `SuperPlayerDRM`'s offline verbs fail
/// typed where persistable keys do not exist.
@MainActor
public final class Downloads: OfflineContent, OfflineAssets {

    /// How a store is opened — fixed for its life, as a player's configuration is.
    public struct Configuration: Sendable {

        /// Where the store keeps everything it writes: its index and its packages. Created if it
        /// does not exist; a file at that path is refused (``DownloadsError/notADirectory(_:)``).
        public var directory: URL

        /// The background session's identifier: the store's, and what the app's
        /// `handleEventsForBackgroundURLSession` hand-off names. Stable across launches, since a
        /// relaunched app re-attaches to its downloads by it.
        public var sessionIdentifier: String

        /// The use case whose ``PlaybackDecision/download`` and ``RetryPolicy/segment`` the store
        /// downloads under, unless ``policy`` decides; ``PlaybackProfile/videoOnDemand`` by default —
        /// the Kotlin `setProfile`.
        public var profile: PlaybackProfile = .videoOnDemand

        /// What decides the rendition ceiling and the retry budget instead of ``profile``'s static
        /// decision — the Kotlin `setPolicy`. Consulted at each enqueue with no observations, since
        /// a store is no player and observes nothing.
        public var policy: (any PlaybackPolicy)?

        /// What names a failed download, or nil for a store that names nothing — the Kotlin
        /// `setResilience`, taking `SuperPlayerResilience`'s `Resilience.standard()`. With it, a
        /// failure asking again cannot help — a full disk — fails the item at once, named, and every
        /// other is named once its budget is spent; without it every failure spends the budget and
        /// ends unnamed.
        public var resilience: (any PlaybackResilience)?

        /// Whether a download may use a metered network — cellular, or a personal hotspot. False by
        /// default, so a download spends no data allowance nobody agreed to (ADR-0013 rule 10); Low
        /// Data Mode holds a download whatever this says.
        ///
        /// **Fixed for the store's life**, where the Kotlin property is set at run time: it is the
        /// background session's configuration, which a session cannot change once made. A viewer's
        /// change of mind is a store opened again.
        public var meteredNetworksAllowed = false

        public init(directory: URL, sessionIdentifier: String) {
            self.directory = directory
            self.sessionIdentifier = sessionIdentifier
        }
    }

    /// The configuration this store was opened with.
    public let configuration: Configuration

    private let downloader: any AssetDownloading
    private let index: DownloadIndex
    private let events: AsyncStream<DownloadEvent>.Continuation
    private var consumer: Task<Void, Never>?
    private var order: [String] = []
    private var items: [String: DownloadItem] = [:]
    private var plans: [String: DownloadPlan] = [:]
    private var packagePaths: [String: String] = [:]
    private var listeners: [any DownloadsListener] = []
    private var lastReported: [String: DownloadItem] = [:]
    private var retries: [String: SpentBudget] = [:]
    private var generations: [String: Int] = [:]
    private var decision: PlaybackDecision
    private var random = SystemRandomNumberGenerator()
    private var released = false

    /// Opens the store in ``Configuration/directory``, reading its index and re-attaching to the
    /// background session by ``Configuration/sessionIdentifier``: a download an earlier process left
    /// running is the system's still, and one it no longer holds is started again from its plan.
    public convenience init(configuration: Configuration) throws {
        #if os(tvOS)
            throw DownloadsError.unavailableOnThisPlatform
        #else
            try self.init(configuration: configuration) { SystemAssetDownloads(context: $0) }
        #endif
    }

    /// The store over a downloader of the caller's: the seam `AssetDownloading` describes, `package`
    /// so the tests stand the transfer in where the simulator cannot run one.
    package init(
        configuration: Configuration,
        downloader makeDownloader: (DownloaderContext) -> any AssetDownloading
    ) throws {
        let directory = configuration.directory
        var isDirectory: ObjCBool = false
        if FileManager.default.fileExists(atPath: directory.path, isDirectory: &isDirectory) {
            guard isDirectory.boolValue else { throw DownloadsError.notADirectory(directory) }
        } else {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true)
        }
        self.configuration = configuration
        let (stream, continuation) = AsyncStream<DownloadEvent>.makeStream()
        events = continuation
        downloader = makeDownloader(
            DownloaderContext(
                sessionIdentifier: configuration.sessionIdentifier,
                constraints: DownloadConstraints(
                    meteredNetworksAllowed: configuration.meteredNetworksAllowed),
                packagesDirectory: directory.appendingPathComponent(Self.packagesDirectoryName),
                events: { continuation.yield($0) }))
        index = DownloadIndex(directory: directory)
        decision = Self.decide(configuration)
        for row in try index.rows() {
            order.append(row.plan.contentId)
            plans[row.plan.contentId] = row.plan
            packagePaths[row.plan.contentId] = row.packagePath
            items[row.plan.contentId] = DownloadItem(
                contentId: row.plan.contentId, state: row.state, bytesDownloaded: row.bytes,
                percentDownloaded: row.percent)
        }
        // One consumer, in order: the downloader's events reach the store as it observed them.
        consumer = Task { [weak self] in
            for await event in stream { self?.handle(event) }
        }
        // Only what the index held at open: an item enqueued while the session is asked has its
        // own start coming, and starting it here as well would be two tasks for one download.
        let unfinished = order.filter { id in
            items[id]?.state == .queued || items[id]?.state == .downloading
        }
        Task { [weak self, downloader] in
            let held = await downloader.inFlight()
            self?.resume(unfinished, except: held)
        }
    }

    deinit {
        events.finish()
        downloader.close()
    }

    // MARK: Reading

    /// The download of `contentId`, or nil where the store holds none.
    public func download(_ contentId: String) -> DownloadItem? { items[contentId] }

    /// Every download, in the order it was first enqueued.
    public func downloads() -> [DownloadItem] { order.compactMap { items[$0] } }

    public func addListener(_ listener: any DownloadsListener) {
        guard !listeners.contains(where: { $0 === listener }) else { return }
        listeners.append(listener)
    }

    public func removeListener(_ listener: any DownloadsListener) {
        listeners.removeAll { $0 === listener }
    }

    // MARK: Writing

    /// Downloads `request` — its first source — in the languages asked for that it carries: each
    /// of `audioLanguages` the content declares an audio rendition in, and with none of them carried
    /// the one audio rendition a player would choose; each of `subtitleLanguages` it carries, and
    /// none otherwise. A language is a BCP 47 tag, `en` naming `en-US` and the reverse.
    ///
    /// Reported ``DownloadState/queued`` at once, while the manifest is read; then refused, failed
    /// on a manifest that cannot be read, or handed to the system. Content already held is left as
    /// it is — downloading, or downloaded — and one that failed is downloaded afresh.
    public func enqueue(
        _ request: MediaRequest, audioLanguages: [String] = [], subtitleLanguages: [String] = []
    ) {
        guard !released else { return }
        let id = request.contentId
        if let held = items[id], held.state != .failed { return }
        retries[id] = nil
        let generation = nextGeneration(of: id)
        decision = Self.decide(configuration)
        if !order.contains(id) { order.append(id) }
        report(DownloadItem(contentId: id, state: .queued))
        let ceiling = decision.download
        Task {
            await self.plan(
                request, generation: generation, ceiling: ceiling, audioLanguages: audioLanguages,
                subtitleLanguages: subtitleLanguages)
        }
    }

    /// Removes the download of `contentId`: its task cancelled, its package deleted, then the store
    /// forgets it — reported ``DownloadState/removing`` and then removed. Content with no download is
    /// ignored.
    public func remove(_ contentId: String) {
        guard !released, let item = items[contentId] else { return }
        _ = nextGeneration(of: contentId)
        report(
            DownloadItem(
                contentId: contentId, state: .removing, bytesDownloaded: item.bytesDownloaded,
                percentDownloaded: item.percentDownloaded))
        downloader.cancel(contentId)
        if let path = packagePaths[contentId] {
            try? FileManager.default.removeItem(at: resolved(path))
        }
        try? index.remove(contentId)
        order.removeAll { $0 == contentId }
        items[contentId] = nil
        plans[contentId] = nil
        packagePaths[contentId] = nil
        lastReported[contentId] = nil
        retries[contentId] = nil
        for listener in listeners { listener.downloadRemoved(contentId) }
    }

    /// The app's `handleEventsForBackgroundURLSession` hand-off: answers whether `identifier` is
    /// this store's session, and if it is, holds `completionHandler` until the session has delivered
    /// the events the system woke the app for, then calls it on the main queue.
    public func handleEventsForBackgroundURLSession(
        _ identifier: String, completionHandler: @escaping @Sendable () -> Void
    ) -> Bool {
        guard identifier == configuration.sessionIdentifier else { return false }
        downloader.handleBackgroundEvents(completion: completionHandler)
        return true
    }

    /// Closes the store. Downloads the system is running carry on, and a store opened again on the
    /// same identifier re-attaches to them; a closed store reports nothing more. Idempotent.
    public func release() {
        guard !released else { return }
        released = true
        downloader.close()
        index.close()
        events.finish()
        consumer?.cancel()
        listeners = []
    }

    // MARK: OfflineAssets

    package func offlineAsset(for contentId: String) -> AVURLAsset? {
        switch items[contentId]?.state {
        case .completed: packagePaths[contentId].map { AVURLAsset(url: resolved($0)) }
        case .downloading: downloader.inProgressAsset(of: contentId)
        default: nil
        }
    }

    // MARK: Deciding

    /// Reads `request`'s manifest and hands the downloader a plan, unless the item was removed or
    /// enqueued again meanwhile.
    private func plan(
        _ request: MediaRequest, generation: Int, ceiling: DownloadSelectionPolicy,
        audioLanguages: [String], subtitleLanguages: [String]
    ) async {
        let id = request.contentId
        let reading: ManifestReading
        do {
            reading = try await ManifestReading.of(request.sources[0])
        } catch {
            // The Kotlin store's answer: a manifest that cannot be read fails the item unnamed.
            guard isCurrent(id, generation) else { return }
            report(DownloadItem(contentId: id, state: .failed))
            return
        }
        guard isCurrent(id, generation) else { return }
        guard !reading.isLive else {
            // Kept in memory only, as on Android: a refusal has nothing to resume.
            report(DownloadItem(contentId: id, state: .failed, refusal: .liveContent))
            return
        }
        var audio = DownloadSelection.carried(audioLanguages, offered: reading.audioLanguages)
        if audio.isEmpty, let chosen = reading.defaultAudioLanguage { audio = [chosen] }
        let plan = DownloadPlan(
            contentId: id, source: request.sources[0], title: request.title ?? id,
            variantPeakBitRateBps: DownloadSelection.variant(
                among: reading.variants, under: ceiling)?.peakBitRateBps,
            audioLanguages: audio,
            subtitleLanguages: DownloadSelection.carried(
                subtitleLanguages, offered: reading.subtitleLanguages))
        do {
            try index.insert(plan)
        } catch {
            report(DownloadItem(contentId: id, state: .failed))
            return
        }
        plans[id] = plan
        // Between the plan and the first media byte is where a protected download's persistable key
        // is acquired (ADR-0013 rule 13, #425): the plan names what will be downloaded, and nothing
        // has been fetched but playlists.
        downloader.start(plan)
    }

    private static func decide(_ configuration: Configuration) -> PlaybackDecision {
        (configuration.policy ?? configuration.profile.policy).decide(PlaybackConditions())
    }

    /// Starts again each of `unfinished` — read from the index at open — that the session no
    /// longer holds and that nothing has touched since.
    private func resume(_ unfinished: [String], except held: Set<String>) {
        guard !released else { return }
        for id in unfinished where !held.contains(id) && generations[id] == nil {
            guard let item = items[id], item.state == .queued || item.state == .downloading,
                let plan = plans[id]
            else { continue }
            downloader.start(plan)
        }
    }

    /// A new generation of `contentId`'s download: every enqueue and removal starts one, so that work
    /// begun for an earlier one — a manifest read, a retry's wait — finds itself stale and does
    /// nothing. A plan cannot tell, being a value: one enqueued again after a removal is equal.
    private func nextGeneration(of contentId: String) -> Int {
        let next = (generations[contentId] ?? 0) + 1
        generations[contentId] = next
        return next
    }

    /// Whether `generation` is still `contentId`'s, its item still queued, and the store open.
    private func isCurrent(_ contentId: String, _ generation: Int) -> Bool {
        !released && generations[contentId] == generation && items[contentId]?.state == .queued
    }

    // MARK: Events

    private func handle(_ event: DownloadEvent) {
        guard !released else { return }
        switch event {
        case .progress(let id, let fraction, let bytes):
            progressed(id, fraction: fraction, bytes: bytes)
        case .finished(let id, let package):
            finished(id, package: package)
        case .failed(let id, let error):
            failed(id, error: error as NSError)
        }
    }

    /// The first report announces ``DownloadState/downloading``, and each later one a whole percent
    /// passed — never backwards, since a retried task reports again from its start.
    private func progressed(_ id: String, fraction: Double, bytes: Int64) {
        guard let item = items[id], item.state == .queued || item.state == .downloading else {
            return
        }
        let percent = max(min(fraction, 1), 0) * 100
        if item.state == .downloading, let reported = item.percentDownloaded,
            Int(percent) <= Int(reported)
        {
            return
        }
        let next = DownloadItem(
            contentId: id, state: .downloading, bytesDownloaded: max(bytes, item.bytesDownloaded),
            percentDownloaded: max(percent, item.percentDownloaded ?? 0))
        try? index.update(
            id, state: next.state, bytes: next.bytesDownloaded, percent: next.percentDownloaded)
        report(next)
    }

    private func finished(_ id: String, package: URL) {
        guard let item = items[id], item.state == .queued || item.state == .downloading else {
            return
        }
        let path = relativePath(of: package)
        packagePaths[id] = path
        retries[id] = nil
        let next = DownloadItem(
            contentId: id, state: .completed, bytesDownloaded: item.bytesDownloaded,
            percentDownloaded: 100)
        try? index.update(
            id, state: .completed, bytes: next.bytesDownloaded, percent: 100, packagePath: path)
        report(next)
    }

    /// A failed task: named where the store has a resilience; failed at once where asking again
    /// cannot help; otherwise started again on ``RetryPolicy/segment`` with `Backoff`'s jitter, the
    /// item staying ``DownloadState/downloading`` meanwhile, and failed once the budget is spent —
    /// ADR-0013 rule 14's #254 addendum at task granularity (`docs/ios-plan.md` §3.7), because the
    /// requests inside a task are AVFoundation's and retried by it.
    ///
    /// The budget bounds one task's worth of trying, and starts over once bytes have moved since
    /// the last failure, as the Kotlin budget bounds a request and resets on progress.
    private func failed(_ id: String, error: NSError) {
        guard let item = items[id], item.state == .queued || item.state == .downloading,
            let plan = plans[id]
        else { return }
        let named = (configuration.resilience as? any DownloadFailureClassifying)?
            .downloadFailure(of: error)
        if named?.isRetryable == false { return fail(item, named) }
        let budget = decision.retry.segment
        let spent = retries[id].flatMap { $0.bytes == item.bytesDownloaded ? $0.count : nil } ?? 0
        guard spent < budget.maxRetries else { return fail(item, named) }
        let retry = spent + 1
        retries[id] = SpentBudget(count: retry, bytes: item.bytesDownloaded)
        let waitMs = Backoff.delayMs(for: budget, retry: retry, using: &random)
        let generation = generations[id]
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(waitMs))
            guard let self, !self.released, self.generations[id] == generation,
                let state = self.items[id]?.state, state == .queued || state == .downloading
            else { return }
            self.downloader.start(plan)
        }
    }

    private func fail(_ item: DownloadItem, _ failure: SuperPlayerError?) {
        retries[item.contentId] = nil
        try? index.update(
            item.contentId, state: .failed, bytes: item.bytesDownloaded,
            percent: item.percentDownloaded)
        report(
            DownloadItem(
                contentId: item.contentId, state: .failed, bytesDownloaded: item.bytesDownloaded,
                percentDownloaded: item.percentDownloaded, failure: failure))
    }

    /// Makes `item` current and tells every listener, unless it is what they were last told.
    private func report(_ item: DownloadItem) {
        items[item.contentId] = item
        guard lastReported[item.contentId] != item else { return }
        lastReported[item.contentId] = item
        for listener in listeners { listener.downloadChanged(item) }
    }

    // MARK: Paths

    /// `package`'s path relative to the directory, for the reason `DownloadIndex` gives: a
    /// container's absolute path changes between installs.
    private func relativePath(of package: URL) -> String {
        let base = configuration.directory.standardizedFileURL.path + "/"
        let path = package.standardizedFileURL.path
        return path.hasPrefix(base) ? String(path.dropFirst(base.count)) : path
    }

    private func resolved(_ path: String) -> URL {
        path.hasPrefix("/")
            ? URL(fileURLWithPath: path) : configuration.directory.appendingPathComponent(path)
    }

    /// Where finished packages are kept inside the directory.
    static let packagesDirectoryName = "packages"

    /// One task's worth of retries spent, and the bytes on disk when the last one began.
    private struct SpentBudget {
        let count: Int
        let bytes: Int64
    }
}

/// What the store reads from a manifest before it plans a download: whether it is live, its
/// variants, and the languages its renditions are declared in — as values, read off the
/// `AVURLAsset` away from the main actor so no AVFoundation object crosses into the store.
private struct ManifestReading: Sendable {
    let isLive: Bool
    let variants: [DownloadSelection.Variant]
    let audioLanguages: [String]
    let defaultAudioLanguage: String?
    let subtitleLanguages: [String]

    /// Reads `source`. A live HLS stream's duration is indefinite, which is what tells it live: an
    /// `AVURLAsset` reports the duration of the media playlist it loaded, and a playlist with no
    /// `EXT-X-ENDLIST` has none (observed on the iOS 27.1 simulator, #424). The variants, groups and
    /// default are read only for content that is not.
    ///
    /// spec: RFC 8216 §4.3.3.4 — `EXT-X-ENDLIST` marks a playlist to which nothing more will be added.
    nonisolated static func of(_ source: URL) async throws -> ManifestReading {
        let asset = AVURLAsset(url: source)
        let duration = try await asset.load(.duration)
        guard !duration.isIndefinite else {
            return ManifestReading(
                isLive: true, variants: [], audioLanguages: [], defaultAudioLanguage: nil,
                subtitleLanguages: [])
        }
        let variants = try await asset.load(.variants).map { variant in
            DownloadSelection.Variant(
                peakBitRateBps: variant.peakBitRate,
                heightPx: variant.videoAttributes.map { Int($0.presentationSize.height) })
        }
        let audible = try await asset.loadMediaSelectionGroup(for: .audible)
        let legible = try await asset.loadMediaSelectionGroup(for: .legible)
        return ManifestReading(
            isLive: false, variants: variants,
            audioLanguages: audible?.options.compactMap(\.extendedLanguageTag) ?? [],
            defaultAudioLanguage: audible?.defaultOption?.extendedLanguageTag,
            subtitleLanguages: legible?.options.compactMap(\.extendedLanguageTag) ?? [])
    }
}

/// What a download store refuses to open on.
public enum DownloadsError: Error, Sendable, Equatable {
    /// The configured directory is a file.
    case notADirectory(URL)
    /// The store's index could not be read or written: SQLite's result code and message.
    case database(code: Int, message: String)
    /// The platform has no download session: tvOS, whose SDK has no `AVAssetDownloadURLSession`.
    case unavailableOnThisPlatform
}
