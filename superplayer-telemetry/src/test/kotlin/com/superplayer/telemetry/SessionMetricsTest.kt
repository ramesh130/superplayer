/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.telemetry

import com.superplayer.core.BufferPolicy
import com.superplayer.core.FailureCategory
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackFailure
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.core.TtffStartBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metric definitions, against hand-built event streams.
 *
 * Every metric in a report goes through [SessionMetrics], so what it computes *is* what the benchmark
 * claims. These are unit tests over synthetic events rather than over playback, deliberately: a
 * definition is a statement about a stream of events and the cheapest honest way to check it is to
 * write the stream by hand and say what should come out. `StockTelemetryAgreementTest` covers the
 * other half — that both collectors produce the streams these assume.
 *
 * Each test names the section of `docs/telemetry-schema.md` it holds this file to. Where the
 * document leaves a choice open and this project made one, the test pins the choice, so changing it
 * is a visible edit rather than a drift.
 */
class SessionMetricsTest {

    @Test
    fun timeToFirstFrameAndItsBoundaryComeFromTheEvent() {
        val metrics = reduce(
            started(0),
            firstFrame(at = 900, ttffMs = 900, boundary = TtffStartBoundary.USER_INTENT),
            ended(60_000),
        )

        // ref: docs/telemetry-schema.md, Time to first frame — taken from the event rather than
        // recomputed, because the collector is the only thing that knows which boundary applied.
        assertEquals(900L, metrics.timeToFirstFrameMs)
        assertEquals(TtffStartBoundary.USER_INTENT, metrics.startBoundary)
    }

    @Test
    fun rebufferRatioCountsOnlyStallsThatWereNotSeekInduced() {
        val metrics = reduce(
            started(0),
            firstFrame(at = 1_000, ttffMs = 1_000),
            rebuffer(at = 11_000, durationMs = 2_000, seekInduced = false),
            rebuffer(at = 21_000, durationMs = 3_000, seekInduced = true),
            ended(61_000),
        )

        // ref: docs/telemetry-schema.md, Rebuffering — the numerator sums only `seekInduced = false`.
        assertEquals(2_000L, metrics.rebufferMs)
        assertEquals(1, metrics.rebufferCount)
        // The denominator loses *both* stalls, because the position advanced during neither. A
        // seek-induced stall left in the denominator would make a session's ratio depend on how much
        // its viewer seeked, which is the behaviour the exclusion exists to remove.
        assertEquals(61_000L - 1_000L - 2_000L - 3_000L, metrics.playingMs)
        assertEquals(2_000.0 / (2_000.0 + 55_000.0), metrics.rebufferRatio!!, 1e-9)
    }

    @Test
    fun aSessionThatNeverRenderedAFrameHasNoRatioRatherThanAZeroOne() {
        val metrics = reduce(started(0), startupFailed(500), ended(600))

        // Zero would be the best-looking number in the column, and it would be wrong: nothing
        // played, so there is no proportion of playing time that was spent stalled. `Distribution.of`
        // drops nulls and the report prints an em dash.
        assertNull(metrics.rebufferRatio)
        assertEquals(0L, metrics.playingMs)
        assertTrue(metrics.startupFailed)
        assertFalse(metrics.exitBeforeVideoStart)
    }

    @Test
    fun aSessionThatEndedWithNoFrameAndNoFailureIsAnExitBeforeVideoStart() {
        val metrics = reduce(started(0), ended(4_000))

        // ref: docs/telemetry-schema.md, Exit before video start — derived, because the library
        // cannot observe an exit. In a benchmark it should never happen, which is why the runner
        // asserts on it rather than letting it disappear into the failure rate.
        assertTrue(metrics.exitBeforeVideoStart)
        assertFalse(metrics.startupFailed)
    }

    @Test
    fun averageBitrateIsWeightedByThePlayingTimeEachSampleCarries() {
        val metrics = reduce(
            started(0),
            firstFrame(at = 0, ttffMs = 100),
            sample(at = 10_000, bitrateBps = 1_000_000, playing = true),
            sample(at = 20_000, bitrateBps = 3_000_000, playing = true),
            // Not playing: the position was not advancing, so no media time passed and this
            // rendition earns no weight. ref: docs/telemetry-schema.md, Bitrate — "across a pause,
            // and across a rebuffer, nothing accrues".
            sample(at = 30_000, bitrateBps = 9_000_000, playing = false),
            ended(40_000),
        )

        assertEquals(2_000_000.0, metrics.averageBitrateBps!!, 1e-9)
    }

    @Test
    fun aSessionWithNoPlayingSampleHasNoBitrateRatherThanZero() {
        val metrics = reduce(
            started(0),
            firstFrame(at = 0, ttffMs = 100),
            sample(at = 10_000, bitrateBps = null, playing = true),
            ended(20_000),
        )

        // A format that declares no bitrate yields null rather than a zero a dashboard would average
        // — the same rule `QoeCollector` applies when it reads the format.
        assertNull(metrics.averageBitrateBps)
    }

    @Test
    fun theInitialRenditionChoiceIsNotASwitch() {
        val metrics = reduce(
            started(0),
            firstFrame(at = 0, ttffMs = 100),
            switch(at = 100, from = null, to = 365_000, direction = TrackSwitchDirection.INITIAL),
            switch(at = 20_000, from = 365_000, to = 2_000_000, direction = TrackSwitchDirection.UP),
            switch(at = 30_000, from = 2_000_000, to = 730_000, direction = TrackSwitchDirection.DOWN),
            ended(40_000),
        )

        // ref: docs/telemetry-schema.md, Bitrate. Counting INITIAL would give every session that
        // played at all a switch it did not make, which matters most on the profiles that make the
        // fewest — exactly where a benchmark is looking for a difference.
        assertEquals(2, metrics.switchCount)
        assertEquals(1, metrics.upshiftCount)
        assertEquals(1, metrics.downshiftCount)
        // Magnitude, not just count: a ladder walked one rung at a time and one jumped end to end
        // are different experiences with the same count. `QoeScore` penalises this rather than the
        // count, and INITIAL contributes nothing because it has nowhere to have moved from.
        assertEquals((2_000_000L - 365_000L) + (2_000_000L - 730_000L), metrics.switchMagnitudeBpsSum)
    }

