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

package com.superplayer.offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.DownloadSelectionPolicy
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of ADR-0013 rule 12 `DownloadSelectionTest` cannot force: that a profile's video ceiling, and
 * the display's, reach the selector a download's tracks are chosen by. The synthetic streams have no video
 * ladder to cut, so this reads the parameters handed to Media3 rather than a download — the one place in
 * this module that looks past the store, and only at what the store hands on.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSelectionParametersTest {

    @Test
    fun theProfilesCeilingIsTheSelectorsCeiling() {
        val parameters = selection(DownloadSelectionPolicy(maxVideoBitrateBps = 800_000, maxVideoHeightPx = 480), displayShortEdgePx = null).parameters

        assertThat(parameters.maxVideoBitrate).isEqualTo(800_000)
        assertThat(parameters.maxVideoHeight).isEqualTo(480)
        // One rendition rather than every one under the ceiling.
        assertThat(parameters.forceHighestSupportedBitrate).isTrue()
    }

    @Test
    fun aDisplayShorterThanTheCeilingNarrowsIt() {
        val parameters = selection(DownloadSelectionPolicy(maxVideoBitrateBps = Int.MAX_VALUE, maxVideoHeightPx = 1_080), displayShortEdgePx = 720).parameters

        assertThat(parameters.maxVideoHeight).isEqualTo(720)
    }

    /** The control for the display: a policy may narrow below what the device allows, and the device never widens past the policy. */
    @Test
    fun aDisplayTallerThanTheCeilingLeavesIt() {
        val parameters = selection(DownloadSelectionPolicy(maxVideoBitrateBps = Int.MAX_VALUE, maxVideoHeightPx = 480), displayShortEdgePx = 2_160).parameters

        assertThat(parameters.maxVideoHeight).isEqualTo(480)
    }

    private fun selection(policy: DownloadSelectionPolicy, displayShortEdgePx: Int?) =
        DownloadSelection(policy, displayShortEdgePx, audioLanguages = emptyList(), subtitleLanguages = emptyList())
}
