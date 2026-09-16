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

import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.superplayer.core.RetryBudget
import kotlin.random.Random

/**
 * One failed load, as a rung sees it.
 *
 * A value rather than three parameters because every rung needs the same three facts and a rung
 * added later will need them too: what the failure *is*, what the budget for this kind of load
 * allows and how much of it has gone, and Media3's own description of the load — the last for rungs
 * that answer a question about locations or tracks and have to name one back.
 */
internal class FailedLoad(
    /** What [ErrorClassifier] made of the failure. The only reading of it any rung does. */
    val failureClass: FailureClass,
    /**
     * The budget for this kind of load, as the decision in force at *this* consultation states it
     * (ADR-0011 rule 11). Which of [com.superplayer.core.RetryPolicy]'s three it came from is
     * resolved before a rung sees it, because no rung has a use for the question separately.
     */
    val budget: RetryBudget,
    /**
     * Which retry this one would be, numbered from 1.
     *
     * Media3 counts errors rather than retries and starts at 1 for the first error, which is the
     * error the first retry answers — so the two numbers coincide and this is Media3's `errorCount`
     * unchanged. Named for what it is used for rather than for where it came from.
     */
    val retry: Int,
    /** Media3's own account of the load, for a rung that must name a location or a track back. */
    val info: LoadErrorHandlingPolicy.LoadErrorInfo,
)

/** What a rung decided to do about a [FailedLoad]. */
internal sealed interface RungOutcome {

    /** This rung took it on: ask for the same load again in [delayMs]. */
    data class RetryAfter(val delayMs: Long) : RungOutcome

    /** This rung took it on by narrowing what Media3 may load next. */
    data class FallBackTo(val selection: LoadErrorHandlingPolicy.FallbackSelection) : RungOutcome

    /** This rung has nothing for it; the one above is offered it next. */
    data object Escalate : RungOutcome
}

/** What one rung of ADR-0011 rule 7's ladder does about a failure. */
internal fun interface LadderRung {

    /** What this rung does about [load], or [RungOutcome.Escalate] to hand it to the rung above. */
    fun attempt(load: FailedLoad): RungOutcome

    companion object {
        /** A rung that is not built yet: every failure passes straight through it. */
        val ESCALATING: LadderRung = LadderRung { RungOutcome.Escalate }
    }
}

/**
 * ADR-0011 rule 7's ladder, as far as it can be climbed from inside a failed load.
 *
 * A failure is offered to each rung from the bottom, and stops at the first that takes it; a rung is
 * reached only when the one below it has declined. The order is [FallbackRung]'s declaration order
 * and is not a profile's to vary — no content wants its variant excluded before its host is tried —
 * and the ceiling is the failure's own ([FailureClass.rungCeiling], rule 1), so a class that says
 * rung 1 is pointless does not spend a budget there on its way up.
 *
 * ## Which rungs are here, and which are not
 *
 * Rungs 1 to 3 — retry the same URL, the next host, the failing variant excluded — are the answers
 * Media3 asks a `LoadErrorHandlingPolicy` for, which is why they are reachable from here at all.
 * Rungs 4 to 6 are not: the next source is a re-adoption and a recreated decoder is a re-prepare,
 * both operations on the player's own state that cannot be performed from a loading thread with a
 * load in hand, and the typed error is what the consumer is handed once nothing below it worked
 * (rule 7 assigns them to rules 5 and 10). [RungOutcome.Escalate] out of the top of this object is
 * therefore where they begin, and today it is where the failure surfaces exactly as it would with
 * no resilience attached — which is what #182 and #183 replace.
 *
 * ## The trap
 *
 * **Media3 asks the questions in the opposite order to the ladder.** A chunk source asks
 * `getFallbackSelectionFor` — rungs 2 and 3 — and only asks `getRetryDelayMsFor` if that answered
 * null. So a rung above 1 that answered the first question while rung 1 still had budget would have
 * skipped rung 1 entirely, silently, and rule 7 would be broken by the order Media3 happens to ask
 * in rather than by anything written here. [climb] is the single place that order is re-imposed:
 * both of `RetryingLoadErrors`' answers come from one climb of this ladder, so a rung above is only
 * ever offered a failure the rungs below have already declined.
 */
internal class FallbackLadder(private val rungs: Map<FallbackRung, LadderRung>) {

    /** The outcome of offering [load] to each rung in turn, up to its class's ceiling. */
    fun climb(load: FailedLoad): RungOutcome {
        for (rung in FallbackRung.entries) {
            if (rung > load.failureClass.rungCeiling) break
            val outcome = rungs[rung]?.attempt(load) ?: RungOutcome.Escalate
            if (outcome != RungOutcome.Escalate) return outcome
        }
        return RungOutcome.Escalate
    }

    companion object {

        /**
         * The ladder as it stands: rung 1 built, rungs 2 and 3 present and passing everything
         * through until the tickets that own them land.
         *
         * Named here rather than assembled at each call site so that a later rung is one entry in
         * this map and no edit to `Resilience` or to `RetryingLoadErrors` — which is the whole
         * point of the rungs being separate objects while only one of them does anything.
         *
         * [random] is where every backoff draws its jitter; one source per player, so two players
         * retrying the same edge do not draw the same sequence.
         */
        fun standard(random: Random): FallbackLadder = FallbackLadder(
            mapOf(
                FallbackRung.RETRY_SAME_URL to RetrySameUrl(random),
                // Another CDN host or DASH `BaseURL`, answered as a `FALLBACK_TYPE_LOCATION`
                // selection. #180.
                FallbackRung.NEXT_HOST to LadderRung.ESCALATING,
                // The failing variant excluded, answered as a `FALLBACK_TYPE_TRACK` selection and
                // still under the ceiling the decision in force allows (rule 8). #181.
                FallbackRung.EXCLUDE_VARIANT to LadderRung.ESCALATING,
            ),
        )
    }
}

/**
 * Rung 1: ask for the same bytes again, after a jittered backoff, while the budget lasts.
 *
 * Two refusals, in this order, and both are ADR-0011's rather than this object's:
 *
 * 1. **The class.** [FailureClass.retryable] is the whole of what a class says about this rung
 *    (rule 1): bytes that arrived and are not what they were described as parse the same way the
 *    second time, and a budget spent on them is a budget the rungs above do not get.
 * 2. **The budget.** [RetryBudget.maxRetries] from the decision in force at this consultation, per
 *    [RetryBudgetKind] — so a manifest that has spent its asks has spent *its* asks, and the
 *    segments of the same session still have all of theirs (rule 11, `PRD.md` §3.3).
 *
 * Nothing here re-seeks or re-prepares, which is rule 9's half of this rung: a retry Media3
 * performs inside a load leaves the position untouched by construction, and the rule is that
 * SuperPlayer adds nothing that disturbs it.
 */
internal class RetrySameUrl(private val random: Random) : LadderRung {

    override fun attempt(load: FailedLoad): RungOutcome {
        if (!load.failureClass.retryable) return RungOutcome.Escalate
        if (load.retry > load.budget.maxRetries) return RungOutcome.Escalate
        return RungOutcome.RetryAfter(Backoff.delayMsFor(load.budget, load.retry, random))
    }
}
