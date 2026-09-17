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

/**
 * One mode a display offers: a physical size and the rate it refreshes at, which is what
 * `Display.Mode` reports and what a television's HDMI sink advertises one entry of.
 *
 * A value of this module's rather than the platform's, because `Display.Mode` has no public
 * constructor; [DeviceStatement.declareDisplayModes] builds the platform's from it.
 */
public data class DisplayMode(val widthPx: Int, val heightPx: Int, val refreshRateHz: Float) {
    init {
        require(widthPx > 0 && heightPx > 0) { "A display mode needs a positive size, was $widthPx × $heightPx" }
        require(refreshRateHz > 0f) { "A display mode refreshes at a positive rate, not $refreshRateHz Hz" }
    }
}
