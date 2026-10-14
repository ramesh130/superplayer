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

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A sample of runs, summarised — and never summarised to one number.
 *
 * `PRD.md` §6: **report variance, and not only means.** That is a rule about what a table may
 * contain, so it is enforced by the type rather than by the person writing the table: there is no
 * way to get a mean out of this class without also having the spread that came with it, and
 * [ReportWriter] prints them together because it has no other option.
 *
 * A distribution of twenty runs whose mean is 900 ms and whose p95 is 4 000 ms describes a player
 * that is usually fine and occasionally terrible, and a mean alone is exactly the summary that hides
 * it. On a shaped network that is the interesting case rather than the rare one.
 */
internal data class Distribution(
    /** How many runs contributed. `PRD.md` §6 requires at least 20 before a number is reported. */
    val count: Int,
    val mean: Double,
    /** The sample standard deviation; see [of] for why it is the sample one. */
    val standardDeviation: Double,
    val min: Double,
    val p50: Double,
    val p95: Double,
    val max: Double,
) {

    /** The values, formatted for a report cell: the mean, its spread, and the tail. */
    fun summary(format: (Double) -> String): String =
        "${format(mean)} ± ${format(standardDeviation)} (p50 ${format(p50)}, p95 ${format(p95)}, n=$count)"

    companion object {

        /**
         * Summarises [values], or null when there are none to summarise.
         *
         * Null rather than a distribution of zeroes, deliberately: a cell where every run failed to
         * produce a time to first frame has *no* time to first frame, and a zero there would be the
         * best-looking number in the column. The report prints "—" and says how many runs it had.
         */
        fun of(values: List<Double>): Distribution? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mean = sorted.average()
            // The *sample* standard deviation (n−1), because these runs are a sample of the runs
            // this configuration could produce rather than the whole population of them — which is
            // the entire premise of running twenty and reporting a spread. With n=1 there is no
            // spread to estimate and the honest answer is zero rather than a division by zero.
            // ref: NIST/SEMATECH e-Handbook of Statistical Methods §1.3.5.6, sample standard
            // deviation. https://www.itl.nist.gov/div898/handbook/
            val variance =
                if (sorted.size < 2) 0.0 else sorted.sumOf { (it - mean) * (it - mean) } / (sorted.size - 1)
            return Distribution(
                count = sorted.size,
                mean = mean,
                standardDeviation = sqrt(variance),
                min = sorted.first(),
                p50 = percentile(sorted, 50.0),
                p95 = percentile(sorted, 95.0),
                max = sorted.last(),
            )
        }

        /**
         * The [percentile]th percentile of an already-sorted [sorted], by the nearest-rank method.
         *
         * ref: NIST/SEMATECH e-Handbook of Statistical Methods §7.2.5.2 — the nearest-rank
         * percentile is the value at ordinal `ceil(p/100 × n)`.
         * https://www.itl.nist.gov/div898/handbook/
         *
         * Nearest rank rather than an interpolating definition, and the choice is worth stating
         * because percentile definitions differ and two harnesses quoting "p95" under different ones
         * are quoting different numbers — which is `PRD.md` F8 arriving inside this benchmark. The
         * nearest rank always returns a value that actually occurred, which is what makes a p95
         * startup time something a reader can go and find in the raw traces.
         */
        fun percentile(sorted: List<Double>, percentile: Double): Double {
            require(sorted.isNotEmpty()) { "No values to take a percentile of" }
            val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
