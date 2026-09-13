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
 * comes back is a [PlaybackPolicy], and what makes it more than [AdaptiveBufferPolicy] on its own
 * is invisible to the caller — the object also implements core's internal extension interface, so
 * `SuperPlayer.Builder.build()` lets it install a [BandwidthOracle]'s meter and an
 * [AdaptiveLoadControl], and hand every later decision back to them. The live half needs no
 * component: it travels on the media item, and core lays it in on every player. On a player built with it the policy is consulted again on every `DecisionTrigger`;
 * `AdaptiveBufferPolicy` alone, handed to `setPolicy` by a consumer, is consulted once and answers
 * with the static profile's numbers, which is ADR-0009 rule 5 working as written.
 *
 * **One policy object per player.** The components it installs are a player's own — a load
 * control carries that player's prepare state, an oracle that player's transport callback — so a
 * second `build()` with the same object is refused rather than shared. The oracle is released when
 * the engine releases its load control, so a consumer who releases the player has released
 * everything.
 */
public object AdaptivePolicy {

    /** The adaptive policy for [profile], ready for `SuperPlayer.Builder.setPolicy`. */
    public fun forProfile(context: Context, profile: PlaybackProfile): PlaybackPolicy =
        AdaptiveEnginePolicy(context.applicationContext, AdaptiveBufferPolicy(profile), AdaptiveSelectionPolicy(profile))
}

/**
 * The extension half of [AdaptivePolicy]: the pure policy, plus what it installs.
 *
 * The target honours the buffer half. The selection half of each decision — the profile's own
 * caps, and the ceiling branch 4 holds after a rebuffer — is *emitted* here and honoured by
 * `NetworkAwareTrackSelection` (#101), which does not exist yet; until it does, no ceiling is in
 * force on a player built with this policy. That is ADR-0009 rule 5 as written: on a player whose
 * engine can honour a changed decision, `EngineBinding.kt` lays no ceiling into the engine's
 * `TrackSelectionParameters`, because a ceiling laid there would clamp every later decision that
 * raised it, and a consumer's own parameters are never rewritten by a trigger. A consumer who needs
 * `DATA_SAVER`'s caps in force today keeps the profile's static policy until #101 lands.
 */
internal class AdaptiveEnginePolicy(
    private val context: Context,
    private val buffer: AdaptiveBufferPolicy,
    private val selection: AdaptiveSelectionPolicy,
) : EnginePolicyExtension {

    private var configured = false

    /** Each pure policy owns one half; the live half travels with the buffer's. */
    override fun decide(conditions: PlaybackConditions): PlaybackDecision =
        buffer.decide(conditions).copy(trackSelection = selection.decide(conditions).trackSelection)

    override fun configureEngine(configuration: EngineConfiguration) {
        check(!configured) { "An adaptive policy serves one player; build another with AdaptivePolicy.forProfile" }
        configured = true

        val oracle = BandwidthOracle.Builder(context).build()
        val loadControl = AdaptiveLoadControl(onEngineReleased = oracle::release)

        oracle.configure(configuration)
        configuration.loadControl = loadControl
        configuration.decisionTarget = DecisionTarget { decision -> loadControl.retarget(decision.buffer) }
    }
}
