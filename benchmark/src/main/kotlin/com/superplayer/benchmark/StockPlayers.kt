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

package com.superplayer.benchmark

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.superplayer.core.BufferPolicy

/**
 * Arms (a) and (b) as a real `ExoPlayer` on a device — the device arm's counterpart of
 * `PlaybackHarness.buildStockPlayer`.
 *
 * Two places have to turn an arm into a player, because the two arms run twice: under Robolectric
 * against generated content, and on a device against public streams. What must **not** be written
 * twice is what an arm *is*, and it is not — [Arm.stockBufferPolicy] is the one statement of that,
 * and both places translate the same value.
 *
 * `superplayer-core`'s `EngineBinding` is the third translation of the same kind, for arm (c). That
 * three exist is not duplication to be removed: each turns an engine-agnostic policy into a
 * `LoadControl` at a different place in a different build, which is exactly what ADR-0005's boundary
 * is for.
 */
internal object StockPlayers {

    /**
     * A stock player for [arm], with nothing configured that the arm does not ask for.
     *
     * `ExoPlayer.Builder(context).build()` for arm (a), with the `LoadControl` never constructed at
     * all rather than constructed from Media3's defaults — see [Arm.MEDIA3_DEFAULT_BUFFER_POLICY]
     * for why building one anyway would quietly make the control arm a configured player.
     */
    fun build(context: Context, arm: Arm): ExoPlayer {
        require(arm.isStock) { "$arm is not a stock arm; build it with SuperPlayer.Builder" }
        val builder = ExoPlayer.Builder(context)
        arm.stockBufferPolicy?.let { builder.setLoadControl(loadControl(it)) }
        return builder.build()
    }

    /**
     * [policy] as Media3's `LoadControl`.
     *
     * The same translation `PlaybackHarness.loadControlFor` makes, including
     * `setPrioritizeTimeOverSizeThresholds` — without which the engine caps the durations at its
     * memory target and an arm that asked for 120 s of buffer would be measured with rather less,
     * which on the device arm is exactly the arm whose peak RSS is the interesting number.
     */
    private fun loadControl(policy: BufferPolicy): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            policy.minBufferMs,
            policy.maxBufferMs,
            policy.bufferForPlaybackMs,
            policy.bufferForPlaybackAfterRebufferMs,
        )
        .setBackBuffer(policy.backBufferMs, policy.retainBackBufferFromKeyframe)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
