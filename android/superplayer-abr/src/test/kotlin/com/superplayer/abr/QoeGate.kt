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

import com.superplayer.telemetry.QoeScore
import java.util.Locale
import kotlin.math.abs

/**
 * What the QoE regression gate judges and how it says so: committed floors in, one verdict per
 * trace out, and a report a reviewer can read as a diff.
 *
 * The score is not computed here. Every number arrives as a [QoeScore.Breakdown], which is
 * `superplayer-telemetry`'s one implementation of the objective, reduced by `SessionMetrics` from the
 * events a consumer's sink would see. This file only compares two of them.
 *
 * **Nothing here writes.** The gate reads its floors and returns a report; an improvement is printed
 * with the line that would record it, and stays unrecorded until somebody commits that line with a
 * reason. A gate that raised its own floor would let an unrelated improvement on one trace pay for a
 * regression that lands on it later, and nobody would see the trade. `docs/testing.md`, *The QoE
 * regression gate*, has the argument.
 */
internal object QoeGate {

    /** A committed floor: what the trace scored when the floor was set, and why it was set there. */
    data class Floor(val breakdown: QoeScore.Breakdown, val why: String)

    /** Worst first: the order a report prints in, so a failure is the first thing read. */
    enum class Verdict(val failsTheGate: Boolean, val words: String) {
        REGRESSED(true, "regressed past the margin"),

        /** Played, but no score: a startup failure, or a session with no playing time. */
        NO_SCORE(true, "no score"),

        /** Scored, but nothing committed to judge it against. */
        NO_FLOOR(true, "no committed floor"),
        WITHIN_MARGIN(false, "within the margin"),
        IMPROVED(false, "improved past the margin"),
    }

    data class Row(val trace: String, val floor: Floor?, val measured: QoeScore.Breakdown?, val verdict: Verdict)

    data class Report(val rows: List<Row>, val margin: Double) {
        val passed: Boolean get() = rows.none { it.verdict.failsTheGate }
    }

    /**
     * One verdict for every trace named by either side.
     *
     * A floor with no measurement and a measurement with no floor both fail: a trace dropped from the
     * gate, or added to it without a floor, is a gate that stopped judging something without saying so.
     */
    fun judge(floors: Map<String, Floor>, measured: Map<String, QoeScore.Breakdown?>, margin: Double): Report {
        val traces = (floors.keys + measured.keys).distinct()
        val rows = traces.map { trace ->
            val floor = floors[trace]
            val now = measured[trace]
            val verdict = when {
                now == null -> Verdict.NO_SCORE
                floor == null -> Verdict.NO_FLOOR
                now.score < floor.breakdown.score - margin -> Verdict.REGRESSED
                now.score > floor.breakdown.score + margin -> Verdict.IMPROVED
                else -> Verdict.WITHIN_MARGIN
            }
            Row(trace, floor, now, verdict)
        }
        return Report(rows.sortedBy { it.verdict.ordinal }, margin)
    }

