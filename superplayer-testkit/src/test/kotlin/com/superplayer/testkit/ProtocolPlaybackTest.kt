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
        // fetched, demuxed and rendered, which is what makes this a playback test rather than a parse
        // test. `play` waits for that end and fails naming the state that arrived instead, so what is
        // left to assert is what the session fetched on the way.
        assertThat(player.playerError).isNull()

        // The playlists first, then the segments, and the segments numbered from zero: the whole
        // claim the fault injector's addressing rests on, seen under a real HLS source.
        //
        // Two manifests, because HLS reads a multivariant playlist and then a media playlist — and
        // `ResourceAddressBook.kindOf` has to classify both by their `.m3u8` name. No
        // initialization segment at all: packed audio has none, which is the one asymmetry between
        // the protocols and the reason a `ResourceKind.INITIALIZATION` fault addresses nothing here.
        val requested = harness.requestedResources(player)
        assertThat(requested.filter { it.kind == ResourceKind.MANIFEST }).hasSize(2)
        assertThat(requested.first().kind).isEqualTo(ResourceKind.MANIFEST)
        assertThat(requested.map { it.kind }).doesNotContain(ResourceKind.INITIALIZATION)
        assertThat(requested.filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.index })
            .containsExactlyElementsIn(0 until TestContent.DEFAULT_SEGMENT_COUNT)
            .inOrder()
    }

    @Test
    fun aSyntheticDashStreamPlaysThroughATransfer() {
        val content = TestContent.dash()
        val player = play(content)

        assertThat(player.playerError).isNull()

        // One manifest — the MPD — where HLS reads two, and one initialization segment where HLS
        // has none. The mirror of the HLS assertions above, and between them they are what
        // `docs/testing.md` claims the resource-kind heuristic does under the real parsers.
        val requested = harness.requestedResources(player)
        assertThat(requested.filter { it.kind == ResourceKind.MANIFEST }).hasSize(1)
        assertThat(requested.first().kind).isEqualTo(ResourceKind.MANIFEST)
        assertThat(requested.filter { it.kind == ResourceKind.INITIALIZATION }).hasSize(1)
        assertThat(requested.filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.index })
            .containsExactlyElementsIn(0 until TestContent.DEFAULT_SEGMENT_COUNT)
            .inOrder()
    }

    private fun play(content: TestContent): SuperPlayer {
        val player = harness.buildPlayer(content = content)
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        // Waited for, not watched for exactly the content's length. Segments load on real threads,
        // so a session that is one segment short when its own duration has passed is a busy machine
        // rather than a stream that did not play (issue #91). Twice the content is a bound no
        // healthy session comes near.
        harness.advanceUntil(player, "the end of the content", 2 * content.durationMs) {
            it.playbackState == Player.STATE_ENDED
        }
        return player
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
    }
}
