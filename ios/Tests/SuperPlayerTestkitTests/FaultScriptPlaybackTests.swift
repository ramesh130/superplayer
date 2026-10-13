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

/// Every ``FaultScript/Kind`` forced on a stock `AVPlayer` and read back at the origin — #410's
/// acceptance, and ADR-0021 rule 3's enforcement: a fault is shown to have *happened* by the
/// origin's own record of how it answered (``LoopbackOrigin/responses()``), with what the player
/// did about it alongside, never by a duration.
///
/// What the player does about a fault is recorded here as the evidence that it met it, not asserted
/// as SuperPlayer's behaviour: there is no SuperPlayer yet, and how a stock `AVPlayer` recovers is
/// AVFoundation's. Each test's control is the rest of the stream — the requests the script does not
/// address are answered untouched — so a script that faulted everything, or nothing, fails.
@MainActor
final class FaultScriptPlaybackTests: XCTestCase {

    private let bound = StockPlayback.bound

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    /// The register `FaultSweepTest`'s shape asks for: every kind, and the test here that forces it.
    /// Checked against ``FaultScript/Kind/allCases``, so a kind added without a test fails by name.
    static let forcedBy: [FaultScript.Kind: String] = [
        .latency: "testALatencyHoldsTheAddressedSegmentAndThePlayerWaitsItOut",
        .throughputCap: "testAThroughputCapPacesTheAddressedSegments",
        .httpStatus: "testAStatusThatRelentsIsAnsweredOnceAndTheRetryIsServed",
        .truncation: "testATruncatedSegmentIsCutShortAndAskedForAgain",
        .connectionReset: "testAResetConnectionAnswersNothingAndTheRetryIsServed",
        .tokenExpiry: "testAnExpiredTokenRefusesItsSegmentAndEveryOneAfterIt",
        .intermediaryCache: "testACacheInFrontOfALiveOriginKeepsServingTheFirstPlaylist",
        .responseHeaders: "testAHeaderSetIsSentOnTheAddressedResponsesAlone",
    ]

