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
import SuperPlayerCore
import SuperPlayerDRM
import SuperPlayerOffline
import SuperPlayerResilience
import SuperPlayerTestkit
import XCTest
import os

/// A protected download carries its persistable licence (ADR-0013 rule 13, #425) — the Kotlin
/// `DownloadLicenceTest` and `DownloadLicenceBudgetTest`: acquired before the first media byte,
/// refused typed with nothing written, reported on the item, and released when the download is
/// removed — at once with a network, and once one comes without.
///
/// Every claim a viewer cares about is a count at an origin rather than an inference from state: one
/// licence request for a download, made before the downloader is handed anything; one more, the
/// release, for its removal, carrying the device's proof of the deletion. Three things are stood in,
/// each at its one `package` seam and each for a reason recorded where the seam is: the transfer
/// (`StandInDownloads`, no `AVAssetDownloadTask` runs in `xctest`), the key session
/// (``DownloadKeySession``, the simulator has no FairPlay), and the network (``StandInNetwork``,
/// a test cannot take the simulator's away). The manifest read, the `EXT-X-SESSION-KEY` read, the
/// store's ordering, the licence exchange against the harness's licence server, the licence
/// store's SQLite file and the classifier are the shipped code.
@MainActor
final class DownloadLicenceTests: XCTestCase {

    /// A ceiling on every wait, never an assertion: what a cold simulator needs to read a manifest
    /// and fetch four segments, with room, as `DownloadsTests` allows.
    private let bound: Duration = .seconds(30)
    private let month: Duration = .seconds(30 * 24 * 3_600)
    private let twoDays: Duration = .seconds(2 * 24 * 3_600)
    private let key = TestContent.defaultKeyIdentifier

    private var harness: PlaybackHarness!
    private var parent: URL!
    private var stores: [Downloads] = []
    private var licenceStores: [OfflineLicenceStore] = []

    override func setUp() async throws {
        harness = PlaybackHarness()
        parent = FileManager.default.temporaryDirectory
            .appendingPathComponent("download-licences-\(UUID().uuidString)")
    }

    override func tearDown() async throws {
        for store in stores { store.release() }
        stores = []
        for store in licenceStores { store.close() }
        licenceStores = []
        harness.stop()
        try? FileManager.default.removeItem(at: parent)
    }

    // MARK: Acquisition

    /// The centre of rule 13: the licence is asked for after the manifest and before the first media
    /// byte — counted at both origins at the moment the downloader is handed the plan — and the item
    /// reports what it allows. The control is clear content on the same store, which asks for no
    /// licence and reports none, so the rule is not "every download acquires".
    func testTheLicenceIsAcquiredBeforeTheFirstMediaByteAndTheItemReportsIt() async throws {
        let fixture = try await open()
        let atStart = Snapshots()
        fixture.downloads.onStart = { [content = fixture.content, server = fixture.server] _ in
            atStart.record(
                posts: server.networkRequests().filter { $0.method == "POST" }.count,
                media: content.networkRequests().filter { $0.kind == .mediaSegment }.count)
        }

        fixture.store.enqueue(request("film", fixture.content))
        try await completion(of: "film", in: fixture.store)

        XCTAssertEqual(atStart.values.first?.posts, 1, "one licence request before the task")
        XCTAssertEqual(atStart.values.first?.media, 0, "and no media byte before it")
        XCTAssertGreaterThan(
            fixture.content.networkRequests().filter { $0.kind == .mediaSegment }.count, 0)
        XCTAssertEqual(fixture.keySession.persistableRequests, [key])
        let licence = try XCTUnwrap(fixture.store.download("film")?.licence)
        XCTAssertFalse(licence.isExpired)
        XCTAssertFalse(licence.renewalDue)
        XCTAssertNil(licence.expiry)
        XCTAssertNotNil(try fixture.licences.licence(for: "film"))
        XCTAssertEqual(fixture.recorder.changes.last?.licence, licence, "a listener hears it too")

        let clear = try await harness.serve(.hls())
        fixture.store.enqueue(request("clip", clear))
        try await completion(of: "clip", in: fixture.store)
        XCTAssertNil(fixture.store.download("clip")?.licence)
        XCTAssertEqual(posts(fixture.server), 1, "clear content asks the licence server nothing")
        XCTAssertEqual(try fixture.licences.contentIds(), ["film"])
    }

