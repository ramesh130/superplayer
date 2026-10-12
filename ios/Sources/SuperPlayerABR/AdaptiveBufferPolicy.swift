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

/// The buffer half of a ``PlaybackDecision``, recomputed from the conditions: `PRD.md` §3.1's five
/// branches — the Kotlin `AdaptiveBufferPolicy`, the same pure function, ported as is.
///
/// A pure function of its ``PlaybackConditions`` — no player, no clock, no state between calls — so
/// every branch is asserted by constructing conditions by hand (`AdaptiveBufferPolicyTests`), and
/// hysteresis is read from ``StallHistory`` rather than remembered (ADR-0009 rule 4).
///
/// It starts from the profile's own static decision, ``PlaybackProfile/policy``, and every branch is
/// a departure from it under a named observation. With nothing observed, or only a cold default
/// (``ThroughputEstimate/sampleCount`` zero), the answer is exactly the static profile's.
///
/// ## The five branches, and what each reaches on iOS
///
/// The logic is the Kotlin logic; what differs is which knob a branch's answer lands on, because
/// ``BufferPolicy`` here is AVFoundation's one forward-buffer preference and no floor
/// (`BufferPolicy` says why).
///
/// 1. **Stable and high throughput → a deep cushion** of ``deepCushionMs`` ahead, on-demand content
///    on an unmetered transport under the on-demand profile. Lands on
///    `preferredForwardBufferDuration`: AVFoundation tops its buffer up toward the preference
///    continuously, rather than in Media3's band between a minimum and a maximum, so the level it
///    holds *is* the preference, and the cushion — what is kept ahead — is the number. Where the
///    profile already asks for more, it keeps its own.
/// 2. **High variance → raise the playback floor, not the ceiling.** The ceiling is untouched, as on
///    Android; the floors it raises **have no knob here**, so the branch moves nothing. Starting
///    later on a variable link is AVFoundation's own stall prediction
///    (`automaticallyWaitsToMinimizeStalling`, on in every row), which nothing tunes.
/// 3. **Live → latency-priority mode**, keyed on the *observed* stream type: the live profile's
///    buffer and ``liveLatency``, which preserves the offset from live so the window is held
///    rather than lost to each stall — the iOS mechanism for what Media3's speed range does.
/// 4. **After a rebuffer → hysteresis.** Its buffer half is the Kotlin after-rebuffer floor, which,
///    like branch 2's, has no knob here; its other half, the held ceiling, is
///    ``AdaptiveSelectionPolicy``'s and ports whole, on this policy's ``rebufferCooldownMs``.
/// 5. **A memory-aware ceiling on every branch** — `docs/ios-plan.md` §3.3: a quarter of the heap
///    budget at the rate the buffer can fill, applied to `preferredForwardBufferDuration`. It caps a
///    number a branch or a profile decided, and leaves AVFoundation's automatic choice (a nil
///    ``BufferPolicy/maxBufferMs``) alone: that choice is the system's own and already "buffers
///    less ... to manage resource consumption" (`AVPlayerItem.h`), so there is no number to cap and
///    inventing one would be a guess standing in for that reading.
///
/// Playback speed scales the decided duration when it is above real time, as on Android.
///
/// Every constant is the Kotlin constant, and its reason is the Kotlin KDoc's, cited rather than
/// restated where nothing about it changes on iOS.
/// ref: `superplayer-abr/src/main/kotlin/com/superplayer/abr/AdaptiveBufferPolicy.kt`
public struct AdaptiveBufferPolicy: PlaybackPolicy {

    /// The profile whose static decision every branch departs from.
    public let profile: PlaybackProfile

    /// How long after a rebuffer the hysteresis holds: ``defaultRebufferCooldownMs`` unless a test
    /// states another, so the harness can watch a hold lapse without waiting the real half-minute.
    package let rebufferCooldownMs: Int64

    public init(profile: PlaybackProfile) {
        self.init(profile: profile, rebufferCooldownMs: Self.defaultRebufferCooldownMs)
    }

