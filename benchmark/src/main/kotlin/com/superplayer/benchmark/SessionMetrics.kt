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

package com.superplayer.benchmark

import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.core.TtffStartBoundary
import kotlin.math.abs

/**
 * What one session was, as the numbers `PRD.md` §6 reports — computed from a [TelemetryEvent] stream
 * and from nothing else.
 *
 * **This is the one implementation of the metric definitions, and every arm goes through it.** That
 * is the structural answer to the thing issue #43 calls the single most likely way this harness ends
 * up lying: a comparison in which the two sides measure differently is not a comparison. This
 * function cannot tell which arm produced the events it is given, so the rebuffer denominator, the
 * seek exclusion and the bitrate weighting are shared by construction rather than by care.
 * `StockTelemetry` and `QoeCollector` are the two *producers* of that vocabulary, and
 * `StockTelemetryAgreementTest` is what holds them to each other.
 *
 * **The definitions are `docs/telemetry-schema.md`, not this file**, exactly as they are not
 * `TelemetryEvent.kt`. Every metric below cites the section it implements, and where this file has
 * to make a choice the document leaves open — because a benchmark needs a number and a schema
 * describes an event stream — it says so with the reason.
 */
internal data class SessionMetrics(

    /** The session these numbers are of. */
    val sessionId: String,

    /**
     * Milliseconds from the start boundary to the first rendered frame, or null when no frame ever
     * rendered — a startup failure, or an exit before video start.
     *
     * ref: `docs/telemetry-schema.md`, *Time to first frame*. Taken from the event rather than
     * recomputed: the boundary is settled when the session opens and the collector is the only thing
     * that knows which boundary applied.
     */
    val timeToFirstFrameMs: Long?,

    /**
     * Which boundary [timeToFirstFrameMs] was measured from, or null when there is no measurement.
     *
     * Carried so a report can refuse to mix the two. The schema is explicit that `CONTENT_ADOPTED`
     * reads lower than `USER_INTENT` and that the two must not be aggregated together; the benchmark
     * declares intent on every arm, so every cell should be `USER_INTENT` — and a cell that is not
     * is a defect in the runner rather than a number to publish.
     */
    val startBoundary: TtffStartBoundary?,

    /**
     * Milliseconds of involuntary stall, **excluding seek-induced stalls**.
     *
     * ref: `docs/telemetry-schema.md`, *Rebuffering*. The numerator of [rebufferRatio].
     */
    val rebufferMs: Long,

    /**
     * How many such stalls there were. `PRD.md` §6 reports the count as well as the ratio, because
     * one long stall and six short ones are different experiences with the same ratio.
     */
    val rebufferCount: Int,

    /**
     * Milliseconds during which the playback position was advancing.
     *
     * ref: `docs/telemetry-schema.md`, *Rebuffering* — "wall-clock time in the session during which
     * the playback position was advancing". Derived here as the span from the first rendered frame
     * to the end of the session, less **every** stall in it, seek-induced ones included: the
     * position does not advance during a seek's wait either, and leaving it in the denominator would
     * make a session's ratio depend on how much it seeked.
     *
     * *The one precondition this derivation has*, stated because it is invisible otherwise: it
     * assumes the session was never paused and never ran past the end of its content, since both
     * stop the position advancing without producing a stall. The benchmark's runner does neither —
     * it plays a fixed span of content strictly shorter than the asset — and a harness that later
     * grew a pause would have to account for it here rather than discover the overstatement in a
     * report.
     */
    val playingMs: Long,

    /**
     * The declared peak bitrate the session played, weighted by the time it played it.
     *
     * ref: `docs/telemetry-schema.md`, *Bitrate*. Computed from `PlaybackStateSampled`, which is
     * what the schema designates for exactly this: bitrate distribution is a time-weighted quantity,
     * and the sample carries its own `samplingIntervalMs` so that a consumer weights by what the
     * event says rather than by a cadence copied out of a document. Only samples where `playing` is
     * true contribute, which is the schema's "across a pause, and across a rebuffer, nothing
     * accrues" falling out of the definition rather than being a special case.
     *
     * **Sampled, therefore granular.** The schema weights by media time; at playback speed 1.0 a
     * playing sample's interval *is* media time, so the two agree, but the resolution is the
     * sampling cadence — a rendition held for less than one interval can fall between samples. That
     * is a property of the vocabulary rather than of this benchmark, it is identical for all three
     * arms, and the report states it where the bitrate column is defined.
     *
     * Null when the session produced no playing sample carrying a bitrate: a session shorter than
     * one sampling interval, or one that never chose a video rendition.
     */
    val averageBitrateBps: Double?,

    /**
     * How many times the video rendition changed after the initial choice.
     *
     * ref: `docs/telemetry-schema.md`, *Bitrate*. `INITIAL` is not a switch — there was nothing to
     * switch from — and counting it would give every session that played at all a switch it did not
     * make, which matters most on the profiles that make the fewest.
     */
    val switchCount: Int,

    /** Of [switchCount], how many went up. `PRD.md` §3.4 asks for the two directions separately. */
    val upshiftCount: Int,

    /** Of [switchCount], how many went down. See [upshiftCount]. */
    val downshiftCount: Int,

    /**
     * The total size of the rendition changes, in bits per second summed over every switch.
     *
     * A count says how often quality moved; this says how far. The QoE objective `PRD.md` Part 5
     * names penalises the magnitude rather than the count — see [QoeScore] — because a ladder walked
     * one rung at a time and a ladder jumped end to end are different experiences with the same
     * switch count, and the second is the one a viewer notices.
     *
     * `INITIAL` contributes nothing: it has no `fromBitrateBps` to have moved from.
     */
    val switchMagnitudeBpsSum: Long,

    /**
     * Whether playback failed before the first frame.
     *
     * ref: `docs/telemetry-schema.md`, *Video start failure and mid-stream failure*. The rate
     * `PRD.md` §6 reports is this over the sessions of a cell, which is the standard's shape:
     * start failures over sessions that attempted playback.
     */
    val startupFailed: Boolean,

    /** Whether playback failed after the first frame. See [startupFailed] for the split. */
    val midStreamFailed: Boolean,

    /**
     * Whether the session ended having produced neither a frame nor a startup failure.
     *
     * ref: `docs/telemetry-schema.md`, *Exit before video start* — derived, not reported, because
     * the library cannot observe an exit. In a benchmark this should never happen: the runner plays
     * every session to a fixed span and never abandons one. A cell reporting any is reporting a
     * defect in the runner, which is why it is surfaced rather than folded into the failure rate.
     */
    val exitBeforeVideoStart: Boolean,

    /**
     * How many of this session's events the delivery path discarded under pressure.
     *
     * ref: `docs/telemetry-schema.md`, *The stream is lossy, and it says so* — a session reporting a
     * non-zero count should be **excluded** from aggregates rather than averaged in, because a
     * metric summed from a partially-dropped stream is a plausible wrong number that nobody audits.
     * [usable] is that rule; the runner counts what it excluded and the report prints it.
     */
    val droppedEventCount: Int,

    /**
     * Whether the session produced a `SessionEnded` at all.
     *
     * A session that did not is one whose player was never released, which in a benchmark means the
     * run itself went wrong rather than that the content did. It has no end timestamp, so it has no
     * playing span and no ratio, and [usable] excludes it.
     */
    val ended: Boolean,
) {

    /**
     * The rebuffer ratio, or null for a session that never started playing.
     *
     * ref: `docs/telemetry-schema.md`, *Rebuffering*:
     * `rebufferRatio = totalRebufferMs / (totalRebufferMs + totalPlayingMs)`.
     *
     * **Per session, and not the thing to average across sessions.** The schema is explicit: a cell's
     * ratio is the sum of numerators over the sum of denominators, never the mean of per-session
     * ratios, because the second weights a five-second session equally with a two-hour one.
     * [CellResult] aggregates it that way; this value exists for a single session's row in a raw
     * trace.
     */
    val rebufferRatio: Double?
        get() {
            val denominator = rebufferMs + playingMs
            return if (denominator <= 0) null else rebufferMs.toDouble() / denominator
        }

    /**
     * Whether this session may be aggregated at all.
     *
     * Two ways it may not, and both are the schema's rather than this benchmark's judgement: an
     * incomplete event stream ([droppedEventCount] above), and a session that produced no
     * `SessionEnded` — one whose player was never released, which in a benchmark means the run
     * itself went wrong.
     */
    val usable: Boolean get() = droppedEventCount == 0 && ended

    companion object {

        /**
         * Reduces one session's events to its metrics.
         *
         * [events] is every event of one session, in the order the sink received them. Events of
         * other sessions are ignored rather than rejected, so a caller holding one list for a
         * process of concurrent players can pass it whole — though the benchmark plays one session
         * at a time and does not need that.
         */
        fun from(sessionId: String, events: List<TelemetryEvent>): SessionMetrics {
            val own = events.filter { it.sessionId == sessionId }
            val firstFrame = own.filterIsInstance<TelemetryEvent.FirstFrameRendered>().firstOrNull()
            val ended = own.filterIsInstance<TelemetryEvent.SessionEnded>().lastOrNull()
            val switches = own.filterIsInstance<TelemetryEvent.TrackSwitched>()
            val stalls = own.filterIsInstance<TelemetryEvent.RebufferEnded>()

            // Only the stalls the schema counts. The seek-induced ones are excluded here and
            // subtracted from the playing span below, which is the two halves of the same rule:
            // a viewer who drags a scrub bar expects a wait, and it is neither a rebuffer nor time
            // the position was advancing.
            val countedStalls = stalls.filter { !it.seekInduced }

            return SessionMetrics(
                sessionId = sessionId,
                timeToFirstFrameMs = firstFrame?.timeToFirstFrameMs,
                startBoundary = firstFrame?.startBoundary,
                rebufferMs = countedStalls.sumOf { it.durationMs },
                rebufferCount = countedStalls.size,
                playingMs = playingMs(firstFrame, ended, stalls),
                averageBitrateBps = timeWeightedBitrateBps(own),
                switchCount = switches.count { it.direction != TrackSwitchDirection.INITIAL },
                upshiftCount = switches.count { it.direction == TrackSwitchDirection.UP },
                downshiftCount = switches.count { it.direction == TrackSwitchDirection.DOWN },
                switchMagnitudeBpsSum = switches.sumOf { switch ->
                    val from = switch.fromBitrateBps ?: return@sumOf 0L
                    abs(switch.toBitrateBps.toLong() - from.toLong())
                },
                startupFailed = own.any { it is TelemetryEvent.StartupFailed },
                midStreamFailed = own.any { it is TelemetryEvent.MidStreamFailed },
                exitBeforeVideoStart = ended != null &&
                    firstFrame == null &&
                    own.none { it is TelemetryEvent.StartupFailed },
                droppedEventCount = ended?.droppedEventCount ?: 0,
                ended = ended != null,
            )
        }

        /** See [SessionMetrics.playingMs], which is where this derivation is argued. */
        private fun playingMs(
            firstFrame: TelemetryEvent.FirstFrameRendered?,
            ended: TelemetryEvent.SessionEnded?,
            stalls: List<TelemetryEvent.RebufferEnded>,
        ): Long {
            if (firstFrame == null || ended == null) return 0
            val span = ended.monotonicTimeMs - firstFrame.monotonicTimeMs
            // Every stall, seek-induced included: none of it is time the position advanced.
            return (span - stalls.sumOf { it.durationMs }).coerceAtLeast(0)
        }

        /** See [SessionMetrics.averageBitrateBps], which is where this derivation is argued. */
        private fun timeWeightedBitrateBps(own: List<TelemetryEvent>): Double? {
            var weightedSum = 0.0
            var weight = 0L
            own.filterIsInstance<TelemetryEvent.PlaybackStateSampled>().forEach { sample ->
                val bitrate = sample.videoBitrateBps ?: return@forEach
                if (!sample.playing) return@forEach
                weightedSum += bitrate.toDouble() * sample.samplingIntervalMs
                weight += sample.samplingIntervalMs
            }
            return if (weight == 0L) null else weightedSum / weight
        }
    }
}
