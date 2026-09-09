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

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The project's single test seam, established here and binding on every later change: playback is
 * driven through SuperPlayer's public [Player] API, against a synthetic HLS stream served by
 * Media3's fake data source, on a fake clock, on the JVM. No device, no network, no assertion that
 * reaches past the facade.
 *
 * That constraint is what makes the tests worth anything. A test that reached for `exoPlayer` would
 * be asserting on Media3's behaviour, which Media3 already tests; asserting through the facade is
 * how a regression in *SuperPlayer's* contract with its consumers gets caught.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerPlaybackTest {

    /**
     * Robolectric has no real codecs. This installs shadow ones for the formats Media3 supports, so
     * the renderer pipeline runs for real rather than being replaced by a fake renderer — the
     * decoder is the only part that is a stand-in.
     */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    /** Builds the players and releases them: the seam itself lives in [SuperPlayerHarness]. */
    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private lateinit var player: SuperPlayer

    @Before
    fun setUp() {
        player = harness.buildPlayer()
    }

    /**
     * Everything a caller does to get from a built player to a playable one, and nothing more: set a
     * media item, prepare, wait. It is public API the whole way, which is the point.
     */
    private fun prepareUntilReady() {
        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()

        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
    }

    @Test
    fun preparingAnHlsStreamReachesReadyAndReportsTheSelectedTrack() {
        prepareUntilReady()

        val tracks = player.currentTracks

        // The audio rendition, and the in-band ID3 track HLS always exposes alongside it. Asserting
        // the whole set rather than just the audio group means a stray extra track — a symptom of
        // the source being built differently than intended — fails here.
        assertThat(tracks.groups.map { it.type })
            .containsExactly(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA)

        val audioGroup = tracks.groups.single { it.type == C.TRACK_TYPE_AUDIO }
        assertThat(audioGroup.isSelected).isTrue()
        assertThat(audioGroup.isTrackSelected(0)).isTrue()

        // The format the facade reports is the one the synthetic playlist declared, demuxed out of
        // the ADTS segment: the whole path from manifest to track selection actually ran.
        val format = audioGroup.getTrackFormat(0)
        assertThat(format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC)
        assertThat(format.codecs).isEqualTo(SyntheticHlsStream.DECLARED_CODECS)
        assertThat(format.bitrate).isEqualTo(SyntheticHlsStream.DECLARED_BITRATE_BPS)
    }

    @Test
    fun theWrappedEngineIsReachableAndIsWhatTheFacadeDelegatesTo() {
        prepareUntilReady()

        // ADR-0001 rule 2's named exception. The point of the escape hatch is that it is the live
        // engine the facade drives — not a copy and not a detached one — so the check is that state
        // set through the facade is observable on it, and that its state is what the facade reports.
        //
        // Asserted behaviourally rather than by identity: the facade delegates through a private
        // ForwardingPlayer, so `exoPlayer === someExposedDelegate` is not a claim the public API can
        // make any more, and it was never the claim that mattered.
        player.playWhenReady = true

        assertThat(player.exoPlayer.playWhenReady).isTrue()
        assertThat(player.exoPlayer.playbackState).isEqualTo(player.playbackState)
        assertThat(player.exoPlayer.currentMediaItem).isEqualTo(player.currentMediaItem)
    }
}
