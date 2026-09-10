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
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
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

            harness.advanceTimeInStepsMs(player, PLAYED_MS)

            // The same segment every time, and *exactly* that far: every segment before the one
            // named was fetched, the one named was reached, and nothing past it was. A fault that
            // fired one segment late would still fail the session and would still have fetched
            // 0..2 — so anything weaker than the whole list would pass for a fault that moved.
            assertThat(player.playerError).isNotNull()
            val segments = harness.requestedResources(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }
            assertThat(segments.map { it.index }).containsExactlyElementsIn(0..FAULTED_SEGMENT).inOrder()
        }
    }

    @Test
    fun anExpiredTokenOutlastsTheEngineSOwnRetries() {
        val script = FaultScript.Builder().expireTokenAtSegment(FAULTED_SEGMENT).build()
        val player = play(script)

        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        // Media3 retries a failed chunk load before giving up. A single failed request would be
        // absorbed by that and this test would be asserting nothing; a token that stays expired is
        // what actually reaches the player as an error — which is the case `superplayer-resilience`
        // is being built to survive, and until it exists, the case that ends the session.
        assertThat(player.playerError).isNotNull()
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

        /** Late enough that segments play first, so a fault that fired too early is visible. */
        const val FAULTED_SEGMENT = 2

        /** Three, because "deterministic" is a claim about repetition and one run cannot make it. */
        const val RUNS = 3
    }
}
