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

package com.superplayer.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
import com.superplayer.testmedia.HostileStream.Severity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every DASH pathology the curated corpus carries, named by the doctor with the corpus's own words (#288).
 *
 * `HlsPathologyTest`'s twin, written the same way and for the same reasons: against `HostileManifests`
 * rather than against the issue that asked for it, with the `BENIGN` grades and the healthy content in test
 * methods of their own, because ADR-0015 rule 12 scores a doctor on its false positives as heavily as on
 * its misses.
 *
 * ## The live half, which is why this phase exists
 *
 * `PRD.md` §3.6 names a skewed clock and a short time-shift buffer as the cause of a surprising share of
 * "live stream freezes after 30 s" tickets, and a viewer meets both as a player that will not start. Two
 * things about how they are asserted here are worth knowing.
 *
 * **The healthy dynamic manifest is a control of its own.** `HostileManifests.dashLiveBaseline()` is the
 * stream every live entry is a modifier over, and it is labelled `HEALTHY` and left out of the corpus. A
 * live rule that flagged it would be flagging "this manifest is live", which is not a defect, and no other
 * control here could tell the two apart.
 *
 * **The short window is core's judgement rather than a threshold of this module's** (ADR-0015 rules 3 and
 * 6). What is asserted is therefore the report's wording and its severity; that the *comparison* is right
 * is `LiveWindowDepthCheck`'s own tests' business, and there is exactly one comparison to be right.
 *
 * ## What is named and still not repaired
 *
 * `dash-availability-start-time-skew` is the one corpus entry `HostileManifestLadderTest` records in
 * `CANNOT_RECOVER`: nothing about it fails to load, so no rung of the fallback ladder is ever offered and a
 * player simply sits at a negative position. That record is untouched by this class and by #288. Naming the
 * defect is not repairing it, and the difference between the two is what Phase 9 is for.
 */
@RunWith(AndroidJUnit4::class)
class DashPathologyTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aLadderWithAStepNoClientCanClimbIsNamedAndGradedOnTheStep() {
        val severe = findingFor(HostileManifests.dashLadderGap(Severity.SEVERE), Pathology.DASH_LADDER_GAP)
        assertThat(severe.severity).isEqualTo(FindingSeverity.DEGRADED)
        // The same two rungs its HLS twin declares, which is the point of judging both by one rule.
        assertThat(severe.magnitude).isEqualTo("adjacent rungs 48× apart, 128 kbps to 6144 kbps")

