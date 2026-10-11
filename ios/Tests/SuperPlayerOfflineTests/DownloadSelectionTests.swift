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

// `@testable` for `DownloadSelection`, internal because it is the rule and not an API: the
// playback tests count what it chose at the origin, and these state the edges no stream here has —
// a height, a ceiling nothing fits, a region subtag.
@testable import SuperPlayerOffline

final class DownloadSelectionTests: XCTestCase {

    private typealias Variant = DownloadSelection.Variant

    private let ladder = [
        Variant(peakBitRateBps: 800_000, heightPx: 360),
        Variant(peakBitRateBps: 2_500_000, heightPx: 720),
        Variant(peakBitRateBps: 6_000_000, heightPx: 1_080),
    ]

    func testTheHighestUnderEachCeilingIsChosen() {
        XCTAssertEqual(DownloadSelection.variant(among: ladder, under: .unlimited), ladder[2])
        XCTAssertEqual(
            DownloadSelection.variant(among: ladder, under: .init(maxVideoHeightPx: 720)), ladder[1]
        )
        XCTAssertEqual(
            DownloadSelection.variant(
                among: ladder, under: .init(maxVideoBitrateBps: 3_000_000, maxVideoHeightPx: 360)),
            ladder[0])
    }

    /// Media3's `exceedVideoConstraintsIfNecessary`: a ceiling nothing fits downloads the least that
    /// breaks it rather than nothing.
    func testACeilingNothingFitsTakesTheLowest() {
        XCTAssertEqual(
            DownloadSelection.variant(among: ladder, under: .init(maxVideoBitrateBps: 100_000)),
            ladder[0])
        XCTAssertNil(DownloadSelection.variant(among: [], under: .unlimited))
    }

    /// Unknown refuses nothing: an audio-only variant declares no height, and a height ceiling does
    /// not cap it.
    func testAFigureTheVariantDoesNotDeclareRefusesNothing() {
        let audio = [
            Variant(peakBitRateBps: 128_000, heightPx: nil),
            Variant(peakBitRateBps: 256_000, heightPx: nil),
        ]
        XCTAssertEqual(
            DownloadSelection.variant(among: audio, under: .init(maxVideoHeightPx: 360)), audio[1])
    }

    func testDeclaredLanguagesAreMatchedBySubtagInDeclaredOrderAndAnUncarriedOneIsSkipped() {
        XCTAssertEqual(
            DownloadSelection.carried(["fr_FR", "xx", "EN"], offered: ["en-US", "fr", "de"]),
            ["fr", "en-US"])
        XCTAssertEqual(
            DownloadSelection.carried(["e"], offered: ["en"]), [], "a subtag, not a prefix")
        XCTAssertEqual(DownloadSelection.carried(["en", "en-GB"], offered: ["en"]), ["en"])
    }
}
