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

package com.superplayer.abr

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The three readings of a window, against values worked by hand.
 *
 * The eight samples are the textbook set whose mean is 5, whose population variance is 4 and whose
 * standard deviation is therefore exactly 2 — chosen so that nothing here is rounded.
 */
class SampleWindowTest {

    @Test
    fun theMeanSpreadAndPercentileAreTheHandComputedOnes() {
        val window = SampleWindow()
        HAND_SET.forEachIndexed { index, mbps -> window.add(mbps * MBPS, atMs = index * 1_000L) }

        assertThat(window.meanBps()).isEqualTo(5 * MBPS)
        // Population standard deviation: sqrt((9+1+1+1+0+0+4+16)/8) = sqrt(4) = 2.
        assertThat(window.spreadBps()).isEqualTo(2 * MBPS)
        // Nearest rank: ceil(0.25 × 8) = 2, and the second-lowest of {2,4,4,4,5,5,7,9} is 4.
        assertThat(window.percentileBps(SampleWindow.CONSERVATIVE_PERCENTILE)).isEqualTo(4 * MBPS)
        assertThat(window.newestAtMs).isEqualTo(7_000L)
    }

    @Test
    fun aNinthSampleDropsTheOldest() {
        val window = SampleWindow()
        HAND_SET.forEachIndexed { index, mbps -> window.add(mbps * MBPS, atMs = index * 1_000L) }

        window.add(13 * MBPS, atMs = 8_000L)

        // {4,4,4,5,5,7,9,13}: the 2 is gone, and the mean is 51 / 8 = 6.375, rounded down.
        assertThat(window.size).isEqualTo(SampleWindow.WINDOW_SAMPLES)
        assertThat(window.meanBps()).isEqualTo(6_375_000L)
        assertThat(window.percentileBps(SampleWindow.CONSERVATIVE_PERCENTILE)).isEqualTo(4 * MBPS)
    }

    @Test
    fun aSingleSampleHasNoSpread() {
        val window = SampleWindow()
        window.add(3 * MBPS, atMs = 0)

        assertThat(window.meanBps()).isEqualTo(3 * MBPS)
        assertThat(window.spreadBps()).isEqualTo(0)
        assertThat(window.percentileBps(SampleWindow.CONSERVATIVE_PERCENTILE)).isEqualTo(3 * MBPS)
    }

    private companion object {
        const val MBPS = 1_000_000L
        val HAND_SET = listOf(2L, 4, 4, 4, 5, 5, 7, 9)
    }
}
