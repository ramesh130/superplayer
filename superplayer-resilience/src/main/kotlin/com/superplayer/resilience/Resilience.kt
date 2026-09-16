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

package com.superplayer.resilience

import androidx.media3.datasource.DataSource
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EngineResilienceExtension
import com.superplayer.core.HeaderRefreshLayer
import com.superplayer.core.PlaybackResilience
import kotlin.random.Random

/**
 * This module's entry point: what `SuperPlayer.Builder.setResilience` and
 * `PlayerPool.Builder.setResilience` are handed.
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setProfile(PlaybackProfile.VIDEO_ON_DEMAND)
 *     .setResilience(Resilience.standard())
 *     .build()
 * ```
 *
 * A factory rather than a constructor for the reason `AdaptivePolicy.forProfile` is one: what it
 * returns is a [PlaybackResilience] — a core type naming no Media3 class — while what it actually
 * builds is core's internal `EngineResilienceExtension`, which is how the load-error policy and the
 * chain layer reach the engine without either appearing in a signature a consumer can see (ADR-0011
 * rule 13, ADR-0001 rule 2).
 *
 * There is nothing to configure here and deliberately so. Everything ADR-0011 rule 12 calls
 * correctness — the jitter, the rung order, the token refresh, the position a rung resumes at — is
 * on for every player this is attached to and is not a caller's to vary; everything rule 11 calls
 * policy — the retry budgets — is decided behind `PlaybackPolicy` as
 * [com.superplayer.core.PlaybackDecision.retry] and read from the decision in force, so it arrives
 * from the profile or the policy rather than from an argument here.
 */
public object Resilience {

    /**
     * The resilience this module ships: ADR-0011's ladder, as far as it is built.
     *
     * One object may be handed to many players — a pool fills every player's slots from the one it
     * was given — so nothing returned here holds a player's state; what is per player is built per
     * player, in [StandardResilience.configureEngine].
     */
    @JvmStatic
    public fun standard(): PlaybackResilience = StandardResilience()
}

/**
 * The extension half of [Resilience.standard]: it fills the two slots ADR-0011 rule 13 opens, once
 * per engine.
 *
 * Internal, so that the core-internal interface it implements stays off this module's public API.
 *
 * Stateless, because it has to be: `PlaybackResilience`'s KDoc makes thread safety the
 * implementation's obligation, and the way to discharge it here is to hold nothing. Everything with
 * a lifetime is built inside [configureEngine], which runs once per player, and belongs to that
 * player alone.
 */
internal class StandardResilience : EngineResilienceExtension {

    override fun configureEngine(configuration: EngineConfiguration) {
        // A source of jitter per player rather than one for the process, so that two players that
        // met the same edge failure at the same instant — which in a feed is the ordinary case —
        // draw different waits. `Random.Default` is shared and would still do that; a per-player
        // instance is here so a test can substitute a seeded one at this seam later without the
        // players of one pool sharing a sequence.
        configuration.loadErrors = RetryingLoadErrors.forPlayer(configuration.decisionInForce, Random.Default)
        // Filled, and today a pass-through. Two reasons it is filled rather than left null: a player
        // with either slot filled is the player whose requests core stamps with their `LoadKind`,
        // and that stamp is what lets `ErrorClassifier` tell a refused segment from a refused
        // manifest (`Transient.CdnEdge` exists only for the former); and #179 replaces the body
        // below without having to reach back into this file.
        configuration.headerRefresh = PassThroughHeaderRefresh
    }
}

/**
 * The header-refresh slot, occupied and doing nothing — #179 is where a request acquires a repaired
 * credential and is asked again.
 *
 * It returns the upstream factory unchanged rather than wrapping it, which is what makes "occupied
 * and doing nothing" exactly true: no data source is allocated, no transfer is intercepted, and
 * every `addTransferListener` reaches the transport because there is no layer in between to drop it
 * (`TransferChain`'s rule for every layer).
 */
internal object PassThroughHeaderRefresh : HeaderRefreshLayer {
    override fun over(upstream: DataSource.Factory): DataSource.Factory = upstream
}
