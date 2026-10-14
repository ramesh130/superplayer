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

/**
 * The standard QoE objective `PRD.md` Part 5 names: bitrate utility, less a rebuffer penalty, less a
 * switch penalty.
 *
 * ref: Yin, Jindal, Sekar and Sinopoli, *A Control-Theoretic Approach for Dynamic Adaptive Video
 * Streaming over HTTP*, SIGCOMM 2015 §2 — the objective is
 * `Σ q(R_k) − μ Σ T_k − Σ |q(R_{k+1}) − q(R_k)|` over the chunks of a session: the quality played,
 * less the time spent stalled, less how much the quality moved about. Mao, Netravali and Alizadeh,
 * *Neural Adaptive Video Streaming with Pensieve*, SIGCOMM 2017 §5, uses the same objective and is
 * where its linear instantiation `q(R) = R` comes from.
 *
 * ## One number, and why this project has one at all
 *
 * A benchmark row has seven columns and they trade against each other: a player that buffers deeper
 * stalls less and starts slower, and a player that caps quality stalls less and looks worse. Reading
 * seven columns and deciding which player won is a judgement, and a judgement made afresh each time
 * is a judgement that can be made to come out however the reader hoped. A stated objective is that
 * judgement written down once, in advance, where it can be argued with.
 *
 * **It does not replace the columns.** The benchmark's `ReportWriter` prints the components beside
 * the score, always, because the score's whole job is to be *decomposable* — an arm that wins on score by trading
 * bitrate for stalls has done something specific and a reader should be able to see which. `PRD.md`
 * §6's F1 rule is exactly this case: the cellular bitrate loss must appear *as a loss* next to the
 * rebuffer win, and a score that absorbed both into one figure would be the thing that rule forbids.
 *
 * ## Two stated departures from the literature
 *
 * **Per second of playing time, not per session.** The published form sums over chunks, so a longer
 * session scores higher; the sessions here differ in length precisely because some of them stalled,
 * which would make the summed form reward the arm that stalled *less* twice over. Every term below
 * is therefore divided by the session's playing time, which makes the score a rate in Mbps-equivalent
 * units and makes two sessions of different lengths comparable. The ordering within a cell is
 * unchanged when the sessions are the same length, which they nearly are.
 *
 * **Sampled quality, not per-chunk quality.** `q(R_k)` needs the rendition of every chunk; what this
 * project's vocabulary carries is `PlaybackStateSampled` at a stated cadence, so the utility term is
 * [SessionMetrics.averageBitrateBps], which is the same quantity at coarser resolution.
 * `docs/telemetry-schema.md` explains why the vocabulary is shaped that way, and the resolution is
 * identical for all three arms so no arm is advantaged by it.
 *
 * ## Whether this gates CI
 *
 * **It does, from Phase 3 on: `superplayer-abr`'s `QoeRegressionGateTest`, under `./gradlew check`.**
 * Issue #43 asked the question and this section used to answer "not yet", with three reasons — the
 * shipped policy was a static table with no adaptive behaviour to regress, a gate would cost every
 * pull request the full matrix, and it would fail on runner noise. Issue #102 answers all three:
 * Phase 3's adaptive policy is the thing to protect, the gate replays six traces three times each
 * rather than the matrix, and the harness's clock plus a median of those three makes what variation
 * remains a behaviour change rather than a slow runner. `docs/testing.md`, *The QoE regression gate*, is where the gate is described,
 * and its committed floors are in `superplayer-abr/src/test/qoe-floors.tsv`.
 *
 * ## Why this lives in `superplayer-telemetry`
 *
 * It was written in `benchmark/`, and the gate runs in the root build, which cannot see that one.
 * Copying it would give the benchmark and the gate two definitions of one score, which is the
 * failure [SessionMetrics] exists to prevent, so the reducer and the objective moved to the module
 * that owns the events' collector. Both builds now reach the same file: the gate as a project
 * dependency, the benchmark through the published artifact.
 */
public object QoeScore {

    /**
     * The score for one session, in Mbps-equivalent units per second of playing time.
     *
     * Null when the session has no playing time or no bitrate to speak of — a startup failure, or a
     * session shorter than one sampling interval. Null rather than zero, for the same reason the
     * benchmark's `Distribution` drops it: a failed session has no score, and a zero would sit in
     * the middle of the range and flatter it.
     */
    public fun of(metrics: SessionMetrics, ladderTopBitrateBps: Int): Double? =
        breakdown(metrics, ladderTopBitrateBps)?.score

    /**
     * The score and the three terms it is made of, or null exactly when [of] is.
     *
     * The terms are separate because the score is meant to be taken apart: a change that holds the
     * score by trading bitrate for stalls has done something specific, and a regression report that
     * printed only the total would hide which.
     */
    public fun breakdown(metrics: SessionMetrics, ladderTopBitrateBps: Int): Breakdown? {
        val bitrateBps = metrics.averageBitrateBps ?: return null
        val playingSeconds = metrics.playingMs / MS_PER_SECOND
        if (playingSeconds <= 0.0) return null

        return Breakdown(
            bitrateUtility = bitrateBps / BPS_PER_MBPS,
            rebufferPenalty = rebufferPenaltyPerSecond(ladderTopBitrateBps) *
                (metrics.rebufferMs / MS_PER_SECOND) / playingSeconds,
            switchPenalty = (metrics.switchMagnitudeBpsSum / BPS_PER_MBPS) / playingSeconds,
        )
    }

    /** One session's score, as its three terms. Every figure is in Mbps-equivalent per second played. */
    public data class Breakdown(
        /** `Σ q(R_k)`, per second played: the time-weighted average bitrate, in Mbps. */
        public val bitrateUtility: Double,
        /** `μ Σ T_k`, per second played. See [rebufferPenaltyPerSecond] for `μ`. */
        public val rebufferPenalty: Double,
        /** `Σ |q(R_{k+1}) − q(R_k)|`, per second played. */
        public val switchPenalty: Double,
    ) {
        /** The objective: utility, less both penalties. */
        public val score: Double get() = bitrateUtility - rebufferPenalty - switchPenalty
    }

    /**
     * `μ`: what one second of stalling costs, in Mbps.
     *
     * ref: Pensieve §5 instantiates `μ` at the **maximum bitrate of the ladder, expressed in Mbps**,
     * so that one second of rebuffering costs exactly as much as one second played at the top rung
     * is worth. That rule is what is implemented here rather than the particular number their ladder
     * produced, because the ladders in this benchmark are not theirs — a constant copied across
     * would silently change how harshly stalling is punished relative to the quality on offer, which
     * is the one thing `μ` is for.
     *
     * The rule is also the defensible reading of the objective: it makes the score's units mean
     * something — a player that stalls for a tenth of its playing time gives up a tenth of the top
     * rung — rather than making the trade a tuning constant this project chose.
     */
    public fun rebufferPenaltyPerSecond(ladderTopBitrateBps: Int): Double = ladderTopBitrateBps / BPS_PER_MBPS

    /** Bits per second in one megabit per second, as a double so the divisions do not truncate. */
    private const val BPS_PER_MBPS = 1_000_000.0

    private const val MS_PER_SECOND = 1_000.0
}
