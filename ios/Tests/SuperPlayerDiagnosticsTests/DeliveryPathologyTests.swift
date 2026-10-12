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
import SuperPlayerDiagnostics
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// The four pathologies that are in no document: how the stream was *delivered* (#428) — the
/// Kotlin `DeliveryPathologyTest`, over a loopback origin that serves the corpus's declared headers.
///
/// A `Cache-Control` the playlist and its segments disagree about, a token scoped to the manifest
/// and not to the media, a token that dies inside the content, a CORS configuration that refuses a
/// credentialed request — none is visible to any parse, because each is a fact about a transfer.
/// ``testAHeaderIsAFactAboutATransferAndNotAboutADocument`` holds the two halves side by side: the
/// same bytes, diagnosed one way when the headers are served and another when they are withheld.
///
/// **The controls are the other half.** ADR-0015 rule 12 scores a doctor on its false positives as
/// heavily as on its misses, and a delivery rule is where flagging everything is easiest. So
/// correctly delivered content is asserted to produce **nothing**, one method per defect, each over
/// content as close to the rule's threshold as healthy content comes.
///
/// **What the probe costs is read at the origin**: a doctor that fetched a segment it did not need
/// cannot hide the request, so "one segment's headers and no media" is an equality on
/// `networkRequests()` and the origin's own record of the body bytes it sent.
final class DeliveryPathologyTests: XCTestCase {

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    // MARK: The cache rule

    func testALivePlaylistHeldPastItsUpdateBoundIsNamedAndSoIsTheSideThatIsWrong() async throws {
        let finding = try await findingFor(
            HostileManifests.hlsCachedLivePlaylist(), .hlsCachedLivePlaylist)

        XCTAssertEqual(finding.severity, .blocking)
        // Not "these two disagree" but *which of them is wrong*, with both readings printed,
        // because the fix is a change to one cache rule on one path.
        XCTAssertEqual(
            finding.magnitude,
            "the playlist, not its segments: held for 600 s against a 2 s target duration, "
                + "while its segments are served \"no-store\"")
    }

    func testTheSegmentsReadingIsTheEvidenceAndNeverTheTest() async throws {
        // One cache rule over everything — the lifetime a CDN sets once for a whole path — is the
        // same defect: the playlist is the wrong party whatever the segments say, and only the
        // sentence moves.
        let finding = try await examine(
            HostileManifests.hlsCachedLivePlaylist(),
            served: ["Cache-Control": "public, max-age=600"]
        ).first { $0.pathology == .hlsCachedLivePlaylist }

        XCTAssertEqual(finding?.severity, .blocking)
        XCTAssertEqual(
            finding?.magnitude,
            "the playlist, not its segments: held for 600 s against a 2 s target duration, "
                + "while its segments are served \"public, max-age=600\"")
    }

    func testALivePlaylistNoCacheRuleIsHoldingIsNotAFinding() async throws {
        // The control the on-demand case cannot stand in for: a playlist with no EXT-X-ENDLIST,
        // where the rule *can* fire, served with a lifetime inside RFC 8216 §6.2.1's bound.
        let findings = try await examine(
            HostileManifests.hlsCachedLivePlaylist(), served: ["Cache-Control": "public, max-age=1"])

        XCTAssertEqual(findings, [])
    }

    func testAHeaderIsAFactAboutATransferAndNotAboutADocument() async throws {
        // The same entry, the same bytes, at the same paths — and the response headers withheld.
        // Nothing a parser can read tells the two apart, and the doctor reports the defect in
        // exactly one of them.
        let entry = HostileManifests.hlsCachedLivePlaylist()
        XCTAssertFalse(
            entry.declaredResponseHeaders.isEmpty,
            "the corpus carries this entry's defect as headers, which is the premise here")

        let served = try await harness.report(of: entry).findings.map(\.pathology)
        let withheld = try await examine(entry, served: [:]).map(\.pathology)

        XCTAssertTrue(served.contains(.hlsCachedLivePlaylist), "\(served)")
        XCTAssertFalse(withheld.contains(.hlsCachedLivePlaylist), "\(withheld)")
    }

    func testNamingTheWrongSideCostsOneSegmentsHeadersAndNoMedia() async throws {
        let entry = HostileManifests.hlsCachedLivePlaylist()
        let origin = try await harness.serve(
            .hostile(entry),
            faults: FaultScript.Builder().addDeclaredResponseHeaders(of: entry).build())

        _ = await MediaSourceDoctor()
            .examine(MediaRequest(contentId: entry.id, sources: [origin.sourceURL]))

        // Two playlists and exactly one segment: ADR-0015 rule 7's #289 addendum in a count. The
        // probe is a HEAD, so what it costs is a round trip and no byte of media, and no second
        // segment is asked for however many the playlist names.
        let asked = origin.networkRequests()
        XCTAssertEqual(asked.filter { $0.kind == .manifest }.map(\.method), ["GET", "GET"])
        let segments = asked.filter { $0.kind != .manifest }
        XCTAssertEqual(segments.map(\.method), ["HEAD"], "one segment's headers, and no more")
        XCTAssertEqual(
            segments.first?.path,
            "/hostile/hls-cached-live-playlist/severe/\(SyntheticHlsStream.segmentName(0))")
        XCTAssertEqual(
            origin.responses().filter { $0.request.kind != .manifest }.map(\.bodyBytesSent), [0])
    }

    func testAStreamNoCacheRuleIsHoldingIsNotAFindingAndCostsNoProbe() async throws {
        // On demand and live alike: an EXT-X-ENDLIST says the document will not change again, and
        // the ordinary live stream sets no lifetime at all.
        for content in [TestContent.hls(), .liveHls()] {
            let origin = try await harness.serve(content)
            let report = await MediaSourceDoctor()
                .examine(MediaRequest(contentId: "film/healthy", sources: [origin.sourceURL]))

            XCTAssertEqual(report.findings, [], "\(content)")
            // A stream with no defect to attribute pays nothing for the rule that would have
            // attributed it.
            XCTAssertEqual(
                origin.networkRequests().filter { $0.kind != .manifest }, [], "\(content)")
        }
    }

