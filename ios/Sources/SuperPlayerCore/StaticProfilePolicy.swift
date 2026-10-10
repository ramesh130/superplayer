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

/// The numbers a profile puts on the engine, as values that name no AVFoundation type — the
/// Kotlin `PlaybackDecision`'s buffer and selection halves, cut to the knobs AVFoundation exposes
/// (`docs/ios-plan.md` §3.1: "a smaller table, since Apple exposes fewer knobs"). The policy
/// boundary (§3.3, i3) is where this grows into the shared `PlaybackDecision`; until then nothing
/// but ``StaticProfilePolicy`` makes one and nothing but `EngineBinding.swift` reads one.
///
/// Every `nil` is "Apple's own default", stated rather than restated as a number, so that a field
/// a row leaves alone reads as left alone.
struct ProfileKnobs: Hashable, Sendable {

    /// How far ahead of the playhead to buffer, in seconds, or nil for AVFoundation's automatic
    /// choice — `preferredForwardBufferDuration` at its default of zero. The Kotlin `maxBufferMs`:
    /// AVFoundation takes one preference and no floor, so a row's *ceiling* is what ports.
    var forwardBufferSeconds: TimeInterval?

    /// The highest variant bitrate to select, or nil for no ceiling — `preferredPeakBitRate`. The
    /// Kotlin `TrackSelectionPolicy.maxVideoBitrateBps`, in the same unit.
    var maxVideoBitrateBps: Int?

    /// The tallest variant to select, in pixels, or nil for no ceiling — the height of
    /// `preferredMaximumResolution`. The Kotlin `TrackSelectionPolicy.maxVideoHeightPx`: a height
    /// alone, so that a ceiling means the same on both platforms.
    var maxVideoHeightPx: Int?

    /// ``maxVideoBitrateBps`` on a network the system marks expensive, or nil to leave
    /// `preferredPeakBitRateForExpensiveNetworks` at its default. Nil in every static row: the
    /// plain ceiling applies on every network, so this one says something only where it is
    /// stricter, and a stricter ceiling on cellular is a reading of the transport — the adaptive
    /// policy's `TransportCaps` (`docs/ios-plan.md` §3.3), which is what the pair is carried for.
    var expensiveNetworkMaxVideoBitrateBps: Int?

    /// ``maxVideoHeightPx`` on a network the system marks expensive, or nil to leave
    /// `preferredMaximumResolutionForExpensiveNetworks` at its default; nil in every static row, as
    /// ``expensiveNetworkMaxVideoBitrateBps`` is.
    var expensiveNetworkMaxVideoHeightPx: Int?

    /// How far behind a live edge to play, in seconds, or nil for the playlist's own recommendation
    /// — `configuredTimeOffsetFromLive` left invalid, which AVFoundation reads as
    /// `recommendedTimeOffsetFromLive`. Nil in every static row, argued at `.liveLinear`: a target of
    /// SuperPlayer's own is the adaptive policy's `LiveLatencyPolicy`.
    var liveOffsetSeconds: TimeInterval?

    /// Whether playback skips forward to its offset from the live edge after buffering —
    /// `automaticallyPreservesTimeOffsetFromLive`, whose default is no.
    var preservesLiveOffset = false

    /// `AVPlayer.automaticallyWaitsToMinimizeStalling`, the one knob here that is the player's and
    /// not the item's; its default is yes, and no row departs from it (``StaticProfilePolicy``).
    var waitsToMinimizeStalling: Bool
}

/// The ``ProfileKnobs`` that ship today: a static per-profile lookup, internal because what a
/// consumer names is the profile and the table is free to be retuned without their code meaning
/// something different. The port of `superplayer-core`'s `StaticProfilePolicy`.
///
/// ## About the numbers
///
/// Each row says what the profile protects against, and each value that departs from Apple's
/// default says why, citing the Kotlin row it mirrors where the reason is that row's. The defaults
/// departed from are `AVPlayerItem`'s and `AVPlayer`'s own, which `ProfileTableTests` reads off a
/// stock item rather than trusting this comment: a forward buffer the system chooses, no bitrate or
/// resolution ceiling on any network, the playlist's own live offset, no offset preserved, and a
/// player that waits to minimise stalling.
///
/// Three things the Kotlin table sets have no AVFoundation knob and are not here: a start floor
/// (`bufferForPlaybackMs` and its after-rebuffer twin — AVFoundation decides when to start from its
/// own prediction, which ``ProfileKnobs/waitsToMinimizeStalling`` switches on or off but does not
/// tune), a back buffer, and retry budgets, which `docs/ios-plan.md` §3.4 says do not port.
///
/// **No row departs from `automaticallyWaitsToMinimizeStalling`.** Switching it off is the nearest
/// thing to the Kotlin short-form row's 1s start floor, and it is not that: a player that does not
/// wait also does not resume on its own after a stall, which turns a fast start into a feed row that
/// freezes — the opposite of what any profile here is for.
/// ref: AVFoundation's `AVPlayer.h`, on `rate`: "AVPlayer can reset the desired rate to 0.0 when
/// ... the playback buffer becomes empty and playback stalls while
/// automaticallyWaitsToMinimizeStalling is NO."
///
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredforwardbufferduration
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredpeakbitrate
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredmaximumresolution
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/configuredtimeoffsetfromlive
/// ref: https://developer.apple.com/documentation/avfoundation/avplayer/automaticallywaitstominimizestalling
enum StaticProfilePolicy {

