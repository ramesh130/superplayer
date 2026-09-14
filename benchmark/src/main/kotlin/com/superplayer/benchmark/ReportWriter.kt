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

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The matrix, as a document.
 *
 * ## The one design rule this file has
 *
 * **There is no "highlight the wins" mode, because there are no modes.** Issue #43 asks for that in
 * as many words, and the reason it is worth stating as a property of the code rather than as an
 * intention is that it is much harder to add later, once somebody has seen a chart they liked.
 *
 * Concretely: [write] takes a [MatrixReport] and nothing else. It has no parameter for which cells
 * to show, no threshold for what is worth mentioning, and no ordering by how well an arm did. Every
 * cell that ran is printed. The losses are printed **first**, before the wins, in the same words and
 * with the same precision — not because losses matter more, but because a summary section that
 * begins with wins is one a reader stops reading before the losses, and `PRD.md` §6 is explicit that
 * a benchmark table with no losses in it is a marketing document and will be read as one.
 *
 * Whether a difference *is* a win or a loss is not decided here either. [Comparison.verdict] decides
 * it, by one rule, in advance, for every metric of every cell, and [ExitCriterion] decides what those
 * verdicts add up to for the phase. This file renders what it is given.
 *
 * The one thing it adds is the order: the exit criterion first, because issue #103 makes met or not
 * met the report's opening paragraph — and a verdict that has to be found after the tables is one
 * a reader can miss.
 */
internal object ReportWriter {

    /** The report as Markdown. See the class KDoc for what this function deliberately cannot do. */
    fun write(report: MatrixReport): String = buildString {
        title()
        exitCriterion(report)
        conditions(report)
        howToReadThis(report)
        // Losses first. See the class KDoc — this ordering is the rule, not a layout preference.
        lossesAndNeutrals(report)
        wins(report)
        theF1Trade(report)
        fullTables(report)
        deviceArm()
        contentAndProvenance()
        rawTraces(report)
    }

    private fun StringBuilder.title() {
        appendLine("# SuperPlayer benchmark — Phase 3 report")
        appendLine()
        appendLine(
            "The fixed matrix of [`PRD.md`](../PRD.md) §6, run by `benchmark/`, with `superplayer-abr`'s " +
                "adaptive policy as the arm under test and the Phase 1 baseline's three players re-run " +
                "beside it. Every number here is produced by this harness against content this " +
                "repository generates or public streams it names (§0.2); none is carried in from " +
                "anywhere else, and none is copied from [the Phase 1 baseline](../baseline/baseline-report.md).",
        )
        appendLine()
    }

    private fun StringBuilder.exitCriterion(report: MatrixReport) {
        val judgement = ExitCriterion.judge(report)
        appendLine("## Exit criterion")
        appendLine()
        appendLine(
            "Phase 3's exit criterion is *measured improvement over the Phase 1 baseline on the " +
                "shaped-network suite, with no regression on stable WiFi*. It is judged against " +
                "**${ExitCriterion.REFERENCE.label}** — the static profile the Phase 1 baseline measured, " +
                "re-run here — by the two-standard-error rule below, and the rule was fixed before this " +
                "run (`ExitCriterion.kt`): on a shaped profile, a QoE score better in at least one " +
                "scenario and worse in none; on stable WiFi, no metric of any scenario worse.",
        )
        appendLine()
        if (judgement.met) {
            appendLine("**Met.** Every shaped profile improved on the static profile, and stable WiFi regressed nowhere.")
        } else {
            val failing = judgement.networks.filterNot { it.met }.joinToString { it.network.label }
            appendLine(
                "**Not met.** The criterion fails on: $failing. The cells that decided it are listed " +
                    "below, and each is a finding for the next phase rather than a reason to retune " +
                    "before committing this report.",
            )
        }
        appendLine()
        appendLine("| Network | Judged on | Verdict | QoE better in | Worse in |")
        appendLine("| --- | --- | --- | --- | --- |")
        judgement.networks.forEach { network ->
            val judgedOn = if (network.network == NetworkProfileName.STABLE_WIFI) "every metric" else "QoE score"
            val verdict = when {
                !network.measured -> "**not met** — not measured"
                network.met -> "met"
                else -> "**not met**"
            }
            val better = network.improvements.joinToString { (cell, _) -> cell.scenario.label }.ifEmpty { EM_DASH }
            val worse = (network.regressions + network.missing)
                .joinToString { (cell, comparison) -> "${cell.scenario.label}: ${comparison.metric} (${comparison.verdict.label})" }
                .ifEmpty { EM_DASH }
            appendLine("| ${network.network.label} | $judgedOn | $verdict | $better | $worse |")
        }
        appendLine()
        val deciding = judgement.networks.flatMap { it.regressions + it.missing }
        if (deciding.isNotEmpty()) {
            appendLine("The comparisons that failed it, ${ExitCriterion.CANDIDATE.label} against ${ExitCriterion.REFERENCE.label}:")
            appendLine()
            comparisonTable(deciding)
        }
    }

