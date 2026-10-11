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
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// The policy boundary through the harness (#415, ADR-0005 and ADR-0009): a hand-written policy's
/// decision reaches the item a request makes, a trigger re-consults it and the *playing* item's
/// knobs change in place with one `DecisionChanged`, and a player under its profile's static policy
/// is consulted once and reports nothing — the Kotlin `SuperPlayerPolicyTest`'s claims.
///
/// **Read through `player.avPlayer`**, as `ProfileTableTests` reads a profile's row: the knobs are
/// the engine's own properties, and a decision is only honoured if it is on them (ADR-0021 rule 1's
/// #401 addendum). The decision changes are what core tells a collector, read by a hand-written
/// recorder for `SessionEdgePlaybackTests`' reason — what is asserted is core's signal, not the
/// event `QoeCollector` builds from it, which `OSLogSinkTests` and the collector's own tests hold.
///
/// Two triggers are forced here, because the harness can produce them on demand: the rate a test
/// sets, and the stream type a live playlist declares. The transport cannot be switched in a
/// simulator, and a rebuffer or a throughput move would need a timed fault to land; those three
/// translations are held without a device at `ConditionsBinding`'s readers instead
/// (`ConditionsBindingTests`), and the reconsultation they feed is the one this file drives.
///
/// Time is real and nothing here measures it: every wait is a ceiling.
@MainActor
final class PolicyPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(60)
    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    /// A consumer's policy, not a profile's, decides what the item is made with — and a second
    /// policy deciding differently makes a different item, so the knobs are the policy's and not
    /// a default both happen to share.
    func testAHandWrittenPolicysDecisionReachesTheItem() async throws {
        let origin = try await harness.serve(.hls())
        let recorder = DecisionRecorder()
        let player = playerWith(
            SpeedPolicy(atRealTime: Self.slow, faster: Self.fast), recorder: recorder)

        try await play(on: player, from: origin)

        let item = try XCTUnwrap(player.avPlayer.currentItem)
        XCTAssertEqual(item.preferredForwardBufferDuration, 12)
        XCTAssertEqual(item.preferredPeakBitRate, 400_000)
        XCTAssertEqual(player.playbackDecision, Self.slow)

        let other = playerWith(
            SpeedPolicy(atRealTime: Self.fast, faster: Self.slow), recorder: DecisionRecorder())
        other.setMediaRequest(MediaRequest(contentId: "b", sources: [origin.sourceURL]))
        XCTAssertEqual(other.avPlayer.currentItem?.preferredForwardBufferDuration, 30)
        other.release()
        player.release()
    }

    /// The acceptance's centre: a rate change re-consults the policy, its new answer is laid on the
    /// item already playing — the same object, not a replacement — and the collector hears of it
    /// exactly once, with the trigger that produced it.
    func testARateChangeReconsultsAndChangesThePlayingItemInPlace() async throws {
        let origin = try await harness.serve(.hls())
        let recorder = DecisionRecorder()
        let player = playerWith(
            SpeedPolicy(atRealTime: Self.slow, faster: Self.fast), recorder: recorder)
        try await play(on: player, from: origin)
        let item = try XCTUnwrap(player.avPlayer.currentItem)

        player.avPlayer.rate = 2
        try await harness.until("the decision changes", timeout: bound) {
            player.playbackDecision == Self.fast
        }
        // Setting the same rate again is not a change, so it is not a second consultation's event;
        // a second of playback after it is room for one to have arrived had it been.
        player.avPlayer.rate = 2
        let then = player.avPlayer.currentTime().seconds
        try await harness.until("playback moves on", timeout: bound) {
            player.avPlayer.currentTime().seconds > then + 1
        }

        XCTAssertIdentical(player.avPlayer.currentItem, item, "the item was not replaced")
        XCTAssertEqual(item.preferredForwardBufferDuration, 30)
        XCTAssertEqual(item.preferredPeakBitRate, 2_000_000)
        XCTAssertEqual(recorder.changes.map(\.trigger), [.playbackSpeedChanged])
        XCTAssertEqual(recorder.changes.map(\.decision), [Self.fast])
        player.release()
    }

    /// A live playlist's indefinite duration is the stream type becoming known, which re-consults:
    /// the live half the policy decides for live content reaches the playing item.
    func testALivePlaylistIsAStreamTypeChangeThatReachesTheItem() async throws {
        let origin = try await harness.serve(.liveHls())
        let recorder = DecisionRecorder()
        let player = playerWith(StreamTypePolicy(), recorder: recorder)

        try await play(on: player, from: origin)
        try await harness.until("the stream type is decided on", timeout: bound) {
            player.playbackDecision == StreamTypePolicy.live
        }

        XCTAssertEqual(player.avPlayer.currentItem?.automaticallyPreservesTimeOffsetFromLive, true)
        XCTAssertEqual(recorder.changes.map(\.trigger), [.streamTypeChanged])
        player.release()
    }

    /// The control: a player under its profile's static policy — the default, or the same policy
    /// handed back explicitly — plays its profile's row, and a rate change consults nothing and
    /// reports nothing, so "a trigger re-consults" above is not every player re-deciding.
    func testAStaticPlayerIsConsultedOnceAndReportsNothing() async throws {
        let origin = try await harness.serve(.hls())
        let policies: [(any PlaybackPolicy)?] = [nil, PlaybackProfile.shortForm.policy]
        for policy in policies {
            let recorder = DecisionRecorder()
            var configuration = SuperPlayer.Configuration(profile: .shortForm)
            configuration.policy = policy
            configuration.telemetry = { recorder }
            let player = SuperPlayer(configuration: configuration)
            try await play(on: player, from: origin)
            let decided = player.playbackDecision

            player.avPlayer.rate = 2
            let then = player.avPlayer.currentTime().seconds
            try await harness.until("the faster rate plays", timeout: bound) {
                player.avPlayer.currentTime().seconds > then + 1
            }

            XCTAssertEqual(player.playbackDecision, decided)
            XCTAssertEqual(player.avPlayer.currentItem?.preferredForwardBufferDuration, 15)
            XCTAssertEqual(recorder.changes.count, 0, "\(String(describing: policy))")
            player.release()
        }
    }

    private static let slow = PlaybackDecision(
        buffer: BufferPolicy(maxBufferMs: 12_000),
        trackSelection: TrackSelectionPolicy(maxVideoBitrateBps: 400_000))

    private static let fast = PlaybackDecision(
        buffer: BufferPolicy(maxBufferMs: 30_000),
        trackSelection: TrackSelectionPolicy(maxVideoBitrateBps: 2_000_000))

    private func playerWith(_ policy: any PlaybackPolicy, recorder: DecisionRecorder)
        -> SuperPlayer
    {
        var configuration = SuperPlayer.Configuration()
        configuration.policy = policy
        configuration.telemetry = { recorder }
        return SuperPlayer(configuration: configuration)
    }

    /// Sets a request for `origin`'s stream and waits for it to play.
    private func play(on player: SuperPlayer, from origin: LoopbackOrigin) async throws {
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("the content plays", timeout: bound) {
            player.avPlayer.timeControlStatus == .playing
                && player.avPlayer.currentTime().seconds > 0.5
        }
    }
}

