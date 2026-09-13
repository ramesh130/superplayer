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

import com.superplayer.core.PlaybackProfile

/**
 * How eagerly a profile's selector climbs the ladder and how quickly it comes down: the
 * thresholds Media3's adaptive selection takes, chosen per profile instead of defaulted.
 *
 * The startup rule `PRD.md` §3.1 asks for — a low-quality start, then an upshift once the buffer
 * target is met — is not new code. Media3 chooses the first rung from the bandwidth meter's
 * estimate, which under this module is the per-transport memory's or its cold default (#99), and
 * then refuses to climb until [minDurationForQualityIncreaseMs] of media is buffered. So "once the
 * buffer target is met" is that threshold set to the profile's own start target, and the asymmetry
 * between it and [maxDurationForQualityDecreaseMs] is what makes a climb deliberate and a descent
 * prompt.
 *
 * Every departure from Media3's default carries its reason; where the default is kept it is
 * because the profile's buffer is the one the default was sized for.
 *
 * ref: https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.Factory
 */
internal data class SelectionThresholds(
    /** Media buffered before the selector may choose a higher rung. */
    val minDurationForQualityIncreaseMs: Int,
    /** The buffer under which the selector will choose a lower rung the moment the estimate says so. */
    val maxDurationForQualityDecreaseMs: Int,
    /** How much already-buffered media of a rung is kept when a better one is chosen. */
    val minDurationToRetainAfterDiscardMs: Int,
    /** The share of the estimate a chosen rung may use, the rest being headroom. */
    val bandwidthFraction: Float,
    /** On a live stream, the share of the distance to the live edge that counts as buffered. */
    val bufferedFractionToLiveEdgeForQualityIncrease: Float,
) {
    internal companion object {
        /**
         * Media3's own defaults, from `AdaptiveTrackSelection`: 10 s to climb, 25 s to descend,
         * 25 s retained, 70 % of the estimate, three quarters of the live edge.
         */
        val MEDIA3_DEFAULT: SelectionThresholds = SelectionThresholds(
            minDurationForQualityIncreaseMs = 10_000,
            maxDurationForQualityDecreaseMs = 25_000,
            minDurationToRetainAfterDiscardMs = 25_000,
            bandwidthFraction = 0.7f,
            bufferedFractionToLiveEdgeForQualityIncrease = 0.75f,
        )

        fun forProfile(profile: PlaybackProfile): SelectionThresholds = when (profile) {
            // The buffer Media3's defaults were sized for — 30 to 60 s ahead — is this profile's,
            // so the defaults are right here and kept.
            PlaybackProfile.VIDEO_ON_DEMAND -> MEDIA3_DEFAULT

            // The live profile keeps 10 to 30 s, a third of on-demand's, and its thresholds scale
            // with it: 25 s of buffer before a descent is a threshold a live buffer never crosses,
            // so the default would make every descent an emergency. Halved to climb, and brought
            // under the profile's ceiling to descend and to retain.
            PlaybackProfile.LIVE_LINEAR -> MEDIA3_DEFAULT.copy(
                minDurationForQualityIncreaseMs = 5_000,
                maxDurationForQualityDecreaseMs = 10_000,
                minDurationToRetainAfterDiscardMs = 10_000,
            )

            // A feed clip: the start target is 2.5 s, the ceiling 15 s. The climb is allowed the
            // moment the start target is met — that is the low start and the upshift, and at
            // Media3's 10 s a clip would end on its first rung — and the descent when the buffer
            // falls to a third of the ceiling. Nothing already buffered is discarded for a better
            // rung: Media3's 25 s to retain is above the whole buffer, deliberately, because a
            // clip is watched for seconds and media fetched twice is bytes on a metered link, and
            // because a buffer cut back to a few seconds on every climb is one a marginal link
            // starves.
            PlaybackProfile.SHORT_FORM -> MEDIA3_DEFAULT.copy(
                minDurationForQualityIncreaseMs = 2_500,
                maxDurationForQualityDecreaseMs = 5_000,
            )

            // Slow to climb, quick to descend, and choosing with more headroom: every climb is
            // bytes, and a rung chosen at 60 % of the estimate rather than 70 % is switched away
            // from less often, which is fewer bytes spent on media that is discarded. 15 s to climb
            // is half again Media3's; the descent at 10 s is half the profile's 20 s ceiling.
            PlaybackProfile.DATA_SAVER -> MEDIA3_DEFAULT.copy(
                minDurationForQualityIncreaseMs = 15_000,
                maxDurationForQualityDecreaseMs = 10_000,
                bandwidthFraction = 0.6f,
            )
        }
    }
}
