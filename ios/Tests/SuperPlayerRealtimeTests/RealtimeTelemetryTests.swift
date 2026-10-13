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
import SuperPlayerRealtime
import SuperPlayerTelemetry
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest
import os

/// A `RealtimePlayer` measured by `QoeCollector` (#461): the schema's events that a stream's clock
/// and renderers can say, counted at a sink, and the ones they cannot, counted as absent
/// (`docs/telemetry-schema.md`, *Realtime sessions*).
///
/// **In real time, with bounded waits** (ADR-0021 rule 4), over `ScriptedFrameSource`'s decodable
/// H.264 at thirty frames a second. What is asserted is which events arrive, in what order and
/// carrying what — never how long anything took: a rebuffer is forced by a transport that goes quiet
/// for longer than its half-second preroll, and its being a rebuffer at all is the assertion.
///
/// Nothing is imported `@testable`: everything read here is what a consumer's sink is handed.
@MainActor
final class RealtimeTelemetryTests: XCTestCase {

    /// The bound on every wait, only a ceiling: one sampling interval of ten seconds and change.
    private let bound: Duration = .seconds(40)

    private let harness = PlaybackHarness()
    private var players: [RealtimePlayer] = []

    override func tearDown() async throws {
        for player in players { player.release() }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    /// The whole of a healthy session, and the control for the rebuffer below: started, a first
    /// frame from the declared intent, the ten-second samples and dropped frames, and an end — and
    /// no rebuffer, no switch, no seek and no live latency, which nothing on the path can say.
    func testARealtimeSessionReportsWhatItsClockAndRenderersCanSay() async throws {
        let sink = Sink()
        let player = makePlayer(ScriptedTransport { _ in ScriptedFrameSource() }, sink: sink)

        player.declarePlaybackIntent()
        try player.setMediaRequest(request())
        player.play()
        try await harness.until("a sample and a dropped-frame count are delivered", timeout: bound)
        {
            sink.events.contains { $0.isStateSample } && sink.events.contains { $0.isDropped }
        }
        player.release()
        try await harness.until("the session ends", timeout: bound) {
            sink.events.contains { $0.isEnd }
        }

        let events = sink.events
        XCTAssertEqual(Set(events.map(\.sessionId)).count, 1, "one session: \(sink.kinds)")
        XCTAssertLessThanOrEqual(events[0].sessionId.count, 64)
        XCTAssertEqual(Array(sink.kinds.prefix(2)), ["sessionStarted", "firstFrameRendered"])
        XCTAssertEqual(sink.kinds.last, "sessionEnded")

        guard case .sessionStarted(let started) = events[0] else { return XCTFail("\(sink.kinds)") }
        XCTAssertEqual(started.contentId, "studio-a")
        XCTAssertEqual(started.profile, .liveLinear)

        guard case .firstFrameRendered(let first) = events[1] else {
            return XCTFail("\(sink.kinds)")
        }
        XCTAssertEqual(first.startBoundary, .userIntent)

        let sample = try XCTUnwrap(events.lazy.compactMap(\.stateSample).first)
        XCTAssertNil(sample.videoBitrateBps, "a realtime track declares no rendition")
        XCTAssertNil(sample.throughputEstimateBps, "nothing on the path is a transfer")
        XCTAssertTrue(sample.playing)
        XCTAssertGreaterThan(sample.bufferedDurationMs, 0, "the transport's preroll is held")

        let dropped = try XCTUnwrap(events.lazy.compactMap(\.dropped).first)
        XCTAssertGreaterThan(dropped.elapsedPlayingMs, 0)
        XCTAssertEqual(dropped.repeatedFrames, 0)

        let absent: Set<String> = [
            "rebufferStarted", "rebufferEnded", "trackSwitched", "seekRequested", "seekCompleted",
            "liveLatencySampled", "startupFailed", "midStreamFailed", "decisionChanged",
        ]
        XCTAssertTrue(absent.isDisjoint(with: sink.kinds), "\(sink.kinds)")
    }

    /// A transport that goes quiet for longer than what the renderers hold starves them under a
    /// running clock, and that is a rebuffer — after the first frame, never seek-induced, and ended
    /// by the frames that arrive when it speaks again.
    func testARendererRunDryIsARebufferEndedByTheFramesThatFeedIt() async throws {
        let sink = Sink()
        let stall = ScriptedFrameSource.Stall(atUs: 2_000_000, seconds: 1.5)
        let player = makePlayer(
            ScriptedTransport { _ in ScriptedFrameSource(stall: stall) }, sink: sink)

        try player.setMediaRequest(request())
        player.play()
        try await harness.until("a rebuffer ends", timeout: bound) {
            sink.events.contains { $0.isRebufferEnd }
        }

        let kinds = sink.kinds
        let firstFrame = try XCTUnwrap(kinds.firstIndex(of: "firstFrameRendered"))
        let started = try XCTUnwrap(kinds.firstIndex(of: "rebufferStarted"))
        let ended = try XCTUnwrap(kinds.firstIndex(of: "rebufferEnded"))
        XCTAssertLessThan(firstFrame, started, "start-up time is not a rebuffer: \(kinds)")
        XCTAssertLessThan(started, ended)
        guard case .rebufferEnded(let rebuffer) = sink.events[ended] else { return XCTFail() }
        XCTAssertFalse(rebuffer.seekInduced, "nothing seeks a realtime stream")
        XCTAssertGreaterThan(rebuffer.durationMs, 0)
        XCTAssertNil(player.failure, "a stall is not a failure")
    }

    /// The control for the margin: a publisher delivering each frame just in time, with no preroll,
    /// holds about one frame and arrives with the host's scheduling jitter — and that is not a
    /// rebuffer. Without the margin, a reading landing in a frame's late millisecond would be one.
    func testAStreamDeliveredJustInTimeIsNotARebuffer() async throws {
        let sink = Sink()
        let player = makePlayer(
            ScriptedTransport { _ in ScriptedFrameSource(prerollUs: 0) }, sink: sink)

        try player.setMediaRequest(request())
        player.play()
        try await harness.until("three seconds of the stream have played", timeout: bound) {
            player.currentTime.seconds > 3
        }

        XCTAssertTrue(sink.kinds.contains("firstFrameRendered"), "\(sink.kinds)")
        XCTAssertFalse(sink.kinds.contains("rebufferStarted"), "\(sink.kinds)")
    }

    /// A stream refused before anything plays is a start-up failure, with no first frame — and the
    /// failure is bucketed off the error alone, since nothing classifies a realtime stream's.
    func testAStreamRefusedBeforeItsFirstFrameIsAStartupFailure() async throws {
        let sink = Sink()
        let player = makePlayer(
            ScriptedTransport { _ in DeclaringFrameSource(tracks: [RealtimeTrack(codec: "theora")])
            }, sink: sink)

        try player.setMediaRequest(request())
        player.play()
        try await harness.until("a start-up failure is delivered", timeout: bound) {
            sink.kinds.contains("startupFailed")
        }

        XCTAssertFalse(sink.kinds.contains("firstFrameRendered"))
        XCTAssertFalse(sink.kinds.contains("midStreamFailed"))
        let failure = try XCTUnwrap(
            sink.events.lazy.compactMap { event -> PlaybackFailure? in
                if case .startupFailed(let e) = event { e.failure } else { nil }
            }.first)
        XCTAssertNil(failure.classification, "no classifier reads a realtime stream's failure")
        XCTAssertEqual(failure.category, .unknown)
    }

    /// The control for the split: the same failure after the first frame is a mid-stream one, at the
    /// position the stream's clock had reached — reported once, however often the player changes
    /// after it.
    func testAStreamThatFailsAfterItsFirstFrameIsAMidStreamFailure() async throws {
        let sink = Sink()
        // A second and a half of frames: the preroll, then a second paced past it.
        let frames = ScriptedFrameSource.prerollFrames + SyntheticVideoHlsStream.framesPerSecond
        let player = makePlayer(
            ScriptedTransport { _ in ScriptedFrameSource(failsAfterFrames: frames) }, sink: sink)

        try player.setMediaRequest(request())
        player.play()
        try await harness.until("a mid-stream failure is delivered", timeout: bound) {
            sink.kinds.contains("midStreamFailed")
        }
        player.pause()
        player.play()
        player.release()
        try await harness.until("the session ends", timeout: bound) {
            sink.events.contains { $0.isEnd }
        }

        XCTAssertNotNil(player.failure)
        XCTAssertFalse(sink.kinds.contains("startupFailed"))
        XCTAssertEqual(sink.kinds.filter { $0 == "midStreamFailed" }.count, 1, "\(sink.kinds)")
        XCTAssertLessThan(
            try XCTUnwrap(sink.kinds.firstIndex(of: "firstFrameRendered")),
            try XCTUnwrap(sink.kinds.firstIndex(of: "midStreamFailed")))
    }

    /// A collector of a consumer's own is told each session's edges and the declared intent, and is
    /// attached to nothing — its `attach(to:)` takes a `SuperPlayer`. Replacing the stream ends one
    /// session before the next starts, as on a `SuperPlayer`.
    func testACollectorOfYourOwnHearsTheEdgesAndIsAttachedToNothing() throws {
        let collector = EdgeCollector()
        let player = RealtimePlayer(
            configuration: .init(
                sources: Realtime.transport(scheme: "scripted", factory: ScriptedTransport()),
                telemetry: { collector }),
            lifecycle: SharedLifecycle(platform: QuietPlatform()))
        players.append(player)

        player.declarePlaybackIntent()
        try player.setMediaRequest(request(contentId: "a"))
        try player.setMediaRequest(request(contentId: "b"))
        player.release()

        XCTAssertEqual(
            collector.calls, ["intent", "start a", "end", "start b", "end", "detach"])
        XCTAssertEqual(Set(collector.sessionIds).count, 2, "a session id per session")
    }

    // MARK: - Helpers

    private static let url = URL(string: "scripted://relay.example/studio-a")!

    private func request(contentId: String = "studio-a") -> MediaRequest {
        MediaRequest(contentId: contentId, sources: [Self.url])
    }

    private func makePlayer(_ transport: ScriptedTransport, sink: Sink) -> RealtimePlayer {
        let player = RealtimePlayer(
            configuration: .init(
                sources: Realtime.transport(scheme: "scripted", factory: transport),
                telemetry: { QoeCollector(sink: sink) }),
            lifecycle: SharedLifecycle(platform: QuietPlatform()))
        players.append(player)
        return player
    }
}

/// A sink that keeps everything it is handed, in order: written on the delivery queue and read on
/// the test's actor, so behind a lock.
private final class Sink: TelemetrySink {

