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

// `@testable` for `protection`, the one reading past the facade, argued as
// `ResiliencePaysNothingTests` argues its own: a key session is invisible through any public API
// until a key is asked for, which no simulator does.
@testable import SuperPlayerCore

/// ADR-0012 rule 13 on iOS (#422), the Kotlin `SuperPlayerDrmSeamTest`: a player configured without
/// `drm` makes no key session; one configured with a hand-written stand-in makes exactly one, hands
/// it every asset the player plays once, and ends it with the player — the two counts ADR-0020 rule
/// 13 asks for, so the counter is shown to see what it counts. What is counted is the **set**, never
/// the answer: a value conforming to ``PlaybackDrm`` alone makes no session and still turns a frame
/// output off (`FrameOutputPaysNothingTests`), because protection declared is not nothing.
///
/// No item here loads — the URL names a port nothing listens on — because a recipient is handed
/// over when an item is set. `SuperPlayerDRMTests`' `LicenceExchangeTests` is the playing half.
@MainActor
final class DrmPaysNothingTests: XCTestCase {

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

    private func player(_ drm: (any PlaybackDrm)?) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        configuration.drm = drm
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        return player
    }

    func testAPlayerWithoutDrmMakesNoKeySession() {
        XCTAssertNil(player(nil).protection)
    }

    /// The control: a stand-in is asked once per player, and its session sees every asset once.
    func testAStandInIsAskedOncePerPlayerAndHandedEveryAssetOnce() throws {
        let drm = StandInDrm()
        let player = player(drm)
        player.setMediaURL(nowhere)
        _ = self.player(drm)

        XCTAssertEqual(drm.sessions.count, 2)
        let session = try XCTUnwrap(drm.sessions.first)
        XCTAssertTrue(player.protection === session)
        XCTAssertEqual(session.recipients.map(\.url), [nowhere, nowhere])
        XCTAssertFalse(session.isReleased)

        player.release()
        XCTAssertTrue(session.isReleased)
    }

    /// A marker conformance alone has nothing to make, so nothing is made.
    func testAValueConformingToTheMarkerAloneMakesNoSession() {
        XCTAssertNil(player(MarkerOnly()).protection)
    }
}

/// A hand-written protection that makes a recording session per player, so what is read is the
/// seam.
private final class StandInDrm: ContentProtecting, @unchecked Sendable {
    // @unchecked: written and read on the main actor only, where a player asks for its session.
    private(set) var sessions: [StandInSession] = []

    func protectionSession(host: any ProtectionHost) -> any ProtectionSession {
        let session = StandInSession()
        sessions.append(session)
        return session
    }
}

@MainActor
private final class StandInSession: ProtectionSession {
    private(set) var recipients: [AVURLAsset] = []
    private(set) var isReleased = false
    func addRecipient(_ asset: AVURLAsset) { recipients.append(asset) }
    func release() { isReleased = true }
}

/// A consumer's own conformance to the public marker, which core has nothing to ask.
private struct MarkerOnly: PlaybackDrm {}
