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
import SuperPlayerOffline
import SuperPlayerResilience
import SuperPlayerTestkit
import XCTest

/// The download store through its public API, over the loopback origin — the Kotlin `DownloadsTest`,
/// `DownloadSelectionTest`, `DownloadLiveRefusalTest`, `DownloadRetryBudgetTest` and
/// `DownloadStorageFullTest`, each claim beside a control.
///
/// **Every test here runs the transfer through `StandInDownloads`**, the stand-in at the store's one
/// `package` seam, because no `AVAssetDownloadTask` runs in the simulator test host
/// (`AssetDownloading`'s doc comment). The manifest read, the live refusal, the choice of rendition
/// and languages, the constraints, the index, the retry and its budget, the naming of a failure and
/// what a player opens are the shipped store's, against a real `AVURLAsset` reading the origin. A
/// real task, a real `.movpkg` and a download surviving its process are #436's, on a device.
@MainActor
final class DownloadsTests: XCTestCase {

    private var harness: PlaybackHarness!
    private var parent: URL!
    private var directory: URL!
    private var stores: [Downloads] = []

    override func setUp() async throws {
        harness = PlaybackHarness()
        parent = FileManager.default.temporaryDirectory
            .appendingPathComponent("downloads-\(UUID().uuidString)")
        directory = parent.appendingPathComponent("downloads")
    }

    override func tearDown() async throws {
        for store in stores { store.release() }
        stores = []
        harness.stop()
        try? FileManager.default.removeItem(at: parent)
    }

    // MARK: The tracer bullet

    /// The Kotlin `DownloadsTest`'s centre: enqueued, completed into the directory, and played by a
    /// player configured with the store with **no** request to the origin — the control being the
    /// same request on a player without it, which fetches.
    func testAVideoOnDemandDownloadCompletesIntoTheDirectoryAndPlaysWithNoRequest() async throws {
        let origin = try await harness.serve(.hls())
        let (store, _) = try open()
        let request = MediaRequest(contentId: "film", sources: [origin.sourceURL], title: "Film")
        store.enqueue(request)
        try await completion(of: "film", in: store)

        XCTAssertEqual(store.download("film")?.percentDownloaded, 100)
        XCTAssertGreaterThan(store.download("film")?.bytesDownloaded ?? 0, 0)
        XCTAssertEqual(
            try FileManager.default.contentsOfDirectory(atPath: parent.path), ["downloads"],
            "nothing lands outside the directory the consumer named")
        let written = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        XCTAssertTrue(written.contains("packages"))
        XCTAssertTrue(
            written.allSatisfy { $0 == "packages" || $0.hasPrefix("downloads.db") },
            "the directory holds the packages and the store's index: \(written)")

        let fetched = origin.networkRequests().count
        var configuration = SuperPlayer.Configuration()
        configuration.offlineContent = store
        let player = SuperPlayer(configuration: configuration)
        defer { player.release() }
        player.setMediaRequest(request)
        player.avPlayer.play()
        try await harness.until("the downloaded content plays", timeout: .seconds(30)) {
            player.avPlayer.currentTime().seconds > 1
        }
        XCTAssertEqual(origin.networkRequests().count, fetched, "a downloaded request asks nobody")

        let online = SuperPlayer(configuration: SuperPlayer.Configuration())
        defer { online.release() }
        online.setMediaRequest(request)
        try await harness.until("a player without the store fetches", timeout: .seconds(30)) {
            origin.networkRequests().count > fetched
        }
    }

    /// `assertProgressReportedInOrder`'s port: queued, then downloading with each whole percent it
    /// passes — never backwards and never twice — then completed at 100, and a removal heard last.
    func testTheListenerHearsEachStateAndWholePercentInOrder() async throws {
        let origin = try await harness.serve(.hls())
        let (store, _) = try open()
        let recorder = Recorder()
        store.addListener(recorder)
        store.enqueue(MediaRequest(contentId: "film", sources: [origin.sourceURL]))
        try await completion(of: "film", in: store)
        store.remove("film")

        let states = recorder.changes.map(\.state)
        XCTAssertEqual(states.first, .queued)
        XCTAssertEqual(Array(states.suffix(2)), [.completed, .removing])
        XCTAssertEqual(
            Set(states.dropFirst().dropLast(2)), [.downloading],
            "between queued and completed, only downloading: \(recorder.changes)")
        let percents = recorder.changes.filter { $0.state == .downloading }
            .compactMap(\.percentDownloaded).map(Int.init)
        XCTAssertEqual(
            percents, [25, 50, 75, 100], "one report per whole percent passed, in order")
        let bytes = recorder.changes.filter { $0.state == .downloading }.map(\.bytesDownloaded)
        XCTAssertEqual(bytes, bytes.sorted())
        XCTAssertEqual(recorder.removed, ["film"])
    }

