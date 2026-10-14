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

import android.content.Context
import com.superplayer.core.DecisionTarget
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EnginePolicyExtension
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.deviceConstraintsOf

/**
 * `superplayer-abr`'s entry point: the adaptive [PlaybackPolicy] for a profile, with the engine
 * components that let a player honour its decisions as they change.
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setProfile(PlaybackProfile.VIDEO_ON_DEMAND)
 *     .setPolicy(AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND))
 *     .build()
 * ```
 *
 * A public type on both sides and no Media3 type in sight, which is ADR-0009 rule 7's shape: what
 * comes back is a [PlaybackPolicy], and what makes it more than [AdaptiveBufferPolicy] and
 * [AdaptiveSelectionPolicy] composed is invisible to the caller — the object also implements
 * core's internal extension interface, so `SuperPlayer.Builder.build()` lets it install a
 * [BandwidthOracle]'s meter, an [AdaptiveLoadControl] and a `NetworkAwareTrackSelection` factory,
 * and hand every later decision back to them. The live half needs no component: it travels on the
 * media item, and core lays it in on every player. On a player built with it the policy is
 * consulted again on every `DecisionTrigger`; either pure policy alone, handed to `setPolicy` by a
 * consumer, is consulted once and answers with the static profile's numbers, which is ADR-0009
 * rule 5 working as written.
 *
 * **One policy object per player, or per pool.** The components it installs are one engine's — a
 * load control carries its players' prepare state, an oracle one transport callback — so a second
 * `build()` of a player of its own with the same object is refused rather than shared. A
 * `PlayerPool` built with `setPolicy` is the exception, and is one engine: the pool configures it
 * once and every player it builds shares the components, so a decision re-applied on one row is
 * re-applied for all (ADR-0010 rule 9). The oracle is released when the last engine releases the
 * load control, so a consumer who releases the player, or the pool, has released everything.
 */
public object AdaptivePolicy {

    /** The adaptive policy for [profile], ready for `SuperPlayer.Builder.setPolicy`. */
    public fun forProfile(context: Context, profile: PlaybackProfile): PlaybackPolicy =
        AdaptiveEnginePolicy(context.applicationContext, AdaptiveBufferPolicy(profile), AdaptiveSelectionPolicy(profile))
}

/**
 * The extension half of [AdaptivePolicy]: the two pure policies, plus what they install.
 *
 * The target honours both halves: the buffer half through [AdaptiveLoadControl], the selection
 * half through [NetworkAwareTrackSelection]'s factory, whose ceiling every selection it built
 * reads on its next evaluation. That is why `EngineBinding.kt` lays no ceiling into the engine's
 * `TrackSelectionParameters` on a player built with this policy (ADR-0009 rule 5): a ceiling laid
 * there would clamp every later decision that raised it, and a consumer's own parameters are
 * never rewritten by a trigger. The decoder is read here, once, and handed to the factory as a
 * constraint rather than observed (rule 2); the display is core's live reading, which the factory's
 * gate reads in force (ADR-0014 rule 10).
 */
internal class AdaptiveEnginePolicy(
    private val context: Context,
    private val buffer: AdaptiveBufferPolicy,
    private val selection: AdaptiveSelectionPolicy,
) : EnginePolicyExtension {

    private var configured = false

    /** The load control this policy's engine was configured with, once it has been. */
    private var loadControl: AdaptiveLoadControl? = null

    /**
     * Each pure policy owns one half; the live half travels with the buffer's. The selection half
     * is decided first because the buffer's memory ceiling is sized at the rate the selector can
     * fill it, which is the ceiling in force and not the profile's own (#115). Then the pace's
     * climb threshold is brought within reach of the buffer just decided, because a memory
     * ceiling under the threshold is a player pinned to its first rung (#114) and neither pure
     * policy sees the other's number.
     */
    override fun decide(conditions: PlaybackConditions): PlaybackDecision {
        val trackSelection = selection.decide(conditions).trackSelection
        val decided = buffer.decide(conditions, selectionInForce = trackSelection)
        val pace = trackSelection.pace?.let { SelectionPaces.reachableWithin(it, decided.buffer) }
        return decided.copy(trackSelection = trackSelection.copy(pace = pace))
    }

    override fun configureEngine(configuration: EngineConfiguration) {
        check(!configured) {
            "An adaptive policy serves one player or one PlayerPool; build another with AdaptivePolicy.forProfile"
        }
        configured = true

        val oracle = BandwidthOracle.Builder(context).build()
        val loadControl = AdaptiveLoadControl(onEngineReleased = oracle::release).also { this.loadControl = it }
        // The decoders are read here, once, and the first ceiling and pace are the profile's own with
        // nothing observed; the first consultation's decision replaces them before anything plays.
        //
        // Protection is read from the same configuration, and for a pool that is the first player's
        // answer: a pool built with this policy configures one engine and shares it
        // (`AdaptivePolicyPoolTest`), and `PlayerPool.Builder.setDrm` gives every player in it the
        // same protection, so there is one answer to read.
        val selections = NetworkAwareTrackSelection.Factory(
            NetworkAwareTrackSelection.Gate(
                constraints = deviceConstraintsOf(),
                source = oracle.meter,
                initial = decide(PlaybackConditions()).trackSelection,
                protectedPlayback = configuration.protectedPlayback,
                // The display is not read here: it is core's window, written at construction on every
                // player and again on each change where an output watches it (ADR-0014 rule 10).
                displayInForce = configuration.displayInForce,
            ),
        )

        oracle.configure(configuration)
        configuration.loadControl = loadControl
        configuration.trackSelectionFactory = selections
        configuration.decisionTarget = DecisionTarget { decision ->
            loadControl.retarget(decision.buffer)
            selections.retarget(decision.trackSelection)
        }
    }

    /** A pooled player after the first shares this engine, so the oracle waits for its release too. */
    override fun onComponentsShared() {
        checkNotNull(loadControl) { "Components are shared only after they were configured" }.addEngine()
    }
}
