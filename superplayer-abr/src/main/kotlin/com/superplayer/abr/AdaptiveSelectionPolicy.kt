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

import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.StallHistory
import com.superplayer.core.ThroughputEstimate
import com.superplayer.core.TrackSelectionPolicy
import kotlin.math.min

/**
 * The selection half of a [PlaybackDecision], recomputed from the conditions: what is *eligible*
 * on this transport, after this stall — the ceiling under which Media3's adaptive selection then
 * chooses. `PRD.md` §3.1's transport caps and the post-rebuffer hold, as [AdaptiveBufferPolicy]
 * is its five buffer branches.
 *
 * A pure function of its [PlaybackConditions], for the same reasons as its twin: every rule below
 * is asserted by constructing conditions by hand (`AdaptiveSelectionPolicyTest`), and hysteresis is
 * read from [StallHistory] rather than remembered here (ADR-0009 rule 4). The buffer half it emits
 * is the static profile's, so that composed with [AdaptiveBufferPolicy] each policy owns one half
 * and neither restates the other's.
 *
 * ## The rules, in order
 *
 * 1. **The profile's own cap**, which is where every rule starts and what none may raise: a data
 *    saver on the fastest link is still saving data.
 * 2. **The transport's cap.** [TransportCaps] names, per profile and per kind of link, the rung
 *    above which F1's trade is made — quality given up on a metered, interruptible link for fewer
 *    interruptions — and the smaller of it and the profile's is the ceiling. The transport is an
 *    observation, so a handover re-consults this policy on `TRANSPORT_CHANGED` and the cap moves
 *    with the link.
 * 3. **After a rebuffer, a hold.** For [AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS] after a
 *    rebuffer ends, the ceiling is held at what the network delivered *most of the time* — the
 *    conservative percentile, or the mean where the meter has none — so the selector cannot climb
 *    straight back to the rung that stalled. It lapses at the first consultation after the
 *    cooldown, which is the next trigger and never the moment itself, because a policy is
 *    consulted on triggers and not on a timer; the buffer policy's KDoc argues why that is the
 *    right shape as well as the permitted one. One cooldown, defined once on the buffer policy,
 *    cited from both: the raised floor and the held ceiling lapse together.
 * 4. **The profile's pace.** How eagerly selection climbs and descends under the ceiling, from
 *    [SelectionPaces]. Alone this policy emits the table's pace, which sits inside the profile's
 *    own buffer; composed, [AdaptivePolicy] brings its climb threshold within reach of the buffer
 *    the other half decided, which only the composition can see.
 *
 * What is *not* here: the display and the decoder. Those are constraints the selector reads once
 * when it is built (ADR-0009 rule 2), not observations a policy is consulted about, and
 * `NetworkAwareTrackSelection` applies them under whatever ceiling this policy emits.
 */
public class AdaptiveSelectionPolicy(public val profile: PlaybackProfile) : PlaybackPolicy {

    private val staticProfile: PlaybackPolicy = PlaybackPolicy.forProfile(profile)

    override fun decide(conditions: PlaybackConditions): PlaybackDecision {
        val base = staticProfile.decide(conditions)
        var selection = base.trackSelection.copy(pace = SelectionPaces.forProfile(profile))

        conditions.transport?.let { transport ->
            selection = selection.under(TransportCaps.capFor(profile, transport))
        }

        val measured = conditions.throughput?.takeIf { it.sampleCount > 0 }
        if (measured != null && inCooldown(conditions.stallHistory)) {
            selection = selection.heldAt(measured)
        }

        return base.copy(trackSelection = selection)
    }

    private fun inCooldown(history: StallHistory): Boolean {
        val since = history.msSinceLastRebufferEnded ?: return false
        return since < AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS
    }

    /** The tighter of two ceilings on each axis, at this policy's pace. */
    private fun TrackSelectionPolicy.under(cap: TrackSelectionPolicy): TrackSelectionPolicy = copy(
        maxVideoBitrateBps = min(maxVideoBitrateBps, cap.maxVideoBitrateBps),
        maxVideoHeightPx = min(maxVideoHeightPx, cap.maxVideoHeightPx),
    )

    /**
     * Rule 3's held ceiling: the conservative percentile, or the mean where the meter has none,
     * under the ceiling already in force. A reading of zero holds nothing — there is no rung under
     * it to hold at.
     */
    private fun TrackSelectionPolicy.heldAt(measured: ThroughputEstimate): TrackSelectionPolicy {
        val delivered = (measured.conservativeBps ?: measured.meanBps).takeIf { it > 0 } ?: return this
        val held = min(delivered, maxVideoBitrateBps.toLong()).toInt()
        return copy(maxVideoBitrateBps = held)
    }
}
