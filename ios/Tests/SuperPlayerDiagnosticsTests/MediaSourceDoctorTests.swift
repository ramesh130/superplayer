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
import os

/// The doctor's shape, its fetch and what it costs — the Kotlin `MediaSourceDoctorTest` and the
/// network half of `DiagnosticsPayNothingTest`, over a loopback origin.
///
/// What the origin counts is what the doctor put on the wire: a request it did not make cannot be
/// counted, so "it fetches playlists and never a segment" is an equality on the origin's record
/// rather than a reading of the doctor's code.
final class MediaSourceDoctorTests: XCTestCase {

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testAVariantThatDeclaresNoCodecsIsNamedWithItsSeverityItsCitationAndItsCause() async throws
    {
        let entry = HostileManifests.hlsMissingCodecs()
        let report = try await harness.report(of: entry)

        XCTAssertEqual(report.contentId, "hls-missing-codecs")
        XCTAssertEqual(report.findings.count, 1, "\(report)")
        let finding = try XCTUnwrap(report.findings.first)
        XCTAssertEqual(finding.pathology, .hlsMissingCodecs)
        XCTAssertEqual(finding.pathology.id, "hls-missing-codecs")
        XCTAssertEqual(finding.severity, .degraded)
        XCTAssertEqual(finding.specCitation, "RFC 8216 §4.3.4.2")
        XCTAssertEqual(finding.cause, entry.cause)
        XCTAssertNil(finding.magnitude)
        XCTAssertEqual(report.chain, [], "a doctor configured with nothing travels no option")
    }

    func testAHealthyStreamYieldsNoFinding() async throws {
        let report = try await harness.report(of: .hls(variantCount: 2))
        XCTAssertEqual(report.findings, [])
    }

    func testNothingLeavesForTheNetworkUntilADoctorIsAskedAndThenOnlyPlaylists() async throws {
        let origin = try await harness.serve(.hls(variantCount: 2))
        let doctor = MediaSourceDoctor()
        XCTAssertEqual(origin.networkRequests(), [], "a doctor made and not asked fetches nothing")

        _ = await doctor.examine(MediaRequest(contentId: "film", sources: [origin.sourceURL]))

        // The multivariant playlist and both media playlists, once each, and no segment: the
        // pathologies are declarations (ADR-0015 rule 7).
        XCTAssertEqual(
            origin.networkRequests().map(\.path).sorted(),
            ["/master.m3u8", "/media-high.m3u8", "/media.m3u8"])
        XCTAssertEqual(Set(origin.networkRequests().map(\.kind)), [.manifest])
    }

