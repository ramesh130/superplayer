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
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// The origin as an HTTP server, over `URLSession` rather than a player: what it answers, how it
/// counts, that each test's origin is its own, and that the live content advances on real time.
///
/// Plain `http://127.0.0.1` is allowed by the process the bundle runs in rather than by anything
/// this repository sets: ``testTheTestHostAllowsPlainHttpToTheOrigin()`` pins where.
final class LoopbackOriginTests: XCTestCase {

    private let harness = PlaybackHarness()

    /// A fresh session per test, so a connection one test's session kept alive is never reused by
    /// another's — the keep-alive assertions read connection ordinals.
    private let session = URLSession(configuration: .ephemeral)

    override func tearDown() async throws {
        session.invalidateAndCancel()
        harness.stop()
        try await super.tearDown()
    }

    /// App Transport Security is the host process's, and a SwiftPM test bundle on the simulator runs
    /// in Xcode's `xctest`, whose embedded `Info.plist` sets `NSAllowsArbitraryLoads`. That — not an
    /// exemption for an IP literal, and not a key of this repository's — is why the origin's plain
    /// HTTP loads. Asserted so that a host that stops allowing it fails here, naming the cause,
    /// rather than as every playback test timing out; a test hosted in an app instead needs
    /// `NSAllowsLocalNetworking` in that app's `Info.plist`.
    func testTheTestHostAllowsPlainHttpToTheOrigin() {
        let ats =
            Bundle.main.object(forInfoDictionaryKey: "NSAppTransportSecurity") as? [String: Any]

        XCTAssertTrue(
            ats?["NSAllowsArbitraryLoads"] as? Bool == true
                || ats?["NSAllowsLocalNetworking"] as? Bool == true,
            "the test host \(Bundle.main.bundlePath) allows no plain HTTP: \(String(describing: ats))"
        )
    }

