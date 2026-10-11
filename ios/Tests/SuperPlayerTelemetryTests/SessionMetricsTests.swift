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

import SuperPlayerCore
import SuperPlayerTelemetry
import XCTest

/// `SessionMetrics` and `QoeScore` over event streams written by hand — the Kotlin
/// `SessionMetricsTest`, case for case and number for number, because the two platforms promise one
/// set of definitions and the same stream has to reduce to the same numbers on either.
final class SessionMetricsTests: XCTestCase {

    private let content = "content"
    private let session = "session"

    func testTimeToFirstFrameAndItsBoundaryComeFromTheEvent() {
        let metrics = reduce(
            started(0), firstFrame(at: 900, ttffMs: 900, boundary: .userIntent), ended(60_000))

        XCTAssertEqual(metrics.timeToFirstFrameMs, 900)
        XCTAssertEqual(metrics.startBoundary, .userIntent)
    }

    func testRebufferRatioCountsOnlyStallsThatWereNotSeekInduced() throws {
        let metrics = reduce(
            started(0), firstFrame(at: 1_000, ttffMs: 1_000),
            rebuffer(at: 11_000, durationMs: 2_000, seekInduced: false),
            rebuffer(at: 21_000, durationMs: 3_000, seekInduced: true), ended(61_000))

        // ref: docs/telemetry-schema.md, Rebuffering — the numerator sums only the stalls no seek
        // caused, and the denominator loses both, since the position advanced during neither.
        XCTAssertEqual(metrics.rebufferMs, 2_000)
        XCTAssertEqual(metrics.rebufferCount, 1)
        XCTAssertEqual(metrics.playingMs, 61_000 - 1_000 - 2_000 - 3_000)
        XCTAssertEqual(try XCTUnwrap(metrics.rebufferRatio), 2_000.0 / 57_000.0, accuracy: 1e-9)
    }

    /// Zero would be the best-looking number in the column, and wrong: nothing played.
    func testASessionThatNeverRenderedAFrameHasNoRatioRatherThanAZeroOne() {
        let metrics = reduce(started(0), startupFailed(500), ended(600))

        XCTAssertNil(metrics.rebufferRatio)
        XCTAssertEqual(metrics.playingMs, 0)
        XCTAssertTrue(metrics.startupFailed)
        XCTAssertFalse(metrics.exitBeforeVideoStart)
    }

    func testASessionThatEndedWithNoFrameAndNoFailureIsAnExitBeforeVideoStart() {
        let metrics = reduce(started(0), ended(4_000))

        XCTAssertTrue(metrics.exitBeforeVideoStart)
        XCTAssertFalse(metrics.startupFailed)
    }

    /// ref: docs/telemetry-schema.md, Bitrate — "across a pause, and across a rebuffer, nothing
    /// accrues", so the sample that was not playing earns no weight.
    func testAverageBitrateIsWeightedByThePlayingTimeEachSampleCarries() throws {
        let metrics = reduce(
            started(0), firstFrame(at: 0, ttffMs: 100),
            sample(at: 10_000, bitrateBps: 1_000_000, playing: true),
            sample(at: 20_000, bitrateBps: 3_000_000, playing: true),
            sample(at: 30_000, bitrateBps: 9_000_000, playing: false), ended(40_000))

        XCTAssertEqual(try XCTUnwrap(metrics.averageBitrateBps), 2_000_000, accuracy: 1e-9)
    }

    func testASessionWithNoPlayingSampleHasNoBitrateRatherThanZero() {
        let metrics = reduce(
            started(0), firstFrame(at: 0, ttffMs: 100),
            sample(at: 10_000, bitrateBps: nil, playing: true), ended(20_000))

        XCTAssertNil(metrics.averageBitrateBps)
    }

    func testTheInitialRenditionChoiceIsNotASwitch() {
        let metrics = reduce(
            started(0), firstFrame(at: 0, ttffMs: 100),
            switched(at: 100, from: nil, to: 365_000, .initial),
            switched(at: 20_000, from: 365_000, to: 2_000_000, .up),
            switched(at: 30_000, from: 2_000_000, to: 730_000, .down), ended(40_000))

        XCTAssertEqual(metrics.switchCount, 2)
        XCTAssertEqual(metrics.upshiftCount, 1)
        XCTAssertEqual(metrics.downshiftCount, 1)
        XCTAssertEqual(
            metrics.switchMagnitudeBpsSum, (2_000_000 - 365_000) + (2_000_000 - 730_000))
    }

    func testASessionWithDroppedEventsIsNotFitToAggregate() {
        let metrics = reduce(
            started(0), firstFrame(at: 500, ttffMs: 500), ended(30_000, dropped: 4))

        XCTAssertFalse(metrics.usable)
        XCTAssertEqual(metrics.droppedEventCount, 4)
    }

