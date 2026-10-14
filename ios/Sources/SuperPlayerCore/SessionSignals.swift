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

/// **The session's observed register**: every key path and notification ``PlaybackSession``
/// observes, in this one file (ADR-0020 rule 13).
///
/// A register of its own beside the facade's `ObservedSignals.swift`, for the reason
/// `SuperPlayerTelemetry`'s `CollectorSignals.swift` is one: that file enumerates what *every*
/// player observes, and its test counts exactly that on a player with no session. A session is a
/// separate object a consumer chooses (ADR-0007 rule 1), so what it watches is registered by it, on
/// its player, for as long as it publishes that player — and a player nobody published observes
/// none of it, which is the "a choice, not a default" claim counted rather than asserted.
/// `PlaybackSessionTests` counts the live registrations — each case once while a session publishes
/// a player, once on the new player after a swap, none after a release — and core's register test
/// admits this file as the one other place in core a registration call may be written.
///
/// What is observed is what makes the Now Playing dictionary go stale: the item, its duration and
/// readiness, the rate, and a seek. The elapsed time is **not** observed: the system extrapolates it from the
/// elapsed time and rate last published, which is MediaPlayer's own contract, so a periodic time
/// observer here would be a second clock correcting the first for nothing.
///
/// ref: https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfopropertyelapsedplaybacktime
enum SessionSignal: CaseIterable, Hashable, Sendable {

    /// Key-value observation of `AVPlayer.currentItem`: new content, whose title, identity and
    /// duration replace the old.
    case currentItem

    /// Key-value observation of `AVPlayer.currentItem.duration`, which is indefinite until the item
    /// is ready and then the content's length — or stays indefinite, which is a live stream.
    case currentItemDuration

    /// Key-value observation of `AVPlayer.currentItem.status`: a live item's duration is indefinite
    /// before it is ready and after, so readiness is the change that says it is live.
    case currentItemStatus

    /// `AVPlayer.rateDidChangeNotification`: played, paused, or a speed changed — the rate the
    /// system extrapolates the elapsed time by.
    case playerRate

    /// `AVPlayerItem.timeJumpedNotification`: a seek, after which the extrapolation is wrong.
    case itemTimeJumped
}

/// One registration of a ``SessionSignal``, removed when this object is released, as the facade's
/// `Observation` is — so dropping the session's list is the whole of unregistering.
final class SessionObservation {

    let signal: SessionSignal

    private let removal: Removal

    private enum Removal {
        case notification(any NSObjectProtocol)
        case keyValue(NSKeyValueObservation)
    }

    private init(signal: SessionSignal, removal: Removal) {
        self.signal = signal
        self.removal = removal
    }

    /// Registers `handler` for every ``SessionSignal`` on `player`.
    ///
    /// Every handler is told only *that* something changed, and is called a main-queue turn later
    /// even when the change was heard on the main thread. That is not caution: the facade replaces
    /// its item before it records the request the item was made from (`SuperPlayer.load`), so a
    /// `currentItem` change read synchronously would publish new content without its title. One
    /// turn later the facade has finished, and the session reads the player as it then is — which
    /// is also why a change delivered twice, or out of order, costs nothing.
    @MainActor
    static func registerAll(
        on player: AVPlayer, _ handler: @escaping @MainActor @Sendable () -> Void
    ) -> [SessionObservation] {
        let later: @Sendable () -> Void = {
            DispatchQueue.main.async { MainActor.assumeIsolated { handler() } }
        }
        return SessionSignal.allCases.map { signal in
            switch signal {
            case .currentItem:
                let observation = player.observe(\.currentItem) { _, _ in later() }
                return SessionObservation(signal: signal, removal: .keyValue(observation))
            case .currentItemDuration:
                let observation = player.observe(\.currentItem?.duration) { _, _ in later() }
                return SessionObservation(signal: signal, removal: .keyValue(observation))
            case .currentItemStatus:
                let observation = player.observe(\.currentItem?.status) { _, _ in later() }
                return SessionObservation(signal: signal, removal: .keyValue(observation))
            case .playerRate:
                let token = NotificationCenter.default.addObserver(
                    forName: AVPlayer.rateDidChangeNotification, object: player, queue: .main
                ) { _ in later() }
                return SessionObservation(signal: signal, removal: .notification(token))
            case .itemTimeJumped:
                // Registered for every item, since the player's item changes under the session;
                // a jump on an item that is not this player's current one reads nothing new, and
                // the handler reads the player rather than the notification.
                let token = NotificationCenter.default.addObserver(
                    forName: AVPlayerItem.timeJumpedNotification, object: nil, queue: .main
                ) { _ in later() }
                return SessionObservation(signal: signal, removal: .notification(token))
            }
        }
    }

    deinit {
        switch removal {
        case .notification(let token): NotificationCenter.default.removeObserver(token)
        case .keyValue(let observation): observation.invalidate()
        }
    }
}
