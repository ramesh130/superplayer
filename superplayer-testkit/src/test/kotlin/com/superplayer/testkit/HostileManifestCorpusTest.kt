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
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.LiveWindowTooShortException
import com.superplayer.core.MediaRequest
import com.superplayer.core.StaleLivePlaylistException
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
import com.superplayer.testmedia.HostileStream.Severity
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
 *
 * The corpus is recorded twice over, and on purpose. [RECORDED] is [HostileManifests.all] — every
 * pathology at its unmistakable severity, which is what a later phase's fix moves. [GRADED] is
 * [HostileManifests.graded] — the same pathologies at every severity they have, which is where a
 * *cliff* shows: the severity at which an entry stops playing cleanly. A `BENIGN` row that does not
 * play to the end, or on, is a finding in its own right, because it is content a real CDN serves
 * every day.
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
        /** The session raised a `PlaybackException` that nothing classified. */
        FAILS,

        /**
         * The session raised a `PlaybackException` whose cause is one of SuperPlayer's own typed
         * failures: not recovered, but named, with a likely cause a user-facing message can be
         * written from. `PRD.md` Part 4's Phase 5 exit criterion asks this of every fault that does
         * not recover, which is why it is its own row and better than [FAILS].
         */
        FAILS_TYPED,

        /** No error, and never ready: the player never had anything to render. */
        NEVER_STARTS,

        /**
         * Ready at some point, and not ready again for the last [READY_WINDOW_MS] of the budget.
         *
         * Read over a window rather than at the budget's last instant: a session that is playing is
         * momentarily buffering at plenty of instants, and which one the budget ends on is the host's
         * answer rather than the pathology's (issue #91).
         */
        STALLS,

        /**
         * Started without an error, but reported a position outside the media it was served: before
         * its start, or more than a segment past its end. Observed, not judged — a position the
         * stream cannot contain is a fact about the session whatever state it ended in.
         */
        DEGRADES,

        /**
         * Ready within the last [READY_WINDOW_MS] of the budget — which for live content, which never
         * ends, is what playing correctly looks like. See [STALLS] for why it is a window.
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
    fun everyGradedEntryIsPlayedAndItsBehaviourRecorded() {
        val observed = HostileManifests.graded()
            .groupBy { it.id }
            .mapValues { (_, levels) -> levels.associate { it.severity to observe(it) } }

        assertThat(observed).containsExactlyEntriesIn(GRADED)
    }

    @Test
    fun theGradedRecordAgreesWithTheSevereOne() {
        // The severe column of [GRADED] and [RECORDED] describe the same entries, so a change to one
        // that is not made to the other is a table out of date rather than a behaviour that moved.
        assertThat(GRADED.mapValues { (_, levels) -> levels.getValue(Severity.SEVERE) })
            .containsExactlyEntriesIn(RECORDED)
    }

    @Test
    fun allIsTheSevereHalfOfTheGradedCorpus() {
        // `all()` is the table the rest of this class records, and it must not churn because
        // severities were added beside it: the same ids, in the same order, every one severe.
        val severe = HostileManifests.graded().filter { it.severity == Severity.SEVERE }

        assertThat(HostileManifests.all().map { it.id }).containsExactlyElementsIn(severe.map { it.id }).inOrder()
        assertThat(HostileManifests.all().map { it.severity }.distinct()).containsExactly(Severity.SEVERE)
    }

    @Test
    fun aPathologyWithAMagnitudeIsGradedAtEveryLevelAndABinaryOneAtOne() {
        val byId = HostileManifests.graded().groupBy { it.id }

        byId.forEach { (id, levels) ->
            if (levels.any { it.magnitude == null }) {
                // Binary: present or absent, so the one entry is the severe one and says so.
                assertWithMessage(id).that(levels.map { it.severity }).containsExactly(Severity.SEVERE)
                assertWithMessage(id).that(levels.single().magnitude).isNull()
            } else {
                assertWithMessage(id).that(levels.map { it.severity })
                    .containsExactlyElementsIn(Severity.entries).inOrder()
                // Three levels of one pathology that describe themselves identically are one level.
                assertWithMessage(id).that(levels.map { it.magnitude }.distinct()).hasSize(Severity.entries.size)
            }
        }
        // Pinned by id, for the malformed set's reason: grading a binary pathology, or collapsing a
        // graded one to a single level, is a decision a reviewer should see.
        assertThat(byId.filterValues { it.size == 1 }.keys).containsExactly(
            "hls-missing-codecs",
            "hls-audio-group-codec-mismatch",
            "hls-dangling-audio-group",
            "hls-discontinuity-without-timeline",
            "hls-cached-live-playlist",
            "dash-missing-codecs",
            "dash-missing-time-shift-buffer-depth",
        )
    }

    @Test
    fun everyGradedEntryIsServedFromItsOwnDirectory() {
        // What lets the whole graded corpus sit in one `FakeDataSet`: no two entries — not even two
        // severities of one pathology — answer to the same URI.
        val uris = HostileManifests.graded().flatMap { it.resources().keys }

        assertThat(uris).containsNoDuplicates()
    }

    @Test
    fun aHealthyLiveStreamPlaysOnInThisHarness() {
        // The control for every live row in the record. Without it, a live entry that fails could be
        // failing for its defect or because live DASH does not play here at all — and those are
        // different findings. It is kept out of the corpus, because it is not a pathology.
        val baseline = HostileManifests.dashLiveBaseline()

        assertThat(baseline.validity).isEqualTo(HostileStream.Validity.HEALTHY)
        assertThat(observe(baseline)).isEqualTo(Outcome.STILL_PLAYING)
        assertThat(HostileManifests.graded().map { it.id }).doesNotContain(baseline.id)
        assertThat(HostileManifests.graded().map { it.validity }).doesNotContain(HostileStream.Validity.HEALTHY)
    }

    @Test
    fun theObservationBudgetFitsEveryFiniteEntryTwice() {
        // An entry short enough to end inside the budget has to end inside *half* of it, or whether
        // it is recorded as ended depends on how fast the host loads — which is how a 17.5 s entry
        // passed locally and failed on CI under a 20 s budget. Entries longer than the budget are
        // live windows, not expected to end, and are exempt.
        HostileManifests.graded()
            .filter { it.durationMs < OBSERVATION_MS }
            .forEach { assertThat(it.durationMs * 2).isAtMost(OBSERVATION_MS) }
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
        HostileManifests.graded().forEach { stream ->
            assertThat(stream.spec).isNotEmpty()
            assertThat(stream.cause).isNotEmpty()
        }
    }

    @Test
    fun theCacheControlPathologyIsCarriedAsDeclaredHeaders() {
        // The one entry whose defect is not in the bytes: `FakeDataSource` reports no response
        // headers, so the corpus declares them and the harness serves them on top. Asserted rather
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

    @Test
    fun theCachedLivePlaylistEndsNamingTheCacheItWasServedThrough() {
        // What the served headers are for. The bytes alone say only that the playlist stopped; the
        // `max-age=600` it arrives with says a shared cache may hold it for ten minutes, which is
        // what turns "the stream is dead" into "a cache is holding the stream" in the error.
        val stream = HostileManifests.hlsCachedLivePlaylist()
        val content = TestContent.hostile(stream)
        val player = harness.buildPlayer(content = content)
        player.setMediaRequest(MediaRequest.Builder(stream.id).addSource(content.sourceUri).build())

        harness.playToFailure(player)

        val cause = player.playerError?.cause
        assertThat(cause).isInstanceOf(StaleLivePlaylistException::class.java)
        cause as StaleLivePlaylistException
        assertThat(cause.likelyCause).isEqualTo(StaleLivePlaylistException.LikelyCause.INTERMEDIARY_CACHE)
        assertThat(cause.servedCacheControl).isEqualTo("public, max-age=600")
        assertThat(cause.playlistUri).endsWith(".m3u8")
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
        // When the player was last seen ready, so that "still playing" is not whichever single instant
        // the budget happened to end on. Deliberately not the position: a live session's position is
        // measured inside a window that slides forward as fast as playback does, so it stands still
        // while the stream plays perfectly — which is most of this corpus.
        var lastReadyAtMs: Long? = null
        while (advanced < OBSERVATION_MS && player.playerError == null &&
            player.playbackState != Player.STATE_ENDED
        ) {
            // In load-sized steps, for `advanceTimeInStepsMs`'s reason: a single long advance gives
            // the engine one pass and never reaches the segment the pathology is about.
            harness.advanceTimeInStepsMs(player, STEP_MS)
            advanced += STEP_MS
            if (player.playbackState == Player.STATE_READY) lastReadyAtMs = advanced
            positionOutsideMedia = positionOutsideMedia || isOutsideMedia(player.currentPosition, stream)
        }
        positionOutsideMedia = positionOutsideMedia || isOutsideMedia(player.currentPosition, stream)

        val error = player.playerError
        val readyAtMs = lastReadyAtMs
        return when {
            error != null && isTyped(error.cause) -> Outcome.FAILS_TYPED
            error != null -> Outcome.FAILS
            !everReady -> Outcome.NEVER_STARTS
            positionOutsideMedia -> Outcome.DEGRADES
            player.playbackState == Player.STATE_ENDED -> Outcome.PLAYS_TO_END
            readyAtMs != null && advanced - readyAtMs <= READY_WINDOW_MS -> Outcome.STILL_PLAYING
            else -> Outcome.STALLS
        }
    }

    /** Whether [cause] is one of the failures SuperPlayer names, rather than the engine's own. */
    private fun isTyped(cause: Throwable?): Boolean =
        cause is StaleLivePlaylistException || cause is LiveWindowTooShortException

    /**
     * Whether [positionMs] is somewhere [stream] has no media: before zero, or more than a segment
     * past the media it carries. A segment of slack, because a player reports its position at the
     * granularity of its own loop and an on-demand session ends a fraction past its last sample.
     */
    private fun isOutsideMedia(positionMs: Long, stream: HostileStream): Boolean =
        positionMs < 0 || positionMs > stream.durationMs + SyntheticHlsStream.SEGMENT_DURATION_MS

    private companion object {

        /**
         * How much playback time each entry is watched for. Two bounds decide it, and both are about
         * an entry running out of *time* or *stream* for a reason unrelated to its pathology:
         *
         * - At least twice the longest on-demand entry — today the 17.5 s ragged-durations one —
         *   because how much playback time a session spends starting depends on the host: the
         *   engine's clock is fake, but loads complete on real threads. At 20 s this left 1.5 s of
         *   margin, and a slower CI runner recorded that entry as still playing.
         *   [theObservationBudgetFitsEveryFiniteEntryTwice] holds the bound.
         * - Well short of the ~120 s a live entry publishes, so a live session is never watched past
         *   the end of its own window.
         */
        const val OBSERVATION_MS = 40_000L

        /** One turn of the loop above: a few loads, so a whole session is tens of turns. */
        const val STEP_MS = 500L

        /**
         * How recently a session must have been ready to count as still playing rather than stalled:
         * a segment's worth of playback time, which is four turns of the loop above.
         *
         * The point is to stop reading one instant. A session that is playing is ready at nearly every
         * turn and momentarily buffering at some of them, so which state the last turn caught was the
         * host's answer rather than the pathology's (issue #91); one that has stopped is never ready
         * again, so any window shorter than the budget separates the two. A segment is the unit a
         * player waits for when it hiccups, which makes it the honest width.
         */
        const val READY_WINDOW_MS = SyntheticHlsStream.SEGMENT_DURATION_MS

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
            // The frozen-live-stream ticket, reproduced and named. These bytes never change, so the
            // reloads past the cache that recover a real one (`LivePlaylistRevalidationTest`) find
            // nothing newer, and the session ends with a `StaleLivePlaylistException` pointing at
            // the cache the served `max-age=600` names — rather than Media3's unclassified
            // `PlaylistStuckException`, which was this row until issue #66.
            "hls-cached-live-playlist" to Outcome.FAILS_TYPED,
            "dash-ladder-gap" to Outcome.PLAYS_TO_END,
            "dash-overstated-bitrate" to Outcome.PLAYS_TO_END,
            "dash-missing-codecs" to Outcome.PLAYS_TO_END,
            // Buffers for the whole budget with no error: the stall with nothing to report.
            "dash-availability-start-time-skew" to Outcome.NEVER_STARTS,
            // A window shorter than one segment holds no playhead: a segment is fetchable only once
            // complete, so every playable position lies before the window's start. Media3 played it
            // anyway, audibly and seconds outside the window, at a negative position — `DEGRADES`
            // until issue #67, which ends it with a `LiveWindowTooShortException` instead.
            "dash-short-time-shift-buffer-depth" to Outcome.FAILS_TYPED,
            // Indistinguishable from the healthy baseline while playing forwards; the unkept
            // promise only matters to a seek backwards, which nothing here makes yet.
            "dash-missing-time-shift-buffer-depth" to Outcome.STILL_PLAYING,
            "dash-mid-stream-ladder-change" to Outcome.PLAYS_TO_END,
        )

        /**
         * What each entry of [HostileManifests.graded] does today, one row per pathology and one
         * column per severity it is generated at. Every pathology appears exactly once, with exactly
         * the severities it has, and its `SEVERE` column is [RECORDED]'s row —
         * [theGradedRecordAgreesWithTheSevereOne] holds that.
         *
         * Read across a row for the cliff. `BENIGN` is content a doctor must not flag, so a benign
         * cell that is not [Outcome.PLAYS_TO_END] or, for live, [Outcome.STILL_PLAYING] is a finding
         * about the player rather than about the content. As of the change that added this table,
         * there is none.
         */
        val GRADED: Map<String, Map<Severity, Outcome>> = mapOf(
            // Flat across every level, for [RECORDED]'s reason: one audio track selected and never
            // switched, so no ladder or declared bitrate can matter here. A flat row is not "no cliff";
            // it is a cliff this harness cannot see until Phase 3's ABR work gives it a second track.
            "hls-ladder-gap" to graded(Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END),
            "hls-overstated-bitrate" to graded(Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END),
            "hls-missing-codecs" to binary(Outcome.PLAYS_TO_END),
            "hls-audio-group-codec-mismatch" to binary(Outcome.PLAYS_TO_END),
            "hls-dangling-audio-group" to binary(Outcome.PLAYS_TO_END),
            "hls-inconsistent-segment-durations" to graded(Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END),
            "hls-discontinuity-without-timeline" to binary(Outcome.PLAYS_TO_END),
            "hls-cached-live-playlist" to binary(Outcome.FAILS_TYPED),
            "dash-ladder-gap" to graded(Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END),
            "dash-overstated-bitrate" to graded(Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END),
            "dash-missing-codecs" to binary(Outcome.PLAYS_TO_END),
            // A skew of one segment only adds latency, which this table cannot see; an hour means
            // nothing is available at all.
            "dash-availability-start-time-skew" to graded(Outcome.STILL_PLAYING, Outcome.STILL_PLAYING, Outcome.NEVER_STARTS),
            // The cliff is at one segment, not below it: a window exactly one segment deep already
            // leaves a playhead outside it the moment a fetch takes any time, so `BORDERLINE` fails
            // named as `SEVERE` does — both were `DEGRADES`, at a negative position, until issue #67.
            // Four segments play on untouched, which is the false positive that must not happen.
            "dash-short-time-shift-buffer-depth" to graded(Outcome.STILL_PLAYING, Outcome.FAILS_TYPED, Outcome.FAILS_TYPED),
            "dash-missing-time-shift-buffer-depth" to binary(Outcome.STILL_PLAYING),
            "dash-mid-stream-ladder-change" to graded(Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END, Outcome.PLAYS_TO_END),
        )

        /** A row for a pathology with a magnitude: one outcome per severity, mildest first. */
        private fun graded(benign: Outcome, borderline: Outcome, severe: Outcome): Map<Severity, Outcome> =
            mapOf(Severity.BENIGN to benign, Severity.BORDERLINE to borderline, Severity.SEVERE to severe)

        /** A row for a binary pathology, generated at one severity because it has no other. */
        private fun binary(severe: Outcome): Map<Severity, Outcome> = mapOf(Severity.SEVERE to severe)
    }
}
