//
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

import Foundation
import MoqFFI
import XCTest

@testable import SuperPlayerMoQ

/// A subscription's session statistics, polled on a cadence and read off the source — the Kotlin
/// `MoqSessionStatisticsTest`, claim for claim, through ``ScriptedMoqRelay``, whose script is a
/// function of the poll count so movement is assertable. The cadence is shortened to
/// ``interval`` through the internal initializer, so no test waits seconds for a poll.
final class MoqSessionStatisticsTests: XCTestCase {

    func testASubscriptionReportsWhatItsSessionSaid() throws {
        let relay = relay()
        let source = subscribed(relay)
        defer { source.cancel() }

        let reading = try awaitStatistics(relay, source)

        XCTAssertEqual(reading, try scripted(at: reading))
        XCTAssertGreaterThan(reading.sampledAtMs, 0)
    }

    /// Polled again and again — not once — and paced rather than continuous. Both halves are counts:
    /// several polls are waited for as an event, and over a window the polls are bounded by the
    /// window over the interval, which a slow host can only make smaller. The window's length is an
    /// input to that bound and is asserted nowhere (ADR-0021 rule 4).
    func testTheSessionIsPolledOnACadenceRatherThanOnceOrContinuously() throws {
        let relay = relay()
        let source = subscribed(relay)
        defer { source.cancel() }

        XCTAssertTrue(relay.awaitPolls(Self.pollsWatched, within: RecordingSink.waitBound))
        let clock = ContinuousClock()
        let before = relay.statisticsPolls
        let start = clock.now
        Thread.sleep(forTimeInterval: Self.quietWindow)
        let elapsed = clock.now - start
        let polled = relay.statisticsPolls - before
        let ceiling =
            Int(elapsed / .milliseconds(Int(MoqSessionStatisticsTests.interval))) + 2

        XCTAssertLessThanOrEqual(polled, ceiling, "\(polled) polls in \(elapsed)")
        // And the reading in force is a later one than the first, which is what makes the polls
        // readings rather than calls whose answers went nowhere.
        let latest = try XCTUnwrap(source.statistics())
        XCTAssertGreaterThan(
            try XCTUnwrap(latest.bytesReceived), try XCTUnwrap(scriptedStatistics(0).bytesReceived))
    }

    /// A session that answers nothing leaves **no** reading — never a record of zeroes.
    func testASubscriptionWithNoReadingReportsAbsenceAndNeverZero() {
        let relay = relay(statisticsThatFail)
        let sink = RecordingSink()
        let source = MoqFrameSource(
            url: broadcast, relay: relay, statisticsIntervalMs: Self.interval)
        defer { source.cancel() }

        source.subscribe(sink)
        XCTAssertTrue(relay.awaitPolls(1, within: RecordingSink.waitBound))
        XCTAssertTrue(sink.awaitFrames(Self.framesWatched))

        XCTAssertNil(source.statistics())
    }

    func testANumberTheSessionDidNotReportIsNilAndNotZero() throws {
        let relay = relay(statisticsMissingTheReceiveRate)
        let source = subscribed(relay)
        defer { source.cancel() }

        let reading = try awaitStatistics(relay, source)

        XCTAssertNil(reading.receiveRateBps)
        // And nothing else moved: the whole record is the script's, with that one field gone.
        let expected = try scripted(at: reading)
        XCTAssertEqual(reading.bytesReceived, expected.bytesReceived)
        XCTAssertEqual(reading.roundTripTimeUs, expected.roundTripTimeUs)
        XCTAssertEqual(reading.transportPacketsLost, expected.transportPacketsLost)
    }

    /// A failed poll ends the polling — one ask, not one per interval — and never the subscription.
    func testAFailedPollDoesNotEndTheSubscription() {
        let relay = relay(statisticsThatFail)
        let sink = RecordingSink()
        let source = MoqFrameSource(
            url: broadcast, relay: relay, statisticsIntervalMs: Self.interval)
        defer { source.cancel() }

        source.subscribe(sink)
        XCTAssertTrue(sink.awaitFrames(Self.framesWatched))
        Thread.sleep(forTimeInterval: Self.quietWindow)

        XCTAssertNil(sink.terminal)
        XCTAssertNil(source.statistics())
        XCTAssertEqual(relay.statisticsPolls, 1)
    }