    func testASessionThatNeverEndedIsNotFitToAggregate() {
        let metrics = reduce(started(0), firstFrame(at: 500, ttffMs: 500))

        XCTAssertFalse(metrics.usable)
        XCTAssertEqual(metrics.playingMs, 0)
    }

    /// `sessionId` is what separates a feed's interleaved players, and nothing else does.
    func testEventsOfOtherSessionsAreIgnored() {
        let mine = reduce(
            started(0), firstFrame(at: 500, ttffMs: 500),
            .rebufferEnded(
                .init(
                    sessionId: "someone-else", contentId: content, timestampMs: 0,
                    monotonicTimeMs: 10_000, durationMs: 9_000, seekInduced: false)),
            ended(30_000))

        XCTAssertEqual(mine.rebufferMs, 0)
        XCTAssertEqual(mine.rebufferCount, 0)
    }

    // MARK: - The objective

    /// Two megabits a second played for forty seconds, two seconds stalled on a ladder topping out
    /// at four, and one switch of a megabit: 2 − 4 × 2/40 − 1/40.
    func testTheScoreIsUtilityLessBothPenaltiesPerSecondPlayed() throws {
        let metrics = reduce(
            started(0), firstFrame(at: 0, ttffMs: 100),
            switched(at: 100, from: nil, to: 1_000_000, .initial),
            switched(at: 5_000, from: 1_000_000, to: 2_000_000, .up),
            rebuffer(at: 20_000, durationMs: 2_000, seekInduced: false),
            sample(at: 10_000, bitrateBps: 2_000_000, playing: true), ended(42_000))

        let breakdown = try XCTUnwrap(QoeScore.breakdown(metrics, ladderTopBitrateBps: 4_000_000))
        XCTAssertEqual(breakdown.bitrateUtility, 2, accuracy: 1e-9)
        XCTAssertEqual(breakdown.rebufferPenalty, 4 * 2 / 40, accuracy: 1e-9)
        XCTAssertEqual(breakdown.switchPenalty, 1.0 / 40, accuracy: 1e-9)
        XCTAssertEqual(
            try XCTUnwrap(QoeScore.of(metrics, ladderTopBitrateBps: 4_000_000)),
            2 - 0.2 - 0.025, accuracy: 1e-9)
    }

    /// A failed session has no score rather than a zero that would flatter it.
    func testASessionWithNothingPlayedHasNoScore() {
        XCTAssertNil(
            QoeScore.of(
                reduce(started(0), startupFailed(500), ended(600)),
                ladderTopBitrateBps: 4_000_000))
    }

    // MARK: - Building event streams by hand

    private func reduce(_ events: TelemetryEvent...) -> SessionMetrics {
        SessionMetrics.from(sessionId: session, events: events)
    }

    private func started(_ at: Int64) -> TelemetryEvent {
        .sessionStarted(
            .init(
                sessionId: session, contentId: content, timestampMs: 0, monotonicTimeMs: at,
                profile: .videoOnDemand, decision: PlaybackDecision()))
    }

    private func ended(_ at: Int64, dropped: Int = 0) -> TelemetryEvent {
        .sessionEnded(
            .init(
                sessionId: session, contentId: content, timestampMs: 0, monotonicTimeMs: at,
                droppedEventCount: dropped, securityLevel: nil))
    }

    private func firstFrame(
        at: Int64, ttffMs: Int64, boundary: TtffStartBoundary = .contentAdopted
    ) -> TelemetryEvent {
        .firstFrameRendered(
            .init(
                sessionId: session, contentId: content, timestampMs: 0, monotonicTimeMs: at,
                timeToFirstFrameMs: ttffMs, startBoundary: boundary))
    }

    private func rebuffer(at: Int64, durationMs: Int64, seekInduced: Bool) -> TelemetryEvent {
        .rebufferEnded(
            .init(
                sessionId: session, contentId: content, timestampMs: 0, monotonicTimeMs: at,
                durationMs: durationMs, seekInduced: seekInduced))
    }

    private func sample(at: Int64, bitrateBps: Int?, playing: Bool) -> TelemetryEvent {
        .playbackStateSampled(
            .init(
                sessionId: session, contentId: content, timestampMs: 0, monotonicTimeMs: at,
                samplingIntervalMs: 10_000, videoBitrateBps: bitrateBps, bufferedDurationMs: 0,
                playing: playing))
    }

    private func switched(
        at: Int64, from: Int?, to: Int, _ direction: TrackSwitchDirection
    ) -> TelemetryEvent {
        .trackSwitched(
            .init(
                sessionId: session, contentId: content, timestampMs: 0, monotonicTimeMs: at,
                fromBitrateBps: from, toBitrateBps: to, direction: direction))
    }

    private func startupFailed(_ at: Int64) -> TelemetryEvent {
        .startupFailed(
            .init(
                sessionId: session, contentId: content, timestampMs: 0, monotonicTimeMs: at,
                failure: PlaybackFailure(category: .unknown, code: nil, message: nil)))
    }
}
