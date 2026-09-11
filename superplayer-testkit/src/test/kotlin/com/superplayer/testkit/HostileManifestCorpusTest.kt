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

package com.superplayer.testkit

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What SuperPlayer does with [HostileManifests] today — recorded, not asserted as correct.
 *
 * Most of this corpus is not handled, and is not meant to be yet: `PRD.md` puts the fixes in Phases
 * 4 through 9, and `MediaSourceDoctor` — the thing that will eventually diagnose each entry — is
 * Phase 9. What this test does is pin the *current* behaviour of every entry in one table, so that a
 * later phase's improvement arrives as a visible diff in [RECORDED] rather than as a silent change
 * nobody reviews, and so that adding a pathology and forgetting to play it is a failure.
 *
 * One test for the whole corpus rather than one per entry, deliberately: the record is the table,
 * and a diff of fifteen rows against fifteen rows is what a reviewer should be reading. A per-entry
 * test would report the first regression and hide the rest.
 */
@RunWith(AndroidJUnit4::class)
class HostileManifestCorpusTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /**
     * How far a session gets. Ordered worst to best, and deliberately coarse.
     *
     * Coarse because the corpus is a baseline and not a specification: what a later phase has to be
     * able to show is that an entry moved from [FAILS] to [PLAYS_TO_END], and a finer vocabulary
     * would make this table churn on Media3 upgrades without anything having changed.
     */
    private enum class Outcome {
        /** The session raised a `PlaybackException`. */
        FAILS,

        /** No error, and never ready: the player never had anything to render. */
        NEVER_STARTS,

        /** Ready at some point, and buffering when the budget ran out. */
        STALLS,

        /**
         * Started without an error, but reported a position outside the media it was served: before
         * its start, or more than a segment past its end. Observed, not judged — a position the
         * stream cannot contain is a fact about the session whatever state it ended in.
         */
        DEGRADES,

        /**
         * Ready at some point, and still ready when the budget ran out — which for live content,
         * which never ends, is what playing correctly looks like.
         */
        STILL_PLAYING,

        /** Reached `STATE_ENDED` — the pathology cost the session nothing observable here. */
        PLAYS_TO_END,
    }

    @Test
    fun everyPathologyIsPlayedAndItsBehaviourRecorded() {
        val observed = HostileManifests.all().associate { it.id to observe(it) }

        // The whole table at once, so a run reports every row that moved rather than the first.
        assertThat(observed).containsExactlyEntriesIn(RECORDED)
    }

    @Test
    fun aHealthyLiveStreamPlaysOnInThisHarness() {
        // The control for every live row in the record. Without it, a live entry that fails could be
        // failing for its defect or because live DASH does not play here at all — and those are
        // different findings. It is kept out of the corpus, because it is not a pathology.
        val baseline = HostileManifests.dashLiveBaseline()

        assertThat(baseline.validity).isEqualTo(HostileStream.Validity.HEALTHY)
        assertThat(observe(baseline)).isEqualTo(Outcome.STILL_PLAYING)
        assertThat(HostileManifests.all().map { it.id }).doesNotContain(baseline.id)
        assertThat(HostileManifests.all().map { it.validity }).doesNotContain(HostileStream.Validity.HEALTHY)
    }

    @Test
    fun theCorpusLabelsWhatIsMalformedRatherThanMerelyHostile() {
        // A document that breaks a MUST is graded differently from one that is merely hostile,
        // whether or not a given parser happens to reject it. Pinned by id: adding one without
        // labelling it, or relabelling one without saying so, fails here.
        val malformed = HostileManifests.all()
            .filter { it.validity == HostileStream.Validity.MALFORMED }
            .map { it.id }

        assertThat(malformed).containsExactly("hls-dangling-audio-group")
    }

    @Test
    fun everyPathologyCitesASpecClauseAndNamesItsCause() {
        HostileManifests.all().forEach { stream ->
            assertThat(stream.spec).isNotEmpty()
            assertThat(stream.cause).isNotEmpty()
        }
    }

    @Test
    fun theCacheControlPathologyIsCarriedAsDeclaredHeaders() {
        // The one entry whose defect is not in the bytes: `FakeDataSource` reports no response
        // headers, so what the corpus can do is record the mismatch and say so. Asserted rather
        // than left to a reader, because the alternative to this test is a pathology that looks
        // covered and exercises nothing.
        val stream = HostileManifests.hlsCachedLivePlaylist()
        val headers = stream.declaredResponseHeaders

        val playlist = headers.entries.single { it.key.endsWith(".m3u8") }
        val segments = headers.entries.filter { it.key.endsWith(".aac") }

        assertThat(playlist.value["Cache-Control"]).isEqualTo("public, max-age=600")
        assertThat(segments).hasSize(HostileManifests.SEGMENT_COUNT)
        segments.forEach { assertThat(it.value["Cache-Control"]).isEqualTo("no-store") }
    }

    /**
     * Plays [stream] as far as it gets within a bounded budget of playback time, and says how far
     * that was.
     *
     * Bounded rather than played to a state, because much of this corpus never reaches one: a
     * manifest whose segments are not available yet leaves a player buffering forever, which is the
     * behaviour being recorded rather than a hang to wait out.
     */
    private fun observe(stream: HostileStream): Outcome {
        val content = TestContent.hostile(stream)
        val player = harness.buildPlayer(content = content)

        // Sampled from a listener rather than from the loop below: a session that is ready between
        // two advances is a session that started, and a loop reading the state every 500 ms would
        // miss it and record the wrong row.
        var everReady = false
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) everReady = true
                }
            },
        )

        player.setMediaRequest(MediaRequest.Builder(stream.id).addSource(content.sourceUri).build())
        player.prepare()
        player.play()

        // The same budget for every entry, rather than one derived from its length: on-demand
        // entries end well inside it, and live ones never end at all, so what distinguishes them is
        // the state they are in when it runs out.
        var advanced = 0L
        var positionOutsideMedia = false
        while (advanced < OBSERVATION_MS && player.playerError == null &&
            player.playbackState != Player.STATE_ENDED
        ) {
            // In load-sized steps, for `advanceTimeInStepsMs`'s reason: a single long advance gives
            // the engine one pass and never reaches the segment the pathology is about.
            harness.advanceTimeInStepsMs(player, STEP_MS)
            advanced += STEP_MS
            positionOutsideMedia = positionOutsideMedia || isOutsideMedia(player.currentPosition, stream)
        }
        positionOutsideMedia = positionOutsideMedia || isOutsideMedia(player.currentPosition, stream)

        return when {
            player.playerError != null -> Outcome.FAILS
            !everReady -> Outcome.NEVER_STARTS
            positionOutsideMedia -> Outcome.DEGRADES
            player.playbackState == Player.STATE_ENDED -> Outcome.PLAYS_TO_END
            player.playbackState == Player.STATE_READY -> Outcome.STILL_PLAYING
            else -> Outcome.STALLS
        }
    }

    /**
     * Whether [positionMs] is somewhere [stream] has no media: before zero, or more than a segment
     * past the media it carries. A segment of slack, because a player reports its position at the
     * granularity of its own loop and an on-demand session ends a fraction past its last sample.
     */
    private fun isOutsideMedia(positionMs: Long, stream: HostileStream): Boolean =
        positionMs < 0 || positionMs > stream.durationMs + SyntheticHlsStream.SEGMENT_DURATION_MS

    private companion object {

        /**
         * How much playback time each entry is watched for: more than twice an on-demand entry's
         * length, and well short of the media a live entry publishes, so neither kind runs out of
         * stream for a reason unrelated to its pathology.
         */
        const val OBSERVATION_MS = 20_000L

        /** One turn of the loop above: a few loads, so a whole session is tens of turns. */
        const val STEP_MS = 500L

        /**
         * What each entry does today, as of the change that added it. Every id in
         * [HostileManifests.all] appears exactly once, and a change to any value is a behaviour
         * change someone has to explain in a commit message.
         *
         * Not a specification. See the class KDoc: these are the rows a later phase is expected to
         * improve, and improving one means editing this table in the same change.
         */
        val RECORDED = mapOf(
            // Every multivariant-playlist pathology is invisible here, and that is itself the
            // finding: an audio-only stream on a renderer that reports no adaptive support gets one
            // track selected and never switches, so a ladder can be as wrong as it likes. These are
            // the rows Phase 3's ABR work is expected to make distinguishable.
            "hls-ladder-gap" to Outcome.PLAYS_TO_END,
            "hls-overstated-bitrate" to Outcome.PLAYS_TO_END,
            "hls-missing-codecs" to Outcome.PLAYS_TO_END,
            "hls-audio-group-codec-mismatch" to Outcome.PLAYS_TO_END,
            // Malformed, and Media3 plays it anyway: the dangling AUDIO reference is ignored rather
            // than rejected. The label is about the document; this row is about the player.
            "hls-dangling-audio-group" to Outcome.PLAYS_TO_END,
            // 17.5 s of genuinely ragged segments, played end to end.
            "hls-inconsistent-segment-durations" to Outcome.PLAYS_TO_END,
            "hls-discontinuity-without-timeline" to Outcome.PLAYS_TO_END,
            // `PlaylistStuckException`: the frozen-live-stream ticket, reproduced. Unclassified
            // today; issue #66.
            "hls-cached-live-playlist" to Outcome.FAILS,
            "dash-ladder-gap" to Outcome.PLAYS_TO_END,
            "dash-overstated-bitrate" to Outcome.PLAYS_TO_END,
            "dash-missing-codecs" to Outcome.PLAYS_TO_END,
            // Buffers for the whole budget with no error: the stall with nothing to report.
            "dash-availability-start-time-skew" to Outcome.NEVER_STARTS,
            // Ready, but at a *negative* position — before the start of a window shorter than one
            // segment. Issue #67.
            "dash-short-time-shift-buffer-depth" to Outcome.DEGRADES,
            // Indistinguishable from the healthy baseline while playing forwards; the unkept
            // promise only matters to a seek backwards, which nothing here makes yet.
            "dash-missing-time-shift-buffer-depth" to Outcome.STILL_PLAYING,
            "dash-mid-stream-ladder-change" to Outcome.PLAYS_TO_END,
        )
    }
}
