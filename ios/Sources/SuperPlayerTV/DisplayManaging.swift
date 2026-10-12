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

/// **The one package-scoped protocol tvOS display criteria are reached through** — the platform
/// call stubbed at one seam per feature, as `docs/ios-plan.md` §5 and ADR-0021 rule 5 have it, the
/// `LifecyclePlatform` and `FairPlayKeySession` shape.
///
/// The system's is `SystemDisplayManager`, over the key window's `AVDisplayManager`, and exists on
/// tvOS alone, because `AVDisplayManager` and `AVDisplayCriteria` are unavailable on iOS. Tests
/// state a display through a stand-in on either platform, which is why nothing here names either
/// type: a criteria value is ``DisplayCriteria``, opaque, and what the binding does with one —
/// prefer it, withdraw it, never compare its contents — needs nothing more. **Whether a panel then
/// switches, how long it was blank and what the viewer's *Match Content* setting made of the request
/// are a device's** (`docs/testing.md`, *A television on tvOS*).
@MainActor
package protocol DisplayManaging: AnyObject, Sendable {

    /// Whether the viewer's settings allow a display mode switch at all — `AVDisplayManager`'s
    /// `isDisplayCriteriaMatchingEnabled`, *Match Content*'s dynamic range or frame rate. Read and
    /// reported, never acted on: a preference asked while it is off is the system's to ignore.
    var isDisplayCriteriaMatchingEnabled: Bool { get }

    /// Whether the display is between modes, and may be blank — `isDisplayModeSwitchInProgress`.
    var isDisplayModeSwitchInProgress: Bool { get }

    /// The criteria `asset` prefers, loaded — `AVAsset.load(.preferredDisplayCriteria)` — or nil
    /// where it states none or the load failed. Asynchronous because the asset may not have loaded
    /// the tracks the criteria are read from yet.
    func preferredCriteria(of asset: AVAsset) async -> DisplayCriteria?

    /// Asks the display for `criteria`, or for a mode suited to everything (nil) — the setter of
    /// `AVDisplayManager.preferredDisplayCriteria`, which "will only be honored when user settings
    /// allow it".
    ///
    /// ref: https://developer.apple.com/documentation/avkit/avdisplaymanager/preferreddisplaycriteria
    func setPreferredCriteria(_ criteria: DisplayCriteria?)

    /// Registers `handler` for one of the display's ``DisplaySignal/Scope/display`` signals,
    /// delivered on the main actor, until the returned registration is released.
    func observe(_ signal: DisplaySignal, _ handler: @escaping @MainActor @Sendable () -> Void)
        -> DisplayObservation
}

/// The criteria a display is asked for: on tvOS an `AVDisplayCriteria`, and in a test whatever the
/// stand-in display made. Opaque, so that the protocol names no type iOS lacks.
package struct DisplayCriteria: Sendable {

    /// What the platform made. `AVDisplayCriteria` is declared `NS_SWIFT_SENDABLE`.
    package let value: any Sendable

    package init(_ value: any Sendable) {
        self.value = value
    }
}

/// **The display binding's register**: every notification `DisplayCriteriaMatching` observes, in
/// this one file — ADR-0020 rule 13's register, kept by this product as `SuperPlayerTelemetry`
/// keeps `CollectorSignals.swift`, because none of these is core's to observe.
///
/// `DisplayCriteriaMatchingTests` counts them: on a player configured with a TV output, each
/// ``Scope/display`` signal once for the player's life and each ``Scope/item`` signal once while
/// an item is current, and none once the player is released. A player configured without one makes
/// no binding, so registers none (`OutputPaysNothingTests` in core counts that half). The same
/// test scans this product's sources, so a notification named or registered anywhere but here
/// fails it.
package enum DisplaySignal: CaseIterable, Hashable, Sendable {

    /// The current item reached its end: the criteria are withdrawn, so the display goes back to a
    /// mode suited to the interface the viewer returns to.
    case itemPlayedToEnd

    /// The current item's time jumped: after an end, the viewer playing it again, which asks for its
    /// criteria again.
    case itemTimeJumped

    /// A display mode switch began — `AVDisplayManagerModeSwitchStartNotification`.
    case modeSwitchStarted

    /// A display mode switch completed — `AVDisplayManagerModeSwitchEndNotification`.
    case modeSwitchEnded

    /// The viewer's *Match Content* settings changed —
    /// `AVDisplayManagerModeSwitchSettingsChangedNotification`.
    case matchingSettingsChanged

    /// What a signal is registered on, and so for how long.
    package enum Scope: Sendable {
        /// The current item, while it is current.
        case item
        /// The display, through ``DisplayManaging/observe(_:_:)``, for the player's whole life.
        case display
    }

    package var scope: Scope {
        switch self {
        case .itemPlayedToEnd, .itemTimeJumped: .item
        case .modeSwitchStarted, .modeSwitchEnded, .matchingSettingsChanged: .display
        }
    }
}

/// One registration of a ``DisplaySignal``, removed when this object is released — core's
/// `Observation`, for this product's register.
package final class DisplayObservation {

    package let signal: DisplaySignal

    /// The notification centre's token, or nil for a registration a stand-in display holds itself.
    private let token: (any NSObjectProtocol)?

    /// A registration with nothing to remove: what a stand-in display hands back, counting it by
    /// whether the binding still holds it.
    package init(signal: DisplaySignal) {
        self.signal = signal
        self.token = nil
    }

    private init(signal: DisplaySignal, token: any NSObjectProtocol) {
        self.signal = signal
        self.token = token
    }

    /// Registers `handler` for `signal` as posted by `object` — an ``DisplaySignal/Scope/item``
    /// signal by the item, a ``DisplaySignal/Scope/display`` one by any object (nil), since the
    /// window's manager that posts it is found late — delivered on the main queue, the binding's
    /// actor, so the handler runs in its isolation. The one place in this product a notification is
    /// named and registered, which `DisplayCriteriaMatchingTests` holds by a scan.
    @MainActor
    static func register(
        _ signal: DisplaySignal, postedBy object: AnyObject?,
        _ handler: @escaping @MainActor @Sendable () -> Void
    ) -> DisplayObservation {
        let name: Notification.Name
        switch signal {
        case .itemPlayedToEnd: name = AVPlayerItem.didPlayToEndTimeNotification
        case .itemTimeJumped: name = AVPlayerItem.timeJumpedNotification
        // ref: https://developer.apple.com/documentation/avkit/avdisplaymanagermodeswitchstartnotification
        #if os(tvOS)
            case .modeSwitchStarted: name = .AVDisplayManagerModeSwitchStart
            case .modeSwitchEnded: name = .AVDisplayManagerModeSwitchEnd
            case .matchingSettingsChanged: name = .AVDisplayManagerModeSwitchSettingsChanged
        #else
            case .modeSwitchStarted, .modeSwitchEnded, .matchingSettingsChanged:
                preconditionFailure("\(signal) is posted by AVDisplayManager, which is tvOS's")
        #endif
        }
        let token = NotificationCenter.default.addObserver(
            forName: name, object: object, queue: .main
        ) { _ in
            MainActor.assumeIsolated { handler() }
        }
        return DisplayObservation(signal: signal, token: token)
    }

    deinit {
        if let token { NotificationCenter.default.removeObserver(token) }
    }
}