        val borderline = findingFor(HostileManifests.dashLadderGap(Severity.BORDERLINE), Pathology.DASH_LADDER_GAP)
        assertThat(borderline.severity).isEqualTo(FindingSeverity.ADVISORY)
    }

    @Test
    fun aRungThatDeclaresMoreThanItsFormatCanCarryIsNamed() {
        val finding = findingFor(
            HostileManifests.dashOverstatedBitrate(Severity.SEVERE),
            Pathology.DASH_OVERSTATED_BITRATE,
        )
        assertThat(finding.severity).isEqualTo(FindingSeverity.DEGRADED)
        assertThat(finding.magnitude).isEqualTo("declares 4000 kbps where the format it names carries at most 1152 kbps")
    }

    @Test
    fun aRepresentationDeclaringNoCodecsIsNamed() {
        val finding = findingFor(HostileManifests.dashMissingCodecs(), Pathology.DASH_MISSING_CODECS)
        assertThat(finding.severity).isEqualTo(FindingSeverity.DEGRADED)
        assertThat(finding.magnitude).isNull()
    }

    @Test
    fun aLadderThatChangesAtAPeriodBoundaryIsNamedAndGradedOnTheJump() {
        val severe = findingFor(
            HostileManifests.dashMidStreamLadderChange(Severity.SEVERE),
            Pathology.DASH_MID_STREAM_LADDER_CHANGE,
        )
        assertThat(severe.severity).isEqualTo(FindingSeverity.DEGRADED)
        assertThat(severe.magnitude).isEqualTo("the ladder moves by 48× at a Period boundary")

        val borderline = findingFor(
            HostileManifests.dashMidStreamLadderChange(Severity.BORDERLINE),
            Pathology.DASH_MID_STREAM_LADDER_CHANGE,
        )
        assertThat(borderline.severity).isEqualTo(FindingSeverity.ADVISORY)
    }

    @Test
    fun anAvailabilityStartTimeAheadOfTheManifestsOwnClockIsNamedWithItsDirectionAndAmount() {
        val finding = findingFor(
            HostileManifests.dashAvailabilityStartTimeSkew(Severity.SEVERE),
            Pathology.DASH_AVAILABILITY_START_TIME_SKEW,
        )
        // Blocking and ungraded: the origin has published nothing yet, whatever the size of the skew.
        assertThat(finding.severity).isEqualTo(FindingSeverity.BLOCKING)
        // Which way and by how much, which is the acceptance criterion in as many words. The corpus's severe
        // entry is a clock an hour out on a stream that has been on air four seconds, so what the document
        // itself says is an anchor 3596 s past its own publish time — and the report says that rather than
        // "the clock is wrong", because a support engineer acts on the direction.
        assertThat(finding.magnitude)
            .isEqualTo("availabilityStartTime 3596 s ahead of the manifest's own clock, so no segment is available yet")
    }

    @Test
    fun aWindowNoPlayheadFitsInsideIsNamedOnCoresOwnJudgement() {
        val severe = findingFor(
            HostileManifests.dashShortTimeShiftBufferDepth(Severity.SEVERE),
            Pathology.DASH_SHORT_TIME_SHIFT_BUFFER_DEPTH,
        )
        assertThat(severe.severity).isEqualTo(FindingSeverity.BLOCKING)
        assertThat(severe.magnitude)
            .isEqualTo("timeShiftBufferDepth of 0.5 s, against the 2 s a segment takes to become available")

        // The corpus's borderline depth is exactly one segment, and its own comment records that measured, it
        // sat outside the window just as the severe one did — because a segment is fetchable only once
        // complete. Core's comparison is `depthUs > lag.us`, so equality is too short, and the doctor reports
        // it at the same severity for the same reason: the judgement is a yes or a no, and grading it here
        // would be the second threshold ADR-0015 rule 6 forbids.
        val borderline = findingFor(
            HostileManifests.dashShortTimeShiftBufferDepth(Severity.BORDERLINE),
            Pathology.DASH_SHORT_TIME_SHIFT_BUFFER_DEPTH,
        )
        assertThat(borderline.severity).isEqualTo(FindingSeverity.BLOCKING)
        assertThat(borderline.magnitude)
            .isEqualTo("timeShiftBufferDepth of 2 s, against the 2 s a segment takes to become available")
    }

    @Test
    fun aLiveManifestPromisingAnUnlimitedWindowIsNamed() {
        val finding = findingFor(
            HostileManifests.dashMissingTimeShiftBufferDepth(),
            Pathology.DASH_MISSING_TIME_SHIFT_BUFFER_DEPTH,
        )
        // Degraded rather than blocking: playing from the live edge is unaffected, and what is measurably
        // worse is a seek into a window the CDN never promised.
        assertThat(finding.severity).isEqualTo(FindingSeverity.DEGRADED)
        assertThat(finding.magnitude).isNull()

        // And told apart from the window that is present and too short, which is core's judgement and
        // declines this case: a packager that wrote nothing and a packager that wrote half a second made two
        // different mistakes, and a report naming one of them sends an engineer to the wrong line.
        assertThat(findings(HostileManifests.dashMissingTimeShiftBufferDepth()).map { it.pathology })
            .containsExactly(Pathology.DASH_MISSING_TIME_SHIFT_BUFFER_DEPTH)
    }

    @Test
    fun aManifestCarryingMoreThanOnePathologyReportsAllOfThem() {
        // As in HLS, the corpus composes nothing and the entry that carries two is the one whose single
        // defect *is* two defects of the document: a ladder gap of 48 puts its top rung at 6144 kbps, which
        // is also more stereo AAC than the codec's own bit reservoir can carry. Both readings are true of the
        // MPD and both are reported. #290's register has to expect two here for the same reason it does for
        // `hls-ladder-gap`.
        assertThat(findings(HostileManifests.dashLadderGap(Severity.SEVERE)).map { it.pathology })
            .containsExactly(Pathology.DASH_LADDER_GAP, Pathology.DASH_OVERSTATED_BITRATE)
    }

    @Test
    fun theBenignGradeOfEveryGradedPathologyIsNotAFinding() {
        val benign = HostileManifests.graded()
            .filter { it.protocol == HostileStream.Protocol.DASH && it.severity == Severity.BENIGN }
        assertWithMessage("the corpus's graded DASH entries").that(benign.map { it.id })
            .containsExactly(
                "dash-ladder-gap",
                "dash-overstated-bitrate",
                "dash-availability-start-time-skew",
                "dash-short-time-shift-buffer-depth",
                "dash-mid-stream-ladder-change",
            )

        benign.forEach { entry ->
            assertWithMessage("$entry — ${entry.magnitude}").that(findings(entry)).isEmpty()
        }
    }

    @Test
    fun theHealthyDynamicManifestIsNotAFinding() {
        // The control the live rules need and no other control can be: a conforming dynamic MPD, with a
        // `UTCTiming`, an anchor four seconds behind its own clock and a minute of window. A rule that read
        // "live" as "defective" passes every other test in this class and fails this one.
        val baseline = HostileManifests.dashLiveBaseline()
        assertThat(baseline.validity).isEqualTo(HostileStream.Validity.HEALTHY)
        assertWithMessage("$baseline").that(findings(baseline)).isEmpty()
    }

    @Test
    fun aHealthyStaticManifestIsNotAFinding() {
        // The known-good static stream, which is a single-rung MPD: the control for everything that is not a
        // ladder, since a ladder of one has no step to be wrong about. The ladder's own control is the
        // corpus's `BENIGN` gap — two rungs exactly a factor of two apart, which is a ladder built to the
        // published guidance — and it is asserted in the benign method above.
        val content = TestContent.dash()
        val environment = harness.diagnosticEnvironment(content)

        val report = MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
            .examine(MediaRequest.Builder(HEALTHY_CONTENT_ID).addSource(content.sourceUri).build())

        assertThat(report.findings).isEmpty()
    }

    /** The one finding of [pathology] [entry] produces, with the corpus's own words asserted on it. */
    private fun findingFor(entry: HostileStream, pathology: Pathology): Finding {
        val finding = findings(entry).singleOrNull { it.pathology == pathology }
        assertWithMessage("$entry — ${entry.magnitude}").that(finding).isNotNull()
        // The id, the citation and the cause are the corpus's — the join ADR-0015 rule 12's register is
        // built on, and a copy that had drifted fails here rather than in a report nobody reads.
        assertWithMessage("$entry").that(pathology.id).isEqualTo(entry.id)
        assertWithMessage("$entry").that(finding!!.specCitation).isEqualTo(entry.spec)
        assertWithMessage("$entry").that(finding.cause).isEqualTo(entry.cause)
        return finding
    }

    /** What a doctor an app would build says about [entry], over the harness's transport. */
    private fun findings(entry: HostileStream): List<Finding> {
        val environment = harness.diagnosticEnvironment(TestContent.hostile(entry))
        return MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
            .examine(MediaRequest.Builder(entry.id).addSource(entry.sourceUri).build())
            .findings
    }

    private companion object {

        /** The identity a healthy stream is asked about under: a content id, never a URL. */
        const val HEALTHY_CONTENT_ID = "film/healthy"
    }
}