    /// Content keyed apart declares two session keys, and both are in the one licence.
    func testEverySessionKeyTheContentDeclaresIsAcquired() async throws {
        let second = "skd://keys.example/film-2"
        let fixture = try await open(content: .protectedHls(keyIdentifiers: [key, second]))
        fixture.store.enqueue(request("film", fixture.content))
        try await completion(of: "film", in: fixture.store)

        XCTAssertEqual(fixture.keySession.persistableRequests, [key, second])
        XCTAssertEqual(posts(fixture.server), 2)
    }

    /// A licence refused — once the store's licence budget is spent — fails the item named, with
    /// nothing written: no task, no media byte, no row in the store's index, no licence stored. The
    /// control is a refusal that relents inside the budget, which completes, so the rule is not
    /// "a refusal fails" but "a refusal the budget cannot outlast fails".
    func testARefusedLicenceFailsTheItemNamedWithNothingWritten() async throws {
        let fixture = try await open(
            faults: FaultScript.Builder().failWithHttpStatus(403, kind: .licence, index: 1).build())
        fixture.store.enqueue(request("film", fixture.content))
        try await harness.until("the item fails", timeout: bound) {
            fixture.store.download("film")?.state == .failed
        }

        let item = try XCTUnwrap(fixture.store.download("film"))
        XCTAssertEqual(item.failure?.causeClass, "Drm.LicenceAcquisition")
        XCTAssertNil(item.licence)
        XCTAssertEqual(posts(fixture.server), 1 + Self.quickBudget.maxRetries)
        XCTAssertTrue(fixture.downloads.started.isEmpty, "the downloader was handed nothing")
        XCTAssertEqual(
            fixture.content.networkRequests().filter { $0.kind == .mediaSegment }.count, 0)
        XCTAssertEqual(try fixture.licences.contentIds(), [])
        fixture.store.release()
        let reopened = try await open(directory: fixture.directory, licences: fixture.licences)
        XCTAssertTrue(reopened.store.downloads().isEmpty, "the store's index holds no row")

        let relenting = try await open(
            faults: FaultScript.Builder()
                .failWithHttpStatus(500, kind: .licence, index: 1, firstAttempts: 1).build(),
            in: "relenting")
        relenting.store.enqueue(request("film", relenting.content))
        try await completion(of: "film", in: relenting.store)
        XCTAssertEqual(posts(relenting.server), 2)
    }

    /// A licence whose durations have run out is reported dead on a completed item, named as the
    /// typed error playing it would end with — readable before any player is made. Completed,
    /// because every byte is there.
    func testAnExpiredLicenceIsReportedOnTheCompletedItemAsTheErrorPlayingItWouldEndWith()
        async throws
    {
        let fixture = try await open(licenceDuration: .zero, playbackDuration: .zero)
        fixture.store.enqueue(request("film", fixture.content))
        try await completion(of: "film", in: fixture.store)

        let licence = try XCTUnwrap(fixture.store.download("film")?.licence)
        XCTAssertTrue(licence.isExpired)
        XCTAssertEqual(licence.expiry?.causeClass, "Drm.LicenceExpired")
        XCTAssertEqual(fixture.store.download("film")?.state, .completed)
    }

    /// A licence inside the licence store's renewal threshold is reported due and still valid — and
    /// nothing renews it: one licence request, the acquisition (ADR-0012 rule 10).
    func testALicenceInsideTheRenewalThresholdIsReportedDueAndNothingRenewsIt() async throws {
        // An hour, well inside the licence store's day.
        let fixture = try await open(licenceDuration: .seconds(3_600))
        fixture.store.enqueue(request("film", fixture.content))
        try await completion(of: "film", in: fixture.store)

        let licence = try XCTUnwrap(fixture.store.download("film")?.licence)
        XCTAssertTrue(licence.renewalDue)
        XCTAssertFalse(licence.isExpired)
        XCTAssertNil(licence.expiry)
        XCTAssertEqual(posts(fixture.server), 1)
    }

