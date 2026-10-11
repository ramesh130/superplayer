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
import Network

/// **The observed register**: every key path and notification ``SuperPlayer`` observes, in this one
/// file, and nowhere else (ADR-0020 rule 13, `docs/ios-plan.md` §3.1's last row).
///
/// It is what replaces the Android `SuperPlayerForwardingTest`. There a facade that delegates can
/// silently fail to forward a callback Media3 added; here there is nothing to forward, and what
/// can go silently wrong instead is an observation registered and never removed, or one registered
/// for a feature the configuration never named. So every registration goes through one of
/// ``Observation``'s two `register` functions, which take a case of this enum and nothing else, and
/// `ObservedSignalRegisterTests` counts the live ones: on a player given no content, each
/// ``Scope/player`` signal once and nothing else; each case but the frame output's exactly once
/// while a request is playing, still once after a replacement; only the ``Scope/player`` signals
/// while an item from a bare URL is. `FrameOutputPaysNothingTests` counts the
/// ``Scope/frameOutputItem`` signal: once on a player whose frame output runs, and on no other
/// (ADR-0022 rule 9). `PolicyPaysNothingTests` counts the ``Scope/reapplicationPlayer`` and
/// ``Scope/reapplicationItem`` signals: none on a player under its profile's static policy, each once
/// on a player given a policy of its own (ADR-0009 rule 7, `DecisionReapplication.swift`).
///
/// The ``Scope/player`` signals are the lifecycle's (`LifecycleBinding.swift`, #402), registered on
/// every player as it is made and for its whole life. ADR-0006 rule 1 makes the lifecycle
/// correctness rather than a feature a configuration names, so "pays nothing" does not reach it:
/// a player whose content was handed to `avPlayer` directly is interrupted by a phone call too.
enum ObservedSignal: CaseIterable, Hashable, Sendable {

    /// The item reached its end. Remembered content that ended resumes from the beginning
    /// (``MediaRequest/StartPosition/resumeFromLastKnown``), and `AVPlayer` has no ended *state*
    /// to read at the moment of replacement — the Kotlin facade reads `STATE_ENDED` — so the end is
    /// heard as it happens.
    case itemPlayedToEnd

    /// The item's time moved discontinuously — a seek. Clears what ``itemPlayedToEnd`` recorded,
    /// because content sought back from its end has not ended: the Kotlin facade's `STATE_ENDED`
    /// is left the same way.
    case itemTimeJumped

    /// The current item's time jumped, on a player whose frame output runs: the sample-buffer layer
    /// is flushed, so frames enqueued for the old position are not shown at the new one (ADR-0022
    /// rule 5, `FramePath.swift`). A signal of its own rather than a second registration of
    /// ``itemTimeJumped``, because it is registered on every item such a player plays — a bare URL's
    /// included — and nowhere else, which is a different scope and a different count.
    case frameOutputItemTimeJumped

    /// The audio session was interrupted, or the interruption ended — a phone call, an alarm,
    /// another app's session that does not mix. The Android audio focus's transient loss and its
    /// return.
    case audioSessionInterrupted

    /// The audio route changed. One whose old device became unavailable is headphones unplugged,
    /// the Android becoming-noisy broadcast.
    case audioRouteChanged

    /// The rate the player was asked for changed, with the reason: the one reading that tells a
    /// pause the interruption made from one the viewer did (`LifecycleBinding.swift`).
    case playerRateChanged

    /// A key-value observation of `AVPlayer.timeControlStatus`: when media starts flowing, which is
    /// when an item knows whether it carries video (`LifecycleBinding.swift`, the idle timer).
    case playerTimeControlStatus

    /// The device's network path changed — an `NWPathMonitor`, the transport's reading
    /// (`ConditionsBinding.swift`). Not a notification or a key path, and in the register all the
    /// same: it is a registration that must be cancelled, and the count is what proves it is.
    case decisionNetworkPath

    /// The rate the player was asked for changed: the playback speed's reading. A registration of
    /// its own beside ``playerRateChanged`` rather than a second reader of the lifecycle's, because
    /// it exists only on a player whose policy is consulted again — a different scope and count.
    case decisionRateChanged

