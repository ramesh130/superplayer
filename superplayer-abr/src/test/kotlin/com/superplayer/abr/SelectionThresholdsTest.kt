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
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import org.junit.Test

/**
 * That the table says what its KDoc says: the pairs are asymmetric, the thresholds sit inside the
 * profile's own buffer, and the Media3 constants it departs from are the ones it names.
 */
class SelectionThresholdsTest {

    @Test
    fun theDefaultsItDepartsFromAreMedia3s() {
        // spec: Media3 `AdaptiveTrackSelection.DEFAULT_*` — pinned here so a Media3 bump that moved
        // them is a diff in this file rather than a silent change of what "kept" means.
        val media3 = SelectionThresholds.MEDIA3_DEFAULT
        assertThat(media3.minDurationForQualityIncreaseMs).isEqualTo(10_000)
        assertThat(media3.maxDurationForQualityDecreaseMs).isEqualTo(25_000)
        assertThat(media3.minDurationToRetainAfterDiscardMs).isEqualTo(25_000)
        assertThat(media3.bandwidthFraction).isEqualTo(0.7f)
        assertThat(media3.bufferedFractionToLiveEdgeForQualityIncrease).isEqualTo(0.75f)
        assertThat(SelectionThresholds.forProfile(PlaybackProfile.VIDEO_ON_DEMAND)).isEqualTo(media3)
    }

    @Test
    fun everyProfileClimbsAndDescendsOnDifferentThresholdsInsideItsOwnBuffer() {
        for (profile in PlaybackProfile.entries) {
            val thresholds = SelectionThresholds.forProfile(profile)
            val buffer = PlaybackPolicy.forProfile(profile).decide(PlaybackConditions()).buffer
            assertWithMessage("$profile climbs and descends on the same threshold")
                .that(thresholds.minDurationForQualityIncreaseMs)
                .isNotEqualTo(thresholds.maxDurationForQualityDecreaseMs)
            // A climb threshold above the buffer's ceiling is a climb that never happens.
            assertWithMessage("$profile cannot climb inside its own buffer")
                .that(thresholds.minDurationForQualityIncreaseMs)
                .isAtMost(buffer.maxBufferMs)
            // A descent threshold above the ceiling is a descent on every evaluation.
            assertWithMessage("$profile descends on every evaluation")
                .that(thresholds.maxDurationForQualityDecreaseMs)
                .isAtMost(buffer.maxBufferMs)
            assertThat(thresholds.bandwidthFraction).isAtLeast(0.5f)
            assertThat(thresholds.bandwidthFraction).isAtMost(1f)
        }
    }

    @Test
    fun theShortFormClimbIsAllowedTheMomentTheStartTargetIsMet() {
        val shortForm = PlaybackPolicy.forProfile(PlaybackProfile.SHORT_FORM).decide(PlaybackConditions()).buffer
        assertThat(SelectionThresholds.forProfile(PlaybackProfile.SHORT_FORM).minDurationForQualityIncreaseMs)
            .isEqualTo(shortForm.minBufferMs)
    }

    @Test
    fun theDataSaverIsSlowerToClimbAndChoosesWithMoreHeadroomThanOnDemand() {
        val saver = SelectionThresholds.forProfile(PlaybackProfile.DATA_SAVER)
        val vod = SelectionThresholds.forProfile(PlaybackProfile.VIDEO_ON_DEMAND)
        assertThat(saver.minDurationForQualityIncreaseMs).isGreaterThan(vod.minDurationForQualityIncreaseMs)
        assertThat(saver.maxDurationForQualityDecreaseMs).isLessThan(vod.maxDurationForQualityDecreaseMs)
        assertThat(saver.bandwidthFraction).isLessThan(vod.bandwidthFraction)
    }
}
