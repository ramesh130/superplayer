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

/// The corpus register on Apple platforms: what the doctor says about every curated HLS pathology,
/// and the test that forces it — the Kotlin `CorpusRegisterTest`, ADR-0015 rule 12's gate, over
/// the Swift corpus (`HostileManifests`), which is the Kotlin corpus's HLS half byte for byte.
///
/// **Both halves, per entry.** ``testEverySevereEntryIsNamedByTheDoctor`` asserts each entry's
/// findings at `severe` *exactly* — a finding the register does not expect is a false positive on
/// hostile content — and ``testNoBenignGradeIsAFinding`` and ``testNoHealthyStreamIsAFinding``
/// assert silence one entry at a time, each with its own message, never as a sum over the corpus.
/// The keys are checked against `HostileManifests.graded(nowEpochSeconds:)` rather than a list
/// written here, so an entry added to the corpus fails ``testEveryCorpusPathologyIsRegistered`` the
/// day it is added. Magnitudes are not recorded: `HlsPathologyTests` pins them beside their reasons.
///
/// **What the register adds on this platform** is ADR-0020 rule 8's enforcement: the doctor reads
/// with a lint of its own rather than the engine's parser, and a lint that misread an entry — saw a
/// defect that is not there, or missed one that is — fails that entry's row here, which is what
/// keeps the two platforms' doctors reading one corpus one way.
///
/// **The delivery rows are scored like the rest** (#428): a defect of a response header or an
/// address rather than of a document, served by the origin from the headers each entry declares
/// (`Examining.swift`), so a row there is as much an equality as a ladder's. ADR-0015 rule 12 still
/// admits "an entry the doctor cannot yet name" as recorded rather than removed, and the gate stays
/// for the next one: such a row carries `cannotYetName`, required of a row with no findings and
/// forbidden of any other (``testARowThatNamesNothingSaysWhyRatherThanPassingQuietly``), so
/// relaxing the gate is never the edit that looks like recording it.
///
/// It does not touch `HostileManifestCorpusTests`, which records what a *player* does with the same
/// entries: naming a defect is not recovering from it (ADR-0015 rule 4).
final class CorpusRegisterTests: XCTestCase {

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testEveryCorpusPathologyIsRegistered() {
        // The corpus is the authority, in its own order.
        var curated: [String] = []
        for entry in HostileManifests.graded(nowEpochSeconds: nowEpochSeconds)
        where !curated.contains(entry.id) {
            curated.append(entry.id)
        }
        XCTAssertEqual(
            Self.register.map(\.id), curated,
            "Every pathology in HostileManifests.graded(nowEpochSeconds:) needs a row in "
                + "CorpusRegisterTests.register, in the corpus's own order: the findings the doctor "
                + "reports at severe and the test that forces them — or, if it cannot yet name it, "
                + "no findings and a cannotYetName saying why (ADR-0015 rule 12).")
    }

    func testEveryPathologyTheDoctorCanNameIsScoredAgainstTheCorpusOrRecordedAsUngradable() {
        let scored = Set(Self.register.flatMap { $0.reading.findings.keys })
        let ungradable = Set(Self.ungradable.map(\.pathology))
        XCTAssertEqual(
            scored.union(ungradable), Set(Pathology.allCases),
            "A pathology the doctor can name is scored against a corpus entry or recorded in "
                + "ungradable with the test that forces it; there is no third case.")
        XCTAssertEqual(scored.intersection(ungradable), [])
    }

    func testEverySevereEntryIsNamedByTheDoctor() async throws {
        for entry in HostileManifests.all(nowEpochSeconds: nowEpochSeconds) {
            let reading = try XCTUnwrap(
                Self.register.first { $0.id == entry.id }?.reading, "\(entry) has no row")
            let report = try await harness.report(of: entry)
            let found = Dictionary(
                report.findings.map { ($0.pathology, $0.severity) }, uniquingKeysWith: max)
            XCTAssertEqual(
                found, reading.findings, "\(entry) — \(entry.magnitude ?? ""): \(report)")
        }
    }

    func testARowThatNamesNothingSaysWhyRatherThanPassingQuietly() {
        for (id, reading) in Self.register {
            XCTAssertFalse(
                reading.findings.isEmpty && reading.cannotYetName == nil,
                "\(id) names no finding, so its row needs cannotYetName saying why")
            XCTAssertFalse(
                !reading.findings.isEmpty && reading.cannotYetName != nil,
                "\(id) names findings, so cannotYetName is not true of it")
        }
    }

