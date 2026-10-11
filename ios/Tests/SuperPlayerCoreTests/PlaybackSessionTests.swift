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
import MediaPlayer
import XCTest

// `@testable` for `PlaybackSession.liveObservations` alone — the session register's count, the
// seam `ObservedSignalRegisterTests` reads on the facade and argued there: a registration is
// invisible through any public API until what it waits for arrives. The platform protocol and the
// centre are `package`, which needs no `@testable`.
@testable import SuperPlayerCore

/// `PlaybackSession`'s shape with no playback (#403, ADR-0007): which targets a session adds and
/// when it removes them, the order `release()` keeps, a swap that keeps the session, the process's
/// one Now Playing between two sessions, the resolver's declining, and the session's own observed
/// register. Everything that needs content to play — the duration, the rate, a remote pause
/// pausing, a resume from a resolved id — is `PlaybackSessionPlaybackTests`', in testkit's target.
///
/// The platform is a ``RecordingNowPlaying`` behind the one `package` protocol ADR-0021 rule 5's
/// #403 addendum allows the feature, `NowPlayingPlatform`: the system's two singletons cannot be
/// reset between tests, and `MPRemoteCommand` has no public way to fire a target. No item here
/// loads — the URL names a port nothing listens on — since what is asserted is made as content is
/// set, not as it plays.
@MainActor
final class PlaybackSessionTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private func request(_ contentId: String) -> MediaRequest {
        MediaRequest(
            contentId: contentId, sources: [nowhere], title: "Title of \(contentId)",
            subtitle: "Subtitle of \(contentId)")
    }

    private func playing(_ contentId: String) -> SuperPlayer {
        let player = SuperPlayer()
        player.setMediaRequest(request(contentId))
        return player
    }

    /// The control the rest needs: a centre no session has claimed has asked nothing of the
    /// platform — so the counts below are of what a session did — and a session adds a target for
    /// every command and publishes the request it found. That a player *alone* can reach no
    /// centre at all is not this count's to show — no player is handed one — but
    /// ``testNoCoreSourceButThePlatformNamesTheSystemCentres``', and, for the dictionary, the
    /// system-centre test in `PlaybackSessionPlaybackTests`.
    func testNothingIsAskedOfThePlatformUntilASessionIsMade() {
        let platform = RecordingNowPlaying()
        let centre = NowPlayingCentre(platform: platform)
        let player = playing("a")
        XCTAssertEqual(platform.calls, [])

        let session = PlaybackSession(player: player, centre: centre)

        XCTAssertEqual(
            platform.calls, RemoteCommand.allCases.map { .add($0) } + [.publish(contentId: "a")])
        XCTAssertEqual(platform.info?[MPMediaItemPropertyTitle] as? String, "Title of a")
        XCTAssertEqual(platform.info?[MPMediaItemPropertyArtist] as? String, "Subtitle of a")
        session.release()
    }

    /// The order the Kotlin `release()` keeps, read where it matters: the dictionary is cleared and
    /// every target removed while the player still holds its content, and the player is released
    /// after — so no lock screen is left drawing content with buttons that act on nothing.
    func testReleaseEndsTheSessionAndThenThePlayer() throws {
        let platform = RecordingNowPlaying()
        let player = playing("a")
        let session = PlaybackSession(
            player: player, centre: NowPlayingCentre(platform: platform))
        var playerHeldContentWhenCleared: Bool?
        platform.onClear = { playerHeldContentWhenCleared = player.avPlayer.currentItem != nil }
        platform.calls = []

        session.release()

        XCTAssertEqual(
            platform.calls, [.publish(contentId: nil)] + RemoteCommand.allCases.map { .remove($0) })
        XCTAssertEqual(playerHeldContentWhenCleared, true)
        XCTAssertNil(player.avPlayer.currentItem, "the player is released after the session")
        XCTAssertFalse(platform.fire(.pause), "no target is left to answer")

        session.release()
        XCTAssertEqual(platform.calls.count, 1 + RemoteCommand.allCases.count, "idempotent")
    }

    /// `setPlayer` keeps the session: no target is removed and the dictionary is not cleared, what
    /// is published is the incoming player's, a command reaches the incoming player — and the
    /// outgoing one is released.
    func testSetPlayerSwapsThePlayerBehindALiveSession() {
        let platform = RecordingNowPlaying()
        let outgoing = playing("a")
        let incoming = playing("b")
        let session = PlaybackSession(
            player: outgoing, centre: NowPlayingCentre(platform: platform))
        platform.calls = []

        session.setPlayer(incoming)

        XCTAssertEqual(platform.calls, [.publish(contentId: "b")])
        XCTAssertTrue(session.player === incoming)
        XCTAssertNil(outgoing.avPlayer.currentItem, "the outgoing player is released")
        XCTAssertTrue(platform.fire(.changePlaybackPosition(toSeconds: 1)))
        XCTAssertNotNil(incoming.avPlayer.currentItem)
        session.release()
    }

    /// One process, one Now Playing: the later session holds it, the earlier one publishes nothing
    /// and its release changes nothing, and the holder's release clears it.
    func testTheSessionMadeMostRecentlyHoldsNowPlaying() {
        let platform = RecordingNowPlaying()
        let centre = NowPlayingCentre(platform: platform)
        let earlier = PlaybackSession(player: playing("a"), centre: centre)
        let later = PlaybackSession(player: playing("b"), centre: centre)
        XCTAssertEqual(
            platform.info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String, "b")
        platform.calls = []

        // A swap publishes at once rather than a main-queue turn later, so the displaced session
        // really asks the centre to publish here, and the centre is what refuses it.
        earlier.setPlayer(playing("c"))
        earlier.release()
        XCTAssertEqual(platform.calls, [], "a displaced session touches nothing")
        XCTAssertTrue(platform.fire(.pause), "the holder answers")

        later.release()
        XCTAssertEqual(
            platform.calls, [.publish(contentId: nil)] + RemoteCommand.allCases.map { .remove($0) })
    }

    /// The toggle and the two skips, which the playback tests do not fire: the toggle starts a
    /// paused player and pauses a playing one (the rate asked for, which `AVPlayer.h` says is set
    /// at once), a skip lands relative to where the player is and back never before the start, and
    /// a seek with no item to seek in is refused.
    func testTheToggleAndTheSkipsAct() {
        let platform = RecordingNowPlaying()
        let player = playing("a")
        let session = PlaybackSession(player: player, centre: NowPlayingCentre(platform: platform))

        XCTAssertTrue(platform.fire(.togglePlayPause))
        XCTAssertEqual(player.avPlayer.rate, 1)
        XCTAssertTrue(platform.fire(.togglePlayPause))
        XCTAssertEqual(player.avPlayer.rate, 0)
        XCTAssertTrue(platform.fire(.skip(bySeconds: 15)))
        XCTAssertTrue(platform.fire(.skip(bySeconds: -5)))

        player.setMediaURL(nowhere)
        player.avPlayer.replaceCurrentItem(with: nil)
        XCTAssertFalse(platform.fire(.skip(bySeconds: 15)), "nothing to seek in")
        session.release()
    }

    /// The resolver's two refusals: no resolver declines every id, and one that does not recognise
    /// an id declines it — each leaving the player as it was. The control is an id it recognises,
    /// adopted as `setMediaRequest` would.
    func testAnIdTheResolverDeclinesLeavesThePlayerAlone() {
        let player = playing("a")
        let unresolving = PlaybackSession(
            player: player, centre: NowPlayingCentre(platform: RecordingNowPlaying()))
        XCTAssertNil(unresolving.setMediaRequest(forContentId: "b"))
        XCTAssertEqual(player.currentContentId, "a")
        unresolving.release()

        let resolved = playing("a")
        let session = PlaybackSession(
            player: resolved, resolver: Catalog(["b": request("b")]),
            centre: NowPlayingCentre(platform: RecordingNowPlaying()))
        XCTAssertNil(session.setMediaRequest(forContentId: "unknown"))
        XCTAssertEqual(resolved.currentContentId, "a")

        XCTAssertEqual(session.setMediaRequest(forContentId: "b"), request("b"))
        XCTAssertEqual(resolved.currentContentId, "b")
        session.release()
    }

    /// The session's observed register: each signal once while it publishes a player, once on the
    /// incoming player after a swap, and none once released.
    func testTheSessionObservesEachSignalOnceAndNothingOnceReleased() {
        let session = PlaybackSession(
            player: playing("a"), centre: NowPlayingCentre(platform: RecordingNowPlaying()))
        let everySignalOnce = Dictionary(
            uniqueKeysWithValues: SessionSignal.allCases.map { ($0, 1) })
        XCTAssertEqual(session.liveObservations, everySignalOnce)

        session.setPlayer(playing("b"))
        XCTAssertEqual(session.liveObservations, everySignalOnce)

        session.release()
        XCTAssertEqual(session.liveObservations, [:])
    }

    /// The facade cannot touch Now Playing, held mechanically: only the platform's own file names
    /// either singleton, so no core source but it reaches them — a player can publish nothing a
    /// session did not.
    func testNoCoreSourceButThePlatformNamesTheSystemCentres() throws {
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/SuperPlayerCore")
        let files = try FileManager.default.contentsOfDirectory(
            at: sources, includingPropertiesForKeys: nil
        ).filter { $0.pathExtension == "swift" }
        XCTAssertTrue(
            files.contains { $0.lastPathComponent == "NowPlayingPlatform.swift" },
            "the scan did not find core's sources at \(sources.path)")

        for file in files where file.lastPathComponent != "NowPlayingPlatform.swift" {
            let text = try String(contentsOf: file, encoding: .utf8)
            for singleton in ["MPNowPlayingInfoCenter.default(", "MPRemoteCommandCenter.shared("] {
                XCTAssertFalse(
                    text.contains(singleton), "\(file.lastPathComponent) reaches \(singleton)")
            }
        }
    }
}

