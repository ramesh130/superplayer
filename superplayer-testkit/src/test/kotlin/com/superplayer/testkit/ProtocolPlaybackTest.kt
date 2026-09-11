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
 * Real HLS and real DASH, played through the harness.
 *
 * Every other test here plays content Media3's fakes synthesize, which is enough when the subject is
 * a measurement and not enough when the subject is *what the protocol fetches*. These play
 * `superplayer-testmedia`'s streams through Media3's own playlist and manifest parsers, its own
 * extractors, and a `DataSource` chain — which is what makes the fault injector's addressing
 * observable under the protocols rather than only over a URL sequence a test wrote down.
 */
@RunWith(AndroidJUnit4::class)
class ProtocolPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aSyntheticHlsStreamPlaysThroughATransfer() {
        val content = TestContent.hls()
        val player = play(content)

        // Played to the end rather than merely to ready: every segment the playlist names was
        // fetched, demuxed and rendered, which is what makes this a playback test rather than a
        // parse test.
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)

        // The playlists first, then the segments, and the segments numbered from zero: the whole
        // claim the fault injector's addressing rests on, seen under a real HLS source.
        val requested = harness.requestedResources(player)
        assertThat(requested.first().kind).isEqualTo(ResourceKind.MANIFEST)
        assertThat(requested.filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.index })
            .containsExactlyElementsIn(0 until TestContent.DEFAULT_SEGMENT_COUNT)
            .inOrder()
    }

    @Test
    fun aSyntheticDashStreamPlaysThroughATransfer() {
        val content = TestContent.dash()
        val player = play(content)

        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)

        val requested = harness.requestedResources(player)
        assertThat(requested.first().kind).isEqualTo(ResourceKind.MANIFEST)
        // DASH has an initialization segment and HLS's packed audio does not, which is the one
        // asymmetry between the two streams — and the reason `ResourceKind` names it separately.
        assertThat(requested.map { it.kind }).contains(ResourceKind.INITIALIZATION)
        assertThat(requested.filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.index })
            .containsExactlyElementsIn(0 until TestContent.DEFAULT_SEGMENT_COUNT)
            .inOrder()
    }

    private fun play(content: TestContent): SuperPlayer {
        val player = harness.buildPlayer(content = content)
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, content.durationMs)
        return player
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
    }
}
