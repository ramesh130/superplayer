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

import com.google.common.truth.Truth.assertThat
import com.superplayer.telemetry.QoeScore
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The gate's judgement and its report, against hand-written floors and scores.
 *
 * `QoeRegressionGateTest` is the gate itself, and it plays six sessions to find out whether a change
 * regressed; this holds the part that decides what "regressed" means and what a reviewer is shown,
 * which is cheaper and more exact to pin with numbers written by hand.
 */
class QoeGateTest {

    @Test
    fun aDropPastTheMarginFailsTheGate() {
        val report = QoeGate.judge(floors(STABLE to 4.0), mapOf(STABLE to breakdown(3.7)), margin = 0.2)

        assertThat(report.rows.single().verdict).isEqualTo(QoeGate.Verdict.REGRESSED)
        assertThat(report.passed).isFalse()
    }

    @Test
    fun aDropInsideTheMarginPasses() {
        val report = QoeGate.judge(floors(STABLE to 4.0), mapOf(STABLE to breakdown(3.85)), margin = 0.2)

        assertThat(report.rows.single().verdict).isEqualTo(QoeGate.Verdict.WITHIN_MARGIN)
        assertThat(report.passed).isTrue()
    }

    @Test
    fun anImprovementPassesButDoesNotMoveTheFloor() {
        val floors = floors(STABLE to 4.0)
        val report = QoeGate.judge(floors, mapOf(STABLE to breakdown(4.9)), margin = 0.2)

        assertThat(report.rows.single().verdict).isEqualTo(QoeGate.Verdict.IMPROVED)
        assertThat(report.passed).isTrue()
        // The floor a later run is judged against is still the committed one: the gate returns a
        // report and writes nothing, so an improvement cannot hide a later regression.
        assertThat(report.rows.single().floor!!.breakdown.score).isWithin(1e-9).of(4.0)
        assertThat(QoeGate.render(report)).contains("the gate does not raise it")
    }

    @Test
    fun oneTraceRegressingFailsTheGateWhateverTheOthersGained() {
        val report = QoeGate.judge(
            floors(STABLE to 4.0, THREE_G to 0.6),
            mapOf(STABLE to breakdown(3.0), THREE_G to breakdown(0.9)),
            margin = 0.2,
        )

        assertThat(report.passed).isFalse()
    }

    @Test
    fun aTraceWithNoFloorOrNoScoreFailsRatherThanPassingUnjudged() {
        val noFloor = QoeGate.judge(floors(), mapOf(STABLE to breakdown(4.0)), margin = 0.2)
        val noScore = QoeGate.judge(floors(STABLE to 4.0), mapOf(STABLE to null), margin = 0.2)
        val notPlayed = QoeGate.judge(floors(STABLE to 4.0), emptyMap(), margin = 0.2)

        assertThat(noFloor.rows.single().verdict).isEqualTo(QoeGate.Verdict.NO_FLOOR)
        assertThat(noScore.rows.single().verdict).isEqualTo(QoeGate.Verdict.NO_SCORE)
        assertThat(notPlayed.rows.single().verdict).isEqualTo(QoeGate.Verdict.NO_SCORE)
        assertThat(listOf(noFloor, noScore, notPlayed).map { it.passed }).containsExactly(false, false, false)
    }

    @Test
    fun theReportPrintsEveryTraceWithRegressionsFirstAndEveryTermOldAndNew() {
        val report = QoeGate.judge(
            floors(STABLE to 4.0, THREE_G to 0.6),
            mapOf(STABLE to breakdown(4.1), THREE_G to QoeScore.Breakdown(0.7, 0.5, 0.05)),
            margin = 0.02,
        )
        val text = QoeGate.render(report)

        val regressed = text.indexOf("| $THREE_G |")
        val passed = text.indexOf("| $STABLE |")
        assertThat(regressed).isAtLeast(0)
        assertThat(passed).isGreaterThan(regressed)
        // Old and new side by side for each term, so the reader sees *which* term moved.
        val row = text.lines().first { it.startsWith("| $THREE_G |") }
        assertThat(row).contains("0.600 → 0.150")
        assertThat(row).contains("0.700 → 0.700")
        assertThat(row).contains("0.000 → 0.500")
        assertThat(row).contains("0.100 → 0.050")
    }

    @Test
    fun floorsParseFromTheCommittedFormatWithTheirReasons() {
        val parsed = QoeGate.parseFloors(
            """
            # a comment
            trace	score	bitrateUtility	rebufferPenalty	switchPenalty	why

            STABLE_WIFI	4.000	4.100	0.000	0.100	Phase 3 configuration, issue #102
            """.trimIndent(),
        )

        val floor = parsed.getValue(STABLE)
        assertThat(floor.breakdown).isEqualTo(QoeScore.Breakdown(4.1, 0.0, 0.1))
        assertThat(floor.why).isEqualTo("Phase 3 configuration, issue #102")
    }

    @Test
    fun aFloorWithoutAReasonIsRefused() {
        // Moving a floor is a decision, and a decision is recorded with why it was made.
        assertThrows(IllegalArgumentException::class.java) {
            QoeGate.parseFloors("STABLE_WIFI\t4.000\t4.100\t0.000\t0.100\t ")
        }
    }

    @Test
    fun aFloorWhoseScoreDisagreesWithItsTermsIsRefused() {
        // The score column is for reading; the terms are what it is. A hand edit that changed one
        // and not the others would judge against a number nobody measured.
        assertThrows(IllegalArgumentException::class.java) {
            QoeGate.parseFloors("STABLE_WIFI\t5.000\t4.100\t0.000\t0.100\twhy")
        }
    }

    @Test
    fun aTraceListedTwiceIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            QoeGate.parseFloors("STABLE_WIFI\t4.000\t4.100\t0.000\t0.100\ta\nSTABLE_WIFI\t4.000\t4.100\t0.000\t0.100\tb")
        }
    }

    @Test
    fun aFloorRowRoundTripsThroughTheFormatThePrintedCandidateUses() {
        val line = QoeGate.floorLine(STABLE, QoeScore.Breakdown(4.1234, 0.0, 0.1), why = "because")

        assertThat(QoeGate.parseFloors(line).getValue(STABLE).why).isEqualTo("because")
    }

    private fun floors(vararg scores: Pair<String, Double>): Map<String, QoeGate.Floor> =
        scores.associate { (trace, score) -> trace to QoeGate.Floor(breakdown(score), why = "test") }

    /** A breakdown with the stated score, carried entirely by utility less a fixed switch penalty. */
    private fun breakdown(score: Double) = QoeScore.Breakdown(score + 0.1, 0.0, 0.1)

    private companion object {
        const val STABLE = "STABLE_WIFI"
        const val THREE_G = "THREE_G"
    }
}
