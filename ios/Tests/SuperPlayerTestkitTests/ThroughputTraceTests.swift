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

import XCTest

// `@testable` for the replay's `arrivalMs`, which is internal here as it is in Kotlin, and which
// the Kotlin `ThroughputTraceTest` reads the same way — the harness's own tests.
@testable import SuperPlayerTestkit

/// The port of `superplayer-testkit`'s `ThroughputTraceTest`: the grammar, its refusals, the
/// replay's arithmetic and the seven profiles, case for case and number for number. The shared
/// fixtures (`ThroughputTraceFixturesTests`) are what hold the two readers to *each other*; this is
/// what holds the Swift one to the document.
final class ThroughputTraceTests: XCTestCase {

    func testTheFormatReadsAsSpecified() throws {
        let trace = try ThroughputTrace.parse(
            """
            superplayer-throughput-trace 1
            # A comment, and a blank line below it.

            at-end hold
            2000 5000000 WIFI
            1000 0 CELLULAR 80
            500 1000000 CELLULAR
            """)

        XCTAssertEqual(trace.durationMs, 3_500)
        XCTAssertFalse(trace.loops)
        XCTAssertEqual(trace.bandwidthBps(at: 1_999), 5_000_000)
        XCTAssertEqual(trace.transport(at: 1_999), .wifi)
        // The round-trip column is optional, and absent means none.
        XCTAssertEqual(trace.rttMs(at: 0), 0)
        XCTAssertEqual(trace.bandwidthBps(at: 2_000), 0)
        XCTAssertEqual(trace.transport(at: 2_000), .cellular)
        XCTAssertEqual(trace.rttMs(at: 2_000), 80)
        // Held: past the end is the last stretch, for good.
        XCTAssertEqual(trace.bandwidthBps(at: 60_000), 1_000_000)
    }

    func testEveryProfileSurvivesBeingWrittenAndReadBack() throws {
        for profile in NetworkProfile.allCases {
            XCTAssertEqual(
                try ThroughputTrace.parse(profile.trace.format()), profile.trace, "\(profile)")
        }
    }

    func testAMalformedTraceIsRefusedNamingTheLine() {
        let header = ThroughputTrace.formatHeader
        let cases = [
            ("not-a-trace\n1000 1 WIFI", "Line 1:"),
            ("\(header)\n1000 1", "Line 2: a stretch is"),
            ("\(header)\n1000 1 WIFI 5 6", "Line 2: a stretch is"),
            ("\(header)\n1000 fast WIFI", "Line 2: bandwidth_bps"),
            ("\(header)\n1000 1 LTE", "Line 2: transport is one of"),
            ("\(header)\n0 1 WIFI", "Line 2: A stretch lasts at least 1 ms"),
            ("\(header)\n1000 -1 WIFI", "Line 2: A bandwidth is"),
            ("\(header)\n1000 1 WIFI\nat-end hold", "Line 3: \"at-end\" appears once"),
            ("\(header)\nat-end forever\n1000 1 WIFI", "Line 2: \"at-end\" is followed by"),
        ]
        for (text, expected) in cases {
            XCTAssertThrowsError(try ThroughputTrace.parse(text), text) { error in
                XCTAssertTrue("\(error)".hasPrefix(expected), "\(error) does not start \(expected)")
            }
        }
    }

    func testATraceThatCouldNeverDeliverIsRefused() {
        // Both are a replay that would wait forever, which hangs a test rather than failing it.
        XCTAssertThrowsError(
            try ThroughputTrace.Builder()
                .add(durationMs: 1_000, bandwidthBps: 0, transport: .cellular).build())
        XCTAssertThrowsError(
            try ThroughputTrace.Builder()
                .add(durationMs: 1_000, bandwidthBps: 1_000, transport: .wifi)
                .add(durationMs: 1_000, bandwidthBps: 0, transport: .cellular)
                .holdAtEnd()
                .build())
    }

    func testALoopingTraceStartsAgainAndAHoldingOneStaysPut() throws {
        let stretches = ThroughputTrace.Builder()
            .add(durationMs: 1_000, bandwidthBps: 1_000, transport: .wifi)
            .add(durationMs: 1_000, bandwidthBps: 2_000, transport: .cellular)

        let looping = try stretches.build()
        XCTAssertEqual(looping.bandwidthBps(at: 2_500), 1_000)
        XCTAssertEqual(looping.transport(at: 2_500), .wifi)

        let holding = try stretches.holdAtEnd().build()
        XCTAssertEqual(holding.bandwidthBps(at: 60_000), 2_000)
        XCTAssertEqual(holding.transport(at: 60_000), .cellular)
    }

