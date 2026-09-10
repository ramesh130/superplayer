/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.telemetry

import android.util.Log
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The path between a collector and a [TelemetrySink]: a bounded queue, drained on a thread the
 * engine does not need, dropping under pressure and counting every drop.
 *
 * This is ADR-0008 rules 3 and 4, and it is the whole of what issue #37 exists for. The guarantee is
 * one sentence: **measuring never costs playback**. A consumer's sink is arbitrary code — an
 * analytics SDK doing a synchronous network write is the ordinary case — and calling it from the
 * playback thread makes that write a video stall the app gets blamed for, with nothing in any log
 * pointing at telemetry.
 *
 * ## The thread
 *
 * One, shared by every collector in the process, daemon, named [THREAD_NAME].
 *
 * Shared rather than per-player because a feed builds players through `PlayerPool` and sixty pooled
 * players may not mean sixty threads (rule 4). Serialized because one thread drains every queue in
 * turn, so a sink needs no locking of its own — and, since the same thread drains everyone, a sink
 * that blocks delays other *sessions'* events rather than anyone's playback. That is the trade this
 * shape makes deliberately: telemetry latency is elastic, playback is not.
 *
 * Daemon, because a thread whose only job is to write events must never be the reason a process
 * stays alive.
 *
 * ## The queue
 *
 * Per collector, so a drop is attributable to the session it belonged to and
 * [TelemetryEvent.SessionEnded.droppedEventCount] can report it. Bounded at [DEFAULT_CAPACITY].
 *
 * **Why 256.** The bound is a stall the delivery path can absorb, so it is derived from a rate and a
 * duration rather than picked for looking round. The library's sustained ceiling per session is the
 * three periodic events every 10 s (`docs/telemetry-schema.md`) plus, in the worst case, one
 * `TrackSwitched` per segment at the 2 s segment duration both the HLS and DASH interoperability
 * profiles use — about 0.8 events per second. `DeviceCapacity` bounds concurrent players by the
 * platform's decoder limit, which no current device reports above 16, so the process ceiling is
 * around 13 events per second. 256 events is therefore roughly 20 seconds of a completely stalled
 * sink before anything is lost, which is far longer than any sink that is merely slow will stall,
 * and short enough to be worth nothing in memory: an event is on the order of 100 bytes, so a full
 * queue is ~25 KB against the megabytes-per-player budget `DeviceCapacity` computes.
 *
 * ## The drop policy: the newest goes
 *
 * When the queue is full an ordinary event is **refused at submission** — the newest is what is
 * lost, and what is already queued is delivered.
 *
 * The argument is that the front of a session is the part that cannot be reconstructed. Time to
 * first frame happens once and is the metric the whole start-up boundary argument exists for; the
 * initial `TrackSwitched` is the only statement of what the session started at. What is arriving at
 * the moment of pressure, by contrast, is mostly periodic samples that recur every 10 s, and one
 * missing sample of a time-weighted quantity costs its weight and nothing else.
 *
 * Dropping the *oldest* is the real alternative and it is better in exactly one case: an error
 * postmortem wants the seconds before the failure, and this policy is the one that loses them.
 * That case is not free to serve, either — evicting from the head can evict another session's
 * terminal event, which rule 3 forbids outright, so honouring the reserve would mean scanning the
 * queue on every drop. The cost is accepted rather than hidden: a session that dropped anything
 * says so, and `docs/telemetry-schema.md` tells a pipeline to exclude such a session rather than
 * average it in.
 *
 * ## The reserve
 *
 * [TelemetryEvent.SessionEnded] is never refused, whatever the queue holds. Rule 3 makes it the one
 * event the bound may not drop, because an event whose whole job is to declare what was lost is
 * worthless if pressure can lose it. It is a reserve rather than an unbounded exception: a session
 * emits exactly one, and the number of sessions in flight is bounded by the number of live players,
 * which `PlayerPool` itself bounds.
 *
 * Its `droppedEventCount` is stamped at *delivery* rather than at submission, which is what makes
 * the number complete — a drop caused by [onMemoryPressure] after the session ended still lands on
 * the event that reports it.
 */