    /**
     * The report, every trace in one table in one voice, worst first.
     *
     * There is no filter and no summary line that could be read instead of the table, for the
     * reason the benchmark's `ReportWriter` has none: a report that shows only what failed is one in
     * which a trace that won by trading stalls for bitrate never gets looked at. Each term is printed
     * floor → this run, so a regression reads as the term that moved.
     */
    fun render(report: Report): String = buildString {
        appendLine("QoE regression gate: ${if (report.passed) "passed" else "FAILED"} (margin ${number(report.margin)})")
        appendLine("Every term is Mbps-equivalent per second played, printed floor → this run.")
        appendLine()
        appendLine("| Trace | Verdict | Score | Δ score | Bitrate utility | Rebuffer penalty | Switch penalty |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        report.rows.forEach { row ->
            val floor = row.floor?.breakdown
            val now = row.measured
            val delta = if (floor != null && now != null) signed(now.score - floor.score) else "—"
            appendLine(
                "| ${row.trace} | ${row.verdict.words} " +
                    "| ${pair(floor?.score, now?.score)} | $delta " +
                    "| ${pair(floor?.bitrateUtility, now?.bitrateUtility)} " +
                    "| ${pair(floor?.rebufferPenalty, now?.rebufferPenalty)} " +
                    "| ${pair(floor?.switchPenalty, now?.switchPenalty)} |",
            )
        }
        report.rows.filter { it.verdict == Verdict.IMPROVED || it.verdict == Verdict.NO_FLOOR }.forEach { row ->
            val measured = row.measured ?: return@forEach
            appendLine()
            if (row.floor != null) {
                appendLine(
                    "${row.trace} scored past its floor of ${number(row.floor.breakdown.score)}, and the gate does " +
                        "not raise it. To raise it, commit this line to $FLOORS_PATH with the reason in its last column:",
                )
            } else {
                appendLine("${row.trace} has no floor. To give it one, commit this line to $FLOORS_PATH with the reason:")
            }
            appendLine(floorLine(row.trace, measured, why = "<why this floor, in the commit that sets it>"))
        }
        if (report.rows.any { it.verdict == Verdict.REGRESSED }) {
            appendLine()
            appendLine(
                "A regression is a behaviour change, not runner noise: the harness replays each trace on its " +
                    "own clock. Fix the change, or — if the loss is the intended price of something — lower the " +
                    "floor in the same commit and say why there. docs/testing.md, The QoE regression gate.",
            )
        }
    }

    /** One floor, in the committed format: tab-separated, the score beside the terms it is made of. */
    fun floorLine(trace: String, breakdown: QoeScore.Breakdown, why: String): String = listOf(
        trace,
        number(breakdown.score),
        number(breakdown.bitrateUtility),
        number(breakdown.rebufferPenalty),
        number(breakdown.switchPenalty),
        why,
    ).joinToString("\t")

    /**
     * The committed floors, keyed by trace.
     *
     * Refuses a floor with no reason, a trace listed twice, and a score that is not its terms — the
     * last because a hand edit to one column would otherwise judge every later run against a number
     * nobody measured.
     */
    fun parseFloors(text: String): Map<String, Floor> {
        val floors = linkedMapOf<String, Floor>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#") || line.startsWith("trace\t")) return@forEachIndexed
            val where = "$FLOORS_PATH line ${index + 1}"
            val columns = line.split('\t')
            require(columns.size == COLUMNS) { "$where has ${columns.size} tab-separated columns, not $COLUMNS" }
            val (trace, score, utility, rebuffer, switching) = columns
            val why = columns[COLUMNS - 1].trim()
            require(why.isNotEmpty()) { "$where has no reason: a floor is a decision, and records why it was made" }
            val breakdown = QoeScore.Breakdown(utility.toDouble(), rebuffer.toDouble(), switching.toDouble())
            require(abs(breakdown.score - score.toDouble()) <= ROUNDING_TOLERANCE) {
                "$where says score $score, but its terms make ${number(breakdown.score)}"
            }
            require(floors.put(trace, Floor(breakdown, why)) == null) { "$where lists $trace a second time" }
        }
        return floors
    }

    /** Where the floors live, relative to the module, which is the directory Gradle runs a test in. */
    const val FLOORS_PATH = "src/test/qoe-floors.tsv"

    private const val COLUMNS = 6

    /** Four figures each rounded to three decimals can disagree with their own sum by this much. */
    private const val ROUNDING_TOLERANCE = 0.002

    fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    /** Signed, with a difference that rounds to nothing printed as `+0.000` rather than `-0.000`. */
    private fun signed(value: Double) = String.format(Locale.ROOT, "%+.3f", value).replace("-0.000", "+0.000")

    private fun pair(floor: Double?, now: Double?) = "${floor?.let(::number) ?: "—"} → ${now?.let(::number) ?: "—"}"
}
