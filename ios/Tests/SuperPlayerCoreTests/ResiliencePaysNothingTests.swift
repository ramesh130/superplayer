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

// `@testable` for `liveObservations`, the one reading past the facade, argued as
// `FrameOutputPaysNothingTests` argues it: a registration is invisible through any public API until
// the failure it waits for arrives.
@testable import SuperPlayerCore

/// ADR-0011 rule 14 on iOS (#419): a player configured without resilience observes no item's
/// failure, and `classify` answers nil; one configured with a hand-written stand-in observes each
/// resilience signal once per item and answers what the stand-in says — the two counts ADR-0020
/// rule 13 asks for, so the counter is shown to see what it counts. A value conforming to
/// ``PlaybackResilience`` alone is a third control: it fills no seam, as a hand-written Kotlin
/// `PlaybackResilience` fills no slot.
///
/// No item here loads — the URL names a port nothing listens on — because the registrations are
/// made when an item is set. `TypedErrorPlaybackTests` is the playing half.
@MainActor
final class ResiliencePaysNothingTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private let failure = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)

    private var players: [SuperPlayer] = []

    override func tearDown() async throws {
        for player in players { player.release() }
        players = []
        try await super.tearDown()
    }

    private func player(_ resilience: (any PlaybackResilience)?) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        configuration.resilience = resilience
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        return player
    }

    private func resilienceObservations(_ player: SuperPlayer) -> [ObservedSignal: Int] {
        player.liveObservations.filter { $0.key.scope == .resilienceItem }
    }

    private var eachOnce: [ObservedSignal: Int] {
        [.resilienceItemStatus: 1, .resilienceItemFailedToPlayToEnd: 1]
    }

    func testAPlayerWithoutResilienceObservesAndClassifiesNothing() {
        let player = player(nil)

        XCTAssertEqual(resilienceObservations(player), [:])
        XCTAssertNil(player.classify(failure))
        XCTAssertNil(player.playerError)
    }

    /// The control: the same reading with a stand-in sees each signal once, and its answer.
    func testAPlayerWithAStandInObservesEachSignalOnceAndAsksIt() throws {
        let player = player(StandInResilience())

        XCTAssertEqual(resilienceObservations(player), eachOnce)
        let typed = try XCTUnwrap(player.classify(failure))
        XCTAssertEqual(typed.causeClass, "StandIn.Failure")
        XCTAssertTrue(typed.underlyingError === failure)
    }

    /// A marker conformance alone has no answer to give, so nothing is registered for it.
    func testAValueConformingToTheMarkerAloneFillsNothing() {
        let player = player(MarkerOnly())

        XCTAssertEqual(resilienceObservations(player), [:])
        XCTAssertNil(player.classify(failure))
    }

    /// Every item is observed — a bare URL's included, since a failure is classifiable whatever
    /// named it — and each replacement leaves exactly one of each; a release leaves none.
    func testEachItemIsObservedOnceAndAReleaseLeavesNone() {
        let player = player(StandInResilience())

        player.setMediaRequest(MediaRequest(contentId: "b", sources: [nowhere]))
        player.setMediaURL(nowhere)
        XCTAssertEqual(resilienceObservations(player), eachOnce)

        player.resetForReuse()
        XCTAssertEqual(resilienceObservations(player), [:])

        player.setMediaURL(nowhere)
        player.release()
        XCTAssertEqual(player.liveObservations, [:])
    }
}

/// A hand-written resilience that names every failure the same, so what is read is the seam.
private struct StandInResilience: FailureClassifying {
    func typedError(
        for error: NSError, loggedFailure: AVPlayerItemErrorLogEvent?, playlistURL: URL?,
        positionMs: Int64?
    ) -> SuperPlayerError {
        SuperPlayerError(
            causeClass: "StandIn.Failure", userMessageKey: "stand_in", isRetryable: false,
            category: .unknown, lowerSecurityLevelMayHelp: false, rungsTried: [],
            positionMs: positionMs, underlyingError: error)
    }
}

/// A consumer's own conformance to the public marker, which core has nothing to ask.
private struct MarkerOnly: PlaybackResilience {}