internal class TelemetryDelivery(
    private val sink: TelemetrySink,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val executor: Executor = sharedExecutor,
) {

    private val lock = ReentrantLock()

    /** Signalled whenever the drain goes idle; only [awaitIdle] waits on it. */
    private val idle = lock.newCondition()

    /** FIFO, so events within one session reach the sink in the order they occurred. */
    private val queue = ArrayDeque<TelemetryEvent>()

    /**
     * Whether a drain task is scheduled or running.
     *
     * One task at a time per queue: the drain loops until the queue is empty, so a second task would
     * only contend for the same events. This flag is also what [awaitIdle] waits to see cleared.
     */
    private var draining = false

    /**
     * Drops so far, per session id, awaiting the session's terminal event to carry them out.
     *
     * Keyed by session because the count is a property of the session a pipeline is deciding whether
     * to trust, not of the process. An entry is removed when the session's [TelemetryEvent.SessionEnded]
     * is delivered, which is also the only thing that ever reads it — so the map holds at most one
     * entry per session in flight, and a session that never ends is a player that was never released.
     */
    private val dropsBySession = mutableMapOf<String, Int>()

    /**
     * Queues [event] for delivery, or drops it and counts the drop.
     *
     * Returns without touching the sink: this is called from the application thread and from the
     * engine's playback thread, and the only work done on either is an enqueue under an uncontended
     * lock.
     */
    fun submit(event: TelemetryEvent) {
        val schedule = lock.withLock {
            // Rule 3's reserve. Everything else yields to the bound.
            if (event !is TelemetryEvent.SessionEnded && queue.size >= capacity) {
                dropsBySession[event.sessionId] = (dropsBySession[event.sessionId] ?: 0) + 1
                return
            }
            queue.addLast(event)
            val wasIdle = !draining
            draining = true
            wasIdle
        }
        if (schedule) {
            executor.execute(::drain)
        }
    }

    /**
     * Discards everything discardable that has not yet been delivered, counting each discard.
     *
     * Reached from `onTrimMemory` (`PRD.md` §3.4): under memory pressure the queue is exactly the
     * kind of deferred, reconstructible-in-aggregate state a process should give back first, and
     * holding it while the platform asks for memory would make telemetry a cause of the very
     * eviction it is trying to report on.
     *
     * The terminal events survive, for the same reason the bound does not apply to them.
     */
    fun onMemoryPressure() {
        lock.withLock {
            val terminal = queue.filterIsInstance<TelemetryEvent.SessionEnded>()
            queue.filterNot { it is TelemetryEvent.SessionEnded }.forEach { discarded ->
                dropsBySession[discarded.sessionId] = (dropsBySession[discarded.sessionId] ?: 0) + 1
            }
            queue.clear()
            queue.addAll(terminal)
        }
    }

    /**
     * Blocks until everything submitted so far has reached the sink, or [timeoutMs] elapses.
     *
     * Tests only, and the reason delivery being asynchronous does not make an assertion a race. It
     * is deliberately not something a consumer can reach: a public "flush" is an invitation to call
     * it from the very thread this class exists to keep out of the sink.
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        lock.withLock {
            while (draining || queue.isNotEmpty()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                idle.awaitNanos(remaining)
            }
        }
        return true
    }

    /**
     * Delivers until the queue is empty, one event at a time and never while holding the lock.
     *
     * Outside the lock is the point: a sink that blocks for seconds must not also stop the playback
     * thread from enqueueing, or this class would have moved the stall rather than removed it.
     */
    private fun drain() {
        while (true) {
            val event = lock.withLock {
                val next = queue.removeFirstOrNull()
                if (next == null) {
                    draining = false
                    idle.signalAll()
                    return
                }
                // Stamped here rather than where the session ended, so that a drop counted after the
                // terminal event was submitted still reaches the sink on it.
                if (next is TelemetryEvent.SessionEnded) {
                    next.copy(droppedEventCount = dropsBySession.remove(next.sessionId) ?: 0)
                } else {
                    next
                }
            }
            try {
                sink.onEvent(event)
            } catch (throwable: Throwable) {
                // `Throwable` for the reason `TelemetrySink.composite` gives: a sink built on an
                // analytics SDK with a missing optional dependency throws `NoClassDefFoundError`,
                // and an escaping one here would kill the shared delivery thread for every other
                // player in the process.
                Log.w(TAG, "Telemetry sink ${sink.javaClass.name} threw; delivery continues", throwable)
            }
        }
    }

    internal companion object {

        /** See the class KDoc for where this number comes from; it is a stall budget, not a round number. */
        const val DEFAULT_CAPACITY: Int = 256

        /** The tag `LogcatSink` and `TelemetrySink.composite` also write under, so one grep finds all of it. */
        private const val TAG: String = LogcatSink.TAG

        private const val THREAD_NAME: String = "SuperPlayerTelemetry"

        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * The process's one delivery thread, created on the first event anything ever submits.
         *
         * `by lazy` rather than eagerly, so that ADR-0008 rule 2 stays true at its strongest reading:
         * a process whose players were all built without telemetry starts no thread at all.
         */
        private val sharedExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, THREAD_NAME).apply { isDaemon = true }
            }
        }
    }
}
