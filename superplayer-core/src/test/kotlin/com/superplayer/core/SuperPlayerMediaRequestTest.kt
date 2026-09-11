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

package com.superplayer.core

import androidx.media3.common.Player
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MediaRequest] driven through the facade, on the seam `docs/testing.md` describes: synthetic
 * streams over Media3's fake data source, a fake clock, and every assertion made through the public
 * API.
 *
 * The two synthetic streams stand in for two pieces of content here rather than for two protocols —
 * what these tests are about is identity, source ordering and start position, none of which knows
 * what a manifest looks like.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerMediaRequestTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private lateinit var player: SuperPlayer

    @Before
    fun setUp() {
        // Both streams, so that a test can move between two pieces of content on one player — which
        // is what makes a remembered position something other than the only position there is.
        player = harness.buildPlayer(
            fakeDataSet = SyntheticDashStream.addTo(SyntheticHlsStream.addTo(FakeDataSet())),
        )
    }

    /**
     * Replacing what a *ready* player is playing is the interesting case for most of these tests,
     * and Media3 masks the new item's timeline and position before the playback thread has seen the
     * command. Draining the pending commands first is what makes the awaited READY the new item's
     * rather than the outgoing one's still standing.
     */
    private fun prepareUntilReady(request: MediaRequest) {
        player.setMediaRequest(request)
        player.prepare()

        harness.settle(player)
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
    }

    @Test
    fun contentIdentityIsCarriedSeparatelyFromTheSourceUrl() {
        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )

        // The whole point of the identifier: the thing that says *what* is playing is not the thing
        // that says where it came from. A second CDN host or a second bitrate is the same content.
        val item = checkNotNull(player.currentMediaItem)
        assertThat(item.mediaId).isEqualTo(EPISODE)
        assertThat(item.mediaId).isNotEqualTo(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
        assertThat(item.localConfiguration?.uri.toString())
            .isEqualTo(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
    }

    @Test
    fun theFirstSourceIsPlayedAndTheRestAreRetainedUnused() {
        val request =
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .addSource(UNSERVED_SOURCE_URI)
                .build()

        // `UNSERVED_SOURCE_URI` is in no FakeDataSet, so the fake data source fails on any request
        // for it. Reaching READY is therefore evidence that the second source was never opened,
        // rather than a claim that it wasn't.
        prepareUntilReady(request)

        assertThat(player.currentMediaItem?.localConfiguration?.uri.toString())
            .isEqualTo(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)

        // Retained, in order, for the failover that #8 deliberately leaves to later work.
        assertThat(request.sources.map { it.toString() })
            .containsExactly(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI, UNSERVED_SOURCE_URI)
            .inOrder()
    }

    @Test
    fun aRequestWithNoStartPositionBeginsAtTheStart() {
        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )

        assertThat(player.currentPosition).isEqualTo(0)
    }

    @Test
    fun aRequestWithAStartPositionBeginsThere() {
        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.At(MID_CONTENT_MS))
                .build(),
        )

        assertThat(player.currentPosition).isEqualTo(MID_CONTENT_MS)
    }

    @Test
    fun aStartPositionBeyondTheContentDurationLandsAtTheEndRatherThanAtTheStart() {
        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(
                    MediaRequest.StartPosition.At(SyntheticHlsStream.DURATION_MS + 5_000),
                )
                .build(),
        )
        player.prepare()

        // The timeline is what carries the duration the position gets resolved against, so it has
        // to have arrived before the resolved position means anything.
        TestPlayerRunHelper.advance(player).untilTimelineChanges()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(player.duration).isEqualTo(SyntheticHlsStream.DURATION_MS)

        // The documented behaviour: clamped to the end of the content, not quietly reinterpreted as
        // the start. Restarting would look like a successful playback of the wrong thing, which is
        // the worse of the two failure modes for a caller whose stored position has gone bad.
        assertThat(player.currentPosition).isAtLeast(player.duration - 1)

        // Nothing is left to play from there: starting playback ends it. The position runs a few
        // milliseconds past the declared duration because the last segment carries whole AAC frames
        // rather than stopping on the playlist's rounded EXTINF, which is ordinary for HLS.
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_ENDED)

        assertThat(player.currentPosition).isAtLeast(player.duration)
    }

    @Test
    fun resumeReturnsToWhereTheSameContentWasLeft() {
        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.At(MID_CONTENT_MS))
                .build(),
        )

        prepareUntilReady(
            MediaRequest.Builder(TRAILER)
                .addSource(SyntheticDashStream.MANIFEST_URI)
                .build(),
        )
        assertThat(player.currentPosition).isEqualTo(0)

        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
                .build(),
        )

        assertThat(player.currentPosition).isEqualTo(MID_CONTENT_MS)
    }

    @Test
    fun resumeIsKeyedByContentIdentityRatherThanBySourceUrl() {
        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.At(MID_CONTENT_MS))
                .build(),
        )

        prepareUntilReady(
            MediaRequest.Builder(TRAILER)
                .addSource(SyntheticDashStream.MANIFEST_URI)
                .build(),
        )

        // The same content, served from somewhere else: the defect this identifier exists to close
        // is that a second URL for one piece of content looks like a second piece of content.
        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticDashStream.MANIFEST_URI)
                .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
                .build(),
        )

        assertThat(player.currentPosition).isEqualTo(MID_CONTENT_MS)
    }

    @Test
    fun resumeWithNothingKnownAboutTheContentBeginsAtTheStart() {
        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
                .build(),
        )

        assertThat(player.currentPosition).isEqualTo(0)
    }

    @Test
    fun resumingContentThatPlayedToTheEndBeginsAtTheStart() {
        player.playWhenReady = true
        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_ENDED)
        // Back to a paused player, so what the rest of this test observes is where playback was put
        // rather than how far it then got.
        player.playWhenReady = false

        prepareUntilReady(
            MediaRequest.Builder(TRAILER)
                .addSource(SyntheticDashStream.MANIFEST_URI)
                .build(),
        )

        // Remembering the end of finished content would resume into a player that immediately ends
        // again, which is a worse answer than the obvious one.
        prepareUntilReady(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
                .build(),
        )

        assertThat(player.currentPosition).isEqualTo(0)
    }

    @Test
    fun onlyTheMostRecentlyUsedContentKeepsItsPosition() {
        // No `prepare` anywhere in this test: setting a request is enough for the player to report
        // the item and the start position, and remembering happens on the way past. That keeps a
        // test about a 128-entry bound from loading 130 streams.
        fun request(contentId: String, at: Long) =
            MediaRequest.Builder(contentId)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.At(at))
                .build()

        fun resume(contentId: String) =
            MediaRequest.Builder(contentId)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
                .build()

        // Comfortably past the bound rather than exactly on it: the test is about what happens
        // beyond the limit, not about where the limit falls to the entry.
        val fillerCount = SuperPlayer.MAX_REMEMBERED_POSITIONS + 5

        player.setMediaRequest(request(EPISODE, MID_CONTENT_MS))
        repeat(fillerCount) { index ->
            player.setMediaRequest(request("$EPISODE:filler-$index", MID_CONTENT_MS))
        }
        val newest = "$EPISODE:filler-${fillerCount - 1}"

        // The oldest content has been pushed out, so its resume degrades to the beginning rather
        // than to some other content's position. That degradation is the documented contract of
        // ResumeFromLastKnown, and this is what stops it being silent.
        player.setMediaRequest(resume(EPISODE))
        assertThat(player.currentPosition).isEqualTo(0)

        player.setMediaRequest(resume(newest))
        assertThat(player.currentPosition).isEqualTo(MID_CONTENT_MS)
    }

    @Test
    fun aRequestWithoutASourceIsRejectedWhereItIsBuilt() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                MediaRequest.Builder(EPISODE).build()
            }

        assertThat(failure).hasMessageThat().contains("at least one source")
    }

    @Test
    fun aBlankContentIdentifierIsRejectedWhereItIsBuilt() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaRequest.Builder("  ")
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build()
        }
    }

    @Test
    fun aNegativeStartPositionIsRejectedWhereItIsWritten() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaRequest.StartPosition.At(-1)
        }
    }

    private companion object {
        const val EPISODE = "superplayer:test:episode-1"
        const val TRAILER = "superplayer:test:trailer"

        /** Served by nothing: any attempt to open it fails the test it appears in. */
        const val UNSERVED_SOURCE_URI = "fake://superplayer.test/mirror/master.m3u8"

        /** Comfortably inside both synthetic streams, and not a boundary of either. */
        const val MID_CONTENT_MS = 1_000L
    }
}
