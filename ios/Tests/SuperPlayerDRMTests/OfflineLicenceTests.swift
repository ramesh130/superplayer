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

import Combine
import SuperPlayerCore
import SuperPlayerDRM
import SuperPlayerResilience
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest

/// The offline licence lifecycle (#423) — acquire, play offline, renew, release, and the expiry
/// split that makes a dead download say something actionable — the Kotlin `OfflineLicenceTest`, over
/// FairPlay's persistable keys.
///
/// Driven through the public API a consumer writes: `OfflineLicences.store(directory:)`,
/// `store.over(player)` for the three verbs that talk to the licence server, and
/// `Drm.fairPlay(_:licence:)` for the fourth, which is playing. The store's directory is a fresh
/// temporary one per test, inside a parent of its own, because a store SuperPlayer chose the place of
/// is what ADR-0012 rule 8 forbids — and the parent is how "nothing outside it" is read.
///
/// **The key session is the stand-in** (``StandInKeySession``), for ``LicenceExchangeTests``' reason:
/// the simulator has no FairPlay. It turns a licence into a "persistable key" by prefixing it and a
/// key into a "proof of deletion" the same way, so a test can tell which bytes travelled where.
/// Everything else is the shipped code: the store, its SQLite file, the exchange, the budget and the
/// classifier, against the harness's licence server.
///
/// **Two claims are counted at the server rather than inferred**, as the Kotlin ones are: offline
/// playback asks for **nothing** — on a licence server of its own that no other player shares, so
/// an empty record is the whole claim — and a release is a request the server received.
@MainActor
final class OfflineLicenceTests: XCTestCase {

    private let bound: Duration = .seconds(30)

    private let key = "skd://elsewhere.example/key-1"
    private let contentId = "film/arrival"

    private let month: Duration = .seconds(30 * 24 * 3_600)
    private let twoDays: Duration = .seconds(2 * 24 * 3_600)

    private let harness = PlaybackHarness()
    private var players: [SuperPlayer] = []
    private var stores: [OfflineLicenceStore] = []
    private var subscriptions: Set<AnyCancellable> = []
    private var parent: URL!

    /// The directory the consumer named, which SuperPlayer never chose and never leaves.
    private var directory: URL { parent.appendingPathComponent("licences", isDirectory: true) }

