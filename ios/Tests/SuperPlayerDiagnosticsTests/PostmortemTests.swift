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

import Combine
import Foundation
import SuperPlayerCore
import SuperPlayerDiagnostics
import SuperPlayerResilience
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// The other half of `PRD.md` §3.6: a session that *failed* is examined, and answers what class of
/// failure it was **and** what was wrong with the stream that produced it (#428) — the Kotlin
/// `PostmortemTest`, over real `SuperPlayer`s at the loopback origin.
///
/// The whole of the API is one argument on the call every preflight already makes —
/// `examine(request, classification: player.classify(error))` — because ADR-0015 rule 8 makes
/// preflight and postmortem one report and one entry point, and rule 5 makes the difference between
/// them one populated field.
///
/// **What this class guards is that the two vocabularies stay apart** (ADR-0015 rules 4 and 5).
/// The classification is the classifier's, read off the `SuperPlayerError` the session ended on,
/// and the diagnostics product could not recompute one: it depends on no classifier. The findings
/// are the doctor's, the same ones the same request answers with nothing having failed. So all four
/// combinations are reachable, and the two that disagree are the interesting ones:
///
/// | | a defect the doctor names | no defect |
/// | --- | --- | --- |
/// | **the session failed** | ``testALiveSessionThatFrozeOnACachedPlaylistReportsThePathologyBesideItsClass`` | ``testAFailureWithNoManifestDefectBehindItReportsTheClassAndNoFinding`` |
/// | **no session failed** | ``testAStreamThatPlaysOnWhileMisconfiguredReportsFindingsAndNoClassification`` | every healthy preflight (`MediaSourceDoctorTests`) |
///
/// plus ADR-0011 rule 14 surfacing: ``testAConsumerWithNoResilienceGetsTheFindingsAndNoClassification``.
///
/// **Where the iOS table differs from the Kotlin one, and why.** The Kotlin failed-with-a-defect
/// row is DASH's short time-shift window, which is not ported (ADR-0020 rule 6); here it is the
/// cached live playlist, the delivery defect this ticket names — the same "live stream freezes
/// after 30 s" ticket, met from both ends, since core's stale-playlist watch (#421) and the
/// doctor's rule ask one judgement. It reads the playlist's responses, which AVFoundation hands over
/// only from iOS 18, so that row skips below it. The core-only row is the dangling audio group,
/// because AVFoundation fails it before a sample plays (`HostileManifestCorpusTests`' record) where
/// a core-only player of a frozen playlist would wait forever with nothing to classify. And
/// `theDefectNoRungCanReach…` has no row: its entry is DASH's `availabilityStartTime` skew.
@MainActor
final class PostmortemTests: XCTestCase {

    /// A ceiling on every wait, never an assertion — `StaleLivePlaylistPlaybackTests`' bound, for
    /// the same frozen stream.
    private let bound: Duration = .seconds(90)

    private let harness = PlaybackHarness()
    private var players: [SuperPlayer] = []
    private var subscriptions: Set<AnyCancellable> = []

    override func tearDown() async throws {
        subscriptions = []
        for player in players { player.release() }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    func testALiveSessionThatFrozeOnACachedPlaylistReportsThePathologyBesideItsClass() async throws
    {
        guard #available(iOS 18, tvOS 18, *) else { throw XCTSkip("needs AVMetrics (iOS 18)") }
        let entry = HostileManifests.hlsCachedLivePlaylist()
        let origin = try await harness.serve(
            .hostile(entry),
            faults: FaultScript.Builder().addDeclaredResponseHeaders(of: entry).build())
        let (player, errors) = play(entry, from: origin, resilient: true)
        try await harness.until("the session ends", timeout: bound) { !errors.isEmpty }
        let classification = try XCTUnwrap(player.classify(errors[0]))

        let report = await examine(entry, at: origin, classification: classification)

        // Side by side. The classification is the resilience product's word for what ended the
        // session — read, so it is the very error the player delivered…
        XCTAssertEqual(report.classification?.causeClass, "Transient.CdnEdge")
        XCTAssertTrue(report.classification?.underlyingError === classification.underlyingError)
        // …and the finding is the doctor's word for what is wrong with the delivery, with the clause
        // it departs from. Neither is derived from the other, and the report prints both.
        let finding = try XCTUnwrap(
            report.findings.first { $0.pathology == .hlsCachedLivePlaylist }, "\(report)")
        XCTAssertEqual(finding.specCitation, entry.spec)
        XCTAssertEqual(finding.severity, .blocking)
        XCTAssertTrue(report.description.hasSuffix(", ended on Transient.CdnEdge)"), "\(report)")
    }

    func testAFailureWithNoManifestDefectBehindItReportsTheClassAndNoFinding() async throws {
        // The control, and the first disagreement: a stream whose only defect is benign — which the
        // register holds the doctor silent on — failing because the *CDN* refused its segments. A
        // postmortem that answered a finding here would have learnt to explain a failure with
        // whatever the manifest happened to say.
        let entry = HostileManifests.hlsLadderGap(.benign)
        let origin = try await harness.serve(
            .hostile(entry),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpNotFound, kind: .mediaSegment).build())
        let (player, errors) = play(entry, from: origin, resilient: true)
        try await harness.until("the session ends", timeout: bound) { !errors.isEmpty }

        let report = await examine(
            entry, at: origin, classification: player.classify(errors[0]))

        // Named rather than merely present: a segment refused 404 is the edge's on this platform
        // (`TypedErrorPlaybackTests`), and a silent report beside some other class would be a
        // different scenario passing under this one's name.
        XCTAssertEqual(report.classification?.causeClass, "Transient.CdnEdge")
        XCTAssertEqual(report.findings, [], "nothing is wrong with this manifest: \(report)")
    }

