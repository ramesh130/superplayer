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

package com.superplayer.tv

import android.content.Context
import android.hardware.display.DisplayManager
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EngineOutputExtension
import com.superplayer.core.PlaybackOutput

/**
 * The module's entry point: the [PlaybackOutput] a television player is built with (ADR-0014 rule 2).
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setProfile(PlaybackProfile.TV_LEANBACK)
 *     .setOutput(TvOutput.standard(context))
 *     .build()
 * player.setVideoSurfaceView(surfaceView)
 * ```
 *
 * **Frame-rate matching** (ADR-0014 rule 4). When the player holds a surface and the current item's
 * first video format declares a frame rate, the player asks the display for that
 * rate with `Surface.setFrameRate`, so 24 fps film on a 60 Hz panel that also offers 24, 48 or 120 Hz
 * is shown at a matching rate rather than with 3:2 judder. It is correctness on every player built with
 * it, and no profile varies it. The request is withdrawn when the surface goes, the content declares no
 * rate, or the player is released. Whether the switch is seamless or blanks the panel is the viewer's
 * *Match content frame rate* setting, which the platform applies to the request.
 *
 * What it deliberately does not do, each a stated loss rather than a gap:
 * - **An undeclared rate is not matched.** HLS without `FRAME-RATE`, and a container that declares
 *   none, ask for nothing. A rate estimated from frame timestamps is known only after frames have been
 *   shown, and a switch on it would blank the panel mid-content.
 * - **A rung switch does not switch again.** The request is made once per item, on its first video
 *   format, before the first frame.
 * - **A display that offers no matching mode is left alone**, and so is one already refreshing at a
 *   multiple of the content's rate; `FrameRateMatching` says why each asks for nothing.
 * - **A `TextureView` is not matched.** Its surface is composited by the app and reaches no display
 *   layer, so a TV player's surface is a `SurfaceView` (ADR-0014 rule 12). A GL surface view Media3
 *   renders through (a spherical or decoder GL view) is not matched either: the request would land on
 *   the view's holder, which is not the surface the engine renders to.
 * - **Below API 30, nothing.** No `Surface` API exists there; an app targeting such a device sets
 *   `WindowManager.LayoutParams.preferredDisplayModeId` on its own window, which the library does not
 *   hold.
 *
 * **Surviving a display change** (ADR-0014 rule 5). An HDMI hotplug to a lesser or more capable display
 * re-selects at the position reached, under the new display's size and HDR types, rather than holding a
 * rendition the display cannot show, and the new display is asked for the content's frame rate afresh.
 * A change of refresh rate alone is not a display change. The refusal that re-arms is the selection
 * gate of `superplayer-abr`'s `AdaptivePolicy`; a player without it re-runs Media3's own
 * viewport-bounded selection, which refuses no HDR rendition. A re-selection may cost a rebuffer.
 *
 * **What only a device shows.** Under `check` the request is observed at the surface, as the call a
 * compositor would receive (`docs/testing.md`, *A TV device*): whether the panel then switched, how
 * long the HDMI link took to resynchronise, and the blank a non-seamless switch costs are not visible
 * there. A time to first frame on a device set to *Always* includes that resynchronisation, which
 * `docs/telemetry-schema.md` says.
 */
public object TvOutput {

    /**
     * A [PlaybackOutput] that matches the display's refresh rate to the content and survives a display
     * change. One instance may be handed to many builders, a pool's included: it fills each player's slot
     * with a binding of that player's own.
     */
    @JvmStatic
    public fun standard(context: Context): PlaybackOutput = StandardTvOutput(context.applicationContext)
}

/**
 * What [TvOutput.standard] returns: a [PlaybackOutput] that is also core's [EngineOutputExtension], and
 * so fills [EngineConfiguration.videoOutput] once per player built.
 */
internal class StandardTvOutput(private val context: Context) : EngineOutputExtension {

    override fun configureEngine(configuration: EngineConfiguration) {
        configuration.videoOutput = FrameRateMatching(checkNotNull(context.getSystemService(DisplayManager::class.java)))
    }
}