    private fun StringBuilder.conditions(report: MatrixReport) {
        val conditions = report.conditions
        appendLine("## What this was measured on")
        appendLine()
        appendLine("| | |")
        appendLine("| --- | --- |")
        appendLine("| SuperPlayer commit | `${conditions.commit}` |")
        appendLine("| SuperPlayer version | `${conditions.superPlayerVersion}` |")
        appendLine("| Media3 | `${conditions.media3Version}` |")
        appendLine("| Runs per cell | ${conditions.runsPerCell} |")
        appendLine("| Robolectric SDK | ${conditions.robolectricSdk} |")
        appendLine("| Java | ${conditions.javaVersion} |")
        appendLine("| Host | ${conditions.host} |")
        appendLine("| Started | ${conditions.startedAt} |")
        appendLine()
        if (conditions.treeDirty) {
            // Prose rather than a boolean in the table above, because a boolean in a table is a
            // thing a reader's eye passes over and this one invalidates the whole document as a
            // baseline: there is no commit anybody can check out that produces these numbers.
            appendLine(
                "> **The working tree had uncommitted changes when this ran.** These numbers describe " +
                    "a state of the repository that is not the commit above and that nobody else can " +
                    "reproduce. A report a phase is graded against must be re-run on a clean tree.",
            )
            appendLine()
        }
        if (conditions.runsPerCell < MINIMUM_RUNS) {
            appendLine(
                "> **This is a smoke run at ${conditions.runsPerCell} run(s) per cell, not a baseline.** " +
                    "`PRD.md` §6 requires at least $MINIMUM_RUNS runs per cell before a number is worth " +
                    "reporting, and the variance columns below are meaningless at this sample size. " +
                    "The full matrix is `benchmark/bench`.",
            )
            appendLine()
        }
    }

