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

import kotlin.math.sqrt

/** Which cell of the matrix a result belongs to: one scenario, one network, one arm. */
internal data class CellKey(
    val scenario: Scenario,
    val network: NetworkProfileName,
    val arm: Arm,
) {
    override fun toString(): String = "${scenario.label} / ${network.label} / ${arm.label}"
}

/**
 * The six network profiles of `PRD.md` §6, named without naming `superplayer-testkit`.
 *
 * The enum in testkit is the one that carries the traces and their citations, and it is where those
 * belong. This one exists because the device arm and the report generator live in `src/main`, which
 * cannot see a test-only dependency, and because a report's row heading should not change if the
 * testkit enum is renamed. `BenchmarkMatrixTest` is the one place the two are mapped onto each
 * other, and it maps every entry of this enum or fails — so a profile added to `PRD.md` §6 and to
 * testkit cannot be quietly missing from the matrix.
 */
internal enum class NetworkProfileName(val label: String, val description: String) {
    STABLE_WIFI("stable WiFi", "20 Mbit/s, never varying"),
    CONGESTED_WIFI("congested WiFi", "3 Mbit/s mean, ±50% second to second"),
    LTE_WITH_DROPOUTS("LTE with dropouts", "5 Mbit/s, out for 2 s every 20 s"),
    THREE_G("3G", "1 Mbit/s"),
    WIFI_TO_CELLULAR_HANDOVER("WiFi→cellular", "20 Mbit/s WiFi, then 5 Mbit/s LTE at 10 s"),
    HIGH_LATENCY("high latency", "20 Mbit/s at a 600 ms round trip"),
}

/**
 * One cell of the matrix: every run of one arm, one scenario and one network, summarised.
 *
 * `PRD.md` §6's honesty rules land here rather than in the report writer, which is deliberate. A
 * rule enforced where a table is *printed* can be got round by printing a different table; a rule
 * enforced where the numbers are *made* cannot. So this class has no mean without a spread on it
 * ([Distribution]), aggregates a ratio the way the schema says a ratio is aggregated, and counts
 * what it threw away instead of quietly thinning the sample.
 */
internal data class CellResult(
    val key: CellKey,

    /** Every session of this cell that was fit to aggregate. See [excludedSessions]. */
    val sessions: List<SessionMetrics>,

    /**
     * Sessions this cell measured and then discarded, and why there is a count rather than a
     * silence.
     *
     * ref: `docs/telemetry-schema.md`, *The stream is lossy, and it says so* — a session whose event
     * stream was partially dropped must be excluded rather than averaged in, because a metric summed
     * from one is a plausible wrong number that nobody audits. Discarding is therefore correct; not
     * *saying* how many were discarded is not, because a cell that threw away nineteen of twenty
     * runs and reported the twentieth would otherwise look exactly like a cell that ran cleanly.
     * The report prints this, and prints it loudly when it is not zero.
     */
    val excludedSessions: Int,
) {

    /** How many runs this cell reports on. `PRD.md` §6 requires at least 20 before it means much. */
    val runs: Int get() = sessions.size

    /** ref: `docs/telemetry-schema.md`, *Time to first frame*. p50 and p95 are `PRD.md` §6's ask. */
    val timeToFirstFrameMs: Distribution? =
        Distribution.of(sessions.mapNotNull { it.timeToFirstFrameMs?.toDouble() })

    /**
     * The cell's rebuffer ratio: **the sum of the numerators over the sum of the denominators.**
     *
     * ref: `docs/telemetry-schema.md`, *Rebuffering* — "aggregate across sessions by summing
     * numerators and denominators, never by averaging per-session ratios", because the second
     * weights a five-second session equally with a two-hour one. That is the headline number, and it
     * is a single value rather than a distribution because a pooled ratio has no spread of its own.
     * [rebufferRatioSpread] is what carries the variance `PRD.md` §6 requires alongside it.
     */
    val rebufferRatio: Double? = run {
        val stalled = sessions.sumOf { it.rebufferMs }
        val playing = sessions.sumOf { it.playingMs }
        if (stalled + playing <= 0) null else stalled.toDouble() / (stalled + playing)
    }

    /**
     * How the per-session ratios were spread, which is **not** the cell's ratio.
     *
     * Reported beside [rebufferRatio] and never instead of it. The mean of this distribution is the
     * average of per-session ratios, which is precisely the aggregation the schema forbids — so it
     * is here to answer "was this cell consistently mediocre or occasionally awful", which is a real
     * question that a pooled ratio cannot answer, and the report labels it as spread rather than as
     * the ratio.
     */
    val rebufferRatioSpread: Distribution? = Distribution.of(sessions.mapNotNull { it.rebufferRatio })

    /** ref: `PRD.md` §6 reports the count as well as the ratio. */
    val rebufferCount: Distribution? = Distribution.of(sessions.map { it.rebufferCount.toDouble() })

    /** ref: `docs/telemetry-schema.md`, *Bitrate* — time-weighted, and the units are bits/second. */
    val averageBitrateBps: Distribution? = Distribution.of(sessions.mapNotNull { it.averageBitrateBps })

    /** ref: `docs/telemetry-schema.md`, *Bitrate* — the initial choice is not a switch. */
    val switchCount: Distribution? = Distribution.of(sessions.map { it.switchCount.toDouble() })

    /**
     * Startup failures over sessions that attempted playback.
     *
     * ref: `docs/telemetry-schema.md`, *Video start failure and mid-stream failure* — "the rates are
     * computed by the pipeline, per the standard's shape: start failures over sessions that
     * attempted playback". Every session in a cell attempted playback, so the denominator is [runs].
     */
    val startupFailureRate: Double? = if (runs == 0) null else sessions.count { it.startupFailed }.toDouble() / runs

    /** Mid-stream failures over sessions that started playing. See [startupFailureRate]. */
    val midStreamFailureRate: Double? = run {
        val started = sessions.count { it.timeToFirstFrameMs != null }
        if (started == 0) null else sessions.count { it.midStreamFailed }.toDouble() / started
    }

    /**
     * Sessions that ended with no frame and no failure — see [SessionMetrics.exitBeforeVideoStart].
     *
     * Should always be zero here, and is surfaced rather than folded away because a non-zero value
     * is a defect in the runner rather than a property of the player.
     */
    val exitBeforeVideoStart: Int = sessions.count { it.exitBeforeVideoStart }

    /** The QoE objective of `PRD.md` Part 5, per session. See [QoeScore] for the form and the units. */
    val qoeScore: Distribution? =
        Distribution.of(sessions.mapNotNull { QoeScore.of(it, key.scenario.ladderTopBitrateBps) })

    companion object {

        /**
         * Builds a cell from every session it ran, keeping the ones fit to aggregate.
         *
         * The split is [SessionMetrics.usable], which is the schema's rule and not this benchmark's
         * judgement about what looks like an outlier. Nothing here drops a session for being slow,
         * for being an outlier, or for spoiling a trend — only for having an event stream that
         * cannot be trusted to be complete. A benchmark that trimmed its own tails would be
         * measuring its author.
         */
        fun of(key: CellKey, all: List<SessionMetrics>): CellResult {
            val (usable, unusable) = all.partition { it.usable }
            return CellResult(key = key, sessions = usable, excludedSessions = unusable.size)
        }
    }
}

