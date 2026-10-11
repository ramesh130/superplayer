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

import AVFoundation
import SuperPlayerCore
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest

@testable import SuperPlayerBenchmark

/// `StockTelemetry` and `QoeCollector` attached to **one `AVPlayer`** derive the same events — the
/// Kotlin `StockTelemetryAgreementTest`, and for its reason the test the benchmark's central claim
/// stands on: if it goes, the claim goes with it.
///
/// ## Both collectors, one player
///
/// A `SuperPlayer` is built with `QoeCollector` as its telemetry, and `StockTelemetry` is attached
/// to the same engine through `player.avPlayer` — ADR-0020's escape hatch, used here for what it is
/// for from outside the library. The two receive the same notifications and key-value changes from
/// the same item, so any difference in what comes out is a difference between the collectors. The
/// Kotlin test's history is the reason it is one player and not two: two players driven through one
/// script differ as *players*, which is the benchmark's subject, not this test's.
///
/// ## What is compared on real time
///
/// Everything the collectors derive that is not a reading of a clock: the sequence of event kinds,
/// the time-to-first-frame boundary, every stall's seek attribution, every switch's bitrates and
/// direction, the sampling cadence and count, and the numbers `SessionMetrics` reduces both streams
/// to that are counts. **Durations are not compared**: the two read `CLOCK_MONOTONIC` in two
/// handlers of one callback, a few microseconds apart, so equality would assert the scheduler's
/// timing and a tolerance would be a duration asserted (ADR-0021 rule 4). The Kotlin test compares
/// them because its clock is a fake one both collectors read at one instant.
///
/// Not compared, as on Android: what each is merely *given* — the session id (core's for one, the
/// test's for the other) and `SessionStarted`'s profile and decision.
///
/// The control is that the session is not quiet: the link falls below the bottom rung for longer
/// than the buffer lasts, so there is a stall, the script seeks, and it plays a ladder, so there is a rendition
/// choice; each is asserted to have
/// happened before the two streams are held equal, so the agreement is not two empty lists.
@MainActor
final class StockTelemetryAgreementTests: XCTestCase {

    private static var played: (qoe: [TelemetryEvent], stock: [TelemetryEvent])?

    /// Why the session could not be played, once it could not: every test of the class reads one
    /// session, so the second test fails at once on the first one's reason rather than waiting out
    /// the bound again.
    private static var unplayable: String?

    /// How far ahead the session's item buffers: two segments, so the slow stretch drains it in
    /// seconds. A setting on the one item both collectors watch, so it is the same input to both.
    private static let forwardBufferSeconds: TimeInterval = 4

    private let bound: Duration = .seconds(90)

    func testBothCollectorsDeriveTheSameEventsFromTheSameCallbacks() async throws {
        let (qoe, stock) = try await session()
        // The coarsest and most valuable assertion: a collector that opened a stall where the other
        // did not, or reported a first frame twice, differs here before it differs in any number.
        XCTAssertEqual(qoe.map(\.kind), stock.map(\.kind))
    }

    func testBothCollectorsMeasureTimeToFirstFrameFromTheSameBoundary() async throws {
        let (qoe, stock) = try await session()
        let fromQoe = try XCTUnwrap(qoe.compactMap(\.firstFrame).first)
        let fromStock = try XCTUnwrap(stock.compactMap(\.firstFrame).first)
        // The boundary is what would make the arms incomparable rather than merely different: the
        // schema forbids aggregating the two together.
        XCTAssertEqual(fromQoe.startBoundary, .userIntent)
        XCTAssertEqual(fromStock.startBoundary, fromQoe.startBoundary)
    }

    func testBothCollectorsAttributeEveryStallTheSameWay() async throws {
        let (qoe, stock) = try await session()
        let fromQoe = qoe.compactMap(\.rebufferEnded)
        XCTAssertFalse(fromQoe.isEmpty, "the slow link produced no stall to compare")
        // The attribution is what would silently move a number: a stall one collector calls the
        // seek's and the other does not is seconds one arm's ratio counts and the other's excludes.
        XCTAssertEqual(
            fromQoe.map(\.seekInduced), stock.compactMap(\.rebufferEnded).map(\.seekInduced))
    }