/// A resolver over a fixed catalog.
@MainActor
private struct Catalog: MediaRequestResolver {
    let requests: [String: MediaRequest]
    init(_ requests: [String: MediaRequest]) { self.requests = requests }
    func resolve(contentId: String) -> MediaRequest? { requests[contentId] }
}

/// A ``NowPlayingPlatform`` that records what it was asked and fires a command as a remote would.
@MainActor
final class RecordingNowPlaying: NowPlayingPlatform {

    enum Call: Equatable {
        /// The dictionary set, named by the content id it carried; nil for a cleared one.
        case publish(contentId: String?)
        case add(RemoteCommand)
        case remove(RemoteCommand)
    }

    var calls: [Call] = []
    private(set) var info: [String: Any]?
    private var handlers: [RemoteCommand: [@MainActor @Sendable (RemoteCommandEvent) -> Bool]] =
        [:]

    /// Called as the dictionary is cleared, before the call returns.
    var onClear: (() -> Void)?

    func setNowPlayingInfo(_ info: [String: Any]?) {
        self.info = info
        calls.append(
            .publish(
                contentId: info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String))
        if info == nil { onClear?() }
    }

    func addTarget(
        _ command: RemoteCommand,
        _ handler: @escaping @MainActor @Sendable (RemoteCommandEvent) -> Bool
    ) {
        calls.append(.add(command))
        handlers[command, default: []].append(handler)
    }

    func removeTargets(_ command: RemoteCommand) {
        calls.append(.remove(command))
        handlers[command] = nil
    }

    /// Fires `event` at its command's targets as the system would; false when none answered.
    func fire(_ event: RemoteCommandEvent) -> Bool {
        let command: RemoteCommand
        switch event {
        case .play: command = .play
        case .pause: command = .pause
        case .togglePlayPause: command = .togglePlayPause
        case .changePlaybackPosition: command = .changePlaybackPosition
        case .skip(let seconds): command = seconds < 0 ? .skipBackward : .skipForward
        }
        return (handlers[command] ?? []).map { $0(event) }.contains(true)
    }
}
