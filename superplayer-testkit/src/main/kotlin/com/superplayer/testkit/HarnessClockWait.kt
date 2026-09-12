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

    /** Transfers between their open and their close. */
    private val openTransfers = AtomicInteger()

    /** Load tasks between their submission and their return, on an executor the harness supplied. */
    private val activeLoadTasks = AtomicInteger()

    /** Every transfer ever opened or closed and every load task ever submitted or finished. */
    private val activity = AtomicInteger()

    /**
     * A count that changes whenever a transfer opens or closes or a load task is submitted or
     * finishes, and never otherwise.
     *
     * What [PlaybackHarness] compares across two settles to know whether the engine has finished
     * reacting to the loads of the current moment: the count between a load's end and the engine
     * hearing of it is the same as the count once everything is quiet, and only a second look tells
     * them apart.
     */
    val activitySoFar: Int get() = activity.get()

    /** A transfer has opened: until [transferClosed], it either waits here or is working. */
    fun transferOpened() {
        openTransfers.incrementAndGet()
        activity.incrementAndGet()
    }

    fun transferClosed() {
        openTransfers.decrementAndGet()
        activity.incrementAndGet()
    }

    /**
     * A load task has been handed to a harness-supplied executor: until [loadTaskFinished], the
     * loading thread is working, waiting here, or on its way to telling the engine.
     */
    fun loadTaskSubmitted() {
        activeLoadTasks.incrementAndGet()
        activity.incrementAndGet()
    }

    /** The task has returned — its transfer is closed *and* its completion has been posted. */
    fun loadTaskFinished() {
        activeLoadTasks.decrementAndGet()
        activity.incrementAndGet()
    }

    /**
     * Whether every open transfer, and every load task on an executor the harness supplied, has
     * acted on the time that has passed: each one is waiting for a moment that has not come yet,
     * rather than released and still working.
     *
     * What [PlaybackHarness] waits for after each advance, so that a load keeps up with the clock
     * rather than falling behind it whenever the loading thread is short of CPU. A trace's load that
     * lags reaches the engine as a stall the trace never described; a live playlist's reload that
     * lags reaches Media3's tracker as a playlist that stopped advancing (issue #91).
     *
     * Counting tasks and not only transfers is what makes the answer exact rather than close: a
     * task is still active in the gap after its transfer closes and before it has posted its
     * completion to the playback thread, and a harness that advanced the clock inside that gap
     * would deliver the completion one step late — on some runs. A load through Media3's own
     * threading, where the harness supplied no executor, is covered by its transfer alone.
     */
    val transfersHaveCaughtUp: Boolean
        get() {
            val now = clock.elapsedRealtime()
            return deadlines.values.count { it > now } >= maxOf(openTransfers.get(), activeLoadTasks.get())
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

/**
 * One wrapper's registration of one transfer with a [HarnessClockWait]: opened once, closed once.
 *
 * Held by every [androidx.media3.datasource.DataSource] the testkit puts under a player, because the
 * bookkeeping is identical in each and getting it wrong in one is invisible: a transfer left counted
 * as open holds up every later advance until the wall-clock bound, and one never counted lets the
 * clock run away from the load.
 *
 * [counts] is how "exactly one wrapper counts a transfer" is arranged where wrappers are stacked. The
 * outermost one counts; the rest hold a registration that does nothing, because one load registered
 * twice can never be seen to catch up — the single thread carrying it waits for one deadline, and the
 * count would ask for two.
 */
internal class TransferRegistration(private val wait: HarnessClockWait, private val counts: Boolean = true) {

    private var open = false

    /** Registers the transfer, at the top of `open` so that a request that throws is still counted. */
    fun opened() {
        if (counts && !open) {
            open = true
            wait.transferOpened()
        }
    }

    /**
     * Releases it, and does nothing on a second call.
     *
     * Owed even when `open` threw — an injected status code leaves a source through `close` — and
     * `DataSource.close` is called more than once by some callers, which is why this is idempotent
     * rather than a bare decrement.
     */
    fun closed() {
        if (open) {
            open = false
            wait.transferClosed()
        }
    }
}