/**
 * One metric, one cell, two arms: what the difference was and whether it was one at all.
 *
 * This type exists because of `PRD.md` §6's hardest rule — **publish the cases where SuperPlayer is
 * neutral or worse** — and because that rule is not satisfied by intending to. A report generator
 * that decided cell by cell which differences were worth mentioning would drift toward mentioning
 * the flattering ones, so the decision is made here, by one rule, for every metric of every cell,
 * before anything is printed. [ReportWriter] renders whatever this produces and cannot filter it:
 * there is no "highlight the wins" mode because there is no mode at all.
 */
internal data class Comparison(
    val metric: String,
    /** True when a *smaller* number is the better one — a stall, a startup time. */
    val lowerIsBetter: Boolean,
    val baseline: Distribution?,
    val superPlayer: Distribution?,
) {

    /** The signed difference of the means, in the metric's own units, or null if either is missing. */
    val delta: Double? =
        if (baseline == null || superPlayer == null) null else superPlayer.mean - baseline.mean

    /** The difference as a fraction of the baseline, or null when the baseline is zero or missing. */
    val relativeDelta: Double? = run {
        val difference = delta ?: return@run null
        val from = baseline?.mean ?: return@run null
        if (from == 0.0) null else difference / from
    }

    /**
     * Two standard errors of the difference of the means — the band inside which a difference is not
     * distinguishable from run-to-run noise.
     *
     * ref: the standard error of a difference of two independent means is
     * `sqrt(sd_a²/n_a + sd_b²/n_b)`; two of them is the conventional rough 95% interval.
     * NIST/SEMATECH e-Handbook of Statistical Methods §7.3.1, comparing two means.
     * https://www.itl.nist.gov/div898/handbook/
     *
     * Deliberately the *rough* form rather than a Welch t-test: the point is not to publish a
     * p-value, it is to have a rule fixed in advance that decides which differences a report is
     * allowed to call differences. A rule that is slightly conservative in both directions serves
     * that; one that is chosen after seeing the numbers does not, however sophisticated.
     */
    val noiseBand: Double? = run {
        val a = baseline ?: return@run null
        val b = superPlayer ?: return@run null
        if (a.count < 2 || b.count < 2) return@run null
        2.0 * sqrt(
            (a.standardDeviation * a.standardDeviation) / a.count +
                (b.standardDeviation * b.standardDeviation) / b.count,
        )
    }

    /** What this comparison is, in one word. See [Verdict]. */
    val verdict: Verdict = run {
        val difference = delta
        when {
            difference == null -> Verdict.NO_DATA

            // A difference smaller than the noise is not a difference. Reported as neutral rather
            // than as a small win, which is the direction this rule exists to stop.
            noiseBand != null && kotlin.math.abs(difference) <= noiseBand -> Verdict.NEUTRAL

            difference == 0.0 -> Verdict.NEUTRAL

            (difference < 0) == lowerIsBetter -> Verdict.BETTER

            else -> Verdict.WORSE
        }
    }

    /** How SuperPlayer did against the baseline on this metric, in this cell. */
    enum class Verdict(val label: String) {
        BETTER("better"),
        WORSE("worse"),
        NEUTRAL("neutral"),

        /** One of the two arms produced no measurement at all — a column of failures, usually. */
        NO_DATA("no data"),
    }
}