    func testNoBenignGradeIsAFinding() async throws {
        let benign = HostileManifests.graded(nowEpochSeconds: nowEpochSeconds)
            .filter { $0.severity == .benign }
        XCTAssertFalse(benign.isEmpty)
        for entry in benign {
            let findings = try await harness.report(of: entry).findings
            XCTAssertEqual(findings, [], "\(entry) — \(entry.magnitude ?? "")")
        }
    }

    func testNoHealthyStreamIsAFinding() async throws {
        for (what, content) in Self.healthy {
            let findings = try await harness.report(of: content).findings
            XCTAssertEqual(findings, [], what)
        }
    }

    func testEveryNamedTestExistsAndIsATest() throws {
        let named =
            Self.register.compactMap { row in row.reading.forcedBy.map { (row.id, $0) } }
            + Self.ungradable.map { ($0.pathology.id, $0.forcedBy) }
        for (id, method) in named {
            let source = try String(contentsOf: method.sourceFile, encoding: .utf8)
            XCTAssertTrue(
                source.contains("final class \(method.className): XCTestCase"),
                "\(id): \(method.sourceFile.path) declares no test class \(method.className)")
            // XCTest runs an instance method whose name begins `test`; one renamed away from that
            // prefix still exists and is no longer run, which is how a pathology would stop being
            // forced without anyone noticing.
            XCTAssertTrue(method.methodName.hasPrefix("test"), "\(id): \(method)")
            XCTAssertTrue(source.contains("    func \(method.methodName)("), "\(id): \(method)")
        }
    }

    /// A test of this target by class and method, the class named for its file.
    struct TestMethod: CustomStringConvertible {
        let className: String
        let methodName: String

        var sourceFile: URL {
            URL(fileURLWithPath: #filePath).deletingLastPathComponent()
                .appendingPathComponent("\(className).swift")
        }

        var description: String { "\(className).\(methodName)" }
    }

    /// What the doctor says about one corpus entry at `severe`, and who forces it.
    ///
    /// `findings` is exhaustive: the assertion is an equality. `cannotYetName` is why a row names
    /// nothing, required of such a row and forbidden of any other.
    struct Reading {
        let findings: [Pathology: FindingSeverity]
        let forcedBy: TestMethod?
        var cannotYetName: String? = nil
    }

    /// A pathology no entry of the corpus carries, the reason, and the test that forces it instead.
    struct Ungradable {
        let pathology: Pathology
        let whyNotInTheCorpus: String
        let forcedBy: TestMethod
    }

    /// **The register**, in the corpus's order: per entry, the findings at `severe` and the test that
    /// forces them.
    static let register: [(id: String, reading: Reading)] = [
        // Two findings, both true of the document: a step of 48 puts the top rung at 6144 kbps,
        // which is also more stereo AAC than the codec can carry.
        (
            "hls-ladder-gap",
            Reading(
                findings: [.hlsLadderGap: .degraded, .hlsOverstatedBitrate: .degraded],
                forcedBy: hls("testALadderWithAStepNoClientCanClimbIsNamedAndGradedOnTheStep"))
        ),
        (
            "hls-overstated-bitrate",
            Reading(
                findings: [.hlsOverstatedBitrate: .degraded],
                forcedBy: hls("testARungThatDeclaresMoreThanItsFormatCanCarryIsNamed"))
        ),
        // Forced by the doctor's tracer test, as on Android: it is the pathology the shape was
        // built over.
        (
            "hls-missing-codecs",
            Reading(
                findings: [.hlsMissingCodecs: .degraded],
                forcedBy: doctor(
                    "testAVariantThatDeclaresNoCodecsIsNamedWithItsSeverityItsCitationAndItsCause"))
        ),
        (
            "hls-audio-group-codec-mismatch",
            Reading(
                findings: [.hlsAudioGroupCodecMismatch: .degraded],
                forcedBy: hls("testAnAudioGroupDeclaredAsAFormatItsSegmentsCannotCarryIsNamed"))
        ),
        (
            "hls-dangling-audio-group",
            Reading(
                findings: [.hlsDanglingAudioGroup: .blocking],
                forcedBy: hls("testAVariantPointingAtAnAudioGroupThatIsNotThereIsNamed"))
        ),
        (
            "hls-inconsistent-segment-durations",
            Reading(
                findings: [.hlsInconsistentSegmentDurations: .degraded],
                forcedBy: hls(
                    "testSegmentsFurtherApartThanAnyToleranceAllowsAreNamedAndGradedOnTheSpread"))
        ),
        (
            "hls-discontinuity-without-timeline",
            Reading(
                findings: [.hlsDiscontinuityWithoutTimeline: .degraded],
                forcedBy: hls("testASpliceWithNothingToPlaceItsTimelineIsNamed"))
        ),
        // A live playlist whose bytes never change, held ten minutes by its declared cache rule. Its
        // window is four segments of two seconds against a two-second target — past the three
        // target durations the live-window rule asks for, and at a media sequence of zero besides —
        // so that rule is silent here, correctly, and the cache rule alone speaks.
        (
            "hls-cached-live-playlist",
            Reading(
                findings: [.hlsCachedLivePlaylist: .blocking],
                forcedBy: delivery(
                    "testALivePlaylistHeldPastItsUpdateBoundIsNamedAndSoIsTheSideThatIsWrong"))
        ),
        (
            "hls-token-scoped-to-manifest",
            Reading(
                findings: [.hlsTokenScopedToManifest: .blocking],
                forcedBy: delivery("testAManifestSignedWhoseSegmentsAreNotIsNamed"))
        ),
        // Half a viewing left: blocking on its magnitude, the one delivery grade that is.
        (
            "hls-token-expiring-in-window",
            Reading(
                findings: [.hlsTokenExpiringInWindow: .blocking],
                forcedBy: delivery(
                    "testATokenThatDiesInsideTheContentIsNamedAndGradedOnWhatIsLeftOfIt"))
        ),
        (
            "hls-cors-refuses-credentials",
            Reading(
                findings: [.hlsCorsRefusesCredentials: .blocking],
                forcedBy: delivery("testResponseHeadersThatWouldRefuseAPlayersRequestAreNamed"))
        ),
    ]