    package init(profile: PlaybackProfile, rebufferCooldownMs: Int64) {
        precondition(rebufferCooldownMs > 0, "rebufferCooldownMs must be positive")
        self.profile = profile
        self.rebufferCooldownMs = rebufferCooldownMs
    }

    public func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
        decide(conditions, selectionInForce: nil)
    }

    /// The decision, with branch 5's fill rate bounded by `selectionInForce` — the ceiling the
    /// selection half decided — rather than by the profile's own: the Kotlin overload, for its
    /// reason (#115). ``AdaptivePolicy`` passes it in.
    func decide(_ conditions: PlaybackConditions, selectionInForce: TrackSelectionPolicy?)
        -> PlaybackDecision
    {
        var decision = profile.policy.decide(conditions)
        let measured = conditions.throughput.flatMap { $0.sampleCount > 0 ? $0 : nil }

        if conditions.streamType == .live {
            decision.buffer = PlaybackProfile.liveLinear.policy.decide(conditions).buffer
            decision.liveLatency = Self.liveLatency
        } else if let measured, measured.spreadBps != nil,
            deepCushionApplies(conditions.transport, measured)
        {
            decision.buffer.maxBufferMs = max(decision.buffer.maxBufferMs ?? 0, Self.deepCushionMs)
        }

        if let speed = conditions.playbackSpeed, speed > 1, let ms = decision.buffer.maxBufferMs {
            decision.buffer.maxBufferMs = Self.roundedUp(
                Double(ms) * Double(speed), Self.floorStepMs)
        }

        if let heap = conditions.heapBudgetBytes, let ms = decision.buffer.maxBufferMs {
            let ceiling = Self.heapCeilingMs(
                heap, measured: measured, selection: selectionInForce ?? decision.trackSelection)
            decision.buffer.maxBufferMs = min(ms, ceiling)
        }

        decision.preload = Self.preload(on: conditions.transport, decision.preload)
        // The selection, retry, download and output halves are the static profile's, carried rather
        // than dropped for the Kotlin reasons: selection is the other half's to move, and the rest
        // are moved by no observation.
        return decision
    }

    /// Branch 1's test, the Kotlin `deepCushionApplies`: the on-demand profile, an unmetered
    /// transport, a stable spread and a high mean.
    private func deepCushionApplies(_ transport: NetworkTransport?, _ measured: ThroughputEstimate)
        -> Bool
    {
        profile == .videoOnDemand && (transport == .wifi || transport == .ethernet)
            && BandwidthOracle.isStable(measured) && measured.meanBps >= Self.highThroughputBps
    }

    /// The prefetch half (ADR-0010 rule 10), the Kotlin `preloadOn`: the profile's own, less its
    /// media on a cellular link — a loaded range or a warmed decoder becomes a prepared source, which
    /// keeps the reach and spends kilobytes. An unknown transport keeps the profile's depth.
    static func preload(on transport: NetworkTransport?, _ policy: PreloadPolicy) -> PreloadPolicy {
        guard case .cellular = transport else { return policy }
        switch policy.depth {
        case .loaded, .decoderWarmed:
            return PreloadPolicy(
                itemsAhead: policy.itemsAhead, itemsBehind: policy.itemsBehind,
                depth: .sourcePrepared)
        case .sourcePrepared, .tracksSelected:
            return policy
        }
    }

    /// Branch 5's ceiling, the Kotlin `heapCeilingMs`: the bytes a quarter of the heap holds, at the
    /// smaller of what the network delivers and the ceiling selection is held under — the tighter of
    /// the plain ceiling and the expensive-network one, since a selection policy decides the second
    /// only on a link it saw as cellular — or ``referenceBitrateBps`` before either is known, in
    /// whole ``ceilingStepMs`` and never under one.
    static func heapCeilingMs(
        _ heapBudgetBytes: Int64, measured: ThroughputEstimate?, selection: TrackSelectionPolicy
    ) -> Int {
        let caps = [selection.maxVideoBitrateBps, selection.expensiveNetworkMaxVideoBitrateBps]
            .compactMap { $0.map(Int64.init) }
        let delivered =
            measured.flatMap { $0.meanBps > 0 ? $0.meanBps : nil } ?? referenceBitrateBps
        let fillRateBps = ([delivered] + caps).min()!
        let bufferBytes = heapBudgetBytes / heapShareDivisor
        let ceilingMs = bufferBytes.multipliedReportingOverflow(by: 8 * 1_000)
        let ms = ceilingMs.overflow ? Int64.max : ceilingMs.partialValue / fillRateBps
        let stepped = ms / ceilingStepMs * ceilingStepMs
        return Int(min(max(stepped, ceilingStepMs), Int64(Int32.max)))
    }

    private static func roundedUp(_ value: Double, _ stepMs: Int) -> Int {
        Int((value / Double(stepMs)).rounded(.up)) * stepMs
    }

    /// Branch 1's cushion, the Kotlin `DEEP_CUSHION_MS`: 50 s, as long as the oracle's eight-sample
    /// memory at the authoring specification's 6 s segments, and `PRD.md` §3.1's number.
    public static let deepCushionMs = 50_000

    /// Branch 1's other condition, the Kotlin `HIGH_THROUGHPUT_BPS`: 10 Mbit/s, above the top 1080p
    /// tier with a margin.
    /// ref: https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
    public static let highThroughputBps: Int64 = 10_000_000

    /// The granularity a speed-scaled duration is rounded up to, the Kotlin `FLOOR_STEP_MS`: half a
    /// second, under which no viewer tells two answers apart.
    public static let floorStepMs = 500

    /// The hysteresis after a rebuffer, the Kotlin `REBUFFER_COOLDOWN_MS`: 30 s, inside the oracle's
    /// eight-sample window at the segment durations in common use. One cooldown, read by this
    /// policy's and ``AdaptiveSelectionPolicy``'s halves alike, so the two lapse together.
    public static let defaultRebufferCooldownMs: Int64 = 30_000

    /// Branch 5's share, the Kotlin `HEAP_SHARE_DIVISOR`: a quarter of the heap budget. On iOS the
    /// budget is what is left rather than the whole allowance (`ConditionsBinding.heapBudgetBytes`),
    /// which makes the quarter a quarter of less, the frugal direction.
    public static let heapShareDivisor: Int64 = 4

    /// The fill rate before anything is measured, the Kotlin `REFERENCE_BITRATE_BPS`: 8 Mbit/s, the
    /// top of the recommended 1080p ladder rounded up.
    public static let referenceBitrateBps: Int64 = 8_000_000

    /// The memory ceiling's granularity, the Kotlin `CEILING_STEP_MS`: a whole second. It is also the
    /// ceiling's floor, where the Kotlin floor is the profile's after-rebuffer start floor: there is
    /// no such floor here, and AVFoundation decides when to resume whatever the preference says.
    public static let ceilingStepMs: Int64 = 1_000

    /// Branch 3's live half: the offset from live preserved after a stall, and the target the
    /// playlist's own (`HOLD-BACK`). The Kotlin `LIVE_LATENCY` is a speed range Media3 drifts in to
    /// hold its target; AVFoundation has no such range, and skipping forward to the offset after
    /// buffering is how it holds one (`PlaybackDecision.swift`'s ``LiveLatencyPolicy``). No target
    /// is decided, because the Kotlin half decides none either — the target is the manifest's on
    /// both platforms.
    public static let liveLatency = LiveLatencyPolicy(targetOffsetMs: nil, preservesOffset: true)
}

extension StallHistory {

    /// Whether a rebuffer ended less than `cooldownMs` ago — the Kotlin `inRebufferCooldown`, the one
    /// test both adaptive halves read, so the hold and its lapse cannot disagree.
    func inRebufferCooldown(_ cooldownMs: Int64) -> Bool {
        guard let since = msSinceLastRebufferEnded else { return false }
        return since < cooldownMs
    }
}
