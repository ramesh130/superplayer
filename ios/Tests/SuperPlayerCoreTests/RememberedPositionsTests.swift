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

import XCTest

// `@testable` for `RememberedPositions` alone, whose order rule — a read is a use — is the one thing
// about the bound the playback test through the harness cannot isolate: there every id is read
// exactly once, as it is set.
@testable import SuperPlayerCore

/// The memory behind `.resumeFromLastKnown`, as the Kotlin access-ordered map behaves: bounded, and
/// the entry dropped is the least recently *used*, a read counting as a use.
final class RememberedPositionsTests: XCTestCase {

    func testTheBoundDropsTheLeastRecentlyWrittenWhenNothingIsRead() {
        var memory = RememberedPositions(bound: 2)

        memory.remember(1, for: "a")
        memory.remember(2, for: "b")
        memory.remember(3, for: "c")

        XCTAssertEqual(memory.count, 2)
        XCTAssertNil(memory.position(for: "a"))
        XCTAssertEqual(memory.position(for: "b"), 2)
        XCTAssertEqual(memory.position(for: "c"), 3)
    }

    /// A read moves an id to the most recent end, so it outlives one written after it was.
    func testAReadCountsAsAUse() {
        var memory = RememberedPositions(bound: 2)
        memory.remember(1, for: "a")
        memory.remember(2, for: "b")

        _ = memory.position(for: "a")
        memory.remember(3, for: "c")

        XCTAssertEqual(memory.position(for: "a"), 1)
        XCTAssertNil(memory.position(for: "b"))
    }

    /// Writing an id again replaces its position rather than adding a second entry.
    func testRewritingAnIdKeepsOneEntry() {
        var memory = RememberedPositions(bound: 2)
        memory.remember(1, for: "a")
        memory.remember(2, for: "b")

        memory.remember(5, for: "a")

        XCTAssertEqual(memory.count, 2)
        XCTAssertEqual(memory.position(for: "a"), 5)
        XCTAssertEqual(memory.position(for: "b"), 2)
    }

    func testThePlayersBoundIsTheKotlinOne() {
        XCTAssertEqual(SuperPlayer.maxRememberedPositions, 128)
    }
}
