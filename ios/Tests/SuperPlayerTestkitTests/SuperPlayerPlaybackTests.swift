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

/// `SuperPlayer`'s content identity through the harness (#400): a request plays, a start position
/// is where it starts, content resumes where it was left, the memory of where is bounded, and an
/// item with no identity resumes nothing.
///
/// Core's tests, living in testkit's target for the reason `SyntheticHlsPlaybackTests` does: they
/// need the phase 2 harness, and `SuperPlayerCoreTests` takes core's phase 1 (`docs/modules.md`).
/// The Kotlin core tests have a harness of core's own; an iOS one would be a second loopback origin.
///
/// **Time is real, and what is asserted is a position, never a duration.** Each start is read at
/// the first poll that finds the player playing, and held to a window argued once, at
/// ``startTolerance``. Playback is started through `player.avPlayer`, because that is where
/// transport controls are for a consumer too (`SuperPlayer`'s doc comment).
@MainActor
final class SuperPlayerPlaybackTests: XCTestCase {

    /// The bound on every wait: long enough for the media to play at 1× on a loaded CI host, and
    /// only a ceiling, never approached by a pass.
    private let bound: Duration = .seconds(60)

    /// How far past the position it should start at a player may first be *seen* playing.
    ///
    /// The start itself is exact — the seek is made with zero tolerance, and the packed audio's
    /// frames are a few tens of milliseconds — so what this covers is the reading: a poll every
    /// `PlaybackHarness.pollInterval`, and the main actor's turn between the player starting and
    /// the poll seeing it. A second is many times both, and still less than half the distance
    /// between any two outcomes a test here tells apart, which are a whole two-second segment or
    /// more from one another.
    private let startTolerance = 1.0

    /// How far *before* its position a player may be seen: an AAC frame's length at the synthetic
    /// stream's rate, rounded up, since a zero-tolerance seek lands on a frame boundary.
    private let frameTolerance = 0.05

    /// Where a played item is left before it is replaced: past the first segment, so the beginning
    /// and the resume position are a whole segment and more apart.
    private let leaveAfter = 2.5

    private let harness = PlaybackHarness()

    /// A source on a port of the loopback interface nothing listens on — the discard port — so an
    /// item set from it is refused at once and never fetches anything.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testARequestPlaysUnderItsIdentity() async throws {
        let origin = try await harness.serve(.hls())
        let player = SuperPlayer()

        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("the position passes one second", timeout: bound) {
            player.avPlayer.currentTime().seconds > 1
        }

