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
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// A ``ThroughputTrace`` replayed at the socket: a transfer's measured rate against the trace's, a
/// profile played through a stock `AVPlayer`, and the transport reported rather than switched.
///
/// **The one place a test here measures time**, because #410's acceptance asks for a rate, and it
/// is measured as a bound whose two sides are argued differently. *Never faster than the trace* is
/// exact: the origin sends each chunk no earlier than the trace says its last byte is due — a wait
/// on the host's clock can run late and never early — and the client's stopwatch starts before the
/// request reaches the origin, so the measured transfer can only be longer. *Not much slower* is
/// the tolerance: the lateness of a wake is not carried into the next chunk's schedule
/// (``LoopbackOrigin``), so what a loaded host adds is one wake's delay plus the client's own
/// connection setup and delivery, which ``slack`` bounds.
@MainActor
final class NetworkShapingTests: XCTestCase {

    /// A ceiling for media that plays at 1× on a loaded CI host, and only a bound.
    private let bound: Duration = .seconds(60)

    /// How much longer than the trace a shaped transfer may take: one second, on transfers sized to
    /// take two and a half — a third again, so a measured rate is at least two thirds of the
    /// trace's. Generous for one late wake and a loopback connection, which is the point: the claim
    /// that matters is the exact one, that the origin never delivers faster than the trace.
    private let slack: Duration = .seconds(1)

    /// One synthetic segment's length, the body every measured transfer here carries.
    private let segmentBytes = SyntheticHlsStream.files()["segment0.aac"]!.count

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    /// 25 kbit/s and a 500 ms round trip: one 6 250-byte segment is due 2.5 s after it is asked for.
    func testAShapedTransferArrivesAtTheTracesRateWithinItsTolerance() async throws {
        let rttMs: Int64 = 500
        let bandwidthBps: Int64 = 25_000
        let trace = try ThroughputTrace.Builder()
            .add(durationMs: 1_000, bandwidthBps: bandwidthBps, transport: .wifi, rttMs: rttMs)
            .build()
        let origin = try await harness.serve(.hls(), network: trace)

        let elapsed = try await timedFetch(origin.url(path: "segment0.aac"))

        let expected = Duration.milliseconds(rttMs + Int64(segmentBytes) * 8_000 / bandwidthBps)
        XCTAssertGreaterThanOrEqual(elapsed, expected, "faster than the trace allows")
        XCTAssertLessThanOrEqual(elapsed, expected + slack, "slower than the stated tolerance")
        let measuredBps = Double(segmentBytes * 8) / (elapsed - .milliseconds(rttMs)).seconds
        XCTAssertLessThanOrEqual(measuredBps, Double(bandwidthBps))
        XCTAssertGreaterThanOrEqual(measuredBps, Double(bandwidthBps) * 2 / 3)
    }

    /// The control on the shaping: the same transfer from an origin with no trace is not held to
    /// one, so the bound above is the trace's doing and not the loopback's.
    func testAnUnshapedTransferIsNotHeldToATrace() async throws {
        let origin = try await harness.serve(.hls())

        let elapsed = try await timedFetch(origin.url(path: "segment0.aac"))

        XCTAssertLessThan(elapsed, .milliseconds(2_500))
        XCTAssertNil(origin.responses().first?.transport)
        XCTAssertNil(origin.transport())
    }

    /// A trace and a cap compose and the slower governs — the Kotlin harness's rule, at the socket.
    func testACapUnderATraceGovernsWhenItIsTheSlower() async throws {
        let trace = try ThroughputTrace.Builder()
            .add(durationMs: 1_000, bandwidthBps: 100_000, transport: .wifi).build()
        let origin = try await harness.serve(
            .hls(), faults: FaultScript.Builder().capThroughputBps(20_000).build(), network: trace)

        let elapsed = try await timedFetch(origin.url(path: "segment0.aac"))

        // 6 250 bytes at the cap's 20 kbit/s is 2.5 s; at the trace's 100 it would be half a second.
        let expected = Duration.milliseconds(Int64(segmentBytes) * 8_000 / 20_000)
        XCTAssertGreaterThanOrEqual(elapsed, expected)
        XCTAssertLessThanOrEqual(elapsed, expected + slack)
    }

    /// A profile is a network a stock player plays through: `THREE_G` is forty times the stream's
    /// own rate, so the stream plays, every response paced on the profile's cellular link.
    func testAStockPlayerPlaysThroughAProfile() async throws {
        let origin = try await harness.serve(.hls(), network: NetworkProfile.threeG.trace)
        let item = AVPlayerItem(url: origin.sourceURL)
        let player = AVPlayer(playerItem: item)
        let ended = EndObserver(item)
        player.play()
        try await harness.until("the stream plays to its end", timeout: bound) { ended.hasEnded }
        player.pause()

        let responses = origin.responses()
        XCTAssertTrue(responses.contains { $0.request.kind == .mediaSegment })
        XCTAssertTrue(responses.allSatisfy { $0.transport == .cellular }, "\(responses)")
    }

    /// The transport half of a trace is *reported* on iOS: a handover changes what the origin says
    /// each request arrived on, and nothing about the simulator's own interface.
    func testAHandoverIsReportedOnTheResponsesAndHolds() async throws {
        let trace = try ThroughputTrace.Builder()
            .add(durationMs: 2_000, bandwidthBps: 20_000_000, transport: .wifi)
            .add(durationMs: 1_000, bandwidthBps: 5_000_000, transport: .cellular)
            .holdAtEnd()
            .build()
        let origin = try await harness.serve(.liveHls(), network: trace)
        let player = AVPlayer(url: origin.sourceURL)
        player.play()
        try await harness.until("a request arrives on the cellular link", timeout: bound) {
            origin.responses().contains { $0.transport == .cellular }
        }
        player.pause()

        let transports = origin.responses().compactMap(\.transport)
        XCTAssertEqual(transports.first, .wifi)
        // Held, so once on cellular every later request is too.
        let switched = try XCTUnwrap(transports.firstIndex(of: .cellular))
        XCTAssertTrue(transports[switched...].allSatisfy { $0 == .cellular }, "\(transports)")
        XCTAssertEqual(origin.transport(), .cellular)
    }

    /// Fetches `url` with a session of its own, returning how long the whole body took to arrive.
    private func timedFetch(_ url: URL) async throws -> Duration {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let clock = ContinuousClock()
        let start = clock.now
        let (data, response) = try await session.data(from: url)
        let elapsed = clock.now - start
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(data.count, segmentBytes)
        return elapsed
    }
}

extension Duration {
    fileprivate var seconds: Double {
        let (seconds, attoseconds) = components
        return Double(seconds) + Double(attoseconds) / 1e18
    }
}
