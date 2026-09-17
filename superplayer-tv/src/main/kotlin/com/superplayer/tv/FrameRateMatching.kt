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

import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import com.superplayer.core.VideoOutputBinding
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One player's frame-rate matching: ADR-0014 rule 4, with the display read at the moment of asking
 * (rule 4's #268 addendum).
 *
 * It holds the surface and the rate core tells it, and whenever either changes decides what the surface
 * should be asking the display for, then asks for exactly that: the content's rate, or nothing.
 *
 * **When it asks.** A rate is requested only where the request can do what it is for:
 * - The active mode of the default display is **not already** a match. 24 fps on a 48 Hz panel, or
 *   30 fps on 60 Hz, shows every frame for a whole number of refreshes already, and a request there
 *   can only move the panel somewhere no better — a blank for nothing on a sink whose switches are not
 *   seamless.
 * - Some mode the display offers **at the active mode's size** is a match. The platform chooses the
 *   mode, and with `CHANGE_FRAME_RATE_ALWAYS` a request no mode can honour is still one it may act on,
 *   by choosing whichever mode scores best, which is a switch the content gains nothing from. The size
 *   is held because a frame-rate request does not change resolution, so a matching mode at another
 *   size is not one the request could reach.
 *
 * One exception keeps a request in force: once this binding has asked, on any surface, the active mode matching is
 * most likely *its own request honoured*, and withdrawing it would put the panel back where it judders.
 * So a match on the active mode withdraws nothing already asked for; it only stops a first request.
 *
 * **Why the display is read here and not watched.** The mode list is read once per decision, on the
 * application thread, from `DisplayManager`. It is not `DisplayCapability` (ADR-0014 rule 9 keeps the
 * rate out of that, because a player re-selecting on its own request would loop), and it is not a
 * watch: rule 5 has core hand the binding the rate again when the display changes, and this will read
 * the new display then. That watch is #269's and is not built yet.
 */
internal class FrameRateMatching(private val displays: DisplayManager) : VideoOutputBinding {

    private var surface: Surface? = null
    private var framesPerSecond: Float? = null

    /**
     * The rate this binding has decided should be in force, whichever surface carries it. Kept across a
     * surface change, because a `SurfaceView` recreated after the panel switched finds the active mode
     * matching its own earlier request, and must ask again rather than let the panel fall back.
     */
    private var decided: Float? = null

    /** The rate asked for on [surface] and not withdrawn, or null for no request on it. */
    private var requested: Float? = null

    override fun onSurfaceChanged(surface: Surface?) {
        if (surface === this.surface) return
        // Withdrawn from the old surface first: a request belongs to the layer it was made on.
        withdraw()
        this.surface = surface
        update()
    }

    override fun onVideoFrameRate(framesPerSecond: Float?) {
        this.framesPerSecond = framesPerSecond
        update()
    }

    override fun release() {
        withdraw()
        surface = null
        framesPerSecond = null
        decided = null
    }

    private fun update() {
        val surface = surface ?: return
        val rate = framesPerSecond
        val wanted = if (rate != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && worthAskingFor(rate)) rate else null
        decided = wanted
        if (wanted == requested) return
        if (wanted == null) {
            withdraw()
        } else if (surface.isValid) {
            ask(surface, wanted)
            requested = wanted
        }
    }

    private fun worthAskingFor(framesPerSecond: Float): Boolean {
        // A display that is not connected has no mode to match.
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val active = display.mode
        // Kept only where the rate already decided is one the active mode honours too, which is what
        // makes the match plausibly that request's rather than the panel's own.
        if (refreshMatches(active.refreshRate, framesPerSecond)) {
            return decided?.let { refreshMatches(active.refreshRate, it) } == true
        }
        return display.supportedModes.any { mode ->
            mode.physicalWidth == active.physicalWidth &&
                mode.physicalHeight == active.physicalHeight &&
                refreshMatches(mode.refreshRate, framesPerSecond)
        }
    }

    private fun withdraw() {
        val surface = surface
        // A surface already released has taken its request with it, and the platform throws on it.
        if (requested != null && surface != null && surface.isValid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Zero is the platform's withdrawal on every level this runs on; `clearFrameRate` is API 34.
            ask(surface, WITHDRAWN)
        }
        requested = null
    }

    private fun ask(surface: Surface, framesPerSecond: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // `ALWAYS` rather than Media3's seamless-only default: whether a switch may blank the panel
            // is the viewer's *Match content frame rate* setting, which the platform applies to this
            // request, and asking for seamless only would override a viewer who chose *Always*
            // (ADR-0014 rule 4).
            surface.setFrameRate(framesPerSecond, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30 has only this form, whose strategy is the platform's own. Below it, nothing exists.
            surface.setFrameRate(framesPerSecond, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }

    private companion object {

        /** The rate `Surface.setFrameRate` reads as "no request". */
        private const val WITHDRAWN = 0f

        /**
         * How far a refresh rate may sit from a whole multiple of the frame rate and still match it, as a
         * fraction: the NTSC pulldown ratio, 1001/1000, so that 23.976 fps matches a 24 Hz mode and 30 fps
         * a 59.94 Hz one, with a ten-thousandth over it because a display reports 59.94 Hz as the nearest
         * float rather than exactly.
         *
         * ref: AOSP `frameworks/native/services/surfaceflinger/Scheduler/RefreshRateSelector.cpp`,
         * `isFractionalPairOrMultiple`, which is how the platform itself pairs 23.976 with 24 and 29.97
         * with 30 when it chooses a mode for a frame-rate request.
         */
        private const val PULLDOWN_TOLERANCE = 1001f / 1000f - 1f + 0.0001f

        /**
         * Whether a panel refreshing at [refreshRateHz] shows content at [framesPerSecond] with every frame
         * held for the same whole number of refreshes — the condition under which there is no judder.
         *
         * ref: Android Developers, *Frame rate* (https://developer.android.com/media/optimize/performance/frame-rate),
         * on a refresh rate that is a multiple of the content's.
         */
        fun refreshMatches(refreshRateHz: Float, framesPerSecond: Float): Boolean {
            val multiple = (refreshRateHz / framesPerSecond).roundToInt()
            if (multiple < 1) return false
            return abs(refreshRateHz / (multiple * framesPerSecond) - 1f) <= PULLDOWN_TOLERANCE
        }
    }
}
