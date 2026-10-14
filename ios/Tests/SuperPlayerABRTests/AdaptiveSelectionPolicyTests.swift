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

/// The selection half's rules, each asserted by constructing conditions by hand — the Kotlin
/// `AdaptiveSelectionPolicyTest`'s cases, ported. The transport cap is read where it lands on iOS,
/// the expensive-network pair, through ``effectiveOnExpensive(_:)``: the ceiling AVFoundation
/// applies on such a link, the tighter of the plain one and the pair.
final class AdaptiveSelectionPolicyTests: XCTestCase {

    // Rule 1: with nothing observed every profile answers as its static profile does, whole.
    func testWithNothingObservedEveryProfileAnswersAsItsStaticProfileDoes() {
        for profile in PlaybackProfile.allCases {
            XCTAssertEqual(
                AdaptiveSelectionPolicy(profile: profile).decide(PlaybackConditions()),
                profile.policy.decide(PlaybackConditions()), "\(profile)")
        }
    }

    // Rule 2: the cellular cap, below the WiFi ceiling on every profile, on the expensive pair.
    func testACellularTransportCapsEveryProfileBelowItsWifiCeiling() {
        for profile in PlaybackProfile.allCases {
            let policy = AdaptiveSelectionPolicy(profile: profile)
            let onWifi = policy.decide(PlaybackConditions(transport: .wifi)).trackSelection
            let onLte = policy.decide(PlaybackConditions(transport: .cellular(.lte))).trackSelection
            let staticSelection = profile.policy.decide(PlaybackConditions()).trackSelection

            XCTAssertEqual(onWifi, staticSelection, "\(profile) on WiFi")
            let wifi = effectiveOnExpensive(onWifi)
            let lte = effectiveOnExpensive(onLte)
            XCTAssertLessThan(lte.bitrate, wifi.bitrate, "\(profile) on LTE")
            XCTAssertLessThanOrEqual(lte.height, wifi.height, "\(profile) on LTE")
            // The plain ceiling is untouched: it binds on every network, and the cap is cellular's.
            XCTAssertEqual(onLte.maxVideoBitrateBps, staticSelection.maxVideoBitrateBps)
            XCTAssertEqual(onLte.maxVideoHeightPx, staticSelection.maxVideoHeightPx)
        }
    }

    // Rule 2's wired row (#267), and every other link the table caps at nothing.
    func testNoCapOnAWiredWifiUnknownOr5GLink() {
        for profile in PlaybackProfile.allCases {
            let staticSelection = profile.policy.decide(PlaybackConditions()).trackSelection
            for transport: NetworkTransport in [.ethernet, .wifi, .unknown, .cellular(.nr)] {
                XCTAssertEqual(
                    AdaptiveSelectionPolicy(profile: profile)
                        .decide(PlaybackConditions(transport: transport)).trackSelection,
                    staticSelection, "\(profile) on \(transport)")
            }
        }
    }

    func testTheTransportCapNeverRaisesTheProfilesOwnCeiling() {
        let dataSaver = AdaptiveSelectionPolicy(profile: .dataSaver)
        let staticSelection = PlaybackProfile.dataSaver.policy.decide(PlaybackConditions())
            .trackSelection
        // LTE's data-saver cap is 360p at 730 kbit/s, under the profile's own 480p at 800 kbit/s:
        // the pair says so. Older cellular is the same entry.
        for generation: CellularGeneration in [.lte, .older] {
            let decided = dataSaver.decide(PlaybackConditions(transport: .cellular(generation)))
                .trackSelection
            XCTAssertEqual(decided.maxVideoBitrateBps, staticSelection.maxVideoBitrateBps)
            XCTAssertEqual(decided.expensiveNetworkMaxVideoBitrateBps, 730_000)
            XCTAssertEqual(decided.expensiveNetworkMaxVideoHeightPx, 360)
        }
        // On 1080p's entry the short-form profile's own 1080p is already as tight: the pair says
        // nothing on that axis rather than restating it.
        let shortForm = AdaptiveSelectionPolicy(profile: .shortForm)
            .decide(PlaybackConditions(transport: .cellular(.lte))).trackSelection
        XCTAssertEqual(shortForm.expensiveNetworkMaxVideoHeightPx, 720)
        let vodOnLte = AdaptiveSelectionPolicy(profile: .videoOnDemand)
            .decide(PlaybackConditions(transport: .cellular(.lte))).trackSelection
        XCTAssertEqual(vodOnLte.expensiveNetworkMaxVideoHeightPx, 1_080)
        XCTAssertEqual(vodOnLte.expensiveNetworkMaxVideoBitrateBps, 15_600_000)
    }

    func testAGenerationThePlatformDidNotNameIsCappedAsLte() {
        for profile in PlaybackProfile.allCases {
            let policy = AdaptiveSelectionPolicy(profile: profile)
            XCTAssertEqual(
                policy.decide(PlaybackConditions(transport: .cellular(nil))).trackSelection,
                policy.decide(PlaybackConditions(transport: .cellular(.lte))).trackSelection,
                "\(profile)")
        }
    }

