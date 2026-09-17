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

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.superplayer.core.DecisionInForce
import com.superplayer.core.DownloadResilienceExtension
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EngineResilienceExtension
import com.superplayer.core.HeaderRefreshLayer
import com.superplayer.core.LoadKind
import com.superplayer.core.PlaybackResilience
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayerError
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
internal class StandardResilience(private val headers: HeaderProvider?) :
    EngineResilienceExtension,
    DownloadResilienceExtension {

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

    // A download's failure is rung 6's typed error with nothing climbed and nowhere reached: a download
    // has no ladder and no playhead, and what it is is the classifier's as it is for a player.
    override fun failureOf(error: Throwable): SuperPlayerError = TypedError.of(error, C.TIME_UNSET, rungsTried = emptyList())

    // The one class that is the path to the origin rather than the origin or the content — the band's own
    // fall-through for a transfer that failed with nothing narrowing it (ADR-0011 rule 2) — and only where no
    // server answered. A 5xx is the same class, but a network that carried an answer was not lost: it spends
    // the segment's budget and then fails named, rather than being waited out for ever (ADR-0013 rule 9's
    // addendum for #254). A segment the edge refused or lost is `Transient.CdnEdge`, which spends it too.
    override fun isNetworkLoss(error: Throwable): Boolean =
        ErrorClassifier.classify(error) == FailureClass.Transient.Network && !ErrorClassifier.aServerAnswered(error)

    override fun waitBeforeResumingMs(attempt: Int): Long = Backoff.delayMsFor(NETWORK_RESUMPTION, attempt, Random.Default)

    // Rung 1's two refusals, in rung 1's order, and nothing above it — written here rather than by asking
    // `RetrySameUrl`, whose `FailedLoad` carries Media3's `LoadErrorInfo`, which a downloader's failure has none of: a download has no host, variant or
    // source to fall back to, so the same bytes asked for again is the whole of its ladder (`RetrySameUrl`).
    // A manifest spends the manifest budget and everything else the segment budget, read off the stamp the
    // download chain puts on every request, as `RetryBudgetKind` reads Media3's data type on a player.
    override fun waitBeforeRetryingMs(error: Throwable, retry: Int, policy: RetryPolicy): Long? {
        if (!ErrorClassifier.classify(error).retryable) return null
        val budget = if (ErrorClassifier.loadKindIn(error) == LoadKind.MANIFEST) policy.manifest else policy.segment
        if (retry > budget.maxRetries) return null
        return Backoff.delayMsFor(budget, retry, Random.Default)
    }

    // One layer per store, for the reason a player gets one of its own: the credential it refreshes is the
    // store's. None without a provider, where a player gets the pass-through only so core stamps its requests,
    // which a download's chain does anyway.
    override fun downloadHeaderRefresh(): HeaderRefreshLayer? = headers?.let { TokenRefreshLayer(it) }

    // The very object a player's DRM slot is handed (#205), over the store's decision rather than a player's:
    // Media3 asks it about a licence load with `C.DATA_TYPE_DRM`, which `RetryBudgetKind` reads as the licence
    // budget. The ladder is the whole standard one, but Media3's `DefaultDrmSession` asks only the retry question
    // and never for a fallback, so rung 1 is all it climbs, as for a download's own requests. The `ClimbRecord`
    // is `forPlayer`'s default, which nothing reads: a store reports no rungs tried (`failureOf`).
    override fun downloadLicenceErrors(decisions: DecisionInForce): LoadErrorHandlingPolicy =
        RetryingLoadErrors.forPlayer(decisions, Random.Default)

    private companion object {

        /**
         * How a download stopped for a lost network paces its attempts to resume: the jittered doubling
         * every retry here draws (ADR-0011 rule 12), with no count that ends it, because a network that is gone is
         * waited for rather than given up on (ADR-0013 rule 9). Two seconds first, which lets a blip — a
         * handover, a tunnel — pass unnoticed; five minutes at most, which a network that stays down for
         * an afternoon costs a dozen round trips an hour and a viewer who comes back into coverage waits
         * at worst.
         */
        val NETWORK_RESUMPTION = RetryBudget(maxRetries = Int.MAX_VALUE, initialBackoffMs = 2_000, maxBackoffMs = 5 * 60_000)
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
