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

/// The composition — the Kotlin `AdaptivePolicyTest`: each half owns its own, a cold start is the
/// static profile's, the buffer's memory ceiling is sized at the selection in force (#115), and the
/// policy brings an oracle per player through core's seam.
@MainActor
final class AdaptivePolicyTests: XCTestCase {

    func testWithNothingObservedEveryProfileIsItsStaticProfile() {
        for profile in PlaybackProfile.allCases {
            XCTAssertEqual(
                AdaptivePolicy.forProfile(profile).decide(PlaybackConditions()),
                profile.policy.decide(PlaybackConditions()), "\(profile)")
        }
    }

    /// Each half's departure survives the composition: the deep cushion and the held ceiling at
    /// once, on a stable fast link just after a stall.
    func testEachHalfOwnsItsOwnHalf() {
        let conditions = PlaybackConditions(
            transport: .wifi,
            throughput: estimate(
                meanBps: 20_000_000, spreadBps: 1_000_000, conservativeBps: 18_000_000),
            stallHistory: rebuffered(0), streamType: .onDemand)
        let decision = AdaptivePolicy.forProfile(.videoOnDemand).decide(conditions)

        XCTAssertEqual(decision.buffer.maxBufferMs, AdaptiveBufferPolicy.deepCushionMs)
        XCTAssertEqual(decision.trackSelection.maxVideoBitrateBps, 18_000_000)
    }

    /// A cellular cap or a hold sits under the profile's own ceiling, and the memory ceiling is sized
    /// at that rate rather than the profile's, which only the composition can see (#115).
    func testTheMemoryCeilingIsSizedAtTheSelectionInForce() {
        let heap: Int64 = 16 * 1_024 * 1_024
        let onOlderCellular = PlaybackConditions(
            transport: .cellular(.older), heapBudgetBytes: heap)

        let composed = AdaptivePolicy.forProfile(.tvLeanback).decide(onOlderCellular)
        let alone = AdaptiveBufferPolicy(profile: .tvLeanback).decide(onOlderCellular)

        XCTAssertEqual(composed.trackSelection.expensiveNetworkMaxVideoBitrateBps, 730_000)
        // A quarter of 16 MiB at 730 kbit/s is 45.96 s, in whole seconds; at the 8 Mbit/s reference
        // rate the buffer policy alone assumes, it would be 4 s.
        XCTAssertEqual(composed.buffer.maxBufferMs, 45_000)
        XCTAssertGreaterThan(composed.buffer.maxBufferMs!, alone.buffer.maxBufferMs!)
    }

    /// The policy brings its estimator — a fresh oracle for every player that asks — and the
    /// halves alone bring none, so a consumer composing them by hand gets core's item window.
    func testThePolicyBringsAnOraclePerPlayer() throws {
        let policy = try XCTUnwrap(
            AdaptivePolicy.forProfile(.videoOnDemand) as? any ThroughputEstimatingPolicy)
        let first = policy.makeThroughputSource()
        let second = policy.makeThroughputSource()
        XCTAssertTrue(first is BandwidthOracle)
        XCTAssertFalse((first as AnyObject) === (second as AnyObject))
        XCTAssertNil(
            AdaptiveBufferPolicy(profile: .videoOnDemand) as? any ThroughputEstimatingPolicy)
        XCTAssertNil(
            AdaptiveSelectionPolicy(profile: .videoOnDemand) as? any ThroughputEstimatingPolicy)
    }
}
