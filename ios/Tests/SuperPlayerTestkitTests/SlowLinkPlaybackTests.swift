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

/// What `AVPlayer` does on a link too slow to carry a segment inside its wait — the same for a stock
/// player and a `SuperPlayer`, which is #474's finding: a `SuperPlayer` stalls on such a link
/// because AVFoundation does, and not because of anything it does at adoption.
///
/// AVFoundation abandons a media segment that has not arrived whole within about a target duration,
/// logging -12889 ("No response for media file in 2s" on this two-second stream), and asks for it
/// again from its first byte. Bytes flowing steadily do not extend the wait. So on a link slower
/// than a segment's bytes over a target duration — for a segment as long as its target, slower
/// than the stream itself — no segment ever arrives: a player that has not started never does, and
/// on 27.1 one that has stalls at the first segment it has to fetch on that link, for good. Seen on
/// the iOS 27.1 simulator with both players on the same link, and the never-starting half on CI's
/// 26.5; it is not a claim about a device. A started session on 26.5 was seen to ask for no segment
/// again inside a minute (`TraceStartPlaybackTests`), so there the wait may grow with the buffer.
///
/// #474's table is consistent with it — four of its stuck points were on a stretch below the rung
/// being fetched, each met at a different place because a slower simulator met it at a different
/// place — but it was not reproduced on 26.5, which no host here has; CI's run of these tests is
/// the check on that runtime. One row this does not explain: a stall on a link that went to nothing
/// for twelve seconds, waited on to end, unless that link held nothing at its end, which the issue
/// does not record.
///
/// The controls are links fast enough: both players play the stream having asked for its first
/// segment once, and on the benchmark's ladder a link that falls between two rungs carries both on
/// at the lower one.
@MainActor
final class SlowLinkPlaybackTests: XCTestCase {

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testAStockPlayerAsksForASegmentItCannotGetAgainAndNeverStarts() async throws {
        try await assertNeverStarts(on: Self.stock)
    }

    func testASuperPlayerAsksForASegmentItCannotGetAgainAndNeverStarts() async throws {
        try await assertNeverStarts(on: Self.superPlayer)
    }

    func testOnALinkFastEnoughBothPlayFromOneAskForTheFirstSegment() async throws {
        // One after the other, each built as it is played, so neither loads while the other plays.
        for build in [Self.stock, Self.superPlayer] {
            let origin = try await harness.serve(
                .videoHls(segmentCount: 30), network: try link(Self.fastEnough))
            let player = build(origin)
            defer { player.release() }
            player.avPlayer.play()
            try await harness.until("playback starts", timeout: StockPlayback.bound) {
                player.avPlayer.timeControlStatus == .playing
                    && player.avPlayer.currentTime().seconds > 0
            }
            XCTAssertEqual(VideoPlayback.attempts(at: VideoPlayback.firstSegmentPath, origin), 1)
        }
    }

    /// #474's own content: the benchmark's ladder, on a link that falls from fast to below its upper
    /// rung and stays above its lower one. Both players play on past the fall, because the lower
    /// rung's segments still arrive inside AVFoundation's wait.
    func testOnALadderALinkBelowTheUpperRungStillCarriesBothPlayersOnTheLower() async throws {
        let trace = try ThroughputTrace.Builder()
            .add(durationMs: Self.ladderFastStretchMs, bandwidthBps: 2_000_000, transport: .wifi)
            .add(durationMs: 1_000, bandwidthBps: Self.betweenTheRungsBps, transport: .wifi)
            .holdAtEnd()
            .build()
        for build in [Self.stock, Self.superPlayer] {
            let origin = try await harness.serve(Self.ladder, network: trace)
            let player = build(origin)
            defer { player.release() }
            player.avPlayer.play()
            try await harness.until("playback passes the fall", timeout: StockPlayback.bound) {
                player.avPlayer.currentTime().seconds > Self.pastTheFallSeconds
            }
        }
    }

