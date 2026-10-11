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
import Combine
import SuperPlayerCore
import SuperPlayerDRM
import SuperPlayerResilience
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest
import os

/// The FairPlay exchange through a real `SuperPlayer` (#422): the certificate fetched, the key
/// request `POST`ed to the server the configuration named, a refusal asked again on
/// `RetryPolicy.licence` and then named, one 401 repaired without spending the budget, a rotation
/// counted at the server and not measured — the Kotlin `LicenceLoadTest`, `KeyRotationTest` and
/// `LicenceTelemetryTest`, over iOS's mechanism.
///
/// **The key session is a stand-in** (``StandInKeySession``), at the one `package` protocol this
/// product defines, because the simulator has no FairPlay (ADR-0021 rule 5): a test makes the key
/// request a device's `AVContentKeySession` would. Everything after that is the shipped code — the
/// exchange, the budget, the backoff, the classifier and the collector — against the harness's
/// licence server on the loopback origin, counted there, because a retry is only a retry if the bytes
/// were asked for again. Every player is built as a consumer builds one: `drm` beside `resilience`.
///
/// Waits are bounded and never asserted (ADR-0021 rule 4); every budget here waits a few
/// milliseconds, so a spent budget is quick, and a test asserts the count it left behind.
@MainActor
final class LicenceExchangeTests: XCTestCase {

    private let bound: Duration = .seconds(30)

    /// A key identifier as a playlist's `EXT-X-KEY` names one. Its host names a server that is not
    /// the configured one, deliberately: the identifier is never an address (`FairPlayConfig`).
    private let key = "skd://elsewhere.example/key-1"

    private let harness = PlaybackHarness()
    private var players: [SuperPlayer] = []
    private var subscriptions: Set<AnyCancellable> = []

