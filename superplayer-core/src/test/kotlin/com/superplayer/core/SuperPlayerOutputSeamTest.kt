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

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The slot ADR-0014 rule 3 adds to core's engine seam, driven by a `PlaybackOutput` hand-written here
 * rather than `superplayer-tv`'s.
 *
 * No display is asked anything here — that is `superplayer-tv`'s `FrameRateMatchingTest`, over video
 * declaring a frame rate on a stated television. What is asserted is the seam: that an output which is
 * also core's extension fills the slot, that the engine is then built with Media3's own frame-rate
 * request switched off, that the binding hears every surface the facade is handed and each item's frame
 * rate once — and, the claim this file exists for, that a player built **without** `setOutput` fills
 * nothing and keeps Media3's own strategy (rule 14).
 *
 * It reads past the facade, and `docs/testing.md` records where: the filled `EngineConfiguration`,
 * because "the slot is empty" is a claim about construction no playback shows, and the engine's
 * `videoChangeFrameRateStrategy`, because which frame-rate strategy Media3 was built with is the other
 * half of rule 14 and nothing public reports it.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerOutputSeamTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private val textures = mutableListOf<SurfaceTexture>()

    @After
    fun releaseTextures() {
        textures.forEach(SurfaceTexture::release)
    }

    /**
     * ADR-0014 rule 14, counted: a player built without `setOutput` leaves the slot empty and keeps
     * Media3's seamless-only frame-rate strategy. The same count on a player *with* an output is
     * non-empty, has the strategy off, and the binding was told something, so the counter is shown to
     * see what it counts.
     */
    @Test
    fun aPlayerBuiltWithoutAnOutputFillsNoSlotAndKeepsMediaThreesFrameRateStrategy() {
        var withoutSlot: EngineConfiguration? = null
        val without = harness.buildPlayer(alsoConfigure = { withoutSlot = it })
        without.setVideoSurface(surface())
        playUntilReady(without)

        assertThat(withoutSlot?.videoOutput).isNull()
        assertThat(without.exoPlayer.videoChangeFrameRateStrategy).isEqualTo(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)

        val binding = RecordingBinding()
        var withSlot: EngineConfiguration? = null
        val with = harness.buildPlayer(output = TestOutput(binding), alsoConfigure = { withSlot = it })
        with.setVideoSurface(surface())
        playUntilReady(with)

        assertThat(withSlot?.videoOutput).isSameInstanceAs(binding)
        assertThat(with.exoPlayer.videoChangeFrameRateStrategy).isEqualTo(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        assertThat(binding.events).isNotEmpty()
    }

    /**
     * The frame rate once per item, and null for content with no video: the synthetic HLS stream is
     * audio. A second item is a second report.
     */
    @Test
    fun theBindingHearsEachItemsFrameRateOnce() {
        val binding = RecordingBinding()
        val player = harness.buildPlayer(output = TestOutput(binding))
        val surface = surface()
        player.setVideoSurface(surface)
        playUntilReady(player)
        // Playing on does not report again: a rung switch changes the format, never the item.
        TestPlayerRunHelper.playUntilPosition(player.exoPlayer, /* mediaItemIndex= */ 0, /* positionMs= */ 1_000)

        playUntilReady(player)

        assertThat(binding.events).containsExactly(SurfaceChanged(surface), FrameRate(null), FrameRate(null)).inOrder()
    }

    @Test
    fun theBindingHearsEverySurfaceTheFacadeIsHandedAndItsRelease() {
        val binding = RecordingBinding()
        val player = harness.buildPlayer(output = TestOutput(binding))
        val first = surface()
        val second = surface()

        player.setVideoSurface(first)
        // A clear naming a surface the player does not hold is a no-op, in Media3 and here.
        player.clearVideoSurface(second)
        player.setVideoSurface(second)
        player.clearVideoSurface(second)
        player.setVideoSurface(first)
        player.clearVideoSurface()
        player.release()

        assertThat(binding.events)
            .containsExactly(SurfaceChanged(first), SurfaceChanged(second), SurfaceChanged(null), SurfaceChanged(first), SurfaceChanged(null), Released)
            .inOrder()
    }

    /**
     * A `SurfaceView`'s surface exists only between its holder's callbacks, and is recreated with the
     * view: the binding hears each, and nothing once the holder is cleared.
     */
    @Test
    fun theBindingFollowsAHoldersSurfaceAsItIsCreatedAndDestroyed() {
        val binding = RecordingBinding()
        val player = harness.buildPlayer(output = TestOutput(binding))
        val holder = StatedHolder(surface())

        player.setVideoSurfaceHolder(holder)
        holder.destroy()
        val recreated = surface()
        holder.create(recreated)
        player.clearVideoSurfaceHolder(holder)
        holder.destroy()

        assertThat(binding.events)
            .containsExactly(SurfaceChanged(holder.initial), SurfaceChanged(null), SurfaceChanged(recreated), SurfaceChanged(null))
            .inOrder()
    }

    /** A texture's surface reaches no display layer, so a request on it would ask nothing (rule 12). */
    @Test
    fun aTextureViewGivesTheBindingNoSurface() {
        val binding = RecordingBinding()
        val player = harness.buildPlayer(output = TestOutput(binding))
        player.setVideoSurface(surface())
        binding.events.clear()

        player.setVideoTextureView(TextureView(ApplicationProvider.getApplicationContext()))

        assertThat(binding.events).containsExactly(SurfaceChanged(null))
    }

    private fun surface(): Surface {
        val texture = SurfaceTexture(/* texName= */ 0)
        textures += texture
        return Surface(texture)
    }

    private fun playUntilReady(player: SuperPlayer) {
        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()
        TestPlayerRunHelper.playUntilPosition(player.exoPlayer, /* mediaItemIndex= */ 0, /* positionMs= */ 0)
        TestPlayerRunHelper.runUntilPlaybackState(player.exoPlayer, Player.STATE_READY)
    }

    /** An output that fills the slot with the binding it was handed. */
    private class TestOutput(private val binding: VideoOutputBinding) : EngineOutputExtension {
        override fun configureEngine(configuration: EngineConfiguration) {
            configuration.videoOutput = binding
        }
    }

    private sealed interface Event

    private data class SurfaceChanged(val surface: Surface?) : Event

    private data class FrameRate(val framesPerSecond: Float?) : Event

    private data object Released : Event

    private class RecordingBinding : VideoOutputBinding {
        val events = mutableListOf<Event>()

        override fun onSurfaceChanged(surface: Surface?) {
            events += SurfaceChanged(surface)
        }

        override fun onVideoFrameRate(framesPerSecond: Float?) {
            events += FrameRate(framesPerSecond)
        }

        override fun release() {
            events += Released
        }
    }

    /**
     * A holder whose surface the test creates and destroys, standing in for a `SurfaceView`'s: what
     * the facade registers is the holder's callbacks, and a real view's surface exists only when its
     * window is attached, which nothing here has.
     */
    private class StatedHolder(val initial: Surface) : SurfaceHolder {
        private val callbacks = mutableListOf<SurfaceHolder.Callback>()
        private var current: Surface? = initial

        fun destroy() {
            callbacks.toList().forEach { it.surfaceDestroyed(this) }
            current = null
        }

        fun create(surface: Surface) {
            current = surface
            callbacks.toList().forEach { it.surfaceCreated(this) }
        }

        override fun addCallback(callback: SurfaceHolder.Callback) {
            callbacks += callback
        }

        override fun removeCallback(callback: SurfaceHolder.Callback) {
            callbacks -= callback
        }

        override fun getSurface(): Surface? = current

        override fun isCreating(): Boolean = false

        override fun getSurfaceFrame(): Rect = Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT)

        @Deprecated("Ignored by the platform since API 11; required by the interface.")
        override fun setType(type: Int) = Unit

        override fun setFixedSize(width: Int, height: Int) = Unit

        override fun setSizeFromLayout() = Unit

        override fun setFormat(format: Int) = Unit

        override fun setKeepScreenOn(screenOn: Boolean) = Unit

        override fun lockCanvas(): Canvas? = null

        override fun lockCanvas(dirty: Rect?): Canvas? = null

        override fun unlockCanvasAndPost(canvas: Canvas) = Unit

        private companion object {
            const val FRAME_WIDTH = 1920
            const val FRAME_HEIGHT = 1080
        }
    }
}
