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

import SuperPlayerABR
import SuperPlayerCore
import XCTest

/// `PRD.md` §3.1's five branches, each asserted by constructing conditions by hand — the Kotlin
/// `AdaptiveBufferPolicyTest`'s cases, ported. Where a branch lands on a knob iOS lacks (the two
/// start floors), the port asserts that nothing else moved in its place, which is the iOS claim.
final class AdaptiveBufferPolicyTests: XCTestCase {

    // With nothing observed — or only a cold default — every profile answers as its static one does.
    func testWithNothingObservedEveryProfileAnswersAsItsStaticProfileDoes() {
        for profile in PlaybackProfile.allCases {
            let staticDecision = profile.policy.decide(PlaybackConditions())
            XCTAssertEqual(
                AdaptiveBufferPolicy(profile: profile).decide(PlaybackConditions()), staticDecision)
            let seeded = PlaybackConditions(
                transport: .wifi,
                throughput: estimate(meanBps: 20_000_000, spreadBps: 0, sampleCount: 0),
                heapBudgetBytes: Self.largeHeap)
            XCTAssertEqual(
                AdaptiveBufferPolicy(profile: profile).decide(seeded), staticDecision, "\(profile)")
        }
    }

    // Branch 1.
    func testAStableHighThroughputWifiNetworkGetsTheDeepCushion() {
        let decision = vod().decide(stableFastWifi())

        XCTAssertNil(Self.staticVod.buffer.maxBufferMs, "the static row is Apple's own choice")
        XCTAssertEqual(decision.buffer.maxBufferMs, AdaptiveBufferPolicy.deepCushionMs)
        XCTAssertTrue(decision.buffer.waitsToMinimizeStalling)
    }

    func testTheDeepCushionNeedsAnUnmeteredTransportAStableSpreadAHighMeanAndOnDemand() {
        let fast = stableFastWifi()
        let notDeepened: [PlaybackConditions] = [
            with(fast) { $0.transport = .cellular(.nr) },
            with(fast) { $0.transport = .unknown },
            with(fast) { $0.transport = nil },
            with(fast) { $0.throughput = estimate(meanBps: 20_000_000, spreadBps: 8_000_000) },
            with(fast) { $0.throughput = estimate(meanBps: 20_000_000, spreadBps: nil) },
            with(fast) { $0.throughput = estimate(meanBps: 5_000_000, spreadBps: 100_000) },
        ]
        for conditions in notDeepened {
            XCTAssertEqual(vod().decide(conditions).buffer, Self.staticVod.buffer, "\(conditions)")
        }
        for profile in [PlaybackProfile.shortForm, .dataSaver, .liveLinear] {
            XCTAssertEqual(
                AdaptiveBufferPolicy(profile: profile).decide(fast).buffer.maxBufferMs,
                profile.policy.decide(PlaybackConditions()).buffer.maxBufferMs, "\(profile)")
        }
        // Ethernet is the other unmetered transport.
        XCTAssertEqual(
            vod().decide(with(fast) { $0.transport = .ethernet }).buffer.maxBufferMs,
            AdaptiveBufferPolicy.deepCushionMs)
    }

    // Branch 2: the same mean, two spreads — the same ceiling. The floors the Kotlin branch raises
    // have no knob here, so nothing else moves either.
    func testTwoNetworksOfEqualMeanAndUnequalSpreadGetTheSameBuffer() {
        let steadier = vod().decide(conditions(estimate(meanBps: 4_000_000, spreadBps: 1_500_000)))
        let noisier = vod().decide(conditions(estimate(meanBps: 4_000_000, spreadBps: 3_000_000)))

        XCTAssertEqual(steadier.buffer, noisier.buffer)
        XCTAssertEqual(noisier.buffer, Self.staticVod.buffer)
        let tv = AdaptiveBufferPolicy(profile: .tvLeanback)
        XCTAssertEqual(
            tv.decide(conditions(estimate(meanBps: 1_000_000, spreadBps: 5_000_000))).buffer,
            PlaybackProfile.tvLeanback.policy.decide(PlaybackConditions()).buffer,
            "a wild spread moves no ceiling either")
    }

