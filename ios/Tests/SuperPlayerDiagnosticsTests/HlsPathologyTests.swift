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
import SuperPlayerDiagnostics
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// The doctor's HLS vocabulary, one pathology per test, against the corpus entry that carries it —
/// the Kotlin `HlsPathologyTest`, method for method, with the magnitudes it pins pinned here to the
/// same words, so a reader holding both platforms' reports reads one fact.
///
/// Every finding is checked against **the corpus's own words** — the entry's `spec` and `cause` —
/// so the vocabulary in `Pathology` cannot drift from the entries it is scored against. The live
/// window has no corpus entry (`CorpusRegisterTests` says why) and is forced on testkit's
/// `liveHls(windowSegmentCount:)`, with the window exactly at the bound and the ordinary live stream
/// as its two controls.
final class HlsPathologyTests: XCTestCase {

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testALadderWithAStepNoClientCanClimbIsNamedAndGradedOnTheStep() async throws {
        let severe = try await finding(HostileManifests.hlsLadderGap(.severe), .hlsLadderGap)
        XCTAssertEqual(severe.severity, .degraded)
        // 128 kbps to 6144 kbps with nothing between, as the corpus's severe step declares.
        XCTAssertEqual(severe.magnitude, "adjacent rungs 48× apart, 128 kbps to 6144 kbps")

        // A step of four is one rung missing from a ladder built to the guidance: reported, and
        // as the lesser reading, which is the whole of "the severity tracks the magnitude".
        let borderline = try await finding(
            HostileManifests.hlsLadderGap(.borderline), .hlsLadderGap)
        XCTAssertEqual(borderline.severity, .advisory)
    }

    func testARungThatDeclaresMoreThanItsFormatCanCarryIsNamed() async throws {
        let finding = try await finding(
            HostileManifests.hlsOverstatedBitrate(.severe), .hlsOverstatedBitrate)
        XCTAssertEqual(finding.severity, .degraded)
        // Four megabits of stereo AAC, against the 1152 kbps the codec's bit reservoir bounds it to.
        XCTAssertEqual(
            finding.magnitude,
            "declares 4000 kbps where the format it names carries at most 1152 kbps")
    }

    func testAVariantThatDeclaresNoCodecsIsNamed() async throws {
        let finding = try await finding(HostileManifests.hlsMissingCodecs(), .hlsMissingCodecs)
        XCTAssertEqual(finding.severity, .degraded)
        XCTAssertNil(finding.magnitude)
    }

    func testAnAudioGroupDeclaredAsAFormatItsSegmentsCannotCarryIsNamed() async throws {
        let finding = try await finding(
            HostileManifests.hlsAudioGroupCodecMismatch(), .hlsAudioGroupCodecMismatch)
        XCTAssertEqual(finding.severity, .degraded)
        XCTAssertNil(finding.magnitude)
    }

    func testAVariantPointingAtAnAudioGroupThatIsNotThereIsNamed() async throws {
        let finding = try await finding(
            HostileManifests.hlsDanglingAudioGroup(), .hlsDanglingAudioGroup)
        // Blocking because the document is malformed, not because a number is large.
        XCTAssertEqual(finding.severity, .blocking)
        XCTAssertNil(finding.magnitude)
    }

    func testSegmentsFurtherApartThanAnyToleranceAllowsAreNamedAndGradedOnTheSpread() async throws {
        let severe = try await finding(
            HostileManifests.hlsInconsistentSegmentDurations(.severe),
            .hlsInconsistentSegmentDurations)
        XCTAssertEqual(severe.severity, .degraded)
        XCTAssertEqual(severe.magnitude, "segments 0.5 s to 10 s long, a 20× spread")

        // The borderline spread is exactly three, the widest pair the published tolerance admits;
        // the threshold is strict, so a playlist that conforms exactly is not flagged — pinned, as
        // the Kotlin test pins it, so the answer moving is visible.
        let borderline = HostileManifests.hlsInconsistentSegmentDurations(.borderline)
        let findings = try await harness.report(of: borderline).findings
        XCTAssertEqual(findings, [], "\(borderline) — \(borderline.magnitude ?? "")")
    }

