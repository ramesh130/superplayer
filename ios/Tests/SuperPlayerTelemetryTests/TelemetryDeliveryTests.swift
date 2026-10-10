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
import SuperPlayerCore
import XCTest
import os

// `@testable` for `TelemetryDelivery`, which is internal as the Kotlin class is: a consumer reaches
// it only through a collector, and the bound is asserted where it is enforced.
@testable import SuperPlayerTelemetry

/// The delivery queue's bound, its refusal rule and its drop count (ADR-0008 rules 3 and 4) — the
/// Kotlin `TelemetryDeliveryTest`'s claims, on a queue of each test's own so a sink held still here
/// holds nothing else still.
///
/// The bound is exercised by holding the sink: its first call parks on a semaphore the test opens,
/// so every event submitted meanwhile meets a queue nothing drains. Counts and order are asserted,
/// never a duration; every wait is a ceiling.
final class TelemetryDeliveryTests: XCTestCase {

    /// The ceiling on every wait, from the moment it is read.
    private var bound: DispatchTime { .now() + .seconds(30) }

    func testEventsReachTheSinkInOrderWithNoDropsBelowTheBound() throws {
        let sink = HeldSink(holding: false)
        let delivery = TelemetryDelivery(sink: sink, queue: ownQueue())

        for index in 0..<10 { delivery.submit(sample(index, session: "a")) }
        delivery.submit(ended("a"))

        let events = try XCTUnwrap(sink.waitForSessionEnd("a", until: bound))
        XCTAssertEqual(events.dropLast().map(\.monotonicTimeMs), Array(0..<10))
        XCTAssertEqual(droppedCount(of: events.last), 0)
        XCTAssertEqual(sink.mainThreadCalls, 0, "a sink is never called on the main thread")
    }

    func testPastTheBoundTheNewestIsRefusedAndCountedOnTheSessionEnd() throws {
        let sink = HeldSink(holding: true)
        let delivery = TelemetryDelivery(sink: sink, capacity: 256, queue: ownQueue())

        // The first event is taken off the queue and parks the sink, so 256 more fill the queue.
        delivery.submit(sample(-1, session: "a"))
        XCTAssertEqual(sink.held.wait(timeout: bound), .success, "the sink was never called")
        for index in 0..<300 { delivery.submit(sample(index, session: "a")) }
        delivery.submit(ended("a"))  // the reserve: refused by no bound
        sink.release()

        let events = try XCTUnwrap(sink.waitForSessionEnd("a", until: bound))
        XCTAssertEqual(
            events.dropLast().map(\.monotonicTimeMs), [-1] + Array(0..<256),
            "what was queued is delivered and what came after it is not")
        XCTAssertEqual(droppedCount(of: events.last), 300 - 256)
    }

    func testADropIsCountedAgainstTheSessionThatLostItAndNoOther() throws {
        let sink = HeldSink(holding: true)
        let delivery = TelemetryDelivery(sink: sink, capacity: 4, queue: ownQueue())

        delivery.submit(sample(-1, session: "a"))
        XCTAssertEqual(sink.held.wait(timeout: bound), .success, "the sink was never called")
        for index in 0..<4 { delivery.submit(sample(index, session: "a")) }  // fills the queue
        for index in 0..<3 { delivery.submit(sample(index, session: "b")) }  // all refused
        delivery.submit(ended("a"))
        delivery.submit(ended("b"))
        sink.release()

        let events = try XCTUnwrap(sink.waitForSessionEnd("b", until: bound))
        let ends = events.filter { if case .sessionEnded = $0 { true } else { false } }
        XCTAssertEqual(ends.map(\.sessionId), ["a", "b"])
        XCTAssertEqual(ends.map(droppedCount(of:)), [0, 3])
    }

    func testMemoryPressureDiscardsAllButTheSessionEndAndCountsEachDiscard() throws {
        let sink = HeldSink(holding: true)
        let delivery = TelemetryDelivery(sink: sink, queue: ownQueue())

        delivery.submit(sample(-1, session: "a"))
        XCTAssertEqual(sink.held.wait(timeout: bound), .success, "the sink was never called")
        for index in 0..<5 { delivery.submit(sample(index, session: "a")) }
        delivery.submit(ended("a"))
        delivery.onMemoryPressure()
        sink.release()

        let events = try XCTUnwrap(sink.waitForSessionEnd("a", until: bound))
        XCTAssertEqual(events.count, 2, "the held event and the session's end")
        XCTAssertEqual(droppedCount(of: events.last), 5)
    }

    func testASinkThatThrowsDoesNotStopDelivery() throws {
        let sink = HeldSink(holding: false, failing: true)
        let delivery = TelemetryDelivery(sink: sink, queue: ownQueue())

        for index in 0..<3 { delivery.submit(sample(index, session: "a")) }
        delivery.submit(ended("a"))

        XCTAssertEqual(try XCTUnwrap(sink.waitForSessionEnd("a", until: bound)).count, 4)
    }

    private func ownQueue() -> DispatchQueue {
        DispatchQueue(label: "TelemetryDeliveryTests.\(name)")
    }

    /// An ordinary event whose monotonic time is `index`, so order is readable off the sink.
    private func sample(_ index: Int, session: String) -> TelemetryEvent {
        .rebufferStarted(
            .init(
                sessionId: session, contentId: "c", timestampMs: 0, monotonicTimeMs: Int64(index),
                seekInduced: false))
    }

    private func ended(_ session: String) -> TelemetryEvent {
        .sessionEnded(.init(sessionId: session, contentId: "c", timestampMs: 0, monotonicTimeMs: 0))
    }

    private func droppedCount(of event: TelemetryEvent?) -> Int? {
        guard case .sessionEnded(let ended) = event else { return nil }
        return ended.droppedEventCount
    }
}

/// A sink that records what reaches it and, when `holding`, parks its first call until released.
private final class HeldSink: TelemetrySink {
    struct Refused: Error {}

    /// Signalled when the first call has begun, so the test knows the drain is parked.
    let held = DispatchSemaphore(value: 0)
    private let gate = DispatchSemaphore(value: 0)
    private let holding: Bool
    private let failing: Bool
    private let received = OSAllocatedUnfairLock(initialState: [TelemetryEvent]())
    private let arrived = DispatchSemaphore(value: 0)
    private let onMain = OSAllocatedUnfairLock(initialState: 0)

    /// How many calls arrived on the main thread — the thread a test method runs on.
    var mainThreadCalls: Int { onMain.withLock { $0 } }

    init(holding: Bool, failing: Bool = false) {
        self.holding = holding
        self.failing = failing
    }

    func onEvent(_ event: TelemetryEvent) throws {
        if Thread.isMainThread { onMain.withLock { $0 += 1 } }
        let first = received.withLock { events in
            events.append(event)
            return events.count == 1
        }
        if first && holding {
            held.signal()
            gate.wait()
        }
        arrived.signal()
        if failing { throw Refused() }
    }

    func release() { gate.signal() }

    /// Everything received, once `session`'s end has arrived; nil if it has not by `deadline`.
    func waitForSessionEnd(_ session: String, until deadline: DispatchTime) -> [TelemetryEvent]? {
        while arrived.wait(timeout: deadline) == .success {
            let events = received.withLock { $0 }
            if events.contains(where: {
                if case .sessionEnded(let ended) = $0 { ended.sessionId == session } else { false }
            }) {
                return events
            }
        }
        return nil
    }
}