    func testEveryKindIsForcedByATestHere() {
        XCTAssertEqual(Set(Self.forcedBy.keys), Set(FaultScript.Kind.allCases))
        let methods = Set(
            Self.defaultTestSuite.tests.compactMap { $0.name.split(separator: " ").last }
        )
        .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "]")) }
        for (kind, test) in Self.forcedBy {
            XCTAssertTrue(methods.contains(test), "\(kind): no test named \(test)")
        }
    }

    /// The player waits for the held segment: every request it makes for it comes before its first
    /// request for anything after it. Whether it waits out the first hold or gives up on it is the
    /// host's speed and not this test's subject (#506). AVFoundation abandons a segment with no
    /// first byte within about a target duration and asks again from byte zero (*The fault sweep on
    /// iOS*), and a one-second hold can outlast that two-second wait on a loaded host. So the hold
    /// is on the first attempt alone, and a retry, where there is one, is served untouched rather
    /// than held again for the host to overrun once more.
    func testALatencyHoldsTheAddressedSegmentAndThePlayerWaitsItOut() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .addLatencyMs(1_000, kind: .mediaSegment, index: 1, firstAttempts: 1).build()
        )
        try await StockPlayback.playToEnd(origin, with: harness)

        let held = faults(origin, "/segment1.aac")
        XCTAssertEqual(held.first, [.latency], "\(held)")
        XCTAssertTrue(held.dropFirst().allSatisfy(\.isEmpty), "\(held)")
        assertUntouched(origin, except: ["/segment1.aac"])
        // What every test here addressing `index: 1` rests on: a stock player asks for the segments
        // in order, whole, so the second segment it asks for is `segment1.aac` at offset zero —
        // and, should it give up on that one, asks for it again before anything after it: with
        // repeats run together, no segment appears twice.
        let segments = origin.networkRequests().filter { $0.kind == .mediaSegment }
        let order = segments.map(\.path).reduce(into: [String]()) { order, path in
            if order.last != path { order.append(path) }
        }
        XCTAssertEqual(
            order.prefix(3), ["/segment0.aac", "/segment1.aac", "/segment2.aac"], "\(segments)")
        XCTAssertEqual(Set(order).count, order.count, "\(segments)")
        XCTAssertTrue(
            segments.allSatisfy { ($0.headers["range"] ?? "bytes=0-").hasPrefix("bytes=0-") },
            "\(segments)")
    }

    func testAThroughputCapPacesTheAddressedSegments() async throws {
        // Twice the synthetic stream's own 25 kbit/s, so a capped stream still plays at 1×.
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder().capThroughputBps(50_000, kind: .mediaSegment).build())
        try await StockPlayback.playToEnd(origin, with: harness)

        let segments = origin.responses().filter { $0.request.kind == .mediaSegment }
        XCTAssertFalse(segments.isEmpty)
        XCTAssertTrue(segments.allSatisfy { $0.faults == [.throughputCap] }, "\(segments)")
        assertUntouched(origin, except: Set(segments.map(\.request.path)))
    }

    func testAStatusThatRelentsIsAnsweredOnceAndTheRetryIsServed() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .failWithHttpStatus(
                    FaultScript.httpServerError, kind: .mediaSegment, index: 1, firstAttempts: 1
                )
                .build())
        let player = AVPlayer(url: origin.sourceURL)
        player.play()
        try await harness.until("segment 1 is asked for again", timeout: bound) {
            origin.responses().filter { $0.request.path == "/segment1.aac" }.count >= 2
        }
        player.pause()

        XCTAssertEqual(statuses(origin, "/segment1.aac").prefix(2), [500, 200])
        assertUntouched(origin, except: ["/segment1.aac"])
    }

    func testATruncatedSegmentIsCutShortAndAskedForAgain() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .truncateAfterBytes(100, kind: .mediaSegment, index: 1, firstAttempts: 1).build())
        let player = AVPlayer(url: origin.sourceURL)
        player.play()
        try await harness.until("segment 1 is asked for again", timeout: bound) {
            origin.responses().filter { $0.request.path == "/segment1.aac" }.count >= 2
        }
        player.pause()

        let cut = try XCTUnwrap(origin.responses().first { $0.request.path == "/segment1.aac" })
        XCTAssertEqual(cut.faults, [.truncation])
        XCTAssertEqual(cut.bodyBytesSent, 100)
        // The promise that was broken: the full length, declared.
        XCTAssertEqual(
            cut.headers["content-length"],
            String(SyntheticHlsStream.files(segmentCount: 2)["segment1.aac"]!.count)
        )
        assertUntouched(origin, except: ["/segment1.aac"])
    }

    func testAResetConnectionAnswersNothingAndTheRetryIsServed() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .resetConnection(kind: .mediaSegment, index: 1, firstAttempts: 1).build())
        let player = AVPlayer(url: origin.sourceURL)
        player.play()
        try await harness.until("segment 1 is asked for again", timeout: bound) {
            origin.responses().filter { $0.request.path == "/segment1.aac" }.count >= 2
        }
        player.pause()

        XCTAssertEqual(statuses(origin, "/segment1.aac").prefix(2), [nil, 200])
        XCTAssertEqual(faults(origin, "/segment1.aac").first, [.connectionReset])
        assertUntouched(origin, except: ["/segment1.aac"])
    }

    func testAnExpiredTokenRefusesItsSegmentAndEveryOneAfterIt() async throws {
        let origin = try await harness.serve(
            .hls(), faults: FaultScript.Builder().expireTokenAtSegment(1).build())
        let player = AVPlayer(url: origin.sourceURL)
        player.play()
        try await harness.until("a segment is refused", timeout: bound) {
            origin.responses().contains { $0.status == FaultScript.httpForbidden }
        }
        player.pause()

        let segments = origin.responses().filter { $0.request.kind == .mediaSegment }
        XCTAssertEqual(statuses(origin, "/segment0.aac").first, 200)
        for response in segments where response.request.path != "/segment0.aac" {
            XCTAssertEqual(response.status, 403, "\(response)")
            XCTAssertEqual(response.faults, [.tokenExpiry])
        }
        assertUntouched(
            origin, except: Set(segments.map(\.request.path)).subtracting(["/segment0.aac"]))
    }

    /// The Kotlin `hlsCachedLivePlaylist`'s cause, served for real: the origin keeps publishing and
    /// the player keeps being handed the copy the cache took first.
    func testACacheInFrontOfALiveOriginKeepsServingTheFirstPlaylist() async throws {
        let origin = try await harness.serve(
            .liveHls(),
            faults: FaultScript.Builder()
                .serveThroughCache(maxAgeSeconds: 600, kind: .manifest, index: 1).build())
        let player = AVPlayer(url: origin.sourceURL)
        player.play()
        let mediaPlaylist = "/live/" + SyntheticHlsStream.mediaPlaylistName
        try await harness.until("the live playlist is reloaded twice", timeout: bound) {
            origin.responses().filter { $0.request.path == mediaPlaylist }.count >= 3
        }
        player.pause()

        let loads = origin.responses().filter { $0.request.path == mediaPlaylist }
        XCTAssertEqual(loads.map(\.servedFromCache).prefix(3), [false, true, true])
        XCTAssertTrue(loads.allSatisfy { $0.faults == [.intermediaryCache] })
        XCTAssertEqual(loads.first?.headers["cache-control"], "public, max-age=600")
        // `Age` on the copies the cache served, and not on the response that filled it.
        XCTAssertEqual(loads.map { $0.headers["age"] != nil }.prefix(3), [false, true, true])
        // The control: the multivariant playlist, index 0, passes through the origin.
        XCTAssertTrue(
            origin.responses().filter { $0.request.path == "/live/master.m3u8" }
                .allSatisfy { $0.faults.isEmpty && !$0.servedFromCache })
    }

    func testAHeaderSetIsSentOnTheAddressedResponsesAlone() async throws {
        let origin = try await harness.serve(
            .hls(),
            faults: FaultScript.Builder()
                .addResponseHeaders(
                    ["Cache-Control": "public, max-age=31536000, immutable"], kind: .mediaSegment
                )
                .build())
        try await StockPlayback.playToEnd(origin, with: harness)

        let responses = origin.responses()
        let segments = responses.filter { $0.request.kind == .mediaSegment }
        XCTAssertFalse(segments.isEmpty)
        XCTAssertTrue(
            segments.allSatisfy {
                $0.headers["cache-control"] == "public, max-age=31536000, immutable"
                    && $0.faults == [.responseHeaders]
            }, "\(segments)")
        XCTAssertTrue(
            responses.filter { $0.request.kind == .manifest }
                .allSatisfy { $0.headers["cache-control"] == nil && $0.faults.isEmpty })
    }

    // MARK: -

    private func statuses(_ origin: LoopbackOrigin, _ path: String) -> [Int?] {
        origin.responses().filter { $0.request.path == path }.map(\.status)
    }

    private func faults(_ origin: LoopbackOrigin, _ path: String) -> [[FaultScript.Kind]] {
        origin.responses().filter { $0.request.path == path }.map(\.faults)
    }

    /// Every response but those to `paths` was the origin's own: no fault, no copy from a cache.
    private func assertUntouched(
        _ origin: LoopbackOrigin, except paths: Set<String>, line: UInt = #line
    ) {
        let touched = origin.responses().filter {
            !paths.contains($0.request.path) && (!$0.faults.isEmpty || $0.servedFromCache)
        }
        XCTAssertEqual(touched, [], "faulted beyond what the script addresses", line: line)
        XCTAssertFalse(origin.responses().isEmpty, line: line)
    }
}