    override func setUp() async throws {
        try await super.setUp()
        parent = FileManager.default.temporaryDirectory
            .appendingPathComponent("OfflineLicenceTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        subscriptions = []
        for player in players { player.release() }
        players = []
        for store in stores { store.close() }
        stores = []
        harness.stop()
        try? FileManager.default.removeItem(at: parent)
        try await super.tearDown()
    }

    // MARK: Acquire

    func testAcquiringStoresBothDurationsAndWritesNothingOutsideTheDirectory() async throws {
        let store = try open()
        let online = try await playOnline()
        let postsBefore = posts(online.server)

        let acquired = try await store.over(online.player).acquire(
            contentId, licenceDuration: month, playbackDuration: twoDays)

        // A second exchange with the server, persistable, and not a copy of the streaming key —
        // which cannot be stored. Counted, because from the store alone the two look identical.
        XCTAssertEqual(online.keySession.persistableRequests.map(\.keyIdentifier), [key])
        XCTAssertEqual(posts(online.server), postsBefore + 1)
        // Both durations, kept apart (ADR-0012 rule 9).
        XCTAssertEqual(acquired.contentId, contentId)
        XCTAssertGreaterThan(
            acquired.licenceDurationRemainingMs, acquired.playbackDurationRemainingMs)
        XCTAssertGreaterThan(acquired.playbackDurationRemainingMs, 0)
        XCTAssertFalse(acquired.isExpired)
        XCTAssertFalse(acquired.renewalDue)
        XCTAssertNotNil(try store.licence(for: contentId))
        XCTAssertEqual(try store.contentIds(), [contentId])
        // Core's reading of the same licence, which a download store will make, agrees with the
        // store's own: one place decides when a licence is dead or due.
        let standing = try XCTUnwrap(try (store as any StoredLicences).standing(of: contentId))
        XCTAssertFalse(standing.isExpired)
        XCTAssertFalse(standing.renewalDue)
        XCTAssertNil(try (store as any StoredLicences).standing(of: "something/else"))

        // ADR-0012 rule 8: the directory is the only thing in its parent, and everything in the
        // directory is the store's one file and SQLite's companions of it.
        store.close()
        XCTAssertEqual(
            try FileManager.default.contentsOfDirectory(atPath: parent.path), ["licences"])
        let written = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        XCTAssertFalse(written.isEmpty)
        XCTAssertTrue(written.allSatisfy { $0.hasPrefix("offline-licences.db") }, "\(written)")
        XCTAssertTrue(online.errors.errors.isEmpty, "\(online.errors.errors)")
    }

    /// Content keyed apart — two keys — stores both under one licence, and the control on the
    /// precondition: content that has asked for no key yet has nothing to acquire.
    func testEveryKeyTheContentAskedForIsAcquiredAndNoneBeforeItAsks() async throws {
        let store = try open()
        let unloaded = try await playOnline(askFor: [])

        do {
            _ = try await store.over(unloaded.player).acquire(
                contentId, licenceDuration: month, playbackDuration: twoDays)
            XCTFail("acquired with no key requested")
        } catch let error as OfflineLicenceStoreError {
            XCTAssertEqual(error, .noKeyRequested)
        }
        XCTAssertEqual(posts(unloaded.server), 0)

        let second = "skd://elsewhere.example/key-2"
        let online = try await playOnline(askFor: [key, second])
        _ = try await store.over(online.player).acquire(
            contentId, licenceDuration: month, playbackDuration: twoDays)

        XCTAssertEqual(online.keySession.persistableRequests.map(\.keyIdentifier), [key, second])
        XCTAssertEqual(try store.contentIds(), [contentId])
    }

    func testARefusedAcquisitionStoresNothingAndEndsNoPlayback() async throws {
        let store = try open()
        // The streaming POST is answered, and every one after it refused, with no retries left.
        let online = try await playOnline(
            faults: FaultScript.Builder()
                .failWithHttpStatus(500, kind: .licence, index: 1, afterAttempts: 1).build(),
            budget: RetryBudget(maxRetries: 0, initialBackoffMs: 2, maxBackoffMs: 8))

        do {
            _ = try await store.over(online.player).acquire(
                contentId, licenceDuration: month, playbackDuration: twoDays)
            XCTFail("acquired against a refusing server")
        } catch let error as LicenceExchangeError {
            XCTAssertEqual(error.stage, .licence)
            XCTAssertEqual(error.status, 500)
        }

        XCTAssertEqual(try store.contentIds(), [])
        // The store's exchange is not playback: nothing was offered to the ladder.
        XCTAssertTrue(online.errors.errors.isEmpty, "\(online.errors.errors)")
    }

    // MARK: Read

    func testAStoredLicenceIsReadableByAFreshStoreWithNoPlayerAndNoNetwork() async throws {
        let acquiring = try open()
        let online = try await playOnline()
        _ = try await acquiring.over(online.player).acquire(
            contentId, licenceDuration: month, playbackDuration: twoDays)
        acquiring.close()
        online.player.release()
        harness.stop()

        let reopened = try open()

        let licence = try XCTUnwrap(try reopened.licence(for: contentId))
        XCTAssertGreaterThan(licence.licenceDurationRemainingMs, 0)
        XCTAssertFalse(licence.isExpired)
        XCTAssertNil(try reopened.licence(for: "something/else"))
        // The keys are never on the reading a consumer sees.
        XCTAssertFalse(String(describing: licence).contains("persisted"))
    }

    func testANameThatIsAFileIsRefused() throws {
        let file = parent.appendingPathComponent("not-a-directory")
        XCTAssertTrue(FileManager.default.createFile(atPath: file.path, contents: Data()))

        XCTAssertThrowsError(try OfflineLicences.store(directory: file)) { error in
            XCTAssertEqual(error as? OfflineLicenceStoreError, .notADirectory(file))
        }
    }

    /// The control on every assertion above: a player with no store acquires online exactly as
    /// before, and nothing is written anywhere — a store is opened by a consumer or it does not exist.
    func testAPlayerWithNoStoreWritesNothing() async throws {
        let online = try await playOnline()

        XCTAssertEqual(posts(online.server), 1)
        XCTAssertFalse(online.keySession.persistsEveryKey)
        XCTAssertTrue(online.keySession.persistableRequests.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }

    // MARK: Play offline

    func testProtectedContentPlaysFromTheStoreAndAsksForNoLicence() async throws {
        let store = try open()
        let online = try await playOnline()
        let licence = try await store.over(online.player).acquire(
            contentId, licenceDuration: month, playbackDuration: twoDays)
        let storedKey = try XCTUnwrap(
            online.keySession.persistableRequests.first?.record.answers.first)

        let sink = RecordingSink()
        let offline = try await playOffline(licence, sink: sink)
        let record = offline.keySession.request(key)
        try await answered(record)

        // The key the device kept, handed back — and the request was persistable, which is the only
        // kind a stored key answers.
        XCTAssertTrue(offline.keySession.persistsEveryKey)
        XCTAssertEqual(record.answers, [storedKey])
        // The assertion that matters: this player's licence server heard nothing, not even a
        // certificate fetch.
        XCTAssertEqual(offline.server.networkRequests().count, 0)
        XCTAssertTrue(offline.errors.errors.isEmpty, "\(offline.errors.errors)")
        // Measured as what it was, the outcome #212 declared and the store makes reachable.
        try await harness.until("the acquisition is measured", timeout: bound) {
            !sink.acquisitions.isEmpty
        }
        XCTAssertEqual(sink.acquisitions.map(\.outcome), [.servedFromOfflineStore])
    }

    /// A key the stored licence does not hold — a licence stored for other content — is refused as a
    /// licence that did not arrive, and still asks nobody.
    func testAKeyTheLicenceDoesNotHoldIsRefusedWithoutAsking() async throws {
        let store = try open()
        let online = try await playOnline()
        let licence = try await store.over(online.player).acquire(
            contentId, licenceDuration: month, playbackDuration: twoDays)

        let offline = try await playOffline(licence)
        let record = offline.keySession.request("skd://elsewhere.example/another-film")
        try await failed(offline.errors)

        guard case .failed = record.answers.first else { return XCTFail("\(record.answers)") }
        let typed = try XCTUnwrap(offline.errors.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Drm.LicenceAcquisition")
        let evidence = try XCTUnwrap(typed.underlyingError as Error as? LicenceExchangeError)
        XCTAssertEqual(evidence.attempts, 0)
        XCTAssertEqual(offline.server.networkRequests().count, 0)
    }

    // MARK: Expiry

    func testAnExpiredLicenceIsReadableFirstAndRefusedRatherThanReAcquired() async throws {
        let store = try open()
        let online = try await playOnline()
        let expired = try await store.over(online.player).acquire(
            contentId, licenceDuration: .zero, playbackDuration: .zero)

        // The *before*: readable with nothing built, so an app can say "go online and refresh".
        XCTAssertTrue(expired.isExpired)
        XCTAssertEqual(expired.playbackDurationRemainingMs, 0)
        XCTAssertEqual(expired.licenceDurationRemainingMs, 0)
        XCTAssertTrue(try XCTUnwrap(try store.licence(for: contentId)).isExpired)

        // The *after*, for an app that plays it anyway: refused and named, never re-acquired.
        let sink = RecordingSink()
        let offline = try await playOffline(expired, sink: sink)
        let record = offline.keySession.request(key)
        try await failed(offline.errors)

        guard case .failed = record.answers.first else { return XCTFail("\(record.answers)") }
        let typed = try XCTUnwrap(offline.errors.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Drm.LicenceExpired")
        XCTAssertEqual(typed.userMessageKey, FailureClass.licenceExpiredMessageKey)
        let evidence = try XCTUnwrap(
            typed.underlyingError as Error as? OfflineLicenceExpiredError)
        XCTAssertEqual(evidence.contentId, contentId)
        XCTAssertEqual(evidence.licenceDurationRemainingMs, 0)
        // Nothing was asked of the server, which is what "refused rather than re-acquired" means in
        // traffic, and nothing was measured, because no acquisition was attempted.
        XCTAssertEqual(offline.server.networkRequests().count, 0)
        XCTAssertTrue(sink.acquisitions.isEmpty)
    }

    /// The recovery an app takes after the reading above, on the player already in hand: its
    /// protection played nothing, but it holds the exchange, and that is all a renewal needs.
    func testADeadDownloadIsRenewableOnThePlayerThatRefusedIt() async throws {
        let store = try open()
        let online = try await playOnline()
        let expired = try await store.over(online.player).acquire(
            contentId, licenceDuration: .zero, playbackDuration: .zero)
        let refused = try await playOffline(expired)
        _ = refused.keySession.request(key)
        try await failed(refused.errors)

        let renewed = try await store.over(refused.player).renew(
            contentId, licenceDuration: month, playbackDuration: twoDays)

        XCTAssertFalse(renewed.isExpired)
        XCTAssertFalse(try XCTUnwrap(try store.licence(for: contentId)).isExpired)
        XCTAssertEqual(posts(refused.server), 1)
    }

    // MARK: Renew

    /// `PRD.md` §3.2's "renew before the remaining duration reaches zero", read as ADR-0012 rule 10
    /// requires: the store reports what is due on a stated threshold and the consumer renews. An hour
    /// is well inside the day.
    func testRenewingReplacesTheStoredLicenceBeforeItReachesZero() async throws {
        let store = try open()
        let online = try await playOnline()
        let nearlyExpired = try await store.over(online.player).acquire(
            contentId, licenceDuration: .seconds(3_600), playbackDuration: .seconds(3_600))
        XCTAssertTrue(nearlyExpired.renewalDue)
        XCTAssertFalse(nearlyExpired.isExpired)

        // On a player that has loaded nothing: a renewal is composed from the stored licence alone,
        // which is what makes it a background job's operation.
        let job = try await playOnline(askFor: [])
        let renewed = try await store.over(job.player).renew(
            contentId, licenceDuration: month, playbackDuration: twoDays)

        XCTAssertFalse(renewed.renewalDue)
        XCTAssertGreaterThan(
            renewed.licenceDurationRemainingMs, nearlyExpired.licenceDurationRemainingMs)
        // A round trip and not a local recalculation, and one licence rather than two.
        XCTAssertEqual(job.keySession.persistableRequests.map(\.keyIdentifier), [key])
        XCTAssertEqual(posts(job.server), 1)
        XCTAssertEqual(try store.contentIds(), [contentId])
        let renewedKey = try XCTUnwrap(
            job.keySession.persistableRequests.first?.record.answers.first)
        let offline = try await playOffline(try XCTUnwrap(try store.licence(for: contentId)))
        let record = offline.keySession.request(key)
        try await answered(record)
        XCTAssertEqual(record.answers, [renewedKey])
    }

    func testRenewingWhatIsNotHeldIsRefused() async throws {
        let store = try open()
        let online = try await playOnline()

        do {
            _ = try await store.over(online.player).renew(
                contentId, licenceDuration: month, playbackDuration: twoDays)
            XCTFail("renewed a licence nobody held")
        } catch let error as OfflineLicenceStoreError {
            XCTAssertEqual(error, .noLicenceHeld(contentId: contentId))
        }
        XCTAssertEqual(posts(online.server), 1)
    }

    // MARK: Release

    func testReleasingDeletesTheKeyTellsTheServerAndLeavesNothing() async throws {
        let store = try open()
        let online = try await playOnline()
        let exchange = try store.over(online.player)
        _ = try await exchange.acquire(contentId, licenceDuration: month, playbackDuration: twoDays)
        guard
            case .licence(let storedKey) = online.keySession.persistableRequests.first?.record
                .answers.first
        else { return XCTFail("no persistable key was made") }

        try await exchange.release(contentId)

        // The device deleted the key it kept, and the server received the device's proof of it —
        // the half an assertion against the store alone cannot see.
        XCTAssertEqual(online.keySession.invalidatedKeys, [storedKey])
        XCTAssertEqual(posts(online.server), 3)
        XCTAssertEqual(
            online.server.networkRequests().last?.body,
            Array(StandInKeySession.proofOfDeletion(of: storedKey)))
        XCTAssertNil(try store.licence(for: contentId))
        XCTAssertEqual(try store.contentIds(), [])
    }

    /// The control: a release the server refused propagates, and the licence waits for its release
    /// rather than being forgotten — a licence released only here still counts against the device —
    /// while it is no longer offered for playback, because its key is already gone from the device.
    /// Asked again, the store re-sends the proof it recorded and deletes nothing twice.
    func testAReleaseTheServerRefusedWaitsAndIsRetriedWithTheSameProof() async throws {
        let store = try open()
        // The streaming POST and the acquisition's are answered; every one after is refused.
        let online = try await playOnline(
            faults: FaultScript.Builder()
                .failWithHttpStatus(500, kind: .licence, index: 1, afterAttempts: 2).build(),
            budget: RetryBudget(maxRetries: 0, initialBackoffMs: 2, maxBackoffMs: 8))
        let exchange = try store.over(online.player)
        _ = try await exchange.acquire(contentId, licenceDuration: month, playbackDuration: twoDays)

        for _ in 0..<2 {
            do {
                try await exchange.release(contentId)
                XCTFail("released against a refusing server")
            } catch let error as LicenceExchangeError {
                XCTAssertEqual(error.status, 500)
            }
        }

        XCTAssertEqual(try store.contentIdsAwaitingRelease(), [contentId])
        XCTAssertNil(try store.licence(for: contentId))
        XCTAssertEqual(try store.contentIds(), [])
        XCTAssertEqual(online.keySession.invalidatedKeys.count, 1)
        let releases = online.server.networkRequests().filter { $0.method == "POST" }.dropFirst(2)
        XCTAssertEqual(releases.count, 2)
        XCTAssertEqual(Set(releases.map(\.body)).count, 1)
    }

    /// A device that will not make a request persistable refuses the acquisition at once, typed,
    /// rather than leaving it waiting for an answer no request will bring.
    func testADeviceThatWillNotPersistRefusesTheAcquisition() async throws {
        let store = try open()
        let online = try await playOnline()
        online.keySession.refusesToPersist = true

        do {
            _ = try await store.over(online.player).acquire(
                contentId, licenceDuration: month, playbackDuration: twoDays)
            XCTFail("acquired on a device that will not persist")
        } catch let error as LicenceExchangeError {
            XCTAssertEqual(error.stage, .keyRequest)
        }
        XCTAssertEqual(try store.contentIds(), [])
        XCTAssertEqual(posts(online.server), 1)
    }

    func testOverAPlayerWithoutFairPlayIsRefused() throws {
        let store = try open()
        let player = SuperPlayer(configuration: SuperPlayer.Configuration())
        players.append(player)

        XCTAssertThrowsError(try store.over(player)) { error in
            XCTAssertEqual(error as? OfflineLicenceStoreError, .notFairPlay)
        }
    }

    // MARK: Fixtures

    private struct Fixture {
        let player: SuperPlayer
        let keySession: StandInKeySession
        let server: LoopbackOrigin
        let errors: ErrorRecord
    }

    private func open() throws -> OfflineLicenceStore {
        let store = try OfflineLicences.store(directory: directory)
        stores.append(store)
        return store
    }

    /// A player configured the way a consumer's download flow configures one — online, FairPlay
    /// beside resilience — whose content has asked for the keys `askFor`, each answered by the server.
    private func playOnline(
        askFor keys: [String]? = nil, faults: FaultScript = FaultScript.Builder().build(),
        budget: RetryBudget? = nil
    ) async throws -> Fixture {
        let fixture = try await play(licence: nil, faults: faults, budget: budget, sink: nil)
        for identifier in keys ?? [key] {
            try await answered(fixture.keySession.request(identifier))
        }
        return fixture
    }

    /// A player configured to play from `licence`, against a licence server of its own.
    private func playOffline(_ licence: OfflineLicence, sink: RecordingSink? = nil) async throws
        -> Fixture
    {
        try await play(
            licence: licence, faults: FaultScript.Builder().build(), budget: nil, sink: sink)
    }

    private func play(
        licence: OfflineLicence?, faults: FaultScript, budget: RetryBudget?, sink: RecordingSink?
    ) async throws -> Fixture {
        let content = try await harness.serve(.hls())
        let server = try await harness.serve(.licenceServer(), faults: faults)
        let keySession = StandInKeySession()
        var configuration = SuperPlayer.Configuration()
        configuration.drm = Drm.fairPlay(
            FairPlayConfig(
                certificateURL: server.url(path: TestContent.licenceCertificatePath),
                licenceURL: server.sourceURL),
            licence: licence, keySession: { keySession })
        configuration.resilience = Resilience.standard()
        if let budget { configuration.policy = LicenceBudget(licence: budget) }
        if let sink { configuration.telemetry = { QoeCollector(sink: sink) } }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        let errors = ErrorRecord()
        player.errors.sink { error in MainActor.assumeIsolated { errors.errors.append(error) } }
            .store(in: &subscriptions)
        player.setMediaRequest(MediaRequest(contentId: contentId, sources: [content.sourceURL]))
        return Fixture(player: player, keySession: keySession, server: server, errors: errors)
    }

    private func answered(_ record: KeyRequestRecord) async throws {
        try await harness.until("the key request is answered", timeout: bound) {
            !record.answers.isEmpty
        }
    }

    private func failed(_ errors: ErrorRecord) async throws {
        try await harness.until("the typed error arrives", timeout: bound) {
            !errors.errors.isEmpty
        }
    }

    private func posts(_ server: LoopbackOrigin) -> Int {
        server.networkRequests().filter { $0.method == "POST" }.count
    }
}