    func testItServesEachResourceWithItsTypeAndCountsTheRequestByKind() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 2))
        let files = SyntheticHlsStream.files(segmentCount: 2)

        let (playlist, playlistResponse) = try await get(origin.sourceURL)
        let (segment, segmentResponse) = try await get(origin.url(path: "segment1.aac"))

        XCTAssertEqual(playlistResponse.statusCode, 200)
        XCTAssertEqual(
            playlistResponse.value(forHTTPHeaderField: "Content-Type"),
            "application/vnd.apple.mpegurl")
        XCTAssertEqual([UInt8](playlist), files["master.m3u8"])
        XCTAssertEqual(segmentResponse.value(forHTTPHeaderField: "Content-Type"), "audio/aac")
        XCTAssertEqual([UInt8](segment), files["segment1.aac"])
        XCTAssertEqual(
            origin.networkRequests().map { "\($0.kind) \($0.path)" },
            ["manifest /master.m3u8", "mediaSegment /segment1.aac"])
    }

    func testAPathNothingPublishedIsA404AndStillCounted() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 1))

        let (_, response) = try await get(origin.url(path: "segment9.aac"))

        XCTAssertEqual(response.statusCode, 404)
        XCTAssertEqual(origin.networkRequests().map(\.path), ["/segment9.aac"])
    }

    func testAByteRangeIsAnsweredPartiallyAndARangePastTheEndIsRefused() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 1))
        let segment = SyntheticHlsStream.files(segmentCount: 1)["segment0.aac"] ?? []

        let (part, partResponse) = try await get(
            origin.url(path: "segment0.aac"), headers: ["Range": "bytes=10-19"])
        let (_, pastResponse) = try await get(
            origin.url(path: "segment0.aac"), headers: ["Range": "bytes=\(segment.count)-"])

        XCTAssertEqual(partResponse.statusCode, 206)
        XCTAssertEqual([UInt8](part), Array(segment[10...19]))
        XCTAssertEqual(
            partResponse.value(forHTTPHeaderField: "Content-Range"),
            "bytes 10-19/\(segment.count)")
        XCTAssertEqual(pastResponse.statusCode, 416)
        XCTAssertEqual(
            pastResponse.value(forHTTPHeaderField: "Content-Range"), "bytes */\(segment.count)")
        XCTAssertEqual(
            origin.networkRequests().map { $0.headers["range"] },
            [
                "bytes=10-19", "bytes=\(segment.count)-",
            ])
    }

    func testAHeadRequestIsAnsweredWithTheLengthAndNoBody() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 1))
        let segment = SyntheticHlsStream.files(segmentCount: 1)["segment0.aac"] ?? []

        let (body, response) = try await get(origin.url(path: "segment0.aac"), method: "HEAD")

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(response.expectedContentLength, Int64(segment.count))
        XCTAssertTrue(body.isEmpty)
        XCTAssertEqual(origin.networkRequests().map(\.method), ["HEAD"])
    }

    /// Two requests on one session share a connection; the control is the same two requests with
    /// the first asking to close, which the origin honours — so one ordinal is the origin keeping
    /// the connection alive and not a counter that never moves.
    func testAConnectionIsKeptAliveUnlessTheClientAsksToCloseIt() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 1))

        _ = try await get(origin.url(path: "master.m3u8"))
        _ = try await get(origin.url(path: "media.m3u8"))
        _ = try await get(origin.url(path: "master.m3u8"), headers: ["Connection": "close"])
        _ = try await get(origin.url(path: "media.m3u8"))

        let connections = origin.networkRequests().map(\.connection)
        XCTAssertEqual(connections[0], connections[1], "\(connections)")
        XCTAssertEqual(connections[1], connections[2], "\(connections)")
        XCTAssertNotEqual(connections[2], connections[3], "\(connections)")
    }

    /// Two harnesses running at once — what two tests in one process are — get two origins on two
    /// ports, and each counts only what was sent to it.
    func testTwoHarnessesGetTwoOriginsOnTwoPortsWithCountsOfTheirOwn() async throws {
        let other = PlaybackHarness()
        defer { other.stop() }
        let first = try await harness.serve(.hls(segmentCount: 1))
        let second = try await other.serve(.hls(segmentCount: 1))

        _ = try await get(first.url(path: "master.m3u8"))
        _ = try await get(second.url(path: "media.m3u8"))
        _ = try await get(second.url(path: "segment0.aac"))

        XCTAssertNotEqual(first.port, second.port)
        XCTAssertEqual(first.networkRequests().map(\.path), ["/master.m3u8"])
        XCTAssertEqual(second.networkRequests().map(\.path), ["/media.m3u8", "/segment0.aac"])
    }

    /// The live origin publishes on real time: a segment past the first window is a 404 at first
    /// and served once a segment duration has passed, and the playlist then lists it.
    func testTheLiveOriginPublishesTheNextSegmentAsRealTimePasses() async throws {
        let origin = try await harness.serve(.liveHls())
        let next = SyntheticHlsStream.segmentName(SyntheticHlsStream.liveWindowSegmentCount)

        let (_, before) = try await get(origin.url(path: "live/" + next))
        XCTAssertEqual(before.statusCode, 404)

        let session = self.session
        let nextURL = origin.url(path: "live/" + next)
        try await harness.until("the next segment is published", timeout: .seconds(30)) {
            let (_, response) = try await session.data(from: nextURL)
            return (response as? HTTPURLResponse)?.statusCode == 200
        }
        let (playlist, _) = try await get(origin.url(path: "live/media.m3u8"))
        XCTAssertTrue(
            String(decoding: playlist, as: UTF8.self).contains(next), "the playlist lists it")
    }

    private func get(_ url: URL, method: String = "GET", headers: [String: String] = [:])
        async throws -> (Data, HTTPURLResponse)
    {
        var request = URLRequest(url: url)
        request.httpMethod = method
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let (data, response) = try await session.data(for: request)
        return (data, try XCTUnwrap(response as? HTTPURLResponse))
    }
}
