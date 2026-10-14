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
 * What the display a player is shown on can show right now: a live reading, not a constraint read
 * once (ADR-0014 rule 9, refining ADR-0009 rule 2).
 *
 * Translated from the platform in `ConditionsBinding.kt` alone, and read by one reader, the selection
 * gate `superplayer-abr` builds, through [DisplayInForce]. It is deliberately **not** a
 * [PlaybackConditions] observation yet: no policy decides anything from the display, and ADR-0014 rule
 * 9 fixes the shape it takes when one does.
 *
 * Both fields keep the gate's convention: *unknown* is null and constrains nothing, while an empty set
 * of HDR types is a display that answered and supports none.
 *
 * **No refresh rate, and no mode list.** Nothing reads them, and equality has to exclude the rate:
 * a frame-rate request (ADR-0014 rule 4) changes the active mode's rate, and a player that re-selected
 * on its own request would loop. The binding that matches the rate reads the modes itself.
 */
internal data class DisplayCapability(
    /**
     * The shorter edge of the display's **active** mode, in physical pixels, or null when unknown.
     *
     * The active mode rather than the largest, because after a hotplug what the panel shows now is the
     * only honest reading; the shorter edge, because a rung is judged by whether the display shows it
     * at full resolution in *some* orientation.
     */
    val shortEdgePx: Int?,

    /** The HDR types the display shows in its active mode, or null when the platform does not say. */
    val hdrTypes: Set<HdrType>?,
) {
    internal companion object {
        /** A display that answered nothing, or no display: constrains nothing. */
        val UNKNOWN: DisplayCapability = DisplayCapability(shortEdgePx = null, hdrTypes = null)
    }
}

/**
 * An HDR format a display can show, in SuperPlayer's vocabulary rather than the platform's
 * `Display.HdrCapabilities.HDR_TYPE_*` integers (ADR-0014 rule 9).
 *
 * ref: https://developer.android.com/reference/android/view/Display.HdrCapabilities
 */
internal enum class HdrType {
    DOLBY_VISION,
    HDR10,
    HLG,
    HDR10_PLUS,
}
