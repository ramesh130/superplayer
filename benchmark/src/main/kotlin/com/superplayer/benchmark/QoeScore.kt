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
 * **It does not replace the columns.** [ReportWriter] prints the components beside the score, always,
 * because the score's whole job is to be *decomposable* — an arm that wins on score by trading
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
 * ## Whether this gates CI — the call issue #43 asks for, made
 *
 * **Not yet, and here is the reasoning rather than a preference.** A regression gate protects
 * something from getting worse. Today there is nothing to protect: `PlaybackPolicy`'s shipped
 * implementation is a static per-profile table that ignores the conditions it is handed, so there is
 * no adaptive behaviour a change could regress, and the only thing a gate would catch is somebody
 * editing `StaticProfilePolicy` — which is a four-line diff a reviewer can already see. Against that
 * it would cost every pull request the full matrix, and it would fail on runner noise, which is how
 * a gate stops being read.
 *
 * `PRD.md` Part 5 does say "regression-gate on that score in CI", and this is a decision about *when*
 * rather than a disagreement. The gate belongs with the thing it protects, which is Phase 3: ABR and
 * buffering are what will make this score move for reasons other than an edit, and Phase 3's own exit
 * criterion is already "measured improvement over the Phase-1 baseline with no regression on stable
 * WiFi" — a sentence that is a gate in prose. The baseline this harness commits is what that gate
 * will compare against, so building the score now and gating on it later is the order that leaves
 * Phase 3 with something to be graded on rather than an intuition to defend.
 */
internal object QoeScore {

    /**
     * The score for one session, in Mbps-equivalent units per second of playing time.
     *
     * Null when the session has no playing time or no bitrate to speak of — a startup failure, or a
     * session shorter than one sampling interval. Null rather than zero, for [Distribution.of]'s
     * reason: a failed session has no score, and a zero would sit in the middle of the range and
     * flatter it.
     */
    fun of(metrics: SessionMetrics, ladderTopBitrateBps: Int): Double? {
        val bitrateBps = metrics.averageBitrateBps ?: return null
        val playingSeconds = metrics.playingMs / MS_PER_SECOND
        if (playingSeconds <= 0.0) return null

        val utility = bitrateBps / BPS_PER_MBPS
        val rebufferPenalty = rebufferPenaltyPerSecond(ladderTopBitrateBps) *
            (metrics.rebufferMs / MS_PER_SECOND) / playingSeconds
        val switchPenalty = (metrics.switchMagnitudeBpsSum / BPS_PER_MBPS) / playingSeconds

        return utility - rebufferPenalty - switchPenalty
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
    fun rebufferPenaltyPerSecond(ladderTopBitrateBps: Int): Double = ladderTopBitrateBps / BPS_PER_MBPS

    /** Bits per second in one megabit per second, as a double so the divisions do not truncate. */
    private const val BPS_PER_MBPS = 1_000_000.0

    private const val MS_PER_SECOND = 1_000.0
}