    private fun StringBuilder.howToReadThis(report: MatrixReport) {
        appendLine("## How to read this")
        appendLine()
        appendLine("**The four arms** (`PRD.md` §6, and `Arm.kt`):")
        appendLine()
        Arm.entries.forEach { arm ->
            val what = when (arm) {
                Arm.STOCK_DEFAULTS ->
                    "`ExoPlayer.Builder(context).build()` and nothing else. Media3's own defaults are " +
                        "${bufferPolicySummary(Arm.MEDIA3_DEFAULT_BUFFER_POLICY)}, and this arm is not " +
                        "built with them — it is built with nothing, so it tracks whatever they become."

                Arm.STOCK_NAIVE_TUNING ->
                    "the same, plus ${bufferPolicySummary(requireNotNull(arm.stockBufferPolicy))} — " +
                        "the buffer configuration an app writes when it has decided its player stalls " +
                        "too much and has not measured why."

                Arm.SUPERPLAYER ->
                    "`SuperPlayer.Builder(context).setProfile(…)`, with the profile each scenario " +
                        "names and its static policy: the player the Phase 1 baseline measured. What it " +
                        "was configured with is in the raw traces, on every `session_started` line."

                Arm.ADAPTIVE ->
                    "the same, plus `setPolicy(AdaptivePolicy.forProfile(context, profile))` from " +
                        "`superplayer-abr`'s published artifact — the arm this report grades. Every " +
                        "comparison below is this arm against one of the other three."
            }
            appendLine("- **${arm.label}** — $what")
        }
        appendLine()
        appendLine(
            "**Every adaptive session starts from a cold estimate.** `superplayer-abr` remembers a " +
                "throughput estimate per transport for the life of the process (ADR-0009 rule 8), and " +
                "the whole matrix is one process, so without care a cell's adaptive number would depend " +
                "on which cells ran before it. The runner cannot clear that memory — it is internal, " +
                "and this build is a consumer — so before each adaptive session it lets the harness " +
                "clock run past the age at which a remembered estimate is forgotten (ADR-0009 rule 9), " +
                "on an idle player. Each adaptive session is therefore a first session on its network, " +
                "which is also how `superplayer-abr`'s QoE regression gate plays one. A returning " +
                "viewer's warm estimate is not measured here.",
        )
        appendLine()
        appendLine(
            "**The metric definitions are [`docs/telemetry-schema.md`](../docs/telemetry-schema.md)**, " +
                "not this document and not the source. Every metric below is computed by one function, " +
                "`SessionMetrics`, from one event vocabulary, for all four arms — which is what makes " +
                "the columns comparable at all. Arms (c) and (d)'s events come from the shipped " +
                "`QoeCollector`; arms (a) and (b) have no SuperPlayer in them, so their events come from " +
                "`StockTelemetry`, which mirrors that collector callback for callback and is held to it " +
                "by `StockTelemetryAgreementTest`.",
        )
        appendLine()
        appendLine("Two things about the numbers themselves:")
        appendLine()
        appendLine(
            "- **Every mean carries its spread**, as `mean ± sd (p50, p95, n)`. `PRD.md` §6 requires " +
                "variance and not only means, and on a shaped network the tail is usually the " +
                "interesting part: a player that is usually fine and occasionally terrible has a good " +
                "mean.",
        )
        appendLine(
            "- **A difference smaller than the noise is reported as neutral, not as a small win.** The " +
                "rule is fixed in advance: a difference counts only if it exceeds two standard errors " +
                "of the difference of the means, which is the conventional rough 95% band. It is " +
                "applied identically to differences in both directions.",
        )
        appendLine()
        appendLine(
            "**Rebuffer ratio is pooled.** The value printed is the sum of every session's stalled " +
                "milliseconds over the sum of stalled-plus-playing, which is what the schema requires; " +
                "averaging per-session ratios would weight a short session like a long one. The " +
                "*verdict* beside it comes from the spread of the per-session ratios, because a pooled " +
                "ratio is one number and one number cannot be told from noise.",
        )
        appendLine()
        appendLine(
            "**What the spread is a spread of.** The network under this arm is a deterministic trace " +
                "replayed against a `FakeClock`, so the variance in these columns is **not** the " +
                "variance a real device on a real link would see. The harness holds every load to " +
                "its clock, so most sessions replay identically and many cells have no spread at " +
                "all; what spread remains is what run-to-run scheduling on the host still reaches, " +
                "because loads run on real threads even though playback time does not. A cell with " +
                "no spread makes any difference from another arm a verdict, however small, so read " +
                "the size of a difference beside its verdict and treat one of a step or two as the " +
                "harness's resolution rather than the player's. None of this estimates how variable " +
                "playback is in the field, and a p95 here should not be quoted as one. Field " +
                "variance is the device arm's to measure.",
        )
        appendLine()
        appendLine(
            "**Time to first frame is quantised to the harness's step.** The Robolectric arm advances " +
                "a `FakeClock` in fixed 50 ms steps (`PlaybackHarness.WAIT_STEP_MS`) while it waits " +
                "for the player to become ready, so a measurement lands on a multiple of that step " +
                "and two arms differing by less than one step read as identical. That puts a floor " +
                "on the difference this column can resolve, and a row of exactly equal start-up " +
                "times across the arms is that floor rather than players agreeing to the " +
                "millisecond. The harness lets the engine finish everything due at one moment " +
                "before either clock moves, so a first frame is stamped with the step in which it " +
                "was rendered and never with the next one. Within a step the engine acts before the " +
                "loads, though, so each finished load reaches the engine one step later than a " +
                "free-running player would hear it. Start-up therefore carries up to a step of " +
                "delay per load it waits on. The delay is the same run after run, but an arm that " +
                "waits on more loads before its first frame carries more of it, so a start-up " +
                "difference of a step or two between arms is within what the harness itself adds.",
        )
        appendLine()
        // The arrivals below are pinned by ThroughputTraceTest; if that test moves, so does this text.
        appendLine(
            "**On congested WiFi, time to first frame is the starting rung's first chunk.** The " +
                "profile's first second carries 4.37 Mbit/s with no round trip, so a two-second chunk " +
                "at 730 kbit/s arrives at 334 ms and reads 400, one at 2 Mbit/s arrives at 915 ms and " +
                "reads 1000, and one at 4.5 Mbit/s arrives at 2786 ms and reads 2850 " +
                "(`ThroughputTraceTest` pins the arrivals; issue #134). A column of 1000s is therefore " +
                "every arm starting on 2 Mbit/s, not a sample edge the harness imposed: the column " +
                "separates arms by the rung they start on and by nothing finer, and two arms that " +
                "start on the same rung read identically on this profile whatever else differs. The " +
                "arrivals are computed from the trace rather than observed, since a trace here carries " +
                "no load events, and they account for every reading in the congested WiFi cells: the " +
                "stock defaults' one 2850 is its first run, which starts on 4.5 Mbit/s before Media3's " +
                "process-wide meter has a sample, and a data-saver arm reads 400 because its cap starts " +
                "it on 730 kbit/s. A time-to-first-frame verdict on this profile is therefore a " +
                "verdict on the starting rung.",
        )
        appendLine()
        appendLine(
            "**Bitrate is sampled at ten seconds**, which is the cadence `PlaybackStateSampled` carries " +
                "and therefore the resolution of any time-weighted average taken from it. A rendition " +
                "held for less than one interval can fall between samples. This is identical for all " +
                "arms, so it moves no comparison, but it does mean the bitrate column is coarser " +
                "than the switch column.",
        )
        appendLine()
        appendLine(
            "**${report.cells.sumOf { it.excludedSessions }} session(s) were excluded** across the whole " +
                "matrix, out of ${report.cells.sumOf { it.runs + it.excludedSessions }} run. A session " +
                "is excluded when its event stream was incomplete — `SessionEnded.droppedEventCount` " +
                "non-zero, which the schema says makes a summed metric a plausible wrong number — or " +
                "when it produced no `SessionEnded` at all. Nothing is excluded for being slow, for " +
                "being an outlier, or for spoiling a trend.",
        )
        appendLine()
    }

