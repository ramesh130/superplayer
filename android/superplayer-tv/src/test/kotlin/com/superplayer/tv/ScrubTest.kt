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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The scrub curve on its own, for the two claims no key injection can vary: its cap, and that a hold's
 * distance does not depend on how often the remote repeats. `TvPlaybackControlsTest` drives the same curve
 * through the controls, at the one repeat rate Compose's test injection has.
 */
class ScrubTest {

    @Test
    fun theSpeedDoublesEveryIntervalHeldUntilItCrossesATenthOfTheContentASecond() {
        val twoHours = 2 * 60 * 60 * 1_000L

        assertThat(Scrub.speedMsPerSecond(heldMs = 0, twoHours)).isEqualTo(30_000)
        assertThat(Scrub.speedMsPerSecond(heldMs = 1_999, twoHours)).isEqualTo(30_000)
        assertThat(Scrub.speedMsPerSecond(heldMs = 2_000, twoHours)).isEqualTo(60_000)
        assertThat(Scrub.speedMsPerSecond(heldMs = 8_000, twoHours)).isEqualTo(480_000)
        // A tenth of two hours a second is twelve minutes a second, and no hold goes faster.
        assertThat(Scrub.speedMsPerSecond(heldMs = 10_000, twoHours)).isEqualTo(720_000)
        assertThat(Scrub.speedMsPerSecond(heldMs = 600_000, twoHours)).isEqualTo(720_000)
    }

    @Test
    fun onShortContentTheStartSpeedIsKeptRatherThanCapped() {
        assertThat(Scrub.speedMsPerSecond(heldMs = 10_000, durationMs = 60_000)).isEqualTo(30_000)
    }

    @Test
    fun aHoldCoversTheSameDistanceWhateverTheRepeatRate() {
        val fast = hold(repeatIntervalMs = 50)
        val slow = hold(repeatIntervalMs = 110)

        // The same curve integrated at two step sizes, which differ by at most a step at the hold's top speed.
        assertThat(fast).isWithin(Scrub.speedMsPerSecond(6_000, CONTENT_MS) * 110 / 1_000).of(slow)
    }

    /** Six seconds held from zero, with the first repeat half a second after the press and then every [repeatIntervalMs]. */
    private fun hold(repeatIntervalMs: Long): Long {
        val scrub = Scrub(startMs = 0, durationMs = CONTENT_MS)
        scrub.move(direction = 1, repeat = false, heldMs = 0, eventTimeMs = DOWN_AT_MS)
        var held = FIRST_REPEAT_MS
        while (held <= 6_000) {
            scrub.move(direction = 1, repeat = true, heldMs = held, eventTimeMs = DOWN_AT_MS + held)
            held += repeatIntervalMs
        }
        return scrub.targetMs
    }

    private companion object {
        const val CONTENT_MS = 2 * 60 * 60 * 1_000L
        const val DOWN_AT_MS = 1_000_000L
        const val FIRST_REPEAT_MS = 500L
    }
}