    // Branch 3: keyed on what the playlist said, whatever profile the consumer named.
    func testContentObservedLiveGetsTheLatencyPriorityHalvesUnderAnyProfile() {
        let live = with(stableFastWifi()) { $0.streamType = .live }
        let latencyPriority = PlaybackProfile.liveLinear.policy.decide(PlaybackConditions()).buffer

        for profile in PlaybackProfile.allCases {
            let decision = AdaptiveBufferPolicy(profile: profile).decide(live)
            XCTAssertEqual(decision.buffer, latencyPriority, "\(profile)")
            XCTAssertEqual(decision.liveLatency, AdaptiveBufferPolicy.liveLatency, "\(profile)")
            // The selection half stays the profile's: a data saver watching live still saves data.
            XCTAssertEqual(
                decision.trackSelection,
                profile.policy.decide(PlaybackConditions()).trackSelection, "\(profile)")
        }
        XCTAssertEqual(AdaptiveBufferPolicy.liveLatency.preservesOffset, true)
        XCTAssertNil(AdaptiveBufferPolicy.liveLatency.targetOffsetMs, "the playlist's HOLD-BACK")
        XCTAssertNil(vod().decide(with(stableFastWifi()) { $0.streamType = .onDemand }).liveLatency)
    }

    // Branch 4: the buffer half is a floor iOS has no knob for, and the held ceiling is the
    // selection policy's — so a rebuffer moves nothing here, inside the cooldown or after it.
    func testARebufferMovesNothingInTheBufferHalf() {
        let measured = estimate(meanBps: 4_000_000, spreadBps: 200_000, conservativeBps: 3_000_000)
        let tv = AdaptiveBufferPolicy(profile: .tvLeanback)
        let calm = tv.decide(conditions(measured))
        for msAgo: Int64 in [0, 1_000, AdaptiveBufferPolicy.defaultRebufferCooldownMs] {
            let after = tv.decide(
                with(conditions(measured)) { $0.stallHistory = rebuffered(msAgo) })
            XCTAssertEqual(after, calm, "\(msAgo) ms after")
        }
    }

    // Branch 5, on every branch that decides a number: a low heap caps it to the second derivation.
    func testALowHeapCapsTheCeilingOnEveryBranchThatDecidesANumber() {
        let heap: Int64 = 64 * 1_024 * 1_024
        let tv = AdaptiveBufferPolicy(profile: .tvLeanback)
        let branches: [(String, AdaptiveBufferPolicy, PlaybackConditions)] = [
            ("static", tv, conditions(nil)),
            ("deep cushion", vod(), stableFastWifi()),
            ("variance", tv, conditions(estimate(meanBps: 4_000_000, spreadBps: 3_000_000))),
            (
                "cooldown", tv,
                with(conditions(estimate(meanBps: 4_000_000, spreadBps: 200_000))) {
                    $0.stallHistory = rebuffered(0)
                }
            ),
            ("double speed", vod(), with(stableFastWifi()) { $0.playbackSpeed = 2 }),
        ]
        for (name, policy, conditions) in branches {
            let unconstrained = policy.decide(
                with(conditions) { $0.heapBudgetBytes = Self.largeHeap })
            let capped = policy.decide(with(conditions) { $0.heapBudgetBytes = heap })
            let free = unconstrained.buffer.maxBufferMs!
            XCTAssertEqual(
                capped.buffer.maxBufferMs, min(free, expectedCeilingMs(heap, conditions)), name)
            XCTAssertLessThan(capped.buffer.maxBufferMs!, free, name)
        }
    }

    /// Apple's automatic choice is a reading the system makes against its own resources, not a
    /// number to cap — the static on-demand row and the live branch keep it whatever the heap.
    func testTheAutomaticChoiceIsNotCapped() {
        let tiny: Int64 = 1_024
        XCTAssertNil(
            vod().decide(with(conditions(nil)) { $0.heapBudgetBytes = tiny }).buffer.maxBufferMs)
        let live = with(stableFastWifi()) {
            $0.streamType = .live
            $0.heapBudgetBytes = tiny
        }
        XCTAssertNil(vod().decide(live).buffer.maxBufferMs)
    }

    func testTheMemoryCeilingIsNeverUnderOneStep() {
        let tiny = AdaptiveBufferPolicy(profile: .tvLeanback)
            .decide(with(conditions(nil)) { $0.heapBudgetBytes = 1_024 }).buffer
        XCTAssertEqual(tiny.maxBufferMs, Int(AdaptiveBufferPolicy.ceilingStepMs))
    }

    func testTheMemoryCeilingIsSizedAtTheRateTheBufferCanFill() {
        let heap: Int64 = 64 * 1_024 * 1_024
        let tv = AdaptiveBufferPolicy(profile: .tvLeanback)
        // A slow measured link buffers cheaply: the same heap holds more seconds of it.
        let slow = tv.decide(
            with(conditions(estimate(meanBps: 1_000_000, spreadBps: 10_000))) {
                $0.heapBudgetBytes = heap
            }
        ).buffer
        let unmeasured = tv.decide(with(conditions(nil)) { $0.heapBudgetBytes = heap }).buffer
        XCTAssertGreaterThan(slow.maxBufferMs!, unmeasured.maxBufferMs!)
        // A profile cap bounds the fill rate as a slow link does.
        let smallHeap: Int64 = 4 * 1_024 * 1_024
        let dataSaver = AdaptiveBufferPolicy(profile: .dataSaver)
            .decide(with(stableFastWifi()) { $0.heapBudgetBytes = smallHeap }).buffer
        XCTAssertEqual(
            dataSaver.maxBufferMs, expectedCeilingMs(smallHeap, stableFastWifi(), capBps: 800_000))
    }