        XCTAssertEqual(player.currentContentId, "a")
        XCTAssertTrue(origin.networkRequests().contains { $0.kind == .mediaSegment })
    }

    /// `.at` is where playback starts, and the segments before it are never fetched — the count
    /// that says the start was not a seek made after the beginning had already loaded.
    func testAnAtPositionStartsThere() async throws {
        let origin = try await harness.serve(.hls())
        let player = SuperPlayer()
        let startMs = 2 * SyntheticHlsStream.segmentDurationMs

        player.setMediaRequest(
            MediaRequest(
                contentId: "a", sources: [origin.sourceURL], startPosition: .at(positionMs: startMs)
            ))
        let started = try await firstPositionPlaying(player)

        assertStarted(at: started, expected: Double(startMs) / 1_000)
        XCTAssertFalse(
            origin.networkRequests().contains {
                $0.path.hasSuffix(SyntheticHlsStream.segmentName(0))
            },
            "the first segment was fetched: \(origin.networkRequests())")
    }

    /// The acceptance's resume: a second request, then the first again with
    /// `.resumeFromLastKnown`, which starts where the first was left.
    func testTheFirstRequestResumesWhereItWasLeftAfterASecond() async throws {
        let first = try await harness.serve(.hls())
        let second = try await harness.serve(.hls())
        let player = SuperPlayer()
        let leftAt = try await playAndLeave(
            player, MediaRequest(contentId: "first", sources: [first.sourceURL]))

        player.setMediaRequest(MediaRequest(contentId: "second", sources: [second.sourceURL]))
        player.setMediaRequest(
            MediaRequest(
                contentId: "first", sources: [first.sourceURL], startPosition: .resumeFromLastKnown)
        )
        let resumed = try await firstPositionPlaying(player)

        assertStarted(at: resumed, expected: leftAt)
    }

    /// Content that played to its end resumes from the beginning — and is the test of the
    /// observed register's two signals as behaviour: the end is heard, and nothing heard after it
    /// clears it. A player that resumed the content at its end instead is never seen playing, so
    /// that defect fails here as the wait's `ConditionTimeout` rather than as a position.
    func testContentThatEndedResumesFromTheBeginning() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 2))
        let other = try await harness.serve(.hls())
        let player = SuperPlayer()
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        // Heard by an observer this test removes rather than awaited as an
        // `XCTNSNotificationExpectation`, which would keep the item alive past the test and release
        // it on XCTest's waiter thread instead of here. Not polled from the time either: on this
        // stream the current time `AVPlayer` reports at the end stays short of the duration it
        // reports, so no reading of the time says "ended".
        let ended = Flag()
        let observer = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: player.avPlayer.currentItem, queue: .main
        ) { _ in MainActor.assumeIsolated { ended.isSet = true } }
        defer { NotificationCenter.default.removeObserver(observer) }
        player.avPlayer.play()
        try await harness.until("the content plays to its end", timeout: bound) { ended.isSet }

        player.setMediaRequest(MediaRequest(contentId: "b", sources: [other.sourceURL]))
        player.setMediaRequest(
            MediaRequest(
                contentId: "a", sources: [origin.sourceURL], startPosition: .resumeFromLastKnown))
        let resumed = try await firstPositionPlaying(player)

        assertStarted(at: resumed, expected: 0)
    }

    /// The bound: content followed by `maxRememberedPositions` others is the least recently used of
    /// one more than the memory holds, and resumes from the beginning.
    ///
    /// Honest at the public bound rather than at a smaller one a test seam would allow: the others
    /// are set and replaced without being played, and name a port nothing listens on, so each is
    /// an item refused at once rather than a load left in flight — 128 of them cost nothing, and
    /// their identities are all the bound counts. Each is remembered — at zero — as the next
    /// replaces it, which is how the Kotlin map fills too.
    func testTheBoundEvictsTheLeastRecentlyUsedContent() async throws {
        let resumed = try await resumeAfter(othersCount: SuperPlayer.maxRememberedPositions)

        assertStarted(at: resumed.position, expected: 0)
    }

    /// The control for the bound: one fewer others, and the content is still remembered — so the
    /// test above is the bound's doing and not a memory that forgets whatever it is given.
    func testOneFewerThanTheBoundIsStillRemembered() async throws {
        let resumed = try await resumeAfter(othersCount: SuperPlayer.maxRememberedPositions - 1)

        assertStarted(at: resumed.position, expected: resumed.leftAt)
    }

    /// The control: an item played from a bare URL has no identity and resumes nothing — a request
    /// for the very same URL, asking to resume, starts at the beginning, because a position is
    /// keyed on content and that item named none.
    func testABareURLItemHasNoIdentityAndResumesNothing() async throws {
        let origin = try await harness.serve(.hls())
        let player = SuperPlayer()
        player.setMediaURL(origin.sourceURL)
        player.avPlayer.play()
        try await harness.until("the position passes \(leaveAfter) s", timeout: bound) {
            player.avPlayer.currentTime().seconds > leaveAfter
        }
        XCTAssertNil(player.currentContentId)

        player.setMediaRequest(
            MediaRequest(
                contentId: "a", sources: [origin.sourceURL], startPosition: .resumeFromLastKnown))
        let resumed = try await firstPositionPlaying(player)

        assertStarted(at: resumed, expected: 0)
    }

    /// Plays `first`, sets `othersCount` other content ids in turn without playing them, then
    /// resumes `first`: where it was left, and where it was first seen playing again.
    private func resumeAfter(othersCount: Int) async throws -> (leftAt: Double, position: Double) {
        let origin = try await harness.serve(.hls())
        let player = SuperPlayer()
        let leftAt = try await playAndLeave(
            player, MediaRequest(contentId: "first", sources: [origin.sourceURL]))

        for index in 0..<othersCount {
            player.setMediaRequest(
                MediaRequest(contentId: "other-\(index)", sources: [nowhere]))
        }
        player.setMediaRequest(
            MediaRequest(
                contentId: "first", sources: [origin.sourceURL], startPosition: .resumeFromLastKnown
            ))
        return (leftAt, try await firstPositionPlaying(player))
    }

    /// Plays `request` past ``leaveAfter`` and pauses it, returning the position it was left at —
    /// read on the same turn of the main actor as the pause, so it is the position the next
    /// `setMediaRequest` remembers.
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

/// A flag a main-queue notification sets and a wait reads, both on the main actor.
@MainActor
private final class Flag {
    var isSet = false
}
