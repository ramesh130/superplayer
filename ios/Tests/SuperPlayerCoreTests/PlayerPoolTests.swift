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
import Foundation
import XCTest

// `@testable` for `liveObservations` alone, the observed register's count, whose argument is
// `ObservedSignalRegisterTests`'. The pool's bound is stated through its `package` initialiser,
// which needs no `@testable`.
@testable import SuperPlayerCore

/// `PlayerPool` and `DeviceCapacity` (#404) with no network: the bound over stated memory figures,
/// acquire to it and then nil, recycle making one available, and what a recycled player no longer
/// carries. What a recycled player carries when its content really played is
/// `PlayerPoolPlaybackTests`', through the harness.
///
/// Every item here names a port nothing listens on, so it is refused at once and nothing is left
/// loading at the end of a test.
@MainActor
final class PlayerPoolTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private let mebibyte: UInt64 = 1024 * 1024

    /// A pool on a device stated to have `bytes` left, so its bound is the memory term's.
    private func pool(
        availableBytes bytes: UInt64, maxSize: Int? = nil,
        telemetry: (@MainActor @Sendable () -> any TelemetryCollector)? = nil
    ) -> PlayerPool {
        var configuration = PlayerPool.Configuration()
        configuration.maxSize = maxSize
        configuration.player.telemetry = telemetry
        return PlayerPool(configuration: configuration, availableMemory: { bytes })
    }

    private func request(_ contentId: String) -> MediaRequest {
        MediaRequest(contentId: contentId, sources: [nowhere])
    }

    // MARK: - The bound

    /// The memory term over stated figures: a whole budget per player, rounded down.
    func testTheBoundIsTheMemoryLeftOverThePerPlayerBudget() {
        let budget = DeviceCapacity.bytesPerPlayer
        XCTAssertEqual(DeviceCapacity.concurrentPlayers(availableMemoryBytes: 2 * budget), 2)
        XCTAssertEqual(DeviceCapacity.concurrentPlayers(availableMemoryBytes: 3 * budget), 3)
        XCTAssertEqual(DeviceCapacity.concurrentPlayers(availableMemoryBytes: 3 * budget - 1), 2)
    }

    /// The control on the memory term: a device with memory to spare is held at the ceiling, so
    /// the bound is not "whatever memory says" and not a pool that grows with the heap.
    func testMemoryToSpareIsHeldAtTheCeiling() {
        XCTAssertEqual(DeviceCapacity.ceiling, 4)
        XCTAssertEqual(
            DeviceCapacity.concurrentPlayers(availableMemoryBytes: 2 * 1024 * mebibyte), 4)
        XCTAssertEqual(DeviceCapacity.concurrentPlayers(availableMemoryBytes: .max), 4)
    }

    /// Never below one: a zero reading — not an app, or past the limit — and a remainder smaller
    /// than one budget are both a pool of one, never of none.
    func testTheBoundIsNeverBelowOne() {
        XCTAssertEqual(DeviceCapacity.concurrentPlayers(availableMemoryBytes: 0), 1)
        XCTAssertEqual(
            DeviceCapacity.concurrentPlayers(
                availableMemoryBytes: DeviceCapacity.bytesPerPlayer - 1), 1)
    }

    /// A smoke test of the public initialiser and no more: whatever this host reports, the bound is
    /// in range, which the clamp guarantees for any reading — what the platform reports under
    /// `xctest` is not stated anywhere a test could hold it to.
    func testThePublicPoolReadsTheDeviceIntoTheRange() {
        XCTAssertTrue((1...DeviceCapacity.ceiling).contains(PlayerPool().maxSize))
    }

    /// A consumer's maximum lowers the device's answer and never raises it.
    func testAConfiguredMaximumIsACeilingOnTheDevicesAndNeverAWayPastIt() {
        let budget = DeviceCapacity.bytesPerPlayer
        XCTAssertEqual(pool(availableBytes: 3 * budget, maxSize: 1).maxSize, 1)
        XCTAssertEqual(pool(availableBytes: 3 * budget, maxSize: 20).maxSize, 3)
        XCTAssertEqual(pool(availableBytes: 3 * budget).maxSize, 3)
    }

    func testAPoolsPlayersAreShortFormUnlessConfiguredOtherwise() {
        XCTAssertEqual(PlayerPool.Configuration().player.profile, .shortForm)
    }

    // MARK: - Acquire and recycle

    func testAcquireToTheBoundAndThenNil() throws {
        let pool = pool(availableBytes: 2 * DeviceCapacity.bytesPerPlayer)
        XCTAssertEqual(pool.size, 0, "no player is made before one is asked for")

        let first = try XCTUnwrap(pool.acquire())
        let second = try XCTUnwrap(pool.acquire())

        XCTAssertFalse(first === second)
        XCTAssertNil(pool.acquire(), "a third player past a bound of two")
        XCTAssertEqual(pool.inUseCount, 2)
        XCTAssertEqual(pool.size, 2)
        pool.release()
    }

    /// Recycling one makes one available, and it is the same player — newest first, so a run of
    /// acquire and recycle keeps returning to it rather than making another.
    func testRecycleMakesOneAvailable() throws {
        let pool = pool(availableBytes: 2 * DeviceCapacity.bytesPerPlayer)
        let first = try XCTUnwrap(pool.acquire())
        _ = try XCTUnwrap(pool.acquire())
        XCTAssertNil(pool.acquire())

        pool.recycle(first)

        XCTAssertEqual(pool.inUseCount, 1)
        XCTAssertEqual(pool.size, 2, "a recycled player is kept, not released")
        XCTAssertTrue(try XCTUnwrap(pool.acquire()) === first)
        XCTAssertNil(pool.acquire())
        pool.release()
    }

    /// A player its holder released, against the rule, is let go on its recycle rather than kept:
    /// the next acquire makes a player that plays instead of handing back one that cannot.
    func testARecycledPlayerItsHolderReleasedIsNotHandedOutAgain() throws {
        let pool = pool(availableBytes: DeviceCapacity.bytesPerPlayer)
        let released = try XCTUnwrap(pool.acquire())
        released.release()

        pool.recycle(released)

        XCTAssertEqual(pool.size, 0)
        let next = try XCTUnwrap(pool.acquire())
        XCTAssertFalse(next === released)
        next.setMediaRequest(request("a"))
        XCTAssertEqual(next.currentContentId, "a")
        pool.release()
    }

    /// A released pool releases every player, in use or idle, hands out nothing, and takes back a
    /// player handed to it late without complaint — the teardown race `recycle` forgives.
    func testReleaseReleasesEveryPlayerAndRefusesFurtherUse() throws {
        let pool = pool(availableBytes: 2 * DeviceCapacity.bytesPerPlayer)
        let idle = try XCTUnwrap(pool.acquire())
        let out = try XCTUnwrap(pool.acquire())
        out.setMediaRequest(request("a"))
        pool.recycle(idle)

        pool.release()
        pool.recycle(out)
        pool.release()

        XCTAssertNil(pool.acquire())
        XCTAssertEqual(pool.size, 0)
        for player in [idle, out] {
            player.setMediaRequest(request("b"))
            XCTAssertNil(player.avPlayer.currentItem, "a released player takes no content")
        }
    }

    // MARK: - What a recycled player no longer carries

    /// No item, no identity, no observation of the last user's item, no position memory — the last
    /// read through the snapshot, which carries the whole memory and is equal to a fresh player's
    /// only if nothing was remembered.
    func testARecycledPlayerCarriesNothingOfTheLastUser() throws {
        let pool = pool(availableBytes: DeviceCapacity.bytesPerPlayer)
        let player = try XCTUnwrap(pool.acquire())
        player.setMediaRequest(request("a"))
        player.setMediaRequest(request("b"))
        // The control: before the recycle the player does carry all of it, so what is asserted
        // after is the reset's doing and not a reading that sees nothing.
        XCTAssertEqual(player.currentContentId, "b")
        let fresh = SuperPlayer()
        XCTAssertNotEqual(player.liveObservations, fresh.liveObservations)
        XCTAssertNotEqual(player.saveSnapshot(), fresh.saveSnapshot())

        pool.recycle(player)

        XCTAssertNil(player.avPlayer.currentItem)
        XCTAssertNil(player.currentContentId)
        // A fresh player's own: the lifecycle's player signals, which are the player's for its
        // whole life, and nothing of an item.
        XCTAssertEqual(player.liveObservations, fresh.liveObservations)
        XCTAssertEqual(player.saveSnapshot(), fresh.saveSnapshot())
        fresh.release()
        pool.release()
    }

    /// The next user's item is observed exactly once per signal, so nothing registered for the
    /// last one is still counted beside it.
    func testTheNextUsersItemIsObservedOnce() throws {
        let pool = pool(availableBytes: DeviceCapacity.bytesPerPlayer)
        let player = try XCTUnwrap(pool.acquire())
        player.setMediaRequest(request("a"))
        pool.recycle(player)

        let reused = try XCTUnwrap(pool.acquire())
        reused.setMediaRequest(request("b"))

        XCTAssertEqual(
            reused.liveObservations,
            // Every signal but the frame output's and the policy's, which this pool's players have
            // none of (#405, #415); `FrameOutputPaysNothingTests` and `PolicyPaysNothingTests`
            // count a recycled player that has them.
            Dictionary(
                uniqueKeysWithValues: ObservedSignal.allCases.filter {
                    ![.frameOutputItem, .reapplicationPlayer, .reapplicationItem].contains($0.scope)
                }
                .map { ($0, 1) }))
        pool.release()
    }

    /// What a previous holder changed on the engine is put back: paused, full volume, unmuted, the
    /// default rate and no media selection criteria.
    func testARecycledPlayersEngineIsPutBack() throws {
        let pool = pool(availableBytes: DeviceCapacity.bytesPerPlayer)
        let player = try XCTUnwrap(pool.acquire())
        let fresh = SuperPlayer(configuration: pool.configuration.player)
        player.setMediaRequest(request("a"))
        player.avPlayer.volume = 0.25
        player.avPlayer.isMuted = true
        player.avPlayer.defaultRate = 2
        player.avPlayer.actionAtItemEnd = .none
        player.avPlayer.automaticallyWaitsToMinimizeStalling =
            !fresh.avPlayer.automaticallyWaitsToMinimizeStalling
        player.avPlayer.setMediaSelectionCriteria(
            AVPlayerMediaSelectionCriteria(
                preferredLanguages: ["fr"], preferredMediaCharacteristics: nil),
            forMediaCharacteristic: .audible)

        pool.recycle(player)

        XCTAssertEqual(player.avPlayer.rate, 0)
        XCTAssertEqual(player.avPlayer.volume, 1)
        XCTAssertFalse(player.avPlayer.isMuted)
        XCTAssertEqual(player.avPlayer.defaultRate, 1)
        XCTAssertEqual(player.avPlayer.actionAtItemEnd, fresh.avPlayer.actionAtItemEnd)
        XCTAssertEqual(
            player.avPlayer.automaticallyWaitsToMinimizeStalling,
            fresh.avPlayer.automaticallyWaitsToMinimizeStalling)
        XCTAssertNil(player.avPlayer.mediaSelectionCriteria(forMediaCharacteristic: .audible))
        fresh.release()
        pool.release()
    }

    /// A recycled player is left needing neither the audio session nor the idle timer: the last
    /// user's want to play does not outlive the row. Read off a recording platform, the lifecycle's
    /// `package` seam; the control is the activation the want made before the recycle.
    func testARecycleLeavesTheLifecycleIdle() async throws {
        let platform = RecordingLifecyclePlatform()
        var configuration = PlayerPool.Configuration()
        configuration.maxSize = 1
        let pool = PlayerPool(
            configuration: configuration, availableMemory: { 0 },
            lifecycle: SharedLifecycle(platform: platform))
        let player = try XCTUnwrap(pool.acquire())
        player.setMediaRequest(request("a"))

        player.avPlayer.play()
        for _ in 0..<200 where !platform.calls.contains(.activate) {
            try await Task.sleep(for: .milliseconds(25))
        }
        XCTAssertEqual(platform.calls, [.activate], "the control: wanting to play activates")

        pool.recycle(player)

        XCTAssertEqual(platform.calls, [.activate, .deactivate])
        pool.release()
    }

    /// Reset for reuse is the fourth session edge (`SessionEdges.swift`): the session ends while the
    /// outgoing content is still the player's, none begins, and the collector stays attached for
    /// the next user's session. A collector per player, made by the configuration's factory.
    func testARecycleEndsTheSessionAndTheCollectorStaysForTheNext() throws {
        let recorder = ResetEdgeRecorder()
        let pool = pool(availableBytes: DeviceCapacity.bytesPerPlayer, telemetry: { recorder })
        let player = try XCTUnwrap(pool.acquire())
        player.setMediaRequest(request("a"))

        pool.recycle(player)
        pool.recycle(try XCTUnwrap(pool.acquire()))  // nothing open, so nothing ends
        try XCTUnwrap(pool.acquire()).setMediaRequest(request("b"))
        pool.release()

        XCTAssertEqual(
            recorder.edges,
            ["attach", "start a", "end (playing a)", "start b", "end (playing b)", "detach"])
    }
}

/// Records each session edge, and for an end what the player was playing as it was signalled.
@MainActor
private final class ResetEdgeRecorder: TelemetryCollector {

    private(set) var edges: [String] = []
    private weak var player: SuperPlayer?

    func attach(to player: SuperPlayer) {
        self.player = player
        edges.append("attach")
    }

    func startSession(contentId: String, sessionId: String) {
        edges.append("start \(contentId)")
    }

    func endSession() {
        edges.append("end (playing \(player?.currentContentId ?? "-"))")
    }

    func declareIntent(monotonicTimeMs: Int64) {}
    func decisionChanged(_ decision: PlaybackDecision, trigger: DecisionTrigger) {}

    func detach() {
        edges.append("detach")
    }
}
