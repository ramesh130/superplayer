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

/// `TestContent.videoLadder` and `liveVideoLadder` played by a stock `AVPlayer`: the content the
/// benchmark's matrix runs over (#414), held to the two things the benchmark needs of it — that
/// every rung costs what it declares, and that `AVPlayer` chooses among the rungs on a link.
///
/// The control is the link: the same ladder on a link too slow for its top rung plays somewhere
/// below it, and on one fast enough for it plays at the top, so the choice is the player's reading
/// of the link rather than a rung it was always going to take.
@MainActor
final class VideoLadderPlaybackTests: XCTestCase {

    private let harness = PlaybackHarness()
    private let ladder = [365_000, 730_000, 2_000_000, 4_500_000]

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    /// Every segment of every rung that was fetched was sent whole at its rung's declared size.
    func testEverySegmentFetchedIsAsLargeAsItsRungDeclares() async throws {
        let origin = try await harness.serve(
            .videoLadder(bitratesBps: ladder, segmentCount: 6),
            network: NetworkProfile.stableWifi.trace)

        let player = try await playPast(seconds: 4, origin)
        player.pause()
        player.replaceCurrentItem(with: nil)

        let segments = origin.responses().filter {
            $0.request.kind == .mediaSegment && $0.request.method == "GET"
                && $0.request.headers["range"] == nil
        }
        XCTAssertFalse(segments.isEmpty)
        for response in segments {
            let path = response.request.path
            let rung = try XCTUnwrap(
                ladder.indices.first { path.hasPrefix("/ladder/rung\($0)/") }, path)
            XCTAssertEqual(
                response.bodyBytesSent,
                SyntheticVideoLadder.segmentBytes(bitrateBps: ladder[rung]), path)
        }
    }

    /// On 3G's megabit the top rung is out of reach; on stable WiFi's twenty it is not. Read off
    /// what the origin was asked for rather than the access log, whose `indicatedBitrate` was seen
    /// to read -1 on an entry the player did play.
    func testThePlayerChoosesARungTheLinkCanCarry() async throws {
        let top = ladder.count - 1
        let slow = try await harness.serve(
            .videoLadder(bitratesBps: ladder, segmentCount: 10),
            network: NetworkProfile.threeG.trace)
        let slowPlayer = try await playPast(seconds: 6, slow)
        slowPlayer.pause()
        slowPlayer.replaceCurrentItem(with: nil)

        let fast = try await harness.serve(
            .videoLadder(bitratesBps: ladder, segmentCount: 30),
            network: NetworkProfile.stableWifi.trace)
        let fastPlayer = try await playPast(seconds: 2, fast)
        try await harness.until("a top-rung segment on stable WiFi", timeout: StockPlayback.bound) {
            Self.rungs(fast).contains(top)
        }
        fastPlayer.pause()
        fastPlayer.replaceCurrentItem(with: nil)

        XCTAssertLessThan(try XCTUnwrap(Self.rungs(slow).last), top, "\(Self.rungs(slow))")
    }

    /// The rung of every media segment `origin` was asked for, in order.
    private static func rungs(_ origin: LoopbackOrigin) -> [Int] {
        origin.networkRequests().filter { $0.kind == .mediaSegment }.compactMap { request in
            request.path.split(separator: "/").dropFirst().first.flatMap {
                Int($0.dropFirst("rung".count))
            }
        }
    }

    /// The live ladder plays from its moving window and lists no end.
    func testTheLiveLadderPlaysFromItsWindow() async throws {
        let origin = try await harness.serve(.liveVideoLadder(bitratesBps: ladder))
        let player = try await playPast(seconds: 2, origin)
        let duration = try XCTUnwrap(player.currentItem?.duration)
        player.pause()
        player.replaceCurrentItem(with: nil)

        XCTAssertTrue(duration.isIndefinite, "a live item has no duration")
        XCTAssertTrue(
            origin.networkRequests().contains {
                $0.path.hasPrefix("/live-ladder/rung") && $0.kind == .mediaSegment
            })
    }

    /// Plays `origin` until the clock has run `seconds` past where it first ran: a live item's
    /// first reading is its window's edge, not zero.
    ///
    /// With an `AVPlayerItemVideoOutput` on the item, which is what stands in for a screen: in
    /// `xctest` no layer is ever in a window, and `AVPlayer` with nobody consuming its video was
    /// seen on the iOS 27.1 simulator to stay on the first-listed rung however fast the link.
    private func playPast(seconds: Double, _ origin: LoopbackOrigin) async throws -> AVPlayer {
        let item = AVPlayerItem(url: origin.sourceURL)
        item.add(AVPlayerItemVideoOutput(pixelBufferAttributes: nil))
        let player = AVPlayer(playerItem: item)
        player.play()
        var start: Double?
        try await harness.until("playback passes \(seconds) s", timeout: StockPlayback.bound) {
            guard player.timeControlStatus == .playing else { return false }
            let now = player.currentTime().seconds
            start = start ?? now
            return now - start! > seconds
        }
        return player
    }
}
