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

import Foundation
import SuperPlayerCore
import XCTest

/// `MediaRequest` as a value: it round-trips through `Codable` whole, is equal by content, and a
/// stored value that is not a request — the three things the Kotlin builder's `require`s refuse —
/// is refused on decoding rather than trapped on, because it is input rather than code.
final class MediaRequestTests: XCTestCase {

    private let full = MediaRequest(
        contentId: "catalog:episode:1138",
        sources: [
            URL(string: "https://cdn-a.example.com/1138/master.m3u8")!,
            URL(string: "https://cdn-b.example.com/1138/master.m3u8")!,
        ],
        startPosition: .at(positionMs: 4_000),
        title: "The Long Way Round",
        subtitle: "Season 2",
        artworkURL: URL(string: "https://img.example.com/1138.jpg")!)

    func testEveryFieldAndEveryStartPositionSurvivesEncodingAndDecoding() throws {
        for start in [
            MediaRequest.StartPosition.beginning, .at(positionMs: 4_000), .resumeFromLastKnown,
        ] {
            let request = MediaRequest(
                contentId: full.contentId, sources: full.sources, startPosition: start,
                title: full.title, subtitle: full.subtitle, artworkURL: full.artworkURL)

            let decoded = try JSONDecoder().decode(
                MediaRequest.self, from: JSONEncoder().encode(request))

            XCTAssertEqual(decoded, request)
            XCTAssertEqual(decoded.sources, request.sources, "the order of the sources")
        }
    }

    func testTheDisplayFieldsAreOptionalAndTheStartDefaultsToTheBeginning() throws {
        let bare = MediaRequest(contentId: "a", sources: full.sources)

        XCTAssertEqual(bare.startPosition, .beginning)
        XCTAssertNil(bare.title)
        XCTAssertEqual(
            try JSONDecoder().decode(MediaRequest.self, from: JSONEncoder().encode(bare)), bare)
    }

    /// Equality is by content, the identity included: two requests for the same URL are not equal
    /// when they name different content, which is the point of not keying on the URL.
    func testRequestsForOneURLUnderTwoIdentitiesAreNotEqual() {
        let other = MediaRequest(contentId: "catalog:episode:1139", sources: full.sources)

        XCTAssertNotEqual(MediaRequest(contentId: full.contentId, sources: full.sources), other)
        XCTAssertEqual(Set([full, full, other]).count, 2)
    }

    func testAStoredValueWithABlankIdentityNoSourceOrANegativeStartIsRefused() throws {
        let valid =
            try JSONSerialization.jsonObject(with: JSONEncoder().encode(full))
            as! [String: Any]

        let refusals: [String: [String: Any]] = [
            "a blank content id": valid.merging(["contentId": "  "]) { $1 },
            "no source": valid.merging(["sources": [String]()]) { $1 },
            "a negative start": valid.merging(["startPosition": ["at": ["positionMs": -1]]]) { $1 },
        ]
        for (problem, stored) in refusals {
            let data = try JSONSerialization.data(withJSONObject: stored)
            XCTAssertThrowsError(
                try JSONDecoder().decode(MediaRequest.self, from: data), "\(problem) was accepted"
            ) { error in
                guard case DecodingError.dataCorrupted = error else {
                    return XCTFail("\(problem): \(error)")
                }
            }
        }
        // The control: the same dictionary unaltered decodes, so the refusals above are the edits'.
        XCTAssertEqual(
            try JSONDecoder().decode(
                MediaRequest.self, from: JSONSerialization.data(withJSONObject: valid)),
            full)
    }
}