    func testASpliceWithNothingToPlaceItsTimelineIsNamed() async throws {
        let finding = try await finding(
            HostileManifests.hlsDiscontinuityWithoutTimeline(), .hlsDiscontinuityWithoutTimeline)
        XCTAssertEqual(finding.severity, .degraded)
        XCTAssertNil(finding.magnitude)
    }

    func testALiveWindowShorterThanThreeTargetDurationsIsNamed() async throws {
        // Two two-second segments against a two-second target: four seconds of window where RFC
        // 8216 §6.2.2 requires six, so no segment starts the three target durations from the end
        // §6.3.3 asks a client to hold back.
        let report = try await harness.report(of: .liveHls(windowSegmentCount: 2))
        let finding = try XCTUnwrap(report.findings.first { $0.pathology == .hlsShortLiveWindow })
        XCTAssertEqual(finding.severity, .blocking)
        XCTAssertEqual(finding.magnitude, "a window of 4 s, against the 6 s of 3 target durations")
        XCTAssertEqual(finding.specCitation, "RFC 8216 §6.2.2, §6.3.3")
        XCTAssertEqual(report.findings.map(\.pathology), [.hlsShortLiveWindow])
    }

    func testALiveWindowExactlyAtTheBoundAndTheOrdinaryLiveStreamAreNotFindings() async throws {
        // The two controls that keep the rule from being "live is defective": a window of exactly
        // three target durations is what §6.2.2 allows, and the six-segment window every live test
        // here plays is twice it.
        let atTheBound = try await harness.report(of: .liveHls(windowSegmentCount: 3))
        XCTAssertEqual(atTheBound.findings, [], "a window of exactly three target durations")
        let ordinary = try await harness.report(of: .liveHls())
        XCTAssertEqual(ordinary.findings, [], "liveHls()'s twelve-second window")
    }

    func testAPlaylistCarryingMoreThanOnePathologyReportsAllOfThem() async throws {
        // A ladder gap of 48 puts its top rung at 6144 kbps, which is also more stereo AAC than the
        // codec can carry: one defect that reads two ways, both true of the document.
        let report = try await harness.report(of: HostileManifests.hlsLadderGap(.severe))
        XCTAssertEqual(
            Set(report.findings.map(\.pathology)), [.hlsLadderGap, .hlsOverstatedBitrate])
    }

    func testTheBenignGradeOfEveryGradedPathologyIsNotAFinding() async throws {
        let benign = HostileManifests.graded(nowEpochSeconds: nowEpochSeconds)
            .filter { $0.severity == .benign }
        XCTAssertEqual(
            benign.map(\.id),
            [
                "hls-ladder-gap", "hls-overstated-bitrate", "hls-inconsistent-segment-durations",
                "hls-token-expiring-in-window",
            ])
        for entry in benign {
            let findings = try await harness.report(of: entry).findings
            XCTAssertEqual(findings, [], "\(entry) — \(entry.magnitude ?? "")")
        }
    }

    func testAHealthyMultivariantPlaylistIsNotAFinding() async throws {
        let report = try await harness.report(of: .hls(), contentId: "film/healthy")
        XCTAssertEqual(report.findings, [])
        XCTAssertEqual(report.contentId, "film/healthy")
    }

    /// The one finding of `pathology` that `entry` produces, with the corpus's own words asserted on
    /// it.
    private func finding(_ entry: HostileStream, _ pathology: Pathology) async throws -> Finding {
        let report = try await harness.report(of: entry)
        let finding = try XCTUnwrap(
            report.findings.first { $0.pathology == pathology },
            "\(entry) — \(entry.magnitude ?? ""): \(report)")
        XCTAssertEqual(pathology.id, entry.id, "\(entry)")
        XCTAssertEqual(finding.specCitation, entry.spec, "\(entry)")
        XCTAssertEqual(finding.cause, entry.cause, "\(entry)")
        return finding
    }
}
