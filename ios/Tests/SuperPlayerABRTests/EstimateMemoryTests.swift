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

/// ADR-0009 rules 8 and 9 on stated times — the Kotlin `EstimateMemoryTest` and `SampleWindowTest`:
/// the window's three readings, per-transport keys, the cold default, and aging between two and
/// fifteen minutes. Every time is an argument, so nothing here waits.
final class EstimateMemoryTests: XCTestCase {

    /// 1..8 Mbit/s: mean 4.5 M, population spread √5.25 M rounded down, and the second-lowest
    /// sample as the nearest-rank 25th percentile.
    func testAFreshWindowIsItsThreeReadings() {
        let memory = EstimateMemory()
        for mbps in 1...8 { memory.record(Int64(mbps) * 1_000_000, on: .wifi, atMs: 1_000) }

        let estimate = memory.estimate(for: .wifi, atMs: 1_400)
        XCTAssertEqual(estimate.meanBps, 4_500_000)
        XCTAssertEqual(estimate.spreadBps, 2_291_287)
        XCTAssertEqual(estimate.conservativeBps, 2_000_000)
        XCTAssertEqual(estimate.sampleCount, 8)
        XCTAssertEqual(estimate.newestSampleAgeMs, 400)
    }

    /// One sample is its own mean and percentile and has no scatter — the Kotlin
    /// `aSingleSampleHasNoSpread`.
    func testASingleSampleHasNoSpread() {
        let memory = EstimateMemory()
        memory.record(3_000_000, on: .wifi, atMs: 0)
        let estimate = memory.estimate(for: .wifi, atMs: 0)
        XCTAssertEqual(estimate.meanBps, 3_000_000)
        XCTAssertEqual(estimate.conservativeBps, 3_000_000)
        XCTAssertEqual(estimate.spreadBps, 0)
    }

    func testTheWindowKeepsTheNewestEight() {
        let memory = EstimateMemory()
        memory.record(100_000_000, on: .wifi, atMs: 0)
        for _ in 0..<8 { memory.record(1_000_000, on: .wifi, atMs: 0) }

        XCTAssertEqual(memory.estimate(for: .wifi, atMs: 0).meanBps, 1_000_000)
        XCTAssertEqual(memory.estimate(for: .wifi, atMs: 0).sampleCount, 8)
    }

    /// Each transport is a window of its own: a handover reads the new one's memory or its cold
    /// default, never the old one's samples — the hotspot apart from the device's own LTE.
    func testEstimatesAreKeptPerTransport() {
        let memory = EstimateMemory()
        memory.record(20_000_000, on: .wifi, atMs: 0)
        memory.record(3_000_000, on: .cellular(.lte), atMs: 0)

        XCTAssertEqual(memory.estimate(for: .wifi, atMs: 0).meanBps, 20_000_000)
        XCTAssertEqual(memory.estimate(for: .cellular(.lte), atMs: 0).meanBps, 3_000_000)
        let hotspot = memory.estimate(for: .cellular(nil), atMs: 0)
        XCTAssertEqual(hotspot.meanBps, ColdDefaults.cellularUnknownGenerationBps)
        XCTAssertEqual(hotspot.sampleCount, 0)
    }

    func testANeverMeasuredTransportIsItsColdDefaultWithAnUnknownSpread() {
        let memory = EstimateMemory()
        let cases: [(NetworkTransport, Int64)] = [
            (.wifi, 5_000_000), (.ethernet, 10_000_000), (.cellular(.nr), 8_000_000),
            (.cellular(.lte), 4_000_000), (.cellular(.older), 400_000),
            (.cellular(nil), 4_000_000), (.unknown, 1_000_000),
        ]
        for (transport, bps) in cases {
            let estimate = memory.estimate(for: transport, atMs: 0)
            XCTAssertEqual(estimate.meanBps, bps, "\(transport)")
            XCTAssertNil(estimate.spreadBps)
            XCTAssertNil(estimate.conservativeBps)
            XCTAssertEqual(estimate.sampleCount, 0)
        }
    }

    /// Fresh for two minutes, worthless at fifteen, linear between — the level only; the spread and
    /// the count stay as measured.
    func testAnEstimateAgesTowardTheColdDefaultBetweenTwoAndFifteenMinutes() {
        let memory = EstimateMemory(coldDefaultBps: { _ in 1_000_000 })
        for _ in 0..<4 { memory.record(9_000_000, on: .wifi, atMs: 0) }
        for _ in 0..<4 { memory.record(11_000_000, on: .wifi, atMs: 0) }

        let fresh = memory.estimate(for: .wifi, atMs: EstimateMemory.freshAgeMs)
        XCTAssertEqual(fresh.meanBps, 10_000_000)

        let midway = (EstimateMemory.freshAgeMs + EstimateMemory.staleAgeMs) / 2
        let half = memory.estimate(for: .wifi, atMs: midway)
        XCTAssertEqual(half.meanBps, 5_500_000, "half of 10 M and half of the 1 M default")
        XCTAssertEqual(half.conservativeBps, 5_000_000, "9 M decayed the same way")
        XCTAssertEqual(half.spreadBps, 1_000_000, "the scatter does not age")
        XCTAssertEqual(half.sampleCount, 8)

        let stale = memory.estimate(for: .wifi, atMs: EstimateMemory.staleAgeMs)
        XCTAssertEqual(stale.meanBps, 1_000_000)
        XCTAssertEqual(stale.sampleCount, 0, "forgotten, not merely decayed")
    }

    /// A sample timed after now was timed on a clock that restarted: forgotten, the Kotlin rule.
    func testASampleFromTheFutureIsForgotten() {
        let memory = EstimateMemory()
        memory.record(20_000_000, on: .wifi, atMs: 50_000)
        XCTAssertEqual(memory.estimate(for: .wifi, atMs: 10_000).sampleCount, 0)
    }

    /// A revision replaces its own sample and moves its time, and a sample pushed out of the window
    /// meanwhile is recorded again as a new one rather than lost.
    func testARevisionReplacesItsOwnSampleOrRecordsAgain() {
        let memory = EstimateMemory()
        let token = memory.record(1_000_000, on: .wifi, atMs: 0)
        memory.record(3_000_000, on: .wifi, atMs: 0)

        _ = memory.revise(token, to: 5_000_000, atMs: 500)
        let revised = memory.estimate(for: .wifi, atMs: 500)
        XCTAssertEqual(revised.meanBps, 4_000_000)
        XCTAssertEqual(revised.sampleCount, 2)
        XCTAssertEqual(revised.newestSampleAgeMs, 0)

        for _ in 0..<8 { memory.record(2_000_000, on: .wifi, atMs: 600) }
        _ = memory.revise(token, to: 10_000_000, atMs: 700)
        let after = memory.estimate(for: .wifi, atMs: 700)
        XCTAssertEqual(after.sampleCount, 8)
        XCTAssertEqual(after.meanBps, (7 * 2_000_000 + 10_000_000) / 8)
    }

    func testForgetEmptiesEveryTransport() {
        let memory = EstimateMemory()
        memory.record(20_000_000, on: .wifi, atMs: 0)
        memory.record(2_000_000, on: .cellular(.lte), atMs: 0)
        memory.forget()
        XCTAssertEqual(memory.estimate(for: .wifi, atMs: 0).sampleCount, 0)
        XCTAssertEqual(memory.estimate(for: .cellular(.lte), atMs: 0).sampleCount, 0)
    }
}
