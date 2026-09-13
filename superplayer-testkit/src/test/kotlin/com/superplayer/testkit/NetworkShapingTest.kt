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

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

/**
 * The shaper, driven directly rather than through a player: which byte arrives when.
 *
 * Every time asserted here is exact, because of how the clock is driven. The load runs on its own
 * thread, as a real one does, and the test thread advances the clock *to* the deadline the load is
 * waiting for — never past it — so the reading the load takes when it is released is the deadline
 * itself. A test that stepped the clock in fixed amounts could only say a byte arrived within a step.
 *
 * `NetworkShapingPlaybackTest` is where the same shaper is exercised under a real load.
 */
@RunWith(AndroidJUnit4::class)
class NetworkShapingTest {

    @Test
    fun bytesArriveWhenTheTraceSaysTheyDo() {
        val trace = ThroughputTrace.Builder().add(1_000, SLOW_BPS, NetworkTransport.WIFI).build()

        // 512 bytes a second, read a quarter at a time.
        assertThat(session(trace, listOf(SEGMENTS[0])))
            .containsAtLeast("seg0@250:128", "seg0@500:256", "seg0@750:384", "seg0@1000:512").inOrder()
    }

    @Test
    fun aDropoutHoldsTheTransferUntilTheLinkReturns() {
        val trace = ThroughputTrace.Builder()
            .add(500, SLOW_BPS, NetworkTransport.CELLULAR)
            .add(1_000, 0, NetworkTransport.CELLULAR)
            .add(500, SLOW_BPS, NetworkTransport.CELLULAR)
            .build()

        // Held, not failed: the third quarter lands a second late, the length of the dropout.
        assertThat(session(trace, listOf(SEGMENTS[0])))
            .containsAtLeast("seg0@250:128", "seg0@500:256", "seg0@1750:384", "seg0@2000:512").inOrder()
    }

    @Test
    fun aRoundTripIsPaidOnceBeforeTheFirstByte() {
        val trace = ThroughputTrace.Builder().add(1_000, SLOW_BPS, NetworkTransport.WIFI, rttMs = 100).build()

        assertThat(session(trace, listOf(SEGMENTS[0])))
            .containsAtLeast("seg0@100:open", "seg0@350:128", "seg0@600:256", "seg0@850:384", "seg0@1100:512")
            .inOrder()
    }

    @Test
    fun theSameTraceDeliversTheSameBytesAtTheSameTimesOnEveryRun() {
        // A trace with everything in it — a round trip, a dropout, a transport change and a rate
        // change — and a session of a playlist and four segments through it, three times over.
        val trace = ThroughputTrace.Builder()
            .add(700, SLOW_BPS, NetworkTransport.WIFI, rttMs = 50)
            .add(300, 0, NetworkTransport.WIFI)
            .add(1_000, 2 * SLOW_BPS, NetworkTransport.CELLULAR, rttMs = 120)
            .build()
        val urls = listOf(PLAYLIST) + SEGMENTS

        val runs = (1..RUNS).map { session(trace, urls) }

        // Deterministic is a claim about repetition: identical, entry for entry, every run — times,
        // counts, and the checksum of what each body delivered.
        runs.drop(1).forEach { assertThat(it).isEqualTo(runs.first()) }
        // And the bytes are the upstream's: every body arrived whole and unaltered.
        urls.forEach { url -> assertThat(runs.first()).contains("${name(url)}:crc=${crcOf(bodyOf(url))}") }
    }

    @Test
    fun aShapedNetworkAndAFaultScriptAreOneTransferPath() {
        // `PRD.md` wants a shaped network with a 403 at a chosen segment to be one test, not two
        // harnesses. Three faults under one trace: segment 0 truncated, segment 1 refused, segment 2
        // late — each landing on the trace's clock in the order a real request meets them.
        val trace = ThroughputTrace.Builder().add(1_000, SLOW_BPS, NetworkTransport.CELLULAR, rttMs = 100).build()
        val script = FaultScript.Builder()
            .truncateAfterBytes(HALF_BODY, ResourceKind.MEDIA_SEGMENT, index = 0)
            .failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, ResourceKind.MEDIA_SEGMENT, index = 1)
            .addLatencyMs(50, ResourceKind.MEDIA_SEGMENT, index = 2)
            .build()

