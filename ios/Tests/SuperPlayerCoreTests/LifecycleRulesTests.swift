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

// `@testable` for the readings and the shared count, which are internal: a consumer meets them only
// as a player that pauses, resumes and keeps the screen on, which `LifecyclePlaybackTests` drives.
@testable import SuperPlayerCore

/// The lifecycle's rules without a player (#402): what each notification the platform posts is read
/// as, when the idle timer is held, and that the audio session and the idle timer — process-wide —
/// are held while *any* player needs them.
///
/// The idle timer's video half is here and only here, because every synthetic stream is audio:
/// `LifecyclePlaybackTests` shows an audio item holding nothing, and that a video item's presentation
/// size is non-zero by the time it plays is AVFoundation's to show on a device
/// (`docs/testing.md`, *The iOS harness*).
@MainActor
final class LifecycleRulesTests: XCTestCase {

    func testAnInterruptionIsReadAsBeganOrAsEndedWithOrWithoutLeaveToResume() {
        let began = AVAudioSession.InterruptionType.began.rawValue
        let ended = AVAudioSession.InterruptionType.ended.rawValue
        let shouldResume = AVAudioSession.InterruptionOptions.shouldResume.rawValue

        XCTAssertEqual(
            LifecycleEvent(.audioSessionInterrupted, [AVAudioSessionInterruptionTypeKey: began]),
            .interruptionBegan)
        XCTAssertEqual(
            LifecycleEvent(
                .audioSessionInterrupted,
                [
                    AVAudioSessionInterruptionTypeKey: ended,
                    AVAudioSessionInterruptionOptionKey: shouldResume,
                ]),
            .interruptionEnded(shouldResume: true))
        XCTAssertEqual(
            LifecycleEvent(.audioSessionInterrupted, [AVAudioSessionInterruptionTypeKey: ended]),
            .interruptionEnded(shouldResume: false))
        XCTAssertEqual(LifecycleEvent(.audioSessionInterrupted, nil), .unreadable)
    }

    /// Only the old device becoming unavailable pauses; every other reason is read as not.
    func testOnlyTheOldDeviceBecomingUnavailableIsReadAsPausing() {
        let pausing: [AVAudioSession.RouteChangeReason] = [.oldDeviceUnavailable]
        let others: [AVAudioSession.RouteChangeReason] = [
            .unknown, .newDeviceAvailable, .categoryChange, .override, .wakeFromSleep,
            .noSuitableRouteForCategory, .routeConfigurationChange,
        ]
        for reason in pausing + others {
            XCTAssertEqual(
                LifecycleEvent(
                    .audioRouteChanged, [AVAudioSessionRouteChangeReasonKey: reason.rawValue]),
                .routeChanged(oldDeviceUnavailable: pausing.contains(reason)), "\(reason.rawValue)")
        }
    }

    func testARateChangeIsTheInterruptionsOnlyWhenItsReasonSaysSo() {
        let key = AVPlayer.rateDidChangeReasonKey
        XCTAssertEqual(
            LifecycleEvent(
                .playerRateChanged,
                [key: AVPlayer.RateDidChangeReason.audioSessionInterrupted.rawValue]),
            .rateChanged(byInterruption: true))
        XCTAssertEqual(
            LifecycleEvent(
                .playerRateChanged, [key: AVPlayer.RateDidChangeReason.setRateCalled.rawValue]),
            .rateChanged(byInterruption: false))
        XCTAssertEqual(
            LifecycleEvent(.playerRateChanged, nil), .rateChanged(byInterruption: false))
    }

    /// Video while wanting to play holds it; audio, an item not ready, or a paused player do not.
    func testTheIdleTimerIsHeldOnlyWhileAPictureIsWantedToPlay() {
        let picture = CGSize(width: 1_920, height: 1_080)
        XCTAssertTrue(LifecycleBinding.holdsIdleTimer(wantsToPlay: true, presentationSize: picture))
        XCTAssertFalse(
            LifecycleBinding.holdsIdleTimer(wantsToPlay: false, presentationSize: picture))
        XCTAssertFalse(LifecycleBinding.holdsIdleTimer(wantsToPlay: true, presentationSize: .zero))
        XCTAssertFalse(LifecycleBinding.holdsIdleTimer(wantsToPlay: true, presentationSize: nil))
    }

    /// The session is activated by the first player that wants to play and deactivated by the last
    /// to stop — a paused player beside a playing one deactivates nothing — and the idle timer is
    /// held the same way.
    func testTheSessionAndTheIdleTimerAreHeldWhileAnyPlayerNeedsThem() {
        let platform = RecordingLifecyclePlatform()
        let shared = SharedLifecycle(platform: platform)
        let first = shared.issueToken()
        let second = shared.issueToken()

        shared.state(first, wantsToPlay: true, showsVideo: true)
        shared.state(second, wantsToPlay: true, showsVideo: true)
        shared.state(first, wantsToPlay: false, showsVideo: false)
        XCTAssertEqual(platform.calls, [.activate, .holdIdleTimer])

        shared.withdraw(second)
        XCTAssertEqual(
            platform.calls, [.activate, .holdIdleTimer, .deactivate, .releaseIdleTimer])
    }