    // MARK: Removal

    /// With the network there, a removal deletes the package and then releases the key: deleted
    /// from the device, its proof of deletion `POST`ed — one more licence request, carrying exactly
    /// that proof — and the licence forgotten.
    func testRemovalDeletesThePackageThenReleasesTheKeyAtTheServer() async throws {
        let fixture = try await open()
        fixture.store.enqueue(request("film", fixture.content))
        try await completion(of: "film", in: fixture.store)
        let package = try XCTUnwrap(
            try FileManager.default.contentsOfDirectory(
                atPath: fixture.directory.appendingPathComponent("packages").path
            ).first)

        fixture.store.remove("film")

        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: fixture.directory.appendingPathComponent("packages/\(package)").path),
            "the package goes at once")
        XCTAssertNil(try fixture.licences.licence(for: "film"), "and the licence with it")
        try await harness.until("the release reaches the server", timeout: bound) {
            try fixture.licences.contentIdsAwaitingRelease().isEmpty
                && self.posts(fixture.server) == 2
        }
        let key = try XCTUnwrap(fixture.keySession.invalidatedKeys.first)
        XCTAssertEqual(fixture.keySession.invalidatedKeys.count, 1)
        XCTAssertEqual(
            fixture.server.networkRequests().last(where: { $0.method == "POST" })?.body,
            Array(DownloadKeySession.proofOfDeletion(of: key)))
        XCTAssertEqual(try fixture.licences.contentIds(), [])
    }

    /// With no network, the removal still deletes the package and the key from the device at once —
    /// the licence awaiting release and given to no player — and tells the server nothing until the
    /// network comes. The control is that it then does, so the rule is "deferred", not "dropped".
    func testWithNoNetworkTheReleaseIsDeferredUntilOneComes() async throws {
        let fixture = try await open()
        fixture.store.enqueue(request("film", fixture.content))
        try await completion(of: "film", in: fixture.store)
        fixture.network.holds = false

        fixture.store.remove("film")
        XCTAssertNil(try fixture.licences.licence(for: "film"), "given to no player at once")
        XCTAssertEqual(try fixture.licences.contentIdsAwaitingRelease(), ["film"])
        try await harness.until("the key is deleted from the device", timeout: bound) {
            fixture.keySession.invalidatedKeys.count == 1
        }
        // The pass that deleted it is the one that would have told the server; nothing else will
        // until the network comes, which the store is watching for.
        XCTAssertTrue(fixture.network.watching)
        XCTAssertEqual(posts(fixture.server), 1, "no release while there is no network")
        XCTAssertEqual(try fixture.licences.contentIdsAwaitingRelease(), ["film"])

        fixture.network.holds = true
        try await harness.until("the release reaches the server", timeout: bound) {
            try fixture.licences.contentIdsAwaitingRelease().isEmpty
        }
        XCTAssertEqual(posts(fixture.server), 2)
        XCTAssertEqual(fixture.keySession.invalidatedKeys.count, 1, "deleted from the device once")
        try await harness.until("nothing owed, nothing watched", timeout: bound) {
            !fixture.network.watching
        }
    }

    /// A release a store owed when it was let go is owed by the next store opened over the same
    /// licences: persisted, and released as that store opens.
    func testAReleaseOwedWhenTheStoreClosedIsMadeByTheNextOne() async throws {
        let fixture = try await open()
        fixture.store.enqueue(request("film", fixture.content))
        try await completion(of: "film", in: fixture.store)
        fixture.network.holds = false
        fixture.store.remove("film")
        try await harness.until("the key is deleted from the device", timeout: bound) {
            fixture.keySession.invalidatedKeys.count == 1
        }
        fixture.store.release()

        let next = try await open(
            directory: fixture.directory, licences: fixture.licences, server: fixture.server)
        try await harness.until("the next store releases it", timeout: bound) {
            try fixture.licences.contentIdsAwaitingRelease().isEmpty
        }
        XCTAssertEqual(posts(fixture.server), 2)
        XCTAssertTrue(next.keySession.invalidatedKeys.isEmpty, "the proof recorded is the one sent")
    }

    /// A store that never meets protected content and owes nothing makes no key session and
    /// watches no network (ADR-0013 rule 15), though it was configured with protection.
    func testAStoreWithProtectionThatDownloadsClearContentMakesNoKeySessionAndWatchesNothing()
        async throws
    {
        let clear = try await harness.serve(.hls())
        let fixture = try await open()
        fixture.store.enqueue(request("clip", clear))
        try await completion(of: "clip", in: fixture.store)
        fixture.store.remove("clip")

        XCTAssertFalse(fixture.keySessionMade.value)
        XCTAssertFalse(fixture.network.everWatched)
        XCTAssertEqual(fixture.server.networkRequests().count, 0)
    }

    // MARK: Fixtures

    /// Two retries, a few milliseconds apart: the count is what is asserted, never a wait.
    private static let quickBudget = RetryBudget(
        maxRetries: 2, initialBackoffMs: 10, maxBackoffMs: 20)

    private struct Fixture {
        let store: Downloads
        let downloads: StandInDownloads
        let network: StandInNetwork
        let keySession: DownloadKeySession
        let keySessionMade: Flag
        let licences: OfflineLicenceStore
        let content: LoopbackOrigin
        let server: LoopbackOrigin
        let directory: URL
        let recorder: Recorder
    }

    /// A protected store over a licence server of its own, with resilience and a quick licence
    /// budget, downloading `content`.
    private func open(
        content: TestContent = .protectedHls(), faults: FaultScript = .none,
        licenceDuration: Duration? = nil, playbackDuration: Duration? = nil,
        directory: URL? = nil, licences: OfflineLicenceStore? = nil,
        server: LoopbackOrigin? = nil, in subdirectory: String = "downloads"
    ) async throws -> Fixture {
        let origin = try await harness.serve(content)
        var licenceServer = server
        if licenceServer == nil {
            licenceServer = try await harness.serve(.licenceServer(), faults: faults)
        }
        let server = licenceServer!
        let licences = try licences ?? openLicences(subdirectory)
        let keySession = DownloadKeySession()
        let made = Flag()
        var configuration = Downloads.Configuration(
            directory: directory ?? parent.appendingPathComponent(subdirectory),
            sessionIdentifier: "test.downloads.\(UUID().uuidString)")
        configuration.resilience = Resilience.standard()
        configuration.policy = LicenceBudget(licence: Self.quickBudget)
        configuration.protection = DownloadProtection(
            drm: Drm.fairPlay(
                FairPlayConfig(
                    certificateURL: server.url(path: TestContent.licenceCertificatePath),
                    licenceURL: server.sourceURL),
                keySession: {
                    made.set()
                    return keySession
                }),
            licences: licences, licenceDuration: licenceDuration ?? month,
            playbackDuration: playbackDuration ?? twoDays)
        var downloads: StandInDownloads?
        let network = StandInNetwork()
        let store = try Downloads(
            configuration: configuration,
            downloader: { context in
                let made = StandInDownloads(context: context)
                downloads = made
                return made
            },
            network: { _ in network })
        stores.append(store)
        let recorder = Recorder()
        store.addListener(recorder)
        return Fixture(
            store: store, downloads: downloads!, network: network, keySession: keySession,
            keySessionMade: made, licences: licences, content: origin, server: server,
            directory: configuration.directory, recorder: recorder)
    }

    private func openLicences(_ subdirectory: String) throws -> OfflineLicenceStore {
        let store = try OfflineLicences.store(
            directory: parent.appendingPathComponent("licences-\(subdirectory)"))
        licenceStores.append(store)
        return store
    }

    private func request(_ contentId: String, _ origin: LoopbackOrigin) -> MediaRequest {
        MediaRequest(contentId: contentId, sources: [origin.sourceURL])
    }

    private func completion(of contentId: String, in store: Downloads) async throws {
        try await harness.until("\(contentId) completes", timeout: bound) {
            let item = store.download(contentId)
            if item?.state == .failed { throw Failed(item: item!) }
            return item?.state == .completed
        }
    }

    private func posts(_ server: LoopbackOrigin) -> Int {
        server.networkRequests().filter { $0.method == "POST" }.count
    }
}

