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
import SuperPlayerCore
import SuperPlayerTestkit
import XCTest

/// The platform's lifecycle rules on a playing `SuperPlayer` (#402, ADR-0006 rule 1): an
/// interruption pauses and, where the system says so, resumes; headphones unplugged pause and no
/// other route change does; the audio session is active while the player wants to play; and an
/// audio item never holds the idle timer.
///
/// **The notifications are the real ones**, posted by the test as the platform posts them — the
/// names, the poster `AVAudioSession.sharedInstance()` and the `userInfo` keys `AVAudioSession.h`
/// declares — because nothing under `check` can make a phone call or pull out headphones. What the
/// binding asks *of* the platform goes to a ``RecordingPlatform`` through the one package-scoped
/// protocol ADR-0021 rule 5's #402 addendum allows the feature, `LifecyclePlatform`, so the session
/// is never actually activated by these tests; that the system honours the activation is a
/// device's to show.
///
/// **Play and pause are read through `player.avPlayer`** — `play()`, `pause()`, `currentTime()`
/// and `timeControlStatus` — including a pause or resume the binding made, which ADR-0021 rule 1's
/// #402 addendum admits because the behaviour under test is the player pausing, and the facade
/// publishes no playback state yet (#408 brings the telemetry vocabulary).
///
/// **A notification posted on the main thread to an observer on the main queue is delivered before
/// `post` returns** — each test's positive half shows it, finding the player paused the moment the
/// post returns — which is what lets a negative half ("it did not resume") be read at once rather
/// than after a sleep.
@MainActor
final class LifecyclePlaybackTests: XCTestCase {

    /// The bound on every wait, only a ceiling: the synthetic stream plays at 1× on a loaded host.
    private let bound: Duration = .seconds(60)

    private let harness = PlaybackHarness()

    /// Every player a test made, paused and emptied before the origin stops, so no load is left in
    /// flight for AVFoundation to wind down after the test (the host's cleanup waits on them).
    private var players: [SuperPlayer] = []

    override func tearDown() async throws {
        for player in players {
            player.avPlayer.pause()
            player.avPlayer.replaceCurrentItem(with: nil)
        }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    func testAnInterruptionPausesAndItsEndResumesWhereTheSystemSaysItShould() async throws {
        let (player, platform) = try await playing()

        post(interruption: .began)
        XCTAssertEqual(player.avPlayer.timeControlStatus, .paused)

        let pausedAt = player.avPlayer.currentTime().seconds
        post(interruption: .ended, options: .shouldResume)
        try await harness.until("the position passes where it paused", timeout: bound) {
            player.avPlayer.currentTime().seconds > pausedAt + 1
        }
        // Activated to play, and again after the interruption, which the system deactivated —
        // never deactivated by the binding in between, since that was the system's to do.
        XCTAssertEqual(platform.calls, [.activate, .activate])
    }

    /// The Android permanent loss: an interruption that ends without leave to resume leaves the
    /// player paused, and the viewer presses play.
    func testAnInterruptionEndingWithoutLeaveToResumeLeavesThePlayerPaused() async throws {
        let (player, _) = try await playing()

        post(interruption: .began)
        XCTAssertEqual(player.avPlayer.timeControlStatus, .paused)
        post(interruption: .ended)

        XCTAssertEqual(player.avPlayer.timeControlStatus, .paused)
    }

    /// The control that keeps "resume" from being "play on every ended": a player the viewer had
    /// paused before the interruption stays paused after it, leave to resume or not.
    func testAnInterruptionsEndDoesNotStartAPlayerThatWasPausedBeforeIt() async throws {
        let (player, platform) = try await playing()
        player.avPlayer.pause()
        // The deactivation is the binding having heard the pause, so the interruption below meets
        // a player the binding knows was paused.
        try await harness.until("the session is deactivated", timeout: bound) {
            platform.calls == [.activate, .deactivate]
        }

        post(interruption: .began)
        post(interruption: .ended, options: .shouldResume)

        XCTAssertEqual(player.avPlayer.timeControlStatus, .paused)
    }

    /// Headphones unplugged: the becoming-noisy rule.
    func testTheOldDeviceBecomingUnavailablePauses() async throws {
        let (player, _) = try await playing()

        post(routeChange: .oldDeviceUnavailable)

        XCTAssertEqual(player.avPlayer.timeControlStatus, .paused)
    }

    /// The control: a route change for any other reason — here a new device, headphones plugged
    /// *in* — leaves playback alone, and the position goes on advancing after it.
    func testARouteChangeForAnotherReasonDoesNotPause() async throws {
        let (player, _) = try await playing()

        post(routeChange: .newDeviceAvailable)
        XCTAssertNotEqual(player.avPlayer.timeControlStatus, .paused)

        let changedAt = player.avPlayer.currentTime().seconds
        try await harness.until("the position passes where the route changed", timeout: bound) {
            player.avPlayer.currentTime().seconds > changedAt + 1
        }
    }

    /// The session is active while the player wants to play and deactivated when it stops — and an
    /// audio-only item, playing, never holds the idle timer, because the screen may lock under
    /// audio. The video half is `LifecycleRulesTests`', since every synthetic stream is audio.
    func testTheSessionIsActiveWhilePlayingAndAnAudioItemHoldsNoIdleTimer() async throws {
        let (player, platform) = try await playing()
        XCTAssertEqual(platform.calls, [.activate])

        player.avPlayer.pause()

        try await harness.until("the session is deactivated", timeout: bound) {
            platform.calls == [.activate, .deactivate]
        }
    }

    /// A `SuperPlayer` over a recording platform, playing the synthetic stream past its first
    /// second, with the session activated by then.
    private func playing() async throws -> (SuperPlayer, RecordingPlatform) {
        let origin = try await harness.serve(.hls())
        let platform = RecordingPlatform()
        let player = SuperPlayer(lifecycle: SharedLifecycle(platform: platform))
        players.append(player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("the position passes one second", timeout: bound) {
            player.avPlayer.currentTime().seconds > 1
        }
        try await harness.until("the session is activated", timeout: bound) {
            platform.calls == [.activate]
        }
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
private final class RecordingPlatform: LifecyclePlatform {

    enum Call: Equatable { case activate, deactivate, holdIdleTimer, releaseIdleTimer }

    private(set) var calls: [Call] = []

    func activateAudioSession() { calls.append(.activate) }
    func deactivateAudioSession() { calls.append(.deactivate) }
    func setIdleTimerDisabled(_ disabled: Bool) {
        calls.append(disabled ? .holdIdleTimer : .releaseIdleTimer)
    }
}
