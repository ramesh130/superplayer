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
import SuperPlayerTestkit

/// The content axis of `PRD.md` §6's matrix — what is played, and which profile arm (c) plays it
/// under — the Kotlin `Scenario`, row for row and rung for rung.
///
/// The profile is part of the scenario rather than a free axis, for the Kotlin reason: comparing
/// `liveLinear` against stock on an on-demand asset would measure a configuration nobody would ship.
/// ``vodDataSaver`` is ``vod``'s content under `dataSaver`, the one shipped profile that caps
/// quality, so the F1 trade appears as a bitrate loss beside whatever it buys.
enum Scenario: String, CaseIterable, Sendable {

    case vod
    case live
    case shortForm
    case vodDataSaver

    /// The row heading and a raw trace's `scenario` field: the Kotlin labels.
    var label: String {
        switch self {
        case .vod: "VOD"
        case .live: "live"
        case .shortForm: "short-form"
        case .vodDataSaver: "VOD (data saver)"
        }
    }

    /// The slug a trace file is named with.
    var slug: String {
        switch self {
        case .vod: "vod"
        case .live: "live"
        case .shortForm: "short-form"
        case .vodDataSaver: "vod-data-saver"
        }
    }

    /// The profile arm (c) is configured with.
    var profile: PlaybackProfile {
        switch self {
        case .vod: .videoOnDemand
        case .live: .liveLinear
        case .shortForm: .shortForm
        case .vodDataSaver: .dataSaver
        }
    }

    /// The rendition ladder, ascending, in bits per second — the Kotlin `FULL_LADDER` and
    /// `FEED_LADDER`, whose citation and argument (Apple's authoring tiers in shape, rungs chosen to
    /// straddle §6's links from 1 to 20 Mbit/s) are `Scenario.kt`'s. The feed ladder drops the top
    /// rung, because a clip watched in a portrait viewport is not published at 4.5 Mbit/s.
    var ladderBitratesBps: [Int] {
        switch self {
        case .shortForm: [365_000, 730_000, 2_000_000]
        default: [365_000, 730_000, 2_000_000, 4_500_000]
        }
    }

    /// Whether the content has a moving live edge.
    var isLive: Bool { self == .live }

    /// The top rung, which the QoE objective's rebuffer penalty is scaled to (`QoeScore`).
    var ladderTopBitrateBps: Int { ladderBitratesBps.max()! }

    /// The content this scenario is played over: `TestContent.videoLadder` long enough that no
    /// session reaches its end — `SessionMetrics.playingMs`'s precondition — or the live ladder.
    var content: TestContent {
        isLive
            ? .liveVideoLadder(bitratesBps: ladderBitratesBps)
            : .videoLadder(bitratesBps: ladderBitratesBps, segmentCount: Self.onDemandSegmentCount)
    }

    /// Two minutes of on-demand content, four times a session's span: comfortably longer, so no
    /// session stops advancing for having run out, which would overstate playing time silently.
    static let onDemandSegmentCount = 60

    /// How long a session plays past its first frame, in milliseconds of **real** time.
    ///
    /// The Kotlin sixty seconds are a `FakeClock`'s, costing CPU; here every second is a second of
    /// the simulator's wall clock, so the length is a trade the README argues against the run count.
    /// Thirty is the least that keeps the Kotlin reasons true: three `PlaybackStateSampled`
    /// intervals at the schema's ten-second cadence, so a bitrate average has more than one sample;
    /// the `lteWithDropouts` outage that ends twenty seconds in; and the handover ten seconds in.
    static let playbackMs: Int64 = 30_000
}

/// The six network profiles of `PRD.md` §6, in its order — testkit's ``NetworkProfile`` less
/// `ethernet`, which `PRD.md` §6 does not list (#267 added it for the QoE gate, not the matrix).
enum MatrixNetwork {

    static let profiles: [NetworkProfile] = [
        .stableWifi, .congestedWifi, .lteWithDropouts, .threeG, .wifiToCellularHandover,
        .highLatency,
    ]

    /// The Kotlin `NetworkProfileName` labels. Exhaustive with no default, so a profile added to
    /// testkit fails to compile here rather than reaching a report unnamed.
    static func label(_ profile: NetworkProfile) -> String {
        switch profile {
        case .stableWifi: "stable WiFi"
        case .congestedWifi: "congested WiFi"
        case .lteWithDropouts: "LTE with dropouts"
        case .threeG: "3G"
        case .wifiToCellularHandover: "WiFi→cellular"
        case .highLatency: "high latency"
        case .ethernet: "Ethernet"
        }
    }

    /// The slug a trace file is named with: the Kotlin trace files' network half.
    static func slug(_ profile: NetworkProfile) -> String {
        switch profile {
        case .stableWifi: "stable-wifi"
        case .congestedWifi: "congested-wifi"
        case .lteWithDropouts: "lte-with-dropouts"
        case .threeG: "3g"
        case .wifiToCellularHandover: "wifi-cellular"
        case .highLatency: "high-latency"
        case .ethernet: "ethernet"
        }
    }

    /// The cellular profiles, the F1 trade's section.
    static let cellular: [NetworkProfile] = [.threeG, .lteWithDropouts, .wifiToCellularHandover]
}
