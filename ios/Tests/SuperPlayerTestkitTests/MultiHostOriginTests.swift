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

/// A second listener as a second host (ADR-0021 rule 2): content served on two hosts, a fault
/// addressed at one of them, and a stock `AVPlayer` moving to the other — the failover the
/// fallback ladder's host rung is about, here only as the harness that will let i5 force it.
@MainActor
final class MultiHostOriginTests: XCTestCase {

    /// A ceiling for media that plays at 1× on a loaded CI host, and only a bound.
    private let bound: Duration = .seconds(60)

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testAPrimaryHostThatRefusesItsPlaylistLeavesAStockPlayerOnTheSecond() async throws {
        let origin = try await harness.serve(
            .hls(hosts: 2),
            faults: FaultScript.Builder()
                .failWithHttpStatus(503, kind: .manifest, index: 1, host: 0).build())
        try await playToEnd(origin)

        let requests = origin.networkRequests()
        XCTAssertEqual(origin.hosts, 2)
        XCTAssertTrue(
            origin.responses().filter { $0.request.host == 0 && $0.request.path == "/media.m3u8" }
                .allSatisfy { $0.status == 503 })
        XCTAssertTrue(requests.contains { $0.host == 1 && $0.path == "/media.m3u8" }, "\(requests)")
        XCTAssertTrue(requests.contains { $0.host == 1 && $0.kind == .mediaSegment }, "\(requests)")
        // The fault addressed host 0 alone: nothing the second host answered was faulted.
        XCTAssertTrue(
            origin.responses().filter { $0.request.host == 1 }.allSatisfy { $0.faults.isEmpty })
    }

    /// The control: with nothing refused, the player stays on the first host, so moving above is
    /// the fault's doing and not a player that spreads its loads.
    func testWithNothingRefusedAStockPlayerStaysOnTheFirstHost() async throws {
        let origin = try await harness.serve(.hls(hosts: 2))
        try await playToEnd(origin)

        let requests = origin.networkRequests()
        XCTAssertTrue(requests.contains { $0.kind == .mediaSegment })
        XCTAssertTrue(requests.allSatisfy { $0.host == 0 }, "\(requests)")
        // And the playlist it read named both hosts, the second as an absolute URL.
        let playlist = try await Self.fetch(origin.url(path: "master.m3u8"))
        XCTAssertTrue(playlist.contains(origin.url(path: "media.m3u8", host: 1).absoluteString))
    }

    /// Two hosts of one live stream publish one window: what each lists at a moment is the same.
    /// Read A, B, then A again until the two reads of A agree — so no segment was published across
    /// the three — and then B, read between them, must agree too.
    func testTwoHostsOfALiveStreamPublishOneWindow() async throws {
        let origin = try await harness.serve(.liveHls(hosts: 2))
        let path = "live/" + SyntheticHlsStream.mediaPlaylistName
        nonisolated(unsafe) var agreed: (String, String)?
        try await harness.until("three reads fall inside one publication", timeout: bound) {
            let first = try await Self.fetch(origin.url(path: path, host: 0))
            let second = try await Self.fetch(origin.url(path: path, host: 1))
            let third = try await Self.fetch(origin.url(path: path, host: 0))
            guard first == third else { return false }
            agreed = (first, second)
            return true
        }
        let (primary, secondary) = try XCTUnwrap(agreed)

        XCTAssertEqual(secondary, primary)
        XCTAssertTrue(primary.contains("#EXT-X-MEDIA-SEQUENCE"))
    }

    private func playToEnd(_ origin: LoopbackOrigin) async throws {
        let item = AVPlayerItem(url: origin.sourceURL)
        let player = AVPlayer(playerItem: item)
        let ended = EndObserver(item)
        player.play()
        try await harness.until("the stream plays to its end", timeout: bound) { ended.hasEnded }
        player.pause()
    }

    private nonisolated static func fetch(_ url: URL) async throws -> String {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let (data, _) = try await session.data(from: url)
        return String(decoding: data, as: UTF8.self)
    }
}
