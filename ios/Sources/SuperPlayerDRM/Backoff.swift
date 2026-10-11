// Copyright 2026 The SuperPlayer Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import SuperPlayerCore

/// How long to wait before asking for a failed licence again: exponential growth, jittered — the
/// Kotlin `superplayer-resilience` `Backoff`, ported line for line, with its reasons.
///
/// **Here rather than in `SuperPlayerResilience`**, where the Kotlin object lives, because on Apple
/// platforms the licence exchange is the one load this library retries itself — `AVPlayer` retries
/// playlists and segments inside the engine, and nothing configures it (`docs/ios-plan.md` §3.5) — so
/// this product is its only caller, and ADR-0012 keeps the two optional products from depending on
/// each other.
///
/// Pure and stateless — a budget, an attempt number and a source of randomness in, a number of
/// milliseconds out — so the property that matters can be asserted on the distribution rather than
/// on one draw.
///
/// ## Why the growth
///
/// A retry that follows immediately asks the same overloaded server the same question while it is
/// still overloaded. Doubling the wait each time is the standard answer: the first retry covers a
/// momentary blip and each one after it concedes that whatever is wrong is taking longer than that,
/// up to the budget's ceiling, past which waiting longer only delays the typed error.
///
/// ## Why the jitter, and why this jitter
///
/// **Jitter is the whole reason this type exists.** A licence server's blip is seen by every client
/// starting playback through it at the same instant; if they all back off by the same computed delay
/// they all return at the same instant too, and the retry storm is a denial of service the clients
/// inflicted on a service that had already recovered. That is why ADR-0011 rule 12 makes jitter
/// correctness rather than policy: no ``PlaybackPolicy`` and no profile may switch it off.
///
/// The draw is **equal jitter**: half the window is waited unconditionally and the other half is
/// drawn uniformly, so a delay is always in `[base / 2, base]`.
///
/// ref: AWS Architecture Blog, "Exponential Backoff And Jitter", which names and measures *full*
/// jitter — uniform over `[0, base]` — and *equal* jitter, uniform over `[base / 2, base]`.
///
/// Equal rather than full for the Kotlin reason: full jitter can draw a delay of nearly zero, which
/// asks a server that failed a millisecond ago — a wasted request out of a budget of three to five.
/// Equal jitter keeps a floor that grows with the attempt while still spreading the arrivals across
/// a window as wide as the base.
enum Backoff {

    /// How long to wait before `retry` — the first retry being 1 — of a request whose budget this
    /// is. The window doubles per retry from ``RetryBudget/initialBackoffMs`` and is clamped at
    /// ``RetryBudget/maxBackoffMs``; `random` draws within its upper half. A budget whose initial
    /// wait is zero waits zero, which is a consumer asking for no backoff rather than a case to
    /// special-case.
    static func delayMs(
        for budget: RetryBudget, retry: Int, using random: inout some RandomNumberGenerator
    ) -> Int64 {
        let window = windowMs(for: budget, retry: retry)
        guard window > 0 else { return 0 }
        let floor = window / 2
        // `floor...window`, inclusive of both ends: the unjittered wait is a delay this may draw,
        // not one it is always under.
        return Int64.random(in: floor...window, using: &random)
    }

    /// The unjittered window ``delayMs(for:retry:using:)`` draws inside — the doubling, before the
    /// randomness. Separate so a test states the growth and the ceiling without a distribution, and
    /// so the overflow guard lives in one place: shifting past the width of an `Int64` would come
    /// back negative, and anything past the cap's own bit is the cap.
    static func windowMs(for budget: RetryBudget, retry: Int) -> Int64 {
        precondition(retry >= 1, "A retry is numbered from 1, was \(retry)")
        let doublings = retry - 1
        guard budget.initialBackoffMs > 0 else { return 0 }
        guard doublings < Int64.bitWidth else { return budget.maxBackoffMs }
        let grown = budget.initialBackoffMs << Int64(doublings)
        // A shift that overflowed is negative or has wrapped below where it started; either way the
        // honest answer is the ceiling the budget already names.
        guard grown > 0, grown >= budget.initialBackoffMs else { return budget.maxBackoffMs }
        return min(grown, budget.maxBackoffMs)
    }
}