    /** Every arm the candidate is compared against, in `Arm.kt`'s order: stock first, the static profile last. */
    private val references: List<Arm> get() = Arm.entries.filter { it != ExitCriterion.CANDIDATE }

    private fun StringBuilder.lossesAndNeutrals(report: MatrixReport) {
        appendLine("## Where the adaptive policy is worse, or no better")
        appendLine()
        appendLine(
            "This section comes before the wins deliberately. `PRD.md` §6: *publish the cases where " +
                "SuperPlayer is neutral or worse — a benchmark table with no losses in it is a " +
                "marketing document and will be read as one.* A loss against " +
                "${Arm.STOCK_NAIVE_TUNING.label} is stated as plainly as any other: it is a cell where " +
                "configuring by intuition did as well, and a finding for the next phase.",
        )
        appendLine()

        references.forEach { baselineArm ->
            val comparisons = report.comparisonsAgainst(baselineArm)
            val losses = comparisons.flatMap { cell -> cell.losses.map { cell to it } }
            val neutrals = comparisons.flatMap { cell -> cell.neutrals.map { cell to it } }

            appendLine("### Against ${baselineArm.label}")
            appendLine()
            if (comparisons.isEmpty()) {
                appendLine("_No cells ran for this comparison._")
                appendLine()
                return@forEach
            }

            val total = comparisons.sumOf { it.comparisons.size }
            val noData = comparisons.flatMap { cell -> cell.comparisons.filter { !it.hasData }.map { cell to it } }
            appendLine("**Worse (${losses.size} of $total comparisons):**")
            appendLine()
            if (losses.isEmpty()) {
                // Said in words rather than left as an empty section, because an empty section reads
                // as an oversight and this one is a finding — and a finding a reader should be
                // suspicious of, which is why the sentence says so rather than celebrating.
                appendLine(
                    "_None._ Treat that with suspicion rather than satisfaction: at this sample size " +
                        "and on generated content, no losses at all is as likely to mean the matrix is " +
                        "not exercising the trade as it is to mean there is not one.",
                )
                appendLine()
            } else {
                comparisonTable(losses)
            }

            appendLine("**Neutral — inside the noise (${neutrals.size}):**")
            appendLine()
            if (neutrals.isEmpty()) {
                appendLine("_None._")
                appendLine()
            } else {
                comparisonTable(neutrals)
            }

            // Printed rather than left out, so the three counts account for every comparison. A
            // comparison with no data is usually a cell where one arm failed to start every run,
            // which is a finding; three of them vanishing from all three sections would leave the
            // numbers not summing and look like an omission.
            appendLine("**No data — one arm produced no measurement (${noData.size}):**")
            appendLine()
            if (noData.isEmpty()) {
                appendLine("_None._")
                appendLine()
            } else {
                comparisonTable(noData)
            }

            check(losses.size + neutrals.size + wins(comparisons).size + noData.size == total) {
                "The summary sections do not account for every comparison of $baselineArm"
            }
        }
    }

