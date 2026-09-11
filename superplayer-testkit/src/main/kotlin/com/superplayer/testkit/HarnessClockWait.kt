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

package com.superplayer.testkit

import androidx.media3.common.util.Clock
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * How a load waits for the harness's clock, without sleeping.
 *
 * Every delay the testkit puts into a transfer — an injected latency, a throughput cap, a trace's
 * round trip and its pacing — runs on a loading thread while the test thread advances the clock, so
 * the wait is on fake time and resolves exactly when the test says it does. One of these is shared
 * by every wrapper under a player, so "is any load waiting" has one answer however the wrappers are
 * stacked.
 *
 * [Clock.onThreadBlocked] is what tells an auto-advancing clock that this thread is waiting on it —
 * `superplayer-core`'s harness uses one — and is a no-op under [PlaybackHarness]'s clock, which the
 * test advances by hand.
 *
 * The wall-clock bound is not a timeout in the ordinary sense: it can only be reached when the test
 * never advances time far enough, and a spin that hung there would look like a stuck build rather
 * than like the test bug it is. An interrupt ends the wait at once, because that is how Media3's
 * `Loader` cancels a load — a released player would otherwise leave its loading thread spinning on
 * a clock nobody will advance again.
 */
internal class HarnessClockWait(private val clock: Clock) {

    /** The deadline each waiting thread is waiting for. */
    private val deadlines = ConcurrentHashMap<Thread, Long>()

    /** Whether any load is currently held by a delay. */
    val isWaiting: Boolean get() = deadlines.isNotEmpty()

    /**
     * The earliest deadline a load is waiting for, or null when none is.
     *
     * Nothing in the library reads it; a test does, to advance the clock *to* the moment a load is
     * waiting for rather than past it — which is what lets a test assert that a byte arrived at an
     * exact time instead of within a step of it.
     */
    val earliestDeadlineMs: Long? get() = deadlines.values.minOrNull()

    /** Shaped transfers between their open and their close. */
    private val openTransfers = AtomicInteger()

    /** A shaped transfer has opened: until [transferClosed], it either waits here or is working. */
    fun transferOpened() {
        openTransfers.incrementAndGet()
    }

    fun transferClosed() {
        openTransfers.decrementAndGet()
    }

    /**
     * Whether every open shaped transfer has acted on the time that has passed: each one is waiting
     * for a moment that has not come yet, rather than released and still working.
     *
     * What [PlaybackHarness] waits for after each advance, so that a load paced on a trace keeps up
     * with the clock rather than falling behind it whenever the loading thread is short of CPU — a
     * lag that would reach the engine as a stall the trace never described.
     */
    val transfersHaveCaughtUp: Boolean
        get() {
            val now = clock.elapsedRealtime()
            return deadlines.values.count { it > now } >= openTransfers.get()
        }

    /** Returns once the clock reads [deadlineMs]; [what] names the delay if the test never gets there. */
    fun until(deadlineMs: Long, what: String) {
        if (clock.elapsedRealtime() >= deadlineMs) return
        val thread = Thread.currentThread()
        val startedAtMs = System.currentTimeMillis()
        deadlines[thread] = deadlineMs
        try {
            while (clock.elapsedRealtime() < deadlineMs) {
                if (thread.isInterrupted) throw InterruptedIOException("Cancelled while waiting for $what")
                clock.onThreadBlocked()
                Thread.yield()
                check(System.currentTimeMillis() - startedAtMs < MAX_WALL_CLOCK_WAIT_MS) {
                    "Waited $MAX_WALL_CLOCK_WAIT_MS ms of real time for $what to elapse on the harness " +
                        "clock, which is still at ${clock.elapsedRealtime()} of $deadlineMs. Advance the " +
                        "harness clock past an injected delay, or the load it holds up never completes."
                }
            }
        } finally {
            deadlines.remove(thread)
        }
    }

    private companion object {
        /** Real seconds, and only ever reached when a test forgot to advance the clock. */
        const val MAX_WALL_CLOCK_WAIT_MS = 10_000L
    }
}
