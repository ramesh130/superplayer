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
import SuperPlayerTestkit
import XCTest
import os

// `@testable` for `QoeCollector.now()`, the one clock a time to first frame is a difference on: the
// test reads it before declaring intent, so the boundary's place is asserted by order on that clock
// rather than by a duration; and for `QoeCollector.milliseconds`, so a position the test reads is
// converted as the collector's are.
@testable import SuperPlayerTelemetry

/// What `QoeCollector` derives from a real `AVPlayer` playing from the loopback origin (ADR-0021
/// rule 2), through the public API: the first frame and its two start boundaries, a rebuffer the
/// origin causes by holding a segment back, a seek, and the session's end — the Kotlin
/// `QoeMetricsTest` and `FaultInducedRebufferTest`'s claims, on real time.
///
/// Real time means nothing here is a millisecond: every assertion is a count, an order, a flag, or
/// an order of two readings on the monotonic clock, and every wait has a ceiling it does not
/// approach. Every test plays its content to the end or releases its player before it returns, so no
/// load is left in flight for AVFoundation to tear down after the test.
///
/// The synthetic streams are audio, so "the first frame" here is the first audio presented — the
/// derivation `docs/telemetry-schema.md` states for iOS — and no test here can show a video track
/// switch or a dropped frame; `docs/testing.md`'s *The iOS harness* says so.
@MainActor
final class QoeCollectorPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(90)
    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    // MARK: - The first frame and its boundary

    /// The first frame once per session, measured from adoption when nothing was declared, and the
    /// control on the rebuffer rule: content that plays to the end unhindered stalls nowhere.
    func testTheFirstFrameIsReportedOnceFromAdoptionWhenNoIntentWasDeclared() async throws {
        let origin = try await harness.serve(.hls())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await playToEnd(player)
        player.release()
        try await waitForSessionEnd(sink)

        let firstFrames = sink.events.compactMap(\.firstFrame)
        XCTAssertEqual(firstFrames.count, 1, "\(sink.kinds)")
        let started = try XCTUnwrap(sink.events.compactMap(\.started).first)
        let first = try XCTUnwrap(firstFrames.first)
        XCTAssertEqual(first.startBoundary, .contentAdopted)
        XCTAssertEqual(
            first.monotonicTimeMs - first.timeToFirstFrameMs, started.monotonicTimeMs,
            "an adoption-labelled measurement starts where the session did")
        XCTAssertEqual(started.profile, .videoOnDemand)
        XCTAssertFalse(sink.kinds.contains("rebufferStarted"), "\(sink.kinds)")
    }

    /// A declaration before the content is known is the boundary, and belongs to one session: the
    /// next session on the same player, with nothing declared again, is adoption-labelled.
    func testADeclaredIntentIsTheBoundaryAndIsSpentByOneSession() async throws {
        let origin = try await harness.serve(.hls())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        let beforeIntent = QoeCollector.now()
        player.declarePlaybackIntent()
        try await play("a", from: origin, on: player)
        try await play("b", from: origin, on: player)
        player.release()
        try await waitForSessionEnd(sink, count: 2)

        let started = sink.events.compactMap(\.started)
        let firstFrames = sink.events.compactMap(\.firstFrame)
        XCTAssertEqual(firstFrames.map(\.contentId), ["a", "b"])
        XCTAssertEqual(firstFrames.map(\.startBoundary), [.userIntent, .contentAdopted])
        let intentStart = firstFrames[0].monotonicTimeMs - firstFrames[0].timeToFirstFrameMs
        XCTAssertGreaterThanOrEqual(intentStart, beforeIntent)
        XCTAssertLessThanOrEqual(
            intentStart, started[0].monotonicTimeMs,
            "the declared boundary is before the session it measures opened")
    }

    /// A declaration made while a session is open cannot move that session's start: it belongs to
    /// the next one.
    func testAnIntentDeclaredDuringASessionBelongsToTheNext() async throws {
        let origin = try await harness.serve(.hls())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        try await play("a", from: origin, on: player)
        player.declarePlaybackIntent()
        try await play("b", from: origin, on: player)
        player.release()
        try await waitForSessionEnd(sink, count: 2)

        XCTAssertEqual(
            sink.events.compactMap(\.firstFrame).map(\.startBoundary),
            [.contentAdopted, .userIntent])
    }

    // MARK: - A rebuffer the origin causes

    /// A segment held back by the origin past the point playback reaches it: a rebuffer, not
    /// attributed to a seek, that ends once the bytes arrive — the Kotlin
    /// `FaultInducedRebufferTest`, with the bytes really late.
    func testASegmentThatArrivesLateIsMeasuredAsARebuffer() async throws {
        let origin = try await harness.serve(
            .hls(segmentCount: 8),
            faults: FaultScript.Builder()
                // The fourth segment, six seconds in, silent on its first two asks. AVPlayer gives a
                // silent segment up after some seconds of its own and asks again, so one ask held
                // is answered by the retry before the three segments before it have played, and
                // every ask held is a stream that never resumes; two outlast what was buffered.
                .addLatencyMs(30_000, kind: .mediaSegment, index: 3, firstAttempts: 2)
                .build())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await playToEnd(player)
        player.release()
        try await waitForSessionEnd(sink)

        // A held answer is recorded when it is sent, which for an ask the player gave up on can be
        // after this line; so what is asserted is that the fault touched that segment alone.
        XCTAssertEqual(
            Set(origin.responses().filter { $0.faults == [.latency] }.map(\.request.path)),
            ["/segment3.aac"], "the fault happened, and to that segment alone")
        let kinds = sink.kinds
        let started = sink.events.compactMap(\.rebufferStarted)
        let ended = sink.events.compactMap(\.rebufferEnded)
        XCTAssertFalse(started.isEmpty, "\(kinds)")
        XCTAssertEqual(started.count, ended.count, "every stall opened is closed: \(kinds)")
        XCTAssertFalse(started[0].seekInduced, "nothing seeked")
        XCTAssertGreaterThan(ended[0].durationMs, 0)
        let firstFrame = try XCTUnwrap(kinds.firstIndex(of: "firstFrameRendered"))
        XCTAssertLessThan(
            firstFrame, try XCTUnwrap(kinds.firstIndex(of: "rebufferStarted")),
            "a stall before the first frame is start-up time, so the first comes first")
        // Sixteen seconds of content and a ten-second stall outlast a sampling interval: on-demand
        // content is sampled, and is never sampled for live latency.
        let samples = sink.events.compactMap(\.stateSample)
        XCTAssertFalse(samples.isEmpty, "\(kinds)")
        XCTAssertTrue(samples.allSatisfy { $0.samplingIntervalMs == 10_000 })
        XCTAssertTrue(samples.allSatisfy { $0.videoBitrateBps == nil }, "the content has no video")
        XCTAssertFalse(kinds.contains("liveLatencySampled"), "\(kinds)")
    }

    // MARK: - The periodic samples

    /// Live content is sampled for its distance from the live edge as well as for its state, at the
    /// cadence every sample states.
    func testLiveContentIsSampledForItsLatency() async throws {
        let origin = try await harness.serve(.liveHls())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        try await play("live", from: origin, on: player)
        // A sample taken while playing is waited for rather than assumed of the first: a live
        // stream on a loaded host can be waiting for a segment the origin publishes on real time at
        // the instant the timer fires, and that sample says so truthfully (#459). A collector that
        // never reports playing still fails, at the bound.
        try await harness.until("a latency sample and a playing sample", timeout: bound) {
            !sink.events.compactMap(\.liveLatency).isEmpty
                && sink.events.compactMap(\.stateSample).contains(where: \.playing)
        }
        player.avPlayer.pause()
        player.release()
        try await waitForSessionEnd(sink)

        let sample = try XCTUnwrap(sink.events.compactMap(\.stateSample).first(where: \.playing))
        XCTAssertEqual(sample.samplingIntervalMs, 10_000)
        XCTAssertGreaterThan(sample.bufferedDurationMs, 0, "a playing stream has media ahead")
        let latency = try XCTUnwrap(sink.events.compactMap(\.liveLatency).first)
        XCTAssertGreaterThanOrEqual(latency.liveLatencyMs, 0)
    }

    // MARK: - Seek

    /// A seek during playback is requested from where playback was and completed at the target,
    /// in that order, once.
    ///
    /// Where it landed is a reading and is bounded rather than equated (#459). AVFoundation posts
    /// the jump on the main thread and names no position, so the collector reads the time when the
    /// notification is handled: on a main thread that was busy as the time jumped, that is the
    /// target plus whatever played meanwhile — CI read 5456 and 5580 for a seek to 5000. So the
    /// landing is held between the target and where playback had reached once the seek completed,
    /// an order of two readings of one media clock, which a collector reporting where the seek came
    /// from, or a reading from before the jump, still fails.
    func testASeekIsRequestedAndThenCompletedAtItsTarget() async throws {
        let origin = try await harness.serve(.hls())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        try await play("a", from: origin, on: player, pastSeconds: 1)
        let target = CMTime(value: 5_000, timescale: 1_000)
        await player.avPlayer.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        try await harness.until("the seek completes", timeout: bound) {
            !sink.events.compactMap(\.seekCompleted).isEmpty
        }
        // Read after both events were delivered, with no seek since, and by the collector's own
        // conversion: no earlier on the media clock than either event's reading.
        let playedToMs = QoeCollector.milliseconds(player.avPlayer.currentTime())
        try await playToEnd(player)
        player.release()
        try await waitForSessionEnd(sink)

        let requested = sink.events.compactMap(\.seekRequested)
        let completed = sink.events.compactMap(\.seekCompleted)
        XCTAssertEqual(requested.count, 1, "\(sink.kinds)")
        XCTAssertEqual(completed.count, 1, "\(sink.kinds)")
        let kinds = sink.kinds
        XCTAssertLessThan(
            try XCTUnwrap(kinds.firstIndex(of: "seekRequested")),
            try XCTUnwrap(kinds.firstIndex(of: "seekCompleted")))
        XCTAssertGreaterThanOrEqual(
            requested[0].toPositionMs, 5_000, "an exact seek lands on its target, not before it")
        XCTAssertLessThanOrEqual(
            requested[0].toPositionMs, playedToMs, "nor past where playback had reached")
        XCTAssertLessThan(
            requested[0].fromPositionMs, 5_000, "it came from earlier on")
        XCTAssertGreaterThanOrEqual(completed[0].toPositionMs, requested[0].toPositionMs)
        XCTAssertLessThanOrEqual(completed[0].toPositionMs, playedToMs)
        XCTAssertGreaterThanOrEqual(completed[0].seekLatencyMs, 0)
        XCTAssertTrue(
            sink.events.compactMap(\.rebufferStarted).allSatisfy(\.seekInduced),
            "any stall around the seek is the seek's: \(kinds)")
    }

    // MARK: - The session's end

    /// A release ends the session: its last event is `SessionEnded`, under the session's id, with
    /// nothing dropped — and a second release sends nothing more.
    func testAReleaseEndsTheSessionWithItsLastEvent() async throws {
        let origin = try await harness.serve(.hls())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        try await play("a", from: origin, on: player)
        player.release()
        player.release()
        try await waitForSessionEnd(sink)

        let events = sink.events
        guard case .sessionEnded(let ended) = try XCTUnwrap(events.last) else {
            return XCTFail("the last event is not the session's end: \(sink.kinds)")
        }
        XCTAssertEqual(sink.kinds.first, "sessionStarted")
        XCTAssertEqual(ended.droppedEventCount, 0)
        XCTAssertEqual(ended.contentId, "a")
        XCTAssertEqual(Set(events.map(\.sessionId)), [ended.sessionId], "one session")
        XCTAssertEqual(events.compactMap(\.ended).count, 1)
    }

    // MARK: - Helpers

    private func measuredPlayer(_ sink: RecordingSink) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: sink) }
        return SuperPlayer(configuration: configuration)
    }

    /// Sets `contentId`'s request and waits for it to play past `pastSeconds`.
    private func play(
        _ contentId: String, from origin: LoopbackOrigin, on player: SuperPlayer,
        pastSeconds: Double = 1
    ) async throws {
        player.setMediaRequest(MediaRequest(contentId: contentId, sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("\(contentId) plays", timeout: bound) {
            player.currentContentId == contentId
                && player.avPlayer.currentTime().seconds > pastSeconds
        }
    }

    /// Waits for the current item to report its end, so nothing is left loading.
    private func playToEnd(_ player: SuperPlayer) async throws {
        let item = try XCTUnwrap(player.avPlayer.currentItem)
        let ended = EndObserver(item)
        try await harness.until("the content plays to its end", timeout: bound) { ended.hasEnded }
    }

    /// Waits for `count` sessions' ends to have been delivered: delivery is asynchronous.
    private func waitForSessionEnd(_ sink: RecordingSink, count: Int = 1) async throws {
        try await harness.until("\(count) session end delivered", timeout: bound) {
            sink.events.compactMap(\.ended).count >= count
        }
    }
}

/// A sink that keeps everything it is handed, in order. Written on the delivery queue and read on
/// the test's actor, so behind a lock.
final class RecordingSink: TelemetrySink {

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

/// Whether an `AVPlayerItem` has posted that it played to its end, readable from the main actor.
@MainActor
final class EndObserver {
    private(set) var hasEnded = false
    // Written once, in `init`, and read once more in `deinit`, which is not on the main actor.
    nonisolated(unsafe) private var token: NSObjectProtocol?

    init(_ item: AVPlayerItem) {
        token = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.hasEnded = true } }
    }

    deinit { token.map(NotificationCenter.default.removeObserver) }
}

extension TelemetryEvent {
    var started: SessionStarted? { if case .sessionStarted(let e) = self { e } else { nil } }
    var ended: SessionEnded? { if case .sessionEnded(let e) = self { e } else { nil } }
    var firstFrame: FirstFrameRendered? {
        if case .firstFrameRendered(let e) = self { e } else { nil }
    }
    var rebufferStarted: RebufferStarted? {
        if case .rebufferStarted(let e) = self { e } else { nil }
    }
    var rebufferEnded: RebufferEnded? { if case .rebufferEnded(let e) = self { e } else { nil } }
    var seekRequested: SeekRequested? { if case .seekRequested(let e) = self { e } else { nil } }
    var seekCompleted: SeekCompleted? { if case .seekCompleted(let e) = self { e } else { nil } }
    var stateSample: PlaybackStateSampled? {
        if case .playbackStateSampled(let e) = self { e } else { nil }
    }
    var liveLatency: LiveLatencySampled? {
        if case .liveLatencySampled(let e) = self { e } else { nil }
    }
}
