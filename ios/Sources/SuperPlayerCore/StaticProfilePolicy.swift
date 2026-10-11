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

/// The ``PlaybackPolicy`` that ships as the default: a static per-profile lookup, the port of
/// `superplayer-core`'s `StaticProfilePolicy`.
///
/// It ignores the conditions it is given and returns its profile's documented decision every time,
/// deliberately rather than unfinished — ``PlaybackPolicy`` argues the case — and it is internal
/// because what a consumer names is the profile, and the table is free to be retuned without their
/// code meaning something different. ``PlaybackProfile/policy`` hands one out without naming the
/// type. A player whose policy is this one observes nothing (`DecisionReapplication.swift`).
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
/// The Kotlin buffer half's start floors, back buffer and keyframe retention have no knob here and
/// no field (``BufferPolicy`` says why). The halves no iOS player reads — the selection pace, the
/// manifest and segment budgets, tunneling — and the two read by products still to land — preload
/// and download — carry the Kotlin row's values unchanged, so a profile's decision reads the same
/// on both platforms; each cites the Kotlin row rather than restating its reason, because the
/// reason is that row's and nothing on iOS acts on the number yet.
///
/// **No row departs from `automaticallyWaitsToMinimizeStalling`.** Switching it off is the nearest
/// thing to the Kotlin short-form row's 1s start floor, and it is not that: a player that does not
/// wait also does not resume on its own after a stall, which turns a fast start into a feed row that
/// freezes — the opposite of what any profile here is for.
/// ref: AVFoundation's `AVPlayer.h`, on `rate`: "AVPlayer can reset the desired rate to 0.0 when
/// ... the playback buffer becomes empty and playback stalls while
/// automaticallyWaitsToMinimizeStalling is NO."
///
/// ref: `superplayer-core/src/main/kotlin/com/superplayer/core/StaticProfilePolicy.kt`
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredforwardbufferduration
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredpeakbitrate
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredmaximumresolution
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/configuredtimeoffsetfromlive
/// ref: https://developer.apple.com/documentation/avfoundation/avplayer/automaticallywaitstominimizestalling
struct StaticProfilePolicy: PlaybackPolicy {

