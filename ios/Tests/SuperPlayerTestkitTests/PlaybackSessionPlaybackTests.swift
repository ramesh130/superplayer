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
import MediaPlayer
import SuperPlayerCore
import SuperPlayerTestMedia
import SuperPlayerTestkit
import UIKit
import XCTest

/// `PlaybackSession` over content that plays (#403, ADR-0007): a player with no session leaves the
/// system's Now Playing alone, a session's dictionary reflects the request and the item, a remote
/// pause pauses, a swap carries the session to the incoming player, and an id from outside resolves
/// and resumes where it was left.
///
/// **One test drives the system's own centres**, because the rest cannot say what they claim
/// against them: `MPNowPlayingInfoCenter.default()` is the process's and is read back exactly as it
/// was set, so whether a player alone touched it, and whether a session really set and cleared it,
/// are asserted there. What no test can do is fire a real command — `MPRemoteCommand` has no public
/// way to invoke a target, and the system invokes one only for a real button — so the commands go
/// through a recording `NowPlayingPlatform`, the one `package` protocol ADR-0021 rule 5's #403
/// addendum allows the feature, and that the system calls the target a button press reaches is a
/// device's to show (the i1 demo's run on an iPhone, `docs/ios-plan.md` §7).
///
/// **A pause a remote command made is read off `player.avPlayer`** — `timeControlStatus` and
/// `currentTime()` — under ADR-0021 rule 1's #403 addendum, for the #402 addendum's reason: the
/// behaviour under test is the player pausing, and a consumer sees it exactly there. What the
/// session *publishes* is read where it is published, the dictionary.
@MainActor
final class PlaybackSessionPlaybackTests: XCTestCase {

    /// The bound on every wait, only a ceiling: the synthetic stream plays at 1× on a loaded host.
    private let bound: Duration = .seconds(60)

    /// Where content is left before it is replaced: past its first two-second segment, so the
    /// beginning and the resume position are a segment and more apart.
    private let leaveAfter = 2.5

    /// How far past its position a player may first be *seen* playing, and how far before: a
    /// poll's interval and a main-actor turn after, still less than half the two-second segment
    /// between the outcomes told apart here, and an AAC frame before.
    private let startTolerance = 1.0
    private let frameTolerance = 0.05

    private let harness = PlaybackHarness()

    /// Every player and session a test made, ended before the origin stops, so no load is left in
    /// flight for AVFoundation to wind down after the test.
    private var players: [SuperPlayer] = []
    private var sessions: [PlaybackSession] = []

    override func tearDown() async throws {
        for session in sessions { session.release() }
        for player in players { player.release() }
        sessions = []
        players = []
        harness.stop()
        try await super.tearDown()
    }

    /// The system's centre, really: untouched while a player with no session plays a titled
    /// request, set with the request's title by a session, and cleared by its release.
    func testOnlyASessionSetsTheSystemNowPlayingAndItsReleaseClearsIt() async throws {
        let centre = MPNowPlayingInfoCenter.default()
        XCTAssertNil(centre.nowPlayingInfo, "another test left the process's Now Playing set")
        let player = try await playing(
            MediaRequest(
                contentId: "a", sources: [try await harness.serve(.hls()).sourceURL], title: "A"))
        XCTAssertNil(centre.nowPlayingInfo, "a player with no session published itself")

        let session = PlaybackSession(player: player)
        XCTAssertEqual(centre.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String, "A")
        XCTAssertEqual(
            centre.nowPlayingInfo?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String,
            "a")

        session.release()
        XCTAssertNil(centre.nowPlayingInfo)
    }

