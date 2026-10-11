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
import UIKit
import os

/// **The coordinator's observed register**: every notification, key path and path monitor
/// ``PreloadCoordinator`` registers, in this one file and nowhere else in `SuperPlayerPreload`
/// (ADR-0020 rule 13), held as core's `ObservedSignals.swift` and telemetry's
/// `CollectorSignals.swift` are.
///
/// A register of its own rather than cases in core's, for the collector's reason: what the
/// coordinator observes is the coordinator's, and a core enum naming a memory warning would be core
/// deciding what preload listens to. `PreloadSignalRegisterTests` counts the live registrations —
/// none in a process whose pool has no coordinator, which is ADR-0010 rule 13's count, each
/// ``Scope/coordinator`` signal once while one is attached, and none once it is released — and
/// fails if any other file in this product calls a registration API.
///
/// The Kotlin rules register two callbacks, `registerComponentCallbacks` and
/// `registerDefaultNetworkCallback`; the first two cases are those. The third has no Kotlin twin:
/// Media3's preload manager prepares a source on its own thread and a warm player is prepared by
/// `prepare()`, while `AVPlayer.preroll(atRate:)` may only be asked of a player whose item is ready,
/// so readiness is heard.
enum PreloadSignal: CaseIterable, Hashable, Sendable {

    /// `UIApplication.didReceiveMemoryWarningNotification`: the memory guard's release — the Kotlin
    /// `onTrimMemory` at `TRIM_MEMORY_RUNNING_LOW` and above (`PlatformRules.swift`).
    case memoryWarning

    /// An `NWPathMonitor`: Low Data Mode, read as `NWPath.isConstrained`, applied at the change —
    /// the Kotlin default-network callback (`PlatformRules.swift`).
    case networkPath

    /// A key-value observation of a warm item's `AVPlayerItem.status`: once it is ready, its player
    /// is prerolled.
    case warmItemStatus

    /// What a signal is registered on, and so for how long.
    enum Scope: Sendable {
        /// The process, from the coordinator's attachment until its release.
        case coordinator
        /// One warm item, while an idle player holds it and has not yet been prerolled.
        case warmItem
    }

    var scope: Scope {
        switch self {
        case .memoryWarning, .networkPath: .coordinator
        case .warmItemStatus: .warmItem
        }
    }
}

/// One registration of a ``PreloadSignal``, removed when this object is released — dropping it is
/// the whole of unregistering, as core's `Observation` is.
///
/// Every live one is counted process-wide in ``live``, which is what lets a test show that a pool
/// with no coordinator registered nothing: there is no coordinator to ask, so the count cannot be
/// one of its properties.
final class PreloadObservation {

    let signal: PreloadSignal

    private let registration: Registration

    private enum Registration {
        case notification(any NSObjectProtocol)
        case keyValue(NSKeyValueObservation)
        case pathMonitor(NWPathMonitor)
    }

    /// The live registrations in this process, per signal.
    static var live: [PreloadSignal: Int] { counts.withLock { $0.filter { $0.value > 0 } } }

    private static let counts = OSAllocatedUnfairLock(initialState: [PreloadSignal: Int]())

    private init(signal: PreloadSignal, registration: Registration) {
        self.signal = signal
        self.registration = registration
        Self.counts.withLock { $0[signal, default: 0] += 1 }
    }

    /// Registers `handler` for ``PreloadSignal/memoryWarning``, delivered on the main queue.
    /// Registered for the notification from any object, as the collector's is, since the
    /// application object is the only poster and a test posts it with none.
    @MainActor
    static func registerMemoryWarning(_ handler: @escaping @MainActor @Sendable () -> Void)
        -> PreloadObservation
    {
        let token = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main
        ) { _ in
            MainActor.assumeIsolated { handler() }
        }
        return PreloadObservation(signal: .memoryWarning, registration: .notification(token))
    }

    /// Registers `handler` for ``PreloadSignal/networkPath``: an `NWPathMonitor` started on the
    /// main queue, handing over whether each path — the first, as the monitor starts, included — is
    /// constrained.
    ///
    /// ref: https://developer.apple.com/documentation/network/nwpath/isconstrained
    @MainActor
    static func registerNetworkPath(_ handler: @escaping @MainActor @Sendable (Bool) -> Void)
        -> PreloadObservation
    {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            MainActor.assumeIsolated { handler(path.isConstrained) }
        }
        monitor.start(queue: .main)
        return PreloadObservation(signal: .networkPath, registration: .pathMonitor(monitor))
    }

    /// Registers `handler` for ``PreloadSignal/warmItemStatus`` on `item`, queued onto the main
    /// queue, since AVFoundation promises no thread for a key-value change; the initial value is
    /// delivered too, so an item already ready is heard at once.
    @MainActor
    static func registerStatus(
        of item: AVPlayerItem, _ handler: @escaping @MainActor @Sendable () -> Void
    ) -> PreloadObservation {
        let observation = item.observe(\.status, options: [.initial]) { _, _ in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { handler() }
            }
        }
        return PreloadObservation(signal: .warmItemStatus, registration: .keyValue(observation))
    }

    deinit {
        switch registration {
        case .notification(let token): NotificationCenter.default.removeObserver(token)
        case .keyValue(let observation): observation.invalidate()
        case .pathMonitor(let monitor): monitor.cancel()
        }
        let signal = signal
        Self.counts.withLock { $0[signal, default: 0] -= 1 }
    }
}
