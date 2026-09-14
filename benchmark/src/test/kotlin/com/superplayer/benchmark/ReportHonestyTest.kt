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

import com.superplayer.core.TtffStartBoundary
import com.superplayer.telemetry.SessionMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PRD.md` §6's honesty rules, as assertions.
 *
 * The rules are the part of a benchmark most likely to erode, because eroding them always looks like
 * an improvement to the presentation: a losses section that is empty gets folded away, a difference
 * that is within the noise gets called a small win, a cell with too few runs gets reported like any
 * other. §6 anticipates this — *a benchmark table with no losses in it is a marketing document and
 * will be read as one* — so the rules are held here rather than remembered.
 *
 * What is asserted is that a **losing** report says so, loudly and in the same words a winning one
 * would use. The fixture is therefore built to lose: the adaptive arm worse on one metric, better on
 * another, and inside the noise on a third, so that each verdict has something to be about.
 */
class ReportHonestyTest {

    @Test
    fun aLossIsStatedBeforeAnyWin() {
        val markdown = ReportWriter.write(report())

        val losses = markdown.indexOf(LOSSES_HEADING)
        val wins = markdown.indexOf(WINS_HEADING)
        assertTrue("The report has no losses section", losses >= 0)
        assertTrue("The report has no wins section", wins >= 0)
        // Ordering, because a summary that begins with wins is one a reader stops reading before the
        // losses. This is the mechanical form of "states losses as prominently as wins".
        assertTrue("The wins are printed before the losses", losses < wins)
    }

    @Test
    fun aLossAppearsInTheReportWithItsSizeAndTheWordWorse() {
        val markdown = ReportWriter.write(report())

        val lossesSection = markdown.substringAfter(LOSSES_HEADING).substringBefore(WINS_HEADING)
        // The metric that regressed, named, in the losses section, with the verdict spelled out. A
        // report that quietly omitted it would still contain the number in the full tables further
        // down, which is exactly the kind of technically-complete presentation the rule is against.
        assertTrue(
            "The losses section does not name the regressed metric:\n$lossesSection",
            lossesSection.contains("time to first frame"),
        )
        assertTrue("The losses section does not say 'worse'", lossesSection.contains("**worse**"))
    }

    @Test
    fun theAdaptiveArmIsComparedAgainstTheStaticProfileAndNotOnlyAgainstStock() {
        val lossesSection = ReportWriter.write(report()).substringAfter(LOSSES_HEADING).substringBefore(WINS_HEADING)

        // Issue #103: beating stock is not the claim `superplayer-abr` makes. Beating the static
        // SuperPlayer profile is, so that comparison has a section of its own like the other two.
        Arm.entries.filter { it != Arm.ADAPTIVE }.forEach { reference ->
            assertTrue("No comparison against ${reference.label}", lossesSection.contains("### Against ${reference.label}"))
        }
    }

    @Test
    fun theExitCriterionIsTheReportsOpeningSection() {
        val markdown = ReportWriter.write(report())

        val verdict = markdown.indexOf("## Exit criterion")
        assertTrue("The report states no exit criterion", verdict >= 0)
        // Issue #103: met or not met is the report's opening paragraph, not a conclusion a reader
        // reaches after the tables — so it sits above the conditions, the reading notes and the losses.
        assertTrue(verdict < markdown.indexOf("## What this was measured on"))
        assertTrue(verdict < markdown.indexOf(LOSSES_HEADING))
    }

    @Test
    fun aRegressionOnStableWifiFailsTheExitCriterionWhateverElseImproved() {
        // The fixture's adaptive arm starts slower than the static profile: a loss outside the noise.
        val report = report(network = NetworkProfileName.STABLE_WIFI)

        val judgement = ExitCriterion.judge(report)
        val stable = judgement.network(NetworkProfileName.STABLE_WIFI)

        assertFalse("A time-to-first-frame regression on stable WiFi was judged met", stable.met)
        assertTrue(stable.regressions.any { (_, comparison) -> comparison.metric == "time to first frame (ms)" })
        assertFalse("One network not met, and the phase was judged met", judgement.met)
        assertTrue(ReportWriter.write(report).contains("**Not met.**"))
    }

    @Test
    fun aQoeWinOnAShapedProfileWithNoQoeLossMeetsThatProfilesCriterion() {
        // Same start-up, fewer stalls: the adaptive arm's QoE score is better than the static
        // profile's by more than the noise, which is what "improvement" means in the criterion.
        val report = report(network = NetworkProfileName.THREE_G, candidateStartsSlower = false)

        val threeG = ExitCriterion.judge(report).network(NetworkProfileName.THREE_G)

        assertTrue("A QoE win on 3G with no QoE loss was judged not met: $threeG", threeG.met)
        assertTrue(threeG.regressions.isEmpty())
    }

    @Test
    fun aNetworkTheRunDidNotMeasureIsNotMet() {
        // Absence of evidence is not a pass. A filtered run that skipped a profile must not report
        // the phase as having met its criterion on it.
        val judgement = ExitCriterion.judge(report(network = NetworkProfileName.THREE_G))

        val unmeasured = judgement.network(NetworkProfileName.HIGH_LATENCY)
        assertFalse(unmeasured.measured)
        assertFalse(unmeasured.met)
        assertFalse(judgement.met)
    }

    @Test
    fun theF1TradeIsPrintedAsABitrateLossOnACellularCell() {
        // The adaptive arm plays 3G at a lower bitrate than the static profile, and stalls less.
        val markdown = ReportWriter.write(report(network = NetworkProfileName.THREE_G, candidateBitrateCut = true))

        val trade = markdown.substringAfter("## The F1 trade, as a loss").substringBefore("\n## ")
        val bitrateRow = trade.lines().firstOrNull { it.contains("average bitrate") && it.contains("3G") }
        assertTrue("The F1 section has no 3G bitrate row:\n$trade", bitrateRow != null)
        assertTrue("The bitrate cut is not called a loss: $bitrateRow", bitrateRow!!.contains("**worse**"))
    }

    @Test
    fun aCellTheRunnerSkipsIsNamedWhereTheVerdictIsReadWithTheIssueThatClosesIt() {
        val markdown = ReportWriter.write(report())
        val verdict = markdown.substringAfter("## Exit criterion").substringBefore("## What this was measured on")

        // A verdict judged without a scenario that says so only in an appendix reads as a verdict on
        // every scenario. So each skipped cell is named in the section the verdict is in.
        UnmeasuredCells.entries.forEach { skip ->
            assertTrue("The exit criterion does not name the skipped ${skip.scenario.label} cells", verdict.contains("Not judged: ${skip.scenario.label}"))
            assertTrue(verdict.contains("#144"))
            assertTrue(markdown.substringAfter("Cells this matrix does not cover").contains(skip.gap.cell))
        }
    }

    @Test
    fun aDifferenceInsideTheNoiseIsNeutralRatherThanASmallWin() {
        // Two distributions a hair apart with a wide spread: the sort of difference that is tempting
        // to report as an improvement and is not one.
        val comparison = Comparison(
            metric = "rebuffer count",
            lowerIsBetter = true,
            baseline = Distribution.of(List(20) { it.toDouble() }),
            superPlayer = Distribution.of(List(20) { it.toDouble() - 0.1 }),
        )

        assertEquals(Comparison.Verdict.NEUTRAL, comparison.verdict)
    }

    @Test
    fun theNoiseRuleIsAppliedTheSameWayInBothDirections() {
        val spread = List(20) { it.toDouble() }
        val better = Comparison("m", lowerIsBetter = true, Distribution.of(spread), Distribution.of(spread.map { it - 0.1 }))
        val worse = Comparison("m", lowerIsBetter = true, Distribution.of(spread), Distribution.of(spread.map { it + 0.1 }))

        // Symmetric by construction, and asserted because an asymmetric threshold — generous about
        // wins, strict about losses — is the single most effective way to make an honest-looking
        // table dishonest, and it would be invisible in the output.
        assertEquals(better.verdict, worse.verdict)
    }

    @Test
    fun aRealDifferenceLargerThanTheNoiseIsNotCalledNeutral() {
        val tight = List(20) { 100.0 }
        val comparison = Comparison(
            metric = "time to first frame (ms)",
            lowerIsBetter = true,
            baseline = Distribution.of(tight),
            superPlayer = Distribution.of(List(20) { 400.0 }),
        )

        // The rule has to be able to say "worse" as well as "neutral", or it is not a rule, it is a
        // way of never reporting anything.
        assertEquals(Comparison.Verdict.WORSE, comparison.verdict)
    }

    @Test
    fun theRebufferRatioAReportPrintsIsThePooledOneItPromises() {
        // The regression this exists for: the comparison tables printed the *mean of per-session
        // ratios* while the prose above them promised the pooled ratio — publishing exactly the
        // aggregation `docs/telemetry-schema.md` forbids, under the name of the one it requires.
        //
        // The fixture makes the two numbers far apart. One long, badly stalling session and
        // nineteen short clean ones: pooled is dominated by the long session's stalled milliseconds,
        // while the per-session mean is dragged to nearly nothing by the nineteen zeroes.
        val skewed = listOf(session(Arm.STOCK_DEFAULTS, 0).copy(rebufferMs = 30_000, playingMs = 30_000)) +
            (1 until 20).map { session(Arm.STOCK_DEFAULTS, it).copy(rebufferMs = 0, playingMs = 1_000) }
        val cell = CellResult(
            key = CellKey(Scenario.VOD, NetworkProfileName.THREE_G, Arm.STOCK_DEFAULTS),
            sessions = skewed,
            excludedSessions = 0,
        )

        val pooled = requireNotNull(cell.rebufferRatio)
        val perSessionMean = requireNotNull(cell.rebufferRatioSpread).mean
        assertTrue("The fixture does not separate the two aggregations", pooled > perSessionMean * 5)

        val comparison = CellComparison(
            scenario = Scenario.VOD,
            network = NetworkProfileName.THREE_G,
            baselineArm = Arm.STOCK_DEFAULTS,
            baseline = cell,
            superPlayer = cell,
        ).comparisons.single { it.metric == "rebuffer ratio" }

        // The value a report may print is the pooled one; the distribution is carried only so the
        // verdict has something to measure noise against.
        assertEquals(pooled, comparison.displayBaseline)
        assertEquals(pooled, comparison.displaySuperPlayer)
    }

    @Test
    fun everyComparisonIsAccountedForBySomeSectionOfTheReport() {
        // `ReportWriter` checks this internally and would throw; asserting it here says what the
        // check is for. A comparison with no data belonged to none of worse, neutral or better, so
        // three of them could vanish from a cell while the printed counts still looked plausible.
        val markdown = ReportWriter.write(report())

        assertTrue(markdown.contains("**No data — one arm produced no measurement"))
    }

    @Test
    fun aRunWithTooFewRunsPerCellSaysItIsNotABaseline() {
        val markdown = ReportWriter.write(report(runsPerCell = 3))

        assertTrue(
            "A three-run report does not say it is not a baseline",
            markdown.contains("not a baseline"),
        )
        assertTrue(markdown.contains("${ReportWriter.MINIMUM_RUNS} runs per cell"))
    }

    @Test
    fun aRunOnADirtyTreeSaysSoInProseRatherThanAsABoolean() {
        val markdown = ReportWriter.write(report(treeDirty = true))

        // Prose, because a boolean in a table is a thing a reader's eye passes over, and this one
        // invalidates the whole document as a baseline: no commit anybody can check out produces
        // these numbers.
        assertTrue(markdown.contains("**The working tree had uncommitted changes when this ran.**"))
    }

    @Test
    fun excludedSessionsAreCountedInTheReportRatherThanDisappearing() {
        val markdown = ReportWriter.write(report(excluded = 7))

        // A cell that threw away runs and reported the rest would otherwise look exactly like a cell
        // that ran cleanly. The schema requires the exclusion; saying nothing about it is the part
        // that would be dishonest.
        assertTrue("The report does not say how many sessions were excluded", markdown.contains("7 session(s) were excluded"))
    }

    @Test
    fun theDrmGapIsInTheReportRatherThanOmitted() {
        val markdown = ReportWriter.write(report())

        // Issue #43 is explicit that the DRM cell is a documented gap rather than a silent omission:
        // an uncovered cell that is absent from a table reads as a cell that passed.
        assertTrue(markdown.contains("Cells this matrix does not cover"))
        assertTrue(markdown.contains("Widevine"))
        assertTrue(markdown.contains("Phase 6"))
    }

    @Test
    fun everyPublicStreamIsListedWithItsSource() {
        val markdown = ReportWriter.write(report())

        // `PRD.md` §0.2: every number is produced against public test streams, and a URL with no
        // provenance satisfies the letter of that and none of its point.
        PublicStreams.streams.forEach { stream ->
            assertTrue("The report omits ${stream.label}", markdown.contains(stream.uri))
            assertTrue("The report omits the source of ${stream.label}", markdown.contains(stream.source))
        }
    }

    @Test
    fun theDeviceOnlyMetricsAreShownAsUnpopulatedRatherThanLeftOut() {
        val markdown = ReportWriter.write(report())

        // Peak RSS and battery are in `PRD.md` §6's reported list and cannot come from Robolectric.
        // A table of dashes says so; leaving the metrics out of the document lets a reader assume
        // the matrix covered them.
        assertTrue(markdown.contains("Peak RSS"))
        assertTrue(markdown.contains("Battery delta over 30 min"))
        assertTrue(markdown.contains("#95"))
        assertFalse(
            "A Robolectric run reported a peak RSS, which it cannot measure",
            markdown.contains("| Peak RSS (MiB) | 0"),
        )
    }

    // --- A report built to lose ---------------------------------------------------------------------

    /**
     * A one-cell matrix in which the adaptive arm is worse on start-up, better on stalls, and inside
     * the noise on bitrate, against both stock arms and the static profile alike.
     *
     * Built to lose on purpose. A fixture where the adaptive arm won everything would let every
     * assertion above pass against a report generator that could not express a loss at all.
     */
    private fun report(
        runsPerCell: Int = 20,
        treeDirty: Boolean = false,
        excluded: Int = 0,
        network: NetworkProfileName = NetworkProfileName.THREE_G,
        candidateStartsSlower: Boolean = true,
        candidateBitrateCut: Boolean = false,
    ): MatrixReport {
        val scenario = Scenario.VOD
        return MatrixReport(
            conditions = RunConditions(
                startedAt = "2026-09-12T00:00:00Z",
                commit = "0000000000000000000000000000000000000000",
                treeDirty = treeDirty,
                superPlayerVersion = "0.1.0-SNAPSHOT",
                media3Version = "1.11.0",
                runsPerCell = runsPerCell,
                robolectricSdk = "35",
                javaVersion = "17",
                host = "test",
            ),
            cells = Arm.entries.map { arm ->
                CellResult(
                    key = CellKey(scenario, network, arm),
                    sessions = (0 until runsPerCell).map { run ->
                        session(arm, run, candidateStartsSlower, candidateBitrateCut)
                    },
                    excludedSessions = if (arm == Arm.ADAPTIVE) excluded else 0,
                )
            },
        )
    }

    /** One session of [arm], shaped so the adaptive arm differs from the rest in the ways a test needs. */
    private fun session(
        arm: Arm,
        run: Int,
        candidateStartsSlower: Boolean = true,
        candidateBitrateCut: Boolean = false,
    ): SessionMetrics {
        val candidate = arm == Arm.ADAPTIVE
        return SessionMetrics(
            sessionId = "$arm-$run",
            // The adaptive arm starts markedly slower here: the loss.
            timeToFirstFrameMs = if (candidate && candidateStartsSlower) 800L else 300L,
            startBoundary = TtffStartBoundary.USER_INTENT,
            // And stalls markedly less: the win.
            rebufferMs = if (candidate) 0L else 4_000L,
            rebufferCount = if (candidate) 0 else 2,
            playingMs = 60_000L,
            // And is a hair apart on bitrate, with enough spread that the hair is noise: the neutral —
            // unless the test asks for F1's trade, where it plays a rung lower on purpose.
            averageBitrateBps = when {
                candidate && candidateBitrateCut -> 365_000.0 + run
                candidate -> 730_000.0 + run
                else -> 730_000.0 + run + 1.0
            },
            switchCount = 1,
            upshiftCount = 1,
            downshiftCount = 0,
            switchMagnitudeBpsSum = 365_000,
            startupFailed = false,
            midStreamFailed = false,
            exitBeforeVideoStart = false,
            droppedEventCount = 0,
            decisionChangeCount = 0,
            ended = true,
        )
    }

    private companion object {
        const val LOSSES_HEADING = "## Where the adaptive policy is worse, or no better"
        const val WINS_HEADING = "## Where the adaptive policy is better"
    }
}
