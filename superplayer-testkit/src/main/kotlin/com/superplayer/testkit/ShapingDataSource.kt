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
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Delivers an upstream's bytes when a [ThroughputTrace] says they arrive, on the harness's clock.
 *
 * A request opened at trace time `t` waits the trace's round-trip time at `t` before its first byte —
 * the request going out and the response coming back, charged once, at open, as a real connection's
 * setup cost lands; a TCP or TLS handshake is not modelled separately. Its bytes then arrive at the
 * rate the trace allows from that moment on, through every stretch the transfer spans, including a
 * stretch at zero bandwidth, which holds the transfer rather than failing it.
 *
 * **Deterministic by construction.** The time each byte arrives is computed from the trace, the
 * moment the request was opened, and the bytes delivered so far ([ThroughputTrace.arrivalMs]) —
 * never from how long the previous wait happened to take — so two runs that open the same requests
 * at the same times deliver the same bytes at the same times.
 *
 * **Each transfer sees the whole link.** Two concurrent transfers are paced independently, each at
 * the trace's full rate, rather than sharing it. Sharing would make each one's timing depend on the
 * order two real threads reached the link in, which is exactly the nondeterminism `docs/testing.md`
 * bars; the harness's content loads one transfer at a time, so for it the question does not arise.
 * `docs/throughput-traces.md` records the limitation.
 *
 * **It composes with the fault injector rather than replacing it**, by sitting in front of it: the
 * round trip is paid before the injector's status code is thrown, because a 403 arrives after one,
 * and a truncated body is paced on the bytes that actually came. The [TransferListener] is
 * registered on the upstream, for the reason [FaultInjectingDataSource] gives.
 */
internal class ShapingDataSource(
    private val upstream: DataSource,
    private val trace: ThroughputTrace,
    private val originMs: Long,
    private val clock: Clock,
    private val wait: HarnessClockWait,
) : DataSource {

    /** Trace time at which this transfer's first byte could arrive. */
    private var deliveryStartMs = 0L
    private var bytesDelivered = 0L
    private var upstreamOpened = false
    private var transferOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        transferOpen = true
        wait.transferOpened()
        val openedAtMs = clock.elapsedRealtime()
        val rttMs = trace.rttMsAt(openedAtMs - originMs)
        val firstByteAtMs = openedAtMs + rttMs
        wait.until(firstByteAtMs, "a round trip of $rttMs ms to ${dataSpec.uri}")

        upstreamOpened = true
        val beforeUpstreamMs = clock.elapsedRealtime()
        val length = upstream.open(dataSpec)
        // Whatever the upstream itself held the open for — an injected latency — delays the first
        // byte by that much. Added as a duration rather than read as a time, so a transfer's
        // schedule stays anchored to when it was opened.
        deliveryStartMs = firstByteAtMs + (clock.elapsedRealtime() - beforeUpstreamMs) - originMs
        bytesDelivered = 0
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) return read
        bytesDelivered += read
        // Paced after the read and on the bytes actually read, for the reason the fault injector's
        // throughput cap gives: charging for the buffer offered would make the rate depend on the
        // reader's buffer size rather than on the trace.
        wait.until(
            originMs + trace.arrivalMs(deliveryStartMs, bytesDelivered),
            "$bytesDelivered bytes of ${upstream.uri} to arrive on the trace",
        )
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (transferOpen) {
            transferOpen = false
            wait.transferClosed()
        }
        // `DataSource.close` is owed even when `open` threw, which is how an injected status code
        // leaves the upstream.
        if (upstreamOpened) {
            upstreamOpened = false
            upstream.close()
        }
    }

    /**
     * Shapes whatever [DataSource] an upstream factory makes, against one trace and one origin.
     *
     * A factory for the reason [FaultInjectingDataSource.Factory] is one: Media3 opens a source per
     * load, and every load of a session has to be replayed against the same trace from the same
     * time zero — the moment this was built.
     */
    class Factory(
        private val upstream: DataSource.Factory,
        private val trace: ThroughputTrace,
        private val clock: Clock,
        private val wait: HarnessClockWait = HarnessClockWait(clock),
    ) : DataSource.Factory {

        private val originMs = clock.elapsedRealtime()

        override fun createDataSource(): DataSource = wrap(upstream.createDataSource())

        /** For the one caller that already holds a source: Media3's `FakeChunkSource` builds its own. */
        fun wrap(source: DataSource): DataSource = ShapingDataSource(source, trace, originMs, clock, wait)
    }
}