/// The key session a download store's protection is given, stood in at `FairPlayKeySession` for
/// `SuperPlayerDRMTests`' `StandInKeySession`'s reason — the persistable half of it alone, since
/// nothing is ever a recipient of a download's session. A persistable key is the licence behind
/// `persisted:`, and a proof of deletion the key behind `deleted:`, so a test can tell which bytes
/// travelled where.
@MainActor
final class DownloadKeySession: FairPlayKeySession {

    private var handler: (@Sendable (FairPlayKeyRequest) -> Void)?

    /// Every identifier a persistable key was asked for, in order.
    private(set) var persistableRequests: [String] = []

    /// Every persistable key the store asked the device to delete, in order.
    private(set) var invalidatedKeys: [Data] = []

    func start(_ handler: @escaping @Sendable (FairPlayKeyRequest) -> Void) {
        self.handler = handler
    }

    func addRecipient(_ asset: AVURLAsset) {}

    func invalidate() { handler = nil }

    func persistEveryKey() {}

    /// Delivers the persistable request at once, as a device answers
    /// `processContentKeyRequest(withIdentifier:)`.
    func requestPersistableKey(
        _ keyIdentifier: String, refused: @escaping @Sendable (any Error) -> Void
    ) {
        persistableRequests.append(keyIdentifier)
        handler?(
            FairPlayKeyRequest(
                keyIdentifier: keyIdentifier, isRenewal: false, isPersistable: true,
                makeServerPlaybackContext: { _, _ in Data("spc:\(keyIdentifier)".utf8) },
                respond: { _ in }, fail: { _ in },
                persistableKey: { licence in Data("persisted:".utf8) + licence }))
    }

