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

import SuperPlayerCore
import XCTest
import os

/// `TelemetrySink.composite(...)`: every child hears every event, in the order given, and a child
/// that throws costs nobody else theirs — the Kotlin `TelemetrySinkCompositeTest`'s claims.
final class CompositeTelemetrySinkTests: XCTestCase {

    private let event = TelemetryEvent.sessionEnded(
        .init(sessionId: "s", contentId: "c", timestampMs: 1, monotonicTimeMs: 2))

    /// The control: with nothing throwing, every child hears the event once, in order.
    func testEveryChildHearsTheEventInTheOrderGiven() throws {
        let log = CallLog()
        let sink: any TelemetrySink = .composite(
            NamedSink(name: "a", log: log), NamedSink(name: "b", log: log))

        try sink.onEvent(event)

        XCTAssertEqual(log.calls, ["a", "b"])
    }

    func testAChildThatThrowsCostsTheOthersNothingAndThrowsNothingBack() throws {
        let log = CallLog()
        let sink: any TelemetrySink = .composite(
            NamedSink(name: "a", log: log),
            NamedSink(name: "throws", log: log, failing: true),
            NamedSink(name: "c", log: log))

        // A throw escaping here would be one escaping onto the shared delivery queue.
        XCTAssertNoThrow(try sink.onEvent(event))
        XCTAssertNoThrow(try sink.onEvent(event))

        XCTAssertEqual(log.calls, ["a", "throws", "c", "a", "throws", "c"])
    }
}

/// What each child was called with, in order; locked because a sink is `Sendable`.
final class CallLog: Sendable {
    private let lock = OSAllocatedUnfairLock(initialState: [String]())
    var calls: [String] { lock.withLock { $0 } }
    func record(_ name: String) { lock.withLock { $0.append(name) } }
}

private struct NamedSink: TelemetrySink {
    struct Refused: Error {}

    let name: String
    let log: CallLog
    var failing = false

    func onEvent(_ event: TelemetryEvent) throws {
        log.record(name)
        if failing { throw Refused() }
    }
}
