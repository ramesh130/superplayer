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
 * Phase 11's exit criterion for the adaptive policy, decided by one rule fixed before the matrix ran.
 *
 * `PRD.md`'s phase table: **measured improvement of the adaptive policy over the static profile on
 * the shaped-network suite, with no regression on stable WiFi.** Issue #103 wrote it as Phase 3's;
 * the roadmap has since moved it to Phase 11, tuning, so that no functional phase waits on a number
 * moving. Every report from Phase 3 on still opens with the verdict, because a report that measures
 * the policy should say where it stands. The words need a rule before they are a
 * criterion, and writing it down after seeing the numbers would be choosing it, so it is here:
 *
 * - **Against what.** The static SuperPlayer profile, [Arm.SUPERPLAYER] — the player the Phase 1
 *   baseline measured, re-run under the same conditions — with [Arm.ADAPTIVE] as the candidate. Not
 *   against stock: beating stock is Phase 1's claim, and the module's claim is beating the profile.
 * - **Improvement**, on each shaped profile, is a QoE score better than the static profile's by more
 *   than [Comparison]'s two standard errors in at least one scenario, and worse by more than them in
 *   none. The QoE objective is `PRD.md` Part 5's single number for "better", so it is the direction.
 * - **No regression**, on stable WiFi, is no metric of any scenario worse by more than two standard
 *   errors — every metric, not the score alone, because a stable link is where an adaptive policy
 *   has nothing to gain and so where any cost it adds is a cost.
 * - **Unmeasured is not met.** A profile no cell ran for, or a judged comparison one arm produced no
 *   data for, cannot pass: absence of evidence is not a pass.
 *
 * The phase is met only when every profile is. [ReportWriter] prints the result as the report's
 * opening section and names the cells that decided it.
 */
internal object ExitCriterion {

    /** The arm the candidate must improve on: the static profile the Phase 1 baseline measured. */
    val REFERENCE: Arm = Arm.SUPERPLAYER

    /** The arm being graded. */
    val CANDIDATE: Arm = Arm.ADAPTIVE

    /** The one metric that decides improvement on a shaped profile. See the class KDoc. */
    const val QOE_METRIC: String = "QoE score"

    fun judge(report: MatrixReport): Judgement {
        val comparisons = report.comparisonsAgainst(REFERENCE, CANDIDATE)
        return Judgement(
            NetworkProfileName.entries.map { network ->
                val cells = comparisons.filter { it.network == network }
                val judged = if (network == NetworkProfileName.STABLE_WIFI) {
                    cells.flatMap { cell -> cell.comparisons.map { cell to it } }
                } else {
                    cells.flatMap { cell -> cell.comparisons.filter { it.metric == QOE_METRIC }.map { cell to it } }
                }
                val regressions = judged.filter { (_, c) -> c.verdict == Comparison.Verdict.WORSE }
                val improvements = judged.filter { (_, c) -> c.verdict == Comparison.Verdict.BETTER && c.metric == QOE_METRIC }
                val missing = judged.filter { (_, c) -> !c.hasData }
                val measured = cells.isNotEmpty()
                NetworkJudgement(
                    network = network,
                    measured = measured,
                    improvements = improvements,
                    regressions = regressions,
                    missing = missing,
                    met = measured &&
                        regressions.isEmpty() &&
                        missing.isEmpty() &&
                        (network == NetworkProfileName.STABLE_WIFI || improvements.isNotEmpty()),
                )
            },
        )
    }

    /** The criterion over every network profile. */
    internal data class Judgement(val networks: List<NetworkJudgement>) {
        val met: Boolean get() = networks.all { it.met }

        fun network(network: NetworkProfileName): NetworkJudgement = networks.single { it.network == network }
    }

    /** The criterion on one network profile, with the comparisons that decided it. */
    internal data class NetworkJudgement(
        val network: NetworkProfileName,
        val measured: Boolean,
        val improvements: List<Pair<CellComparison, Comparison>>,
        val regressions: List<Pair<CellComparison, Comparison>>,
        val missing: List<Pair<CellComparison, Comparison>>,
        val met: Boolean,
    )
}
