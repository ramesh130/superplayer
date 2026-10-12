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
import UIKit

// The one place the platform's playback lifecycle rules become calls on the platform — the Kotlin
// `LifecycleBinding.kt`, for its reason: each is a rule of the *platform* rather than a choice about
// this playback, so none of it is decided by a policy and none of it varies by `PlaybackProfile`
// (ADR-0006 rule 1). It is on for every player, from the moment the player is made.
//
// What maps onto what (`docs/ios-plan.md` §3.1, the lifecycle row):
//
// | Android (`LifecycleBinding.kt`) | here |
// | --- | --- |
// | audio focus, requested while playing | the audio session, category `.playback` and mode `.moviePlayback`, active while any player wants to play, deactivated with `.notifyOthersOnDeactivation` |
// | transient loss pauses and resumes; permanent loss does not resume | an interruption pauses; its end resumes only where the system says `.shouldResume` *and* the interruption paused a player that was playing |
// | becoming noisy pauses | a route change whose old device became unavailable pauses; no other reason does |
// | the wake lock while playing | `UIApplication.isIdleTimerDisabled` while a player that wants to play is showing video |
// | the Wi-Fi lock | **nothing**: iOS and tvOS have no Wi-Fi lock, and an app's network stays up while it plays in the foreground or holds the audio background mode |
//
// **Background audio is the app's.** Playing on with the screen locked needs the `audio` entry in
// the *app's* `UIBackgroundModes`; the library declares nothing in anyone's `Info.plist`, as the
// Android library declares no foreground-service permission (ADR-0007 rule 4). Without it, AVPlayer
// pauses when the app is backgrounded, which this binding hears as a rate change like any other.
//
// The platform calls are behind one package-scoped protocol, `LifecyclePlatform` (ADR-0021 rule 5's
// #402 addendum), so `check` can drive an interruption and a route change by posting the real notifications and
// read what the binding asked of the platform without a device.

/// The platform half of the lifecycle: the process's audio session and its idle timer.
///
/// One protocol for the feature rather than one per call (ADR-0021 rule 5), so the stub a test
/// substitutes is a seam a reviewer reads whole. Nothing here throws: what the system does when it
/// refuses an activation is interrupt the session, which the binding hears (``LifecycleBinding``).
@MainActor
package protocol LifecyclePlatform: AnyObject {

    /// Sets the playback category and activates the audio session. Asked on the main actor, it need
    /// not be performed there, and the system's is not (``SystemLifecyclePlatform``); the calls are
    /// performed in the order asked.
    func activateAudioSession()

    /// Deactivates the audio session, telling other apps they may resume, after every activation
    /// asked before it.
    func deactivateAudioSession()

    /// Holds (`true`) or releases (`false`) the idle timer, which dims and locks the screen.
    func setIdleTimerDisabled(_ disabled: Bool)
}

/// The system's ``LifecyclePlatform``: `AVAudioSession.sharedInstance()` and `UIApplication.shared`.
///
/// **The session is configured, activated and deactivated off the main thread** (#494), on one
/// serial queue of this object's. Apple documents activation as a call that can block while the
/// audio server answers, and the session reports one made on the main thread itself — observed on an
/// iPhone as `AVAudioSession_iOS.mm:978 This method can lead to UI unresponsiveness if called on the
/// main thread` (`devicelab/test/fixtures/ios/xcodebuild-test-moq-smoke.log`) — which is where every
/// player reaches it, on the main actor, when it starts wanting to play. The queue is serial so the
/// calls are performed in the order ``SharedLifecycle`` made them: a deactivation never overtakes
/// the activation before it, nor an activation the deactivation before it. The count already calls
/// only on a change, so the queue holds alternating calls and never two alike in a row.
///
/// What ADR-0006 rule 1 needs is the session in `.playback` and active before media is heard. The
/// activation was never a step of `play()`: it answers the rate change `play()` posts, after
/// AVFoundation has started toward the media on its own threads. Done synchronously it still
/// finished inside that main-actor turn, which in practice was before any audio; on the queue there
/// is a window in which audio can start first. Two things close what that window could cost. The
/// category is set when this object is made, first on the queue and so before any activation —
/// that is, as the first player is made and not when it plays — so audio that does start first
/// starts in `.playback` rather than in the default `.soloAmbient`, which the silent switch mutes.
/// And audio started on an inactive session activates it implicitly, so what the window can cost is
/// the order of two calls and not the audio.
///
/// One window is new and is left open. An activation still queued when an interruption begins runs
/// after the system deactivated the session for it. During a call — the interruption that matters —
/// the system refuses it, as it would refuse any activation then; were one granted, the session
/// would stay active with every player paused until the next play, without telling another app it
/// may resume. A guard would need the queue to know about interruptions the main actor hears, which
/// is a second count of one fact.
///
/// ref: https://developer.apple.com/documentation/avfaudio/avaudiosession/setactive(_:options:)
/// ref: https://developer.apple.com/library/archive/documentation/Audio/Conceptual/AudioSessionProgrammingGuide/ConfiguringanAudioSession/ConfiguringanAudioSession.html
@MainActor
final class SystemLifecyclePlatform: LifecyclePlatform {

