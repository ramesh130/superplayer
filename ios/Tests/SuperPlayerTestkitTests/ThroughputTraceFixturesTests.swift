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

// `@testable` for the replay's `arrivalMs`, which the readings record.
@testable import SuperPlayerTestkit

/// The Swift half of ADR-0021 rule 6 for the trace grammar: every `.trace` under
/// `ios/Tests/Fixtures/throughput-traces/` reads here exactly as `superplayer-testkit`'s
/// `ThroughputTraceFixturesTest` says the Kotlin reader reads it, and every ``NetworkProfile`` is the
/// trace the Kotlin profile wrote there.
///
/// "Reads exactly as" is an equality of text: the Kotlin test writes a `.readings` file beside each
/// trace — the trace written back, its conditions at fixed times, and when fixed transfers arrive —
/// and this test computes the same lines with the Swift reader. So a difference in the grammar, the
/// profiles or the replay's integer arithmetic fails here by name, and neither side needs the
/// other's toolchain.
final class ThroughputTraceFixturesTests: XCTestCase {

    /// `ios/Tests/Fixtures/throughput-traces/`, found from this file's place in the source tree, as
    /// the synthetic streams' fixtures are and for their reason.
    static let directory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "Fixtures/throughput-traces")

    func testEveryTraceReadsAsTheKotlinReaderReadsIt() throws {
        let traces = try Self.files(withSuffix: ".trace")
        XCTAssertTrue(traces.contains("sample.trace"), "the hand-written sample is missing")
        for name in traces {
            let text = try Self.read(name)
            let readingsName = String(name.dropLast(".trace".count)) + ".readings"
            let committed = try Self.read(readingsName)
            let computed = try Self.readings(of: text)
            XCTAssertEqual(
                Self.difference(computed, committed), nil,
                "\(name): the Swift reader does not read it as \(readingsName) records")
        }
    }

    func testEveryProfileIsTheTraceTheKotlinProfileWrote() throws {
        for profile in NetworkProfile.allCases {
            XCTAssertEqual(
                profile.trace.format(), try Self.read(Self.fileName(profile)),
                "\(profile): the Swift profile is not the Kotlin one")
        }
        // And nothing there names a profile this side lacks.
        let profileFiles = Set(NetworkProfile.allCases.map(Self.fileName))
        let readings = Set(try Self.files(withSuffix: ".readings"))
        let traces = Set(try Self.files(withSuffix: ".trace"))
        XCTAssertEqual(
            readings, Set(traces.map { String($0.dropLast(".trace".count)) + ".readings" }))
        XCTAssertTrue(profileFiles.isSubset(of: traces))
    }

    /// The control: a reading the reader does not produce is named, so the equality above is one.
    func testAReadingThatDiffersIsNamedByItsLine() throws {
        let committed = try Self.read("three-g.readings")
        let altered = committed.replacingOccurrences(
            of: "transport CELLULAR", with: "transport WIFI")

        XCTAssertNotEqual(altered, committed)
        XCTAssertEqual(
            Self.difference(try Self.readings(of: Self.read("three-g.trace")), altered)?.prefix(5),
            "line ")
    }

    /// A profile's file name: its constant, lowercased and hyphenated — the Kotlin test's rule.
    static func fileName(_ profile: NetworkProfile) -> String {
        profile.rawValue.lowercased().replacingOccurrences(of: "_", with: "-") + ".trace"
    }

    /// The Kotlin `readings`, line for line.
    static func readings(of text: String) throws -> String {
        let trace = try ThroughputTrace.parse(text)
        let d = trace.durationMs
        var out =
            "# What ThroughputTrace.parse reads. Written by ThroughputTraceFixturesTest; "
            + "./gradlew updateIosFixtures.\n"
        for line in trace.format().split(separator: "\n") where !line.isEmpty {
            out += "format \(line)\n"
        }
        out += "loops \(trace.loops)\n"
        out += "durationMs \(d)\n"
        for t in [0, 1, d / 2, d - 1, d, d + 1, 2 * d + d / 3, 10 * d] {
            out +=
                "at \(t) bandwidth \(trace.bandwidthBps(at: t)) rtt \(trace.rttMs(at: t)) "
                + "transport \(trace.transport(at: t).rawValue)\n"
        }
        for start in [0, d / 2, d + 1] {
            for bytes: Int64 in [0, 1, 4_096, 250_000, 2_500_000] {
                out +=
                    "arrival \(start) \(bytes) \(trace.arrivalMs(startMs: start, bytes: bytes))\n"
            }
        }
        return out
    }

    /// The first line two readings differ on, or nil when they are the same text.
    static func difference(_ computed: String, _ committed: String) -> String? {
        let mine = computed.split(separator: "\n", omittingEmptySubsequences: false)
        let theirs = committed.split(separator: "\n", omittingEmptySubsequences: false)
        for index in 0..<max(mine.count, theirs.count) {
            let a = index < mine.count ? String(mine[index]) : "<none>"
            let b = index < theirs.count ? String(theirs[index]) : "<none>"
            if a != b { return "line \(index + 1): Swift reads \"\(a)\", Kotlin \"\(b)\"" }
        }
        return nil
    }

    private static func files(withSuffix suffix: String) throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0.hasSuffix(suffix) }.sorted()
    }

    private static func read(_ name: String) throws -> String {
        try String(contentsOf: directory.appending(path: name), encoding: .utf8)
    }
}
