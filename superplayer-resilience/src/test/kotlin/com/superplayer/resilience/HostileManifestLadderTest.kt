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

package com.superplayer.resilience

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.testkit.HostileObservation
import com.superplayer.testkit.HostileOutcome
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hostile corpus played again, this time on a player built with `Resilience.standard()` — the
 * other half of `PRD.md` Part 4's Phase 5 exit criterion, and the half about content rather than
 * about the network.
 *
 * **Why a second table rather than a re-recording of the first.** `HostileManifestCorpusTest` is
 * `superplayer-testkit`'s, which is phase 2, and a phase 2 module may not depend on a phase 5 one
 * (`docs/modules.md`) — so the ladder cannot be installed there at all. The two tables are taken by
 * one observer ([HostileObservation.observe]) over one corpus, and differ in exactly one argument:
 * the resilience. That makes a cell that differs between [WITH_LADDER] and
 * `HostileManifestCorpusTest.RECORDED` the ladder's doing and nothing else, and a cell that agrees
 * one more count of ADR-0011 rule 14 — a player without the module behaves as it did before.
 *
 * **What the criterion asks of this corpus, and what it cannot ask.** Every entry must recover or
 * end named ([everyEntryRecoversOrEndsNamed]), and the entries that do neither are listed in
 * [CANNOT_RECOVER] with the reason each is beyond a ladder rather than left out of the record. They
 * are the honest part of this file: a ladder climbs when a *load* fails, and an entry that never
 * fails a load — a manifest promising media that does not exist yet, a window that keeps playing
 * while promising something it cannot keep — offers it nothing to climb from. Recording them is the
 * finding; tuning the ladder until they went green would be the opposite of one.
 *
 * Only [HostileManifests.all] is played here, not `graded()`. The severity columns are about where a
 * pathology's *cliff* falls, which is a property of the content and of Media3's parsers rather than
 * of the ladder, and they are recorded once, in the core-only table.
 */
@RunWith(AndroidJUnit4::class)
class HostileManifestLadderTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun everyPathologyIsPlayedThroughTheLadderAndItsBehaviourRecorded() {
        val observed = HostileManifests.all().associate { it.id to observe(it) }

