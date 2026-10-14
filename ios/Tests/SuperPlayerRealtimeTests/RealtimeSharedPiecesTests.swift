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
import AVKit
import MediaPlayer
import SuperPlayerCore
import SuperPlayerTestkit
import XCTest

@testable import SuperPlayerRealtime

/// What a `RealtimePlayer` shares with a `SuperPlayer` through core's engine protocols beside the view
/// (#461): the lifecycle's rules, a `PlaybackSession`, and Picture in Picture. Telemetry, the fourth,
/// is `RealtimeTelemetryTests`'.
///
/// **The lifecycle** is driven as `LifecyclePlaybackTests` drives a `SuperPlayer`'s: the real
/// interruption and route-change notifications posted from the real `AVAudioSession`, the platform a
/// recorder behind core's `LifecyclePlatform`. Whether a player is playing is read off its clock
/// running (`isRendering`) and its state, never off a duration.
///
/// **A session** publishes to a recorder behind core's `NowPlayingPlatform` (ADR-0021 rule 5's #403
/// addendum), since the system's two singletons can neither be reset nor made to fire a command.
///
/// **Picture in Picture** is read as far as the simulator lets a test go: the content source the
/// library's view asks for, and the answers its playback delegate gives. Whether the system then
/// shows a window is a device's (`docs/testing.md`, *A realtime transport on iOS*).
///
/// `@testable` for the playback delegate's type, which a consumer never names: AVKit is its reader.
/// The rest it reads beyond the public player — `playbackState`, `hasVideo`, `perform` and
/// `PlaybackSession(engine:)` — is the `package` seam itself, which needs no `@testable`.
@MainActor
final class RealtimeSharedPiecesTests: XCTestCase {

    private let bound: Duration = .seconds(30)

    private let harness = PlaybackHarness()
    private var players: [RealtimePlayer] = []
    private var sessions: [PlaybackSession] = []

    override func tearDown() async throws {
        for session in sessions { session.release() }
        for player in players { player.release() }
        sessions = []
        players = []
        harness.stop()
        try await super.tearDown()
    }

    // MARK: - The lifecycle

    func testAnInterruptionPausesAndItsEndResumesWhereTheSystemSaysItShould() async throws {
        let (player, platform) = try await playing()

        post(interruption: .began)
        XCTAssertEqual(player.playbackState, .paused)
        try await harness.until("the clock stops", timeout: bound) { !player.isRendering }

        post(interruption: .ended, options: .shouldResume)
        let resumedAt = player.currentTime.seconds
        try await harness.until("the clock passes where it resumed", timeout: bound) {
            player.currentTime.seconds > resumedAt + 0.5
        }
        // Activated to play, and again after the interruption, which the system deactivated — never
        // deactivated by the binding in between, since that was the system's to do.
        XCTAssertEqual(Array(platform.calls.filter(\.isSession).prefix(2)), [.activate, .activate])
    }

    /// The Android permanent loss: an interruption that ends without leave to resume leaves the
    /// stream paused, and the viewer presses play.
    func testAnInterruptionEndingWithoutLeaveToResumeLeavesTheStreamPaused() async throws {
        let (player, _) = try await playing()

        post(interruption: .began)
        post(interruption: .ended)

        XCTAssertEqual(player.playbackState, .paused)
    }

    /// The control that keeps "resume" from being "play on every ended": a stream the viewer had
    /// paused before the interruption stays paused after it, leave to resume or not.
    func testAnInterruptionsEndDoesNotStartAStreamThatWasPausedBeforeIt() async throws {
        let (player, platform) = try await playing()
        player.pause()
        XCTAssertEqual(platform.calls.filter(\.isSession), [.activate, .deactivate])

        post(interruption: .began)
        post(interruption: .ended, options: .shouldResume)

        XCTAssertEqual(player.playbackState, .paused)
    }

    /// Headphones unplugged pause; a route change for any other reason leaves the stream playing.
    func testTheOldDeviceBecomingUnavailablePausesAndNoOtherRouteChangeDoes() async throws {
        let (player, _) = try await playing()

        post(routeChange: .newDeviceAvailable)
        XCTAssertNotEqual(player.playbackState, .paused)

        post(routeChange: .oldDeviceUnavailable)
        XCTAssertEqual(player.playbackState, .paused)
    }

    /// A video stream holds the idle timer while it plays, and a released one holds nothing.
    func testAVideoStreamHoldsTheIdleTimerWhilePlayingAndReleasingLetsGoOfEverything() async throws
    {
        let (player, platform) = try await playing()
        try await harness.until("the tracks are declared", timeout: bound) { player.hasVideo }
        XCTAssertTrue(platform.calls.contains(.holdIdleTimer))

        player.release()

        XCTAssertEqual(Array(platform.calls.suffix(2)), [.deactivate, .releaseIdleTimer])
    }

    // MARK: - A session

