/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.abr

import com.superplayer.core.CellularGeneration
import com.superplayer.core.NetworkTransport
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.TrackSelectionPolicy

/**
 * `PRD.md` §3.1's per-profile, per-transport bitrate caps: the ceiling a profile accepts on each
 * kind of link, before the profile's own cap and the network's measured rate narrow it further.
 *
 * A cellular ceiling is not a WiFi ceiling. This is F1's explicit trade — slightly lower quality
 * for significantly fewer interruptions on the link where an interruption is likeliest and every
 * byte is metered — and the benchmark must show it *as a bitrate loss* beside the rebuffer win
 * (#103), which is why the cellular entries below are real and not the profile's own restated.
 *
 * The rungs are Apple's, because a ladder in the field is encoded to that table. Each entry caps
 * both axes: the height names the rung, and the bitrate is what the transport is really being
 * asked to carry. The bitrate a selector compares is the rung's *declared* one, which Media3 reads
 * as the peak (`BANDWIDTH`) where a manifest states it, so every height here is paired with the
 * *highest* average bitrate the specification recommends for it, doubled where the specification
 * lets a peak run to twice the average: a rung encoded as the specification recommends at that
 * height passes, one encoded hotter than it is the transport's loss to refuse, and the next height
 * up is refused by the height.
 * ref: Apple, *HLS Authoring Specification for Apple Devices*, video encoding requirements — the
 * 640×360 tier at 365 kbit/s, the 1280×720 tiers up to 4 500 kbit/s, the 1920×1080 tiers up to
 * 7 800 kbit/s, and a peak of no more than twice the average for on-demand content:
 * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
 *
 * A generation the platform did not name is treated as LTE — the generation most cellular sessions
 * are on, and the middle entry rather than either edge. An unknown transport is capped at nothing:
 * a cap on a link nothing is known about is a guess dressed as a policy.
 */
internal object TransportCaps {

    /** The cap for [profile] on [transport]; [UNCAPPED] where the transport itself imposes none. */
    fun capFor(profile: PlaybackProfile, transport: NetworkTransport): TrackSelectionPolicy = when (transport) {
        NetworkTransport.Wifi, NetworkTransport.Ethernet, NetworkTransport.Unknown -> UNCAPPED

        is NetworkTransport.Cellular -> when (transport.generation ?: CellularGeneration.LTE) {
            // 5G is not capped on its own account. Its delivered rate ranges from below LTE's to
            // far above WiFi's, so a table entry would be wrong more often than right; the
            // measured estimate, which is per transport, is what bounds it.
            CellularGeneration.NR -> UNCAPPED

            CellularGeneration.LTE -> when (profile) {
                // Full HD is where LTE stops paying: `ColdDefaults.CELLULAR_LTE_BPS` is 4 Mbit/s,
                // and a 1080p rung is one an ordinary LTE cell sustains only on a good day; the
                // 1440p and 2160p tiers above it are the ones it never does.
                PlaybackProfile.VIDEO_ON_DEMAND, PlaybackProfile.LIVE_LINEAR -> RUNG_1080P

                // A feed clip is watched for seconds; the climb to 1080p is bytes spent on a rung
                // that is abandoned before it is seen, and on a metered link that is the viewer's
                // money. 720p is the highest rung a phone in portrait shows a difference from.
                PlaybackProfile.SHORT_FORM -> RUNG_720P

                // The profile that exists to spend fewer bytes spends fewer on the link they are
                // paid for: 360p, which is the F1 loss #103's benchmark reports beside the win.
                PlaybackProfile.DATA_SAVER -> RUNG_360P
            }

            // A 3G-class link delivers under a megabit when it delivers: `ColdDefaults`' 400 kbit/s.
            // One rung above the bottom is the most any profile should ask of it.
            CellularGeneration.OLDER -> RUNG_360P
        }
    }

    val UNCAPPED: TrackSelectionPolicy = TrackSelectionPolicy(
        maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
        maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
    )

    /** The top 1080p tier's 7.8 Mbit/s average, at a peak of twice that. */
    val RUNG_1080P: TrackSelectionPolicy = TrackSelectionPolicy(maxVideoBitrateBps = 15_600_000, maxVideoHeightPx = 1_080)

    /** The top 720p tier's 4.5 Mbit/s average, at a peak of twice that. */
    val RUNG_720P: TrackSelectionPolicy = TrackSelectionPolicy(maxVideoBitrateBps = 9_000_000, maxVideoHeightPx = 720)

    /** The 360p tier's 365 kbit/s average, at a peak of twice that. */
    val RUNG_360P: TrackSelectionPolicy = TrackSelectionPolicy(maxVideoBitrateBps = 730_000, maxVideoHeightPx = 360)
}