        // The whole table at once, for `HostileManifestCorpusTest`'s reason: a run reports every row
        // that moved rather than the first.
        assertThat(observed).containsExactlyEntriesIn(WITH_LADDER)
    }

    @Test
    fun everyEntryRecoversOrEndsNamed() {
        // `PRD.md` Part 4, read off the record above: an entry either played (to the end, or on, for
        // live content) or ended with a name a viewer-facing message and a warehouse row can be
        // written from. Anything else is listed in [CANNOT_RECOVER] with its reason, which is the
        // exception this criterion is allowed and the one it is not allowed to hide.
        WITH_LADDER.forEach { (id, outcome) ->
            if (id in CANNOT_RECOVER) return@forEach
            assertWithMessage(id).that(outcome).isAnyOf(
                HostileOutcome.PLAYS_TO_END,
                HostileOutcome.STILL_PLAYING,
                HostileOutcome.FAILS_TYPED,
            )
        }
    }

    @Test
    fun nothingListedAsBeyondTheLadderIsQuietlyPassing() {
        // The list is an admission, so it must stay one: an entry that has started recovering or
        // ending named belongs in the criterion above rather than in the exceptions, and a reason
        // left in the file after the defect it describes was fixed is worse than no reason at all.
        CANNOT_RECOVER.forEach { id ->
            assertWithMessage(id).that(WITH_LADDER).containsKey(id)
            assertWithMessage(id).that(WITH_LADDER[id]).isNoneOf(
                HostileOutcome.PLAYS_TO_END,
                HostileOutcome.STILL_PLAYING,
                HostileOutcome.FAILS_TYPED,
            )
        }
    }

    @Test
    fun noEntryEndsOnAFailureNothingNamed() {
        // The criterion's sharper half, and the one that holds for every entry without exception: a
        // player with the module never ends a session on an unclassified `PlaybackException`,
        // because rung 6 is reached by every failure that got past the rungs below it (ADR-0011
        // rule 10). An entry the ladder cannot rescue may stall; it may not fail anonymously.
        assertThat(WITH_LADDER.values).doesNotContain(HostileOutcome.FAILS)
    }

    private fun observe(stream: HostileStream): HostileOutcome =
        HostileObservation.observe(harness, stream, Resilience.standard())

    private companion object {

        /**
         * What each entry of [HostileManifests.all] does on a player built with the ladder, as of the
         * change that added it.
         *
         * Read it beside `HostileManifestCorpusTest.RECORDED`, which is the same corpus on a player
         * without the module: a row that differs is a row the ladder moved.
         *
         * **No row differs, and that is the finding rather than a disappointment.** Every entry here
         * is a manifest that lies about media the origin then serves perfectly: nothing 404s, nothing
         * resets, no credential expires, so no load fails and the ladder is never asked a question.
         * What the two typed rows end on is core's own detection (ADR-0011 rule 4) — a live playlist
         * that stopped advancing, a time-shift window no playhead fits in — reaching rung 6 with the
         * likely cause core named still on it. The corpus that does move the ladder is the injected
         * one, in [FaultSweepTest]; the phase's two halves are different kinds of defect, which is
         * why `PRD.md` Part 4 asks for both.
         */
        val WITH_LADDER: Map<String, HostileOutcome> = mapOf(
            "hls-ladder-gap" to HostileOutcome.PLAYS_TO_END,
            "hls-overstated-bitrate" to HostileOutcome.PLAYS_TO_END,
            "hls-missing-codecs" to HostileOutcome.PLAYS_TO_END,
            "hls-audio-group-codec-mismatch" to HostileOutcome.PLAYS_TO_END,
            "hls-dangling-audio-group" to HostileOutcome.PLAYS_TO_END,
            "hls-inconsistent-segment-durations" to HostileOutcome.PLAYS_TO_END,
            "hls-discontinuity-without-timeline" to HostileOutcome.PLAYS_TO_END,
            // Named, and named by core's own layer before Media3 could call the playlist stuck: the
            // `StaleLivePlaylistException` that reaches rung 6 carries the intermediary cache the
            // served `max-age=600` points at (issue #66).
            "hls-cached-live-playlist" to HostileOutcome.FAILS_TYPED,
            "dash-ladder-gap" to HostileOutcome.PLAYS_TO_END,
            "dash-overstated-bitrate" to HostileOutcome.PLAYS_TO_END,
            "dash-missing-codecs" to HostileOutcome.PLAYS_TO_END,
            // The one entry the criterion does not hold for: see [CANNOT_RECOVER].
            "dash-availability-start-time-skew" to HostileOutcome.NEVER_STARTS,
            // A window shallower than a segment takes to become available, named by core's
            // `LiveWindowTooShortException` rather than played at a negative position (issue #67).
            "dash-short-time-shift-buffer-depth" to HostileOutcome.FAILS_TYPED,
            "dash-missing-time-shift-buffer-depth" to HostileOutcome.STILL_PLAYING,
            "dash-mid-stream-ladder-change" to HostileOutcome.PLAYS_TO_END,
        )

        /**
         * The entries that neither recover nor end named, each with the reason it is beyond this
         * ladder rather than a defect in it.
         *
         * One, and it is the shape of defect a ladder cannot reach: an `availabilityStartTime` an
         * hour ahead of the clock means every segment the manifest names is not published yet, so the
         * player waits — correctly, since the packager has told it the media exists in the future —
         * and no load ever fails. There is nothing to retry, no other host to ask, and no failure to
         * classify: the session simply never starts.
         *
         * Diagnosing it needs a *doctor* reading the manifest against the clock rather than a rung
         * reading a failure, which is `PRD.md` §3.6's `MediaSourceDoctor` and Phase 9's. Recorded
         * here so that the phase's claim is read with it: every injected fault recovers or ends
         * typed, and one hostile manifest still leaves a viewer looking at a spinner with the library
         * saying nothing.
         */
        val CANNOT_RECOVER: Set<String> = setOf("dash-availability-start-time-skew")
    }
}
