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

package com.superplayer.moq

import android.net.Uri
import com.superplayer.core.EncodedFrame
import com.superplayer.core.FrameSink
import com.superplayer.core.RealtimeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.moq.MoqCatalog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a subscription says about the session under it, and what it deliberately does not (#368).
 *
 * Seven claims, and the ones about absence are what this ticket exists for. A reading **arrives** and
 * is the session's own numbers; the poller **paces** itself rather than spinning or asking once; a
 * session with nothing to say, and a single number a session did not report, are each **absent and
 * never zero**, which is the shape ADR-0018 rule 8 asks for in so many words; a failed poll costs
 * the subscription nothing; and upstream media loss is [MoqUpstreamMediaLoss.NOT_INSTRUMENTED] on
 * every reading, because the counters that would answer it are exported over no UniFFI binding.
 *
 * Nothing here opens a QUIC session and nothing here is telemetry: the relay is [ScriptedMoqRelay]
 * and the reading never enters `TelemetryEvent`, which [MoqSessionStatistics]' own KDoc argues.
 */
@RunWith(RobolectricTestRunner::class)
class MoqSessionStatisticsTest {

    /**
     * A subscription reports what its session said, and the numbers are the session's rather than a
     * record of defaults.
     *
     * **Every** field is asserted, by comparing the whole record, because the defect this method
     * names — a record constructed but never filled — passes any assertion that only checks a field
     * is present.
     */
    @Test
    fun aSubscriptionReportsWhatItsSessionSaid() {
        val relay = relay()
        val source = subscribed(relay)
        try {
            val reading = awaitStatistics(relay, source)

            assertEquals(scriptedAt(reading), reading)
            assertTrue("a reading is stamped with when it was taken", reading.sampledAtMs > 0)
        } finally {
            source.cancel()
        }
    }

    /**
     * The session is polled **repeatedly and on a pace**, which is the half of this ticket that is
     * a cadence rather than a call.
     *
     * The claim is about a span and the span **is** the measurement, which is the shape
     * `docs/testing.md`'s *Determinism* permits: the polls are *awaited* rather than sampled after a
     * window — [ScriptedMoqRelay.awaitPolls] either sees the number this test asked for or fails
     * naming it — and what is then asserted is how long getting them took. A poller that asked once
     * fails the await; one that spun, re-reading the FFI as fast as the host allows, gets there in
     * about no time at all and fails the elapsed bound.
     *
     * **There is deliberately no upper bound on the poll count.** A count is what a loaded host
     * makes unreliable, and an assertion that can be failed by a slow machine is the flake that rule
     * forbids. Elapsed time has no such direction: a clock cannot run backwards, so a lower bound on
     * how long [POLLS_WATCHED] polls took is as true under contention as it is on an idle laptop.
     */
    @Test
    fun theSessionIsPolledOnACadenceRatherThanOnceOrContinuously() {
        val relay = relay()
        val startedAt = System.nanoTime()
        val source = subscribed(relay)
        try {
            assertTrue(
                "waited for $POLLS_WATCHED polls of the session",
                relay.awaitPolls(POLLS_WATCHED, WAIT_BOUND_MS),
            )
            val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

            assertTrue(
                "$POLLS_WATCHED polls were paced, and took $elapsedMs ms",
                elapsedMs >= PACED_AT_LEAST_MS,
            )
            // And the reading in force is a later one than the first, which is what makes the polls
            // above readings rather than calls whose answers went nowhere.
            val latest = requireNotNull(source.statistics())
            assertTrue(
                "a later reading replaced the first, and was ${latest.bytesReceived}",
                requireNotNull(latest.bytesReceived) >
                    requireNotNull(scriptedStatistics(poll = 0).bytesReceived),
            )
        } finally {
            source.cancel()
        }
    }

    /**
     * A subscription with no reading reports **nothing**, and never a record of zeroes.
     *
     * This is the control the ticket asks for in as many words: a figure that reads zero because
     * nothing produced it is worse than one that is absent. The session here is one whose statistics
     * call fails, which is the only way a reading is missing altogether — MoQ always answers *a*
     * snapshot, so "no statistics" at the session's granularity is field-level absence and is the
     * next method's subject. The frames assertion is what makes the null mean "asked and got
     * nothing" rather than "nothing ran": the subscription is delivering throughout, and the relay's
     * own poll count says the session was asked.
     */
    @Test
    fun aSubscriptionWithNoReadingReportsAbsenceAndNeverZero() {
        val relay = relay(statisticsScript = ::statisticsThatFail)
        val sink = RecordingSink()
        val source = MoqFrameSource(broadcast(), relay, STATISTICS_INTERVAL_MS)
        try {
            source.subscribe(sink)
            assertTrue("waited for the session to be asked", relay.awaitPolls(count = 1, boundMs = WAIT_BOUND_MS))
            sink.awaitFrames(FRAMES_WATCHED)

            assertNull("there is no reading, which is not a record of zeroes", source.statistics())
        } finally {
            source.cancel()
        }
    }

    /**
     * A number the session did not report is **null on the reading**, beside the ones it did.
     *
     * The absence this ticket is about is upstream loss, but MoQ declares every statistic optional
     * and the receive-rate estimate really is absent against an older relay — so the same rule is
     * asserted at field granularity here: a mapping that defaulted an unreported number to zero
     * would report a link carrying nothing on a session that is delivering frames. The reading's
     * other fields are asserted present in the same breath, which is what keeps this from passing on
     * a reading that was empty throughout.
     */
    @Test
    fun aNumberTheSessionDidNotReportIsNullAndNotZero() {
        val relay = relay(statisticsScript = ::statisticsMissingTheReceiveRate)
        val source = subscribed(relay)
        try {
            val reading = awaitStatistics(relay, source)

            assertNull("the estimate the relay does not carry", reading.receiveRateBps)
            // And nothing else moved: the whole record is the script's, with that one field gone.
            assertEquals(scriptedAt(reading).copy(receiveRateBps = null), reading)
        } finally {
            source.cancel()
        }
    }

    /**
     * A statistics call that **fails** costs the subscription nothing.
     *
     * Measurement is not a reason to end a viewer's broadcast, and the defect this names is a real
     * one in the shape of the code: the poller runs inside the same wrapper every other thread of
     * this source does, and that wrapper turns anything raised into `FrameSink.onError`.
     */
    @Test
    fun aFailedPollDoesNotEndTheSubscription() {
        val relay = relay(statisticsScript = ::statisticsThatFail)
        val sink = RecordingSink()
        val source = MoqFrameSource(broadcast(), relay, STATISTICS_INTERVAL_MS)
        try {
            source.subscribe(sink)
            sink.awaitFrames(FRAMES_WATCHED)
            pause(QUIET_WINDOW_MS)

            assertNull("the subscription did not fail", sink.terminal)
            assertNull("and has no reading", source.statistics())
            // The poller stopped rather than asking again, which is what "a failed poll ends the
            // polling" means: one ask, not one per interval.
            assertEquals(1, relay.statisticsPolls.get())
        } finally {
            source.cancel()
        }
    }

    /**
     * Upstream **media** loss is absent on every reading, and it is absent as a *value*.
     *
     * The assertion is on the type rather than on a number, which is the whole of ADR-0018 rule 8's
     * "says so rather than reporting zero": there is no field here a pipeline could sum, average or
     * compare against a threshold. `transportPacketsLost` beside it is deliberately non-zero in the
     * script, so a reader who conflated the two would find this method passing and the ADR's claim
     * false — which is why the second assertion is here.
     */
    @Test
    fun upstreamMediaLossIsNotInstrumentedAndIsNotZero() {
        val relay = relay()
        val source = subscribed(relay)
        try {
            val reading = awaitStatistics(relay, source)

            assertEquals(MoqUpstreamMediaLoss.NOT_INSTRUMENTED, reading.upstreamMediaLoss)
            assertTrue(
                "the connection's own loss is a different fact and really is reported",
                requireNotNull(reading.transportPacketsLost) > 0,
            )
        } finally {
            source.cancel()
        }
    }

    /**
     * The last reading **survives cancellation**, and the poller does not.
     *
     * Two halves of one claim. The final numbers of a session that has just ended are the ones a bug
     * report wants, so they are kept; and `cancel` joins the statistics thread like every other, so
     * nothing goes on asking a session that has been closed — which is asserted as a poll count that
     * has stopped moving rather than as a thread that is gone.
     */
    @Test
    fun theLastReadingSurvivesCancellationAndThePollerDoesNot() {
        val relay = relay()
        val source = subscribed(relay)
        awaitStatistics(relay, source)
        source.cancel()
        val pollsAtCancel = relay.statisticsPolls.get()

        pause(QUIET_WINDOW_MS)

        assertNotNull("the last reading is kept", source.statistics())
        assertEquals("and nothing asked again", pollsAtCancel, relay.statisticsPolls.get())
    }

    /** Subscribes, waits for frames so the session is certainly open, and hands back the source. */
    private fun subscribed(relay: ScriptedMoqRelay): MoqFrameSource {
        val sink = RecordingSink()
        val source = MoqFrameSource(broadcast(), relay, STATISTICS_INTERVAL_MS)
        source.subscribe(sink)
        sink.awaitFrames(FRAMES_WATCHED)
        return source
    }

    /**
     * Waits for the first reading, and fails naming it rather than reaching an assertion on null.
     *
     * The **poll** is what is awaited, on the relay's own event, and the reading is read after it —
     * so this is a wait on something that happened rather than a clock polled until a field changes.
     *
     * **Two** polls and not one, and the off-by-one is the relay's shape rather than caution: a
     * scripted session announces a poll *before* it answers it, so that a session scripted to throw
     * is still a poll a waiting test can see. The first announcement therefore races the reading it
     * is about, while the second cannot: the poller stores one reading before asking for the next.
     */
    private fun awaitStatistics(relay: ScriptedMoqRelay, source: MoqFrameSource): MoqSessionStatistics {
        assertTrue("waited for the session's first reading", relay.awaitPolls(count = 2, boundMs = WAIT_BOUND_MS))
        return requireNotNull(source.statistics()) { "the session was polled and left no reading" }
    }

    /**
     * What the script says at the poll [reading] came from, stamped with that reading's own clock.
     *
     * The ordinal is read **off the reading**, because which poll a test is holding is a race it has
     * no business caring about: a poller several polls in is still correct, and a test that assumed
     * the first would fail for the wrong reason. `transportPacketsLost` is the script's poll count
     * plus one, so it names the poll, and every *other* field is then checked against it — which is
     * what makes a whole-record comparison stronger than a field-by-field one rather than circular.
     * The timestamp is the library's own reading and is asserted separately.
     */
    private fun scriptedAt(reading: MoqSessionStatistics): MoqSessionStatistics {
        val ordinal = requireNotNull(reading.transportPacketsLost).toInt() - 1
        assertTrue("the reading names a poll that happened, and named $ordinal", ordinal >= 0)
        return scriptedStatistics(ordinal).copy(sampledAtMs = reading.sampledAtMs)
    }

    private fun relay(
        statisticsScript: (Int) -> MoqSessionStatistics = ::scriptedStatistics,
    ) = ScriptedMoqRelay(catalog = videoOnly(), statisticsScript = statisticsScript)

    private fun videoOnly(): MoqCatalog =
        DeclaredCatalogs.videoOnly(VIDEO, DeclaredCatalogs.OBSERVED_AVC3_CODEC, description = null)

    private fun broadcast(): Uri = Uri.parse("${MoqFrameSource.SCHEME}://relay.example/studio-a")

    /** Waits [millis] on a latch nobody counts down, which is `MoqFrameSourceTest`'s own idiom. */
    private fun pause(millis: Long) {
        CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS)
    }

    private companion object {

        const val VIDEO = "video"

        /** Enough frames that the session is certainly open and delivering. */
        const val FRAMES_WATCHED = 4

        /**
         * The cadence these tests state, in place of [MoqFrameSource.STATISTICS_INTERVAL_MS].
         *
         * The shipped second is argued for a viewer; this is argued for a test, and the two are
         * different questions. Twenty milliseconds is long enough to be a *pace* — an order of
         * magnitude above the scripted frame interval, so a poller that spun is distinguishable —
         * and short enough that a window holding several polls costs a fraction of a second.
         */
        const val STATISTICS_INTERVAL_MS = 20L

        /**
         * How long a claim about *nothing further happening* is watched for.
         *
         * `MoqFrameSourceTest.QUIET_AFTER_CANCEL_MS`'s idiom and its argument: "nothing more
         * arrives" is the absence of an event, so there is nothing to wait on and the only form the
         * assertion has is a window in which a defect would have shown. It is several times
         * [STATISTICS_INTERVAL_MS], so a poller that was still asking would have asked many times
         * over inside it.
         */
        const val QUIET_WINDOW_MS = 200L

        /** How many polls the cadence assertion waits for; enough that a pace is a visible span. */
        const val POLLS_WATCHED = 8

        /**
         * How long [POLLS_WATCHED] polls must take at the least.
         *
         * Nominally seven intervals separate eight polls, and this is **half** of that. The halving
         * is not slack for a slow host — a slow host can only make this longer — but for the one way
         * the figure can come in under nominal: `Object.wait` is permitted to return early, and
         * seven of them each shaving a millisecond would fail an exact bound for no defect. What the
         * bound separates is a paced poller from one that spun, and those differ by the whole span
         * rather than by a few milliseconds, so halving costs the assertion nothing.
         */
        const val PACED_AT_LEAST_MS = (POLLS_WATCHED - 1) * STATISTICS_INTERVAL_MS / 2

        /** Arithmetic, so the elapsed reading is in the same unit as the bound it is compared to. */
        const val NANOS_PER_MILLI = 1_000_000L

        /** How long a first reading is waited for before a test gives up and fails as itself. */
        const val WAIT_BOUND_MS = 10_000L
    }

    /** Keeps enough of what arrived for a statistics test: frames to wait on, and the terminal call. */
    private class RecordingSink : FrameSink {

        private val lock = Any()
        private var delivered = 0
        private var wanted: CountDownLatch = CountDownLatch(0)

        var terminal: String? = null
            private set

        override fun onTracks(tracks: List<RealtimeTrack>) = Unit

        override fun onFrame(frame: EncodedFrame) = synchronized(lock) {
            delivered++
            wanted.countDown()
        }

        override fun onEnded() = end("onEnded")

        override fun onError(cause: Throwable) = end("onError")

        private fun end(which: String) = synchronized(lock) {
            if (terminal == null) terminal = which
            while (wanted.count > 0) wanted.countDown()
        }

        /** Waits for [count] frames, or for the subscription to end before that many arrive. */
        fun awaitFrames(count: Int) {
            synchronized(lock) { wanted = CountDownLatch((count - delivered).coerceAtLeast(0)) }
            assertTrue(
                "waited for $count frames or the end of the subscription",
                wanted.await(WAIT_BOUND_MS, TimeUnit.MILLISECONDS),
            )
        }
    }
}
