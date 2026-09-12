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
 * would use. The fixture is therefore built to lose: SuperPlayer worse on one metric, better on
 * another, and inside the noise on a third, so that each verdict has something to be about.
 */
class ReportHonestyTest {

    @Test
    fun aLossIsStatedBeforeAnyWin() {
        val markdown = ReportWriter.write(report())

        val losses = markdown.indexOf("## Where SuperPlayer is worse, or no better")
        val wins = markdown.indexOf("## Where SuperPlayer is better")
        assertTrue("The report has no losses section", losses >= 0)
        assertTrue("The report has no wins section", wins >= 0)
        // Ordering, because a summary that begins with wins is one a reader stops reading before the
        // losses. This is the mechanical form of "states losses as prominently as wins".
        assertTrue("The wins are printed before the losses", losses < wins)
    }

    @Test
    fun aLossAppearsInTheReportWithItsSizeAndTheWordWorse() {
        val markdown = ReportWriter.write(report())

        val lossesSection = markdown.substringAfter("## Where SuperPlayer is worse, or no better")
            .substringBefore("## Where SuperPlayer is better")
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
        assertFalse(
            "A Robolectric run reported a peak RSS, which it cannot measure",
            markdown.contains("| Peak RSS (MiB) | 0"),
        )
    }

    // --- A report built to lose ---------------------------------------------------------------------

    /**
     * A one-cell matrix in which SuperPlayer is worse on start-up, better on stalls, and inside the
     * noise on bitrate.
     *
     * Built to lose on purpose. A fixture where SuperPlayer won everything would let every assertion
     * above pass against a report generator that could not express a loss at all.
     */
    private fun report(
        runsPerCell: Int = 20,
        treeDirty: Boolean = false,
        excluded: Int = 0,
    ): MatrixReport {
        val scenario = Scenario.VOD
        val network = NetworkProfileName.THREE_G
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
                    sessions = (0 until runsPerCell).map { run -> session(arm, run) },
                    excludedSessions = if (arm == Arm.SUPERPLAYER) excluded else 0,
                )
            },
        )
    }

    /** One session of [arm], shaped so the arms differ in the three ways the fixture needs. */
    private fun session(arm: Arm, run: Int): SessionMetrics = SessionMetrics(
        sessionId = "$arm-$run",
        // SuperPlayer starts markedly slower here: the loss.
        timeToFirstFrameMs = if (arm == Arm.SUPERPLAYER) 800L else 300L,
        startBoundary = TtffStartBoundary.USER_INTENT,
        // And stalls markedly less: the win.
        rebufferMs = if (arm == Arm.SUPERPLAYER) 0L else 4_000L,
        rebufferCount = if (arm == Arm.SUPERPLAYER) 0 else 2,
        playingMs = 60_000L,
        // And is a hair apart on bitrate, with enough spread that the hair is noise: the neutral.
        averageBitrateBps = 365_000.0 + if (arm == Arm.SUPERPLAYER) run.toDouble() else run + 1.0,
        switchCount = 1,
        upshiftCount = 1,
        downshiftCount = 0,
        switchMagnitudeBpsSum = 365_000,
        startupFailed = false,
        midStreamFailed = false,
        exitBeforeVideoStart = false,
        droppedEventCount = 0,
        ended = true,
    )
}
