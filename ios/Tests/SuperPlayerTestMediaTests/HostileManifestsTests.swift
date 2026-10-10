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

import SuperPlayerTestMedia
import XCTest

/// The shape of the Swift corpus — the structural half of the Kotlin `HostileManifestCorpusTest`,
/// which lives beside the recording there and here lives beside the generator, because nothing in
/// it plays anything. What a player does with each entry is `SuperPlayerTestkit`'s
/// `HostileManifestCorpusTests`.
final class HostileManifestsTests: XCTestCase {

    /// Any instant will do for a test about shape, since nothing here checks a token; this one is
    /// the fixtures', so an expiry these tests name reads the same as the committed one.
    private let now: Int64 = 1_800_000_000

    func testAllIsTheSevereHalfOfTheGradedCorpus() {
        let severe = HostileManifests.graded(nowEpochSeconds: now).filter { $0.severity == .severe }

        XCTAssertEqual(HostileManifests.all(nowEpochSeconds: now).map(\.id), severe.map(\.id))
        XCTAssertEqual(
            Set(HostileManifests.all(nowEpochSeconds: now).map(\.severity)), [.severe])
    }

    func testAPathologyWithAMagnitudeIsGradedAtEveryLevelAndABinaryOneAtOne() {
        let byId = Dictionary(grouping: HostileManifests.graded(nowEpochSeconds: now), by: \.id)

        for (id, levels) in byId {
            if levels.contains(where: { $0.magnitude == nil }) {
                XCTAssertEqual(levels.map(\.severity), [.severe], id)
                XCTAssertNil(levels.first?.magnitude, id)
            } else {
                XCTAssertEqual(levels.map(\.severity), HostileStream.Severity.allCases, id)
                XCTAssertEqual(Set(levels.compactMap(\.magnitude)).count, levels.count, id)
            }
        }
        // Pinned by id, as the Kotlin test pins them: grading a binary pathology, or collapsing a
        // graded one to a single level, is a decision a reviewer should see.
        XCTAssertEqual(
            Set(byId.filter { $0.value.count == 1 }.keys),
            [
                "hls-missing-codecs", "hls-audio-group-codec-mismatch", "hls-dangling-audio-group",
                "hls-discontinuity-without-timeline", "hls-cached-live-playlist",
                "hls-token-scoped-to-manifest", "hls-cors-refuses-credentials",
            ])
    }

    func testTheCorpusLabelsWhatIsMalformedRatherThanMerelyHostile() {
        let malformed = HostileManifests.all(nowEpochSeconds: now).filter {
            $0.validity == .malformed
        }

        XCTAssertEqual(malformed.map(\.id), ["hls-dangling-audio-group"])
    }

    func testEveryGradedEntryIsServedFromItsOwnDirectory() {
        // What lets one origin serve the whole graded corpus: no two entries, not even two
        // severities of one pathology, answer to the same path.
        let paths = HostileManifests.graded(nowEpochSeconds: now).flatMap(\.files.keys)

        XCTAssertEqual(Set(paths).count, paths.count)
    }

    func testEveryEntryCitesAClauseAndNamesItsCause() {
        for entry in HostileManifests.graded(nowEpochSeconds: now) {
            XCTAssertFalse(entry.spec.isEmpty, "\(entry)")
            XCTAssertFalse(entry.cause.isEmpty, "\(entry)")
            XCTAssertNotNil(entry.files[entry.sourcePath], "\(entry): its source is not served")
        }
    }

    func testTheDeliveryPathologiesAreCarriedAsDeclaredHeaders() {
        let cached = HostileManifests.hlsCachedLivePlaylist()
        let playlist = cached.declaredResponseHeaders.filter { $0.key.hasSuffix(".m3u8") }
        let segments = cached.declaredResponseHeaders.filter { $0.key.hasSuffix(".aac") }

        XCTAssertEqual(playlist.count, 1)
        XCTAssertEqual(playlist.first?.value["Cache-Control"], "public, max-age=600")
        XCTAssertEqual(segments.count, HostileManifests.segmentCount)
        XCTAssertTrue(segments.allSatisfy { $0.value["Cache-Control"] == "no-store" })

        // A CORS configuration is a property of the origin rather than of one path (#289).
        let cors = HostileManifests.hlsCorsRefusesCredentials()
        XCTAssertEqual(Set(cors.declaredResponseHeaders.keys), Set(cors.files.keys))
        XCTAssertTrue(
            cors.declaredResponseHeaders.values.allSatisfy {
                $0 == [
                    "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Credentials": "true",
                ]
            })
    }

    func testTheTokenPathologiesAreCarriedInThePathsTheyArePublishedAt() {
        let scoped = HostileManifests.hlsTokenScopedToManifest(nowEpochSeconds: now)
        XCTAssertEqual(
            scoped.files.keys.filter { $0.contains("expires=") }, [scoped.sourcePath],
            "scoped to the manifest: nothing below it is signed")
        XCTAssertTrue(scoped.sourcePath.hasSuffix("?expires=\(now + 86_400)&signature=corpus"))

        // The lifetime entry signs everything, so that only its expiry is wrong.
        let expiring = HostileManifests.hlsTokenExpiringInWindow(.severe, nowEpochSeconds: now)
        XCTAssertTrue(expiring.files.keys.allSatisfy { $0.contains("?expires=\(now + 4)&") })
    }
}
