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
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest

/// The `sid` join `docs/telemetry-schema.md` promises, held on iOS (#446): what the origin saw on
/// the wire against what the sink was handed, for one real `SuperPlayer` measured by a real
/// `QoeCollector` — the Kotlin `SuperPlayerCmcdTest`'s claim, with the direction reversed, since
/// here the id is read off the asset rather than minted (`CmcdBinding.swift`).
///
/// Here rather than in core's tests because the claim is about *every event*, and events are
/// `QoeCollector`'s. Every observation of the wire is the origin's (ADR-0021 rule 2), and nothing
/// reads the asset: the join is asserted as the CDN would see it, an equality of two recorded
/// strings. The CMCD tests skip below iOS 18 and tvOS 18, where the SDK has no switch — the one gate
/// `CmcdFindingTests` has too; the disabled test does not, since `X-Playback-Session-Id` is sent
/// everywhere. Each test plays its last content to the end before releasing, so no load is left in
/// flight for AVFoundation to tear down after it.
@MainActor
final class CmcdPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(90)
    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    /// Every request of the session carries `sid` equal to the `sessionId` on every event of it —
    /// manifests and segments, by default and with nothing configured but a collector.
    func testEveryRequestCarriesTheSessionIdOfEveryEventAsSid() async throws {
        try Self.requireCmcd()
        let origin = try await harness.serve(.hls())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await playToEnd(player)
        player.release()
        try await waitForSessionEnds(sink, count: 1)

        let sessionIds = Set(sink.events.map(\.sessionId))
        XCTAssertEqual(sessionIds.count, 1, "one session's events: \(sink.kinds)")
        let sessionId = try XCTUnwrap(sessionIds.first)
        let requests = origin.networkRequests()
        XCTAssertTrue(requests.contains { $0.kind == .mediaSegment }, "\(requests)")
        for request in requests {
            XCTAssertEqual(Self.sid(request), sessionId, "\(request)")
            XCTAssertEqual(request.headers["x-playback-session-id"], sessionId, "\(request)")
        }
    }

    /// Two adoptions are two sessions under two `sid`s, each its own session's — the same content
    /// twice, so it is the adoption that makes the session and not the content. This is what a fresh
    /// asset per adoption buys: a reused asset would vend its identifier again.
    func testTwoAdoptionsAreTwoSidsEachEqualToItsOwnSessionsId() async throws {
        try Self.requireCmcd()
        let origin = try await harness.serve(.hls())
        let sink = RecordingSink()
        let player = measuredPlayer(sink)

        try await play("a", from: origin, on: player)
        let firstRequests = origin.networkRequests().count
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await playToEnd(player)
        player.release()
        try await waitForSessionEnds(sink, count: 2)

        let started = sink.events.compactMap(\.started).map(\.sessionId)
        XCTAssertEqual(started.count, 2, "\(sink.kinds)")
        XCTAssertEqual(Set(started).count, 2, "two adoptions, two ids: \(started)")
        let requests = origin.networkRequests()
        // The second adoption's first request is a manifest under the second id; everything the
        // origin saw is under one of the two, and each id reached the wire.
        XCTAssertEqual(Set(requests.map(Self.sid)), Set(started.map { Optional($0) }))
        let second = requests[firstRequests...].first { $0.kind == .manifest }
        XCTAssertEqual(second.flatMap(Self.sid), started[1], "\(requests)")
        for sessionId in started {
            let events = sink.events.filter { $0.sessionId == sessionId }
            XCTAssertEqual(events.compactMap(\.started).count, 1, "\(sessionId): \(sink.kinds)")
            XCTAssertEqual(events.compactMap(\.ended).count, 1, "\(sessionId): \(sink.kinds)")
        }
    }

    /// The control: ``CmcdMode/disabled`` sends no `CMCD-*` header at all, so the keys above are
    /// the mode's doing — and the session is still named on the wire by `X-Playback-Session-Id`,
    /// which is the telemetry id here too. Not gated on 18: there is no switch to leave off below it.
    func testDisabledSendsNoCmcdAndStillNamesTheSessionInXPlaybackSessionId() async throws {
        let origin = try await harness.serve(.hls())
        let sink = RecordingSink()
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: sink) }
        try configuration.setCmcdMode(.disabled)
        let player = SuperPlayer(configuration: configuration)

        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await playToEnd(player)
        player.release()
        try await waitForSessionEnds(sink, count: 1)

        let sessionId = try XCTUnwrap(sink.events.compactMap(\.started).first?.sessionId)
        let requests = origin.networkRequests()
        XCTAssertFalse(requests.isEmpty)
        for request in requests {
            XCTAssertTrue(
                request.headers.keys.allSatisfy { !$0.hasPrefix("cmcd-") }, "\(request)")
            XCTAssertNil(request.query, "\(request)")
            XCTAssertEqual(request.headers["x-playback-session-id"], sessionId, "\(request)")
        }
    }

    // MARK: - Helpers

    /// Skips where `sendsCommonMediaClientDataAsHTTPHeaders` does not exist.
    private static func requireCmcd() throws {
        guard #available(iOS 18, tvOS 18, *) else {
            throw XCTSkip("AVFoundation sends CMCD from iOS 18 and tvOS 18")
        }
    }

    /// `sid` as `request` carried it, unquoted (spec: CTA-5004 §3.1 sends a string-valued key
    /// quoted), or nil. Split on commas, which a `CMCD-Session` value never holds unquoted.
    private static func sid(_ request: NetworkRequest) -> String? {
        guard let session = request.headers["cmcd-session"] else { return nil }
        for pair in session.split(separator: ",") where pair.hasPrefix("sid=") {
            return String(pair.dropFirst("sid=".count)).trimmingCharacters(
                in: CharacterSet(charactersIn: "\""))
        }
        return nil
    }

    private func measuredPlayer(_ sink: RecordingSink) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: sink) }
        return SuperPlayer(configuration: configuration)
    }

    /// Sets `contentId`'s request and waits for it to play past its first second.
    private func play(
        _ contentId: String, from origin: LoopbackOrigin, on player: SuperPlayer
    ) async throws {
        player.setMediaRequest(MediaRequest(contentId: contentId, sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("\(contentId) plays", timeout: bound) {
            player.currentContentId == contentId && player.avPlayer.currentTime().seconds > 1
        }
    }

    private func playToEnd(_ player: SuperPlayer) async throws {
        let item = try XCTUnwrap(player.avPlayer.currentItem)
        let ended = EndObserver(item)
        try await harness.until("the content plays to its end", timeout: bound) { ended.hasEnded }
    }

    /// Waits for `count` sessions' ends to have been delivered: delivery is asynchronous.
    private func waitForSessionEnds(_ sink: RecordingSink, count: Int) async throws {
        try await harness.until("\(count) session end delivered", timeout: bound) {
            sink.events.compactMap(\.ended).count >= count
        }
    }
}
