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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The injector under a real load, driven by a real player through the public API.
 *
 * `FaultInjectionTest` pins what the wrapper does to a transfer. What this pins is the half that
 * only a player can show: that a fault addressed by segment index lands on that segment while the
 * ones before it play, and that it lands there on **every** run — because a suite whose faults moved
 * between runs is a flake generator and gets disabled, which is the same thing as not having one.
 */
@RunWith(AndroidJUnit4::class)
class FaultInjectionPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun withNothingArmedTheContentPlaysThroughTheInjector() {
        // A ladder, because the injector sits in the adaptive load path: Media3's non-adaptive fake
        // synthesizes its samples in memory, so a single-rendition player with nothing armed fetches
        // nothing at all and there is no transparency to demonstrate.
        val player = harness.buildPlayer(content = TestContent.videoLadder(), faults = FaultScript.NONE)
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)
        val positionAtStartMs = player.currentPosition
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        // What the session did over the span rather than the state it is in at the last instant of it:
        // a healthy player is momentarily buffering at plenty of instants, and which one a fixed span
        // ends on is the machine's answer rather than the injector's (issue #91). Playback having
        // moved on across the whole span is the stronger claim in any case — the state at the end
        // says nothing about whether anything happened before it.
        assertThat(player.playerError).isNull()
        assertThat(player.currentPosition).isGreaterThan(positionAtStartMs)
        // Segments were fetched, so the transparent case is transparent about something.
        assertThat(harness.requestedResources(player)).isNotEmpty()
    }

    @Test
    fun aFailedSegmentFailsPlaybackAtThatSegmentOnEveryRun() {
        repeat(RUNS) {
            val script = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT)
                .build()
            val player = play(script)

            // Waited for rather than watched for: the session stops being advanced at the failure
            // instead of running on past it, which makes "and nothing past it was fetched" a claim
            // about the fault rather than about how many loads a span happened to allow.
            harness.advanceUntil(player, "the injected failure", PLAYED_MS) { it.playerError != null }

            // The same segment every time, and *exactly* that far: every segment before the one
            // named was fetched, the one named was reached, and nothing past it was. A fault that
            // fired one segment late would still fail the session and would still have fetched
            // 0..2 — so anything weaker than the whole list would pass for a fault that moved.
            val segments = harness.requestedResources(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }
            assertThat(segments.map { it.index }).containsExactlyElementsIn(0..FAULTED_SEGMENT).inOrder()
        }
    }

    @Test
    fun anExpiredTokenOutlastsTheEngineSOwnRetries() {
        val script = FaultScript.Builder().expireTokenAtSegment(FAULTED_SEGMENT).build()
        val player = play(script)

        harness.advanceUntil(player, "the failure that outlasts the retries", PLAYED_MS) {
            it.playerError != null
        }

        // Media3 retries a failed chunk load before giving up. A single failed request would be
        // absorbed by that and this test would be asserting nothing; a token that stays expired is
        // what actually reaches the player as an error — which is the case `superplayer-resilience`
        // is being built to survive, and until it exists, the case that ends the session.
        assertThat(player.playerError).isNotNull()
    }

    @Test
    fun theSameScriptFailsTheSameSegmentUnderHlsAndUnderDash() {
        // One script, two protocols, and not a URL in either: `ResourceKind.MEDIA_SEGMENT` plus an
        // index is the same sentence under an HLS media playlist and under an MPD's `SegmentList`,
        // which is the whole reason a fault is addressed this way. This is where that claim stops
        // being about a URL sequence and becomes about playback — real parsers, real extractors,
        // real segment fetches — for both protocols at once.
        val faulted = mutableMapOf<String, List<Int>>()

        listOf("HLS" to TestContent.hls(), "DASH" to TestContent.dash()).forEach { (name, content) ->
            val script = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_NOT_FOUND, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT)
                .build()
            val player = harness.buildPlayer(content = content, faults = script)
            player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
            // Waits for the error rather than advancing a fixed span: the two protocols reach the
            // faulted segment after different numbers of fetches — HLS reads a multivariant playlist
            // and then a media playlist before its first segment — and a fixed advance long enough
            // for both would be a number chosen to make the slower one pass.
            harness.playToFailure(player)

            faulted[name] = harness.requestedResources(player)
                .filter { it.kind == ResourceKind.MEDIA_SEGMENT }
                .map { it.index }
        }

        // The segments before the faulted one were fetched, the faulted one was reached, and
        // nothing past it was — identically under both, which is what "the same script" means.
        assertThat(faulted["HLS"]).containsExactlyElementsIn(0..FAULTED_SEGMENT).inOrder()
        assertThat(faulted["DASH"]).isEqualTo(faulted["HLS"])
    }

    private fun play(faults: FaultScript): SuperPlayer {
        val player = harness.buildPlayer(content = TestContent.video(), faults = faults)
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        player.prepare()
        player.play()
        return player
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
        const val SOURCE = "fake://superplayer.test/never-fetched"

        /** Far enough into the content that the faulted segment is reached and played past. */
        const val PLAYED_MS = 20_000L

        /**
         * Late enough that segments play first, so a fault that fired too early is visible — and
         * within [TestContent.DEFAULT_SEGMENT_COUNT], so the synthetic streams have a segment for it
         * to land on.
         */
        const val FAULTED_SEGMENT = 2

        /** Three, because "deterministic" is a claim about repetition and one run cannot make it. */
        const val RUNS = 3
    }
}