    /// A realtime stream is published as live, with no duration and no elapsed time, and draws only
    /// the buttons it can honour: play, pause and toggle, no seek.
    func testASessionPublishesTheStreamAsLiveAndDrawsNoSeekCommand() throws {
        let platform = RecordingNowPlaying()
        let player = makePlayer()
        try player.setMediaRequest(request(title: "Studio A"))

        let session = makeSession(player, platform: platform)

        XCTAssertEqual(
            platform.added, [.play, .pause, .togglePlayPause], "no scrubber and no skip")
        let info = try XCTUnwrap(platform.info)
        XCTAssertEqual(info[MPNowPlayingInfoPropertyIsLiveStream] as? Bool, true)
        XCTAssertEqual(info[MPMediaItemPropertyTitle] as? String, "Studio A")
        XCTAssertEqual(
            info[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String, "studio-a")
        XCTAssertNil(info[MPMediaItemPropertyPlaybackDuration])
        XCTAssertNil(info[MPNowPlayingInfoPropertyElapsedPlaybackTime])
        XCTAssertTrue(session.realtimePlayer === player)
        XCTAssertNil(session.player, "a realtime session publishes no SuperPlayer")
    }

    /// A remote play and pause reach the stream, and the rate published follows; a seek, were one to
    /// arrive, is refused rather than coerced.
    func testRemotePlayAndPauseReachTheStreamAndASeekIsRefused() async throws {
        let platform = RecordingNowPlaying()
        let player = makePlayer()
        try player.setMediaRequest(request())
        let session = makeSession(player, platform: platform)

        XCTAssertTrue(platform.fire(.play))
        try await harness.until("the clock runs", timeout: bound) { player.isRendering }
        XCTAssertEqual(platform.info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 1)

        XCTAssertTrue(platform.fire(.pause))
        XCTAssertEqual(player.playbackState, .paused)
        XCTAssertEqual(platform.info?[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 0)

        XCTAssertFalse(platform.fire(.skipForward), "no target was added for a skip")
        XCTAssertFalse(player.perform(.skip(bySeconds: 15)))
        XCTAssertFalse(player.perform(.changePlaybackPosition(toSeconds: 0)))
        _ = session
    }

    /// Content named from outside the app is resolved and played at the live edge; a resolution that
    /// starts anywhere else is declined, with the stream playing left alone.
    func testAResolvedIdPlaysAtTheEdgeAndOneThatSeeksIsDeclined() throws {
        let player = makePlayer()
        try player.setMediaRequest(request(contentId: "playing"))
        let session = makeSession(
            player, platform: RecordingNowPlaying(),
            resolver: Catalog([
                "edge": request(contentId: "edge"),
                "resume": request(contentId: "resume", start: .resumeFromLastKnown),
            ]))

        XCTAssertNil(session.setMediaRequest(forContentId: "resume"))
        XCTAssertEqual(player.currentContentId, "playing")

        XCTAssertEqual(session.setMediaRequest(forContentId: "edge")?.contentId, "edge")
        XCTAssertEqual(player.currentContentId, "edge")
    }

    /// One session across both player types: a `SuperPlayer` swapped in behind a realtime session
    /// draws the seek commands, and the realtime player it replaced is released.
    func testSwappingASuperPlayerInDrawsTheSeekCommandsAndReleasesTheStream() throws {
        let platform = RecordingNowPlaying()
        let realtime = makePlayer()
        try realtime.setMediaRequest(request())
        let session = makeSession(realtime, platform: platform)

        let player = SuperPlayer()
        session.setPlayer(player)

        XCTAssertEqual(
            platform.added,
            [.play, .pause, .togglePlayPause, .changePlaybackPosition, .skipForward, .skipBackward])
        XCTAssertTrue(session.player === player)
        XCTAssertNil(session.realtimePlayer)
        XCTAssertNil(realtime.currentContentId, "the realtime player was released")

        let next = makePlayer()
        session.setPlayer(next)
        XCTAssertEqual(
            platform.removed, [.changePlaybackPosition, .skipForward, .skipBackward],
            "the stream swapped back in withdraws them")
    }

    // MARK: - Picture in Picture

    /// The library's view asks the player for a content source over its display layer, and the
    /// playback delegate answers for a live, unseekable timeline: unbounded, paused as the player
    /// is, and a skip that moves nothing.
    func testPictureInPictureIsOfferedOverALiveUnseekableTimeline() async throws {
        let player = makePlayer()
        try player.setMediaRequest(request())
        let view = PlayerLayerView(player: player)
        XCTAssertNotNil(view.makePictureInPictureContentSource())
        XCTAssertNil(
            player.pictureInPictureContentSource(for: AVSampleBufferDisplayLayer()),
            "only the player's own layer")

        let delegate = RealtimePictureInPicturePlayback(player: player)
        let controller = AVPictureInPictureController(
            contentSource: try XCTUnwrap(view.makePictureInPictureContentSource()))
        let range = delegate.pictureInPictureControllerTimeRangeForPlayback(controller)
        XCTAssertEqual(range.start, .negativeInfinity)
        XCTAssertEqual(range.duration, .positiveInfinity)
        XCTAssertTrue(delegate.pictureInPictureControllerIsPlaybackPaused(controller))

        delegate.pictureInPictureController(controller, setPlaying: true)
        XCTAssertFalse(delegate.pictureInPictureControllerIsPlaybackPaused(controller))
        try await harness.until("the clock runs", timeout: bound) { player.isRendering }

        var skipped = false
        delegate.pictureInPictureController(
            controller, skipByInterval: CMTime(value: 15, timescale: 1)
        ) { skipped = true }
        XCTAssertTrue(skipped, "the skip completes, having moved nothing")
    }

    // MARK: - Helpers

    private static let url = URL(string: "scripted://relay.example/studio-a")!

    private func request(
        contentId: String = "studio-a", title: String? = nil,
        start: MediaRequest.StartPosition = .beginning
    ) -> MediaRequest {
        MediaRequest(contentId: contentId, sources: [Self.url], startPosition: start, title: title)
    }

    private func makePlayer(platform: any LifecyclePlatform = QuietPlatform()) -> RealtimePlayer {
        let player = RealtimePlayer(
            configuration: .init(
                sources: Realtime.transport(scheme: "scripted", factory: ScriptedTransport())),
            lifecycle: SharedLifecycle(platform: platform))
        players.append(player)
        return player
    }

    private func makeSession(
        _ player: RealtimePlayer, platform: RecordingNowPlaying,
        resolver: (any MediaRequestResolver)? = nil
    ) -> PlaybackSession {
        let session = PlaybackSession(
            engine: player, resolver: resolver, centre: NowPlayingCentre(platform: platform))
        sessions.append(session)
        return session
    }

    /// A stream playing on a player whose lifecycle platform records, once the session is active.
    private func playing() async throws -> (RealtimePlayer, RecordingLifecycle) {
        let platform = RecordingLifecycle()
        let player = makePlayer(platform: platform)
        try player.setMediaRequest(request())
        player.play()
        try await harness.until("the clock runs", timeout: bound) { player.isRendering }
        XCTAssertEqual(platform.calls.filter(\.isSession), [.activate])
        return (player, platform)
    }

    private func post(
        interruption type: AVAudioSession.InterruptionType,
        options: AVAudioSession.InterruptionOptions = []
    ) {
        var userInfo: [AnyHashable: Any] = [AVAudioSessionInterruptionTypeKey: type.rawValue]
        if type == .ended { userInfo[AVAudioSessionInterruptionOptionKey] = options.rawValue }
        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification, object: AVAudioSession.sharedInstance(),
            userInfo: userInfo)
    }

    private func post(routeChange reason: AVAudioSession.RouteChangeReason) {
        NotificationCenter.default.post(
            name: AVAudioSession.routeChangeNotification, object: AVAudioSession.sharedInstance(),
            userInfo: [AVAudioSessionRouteChangeReasonKey: reason.rawValue])
    }
}

/// A `LifecyclePlatform` that records what it was asked, in order, and touches nothing.
@MainActor
private final class RecordingLifecycle: LifecyclePlatform {

