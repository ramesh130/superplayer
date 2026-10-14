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

import com.google.common.truth.Truth.assertThat
import com.superplayer.core.RetryBudget
import org.junit.Test
import kotlin.random.Random

/**
 * The backoff, as a distribution.
 *
 * `PRD.md` §3.3 asks for growth *and* jitter, and the second of those is not a property of any one
 * delay — a single draw of a jittered value is indistinguishable from a constant. So the growth is
 * asserted on the window, which is deterministic, and the jitter on the spread of many draws inside
 * one, which is where the property actually lives. A plain JVM test, because [Backoff] is arithmetic
 * and touches no Android type.
 */
class RetryBackoffTest {

    @Test
    fun theWindowDoublesWithEachRetryAndStopsAtTheBudgetSCeiling() {
        val budget = RetryBudget(maxRetries = 6, initialBackoffMs = 1_000, maxBackoffMs = 5_000)

        assertThat((1..6).map { Backoff.windowMsFor(budget, it) })
            .containsExactly(1_000L, 2_000L, 4_000L, 5_000L, 5_000L, 5_000L)
            .inOrder()
    }

    @Test
    fun aWindowNeverOverflowsHoweverManyRetriesAreCountedInto() {
        // Media3 keeps incrementing its error count past whatever this object stops retrying at, and
        // the count reaches this function before the budget is checked. A shift that ran off the end
        // of a `Long` would come back negative and be read as "retry immediately" — the opposite of
        // a backoff — which is why the guard is asserted rather than trusted.
        val budget = RetryBudget(maxRetries = 3, initialBackoffMs = 1_000, maxBackoffMs = 5_000)

        listOf(63, 64, 65, 1_000, Int.MAX_VALUE).forEach { retry ->
            assertThat(Backoff.windowMsFor(budget, retry)).isEqualTo(5_000L)
        }
    }

    @Test
    fun everyDelayFallsInTheUpperHalfOfItsWindowAndTheDrawsAreSpreadAcrossIt() {
        val budget = RetryBudget(maxRetries = 4, initialBackoffMs = 1_000, maxBackoffMs = 8_000)
        // Seeded, so a suite that runs this a thousand times over its life fails on a real defect
        // rather than on a tail of the distribution. What is asserted below holds for every seed;
        // the seed only makes the failure reproducible.
        val random = Random(SEED)

        (1..4).forEach { retry ->
            val window = Backoff.windowMsFor(budget, retry)
            val draws = List(DRAWS) { Backoff.delayMsFor(budget, retry, random) }

            // Equal jitter's bound: never above the window, and never below half of it — the floor
            // is what stops a draw from asking an edge that failed a millisecond ago.
            assertThat(draws.min()).isAtLeast(window / 2)
            assertThat(draws.max()).isAtMost(window)
            // And the jitter is real rather than nominal: the draws cover most of the window they
            // are allowed. A constant delay — the failure this test exists to catch — would have a
            // spread of zero, and a herd of clients returning to a recovering edge together.
            val spread = draws.max() - draws.min()
            assertThat(spread).isAtLeast(window / 2 * 8 / 10)
        }
    }

    @Test
    fun theDelaysOfLaterRetriesAreLongerThanThoseOfEarlierOnes() {
        val budget = RetryBudget(maxRetries = 4, initialBackoffMs = 1_000, maxBackoffMs = 8_000)
        val random = Random(SEED)

        // Compared as distributions rather than as draws, which is the only comparison jitter
        // leaves available: the *floor* of one retry's window is the ceiling of the one before it,
        // so every draw at retry n + 1 is at least every draw at retry n while the window is still
        // doubling. Asserted on the extremes of many draws rather than on one pair.
        val byRetry = (1..3).map { retry -> List(DRAWS) { Backoff.delayMsFor(budget, retry, random) } }

        byRetry.zipWithNext { earlier, later ->
            assertThat(later.min()).isAtLeast(earlier.max())
        }
    }

    @Test
    fun aBudgetThatAsksForNoBackoffGetsNone() {
        // Zero is a consumer saying "ask again at once", which is a thing a policy may decide; it is
        // not a case to guard against, and half of zero is still zero.
        val budget = RetryBudget(maxRetries = 3, initialBackoffMs = 0, maxBackoffMs = 0)

        assertThat((1..3).map { Backoff.delayMsFor(budget, it, Random(SEED)) }).containsExactly(0L, 0L, 0L)
    }

    private companion object {
        const val SEED = 20_260_916L

        /** Enough draws that a window's coverage is a property of the algorithm rather than luck. */
        const val DRAWS = 200
    }
}