    /// A key-value observation of `AVPlayer.timeControlStatus`: the stall history's reading, beside
    /// the lifecycle's ``playerTimeControlStatus`` for ``decisionRateChanged``'s reason.
    case decisionTimeControlStatus

    /// The current item's time jumped: a seek, which makes the stall that follows it not a rebuffer.
    case decisionItemTimeJumped

    /// A key-value observation of `AVPlayerItem.duration`: the stream type's reading, indefinite
    /// for live content and a number once a live playlist has ended.
    case decisionItemDuration

    /// The current item's access log gained an entry: the throughput's reading.
    case decisionAccessLogEntry

    /// What a signal is registered on, and so for how long.
    enum Scope: Sendable {
        /// The current item, while it carries an identity (``SuperPlayer/setMediaRequest(_:)``).
        case identifiedItem
        /// The player and the process's audio session, for the player's whole life.
        case player
        /// The current item, whatever it carries, on a player whose frame output runs — nothing on
        /// any other player, which is ADR-0022 rule 9's count.
        case frameOutputItem
        /// The player and the device, for the player's whole life, on a player whose policy is
        /// consulted again — nothing on any other player, which is ADR-0009 rule 7's count.
        case reapplicationPlayer
        /// The current item, whatever it carries, on a player whose policy is consulted again.
        case reapplicationItem
    }

    var scope: Scope {
        switch self {
        case .itemPlayedToEnd, .itemTimeJumped: .identifiedItem
        case .frameOutputItemTimeJumped: .frameOutputItem
        case .audioSessionInterrupted, .audioRouteChanged, .playerRateChanged,
            .playerTimeControlStatus:
            .player
        case .decisionNetworkPath, .decisionRateChanged, .decisionTimeControlStatus:
            .reapplicationPlayer
        case .decisionItemTimeJumped, .decisionItemDuration, .decisionAccessLogEntry:
            .reapplicationItem
        }
    }
}

/// One registration of an ``ObservedSignal``, removed when this object is released — so dropping
/// the facade's list of them is the whole of unregistering, and there is no path that forgets to.
///
/// Not isolated to the main actor, unlike the facade that holds it, so that its `deinit` may read
/// the token: `NotificationCenter.removeObserver(_:)` and `NSKeyValueObservation.invalidate()` are
/// safe from any thread.
final class Observation {

    let signal: ObservedSignal

    private let registration: Registration

    /// What removing the registration takes.
    private enum Registration {
        case notification(any NSObjectProtocol)
        case keyValue(NSKeyValueObservation)
        case pathMonitor(NWPathMonitor)
    }

    private init(signal: ObservedSignal, registration: Registration) {
        self.signal = signal
        self.registration = registration
    }

    /// Registers `handler` for an ``ObservedSignal/Scope/identifiedItem``,
    /// ``ObservedSignal/Scope/frameOutputItem`` or ``ObservedSignal/Scope/reapplicationItem``
    /// `signal` as posted by `item`, delivered on the main queue — the facade's actor, so the
    /// handler runs in its isolation. The duration's key-value observation is queued onto the main
    /// queue, as the player's `timeControlStatus` is below, since AVFoundation promises no thread.
    @MainActor
    static func register(
        _ signal: ObservedSignal, on item: AVPlayerItem,
        _ handler: @escaping @MainActor @Sendable () -> Void
    ) -> Observation {
        let name: Notification.Name
        switch signal {
        case .itemPlayedToEnd: name = AVPlayerItem.didPlayToEndTimeNotification
        case .itemTimeJumped, .frameOutputItemTimeJumped, .decisionItemTimeJumped:
            name = AVPlayerItem.timeJumpedNotification
        case .decisionAccessLogEntry: name = AVPlayerItem.newAccessLogEntryNotification
        case .decisionItemDuration:
            let observation = item.observe(\.duration) { _, _ in
                DispatchQueue.main.async {
                    MainActor.assumeIsolated { handler() }
                }
            }
            return Observation(signal: signal, registration: .keyValue(observation))
        case .audioSessionInterrupted, .audioRouteChanged, .playerRateChanged,
            .playerTimeControlStatus, .decisionNetworkPath, .decisionRateChanged,
            .decisionTimeControlStatus:
            preconditionFailure("\(signal) is registered on a player, not on an item")
        }
        let token = NotificationCenter.default.addObserver(
            forName: name, object: item, queue: .main
        ) { _ in
            MainActor.assumeIsolated { handler() }
        }
        return Observation(signal: signal, registration: .notification(token))
    }

