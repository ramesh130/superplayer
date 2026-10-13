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

        /// The protection a protected download acquires its offline licence under, and the store it
        /// keeps the licence in — the Kotlin `setDrm(drm, licences)` (ADR-0013 rule 13). Nil by
        /// default, and then content that declares protection downloads with no licence, and
        /// nothing can play it offline.
        ///
        /// With it, content whose multivariant playlist declares a FairPlay key (``DownloadProtection``
        /// says which declaration) acquires a persistable key after its manifest is read and before
        /// its first media byte, and a refused licence fails the item named, with nothing written.
        /// ``Downloads/remove(_:)`` deletes the package at once and the key with it, from the device
        /// at once and at the key server once the network the store downloads under is there; until
        /// then the licence store lists it in `contentIdsAwaitingRelease()` and gives it to no player.
        /// What the licence allows is ``DownloadItem/licence``, and nothing renews it.
        public var protection: DownloadProtection?

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
    /// Each download's place in the order of first enqueue, which is also its row's `ordinal` in the
    /// index: one number read by ``downloads()`` and written by the index, so the store's order and
    /// the next store's cannot disagree (#507). Taken when the content is first enqueued, kept
    /// through a failure and a re-enqueue, and given up only by ``remove(_:)``.
    private var places: [String: Int64] = [:]
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

    // The licence half (#425): nothing below is made or registered until a protected download, or a
    // release owed, needs it — so a store with no protection pays for none of it (ADR-0013 rule 15).
    private let network: any NetworkWatching
    private var licensing: (any DownloadLicensing)?
    private var licensingAsked = false
    private var licenceTail: Task<Void, Never>?
    private var watchingNetwork = false
    private var networkHolds = false
    private var releasingLicences = false
    private var releaseAgain = false
    private var expiries: [String: SuperPlayerError] = [:]
    private var lastPlace: Int64 = 0

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
    /// so the tests stand the transfer in where the simulator cannot run one — and over a watch of
    /// the network of the caller's (`NetworkWatching`), so they can take the network away.
    package init(
        configuration: Configuration,
        downloader makeDownloader: (DownloaderContext) -> any AssetDownloading,
        network makeNetwork: (DownloadConstraints) -> any NetworkWatching = {
            SystemNetworkWatch(constraints: $0)
        }
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
        let constraints = DownloadConstraints(
            meteredNetworksAllowed: configuration.meteredNetworksAllowed)
        downloader = makeDownloader(
            DownloaderContext(
                sessionIdentifier: configuration.sessionIdentifier,
                constraints: constraints,
                packagesDirectory: directory.appendingPathComponent(Self.packagesDirectoryName),
                events: { continuation.yield($0) }))
        network = makeNetwork(constraints)
        index = DownloadIndex(directory: directory)
        decision = Self.decide(configuration)
        for row in try index.rows() {
            places[row.plan.contentId] = row.ordinal
            plans[row.plan.contentId] = row.plan
            packagePaths[row.plan.contentId] = row.packagePath
            items[row.plan.contentId] = DownloadItem(
                contentId: row.plan.contentId, state: row.state, bytesDownloaded: row.bytes,
                percentDownloaded: row.percent)
        }
        // Over every row, a row this build cannot read included, so no place is given twice.
        lastPlace = try index.maxOrdinal()
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
        // A release a last process owed is owed still: the Kotlin store's pass on opening.
        releaseOwedLicences()
    }

    deinit {
        events.finish()
        downloader.close()
    }

    // MARK: Reading

    /// The download of `contentId`, or nil where the store holds none — its licence read now.
    public func download(_ contentId: String) -> DownloadItem? {
        items[contentId].map(withLicence)
    }

    /// Every download, in the order it was first enqueued — each licence read now.
    public func downloads() -> [DownloadItem] { order.compactMap { items[$0] }.map(withLicence) }

    /// The content ids by their places: the order of first enqueue.
    private var order: [String] { places.sorted { $0.value < $1.value }.map(\.key) }

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
        // Its place, taken now if it has none: the plan is written once its manifest is read, and
        // two reads finish in either order (#425); one enqueued again after a failure that wrote
        // nothing keeps the place it was first given (#507).
        let ordinal: Int64
        if let place = places[id] {
            ordinal = place
        } else {
            lastPlace += 1
            ordinal = lastPlace
            places[id] = ordinal
        }
        report(DownloadItem(contentId: id, state: .queued))
        let ceiling = decision.download
        Task {
            await self.plan(
                request, generation: generation, ordinal: ordinal, ceiling: ceiling,
                audioLanguages: audioLanguages, subtitleLanguages: subtitleLanguages)
        }
    }

    /// Removes the download of `contentId`: its task cancelled, its package deleted, then the store
    /// forgets it — reported ``DownloadState/removing`` and then removed. Content with no download is
    /// ignored.
    ///
    /// A protected download's licence goes with it (ADR-0013 rule 13): owed a release at once, so
    /// the licence store gives it to no player from the moment the viewer asked; its keys deleted
    /// from the device, which needs no network; and the key server told, now if the network the
    /// store downloads under is there and otherwise as soon as it is — the storage is the viewer's at
    /// once, and a release is a round trip a removal made on a plane still owes.
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
        places[contentId] = nil
        items[contentId] = nil
        plans[contentId] = nil
        packagePaths[contentId] = nil
        lastReported[contentId] = nil
        retries[contentId] = nil
        expiries[contentId] = nil
        for listener in listeners { listener.downloadRemoved(contentId) }
        oweRelease(contentId)
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
        network.cancel()
        licensing?.close()
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
        _ request: MediaRequest, generation: Int, ordinal: Int64, ceiling: DownloadSelectionPolicy,
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
        // Between the plan and the first media byte, a protected download's persistable key is
        // acquired (ADR-0013 rule 13): nothing has been fetched but playlists, so a refused licence
        // costs no media and writes nothing — the plan reaches the index only after it, as the Kotlin
        // store's item reaches the manager only after it.
        switch await licence(for: request, generation) {
        case .stale: return
        case .failed(let failure):
            report(DownloadItem(contentId: id, state: .failed, failure: failure))
            return
        case .held: break
        }
        do {
            try index.insert(plan, ordinal: ordinal)
        } catch {
            report(DownloadItem(contentId: id, state: .failed))
            return
        }
        plans[id] = plan
        downloader.start(plan)
    }

    /// How a download's licence step ended.
    private enum LicenceStep {
        /// Held — acquired, kept, or none needed — and the download may go on.
        case held
        /// The item failed, with the typed error where the store can name it.
        case failed(SuperPlayerError?)
        /// The item was removed or enqueued again meanwhile, and this work is nobody's.
        case stale
    }

    /// The licence step of `request`'s download: its declared keys read, and acquired where it
    /// declares any.
    private func licence(for request: MediaRequest, _ generation: Int) async -> LicenceStep {
        let id = request.contentId
        let keys = await sessionKeys(of: request.sources[0])
        guard isCurrent(id, generation) else { return .stale }
        // The multivariant playlist could not be read for its keys: a manifest that cannot be read,
        // failed unnamed as the asset's read is.
        guard let keys else { return .failed(nil) }
        guard !keys.isEmpty else { return .held }
        let failure = await acquireLicence(id, keyIdentifiers: keys)
        guard isCurrent(id, generation) else {
            // Removed while its licence was on the way: a licence for a download nobody wants, owed
            // a release at once. Enqueued again instead, the newer enqueue keeps it.
            if failure == nil, items[id] == nil { oweRelease(id) }
            return .stale
        }
        return failure.map { .failed(named($0)) } ?? .held
    }

    /// The FairPlay keys `source` declares, on a store whose protection can acquire them; an empty
    /// list on any other store, which reads nothing more; nil where the playlist could not be read.
    private func sessionKeys(of source: URL) async -> [String]? {
        // Asked of the configuration and not of `licences()`, which would make a key session for
        // content that may turn out clear.
        guard configuration.protection?.drm is any DownloadProtecting, storedLicences != nil else {
            return []
        }
        do {
            return SessionKeys.declared(in: try await Self.playlistText(source))
        } catch {
            return nil
        }
    }

    /// `url`'s body as text, read once more for what an `AVURLAsset` does not say — its
    /// `EXT-X-SESSION-KEY`s. One extra request of the multivariant playlist, and only on a store
    /// configured with protection, the price of the store, not the asset, reading the declaration.
    /// It goes as the asset's read went: with no credential, since a `HeaderProvider` answers a
    /// refusal rather than holding a credential to send first, and asked once, a failure failing the
    /// item unnamed as an unreadable manifest does on Android — so a manifest the asset could read
    /// is one this read meets the same way.
    nonisolated private static func playlistText(_ url: URL) async throws -> String {
        let (data, response) = try await playlistSession.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 200
        guard (200..<300).contains(status) else { throw URLError(.badServerResponse) }
        return String(decoding: data, as: UTF8.self)
    }

    /// Ephemeral, so the read lands nothing on disk outside the directory and is never answered
    /// from a cache instead of the origin.
    nonisolated private static let playlistSession = URLSession(configuration: .ephemeral)

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

    // MARK: Licences (#425)

    /// The licence store, where the store was configured with one this product can read.
    private var storedLicences: (any StoredLicences)? {
        configuration.protection?.licences as? any StoredLicences
    }

    /// The store's licence exchanges, made the first time they are needed and never before — nil
    /// where the protection or the licence store is not one that can acquire, in which case
    /// protected content downloads with no licence, as on a store configured with none.
    private func licences() -> (any DownloadLicensing)? {
        if licensingAsked { return licensing }
        guard !released, let protection = configuration.protection,
            let drm = protection.drm as? any DownloadProtecting, let stored = storedLicences
        else { return nil }
        licensingAsked = true
        let fallback = Self.decide(configuration).retry.licence
        licensing = drm.downloadLicences(
            over: stored,
            // Re-read at every ask, as a player's licence budget is.
            budget: { [weak self] in self?.decision.retry.licence ?? fallback },
            headerProvider: (configuration.resilience as? any FailureClassifying)?.headerProvider)
        return licensing
    }

    /// Runs `work` once every licence exchange asked for before it has ended — one at a time, the
    /// Kotlin `DownloadLicences`' single thread — so a release and an acquisition of one content id
    /// never interleave, and two acquisitions of one never both write.
    private func onLicenceQueue<T: Sendable>(_ work: @escaping @MainActor () async -> T) async -> T
    {
        let previous = licenceTail
        let task = Task { @MainActor in
            await previous?.value
            return await work()
        }
        licenceTail = Task { _ = await task.value }
        return await task.value
    }

    /// Acquires `contentId`'s licence for `keyIdentifiers`, answering what it failed on or nil.
    ///
    /// A licence still in force is kept rather than acquired again, which would leave the first one
    /// counted against the device with nobody holding it — the Kotlin store's rule. One that has
    /// expired, or that an earlier removal still owes, is released first, at the device and the
    /// server: the store's index holds one licence per content id, and a release the server never
    /// heard of is the defect a release exists to prevent. The network is there, since the
    /// acquisition is about to use it; a release it refuses fails the item with nothing written.
    private func acquireLicence(_ contentId: String, keyIdentifiers: [String]) async
        -> (any Error)?
    {
        guard let protection = configuration.protection else { return nil }
        return await onLicenceQueue { [weak self] () -> (any Error)? in
            guard let self, !self.released else { return CancellationError() }
            // A protection that cannot acquire downloads with no licence, as `protection` says.
            guard let licensing = self.licences(), let stored = self.storedLicences else {
                return nil
            }
            do {
                if let standing = try stored.standing(of: contentId) {
                    guard standing.isExpired else { return nil }
                    _ = try stored.awaitRelease(contentId)
                }
                if try stored.awaitingRelease().contains(contentId) {
                    try await licensing.release(contentId, tellingServer: true)
                }
                try await licensing.acquire(
                    contentId, keyIdentifiers: keyIdentifiers,
                    licenceDuration: protection.licenceDuration,
                    playbackDuration: protection.playbackDuration)
                return nil
            } catch {
                return error
            }
        }
    }

    /// Releases every licence owed a release: each key deleted from the device at once, and the key
    /// server told where the network the store downloads under is there. One the server refuses
    /// stays owed for a later pass — when the network next comes, when another removal asks, or
    /// when the store is next opened — as the Kotlin store keeps it for its next chance.
    ///
    /// The network is watched while anything is owed and not otherwise.
    private func releaseOwedLicences() {
        guard !released, let stored = storedLicences else { return }
        let owed = (try? stored.awaitingRelease()) ?? []
        guard !owed.isEmpty else { return stopWatchingNetwork() }
        guard let licensing = licences() else { return }
        if !watchingNetwork {
            watchingNetwork = true
            network.watch { [weak self] holds in
                guard let self else { return }
                let came = holds && !self.networkHolds
                self.networkHolds = holds
                if came { self.releaseOwedLicences() }
            }
        }
        guard !releasingLicences else {
            releaseAgain = true
            return
        }
        releasingLicences = true
        // Before the watch's first reading this is false, so the first pass after a removal deletes
        // on the device alone and the reading that follows — the path at once, when there is one —
        // starts the pass that tells the server. Two passes rather than a wait for the reading,
        // because the device half needs no network and should not wait for one.
        let tellingServer = networkHolds
        Task { [weak self] in
            guard let self else { return }
            await self.onLicenceQueue { [weak self] in
                for contentId in owed.sorted() {
                    guard let self, !self.released else { return }
                    try? await licensing.release(contentId, tellingServer: tellingServer)
                }
            }
            self.releasingLicences = false
            if self.releaseAgain {
                self.releaseAgain = false
                self.releaseOwedLicences()
            } else if (try? stored.awaitingRelease())?.isEmpty == true {
                self.stopWatchingNetwork()
            }
        }
    }

    /// Owes `contentId`'s licence a release, where the licence store holds one, and starts a pass.
    private func oweRelease(_ contentId: String) {
        guard let stored = storedLicences, (try? stored.awaitRelease(contentId)) == true else {
            return
        }
        releaseOwedLicences()
    }

    private func stopWatchingNetwork() {
        guard watchingNetwork else { return }
        watchingNetwork = false
        networkHolds = false
        network.cancel()
    }

    /// `item` with what its licence allows read from the licence store now, on a store with one.
    private func withLicence(_ item: DownloadItem) -> DownloadItem {
        guard let stored = storedLicences,
            let standing = try? stored.standing(of: item.contentId)
        else { return item }
        var expiry: SuperPlayerError?
        if standing.isExpired {
            expiry =
                expiries[item.contentId]
                ?? named(
                    OfflineLicenceExpiredError(
                        contentId: item.contentId,
                        playbackDurationRemainingMs: standing.playbackDurationRemainingMs,
                        licenceDurationRemainingMs: standing.licenceDurationRemainingMs))
            // Made once, so an item reporting it again compares equal.
            expiries[item.contentId] = expiry
        } else {
            // Renewed since: a later expiry is a new one, with its own remaining durations.
            expiries[item.contentId] = nil
        }
        return item.with(
            licence: DownloadLicence(
                isExpired: standing.isExpired, renewalDue: standing.renewalDue, expiry: expiry))
    }

    /// `error` as the typed error it is, where the store has a resilience to name it; nil otherwise.
    private func named(_ error: any Error) -> SuperPlayerError? {
        (configuration.resilience as? any DownloadFailureClassifying)?
            .downloadFailure(of: error as NSError)
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

    /// Makes `item` current and tells every listener, unless it is what they were last told — its
    /// licence read on a change of state and carried otherwise, because a progress report is no
    /// reason to read the licence store (the Kotlin store's rule).
    private func report(_ item: DownloadItem) {
        items[item.contentId] = item
        let last = lastReported[item.contentId]
        let told =
            last?.state == item.state ? item.with(licence: last?.licence) : withLicence(item)
        guard last != told else { return }
        lastReported[item.contentId] = told
        for listener in listeners { listener.downloadChanged(told) }
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
