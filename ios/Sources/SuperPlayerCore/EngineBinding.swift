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

/// **The one place a decision becomes AVFoundation configuration** — the analog of the Kotlin
/// `EngineBinding.kt` (`docs/ios-plan.md` §3.3's second row). ``PlaybackDecision`` names no
/// AVFoundation type; this file is where it meets one, so a knob set anywhere else is a bug against
/// ADR-0005 whatever it is worth.
///
/// Two halves, because the knobs live on two objects: ``apply(to:)-(AVPlayer)`` as the player is
/// made, and ``apply(to:)-(AVPlayerItem)`` on every item the facade makes, before the item is the
/// player's — so the first variant chosen and the first second buffered are already the decision's.
///
/// **Every knob here is live-settable, and that is ADR-0009 rule 5's condition met by the platform.**
/// The rule says a re-applied decision is honoured whole or the policy is not re-consulted; on
/// Android that needs `superplayer-abr`'s retargetable load control and selection, because
/// `DefaultLoadControl` fixes its durations at construction. `preferredForwardBufferDuration`, both
/// ceilings and their expensive-network pair, the live offset and its preservation are all
/// documented as read by the item as it plays, and `automaticallyWaitsToMinimizeStalling` by the
/// player — so the facade (`SuperPlayer.decisionChanged(to:on:)`) lays a decision
/// `DecisionReapplication.swift` changed on the *current* item and the player in place, through
/// these same two functions, and nothing is left half in force. There is no second target as the
/// Kotlin file grew one. The cost, recorded at ADR-0009 rule 5's #415 addendum: a knob a consumer
/// set on the playing item through `player.avPlayer` is rewritten by the next change.
///
/// What is not laid, because nothing on this platform reads it: ``TrackSelectionPolicy/pace``, the
/// ``RetryPolicy/manifest`` and ``RetryPolicy/segment`` budgets, and ``PlaybackDecision/output``
/// (`PlaybackDecision.swift` says why each). ``PlaybackDecision/preload``,
/// ``PlaybackDecision/download`` and ``RetryPolicy/licence`` are read by the products that act on
/// them, from ``SuperPlayer/playbackDecision``, and not laid on an engine object at all.
///
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredforwardbufferduration
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredpeakbitrate
/// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/configuredtimeoffsetfromlive
extension PlaybackDecision {

    /// The player's half: whether it waits to minimise stalling.
    @MainActor
    func apply(to player: AVPlayer) {
        player.automaticallyWaitsToMinimizeStalling = buffer.waitsToMinimizeStalling
    }

    /// The item's half. A field that is nil is written as Apple's own default rather than skipped,
    /// so an item's knobs are the decision's whole and never a remainder of an earlier decision —
    /// which is what makes laying a changed decision on a playing item honour it whole.
    @MainActor
    func apply(to item: AVPlayerItem) {
        item.preferredForwardBufferDuration = buffer.maxBufferMs.map(Self.seconds) ?? 0
        item.preferredPeakBitRate = Double(trackSelection.maxVideoBitrateBps ?? 0)
        item.preferredMaximumResolution = Self.resolution(
            maxHeightPx: trackSelection.maxVideoHeightPx)
        item.preferredPeakBitRateForExpensiveNetworks = Double(
            trackSelection.expensiveNetworkMaxVideoBitrateBps ?? 0)
        item.preferredMaximumResolutionForExpensiveNetworks = Self.resolution(
            maxHeightPx: trackSelection.expensiveNetworkMaxVideoHeightPx)
        item.configuredTimeOffsetFromLive =
            liveLatency?.targetOffsetMs.map {
                CMTime(value: CMTimeValue($0), timescale: Self.millisecondsPerSecond)
            } ?? .invalid
        item.automaticallyPreservesTimeOffsetFromLive = liveLatency?.preservesOffset ?? false
    }

    /// A height ceiling as the size `preferredMaximumResolution` takes, or `.zero` — Apple's "no
    /// limit" — for none.
    ///
    /// The policy states a height alone and Apple takes a size, which is the Kotlin binding's
    /// situation and its answer: leave the other axis unbounded "rather than inventing a width the
    /// policy did not decide" (`EngineBinding.kt`, `setMaxVideoSize(UNLIMITED, maxVideoHeightPx)`).
    /// A 16:9 width would be such an invention — 1920×1080 also refuses a portrait 1080×1920 rung
    /// by its width, a second ceiling no row states. Finite rather than infinite, because the value
    /// is handed to a C struct whose arithmetic Apple does not document.
    static func resolution(maxHeightPx: Int?) -> CGSize {
        guard let maxHeightPx else { return .zero }
        return CGSize(width: unboundedWidthPx, height: maxHeightPx)
    }

    /// The width ``resolution(maxHeightPx:)`` pairs with a height: 2³¹ − 1, the Kotlin
    /// `TrackSelectionPolicy.UNLIMITED` (`Int.MAX_VALUE`) the same axis is left at there, and far
    /// past any encoded width.
    static let unboundedWidthPx = Int(Int32.max)

    /// A decision's milliseconds as the seconds AVFoundation's durations take.
    private static func seconds(_ milliseconds: Int) -> TimeInterval {
        TimeInterval(milliseconds) / TimeInterval(millisecondsPerSecond)
    }

    /// The timescale a decision's milliseconds are written in, exactly.
    private static let millisecondsPerSecond: CMTimeScale = 1_000
}
