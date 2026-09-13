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

package com.superplayer.abr

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The last few throughput samples taken on one transport, and the three readings a
 * `ThroughputEstimate` takes of them: the level, the scatter, and a value the network delivered
 * most of the time.
 *
 * One distribution, three readings — rather than an EWMA for the level and a window beside it for
 * the rest. Two estimators of the same samples can disagree, and "quality drops randomly" is what a
 * selector and a load control reading different numbers looks like (#99); a mean and a percentile
 * of the *same* window cannot disagree about what was measured, only about how to summarise it.
 * Media3's own meter keeps a weighted window and reports its median, holding the distribution and
 * exposing none of it; this exposes it, because the spread is what predicts a stall (ADR-0009,
 * context section — the argument is there, and `PlaybackConditions.throughput` cites it).
 *
 * Every reading is integer arithmetic on bits per second, rounded down, so a value a test computes
 * by hand is the value this returns. Not thread-safe: [EstimateMemory] guards it.
 */
internal class SampleWindow(private val capacity: Int = WINDOW_SAMPLES) {

    /** Oldest first. */
    private val samples = ArrayDeque<Sample>()

    init {
        require(capacity >= 1) { "A window holds at least one sample, not $capacity" }
    }

    val size: Int
        get() = samples.size

    val isEmpty: Boolean
        get() = samples.isEmpty()

    /** When the newest sample was taken, or null for an empty window. */
    val newestAtMs: Long?
        get() = samples.lastOrNull()?.atMs

    /** Adds a sample of [bps] taken at [atMs], dropping the oldest once the window is full. */
    fun add(bps: Long, atMs: Long) {
        require(bps >= 0) { "A throughput sample cannot be negative, was $bps" }
        samples.addLast(Sample(bps, atMs))
        if (samples.size > capacity) samples.removeFirst()
    }

    fun clear() {
        samples.clear()
    }

    /** The arithmetic mean of the window, rounded down. */
    fun meanBps(): Long {
        check(samples.isNotEmpty()) { "An empty window has no mean" }
        return samples.sumOf { it.bps } / samples.size
    }

    /**
     * The population standard deviation of the window, rounded down — how far the samples scatter
     * around the mean, in the same unit as the mean so a policy can compare the two directly.
     *
     * Population rather than sample deviation: the window *is* the set being described, not a
     * sample of a larger one whose deviation is being inferred, and the population form is the one a
     * reader can check by hand against eight numbers.
     */
    fun spreadBps(): Long {
        check(samples.isNotEmpty()) { "An empty window has no spread" }
        val mean = samples.sumOf { it.bps }.toDouble() / samples.size
        val variance = samples.sumOf { (it.bps - mean) * (it.bps - mean) } / samples.size
        return sqrt(variance).toLong()
    }

    /**
     * The [percent]th percentile of the window by the nearest-rank method: the samples sorted
     * ascending, and the one at rank `ceil(percent / 100 × n)`. No interpolation, so the value is
     * always one the network actually delivered.
     */
    fun percentileBps(percent: Int): Long {
        check(samples.isNotEmpty()) { "An empty window has no percentile" }
        require(percent in 1..100) { "A percentile is between 1 and 100, not $percent" }
        val sorted = samples.map { it.bps }.sorted()
        val rank = ceil(percent / 100.0 * sorted.size).toInt()
        return sorted[rank - 1]
    }

    private class Sample(val bps: Long, val atMs: Long)

    companion object {
        /**
         * How many samples the window holds.
         *
         * Eight, which at one sample per 2-second segment is the last sixteen seconds of transfer.
         * ref: Mao, Netravali and Alizadeh, *Neural Adaptive Video Streaming with Pensieve*,
         * SIGCOMM 2017, §4 — the past eight chunks' throughput is the network state the learned
         * policy is given, and the number is chosen there as long enough to see the variability and
         * short enough to track a change. Yin et al. (SIGCOMM 2015, §5.1) use five for their
         * harmonic-mean predictor; eight is preferred here because the *spread* is a reading of this
         * window too, and a standard deviation of five samples is a noisier fact than one of eight.
         * Larger would lag a handover by more segments than the reseed (ADR-0009 rule 9) already
         * saves.
         */
        const val WINDOW_SAMPLES: Int = 8

        /**
         * The percentile `ThroughputEstimate.conservativeBps` reports.
         *
         * The 25th: with the nearest-rank method over eight samples that is the second-lowest sample,
         * a throughput the network delivered in three transfers out of four. It is deliberately not
         * the minimum — one dropout would then pin the conservative estimate to zero until it aged
         * out of the window — and deliberately below Media3's median, which is the *typical* network
         * and therefore the one that stalls a viewer half the time it is trusted. ref: Yin, Jindal,
         * Sekar and Sinopoli, SIGCOMM 2015, §5.1 — a robust adaptation discounts its prediction by
         * the prediction's error; a lower percentile of the recent samples is that discount taken
         * from the samples themselves rather than from a separate error estimate.
         */
        const val CONSERVATIVE_PERCENTILE: Int = 25
    }
}