    func testBytesArriveAtTheRateOfEveryStretchTheyCross() throws {
        // 4 096 bit/s is 512 bytes a second, so a quarter of that takes a quarter of a second.
        let trace = try dropoutTrace()

        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 128), 250)
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 256), 500)
        // The dropout carries nothing, so the third quarter waits it out.
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 384), 1_750)
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 512), 2_000)
        // And into the next pass, dropout and all.
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 896), 3_750)
        // A transfer starting in the dropout waits for the link before its first byte.
        XCTAssertEqual(trace.arrivalMs(startMs: 600, bytes: 128), 1_750)
    }

    func testATransferOutlastingManyPassesLandsWhereWalkingThemWouldPutIt() throws {
        // One pass carries exactly 512 bytes and ends at 2 000 ms, so a thousand passes end at
        // 2 000 000 — and the pass-skipping shortcut has to agree with that to the millisecond.
        let trace = try dropoutTrace()

        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 512 * 1_000), 2_000_000)
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 512 * 1_000 + 128), 2_000_250)
    }

    func testAHoldingTraceDeliversAtItsLastRateAfterTheEnd() throws {
        let trace = try ThroughputTrace.Builder()
            .add(durationMs: 1_000, bandwidthBps: 8_000, transport: .wifi).holdAtEnd().build()

        // 2 000 bytes is 16 000 bits: two seconds at 8 000 bit/s, the second of them past the end.
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 2_000), 2_000)
    }

    func testAByteHasNotArrivedUntilAllOfItHas() throws {
        let trace = try ThroughputTrace.Builder()
            .add(durationMs: 10_000, bandwidthBps: 3, transport: .wifi).build()

        // 8 bits at 3 bit/s is 2 666.67 ms, and the last bit lands in the 2 667th millisecond.
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 1), 2_667)
    }

    func testTheStableAndThreeGProfilesAreTheConstantRatesThePrdNames() {
        XCTAssertEqual(rates(.stableWifi), [20_000_000])
        XCTAssertEqual(NetworkProfile.stableWifi.trace.transport(at: 0), .wifi)
        XCTAssertEqual(rates(.threeG), [1_000_000])
        XCTAssertEqual(NetworkProfile.threeG.trace.transport(at: 0), .cellular)
    }

    func testCongestedWifiAveragesExactlyThreeMbpsAndSwingsWithinHalfOfIt() {
        let trace = NetworkProfile.congestedWifi.trace
        let perSecond = stride(from: Int64(0), to: trace.durationMs, by: 1_000).map {
            trace.bandwidthBps(at: $0)
        }

        XCTAssertEqual(perSecond.reduce(0, +) / Int64(perSecond.count), 3_000_000)
        XCTAssertTrue(perSecond.allSatisfy { (1_500_000...4_500_000).contains($0) })
        // It varies, which is the difference between this profile and a 3 Mbit/s cap.
        XCTAssertGreaterThan(Set(perSecond).count, perSecond.count / 2)
    }

    /// The Kotlin test's pinned draws: `java.util.Random(2066)`'s first two seconds, which the
    /// benchmark's caveat rests on. The Swift port of the generator draws the same numbers or this
    /// fails before the fixtures do.
    func testCongestedWifiDrawsTheJvmsSequence() {
        let trace = NetworkProfile.congestedWifi.trace

        XCTAssertEqual(trace.bandwidthBps(at: 0), 4_374_064)
        XCTAssertEqual(trace.bandwidthBps(at: 1_000), 1_625_936)
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 730_000 * 2 / 8), 334)
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 2_000_000 * 2 / 8), 915)
        XCTAssertEqual(trace.arrivalMs(startMs: 0, bytes: 4_500_000 * 2 / 8), 2_786)
    }

    func testLteDropsOutForTwoSecondsEveryPeriod() {
        let trace = NetworkProfile.lteWithDropouts.trace
        let period = NetworkProfile.lteDropoutPeriodMs

        XCTAssertEqual(trace.bandwidthBps(at: 0), 5_000_000)
        XCTAssertEqual(trace.bandwidthBps(at: period - 2_001), 5_000_000)
        XCTAssertEqual(trace.bandwidthBps(at: period - 2_000), 0)
        XCTAssertEqual(trace.bandwidthBps(at: period - 1), 0)
        // And again in the next period, because it loops.
        XCTAssertEqual(trace.bandwidthBps(at: 2 * period - 1), 0)
        // A dropout is a link with nothing on it, not a change of link.
        XCTAssertEqual(trace.transport(at: period - 1), .cellular)
    }

    func testTheHandoverChangesTransportMidPlaybackAndDoesNotChangeBack() {
        let trace = NetworkProfile.wifiToCellularHandover.trace
        let at = NetworkProfile.handoverAtMs

        XCTAssertEqual(trace.transport(at: at - 1), .wifi)
        XCTAssertEqual(trace.bandwidthBps(at: at - 1), 20_000_000)
        XCTAssertEqual(trace.transport(at: at), .cellular)
        XCTAssertEqual(trace.bandwidthBps(at: at), 5_000_000)
        // Held, not looped: the device does not wander back onto WiFi because the trace ran out.
        XCTAssertEqual(trace.transport(at: at * 100), .cellular)
    }

    func testHighLatencyDiffersFromStableWifiOnlyInItsRoundTrip() {
        XCTAssertEqual(NetworkProfile.highLatency.trace.rttMs(at: 0), 600)
        XCTAssertEqual(rates(.highLatency), rates(.stableWifi))
        XCTAssertEqual(
            NetworkProfile.allCases.filter { $0.trace.rttMs(at: 0) != 0 }, [.highLatency])
    }

    func testEthernetDiffersFromStableWifiOnlyInItsTransport() {
        XCTAssertEqual(rates(.ethernet), rates(.stableWifi))
        XCTAssertEqual(NetworkProfile.ethernet.trace.transport(at: 0), .ethernet)
        XCTAssertEqual(NetworkProfile.ethernet.trace.rttMs(at: 0), 0)
    }

    private func dropoutTrace() throws -> ThroughputTrace {
        try ThroughputTrace.Builder()
            .add(durationMs: 500, bandwidthBps: 4_096, transport: .cellular)
            .add(durationMs: 1_000, bandwidthBps: 0, transport: .cellular)
            .add(durationMs: 500, bandwidthBps: 4_096, transport: .cellular)
            .build()
    }

    private func rates(_ profile: NetworkProfile) -> Set<Int64> {
        let trace = profile.trace
        return Set(
            stride(from: Int64(0), to: trace.durationMs, by: 500).map { trace.bandwidthBps(at: $0) }
        )
    }
}
