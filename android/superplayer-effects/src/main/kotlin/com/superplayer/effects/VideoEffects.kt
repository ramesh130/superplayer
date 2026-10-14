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

package com.superplayer.effects

import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EngineFrameOutputExtension
import com.superplayer.core.FrameOutput
import com.superplayer.core.PlaybackFrameOutput

/**
 * The module's entry point: the [PlaybackFrameOutput] a player hands its decoded frames to an effect
 * through (ADR-0022).
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setFrameOutput(VideoEffects.frameOutput(segmentation))
 *     .build()
 * player.setVideoSurfaceView(surfaceView)
 * ```
 *
 * The consumer's [FrameOutput] is laid on Media3's GL effect pipeline, which runs between the decoder
 * and the surface, as one effect, set up as the engine is built and so before `prepare()`. SuperPlayer
 * ships no effect of its own; this is the place one attaches (rule 8).
 *
 * What it deliberately does not do, each a stated loss rather than a gap:
 * - **No effect on a player built with `setDrm` or `setRealtime`.** Such a player builds and plays as it
 *   would without the output, and `SuperPlayer.frameOutputOffReason` says which — never an error
 *   (rule 6). A protected catalogue that wants effects on its clear content builds a second player.
 * - **No tunneling while it runs.** A tunneled path hands frames to the display past the pipeline, so a
 *   decision asking for tunneling is not laid on a player built with this (rule 4). Frame-rate matching
 *   from `superplayer-tv` is unaffected and may be set beside it.
 * - **No change of time or, yet, of size.** The returned frame is shown at the time it was handed in,
 *   which is the signature's doing, and at the size of the input (rule 10 leaves resolution open).
 * - **Media3's own video renderer only.** The pipeline is a feature of `MediaCodecVideoRenderer`, which
 *   is the renderer every SuperPlayer is built with; nothing here can replace it.
 *
 * **What only a device shows.** Under `check` the effect is observed where the engine delivers it, at
 * the video renderer, before that renderer is first enabled (`docs/testing.md`, *A frame output*).
 * Robolectric has no GPU and runs no effect pipeline, so whether frames reach [FrameOutput.process], on
 * time, and come out on the surface, is a device's to show.
 */
public object VideoEffects {

    /**
     * A [PlaybackFrameOutput] that hands every decoded frame to [output] before it is shown. One
     * instance may be handed to many builders, a pool's included: each player's engine is laid an
     * effect of its own over the one [output], which every one of them then calls.
     */
    @JvmStatic
    public fun frameOutput(output: FrameOutput): PlaybackFrameOutput = GlFrameOutput(output)
}

/**
 * What [VideoEffects.frameOutput] returns: a [PlaybackFrameOutput] that is also core's
 * [EngineFrameOutputExtension], and so fills [EngineConfiguration.frameOutput] once per player built —
 * on a player whose output is on, which core decides before asking.
 */
internal class GlFrameOutput(private val output: FrameOutput) : EngineFrameOutputExtension {

    override fun configureEngine(configuration: EngineConfiguration) {
        configuration.frameOutput = FrameOutputEffect(output)
    }
}