    /** The winning comparisons of [comparisons]; see [wins] for where they are printed. */
    private fun wins(comparisons: List<CellComparison>): List<Comparison> =
        comparisons.flatMap { it.wins }

    private fun StringBuilder.wins(report: MatrixReport) {
        appendLine("## Where the adaptive policy is better")
        appendLine()
        references.forEach { baselineArm ->
            val comparisons = report.comparisonsAgainst(baselineArm)
            val wins = comparisons.flatMap { cell -> cell.wins.map { cell to it } }
            appendLine("### Against ${baselineArm.label}")
            appendLine()
            if (wins.isEmpty()) {
                appendLine("_None._")
                appendLine()
            } else {
                comparisonTable(wins)
            }
        }
    }

    /**
     * One table of comparisons, in the same shape whatever the verdict.
     *
     * The same renderer for wins, losses and neutrals, which is the mechanical half of "states
     * losses in the same voice as wins": there is no second, gentler table for the bad news.
     */
    private fun StringBuilder.comparisonTable(rows: List<Pair<CellComparison, Comparison>>) {
        val first = rows.first().first
        appendLine("| Scenario | Network | Metric | ${first.baselineArm.label} | ${first.candidateArm.label} | Change |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        rows.forEach { (cell, comparison) ->
            appendLine(
                "| ${cell.scenario.label} | ${cell.network.label} | ${comparison.metric} " +
                    "| ${value(comparison.metric, comparison.baseline, comparison.displayBaseline)} " +
                    "| ${value(comparison.metric, comparison.superPlayer, comparison.displaySuperPlayer)} " +
                    "| ${change(comparison)} |",
            )
        }
        appendLine()
    }

