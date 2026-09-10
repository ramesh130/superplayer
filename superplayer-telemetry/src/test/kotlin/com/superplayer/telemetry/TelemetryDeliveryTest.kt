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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bound, the drop policy and the drop count — ADR-0008 rules 3 and 4, at the one class that
 * implements them.
 *
 * Deliberately below `QoeCollector`: what is under test here is the *delivery path*, and driving it
 * through a player would mean provoking memory pressure and a stalled sink through two layers that
 * have nothing to do with either. `QoeCollectorTest` covers the composition — that a collector
 * submits rather than calls, and that a consumer's sink is reached at all.
 *
 * Every test that needs delivery to stall uses [pausedExecutor] rather than a sleeping sink, so the
 * assertions are on counts rather than on tolerances: the queue is stalled for exactly as long as
 * the test says and not one scheduler quantum more.
 */
@RunWith(AndroidJUnit4::class)
class TelemetryDeliveryTest {

    private val received = mutableListOf<TelemetryEvent>()
    private val sink = TelemetrySink { synchronized(received) { received += it } }

    /**
     * An executor that queues the drain task and runs it only when the test says so.
     *
     * This is what makes "the sink has fallen behind" a state a test can hold the delivery path in
     * for as long as it needs, without a sleep anywhere.
     */
    private class PausedExecutor : Executor {
        private val pending = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            synchronized(pending) { pending += command }
        }

