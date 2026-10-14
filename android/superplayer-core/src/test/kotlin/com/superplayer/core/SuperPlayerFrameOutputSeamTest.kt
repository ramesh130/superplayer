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

import android.view.Surface
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second output slot ADR-0022 rule 3 adds to core's engine seam, driven by a `PlaybackFrameOutput`
 * hand-written here rather than `superplayer-effects`'.
 *
 * What is asserted is the seam: that a frame output which is also core's extension fills
 * `EngineConfiguration.frameOutput` and nothing else, that the decision's tunneling half yields to it
 * (rule 4), that a player built with protection or for realtime never asks the extension and reports
 * why (rule 6), and — the claim this file exists for — that a player built **without** one fills neither
 * slot (rule 9). That the effect then reaches the engine's video renderer before `prepare()`, and that a
 * protected player plays to the end with it off, is `superplayer-effects`' `FrameOutputPlaybackTest`,
 * through the harness and a real licence server.
 *
 * It reads past the facade where `SuperPlayerOutputSeamTest` does and for its reasons, which
 * `docs/testing.md` records: the filled `EngineConfiguration`, because "the slot is empty" is a claim
 * about construction no playback shows, and the selector's `tunnelingEnabled`, because "no tunneling
 * parameter is laid" is rule 4's claim about a player whose audio-only content plays untunneled either way.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerFrameOutputSeamTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /**
     * ADR-0022 rule 9, counted the ADR-0014 rule 14 way: a player built with a profile alone fills
     * neither output slot, and one built with a stand-in frame output fills that slot with the stand-in's
     * effect — and still not `videoOutput`, so filling one slot is shown not to fill the other. Both
     * players play, so the second is evidence that the counter sees what it counts.
     */
    @Test
    fun aPlayerBuiltWithoutAFrameOutputFillsNeitherSlotAndOneWithItFillsOnlyItsOwn() {
        var withoutSlots: EngineConfiguration? = null
        val without = harness.buildPlayer(alsoConfigure = { withoutSlots = it })
        playUntilReady(without)

        assertThat(withoutSlots?.frameOutput).isNull()
        assertThat(withoutSlots?.videoOutput).isNull()
        assertThat(without.frameOutputOffReason).isNull()

        val output = StandInFrameOutput()
        var withSlots: EngineConfiguration? = null
        val with = harness.buildPlayer(frameOutput = output, alsoConfigure = { withSlots = it })
        playUntilReady(with)

        assertThat(output.asked).isEqualTo(1)
        assertThat(withSlots?.frameOutput).isSameInstanceAs(output.effect)
        assertThat(withSlots?.videoOutput).isNull()
        assertThat(with.frameOutputOffReason).isNull()
    }

    /**
     * ADR-0022 rule 6: a player built with protection has its frame output off for its lifetime. The
     * extension is never asked — so nothing of the module runs — the slot stays empty, and the reason is
     * readable the moment the player exists. Not an error: the player is built and is not failed.
     */
    @Test
    fun aProtectedPlayerNeverAsksTheFrameOutputAndSaysWhy() {
        val output = StandInFrameOutput()
        var slots: EngineConfiguration? = null
        val player = harness.buildPlayer(drm = object : PlaybackDrm {}, frameOutput = output, alsoConfigure = { slots = it })

        assertThat(player.frameOutputOffReason).isEqualTo(FrameOutputOffReason.PROTECTED_CONTENT)
        assertThat(output.asked).isEqualTo(0)
        assertThat(slots?.frameOutput).isNull()

        playUntilReady(player)
        assertThat(player.playerError).isNull()
        assertThat(player.frameOutputOffReason).isEqualTo(FrameOutputOffReason.PROTECTED_CONTENT)
    }

    /**
     * ADR-0022 rule 6's realtime half: a player built with `setRealtime` has the output off with its own
     * reason, and — the control that keeps the rule from being "refuse every combination" — the same
     * stand-in on a player built with neither is asked and runs (the first test).
     */
    @Test
    fun aRealtimePlayerNeverAsksTheFrameOutputAndSaysWhy() {
        val output = StandInFrameOutput()
        var slots: EngineConfiguration? = null
        val player = harness.buildPlayer(realtime = NoRealtimeStreams(), frameOutput = output, alsoConfigure = { slots = it })

        assertThat(player.frameOutputOffReason).isEqualTo(FrameOutputOffReason.REALTIME)
        assertThat(output.asked).isEqualTo(0)
        assertThat(slots?.frameOutput).isNull()

        // Protection is named where both apply, because it is the stronger statement of the two.
        val both = harness.buildPlayer(drm = object : PlaybackDrm {}, realtime = NoRealtimeStreams(), frameOutput = StandInFrameOutput())
        assertThat(both.frameOutputOffReason).isEqualTo(FrameOutputOffReason.PROTECTED_CONTENT)
    }

    /**
     * An effect filled into the slot by some other hand — a test's configurator, which runs after the
     * extensions — is still not laid on a player whose output is off, because what is laid is decided
     * after the seam rather than trusted to it. Read through rule 4's parameter: an effect laid would
     * have stopped tunneling being laid.
     */
    @Test
    fun anEffectFilledByAnyHandIsNotLaidOnAPlayerWhoseOutputIsOff() {
        val player = harness.buildPlayer(
            PlaybackProfile.TV_LEANBACK,
            drm = object : PlaybackDrm {},
            output = TestOutput(),
            frameOutput = StandInFrameOutput(),
            alsoConfigure = { it.frameOutput = object : Effect {} },
        )

        assertThat(tunnelingLaid(player)).isTrue()
    }

    /**
     * ADR-0022 rule 4: a player with both slots filled is built, and the decision's tunneling half yields
     * to the frame output, while `playbackDecision` keeps saying what was asked. The same `TV_LEANBACK`
     * player with the output slot alone lays it, which is the control; and a protected player, whose
     * frame output is off, lays it too, because nothing runs that it would bypass.
     */
    @Test
    fun tunnelingYieldsToARunningFrameOutput() {
        val both = harness.buildPlayer(PlaybackProfile.TV_LEANBACK, output = TestOutput(), frameOutput = StandInFrameOutput())
        val outputAlone = harness.buildPlayer(PlaybackProfile.TV_LEANBACK, output = TestOutput())
        val protectedBoth = harness.buildPlayer(
            PlaybackProfile.TV_LEANBACK,
            drm = object : PlaybackDrm {},
            output = TestOutput(),
            frameOutput = StandInFrameOutput(),
        )

        assertThat(both.playbackDecision.output.tunneling).isTrue()
        assertThat(tunnelingLaid(both)).isFalse()
        assertThat(tunnelingLaid(outputAlone)).isTrue()
        assertThat(tunnelingLaid(protectedBoth)).isTrue()
    }

    /** Whether the engine's selector was asked to tunnel — ADR-0014 rule 7's parameter, which Media3 leaves off. */
    private fun tunnelingLaid(player: SuperPlayer): Boolean =
        (player.exoPlayer.trackSelector as DefaultTrackSelector).parameters.tunnelingEnabled

    private fun playUntilReady(player: SuperPlayer) {
        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()
        TestPlayerRunHelper.playUntilPosition(player.exoPlayer, /* mediaItemIndex= */ 0, /* positionMs= */ 0)
    }

    /** A frame output that fills its slot with an effect of its own, and counts how often it was asked. */
    private class StandInFrameOutput : EngineFrameOutputExtension {
        val effect: Effect = object : Effect {}
        var asked = 0
            private set

        override fun configureEngine(configuration: EngineConfiguration) {
            asked++
            configuration.frameOutput = effect
        }
    }

    /** An output that fills the first slot with a binding that does nothing, so the second has a neighbour. */
    private class TestOutput : EngineOutputExtension {
        override fun configureEngine(configuration: EngineConfiguration) {
            configuration.videoOutput = object : VideoOutputBinding {
                override fun onSurfaceChanged(surface: Surface?) = Unit

                override fun onVideoFrameRate(framesPerSecond: Float?) = Unit

                override fun release() = Unit
            }
        }
    }

    /** Realtime sources answering a scheme no item here uses, so the player is one built for realtime and plays nothing of it. */
    private class NoRealtimeStreams :
        RealtimeSources(
            schemes = setOf("unused-realtime"),
            mediaSources = RealtimeMediaSources { error("no realtime item is played here") as MediaSource },
        )
}
