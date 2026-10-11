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
import SuperPlayerPreload
import SuperPlayerTestkit
import UIKit
import XCTest

/// A scripted scroll over a feed whose every row is an origin of its own, so what was fetched for a
/// row is that origin's count — the port of `PreloadCoordinatorPlaybackTest`, `DecoderWarmupTest`
/// and `PreloadPlatformRulesTest`, through the public API and the coordinator's `package` seam for
/// the device it states (#417's acceptance).
///
/// Time is real and nothing here measures it: every wait is a bounded `until`, and a claim that
/// nothing was fetched is read after the playing row has played past a point by which the
/// coordinator's own control — the first test — has already fetched.
@MainActor
final class PreloadCoordinatorPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(60)

    /// Plenty of memory, stated: the pool's bound is its ceiling of four, and the memory guard
    /// admits the whole decided window, so what a test sees is the decision's and not the guard's.
    private let plentiful: @Sendable () -> UInt64 = { 4 * 1024 * 1024 * 1024 }

    /// How far the playing row plays before a test reads that a row was *not* fetched: past the
    /// point by which ``testAPrefetchedRowIsFetchedOnceAndNotAgainWhenItPlays`` sees its row
    /// prefetched, since the prefetch starts at the scroll, before the playing row's first request.
    private let quietAfter = 1.5

    private let harness = PlaybackHarness()

    /// Every coordinator and pool a test made, released however the test ended, so a failed wait
    /// leaves no registration behind for the register's process-wide count to find.
    private var releases: [() -> Void] = []

    override func tearDown() async throws {
        for release in releases { release() }
        releases = []
        harness.stop()
        try await super.tearDown()
    }

    // MARK: - Prefetch

    /// The acceptance's first count: a row prefetched ahead is fetched once — its multivariant
    /// and media playlists, by the coordinator — and not again when it plays. The row two ahead is
    /// outside `videoOnDemand`'s window of one, and is fetched by nobody, so the prefetch is not
    /// "fetch everything".
    func testAPrefetchedRowIsFetchedOnceAndNotAgainWhenItPlays() async throws {
        let feed = try await serveFeed(rows: 3)
        let (pool, preload) = coordinated(.videoOnDemand)
        preload.setItems(feed.requests)
        preload.setScrollPosition(index: 0)
        let first = try XCTUnwrap(pool.acquire())
        first.setMediaRequest(feed.requests[0])
        first.avPlayer.play()

        try await until("row 1's playlists are prefetched") { feed.manifests(1).count == 2 }
        try await until("row 0 plays past \(quietAfter) s") {
            first.avPlayer.currentTime().seconds > self.quietAfter
        }
        XCTAssertEqual(feed.origins[2].networkRequests(), [], "outside the window")

        first.avPlayer.pause()
        preload.setScrollPosition(index: 1)
        pool.recycle(first)
        let next = try XCTUnwrap(pool.acquire())
        next.setMediaRequest(feed.requests[1])
        try await play(next)

        XCTAssertEqual(feed.manifests(1).sorted(), ["/master.m3u8", "/media.m3u8"])
        XCTAssertEqual(next.currentContentId, "row-1", "played through setMediaRequest")
        preload.release()
        pool.release()
    }

    /// ADR-0010 rule 13's control: the same scroll on a pool with no coordinator fetches nothing
    /// ahead, and the row is fetched when it plays.
    func testAPoolWithoutACoordinatorFetchesNothingAhead() async throws {
        let feed = try await serveFeed(rows: 2)
        let pool = PlayerPool(
            configuration: poolConfiguration(.videoOnDemand), availableMemory: plentiful)
        releases.append { pool.release() }
        let first = try XCTUnwrap(pool.acquire())
        first.setMediaRequest(feed.requests[0])
        first.avPlayer.play()
        try await until("row 0 plays past \(quietAfter) s") {
            first.avPlayer.currentTime().seconds > self.quietAfter
        }

        XCTAssertEqual(feed.origins[1].networkRequests(), [])
        first.avPlayer.pause()
        pool.release()
    }

    // MARK: - Held players

    /// At `shortForm`'s depth the rows ahead are held on the pool's idle players, and the row the
    /// feed moves to is handed the player holding it: its item is already there, `setMediaRequest`
    /// keeps that very item, and the row's playlists are not fetched again.
    func testTheRowAheadIsHandedThePlayerHoldingIt() async throws {
        let feed = try await serveFeed(rows: 4)
        let (pool, preload) = coordinated(.shortForm)
        preload.setItems(feed.requests)
        preload.setScrollPosition(index: 0)
        let playing = try idleTwoAndAcquire(pool)
        playing.setMediaRequest(feed.requests[0])

        try await until("two rows are held") { preload.warmDecoderCount == 2 }
        try await until("row 1 has loaded media") { feed.segments(1) > 0 }
        try await until("a held player is prerolled") { preload.prerollsAsked > 0 }
        XCTAssertLessThanOrEqual(
            preload.warmDecoderCount, pool.maxSize - pool.inUseCount, "the pool's bound")

        preload.setScrollPosition(index: 1)
        let next = try XCTUnwrap(pool.acquire())
        let heldItem = try XCTUnwrap(next.avPlayer.currentItem, "the player holding row 1")
        XCTAssertNil(next.currentContentId, "a held row reports nothing until it is adopted")
        next.setMediaRequest(feed.requests[1])
        XCTAssertTrue(next.avPlayer.currentItem === heldItem, "adoption keeps the held item")
        XCTAssertEqual(next.currentContentId, "row-1")
        try await play(next)

        XCTAssertEqual(feed.manifests(1).sorted(), ["/master.m3u8", "/media.m3u8"])
        preload.release()
        pool.release()
    }

    /// At a loaded depth the rows ahead are held and load media, and no player is prerolled: the
    /// control that keeps the preroll above from being every held row's.
    func testALoadedDepthHoldsRowsWithoutPrerollingThem() async throws {
        let feed = try await serveFeed(rows: 4)
        let (pool, preload) = coordinated(.shortForm, policy: LoadedDepth())
        preload.setItems(feed.requests)
        preload.setScrollPosition(index: 0)
        let playing = try idleTwoAndAcquire(pool)
        playing.setMediaRequest(feed.requests[0])

        try await until("two rows are held") { preload.warmDecoderCount == 2 }
        try await until("row 1 has loaded media") { feed.segments(1) > 0 }
        XCTAssertEqual(preload.prerollsAsked, 0)
    }

    /// The control: at `videoOnDemand`'s depth nothing loads media ahead, so nothing is held and the
    /// player acquired for the next row is a reset one.
    func testBelowALoadedDepthNoPlayerIsHeld() async throws {
        let feed = try await serveFeed(rows: 3)
        let (pool, preload) = coordinated(.videoOnDemand)
        preload.setItems(feed.requests)
        preload.setScrollPosition(index: 0)
        let playing = try idleTwoAndAcquire(pool)
        playing.setMediaRequest(feed.requests[0])
        try await until("row 1's playlists are prefetched") { feed.manifests(1).count == 2 }

        XCTAssertEqual(preload.warmDecoderCount, 0)
        preload.setScrollPosition(index: 1)
        XCTAssertNil(try XCTUnwrap(pool.acquire()).avPlayer.currentItem)
        preload.release()
        pool.release()
    }

    // MARK: - Platform rules

    /// A memory warning lets every held player go, and what was prefetched with them, and nothing
    /// is fetched again until the feed moves: the row, played after the warning, fetches its
    /// playlists a second time.
    func testAMemoryWarningReleasesWhatWasPrefetched() async throws {
        let feed = try await serveFeed(rows: 4)
        let (pool, preload) = coordinated(.shortForm)
        preload.setItems(feed.requests)
        preload.setScrollPosition(index: 0)
        let playing = try idleTwoAndAcquire(pool)
        playing.setMediaRequest(feed.requests[0])
        try await until("two rows are held") { preload.warmDecoderCount == 2 }
        try await until("row 1's playlists are prefetched") { feed.manifests(1).count == 2 }

        NotificationCenter.default.post(
            name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
        try await until("every held player is let go") { preload.warmDecoderCount == 0 }

        // Nothing is fetched again until the feed moves, and the playing row plays on.
        playing.avPlayer.play()
        try await until("row 0 plays past \(quietAfter) s") {
            playing.avPlayer.currentTime().seconds > self.quietAfter
        }
        XCTAssertEqual(feed.manifests(1).count, 2, "nothing fetched before the feed moves")
        XCTAssertEqual(preload.warmDecoderCount, 0)
        XCTAssertEqual(playing.currentContentId, "row-0", "the playing row is not released")
        playing.avPlayer.pause()

        preload.setScrollPosition(index: 1)
        let next = try XCTUnwrap(pool.acquire())
        next.setMediaRequest(feed.requests[1])
        try await play(next)
        XCTAssertEqual(feed.manifests(1).filter { $0 == "/master.m3u8" }.count, 2)
        preload.release()
        pool.release()
    }

    /// Low Data Mode prefetches nothing, and leaving it is applied at the path change, with no
    /// scroll: the row ahead is fetched then.
    func testAConstrainedPathPrefetchesNothingUntilItChanges() async throws {
        let feed = try await serveFeed(rows: 2)
        let (pool, preload) = coordinated(.videoOnDemand)
        preload.pathChanged(isConstrained: true)
        preload.setItems(feed.requests)
        preload.setScrollPosition(index: 0)
        let first = try XCTUnwrap(pool.acquire())
        first.setMediaRequest(feed.requests[0])
        first.avPlayer.play()
        try await until("row 0 plays past \(quietAfter) s") {
            first.avPlayer.currentTime().seconds > self.quietAfter
        }
        XCTAssertEqual(feed.origins[1].networkRequests(), [])

        preload.pathChanged(isConstrained: false)
        try await until("row 1's playlists are prefetched") { feed.manifests(1).count == 2 }
        first.avPlayer.pause()
        preload.release()
        pool.release()
    }

    // MARK: - The feed

    private struct Feed {
        let origins: [LoopbackOrigin]
        let requests: [MediaRequest]

        func manifests(_ row: Int) -> [String] {
            origins[row].networkRequests().filter { $0.kind == .manifest }.map(\.path)
        }

        func segments(_ row: Int) -> Int {
            origins[row].networkRequests().filter { $0.kind == .mediaSegment }.count
        }
    }

    private func serveFeed(rows: Int) async throws -> Feed {
        var origins: [LoopbackOrigin] = []
        for _ in 0..<rows { origins.append(try await harness.serve(.hls())) }
        let requests = origins.enumerated().map { row, origin in
            MediaRequest(contentId: "row-\(row)", sources: [origin.sourceURL])
        }
        return Feed(origins: origins, requests: requests)
    }

    private func poolConfiguration(_ profile: PlaybackProfile) -> PlayerPool.Configuration {
        var configuration = PlayerPool.Configuration()
        configuration.player.profile = profile
        return configuration
    }

    /// A pool of four under `profile`, with a coordinator attached before its first player that
    /// reads the stated memory and watches no real path.
    private func coordinated(_ profile: PlaybackProfile, policy: (any PlaybackPolicy)? = nil)
        -> (PlayerPool, PreloadCoordinator)
    {
        var configuration = poolConfiguration(profile)
        configuration.player.policy = policy
        let pool = PlayerPool(configuration: configuration, availableMemory: plentiful)
        let preload = PreloadCoordinator(
            pool: pool, availableMemory: plentiful, watchesNetworkPath: false)
        releases.append {
            preload.release()
            pool.release()
        }
        return (pool, preload)
    }

    /// `shortForm`'s decision with its depth loaded rather than warmed: the control that tells a
    /// held row from a prerolled one.
    private struct LoadedDepth: PlaybackPolicy {
        func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
            var decision = PlaybackProfile.shortForm.policy.decide(conditions)
            decision.preload.depth = .loaded(durationMs: 1_000)
            return decision
        }
    }

    /// Makes three players and hands two back, so the pool has two idle players the coordinator
    /// may hold rows on — it never asks the pool to make one — and returns the third, for the
    /// playing row.
    private func idleTwoAndAcquire(_ pool: PlayerPool) throws -> SuperPlayer {
        let players = try (0..<3).map { _ in try XCTUnwrap(pool.acquire()) }
        pool.recycle(players[1])
        pool.recycle(players[2])
        return players[0]
    }

    private func play(_ player: SuperPlayer) async throws {
        player.avPlayer.play()
        try await until("the row plays") { player.avPlayer.currentTime().seconds > 0.5 }
        player.avPlayer.pause()
    }

    private func until(_ condition: String, _ holds: () -> Bool) async throws {
        try await harness.until(condition, timeout: bound, holds)
    }
}
