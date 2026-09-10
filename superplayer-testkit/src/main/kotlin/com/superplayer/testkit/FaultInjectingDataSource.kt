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
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.test.utils.FakeAdaptiveDataSet
import androidx.media3.test.utils.FakeChunkSource
import androidx.media3.test.utils.FakeDataSource
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Where a request sits in the stream: what kind of resource it is, and which one of that kind.
 *
 * See [ResourceKind] for why a fault is addressed this way rather than by URL.
 */
internal class ResourceAddress(val kind: ResourceKind, val index: Int) {
    override fun toString(): String = "$kind#$index"
}

/**
 * Hands out a stable [ResourceAddress] for every URI a session fetches.
 *
 * One book per player, not per [DataSource]: Media3 opens a new source per load, and indices that
 * restarted at each one would make "the third segment" mean "the third segment of whichever load
 * this is".
 *
 * **Two requests for the same resource get the same address.** The key is the URI without its query,
 * plus the byte offset asked for, so:
 *
 * - a signed URL whose token changes between a request and its retry is still one segment — which is
 *   the case the token-expiry fault is about, and would otherwise be the case that made every index
 *   after a retry slide by one;
 * - a segment addressed as a byte range of a larger resource is still a segment. `EXT-X-BYTERANGE`
 *   (// spec: RFC 8216 §4.3.2.2) is how a real HLS stream can be packaged that way, and it is how
 *   Media3's own adaptive fake is packaged — one URL per rendition, one range per chunk — so an
 *   address book keyed on the URL alone would find one segment in a session that fetched thirty.
 */
internal class ResourceAddressBook {

    private val assigned = LinkedHashMap<String, ResourceAddress>()
    private val nextIndex = mutableMapOf<ResourceKind, Int>()

    /** Every address handed out, in the order it was first requested. What a test asserts on. */
    val requested: List<ResourceAddress>
        @Synchronized get() = assigned.values.toList()

    @Synchronized
    fun addressOf(dataSpec: DataSpec): ResourceAddress = assigned.getOrPut(key(dataSpec)) {
        val kind = kindOf(dataSpec.uri)
        val index = nextIndex.getOrDefault(kind, 0)
        nextIndex[kind] = index + 1
        ResourceAddress(kind, index)
    }

    private fun key(dataSpec: DataSpec): String {
        val uri = dataSpec.uri.buildUpon().clearQuery().fragment(null).build()
        return "$uri@${dataSpec.position}"
    }

    private companion object {

        /**
         * What kind of resource a URI names, by the naming every adaptive packager uses.
         *
         * The extensions are the protocols' own: `.m3u8` is HLS's playlist
         * (// spec: RFC 8216 §4) and `.mpd` is DASH's manifest (// spec: ISO/IEC 23009-1 §5.3).
         * An initialization segment has no reserved extension in either — it is whatever
         * `EXT-X-MAP` (// spec: RFC 8216 §4.3.2.5) or `<Initialization>`
         * (// spec: ISO/IEC 23009-1 §5.3.9.2) points at — so it is recognised by the name every
         * packager gives it. Anything else carries samples.
         *
         * The heuristic lives here rather than in a test's fixture on purpose: a test names a kind
         * and an index, and nothing above this line knows that a URL was involved at all.
         */
        fun kindOf(uri: Uri): ResourceKind {
            val name = (uri.lastPathSegment ?: uri.toString()).lowercase()
            return when {
                name.endsWith(".m3u8") || name.endsWith(".mpd") -> ResourceKind.MANIFEST
                name.contains("init") -> ResourceKind.INITIALIZATION
                else -> ResourceKind.MEDIA_SEGMENT
            }
        }
    }
}

