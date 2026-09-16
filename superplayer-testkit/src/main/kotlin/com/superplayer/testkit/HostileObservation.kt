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
import com.superplayer.core.LiveWindowTooShortException
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackResilience
import com.superplayer.core.StaleLivePlaylistException
import com.superplayer.core.SuperPlayerError
import com.superplayer.testmedia.HostileStream
import com.superplayer.testmedia.SyntheticHlsStream

/**
 * How far a session got with a [HostileStream]. Ordered worst to best, and deliberately coarse.
 *
 * Coarse because the corpus is a baseline and not a specification: what a later phase has to be able
 * to show is that an entry moved from [FAILS] to [PLAYS_TO_END], and a finer vocabulary would make
 * the tables that record it churn on Media3 upgrades without anything having changed.
 */
public enum class HostileOutcome {

    /** The session raised a `PlaybackException` that nothing classified. */
    FAILS,

    /**
     * The session raised a `PlaybackException` whose cause is one of SuperPlayer's own typed
     * failures: not recovered, but named, with a likely cause a user-facing message can be written
     * from. `PRD.md` Part 4's Phase 5 exit criterion asks this of every fault that does not recover,
     * which is why it is its own row and better than [FAILS].
     */
    FAILS_TYPED,

    /** No error, and never ready: the player never had anything to render. */
    NEVER_STARTS,

    /**
     * Ready at some point, and not ready again for the last [HostileObservation.READY_WINDOW_MS] of
     * the budget.
     *
     * Read over a window rather than at the budget's last instant: a session that is playing is
     * momentarily buffering at plenty of instants, and which one the budget ends on is the host's
     * answer rather than the pathology's (issue #91).
     */
    STALLS,

    /**
     * Started without an error, but reported a position outside the media it was served: before its
     * start, or more than a segment past its end. Observed, not judged — a position the stream
     * cannot contain is a fact about the session whatever state it ended in.
     */
    DEGRADES,

    /**
     * Ready within the last [HostileObservation.READY_WINDOW_MS] of the budget — which for live
     * content, which never ends, is what playing correctly looks like. See [STALLS] for why it is a
     * window.
     */
    STILL_PLAYING,

    /** Reached `STATE_ENDED` — the pathology cost the session nothing observable here. */
    PLAYS_TO_END,
}

/**
 * Playing one entry of the hostile corpus and saying how far it got — the observation two recorded
 * tables share.
 *
 * It is here rather than inside either test because there are two of them and they must agree: the
 * core-only record is `superplayer-testkit`'s `HostileManifestCorpusTest`, and the record with
 * ADR-0011's ladder in place is `superplayer-resilience`'s `HostileManifestLadderTest`, in that
 * module because a phase 2 module may not depend on a phase 5 one (`docs/modules.md`). Two copies of
 * this loop would make a diff between the two tables a diff between two observers as much as between
 * two players, which is the one thing the pair exists to show.
 */
public object HostileObservation {

    /**
     * How much playback time each entry is watched for. Two bounds decide it, and both are about an
     * entry running out of *time* or *stream* for a reason unrelated to its pathology:
     *
     * - At least twice the longest on-demand entry — today the 17.5 s ragged-durations one — because
     *   how much playback time a session spends starting depends on the host: the engine's clock is
     *   fake, but loads complete on real threads. At 20 s this left 1.5 s of margin, and a slower CI
     *   runner recorded that entry as still playing. `HostileManifestCorpusTest`'s
     *   `theObservationBudgetFitsEveryFiniteEntryTwice` holds the bound.
     * - Well short of the ~120 s a live entry publishes, so a live session is never watched past the
     *   end of its own window.
     */
    public const val OBSERVATION_MS: Long = 40_000L

    /** One turn of the loop below: a few loads, so a whole session is tens of turns. */
    public const val STEP_MS: Long = 500L

    /**
     * How recently a session must have been ready to count as still playing rather than stalled: a
     * segment's worth of playback time, which is four turns of the loop below.
     *
     * The point is to stop reading one instant. A session that is playing is ready at nearly every
     * turn and momentarily buffering at some of them, so which state the last turn caught was the
     * host's answer rather than the pathology's (issue #91); one that has stopped is never ready
     * again, so any window shorter than the budget separates the two. A segment is the unit a player
     * waits for when it hiccups, which makes it the honest width.
     */
    public const val READY_WINDOW_MS: Long = SyntheticHlsStream.SEGMENT_DURATION_MS

    /**
     * Plays [stream] on a player [harness] builds, as far as it gets within [OBSERVATION_MS] of
     * playback time, and says how far that was.
     *
     * Bounded rather than played to a state, because much of the corpus never reaches one: a manifest
     * whose segments are not available yet leaves a player buffering for ever, which is the behaviour
     * being recorded rather than a hang to wait out.
     *
     * [resilience] is what `SuperPlayer.Builder.setResilience` takes, and null is the player a
     * core-only consumer builds. It is the only difference between the two recorded tables, which is
     * what makes a cell that differs between them the ladder's doing and nothing else.
     */
    @JvmStatic
    @JvmOverloads
    public fun observe(
        harness: PlaybackHarness,
        stream: HostileStream,
        resilience: PlaybackResilience? = null,
    ): HostileOutcome {
        val content = TestContent.hostile(stream)
        val player = harness.buildPlayer(content = content, resilience = resilience)

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

        // The same budget for every entry, rather than one derived from its length: on-demand entries
        // end well inside it, and live ones never end at all, so what distinguishes them is the state
        // they are in when it runs out.
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
            error != null && isTyped(error.cause) -> HostileOutcome.FAILS_TYPED
            error != null -> HostileOutcome.FAILS
            !everReady -> HostileOutcome.NEVER_STARTS
            positionOutsideMedia -> HostileOutcome.DEGRADES
            player.playbackState == Player.STATE_ENDED -> HostileOutcome.PLAYS_TO_END
            readyAtMs != null && advanced - readyAtMs <= READY_WINDOW_MS -> HostileOutcome.STILL_PLAYING
            else -> HostileOutcome.STALLS
        }
    }

    /**
     * Whether [cause] is one of the failures SuperPlayer names, rather than the engine's own.
     *
     * Three types rather than two, and the third is the general one: core detects the two transfer
     * defects a core-only consumer is exposed to and raises them itself (ADR-0011 rule 4), while a
     * player with resilience ends every failure nothing rescued on a `SuperPlayerError` carrying the
     * classifier's own class (rule 10). A session that ends on the third is named exactly as
     * `PRD.md` Part 4 asks, so it belongs in the same row as the first two.
     */
    private fun isTyped(cause: Throwable?): Boolean =
        cause is StaleLivePlaylistException || cause is LiveWindowTooShortException || cause is SuperPlayerError

    /**
     * Whether [positionMs] is somewhere [stream] has no media: before zero, or more than a segment
     * past the media it carries. A segment of slack, because a player reports its position at the
     * granularity of its own loop and an on-demand session ends a fraction past its last sample.
     */
    private fun isOutsideMedia(positionMs: Long, stream: HostileStream): Boolean =
        positionMs < 0 || positionMs > stream.durationMs + SyntheticHlsStream.SEGMENT_DURATION_MS
}
