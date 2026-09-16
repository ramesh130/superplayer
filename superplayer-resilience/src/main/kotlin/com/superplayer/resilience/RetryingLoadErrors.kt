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
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.superplayer.core.DecisionInForce
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import kotlin.random.Random

/**
 * Which of [RetryPolicy]'s three budgets a load spends.
 *
 * Derived from Media3's own `C.DATA_TYPE_*` and from nothing else, in this one place. The stamp core
 * puts on every request of a player with resilience attached — `LoadKind` — is a finer reading and
 * belongs to the header-refresh layer (#179); it cannot serve here, because
 * [LoadErrorHandlingPolicy.getMinimumLoadableRetryCount] is asked about a load with nothing in hand
 * but its data type, and a budget that answered one of Media3's two questions from the stamp and the
 * other from the type could disagree with itself.
 */
internal enum class RetryBudgetKind {

    /** A playlist, an MPD, a steering manifest, a time sync: a description of where media is. */
    MANIFEST,

    /** A segment, an initialization segment, a progressive file: media, or what decodes it. */
    SEGMENT,

    /** A licence or provisioning round trip. See [RetryPolicy.licence] for why none arrives today. */
    LICENCE,
    ;

    /** That kind's budget out of [policy]. */
    fun budgetIn(policy: RetryPolicy): RetryBudget = when (this) {
        MANIFEST -> policy.manifest
        SEGMENT -> policy.segment
        LICENCE -> policy.licence
    }

    companion object {

        /**
         * The kind Media3's [dataType] names.
         *
         * A steering manifest and a time synchronization are grouped with the manifest because they
         * are the same thing in the way that matters here: a description the player needs before it
         * can ask for media, fetched on the manifest's own schedule. Everything not named is media —
         * an unknown type, an ad, a custom one an app assigned — because the segment budget is the
         * one that failing to reach the right answer costs least: it is spent on one object of a
         * stream that is otherwise playing, rather than on the description the whole session rests
         * on.
         */
        fun ofDataType(dataType: Int): RetryBudgetKind = when (dataType) {
            C.DATA_TYPE_MANIFEST,
            C.DATA_TYPE_STEERING_MANIFEST,
            C.DATA_TYPE_TIME_SYNCHRONIZATION,
            -> MANIFEST

            C.DATA_TYPE_DRM -> LICENCE

            else -> SEGMENT
        }
    }
}

/**
 * The object Media3 asks about a failed load, answering out of the ladder rather than out of its own
 * defaults — the Media3-facing half of ADR-0011 rule 11, and the reason that rule gives the name
 * `RetryPolicy` to the *numbers* and not to this.
 *
 * Internal, because `LoadErrorHandlingPolicy` is an `@UnstableApi` type and ADR-0001 rule 2 keeps
 * those off SuperPlayer's public API. One of these per player, filling
 * `EngineConfiguration.loadErrors`, from where core hands it to the `MediaSource.Factory`
 * `TransferChain` assembles and so to every protocol's source.
 *
 * ## What it reads, and when
 *
 * [decisions] on **every** consultation, never once at construction: rule 11 requires a re-consulted
 * decision to be honoured whole on the next load error with nothing rebuilt, which is the contract
 * `NetworkAwareTrackSelection` has for the selection pace. So an adaptive policy that shortens the
 * budget after a stall has shortened it for the next failure, not for the next player.
 *
 * [ErrorClassifier] for what the failure is, and nothing else — no status is read here, no error
 * code is branched on, and there is no second taxonomy (ADR-0011 rule 1).
 *
 * ## The two questions, and the one climb
 *
 * Media3 asks a chunk source's policy for a fallback selection first and a retry delay second, which
 * is the ladder upside down; [FallbackLadder]'s KDoc says why that matters and how it is re-imposed.
 * Both answers below come out of one climb of one ladder, so a rung is never reached before the rung
 * beneath it has declined.
 */
internal class RetryingLoadErrors(
    private val decisions: DecisionInForce,
    private val ladder: FallbackLadder,
) : LoadErrorHandlingPolicy {

    /**
     * Only a selection: a climb that ended at rung 1 answers null here, so that Media3 goes on to
     * ask the retry question, which is where rung 1's answer belongs.
     */
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? =
        (climb(loadErrorInfo, fallbackOptions) as? RungOutcome.FallBackTo)?.selection

    /**
     * The backoff rung 1 decided, or `C.TIME_UNSET` — Media3's "do not retry" — when the budget is
     * spent, the class is not retryable, or a rung above took the failure on.
     *
     * `C.TIME_UNSET` is the escalation seam and not the end of the session: it is the point at which
     * everything above rung 3 begins, which today means the failure surfaces to the consumer exactly
     * as it does on a player with no resilience at all (ADR-0011 rule 7, and see [FallbackLadder]).
     *
     * The climb behind this answer carries no [LoadErrorHandlingPolicy.FallbackOptions], because
     * Media3 hands none to this question, so rungs 2 and 3 decline it and only rung 1 can answer.
     * That is the right shape rather than a gap: every source that has a location or a track to fall
     * back to asks the *other* question first and acts on what it is told there, so the only loads
     * that reach here alone are the ones with nowhere above rung 1 to go.
     */
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        when (val outcome = climb(loadErrorInfo)) {
            is RungOutcome.RetryAfter -> outcome.delayMs
            else -> C.TIME_UNSET
        }

    /**
     * The same count rung 1 enforces, so Media3's own patience and this object's agree.
     *
     * Media3 uses it in `Loader.maybeThrowError`, which surfaces a persistent error to the renderer
     * once the error count passes it. Answering anything larger than the budget would leave a load
     * that this object has already stopped retrying being waited on; anything smaller would surface
     * a failure the budget had not finished spending.
     */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        RetryBudgetKind.ofDataType(dataType).budgetIn(retryPolicy()).maxRetries

    private fun climb(
        info: LoadErrorHandlingPolicy.LoadErrorInfo,
        options: LoadErrorHandlingPolicy.FallbackOptions? = null,
    ): RungOutcome {
        val kind = RetryBudgetKind.ofDataType(info.mediaLoadData.dataType)
        return ladder.climb(
            FailedLoad(
                failureClass = ErrorClassifier.classify(info.exception),
                budget = kind.budgetIn(retryPolicy()),
                retry = info.errorCount,
                info = info,
                fallbackOptions = options,
            ),
        )
    }

    /**
     * The retry half of the decision in force, or Media3's own numbers in the window before the
     * policy has been consulted — which closes before the engine is built, and so before any load
     * of this player's can have failed.
     */
    private fun retryPolicy(): RetryPolicy = decisions.current()?.retry ?: RetryPolicy.MEDIA3_DEFAULT

    companion object {

        /** One for a player, with its own source of jitter. */
        fun forPlayer(decisions: DecisionInForce, random: Random): RetryingLoadErrors =
            RetryingLoadErrors(decisions, FallbackLadder.standard(random))
    }
}