    func testAPlaybackSpeedAboveRealTimeScalesTheDecidedDuration() {
        let tv = AdaptiveBufferPolicy(profile: .tvLeanback)
        let staticTv = PlaybackProfile.tvLeanback.policy.decide(PlaybackConditions()).buffer
        XCTAssertEqual(
            tv.decide(PlaybackConditions(playbackSpeed: 2)).buffer.maxBufferMs,
            staticTv.maxBufferMs! * 2)
        // Slower than real time drains slower; nothing is shrunk for it.
        XCTAssertEqual(tv.decide(PlaybackConditions(playbackSpeed: 0.5)).buffer, staticTv)
        // And Apple's automatic choice has no duration to scale.
        XCTAssertNil(vod().decide(PlaybackConditions(playbackSpeed: 2)).buffer.maxBufferMs)
    }

    /// The prefetch half, the Kotlin `preloadOn`: a loaded or warmed depth is cut to a prepared source
    /// on cellular, and kept everywhere else.
    func testAFeedPrefetchesNoMediaOnCellular() {
        let shortForm = AdaptiveBufferPolicy(profile: .shortForm)
        let staticPreload = PlaybackProfile.shortForm.policy.decide(PlaybackConditions()).preload
        let onLte = shortForm.decide(PlaybackConditions(transport: .cellular(.lte))).preload
        XCTAssertEqual(onLte.depth, .sourcePrepared)
        XCTAssertEqual(onLte.itemsAhead, staticPreload.itemsAhead)
        XCTAssertEqual(onLte.itemsBehind, staticPreload.itemsBehind)
        for transport: NetworkTransport? in [.wifi, .ethernet, .unknown, nil] {
            XCTAssertEqual(
                shortForm.decide(PlaybackConditions(transport: transport)).preload, staticPreload)
        }
    }

    // MARK: - Fixtures

    private func vod() -> AdaptiveBufferPolicy { AdaptiveBufferPolicy(profile: .videoOnDemand) }

    private func stableFastWifi() -> PlaybackConditions {
        conditions(
            estimate(meanBps: 20_000_000, spreadBps: 1_000_000, conservativeBps: 18_000_000))
    }

    private func conditions(_ throughput: ThroughputEstimate?) -> PlaybackConditions {
        PlaybackConditions(
            transport: .wifi, throughput: throughput, streamType: .onDemand,
            heapBudgetBytes: Self.largeHeap)
    }

    /// Branch 5's arithmetic, written out once here so the test is a second derivation of it.
    private func expectedCeilingMs(
        _ heap: Int64, _ conditions: PlaybackConditions, capBps: Int64? = nil
    ) -> Int {
        let measured = conditions.throughput.flatMap { $0.sampleCount > 0 ? $0.meanBps : nil }
        let rate = min(measured ?? AdaptiveBufferPolicy.referenceBitrateBps, capBps ?? .max)
        let ms = heap / AdaptiveBufferPolicy.heapShareDivisor * 8 * 1_000 / rate
        return Int(ms / AdaptiveBufferPolicy.ceilingStepMs * AdaptiveBufferPolicy.ceilingStepMs)
    }

    private static let largeHeap: Int64 = 2_048 * 1_024 * 1_024
    private static let staticVod = PlaybackProfile.videoOnDemand.policy.decide(PlaybackConditions())
}

func estimate(
    meanBps: Int64, spreadBps: Int64?, conservativeBps: Int64? = nil, sampleCount: Int = 8
) -> ThroughputEstimate {
    ThroughputEstimate(
        meanBps: meanBps, spreadBps: spreadBps, conservativeBps: conservativeBps,
        sampleCount: sampleCount, newestSampleAgeMs: 0)
}

func rebuffered(_ msAgo: Int64, count: Int = 1) -> StallHistory {
    StallHistory(
        rebufferCount: count, msSinceLastRebufferEnded: msAgo, lastRebufferDurationMs: 1_500)
}

func with(_ conditions: PlaybackConditions, _ change: (inout PlaybackConditions) -> Void)
    -> PlaybackConditions
{
    var changed = conditions
    change(&changed)
    return changed
}
