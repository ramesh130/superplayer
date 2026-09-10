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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The harness's own tests: a test-support module that is wrong is worse than none, because every
 * failure it causes is read as a failure of the code under test.
 *
 * What is pinned here is the two claims every user of it depends on — that a player it builds
 * actually plays and renders, and that the two clocks it drives stay equal.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackHarnessTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aPlayerItBuildsReachesReadyAndRendersAFirstFrame() {
        var firstFrames = 0
        val player = harness.buildPlayer()
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrames++
            }
        })

        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        // The metric the whole schema is anchored on cannot be measured if this callback never
        // arrives, so it is the first thing this module has to be able to promise.
        assertThat(firstFrames).isEqualTo(1)
    }

    @Test
    fun timeAdvancesByExactlyTheAmountAsked() {
        val player = harness.buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)

        val before = harness.elapsedRealtimeMs()
        harness.advanceTimeMs(player, 1_500)

        // Exact, not approximate. Every duration a telemetry test asserts on is a difference of two
        // of these readings, and a clock that drifted by a scheduler quantum would turn every one of
        // those assertions into a tolerance.
        assertThat(harness.elapsedRealtimeMs() - before).isEqualTo(1_500)
    }

    @Test
    fun aStalledRendererPutsThePlayerIntoBufferingAndResumingTakesItOut() {
        val player = harness.buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)

        harness.stallRendering(player)
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)

        harness.resumeRendering(player)
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    @Test
    fun aFailingRendererSurfacesAsAPlayerError() {
        val player = harness.buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)

        harness.failRendering(player)

        assertThat(player.playerError).isNotNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
        const val SOURCE = "fake://superplayer.test/never-fetched"
    }
}
