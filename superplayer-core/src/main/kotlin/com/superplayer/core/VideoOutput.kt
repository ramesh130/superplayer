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
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks

/**
 * What a player's picture is shown on, and what `superplayer-tv` does about it on the player's behalf
 * (ADR-0014).
 *
 * The handle a consumer names in [SuperPlayer.Builder.setOutput] and [PlayerPool.Builder.setOutput],
 * and nothing else — the peer of [PlaybackDrm] and [PlaybackResilience], and here for their reason:
 * what filling it changes is the engine's frame-rate strategy and, later, the selector's parameters,
 * all `@UnstableApi` vocabulary, so what the builder takes is this and the object behind it stays in
 * the module that built it (ADR-0001 rule 2, ADR-0014 rule 2).
 *
 * **Fixed for the player's lifetime**, as protection is: the engine is built with the strategy the
 * slot implies, and Media3 fixes that at construction.
 *
 * **One object may serve many players.** A pool fills every pooled player from the one instance, and
 * what it fills is per player — a surface and a frame rate are one player's facts.
 *
 * Implementations come from `superplayer-tv` (`TvOutput.standard(context)`), and reach the engine as
 * [EngineOutputExtension]; a `PlaybackOutput` that is not one fills no slot and registers nothing,
 * which is the same contract a [PlaybackPolicy] that is not an [EnginePolicyExtension] has.
 */
public interface PlaybackOutput

/**
 * The seam by which `superplayer-tv` reaches an engine core builds: a [PlaybackOutput] that *also*
 * implements this fills the one slot ADR-0014 rule 3 names, [EngineConfiguration.videoOutput].
 *
 * Internal, and reachable from `superplayer-tv` because that module compiles as the **eighth** Kotlin
 * friend of core (`build-logic`'s `KotlinFriendModules.kt`). Called once per `build()` — pooled or not,
 * because a binding holds one player's surface — on the thread building the player, before the test
 * configurator, so a test's engine configuration still wins.
 */
internal interface EngineOutputExtension : PlaybackOutput {

    /** Fills [EngineConfiguration.videoOutput] with a binding of this player's own. */
    fun configureEngine(configuration: EngineConfiguration)
}

/**
 * One player's video output, as the module sees it: the surface the engine renders to and the frame
 * rate of the content it renders (ADR-0014 rule 3).
 *
 * Every method is called on the player's application thread. Core tells the binding *facts*; what it
 * asks of the platform about them — a frame-rate request, and in later issues a re-selection — is the
 * binding's, so nothing here names a display.
 */
internal interface VideoOutputBinding {

    /**
     * The surface the engine renders to came into or went out of existence: a `setVideoSurface`, a
     * `SurfaceHolder`'s `surfaceCreated` and `surfaceDestroyed`, a clear. Null for no surface, and for
     * a `TextureView`, whose surface reaches no display layer a frame-rate request could attach to
     * (ADR-0014 rule 12).
     */
    fun onSurfaceChanged(surface: Surface?)

    /**
     * The frame rate the first selected video format of the current media item declares, once per
     * item and before its first frame is rendered. Null for a format that declares none and for
     * content with no video. A later rung with a different rate is not reported (ADR-0014 rule 4).
     *
     * "Before its first frame" is the ordinary case rather than a guarantee: the tracks arrive on the
     * application thread while the frame is rendered on the playback thread, so an application looper
     * busy past the first frame makes the request land after it. A fallback to the next source is a
     * new item to Media3 and is reported again; a source of the same content declares the same rate,
     * and a rate already requested asks nothing more.
     */
    fun onVideoFrameRate(framesPerSecond: Float?)

    /** The player is being released; whatever was asked of the platform is withdrawn. */
    fun release()
}

/**
 * Core's half of the output slot: turns the facade's surface calls and the engine's track changes into
 * the facts a [VideoOutputBinding] is told.
 *
 * Built only on a player whose slot is filled, so a player without `superplayer-tv` holds no instance
 * and registers no listener (ADR-0014 rule 14). The surface calls reach it from the facade's own
 * overrides of `Player`'s video-surface methods, after the engine has been handed the surface, and the
 * frame rate from a [Player.Listener] on the engine.
 */
internal class VideoOutputAttachment(private val binding: VideoOutputBinding) : Player.Listener {

    /** The surface set directly, or the holder's surface while it exists; null otherwise. */
    private var surface: Surface? = null

    /** The holder whose callbacks are registered, which is also what a clear of one is compared to. */
    private var holder: SurfaceHolder? = null

    /**
     * Whether the current media item's frame rate has been reported, which is what makes the report
     * once per item: a rung switch changes the selected format and not the item.
     */
    private var reportedForItem = false

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(created: SurfaceHolder) {
            changeSurface(created.surface)
        }

        override fun surfaceChanged(changed: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(destroyed: SurfaceHolder) {
            changeSurface(null)
        }
    }

    fun surfaceSet(surface: Surface?) {
        detachHolder()
        changeSurface(surface)
    }

    fun surfaceCleared(surface: Surface?) {
        // Media3 clears only the surface it holds, and a clear naming another one, or none, is a no-op
        // there too.
        if (surface != null && surface === this.surface && holder == null) surfaceSet(null)
    }

    fun holderSet(holder: SurfaceHolder?) {
        detachHolder()
        if (holder == null) {
            changeSurface(null)
            return
        }
        this.holder = holder
        holder.addCallback(holderCallback)
        // A holder handed over after its surface was created will not call `surfaceCreated` again.
        changeSurface(holder.surface?.takeIf { it.isValid })
    }

    fun holderCleared(holder: SurfaceHolder?) {
        if (holder != null && holder === this.holder) surfaceSet(null)
    }

    /** A `TextureView`'s surface is composited by the app, so no request on it reaches the display. */
    fun textureViewSet() {
        surfaceSet(null)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        reportedForItem = false
    }

    override fun onTracksChanged(tracks: Tracks) {
        // Empty between items and before a prepare: nothing is known yet, and the next non-empty
        // reading is the item's first.
        if (tracks.isEmpty) {
            reportedForItem = false
            return
        }
        if (reportedForItem) return
        reportedForItem = true
        binding.onVideoFrameRate(declaredVideoFrameRate(tracks))
    }

    fun release() {
        detachHolder()
        surface = null
        binding.release()
    }

    private fun changeSurface(surface: Surface?) {
        this.surface = surface
        binding.onSurfaceChanged(surface)
    }

    private fun detachHolder() {
        holder?.removeCallback(holderCallback)
        holder = null
    }

    private companion object {

        /**
         * The declared frame rate of the first selected video track, or null. A format with no
         * declaration carries `Format.NO_VALUE`, which is not a rate.
         */
        fun declaredVideoFrameRate(tracks: Tracks): Float? {
            val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return null
            val track = (0 until group.length).firstOrNull(group::isTrackSelected) ?: return null
            return group.getTrackFormat(track).frameRate.takeIf { it != Format.NO_VALUE.toFloat() && it > 0f }
        }
    }
}
