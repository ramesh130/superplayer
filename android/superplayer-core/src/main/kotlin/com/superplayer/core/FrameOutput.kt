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

/**
 * What an effect does to the decoded picture of a playing video: handed one frame and its presentation
 * time, it returns the frame to display at that same time (ADR-0022 rule 1).
 *
 * The one SuperPlayer type both platforms share. On Android the frame is a GL texture, carried as a
 * [VideoFrame]; on iOS it is a `CVPixelBuffer`. What is shared is the name, this one method and its
 * timing contract, and the [FrameOutputOffReason]s — not an effects API, so no effect written for one
 * platform runs on the other, and none is meant to. Media3's `Effect` and `setVideoEffects` are both
 * `@UnstableApi` and appear nowhere on this surface (ADR-0001 rule 2).
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setFrameOutput(VideoEffects.frameOutput { frame, _ -> segmentation.apply(frame) })
 *     .build()
 * ```
 *
 * **Called on the effect pipeline's GL thread**, with that pipeline's EGL context current, once per
 * frame the engine decodes and before it is shown. Every texture named in a [VideoFrame] — the one
 * handed in and the one handed back — belongs to that context. The method is on the path between the
 * decoder and the display, so a frame it is late with is a frame dropped, which telemetry already
 * counts (ADR-0022 rule 10).
 *
 * **The frame's time cannot be changed**: the method returns a frame and not a time, which is how
 * Media3's refusal of timestamp-changing effects is met by the signature rather than by a refusal at
 * run time (rule 2). The input may be returned as it is, which is a pass-through.
 *
 * **What it does with a frame is its own affair.** The output is fixed when the player is built; an
 * effect that switches its own processing on or off, or changes its model, does it inside [process],
 * and nothing about the engine is reconfigured (rule 2).
 *
 * **Whether an effect may change resolution is not decided** (rule 10): the returned frame is drawn
 * at the size of the frame handed in, stretched if its own size differs.
 */
public fun interface FrameOutput {

    /** Returns the frame to display at [presentationTimeUs] in place of [frame]. */
    public fun process(frame: VideoFrame, presentationTimeUs: Long): VideoFrame
}

/**
 * One decoded video frame as a GL texture: what a [FrameOutput] is handed and what it hands back on
 * Android (ADR-0022 rule 1).
 *
 * A texture rather than a copy of the pixels because that is what an effect running on the GPU reads
 * and writes, and wrapping it in anything richer would copy it or hide it. [textureId] names a
 * `GL_TEXTURE_2D` in the effect pipeline's EGL context, valid for the duration of the one
 * [FrameOutput.process] call it arrived in; an effect that wants a frame later copies it.
 */
public class VideoFrame(
    /** The `GL_TEXTURE_2D` name, in the context current on the thread [FrameOutput.process] runs on. */
    public val textureId: Int,
    /** The texture's width in pixels. */
    public val width: Int,
    /** The texture's height in pixels. */
    public val height: Int,
)

/**
 * Why a player built with a frame output is not running it (ADR-0022 rule 6).
 *
 * Read from [SuperPlayer.frameOutputOffReason]. **Never an error**: a player off for one of these
 * plays every item exactly as it would have without the output, so nothing reaches `onPlayerError`,
 * no `SuperPlayerError` is made and no `FailureClass` names it — nothing failed, and ADR-0011's
 * taxonomy is for sessions that ended.
 */
public enum class FrameOutputOffReason {

    /**
     * The player was built with `setDrm`. Media3 runs no effect pipeline over protected content, and
     * the decrypted frames of such content are what protection exists to keep from app code. Protection
     * is fixed per player (ADR-0012 rule 1) and so is the output, so the answer is given when the player
     * is built — and it covers the player's *clear* items too, because switching per item was rejected.
     * A catalogue that wants effects on its clear content builds a second player for it.
     */
    PROTECTED_CONTENT,

    /**
     * The player was built with `setRealtime`. Whether Media3's pipeline runs over the sample queues a
     * realtime source writes is unexamined, and an output that silently applied or silently did not
     * would be the worse answer (ADR-0022 rules 6 and 10).
     */
    REALTIME,
}

/**
 * The handle `SuperPlayer.Builder.setFrameOutput` and `PlayerPool.Builder.setFrameOutput` take: a
 * [FrameOutput] made ready for the engine by the module that carries Media3's effect pipeline.
 *
 * The peer of [PlaybackOutput], and a second handle rather than a second meaning of that one: what
 * filling the slot behind it changes is a different signal from what `superplayer-tv`'s output
 * changes, and a consumer may name both (ADR-0022 rules 3 and 4). What builds one is
 * `superplayer-effects`' `VideoEffects.frameOutput(output)`, which carries `media3-effect` so that
 * core does not; a `PlaybackFrameOutput` that is not core's [EngineFrameOutputExtension] fills no slot,
 * the contract every handle of this kind has.
 *
 * **One object may serve many players**, a pool's included: the effect laid on each engine is built
 * per player, and the consumer's [FrameOutput] is called by each of them.
 */
public interface PlaybackFrameOutput

/**
 * The seam by which `superplayer-effects` reaches an engine core builds: a [PlaybackFrameOutput] that
 * *also* implements this fills the slot ADR-0022 rule 3 names, [EngineConfiguration.frameOutput].
 *
 * Internal, and reachable from `superplayer-effects` because that module compiles as the **eleventh**
 * Kotlin friend of core (`build-logic`'s `KotlinFriendModules.kt`). Called once per `build()`, pooled
 * or not, on the thread building the player and before the test configurator — and **not at all** on
 * a player whose output is off (rule 6), so nothing of the module runs there.
 */
internal interface EngineFrameOutputExtension : PlaybackFrameOutput {

    /** Fills [EngineConfiguration.frameOutput] with the effect this player's engine is to run. */
    fun configureEngine(configuration: EngineConfiguration)
}

/**
 * Why a player built with [output] is not running it, or null where it is or where there is none to
 * run: protection first, because it is the stronger statement of the two (ADR-0022 rule 6).
 */
internal fun frameOutputOffFor(
    output: PlaybackFrameOutput?,
    protected: Boolean,
    realtime: Boolean,
): FrameOutputOffReason? = when {
    output == null -> null
    protected -> FrameOutputOffReason.PROTECTED_CONTENT
    realtime -> FrameOutputOffReason.REALTIME
    else -> null
}
