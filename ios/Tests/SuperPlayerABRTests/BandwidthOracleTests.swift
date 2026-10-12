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

/// The oracle as core drives it on an adaptive player, on an injected clock and a memory of its
/// own — the Kotlin `BandwidthOracleTest` and `OracleBandwidthMeterTest`'s claims, with the access
/// log in place of transfers: an entry is one sample, revised while it moves; a handover reseeds
/// from the new transport's memory; an entry open across the handover is not counted on the new one;
/// and
/// a policy is told of a fifth's move or a crossing of the stability line, and of nothing smaller.
@MainActor
final class BandwidthOracleTests: XCTestCase {

    private let clock = TestClock()
    private let memory = EstimateMemory()

    private func oracle(on transport: NetworkTransport = .wifi) -> BandwidthOracle {
        let oracle = BandwidthOracle(memory: memory, now: clock.read)
        oracle.transportChanged(to: transport, atMs: clock.now)
        return oracle
    }

    /// Before the platform has said anything the transport is unknown, with that cold default.
    func testAnOracleStartsOnAnUnknownTransportsColdDefault() {
        let oracle = BandwidthOracle(memory: memory, now: clock.read)
        XCTAssertEqual(oracle.currentTransport(), .unknown)
        XCTAssertEqual(oracle.currentEstimate().meanBps, ColdDefaults.unknownBps)
        XCTAssertEqual(oracle.currentEstimate().sampleCount, 0)
    }

    /// Each entry is one sample; the open entry's moving figure revises its sample rather than
    /// adding one, and an unmeasured entry is none.
    func testEachAccessLogEntryIsOneSampleRevisedWhileItMoves() throws {
        let oracle = oracle()
        oracle.read(observedBitratesBps: [-1, 2_000_000], atMs: 0)
        oracle.read(observedBitratesBps: [-1, 4_000_000], atMs: 1_000)
        oracle.read(observedBitratesBps: [-1, 4_000_000, 6_000_000], atMs: 2_000)

        let estimate = try XCTUnwrap(oracle.estimate(atMs: 2_000))
        XCTAssertEqual(estimate.sampleCount, 2)
        XCTAssertEqual(estimate.meanBps, 5_000_000)
    }

    /// A closed entry keeps the time it was last measured at: reading the log again moves nothing.
    func testRereadingAnUnchangedLogMovesNoSamplesTime() throws {
        let oracle = oracle()
        oracle.read(observedBitratesBps: [3_000_000], atMs: 0)
        oracle.read(observedBitratesBps: [3_000_000], atMs: 5_000)
        XCTAssertEqual(try XCTUnwrap(oracle.estimate(atMs: 5_000)).newestSampleAgeMs, 5_000)
    }

    /// The memory outlives an item and a player: what one measured, the next starts from.
    func testTheEstimateOutlivesTheItemAndThePlayer() throws {
        let first = oracle()
        first.read(observedBitratesBps: [8_000_000], atMs: 0)
        first.itemChanged()
        first.read(observedBitratesBps: [4_000_000], atMs: 100)
        XCTAssertEqual(try XCTUnwrap(first.estimate(atMs: 100)).sampleCount, 2)

        clock.now = 100
        let second = oracle()
        XCTAssertEqual(try XCTUnwrap(second.estimate(atMs: 100)).meanBps, 6_000_000)
    }

    /// A handover reseeds from the new transport — its own memory where it has one — and the entry
    /// open across it keeps its pre-handover figure on the old link and is never counted on the new
    /// one; the entries after it are the new link's.
    func testAHandoverReseedsAndTheOpenEntryIsNotCountedOnTheNewLink() throws {
        memory.record(3_000_000, on: .cellular(.lte), atMs: 0)
        let oracle = oracle()
        oracle.read(observedBitratesBps: [20_000_000], atMs: 0)

        oracle.transportChanged(to: .cellular(.lte), atMs: 100)
        XCTAssertEqual(oracle.currentTransport(), .cellular(.lte))
        XCTAssertEqual(try XCTUnwrap(oracle.estimate(atMs: 100)).meanBps, 3_000_000)

        oracle.read(observedBitratesBps: [12_000_000], atMs: 200)
        XCTAssertEqual(
            try XCTUnwrap(oracle.estimate(atMs: 200)).sampleCount, 1,
            "the mixed entry's later figure went to neither link")
        oracle.read(observedBitratesBps: [12_000_000, 1_000_000], atMs: 300)
        XCTAssertEqual(try XCTUnwrap(oracle.estimate(atMs: 300)).meanBps, 2_000_000)
        XCTAssertEqual(memory.estimate(for: .wifi, atMs: 300).meanBps, 20_000_000)
    }

    /// The trigger: a move under a fifth of the last announced mean is not material, a fifth is, a
    /// crossing of the stability line is however small the move, and a handover's reseed is already
    /// announced by core's transport trigger, so it is not announced again.
    func testAMaterialMoveIsAFifthOrAStabilityCrossing() {
        let oracle = oracle()
        XCTAssertFalse(
            oracle.movedMaterially(atMs: 0), "the reseed was announced with the transport")

        oracle.read(observedBitratesBps: Array(repeating: 10_000_000, count: 8), atMs: 0)
        XCTAssertTrue(oracle.movedMaterially(atMs: 0), "cold 5 M to a stable 10 M")
        XCTAssertFalse(oracle.movedMaterially(atMs: 0), "nothing moved since")

        // The mean barely moves, to 10.1 M, but the spread passes a quarter of it: unstable now.
        oracle.itemChanged()
        oracle.read(observedBitratesBps: [2_500_000, 18_500_000], atMs: 10)
        XCTAssertTrue(oracle.movedMaterially(atMs: 10), "a stability crossing")

        // A small move on the same side of the line is nothing.
        oracle.itemChanged()
        oracle.read(observedBitratesBps: [11_000_000], atMs: 20)
        XCTAssertFalse(oracle.movedMaterially(atMs: 20))

        oracle.transportChanged(to: .cellular(.lte), atMs: 30)
        XCTAssertFalse(oracle.movedMaterially(atMs: 30))
    }
}

/// A monotonic clock a test sets.
final class TestClock: @unchecked Sendable {
    var now: Int64 = 0
    var read: @Sendable () -> Int64 { { [self] in now } }
}
