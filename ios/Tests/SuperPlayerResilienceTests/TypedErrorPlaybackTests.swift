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
import SuperPlayerResilience
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest
import os

/// Rung 6 through the harness (#419): a refused segment and a refused playlist end a real
/// `SuperPlayer` on a ``SuperPlayerError`` naming different classes, read from AVFoundation's own
/// error log — the acceptance — and telemetry reports the same class; a player configured without
/// resilience classifies nothing and delivers nothing, on both halves (ADR-0011 rule 14). The
/// Kotlin `TypedErrorPlaybackTest`'s role.
///
/// The refusals are the origin's (``FaultScript``), so each is shown to have happened by the
/// origin's record of how it answered, and every outcome is a typed value read with a bounded wait;
/// nothing is timed. A segment refused 404 on every attempt is what AVFoundation retries before it
/// gives up, which is why ``bound`` is generous.
@MainActor
final class TypedErrorPlaybackTests: XCTestCase {

    /// The ceiling on every wait: a segment refused on every attempt is asked for about a dozen
    /// times over some thirty seconds before the item fails (observed, #419). Only a ceiling.
    private let bound: Duration = .seconds(90)

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

    private func player(resilient: Bool, sink: RecordingSink? = nil) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        if resilient { configuration.resilience = Resilience.standard() }
        if let sink { configuration.telemetry = { QoeCollector(sink: sink) } }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        return player
    }

    /// Plays `origin` on `player` and returns every error ``SuperPlayer/errors`` delivered once the
    /// item has failed.
    private func playUntilItFails(_ player: SuperPlayer, _ origin: LoopbackOrigin) async throws
        -> ErrorRecord
    {
        let record = ErrorRecord()
        // On the main actor, where the facade sends: `errors` delivers on the actor it is isolated to.
        player.errors.sink { error in MainActor.assumeIsolated { record.errors.append(error) } }
            .store(in: &subscriptions)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("the item fails", timeout: bound) {
            player.avPlayer.currentItem?.status == .failed
        }
        return record
    }

    // MARK: The acceptance: a segment and a playlist refused 404

    func testASegmentRefused404EndsOnTheCdnEdge() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpNotFound, kind: .mediaSegment, index: 1)
                .build())
        let player = player(resilient: true)

        let record = try await playUntilItFails(player, origin)
        try await harness.until("the typed error arrives", timeout: bound) {
            !record.errors.isEmpty
        }

        XCTAssertTrue(
            origin.responses().contains { $0.request.path == "/segment1.aac" && $0.status == 404 })
        // The platform fact the classifier reads, held here so a runtime that changes it fails a
        // test rather than a classification: a segment retried is logged without its URI.
        let logged = try XCTUnwrap(player.avPlayer.currentItem?.errorLog()?.events.last)
        XCTAssertTrue(logged.uri == nil || logged.uri?.hasSuffix(".aac") == true, "\(logged)")

        let typed = try assertOneTypedError(record, on: player)
        XCTAssertEqual(typed.causeClass, "Transient.CdnEdge")
        XCTAssertEqual(typed.category, .network)
        XCTAssertTrue(typed.isRetryable)
        XCTAssertEqual(typed.userMessageKey, FailureClass.networkMessageKey)
        XCTAssertEqual(typed.rungsTried, [])
    }

    /// The control that keeps the pair from being "every 404 is the edge": the media playlist
    /// refused the same way.
    func testAPlaylistRefused404EndsOnTheNetwork() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpNotFound, kind: .manifest, index: 1).build())
        let player = player(resilient: true)

        let record = try await playUntilItFails(player, origin)
        try await harness.until("the typed error arrives", timeout: bound) {
            !record.errors.isEmpty
        }

        XCTAssertTrue(
            origin.responses().contains { $0.request.path == "/media.m3u8" && $0.status == 404 })
        let logged = try XCTUnwrap(player.avPlayer.currentItem?.errorLog()?.events.last)
        XCTAssertEqual(logged.uri.flatMap(URL.init(string:))?.lastPathComponent, "media.m3u8")

        let typed = try assertOneTypedError(record, on: player)
        XCTAssertEqual(typed.causeClass, "Transient.Network")
    }

    /// The multivariant playlist itself refused, which AVFoundation logs nothing for.
    func testTheMultivariantPlaylistRefused404EndsOnTheNetwork() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpNotFound, kind: .manifest, index: 0).build())
        let player = player(resilient: true)

        let record = try await playUntilItFails(player, origin)
        try await harness.until("the typed error arrives", timeout: bound) {
            !record.errors.isEmpty
        }

        XCTAssertEqual(player.avPlayer.currentItem?.errorLog()?.events.count ?? 0, 0)
        XCTAssertEqual(try assertOneTypedError(record, on: player).causeClass, "Transient.Network")
        // An error that is not the item's is classified on itself, not answered with the item's.
        let unrelated = NSError(
            domain: AVFoundationErrorDomain, code: AVError.Code.decoderNotFound.rawValue)
        XCTAssertEqual(player.classify(unrelated)?.causeClass, "Device.DecoderInit")
    }

    // MARK: Telemetry reads the same class

    func testTheStartupFailureCarriesTheClassification() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpForbidden, kind: .mediaSegment, index: 0)
                .build())
        let sink = RecordingSink()
        let player = player(resilient: true, sink: sink)

        let record = try await playUntilItFails(player, origin)
        try await harness.until("the startup failure is delivered", timeout: bound) {
            sink.startupFailures.count == 1 && !record.errors.isEmpty
        }

        let failure = try XCTUnwrap(sink.startupFailures.first)
        let typed = try assertOneTypedError(record, on: player)
        XCTAssertEqual(failure.classification, "Transient.CdnEdge")
        XCTAssertEqual(failure.classification, typed.causeClass)
        XCTAssertEqual(failure.category, typed.category)
        XCTAssertEqual(failure.code, "NSURLErrorDomain:\(NSURLErrorNoPermissionsToReadFile)")
    }

    // MARK: ADR-0011 rule 14: without resilience, nothing

    /// The same refusal on a player configured without resilience: the item fails exactly as on a
    /// stock player, its error is AVFoundation's on the item, nothing is delivered, `classify`
    /// answers nil, and telemetry's classification is nil while its category is still reported.
    func testAPlayerWithoutResilienceClassifiesNothing() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpForbidden, kind: .mediaSegment, index: 0)
                .build())
        let sink = RecordingSink()
        let player = player(resilient: false, sink: sink)

        let record = try await playUntilItFails(player, origin)
        try await harness.until("the startup failure is delivered", timeout: bound) {
            sink.startupFailures.count == 1
        }

        let error = try XCTUnwrap(player.avPlayer.currentItem?.error)
        XCTAssertNil(player.classify(error))
        XCTAssertNil(player.playerError)
        XCTAssertTrue(record.errors.isEmpty)
        let failure = try XCTUnwrap(sink.startupFailures.first)
        XCTAssertNil(failure.classification)
        XCTAssertEqual(failure.category, .network)
    }

    // MARK: - Helpers

    /// The one typed error delivered, held to be the one ``SuperPlayer/playerError`` keeps and
    /// ``SuperPlayer/classify(_:)`` answers for the item's own error, with that error underneath.
    private func assertOneTypedError(_ record: ErrorRecord, on player: SuperPlayer) throws
        -> SuperPlayerError
    {
        XCTAssertEqual(record.errors.count, 1, "\(record.errors)")
        let typed = try XCTUnwrap(record.errors.first as? SuperPlayerError)
        XCTAssertEqual((player.playerError as? SuperPlayerError)?.causeClass, typed.causeClass)
        let itemError = try XCTUnwrap(player.avPlayer.currentItem?.error) as NSError
        XCTAssertEqual(player.classify(itemError)?.causeClass, typed.causeClass)
        XCTAssertEqual(typed.underlyingError.domain, itemError.domain)
        XCTAssertEqual(typed.underlyingError.code, itemError.code)
        let bridged = typed as NSError
        XCTAssertEqual(bridged.domain, SuperPlayerError.errorDomain)
        XCTAssertTrue(bridged.userInfo[NSUnderlyingErrorKey] as? NSError === typed.underlyingError)
        return typed
    }
}

/// What a subscription to ``SuperPlayer/errors`` heard, on the main actor.
@MainActor
final class ErrorRecord {
    var errors: [any Error] = []
}

/// A sink that keeps everything it is handed. Written on the delivery queue and read on the test's
/// actor, so behind a lock.
final class RecordingSink: TelemetrySink {

    private let recorded = OSAllocatedUnfairLock(initialState: [TelemetryEvent]())

    func onEvent(_ event: TelemetryEvent) {
        recorded.withLock { $0.append(event) }
    }

    /// The failures of every startup-failure event, in order.
    var startupFailures: [PlaybackFailure] {
        recorded.withLock { events in
            events.compactMap { event in
                if case .startupFailed(let failed) = event { return failed.failure }
                return nil
            }
        }
    }
}
