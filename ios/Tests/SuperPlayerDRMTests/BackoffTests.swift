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
import XCTest

// `@testable` for `Backoff`, internal as the Kotlin object is: no public reading shows a wait's
// spread, and a playback test that timed one would assert a duration (ADR-0021 rule 4).
@testable import SuperPlayerDRM

/// ``Backoff`` as the Kotlin `RetryBackoffTest` holds its twin: the growth and the ceiling stated
/// without a distribution, and the jitter asserted as a *spread* — every draw inside
/// `[base / 2, base]`, both ends reachable — rather than as one delay, which is the property ADR-0011
/// rule 12 makes correctness.
final class BackoffTests: XCTestCase {

    private let budget = RetryBudget(maxRetries: 10, initialBackoffMs: 1_000, maxBackoffMs: 5_000)

    func testTheWindowDoublesFromTheInitialWaitAndStopsAtTheCeiling() {
        XCTAssertEqual(
            (1...5).map { Backoff.windowMs(for: budget, retry: $0) },
            [1_000, 2_000, 4_000, 5_000, 5_000])
    }

    func testAnAttemptFarPastTheWidthOfTheNumberIsTheCeilingAndNotAnOverflow() {
        XCTAssertEqual(Backoff.windowMs(for: budget, retry: 64), 5_000)
        XCTAssertEqual(Backoff.windowMs(for: budget, retry: 1_000), 5_000)
        let huge = RetryBudget(
            maxRetries: 1, initialBackoffMs: Int64.max / 4, maxBackoffMs: Int64.max / 2)
        XCTAssertEqual(Backoff.windowMs(for: huge, retry: 4), Int64.max / 2)
    }

    func testAZeroInitialWaitWaitsZero() {
        let none = RetryBudget(maxRetries: 3, initialBackoffMs: 0, maxBackoffMs: 0)
        var random = SystemRandomNumberGenerator()
        XCTAssertEqual(Backoff.delayMs(for: none, retry: 3, using: &random), 0)
    }

    /// Equal jitter: never below half the window, never above it, and spread across it — the
    /// control that the draw is not the window itself.
    func testEveryDrawIsInTheUpperHalfOfTheWindowAndTheDrawsSpread() {
        var random = SystemRandomNumberGenerator()
        let draws = (0..<2_000).map { _ in Backoff.delayMs(for: budget, retry: 2, using: &random) }

        XCTAssertTrue(
            draws.allSatisfy { (1_000...2_000).contains($0) }, "\(draws.min()!)…\(draws.max()!)")
        XCTAssertLessThan(draws.min()!, 1_100)
        XCTAssertGreaterThan(draws.max()!, 1_900)
    }
}