    /// One call on the process's audio session.
    enum SessionCall: Equatable, Sendable {
        /// The category and mode, set before any activation.
        case configure
        case activate
        case deactivate
    }

    /// Performs every session call, one at a time and in the order asked. User-initiated, because an
    /// activation answers a viewer pressing play and the media waits on it.
    private let queue: DispatchQueue

    /// Performs one call, blocking until the system answers; run on ``queue`` and nowhere else. The
    /// shipped one is ``systemAudioSession(_:)``. A test hands a recorder here to read the order and
    /// the thread of the calls; what the audio server does with them stays the device's (ADR-0021
    /// rule 5's #402 addendum), and the one protocol for the feature is still ``LifecyclePlatform``.
    private let audioSession: @Sendable (SessionCall) -> Void

    init(
        queue: DispatchQueue = DispatchQueue(
            label: "com.superplayer.audio-session", qos: .userInitiated),
        audioSession: @escaping @Sendable (SessionCall) -> Void = systemAudioSession
    ) {
        self.queue = queue
        self.audioSession = audioSession
        queue.async { audioSession(.configure) }
    }

    func activateAudioSession() {
        queue.async { [audioSession] in audioSession(.activate) }
    }

    func deactivateAudioSession() {
        queue.async { [audioSession] in audioSession(.deactivate) }
    }