    /// Registers `handler` for a ``ObservedSignal/Scope/player`` `signal`, delivered on the main
    /// queue with the notification's `userInfo`; the key-value observation hands over nil, and its
    /// handler reads the player instead.
    ///
    /// The audio session's two notifications are registered as posted by
    /// `AVAudioSession.sharedInstance()`, the object `AVAudioSession.h` says posts them, so a test
    /// posts the real notification from the real object. AVFoundation promises no thread for a
    /// `timeControlStatus` change, so that handler is always queued onto the main queue rather than
    /// assumed to be on it.
    @MainActor
    static func register(
        _ signal: ObservedSignal, on player: AVPlayer,
        _ handler: @escaping @MainActor @Sendable ([AnyHashable: Any]?) -> Void
    ) -> Observation {
        let name: Notification.Name
        let object: AnyObject
        switch signal {
        case .audioSessionInterrupted:
            (name, object) = (
                AVAudioSession.interruptionNotification, AVAudioSession.sharedInstance()
            )
        case .audioRouteChanged:
            (name, object) = (
                AVAudioSession.routeChangeNotification, AVAudioSession.sharedInstance()
            )
        case .playerRateChanged, .decisionRateChanged:
            (name, object) = (AVPlayer.rateDidChangeNotification, player)
        case .playerTimeControlStatus, .decisionTimeControlStatus:
            let observation = player.observe(\.timeControlStatus) { _, _ in
                DispatchQueue.main.async {
                    MainActor.assumeIsolated { handler(nil) }
                }
            }
            return Observation(signal: signal, registration: .keyValue(observation))
        case .decisionNetworkPath:
            preconditionFailure("\(signal) is registered on the device, not on a player")
        case .itemPlayedToEnd, .itemTimeJumped, .frameOutputItemTimeJumped,
            .decisionItemTimeJumped, .decisionItemDuration, .decisionAccessLogEntry:
            preconditionFailure("\(signal) is registered on an item, not on a player")
        }
        let token = NotificationCenter.default.addObserver(
            forName: name, object: object, queue: .main
        ) { notification in
            // Unchecked, and safe: the observer was registered on the main queue, so this block is
            // already on the main thread, and `assumeIsolated` runs its closure there and then —
            // the dictionary is never touched from two threads, which is what the check is for.
            nonisolated(unsafe) let userInfo = notification.userInfo
            MainActor.assumeIsolated { handler(userInfo) }
        }
        return Observation(signal: signal, registration: .notification(token))
    }

    /// Registers `handler` for ``ObservedSignal/decisionNetworkPath``: an `NWPathMonitor` started
    /// on the main queue, so each path — the first one, delivered as the monitor starts, included —
    /// arrives in the facade's isolation. Cancelling it is the whole of unregistering.
    ///
    /// ref: https://developer.apple.com/documentation/network/nwpathmonitor
    @MainActor
    static func registerNetworkPath(
        _ handler: @escaping @MainActor @Sendable (NWPath) -> Void
    ) -> Observation {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            MainActor.assumeIsolated { handler(path) }
        }
        monitor.start(queue: .main)
        return Observation(signal: .decisionNetworkPath, registration: .pathMonitor(monitor))
    }

    deinit {
        switch registration {
        case .notification(let token): NotificationCenter.default.removeObserver(token)
        case .keyValue(let observation): observation.invalidate()
        case .pathMonitor(let monitor): monitor.cancel()
        }
    }
}
