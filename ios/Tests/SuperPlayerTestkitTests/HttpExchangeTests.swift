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
import XCTest

@testable import SuperPlayerTestkit

/// The origin's HTTP as pure functions: the framing and range cases a `URLSession` will not send on
/// demand — a pipelined pair, a head split across reads, a suffix range, a set of ranges.
///
/// `@testable`, as the Android harness's own tests read its internal `requestedResources`: these
/// are the harness's tests of itself, and ADR-0021 rule 1's facade is SuperPlayer's.
final class HttpExchangeTests: XCTestCase {

    func testTwoPipelinedRequestsAreReadOneAtATimeInOrder() throws {
        let buffer = Data(
            "GET /a.m3u8?x=1 HTTP/1.1\r\nHost: h\r\n\r\nGET /b.aac HTTP/1.1\r\nRange: bytes=0-1\r\n\r\n"
                .utf8)

        guard case .request(let first, let consumed) = HttpExchange.parse(buffer) else {
            return XCTFail("the first request was not read")
        }
        guard case .request(let second, _) = HttpExchange.parse(buffer.dropFirst(consumed)) else {
            return XCTFail("the second request was not read")
        }

        XCTAssertEqual(first.path, "/a.m3u8")
        XCTAssertEqual(first.query, "x=1")
        XCTAssertEqual(first.headers["host"], "h")
        XCTAssertEqual(second.path, "/b.aac")
        XCTAssertNil(second.query)
        XCTAssertEqual(second.headers["range"], "bytes=0-1")
    }

    func testAHeadThatHasNotEndedIsIncompleteAndBytesThatAreNotHttpAreMalformed() {
        XCTAssertEqual(HttpExchange.parse(Data("GET /a HTTP/1.1\r\nHost:".utf8)), .incomplete)
        XCTAssertEqual(HttpExchange.parse(Data("hello\r\n\r\n".utf8)), .malformed)
        XCTAssertEqual(
            HttpExchange.parse(Data(repeating: 0x41, count: HttpExchange.maximumHeadBytes + 1)),
            .malformed)
    }

    func testABodyIsWaitedForAndConsumedWithItsRequest() {
        let head = "POST /a HTTP/1.1\r\nContent-Length: 3\r\n\r\n"
        XCTAssertEqual(HttpExchange.parse(Data((head + "ab").utf8)), .incomplete)
        guard case .request(_, let consumed) = HttpExchange.parse(Data((head + "abc").utf8)) else {
            return XCTFail("the request was not read")
        }
        XCTAssertEqual(consumed, head.utf8.count + 3)
    }

    func testHttp11IsPersistentUnlessClosedAndHttp10OnlyWhenAskedToBe() {
        func keepAlive(_ head: String) -> Bool? {
            guard case .request(let request, _) = HttpExchange.parse(Data(head.utf8)) else {
                return nil
            }
            return request.keepAlive
        }
        XCTAssertEqual(keepAlive("GET / HTTP/1.1\r\n\r\n"), true)
        XCTAssertEqual(keepAlive("GET / HTTP/1.1\r\nConnection: close\r\n\r\n"), false)
        XCTAssertEqual(keepAlive("GET / HTTP/1.0\r\n\r\n"), false)
        XCTAssertEqual(keepAlive("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n"), true)
    }

    func testEachRangeFormIsAnsweredAndARangeNotServedIsTheWholeBody() {
        let body: [UInt8] = Array(0..<10)

        XCTAssertEqual(HttpExchange.ranged(body, "bytes=2-4").body, [2, 3, 4])
        XCTAssertEqual(HttpExchange.ranged(body, "bytes=7-").body, [7, 8, 9])
        XCTAssertEqual(HttpExchange.ranged(body, "bytes=-3").body, [7, 8, 9])
        XCTAssertEqual(HttpExchange.ranged(body, "bytes=8-99").body, [8, 9])
        XCTAssertEqual(
            HttpExchange.ranged(body, "bytes=8-99").headers.first?.1, "bytes 8-9/10")
        XCTAssertEqual(HttpExchange.ranged(body, "bytes=10-").status, 416)
        XCTAssertEqual(HttpExchange.ranged(body, "bytes=-0").status, 416)
        // Ignored, as RFC 9110 §14.2 allows: several ranges, another unit, and nonsense.
        XCTAssertEqual(HttpExchange.ranged(body, "bytes=0-1,4-5").status, 200)
        XCTAssertEqual(HttpExchange.ranged(body, "items=0-1").status, 200)
        XCTAssertEqual(HttpExchange.ranged(body, "bytes=4-2").status, 200)
    }

    func testAnHttp10ConnectionKeptAliveIsSaidToBeInTheResponse() {
        let request = HttpExchange.Request(
            method: "GET", path: "/a.m3u8", query: nil, headers: [:], keepAlive: true,
            isHttp10: true)
        let headers = HttpExchange.respond(to: request, body: [1]).headers

        XCTAssertTrue(headers.contains { $0 == ("Connection", "keep-alive") }, "\(headers)")
    }

    func testAMethodOtherThanGetOrHeadIsA405() {
        let request = HttpExchange.Request(
            method: "POST", path: "/a.m3u8", query: nil, headers: [:], keepAlive: true)

        XCTAssertEqual(HttpExchange.respond(to: request, body: [1]).status, 405)
    }

    func testTheKindIsReadOffTheLastPathComponent() {
        XCTAssertEqual(ResourceKind.of(path: "/live/media.m3u8"), .manifest)
        XCTAssertEqual(ResourceKind.of(path: "/MASTER.M3U8"), .manifest)
        XCTAssertEqual(ResourceKind.of(path: "/init.mp4"), .initialization)
        XCTAssertEqual(ResourceKind.of(path: "/live/segment7.aac"), .mediaSegment)
        // A directory named like a manifest does not make its segments manifests.
        XCTAssertEqual(ResourceKind.of(path: "/a.m3u8/segment0.aac"), .mediaSegment)
    }
}