        assertThat(session(trace, SEGMENTS.take(3), script).filterNot { ":crc=" in it }).containsExactly(
            // The truncated body is paced on the bytes that came, and ends when they stop.
            "seg0@100:open", "seg0@350:128", "seg0@600:256",
            // The 403 is the answer to a request, so it arrives a round trip after it was asked.
            "seg1@700:403",
            // The injected latency is paid after the round trip, and delays every byte behind it.
            "seg2@850:open", "seg2@1100:128", "seg2@1350:256", "seg2@1600:384", "seg2@1850:512",
        ).inOrder()
    }

    @Test
    fun transferListenerReportingSurvivesTheShaper() {
        var bytes = 0L
        val listener = object : TransferListener {
            override fun onTransferInitializing(s: DataSource, d: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(s: DataSource, d: DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(s: DataSource, d: DataSpec, isNetwork: Boolean, count: Int) {
                bytes += count
            }

            override fun onTransferEnd(s: DataSource, d: DataSpec, isNetwork: Boolean) = Unit
        }
        val trace = ThroughputTrace.Builder().add(1_000, SLOW_BPS, NetworkTransport.WIFI).build()

        // Measurement is a propagated `TransferListener` (`PRD.md` §2.4): a shaper that swallowed it
        // would hand phase 3 a bandwidth meter measuring nothing under exactly the traces built for it.
        session(trace, listOf(SEGMENTS[0]), listener = listener)

        assertThat(bytes).isEqualTo(BODY_BYTES.toLong())
    }

    @Test
    fun aCancelledLoadStopsWaitingOnTheClock() {
        val clock = FakeClock(0L, /* isAutoAdvancing= */ false)
        val wait = HarnessClockWait(clock)
        val trace = ThroughputTrace.Builder().add(1_000, SLOW_BPS, NetworkTransport.WIFI).build()
        val source = factory(trace, FaultScript.NONE, clock, wait).createDataSource()
        val failure = AtomicReference<Throwable>()
        val load = Thread {
            runCatching {
                source.open(DataSpec(Uri.parse(SEGMENTS[0])))
                source.read(ByteArray(READ_BYTES), 0, READ_BYTES)
            }.onFailure(failure::set)
        }

        load.start()
        val startedAtMs = System.currentTimeMillis()
        while (!wait.isWaiting) {
            check(load.isAlive && System.currentTimeMillis() - startedAtMs < JOIN_TIMEOUT_MS) {
                "The load never waited on the clock: ${failure.get()}"
            }
            Thread.yield()
        }
        // How Media3's `Loader` cancels a load. Without an answer to it, a released player's loading
        // thread would spin on a clock nobody advances until the wall-clock bound ran out.
        load.interrupt()
        load.join(JOIN_TIMEOUT_MS)

        assertThat(load.isAlive).isFalse()
        assertThat(failure.get()).isInstanceOf(InterruptedIOException::class.java)
    }

    @Test
    fun aHeldLoadActsOnTheClockOnlyOnceReleased() {
        val clock = FakeClock(0L, /* isAutoAdvancing= */ false)
        val wait = HarnessClockWait(clock)
        val failure = AtomicReference<Throwable>()
        val load = Thread {
            runCatching {
                wait.transferOpened()
                try {
                    wait.until(HELD_DEADLINE_MS, "a held deadline")
                } finally {
                    wait.transferClosed()
                }
            }.onFailure(failure::set)
        }

        wait.holdLoads()
        load.start()
        try {
            val startedAtMs = System.currentTimeMillis()
            while (!wait.isWaiting) {
                check(load.isAlive && System.currentTimeMillis() - startedAtMs < JOIN_TIMEOUT_MS) {
                    "The load never waited on the clock: ${failure.get()}"
                }
                Thread.yield()
            }
            // Past the deadline, as moving the clock for the engine's sake does: the open transfer
            // must neither wake nor be counted as behind, or the harness could not settle the engine
            // while it is held.
            clock.advanceTime(HELD_DEADLINE_MS * 2)
            load.join(HELD_GRACE_MS)
            assertThat(load.isAlive).isTrue()
            assertThat(wait.transfersHaveCaughtUp).isTrue()

            wait.releaseLoads()
            load.join(JOIN_TIMEOUT_MS)

            assertThat(load.isAlive).isFalse()
            assertThat(failure.get()).isNull()
        } finally {
            load.interrupt()
        }
    }

    /**
     * Fetches [urls] one after another through the shaper over the injector, as a player's loading
     * thread would, and returns what happened when: `name@ms:bytes` after each read, `name@ms:open`
     * once a request with a round trip or a latency to pay has opened, `name@ms:status` for a
     * refused one, and `name:crc=…` — the checksum of what a delivered body carried — at its end.
     */
    private fun session(
        trace: ThroughputTrace,
        urls: List<String>,
        script: FaultScript = FaultScript.NONE,
        listener: TransferListener? = null,
    ): List<String> {
        val clock = FakeClock(0L, /* isAutoAdvancing= */ false)
        val wait = HarnessClockWait(clock)
        val shaper = factory(trace, script, clock, wait)
        val log = mutableListOf<String>()
        onALoadingThread(clock, wait) {
            urls.forEach { url ->
                val source = shaper.createDataSource()
                listener?.let(source::addTransferListener)
                val openedAt = clock.elapsedRealtime()
                try {
                    source.open(DataSpec(Uri.parse(url)))
                    if (clock.elapsedRealtime() != openedAt) log += "${name(url)}@${clock.elapsedRealtime()}:open"
                    val buffer = ByteArray(READ_BYTES)
                    val crc = CRC32()
                    var total = 0
                    while (true) {
                        val read = source.read(buffer, 0, READ_BYTES)
                        if (read == C.RESULT_END_OF_INPUT) break
                        crc.update(buffer, 0, read)
                        total += read
                        log += "${name(url)}@${clock.elapsedRealtime()}:$total"
                    }
                    log += "${name(url)}:crc=${crc.value}"
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    log += "${name(url)}@${clock.elapsedRealtime()}:${e.responseCode}"
                } finally {
                    source.close()
                }
            }
        }
        return log
    }

    private fun factory(trace: ThroughputTrace, script: FaultScript, clock: FakeClock, wait: HarnessClockWait) =
        ShapingDataSource.Factory(
            FaultInjectingDataSource.Factory(FakeDataSource.Factory().setFakeDataSet(dataSet()), script, clock, wait),
            trace,
            clock,
            wait,
        )

    /**
     * Runs [body] on a loading thread, advancing [clock] to each deadline it waits for.
     *
     * Only ever *to* a deadline a load is waiting on, and only once that load has reported it: the
     * load registers its deadline before it waits and removes it when released, so the clock cannot
     * be moved past a time the load has not yet seen. Bounded in wall-clock time so a load that
     * never finishes fails rather than hangs.
     */
    private fun onALoadingThread(clock: FakeClock, wait: HarnessClockWait, body: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        val thread = Thread { runCatching(body).onFailure(failure::set) }
        val startedAtMs = System.currentTimeMillis()
        thread.start()
        while (thread.isAlive) {
            val deadline = wait.earliestDeadlineMs
            val now = clock.elapsedRealtime()
            if (deadline != null && deadline > now) clock.advanceTime(deadline - now) else Thread.yield()
            check(System.currentTimeMillis() - startedAtMs < JOIN_TIMEOUT_MS) { "The session never finished" }
        }
        failure.get()?.let { throw it }
    }

    private fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun name(url: String): String = url.substringAfterLast('/').substringBefore('.')

    private fun dataSet(): FakeDataSet = FakeDataSet().apply {
        (listOf(PLAYLIST) + SEGMENTS).forEach { url -> setData(url, bodyOf(url)) }
    }

    private fun bodyOf(url: String): ByteArray = ByteArray(BODY_BYTES) { (url.hashCode() + it).toByte() }

    private companion object {
        const val PLAYLIST = "https://cdn.test/hls/media.m3u8"
        val SEGMENTS = (0..3).map { "https://cdn.test/hls/seg$it.aac" }

        const val BODY_BYTES = 512
        const val HALF_BODY = 256L

        /** A read's worth: a quarter of a body, so every body is four arrivals. */
        const val READ_BYTES = 128

        /** 4 096 bit/s, which is 512 bytes a second: one read's worth every 250 ms. */
        const val SLOW_BPS = 4_096L

        const val RUNS = 3
        const val JOIN_TIMEOUT_MS = 10_000L

        /** A held load's deadline on the fake clock. */
        const val HELD_DEADLINE_MS = 100L

        /** Real time a held load is given to wake wrongly; long beside a yield loop, short beside a build. */
        const val HELD_GRACE_MS = 200L
    }
}
