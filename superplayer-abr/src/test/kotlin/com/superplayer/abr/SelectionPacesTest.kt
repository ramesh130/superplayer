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

import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.BufferPolicy
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SelectionPace
import org.junit.Test

/**
 * That the table says what its KDoc says: the pairs are asymmetric, the thresholds sit within
 * reach of the profile's own buffer, the Media3 constants it departs from are the ones it names,
 * and a ceiling under the climb threshold brings the threshold within reach (#114).
 */
class SelectionPacesTest {

    @Test
    fun theEngineDefaultIsMedia3s() {
        // ref: Media3 `AdaptiveTrackSelection.DEFAULT_*` — read from Media3 rather than restated,
        // so a Media3 bump that moved them is a failure here rather than a silent change of what
        // "kept" means.
        val engine = SelectionPace.ENGINE_DEFAULT
        assertThat(engine.climbAfterBufferedMs).isEqualTo(AdaptiveTrackSelection.DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS)
        assertThat(engine.descendBelowBufferedMs).isEqualTo(AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS)
        assertThat(engine.retainAfterDiscardMs).isEqualTo(AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS)
        assertThat(engine.bandwidthFraction).isEqualTo(AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION)
        assertThat(SelectionPaces.forProfile(PlaybackProfile.VIDEO_ON_DEMAND)).isEqualTo(engine)
    }

    @Test
    fun everyProfileClimbsAndDescendsOnDifferentThresholdsWithinReachOfItsOwnBuffer() {
        for (profile in PlaybackProfile.entries) {
            val pace = SelectionPaces.forProfile(profile)
            val buffer = PlaybackPolicy.forProfile(profile).decide(PlaybackConditions()).buffer
            assertWithMessage("$profile climbs and descends on the same threshold")
                .that(pace.climbAfterBufferedMs)
                .isNotEqualTo(pace.descendBelowBufferedMs)
            // A climb threshold out of the buffer's reach is a climb that never happens.
            assertWithMessage("$profile cannot climb inside its own buffer")
                .that(SelectionPaces.reachableWithin(pace, buffer))
                .isEqualTo(pace)
            // A descent threshold above the ceiling is a descent on every evaluation.
            assertWithMessage("$profile descends on every evaluation")
                .that(pace.descendBelowBufferedMs)
                .isAtMost(buffer.maxBufferMs)
            assertThat(pace.bandwidthFraction).isAtLeast(0.5f)
            assertThat(pace.bandwidthFraction).isAtMost(1f)
        }
    }

    @Test
    fun theShortFormClimbIsAllowedTheMomentTheStartTargetIsMet() {
        val shortForm = PlaybackPolicy.forProfile(PlaybackProfile.SHORT_FORM).decide(PlaybackConditions()).buffer
        assertThat(SelectionPaces.forProfile(PlaybackProfile.SHORT_FORM).climbAfterBufferedMs)
            .isEqualTo(shortForm.minBufferMs)
    }

    @Test
    fun theDataSaverIsSlowerToClimbAndChoosesWithMoreHeadroomThanOnDemand() {
        val saver = SelectionPaces.forProfile(PlaybackProfile.DATA_SAVER)
        val vod = SelectionPaces.forProfile(PlaybackProfile.VIDEO_ON_DEMAND)
        assertThat(saver.climbAfterBufferedMs).isGreaterThan(vod.climbAfterBufferedMs)
        assertThat(saver.descendBelowBufferedMs).isLessThan(vod.descendBelowBufferedMs)
        assertThat(saver.bandwidthFraction).isLessThan(vod.bandwidthFraction)
    }

    // #114: the reach is the ceiling less a segment; only the climb threshold moves, and only down.
    @Test
    fun aCeilingUnderTheClimbThresholdBringsOnlyTheClimbWithinReach() {
        val saver = SelectionPaces.forProfile(PlaybackProfile.DATA_SAVER)

        val tenSeconds = SelectionPaces.reachableWithin(saver, bufferWithCeiling(10_000))
        assertThat(tenSeconds).isEqualTo(saver.copy(climbAfterBufferedMs = 10_000 - SelectionPaces.SEGMENT_ALLOWANCE_MS))

        // A ceiling that holds no segment beyond it leaves nothing to wait for.
        assertThat(SelectionPaces.reachableWithin(saver, bufferWithCeiling(5_000)).climbAfterBufferedMs).isEqualTo(0)

        // A deep ceiling raises nothing.
        assertThat(SelectionPaces.reachableWithin(saver, bufferWithCeiling(120_000))).isEqualTo(saver)
    }

    private fun bufferWithCeiling(maxBufferMs: Int): BufferPolicy = BufferPolicy(
        minBufferMs = minOf(10_000, maxBufferMs),
        maxBufferMs = maxBufferMs,
        bufferForPlaybackMs = 2_500,
        bufferForPlaybackAfterRebufferMs = 5_000,
        backBufferMs = 0,
        retainBackBufferFromKeyframe = false,
    )
}
