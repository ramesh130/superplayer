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

import Dispatch
import SuperPlayerCore
import os

/// The path between a collector and a ``TelemetrySink``: a bounded queue, drained on a queue the
/// engine does not use, dropping under pressure and counting every drop — the Kotlin
/// `superplayer-telemetry` `TelemetryDelivery`, and ADR-0008 rules 3 and 4. Here because it is
/// there: the delivery machinery is the telemetry module's, and core only declares the seam.
///
/// The guarantee is one sentence: **measuring never costs playback**. A sink is arbitrary code — an
/// analytics SDK writing to the network synchronously is the ordinary case — and calling it where
/// the facade's observations land would make that write a stall the app gets blamed for.
///
/// ## The queue it is called on
///
/// One serial `DispatchQueue` for the process, ``sharedQueue``: shared because a feed of pooled
/// players must not mean a thread each (rule 4), serial so a sink needs no locking, and created on
/// first use — a Swift `static let` is lazy — so a process whose players measure nothing creates
/// none (rule 2). Its quality of service is `.utility`, which is what Apple names for work whose
/// result the user is not waiting on; the Kotlin thread is a daemon for the same reason, that
/// telemetry must never be what keeps anything running.
///
/// That a sink is never called on the main actor is the compiler's, not this queue's
/// (``TelemetrySink``'s *Which thread*): the requirement is nonisolated and the protocol `Sendable`,
/// so no conformance could run there even if this queue were the main one.
///
/// ref: https://developer.apple.com/documentation/dispatch/dispatchqos/qosclass/utility
///
/// ## The bound, the drop and the reserve
///
/// Per collector, so a drop is attributable to the session it cost; bounded at ``defaultCapacity``;
/// an ordinary event that finds the queue full is **refused at submission** — the newest is what is
/// lost — and ``TelemetryEvent/SessionEnded`` is never refused, because it carries the count of what
/// was. Each of the three is argued at the Kotlin class and restated in `docs/telemetry-schema.md`'s
/// *The stream is lossy*; the numbers and the policy are the same on both platforms, because they
/// describe one schema's stream.
///
/// The count is stamped as the terminal event *leaves* the queue rather than when it was submitted,
/// so a drop counted after the session ended — by ``onMemoryPressure()`` — still lands on it.
final class TelemetryDelivery: Sendable {

    /// A stall budget, not a round number: about twenty seconds of a completely stalled sink at the
    /// process's worst sustained rate, derived at the Kotlin `DEFAULT_CAPACITY` and in the document.
    static let defaultCapacity = 256

    /// How many sessions' drop counts are kept at once, the Kotlin `MAX_TRACKED_SESSIONS`: far above
    /// the players any device decodes for at once, so eviction only reaches an entry no terminal
    /// event is still coming for — one submitted after its session's end was delivered.
    static let maxTrackedSessions = 64

    /// Core's `TelemetryLog` names, repeated: a sink that threw is filed where a person debugging
    /// telemetry already filters.
    private static let logger = Logger(subsystem: "com.superplayer", category: "SuperPlayerQoE")

    /// The process's one delivery queue.
    static let sharedQueue = DispatchQueue(label: "SuperPlayerTelemetry", qos: .utility)

    private let sink: any TelemetrySink
    private let capacity: Int
    private let queue: DispatchQueue
    private let state = OSAllocatedUnfairLock(initialState: State())

    /// Everything the lock guards.
    private struct State: Sendable {
        /// FIFO, so a session's events reach the sink in the order they occurred.
        var pending: [TelemetryEvent] = []
        /// Whether a drain is scheduled or running; one at a time, since a drain loops until empty.
        var draining = false
        /// Drops so far per session id, removed when that session's terminal event is delivered.
        var drops: [String: Int] = [:]
        /// Session ids by when ``drops`` last touched them, least recent first — the Kotlin
        /// access-ordered map, as `RememberedPositions` keeps it, for the same bound.
        var dropOrder: [String] = []

        mutating func countDrop(of sessionId: String) {
            drops[sessionId, default: 0] += 1
            dropOrder.removeAll { $0 == sessionId }
            dropOrder.append(sessionId)
            if dropOrder.count > TelemetryDelivery.maxTrackedSessions {
                drops[dropOrder.removeFirst()] = nil
            }
        }

        mutating func takeDrops(of sessionId: String) -> Int {
            dropOrder.removeAll { $0 == sessionId }
            return drops.removeValue(forKey: sessionId) ?? 0
        }
    }

    init(
        sink: any TelemetrySink, capacity: Int = defaultCapacity,
        queue: DispatchQueue = sharedQueue
    ) {
        self.sink = sink
        self.capacity = capacity
        self.queue = queue
    }

    /// Queues `event` for delivery, or refuses it and counts the refusal against its session.
    /// Returns without touching the sink: the only work on the caller's thread is an append under
    /// an uncontended lock.
    func submit(_ event: TelemetryEvent) {
        let schedule = state.withLock { state -> Bool in
            if case .sessionEnded = event {
                // Rule 3's reserve: the terminal event is never refused.
            } else if state.pending.count >= capacity {
                state.countDrop(of: event.sessionId)
                return false
            }
            state.pending.append(event)
            defer { state.draining = true }
            return !state.draining
        }
        if schedule {
            queue.async { self.drain() }
        }
    }

    /// Discards everything pending but the terminal events, counting each discard — the Kotlin
    /// `onMemoryPressure`, reached on iOS from the memory warning the collector hears (#409).
    func onMemoryPressure() {
        state.withLock { state in
            var kept: [TelemetryEvent] = []
            for event in state.pending {
                if case .sessionEnded = event {
                    kept.append(event)
                } else {
                    state.countDrop(of: event.sessionId)
                }
            }
            state.pending = kept
        }
    }

    /// Delivers until nothing is pending, one event at a time and never while holding the lock: a
    /// sink that blocks must not also stop the caller from enqueueing, or the stall would only have
    /// moved.
    private func drain() {
        while let event = next() {
            do {
                try sink.onEvent(event)
            } catch {
                Self.logger.warning(
                    "Telemetry sink \(String(describing: type(of: self.sink)), privacy: .public) threw; delivery continues: \(String(describing: error), privacy: .public)"
                )
            }
        }
    }

    /// The next event to deliver, its drop count stamped if it ends a session; nil, with the drain
    /// marked finished, when nothing is pending.
    private func next() -> TelemetryEvent? {
        state.withLock { state in
            guard !state.pending.isEmpty else {
                state.draining = false
                return nil
            }
            let event = state.pending.removeFirst()
            guard case .sessionEnded(let ended) = event else { return event }
            return .sessionEnded(
                TelemetryEvent.SessionEnded(
                    sessionId: ended.sessionId, contentId: ended.contentId,
                    timestampMs: ended.timestampMs, monotonicTimeMs: ended.monotonicTimeMs,
                    droppedEventCount: state.takeDrops(of: ended.sessionId),
                    securityLevel: ended.securityLevel))
        }
    }
}