    enum Call: Equatable {
        case activate, deactivate, holdIdleTimer, releaseIdleTimer
        var isSession: Bool { self == .activate || self == .deactivate }
    }

    private(set) var calls: [Call] = []

    func activateAudioSession() { calls.append(.activate) }
    func deactivateAudioSession() { calls.append(.deactivate) }
    func setIdleTimerDisabled(_ disabled: Bool) {
        calls.append(disabled ? .holdIdleTimer : .releaseIdleTimer)
    }
}

/// A `NowPlayingPlatform` that records the dictionary and the targets, and fires a target as the
/// system would for a button.
@MainActor
private final class RecordingNowPlaying: NowPlayingPlatform {
    private(set) var info: [String: Any]?
    private(set) var added: [RemoteCommand] = []
    private(set) var removed: [RemoteCommand] = []
    private var targets: [RemoteCommand: (RemoteCommandEvent) -> Bool] = [:]

    func setNowPlayingInfo(_ info: [String: Any]?) { self.info = info }

    func addTarget(
        _ command: RemoteCommand,
        _ handler: @escaping @MainActor @Sendable (RemoteCommandEvent) -> Bool
    ) {
        added.append(command)
        targets[command] = handler
    }

    func removeTargets(_ command: RemoteCommand) {
        removed.append(command)
        targets[command] = nil
    }

    /// Fires `command`'s target with its plain event, answering what the target did; false with
    /// no target, as a button the system never drew.
    func fire(_ command: RemoteCommand) -> Bool {
        let event: RemoteCommandEvent =
            switch command {
            case .play: .play
            case .pause: .pause
            case .togglePlayPause: .togglePlayPause
            case .changePlaybackPosition: .changePlaybackPosition(toSeconds: 0)
            case .skipForward: .skip(bySeconds: 15)
            case .skipBackward: .skip(bySeconds: -5)
            }
        return targets[command]?(event) ?? false
    }
}

/// A resolver over a fixed table.
@MainActor
private struct Catalog: MediaRequestResolver {
    let requests: [String: MediaRequest]
    init(_ requests: [String: MediaRequest]) { self.requests = requests }
    func resolve(contentId: String) -> MediaRequest? { requests[contentId] }
}
