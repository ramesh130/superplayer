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

/// **The observed register**: every key path and notification ``SuperPlayer`` observes, in this one
/// file, and nowhere else (ADR-0020 rule 13, `docs/ios-plan.md` §3.1's last row).
///
/// It is what replaces the Android `SuperPlayerForwardingTest`. There a facade that delegates can
/// silently fail to forward a callback Media3 added; here there is nothing to forward, and what
/// can go silently wrong instead is an observation registered and never removed, or one registered
/// for a feature the configuration never named. So every registration goes through
/// ``Observation/register(_:on:_:)``, which takes a case of this enum and nothing else, and
/// `ObservedSignalRegisterTests` counts the live ones: none on a player given no request, each case
/// exactly once on the item a request is playing, still once after a replacement, and none for an
/// item played from a bare URL.
///
/// A key-value observation arrives as a case here too, with its key path, when the facade first
/// needs one; until then the register is notifications alone.
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

    /// The notification this signal is, posted by the current `AVPlayerItem`.
    var notification: Notification.Name {
        switch self {
        case .itemPlayedToEnd: AVPlayerItem.didPlayToEndTimeNotification
        case .itemTimeJumped: AVPlayerItem.timeJumpedNotification
        }
    }
}

/// One registration of an ``ObservedSignal``, removed when this object is released — so dropping
/// the facade's list of them is the whole of unregistering, and there is no path that forgets to.
///
/// Not isolated to the main actor, unlike the facade that holds it, so that its `deinit` may read
/// the token: `NotificationCenter.removeObserver(_:)` is safe from any thread.
final class Observation {

    let signal: ObservedSignal

    private let token: any NSObjectProtocol

    private init(signal: ObservedSignal, token: any NSObjectProtocol) {
        self.signal = signal
        self.token = token
    }

    /// Registers `handler` for `signal` as posted by `item`, delivered on the main queue — the
    /// facade's actor, so the handler runs in its isolation.
    @MainActor
    static func register(
        _ signal: ObservedSignal, on item: AVPlayerItem,
        _ handler: @escaping @MainActor @Sendable () -> Void
    ) -> Observation {
        let token = NotificationCenter.default.addObserver(
            forName: signal.notification, object: item, queue: .main
        ) { _ in
            MainActor.assumeIsolated { handler() }
        }
        return Observation(signal: signal, token: token)
    }

    deinit {
        NotificationCenter.default.removeObserver(token)
    }
}