    private let recorded = OSAllocatedUnfairLock(initialState: [TelemetryEvent]())

    func onEvent(_ event: TelemetryEvent) {
        recorded.withLock { $0.append(event) }
    }

    var events: [TelemetryEvent] { recorded.withLock { $0 } }

    /// Each event's case name, in order — what a failure message prints.
    var kinds: [String] {
        events.map { "\($0)".prefix { $0 != "(" }.description }
    }
}

/// A collector of a consumer's own: it records what it is told, and nothing else.
@MainActor
private final class EdgeCollector: TelemetryCollector {
    private(set) var calls: [String] = []
    private(set) var sessionIds: [String] = []

    func attach(to player: SuperPlayer) { calls.append("attach") }
    func startSession(contentId: String, sessionId: String) {
        calls.append("start \(contentId)")
        sessionIds.append(sessionId)
    }
    func endSession() { calls.append("end") }
    func declareIntent(monotonicTimeMs: Int64) { calls.append("intent") }
    func decisionChanged(_ decision: PlaybackDecision, trigger: DecisionTrigger) {
        calls.append("decision")
    }
    func detach() { calls.append("detach") }
}

extension TelemetryEvent {
    fileprivate var isStateSample: Bool { stateSample != nil }
    fileprivate var isDropped: Bool { dropped != nil }
    fileprivate var isEnd: Bool { if case .sessionEnded = self { true } else { false } }
    fileprivate var isRebufferEnd: Bool { if case .rebufferEnded = self { true } else { false } }
    fileprivate var stateSample: PlaybackStateSampled? {
        if case .playbackStateSampled(let e) = self { e } else { nil }
    }
    fileprivate var dropped: VideoFramesDropped? {
        if case .videoFramesDropped(let e) = self { e } else { nil }
    }
}