/**
 * The failures a real network produces, injected on demand and deterministically.
 *
 * A [DataSource] wrapping another one, so it composes over whatever factory the harness installed
 * rather than replacing the mechanism (`docs/testing.md`). What it is *for* is `PRD.md` Part 5's
 * rule that every fallback rung has a test that forces exactly that rung: a rung with no way to
 * force it is a rung nobody knows is broken.
 *
 * **Transparent when nothing is armed.** No fault matching a request means the bytes, the declared
 * length, the response headers and the [TransferListener] callbacks are the upstream's, unchanged.
 * The listener half is not a nicety: measurement is a propagated `TransferListener` (`PRD.md` §2.4),
 * so a wrapper that swallowed one would blind the bandwidth meter and make every ABR test in phase 3
 * quietly meaningless. It is propagated by *registering the listener on the upstream source*, which
 * is the only arrangement where the bytes reported and the bytes upstream actually moved cannot
 * disagree.
 *
 * **Nothing sleeps.** Latency and throughput caps are paced against the harness's [Clock], which is
 * fake: a delay elapses when the test advances time past it and not before. That is what makes
 * "fails at segment 3" fail at segment 3 on every run and every machine — a suite whose faults
 * landed on wall-clock timing would be a flake generator and would get disabled.
 */
internal class FaultInjectingDataSource(
    private val upstream: DataSource,
    private val script: FaultScript,
    private val clock: Clock,
    private val addresses: ResourceAddressBook,
    private val waiting: AtomicInteger,
) : DataSource {

    private var bytesDelivered = 0L
    private var truncateAfterBytes = Long.MAX_VALUE
    private var throughputBps = 0L
    private var deliveryStartedAtMs = 0L
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val address = addresses.addressOf(dataSpec)
        val effects = script.faults.filter { it.matches(address) }.map { it.effect }

        // Order is the order a real request fails in, and it is load-bearing: a name that does not
        // resolve never reaches a socket, so a DNS failure pre-empts everything, and a status code
        // arrives only after the round trip the latency describes.
        effects.filterIsInstance<Effect.DnsFailure>().firstOrNull()?.let {
            throw HttpDataSource.HttpDataSourceException(
                UnknownHostException("Injected DNS failure for ${dataSpec.uri.host} ($address)"),
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        val latencyMs = effects.filterIsInstance<Effect.Latency>().sumOf { it.millis }
        if (latencyMs > 0) awaitClock(clock.elapsedRealtime() + latencyMs, "latency for $address")
        effects.filterIsInstance<Effect.HttpStatus>().firstOrNull()?.let { status ->
            throw HttpDataSource.InvalidResponseCodeException(
                status.code,
                "Injected ${status.code} for $address",
                /* cause= */ null,
                /* headerFields= */ emptyMap(),
                dataSpec,
                /* responseBody= */ ByteArray(0),
            )
        }

        bytesDelivered = 0
        truncateAfterBytes = effects.filterIsInstance<Effect.Truncate>().minOfOrNull { it.afterBytes }
            ?: Long.MAX_VALUE
        throughputBps = effects.filterIsInstance<Effect.ThroughputCap>().minOfOrNull { it.bitsPerSecond } ?: 0
        deliveryStartedAtMs = clock.elapsedRealtime()

        // The upstream's declared length, unmodified even under truncation: a truncated response is
        // one that promised a length and then broke the promise, which is what makes it a different
        // failure from a status code and what the layer above is being tested on.
        val length = upstream.open(dataSpec)
        upstreamOpen = true
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesDelivered >= truncateAfterBytes) return C.RESULT_END_OF_INPUT
        val allowed = min(length.toLong(), truncateAfterBytes - bytesDelivered).toInt()
        val read = upstream.read(buffer, offset, allowed)
        if (read == C.RESULT_END_OF_INPUT) return read
        bytesDelivered += read
        // Paced on the bytes actually read rather than on the bytes asked for, and after the read
        // rather than before it: a caller that offers a 64 KiB buffer and is handed 4 KiB has been
        // sent 4 KiB, and charging it for the buffer would make the cap depend on the reader's
        // buffer size rather than on the link.
        if (throughputBps > 0) {
            awaitClock(
                deliveryStartedAtMs + bytesDelivered * BITS_PER_BYTE * MILLIS_PER_SECOND / throughputBps,
                "a throughput cap of $throughputBps bps",
            )
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    /**
     * Waits until the harness's clock reads [deadlineMs], without sleeping.
     *
     * This runs on a loading thread while the test thread advances the clock, so the wait is on fake
     * time and resolves exactly when the test says it does. [Clock.onThreadBlocked] is what tells an
     * auto-advancing clock that this thread is waiting on it — `superplayer-core`'s harness uses one
     * — and is a no-op under [PlaybackHarness]'s clock, which the test advances by hand.
     *
     * The wall-clock bound is not a timeout in the ordinary sense: it can only be reached when the
     * test never advances time far enough, and a spin that hung there would look like a stuck build
     * rather than like the test bug it is.
     */
    private fun awaitClock(deadlineMs: Long, what: String) {
        val startedAtMs = System.currentTimeMillis()
        waiting.incrementAndGet()
        try {
            spinUntil(deadlineMs, what, startedAtMs)
        } finally {
            waiting.decrementAndGet()
        }
    }

    private fun spinUntil(deadlineMs: Long, what: String, startedAtMs: Long) {
        while (clock.elapsedRealtime() < deadlineMs) {
            clock.onThreadBlocked()
            Thread.yield()
            check(System.currentTimeMillis() - startedAtMs < MAX_WALL_CLOCK_WAIT_MS) {
                "Waited $MAX_WALL_CLOCK_WAIT_MS ms of real time for $what to elapse on the harness " +
                    "clock, which is still at ${clock.elapsedRealtime()} of $deadlineMs. Advance the " +
                    "harness clock past an injected delay, or the load it holds up never completes."
            }
        }
    }

    /**
     * Wraps whatever [DataSource] an upstream factory makes.
     *
     * A factory rather than a bare source because Media3 opens one source per load and the addresses
     * have to be shared across all of them — see [ResourceAddressBook].
     */
    class Factory(
        private val upstream: DataSource.Factory,
        private val script: FaultScript,
        private val clock: Clock,
    ) : DataSource.Factory {

        /** Shared by every source this makes, so indices count the session rather than the load. */
        val addresses: ResourceAddressBook = ResourceAddressBook()

        /**
         * How many loads are currently waiting on the clock for an injected delay to elapse.
         *
         * Nothing in the library reads it; a test does, to advance the clock at the moment a load is
         * actually waiting on it rather than racing to advance it first. Without that, "how long did
         * the delay take" would be answered by whichever thread got there first.
         */
        private val waiting = AtomicInteger()

        /** Whether any load is currently held by an injected delay. */
        val isWaitingOnTheClock: Boolean get() = waiting.get() > 0

        override fun createDataSource(): DataSource = wrap(upstream.createDataSource())

        /** For the one caller that already holds a source: Media3's `FakeChunkSource` builds its own. */
        fun wrap(source: DataSource): DataSource =
            FaultInjectingDataSource(source, script, clock, addresses, waiting)
    }

    private companion object {
        const val BITS_PER_BYTE = 8L
        const val MILLIS_PER_SECOND = 1_000L

        /** Real seconds, and only ever reached when a test forgot to advance the clock. */
        const val MAX_WALL_CLOCK_WAIT_MS = 10_000L
    }
}

/**
 * Media3's own adaptive chunk source, loading through the injector.
 *
 * The composition `docs/testing.md` asks for: whatever the harness installed stays installed and the
 * wrapper goes in front of it. `FakeChunkSource.Factory` builds its own `DataSource` rather than
 * taking one, and it takes a concrete `FakeDataSource.Factory` that no wrapper can be — so the seam
 * is its one overridable method rather than the factory it holds.
 *
 * The [TransferListener] is registered on the **upstream** source, which is where the bytes actually
 * move. A wrapper that intercepted the registration and re-raised the callbacks itself could report
 * a byte count the transfer never made; this arrangement cannot.
 */
internal class FaultInjectingChunkSourceFactory(
    private val dataSets: FakeAdaptiveDataSet.Factory,
    private val dataSources: FakeDataSource.Factory,
    private val injector: FaultInjectingDataSource.Factory,
) : FakeChunkSource.Factory(dataSets, dataSources) {

    override fun createChunkSource(
        trackSelection: ExoTrackSelection,
        durationUs: Long,
        transferListener: TransferListener?,
    ): FakeChunkSource {
        val dataSet = dataSets.createDataSet(trackSelection.trackGroup, durationUs)
        val source = dataSources.setFakeDataSet(dataSet).createDataSource()
        transferListener?.let { source.addTransferListener(it) }
        return FakeChunkSource(trackSelection, injector.wrap(source), dataSet)
    }
}
