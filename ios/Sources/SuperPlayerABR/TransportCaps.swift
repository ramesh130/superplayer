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

/// `PRD.md` §3.1's per-profile, per-transport caps — the Kotlin `TransportCaps`, entry for entry and
/// for the reasons written beside each entry there: a cellular ceiling is F1's explicit trade, a
/// wired or WiFi link and 5G are capped for no profile, an unknown generation is LTE, and an
/// unknown transport is capped at nothing.
///
/// The rungs are Apple's, each a height paired with the highest average bitrate the HLS authoring
/// specification recommends at it, doubled for the peak its `BANDWIDTH` may declare.
/// ref: https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
/// ref: `superplayer-abr/src/main/kotlin/com/superplayer/abr/TransportCaps.kt`
///
/// **Where it lands differs on iOS**: on the expensive-network pair rather than the plain ceiling
/// (``AdaptiveSelectionPolicy`` says why). A ``TrackSelectionPolicy`` with a nil field is uncapped on
/// that axis, the Kotlin `UNLIMITED`.
enum TransportCaps {

    /// The cap for `profile` on `transport`; ``uncapped`` where the transport imposes none.
    static func cap(for profile: PlaybackProfile, on transport: NetworkTransport)
        -> TrackSelectionPolicy
    {
        switch transport {
        // The Kotlin row: no radio to drop out on (#267), or nothing known to cap against.
        case .wifi, .ethernet, .unknown: return uncapped
        // A personal hotspot is this on iOS; LTE is the Kotlin reading of an unnamed generation.
        case .cellular(let generation):
            switch generation ?? .lte {
            // 5G is bounded by its measured estimate, not by a table entry.
            case .nr: return uncapped
            case .lte:
                switch profile {
                case .videoOnDemand, .liveLinear, .tvLeanback: return rung1080p
                case .shortForm: return rung720p
                case .dataSaver: return rung360p
                }
            case .older: return rung360p
            }
        }
    }

    static let uncapped = TrackSelectionPolicy()

    /// The top 1080p tier's 7.8 Mbit/s average, at a peak of twice that.
    static let rung1080p = TrackSelectionPolicy(
        maxVideoBitrateBps: 15_600_000, maxVideoHeightPx: 1_080)

    /// The top 720p tier's 4.5 Mbit/s average, at a peak of twice that.
    static let rung720p = TrackSelectionPolicy(maxVideoBitrateBps: 9_000_000, maxVideoHeightPx: 720)

    /// The 360p tier's 365 kbit/s average, at a peak of twice that.
    static let rung360p = TrackSelectionPolicy(maxVideoBitrateBps: 730_000, maxVideoHeightPx: 360)
}