    func testBothCollectorsReportTheSameTrackSwitches() async throws {
        let (qoe, stock) = try await session()
        let fromQoe = qoe.compactMap(\.trackSwitched)
        let fromStock = stock.compactMap(\.trackSwitched)
        XCTAssertFalse(fromQoe.isEmpty, "the ladder produced no rendition choice to compare")
        XCTAssertEqual(fromQoe.map(\.direction), fromStock.map(\.direction))
        XCTAssertEqual(fromQoe.map(\.fromBitrateBps), fromStock.map(\.fromBitrateBps))
        XCTAssertEqual(fromQoe.map(\.toBitrateBps), fromStock.map(\.toBitrateBps))
    }

    /// The cadence is the weight of a time-weighted average: two arms sampled at different rates
    /// would make the bitrate column two statistics under one name.
    func testBothCollectorsSampleAtTheSameCadence() async throws {
        let (qoe, stock) = try await session()
        let fromQoe = qoe.compactMap(\.stateSample)
        XCTAssertFalse(fromQoe.isEmpty, "the session produced no state sample to compare")
        XCTAssertEqual(
            fromQoe.map(\.samplingIntervalMs),
            stock.compactMap(\.stateSample).map(\.samplingIntervalMs))
        XCTAssertEqual(
            fromQoe.map(\.videoBitrateBps), stock.compactMap(\.stateSample).map(\.videoBitrateBps))
    }

    /// At the level a report is built at: the shared reducer lands on the same counts and the same
    /// bitrate whichever collector fed it.
    func testTheSharedReducerGetsTheSameCountsFromEitherCollector() async throws {
        let (qoe, stock) = try await session()
        XCTAssertTrue(qoe.contains { $0.kind == "seekRequested" }, "the script issued no seek")
        let fromQoe = SessionMetrics.from(sessionId: qoe[0].sessionId, events: qoe)
        let fromStock = SessionMetrics.from(sessionId: stock[0].sessionId, events: stock)
        XCTAssertTrue(fromQoe.usable)
        XCTAssertTrue(fromStock.usable)
        XCTAssertEqual(fromQoe.rebufferCount, fromStock.rebufferCount)
        XCTAssertEqual(fromQoe.switchCount, fromStock.switchCount)
        XCTAssertEqual(fromQoe.upshiftCount, fromStock.upshiftCount)
        XCTAssertEqual(fromQoe.downshiftCount, fromStock.downshiftCount)
        XCTAssertEqual(fromQoe.switchMagnitudeBpsSum, fromStock.switchMagnitudeBpsSum)
        XCTAssertEqual(fromQoe.averageBitrateBps, fromStock.averageBitrateBps)
        XCTAssertEqual(fromQoe.startupFailed, fromStock.startupFailed)
    }

    // MARK: - One session, watched twice

    /// Plays the scripted session once for the whole class: a stall, a seek, a sample.
    private func session() async throws -> (qoe: [TelemetryEvent], stock: [TelemetryEvent]) {
        if let played = Self.played { return played }
        if let unplayable = Self.unplayable { throw XCTSkip("Not played: \(unplayable)") }
        let harness = PlaybackHarness()
        defer { harness.stop() }
        let origin = try await harness.serve(
            .videoLadder(bitratesBps: [365_000, 730_000], segmentCount: 30),
            network: try Self.outage())

        let qoe = EventRecorder()
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: qoe) }
        let player = SuperPlayer(
            configuration: configuration, lifecycle: SharedLifecycle(platform: QuietPlatform()))
        let stock = EventRecorder()
        let mirror = StockTelemetry { stock.record($0) }
        mirror.attach(to: player.avPlayer)

        // Intent for both before either session opens, so neither boundary can differ; then the two
        // openings, adjacent — `setMediaRequest` is what opens arm (c)'s.
        player.declarePlaybackIntent()
        mirror.declareIntent(monotonicTimeMs: SessionRunner.now())
        player.setMediaRequest(MediaRequest(contentId: "agreement", sources: [origin.sourceURL]))
        mirror.startSession(
            contentId: "agreement", sessionId: "stock-mirror", profile: .videoOnDemand,
            decision: player.playbackDecision)
        player.avPlayer.currentItem?.add(AVPlayerItemVideoOutput(pixelBufferAttributes: nil))
        player.avPlayer.currentItem?.preferredForwardBufferDuration = Self.forwardBufferSeconds
        player.avPlayer.play()

        do {
            try await harness.until("the slow link stalls playback and it resumes", timeout: bound)
            {
                stock.events.contains { $0.kind == "rebufferEnded" }
            }
        } catch {
            // What a failure needs to be diagnosed from a CI log: what each collector derived and
            // what the origin was asked for.
            Self.unplayable =
                "\(error); stock derived \(stock.events.map(\.kind)), the collector "
                + "\(qoe.events.map(\.kind)); requested "
                + "\(origin.networkRequests().map(\.path))"
            player.release()
            XCTFail(Self.unplayable!)
            throw error
        }
        // A seek back into what has played: requested and completed, with the exclusion window
        // after it.
        await player.avPlayer.seek(to: CMTime(value: 1, timescale: 1))
        try await harness.until("the seek completes", timeout: bound) {
            stock.events.contains { $0.kind == "seekCompleted" }
        }
        // End just after a sample, so the next is a whole interval away from the end and both
        // collectors' timers, started microseconds apart, have fired the same number of times.
        try await harness.until("a state sample from each", timeout: bound) {
            qoe.events.contains { $0.kind == "playbackStateSampled" }
                && stock.events.contains { $0.kind == "playbackStateSampled" }
        }
        mirror.endSession()
        mirror.detach()
        player.release()
        try await harness.until("arm (c)'s SessionEnded is delivered", timeout: bound) {
            qoe.events.contains { $0.kind == "sessionEnded" }
        }
        let played = (qoe.events, stock.events)
        Self.played = played
        return played
    }
}

