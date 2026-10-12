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

import SuperPlayerCore

/// The throughput a transport starts from when nothing recent has been measured on it in this
/// process — ADR-0009 rule 9's table, the Kotlin `ColdDefaults` value for value.
///
/// Every value is a **cold default rather than a prediction**, for the Kotlin reason: its one job is
/// to say something before the first sample, which replaces it, and guessing high costs a rebuffer
/// where guessing low costs a segment of quality. Each sits roughly a fifth under the published
/// median for its transport, and the reason and citation for each is the Kotlin constant's, cited
/// rather than restated.
///
/// **What it chooses differs on iOS.** On Android the cold default is what Media3's selector picks a
/// first rendition with. AVFoundation picks its first variant itself, from its own estimate, and
/// nothing hands it one (`docs/ios-plan.md` §3.3, the `NetworkAwareTrackSelection` row); so here the
/// cold default is what a policy is handed — with a ``ThroughputEstimate/sampleCount`` of zero, which
/// both adaptive halves read as nothing measured — and nothing more.
///
/// ref: `superplayer-abr/src/main/kotlin/com/superplayer/abr/ColdDefaults.kt`
package enum ColdDefaults {

    /// The cold default for `transport`, in bits per second.
    package static func bps(for transport: NetworkTransport) -> Int64 {
        switch transport {
        case .wifi: wifiBps
        case .ethernet: ethernetBps
        case .cellular(.nr): cellularNrBps
        case .cellular(.lte): cellularLteBps
        case .cellular(.older): cellularOlderBps
        // A hotspot is always this on iOS, since its generation is the other phone's
        // (`ConditionsBinding.transport(of:cellularGeneration:)`).
        case .cellular(nil): cellularUnknownGenerationBps
        case .unknown: unknownBps
        }
    }

    /// The Kotlin `WIFI_BPS`, 5 Mbit/s: a fifth of the mobile median as the conservative reading of
    /// "some WiFi". ref: https://www.speedtest.net/global-index
    package static let wifiBps: Int64 = 5_000_000

    /// The Kotlin `ETHERNET_BPS`, 10 Mbit/s: twice WiFi's, with no radio between the device and the
    /// line. ref: https://www.speedtest.net/global-index
    package static let ethernetBps: Int64 = 10_000_000

    /// The Kotlin `CELLULAR_NR_BPS`, 8 Mbit/s. ref: https://www.opensignal.com/reports
    package static let cellularNrBps: Int64 = 8_000_000

    /// The Kotlin `CELLULAR_LTE_BPS`, 4 Mbit/s. ref: https://www.opensignal.com/reports
    package static let cellularLteBps: Int64 = 4_000_000

    /// The Kotlin `CELLULAR_OLDER_BPS`, 400 kbit/s: 3G and 2G as one "slow".
    /// ref: https://www.opensignal.com/reports
    package static let cellularOlderBps: Int64 = 400_000

    /// LTE's value, the Kotlin `CELLULAR_UNKNOWN_GENERATION_BPS`, for its reason: a cellular network
    /// of unknown generation is far more likely LTE-or-better than older, and LTE's is the
    /// conservative one of the two. On iOS the generation needs no permission, so the unknown case is
    /// a personal hotspot rather than an app that lacks one.
    /// ref: https://www.ericsson.com/en/reports-and-papers/mobility-report
    package static let cellularUnknownGenerationBps: Int64 = cellularLteBps

    /// The Kotlin `UNKNOWN_BPS`, 1 Mbit/s: a value every ladder has a rung at or below.
    /// ref: https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
    package static let unknownBps: Int64 = 1_000_000
}