    /// Plays on a link at ``tooSlow`` until the first segment has been asked for
    /// ``abandonedAttempts`` times, and holds that playback never started and that AVFoundation said
    /// why.
    private func assertNeverStarts(on build: @MainActor (LoopbackOrigin) -> Played) async throws {
        let origin = try await harness.serve(
            .videoHls(segmentCount: 30), network: try link(Self.tooSlow))
        let player = build(origin)
        defer { player.release() }
        player.avPlayer.play()
        try await harness.until("the first segment asked for again", timeout: StockPlayback.bound) {
            VideoPlayback.attempts(at: VideoPlayback.firstSegmentPath, origin)
                >= Self.abandonedAttempts
        }
        XCTAssertEqual(player.avPlayer.currentTime().seconds, 0)
        XCTAssertNotEqual(player.avPlayer.timeControlStatus, .playing)
        let abandoned = player.avPlayer.currentItem?.errorLog()?.events.filter {
            $0.errorDomain == "CoreMediaErrorDomain" && $0.errorStatusCode == -12889
        }
        XCTAssertFalse(abandoned?.isEmpty ?? true, "AVFoundation logged no -12889")
        // Every attempt was answered in full: the origin did not fail it, AVFoundation stopped
        // waiting for it.
        for response in origin.responses()
        where response.request.path == VideoPlayback.firstSegmentPath {
            XCTAssertEqual(response.status, 200)
        }
    }

    /// A quarter of the stream's own rate: a segment takes four target durations to arrive against
    /// AVFoundation's wait of about one, so no host is quick enough to land one.
    private static let tooSlow = 0.25

    /// Twice the stream's own rate: a segment arrives in half a target duration, the same factor of
    /// two the fault sweep's latency row keeps clear of that wait.
    private static let fastEnough = 2.0

    /// Three asks: the first and two abandoned ones, so the asking again is a pattern rather than
    /// one retry of a request that went wrong.
    private static let abandonedAttempts = 3

    /// The benchmark's two lowest rungs, as many segments as a minute of playing.
    private static let ladder = TestContent.videoLadder(
        bitratesBps: [365_000, 730_000], segmentCount: 30)

    /// Between the two rungs: below the upper's 730 kbit/s, and above the lower's 365 by more than a
    /// third, so a lower-rung segment arrives in three quarters of its target duration.
    private static let betweenTheRungsBps: Int64 = 500_000

    /// The ladder's fast stretch, from its first segment: room for a start and a climb before the
    /// fall, both of which took under two seconds on the simulator.
    private static let ladderFastStretchMs: Int64 = 6_000

    /// A position no session reaches unless it fetched on the slow link: the fast stretch and the
    /// ``forwardBufferSeconds`` it could have buffered by then, twice over.
    private static let pastTheFallSeconds: Double = 20

    /// How far ahead each item buffers: two of a stream's segments, so the fast stretch cannot fetch
    /// the whole stream and leave the slow link nothing to carry.
    private static let forwardBufferSeconds: TimeInterval = 4

    /// A link at `fraction` of the stream's own bitrate, from the start and for good.
    private func link(_ fraction: Double) throws -> ThroughputTrace {
        try ThroughputTrace.Builder()
            .add(
                durationMs: 1_000,
                bandwidthBps: Int64(Double(VideoPlayback.streamBitrateBps) * fraction),
                transport: .wifi
            )
            .holdAtEnd()
            .build()
    }

    /// A player of an origin's content, of either kind, and how to let go of it.
    private struct Played {
        let avPlayer: AVPlayer
        let release: () -> Void
    }

    private static func stock(_ origin: LoopbackOrigin) -> Played {
        let avPlayer = AVPlayer(url: origin.sourceURL)
        standInForAScreen(avPlayer)
        return Played(avPlayer: avPlayer) { avPlayer.replaceCurrentItem(with: nil) }
    }

    private static func superPlayer(_ origin: LoopbackOrigin) -> Played {
        let player = SuperPlayer(
            configuration: SuperPlayer.Configuration(),
            lifecycle: SharedLifecycle(platform: QuietPlatform()))
        player.setMediaRequest(MediaRequest(contentId: "slow-link", sources: [origin.sourceURL]))
        standInForAScreen(player.avPlayer)
        return Played(avPlayer: player.avPlayer) { player.release() }
    }

    /// An `AVPlayerItemVideoOutput` on the item, which is what consumes video in `xctest` — without
    /// one `AVPlayer` never leaves a ladder's first rung (`ios/Benchmark/README.md`) — and the short
    /// forward buffer, written alike on either kind of player (ADR-0021 rule 1's #474 addendum).
    private static func standInForAScreen(_ avPlayer: AVPlayer) {
        avPlayer.currentItem?.add(AVPlayerItemVideoOutput(pixelBufferAttributes: nil))
        avPlayer.currentItem?.preferredForwardBufferDuration = forwardBufferSeconds
    }
}