extension StockTelemetryAgreementTests {

    /// A link fast enough to start on, then too slow for any rung: 2 Mbit/s for ten seconds, so
    /// the first frame comes however slow the host — then 150 kbit/s for thirty, under half of
    /// what the 365 kbit/s rung needs, so the buffer drains whatever the player chose and it has to
    /// wait — then stable WiFi's twenty megabits for good, so playback resumes and stays up.
    ///
    /// Two earlier forms are why it is this one. A link cut to nothing stalled on a developer's
    /// simulator and never on CI's; a link slow from six seconds in was slow before CI's player had
    /// started, and a player that has not started waits rather than stalls, which no collector
    /// reports as a rebuffer. The session also caps the item's forward buffer
    /// (``forwardBufferSeconds``), so the drain is seconds long rather than as long as the fast
    /// stretch let the player buffer.
    static func outage() throws -> ThroughputTrace {
        try ThroughputTrace.Builder()
            .add(durationMs: 10_000, bandwidthBps: 2_000_000, transport: .wifi)
            .add(durationMs: 30_000, bandwidthBps: 150_000, transport: .wifi)
            .add(durationMs: 1_000, bandwidthBps: 20_000_000, transport: .wifi)
            .holdAtEnd()
            .build()
    }
}

extension TelemetryEvent {
    /// The case's name, which is what the two streams are compared by: the events themselves carry
    /// ids and clock readings that differ by construction.
    ///
    /// An exhaustive switch rather than the value's printed form, which is no stable format; a case
    /// added to the vocabulary fails to compile here.
    var kind: String {
        switch self {
        case .sessionStarted: "sessionStarted"
        case .decisionChanged: "decisionChanged"
        case .sessionEnded: "sessionEnded"
        case .firstFrameRendered: "firstFrameRendered"
        case .rebufferStarted: "rebufferStarted"
        case .rebufferEnded: "rebufferEnded"
        case .startupFailed: "startupFailed"
        case .midStreamFailed: "midStreamFailed"
        case .trackSwitched: "trackSwitched"
        case .seekRequested: "seekRequested"
        case .seekCompleted: "seekCompleted"
        case .licenceAcquisitionEnded: "licenceAcquisitionEnded"
        case .liveLatencySampled: "liveLatencySampled"
        case .playbackStateSampled: "playbackStateSampled"
        case .videoFramesDropped: "videoFramesDropped"
        }
    }

    var firstFrame: FirstFrameRendered? {
        if case .firstFrameRendered(let e) = self { e } else { nil }
    }
    var rebufferEnded: RebufferEnded? { if case .rebufferEnded(let e) = self { e } else { nil } }
    var trackSwitched: TrackSwitched? { if case .trackSwitched(let e) = self { e } else { nil } }
    var stateSample: PlaybackStateSampled? {
        if case .playbackStateSampled(let e) = self { e } else { nil }
    }
}
