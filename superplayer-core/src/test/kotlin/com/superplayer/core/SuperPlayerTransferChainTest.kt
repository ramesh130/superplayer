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

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The transfer chain `SuperPlayer.Builder.build()` assembles, driven for real.
 *
 * Every other test in this module substitutes Media3's `FakeDataSource` through the builder's
 * internal engine configurator, which is the right seam for a test about playback — and precisely
 * the wrong one for a test about what SuperPlayer loads *through*, because the substitution replaces
 * the whole chain. So this class replaces nothing below the clock and plays a `file:` URI, which
 * `DefaultDataSource` resolves without a network.
 *
 * What it is here to catch is a regression in the composition itself. `TransferChain` exists so that
 * cache, measurement, CMCD and header refresh have one documented place to insert themselves; the
 * cost of owning that assembly instead of letting `ExoPlayer.Builder` default it is that SuperPlayer
 * can now get it wrong — a missing scheme, a factory that resolves nothing — in a way that no test
 * driving `FakeDataSource` would ever see.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerTransferChainTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    /** Where the synthetic stream is written, and what removes it afterwards. */
    @get:Rule
    val streamDirectory: TemporaryFolder = TemporaryFolder()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    @Test
    fun theChainABuiltPlayerLoadsThroughResolvesContentAndPlaysIt() {
        val playlistUri = SyntheticHlsStream.writeTo(streamDirectory.root)
        val player = harness.buildPlayerOnItsOwnTransferChain()

        player.setMediaItem(MediaItem.fromUri(playlistUri))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        // Ready over a real chain means the playlist, the media playlist and the segment were all
        // fetched and demuxed: the whole chain resolved, not merely its outermost source.
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.duration).isEqualTo(SyntheticHlsStream.DURATION_MS)
        assertThat(player.playerError).isNull()
    }

    @Test
    fun aTestsOwnDataSourceStillWinsOverTheChainTheBuilderInstalls() {
        // The seam `docs/testing.md` depends on: whatever `build()` assembles by default, an engine
        // configurator installed afterwards replaces it. The stream here is served entirely by
        // `FakeDataSource` from a `fake:` URI that no real data source could open, so reaching
        // STATE_READY is only possible if the substitution took effect.
        val player = harness.buildPlayer()

        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(player.playerError).isNull()
    }
}
