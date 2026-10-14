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
import SuperPlayerTestkit
import XCTest

// `@testable` for the binding's register alone — `player.videoOutput` and its live registrations —
// argued as `DrmPaysNothingTests` argues its own: a registration is invisible through any public API.
@testable import SuperPlayerCore
@testable import SuperPlayerTV

/// ADR-0014 rule 4 read for tvOS (#426), the Kotlin `FrameRateMatchingTest`: each item a player
/// makes current asks the display for its own criteria, a replacement asks in place, and the request
/// is withdrawn at the item's end, on a recycle and on release — and only by the player whose request
/// is in force. The display is ``StandInDisplay``, because `AVDisplayManager` is a tvOS type and no
/// simulator switches a mode; what a panel then does is a device's (`docs/testing.md`, *A television
/// on tvOS*). Run on the iOS and the tvOS simulator alike.
///
/// The controls keep it from being "ask for everything" or "withdraw on everything": a replacement
/// withdraws nothing between two items, another player's release withdraws nothing, an answer for
/// an item already replaced asks nothing, and a seek within content asks nothing again. That a
/// player configured without an output makes no binding is core's `OutputPaysNothingTests`.
@MainActor
final class DisplayCriteriaMatchingTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on: an item is made
    /// current and asked for its criteria whether or not it ever loads.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private static func nowhere(_ name: String) -> URL {
        URL(string: "http://127.0.0.1:9/\(name)")!
    }

    private let harness = PlaybackHarness()
    private var players: [SuperPlayer] = []
    private var pools: [PlayerPool] = []

    override func tearDown() async throws {
        for player in players { player.release() }
        for pool in pools { pool.release() }
        players = []
        pools = []
        harness.stop()
        try await super.tearDown()
    }

    private func configuration(_ display: StandInDisplay) -> SuperPlayer.Configuration {
        var configuration = SuperPlayer.Configuration()
        configuration.profile = .tvLeanback
        configuration.output = TvOutput.standard(displays: display)
        return configuration
    }

    private func player(_ display: StandInDisplay) -> SuperPlayer {
        let player = SuperPlayer(configuration: configuration(display))
        players.append(player)
        return player
    }

    private func play(_ name: String, on player: SuperPlayer) {
        player.setMediaRequest(MediaRequest(contentId: name, sources: [Self.nowhere(name)]))
    }

    private func until(
        _ display: StandInDisplay, asked expected: [String], _ description: String
    ) async throws {
        try await harness.until(description, timeout: .seconds(10)) {
            display.requests == expected
        }
    }

    func testEachItemAsksForItsOwnCriteriaAndAReplacementWithdrawsNothingBetween() async throws {
        let display = StandInDisplay()
        let player = player(display)

        play("a.m3u8", on: player)
        try await until(display, asked: ["prefer a.m3u8"], "the first item asked")
        play("b.m3u8", on: player)
        try await until(
            display, asked: ["prefer a.m3u8", "prefer b.m3u8"], "the second asked in place")
        // A bare URL is an item too, and asks as one.
        player.setMediaURL(Self.nowhere("c.m3u8"))
        try await until(
            display, asked: ["prefer a.m3u8", "prefer b.m3u8", "prefer c.m3u8"],
            "the bare URL asked")
    }

    func testAReleaseWithdrawsAndAsksNothingAfter() async throws {
        let display = StandInDisplay()
        let player = player(display)
        play("a.m3u8", on: player)
        try await until(display, asked: ["prefer a.m3u8"], "the item asked")

        player.release()
        XCTAssertEqual(display.requests, ["prefer a.m3u8", "withdraw"])
        play("b.m3u8", on: player)
        XCTAssertEqual(display.requests, ["prefer a.m3u8", "withdraw"])
    }

    /// The recycle is the pool's (`resetForReuse`), and the next row's adoption asks again.
    func testARecycleWithdrawsAndTheNextRowAsksAgain() async throws {
        let display = StandInDisplay()
        var poolConfiguration = PlayerPool.Configuration()
        poolConfiguration.player = configuration(display)
        let pool = PlayerPool(configuration: poolConfiguration)
        pools.append(pool)
        let player = try XCTUnwrap(pool.acquire())

        play("a.m3u8", on: player)
        try await until(display, asked: ["prefer a.m3u8"], "the row asked")
        pool.recycle(player)
        XCTAssertEqual(display.requests, ["prefer a.m3u8", "withdraw"])

        let next = try XCTUnwrap(pool.acquire())
        play("b.m3u8", on: next)
        try await until(
            display, asked: ["prefer a.m3u8", "withdraw", "prefer b.m3u8"], "the next row asked")
    }

    /// One display, two players: the one whose request is in force is the only one that withdraws.
    func testOnlyThePlayerWhoseRequestIsInForceWithdraws() async throws {
        let display = StandInDisplay()
        let first = player(display)
        let second = player(display)
        play("a.m3u8", on: first)
        try await until(display, asked: ["prefer a.m3u8"], "the first player asked")
        play("b.m3u8", on: second)
        try await until(
            display, asked: ["prefer a.m3u8", "prefer b.m3u8"], "the second player asked")

        first.release()
        XCTAssertEqual(display.requests, ["prefer a.m3u8", "prefer b.m3u8"])
        second.release()
        XCTAssertEqual(display.requests, ["prefer a.m3u8", "prefer b.m3u8", "withdraw"])
    }

    /// An answer that arrives after its item was replaced asks nothing; the current item's does.
    func testAnAnswerForAnItemAlreadyReplacedAsksNothing() async throws {
        let display = StandInDisplay()
        display.holdsAnswers = true
        let player = player(display)

        play("a.m3u8", on: player)
        try await harness.until("the first answer is pending", timeout: .seconds(10)) {
            display.waiting == ["a.m3u8"]
        }
        play("b.m3u8", on: player)
        try await harness.until("both answers are pending", timeout: .seconds(10)) {
            display.waiting == ["a.m3u8", "b.m3u8"]
        }

        display.answer("b.m3u8")
        try await until(display, asked: ["prefer b.m3u8"], "the current item asked")
        // The stale answer arrives now, and a third item's after it: the main actor runs them in
        // that order, so a request from the first would stand between the two that follow.
        display.answer("a.m3u8")
        play("c.m3u8", on: player)
        try await harness.until("the third answer is pending", timeout: .seconds(10)) {
            display.waiting == ["c.m3u8"]
        }
        display.answer("c.m3u8")
        try await until(
            display, asked: ["prefer b.m3u8", "prefer c.m3u8"], "the stale answer asked nothing")
    }

    /// An item that states no criteria puts back what the previous one asked for: its content wants
    /// a mode suited to everything.
    func testAnItemStatingNoCriteriaWithdrawsThePreviousRequest() async throws {
        let display = StandInDisplay()
        display.statesNoCriteria = ["b.m3u8"]
        let player = player(display)

        play("a.m3u8", on: player)
        try await until(display, asked: ["prefer a.m3u8"], "the first item asked")
        play("b.m3u8", on: player)
        try await until(display, asked: ["prefer a.m3u8", "withdraw"], "the second withdrew")
    }

    /// Played to its real end over the loopback origin: the request is withdrawn there, and asked
    /// again when the viewer plays it again from the start — while a seek before the end asks
    /// nothing, since the criteria in force are already the content's.
    func testTheRequestIsWithdrawnAtTheItemsEndAndAskedAgainWhenPlayedAgain() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 2))
        let display = StandInDisplay()
        let player = player(display)
        let name = origin.sourceURL.lastPathComponent
        player.setMediaRequest(MediaRequest(contentId: "ends", sources: [origin.sourceURL]))
        try await until(display, asked: ["prefer \(name)"], "the item asked")

        player.avPlayer.play()
        try await harness.until("the position advances", timeout: .seconds(60)) {
            player.avPlayer.currentTime().seconds > 0.5
        }
        // A seek within content: had it asked again, a second request would stand before the end's.
        await player.avPlayer.seek(to: CMTime(value: 1, timescale: 10))
        try await harness.until("the item played to its end", timeout: .seconds(60)) {
            display.requests.count >= 2
        }
        XCTAssertEqual(display.requests, ["prefer \(name)", "withdraw"])

        await player.avPlayer.seek(to: .zero)
        try await until(
            display, asked: ["prefer \(name)", "withdraw", "prefer \(name)"], "asked again")
    }

    /// The register (ADR-0020 rule 13): each display signal once for the player's life, each item
    /// signal once while an item is current, and nothing once the player is released.
    func testTheBindingsRegistrationsAreOnceEachAndGoWithThePlayer() async throws {
        let display = StandInDisplay()
        let player = player(display)
        let binding = try XCTUnwrap(player.videoOutput as? DisplayCriteriaMatching)
        let displaySignals = DisplaySignal.allCases.filter { $0.scope == .display }
        let everySignalOnce = Dictionary(
            uniqueKeysWithValues: DisplaySignal.allCases.map { ($0, 1) })

        XCTAssertEqual(
            binding.liveObservations,
            Dictionary(uniqueKeysWithValues: displaySignals.map { ($0, 1) }))
        play("a.m3u8", on: player)
        XCTAssertEqual(binding.liveObservations, everySignalOnce)
        play("b.m3u8", on: player)
        XCTAssertEqual(binding.liveObservations, everySignalOnce, "a replacement registered twice")
        XCTAssertEqual(
            display.liveObservations,
            Dictionary(uniqueKeysWithValues: displaySignals.map { ($0, 1) }))

        player.release()
        XCTAssertEqual(binding.liveObservations, [:])
        XCTAssertEqual(display.liveObservations, [:])
    }

    /// "Nowhere else", held mechanically, as `CollectorSignalRegisterTests` holds its product's: no
    /// source of this product but the register's own file names a notification centre registration.
    func testNoSourceButTheRegisterRegistersANotification() throws {
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/SuperPlayerTV")
        let files = try FileManager.default.contentsOfDirectory(
            at: sources, includingPropertiesForKeys: nil
        ).filter { $0.pathExtension == "swift" }
        XCTAssertTrue(
            files.contains { $0.lastPathComponent == "DisplayManaging.swift" },
            "the scan did not find the product's sources at \(sources.path)")
        let registrations = ["addObserver(", "observe(\\.", "Notification.Name"]
        for file in files where file.lastPathComponent != "DisplayManaging.swift" {
            let text = try String(contentsOf: file, encoding: .utf8)
            for call in registrations {
                XCTAssertFalse(
                    text.contains(call),
                    "\(file.lastPathComponent) has \(call) outside the register")
            }
        }
    }

    #if !os(tvOS)
        /// On iOS the standard output has no display to ask — `AVDisplayManager` is tvOS's — so it
        /// makes no binding, and a player configured with it is one configured without.
        func testTheStandardOutputMakesNoBindingOnIOS() {
            var configuration = SuperPlayer.Configuration()
            configuration.output = TvOutput.standard()
            let player = SuperPlayer(configuration: configuration)
            players.append(player)
            play("a.m3u8", on: player)
            XCTAssertNil(player.videoOutput)
        }
    #endif
}