    let profile: PlaybackProfile

    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
        Self.decision(for: profile)
    }

    static func decision(for profile: PlaybackProfile) -> PlaybackDecision {
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
        //
        // Preload, retry, download and output: the Kotlin `VIDEO_ON_DEMAND` row's, for its reasons
        // — one title ahead to its playlist, the long-form budgets, a 1080p download.
        case .videoOnDemand:
            PlaybackDecision(
                preload: PreloadPolicy(itemsAhead: 1, itemsBehind: 0, depth: .sourcePrepared),
                retry: RetryPolicy(segment: longFormSegmentBudget, licence: longFormLicenceBudget),
                download: DownloadSelectionPolicy(maxVideoHeightPx: 1_080))

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
        // which a playlist states with RFC 8216bis's `HOLD-BACK`), not a number of ours: the Kotlin
        // static row sets no live target either — a target is the adaptive policy's
        // `LiveLatencyPolicy` (§3.3) — and the packager knows its segment cadence where this table
        // does not.
        //
        // The forward buffer stays automatic: on a live stream it cannot run past the edge, so the
        // offset bounds it already, and the Kotlin row's 30s ceiling exists to stop the very drift
        // the preserved offset stops here.
        // ref: https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis#section-4.4.3.8
        //
        // No prefetch, the live budgets and no download ceiling: the Kotlin `LIVE_LINEAR` row's.
        case .liveLinear:
            PlaybackDecision(
                liveLatency: LiveLatencyPolicy(preservesOffset: true),
                retry: RetryPolicy(
                    manifest: liveManifestBudget,
                    segment: RetryBudget(maxRetries: 2, initialBackoffMs: 250, maxBackoffMs: 1_000),
                    licence: liveManifestBudget))

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
        //
        // Preload two ahead and one behind, decoders warmed on 1s of media: the Kotlin `SHORT_FORM`
        // row's, whose depth is its own `bufferForPlaybackMs` — the media a row needs before it may
        // start, a number with no knob here but still the right depth to prefetch to. The feed
        // budget for all three kinds, and a 720p download.
        case .shortForm:
            PlaybackDecision(
                buffer: BufferPolicy(maxBufferMs: 15_000),
                trackSelection: TrackSelectionPolicy(maxVideoHeightPx: 1_080),
                preload: PreloadPolicy(
                    itemsAhead: 2, itemsBehind: 1, depth: .decoderWarmed(durationMs: 1_000)),
                retry: RetryPolicy(manifest: feedBudget, segment: feedBudget, licence: feedBudget),
                download: DownloadSelectionPolicy(maxVideoHeightPx: 720))

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
        //
        // One title either side to its playlist, the long-form budgets, a 2160p download, and
        // tunneling asked for: the Kotlin `TV_LEANBACK` row's. Tunneling is the one value a player
        // here cannot act on either way — Apple TV's output is always tunneled — and is kept so the
        // row reads as the Kotlin one does.
        case .tvLeanback:
            PlaybackDecision(
                buffer: BufferPolicy(maxBufferMs: 120_000),
                preload: PreloadPolicy(itemsAhead: 1, itemsBehind: 1, depth: .sourcePrepared),
                retry: RetryPolicy(segment: longFormSegmentBudget, licence: longFormLicenceBudget),
                download: DownloadSelectionPolicy(maxVideoHeightPx: 2_160),
                output: OutputPolicy(tunneling: true))

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
        //
        // No prefetch, two asks at unhurried waits with a patient licence, and a download held to
        // playback's ceilings: the Kotlin `DATA_SAVER` row's.
        case .dataSaver:
            PlaybackDecision(
                buffer: BufferPolicy(maxBufferMs: 20_000),
                trackSelection: TrackSelectionPolicy(
                    maxVideoBitrateBps: 800_000, maxVideoHeightPx: 480),
                retry: RetryPolicy(
                    manifest: dataSaverBudget, segment: dataSaverBudget,
                    licence: longFormLicenceBudget),
                download: DownloadSelectionPolicy(
                    maxVideoBitrateBps: 800_000, maxVideoHeightPx: 480)
            )
        }
    }

    /// The Kotlin `LONG_FORM_SEGMENT_BUDGET`, shared by `.videoOnDemand` and `.tvLeanback` and argued
    /// at the Kotlin `VIDEO_ON_DEMAND` row. Ignored on iOS (``RetryPolicy/segment``).
    static let longFormSegmentBudget = RetryBudget(
        maxRetries: 5, initialBackoffMs: 500, maxBackoffMs: 8_000)

    /// The Kotlin `LONG_FORM_LICENCE_BUDGET`: four asks at the shortest waits, because a licence is
    /// spent entirely before the first frame. Also the Kotlin `DATA_SAVER` row's licence, which is
    /// the same four numbers argued at that row.
    static let longFormLicenceBudget = RetryBudget(
        maxRetries: 4, initialBackoffMs: 250, maxBackoffMs: 2_000)

    /// The Kotlin `LIVE_LINEAR` row's manifest and licence budget, argued there: a reload has to be
    /// recovered inside one target duration. The same numbers as ``longFormLicenceBudget``, named
    /// apart because the reasons are.
    static let liveManifestBudget = RetryBudget(
        maxRetries: 4, initialBackoffMs: 250, maxBackoffMs: 2_000)

    /// The Kotlin `FEED_BUDGET`, argued at the Kotlin `SHORT_FORM` row.
    static let feedBudget = RetryBudget(maxRetries: 2, initialBackoffMs: 250, maxBackoffMs: 1_000)

    /// The Kotlin `DATA_SAVER_BUDGET`: two asks at the standard budget's waits.
    static let dataSaverBudget = RetryBudget(
        maxRetries: 2, initialBackoffMs: RetryBudget.standard.initialBackoffMs,
        maxBackoffMs: RetryBudget.standard.maxBackoffMs)
}
