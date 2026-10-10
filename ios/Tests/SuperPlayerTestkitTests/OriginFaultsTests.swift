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

// `@testable`, as `HttpExchangeTests` is and for its reason: the addressing is the part of a fault
// script that is easy to get wrong, and as a function of its input it is testable without a socket.
@testable import SuperPlayerTestkit

/// A ``FaultScript``'s addressing and its stateful faults, as `OriginFaults` applies them to a
/// sequence of requests — the claims the Kotlin `FaultInjectionTest` makes of the injector's
/// addressing, made of the origin's.
final class OriginFaultsTests: XCTestCase {

    func testIndicesCountDistinctResourcesOfOneKindPerHostInTheOrderFirstRequested() {
        var faults = OriginFaults(.none)

        XCTAssertEqual(faults.address(host: 0, path: "/master.m3u8").index, 0)
        XCTAssertEqual(faults.address(host: 0, path: "/segment2.aac").index, 0)
        XCTAssertEqual(faults.address(host: 0, path: "/media.m3u8").index, 1)
        XCTAssertEqual(faults.address(host: 0, path: "/segment0.aac").index, 1)
        // Fetched again, a resource keeps its index and counts an attempt.
        let again = faults.address(host: 0, path: "/segment2.aac")
        XCTAssertEqual(again.index, 0)
        XCTAssertEqual(again.attempt, 2)
        // The same path on another host is another resource, indexed from zero there.
        let elsewhere = faults.address(host: 1, path: "/segment0.aac")
        XCTAssertEqual(elsewhere.index, 0)
        XCTAssertEqual(elsewhere.attempt, 1)
    }

    func testAFaultAddressedAtAnIndexReachesThatResourceAlone() {
        var faults = OriginFaults(
            FaultScript.Builder().failWithHttpStatus(503, kind: .mediaSegment, index: 1).build())

        XCTAssertEqual(
            statuses(&faults, ["/segment0.aac", "/segment1.aac", "/segment2.aac"]),
            [
                200, 503, 200,
            ])
        // The kind is part of the address: the second manifest is not the second segment.
        XCTAssertEqual(statuses(&faults, ["/master.m3u8", "/media.m3u8"]), [200, 200])
    }

    func testFirstAttemptsRelentsAndAfterAttemptsBegins() {
        var relenting = OriginFaults(
            FaultScript.Builder().failWithHttpStatus(500, kind: .mediaSegment, firstAttempts: 2)
                .build())
        XCTAssertEqual(
            statuses(&relenting, ["/segment0.aac", "/segment0.aac", "/segment0.aac"]),
            [500, 500, 200])

        var beginning = OriginFaults(
            FaultScript.Builder().failWithHttpStatus(500, kind: .mediaSegment, afterAttempts: 1)
                .build())
        XCTAssertEqual(
            statuses(&beginning, ["/segment0.aac", "/segment0.aac", "/segment0.aac"]),
            [200, 500, 500])
    }

    func testAHostNarrowsAFaultToTheRequestsThatWentToIt() {
        var faults = OriginFaults(FaultScript.Builder().failWithHttpStatus(503, host: 0).build())

        XCTAssertEqual(statuses(&faults, ["/media.m3u8"], host: 0), [503])
        XCTAssertEqual(statuses(&faults, ["/media.m3u8"], host: 1), [200])
    }

    func testATokenExpiryRefusesItsSegmentAndEveryOneAfterIt() {
        var faults = OriginFaults(FaultScript.Builder().expireTokenAtSegment(1).build())

        XCTAssertEqual(
            statuses(
                &faults, ["/segment0.aac", "/segment1.aac", "/segment2.aac", "/segment1.aac"]),
            [200, 403, 403, 403])
    }

    func testARefreshableTokenIsServedOnceTheCredentialChanges() {
        var faults = OriginFaults(
            FaultScript.Builder().expireTokenAtSegment(0, refreshable: true).build())

        XCTAssertEqual(statuses(&faults, ["/segment0.aac"], query: "token=old"), [403])
        XCTAssertEqual(statuses(&faults, ["/segment0.aac"], query: "token=old"), [403])
        // The control on the credential: the same segment, a new signature.
        XCTAssertEqual(statuses(&faults, ["/segment0.aac"], query: "token=new"), [200])
    }

