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
import SuperPlayerTestkit
import XCTest

/// `PlaybackSnapshot` through the harness (#401): a snapshot taken from one player, carried through
/// `JSONEncoder` and `JSONDecoder`, and restored into a **new** player, puts it where the first had
/// got to — and the new player resumes, with `.resumeFromLastKnown`, content only the first had
/// played.
///
/// In testkit's target for `SuperPlayerPlaybackTests`' reason: it needs the phase 2 harness. Time is
/// real and what is asserted is a position, held to the windows argued at
/// ``startTolerance`` and ``frameTolerance``, never a duration.
@MainActor
final class SnapshotPlaybackTests: XCTestCase {

    /// The ceiling on every wait, never approached by a pass.
    private let bound: Duration = .seconds(60)

    /// How far past its position a player may first be seen playing: a poll and a main-actor turn
    /// between the player starting and the poll seeing it, still less than half the two-second
    /// segment between any two outcomes told apart here. `SuperPlayerPlaybackTests` reads a held
    /// start instead and needs no such window (#502).
    private let startTolerance = 1.0

    /// An AAC frame at the synthetic stream's rate, rounded up: a zero-tolerance seek lands on a
    /// frame boundary at or before the position.
    private let frameTolerance = 0.05

    /// Where played content is left: past the first segment, so the beginning and a resume
    /// position are a whole segment and more apart.
    private let leaveAfter = 2.5

    private let harness = PlaybackHarness()

    /// Every player a test made, emptied at its end so no load is left in flight behind it.
    private var players: [SuperPlayer] = []

    override func tearDown() async throws {
        for player in players {
            player.avPlayer.pause()
            player.avPlayer.replaceCurrentItem(with: nil)
        }
        harness.stop()
        try await super.tearDown()
    }

    /// The acceptance: two pieces of content played and left on one player, its snapshot through
    /// JSON, and a new player restored from it. The new player is at the second's position, paused
    /// as the first was left — and resumes the first, which it never played, where it was left.
    ///
    /// Two controls in the same test, because each needs the content already played: a new player
    /// that was *not* restored resumes the first from the beginning, so the resume is the snapshot's
    /// doing; and the restored player waits paused until it is told to play, so the intent restored
    /// is the one saved rather than "always play".
    func testARoundTripThroughJSONRestoresThePositionAndResumesInANewPlayer() async throws {
        let first = try await harness.serve(.hls())
        let second = try await harness.serve(.hls())
        let firstRequest = MediaRequest(contentId: "first", sources: [first.sourceURL])
        let original = makePlayer()
        let firstLeftAt = try await playAndLeave(original, firstRequest)
        let secondLeftAt = try await playAndLeave(
            original, MediaRequest(contentId: "second", sources: [second.sourceURL]))

        let stored = try JSONEncoder().encode(original.saveSnapshot())
        original.avPlayer.replaceCurrentItem(with: nil)
        let restored = makePlayer()
        restored.restoreSnapshot(try JSONDecoder().decode(PlaybackSnapshot.self, from: stored))

        XCTAssertEqual(restored.currentContentId, "second")
        // Readiness, under ADR-0021 rule 1's #401 addendum: a ready item that is still paused is
        // the restored intent, where a wait on time would only be a guess at when it could play.
        try await harness.until("the restored item is ready", timeout: bound) {
            restored.avPlayer.currentItem?.status == .readyToPlay
        }
        XCTAssertEqual(
            restored.avPlayer.timeControlStatus, .paused, "a snapshot taken paused restored playing"
        )
        assertStarted(at: try await firstPositionPlaying(restored), expected: secondLeftAt)

        restored.setMediaRequest(
            MediaRequest(
                contentId: "first", sources: [first.sourceURL], startPosition: .resumeFromLastKnown)
        )
        assertStarted(at: try await firstPositionPlaying(restored), expected: firstLeftAt)

        let unrestored = makePlayer()
        unrestored.setMediaRequest(
            MediaRequest(
                contentId: "first", sources: [first.sourceURL], startPosition: .resumeFromLastKnown)
        )
        assertStarted(at: try await firstPositionPlaying(unrestored), expected: 0)
    }

    /// A snapshot taken while playing restores a player that plays without being told to — the
    /// Kotlin `playWhenReady`, restored as the intent it is.
    func testASnapshotTakenPlayingRestoresPlaying() async throws {
        let origin = try await harness.serve(.hls())
        let original = makePlayer()
        original.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        original.avPlayer.play()
        try await harness.until("the position passes \(leaveAfter) s", timeout: bound) {
            original.avPlayer.currentTime().seconds > leaveAfter
        }

        let snapshot = original.saveSnapshot()
        original.avPlayer.replaceCurrentItem(with: nil)
        let restored = makePlayer()
        restored.restoreSnapshot(
            try JSONDecoder().decode(PlaybackSnapshot.self, from: JSONEncoder().encode(snapshot)))

        XCTAssertTrue(snapshot.playWhenReady)
        try await harness.until("the restored player plays", timeout: bound) {
            restored.avPlayer.timeControlStatus == .playing
        }
        assertStarted(
            at: restored.avPlayer.currentTime().seconds,
            expected: Double(snapshot.positionMs!) / 1_000)
    }

    private func makePlayer() -> SuperPlayer {
        let player = SuperPlayer()
        players.append(player)
        return player
    }

    /// Plays `request` past ``leaveAfter`` and pauses it, returning the position it was left at.
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
