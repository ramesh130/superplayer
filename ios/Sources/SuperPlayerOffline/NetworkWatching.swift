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

import Foundation
import Network

/// Whether the network a store downloads under is there — what a licence release owed by a removal
/// waits for (ADR-0013 rule 13): the Kotlin store's `getNotMetRequirements` over `NETWORK` and
/// `NETWORK_UNMETERED`, and a second `package` seam of this product's beside `AssetDownloading`,
/// because a test cannot take the simulator's network away.
///
/// Watched only while a release is owed, so a store that owes none — every store with no protection —
/// watches nothing (ADR-0013 rule 15).
@MainActor
package protocol NetworkWatching: AnyObject {

    /// Calls `changed` with whether the requirement holds, now and on every change, on the main
    /// actor, until ``cancel()``.
    func watch(_ changed: @escaping @MainActor @Sendable (Bool) -> Void)

    /// Stops watching; nothing more is called.
    func cancel()
}

/// The shipped watch: `NWPathMonitor`'s path, read against the store's constraints.
///
/// The network the store downloads under and nothing else, for the Kotlin reason — a release is
/// small, and what holds a download's bytes is not what holds a licence given back: a satisfied
/// path, not expensive unless the store allows metered networks, and not constrained, since Low Data
/// Mode holds a download whatever the store says (`DownloadConstraints`).
///
/// ref: https://developer.apple.com/documentation/network/nwpathmonitor — `pathUpdateHandler` is
/// called with the current path on start and on every change; `isExpensive` and `isConstrained` are
/// the cellular-or-hotspot and Low Data Mode readings.
@MainActor
final class SystemNetworkWatch: NetworkWatching {

    private let constraints: DownloadConstraints
    private var monitor: NWPathMonitor?

    init(constraints: DownloadConstraints) {
        self.constraints = constraints
    }

    func watch(_ changed: @escaping @MainActor @Sendable (Bool) -> Void) {
        cancel()
        let monitor = NWPathMonitor()
        let constraints = constraints
        monitor.pathUpdateHandler = { path in
            let holds =
                path.status == .satisfied
                && (constraints.allowsExpensiveNetworkAccess || !path.isExpensive)
                && (constraints.allowsConstrainedNetworkAccess || !path.isConstrained)
            // On the main queue, as `start(queue:)` names it, so nothing arrives after `cancel()`.
            MainActor.assumeIsolated { changed(holds) }
        }
        monitor.start(queue: .main)
        self.monitor = monitor
    }

    func cancel() {
        monitor?.cancel()
        monitor = nil
    }

    /// A store let go without ``Downloads/release()`` stops watching with it.
    deinit {
        monitor?.cancel()
    }
}