    // MARK: Selection

    /// The rendition is the highest under ``PlaybackDecision/download``'s ceiling, counted per
    /// rendition at the origin; the control is the same content with no ceiling, which takes the
    /// other one — so the rule is neither "always the lowest" nor "always the highest".
    func testTheRenditionIsTheHighestUnderTheDecisionsCeiling() async throws {
        let origin = try await harness.serve(.hlsWithChoice(audioLanguages: ["en"]))
        let (capped, _) = try open(
            policy: Decided(PlaybackDecision(download: .init(maxVideoBitrateBps: 200_000))),
            in: "capped")
        capped.enqueue(MediaRequest(contentId: "capped", sources: [origin.sourceURL]))
        try await completion(of: "capped", in: capped)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceLowDirectory), 4)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceHighDirectory), 0)

        let (unlimited, _) = try open(in: "unlimited")
        unlimited.enqueue(MediaRequest(contentId: "unlimited", sources: [origin.sourceURL]))
        try await completion(of: "unlimited", in: unlimited)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceHighDirectory), 4)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceLowDirectory), 4)
    }

    /// Each declared language the content carries is downloaded and one it lacks is skipped, never
    /// refused — counted per rendition at the origin, with the languages not asked for fetching
    /// nothing.
    func testDeclaredLanguagesTheContentCarriesAreTakenAndOneItLacksIsSkipped() async throws {
        let origin = try await harness.serve(.hlsWithChoice(audioLanguages: ["en", "fr", "de"]))
        let (store, _) = try open()
        store.enqueue(
            MediaRequest(contentId: "film", sources: [origin.sourceURL]),
            audioLanguages: ["fr-FR", "xx", "de"])
        try await completion(of: "film", in: store)

        XCTAssertEqual(segments(at: origin, under: TestContent.choiceAudioDirectory("fr")), 4)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceAudioDirectory("de")), 4)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceAudioDirectory("en")), 0)
    }

    /// With no declared language carried, the audio a player would choose — the group's default —
    /// is taken, and only it: the Kotlin default-audio pass, never every language.
    func testWithNoDeclaredLanguageCarriedTheDefaultAudioIsTaken() async throws {
        let origin = try await harness.serve(.hlsWithChoice(audioLanguages: ["en", "fr"]))
        let (store, _) = try open()
        store.enqueue(
            MediaRequest(contentId: "film", sources: [origin.sourceURL]), audioLanguages: ["xx"])
        try await completion(of: "film", in: store)

        XCTAssertEqual(segments(at: origin, under: TestContent.choiceAudioDirectory("en")), 4)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceAudioDirectory("fr")), 0)
    }

    /// Subtitles are the declared languages the content carries and nothing else: one asked for is
    /// fetched, one the content lacks skipped, and — the control — a download that asks for none
    /// fetches none, since the Kotlin rule has no default subtitle as it has a default audio.
    func testSubtitlesAreTheDeclaredLanguagesCarriedAndNoneByDefault() async throws {
        let origin = try await harness.serve(
            .hlsWithChoice(audioLanguages: ["en"], subtitleLanguages: ["en", "de"]))
        let (store, _) = try open()
        store.enqueue(
            MediaRequest(contentId: "asked", sources: [origin.sourceURL]),
            subtitleLanguages: ["de", "xx"])
        try await completion(of: "asked", in: store)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceSubtitlesDirectory("de")), 4)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceSubtitlesDirectory("en")), 0)

        store.enqueue(MediaRequest(contentId: "unasked", sources: [origin.sourceURL]))
        try await completion(of: "unasked", in: store)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceSubtitlesDirectory("de")), 4)
        XCTAssertEqual(segments(at: origin, under: TestContent.choiceSubtitlesDirectory("en")), 0)
    }

    // MARK: Refusal

    /// The Kotlin `DownloadLiveRefusalTest`: live content is refused at enqueue — failed, with a
    /// refusal and no failure, no task started and no segment fetched — while the control, on-demand
    /// content enqueued beside it, completes.
    func testLiveContentIsRefusedAtEnqueueWithNoTaskAndNoSegment() async throws {
        let live = try await harness.serve(.liveHls())
        let vod = try await harness.serve(.hls())
        let (store, downloads) = try open(resilience: Resilience.standard())
        store.enqueue(MediaRequest(contentId: "live", sources: [live.sourceURL]))
        store.enqueue(MediaRequest(contentId: "film", sources: [vod.sourceURL]))
        try await harness.until("the live item ends", timeout: .seconds(30)) {
            store.download("live")?.state == .failed
        }
        try await completion(of: "film", in: store)

        XCTAssertEqual(store.download("live")?.refusal, .liveContent)
        XCTAssertNil(store.download("live")?.failure, "a refusal is not a failure")
        XCTAssertEqual(downloads.started.map(\.contentId), ["film"])
        XCTAssertFalse(live.networkRequests().contains { $0.kind == .mediaSegment })
    }

    // MARK: Retry and failure

    /// The Kotlin `DownloadRetryBudgetTest` at task granularity: a task a 5xx fails is started again
    /// on ``RetryPolicy/segment``, staying downloading, and fails named once the budget is spent —
    /// one start and two retries, counted.
    func testATaskA5xxFailsIsStartedAgainOnTheSegmentBudgetThenFailsNamed() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder().failWithHttpStatus(500, kind: .mediaSegment, index: 2)
                .build())
        let (store, downloads) = try open(
            policy: Decided(PlaybackDecision(retry: RetryPolicy(segment: Self.quickBudget))),
            resilience: Resilience.standard())
        let recorder = Recorder()
        store.addListener(recorder)
        store.enqueue(MediaRequest(contentId: "film", sources: [origin.sourceURL]))
        try await harness.until("the item fails", timeout: .seconds(30)) {
            store.download("film")?.state == .failed
        }

        XCTAssertEqual(downloads.started.count, 1 + Self.quickBudget.maxRetries)
        XCTAssertEqual(store.download("film")?.failure?.causeClass, "Transient.Network")
        XCTAssertEqual(
            recorder.changes.map(\.state).filter { $0 != .downloading }, [.queued, .failed],
            "a retry is not reported as a failure")
    }

    /// The control: a 5xx that relents after the first attempt is one re-enqueue, and completes.
    func testATaskThatFailsOnceAndRelentsCompletesAfterOneRetry() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(500, kind: .mediaSegment, index: 2, firstAttempts: 1).build())
        let (store, downloads) = try open(
            policy: Decided(PlaybackDecision(retry: RetryPolicy(segment: Self.quickBudget))),
            resilience: Resilience.standard())
        store.enqueue(MediaRequest(contentId: "film", sources: [origin.sourceURL]))
        try await completion(of: "film", in: store)
        XCTAssertEqual(downloads.started.count, 2)
    }

    /// The Kotlin `DownloadStorageFullTest`: a full disk fails the one item that met it, at once and
    /// named `Storage.Full` — no retry, since asking again cannot help — while the item beside it
    /// completes.
    func testAFullDiskFailsThatItemAtOnceAndTheOtherCompletes() async throws {
        let origin = try await harness.serve(.hls())
        let (store, downloads) = try open(
            policy: Decided(PlaybackDecision(retry: RetryPolicy(segment: Self.quickBudget))),
            resilience: Resilience.standard())
        downloads.failWithDiskFull("full")
        store.enqueue(MediaRequest(contentId: "full", sources: [origin.sourceURL]))
        store.enqueue(MediaRequest(contentId: "film", sources: [origin.sourceURL]))
        try await completion(of: "film", in: store)
        try await harness.until("the full item fails", timeout: .seconds(30)) {
            store.download("full")?.state == .failed
        }

        XCTAssertEqual(store.download("full")?.failure?.causeClass, "Storage.Full")
        XCTAssertEqual(downloads.started.filter { $0.contentId == "full" }.count, 1)
    }

    /// Without a resilience nothing is named: the same 5xx spends the budget and ends unnamed, as a
    /// Kotlin store without `setResilience` ends.
    func testWithoutAResilienceAFailureSpendsTheBudgetAndIsUnnamed() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder().failWithHttpStatus(500, kind: .mediaSegment, index: 2)
                .build())
        let (store, downloads) = try open(
            policy: Decided(PlaybackDecision(retry: RetryPolicy(segment: Self.quickBudget))))
        store.enqueue(MediaRequest(contentId: "film", sources: [origin.sourceURL]))
        try await harness.until("the item fails", timeout: .seconds(30)) {
            store.download("film")?.state == .failed
        }
        XCTAssertNil(store.download("film")?.failure)
        XCTAssertEqual(downloads.started.count, 1 + Self.quickBudget.maxRetries)
    }

    // MARK: The index and the session

    /// A store opened again on the same directory reads what the last one wrote: a completed download
    /// plays from its package, an unfinished one the session still holds is left to it, and one it
    /// no longer holds is started again from its plan — the re-attachment the platform's promise of
    /// survival rests on, whose other half is #436's.
    func testAReopenedStoreReadsItsIndexAndStartsAgainOnlyWhatTheSessionLost() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder().addLatencyMs(60_000, kind: .mediaSegment, index: 3)
                .build())
        let (first, _) = try open(policy: Decided(PlaybackDecision()))
        first.enqueue(MediaRequest(contentId: "held", sources: [origin.sourceURL]))
        first.enqueue(MediaRequest(contentId: "lost", sources: [origin.sourceURL]))
        try await harness.until("both are downloading", timeout: .seconds(30)) {
            first.downloads().allSatisfy { $0.state == .downloading }
                && first.downloads().count == 2
        }
        first.release()

        let (second, downloads) = try open(heldBySession: ["held"])
        XCTAssertEqual(second.downloads().map(\.contentId), ["held", "lost"])
        XCTAssertEqual(second.downloads().map(\.state), [.downloading, .downloading])
        try await harness.until("the lost one is started again", timeout: .seconds(10)) {
            !downloads.started.isEmpty
        }
        XCTAssertEqual(downloads.started.map(\.contentId), ["lost"])
    }

    /// The index's order is the order of enqueue whichever manifest read finishes first — #425's
    /// race, made to happen rather than waited for (#507): the first item's multivariant playlist is
    /// held at its own origin, so the second item's plan reaches the index before the first's does,
    /// and the next store still reads them in the order they were enqueued. A store that took an
    /// item's ordinal as its plan was written reads them the other way round, every run.
    func testTheIndexKeepsTheOrderOfEnqueueWhenTheFirstManifestReadFinishesLast() async throws {
        // Each origin holds its fourth segment, so both items stay downloading until the release.
        let holdingASegment = FaultScript.Builder()
            .addLatencyMs(60_000, kind: .mediaSegment, index: 3)
        // Five seconds of real time, against the milliseconds a loopback manifest read takes: long
        // enough that the second read and its plan are written first on a loaded host too. A host
        // slower than that weakens the test to the order that would pass anyway, never fails it.
        let slow = try await harness.serve(
            .hls(),
            faults: holdingASegment.addLatencyMs(5_000, kind: .manifest, index: 0).build())
        let quick = try await harness.serve(.hls(), faults: holdingASegment.build())
        let (first, _) = try open(policy: Decided(PlaybackDecision()))
        first.enqueue(MediaRequest(contentId: "slow", sources: [slow.sourceURL]))
        first.enqueue(MediaRequest(contentId: "quick", sources: [quick.sourceURL]))
        try await harness.until("both are downloading", timeout: .seconds(30)) {
            first.downloads().count == 2
                && first.downloads().allSatisfy { $0.state == .downloading }
        }
        first.release()

        let (second, _) = try open(heldBySession: ["slow", "quick"])
        XCTAssertEqual(second.downloads().map(\.contentId), ["slow", "quick"])
    }

    /// An item keeps the place it was **first** enqueued in, in the next store as in this one: one
    /// whose manifest could not be read, so that nothing of it reached the index, and that is
    /// enqueued again after another, is first in both. A store that took a new place at every
    /// enqueue answered first-enqueued order while it was open and the reverse once reopened, every
    /// run.
    func testAnItemEnqueuedAgainAfterAFailedReadKeepsItsFirstPlaceInTheNextStore() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder().addLatencyMs(60_000, kind: .mediaSegment, index: 3)
                .build())
        let (first, _) = try open(policy: Decided(PlaybackDecision()))
        first.enqueue(
            MediaRequest(contentId: "retried", sources: [origin.url(path: "/absent.m3u8")]))
        try await harness.until("the unreadable one fails", timeout: .seconds(30)) {
            first.download("retried")?.state == .failed
        }
        first.enqueue(MediaRequest(contentId: "other", sources: [origin.sourceURL]))
        first.enqueue(MediaRequest(contentId: "retried", sources: [origin.sourceURL]))
        try await harness.until("both are downloading", timeout: .seconds(30)) {
            first.downloads().count == 2
                && first.downloads().allSatisfy { $0.state == .downloading }
        }
        XCTAssertEqual(first.downloads().map(\.contentId), ["retried", "other"])
        first.release()

        let (second, _) = try open(heldBySession: ["retried", "other"])
        XCTAssertEqual(second.downloads().map(\.contentId), ["retried", "other"])
    }

    /// The other half of reading the index: a completed download is completed in the next store, and
    /// plays from its package there with no request.
    func testACompletedDownloadSurvivesTheStoreAndPlaysFromTheNextOne() async throws {
        let origin = try await harness.serve(.hls())
        let (first, _) = try open()
        let request = MediaRequest(contentId: "film", sources: [origin.sourceURL])
        first.enqueue(request)
        try await completion(of: "film", in: first)
        first.release()
        let fetched = origin.networkRequests().count

        let (second, _) = try open()
        XCTAssertEqual(second.download("film")?.state, .completed)
        var configuration = SuperPlayer.Configuration()
        configuration.offlineContent = second
        let player = SuperPlayer(configuration: configuration)
        defer { player.release() }
        player.setMediaRequest(request)
        player.avPlayer.play()
        try await harness.until("it plays", timeout: .seconds(30)) {
            player.avPlayer.currentTime().seconds > 1
        }
        XCTAssertEqual(origin.networkRequests().count, fetched)
    }

    /// A download still in progress plays from its task's own asset — the one the downloader holds
    /// for it — and a removed one from its sources again.
    func testAnInProgressDownloadPlaysFromItsTasksAsset() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder().addLatencyMs(60_000, kind: .mediaSegment, index: 3)
                .build())
        let (store, downloads) = try open()
        let request = MediaRequest(contentId: "film", sources: [origin.sourceURL])
        store.enqueue(request)
        try await harness.until("it is downloading", timeout: .seconds(30)) {
            store.download("film")?.state == .downloading
        }
        var configuration = SuperPlayer.Configuration()
        configuration.offlineContent = store
        let player = SuperPlayer(configuration: configuration)
        defer { player.release() }
        player.setMediaRequest(request)
        XCTAssertTrue(player.avPlayer.currentItem?.asset === downloads.inProgressAsset(of: "film"))

        store.remove("film")
        player.setMediaRequest(request)
        XCTAssertFalse(player.avPlayer.currentItem?.asset === downloads.inProgressAsset(of: "film"))
        XCTAssertEqual((player.avPlayer.currentItem?.asset as? AVURLAsset)?.url, origin.sourceURL)
    }

    /// Removal deletes the package and forgets the download, in the index too: a player plays the
    /// request from its sources again, and a reopened store holds nothing.
    func testRemoveDeletesThePackageAndForgetsTheDownload() async throws {
        let origin = try await harness.serve(.hls())
        let (store, _) = try open()
        store.enqueue(MediaRequest(contentId: "film", sources: [origin.sourceURL]))
        try await completion(of: "film", in: store)
        let packages = directory.appendingPathComponent("packages")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: packages.path).count, 1)

        store.remove("film")
        XCTAssertNil(store.download("film"))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: packages.path), [])
        store.release()
        let (reopened, _) = try open()
        XCTAssertEqual(reopened.downloads(), [])
    }

    /// The session's constraints (ADR-0013 rules 10 and 11 read for iOS): unmetered, Low Data Mode
    /// honoured and discretionary by default; metered networks allowed relaxes the network alone.
    func testTheSessionIsUnmeteredByDefaultAndMeteredNetworksRelaxOnlyTheNetwork() throws {
        let (store, downloads) = try open()
        XCTAssertEqual(downloads.context.sessionIdentifier, store.configuration.sessionIdentifier)
        XCTAssertFalse(downloads.context.constraints.allowsExpensiveNetworkAccess)
        XCTAssertFalse(downloads.context.constraints.allowsCellularAccess)
        XCTAssertFalse(downloads.context.constraints.allowsConstrainedNetworkAccess)
        XCTAssertTrue(downloads.context.constraints.isDiscretionary)
        XCTAssertEqual(
            downloads.context.packagesDirectory, directory.appendingPathComponent("packages"))

        let (_, metered) = try open(meteredNetworksAllowed: true, in: "metered")
        XCTAssertTrue(metered.context.constraints.allowsExpensiveNetworkAccess)
        XCTAssertTrue(metered.context.constraints.allowsCellularAccess)
        XCTAssertFalse(metered.context.constraints.allowsConstrainedNetworkAccess)
        XCTAssertTrue(metered.context.constraints.isDiscretionary)
    }

    /// The app's hand-off: another session's identifier is refused, and this one's completion handler
    /// is held until the session has delivered its events, then called.
    func testTheBackgroundHandOffIsHeldUntilTheSessionsEventsAreDelivered() async throws {
        let (store, downloads) = try open()
        let called = Called()
        XCTAssertFalse(
            store.handleEventsForBackgroundURLSession("another") { called.mark() })
        XCTAssertTrue(
            store.handleEventsForBackgroundURLSession(store.configuration.sessionIdentifier) {
                called.mark()
            })
        XCTAssertFalse(called.value)
        downloads.finishBackgroundEvents()
        try await harness.until("the completion handler runs", timeout: .seconds(5)) {
            called.value
        }
    }

    /// The directory is refused where it is a file, and a store closed lets its session go.
    func testAFileNamedAsTheDirectoryIsRefusedAndReleaseLetsTheSessionGo() throws {
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: directory.path, contents: Data())
        XCTAssertThrowsError(try open()) { error in
            XCTAssertEqual(error as? DownloadsError, .notADirectory(self.directory))
        }
        try FileManager.default.removeItem(at: directory)
        let (store, downloads) = try open()
        store.release()
        XCTAssertTrue(downloads.released)
    }

    // MARK: Helpers

    /// Two retries — three attempts — a few milliseconds apart: the budget's count is what is
    /// asserted, never a wait.
    private static let quickBudget = RetryBudget(
        maxRetries: 2, initialBackoffMs: 10, maxBackoffMs: 20)

    private func open(
        policy: (any PlaybackPolicy)? = nil, resilience: (any PlaybackResilience)? = nil,
        meteredNetworksAllowed: Bool = false, heldBySession: Set<String> = [],
        in subdirectory: String? = nil
    ) throws -> (Downloads, StandInDownloads) {
        let storeDirectory = subdirectory.map { parent.appendingPathComponent($0) } ?? directory!
        var configuration = Downloads.Configuration(
            directory: storeDirectory, sessionIdentifier: "test.downloads.\(UUID().uuidString)")
        configuration.policy = policy
        configuration.resilience = resilience
        configuration.meteredNetworksAllowed = meteredNetworksAllowed
        var made: StandInDownloads?
        let store = try Downloads(configuration: configuration) { context in
            let downloads = StandInDownloads(context: context, heldBySession: heldBySession)
            made = downloads
            return downloads
        }
        stores.append(store)
        return (store, made!)
    }

    private func completion(of contentId: String, in store: Downloads) async throws {
        try await harness.until("\(contentId) completes", timeout: .seconds(30)) {
            let state = store.download(contentId)?.state
            if state == .failed { throw Failed(item: store.download(contentId)!) }
            return state == .completed
        }
    }

    private func segments(at origin: LoopbackOrigin, under directory: String) -> Int {
        origin.networkRequests().filter {
            $0.kind == .mediaSegment
                && $0.path.hasPrefix("/" + TestContent.choiceDirectory + directory)
        }.count
    }
}

/// A policy whose decision is the one it was handed.
private struct Decided: PlaybackPolicy {
    let decision: PlaybackDecision
    init(_ decision: PlaybackDecision) { self.decision = decision }
    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision { decision }
}

@MainActor
private final class Recorder: DownloadsListener {
    var changes: [DownloadItem] = []
    var removed: [String] = []
    func downloadChanged(_ item: DownloadItem) { changes.append(item) }
    func downloadRemoved(_ contentId: String) { removed.append(contentId) }
}

private final class Called: @unchecked Sendable {
    private let lock = NSLock()
    private var called = false
    var value: Bool { lock.withLock { called } }
    func mark() { lock.withLock { called = true } }
}

private struct Failed: Error, CustomStringConvertible {
    let item: DownloadItem
    var description: String { "the download failed: \(item)" }
}
