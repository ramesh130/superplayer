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
import XCTest

// `@testable` for the two tables compared: each is internal to its own module, and this is the one
// place they meet.
@testable import SuperPlayerBenchmark
@testable import SuperPlayerTelemetry

/// `StockTelemetry`'s failure bucket against `QoeCollector`'s, code by code: the one derivation the
/// agreement test's session cannot reach, since nothing in it fails, so a copy that drifted from
/// the collector would otherwise drift unseen.
///
/// The control is that the table is not one answer: the codes below land in five different
/// buckets, so two tables that agreed by both answering `unknown` would fail.
@MainActor
final class StockTelemetryFailureTableTests: XCTestCase {

    func testEveryCodeAndStatusLandsInTheSameBucketFromEitherCollector() {
        var cases: [(NSError?, Int?)] = [
            (nil, nil), (nil, 404), (nil, 503), (nil, 302),
            (NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut), nil),
            (NSError(domain: "elsewhere", code: 1), nil),
        ]
        // Every AVError code from well below to well above the ones either table names.
        for code in -11_900...(-11_800) {
            cases.append((NSError(domain: AVFoundationErrorDomain, code: code), nil))
        }

        var buckets = Set<FailureCategory>()
        for (error, status) in cases {
            let fromQoe = QoeCollector.category(error, loggedStatus: status)
            XCTAssertEqual(
                StockTelemetry.category(error, loggedStatus: status), fromQoe,
                "\(String(describing: error)), status \(String(describing: status))")
            buckets.insert(fromQoe)
        }
        XCTAssertEqual(buckets, [.unknown, .network, .source, .decoder, .renderer])
    }
}