    private fun StringBuilder.theF1Trade(report: MatrixReport) {
        appendLine("## The F1 trade, as a loss")
        appendLine()
        appendLine(
            "`PRD.md` F1's trade is *lower bitrate on a constrained link, in exchange for fewer stalls*, " +
                "and §6 requires it to appear **as a bitrate loss beside the rebuffer win** rather than " +
                "as a rebuffer win on its own. In the Phase 1 baseline the only instance was " +
                "`DATA_SAVER`'s static cap. The adaptive policy makes the trade on purpose — " +
                "`TransportCaps` narrows the eligible ladder on cellular, and the estimate discounts " +
                "a rung by its spread — so it is shown here on every cellular cell, " +
                "${ExitCriterion.CANDIDATE.label} against ${ExitCriterion.REFERENCE.label}, with the " +
                "bitrate row and the stall rows side by side and each verdict by the same rule as " +
                "everywhere else. A bitrate cut with no stall win beside it is a trade that bought " +
                "nothing, and reads that way below.",
        )
        appendLine()

        val cellular = listOf(
            NetworkProfileName.THREE_G,
            NetworkProfileName.LTE_WITH_DROPOUTS,
            NetworkProfileName.WIFI_TO_CELLULAR_HANDOVER,
        )
        val traded = setOf("average bitrate (bit/s)", "rebuffer ratio", "rebuffer count", ExitCriterion.QOE_METRIC)
        val rows = report.comparisonsAgainst(ExitCriterion.REFERENCE)
            .filter { it.network in cellular }
            .sortedBy { cellular.indexOf(it.network) }
            .flatMap { cell -> cell.comparisons.filter { it.metric in traded }.map { cell to it } }
        if (rows.isEmpty()) {
            // Said rather than left as an empty table. This section is here to satisfy a rule about
            // not hiding a loss, so a run that did not measure the cells the rule is about has to say
            // so — an empty table under that heading reads as "there was no trade".
            appendLine(
                "_This run measured no cellular cell for both arms, so the trade is not shown. A " +
                    "filtered run (`--cells`) is the usual reason; a full matrix always measures it._",
            )
            appendLine()
            return
        }
        comparisonTable(rows)
    }

    private fun StringBuilder.fullTables(report: MatrixReport) {
        appendLine("## Every cell")
        appendLine()
        appendLine(
            "All of it, whatever it says. Peak RSS and battery are the device arm's and are not in " +
                "these tables; see below.",
        )
        appendLine()
        Scenario.entries.forEach { scenario ->
            appendLine("### ${scenario.label}")
            appendLine()
            appendLine(
                "Profile for arms (c) and (d): `${scenario.profile}`. Ladder: " +
                    "${scenario.ladderBitratesBps.joinToString(", ") { "${it / 1000} kbit/s" }}. " +
                    "Session length: ${scenario.playbackMs / 1000} s.",
            )
            appendLine()
            appendLine(
                "| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count " +
                    "| Bitrate (bit/s) | Switches | Startup failures | QoE score |",
            )
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            NetworkProfileName.entries.forEach { network ->
                Arm.entries.forEach { arm ->
                    val cell = report.cell(scenario, network, arm) ?: return@forEach
                    appendLine(
                        "| ${network.label} | ${arm.label} | ${runsColumn(cell)} " +
                            "| ${ttff(cell.timeToFirstFrameMs)} " +
                            "| ${ratio(cell.rebufferRatio)} " +
                            "| ${plain(cell.rebufferCount)} " +
                            "| ${bitrate(cell.averageBitrateBps)} " +
                            "| ${plain(cell.switchCount)} " +
                            "| ${percentage(cell.startupFailureRate)} " +
                            "| ${plain(cell.qoeScore)} |",
                    )
                }
            }
            appendLine()
        }
    }

