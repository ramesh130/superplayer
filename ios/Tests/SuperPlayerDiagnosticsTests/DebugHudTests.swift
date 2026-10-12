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
import SuperPlayerDiagnostics
import SuperPlayerResilience
import SuperPlayerTelemetry
import SuperPlayerTestMedia
import SuperPlayerTestkit
import SwiftUI
import XCTest
import os

/// The debug HUD over a `SuperPlayer` and a stock `AVPlayer` playing from the loopback origin — the
/// Kotlin `DebugHudTest`'s claims: every row answered on the facade with telemetry, the stock
/// player degrading to `unavailable` rather than refusing, the telemetry half fed through the sink's
/// own front door, the guard, and the overlay rendered.
///
/// The rows are read off the HUD's model, `HudFacts`, which is what the view draws and nothing
/// else; that the view draws it is `testTheOverlayRendersAndTheGuardedBodyIsTheOverlay`'s.
@MainActor
final class DebugHudTests: XCTestCase {

    private let bound: Duration = .seconds(60)
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

    // MARK: - Two players

    /// A `SuperPlayer` with telemetry answers all six rows: the rendition and the buffer off its
    /// `AVPlayer`, the ceiling off its decision — `unlimited`, the default profile's, which is a
    /// decision and not a missing reading — and the estimate and dropped frames off the sink.
    func testASuperPlayerWithTelemetryAnswersEveryRow() async throws {
        let origin = try await harness.serve(.hls())
        let telemetry = DebugHudTelemetry()
        let started = StartedSink()
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: .composite(telemetry, started)) }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("the session has started", timeout: bound) { started.hasStarted }
        // The estimate is the collector's ten-second sample, which a test does not wait out: it is
        // fed through the sink's own front door, as the Kotlin test feeds it — after the session's
        // start, which begins the reading over.
        telemetry.onEvent(.playbackStateSampled(sample(estimateBps: 2_400_000)))

        let facts = try await readWhenPlaying(.superPlayer(player), telemetry: telemetry)
        let rows = values(facts)

        XCTAssertEqual(rows["source"], "SuperPlayer, telemetry attached")
        XCTAssertEqual(rows["bitrate"], "\(variantBitrate / 1_000) kbps")
        XCTAssertGreaterThan(facts.bufferedDurationMs, 0)
        XCTAssertEqual(rows["dropped"], "0")
        XCTAssertEqual(
            rows["estimate"],
            "2400 kbps estimated, \(variantBitrate / 1_000) kbps selected, unlimited ceiling")
        XCTAssertEqual(rows["failures"], "none")
    }

    /// A stock `AVPlayer` answers what it can — the rendition, the buffer, the position — and reads
    /// `unavailable` on the rest, rather than refusing.
    func testAStockPlayerDegradesToWhatItCanAnswer() async throws {
        let origin = try await harness.serve(.hls())
        let player = AVPlayer(url: origin.sourceURL)
        player.play()
        defer { player.pause() }

        let facts = try await readWhenPlaying(.stock(player), telemetry: nil)
        let rows = values(facts)

        XCTAssertEqual(rows["source"], "AVPlayer, no telemetry")
        XCTAssertEqual(rows["bitrate"], "\(variantBitrate / 1_000) kbps")
        XCTAssertGreaterThan(facts.bufferedDurationMs, 0)
        XCTAssertEqual(rows["dropped"], "unavailable")
        XCTAssertEqual(
            rows["estimate"],
            "unavailable estimated, \(variantBitrate / 1_000) kbps selected, unavailable ceiling")
        XCTAssertEqual(rows["failures"], "none")
    }

    /// A stock player's own failure is the failures row: the item's error by its domain and code,
    /// never its message.
    func testAStockPlayersFailureIsItsOwnErrorsCode() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpNotFound, kind: .manifest, index: 0).build())
        let player = AVPlayer(url: origin.sourceURL)
        player.play()
        defer { player.pause() }
        try await harness.until("the item fails", timeout: bound) {
            player.currentItem?.status == .failed
        }
        let error = try XCTUnwrap(player.currentItem?.error) as NSError

        let failures = values(HudFacts.read(.stock(player), telemetry: nil))["failures"]

        XCTAssertEqual(failures, "delivered \(error.domain):\(error.code)")
        XCTAssertFalse(failures?.contains("127.0.0.1") ?? true)
    }

    /// On a `SuperPlayer` the delivered error is the one rung 6 handed the consumer, printed as the
    /// engine's code under it, beside — never merged with — what telemetry classified.
    func testASuperPlayersDeliveredErrorSitsBesideWhatTelemetryClassified() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpNotFound, kind: .manifest, index: 0).build())
        let telemetry = DebugHudTelemetry()
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: telemetry) }
        configuration.resilience = Resilience.standard()
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("the session fails typed and telemetry hears it", timeout: bound) {
            player.playerError != nil && !telemetry.reading().failures.isEmpty
        }
        let typed = try XCTUnwrap(player.playerError as? SuperPlayerError)

        let failures = HudFacts.read(.superPlayer(player), telemetry: telemetry).failures

        XCTAssertEqual(
            failures.first,
            "delivered \(typed.underlyingError.domain):\(typed.underlyingError.code)")
        XCTAssertEqual(failures.dropFirst().first?.hasPrefix(typed.causeClass), true, "\(failures)")
    }

    // MARK: - The telemetry half

    /// A new session starts the reading over; a sample's own nil does not blank the estimate;
    /// dropped frames are the session's running total; and failures are newest first and four at
    /// most — the Kotlin `HudTelemetryReading`'s four claims.
    func testTheSinkKeepsAReadingOfTheSessionAndForgetsTheLastOne() {
        let telemetry = DebugHudTelemetry()

        telemetry.onEvent(.playbackStateSampled(sample(estimateBps: 1_000_000)))
        telemetry.onEvent(.playbackStateSampled(sample(estimateBps: nil)))
        XCTAssertEqual(telemetry.reading().throughputEstimateBps, 1_000_000)

        telemetry.onEvent(.videoFramesDropped(dropped(3)))
        telemetry.onEvent(.videoFramesDropped(dropped(4)))
        XCTAssertEqual(telemetry.reading().droppedFrames, 7)

        for code in 1...5 {
            telemetry.onEvent(
                .midStreamFailed(
                    TelemetryEvent.MidStreamFailed(
                        sessionId: "s", contentId: "a", timestampMs: 0, monotonicTimeMs: 0,
                        failure: PlaybackFailure(
                            category: .network, code: "Domain:\(code)", message: "http://host/x",
                            classification: "Transient.CdnEdge"),
                        positionMs: 0)))
        }
        XCTAssertEqual(
            telemetry.reading().failures,
            (2...5).reversed().map { "Transient.CdnEdge Domain:\($0)" })
        XCTAssertFalse(telemetry.reading().failures.joined().contains("http://"))

        telemetry.onEvent(
            .sessionStarted(
                TelemetryEvent.SessionStarted(
                    sessionId: "t", contentId: "b", timestampMs: 0, monotonicTimeMs: 0,
                    profile: .videoOnDemand,
                    decision: PlaybackProfile.videoOnDemand.policy
                        .decide(PlaybackConditions()))))
        XCTAssertEqual(telemetry.reading(), HudTelemetryReading())
    }

    // MARK: - The guard and the view

    /// Debug-compiled on the simulator: shown. Release-compiled: never, whatever the signature.
    /// On a device: only where the embedded profile grants `get-task-allow` — a development
    /// signature — and not where it is false, where there is no profile (App Store, TestFlight),
    /// or where the profile cannot be read.
    func testTheGuardIsADebugBuildWithADevelopmentSignature() {
        let development = profile(getTaskAllow: true)
        let distribution = profile(getTaskAllow: false)

        XCTAssertTrue(
            debugHudAvailability(compiledForDebug: true, simulator: true, provisioningProfile: nil))
        XCTAssertTrue(
            debugHudAvailability(
                compiledForDebug: true, simulator: false, provisioningProfile: development))

        XCTAssertFalse(
            debugHudAvailability(
                compiledForDebug: false, simulator: true, provisioningProfile: development))
        XCTAssertFalse(
            debugHudAvailability(
                compiledForDebug: true, simulator: false, provisioningProfile: distribution))
        XCTAssertFalse(
            debugHudAvailability(compiledForDebug: true, simulator: false, provisioningProfile: nil)
        )
        XCTAssertFalse(
            debugHudAvailability(
                compiledForDebug: true, simulator: false, provisioningProfile: Data("junk".utf8)))
        // This test runs Debug-compiled on a simulator, so the real reading answers yes.
        XCTAssertTrue(isDebugHudAvailable())
    }

    /// The overlay draws: rendered off screen, it is an image of the overlay's size, and the HUD's
    /// body — this run being a Debug build on a simulator — renders the same overlay.
    func testTheOverlayRendersAndTheGuardedBodyIsTheOverlay() async throws {
        let origin = try await harness.serve(.hls())
        let player = AVPlayer(url: origin.sourceURL)
        let hud = DebugHud(player: player)

        let overlay = try XCTUnwrap(ImageRenderer(content: hud.overlay).uiImage)
        let body = try XCTUnwrap(ImageRenderer(content: hud).uiImage)

        XCTAssertGreaterThan(overlay.size.width, 0)
        XCTAssertGreaterThan(overlay.size.height, 0)
        XCTAssertEqual(body.size, overlay.size)
    }

    // MARK: - Helpers

    /// The synthetic stream's one variant, as its multivariant playlist declares it.
    private let variantBitrate = SyntheticHlsStream.declaredBitrateBps

    private func readWhenPlaying(_ source: HudSource, telemetry: DebugHudTelemetry?) async throws
        -> HudFacts
    {
        try await harness.until("the HUD reads a rendition and a buffer", timeout: bound) {
            let facts = HudFacts.read(source, telemetry: telemetry)
            return facts.selectedBitrateBps != nil && facts.bufferedDurationMs > 0
        }
        return HudFacts.read(source, telemetry: telemetry)
    }

    private func values(_ facts: HudFacts) -> [String: String] {
        Dictionary(uniqueKeysWithValues: facts.rows().map { ($0.label, $0.value) })
    }

    private func sample(estimateBps: Int?) -> TelemetryEvent.PlaybackStateSampled {
        TelemetryEvent.PlaybackStateSampled(
            sessionId: "s", contentId: "a", timestampMs: 0, monotonicTimeMs: 0,
            samplingIntervalMs: 10_000, videoBitrateBps: nil, bufferedDurationMs: 0,
            playing: true, throughputEstimateBps: estimateBps)
    }

    private func dropped(_ count: Int) -> TelemetryEvent.VideoFramesDropped {
        TelemetryEvent.VideoFramesDropped(
            sessionId: "s", contentId: "a", timestampMs: 0, monotonicTimeMs: 0,
            droppedFrames: count, repeatedFrames: 0, elapsedPlayingMs: 10_000)
    }

    /// A provisioning profile as Apple ships one: a property list inside a signed envelope, here
    /// bytes either side standing for the envelope.
    private func profile(getTaskAllow: Bool) -> Data {
        let plist = try! PropertyListSerialization.data(
            fromPropertyList: ["Name": "stated", "Entitlements": ["get-task-allow": getTaskAllow]],
            format: .xml, options: 0)
        return Data([0x30, 0x82, 0x01]) + plist + Data([0xA0, 0x00])
    }
}

/// Whether a session has started, heard on the delivery queue.
private final class StartedSink: TelemetrySink, Sendable {
    private let started = OSAllocatedUnfairLock(initialState: false)

    var hasStarted: Bool { started.withLock { $0 } }

    func onEvent(_ event: TelemetryEvent) {
        if case .sessionStarted = event { started.withLock { $0 = true } }
    }
}
