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
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest
import os

/// A stock `AVPlayer` plays from the harness's origin — on-demand and live — to an advancing
/// position, and what it fetched is counted by kind: #399's acceptance, through the public API.
///
/// Time is real (ADR-0021 rule 4), so every wait is ``PlaybackHarness/until(_:timeout:isolation:_:)``
/// with a bound a pass never approaches, and every assertion is a position passed, a path fetched
/// or a count — never how long any of it took.
@MainActor
final class StockPlayerOriginTests: XCTestCase {

    /// A ceiling for media that plays at 1× on a loaded CI host: several times the few seconds of
    /// media a condition here waits for, and only a bound.
    private let bound: Duration = .seconds(60)

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testAStockPlayerPlaysOnDemandHlsToAnAdvancingPositionAndFetchesEachResourceItNeeds()
        async throws
    {
        let origin = try await harness.serve(.hls(segmentCount: 3))
        let player = AVPlayer(url: origin.sourceURL)
        player.play()

        try await harness.until("the item is ready to play", timeout: bound) {
            player.currentItem?.status == .readyToPlay
        }
        let readyAt = player.currentTime().seconds
        // Past the first segment's end, so a second segment was fetched and played into.
        try await harness.until("the position passes the first segment", timeout: bound) {
            player.currentTime().seconds > readyAt + 2.5
        }
        player.pause()

        let requests = origin.networkRequests()
        XCTAssertEqual(
            Set(requests.filter { $0.kind == .manifest }.map(\.path)),
            ["/master.m3u8", "/media.m3u8"], "\(requests)")
        // The control on the counting: every segment fetched is one the stream has, and the first
        // two were fetched — a count that read every request as one kind, or dropped a path, fails.
        let segments = Set(requests.filter { $0.kind == .mediaSegment }.map(\.path))
        XCTAssertTrue(
            segments.isSubset(of: ["/segment0.aac", "/segment1.aac", "/segment2.aac"]),
            "\(segments)")
        XCTAssertTrue(segments.isSuperset(of: ["/segment0.aac", "/segment1.aac"]), "\(segments)")
        XCTAssertFalse(requests.contains { $0.kind == .initialization }, "\(requests)")
    }

    /// The live origin keeps publishing: the player reloads the media playlist, fetches a segment
    /// that did not exist when it started, and its position advances by more than a segment.
    func testAStockPlayerPlaysLiveHlsWhileTheOriginKeepsPublishing() async throws {
        let origin = try await harness.serve(.liveHls())
        let player = AVPlayer(url: origin.sourceURL)
        player.play()

        try await harness.until("the item is ready to play", timeout: bound) {
            player.currentItem?.status == .readyToPlay
        }
        let readyAt = player.currentTime().seconds
        // The window starts with segments 0 to 5 published, so segment 6 is the first published
        // after the player started — one segment duration of real time later.
        let firstUnpublished =
            "/live/"
            + SyntheticHlsStream.segmentName(SyntheticHlsStream.liveWindowSegmentCount)
        try await harness.until("a segment published after the start is fetched", timeout: bound) {
            origin.networkRequests().contains { $0.path == firstUnpublished }
        }
        // Compared with where it stood when ready rather than with a segment's timestamp, since
        // where an undated live item's timeline starts is the engine's to choose.
        try await harness.until("the position advances past a segment", timeout: bound) {
            player.currentTime().seconds > readyAt + 2.5
        }
        player.pause()

        XCTAssertEqual(player.currentItem?.status, .readyToPlay)
        XCTAssertFalse(
            player.currentItem?.duration.isNumeric ?? true, "a live item has no finite duration")
        let mediaPlaylistLoads = origin.networkRequests().filter {
            $0.path == "/live/" + SyntheticHlsStream.mediaPlaylistName
        }
        XCTAssertGreaterThan(mediaPlaylistLoads.count, 1, "the live playlist was never reloaded")
    }

    /// ADR-0021's *Context* records, as an observation for this ticket to confirm, that
    /// AVFoundation's media loads do not go through an app's registered `URLProtocol`s — the reason
    /// the harness is a server and not the ordinary Apple fake. Confirmed here, with the origin's
    /// count as the control that the loads happened at all.
    func testAStockPlayersLoadsDoNotPassThroughARegisteredURLProtocol() async throws {
        XCTAssertTrue(URLProtocol.registerClass(ObservingURLProtocol.self))
        defer { URLProtocol.unregisterClass(ObservingURLProtocol.self) }
        ObservingURLProtocol.reset()
        let origin = try await harness.serve(.hls(segmentCount: 3))
        let player = AVPlayer(url: origin.sourceURL)
        player.play()

        try await harness.until("a segment is fetched", timeout: bound) {
            origin.networkRequests().contains { $0.kind == .mediaSegment }
        }
        player.pause()

        XCTAssertEqual(ObservingURLProtocol.consulted(), [])
        // The control that the protocol sees what it is registered for: a URLSession load of the
        // same origin is offered to it — as often as the loading system asks, which is not once.
        _ = try? await URLSession.shared.data(from: origin.url(path: "master.m3u8"))
        XCTAssertEqual(Set(ObservingURLProtocol.consulted()), ["/master.m3u8"])
    }
}

/// A `URLProtocol` that records every request it is offered and handles none, so registering it
/// changes nothing about where a load goes.
private final class ObservingURLProtocol: URLProtocol {
    private static let offered = OSAllocatedUnfairLock(initialState: [String]())

    static func reset() { offered.withLock { $0 = [] } }
    static func consulted() -> [String] { offered.withLock { $0 } }

    override class func canInit(with request: URLRequest) -> Bool {
        if let url = request.url, url.host == "127.0.0.1" {
            offered.withLock { $0.append(url.path) }
        }
        return false
    }
}