    func testTheCacheServesItsCopyWhileFreshAndTheOriginOnceStaleOrAskedNotTo() {
        var faults = OriginFaults(
            FaultScript.Builder().serveThroughCache(maxAgeSeconds: 6, kind: .manifest).build())
        let path = "/media.m3u8"
        var version = 1
        func ask(at nowMs: Int64, noCache: Bool = false) -> OriginFaults.Answer {
            let request = Self.request(path, headers: noCache ? ["cache-control": "no-cache"] : [:])
            return faults.answer(
                request, at: faults.address(host: 0, path: path), nowMs: nowMs
            ) { HttpExchange.resolve(request, body: Array("v\(version)".utf8)) }
        }

        XCTAssertFalse(ask(at: 0).servedFromCache)
        version = 2
        let hit = ask(at: 5_999)
        XCTAssertTrue(hit.servedFromCache)
        XCTAssertEqual(hit.response?.body, Array("v1".utf8))
        XCTAssertTrue(hit.response?.headers.contains { $0 == ("Age", "5") } ?? false)
        // Stale at its max-age, so the origin's current version replaces it.
        let refilled = ask(at: 6_000)
        XCTAssertFalse(refilled.servedFromCache)
        XCTAssertEqual(refilled.response?.body, Array("v2".utf8))
        // spec: RFC 9111 §5.2.1.4 — a request's `no-cache` goes to the origin.
        version = 3
        XCTAssertEqual(ask(at: 6_001, noCache: true).response?.body, Array("v3".utf8))
    }

    func testACacheThatDisregardsNoCacheServesItsCopyAnyway() {
        var faults = OriginFaults(
            FaultScript.Builder()
                .serveThroughCache(maxAgeSeconds: 60, honoursNoCache: false, kind: .manifest)
                .build())
        let request = Self.request("/media.m3u8", headers: ["cache-control": "no-cache"])
        var version = 1
        func ask() -> OriginFaults.Answer {
            faults.answer(request, at: faults.address(host: 0, path: "/media.m3u8"), nowMs: 0) {
                HttpExchange.resolve(request, body: Array("v\(version)".utf8))
            }
        }

        _ = ask()
        version = 2
        XCTAssertTrue(ask().servedFromCache)
    }

    func testFaultsThatComposeAreAllReportedInTheScriptsOrder() {
        var faults = OriginFaults(
            FaultScript.Builder()
                .addResponseHeaders(["X-Edge": "a"], kind: .mediaSegment)
                .truncateAfterBytes(3, kind: .mediaSegment)
                .addLatencyMs(250, kind: .mediaSegment)
                .capThroughputBps(8_000)
                .build())
        let request = Self.request("/segment0.aac")
        let answer = faults.answer(
            request, at: faults.address(host: 0, path: request.path), nowMs: 0
        ) { HttpExchange.resolve(request, body: [1, 2, 3, 4, 5]) }

        XCTAssertEqual(
            answer.faults, [.responseHeaders, .truncation, .latency, .throughputCap])
        XCTAssertEqual(answer.truncateAfterBytes, 3)
        XCTAssertEqual(answer.latencyMs, 250)
        XCTAssertEqual(answer.capBps, 8_000)
        // The control: a request the script does not address is answered untouched.
        let manifest = Self.request("/media.m3u8")
        let untouched = faults.answer(
            manifest, at: faults.address(host: 0, path: manifest.path), nowMs: 0
        ) { HttpExchange.resolve(manifest, body: [1]) }
        XCTAssertEqual(untouched.faults, [.throughputCap])
        XCTAssertNil(untouched.truncateAfterBytes)
    }

    func testAResetAnswersNothing() {
        var faults = OriginFaults(FaultScript.Builder().resetConnection(index: 0).build())
        let request = Self.request("/segment0.aac")
        let answer = faults.answer(
            request, at: faults.address(host: 0, path: request.path), nowMs: 0
        ) { HttpExchange.resolve(request, body: [1]) }

        XCTAssertNil(answer.response)
        XCTAssertEqual(answer.faults, [.connectionReset])
    }

    func testAScriptReportsEachKindItCarriesOnce() {
        let script = FaultScript.Builder()
            .failWithHttpStatus(500, index: 0)
            .expireTokenAtSegment(2)
            .failWithHttpStatus(404, index: 1)
            .build()

        XCTAssertEqual(script.kinds, [.httpStatus, .tokenExpiry])
        XCTAssertEqual(FaultScript.none.kinds, [])
    }

    private func statuses(
        _ faults: inout OriginFaults, _ paths: [String], host: Int = 0, query: String? = nil
    ) -> [Int?] {
        paths.map { path in
            let request = Self.request(path, query: query)
            return faults.answer(
                request, at: faults.address(host: host, path: path), nowMs: 0
            ) { HttpExchange.resolve(request, body: [0]) }.response?.status
        }
    }

    private static func request(
        _ path: String, query: String? = nil, headers: [String: String] = [:]
    ) -> HttpExchange.Request {
        HttpExchange.Request(
            method: "GET", path: path, query: query, headers: headers, keepAlive: true)
    }
}