    func testAStreamThatPlaysOnWhileMisconfiguredReportsFindingsAndNoClassification() async throws {
        // The second disagreement, and the direction a preflight is for: a ladder with a step no
        // client climbs gracefully plays to the end in AVFoundation (`HostileManifestCorpusTests`),
        // so there is no failure to classify and the defect is still there to name.
        let entry = HostileManifests.hlsLadderGap()
        let origin = try await harness.serve(.hostile(entry))
        let (player, errors) = play(entry, from: origin, resilient: true)
        try await harness.until("playback passes the second segment", timeout: bound) {
            player.avPlayer.currentTime().seconds > 4
        }
        XCTAssertTrue(errors.isEmpty, "this entry plays: there is nothing to classify")
        XCTAssertNil(player.playerError)

        let report = await examine(entry, at: origin, classification: nil)

        XCTAssertNil(report.classification)
        XCTAssertTrue(report.findings.map(\.pathology).contains(.hlsLadderGap), "\(report)")
    }

    func testAConsumerWithNoResilienceGetsTheFindingsAndNoClassification() async throws {
        // ADR-0011 rule 14's existing behaviour surfacing here, and ADR-0015 rule 1's reason for
        // keeping SuperPlayerResilience off the diagnostics product: a player configured without it
        // classifies nothing, so `classify` answers nil and the report carries the findings alone.
        // AVFoundation fails this entry whatever the player was configured with.
        let entry = HostileManifests.hlsDanglingAudioGroup()
        let origin = try await harness.serve(.hostile(entry))
        let (player, errors) = play(entry, from: origin, resilient: false)
        try await harness.until("the item fails", timeout: bound) {
            player.avPlayer.currentItem?.status == .failed
        }
        let failure = try XCTUnwrap(player.avPlayer.currentItem?.error)
        XCTAssertNil(player.classify(failure), "a player with no resilience classifies nothing")
        XCTAssertTrue(errors.isEmpty)

        let report = await examine(
            entry, at: origin, classification: player.classify(failure))

        XCTAssertNil(report.classification)
        XCTAssertTrue(
            report.findings.map(\.pathology).contains(.hlsDanglingAudioGroup), "\(report)")
    }

    // MARK: Helpers

    /// Plays `entry` from `origin` on a player configured with resilience or without, and the
    /// errors `player.errors` delivers, appended on the main actor where the facade sends them.
    private func play(_ entry: HostileStream, from origin: LoopbackOrigin, resilient: Bool)
        -> (SuperPlayer, Errors)
    {
        var configuration = SuperPlayer.Configuration()
        if resilient { configuration.resilience = Resilience.standard() }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        let errors = Errors()
        player.errors.sink { error in MainActor.assumeIsolated { errors.append(error) } }
            .store(in: &subscriptions)
        player.setMediaRequest(MediaRequest(contentId: entry.id, sources: [origin.sourceURL]))
        player.avPlayer.play()
        return (player, errors)
    }

    /// The postmortem itself: the request the session was playing, asked of a doctor afterwards —
    /// a second fetch from the same origin under the same faults, and not a replay of the first.
    private func examine(
        _ entry: HostileStream, at origin: LoopbackOrigin, classification: SuperPlayerError?
    ) async -> DiagnosticReport {
        await MediaSourceDoctor()
            .examine(
                MediaRequest(contentId: entry.id, sources: [origin.sourceURL]),
                classification: classification)
    }

    /// The errors one player delivered, in order.
    private final class Errors {
        private(set) var all: [any Error] = []
        var isEmpty: Bool { all.isEmpty }
        subscript(index: Int) -> any Error { all[index] }
        func append(_ error: any Error) { all.append(error) }
    }
}