    private fun StringBuilder.deviceArm() {
        appendLine("## Peak RSS and battery — the device arm")
        appendLine()
        appendLine(
            "`PRD.md` §6 reports peak RSS and battery delta over a 30-minute session, and **neither is " +
                "in the tables above**. They cannot be: both are properties of a process on a device " +
                "with a battery, and everything above runs under Robolectric on a JVM. Reporting a heap " +
                "figure from a JVM as though it were an Android app's resident set would be a plausible " +
                "wrong number, which is the failure mode this whole harness is arranged against.",
        )
        appendLine()
        appendLine(
            "The device arm is `benchmark/`'s own app, `BenchmarkActivity`: given an arm, one of the " +
                "public streams below and a duration, it plays a 30-minute session on a real device " +
                "over a real network and writes its telemetry in the same trace format as the rows " +
                "above. It measures neither number itself — an app measuring its own memory would be " +
                "measuring the measurement — and both are read off the process from outside, by " +
                "Perfetto's `process_memory` and `battery` data sources.",
        )
        appendLine()
        appendLine(
            "**The harness around that app is not built yet.** `devicelab/` is this repository's entry " +
                "point for device measurement and is currently shaped around the demo; wiring a second " +
                "app through it is issue #95. `benchmark/README.md` has the manual `adb` " +
                "recipe in the meantime, and says why a benchmark sits outside `docs/testing.md`'s " +
                "no-device rule rather than against it.",
        )
        appendLine()
        appendLine(
            "**These columns are unpopulated in this report.** A device run has not been taken " +
                "against this commit, and a table of dashes is the honest way to say so — the " +
                "alternative being to leave the metrics out of the document and let a reader assume " +
                "the matrix covered them.",
        )
        appendLine()
        appendLine("| Metric | Arm (a) | Arm (b) | Arm (c) | Arm (d) |")
        appendLine("| --- | --- | --- | --- | --- |")
        appendLine("| Peak RSS (MiB) | — | — | — | — |")
        appendLine("| Battery delta over 30 min (%) | — | — | — | — |")
        appendLine()
    }

    private fun StringBuilder.contentAndProvenance() {
        appendLine("## The content, and where it comes from")
        appendLine()
        appendLine(
            "**The Robolectric arm plays generated content**, not fetched streams: rendition ladders " +
                "synthesized by `superplayer-testkit`'s `PlaybackHarness` over Media3's own adaptive " +
                "fakes, at the bitrates each scenario names. That is the arm that has to be " +
                "reproducible, and a stream fetched over the internet is the one thing that cannot be — " +
                "the same reasoning `docs/testing.md` gives for barring the network from the test suite. " +
                "The ladders follow Apple's HLS Authoring Specification in shape; the exact rungs are " +
                "chosen against `PRD.md` §6's network profiles, and `Scenario.kt` argues them. The " +
                "scenarios, the streams and the network profiles are unchanged from the Phase 1 " +
                "baseline, so the two reports describe one matrix.",
        )
        appendLine()
        appendLine("**The device arm plays public streams**, each listed with the page that publishes it:")
        appendLine()
        appendLine("| Content | Protocol | URI | Source |")
        appendLine("| --- | --- | --- | --- |")
        PublicStreams.streams.forEach { stream ->
            appendLine("| ${stream.label} | ${stream.protocol} | `${stream.uri}` | ${stream.source} |")
        }
        appendLine()
        appendLine("### Cells this matrix does not cover")
        appendLine()
        appendLine(
            "Written down rather than left out. An uncovered cell that is absent from a table reads as " +
                "a cell that passed.",
        )
        appendLine()
        PublicStreams.gaps.forEach { gap ->
            appendLine("**${gap.cell}**")
            appendLine()
            appendLine(gap.why)
            appendLine()
            appendLine("_Closed by:_ ${gap.closedBy}")
            appendLine()
        }
    }