/// A policy that reads one observation, the playback speed: `atRealTime` at 1× or unobserved,
/// `faster` above it. Reading nothing else is what makes every other trigger — the network path the
/// monitor delivers as it starts among them — a consultation that changes nothing.
private struct SpeedPolicy: PlaybackPolicy {
    let atRealTime: PlaybackDecision
    let faster: PlaybackDecision

    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
        (conditions.playbackSpeed ?? 1) > 1 ? faster : atRealTime
    }
}

/// A policy that reads the stream type alone: a live half preserving the offset for live content,
/// nothing for anything else.
private struct StreamTypePolicy: PlaybackPolicy {
    static let live = PlaybackDecision(liveLatency: LiveLatencyPolicy(preservesOffset: true))

    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
        conditions.streamType == .live ? Self.live : PlaybackDecision()
    }
}

/// A collector that records each decision change core signals, and nothing else.
@MainActor
private final class DecisionRecorder: TelemetryCollector {
    private(set) var changes: [(decision: PlaybackDecision, trigger: DecisionTrigger)] = []

    func attach(to player: SuperPlayer) {}
    func startSession(contentId: String, sessionId: String) {}
    func endSession() {}
    func declareIntent(monotonicTimeMs: Int64) {}
    func decisionChanged(_ decision: PlaybackDecision, trigger: DecisionTrigger) {
        changes.append((decision, trigger))
    }
    func detach() {}
}
