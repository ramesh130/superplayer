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
 * Every HLS pathology the curated corpus carries, named by the doctor with the corpus's own words (#287).
 *
 * `MediaSourceDoctorTest` pins the doctor's *shape* over one pathology; this class is the vocabulary, and it
 * is written against `HostileManifests` rather than against the issue that asked for it: where the two
 * disagree about how many severities an entry has, the corpus is right. Three of the entries below are
 * generated at every [HostileStream.Severity] and four are binary, and each test reads that off
 * [HostileManifests.graded] rather than asserting a count of its own.
 *
 * ## The two halves, and why the second is in test methods of its own
 *
 * ADR-0015 rule 12 scores a doctor on its false positives as heavily as on its misses: a doctor that flags
 * everything names every pathology and is useless. So the `BENIGN` grade of every graded entry and the
 * healthy stream of the same protocol are asserted to produce **nothing**, in their own methods, where a
 * regression reads as "the doctor flags benign content" rather than as one assertion inside a longer test.
 *
 * ## Grading
 *
 * A graded pathology's severity is a reading of *its magnitude* rather than a constant per pathology, which
 * is what [FindingSeverity] is per finding for. The thresholds are `HlsPathologies`', each argued at the
 * constant that carries it; what these tests fix is the behaviour they produce — the corpus's `BENIGN` is
 * not a finding at all, its `SEVERE` is the graver of the two grades, and `BORDERLINE` is where a reasonable
 * threshold may fall either way, so it is asserted as *at most* the severe reading rather than as one value.
 *
 * #290 is where the whole corpus is scored entry by entry in one register; what this class does is force
 * each HLS entry through a real doctor over the harness's transport.
 */
@RunWith(AndroidJUnit4::class)
class HlsPathologyTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aLadderWithAStepNoClientCanClimbIsNamedAndGradedOnTheStep() {
        val severe = findingFor(HostileManifests.hlsLadderGap(Severity.SEVERE), Pathology.HLS_LADDER_GAP)
        assertThat(severe.severity).isEqualTo(FindingSeverity.DEGRADED)
        // 128 kbps to 6144 kbps with nothing between, as the corpus's severe step declares.
        assertThat(severe.magnitude).isEqualTo("adjacent rungs 48× apart, 128 kbps to 6144 kbps")

