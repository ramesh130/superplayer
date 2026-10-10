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

import AVFoundation
import SuperPlayerCore
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// The measurement session's edges as core signals them to a collector (#408, ADR-0008 rule 2),
/// through the harness: content that really plays opens a session, a replacement ends it before the
/// next begins, content with no identity ends it and opens none, and a release ends it once, before
/// the collector lets go. The collector is a hand-written recorder, because the real one is #409's;
/// what is asserted is what core tells any collector, and in what order.
///
/// Restore and reset-for-reuse are the other two edges, and their callers are #401's
/// `restoreSnapshot` and #404's `resetForReuse`; `SessionEdges.swift` says what each calls.
///
/// Time is real and nothing here measures it: each step waits, with a ceiling, for the position to
/// advance, so every session asserted is one whose content was playing.
@MainActor
final class SessionEdgePlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(60)
    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testEachAdoptionIsASessionAndAReplacementEndsTheOneBefore() async throws {
        let origin = try await harness.serve(.hls())
        let recorder = EdgeRecorder()
        let player = measuredPlayer(recorder)

        try await play(MediaRequest(contentId: "a", sources: [origin.sourceURL]), on: player)
        try await play(MediaRequest(contentId: "b", sources: [origin.sourceURL]), on: player)
        try await play(MediaRequest(contentId: "a", sources: [origin.sourceURL]), on: player)
        player.release()

        XCTAssertEqual(
            recorder.edges.map(\.description),
            [
                "attach", "start a (playing a)", "end (playing a)", "start b (playing b)",
                "end (playing b)", "start a (playing a)", "end (playing a)", "detach",
            ])
        let ids = recorder.sessionIds
        XCTAssertEqual(Set(ids).count, 3, "a session per adoption, the same content's included")
        XCTAssertTrue(ids.allSatisfy { UUID(uuidString: $0) != nil }, "\(ids) are not UUIDs")
    }

    func testContentWithNoIdentityEndsTheSessionAndOpensNone() async throws {
        let origin = try await harness.serve(.hls())
        let recorder = EdgeRecorder()
        let player = measuredPlayer(recorder)

        try await play(MediaRequest(contentId: "a", sources: [origin.sourceURL]), on: player)
        player.setMediaURL(origin.sourceURL)
        player.avPlayer.play()
        try await harness.until("the bare item plays", timeout: bound) {
            player.avPlayer.currentTime().seconds > 1
        }
        player.release()

        XCTAssertEqual(
            recorder.edges.map(\.description),
            ["attach", "start a (playing a)", "end (playing a)", "detach"])
    }

    /// The control on the release edge: a second release signals nothing, so "ends once" is a count
    /// and not a release that ends nothing.
    func testReleaseEndsTheOpenSessionOnceBeforeTheCollectorLetsGo() async throws {
        let origin = try await harness.serve(.hls())
        let recorder = EdgeRecorder()
        let player = measuredPlayer(recorder)

        try await play(MediaRequest(contentId: "a", sources: [origin.sourceURL]), on: player)
        player.release()
        player.release()
        player.setMediaRequest(MediaRequest(contentId: "b", sources: [origin.sourceURL]))

        XCTAssertEqual(
            recorder.edges.map(\.description),
            ["attach", "start a (playing a)", "end (playing a)", "detach"])
        XCTAssertNil(player.avPlayer.currentItem, "a released player takes no content")
    }

    private func measuredPlayer(_ recorder: EdgeRecorder) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { recorder }
        return SuperPlayer(configuration: configuration)
    }

    /// Sets `request` and waits for it to play past its first second.
    private func play(_ request: MediaRequest, on player: SuperPlayer) async throws {
        player.setMediaRequest(request)
        player.avPlayer.play()
        try await harness.until("\(request.contentId) plays", timeout: bound) {
            player.currentContentId == request.contentId
                && player.avPlayer.currentTime().seconds > 1
        }
    }
}

/// A collector that records each edge and what the player it watches was playing at that moment —
/// which is how the order "after the new item is the player's" and "before the old one goes" is
/// read.
@MainActor
private final class EdgeRecorder: TelemetryCollector {

    enum Edge: CustomStringConvertible {
        case attach
        case start(String, String, playing: String?)
        case end(playing: String?)
        case detach

        var description: String {
            switch self {
            case .attach: "attach"
            case .start(let content, _, let playing): "start \(content) (playing \(playing ?? "-"))"
            case .end(let playing): "end (playing \(playing ?? "-"))"
            case .detach: "detach"
            }
        }
    }

    private(set) var edges: [Edge] = []
    private weak var player: SuperPlayer?

    var sessionIds: [String] {
        edges.compactMap { if case .start(_, let id, _) = $0 { id } else { nil } }
    }

    func attach(to player: SuperPlayer) {
        self.player = player
        edges.append(.attach)
    }

    func startSession(contentId: String, sessionId: String) {
        edges.append(.start(contentId, sessionId, playing: player?.currentContentId))
    }

    func endSession() {
        edges.append(.end(playing: player?.currentContentId))
    }

    func detach() {
        edges.append(.detach)
    }
}
