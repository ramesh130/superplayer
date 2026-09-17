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

import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * One `Surface.setFrameRate` call made on a surface the harness gave a player: what was asked of the
 * display, which is as far as anything under Robolectric can see (`docs/testing.md`, *A TV device*).
 *
 * [framesPerSecond] of zero is a request withdrawn, which is the platform's own spelling of a clear.
 * [compatibility] is a `Surface.FRAME_RATE_COMPATIBILITY_*` constant. [changeFrameRateStrategy] is a
 * `Surface.CHANGE_FRAME_RATE_*` constant, or null for the two-argument form, which is the only one API
 * 30 has.
 *
 * A request is not a mode switch. Robolectric has no compositor, so the active mode stays what a test
 * stated, and whether a panel then refreshed at a matching rate is a device's to show.
 */
public data class FrameRateRequest(
    val framesPerSecond: Float,
    val compatibility: Int,
    val changeFrameRateStrategy: Int?,
)

/**
 * A surface that records every frame-rate request made on it, and otherwise is the off-screen
 * `SurfaceTexture`-backed surface the harness always gave a player.
 *
 * A subclass rather than a Robolectric shadow: `Surface.setFrameRate` is public and not final, the
 * surface is the harness's own object, and what is recorded is a call a consumer's surface would have
 * received on a device — so the observation sits at the platform, the place `docs/testing.md`'s
 * *Asserting on the platform* allows, without a shadow every test class would have to declare. Each
 * call is forwarded, so the platform's own argument checks still run.
 */
internal class RecordingSurface(texture: SurfaceTexture) : Surface(texture) {

    private val recorded = mutableListOf<FrameRateRequest>()

    val requests: List<FrameRateRequest>
        get() = synchronized(recorded) { recorded.toList() }

    override fun setFrameRate(frameRate: Float, compatibility: Int) {
        synchronized(recorded) { recorded += FrameRateRequest(frameRate, compatibility, changeFrameRateStrategy = null) }
        super.setFrameRate(frameRate, compatibility)
    }

    override fun setFrameRate(frameRate: Float, compatibility: Int, changeFrameRateStrategy: Int) {
        synchronized(recorded) { recorded += FrameRateRequest(frameRate, compatibility, changeFrameRateStrategy) }
        super.setFrameRate(frameRate, compatibility, changeFrameRateStrategy)
    }
}
