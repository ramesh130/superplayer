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

import com.superplayer.core.BufferPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SelectionPace

/**
 * How eagerly each profile's selector climbs the ladder and how quickly it comes down: the
 * [SelectionPace] [AdaptiveSelectionPolicy] decides, chosen per profile instead of defaulted.
 *
 * The startup rule `PRD.md` §3.1 asks for — a low-quality start, then an upshift once the buffer
 * target is met — is not new code. Media3 chooses the first rung from the bandwidth meter's
 * estimate, which under this module is the per-transport memory's or its cold default (#99), and
 * then refuses to climb until [SelectionPace.climbAfterBufferedMs] of media is buffered. So "once
 * the buffer target is met" is that threshold set to the profile's own start target, and the
 * asymmetry between it and [SelectionPace.descendBelowBufferedMs] is what makes a climb
 * deliberate and a descent prompt.
 *
 * Every departure from the engine's default ([SelectionPace.ENGINE_DEFAULT]) carries its reason;
 * where the default is kept it is because the profile's buffer is the one the default was sized
 * for. The pace travels in the decision, so these are policy constants decided behind
 * `PlaybackPolicy` as ADR-0005 rule 2 requires, and `NetworkAwareTrackSelection` reads whichever
 * pace is in force on every evaluation rather than being built with one.
 */
internal object SelectionPaces {

    fun forProfile(profile: PlaybackProfile): SelectionPace = when (profile) {
        // The buffer Media3's defaults were sized for — 30 to 60 s ahead — is this profile's,
        // so the defaults are right here and kept.
        PlaybackProfile.VIDEO_ON_DEMAND -> SelectionPace.ENGINE_DEFAULT

        // The live profile keeps 10 to 30 s, a third of on-demand's, and its thresholds scale
        // with it: 25 s of buffer before a descent is a threshold a live buffer never crosses,
        // so the default would make every descent an emergency. Halved to climb, and brought
        // under the profile's ceiling to descend and to retain.
        PlaybackProfile.LIVE_LINEAR -> SelectionPace.ENGINE_DEFAULT.copy(
            climbAfterBufferedMs = 5_000,
            descendBelowBufferedMs = 10_000,
            retainAfterDiscardMs = 10_000,
        )

        // A feed clip: the start target is 2.5 s, the ceiling 15 s. The climb is allowed the
        // moment the start target is met — that is the low start and the upshift, and at
        // Media3's 10 s a clip would end on its first rung — and the descent when the buffer
        // falls to a third of the ceiling. Nothing already buffered is discarded for a better
        // rung: Media3's 25 s to retain is above the whole buffer, deliberately, because a
        // clip is watched for seconds and media fetched twice is bytes on a metered link, and
        // because a buffer cut back to a few seconds on every climb is one a marginal link
        // starves.
        PlaybackProfile.SHORT_FORM -> SelectionPace.ENGINE_DEFAULT.copy(
            climbAfterBufferedMs = 2_500,
            descendBelowBufferedMs = 5_000,
        )

        // Slow to climb, quick to descend, and choosing with more headroom: every climb is
        // bytes, and a rung chosen at 60 % of the estimate rather than 70 % is switched away
        // from less often, which is fewer bytes spent on media that is discarded. 14 s to climb
        // is as slow as the profile's 20 s ceiling allows — the deepest buffer an evaluation sees
        // is one segment under it, [SEGMENT_ALLOWANCE_MS] — and the descent at 10 s is half the
        // ceiling.
        PlaybackProfile.DATA_SAVER -> SelectionPace.ENGINE_DEFAULT.copy(
            climbAfterBufferedMs = 14_000,
            descendBelowBufferedMs = 10_000,
            bandwidthFraction = 0.6f,
        )
    }

    /**
     * [pace] with its climb threshold brought within reach of [buffer] (#114).
     *
     * Selection is evaluated only when the player asks for the next chunk, and the player asks
     * only while the buffer is under its ceiling, so the deepest buffer an evaluation ever sees is
     * `maxBufferMs` less one chunk. A threshold above that is never met and the player never
     * climbs: which is exactly what a memory ceiling under the profile's threshold did. The
     * threshold is lowered to the reach and never raised, and at a ceiling too shallow to hold a
     * chunk beyond it is zero — a climb whenever the estimate affords one, since a buffer that
     * small has no cushion a threshold could protect. The descent and the rest are left alone: a
     * descent threshold above the ceiling makes descents prompt, which is not a pin.
     */
    fun reachableWithin(pace: SelectionPace, buffer: BufferPolicy): SelectionPace {
        val reachMs = (buffer.maxBufferMs - SEGMENT_ALLOWANCE_MS).coerceAtLeast(0)
        if (pace.climbAfterBufferedMs <= reachMs) return pace
        return pace.copy(climbAfterBufferedMs = reachMs)
    }

    /**
     * One chunk's worth of buffer, for [reachableWithin]: 6 s.
     *
     * ref: Apple, *HLS Authoring Specification for Apple Devices* — the recommended target segment
     * duration is 6 seconds. The chunk is the content's and not knowable when a policy decides, so
     * the recommended duration stands in for it; a stream with longer segments is one this reach
     * is short for by the difference.
     * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
     */
    const val SEGMENT_ALLOWANCE_MS: Int = 6_000
}
