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
import SuperPlayerABR
import SuperPlayerCore
import SuperPlayerTestkit
import XCTest

/// The adaptive policy on a real `SuperPlayer` over a shaped link — the Kotlin
/// `AdaptivePolicyPlaybackTest`'s post-rebuffer hold, on real time: a stall moves the ceiling onto
/// the playing item, a trigger inside the cooldown keeps it, and the first trigger after the cooldown
/// lifts it.
///
/// **What it plays and why.** The audio stream, at 2 Mbit/s, with its fourth segment held back by
/// the origin — `QoeCollectorPlaybackTests`' stall, which lands on every simulator this repository has
/// run on. Not the video ladder: a `SuperPlayer` on the ladder can stop fetching on CI's iOS 26.5
/// simulator (#474), and a ceiling is a decision laid on the item whatever the item carries, so
/// nothing here needs a rung to be climbed.
///
/// **The cooldown is shortened** through the policy's `package` seam, to ``cooldownMs`` rather than
/// the real half-minute, and the memory is the test's own so another test's samples are not held at.
/// The two triggers are rate changes the test makes. The first is made in the same main-actor turn
/// the hold is seen in, so what separates it from the rebuffer's end is the hold's own consultation
/// and one poll of the wait — far inside eight seconds on any host that plays at all. The second is
/// made once the cooldown has passed. The profile is the television's, whose decided forward buffer
/// scales with the rate, so each trigger is a `DecisionChanged` the test can wait for rather than a
/// consultation it assumes happened. Every wait is a ceiling.
///
/// Read through `player.avPlayer`'s item, as `PolicyPlaybackTests` reads a decision: a ceiling is
/// only honoured if it is on the engine's knob (ADR-0021 rule 1's #401 addendum).
@MainActor
final class AdaptivePolicyPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(90)
    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testAStallHoldsTheCeilingAndTheFirstTriggerAfterTheCooldownLiftsIt() async throws {
        let origin = try await harness.serve(
            .hls(segmentCount: 30),
            faults: FaultScript.Builder()
                // QoeCollectorPlaybackTests' fault and its reasoning: two asks held, one more than
                // AVPlayer's own retry outlasts.
                .addLatencyMs(30_000, kind: .mediaSegment, index: 3, firstAttempts: 2)
                .build(),
            network: try ThroughputTrace.Builder()
                .add(durationMs: 1_000, bandwidthBps: 2_000_000, transport: .wifi)
                .holdAtEnd()
                .build())
        let recorder = DecisionRecorder()
        var configuration = SuperPlayer.Configuration(profile: .tvLeanback)
        configuration.policy = AdaptivePolicy.forProfile(
            .tvLeanback, rebufferCooldownMs: Self.cooldownMs, memory: EstimateMemory())
        configuration.telemetry = { recorder }
        let player = SuperPlayer(configuration: configuration)
        defer { player.release() }

        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("the content plays", timeout: bound) {
            player.avPlayer.timeControlStatus == .playing
                && player.avPlayer.currentTime().seconds > 0.5
        }
        // The control: before any stall, the television profile's ceiling — none.
        XCTAssertNil(player.playbackDecision.trackSelection.maxVideoBitrateBps)
        XCTAssertEqual(player.avPlayer.currentItem?.preferredPeakBitRate, 0)

        try await harness.until("the stall holds the ceiling", timeout: bound) {
            player.playbackDecision.trackSelection.maxVideoBitrateBps != nil
        }
        let heldAt = ConditionsBinding.monotonicNowMs()
        let held = try XCTUnwrap(player.playbackDecision.trackSelection.maxVideoBitrateBps)
        XCTAssertEqual(player.avPlayer.currentItem?.preferredPeakBitRate, Double(held))
        XCTAssertEqual(
            Set(origin.responses().filter { $0.faults == [.latency] }.map(\.request.path)),
            ["/segment3.aac"], "the stall was the origin's")

        // Inside the cooldown a trigger re-consults and the hold stands.
        player.avPlayer.rate = 1.5
        try await harness.until("the faster rate is decided on", timeout: bound) {
            recorder.triggers.filter { $0 == .playbackSpeedChanged }.count == 1
        }
        XCTAssertNotNil(player.playbackDecision.trackSelection.maxVideoBitrateBps)

        try await harness.until("the cooldown passes", timeout: bound) {
            ConditionsBinding.monotonicNowMs() - heldAt > Self.cooldownMs
        }
        player.avPlayer.rate = 1
        try await harness.until("the real-time rate is decided on", timeout: bound) {
            recorder.triggers.filter { $0 == .playbackSpeedChanged }.count == 2
        }
        XCTAssertNil(player.playbackDecision.trackSelection.maxVideoBitrateBps, "the hold lapsed")
        XCTAssertEqual(player.avPlayer.currentItem?.preferredPeakBitRate, 0)
    }

    /// Long enough that the rate change made the moment the hold is seen lands inside it on any
    /// host; short enough that the test waits seconds rather than the real half-minute.
    private static let cooldownMs: Int64 = 8_000
}

/// A collector that records the trigger of each decision change core signals, and nothing else —
/// `PolicyPlaybackTests`' recorder, for its reason: what is waited for is core's signal.
@MainActor
private final class DecisionRecorder: TelemetryCollector {
    private(set) var triggers: [DecisionTrigger] = []

    func attach(to player: SuperPlayer) {}
    func startSession(contentId: String, sessionId: String) {}
    func endSession() {}
    func declareIntent(monotonicTimeMs: Int64) {}
    func decisionChanged(_ decision: PlaybackDecision, trigger: DecisionTrigger) {
        triggers.append(trigger)
    }
    func detach() {}
}
