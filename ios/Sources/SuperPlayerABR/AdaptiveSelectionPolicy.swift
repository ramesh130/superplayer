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

/// The selection half of a ``PlaybackDecision``, recomputed from the conditions: what is *eligible*
/// on this transport, after this stall — the Kotlin `AdaptiveSelectionPolicy`, the same pure
/// function, its buffer half the static profile's so that composed with ``AdaptiveBufferPolicy``
/// each owns one half.
///
/// ## The rules, in order
///
/// 1. **The profile's own cap**, which no rule raises: a data saver on the fastest link is still
///    saving data.
/// 2. **The transport's cap** (``TransportCaps``), on a link observed to be cellular — and it lands
///    on **the expensive-network pair**, `preferredPeakBitRateForExpensiveNetworks` and
///    `preferredMaximumResolutionForExpensiveNetworks`, rather than on the plain ceiling as the
///    Kotlin cap does (`docs/ios-plan.md` §3.3). The pair is the platform's own spelling of "on a
///    metered link", and AVFoundation re-reads which link it is on itself: a handover back to WiFi
///    lifts the cap the moment the path changes, before the ``DecisionTrigger/transportChanged``
///    that re-consults this policy arrives, while on cellular the pair binds exactly as a plain
///    ceiling would. It is decided only where it is stricter than the plain ceiling, since
///    AVFoundation applies the plain one on every network and the pair says nothing otherwise.
/// 3. **After a rebuffer, a hold**: for the cooldown ``AdaptiveBufferPolicy`` defines, the plain
///    ceiling is held at what the network delivered most of the time — the conservative
///    percentile, or the mean where there is none — never above the ceiling in force, and it lapses
///    at the first consultation after the cooldown, which is the next trigger.
///
/// **No pace.** The Kotlin rule 4 sets the profile's `SelectionPace`; Apple selects variants with no
/// pace setting, so ``TrackSelectionPolicy/pace`` stays the static profile's — none — rather than
/// carrying numbers nothing on this platform reads (`docs/ios-plan.md` §3.3, the
/// `NetworkAwareTrackSelection` row). The display and the decoder are Apple's to refuse, as there.
///
/// ref: `android/superplayer-abr/src/main/kotlin/com/superplayer/abr/AdaptiveSelectionPolicy.kt`
public struct AdaptiveSelectionPolicy: PlaybackPolicy {

    /// The profile whose static ceiling every rule starts from.
    public let profile: PlaybackProfile

    /// The hold's cooldown, ``AdaptiveBufferPolicy/defaultRebufferCooldownMs`` unless a test states
    /// another — and always the buffer half's own when ``AdaptivePolicy`` composes the two.
    package let rebufferCooldownMs: Int64

    public init(profile: PlaybackProfile) {
        self.init(
            profile: profile, rebufferCooldownMs: AdaptiveBufferPolicy.defaultRebufferCooldownMs)
    }

    package init(profile: PlaybackProfile, rebufferCooldownMs: Int64) {
        precondition(rebufferCooldownMs > 0, "rebufferCooldownMs must be positive")
        self.profile = profile
        self.rebufferCooldownMs = rebufferCooldownMs
    }

    public func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
        var decision = profile.policy.decide(conditions)
        var selection = decision.trackSelection

        if case .cellular = conditions.transport, let transport = conditions.transport {
            let cap = TransportCaps.cap(for: profile, on: transport)
            selection.expensiveNetworkMaxVideoBitrateBps = Self.stricter(
                cap.maxVideoBitrateBps, than: selection.maxVideoBitrateBps)
            selection.expensiveNetworkMaxVideoHeightPx = Self.stricter(
                cap.maxVideoHeightPx, than: selection.maxVideoHeightPx)
        }

        if let measured = conditions.throughput, measured.sampleCount > 0,
            conditions.stallHistory.inRebufferCooldown(rebufferCooldownMs)
        {
            selection.maxVideoBitrateBps = Self.held(
                at: measured, under: selection.maxVideoBitrateBps)
        }

        decision.trackSelection = selection
        return decision
    }

    /// `cap` where it says less than `plain`, and nil — nothing to say — where it does not.
    private static func stricter(_ cap: Int?, than plain: Int?) -> Int? {
        guard let cap else { return nil }
        guard let plain else { return cap }
        return cap < plain ? cap : nil
    }

    /// Rule 3's ceiling: the conservative percentile, or the mean where there is none, under the
    /// ceiling already in force. A reading of zero holds nothing — there is no rung under it.
    private static func held(at measured: ThroughputEstimate, under ceiling: Int?) -> Int? {
        let delivered = measured.conservativeBps ?? measured.meanBps
        guard delivered > 0 else { return ceiling }
        let capped = min(delivered, Int64(ceiling ?? Int.max))
        return Int(min(capped, Int64(Int32.max)))
    }
}
