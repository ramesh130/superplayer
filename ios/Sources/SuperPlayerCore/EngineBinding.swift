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

/// **The one place a profile's numbers become AVFoundation configuration** — the analog of the
/// Kotlin `EngineBinding.kt`, and the file the policy boundary (`docs/ios-plan.md` §3.3) grows when
/// a decision can change during playback. ``ProfileKnobs`` names no AVFoundation type; this file is
/// where it meets one, so a knob set anywhere else is a bug against ADR-0005 whatever it is worth.
///
/// Two halves, because the knobs live on two objects: ``apply(to:)-(AVPlayer)`` once, as the
/// player is made, and ``apply(to:)-(AVPlayerItem)`` on every item the facade makes, before the
/// item is the player's — so the first variant chosen and the first second buffered are already
/// the profile's.
extension ProfileKnobs {

    /// The player's half: whether it waits to minimise stalling.
    @MainActor
    func apply(to player: AVPlayer) {
        player.automaticallyWaitsToMinimizeStalling = waitsToMinimizeStalling
    }

    /// The item's half. A field that is nil is written as Apple's own default rather than skipped,
    /// so an item's knobs are the row's whole and never a remainder of whoever set them before.
    @MainActor
    func apply(to item: AVPlayerItem) {
        item.preferredForwardBufferDuration = forwardBufferSeconds ?? 0
        item.preferredPeakBitRate = Double(maxVideoBitrateBps ?? 0)
        item.preferredMaximumResolution = Self.resolution(maxHeightPx: maxVideoHeightPx)
        item.preferredPeakBitRateForExpensiveNetworks = Double(
            expensiveNetworkMaxVideoBitrateBps ?? 0)
        item.preferredMaximumResolutionForExpensiveNetworks = Self.resolution(
            maxHeightPx: expensiveNetworkMaxVideoHeightPx)
        item.configuredTimeOffsetFromLive =
            liveOffsetSeconds.map { CMTime(seconds: $0, preferredTimescale: 1_000) } ?? .invalid
        item.automaticallyPreservesTimeOffsetFromLive = preservesLiveOffset
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
}
