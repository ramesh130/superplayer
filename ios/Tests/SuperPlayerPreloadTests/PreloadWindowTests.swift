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

// `@testable` for the two pure halves of the coordinator — the scroll order and the memory
// guard's admission — which are internal because a consumer reaches them only through what is
// fetched, and `PreloadCoordinatorPlaybackTests` counts that.
@testable import SuperPlayerPreload

/// Which rows a window holds and how many memory admits, as arithmetic: the Kotlin coordinator's
/// direction and heading rules (`reversingTheScrollReordersWhichFirstSegmentIsFetchedNext`,
/// `aFlingFetchesWhereItWillLandRatherThanTheRowsItPasses`) and its guard's admission
/// (`aSmallHeapLowersHowManyRowsArePrefetched`), which a real-time harness would only make slower.
@MainActor
final class PreloadWindowTests: XCTestCase {

    private let twoAheadOneBehind = PreloadPolicy(
        itemsAhead: 2, itemsBehind: 1, depth: .decoderWarmed(durationMs: 1_000))

    func testAFeedStartsMovingForwardAheadFirstThenBehind() {
        var window = ScrollWindow()
        window.move(to: 5, velocity: 0)

        XCTAssertEqual(window.priorityOrder(count: 20, policy: twoAheadOneBehind), [6, 7, 4])
    }

    func testReversingTheScrollReversesTheOrder() {
        var window = ScrollWindow()
        window.move(to: 5, velocity: 0)
        window.move(to: 4, velocity: 0)

        XCTAssertEqual(window.priorityOrder(count: 20, policy: twoAheadOneBehind), [3, 2, 5])
    }

    /// Eight rows a second projects four rows past where the feed is, so the rows it passes on the
    /// way are not fetched.
    func testAFlingFetchesWhereItWillLand() {
        var window = ScrollWindow()
        window.move(to: 5, velocity: 8)

        XCTAssertEqual(window.priorityOrder(count: 20, policy: twoAheadOneBehind), [9, 10, 4])
    }

    func testTheFeedsEndsBoundTheWindow() {
        var window = ScrollWindow()
        window.move(to: 0, velocity: 0)
        XCTAssertEqual(window.priorityOrder(count: 2, policy: twoAheadOneBehind), [1])
        XCTAssertEqual(window.priorityOrder(count: 2, policy: .none), [])
    }

    func testPlentyOfMemoryAdmitsTheWholeWindow() {
        let guardian = MemoryGuard(availableMemory: { 4 * 1024 * 1024 * 1024 })

        XCTAssertEqual(guardian.admit([6, 7, 4], policy: twoAheadOneBehind, held: 2), [6, 7, 4])
    }

    /// A quarter of what is left admits as many rows as their cost fits, nearest first: here the
    /// two held rows at a second of media each, and not the third.
    func testLittleMemoryAdmitsFewerRowsNearestFirst() {
        let heldRow = MemoryGuard.bytes(for: .decoderWarmed(durationMs: 1_000), held: true)
        let available = (2 * heldRow + MemoryGuard.preparedItemBytes / 2) * 4
        let guardian = MemoryGuard(availableMemory: { available })

        XCTAssertEqual(guardian.admit([6, 7, 4], policy: twoAheadOneBehind, held: 2), [6, 7])
    }

    /// A reading of zero — not an app, or over the limit — admits the next row only.
    func testAnUnreadableAllowanceAdmitsTheNextRow() {
        let guardian = MemoryGuard(availableMemory: { 0 })

        XCTAssertEqual(guardian.admit([6, 7, 4], policy: twoAheadOneBehind, held: 2), [6])
    }

    /// An unheld row loads its playlists alone, so it costs its asset whatever its depth.
    func testOnlyAHeldRowIsCostedAtItsMedia() {
        let depth = PreloadDepth.loaded(durationMs: 2_000)
        XCTAssertEqual(MemoryGuard.bytes(for: depth, held: false), MemoryGuard.preparedItemBytes)
        XCTAssertEqual(
            MemoryGuard.bytes(for: depth, held: true),
            MemoryGuard.preparedItemBytes + 2 * MemoryGuard.referenceBytesPerSecond)
    }
}