    func invalidatePersistableKey(_ key: Data) async throws -> Data {
        invalidatedKeys.append(key)
        return Self.proofOfDeletion(of: key)
    }

    nonisolated static func proofOfDeletion(of key: Data) -> Data {
        Data("deleted:".utf8) + key
    }
}

/// The network the store downloads under, stated by the test.
@MainActor
final class StandInNetwork: NetworkWatching {

    private var changed: (@MainActor @Sendable (Bool) -> Void)?

    /// Whether the store is watching now, and whether it ever has.
    private(set) var watching = false
    private(set) var everWatched = false

    /// The requirement holds — there by default; a change is heard at once by a store watching.
    var holds = true {
        didSet { if holds != oldValue { changed?(holds) } }
    }

    func watch(_ changed: @escaping @MainActor @Sendable (Bool) -> Void) {
        self.changed = changed
        watching = true
        everWatched = true
        // As `NWPathMonitor` does: the current reading first, asynchronously.
        let holds = holds
        Task { @MainActor [weak self] in self?.changed?(holds) }
    }

    func cancel() {
        changed = nil
        watching = false
    }
}

/// The store's licence budget stated, everything else the profile's.
private struct LicenceBudget: PlaybackPolicy {
    let licence: RetryBudget

    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
        var decision = PlaybackProfile.videoOnDemand.policy.decide(conditions)
        decision.retry.licence = licence
        return decision
    }
}

/// What two origins had seen when the downloader was handed a plan.
private final class Snapshots: Sendable {
    private let state = OSAllocatedUnfairLock<[(posts: Int, media: Int)]>(initialState: [])
    var values: [(posts: Int, media: Int)] { state.withLock { $0 } }
    func record(posts: Int, media: Int) { state.withLock { $0.append((posts, media)) } }
}

private final class Flag: Sendable {
    private let state = OSAllocatedUnfairLock(initialState: false)
    var value: Bool { state.withLock { $0 } }
    func set() { state.withLock { $0 = true } }
}

@MainActor
private final class Recorder: DownloadsListener {
    var changes: [DownloadItem] = []
    func downloadChanged(_ item: DownloadItem) { changes.append(item) }
    func downloadRemoved(_ contentId: String) {}
}

private struct Failed: Error, CustomStringConvertible {
    let item: DownloadItem
    var description: String { "the download failed: \(item)" }
}
