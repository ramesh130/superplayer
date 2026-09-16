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
 * There is one argument and deliberately only one. Everything ADR-0011 rule 12 calls correctness —
 * the jitter, the rung order, *when* a token is refreshed, the position a rung resumes at — is on
 * for every player this is attached to and is not a caller's to vary; everything rule 11 calls
 * policy — the retry budgets — is decided behind `PlaybackPolicy` as
 * [com.superplayer.core.PlaybackDecision.retry] and read from the decision in force, so it arrives
 * from the profile or the policy rather than from an argument here. What the [HeaderProvider]
 * argument carries is neither: it is the credential itself, which only the app can mint, and no
 * profile, policy or library default could stand in for it.
 */
public object Resilience {

    /**
     * The resilience this module ships: ADR-0011's ladder, as far as it is built.
     *
     * [headers] is where a refused request's next credential comes from. With none, a 401 or 403 is
     * a load error like any other and the ladder is all there is to meet it; with one, the refusal
     * is repaired before a retry is spent on it ([HeaderProvider], [TokenRefreshLayer]).
     *
     * One object may be handed to many players — a pool fills every player's slots from the one it
     * was given — so nothing returned here holds a player's state; what is per player is built per
     * player, in [StandardResilience.configureEngine].
     */
    @JvmStatic
    @JvmOverloads
    public fun standard(headers: HeaderProvider? = null): PlaybackResilience = StandardResilience(headers)
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
internal class StandardResilience(private val headers: HeaderProvider?) : EngineResilienceExtension {

    override fun configureEngine(configuration: EngineConfiguration) {
        // A source of jitter per player rather than one for the process, so that two players that
        // met the same edge failure at the same instant — which in a feed is the ordinary case —
        // draw different waits. `Random.Default` is shared and would still do that; a per-player
        // instance is here so a test can substitute a seeded one at this seam later without the
        // players of one pool sharing a sequence.
        // One record of what this player's climb has tried, written by every rung and read by rung 6
        // (`SuperPlayerError.rungsTried`). Per player rather than shared for the reason the jitter is:
        // in a feed, one row's retries are not another row's history.
        val climb = ClimbRecord()
        configuration.loadErrors =
            RetryingLoadErrors.forPlayer(configuration.decisionInForce, Random.Default, climb)
        // One layer per player, because the credential it refreshes is the session's, and the
        // pass-through where there is no provider to refresh one with. Filled either way rather than
        // left null, because a player with either slot filled is the player whose requests core
        // stamps with their `LoadKind`, and that stamp is what lets `ErrorClassifier` tell a refused
        // segment from a refused manifest (`Transient.CdnEdge` exists only for the former).
        configuration.headerRefresh = headers?.let { TokenRefreshLayer(it) } ?: PassThroughHeaderRefresh
        // The rungs above everything a load error can answer, and the one slot that faces the other
        // way: core asks it once a failure has got past the ladder above (ADR-0011 rules 5 and 10).
        // One per player, and only because rung 6 reports the climb: the request, the position and
        // the decoders in hand are still the player's and are never copied to this side, but what
        // this player has already tried is a fact about this player.
        configuration.playerStateRungs = PlayerStateLadder(climb)
    }
}

/**
 * The header-refresh slot, occupied and doing nothing: what fills it for a player built with no
 * [HeaderProvider], since there is then no credential to repair a refused request with.
 *
 * It returns the upstream factory unchanged rather than wrapping it, which is what makes "occupied
 * and doing nothing" exactly true: no data source is allocated, no transfer is intercepted, and
 * every `addTransferListener` reaches the transport because there is no layer in between to drop it
 * (`TransferChain`'s rule for every layer).
 */
internal object PassThroughHeaderRefresh : HeaderRefreshLayer {
    override fun over(upstream: DataSource.Factory): DataSource.Factory = upstream
}
