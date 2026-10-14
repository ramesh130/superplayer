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

package com.superplayer.telemetry

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.testkit.GoldenFile
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testkit.ThroughputTrace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The golden traces: one whole session per case, traced by [SessionTraceRecorder] and held to the
 * committed file under `src/test/golden`. `docs/testing.md`, *Golden traces*, says what a diff here
 * means and `./gradlew updateGoldenTraces` is how an intended one is accepted.
 *
 * Every case plays through the harness and nothing else, so what a golden pins is the library's
 * behaviour over a known stream on a known network — not the machine it ran on. Every case is a
 * real protocol over a real transfer, because that is the path whose loading threads the harness
 * owns; `docs/testing.md` says why the described fake source is not golden material.
 */
@RunWith(AndroidJUnit4::class)
class GoldenTraceTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun hlsOnDemand() {
        GoldenFile.check("hls-vod.trace", play(TestContent.hls()))
    }

    @Test
    fun dashOnDemand() {
        GoldenFile.check("dash-vod.trace", play(TestContent.dash()))
    }

    @Test
    fun hlsOnDemandOverThreeG() {
        GoldenFile.check("hls-vod-three-g.trace", play(TestContent.hls(), network = NetworkProfile.THREE_G.trace))
    }

    @Test
    fun dashOnDemandOverLteWithDropouts() {
        GoldenFile.check(
            "dash-vod-lte-with-dropouts.trace",
            play(TestContent.dash(), network = NetworkProfile.LTE_WITH_DROPOUTS.trace),
        )
    }

    private fun play(content: TestContent, network: ThroughputTrace? = null): String {
        val recorder = SessionTraceRecorder()
        val collector = QoeCollector(recorder)
        val player = harness.buildPlayer(content = content, telemetry = collector, network = network)
        recorder.attach(player)
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content", END_BOUND_MS) {
            it.playbackState == Player.STATE_ENDED
        }
        harness.release(player)
        assertThat(collector.awaitDelivered(DELIVERY_TIMEOUT_MS)).isTrue()
        return recorder.trace().format()
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
        const val END_BOUND_MS = 120_000L
        const val DELIVERY_TIMEOUT_MS = 10_000L
    }
}