    // Rule 3: the hold, and its lapse on the shared cooldown.
    func testARebufferHoldsTheCeilingAtTheConservativePercentileForTheCooldown() {
        let measured = estimate(meanBps: 4_000_000, spreadBps: 200_000, conservativeBps: 3_000_000)
        let justEnded = with(conditions(measured)) { $0.stallHistory = rebuffered(1_000) }

        XCTAssertEqual(vod().decide(justEnded).trackSelection.maxVideoBitrateBps, 3_000_000)
        XCTAssertEqual(
            vod().decide(justEnded).trackSelection.maxVideoHeightPx,
            Self.staticVod.trackSelection.maxVideoHeightPx)

        // Once the cooldown has lapsed it goes back: the history is still there, the hold is not.
        let lapsed = with(justEnded) {
            $0.stallHistory = rebuffered(AdaptiveBufferPolicy.defaultRebufferCooldownMs)
        }
        XCTAssertEqual(vod().decide(lapsed).trackSelection, Self.staticVod.trackSelection)
    }

    func testTheHeldCeilingIsTheMeanWhereThereIsNoPercentileAndNeverAboveTheCeilingInForce() {
        let noPercentile = with(conditions(estimate(meanBps: 2_000_000, spreadBps: 100_000))) {
            $0.stallHistory = rebuffered(0)
        }
        XCTAssertEqual(vod().decide(noPercentile).trackSelection.maxVideoBitrateBps, 2_000_000)

        // Under the profile's own cap on a fast link…
        let fastNetwork = with(
            conditions(
                estimate(meanBps: 20_000_000, spreadBps: 100_000, conservativeBps: 18_000_000))
        ) { $0.stallHistory = rebuffered(0) }
        XCTAssertEqual(
            AdaptiveSelectionPolicy(profile: .dataSaver).decide(fastNetwork).trackSelection,
            PlaybackProfile.dataSaver.policy.decide(PlaybackConditions()).trackSelection)
        // …and the cellular pair still binds beside it where it is the tighter one.
        let fastLte = vod().decide(with(fastNetwork) { $0.transport = .cellular(.lte) })
            .trackSelection
        XCTAssertEqual(effectiveOnExpensive(fastLte).bitrate, 15_600_000)

        // Nothing measured, or a cold default only: nothing to hold at.
        let unmeasured = PlaybackConditions(stallHistory: rebuffered(0))
        XCTAssertEqual(vod().decide(unmeasured).trackSelection, Self.staticVod.trackSelection)
        let seeded = with(conditions(estimate(meanBps: 2_000_000, spreadBps: 0, sampleCount: 0))) {
            $0.stallHistory = rebuffered(0)
        }
        XCTAssertEqual(vod().decide(seeded).trackSelection, Self.staticVod.trackSelection)
        // A reading of zero holds nothing: there is no rung under it.
        let dead = with(conditions(estimate(meanBps: 0, spreadBps: 0, conservativeBps: 0))) {
            $0.stallHistory = rebuffered(0)
        }
        XCTAssertEqual(vod().decide(dead).trackSelection, Self.staticVod.trackSelection)
    }

    /// The cooldown the hold lapses on is the one the policy was made with — the seam the harness
    /// test shortens, held here to mean what it says.
    func testTheHoldLapsesOnTheCooldownThePolicyWasMadeWith() {
        let short = AdaptiveSelectionPolicy(profile: .videoOnDemand, rebufferCooldownMs: 2_000)
        let measured = conditions(
            estimate(meanBps: 4_000_000, spreadBps: 1, conservativeBps: 3_000_000))
        XCTAssertEqual(
            short.decide(with(measured) { $0.stallHistory = rebuffered(1_999) }).trackSelection
                .maxVideoBitrateBps, 3_000_000)
        XCTAssertNil(
            short.decide(with(measured) { $0.stallHistory = rebuffered(2_000) }).trackSelection
                .maxVideoBitrateBps)
    }

    // The buffer half is never this policy's to move, and there is no pace to set.
    func testTheBufferHalfIsAlwaysTheStaticProfilesAndNoPaceIsDecided() {
        let busy = PlaybackConditions(
            transport: .cellular(.lte),
            throughput: estimate(
                meanBps: 20_000_000, spreadBps: 100_000, conservativeBps: 18_000_000),
            stallHistory: rebuffered(0, count: 3), heapBudgetBytes: 64 * 1_024 * 1_024,
            playbackSpeed: 2)
        let decision = vod().decide(busy)
        XCTAssertEqual(decision.buffer, Self.staticVod.buffer)
        XCTAssertNil(decision.liveLatency)
        XCTAssertNil(decision.trackSelection.pace)
    }

    // MARK: - Fixtures

    private func vod() -> AdaptiveSelectionPolicy {
        AdaptiveSelectionPolicy(profile: .videoOnDemand)
    }

    private func conditions(_ throughput: ThroughputEstimate?) -> PlaybackConditions {
        PlaybackConditions(transport: .wifi, throughput: throughput)
    }

    /// The ceiling AVFoundation applies on an expensive network: the tighter of the plain ceiling
    /// and the pair, per axis, with none read as unlimited.
    private func effectiveOnExpensive(_ selection: TrackSelectionPolicy) -> (
        bitrate: Int, height: Int
    ) {
        let bitrate =
            [selection.maxVideoBitrateBps, selection.expensiveNetworkMaxVideoBitrateBps]
            .compactMap { $0 }.min() ?? .max
        let height =
            [selection.maxVideoHeightPx, selection.expensiveNetworkMaxVideoHeightPx]
            .compactMap { $0 }.min() ?? .max
        return (bitrate, height)
    }

    private static let staticVod = PlaybackProfile.videoOnDemand.policy.decide(PlaybackConditions())
}