    func testAManifestTheOriginRefusesIsAFindingRatherThanAThrow() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpForbidden, kind: .manifest, index: 0).build())
        let report = await MediaSourceDoctor()
            .examine(MediaRequest(contentId: "film", sources: [origin.sourceURL]))

        XCTAssertEqual(report.findings.map(\.pathology), [.manifestUnreachable])
        XCTAssertEqual(report.findings.first?.severity, .blocking)
        // The status, and only the status: a refusal's own text may carry the URL and its token.
        XCTAssertEqual(report.findings.first?.magnitude, "HTTP 403")
    }

    func testAHostThatDoesNotAnswerIsUnreachableWithNoStatus() async throws {
        let origin = try await harness.serve(
            .hls(), faults: FaultScript.Builder().resetConnection(kind: .manifest).build())
        let report = await MediaSourceDoctor()
            .examine(MediaRequest(contentId: "film", sources: [origin.sourceURL]))

        XCTAssertEqual(report.findings.map(\.pathology), [.manifestUnreachable])
        XCTAssertNil(report.findings.first?.magnitude)
    }

    func testAManifestTheLintCannotReadIsToldApartFromOneThatDidNotArrive() async throws {
        // A 200 whose body is no playlist — here a segment's ADTS bytes, the shape of an error or
        // consent page served with a 200 — is unreadable, not unreachable: something came back.
        let origin = try await harness.serve(.hls())
        let report = await MediaSourceDoctor()
            .examine(
                MediaRequest(
                    contentId: "film",
                    sources: [origin.url(path: SyntheticHlsStream.segmentName(0))]))

        XCTAssertEqual(report.findings.map(\.pathology), [.manifestUnreadable])
        XCTAssertEqual(report.findings.first?.severity, .blocking)
        XCTAssertNil(report.findings.first?.magnitude)
    }

    func testOneRenditionThatCannotBeFetchedIsNamedBesideWhatElseIsWrong() async throws {
        // The second variant's playlist refused, the first's read: a report on a stream with one
        // broken rendition names it and still reads the rest.
        let entry = HostileManifests.hlsLadderGap(.severe)
        let origin = try await harness.serve(
            .hostile(entry),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpNotFound, kind: .manifest, index: 2).build())
        let report = await MediaSourceDoctor()
            .examine(MediaRequest(contentId: entry.id, sources: [origin.sourceURL]))

        XCTAssertEqual(
            report.findings.map(\.pathology),
            [.hlsLadderGap, .hlsOverstatedBitrate, .manifestUnreachable])
        XCTAssertEqual(report.findings.last?.magnitude, "HTTP 404")
    }

    func testARefusedCredentialIsOfferedToTheProviderOnceAndCarriedAfter() async throws {
        let faults = FaultScript.Builder()
            .failWithHttpStatus(401, kind: .manifest, index: 0, firstAttempts: 1).build()
        let origin = try await harness.serve(.hls(), faults: faults)
        let tokens = Tokens(minting: ["Authorization": "Bearer fresh"])
        let doctor = MediaSourceDoctor(configuration: .init(headers: tokens))

        let report = await doctor.examine(
            MediaRequest(contentId: "film", sources: [origin.sourceURL]))

        XCTAssertEqual(report.findings, [], "\(report)")
        XCTAssertEqual(report.chain, [.headerRefresh])
        XCTAssertEqual(tokens.refusals().map(\.status), [401])
        XCTAssertEqual(tokens.refusals().first?.sentHeaders, [:])
        // Refused bare, then asked again with the credential, which the media playlist carries too
        // — a token is the session's, so it rides every later request rather than one.
        XCTAssertEqual(
            origin.networkRequests().map { "\($0.path) \($0.headers["authorization"] ?? "-")" },
            ["/master.m3u8 -", "/master.m3u8 Bearer fresh", "/media.m3u8 Bearer fresh"])
    }

    func testWithNoProviderTheSameRefusalIsUnreachable() async throws {
        // The control: the repair is the provider's doing and not a retry of the doctor's own.
        let faults = FaultScript.Builder()
            .failWithHttpStatus(401, kind: .manifest, index: 0, firstAttempts: 1).build()
        let origin = try await harness.serve(.hls(), faults: faults)
        let report = await MediaSourceDoctor()
            .examine(MediaRequest(contentId: "film", sources: [origin.sourceURL]))

        XCTAssertEqual(report.findings.map(\.magnitude), ["HTTP 401"])
        XCTAssertEqual(origin.networkRequests().count, 1)
    }

    func testAProviderThatReturnsWhatWasSentDeclinesAndTheRefusalStands() async throws {
        let faults = FaultScript.Builder()
            .failWithHttpStatus(403, kind: .manifest, index: 0).build()
        let origin = try await harness.serve(.hls(), faults: faults)
        let tokens = Tokens(minting: [:])
        let report = await MediaSourceDoctor(configuration: .init(headers: tokens))
            .examine(MediaRequest(contentId: "film", sources: [origin.sourceURL]))

        XCTAssertEqual(report.findings.map(\.magnitude), ["HTTP 403"])
        XCTAssertEqual(tokens.refusals().count, 1, "asked once per request, never in a loop")
        XCTAssertEqual(origin.networkRequests().count, 1)
    }

    /// A provider that mints one fixed credential and remembers every refusal it was told about.
    private final class Tokens: HeaderProvider {
        private let minted: [String: String]
        private let asked = OSAllocatedUnfairLock(initialState: [CredentialRefusal]())

        init(minting minted: [String: String]) { self.minted = minted }

        func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String] {
            asked.withLock { $0.append(refusal) }
            return minted
        }

        func refusals() -> [CredentialRefusal] { asked.withLock { $0 } }
    }
}
