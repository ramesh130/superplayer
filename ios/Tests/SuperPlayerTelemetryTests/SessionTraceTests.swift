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

// `@testable` for `TraceKind`, the grammar's kinds, and the recorder's `itemName` rule.
@testable import SuperPlayerTelemetry

/// **One grammar on both platforms** (ADR-0020 rule 4, ADR-0021 rule 6): the Swift reader parses
/// every golden the Kotlin recorder committed, writes each back byte for byte, and finds every line
/// in the `+<ms> <kind> <fields>` shape with a kind of the grammar — so a trace from either
/// platform is read by the other. The Kotlin goldens are read from the source tree by `#filePath`,
/// as `SyntheticHlsFixturesTests` reads the fixtures, so this test needs no copy of them.
///
/// Then the reading a golden compares here, ``SessionTrace/structural()``: what it masks, what it
/// keeps, and that it moves no line across a kind boundary.
final class SessionTraceTests: XCTestCase {

    /// The Kotlin goldens, `superplayer-telemetry/src/test/golden/`.
    static let androidGoldens = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("superplayer-telemetry/src/test/golden")

    private func androidGoldenFiles() throws -> [URL] {
        let files = try FileManager.default.contentsOfDirectory(
            at: Self.androidGoldens, includingPropertiesForKeys: nil
        ).filter { $0.pathExtension == "trace" }.sorted { $0.path < $1.path }
        XCTAssertTrue(
            files.contains { $0.lastPathComponent == "hls-vod.trace" },
            "no Kotlin golden found at \(Self.androidGoldens.path)")
        return files
    }

    func testEveryAndroidGoldenParsesAndWritesBackByteForByte() throws {
        for file in try androidGoldenFiles() {
            let text = try String(contentsOf: file, encoding: .utf8)
            let trace = try SessionTrace.parse(text)

            XCTAssertTrue(trace.timed, file.lastPathComponent)
            XCTAssertFalse(trace.lines.isEmpty, file.lastPathComponent)
            XCTAssertEqual(trace.format(), text, file.lastPathComponent)
        }
    }

    /// Every line of every Kotlin golden is a fact this grammar names: a time, then one of the
    /// kinds both recorders rank in the same order.
    func testEveryAndroidLineIsInTheGrammar() throws {
        let kinds = Set(TraceKind.allCases.map(\.token))
        for file in try androidGoldenFiles() {
            let trace = try SessionTrace.parse(String(contentsOf: file, encoding: .utf8))
            for line in trace.lines {
                let columns = line.split(separator: " ", maxSplits: 2)
                XCTAssertEqual(columns.count, 3, "\(file.lastPathComponent): \(line)")
                XCTAssertTrue(
                    columns[0].hasPrefix("+") && Int(columns[0].dropFirst()) != nil,
                    "\(file.lastPathComponent): \(line)")
                XCTAssertTrue(
                    kinds.contains(String(columns[1])), "\(file.lastPathComponent): \(line)")
            }
        }
    }

    /// The Kotlin VOD golden read structurally: its timings and durations masked, its kinds in
    /// rank order — the reading an iOS golden is compared in, applied to Android's.
    func testTheAndroidVodGoldenReadsStructurally() throws {
        let trace = try SessionTrace.parse(
            String(
                contentsOf: Self.androidGoldens.appendingPathComponent("hls-vod.trace"),
                encoding: .utf8))
        let structural = trace.structural()

        XCTAssertFalse(structural.timed)
        XCTAssertEqual(structural.lines.count, trace.lines.count)
        XCTAssertEqual(
            structural.lines.filter { $0.hasPrefix("state ") },
            ["state BUFFERING", "state READY", "state ENDED"])
        XCTAssertTrue(
            structural.lines.contains(
                "load completed media:audio 0-2000 bitrate=128000 durationMs=*"),
            structural.format())
        XCTAssertEqual(structural.lines.last, "telemetry SessionEnded droppedEventCount=0")
        let kindOrder = structural.lines.map { String($0.prefix { $0 != " " }) }
        XCTAssertEqual(
            kindOrder, kindOrder.sorted { kindRank($0) < kindRank($1) },
            "structural lines are grouped by kind in rank order")
    }

