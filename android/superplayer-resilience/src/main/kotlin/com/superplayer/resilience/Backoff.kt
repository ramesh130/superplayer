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

import com.superplayer.core.RetryBudget
import kotlin.random.Random

/**
 * How long to wait before asking for a failed load again: exponential growth, jittered.
 *
 * Pure and stateless — a budget, an attempt number and a source of randomness in, a number of
 * milliseconds out — so the property that matters can be asserted on the distribution rather than
 * on one draw.
 *
 * ## Why the growth
 *
 * A retry that follows immediately asks the same overloaded edge the same question while it is
 * still overloaded. Doubling the wait each time is the standard answer: the first retry covers a
 * momentary blip and each one after it concedes that whatever is wrong is taking longer than that,
 * up to the budget's ceiling, past which waiting longer only delays reaching a rung that might
 * actually work.
 *
 * ## Why the jitter, and why this jitter
 *
 * **Jitter is the whole reason this file exists.** A CDN edge blip is seen by every client watching
 * through that edge at the same instant; if they all back off by the same computed delay they all
 * return at the same instant too, and the retry storm is a denial of service the clients inflicted
 * on a service that had already recovered. Spreading the return times over a window is what turns a
 * synchronised herd into a smooth arrival rate. That is why ADR-0011 rule 12 makes jitter
 * correctness rather than policy: it has one right answer for every profile, so no
 * [com.superplayer.core.PlaybackPolicy] and no profile table may switch it off.
 *
 * The draw is **equal jitter**: half the window is waited unconditionally and the other half is
 * drawn uniformly, so a delay is always in `[base / 2, base]` (// ref: AWS Architecture Blog,
 * "Exponential Backoff And Jitter", which names and measures *full* jitter — uniform over
 * `[0, base]` — and *equal* jitter, uniform over `[base / 2, base]`).
 *
 * Equal rather than full because of what the two trade. Full jitter spreads hardest and is the
 * right choice for a fleet of writers contending for one resource, but it can draw a delay of
 * nearly zero, which for a player means asking an edge that failed a millisecond ago — a wasted
 * request, and a wasted attempt out of a budget that is small (three to five, not hundreds). Equal
 * jitter keeps a guaranteed floor that grows with the attempt while still spreading the arrivals
 * across a window as wide as the base, which is spread enough for a herd whose members each make a
 * handful of attempts.
 */
internal object Backoff {

    /**
     * How long to wait before [retry] — the first retry being 1 — of a load whose [budget] this is.
     *
     * The window doubles per retry from [RetryBudget.initialBackoffMs] and is clamped at
     * [RetryBudget.maxBackoffMs]; [random] draws within its upper half. A budget whose initial wait
     * is zero waits zero, which is a consumer asking for no backoff rather than a case to special
     * case.
     */
    fun delayMsFor(budget: RetryBudget, retry: Int, random: Random): Long {
        require(retry >= 1) { "A retry is numbered from 1, was $retry" }
        val window = windowMsFor(budget, retry)
        if (window == 0L) return 0L
        val floor = window / 2
        // `floor..window` inclusive of both ends: the unjittered wait is a delay this may draw, not
        // one it is always under. `nextLong` is exclusive at the top, hence the + 1.
        return floor + random.nextLong(window - floor + 1)
    }

    /**
     * The unjittered window [delayMsFor] draws inside — the doubling, before the randomness.
     *
     * Separate so that a test can state the growth and the ceiling without a distribution, and so
     * that the overflow guard lives in one place: a budget with a large initial wait and an attempt
     * number Media3 kept incrementing would otherwise shift past the width of a `Long` and come
     * back negative. Shifting by the position of the cap's highest bit is past the cap already, so
     * anything beyond that is the cap.
     */
    fun windowMsFor(budget: RetryBudget, retry: Int): Long {
        require(retry >= 1) { "A retry is numbered from 1, was $retry" }
        val doublings = retry - 1
        if (budget.initialBackoffMs == 0L) return 0L
        if (doublings >= Long.SIZE_BITS) return budget.maxBackoffMs
        val grown = budget.initialBackoffMs shl doublings
        // A shift that overflowed is negative or has wrapped below where it started; either way the
        // honest answer is the ceiling the budget already names.
        if (grown <= 0L || grown < budget.initialBackoffMs) return budget.maxBackoffMs
        return minOf(grown, budget.maxBackoffMs)
    }
}
