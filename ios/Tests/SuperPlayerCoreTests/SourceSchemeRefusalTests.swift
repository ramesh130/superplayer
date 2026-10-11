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
import Combine
import SuperPlayerCore
import XCTest

/// ADR-0020 rule 11's refusal, which #431 moved here (its #431 addendum): a realtime source handed to
/// a `SuperPlayer` is refused typed, naming `RealtimePlayer`, on the facade's error publisher — before
/// anything else runs, so the player is left exactly as it was. Through the public API alone, and on
/// a player configured with nothing, because the refusal is core's and not resilience's.
///
/// The controls keep it from being "refuse everything" — an `http`, an `https` and a `file` source
/// are each taken, with nothing delivered — and from being a registry: a scheme no transport was ever
/// built for is refused the same way, since what is refused is what `AVPlayer` cannot load.
@MainActor
final class SourceSchemeRefusalTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private var subscriptions: Set<AnyCancellable> = []
    private var heard: [any Error] = []

    override func tearDown() async throws {
        subscriptions = []
        heard = []
        try await super.tearDown()
    }

    private func listening(_ player: SuperPlayer) -> SuperPlayer {
        player.errors.sink { [weak self] error in
            MainActor.assumeIsolated { self?.heard.append(error) }
        }
        .store(in: &subscriptions)
        return player
    }

    func testARealtimeSourceIsRefusedNamingRealtimePlayerAndChangesNothing() throws {
        let player = listening(SuperPlayer())
        player.setMediaRequest(MediaRequest(contentId: "vod", sources: [nowhere]))
        let item = try XCTUnwrap(player.avPlayer.currentItem)

        player.setMediaRequest(
            MediaRequest(
                contentId: "live", sources: [URL(string: "moq://relay.example/broadcast")!]))

        let refusal = try XCTUnwrap(heard.first as? UnplayableSourceSchemeError)
        XCTAssertEqual(heard.count, 1)
        XCTAssertEqual(refusal.scheme, "moq")
        XCTAssertTrue(refusal.description.contains("RealtimePlayer"), refusal.description)
        XCTAssertEqual(player.playerError as? UnplayableSourceSchemeError, refusal)
        XCTAssertTrue(player.avPlayer.currentItem === item, "the playing item is untouched")
        XCTAssertEqual(player.currentContentId, "vod")
    }

    func testABareURLOfAnotherSchemeIsRefusedTheSameWay() throws {
        let player = listening(SuperPlayer())

        player.setMediaURL(URL(string: "whep://example.com/stream")!)

        XCTAssertEqual((heard.first as? UnplayableSourceSchemeError)?.scheme, "whep")
        XCTAssertNil(player.avPlayer.currentItem)
    }

    /// The controls: what `AVPlayer` loads is taken, whatever its case, and nothing is delivered —
    /// and a refusal is cleared by the next content taken.
    func testHttpHttpsAndFileSourcesAreTaken() {
        let player = listening(SuperPlayer())
        player.setMediaURL(URL(string: "rtsp://example.com/stream")!)
        XCTAssertNotNil(player.playerError)

        for (index, source) in [
            nowhere, URL(string: "HTTPS://127.0.0.1:9/never.m3u8")!,
            URL(fileURLWithPath: "/nonexistent/never.m3u8"),
        ].enumerated() {
            player.setMediaRequest(MediaRequest(contentId: "\(index)", sources: [source]))
            XCTAssertEqual(player.currentContentId, "\(index)")
        }
        XCTAssertEqual(heard.count, 1)
        XCTAssertNil(player.playerError)
    }
}