    static func knobs(for profile: PlaybackProfile) -> ProfileKnobs {
        switch profile {
        // Long-form on-demand. The viewer is committed, so the thing to protect is the middle of
        // playback.
        //
        // Every knob at Apple's default. The Kotlin row departs from Media3's 50s/50s to 30s/60s,
        // and its reason is memory: without a fixed ceiling a stream keeps buffering. AVFoundation's
        // automatic forward buffer is not a fixed number to correct — it is the system's choice,
        // and the system buffers less than any preference "in order to manage resource
        // consumption" — so the Kotlin 60s here would be a guess standing in for a reading the
        // system already makes. Uncapped, as the Kotlin row is.
        // ref: AVFoundation's `AVPlayerItem.h`, on `preferredForwardBufferDuration`
        case .videoOnDemand:
            ProfileKnobs(waitsToMinimizeStalling: true)

        // Live linear. Falling behind the edge is the cost, and seconds lost to a stall are not
        // given back on their own.
        //
        // The offset from live is preserved. By default a player that stalls resumes where it
        // stopped, so every stall leaves it that much further behind the edge for the rest of the
        // session — the Kotlin row's "a live player that buffered a minute ahead would be a minute
        // behind, which is a different product". Preserved, it skips forward after buffering to
        // the distance it had when the buffering began. The cost is Apple's to state and the
        // Kotlin row's trade: on a link that cannot keep up, playback "may stall interminably"
        // rather than fall behind — a lower rebuffer margin in exchange for staying at the edge.
        // ref: AVFoundation's `AVPlayerItem.h`, on `automaticallyPreservesTimeOffsetFromLive`
        //
        // The offset itself is the playlist's recommendation (`recommendedTimeOffsetFromLive`,
        // which a playlist states with RFC 8216bis's `HOLD-BACK`), not a number of ours: the Kotlin static row sets no live
        // target either — a target is the adaptive policy's `LiveLatencyPolicy` (§3.3) — and the
        // packager knows its segment cadence where this table does not.
        //
        // The forward buffer stays automatic: on a live stream it cannot run past the edge, so the
        // offset bounds it already, and the Kotlin row's 30s ceiling exists to stop the very drift
        // the preserved offset stops here.
        // ref: https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis#section-4.4.3.8
        case .liveLinear:
            ProfileKnobs(preservesLiveOffset: true, waitsToMinimizeStalling: true)

        // Short-form feed content. Both costs are paid before the viewer has decided anything: how
        // long the first frame takes and how much was spent on a clip swiped past.
        //
        // 15s ahead, the Kotlin row's ceiling and for its reason: more than most of this content
        // *is*, so buffering further is data spent on media that will never be shown — and
        // AVFoundation's automatic choice has no way to know the item is a feed row.
        //
        // Capped at 1080 pixels tall, the Kotlin row's cap and a screen measure rather than a data
        // one: feed content is watched on a phone, where a 4K rendition is decoded, downscaled and
        // thrown away. On every network alike, because the reason is the screen and not the link.
        case .shortForm:
            ProfileKnobs(
                forwardBufferSeconds: 15, maxVideoHeightPx: 1_080, waitsToMinimizeStalling: true)

        // Lean-back television. Holding media ahead costs less and a stall costs more.
        //
        // 120s ahead, the Kotlin row's ceiling and its derivation: the room above a long-form buffer
        // is what rides out a household's broadband blip — another device saturating the line, a
        // router's restart — and Apple's top recommended 1080p tier, 7.8 Mbit/s on average, takes
        // about 134s to fill Media3's 125 MiB default video target, so 120s holds no 1080p media
        // that target would not. Departing from the automatic choice because that choice is one
        // for every device and network alike, and this row's whole argument is that a television on
        // mains power and an unmetered link is not that average. A preference and not a demand: the
        // system still buffers less where its resources require, as on the on-demand row.
        // ref: https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
        //
        // Uncapped: a television is the one screen every rung of a ladder was encoded for.
        case .tvLeanback:
            ProfileKnobs(forwardBufferSeconds: 120, waitsToMinimizeStalling: true)

        // Data saver. Every number is chosen against the viewer's data budget rather than against
        // picture quality.
        //
        // 20s ahead, the Kotlin row's ceiling: buffered media that is never watched — the viewer
        // stops, or switches away — is data spent for nothing, and this is the one profile where
        // that waste is what is minimised. A slightly higher stall risk is the trade asked for.
        //
        // 480 pixels tall and 800 kbps, the Kotlin row's ceilings: 480p is the highest rung that is
        // still unambiguously a data-saving choice on a phone-sized screen and 800 kbps a typical
        // encode of it, and both are set because a ladder may offer a high-bitrate encode of a low
        // resolution that either alone would let through.
        //
        // The expensive-network pair is left alone, as in every row. AVFoundation applies the
        // plain ceilings on every network and reads the pair only where it is stricter, so these
        // ceilings already hold on a cellular link; and a stricter one is a reading of the
        // transport, which the static table cannot see — that is the adaptive policy's
        // `TransportCaps` (§3.3).
        // ref: AVFoundation's `AVPlayerItem.h`, on `preferredPeakBitRateForExpensiveNetworks`:
        // "preferredPeakBitRate still applies unconditionally"
        case .dataSaver:
            ProfileKnobs(
                forwardBufferSeconds: 20, maxVideoBitrateBps: 800_000, maxVideoHeightPx: 480,
                waitsToMinimizeStalling: true)
        }
    }
}