    /// An interrupted session is inactive, so the next player to want it activates it again
    /// rather than the count assuming it is still up — and nothing deactivates what the system did.
    func testASessionTheSystemInterruptedIsActivatedAgainOnTheNextNeed() {
        let platform = RecordingLifecyclePlatform()
        let shared = SharedLifecycle(platform: platform)
        let token = shared.issueToken()

        shared.state(token, wantsToPlay: true, showsVideo: false)
        shared.sessionWasInterrupted()
        shared.state(token, wantsToPlay: false, showsVideo: false)
        shared.state(token, wantsToPlay: true, showsVideo: false)

        XCTAssertEqual(platform.calls, [.activate, .activate])
    }

    /// The system platform activates the session off the main thread (#494) — the call returns
    /// while an activation the audio server is slow to answer is still blocked — and performs the
    /// calls in the order they were made, so a deactivation never overtakes the activation before
    /// it however long that activation takes.
    func testTheSystemPlatformActivatesOffTheMainThreadInTheOrderAsked() {
        let session = RecordingAudioSession(holding: .activate)
        let queue = DispatchQueue(label: "LifecycleRulesTests.audio-session")
        let platform = SystemLifecyclePlatform(queue: queue, audioSession: session.call)

        platform.activateAudioSession()
        platform.deactivateAudioSession()
        platform.activateAudioSession()
        // The main thread got here with the first activation still blocked in the audio server.
        XCTAssertTrue(session.heldCallIsBlocked())

        session.releaseHeldCall()
        queue.sync {}
        XCTAssertEqual(session.calls.map(\.call), [.configure, .activate, .deactivate, .activate])
        XCTAssertEqual(session.calls.map(\.onMainThread), [false, false, false, false])
    }

    /// Through the shared count, as every player reaches it: the category is set as the platform is
    /// made, before any player wants to play, so audio that starts before the queued activation runs
    /// starts in `.playback`; and each change the count makes is performed once, as on the main
    /// thread before.
    func testTheCategoryIsSetBeforeAnyActivationAndEachChangeIsPerformedOnce() {
        let session = RecordingAudioSession(holding: nil)
        let queue = DispatchQueue(label: "LifecycleRulesTests.audio-session")
        let shared = SharedLifecycle(
            platform: SystemLifecyclePlatform(queue: queue, audioSession: session.call))
        queue.sync {}
        XCTAssertEqual(session.calls.map(\.call), [.configure])

        let first = shared.issueToken()
        let second = shared.issueToken()
        shared.state(first, wantsToPlay: true, showsVideo: false)
        shared.state(second, wantsToPlay: true, showsVideo: false)
        shared.withdraw(first)
        shared.withdraw(second)

        queue.sync {}
        XCTAssertEqual(session.calls.map(\.call), [.configure, .activate, .deactivate])
    }
}

/// The audio session's half of ``SystemLifecyclePlatform``, recording each call and whether it was
/// made on the main thread, and holding the first call of one kind until released, as an audio
/// server slow to answer `setActive` would.
private final class RecordingAudioSession: @unchecked Sendable {

    struct Recorded: Equatable {
        let call: SystemLifecyclePlatform.SessionCall
        let onMainThread: Bool
    }

    private let held: SystemLifecyclePlatform.SessionCall?
    private let release = DispatchSemaphore(value: 0)
    private let blocked = DispatchSemaphore(value: 0)

    // Guards `recorded` and `holding`: the calls arrive on the platform's queue, the reads on the
    // main thread.
    private let lock = NSLock()
    private var recorded: [Recorded] = []
    private var holding = true

    init(holding call: SystemLifecyclePlatform.SessionCall?) {
        held = call
    }

    var calls: [Recorded] { lock.withLock { recorded } }

    /// Waits, bounded, until the held call has arrived and is being held: a ceiling, not a duration
    /// asserted (`docs/testing.md`).
    func heldCallIsBlocked() -> Bool { blocked.wait(timeout: .now() + 5) == .success }

    func releaseHeldCall() { release.signal() }

    var call: @Sendable (SystemLifecyclePlatform.SessionCall) -> Void {
        { [self] call in
            let hold: Bool = lock.withLock {
                recorded.append(Recorded(call: call, onMainThread: Thread.isMainThread))
                guard holding, call == held else { return false }
                holding = false
                return true
            }
            if hold {
                blocked.signal()
                release.wait()
            }
        }
    }
}

/// A ``LifecyclePlatform`` that records what it was asked, in order.
@MainActor
final class RecordingLifecyclePlatform: LifecyclePlatform {

    enum Call: Equatable { case activate, deactivate, holdIdleTimer, releaseIdleTimer }

    private(set) var calls: [Call] = []

    func activateAudioSession() { calls.append(.activate) }
    func deactivateAudioSession() { calls.append(.deactivate) }
    func setIdleTimerDisabled(_ disabled: Bool) {
        calls.append(disabled ? .holdIdleTimer : .releaseIdleTimer)
    }
}