    private fun StringBuilder.rawTraces(report: MatrixReport) {
        appendLine("## Raw traces")
        appendLine()
        appendLine(
            "`PRD.md` §6 requires them published, because a summary is an argument and a trace is " +
                "evidence: every number above can be recomputed from these files, and a statistic this " +
                "report did not think to print can be taken from them.",
        )
        appendLine()
        appendLine(
            "One JSONL file per cell in `traces/`, one line per telemetry event, field names as " +
                "`LogcatSink` writes them. Each line carries its scenario, network, arm and run index, " +
                "so files concatenate without losing what they were. Arms (a) to (c) keep the Phase 1 " +
                "baseline's file names, so `diff` against `../baseline/traces/` compares a cell with itself.",
        )
        appendLine()
        appendLine("```text")
        appendLine("grep '\"evt\":\"rebuffer_ended\"' traces/*.jsonl                  # every stall in the matrix")
        appendLine("grep '\"run\":3' traces/vod__3g__superplayer-adaptive.jsonl       # one session, end to end")
        appendLine("```")
        appendLine()
        appendLine("${report.cells.size} cell(s), ${report.cells.sumOf { it.runs }} aggregated session(s).")
        appendLine()
    }

    // --- Formatting -------------------------------------------------------------------------------
    //
    // Every one of these renders "no measurement" as an em dash rather than as a zero. A cell where
    // every run failed has no time to first frame, and a zero there would be the best-looking number
    // in the column.

    private fun runsColumn(cell: CellResult): String =
        if (cell.excludedSessions == 0) "${cell.runs}" else "${cell.runs} (+${cell.excludedSessions} excluded)"

    private fun ttff(distribution: Distribution?): String = distribution?.let {
        "${it.p50.roundToLong()} / ${it.p95.roundToLong()} (mean ${it.mean.roundToLong()} ± ${
            it.standardDeviation.roundToLong()
        }, n=${it.count})"
    } ?: EM_DASH

    private fun plain(distribution: Distribution?): String =
        distribution?.summary { "%.2f".format(it) } ?: EM_DASH

    private fun bitrate(distribution: Distribution?): String =
        distribution?.summary { it.roundToLong().toString() } ?: EM_DASH

    private fun ratio(value: Double?): String = value?.let { "%.4f".format(it) } ?: EM_DASH

    private fun percentage(value: Double?): String = value?.let { "%.1f%%".format(it * 100) } ?: EM_DASH

    /**
     * A comparison's value column, formatted the way that metric is formatted elsewhere.
     *
     * [display] wins when it is set, which is how rebuffer ratio prints the pooled value while its
     * verdict comes from the distribution behind it. See `CellComparison.comparisons`.
     */
    private fun value(metric: String, distribution: Distribution?, display: Double?): String = when {
        display != null -> ratio(display)
        distribution == null -> EM_DASH
        metric.contains("bit/s") -> bitrate(distribution)
        metric.contains("(ms)") -> distribution.summary { it.roundToLong().toString() }
        else -> plain(distribution)
    }

    /**
     * The change column: the signed difference, its relative size, and the verdict.
     *
     * The sign is always the raw arithmetic one — candidate minus reference — rather than "an
     * improvement of". A column that flipped its sign according to whether lower was better would
     * make every row read as a positive number, which is precisely the presentation `PRD.md` §6 is
     * written against. The verdict word is what says which direction is good.
     */
    private fun change(comparison: Comparison): String {
        val delta = comparison.delta ?: return "${EM_DASH} (${comparison.verdict.label})"
        val relative = comparison.relativeDelta
        val sign = if (delta >= 0) "+" else "−"
        val magnitude = if (abs(delta) >= 100) abs(delta).roundToLong().toString() else "%.3f".format(abs(delta))
        val percent = relative?.let { " (%s%.1f%%)".format(if (it >= 0) "+" else "−", abs(it) * 100) } ?: ""
        return "$sign$magnitude$percent — **${comparison.verdict.label}**"
    }

    private fun bufferPolicySummary(policy: com.superplayer.core.BufferPolicy): String =
        "${policy.minBufferMs / 1000} s/${policy.maxBufferMs / 1000} s of buffer, " +
            "${policy.bufferForPlaybackMs / 1000.0} s before playback starts and " +
            "${policy.bufferForPlaybackAfterRebufferMs / 1000.0} s after a rebuffer"

    private const val EM_DASH = "—"

    /** `PRD.md` §6's floor: fewer than this and the run is a smoke test rather than a baseline. */
    const val MINIMUM_RUNS = 20
}
