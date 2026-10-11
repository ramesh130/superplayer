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

import OSLog
import SuperPlayerCore
import XCTest

// `@testable` for `OSLogSink.line(for:)`, the one place the format lives: what the unified log
// stores of a private value depends on whether a debugger is attached, so the format is asserted
// where it is made, and one line is read back from the store to show it is written at all.
@testable import SuperPlayerTelemetry

/// `OSLogSink`'s one-line grammar — the Kotlin `LogcatSinkTest`'s claims: one line per event, the
/// discriminator first, the Kotlin names, quoting that keeps a line one line, and the failures at a
/// level of their own with the engine's message private.
final class OSLogSinkTests: XCTestCase {

    private let common = "v=2 sid=S cid=\"urn:content:1\" t=1 mono=2"

    func testAnEventIsOneLineWithTheDiscriminatorFirstAndTheKotlinNames() {
        let line = OSLogSink.line(
            for: .firstFrameRendered(
                .init(
                    sessionId: "S", contentId: "urn:content:1", timestampMs: 1, monotonicTimeMs: 2,
                    timeToFirstFrameMs: 840, startBoundary: .userIntent)))

        XCTAssertEqual(line.text, "evt=first_frame \(common) ttffMs=840 from=USER_INTENT")
        XCTAssertEqual(line.level, .default)
        XCTAssertNil(line.privateMessage)
    }

    func testASessionStartNamesTheProfileAsKotlinDoes() {
        let line = OSLogSink.line(
            for: .sessionStarted(
                .init(
                    sessionId: "S", contentId: "urn:content:1", timestampMs: 1, monotonicTimeMs: 2,
                    profile: .liveLinear, decision: PlaybackDecision())))

        XCTAssertEqual(line.text, "evt=session_started \(common) profile=LIVE_LINEAR")
    }

    func testAnAbsentValuePrintsAsNull() {
        let line = OSLogSink.line(
            for: .trackSwitched(
                .init(
                    sessionId: "S", contentId: "urn:content:1", timestampMs: 1, monotonicTimeMs: 2,
                    fromBitrateBps: nil, toBitrateBps: 128_000, direction: .initial)))

        XCTAssertEqual(
            line.text,
            "evt=track_switched \(common) direction=INITIAL fromBps=null toBps=128000")
    }

    /// A failure is an error-level line whose message — the one field that can carry a URL and a
    /// newline — is private, last, and escaped so the line stays one line.
    func testAFailureIsAnErrorWithItsMessagePrivateAndEscaped() {
        let line = OSLogSink.line(
            for: .midStreamFailed(
                .init(
                    sessionId: "S", contentId: "urn:content:1", timestampMs: 1, monotonicTimeMs: 2,
                    failure: PlaybackFailure(
                        category: .network, code: "NSURLErrorDomain:-1001",
                        message: "timed out\nat \"edge\""),
                    positionMs: 4_000)))

        XCTAssertEqual(line.level, .error)
        XCTAssertEqual(
            line.text,
            "evt=midstream_failed \(common) category=NETWORK code=\"NSURLErrorDomain:-1001\" "
                + "positionMs=4000")
        XCTAssertEqual(line.privateMessage, "\"timed out\\nat \\\"edge\\\"\"")
        XCTAssertFalse(line.whole.contains("\n"))
    }

    /// The control on the format test: the line really reaches the unified log, under the
    /// subsystem and category a person filters on.
    func testTheLineIsWrittenUnderTheQoECategory() throws {
        let marker = UUID().uuidString
        let start = Date()
        OSLogSink().onEvent(
            .rebufferStarted(
                .init(
                    sessionId: marker, contentId: "c", timestampMs: 1, monotonicTimeMs: 2,
                    seekInduced: false)))

        let store = try OSLogStore(scope: .currentProcessIdentifier)
        let entries = try store.getEntries(at: store.position(date: start.addingTimeInterval(-1)))
            .compactMap { $0 as? OSLogEntryLog }
            .filter { $0.composedMessage.contains(marker) }
        let entry = try XCTUnwrap(entries.first, "the line was not found in the log")
        XCTAssertEqual(entry.subsystem, "com.superplayer")
        XCTAssertEqual(entry.category, "SuperPlayerQoE")
        XCTAssertTrue(entry.composedMessage.hasPrefix("evt=rebuffer_started v=2 sid=\(marker)"))
    }
}