    // MARK: The token rules

    func testAManifestSignedWhoseSegmentsAreNotIsNamed() async throws {
        let finding = try await findingFor(
            HostileManifests.hlsTokenScopedToManifest(nowEpochSeconds: nowEpochSeconds),
            .hlsTokenScopedToManifest)

        XCTAssertEqual(finding.severity, .blocking)
        XCTAssertEqual(
            finding.magnitude,
            "the manifest is signed and its \(HostileManifests.segmentCount) segments are not")
    }

    func testATokenThatDiesInsideTheContentIsNamedAndGradedOnWhatIsLeftOfIt() async throws {
        let severe = try await findingFor(
            HostileManifests.hlsTokenExpiringInWindow(.severe, nowEpochSeconds: nowEpochSeconds),
            .hlsTokenExpiringInWindow)
        // Half a viewing left: no viewer reaches the end however promptly they start.
        XCTAssertEqual(severe.severity, .blocking)
        // The content's own duration is pinned; what is left of the token is not, deliberately: the
        // corpus mints the expiry against the clock and the rule reads it again later, so a second
        // crossed between the two moves the first number by one and nothing is wrong.
        XCTAssertTrue(
            severe.magnitude?.hasSuffix("of token left over 8 s of content") == true,
            "\(severe)")

        // One and a half viewings: the content plays through and a viewer who pauses loses it —
        // reported, and as the lesser reading.
        let borderline = try await findingFor(
            HostileManifests.hlsTokenExpiringInWindow(.borderline, nowEpochSeconds: nowEpochSeconds),
            .hlsTokenExpiringInWindow)
        XCTAssertEqual(borderline.severity, .degraded)
    }

    func testATokenThatOutlivesTheContentIsNotAFinding() async throws {
        // The control for both token rules, and the corpus's own benign grade: every URI is signed,
        // so the scope rule has nothing to say, and the token covers the content many times over.
        let benign = HostileManifests.hlsTokenExpiringInWindow(
            .benign, nowEpochSeconds: nowEpochSeconds)

        let findings = try await harness.report(of: benign).findings
        XCTAssertEqual(findings, [])
    }

    func testAnUnsignedSourceIsNotAFindingOfEitherTokenRule() async throws {
        // A source with no expiry at all is not signed, which is every stream the healthy controls
        // play: neither rule may read "no token" as "a token scoped wrongly" or "a token at zero".
        let findings = try await harness.report(of: .hls()).findings
        XCTAssertEqual(findings, [])
    }

    // MARK: The CORS rule

    func testResponseHeadersThatWouldRefuseAPlayersRequestAreNamed() async throws {
        let entry = HostileManifests.hlsCorsRefusesCredentials()
        let finding = try await findingFor(entry, .hlsCorsRefusesCredentials)

        XCTAssertEqual(finding.severity, .blocking)
        // Binary: a configuration refuses the request or it does not.
        XCTAssertNil(finding.magnitude)
        // The entry plays to the end in a stock AVPlayer (`HostileManifestCorpusTests`), which is
        // the whole reason the defect needs a doctor to be seen at all.
        XCTAssertEqual(entry.severity, .severe)
    }

    func testAnOriginThatSaysNothingAboutCorsIsNotAFinding() async throws {
        // Almost every origin serving a native player emits no CORS headers, and every one of them
        // is correct.
        let findings = try await harness.report(of: .hls()).findings
        XCTAssertFalse(findings.map(\.pathology).contains(.hlsCorsRefusesCredentials))
    }

    func testAnOriginThatSpeaksCorsCorrectlyIsNotAFinding() async throws {
        // A named allowed origin beside allowed credentials is exactly what the CORS check asks
        // for: the rule reads the values rather than reacting to the protocol being spoken.
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .addResponseHeaders([
                    "Access-Control-Allow-Origin": "https://app.example.test",
                    "Access-Control-Allow-Credentials": "true",
                ]).build())
        let report = await MediaSourceDoctor()
            .examine(MediaRequest(contentId: "film/healthy", sources: [origin.sourceURL]))

        XCTAssertEqual(report.findings, [])
    }

    // MARK: Helpers

    /// The one finding of `pathology` `entry` produces, with the corpus's own words asserted on it.
    private func findingFor(_ entry: HostileStream, _ pathology: Pathology) async throws
        -> Finding
    {
        let report = try await harness.report(of: entry)
        let finding = try XCTUnwrap(
            report.findings.first { $0.pathology == pathology },
            "\(entry) — \(entry.magnitude ?? ""): \(report)")
        // The id, the citation and the cause are the corpus's: the join the register scores on.
        XCTAssertEqual(pathology.id, entry.id)
        XCTAssertEqual(finding.specCitation, entry.spec)
        XCTAssertEqual(finding.cause, entry.cause)
        return finding
    }

    /// What a doctor says about `entry`'s bytes served with `served` on every response in place of
    /// the headers the entry declares — the Kotlin `servedWithResponseHeaders`. Empty withholds them.
    private func examine(_ entry: HostileStream, served: [String: String]) async throws
        -> [Finding]
    {
        let faults =
            served.isEmpty ? FaultScript.none : FaultScript.Builder().addResponseHeaders(served).build()
        let origin = try await harness.serve(.hostile(entry), faults: faults)
        return await MediaSourceDoctor()
            .examine(MediaRequest(contentId: entry.id, sources: [origin.sourceURL])).findings
    }
}
