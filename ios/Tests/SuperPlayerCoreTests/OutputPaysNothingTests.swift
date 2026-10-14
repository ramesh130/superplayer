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
import XCTest

// `@testable` for `videoOutput` and `liveObservations`, the readings past the facade, argued as
// `DrmPaysNothingTests` argues its own: a binding is invisible through any public API until a
// display is asked something, which no simulator shows.
@testable import SuperPlayerCore

/// ADR-0014 rule 14 on iOS (#426), the Kotlin `SuperPlayerOutputSeamTest`: a player configured
/// without `output` makes no binding and registers nothing more than a player always does; one
/// configured with a hand-written stand-in makes exactly one, tells it every item the player makes
/// current — a request's, a bare URL's, none on a recycle — and ends it with the player, which is
/// the counter shown to see what it counts. A value conforming to ``PlaybackOutput`` alone makes
/// nothing. `SuperPlayerTVTests`' `DisplayCriteriaMatchingTests` counts the TV binding's own register.
@MainActor
final class OutputPaysNothingTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private var players: [SuperPlayer] = []

    override func tearDown() async throws {
        for player in players { player.release() }
        players = []
        try await super.tearDown()
    }

    private func player(_ output: (any PlaybackOutput)?) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        configuration.output = output
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        return player
    }

    func testAPlayerWithoutAnOutputMakesNoBindingAndRegistersNothingMore() {
        let without = player(nil)
        XCTAssertNil(without.videoOutput)
        // What any player playing a request registers, `ObservedSignalRegisterTests`' count, and
        // nothing else: the slot has no signal in core's register.
        let always = ObservedSignal.allCases.filter {
            $0.scope == .identifiedItem || $0.scope == .player
        }
        XCTAssertEqual(
            without.liveObservations, Dictionary(uniqueKeysWithValues: always.map { ($0, 1) }))
    }

    /// The control: a stand-in is asked once per player and told every item once.
    func testAStandInIsAskedOncePerPlayerAndToldEveryItem() throws {
        let output = StandInOutput()
        let player = player(output)
        player.setMediaURL(nowhere)
        _ = self.player(output)

        XCTAssertEqual(output.bindings.count, 2)
        let binding = try XCTUnwrap(output.bindings.first)
        XCTAssertTrue(player.videoOutput === binding)
        XCTAssertEqual(binding.items, [nowhere, nowhere])
        XCTAssertFalse(binding.isReleased)

        player.release()
        XCTAssertTrue(binding.isReleased)
    }

    /// A pool's recycle tells the binding there is no item, before its next row names one.
    func testARecycleTellsTheBindingThereIsNoItem() throws {
        let output = StandInOutput()
        var configuration = PlayerPool.Configuration()
        configuration.player.output = output
        let pool = PlayerPool(configuration: configuration)
        defer { pool.release() }
        let player = try XCTUnwrap(pool.acquire())
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))

        pool.recycle(player)
        let binding = try XCTUnwrap(output.bindings.first)
        XCTAssertEqual(binding.items, [nowhere, nil])
    }

    /// A marker conformance alone has nothing to make, so nothing is made.
    func testAValueConformingToTheMarkerAloneMakesNoBinding() {
        XCTAssertNil(player(MarkerOnly()).videoOutput)
    }
}

/// A hand-written output that makes a recording binding per player, so what is read is the seam.
private final class StandInOutput: VideoOutputting, @unchecked Sendable {
    // @unchecked: written and read on the main actor only, where a player asks for its binding.
    private(set) var bindings: [StandInBinding] = []

    func videoOutputBinding(host: any VideoOutputHost) -> (any VideoOutputBinding)? {
        let binding = StandInBinding()
        bindings.append(binding)
        return binding
    }
}

@MainActor
private final class StandInBinding: VideoOutputBinding {
    /// Each item's source, or nil for none.
    private(set) var items: [URL?] = []
    private(set) var isReleased = false
    func itemChanged(to item: AVPlayerItem?) {
        items.append(item.flatMap { ($0.asset as? AVURLAsset)?.url })
    }
    func release() { isReleased = true }
}

/// A consumer's own conformance to the public marker, which core has nothing to ask.
private struct MarkerOnly: PlaybackOutput {}