    func testUpstreamMediaLossIsNotInstrumentedAndIsNotZero() throws {
        let relay = relay()
        let source = subscribed(relay)
        defer { source.cancel() }

        let reading = try awaitStatistics(relay, source)

        XCTAssertEqual(reading.upstreamMediaLoss, .notInstrumented)
        // The connection's own loss is a different fact, and really is reported.
        XCTAssertGreaterThan(try XCTUnwrap(reading.transportPacketsLost), 0)
    }

    func testTheLastReadingSurvivesCancellationAndThePollerDoesNot() throws {
        let relay = relay()
        let source = subscribed(relay)
        _ = try awaitStatistics(relay, source)
        source.cancel()
        let pollsAtCancel = relay.statisticsPolls

        Thread.sleep(forTimeInterval: Self.quietWindow)

        XCTAssertNotNil(source.statistics())
        XCTAssertEqual(relay.statisticsPolls, pollsAtCancel)
    }

    // MARK: - Fixtures

    private func subscribed(_ relay: ScriptedMoqRelay) -> MoqFrameSource {
        let sink = RecordingSink()
        let source = MoqFrameSource(
            url: broadcast, relay: relay, statisticsIntervalMs: Self.interval)
        source.subscribe(sink)
        XCTAssertTrue(sink.awaitFrames(Self.framesWatched))
        return source
    }

    /// The reading after the session has been polled twice, so it is one the poller kept.
    private func awaitStatistics(_ relay: ScriptedMoqRelay, _ source: MoqFrameSource) throws
        -> MoqSessionStatistics
    {
        XCTAssertTrue(relay.awaitPolls(2, within: RecordingSink.waitBound))
        return try XCTUnwrap(source.statistics(), "the session was polled and left no reading")
    }

    /// What the script answered on the poll `reading` came from, stamped at the reading's own time.
    private func scripted(at reading: MoqSessionStatistics) throws -> MoqSessionStatistics {
        let ordinal = Int(try XCTUnwrap(reading.transportPacketsLost)) - 1
        XCTAssertGreaterThanOrEqual(ordinal, 0)
        let script = scriptedStatistics(ordinal)
        return MoqSessionStatistics(
            sampledAtMs: reading.sampledAtMs, roundTripTimeUs: script.roundTripTimeUs,
            sendRateBps: script.sendRateBps, receiveRateBps: script.receiveRateBps,
            bytesSent: script.bytesSent, bytesReceived: script.bytesReceived,
            packetsSent: script.packetsSent, packetsReceived: script.packetsReceived,
            transportBytesLost: script.transportBytesLost,
            transportPacketsLost: script.transportPacketsLost)
    }

    private func relay(
        _ script: @escaping @Sendable (Int) throws -> MoqSessionStatistics = scriptedStatistics
    ) -> ScriptedMoqRelay {
        ScriptedMoqRelay(
            catalog: DeclaredCatalogs.catalog(video: [
                "video": DeclaredCatalogs.video(
                    DeclaredCatalogs.observedAvc3Codec, description: nil)
            ]),
            statisticsScript: script)
    }

    private let broadcast = URL(string: "\(MoqFrameSource.scheme)://relay.example/anon/studio-a")!

    private static let framesWatched = 4

    /// The test cadence: twenty milliseconds, so a handful of polls costs a fraction of a second.
    private static let interval: Int64 = 20

    /// Ten intervals: long enough that a continuous poller would be counted in the hundreds and a
    /// poller that outlived its cancel would ask again inside it.
    private static let quietWindow: TimeInterval = 0.2

    /// Several polls, so "polled once" cannot pass.
    private static let pollsWatched = 8
}