    /// What a structural reading masks and drops, with its control: a field not named for a time,
    /// and not a measured rate — a declared bitrate, a direction, a profile — is kept, and an event
    /// the engine caused is kept while one the sampling timer caused is dropped, so a structural
    /// reading is not "mask everything".
    func testStructuralMasksReadingsOfTimeAndKeepsEverythingElse() throws {
        let trace = try SessionTrace.parse(
            """
            superplayer-session-trace v1
            timings relative-ms
            +0 telemetry SessionStarted profile=VIDEO_ON_DEMAND
            +5 load started manifest
            +9 load completed manifest durationMs=4
            +9 bandwidth bitrateBps=81920
            +12 state READY
            +40 telemetry FirstFrameRendered timeToFirstFrameMs=40 startBoundary=CONTENT_ADOPTED
            +41 telemetry TrackSwitched fromBitrateBps=null toBitrateBps=128000 direction=INITIAL
            +42 telemetry PlaybackStateSampled samplingIntervalMs=10000 videoBitrateBps=128000 bufferedDurationMs=3 playing=true
            +43 telemetry SeekCompleted toPositionMs=2000 seekLatencyMs=7

            """)

        XCTAssertEqual(
            trace.structural().format(),
            """
            superplayer-session-trace v1
            timings omitted
            state READY
            load started manifest
            load completed manifest durationMs=*
            bandwidth bitrateBps=*
            telemetry SessionStarted profile=VIDEO_ON_DEMAND
            telemetry FirstFrameRendered timeToFirstFrameMs=* startBoundary=CONTENT_ADOPTED
            telemetry TrackSwitched fromBitrateBps=null toBitrateBps=128000 direction=INITIAL
            telemetry SeekCompleted toPositionMs=* seekLatencyMs=*

            """)
    }

    func testWithoutTimingsKeepsTheOrderAndDropsTheColumn() throws {
        let trace = try SessionTrace.parse(
            "superplayer-session-trace v1\ntimings relative-ms\n+0 state BUFFERING\n+3 item id=a\n")

        XCTAssertEqual(trace.withoutTimings().lines, ["state BUFFERING", "item id=a"])
        XCTAssertEqual(trace.withoutTimings().withoutTimings(), trace.withoutTimings())
    }

    /// The control on the reader: text that is not a trace this version reads is refused, typed.
    func testTextThatIsNotATraceIsRefused() {
        XCTAssertThrowsError(
            try SessionTrace.parse("superplayer-session-trace v2\ntimings omitted\n"))
        XCTAssertThrowsError(
            try SessionTrace.parse("superplayer-session-trace v1\ntimings later\n"))
    }

    /// Rule 1 for the content id, as the Kotlin `itemName`: a URI is withheld, an ordinary id —
    /// slashes and all — is printed, and none is `none`.
    func testAUriShapedContentIdIsWithheld() {
        XCTAssertEqual(
            SessionTraceRecorder.itemName("series/expanse/s01e01"), "series/expanse/s01e01")
        XCTAssertEqual(SessionTraceRecorder.itemName("urn-content-12345"), "urn-content-12345")
        XCTAssertEqual(
            SessionTraceRecorder.itemName("https://cdn.example/a.m3u8"), "uri-withheld")
        XCTAssertEqual(SessionTraceRecorder.itemName("urn:content:12345"), "uri-withheld")
        XCTAssertEqual(SessionTraceRecorder.itemName("episode?token=abc"), "uri-withheld")
        XCTAssertEqual(SessionTraceRecorder.itemName(nil), "none")
        XCTAssertEqual(SessionTraceRecorder.itemName(""), "none")
    }

    private func kindRank(_ token: String) -> Int {
        TraceKind.allCases.first { $0.token == token }?.rawValue ?? Int.max
    }
}
