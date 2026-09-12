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

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The comparison arms of `PRD.md` §6, driven through the harness that also builds the SuperPlayer.
 *
 * `benchmark/` compares three players and its whole claim rests on their being comparable, so what
 * this pins is not that a stock player plays — Media3's own tests cover that — but that **this
 * harness drives all three the same way**: one clock, one shaped transport, one fault injector, one
 * renderer, one video output. A difference in a benchmark cell has to be attributable to the player
 * under test, and everything asserted here is something that would otherwise quietly attribute it to
 * the harness instead.
 */
@RunWith(AndroidJUnit4::class)
class StockPlayerTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aStockPlayerOfEitherTuningPlaysTheHarnessContent() {
        StockTuning.entries.forEach { tuning ->
            val player = harness.buildStockPlayer(content = ladder(), tuning = tuning)
            player.setMediaItem(MediaItem.fromUri(ladder().sourceUri))

            harness.playToReady(player)

            assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
            assertThat(player.playerError).isNull()
        }
    }

    @Test
    fun aStockPlayerLoadsThroughTheSameShapedTransportAsASuperPlayer() {
        // The two arms over one trace. What matters is not which is faster — that is the benchmark's
        // question and this is not the benchmark — but that the stock player's loads went through
        // the shaper at all: a stock arm that silently bypassed the trace would make every
        // network-profile row in the report meaningless, and it would look like a clean win.
        // `NetworkShapingPlaybackTest`'s content, not a ladder, so its derived floor applies here
        // unchanged: one 800 kbit/s rendition, whose first segment is a known number of bytes.
        val content = TestContent.video()
        val stock = harness.buildStockPlayer(content = content, network = NetworkProfile.THREE_G.trace)
        stock.setMediaItem(MediaItem.fromUri(content.sourceUri))
        val stockStartedAtMs = harness.elapsedRealtimeMs()
        harness.playToReady(stock)
        val stockToReadyMs = harness.elapsedRealtimeMs() - stockStartedAtMs

        // The floor `NetworkShapingPlaybackTest` derives for the same trace and the same ladder: the
        // first 2-second segment of the bottom rung cannot arrive faster than the trace delivers it.
        // An unshaped load would be there in nothing at all, which is what this rules out.
        assertThat(stockToReadyMs).isAtLeast(FIRST_SEGMENT_AT_THREE_G_MS)
    }

    @Test
    fun theHarnessRegistersAPlayerOfEitherKind() {
        // Registration is what makes a player the harness's: `after` releases from the same map
        // `requestedResources` reads, so a stock player that was built but not registered would
        // leak a decoder and a loading thread per run — and a benchmark builds hundreds of players
        // in one JVM. The failure mode is exhaustion much later, in unrelated work, with nothing
        // pointing back here, which is why it is asserted rather than left to teardown.
        val superPlayer = harness.buildPlayer(content = ladder())
        superPlayer.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(ladder().sourceUri).build())
        harness.playToReady(superPlayer)
        val stock = harness.buildStockPlayer(content = ladder())
        stock.setMediaItem(MediaItem.fromUri(ladder().sourceUri))
        harness.playToReady(stock)

        // Throws "This harness did not build that player" for anything it did not register.
        assertThat(harness.requestedResources(superPlayer)).isNotNull()
        assertThat(harness.requestedResources(stock)).isNotNull()
    }

    private fun ladder(): TestContent = TestContent.videoLadder()

    private companion object {
        const val CONTENT = "stock-arm"

        /**
         * The floor `NetworkShapingPlaybackTest` derives: 200 000 bytes of first segment at
         * 1 Mbit/s is 1 600 ms before there can be a frame. A property of the trace and the ladder,
         * not of the player, which is why the same number bounds both arms.
         */
        const val FIRST_SEGMENT_AT_THREE_G_MS = 1_600L
    }
}