        // A step of four is one rung missing from a ladder built to the guidance: reported, and reported
        // as the lesser reading, which is the whole of "the severity tracks the magnitude".
        val borderline = findingFor(HostileManifests.hlsLadderGap(Severity.BORDERLINE), Pathology.HLS_LADDER_GAP)
        assertThat(borderline.severity).isEqualTo(FindingSeverity.ADVISORY)
    }

    @Test
    fun aRungThatDeclaresMoreThanItsFormatCanCarryIsNamed() {
        val finding = findingFor(
            HostileManifests.hlsOverstatedBitrate(Severity.SEVERE),
            Pathology.HLS_OVERSTATED_BITRATE,
        )
        assertThat(finding.severity).isEqualTo(FindingSeverity.DEGRADED)
        // Four megabits of stereo AAC, against the 1152 kbps the codec's own bit reservoir bounds it to.
        assertThat(finding.magnitude).isEqualTo("declares 4000 kbps where the format it names carries at most 1152 kbps")
    }

    @Test
    fun anAudioGroupDeclaredAsAFormatItsSegmentsCannotCarryIsNamed() {
        val finding = findingFor(
            HostileManifests.hlsAudioGroupCodecMismatch(),
            Pathology.HLS_AUDIO_GROUP_CODEC_MISMATCH,
        )
        assertThat(finding.severity).isEqualTo(FindingSeverity.DEGRADED)
        assertThat(finding.magnitude).isNull()
    }

    @Test
    fun aVariantPointingAtAnAudioGroupThatIsNotThereIsNamed() {
        val finding = findingFor(HostileManifests.hlsDanglingAudioGroup(), Pathology.HLS_DANGLING_AUDIO_GROUP)
        // The one HLS finding that is blocking, and it is the document being malformed rather than a
        // magnitude: a conformant client may reject the whole playlist, and that this app's player does not
        // is a property of Media3's leniency rather than of the stream.
        assertThat(finding.severity).isEqualTo(FindingSeverity.BLOCKING)
        assertThat(finding.magnitude).isNull()
    }

    @Test
    fun segmentsFurtherApartThanAnyToleranceAllowsAreNamedAndGradedOnTheSpread() {
        val severe = findingFor(
            HostileManifests.hlsInconsistentSegmentDurations(Severity.SEVERE),
            Pathology.HLS_INCONSISTENT_SEGMENT_DURATIONS,
        )
        assertThat(severe.severity).isEqualTo(FindingSeverity.DEGRADED)
        assertThat(severe.magnitude).isEqualTo("segments 0.5 s to 10 s long, a 20× spread")

        // The corpus's borderline spread is exactly three — half the nominal beside one and a half times it,
        // which is the widest pair the published tolerance admits, and which its own comment calls "within
        // the letter of the tolerance". The doctor is entitled to call that either way, and this is which
        // way it calls it: the threshold is strict, so a playlist that conforms exactly is not flagged.
        // Pinned rather than left open, because "either way" is a licence for the threshold and not for the
        // test, and the answer moving is something a reader of #290's register needs to see.
        val borderline = HostileManifests.hlsInconsistentSegmentDurations(Severity.BORDERLINE)
        assertWithMessage("$borderline — ${borderline.magnitude}").that(findings(borderline)).isEmpty()
    }

    @Test
    fun aSpliceWithNothingToPlaceItsTimelineIsNamed() {
        val finding = findingFor(
            HostileManifests.hlsDiscontinuityWithoutTimeline(),
            Pathology.HLS_DISCONTINUITY_WITHOUT_TIMELINE,
        )
        assertThat(finding.severity).isEqualTo(FindingSeverity.DEGRADED)
        assertThat(finding.magnitude).isNull()
    }

    @Test
    fun aPlaylistCarryingMoreThanOnePathologyReportsAllOfThem() {
        // The corpus composes nothing — every entry is one good stream with one thing wrong — so the
        // playlist that carries two is the one whose single defect *is* two defects of the document: a
        // ladder gap of 48 puts its top rung at 6144 kbps, which is also more stereo AAC than the codec can
        // carry. The corpus scales that rung's segments to match, so the bytes bear the declaration out;
        // what the doctor reads is the playlist, where 6 Mbps of AAC-LC is a rate no encoder measured. Both
        // readings are true of the document and both are reported, which is what this test fixes.
        val entry = HostileManifests.hlsLadderGap(Severity.SEVERE)

        assertThat(findings(entry).map { it.pathology })
            .containsExactly(Pathology.HLS_LADDER_GAP, Pathology.HLS_OVERSTATED_BITRATE)
    }

    @Test
    fun theBenignGradeOfEveryGradedPathologyIsNotAFinding() {
        val benign = HostileManifests.graded()
            .filter { it.protocol == HostileStream.Protocol.HLS && it.severity == Severity.BENIGN }
        assertWithMessage("the corpus's graded HLS entries").that(benign.map { it.id })
            .containsExactly("hls-ladder-gap", "hls-overstated-bitrate", "hls-inconsistent-segment-durations")

        benign.forEach { entry ->
            assertWithMessage("$entry — ${entry.magnitude}").that(findings(entry)).isEmpty()
        }
    }

    @Test
    fun aHealthyMultivariantPlaylistIsNotAFinding() {
        // The known-good stream is not a degenerate control: it carries two rungs a factor of two apart,
        // which is a ladder built exactly to the published guidance and the closest a healthy playlist
        // comes to the gap rule's threshold.
        val content = TestContent.hls(variantCount = 2)
        val environment = harness.diagnosticEnvironment(content)

        val report = MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
            .examine(MediaRequest.Builder(HEALTHY_CONTENT_ID).addSource(content.sourceUri).build())

        assertThat(report.findings).isEmpty()
    }

    /** The one finding of [pathology] [entry] produces, with the corpus's own words asserted on it. */
    private fun findingFor(entry: HostileStream, pathology: Pathology): Finding {
        val finding = findings(entry).singleOrNull { it.pathology == pathology }
        assertWithMessage("$entry — ${entry.magnitude}").that(finding).isNotNull()
        // The id, the citation and the cause are the corpus's, which is the join ADR-0015 rule 12's
        // register is built on: the doctor keeps its own copy of the words because a library cannot depend
        // on a test module, and a copy that had drifted would fail here rather than in a report nobody reads.
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
