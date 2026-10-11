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

/// A pooled player whose content really played, recycled (#404): it carries no item and no
/// position memory from the last user, read through the public API — the content it played,
/// requested again with `.resumeFromLastKnown` on the very same player, starts at the beginning.
/// The control is the same steps without the recycle, which resume where the content was left.
///
/// In testkit's target for `SuperPlayerPlaybackTests`' reason: it needs the phase 2 harness. The
/// register's count of observations is `PlayerPoolTests`', in core's target, where an item that
/// never loads is enough to make a registration.
///
/// Time is real and nothing here measures it; a start is a position read at the first poll that
/// finds the player playing, held to the tolerances `SuperPlayerPlaybackTests` argues.
@MainActor
final class PlayerPoolPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(60)

    /// `SuperPlayerPlaybackTests.startTolerance` and `frameTolerance`, for their reasons.
    private let startTolerance = 1.0
    private let frameTolerance = 0.05

    /// Past the first two-second segment, so the beginning and the resume position are a whole
    /// segment and more apart.
    private let leaveAfter = 2.5

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    /// A pool of one, stated rather than read, so the player acquired after a recycle can only be
    /// the one recycled.
    private func poolOfOne() -> PlayerPool {
        PlayerPool(configuration: PlayerPool.Configuration(), availableMemory: { 0 })
    }

    func testARecycledPlayerResumesNothingTheLastUserPlayed() async throws {
        let origin = try await harness.serve(.hls())
        let pool = poolOfOne()
        let player = try XCTUnwrap(pool.acquire())
        let request = MediaRequest(contentId: "a", sources: [origin.sourceURL])
        _ = try await playAndLeave(player, request)

        pool.recycle(player)
        XCTAssertNil(player.avPlayer.currentItem, "the last user's item")
        XCTAssertNil(player.currentContentId)
        let reused = try XCTUnwrap(pool.acquire())
        XCTAssertTrue(reused === player, "a pool of one hands back the player it was given")
        reused.setMediaRequest(resuming(request))
        let resumed = try await firstPositionPlaying(reused)

        assertStarted(at: resumed, expected: 0)
        reused.avPlayer.pause()
        pool.release()
    }

    /// The control: the same steps with no recycle between resume where the content was left, so
    /// the test above is the reset's doing and not a resume that never works.
    func testWithoutTheRecycleTheSamePlayerResumes() async throws {
        let origin = try await harness.serve(.hls())
        let pool = poolOfOne()
        let player = try XCTUnwrap(pool.acquire())
        let request = MediaRequest(contentId: "a", sources: [origin.sourceURL])
        let leftAt = try await playAndLeave(player, request)

        player.setMediaRequest(resuming(request))
        let resumed = try await firstPositionPlaying(player)

        assertStarted(at: resumed, expected: leftAt)
        player.avPlayer.pause()
        pool.release()
    }

    private func resuming(_ request: MediaRequest) -> MediaRequest {
        MediaRequest(
            contentId: request.contentId, sources: request.sources,
            startPosition: .resumeFromLastKnown)
    }

    /// Plays `request` past ``leaveAfter`` and pauses it, returning where it was left.
    private func playAndLeave(_ player: SuperPlayer, _ request: MediaRequest) async throws
        -> Double
    {
        player.setMediaRequest(request)
        player.avPlayer.play()
        try await harness.until("the position passes \(leaveAfter) s", timeout: bound) {
            player.avPlayer.currentTime().seconds > leaveAfter
        }
        player.avPlayer.pause()
        return player.avPlayer.currentTime().seconds
    }

    /// Starts the current item and returns its position at the first poll that finds it playing.
    private func firstPositionPlaying(_ player: SuperPlayer) async throws -> Double {
        player.avPlayer.play()
        try await harness.until("the player is playing", timeout: bound) {
            player.avPlayer.timeControlStatus == .playing
        }
        return player.avPlayer.currentTime().seconds
    }

    private func assertStarted(
        at position: Double, expected: Double, file: StaticString = #filePath, line: UInt = #line
    ) {
        XCTAssertTrue(
            (expected - frameTolerance...expected + startTolerance).contains(position),
            "first seen playing at \(position) s, expected \(expected) s", file: file, line: line)
    }
}