    /// The calls themselves.
    ///
    /// `.playback`, because playing media is the app's purpose while it plays — audio continues with
    /// the silent switch on and, where the app declares the background mode, with the screen locked
    /// — and `.moviePlayback`, the mode for video content, which lets the system tune the route's
    /// signal processing for it. One mode for every item, because the session is the process's and
    /// is set before an item says whether it carries video. It is set again with each activation,
    /// as it always was, so a category the app changed between two plays is put back for the next.
    ///
    /// `.notifyOthersOnDeactivation`, so the app this player interrupted — a podcast, a music
    /// player — is told it may resume: the Android rule of abandoning focus when playback stops.
    ///
    /// ref: https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playback
    /// ref: https://developer.apple.com/documentation/avfaudio/avaudiosession/mode-swift.struct/movieplayback
    /// ref: https://developer.apple.com/documentation/avfaudio/avaudiosession/setactiveoptions/notifyothersondeactivation
    nonisolated static func systemAudioSession(_ call: SessionCall) {
        let session = AVAudioSession.sharedInstance()
        switch call {
        case .configure:
            try? session.setCategory(.playback, mode: .moviePlayback)
        case .activate:
            // A refusal is not swallowed so much as answered elsewhere: the system refuses an
            // activation only while something with priority holds the audio (a call), and AVPlayer
            // is then held silent and the session interrupted — which the binding hears and handles.
            try? session.setCategory(.playback, mode: .moviePlayback)
            try? session.setActive(true)
        case .deactivate:
            // Fails only while audio I/O is still running in this process, which the shared count
            // rules out for SuperPlayer's own players; a consumer's other audio keeps the session
            // up, which is what it would want.
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    func setIdleTimerDisabled(_ disabled: Bool) {
        UIApplication.shared.isIdleTimerDisabled = disabled
    }
}

/// The process's audio session and idle timer, held while *any* player needs them.
///
/// Both are process-wide, and a player is not: a feed plays one row and preloads the rest, and a
/// player paused beside one that is playing must not deactivate the session under it, nor let the
/// screen dim under a video it is not showing. So each ``LifecycleBinding`` states what it needs
/// and this object makes the platform call only when the first need arrives or the last one leaves.
/// A `RealtimePlayer` states its needs here too, through the `package` half below, so one session
/// and one idle timer are counted across both player types (ADR-0020 rule 11).
@MainActor
package final class SharedLifecycle {

    /// The system's, which every player a consumer makes shares.
    package static let system = SharedLifecycle(platform: SystemLifecyclePlatform())

    private let platform: any LifecyclePlatform

    /// Bindings that want to play, and those of them showing video, by ``LifecycleBinding`` token.
    private var playing: Set<UInt64> = []
    private var showingVideo: Set<UInt64> = []

    /// What the platform was last told, so a call is made on a change and never repeated.
    private var sessionActive = false
    private var idleTimerHeld = false

    private var nextToken: UInt64 = 0

    package init(platform: any LifecyclePlatform) {
        self.platform = platform
    }

    /// A token naming one binding for its life: never reused, unlike an object's address, which a
    /// binding made after another's release may take before the release has been withdrawn here.
    package func issueToken() -> UInt64 {
        nextToken += 1
        return nextToken
    }

    /// What the binding `token` needs now.
    package func state(_ token: UInt64, wantsToPlay: Bool, showsVideo: Bool) {
        if wantsToPlay { playing.insert(token) } else { playing.remove(token) }
        if showsVideo { showingVideo.insert(token) } else { showingVideo.remove(token) }
        reconcile()
    }

    /// The binding `token` is gone and needs nothing.
    package func withdraw(_ token: UInt64) {
        state(token, wantsToPlay: false, showsVideo: false)
    }

    /// The system deactivated the session for an interruption (`AVAudioSession.h`: an interrupted
    /// session is inactive), so it is activated again on the next need rather than assumed to be up.
    func sessionWasInterrupted() {
        sessionActive = false
    }

    private func reconcile() {
        let wantsSession = !playing.isEmpty
        if wantsSession != sessionActive {
            sessionActive = wantsSession
            if wantsSession {
                platform.activateAudioSession()
            } else {
                platform.deactivateAudioSession()
            }
        }
        let wantsIdleTimer = !showingVideo.isEmpty
        if wantsIdleTimer != idleTimerHeld {
            idleTimerHeld = wantsIdleTimer
            platform.setIdleTimerDisabled(wantsIdleTimer)
        }
    }
}

/// One player's lifecycle: hears the ``ObservedSignal/Scope/player`` signals, pauses and resumes the
/// player, and tells ``SharedLifecycle`` what it needs.
@MainActor
final class LifecycleBinding {

    private let player: AVPlayer
    private let shared: SharedLifecycle
    private let token: UInt64

    /// The registrations of the ``ObservedSignal/Scope/player`` signals, for the player's life.
    private(set) var observations: [Observation] = []

    /// Whether the player was last asked to play, by anyone but an interruption.
    ///
    /// `AVPlayer.rate` is the rate *asked for* (`AVPlayer.h`: it stays non-zero while the player
    /// waits for data), so a stall with intent to play keeps the session and the idle timer — the
    /// Android wake lock's own rule, that a rebuffer needs the device awake in order to end. A
    /// change whose reason is an interruption is not counted, because AVPlayer zeroes the rate for
    /// an interruption itself and in no promised order with the notification: without the reason,
    /// the player that was playing could already read as paused when the interruption is heard.
    private var wantsToPlay = false

    /// Whether the interruption in progress paused a player that was playing, the one case its end
    /// resumes.
    private var resumeAfterInterruption = false

    init(player: AVPlayer, shared: SharedLifecycle) {
        self.player = player
        self.shared = shared
        self.token = shared.issueToken()
        observations = ObservedSignal.allCases.filter { $0.scope == .player }.map { signal in
            Observation.register(signal, on: player) { [weak self] userInfo in
                self?.heard(LifecycleEvent(signal, userInfo))
            }
        }
    }

    /// Stops hearing the signals and withdraws what this player needed, so a released player holds
    /// neither the session nor the idle timer however it was left (`SuperPlayer.release()`).
    func release() {
        observations = []
        shared.withdraw(token)
    }

    /// Puts the binding back in a fresh player's idle state for ``SuperPlayer``'s `resetForReuse`
    /// (#404): wanting nothing, with no interruption to resume after, and saying so to
    /// ``SharedLifecycle`` at once — not on the rate change the reset's pause may post later, if
    /// at all, since a recycled player that went on holding the session or the idle timer would be
    /// the last user's need outliving its row. The observations stay: they are the player's, for
    /// its whole life, as a fresh player's are.
    func resetForReuse() {
        wantsToPlay = false
        resumeAfterInterruption = false
        publish()
    }

    deinit {
        // `deinit` is not isolated to the main actor — an isolated `deinit` needs a runtime newer
        // than the iOS 17 floor — so the release is withdrawn one main-actor turn after it.
        Task { @MainActor [shared, token] in shared.withdraw(token) }
    }

    private func heard(_ event: LifecycleEvent) {
        switch event {
        case .interruptionBegan:
            resumeAfterInterruption = wantsToPlay
            // Cleared here rather than left to the pause below: if AVPlayer zeroed the rate for
            // the interruption first, that pause changes nothing and reports no change.
            wantsToPlay = false
            shared.sessionWasInterrupted()
            player.pause()
        case .interruptionEnded(let shouldResume):
            if shouldResume && resumeAfterInterruption { player.play() }
            resumeAfterInterruption = false
        case .routeChanged(let oldDeviceUnavailable):
            if oldDeviceUnavailable { player.pause() }
        case .rateChanged(let byInterruption):
            if !byInterruption { wantsToPlay = player.rate != 0 }
        case .timeControlStatusChanged, .unreadable:
            break
        }
        publish()
    }

    /// Tells ``SharedLifecycle`` what this player needs now. The idle timer's half is re-read on
    /// every signal — `timeControlStatus` is among them for this alone — because whether an item
    /// carries video is known only once it is ready, which is after a consumer's `play()` and
    /// before its media flows.
    private func publish() {
        shared.state(
            token, wantsToPlay: wantsToPlay,
            showsVideo: Self.holdsIdleTimer(
                wantsToPlay: wantsToPlay, presentationSize: player.currentItem?.presentationSize))
    }

    /// Whether the idle timer is held: while the player wants to play an item with a picture.
    ///
    /// "Video" is the item's presentation size, which `AVPlayerItem.h` documents as zero for an
    /// item with no video — so audio-only content lets the screen lock, which is the point of the
    /// distinction, and an item not yet ready (size unknown, so zero) holds nothing until it is.
    nonisolated static func holdsIdleTimer(wantsToPlay: Bool, presentationSize: CGSize?) -> Bool {
        guard wantsToPlay, let size = presentationSize else { return false }
        return size.width > 0 && size.height > 0
    }
}

/// What a ``ObservedSignal/Scope/player`` signal said, read off the notification's `userInfo` where
/// it is heard, and tested on the dictionaries the platform posts without a player.
enum LifecycleEvent: Equatable, Sendable {

    /// ref: https://developer.apple.com/documentation/avfaudio/handling-audio-interruptions
    case interruptionBegan
    case interruptionEnded(shouldResume: Bool)

    /// Whether the old device became unavailable — headphones unplugged, a Bluetooth output lost —
    /// the one reason that pauses, because the audio would otherwise come out of the speaker and
    /// announce to a room what someone was listening to privately. A new device, a category change
    /// or an override changes nothing a listener did not choose.
    ///
    /// ref: https://developer.apple.com/documentation/avfaudio/responding-to-audio-route-changes
    case routeChanged(oldDeviceUnavailable: Bool)

    /// Whether the change was AVPlayer's own, for an interruption.
    ///
    /// ref: AVFoundation's `AVPlayer.h`, `AVPlayerRateDidChangeReasonAudioSessionInterrupted`
    case rateChanged(byInterruption: Bool)

    case timeControlStatusChanged

    /// An interruption naming no type it knows — nothing to act on.
    case unreadable

    /// On the main actor, where every signal is heard, because AVFoundation declares
    /// `AVPlayer.rateDidChangeReasonKey` there.
    @MainActor
    init(_ signal: ObservedSignal, _ userInfo: [AnyHashable: Any]?) {
        switch signal {
        case .audioSessionInterrupted:
            let type = (userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(
                AVAudioSession.InterruptionType.init(rawValue:))
            let options = AVAudioSession.InterruptionOptions(
                rawValue: userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            switch type {
            case .began: self = .interruptionBegan
            case .ended: self = .interruptionEnded(shouldResume: options.contains(.shouldResume))
            default: self = .unreadable
            }
        case .audioRouteChanged:
            let reason = (userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(
                AVAudioSession.RouteChangeReason.init(rawValue:))
            self = .routeChanged(oldDeviceUnavailable: reason == .oldDeviceUnavailable)
        case .playerRateChanged:
            let reason = (userInfo?[AVPlayer.rateDidChangeReasonKey] as? String).map(
                AVPlayer.RateDidChangeReason.init(rawValue:))
            self = .rateChanged(byInterruption: reason == .audioSessionInterrupted)
        case .playerTimeControlStatus:
            self = .timeControlStatusChanged
        case .itemPlayedToEnd, .itemTimeJumped, .frameOutputItemTimeJumped, .resilienceItemStatus,
            .resilienceItemFailedToPlayToEnd, .decisionNetworkPath, .decisionRateChanged,
            .decisionTimeControlStatus, .decisionItemTimeJumped, .decisionItemDuration,
            .decisionAccessLogEntry, .resilienceMediaServicesReset, .resilienceRateChanged,
            .resilienceItemLiveEdge, .resilienceItemRequestMetrics, .resilienceItemErrorLogEntry:
            // An item's signals are never registered on a player, and the policy's are never
            // handed to the lifecycle (``Observation``).
            self = .unreadable
        }
    }
}