    @Test
    fun aSessionWithDroppedEventsIsNotFitToAggregate() {
        val metrics = reduce(
            started(0),
            firstFrame(at = 500, ttffMs = 500),
            ended(30_000, droppedEventCount = 4),
        )

        // ref: docs/telemetry-schema.md, The stream is lossy, and it says so — a session reporting a
        // non-zero count is excluded rather than averaged in, because a metric summed from a
        // partially dropped stream is a plausible wrong number that nobody audits.
        assertFalse(metrics.usable)
        assertEquals(4, metrics.droppedEventCount)
    }

    @Test
    fun aSessionThatNeverEndedIsNotFitToAggregate() {
        val metrics = reduce(started(0), firstFrame(at = 500, ttffMs = 500))

        // No `SessionEnded` means the player was never released, which in a benchmark is the run
        // going wrong rather than the content. It has no end timestamp, so it has no playing span.
        assertFalse(metrics.usable)
        assertEquals(0L, metrics.playingMs)
    }

    @Test
    fun eventsOfOtherSessionsAreIgnored() {
        val mine = reduce(
            started(0),
            firstFrame(at = 500, ttffMs = 500),
            // A concurrently playing player's stall, in the same list. A feed holds several players
            // and a pipeline receives them interleaved; `sessionId` is what separates them and
            // nothing else does.
            TelemetryEvent.RebufferEnded(
                sessionId = "someone-else",
                contentId = CONTENT,
                timestampMs = 0,
                monotonicTimeMs = 10_000,
                durationMs = 9_000,
                seekInduced = false,
            ),
            ended(30_000),
        )

        assertEquals(0L, mine.rebufferMs)
        assertEquals(0, mine.rebufferCount)
    }

    // --- Building event streams by hand -----------------------------------------------------------

    private fun reduce(vararg events: TelemetryEvent): SessionMetrics =
        SessionMetrics.from(SESSION, events.toList())

    private fun started(at: Long) = TelemetryEvent.SessionStarted(
        sessionId = SESSION,
        contentId = CONTENT,
        timestampMs = 0,
        monotonicTimeMs = at,
        profile = PlaybackProfile.VIDEO_ON_DEMAND,
        decision = PlaybackDecision(
            buffer = BufferPolicy(30_000, 60_000, 2_500, 5_000, 30_000, true),
            trackSelection = TrackSelectionPolicy(
                TrackSelectionPolicy.UNLIMITED,
                TrackSelectionPolicy.UNLIMITED,
            ),
        ),
    )

    private fun ended(at: Long, droppedEventCount: Int = 0) = TelemetryEvent.SessionEnded(
        sessionId = SESSION,
        contentId = CONTENT,
        timestampMs = 0,
        monotonicTimeMs = at,
        droppedEventCount = droppedEventCount,
    )

    private fun firstFrame(
        at: Long,
        ttffMs: Long,
        boundary: TtffStartBoundary = TtffStartBoundary.USER_INTENT,
    ) = TelemetryEvent.FirstFrameRendered(
        sessionId = SESSION,
        contentId = CONTENT,
        timestampMs = 0,
        monotonicTimeMs = at,
        timeToFirstFrameMs = ttffMs,
        startBoundary = boundary,
    )

    private fun rebuffer(at: Long, durationMs: Long, seekInduced: Boolean) = TelemetryEvent.RebufferEnded(
        sessionId = SESSION,
        contentId = CONTENT,
        timestampMs = 0,
        monotonicTimeMs = at,
        durationMs = durationMs,
        seekInduced = seekInduced,
    )

    private fun sample(at: Long, bitrateBps: Int?, playing: Boolean) = TelemetryEvent.PlaybackStateSampled(
        sessionId = SESSION,
        contentId = CONTENT,
        timestampMs = 0,
        monotonicTimeMs = at,
        samplingIntervalMs = SAMPLING_INTERVAL_MS,
        videoBitrateBps = bitrateBps,
        bufferedDurationMs = 20_000,
        playing = playing,
    )

    private fun switch(at: Long, from: Int?, to: Int, direction: TrackSwitchDirection) =
        TelemetryEvent.TrackSwitched(
            sessionId = SESSION,
            contentId = CONTENT,
            timestampMs = 0,
            monotonicTimeMs = at,
            fromBitrateBps = from,
            toBitrateBps = to,
            direction = direction,
        )

    private fun startupFailed(at: Long) = TelemetryEvent.StartupFailed(
        sessionId = SESSION,
        contentId = CONTENT,
        timestampMs = 0,
        monotonicTimeMs = at,
        failure = PlaybackFailure(FailureCategory.NETWORK, "ERROR_CODE_IO_BAD_HTTP_STATUS", "403"),
    )

    private companion object {
        const val SESSION = "session-under-test"
        const val CONTENT = "benchmark:ladder"

        // The schema's ten-second cadence (`docs/telemetry-schema.md`, *Bitrate*). A literal rather than
        // the collector's constant: the reducer weights by what each sample says, not by a cadence.
        const val SAMPLING_INTERVAL_MS = 10_000L
    }
}