        /** Runs every task scheduled so far, on the calling thread. */
        fun runQueued() {
            val tasks = synchronized(pending) { pending.toList().also { pending.clear() } }
            tasks.forEach(Runnable::run)
        }
    }

    private val pausedExecutor = PausedExecutor()

    @Test
    fun eventsReachTheSinkInTheOrderTheyWereSubmitted() {
        val delivery = TelemetryDelivery(sink, capacity = 8, executor = pausedExecutor)

        delivery.submit(sessionStarted(SESSION))
        repeat(3) { delivery.submit(rebufferStarted(SESSION)) }
        delivery.submit(sessionEnded(SESSION))
        pausedExecutor.runQueued()

        // Rule 4: events within one session arrive in the order they occurred. Everything a pipeline
        // does with a rebuffer — pairing a start with an end, attributing it to a seek — assumes it.
        assertThat(received.map { it::class.simpleName })
            .containsExactly("SessionStarted", "RebufferStarted", "RebufferStarted", "RebufferStarted", "SessionEnded")
            .inOrder()
    }

    @Test
    fun aFullQueueDropsTheNewestAndTheTerminalEventReportsHowMany() {
        val delivery = TelemetryDelivery(sink, capacity = 4, executor = pausedExecutor)

        // Four fit. The next ten are refused — the newest goes, which is the policy the class KDoc
        // argues for, and the four already queued are what survives.
        val survivors = List(4) { sessionStarted(SESSION) }
        survivors.forEach(delivery::submit)
        repeat(10) { delivery.submit(rebufferStarted(SESSION)) }
        delivery.submit(sessionEnded(SESSION))
        pausedExecutor.runQueued()

        // Bounded: five events for fifteen submitted, and the fifth is the reserved terminal one.
        assertThat(received).hasSize(5)
        assertThat(received.take(4)).containsExactlyElementsIn(survivors).inOrder()
        val ended = received.last() as TelemetryEvent.SessionEnded
        assertThat(ended.droppedEventCount).isEqualTo(10)
    }

    @Test
    fun theTerminalEventIsNeverRefusedHoweverFullTheQueueIs() {
        val delivery = TelemetryDelivery(sink, capacity = 1, executor = pausedExecutor)

        repeat(50) { delivery.submit(rebufferStarted(SESSION)) }
        delivery.submit(sessionEnded(SESSION))
        pausedExecutor.runQueued()

        // Rule 3's reserve. An event whose whole job is to declare what was lost is worthless if
        // pressure can lose it, so a full queue takes it anyway.
        val ended = received.filterIsInstance<TelemetryEvent.SessionEnded>().single()
        assertThat(ended.droppedEventCount).isEqualTo(49)
    }

    @Test
    fun aDropIsCountedAgainstTheSessionThatLostIt() {
        val delivery = TelemetryDelivery(sink, capacity = 2, executor = pausedExecutor)

        // A feed interleaves sessions through one process-wide delivery thread, so a count that was
        // a property of the process rather than of the session would tell a pipeline to throw away
        // every concurrent session because one of them was noisy.
        delivery.submit(sessionStarted(SESSION))
        delivery.submit(sessionStarted(OTHER_SESSION))
        repeat(5) { delivery.submit(rebufferStarted(SESSION)) }
        delivery.submit(rebufferStarted(OTHER_SESSION))
        delivery.submit(sessionEnded(SESSION))
        delivery.submit(sessionEnded(OTHER_SESSION))
        pausedExecutor.runQueued()

        val ends = received.filterIsInstance<TelemetryEvent.SessionEnded>().associateBy { it.sessionId }
        assertThat(ends.getValue(SESSION).droppedEventCount).isEqualTo(5)
        assertThat(ends.getValue(OTHER_SESSION).droppedEventCount).isEqualTo(1)
    }

    @Test
    fun memoryPressureDiscardsWhatIsPendingAndCountsEveryDiscard() {
        val delivery = TelemetryDelivery(sink, capacity = 64, executor = pausedExecutor)

        delivery.submit(sessionStarted(SESSION))
        repeat(9) { delivery.submit(rebufferStarted(SESSION)) }
        delivery.submit(sessionEnded(SESSION))

        // `PRD.md` §3.4: a queue of deferred events is exactly the kind of state a process gives back
        // when the platform asks, and holding it would make telemetry a cause of the eviction it is
        // trying to report on.
        delivery.onMemoryPressure()
        pausedExecutor.runQueued()

        // The terminal event survives, and it is what says the other ten are gone.
        val ended = received.single() as TelemetryEvent.SessionEnded
        assertThat(ended.droppedEventCount).isEqualTo(10)
    }

    @Test
    fun aDropCausedAfterTheSessionEndedStillLandsOnItsTerminalEvent() {
        val delivery = TelemetryDelivery(sink, capacity = 64, executor = pausedExecutor)

        delivery.submit(sessionStarted(SESSION))
        // Drained, so the only thing pressure can find below is the event submitted after the end.
        pausedExecutor.runQueued()
        delivery.submit(sessionEnded(SESSION))
        // Submitted after the terminal event: a pooled player recycled straight into new content
        // does exactly this, and pressure arriving now discards events the count must still include.
        // Stamping at submission rather than at delivery is what would lose them.
        delivery.submit(rebufferStarted(SESSION))
        delivery.onMemoryPressure()
        pausedExecutor.runQueued()

        val ended = received.filterIsInstance<TelemetryEvent.SessionEnded>().single()
        assertThat(ended.droppedEventCount).isEqualTo(1)
    }

    @Test
    fun aSinkIsNeverCalledOnTheThreadThatSubmitted() {
        val delivered = CountDownLatch(1)
        val sinkThread = arrayOfNulls<Thread>(1)
        val delivery = TelemetryDelivery(
            sink = {
                sinkThread[0] = Thread.currentThread()
                delivered.countDown()
            },
            capacity = 8,
        )

        delivery.submit(sessionStarted(SESSION))

        // Rule 4, at its narrowest and most important reading: whatever thread caused an event — the
        // application thread for a `setMediaRequest`, the playback thread for anything an engine
        // callback derives — is not the thread the consumer's code runs on.
        assertThat(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(sinkThread[0]).isNotSameInstanceAs(Thread.currentThread())
    }

    @Test
    fun aSinkThatBlocksForeverBlocksNoSubmitter() {
        val stuck = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val delivered = AtomicInteger()
        val delivery = TelemetryDelivery(
            sink = { event ->
                synchronized(received) { received += event }
                delivered.incrementAndGet()
                entered.countDown()
                stuck.await()
            },
            capacity = 32,
        )

        delivery.submit(sessionStarted(SESSION))
        assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

        try {
            // The sink is now wedged. This is the failure the whole issue exists for: an analytics
            // SDK doing a synchronous network write on a bad connection. Every one of these
            // submissions is what a playback callback would be doing, and each has to return — a
            // test that hangs here is the video stall a user would have reported instead.
            repeat(100) { delivery.submit(rebufferStarted(SESSION)) }
            delivery.submit(sessionEnded(SESSION))

            // And the backlog stayed bounded rather than growing to meet the flood.
            assertThat(delivered.get()).isEqualTo(1)
        } finally {
            stuck.countDown()
        }

        assertThat(delivery.awaitIdle(TIMEOUT_SECONDS * 1_000)).isTrue()
        // 100 rebuffers offered into 32 slots, one of which the in-flight `SessionStarted` had
        // already vacated: what is left is the bound, plus the terminal event that reports the rest.
        val ended = received.filterIsInstance<TelemetryEvent.SessionEnded>().single()
        assertThat(ended.droppedEventCount).isEqualTo(100 - 32)
        // And the memory the flood cost stayed at the bound rather than growing to meet it: 101
        // events were offered and 34 exist. An unbounded queue is the second way telemetry kills an
        // app — not a stall but an OOM whose crash report names the allocation site, never the
        // cause.
        assertThat(received).hasSize(1 + 32 + 1)
    }

    @Test
    fun oneThreadServesEveryDeliveryInTheProcess() {
        val threads = Executors.newFixedThreadPool(4)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val delivered = CountDownLatch(8)
        val deliveries = List(8) {
            TelemetryDelivery(
                sink = {
                    seen += Thread.currentThread().name
                    delivered.countDown()
                },
                capacity = 8,
            )
        }

        // A feed builds a player per row. Rule 4 says sixty pooled players may not mean sixty
        // threads, and a per-collector queue is what makes that easy to get wrong.
        deliveries.forEach { delivery -> threads.execute { delivery.submit(sessionStarted(SESSION)) } }
        assertThat(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        threads.shutdown()

        assertThat(seen).hasSize(1)
    }

    private fun sessionStarted(sessionId: String) = TelemetryEvent.SessionStarted(
        sessionId = sessionId,
        contentId = CONTENT,
        timestampMs = TIMESTAMP_MS,
        monotonicTimeMs = MONOTONIC_MS,
        profile = PlaybackProfile.VIDEO_ON_DEMAND,
        decision = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND).decide(PlaybackConditions()),
    )

    private fun rebufferStarted(sessionId: String) = TelemetryEvent.RebufferStarted(
        sessionId = sessionId,
        contentId = CONTENT,
        timestampMs = TIMESTAMP_MS,
        monotonicTimeMs = MONOTONIC_MS,
        seekInduced = false,
    )

    private fun sessionEnded(sessionId: String) = TelemetryEvent.SessionEnded(
        sessionId = sessionId,
        contentId = CONTENT,
        timestampMs = TIMESTAMP_MS,
        monotonicTimeMs = MONOTONIC_MS,
    )

    private companion object {
        const val SESSION = "session-a"
        const val OTHER_SESSION = "session-b"
        const val CONTENT = "series/expanse/s01e01"
        const val TIMESTAMP_MS = 1_700_000_000_000L
        const val MONOTONIC_MS = 42_000L

        /** Generous, because it is only ever reached when something is genuinely stuck. */
        const val TIMEOUT_SECONDS = 5L
    }
}
