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

import Foundation
import XCTest

// `@testable` for the register and its count, the internal seam argued below.
@testable import SuperPlayerCore

/// The observed register's test (ADR-0020 rule 13, `docs/ios-plan.md` §3.1's last row): what
/// replaces the Android `SuperPlayerForwardingTest`, because here nothing is forwarded and what goes
/// silently wrong is an observation registered and never removed.
///
/// It reads `SuperPlayer.liveObservations`, an internal seam, which is past the facade and argued:
/// a registration is invisible through any public API until the notification it waits for arrives,
/// and the behaviour each case exists for is asserted through the harness in
/// `SuperPlayerPlaybackTests` (content that ended resumes from the beginning). No item here ever
/// loads — the URL names a port nothing listens on — because a registration is made when an item is
/// set, not when it plays.
///
/// ADR-0020 rule 13 asks the count to be taken twice, so the counter is shown to see what it counts:
/// with nothing configured (`testAPlayerGivenNoContentObservesOnlyTheLifecycle`), and with the feature in
/// use, which here is a request set rather than a hand-written stand-in, since the register's only
/// client is the facade itself.
@MainActor
final class ObservedSignalRegisterTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private func request(_ contentId: String) -> MediaRequest {
        MediaRequest(contentId: contentId, sources: [nowhere])
    }

    /// Every signal a player configured with nothing but content registers, once: all of the
    /// register but the frame output's and resilience's, which `FrameOutputPaysNothingTests` and
    /// `ResiliencePaysNothingTests` count on a player that has the feature and on one that does not
    /// (ADR-0022 rule 9, ADR-0011 rule 14).
    private var everySignalOnce: [ObservedSignal: Int] {
        Dictionary(
            uniqueKeysWithValues: ObservedSignal.allCases.filter {
                $0.scope != .frameOutputItem && $0.scope != .resilienceItem
            }
            .map { ($0, 1) })
    }

    /// The lifecycle's signals, each once: what every player observes for its whole life (#402).
    private var thePlayerSignalsOnce: [ObservedSignal: Int] {
        everySignalOnce.filter { $0.key.scope == .player }
    }

    /// The control the count needs: a player given nothing has registered nothing on an item, so
    /// the counter is not one that reads "everything" regardless (ADR-0020 rule 13, "pays
    /// nothing"). What it has registered is the lifecycle, which ADR-0006 rule 1 puts on every
    /// player rather than behind anything a configuration names.
    func testAPlayerGivenNoContentObservesOnlyTheLifecycle() {
        XCTAssertFalse(thePlayerSignalsOnce.isEmpty)
        XCTAssertNotEqual(thePlayerSignalsOnce, everySignalOnce)
        XCTAssertEqual(SuperPlayer().liveObservations, thePlayerSignalsOnce)
    }

    func testEverySignalInTheRegisterIsObservedOnceOnTheItemARequestPlays() {
        let player = SuperPlayer()

        player.setMediaRequest(request("a"))

        XCTAssertEqual(player.liveObservations, everySignalOnce)
    }

    /// The defect the register exists for: an observation of the outgoing item that is never
    /// removed. Replacing the content any number of times leaves exactly one of each.
    func testReplacingTheContentRemovesTheOutgoingItemsObservations() {
        let player = SuperPlayer()

        for contentId in ["a", "b", "c", "a"] { player.setMediaRequest(request(contentId)) }

        XCTAssertEqual(player.liveObservations, everySignalOnce)
    }

    /// A released player observes nothing at all, the lifecycle included.
    func testAReleasedPlayerObservesNothing() {
        let player = SuperPlayer()
        player.setMediaRequest(request("a"))

        player.release()

        XCTAssertEqual(player.liveObservations, [:])
    }

    /// An item with no identity has nothing to remember, so nothing is observed on it — and the
    /// identified item it replaced has its observations removed. The lifecycle is untouched.
    func testAnItemFromABareURLIsObservedForNothing() {
        let player = SuperPlayer()
        player.setMediaRequest(request("a"))

        player.setMediaURL(nowhere)

        XCTAssertEqual(player.liveObservations, thePlayerSignalsOnce)
        XCTAssertNil(player.currentContentId)
    }

    /// The identity travels with the item rather than in a field the facade keeps beside the
    /// player: an item taken away and handed back through `avPlayer` — what `SuperPlayer.avPlayer`'s
    /// doc comment says a consumer may do — is named again, and the bare item between is named by
    /// nothing.
    func testTheIdentityTravelsWithTheItem() throws {
        let player = SuperPlayer()
        player.setMediaRequest(request("catalog:episode:1138"))
        let item = try XCTUnwrap(player.avPlayer.currentItem)

        player.setMediaURL(nowhere)
        XCTAssertNil(player.currentContentId)
        player.avPlayer.replaceCurrentItem(with: item)

        XCTAssertEqual(player.currentContentId, "catalog:episode:1138")
    }

    /// "Nowhere else", held mechanically: the count above sees only registrations made through
    /// `Observation.register`, so an `addObserver` or a key-value `observe` written anywhere else in
    /// core would be invisible to it. Core's sources are read from the host through `#filePath`, as
    /// `SyntheticHlsFixturesTests` reads its fixtures, and every registration call must be in the
    /// register's own file — or in the session's (`SessionSignals.swift`, #403), the one other register
    /// core holds, since a `PlaybackSession` is an object of its own over a player and what it
    /// observes is counted by `PlaybackSessionTests` rather than here.
    func testNoCoreSourceButTheRegisterRegistersAnObservation() throws {
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/SuperPlayerCore")
        let files = try FileManager.default.contentsOfDirectory(
            at: sources, includingPropertiesForKeys: nil
        ).filter { $0.pathExtension == "swift" }
        XCTAssertTrue(
            files.contains { $0.lastPathComponent == "ObservedSignals.swift" },
            "the scan did not find core's sources at \(sources.path)")

        let registrations = [
            "addObserver(", ".observe(", "addPeriodicTimeObserver(", "addBoundaryTimeObserver(",
        ]
        let registers: Set = ["ObservedSignals.swift", "SessionSignals.swift"]
        for file in files where !registers.contains(file.lastPathComponent) {
            let text = try String(contentsOf: file, encoding: .utf8)
            for call in registrations {
                XCTAssertFalse(
                    text.contains(call),
                    "\(file.lastPathComponent) calls \(call) outside the observed registers")
            }
        }
    }
}
