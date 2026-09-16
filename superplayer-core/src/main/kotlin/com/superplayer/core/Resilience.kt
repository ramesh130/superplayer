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

package com.superplayer.core

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource

/**
 * How a player survives a failure: the retry, the fallback ladder and the token refresh
 * `superplayer-resilience` performs on its behalf (ADR-0011).
 *
 * The handle a consumer names in [SuperPlayer.Builder.setResilience], and nothing else. Every
 * decision it stands for — which rungs a failure may reach, how long to back off and with what
 * jitter, when to refresh a token — is that module's, behind this type; what core has here is the
 * type itself, so that the builder can take one without naming a later phase and without a Media3
 * class appearing in a public signature (ADR-0001 rule 2).
 *
 * It is a type rather than a configuration value for the reason [SuperPlayer.Builder.setTelemetry]
 * takes a collector and [SuperPlayer.Builder.setCache] takes a [ContentCache]: the wrong call must
 * not compile. A number or a flag passed here would type-check and survive nothing.
 *
 * **One object may serve many players, concurrently.** [PlayerPool.Builder.setResilience] takes a
 * single instance and fills every pooled player's slots from it, so in a feed the layer it installs is
 * opened from several players' loading threads at once. That is the right shape for what Phase 5 puts
 * here — one refresh of an expiring token should serve every player loading from that CDN rather than
 * each rediscovering the 401 — but it makes thread safety the implementation's obligation rather than
 * an accident of how a consumer builds. `PlayerPoolTest.aPoolWithResilienceFillsTheSlotsOfEveryPlayerItBuildsFromTheOneObject`
 * is where that sharing is pinned; #179 is where it first matters.
 *
 * Implementations come from `superplayer-resilience`, and reach the engine as [EngineResilienceExtension];
 * a `PlaybackResilience` that is not one fills no slot, registers nothing and changes no transfer,
 * which is the same contract a [PlaybackPolicy] that is not an [EnginePolicyExtension] has.
 */
public interface PlaybackResilience

/**
 * The seam by which `superplayer-resilience` reaches an engine core builds: a [PlaybackResilience]
 * that *also* implements this fills the two slots ADR-0011 rule 13 names.
 *
 * Internal, and reachable from `superplayer-resilience` because that module compiles as the fifth
 * Kotlin friend of core (`build-logic`'s `KotlinFriendModules.kt` carries the argument for why a
 * friend path is not a widening of the seam, and why the count is worth watching). The public half
 * of the same call is [SuperPlayer.Builder.setResilience], whose parameter is [PlaybackResilience]:
 * a consumer names a SuperPlayer type on both sides, and the `LoadErrorHandlingPolicy` and
 * `DataSource.Factory` this deals in — both `@UnstableApi` — stay behind the facade.
 *
 * Why an interface on the resilience object rather than two builder calls, and why not `ServiceLoader`:
 * [EnginePolicyExtension] answers both, and the answers are the same ones. The difference from that
 * interface is *when* this runs. A policy extension configures one engine per pool, because the
 * components it installs are the pool's to share (ADR-0010 rule 9); the slots here are a chain layer
 * and a load-error policy, and a chain is built per player, so this is called once per engine —
 * pooled or not — and before the test configurator, so a test's engine configuration still wins.
 */
internal interface EngineResilienceExtension : PlaybackResilience {

    /**
     * Fills the slots of [configuration] this resilience needs: [EngineConfiguration.loadErrors],
     * [EngineConfiguration.headerRefresh], or both. Called once per `build()`, on the thread
     * building the player, before the test configurator.
     */
    fun configureEngine(configuration: EngineConfiguration)
}

/**
 * The link `superplayer-resilience` contributes to `TransferChain`'s header-refresh slot: below the
 * cache slot and directly over the transport, so that a token refresh and the retry it triggers are
 * a single transfer to everything above (ADR-0011 rule 13).
 *
 * Called once per player, with the chain beneath the slot — which is the transport itself. The
 * returned factory must forward every `addTransferListener` it is handed to [over]'s upstream, as
 * every layer of the chain must, or bandwidth estimation goes silently blind (`TransferChain`'s
 * KDoc).
 *
 * What the layer needs in order to tell a 403 on a segment from a failure of the manifest is on each
 * request: [LoadKind.of] reads off a `DataSpec` what kind of load it is, and a player with this slot
 * filled stamps every request with one whether or not it also has a cache. An identity is a cache's
 * business and is stamped only where there is one, so [ContentIdentity.of] is null here on a player
 * built with resilience alone.
 *
 * Refreshing is this layer's and the classification of what it saw is [PlaybackResilience]'s; core
 * neither reads a status here nor re-derives a detection it has already made above (ADR-0011 rule 4).
 */
internal fun interface HeaderRefreshLayer {

    /** [upstream], with each request repaired and retried where this layer's provider can repair it. */
    fun over(upstream: DataSource.Factory): DataSource.Factory
}

/**
 * The third slot, and the one that faces the other way: what core *asks* once every rung a load
 * error could answer has declined (ADR-0011 rule 5).
 *
 * Rungs 1 to 3 are answers `superplayer-resilience` gives Media3 about a load, from a loading thread,
 * with the load in hand. Rung 4 is not one of those: opening the next entry of [MediaRequest.sources]
 * is a re-adoption, an operation on the player's own state, and the only object that can perform it
 * is the facade. So the module's ladder stops where a climb escalates out of the top of it, and the
 * failure arrives here instead — at the player, on the application thread, where the position
 * playback reached is still readable and a media item may be replaced.
 *
 * **Which direction "only when the ladder asks" runs.** Core does not decide that a failure has
 * earned rung 4 and it reads no error code — that would be the second taxonomy ADR-0011 rule 1 forbids.
 * It asks this, and the answer is the ladder's own decision about the rung core is about to perform;
 * what core contributes is the half the module cannot see, which is whether there is a next source at
 * all. A player built with no resilience has nothing to ask and therefore performs no rung, which is
 * rule 14's accounting for this slot: it registers no listener either.
 *
 * Asked on the application thread, once per surfaced failure, and expected to be pure — the decision
 * is memoized against the failure it was made for, so an implementation that answered differently the
 * second time would not be asked twice anyway.
 *
 * Rung 5, the recreated decoder, is the other half of rule 5 and is not here yet: it arrives as a
 * second question on this interface (#182), because the rung order is one order and both rungs are
 * asked of the same object.
 */
internal interface PlayerStateRungs {

    /**
     * Whether [error] may climb to rung 4 — the next source — at all, which is the class's ceiling
     * and nothing else.
     *
     * "May", not "should": whether there *is* a next source to open is core's half of the question
     * and is not visible from here.
     */
    fun opensNextSource(error: PlaybackException): Boolean
}