    /// The pathologies no corpus entry carries, with the reason and the test that forces each.
    static let ungradable: [Ungradable] = [
        Ungradable(
            pathology: .hlsShortLiveWindow,
            whyNotInTheCorpus: "its Kotlin twin is dash-short-time-shift-buffer-depth, and the "
                + "DASH entries are not ported (ADR-0020 rule 6); testkit's "
                + "liveHls(windowSegmentCount:) publishes the stream with that one thing wrong, "
                + "and the window exactly at the bound beside it",
            forcedBy: hls("testALiveWindowShorterThanThreeTargetDurationsIsNamed")),
        Ungradable(
            pathology: .manifestUnreachable,
            whyNotInTheCorpus: "no stream can carry a refusal: it is the transport's answer, which "
                + "the origin's fault script supplies instead",
            forcedBy: doctor("testAManifestTheOriginRefusesIsAFindingRatherThanAThrow")),
        Ungradable(
            pathology: .manifestUnreadable,
            whyNotInTheCorpus: "every corpus entry is a playlist the RFC's grammar reads — that is "
                + "what valid-but-hostile and even malformed mean there — so bytes that are no "
                + "playlist at all are served by pointing the doctor at something else",
            forcedBy: doctor("testAManifestTheLintCannotReadIsToldApartFromOneThatDidNotArrive")),
    ]

    /// Content with nothing wrong with it, with what each controls. The ladder's own control is the
    /// corpus's benign gap, two rungs exactly a factor of two apart, which the benign sweep covers.
    static let healthy: [(String, TestContent)] = [
        ("a healthy single-rung multivariant playlist", .hls()),
        ("a healthy two-rung ladder, built to the published guidance", .hls(variantCount: 2)),
        // The control the live rules need and no on-demand stream can be: a conforming live
        // playlist, twice the bound. A rule that read "live" as "defective" passes everything else
        // here and fails on this — the role the Kotlin register gives the corpus's healthy dynamic
        // MPD, which is DASH and not ported. Examined at once its window has not slid yet, so the
        // window comparison's own control, a slid window exactly at the bound, is
        // `HlsPathologyTests`'.
        ("the ordinary live stream", .liveHls()),
    ]

    static func hls(_ methodName: String) -> TestMethod {
        TestMethod(className: "HlsPathologyTests", methodName: methodName)
    }

    static func doctor(_ methodName: String) -> TestMethod {
        TestMethod(className: "MediaSourceDoctorTests", methodName: methodName)
    }

    static func delivery(_ methodName: String) -> TestMethod {
        TestMethod(className: "DeliveryPathologyTests", methodName: methodName)
    }
}
