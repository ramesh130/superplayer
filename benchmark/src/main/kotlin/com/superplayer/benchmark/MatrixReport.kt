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
 * Everything one matrix run produced: the conditions it ran under, and every cell of it.
 *
 * The unit [ReportWriter] renders and the unit a later run is compared against. It holds cells for
 * *every* arm rather than pre-computed differences, so that a reader who disagrees with how this
 * project decides a difference is real can take the cells and decide differently — which is the same
 * reason `PRD.md` §6 requires the raw traces beside the summary.
 */
internal data class MatrixReport(
    val conditions: RunConditions,
    val cells: List<CellResult>,
) {

    /** The cell for one scenario, network and arm, or null when the matrix did not run it. */
    fun cell(scenario: Scenario, network: NetworkProfileName, arm: Arm): CellResult? =
        cells.firstOrNull { it.key.scenario == scenario && it.key.network == network && it.key.arm == arm }

    /**
     * Every metric of every cell, SuperPlayer against one stock arm.
     *
     * The list is produced whole and consumed whole. There is deliberately no parameter here for
     * which comparisons to include and no ordering by how favourable they are: `PRD.md` §6 requires
     * the neutral and worse cells to be published, and the way a report generator stops publishing
     * them is by growing an option that makes it easy not to.
     */
    fun comparisonsAgainst(baseline: Arm): List<CellComparison> = buildList {
        Scenario.entries.forEach { scenario ->
            NetworkProfileName.entries.forEach { network ->
                val stock = cell(scenario, network, baseline) ?: return@forEach
                val superPlayer = cell(scenario, network, Arm.SUPERPLAYER) ?: return@forEach
                add(CellComparison(scenario, network, baseline, stock, superPlayer))
            }
        }
    }
}

/** One cell's worth of SuperPlayer-against-a-baseline comparisons, one per reported metric. */
internal data class CellComparison(
    val scenario: Scenario,
    val network: NetworkProfileName,
    val baselineArm: Arm,
    val baseline: CellResult,
    val superPlayer: CellResult,
) {

    /**
     * The comparisons, in `PRD.md` §6's own order of reported metrics.
     *
     * Rebuffer ratio takes its **verdict** from the spread of per-session ratios and its **printed
     * value** from the pooled one, and the split is not a nicety. `docs/telemetry-schema.md`
     * requires a cell's ratio to be the sum of numerators over the sum of denominators — averaging
     * per-session ratios weights a five-second session like an hour-long one — but a pooled ratio is
     * one number, and one number cannot be told from run-to-run noise. So the distribution is
     * carried for the verdict and [displayBaseline]/[displaySuperPlayer] override what is printed.
     *
     * This was wrong first time round and is worth recording: the tables printed the distribution's
     * *mean*, under a heading and a paragraph both promising the pooled value. The report said one
     * thing and published the other, and the aggregation it published was the one the schema names
     * as forbidden.
     */
    val comparisons: List<Comparison> = listOf(
        Comparison(
            metric = "time to first frame (ms)",
            lowerIsBetter = true,
            baseline = baseline.timeToFirstFrameMs,
            superPlayer = superPlayer.timeToFirstFrameMs,
        ),
        Comparison(
            metric = "rebuffer ratio",
            lowerIsBetter = true,
            baseline = baseline.rebufferRatioSpread,
            superPlayer = superPlayer.rebufferRatioSpread,
            displayBaseline = baseline.rebufferRatio,
            displaySuperPlayer = superPlayer.rebufferRatio,
        ),
        Comparison(
            metric = "rebuffer count",
            lowerIsBetter = true,
            baseline = baseline.rebufferCount,
            superPlayer = superPlayer.rebufferCount,
        ),
        Comparison(
            metric = "average bitrate (bit/s)",
            lowerIsBetter = false,
            baseline = baseline.averageBitrateBps,
            superPlayer = superPlayer.averageBitrateBps,
        ),
        Comparison(
            metric = "switch count",
            lowerIsBetter = true,
            baseline = baseline.switchCount,
            superPlayer = superPlayer.switchCount,
        ),
        Comparison(
            metric = "QoE score",
            lowerIsBetter = false,
            baseline = baseline.qoeScore,
            superPlayer = superPlayer.qoeScore,
        ),
    )

    /** The comparisons where SuperPlayer came out worse. `PRD.md` §6's rule is about exactly these. */
    val losses: List<Comparison> = comparisons.filter { it.verdict == Comparison.Verdict.WORSE }

    /** The comparisons where the difference was inside the noise. */
    val neutrals: List<Comparison> = comparisons.filter { it.verdict == Comparison.Verdict.NEUTRAL }

    /** The comparisons where SuperPlayer came out ahead. */
    val wins: List<Comparison> = comparisons.filter { it.verdict == Comparison.Verdict.BETTER }
}
