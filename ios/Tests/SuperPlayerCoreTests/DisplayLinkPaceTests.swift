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

import QuartzCore
import XCTest

// `@testable` for `DisplayLinkPace`, internal because what it answers is a `CADisplayLink`'s range
// and no consumer asks for one; it is a pure value, so it is read here directly.
@testable import SuperPlayerCore

/// The frame output's display-link pacing (#491): twice the content's frame rate, taken from the
/// shortest gap between the presentation times handed over, so drift between the display's clock
/// and the item's moves which tick sees a frame and never whether one does. `FramePath.swift`'s
/// `DisplayLinkPace` argues the rule; `FrameOutputPacingTests` reads it off a playing player, and
/// the loss it answers is counted by `devicelab/lab run frames --platform ios`.
final class DisplayLinkPaceTests: XCTestCase {

    func testSixtyFramesASecondAsksForOneHundredAndTwenty() throws {
        var pace = DisplayLinkPace()
        XCTAssertNil(pace.rangeAfterFrame(at: 10), "one frame says nothing about a rate")
        let range = try XCTUnwrap(pace.rangeAfterFrame(at: 10 + 1.0 / 60))
        XCTAssertEqual(range.preferred ?? 0, 120, accuracy: 0.01)
        XCTAssertEqual(range.maximum, 120, accuracy: 0.01)
        XCTAssertEqual(range.minimum, 60, accuracy: 0.01, "never slower than the content")
    }

    /// The control that keeps the rule from being "ask for the display's most": a thirty-frame
    /// stream asks for sixty, which is what an iPhone's link runs at anyway.
    func testThirtyFramesASecondAsksForSixty() throws {
        var pace = DisplayLinkPace()
        _ = pace.rangeAfterFrame(at: 0)
        let range = try XCTUnwrap(pace.rangeAfterFrame(at: 1.0 / 30))
        XCTAssertEqual(range.preferred ?? 0, 60, accuracy: 0.01)
        XCTAssertEqual(range.minimum, 30, accuracy: 0.01)
    }

    /// A lost frame only lengthens a gap, so the rate is the shortest gap's, and the range is asked
    /// again only when that gap shortens — a variant switch to a faster rate.
    func testALongerGapChangesNothingAndAShorterOneRaisesTheRate() throws {
        var pace = DisplayLinkPace()
        _ = pace.rangeAfterFrame(at: 0)
        XCTAssertNotNil(pace.rangeAfterFrame(at: 1.0 / 30))
        XCTAssertNil(
            pace.rangeAfterFrame(at: 3.0 / 30), "a gap of two frames is a lost frame, not a rate")
        XCTAssertNil(pace.rangeAfterFrame(at: 4.0 / 30), "the same interval again asks nothing new")
        let faster = try XCTUnwrap(pace.rangeAfterFrame(at: 4.0 / 30 + 1.0 / 60))
        XCTAssertEqual(faster.preferred ?? 0, 120, accuracy: 0.01)
    }

    /// A seek back, or the same frame handed twice, is no interval at all.
    func testASeekBackOrARepeatSaysNothing() throws {
        var pace = DisplayLinkPace()
        _ = pace.rangeAfterFrame(at: 5)
        XCTAssertNil(pace.rangeAfterFrame(at: 5))
        XCTAssertNil(pace.rangeAfterFrame(at: 1))
        let range = try XCTUnwrap(
            pace.rangeAfterFrame(at: 1 + 1.0 / 60), "measured from the frame after it")
        XCTAssertEqual(range.preferred ?? 0, 120, accuracy: 0.01)
    }
}