    override func tearDown() async throws {
        subscriptions = []
        for player in players { player.release() }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    // MARK: The exchange

    func testAKeyRequestIsPostedToTheConfiguredServerAndItsLicenceHandedBack() async throws {
        let fixture = try await play()

        let record = fixture.keySession.request(key)
        try await answered(record)

        XCTAssertEqual(record.certificate, Data(TestContent.licenceCertificate))
        // The skd URI's host, the convention a key server reads the asset from.
        XCTAssertEqual(record.contentIdentifier, Data("elsewhere.example".utf8))
        let spc = StandInKeySession.serverPlaybackContext(for: key)
        XCTAssertEqual(record.answers, [.licence(Data(TestContent.licence(for: Array(spc))))])
        // One certificate, one key request, both at the configured server: the identifier's own host
        // was never asked for anything.
        let requests = fixture.server.networkRequests()
        XCTAssertEqual(requests.map(\.method), ["GET", "POST"])
        XCTAssertEqual(requests.map(\.path), ["/fairplay/certificate", "/fairplay/licence"])
        XCTAssertEqual(requests.last?.body, Array(spc))
        XCTAssertEqual(requests.last?.headers["content-type"], "application/octet-stream")
        XCTAssertTrue(fixture.errors.errors.isEmpty, "\(fixture.errors.errors)")
    }

    /// Two keys asked for at once — the ordinary start-up of content whose audio and video are keyed
    /// apart — and a third later share one certificate fetch.
    func testTheCertificateIsFetchedOncePerPlayer() async throws {
        let fixture = try await play()

        let first = fixture.keySession.request(key)
        let second = fixture.keySession.request("skd://elsewhere.example/key-2")
        try await answered(first)
        try await answered(second)
        try await answered(fixture.keySession.request("skd://elsewhere.example/key-3"))

        let methods = fixture.server.networkRequests().map(\.method)
        XCTAssertEqual(methods.filter { $0 == "GET" }.count, 1, "\(methods)")
        XCTAssertEqual(methods.filter { $0 == "POST" }.count, 3, "\(methods)")
    }

    // MARK: The budget

    /// Two refusals and a third ask that carried the licence, out of a budget of two retries: the
    /// session never hears of the refusals.
    func testARefusedLicenceIsAskedForAgainOnItsOwnBudget() async throws {
        let fixture = try await play(
            faults: licenceRefused(500, firstAttempts: 2), budget: retries(2))

        let record = fixture.keySession.request(key)
        try await answered(record)

        guard case .licence = record.answers.first else {
            return XCTFail("\(record.answers)")
        }
        XCTAssertEqual(licenceRequests(fixture.server), 3)
        XCTAssertTrue(fixture.errors.errors.isEmpty, "\(fixture.errors.errors)")
    }

    /// The control on the budget: a refusal that never relents is asked for exactly the budget's
    /// worth again, and then named — a licence the round trip did not produce, on rung 6.
    func testTheLicenceBudgetIsWhatStopsTheAskingAndTheFailureIsNamed() async throws {
        let fixture = try await play(faults: licenceRefused(500), budget: retries(2))

        let record = fixture.keySession.request(key)
        try await failed(fixture)

        XCTAssertEqual(licenceRequests(fixture.server), 3)
        XCTAssertEqual(record.answers.count, 1)
        guard case .failed = record.answers.first else { return XCTFail("\(record.answers)") }
        let typed = try XCTUnwrap(fixture.errors.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Drm.LicenceAcquisition")
        XCTAssertEqual(typed.category, .drm)
        XCTAssertEqual(typed.rungsTried, [])
        let evidence = try XCTUnwrap(typed.underlyingError as Error as? LicenceExchangeError)
        XCTAssertEqual(evidence.stage, .licence)
        XCTAssertEqual(evidence.status, 500)
        XCTAssertEqual(evidence.attempts, 3)
    }

    // MARK: The credential

    /// ADR-0012 rule 2 on iOS: the licence budget is nothing, so a licence that arrives cannot have
    /// been rescued by a retry — the provider was asked once and the key request asked again with
    /// what it returned.
    func testARefusedCredentialIsRepairedOnceWithoutSpendingTheBudget() async throws {
        let provider = MintingProvider()
        let fixture = try await play(
            faults: licenceRefused(401, firstAttempts: 1), budget: retries(0), provider: provider)

        let record = fixture.keySession.request(key)
        try await answered(record)

        guard case .licence = record.answers.first else { return XCTFail("\(record.answers)") }
        let refusals = provider.refusals
        XCTAssertEqual(refusals.count, 1, "\(refusals)")
        XCTAssertEqual(refusals.first?.status, 401)
        XCTAssertEqual(refusals.first?.uri, fixture.server.sourceURL)
        XCTAssertEqual(refusals.first?.sentHeaders, [:])
        let posts = fixture.server.networkRequests().filter { $0.method == "POST" }
        XCTAssertEqual(posts.map { $0.headers["authorization"] }, [nil, "Bearer fresh-1"])
        // The SPC posted with the fresh credential is the one the refusal met.
        XCTAssertEqual(posts.map(\.body), Array(repeating: posts[0].body, count: 2))
        XCTAssertTrue(fixture.errors.errors.isEmpty, "\(fixture.errors.errors)")
    }

    /// A provider with nothing newer to give declines, and the refusal stands: asked once, and the
    /// failure named as the licence's.
    func testAProviderWithNothingNewerToGiveDeclinesAndTheRefusalStands() async throws {
        let fixture = try await play(
            faults: licenceRefused(403), budget: retries(0), provider: EchoingProvider())

        _ = fixture.keySession.request(key)
        try await failed(fixture)

        XCTAssertEqual(licenceRequests(fixture.server), 1)
        let typed = try XCTUnwrap(fixture.errors.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Drm.LicenceAcquisition")
    }

    // MARK: The device

    /// A key request the device will not compose asks no server, and is the protection stack's
    /// failure rather than the licence server's.
    func testAKeyRequestTheDeviceWillNotComposeIsASystemErrorAndAsksNoServer() async throws {
        let fixture = try await play()

        _ = fixture.keySession.request(key, composes: false)
        try await failed(fixture)

        XCTAssertEqual(licenceRequests(fixture.server), 0)
        let typed = try XCTUnwrap(fixture.errors.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Drm.SystemError")
    }

    // MARK: Rotation and telemetry

    /// The first key of the content is the one acquisition measured; a rotation to a second key and
    /// a renewal of the first are one licence request each, counted at the server, and measured as
    /// nothing — `docs/telemetry-schema.md`'s *Licence acquisition*.
    func testARotationIsOneLicenceRequestAndNoAcquisition() async throws {
        let sink = RecordingSink()
        let fixture = try await play(sink: sink)

        try await answered(fixture.keySession.request(key))
        try await answered(fixture.keySession.request("skd://elsewhere.example/key-2"))
        try await answered(fixture.keySession.request(key, renewal: true))

        XCTAssertEqual(licenceRequests(fixture.server), 3)
        try await harness.until("the acquisition is delivered", timeout: bound) {
            !sink.acquisitions.isEmpty
        }
        // Telemetry is delivered off the main actor, so the absence of a second is read after a
        // release that ends the session, behind which nothing of it can still be queued.
        fixture.player.release()
        try await harness.until("the session ends", timeout: bound) { sink.hasEnded }
        let acquisitions = sink.acquisitions
        XCTAssertEqual(acquisitions.map(\.outcome), [.acquiredFromServer])
        XCTAssertEqual(acquisitions.first?.securityLevel, nil)
        XCTAssertEqual(acquisitions.first?.contentId, "a")
        XCTAssertGreaterThanOrEqual(acquisitions.first?.durationMs ?? -1, 0)
    }

    /// A refused acquisition is measured too, and before the session's failure.
    func testARefusedAcquisitionIsMeasured() async throws {
        let sink = RecordingSink()
        let fixture = try await play(faults: licenceRefused(500), budget: retries(0), sink: sink)

        _ = fixture.keySession.request(key)
        try await failed(fixture)
        fixture.player.release()
        try await harness.until("the session ends", timeout: bound) { sink.hasEnded }

        XCTAssertEqual(sink.acquisitions.map(\.outcome), [.refused])
    }

    /// A renewal that fails leaves the player on the key it holds: the request is told, the session
    /// is not — the Kotlin `KeyRotationTest`'s second finding.
    func testARenewalThatFailsEndsNothing() async throws {
        let fixture = try await play(
            faults: FaultScript.Builder()
                .failWithHttpStatus(500, kind: .licence, index: 1, afterAttempts: 1).build(),
            budget: retries(0))

        try await answered(fixture.keySession.request(key))
        let renewal = fixture.keySession.request(key, renewal: true)
        try await answered(renewal)

        guard case .failed = renewal.answers.first else { return XCTFail("\(renewal.answers)") }
        XCTAssertTrue(fixture.errors.errors.isEmpty, "\(fixture.errors.errors)")
    }

    // MARK: The session's lifetime

    /// Every asset the player plays is a recipient, once, and the session ends with the player —
    /// ADR-0012 rule 1's one session per player, with each item a recipient.
    func testEveryAssetIsARecipientOnceAndTheSessionEndsWithThePlayer() async throws {
        let fixture = try await play()
        fixture.player.setMediaRequest(
            MediaRequest(contentId: "b", sources: [fixture.content.sourceURL]))

        XCTAssertEqual(fixture.keySession.recipients.count, 2)
        XCTAssertEqual(
            fixture.keySession.recipients.map(\.url),
            Array(repeating: fixture.content.sourceURL, count: 2))
        XCTAssertFalse(fixture.keySession.isInvalidated)

        fixture.player.release()
        XCTAssertTrue(fixture.keySession.isInvalidated)
    }

    /// Without resilience nothing names the failure, as on Android: the key request is still told,
    /// so on a device the item fails as AVFoundation fails it, and the error is AVFoundation's.
    func testWithoutResilienceTheKeyRequestIsRefusedAndNothingIsNamed() async throws {
        let fixture = try await play(
            faults: licenceRefused(500), budget: retries(0), resilience: false)

        let record = fixture.keySession.request(key)
        try await answered(record)

        guard case .failed = record.answers.first else { return XCTFail("\(record.answers)") }
        XCTAssertTrue(fixture.errors.errors.isEmpty, "\(fixture.errors.errors)")
        XCTAssertNil(fixture.player.playerError)
    }

    // MARK: Fixtures

    private struct Fixture {
        let player: SuperPlayer
        let keySession: StandInKeySession
        let server: LoopbackOrigin
        let content: LoopbackOrigin
        let errors: ErrorRecord
    }

    /// A player of clear content — no item here asks for a key, since none could — configured for
    /// FairPlay against the harness's licence server with `faults`, and the licence `budget`.
    private func play(
        faults: FaultScript = FaultScript.Builder().build(), budget: RetryBudget? = nil,
        provider: (any HeaderProvider)? = nil, sink: RecordingSink? = nil, resilience: Bool = true
    ) async throws -> Fixture {
        let content = try await harness.serve(.hls())
        let server = try await harness.serve(.licenceServer(), faults: faults)
        let keySession = StandInKeySession()
        var configuration = SuperPlayer.Configuration()
        configuration.drm = Drm.fairPlay(
            FairPlayConfig(
                certificateURL: server.url(path: TestContent.licenceCertificatePath),
                licenceURL: server.sourceURL),
            keySession: { keySession })
        if resilience { configuration.resilience = Resilience.standard(headers: provider) }
        if let budget { configuration.policy = LicenceBudget(licence: budget) }
        if let sink { configuration.telemetry = { QoeCollector(sink: sink) } }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        let errors = ErrorRecord()
        player.errors.sink { error in MainActor.assumeIsolated { errors.errors.append(error) } }
            .store(in: &subscriptions)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [content.sourceURL]))
        return Fixture(
            player: player, keySession: keySession, server: server, content: content,
            errors: errors)
    }

    private func answered(_ record: KeyRequestRecord) async throws {
        try await harness.until("the key request is answered", timeout: bound) {
            !record.answers.isEmpty
        }
    }

    private func failed(_ fixture: Fixture) async throws {
        try await harness.until("the typed error arrives", timeout: bound) {
            !fixture.errors.errors.isEmpty
        }
    }

    private func licenceRequests(_ server: LoopbackOrigin) -> Int {
        server.networkRequests().filter { $0.method == "POST" }.count
    }

    /// The licence endpoint — the server's second resource, after the certificate — answering
    /// `status`, on its first `firstAttempts` asks or on every one.
    private func licenceRefused(_ status: Int, firstAttempts: Int? = nil) -> FaultScript {
        FaultScript.Builder()
            .failWithHttpStatus(status, kind: .licence, index: 1, firstAttempts: firstAttempts)
            .build()
    }

    /// `count` retries, a few milliseconds apart, so a spent budget is quick.
    private func retries(_ count: Int) -> RetryBudget {
        RetryBudget(maxRetries: count, initialBackoffMs: 2, maxBackoffMs: 8)
    }
}

/// The video-on-demand decision with the licence budget a test names.
private struct LicenceBudget: PlaybackPolicy {
    let licence: RetryBudget

    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
        var decision = PlaybackProfile.videoOnDemand.policy.decide(conditions)
        decision.retry.licence = licence
        return decision
    }
}

/// What reached ``SuperPlayer/errors``, in order.
@MainActor
final class ErrorRecord {
    var errors: [any Error] = []
}

/// The events a collector delivered, readable from any thread.
final class RecordingSink: TelemetrySink {

    private let recorded = OSAllocatedUnfairLock(initialState: [TelemetryEvent]())

    func onEvent(_ event: TelemetryEvent) {
        recorded.withLock { $0.append(event) }
    }

    var acquisitions: [TelemetryEvent.LicenceAcquisitionEnded] {
        recorded.withLock { events in
            events.compactMap { event in
                if case .licenceAcquisitionEnded(let ended) = event { return ended }
                return nil
            }
        }
    }

    var hasEnded: Bool {
        recorded.withLock { events in
            events.contains { event in
                if case .sessionEnded = event { return true }
                return false
            }
        }
    }
}

/// A provider minting a new token per question, keeping every refusal it was told about.
private final class MintingProvider: HeaderProvider {

    private let asked = OSAllocatedUnfairLock(initialState: [CredentialRefusal]())

    var refusals: [CredentialRefusal] { asked.withLock { $0 } }

    func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String] {
        let count = asked.withLock { refusals in
            refusals.append(refusal)
            return refusals.count
        }
        return ["Authorization": "Bearer fresh-\(count)"]
    }
}

/// A provider with nothing newer: what was sent, back.
private struct EchoingProvider: HeaderProvider {
    func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String] {
        refusal.sentHeaders
    }
}
