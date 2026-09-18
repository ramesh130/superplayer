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
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackResilience
import com.superplayer.core.SuperPlayer
import com.superplayer.core.SuperPlayerError
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.HostileObservation
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other half of `PRD.md` §3.6: a session that *failed* is examined, and answers what class of failure
 * it was **and** what was wrong with the stream that produced it (#291).
 *
 * The whole of the API is one argument on the call every preflight already makes — `examine(request,
 * player.classify(error))` — because ADR-0015 rule 8 makes preflight and postmortem one report and one
 * entry point, and rule 5 makes the difference between them one populated field.
 *
 * **What this class is really guarding is that the two vocabularies stay apart** (ADR-0015 rules 4 and 5).
 * The classification is `ErrorClassifier`'s, read off the `SuperPlayerError` the session ended on, and this
 * module could not recompute one if it wanted to: it depends on no classifier (rule 1). The findings are
 * the doctor's, and are the same findings the same request answers with nothing having failed. So the four
 * combinations are all reachable, and the two that disagree are the interesting ones — each has a test
 * here, because a report that made either impossible would have merged the two:
 *
 * | | a defect the doctor names | no defect |
 * | --- | --- | --- |
 * | **the session failed** | [aLiveSessionThatFailedOnAShortWindowReportsThePathologyBesideItsClass] | [aFailureWithNoManifestDefectBehindItReportsTheClassAndNoFinding] |
 * | **no session failed** | [aStreamThatPlaysOnWhileMisconfiguredReportsFindingsAndNoClassification] | every healthy preflight (`MediaSourceDoctorTest`) |
 *
 * The players are driven exactly as `HostileObservation` drives them, on its budget and its step, because
 * what is being read off them here — did this session fail, and on what — is what that observer reads. It
 * is not *called*, because a postmortem needs the player the observation throws away.
 */
@RunWith(AndroidJUnit4::class)
class PostmortemTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aLiveSessionThatFailedOnAShortWindowReportsThePathologyBesideItsClass() {
        // The corpus's own "live stream freezes after 30 s" ticket: a time-shift window shallower than a
        // segment takes to become available, which core raises `LiveWindowTooShortException` for at
        // playback time (issue #67) and which `DashPathologies` names from the manifest alone (#288).
        // `HostileManifestLadderTest` records it as `FAILS_TYPED`, so there is really a failure to read.
        val entry = HostileManifests.dashShortTimeShiftBufferDepth()
        val failure = classificationOf(entry, resilience = Resilience.standard())

        val report = examine(entry, failure)

        // Side by side. The classification is the resilience module's word for what ended the session…
        assertThat(report.classification).isNotNull()
        assertThat(report.classification?.causeClass).isEqualTo("Content.ManifestInvalid")
        // …and the finding is the doctor's word for what is wrong with the document, with the clause it
        // departs from. Neither is derived from the other, and the report prints both.
        assertThat(report.findings.map { it.pathology })
            .contains(Pathology.DASH_SHORT_TIME_SHIFT_BUFFER_DEPTH)
        val finding = report.findings.first { it.pathology == Pathology.DASH_SHORT_TIME_SHIFT_BUFFER_DEPTH }
        assertThat(finding.specCitation).isEqualTo(entry.spec)
        assertThat(finding.severity).isEqualTo(FindingSeverity.BLOCKING)
    }

    @Test
    fun aFailureWithNoManifestDefectBehindItReportsTheClassAndNoFinding() {
        // The control, and the first of the two disagreements: a stream whose only defect is benign — which
        // the register (#290) holds the doctor silent on — failing because the *network* refused its
        // segments. A postmortem that answered a finding here would be one that had learnt to explain a
        // failure with whatever the manifest happened to say.
        val entry = HostileManifests.hlsLadderGap(HostileStream.Severity.BENIGN)
        val refusedSegments = FaultScript.Builder()
            .failWithHttpStatus(500, kind = ResourceKind.MEDIA_SEGMENT)
            .build()
        val failure = classificationOf(entry, resilience = Resilience.standard(), faults = refusedSegments)

        val report = examine(entry, failure, faults = refusedSegments)

        // Named rather than merely non-null: a 500 is a status `ErrorClassifier` reads nothing into, so it
        // falls through to the I/O band's own answer — the CDN being unwell. Asserting the class is what
        // makes this the control it claims to be, since a silent report beside some *other* class would be
        // a different scenario passing under this one's name.
        assertThat(report.classification?.causeClass).isEqualTo("Transient.Network")
        assertWithMessage("nothing is wrong with this manifest, and the doctor says so")
            .that(report.findings).isEmpty()
    }

    @Test
    fun aStreamThatPlaysOnWhileMisconfiguredReportsFindingsAndNoClassification() {
        // The second disagreement, and the direction a preflight is for: a ladder with a step no client can
        // climb gracefully plays to the end on every recorded player (`HostileManifestLadderTest`), so
        // there is no failure to classify and the defect is still there to name.
        val entry = HostileManifests.hlsLadderGap()
        val player = playedOut(entry, resilience = Resilience.standard())
        assertWithMessage("this entry plays: there is nothing to classify").that(player.playerError).isNull()

        val report = examine(entry, classification = null)

        assertThat(report.classification).isNull()
        assertThat(report.findings.map { it.pathology }).contains(Pathology.HLS_LADDER_GAP)
        player.release()
    }

    @Test
    fun theDefectNoRungCanReachIsNamedByTheDoctorAndByNothingElse() {
        // `HostileManifestLadderTest.CANNOT_RECOVER`'s one entry, and the reason it is recorded there: an
        // `availabilityStartTime` ahead of the stream's real start publishes every segment in the future,
        // so no load fails, no rung is offered and there is no failure to classify — the viewer watches a
        // spinner. That record is untouched by this ticket; what changes is that the defect now has a name
        // and a citation. It is the shape of postmortem this phase exists for, and its classification is
        // null for a reason that is not the absence of the resilience module.
        val entry = HostileManifests.dashAvailabilityStartTimeSkew()
        val player = playedOut(entry, resilience = Resilience.standard())
        assertWithMessage("no load failed, so nothing was classified").that(player.playerError).isNull()

        val report = examine(entry, classification = player.playerError?.let(player::classify))

        assertThat(report.classification).isNull()
        assertThat(report.findings.map { it.pathology })
            .containsExactly(Pathology.DASH_AVAILABILITY_START_TIME_SKEW)
        player.release()
    }

    @Test
    fun aConsumerWithNoResilienceModuleGetsTheFindingsAndNoClassification() {
        // ADR-0011 rule 14's existing behaviour surfacing here rather than a new rule, and ADR-0015 rule 1's
        // reason for keeping `superplayer-resilience` off this module's own classpath: a player built
        // without it classifies nothing, so `classify` answers null and the report carries the findings
        // alone. The same content as the first test, which fails on this player too — core raises the
        // typed exception whether or not anything is there to classify it.
        val entry = HostileManifests.dashShortTimeShiftBufferDepth()
        val player = playedOut(entry, resilience = null)
        val error = checkNotNull(player.playerError) { "this entry fails on a core-only player too" }
        assertWithMessage("a player with no resilience classifies nothing").that(player.classify(error)).isNull()

        val report = examine(entry, classification = player.classify(error))

        assertThat(report.classification).isNull()
        assertThat(report.findings.map { it.pathology })
            .contains(Pathology.DASH_SHORT_TIME_SHIFT_BUFFER_DEPTH)
        player.release()
    }

    /**
     * Plays [entry] on a player built with [resilience] until it fails, and answers what `ErrorClassifier`
     * made of that failure — read through `player.classify`, which is the door a consumer and `QoeCollector`
     * both use (ADR-0011 rule 3).
     */
    private fun classificationOf(
        entry: HostileStream,
        resilience: PlaybackResilience?,
        faults: FaultScript = FaultScript.NONE,
    ): SuperPlayerError {
        val player = playedOut(entry, resilience, faults)
        try {
            val error = checkNotNull(player.playerError) { "$entry was expected to fail and did not" }
            return checkNotNull(player.classify(error)) { "$entry failed unclassified" }
        } finally {
            player.release()
        }
    }

    /**
     * Builds a player of [entry] and runs the session to wherever it gets: an error, the end of the media,
     * or the observer's budget. The player is the caller's to read and to release, which is the whole
     * reason `HostileObservation.observe` cannot stand in — it answers an outcome and keeps no player.
     */
    private fun playedOut(
        entry: HostileStream,
        resilience: PlaybackResilience?,
        faults: FaultScript = FaultScript.NONE,
    ): SuperPlayer {
        val player = harness.buildPlayer(
            content = TestContent.hostile(entry),
            resilience = resilience,
            faults = faults,
        )
        player.setMediaRequest(requestFor(entry))
        player.prepare()
        player.play()
        var advanced = 0L
        while (advanced < HostileObservation.OBSERVATION_MS && player.playerError == null &&
            player.playbackState != Player.STATE_ENDED
        ) {
            // In load-sized steps, as `HostileObservation` does: one long advance gives the engine a single
            // pass and never reaches the segment the pathology is about.
            harness.advanceTimeInStepsMs(player, HostileObservation.STEP_MS)
            advanced += HostileObservation.STEP_MS
        }
        return player
    }

    /** The postmortem itself: the request the session was playing, and what it ended on. */
    private fun examine(
        entry: HostileStream,
        classification: SuperPlayerError?,
        faults: FaultScript = FaultScript.NONE,
    ): DiagnosticReport {
        // A doctor of its own environment, fetching the same bytes under the same faults the session met:
        // what a support engineer runs afterwards is a second fetch and not a replay of the first.
        val environment = harness.diagnosticEnvironment(TestContent.hostile(entry), faults = faults)
        return MediaSourceDoctor.Builder(context)
            .setEnvironment(environment)
            .build()
            .examine(requestFor(entry), classification)
    }

    /** What an app would ask about [entry]: an identity of its own and the stream's URI as its source. */
    private fun requestFor(entry: HostileStream): MediaRequest =
        MediaRequest.Builder(entry.id).addSource(entry.sourceUri).build()
}