    /// The acceptance's dictionary and its remote pause: title, subtitle, artwork, duration,
    /// elapsed time and rate while playing; then a pause from the remote pauses the player and the
    /// dictionary says so; and a seek from the scrubber moves both.
    func testTheDictionaryReflectsTheRequestAndARemotePausePauses() async throws {
        let platform = RecordingNowPlaying()
        let player = try await playing(
            MediaRequest(
                contentId: "a", sources: [try await harness.serve(.hls()).sourceURL], title: "A",
                subtitle: "Episode one", artworkURL: try artworkFile()))
        let session = PlaybackSession(player: player, centre: NowPlayingCentre(platform: platform))
        sessions.append(session)

        try await harness.until("the artwork is published", timeout: bound) {
            platform.info?[MPMediaItemPropertyArtwork] != nil
        }
        let info = try XCTUnwrap(platform.info)
        XCTAssertEqual(info[MPMediaItemPropertyTitle] as? String, "A")
        XCTAssertEqual(info[MPMediaItemPropertyArtist] as? String, "Episode one")
        let artwork = try XCTUnwrap(info[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork)
        XCTAssertEqual(artwork.bounds.size, CGSize(width: 4, height: 4))
        let durationMs =
            Int64(TestContent.defaultSegmentCount) * SyntheticHlsStream.segmentDurationMs
        XCTAssertEqual(
            try XCTUnwrap(info[MPMediaItemPropertyPlaybackDuration] as? Double),
            Double(durationMs) / 1_000, accuracy: 0.1)
        XCTAssertEqual(info[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 1)
        XCTAssertGreaterThan(info[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double ?? 0, 0)

        XCTAssertTrue(platform.fire(.pause))
        XCTAssertEqual(player.avPlayer.timeControlStatus, .paused)
        try await harness.until("the dictionary says paused", timeout: bound) {
            platform.info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 0
        }

        XCTAssertTrue(platform.fire(.changePlaybackPosition(toSeconds: 5)))
        try await harness.until("the published position is where the scrubber went", timeout: bound)
        {
            abs((platform.info?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double ?? 0) - 5)
                < frameTolerance
        }
        XCTAssertEqual(player.avPlayer.timeControlStatus, .paused, "a seek does not play")
    }

    /// `setPlayer` keeps the session: a remote play reaches the incoming player, which then plays,
    /// and what is published is the incoming player's content.
    func testARemoteCommandReachesThePlayerSwappedIn() async throws {
        let origin = try await harness.serve(.hls())
        let platform = RecordingNowPlaying()
        let outgoing = try await playing(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        let session = PlaybackSession(
            player: outgoing, centre: NowPlayingCentre(platform: platform))
        sessions.append(session)
        let incoming = SuperPlayer()
        players.append(incoming)
        incoming.setMediaRequest(MediaRequest(contentId: "b", sources: [origin.sourceURL]))

        session.setPlayer(incoming)
        XCTAssertTrue(platform.fire(.play))

        try await harness.until("the incoming player passes one second", timeout: bound) {
            incoming.avPlayer.currentTime().seconds > 1
        }
        XCTAssertEqual(
            platform.info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String, "b")
        XCTAssertNil(outgoing.avPlayer.currentItem, "the outgoing player was released")
    }

    /// The resolver's acceptance: content named by id from outside the app — as a CarPlay row's
    /// handler names it — is resolved, played, left, and named again, and resumes where it was
    /// left, because the resolver answered `.resumeFromLastKnown` and the request was adopted as
    /// `setMediaRequest` adopts one.
    func testAnIdFromOutsideResolvesAndResumesWhereItWasLeft() async throws {
        let first = try await harness.serve(.hls())
        let second = try await harness.serve(.hls())
        let player = SuperPlayer()
        players.append(player)
        let session = PlaybackSession(
            player: player,
            resolver: Catalog([
                "first": MediaRequest(
                    contentId: "first", sources: [first.sourceURL],
                    startPosition: .resumeFromLastKnown),
                "second": MediaRequest(contentId: "second", sources: [second.sourceURL]),
            ]),
            centre: NowPlayingCentre(platform: RecordingNowPlaying()))
        sessions.append(session)

        XCTAssertNotNil(session.setMediaRequest(forContentId: "first"))
        player.avPlayer.play()
        try await harness.until("the position passes \(leaveAfter) s", timeout: bound) {
            player.avPlayer.currentTime().seconds > leaveAfter
        }
        player.avPlayer.pause()
        let leftAt = player.avPlayer.currentTime().seconds

        XCTAssertNotNil(session.setMediaRequest(forContentId: "second"))
        XCTAssertNotNil(session.setMediaRequest(forContentId: "first"))
        XCTAssertEqual(player.currentContentId, "first")
        player.avPlayer.play()
        try await harness.until("the player is playing", timeout: bound) {
            player.avPlayer.timeControlStatus == .playing
        }
        let resumed = player.avPlayer.currentTime().seconds

        XCTAssertTrue(
            (leftAt - frameTolerance...leftAt + startTolerance).contains(resumed),
            "first seen playing at \(resumed) s, expected \(leftAt) s")
    }

    /// A `SuperPlayer` playing `request` past its first second.
    private func playing(_ request: MediaRequest) async throws -> SuperPlayer {
        let player = SuperPlayer()
        players.append(player)
        player.setMediaRequest(request)
        player.avPlayer.play()
        try await harness.until("the position passes one second", timeout: bound) {
            player.avPlayer.currentTime().seconds > 1
        }
        return player
    }

    /// A four-by-four-pixel PNG in the test's temporary directory, fetched by the session as a `file:` URL:
    /// `URLSession` reads one as it reads `http:`, and the origin serves streams, not images.
    private func artworkFile() throws -> URL {
        // At a scale of one, so the decoded file's size in points is its size in pixels.
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4), format: format)
        let image = renderer.image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("artwork-\(UUID().uuidString).png")
        try XCTUnwrap(image.pngData()).write(to: url)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
}

/// A resolver over a fixed catalog.
@MainActor
private struct Catalog: MediaRequestResolver {
    let requests: [String: MediaRequest]
    init(_ requests: [String: MediaRequest]) { self.requests = requests }
    func resolve(contentId: String) -> MediaRequest? { requests[contentId] }
}

/// A `NowPlayingPlatform` that keeps the dictionary it was handed and fires a command at the
/// targets it was given, as a remote would.
@MainActor
private final class RecordingNowPlaying: NowPlayingPlatform {

    private(set) var info: [String: Any]?
    private var handlers: [RemoteCommand: [@MainActor @Sendable (RemoteCommandEvent) -> Bool]] =
        [:]

    func setNowPlayingInfo(_ info: [String: Any]?) { self.info = info }

    func addTarget(
        _ command: RemoteCommand,
        _ handler: @escaping @MainActor @Sendable (RemoteCommandEvent) -> Bool
    ) {
        handlers[command, default: []].append(handler)
    }

    func removeTargets(_ command: RemoteCommand) { handlers[command] = nil }

    /// Fires `event` at its command's targets; false when none answered.
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
