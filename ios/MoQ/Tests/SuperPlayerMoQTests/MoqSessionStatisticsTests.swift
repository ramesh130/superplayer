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

    /// Polled again and again, not once: several polls are waited for as an event, and the reading
    /// in force moves with them.
    func testTheSessionIsPolledAgainRatherThanOnce() throws {
        let relay = relay()
        let source = subscribed(relay)
        defer { source.cancel() }

        XCTAssertTrue(relay.awaitPolls(Self.pollsWatched, within: RecordingSink.waitBound))
        // The reading in force is a later one than the first, which is what makes the polls
        // readings rather than calls whose answers went nowhere.
        let latest = try XCTUnwrap(source.statistics())
        XCTAssertGreaterThan(
            try XCTUnwrap(latest.bytesReceived), try XCTUnwrap(scriptedStatistics(0).bytesReceived))
    }

    /// Paced rather than continuous, stated as a count against another count rather than against a
    /// clock (ADR-0021 rule 4): at an interval of an hour, the session is asked once while the
    /// subscription delivers ``framesWhilePaced`` frames — which a poller that did not wait would
    /// have outnumbered many times over, since a poll is no slower than a scripted frame.
    func testTheSessionIsPolledOnACadenceRatherThanContinuously() {
        let relay = relay()
        let sink = RecordingSink()
        let source = MoqFrameSource(
            url: broadcast, relay: relay, statisticsIntervalMs: Self.anHour)
        defer { source.cancel() }

        source.subscribe(sink)
        XCTAssertTrue(relay.awaitPolls(1, within: RecordingSink.waitBound))
        XCTAssertTrue(sink.awaitFrames(Self.framesWhilePaced))

        XCTAssertEqual(relay.statisticsPolls, 1)
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
        // The poller giving up is an event: its thread returns, after which it can ask nothing more.
        // It is waited for once it has asked, which is when it certainly exists.
        XCTAssertTrue(relay.awaitPolls(1, within: RecordingSink.waitBound))
        XCTAssertTrue(source.awaitThread("moq-stats", within: RecordingSink.waitBound))
        XCTAssertTrue(sink.awaitFrames(Self.framesWatched))

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

        XCTAssertNotNil(source.statistics())
        // `cancel()` joins the poller, so it has returned and can ask nothing more.
        XCTAssertTrue(source.joinedThreadsHaveFinished)
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

    /// An interval no test outlasts, so a second poll can only come from a poller that did not wait.
    private static let anHour: Int64 = 3_600_000

    /// Frames delivered while the hour-long interval holds: at the scripted relay's two milliseconds
    /// each, about an eighth of a second in which an unpaced poller would ask hundreds of times.
    private static let framesWhilePaced = 64

    /// Several polls, so "polled once" cannot pass.
    private static let pollsWatched = 8
}
