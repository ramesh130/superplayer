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
import java.io.ByteArrayOutputStream
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
 * fake, through [HarnessClockWait]: a delay elapses when the test advances time past it and not before. That is what makes
 * "fails at segment 3" fail at segment 3 on every run and every machine — a suite whose faults
 * landed on wall-clock timing would be a flake generator and would get disabled.
 */
internal class FaultInjectingDataSource(
    private val upstream: DataSource,
    private val script: FaultScript,
    private val clock: Clock,
    private val addresses: ResourceAddressBook,
    private val wait: HarnessClockWait,
    private val intermediary: IntermediaryCache,
    private val cacheBypassingRequests: AtomicInteger,
    private val countsTransfers: Boolean,
) : DataSource {

    /** A response the intermediary cache answered, served from memory in place of the upstream. */
    private var cached: IntermediaryCache.Response? = null
    private var cachedReadPosition = 0
    private var cachedUri: Uri? = null

    private var bytesDelivered = 0L
    private var truncateAfterBytes = Long.MAX_VALUE
    private var throughputBps = 0L
    private var deliveryStartedAtMs = 0L
    private var upstreamOpen = false

    /**
     * This transfer's registration with the harness's clock wait.
     *
     * Every transfer registers, not only a delayed one: what the harness does with the count is wait
     * for the loading thread before it advances the clock again, and a transfer that is merely slow
     * to be scheduled is exactly the one the clock would otherwise run away from.
     */
    private val transfer = TransferRegistration(wait, counts = countsTransfers)

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        transfer.opened()
        val address = addresses.addressOf(dataSpec)
        if (IntermediaryCache.asksCachesToStepAside(dataSpec)) cacheBypassingRequests.incrementAndGet()
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
        if (latencyMs > 0) wait.until(clock.elapsedRealtime() + latencyMs, "latency for $address")
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

        // After the faults of the request itself, which happen between the player and the cache
        // just as they happen between the player and an origin.
        val cacheRule = effects.filterIsInstance<Effect.IntermediaryCache>().firstOrNull()
        if (cacheRule != null && dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) {
            val response = intermediary.respond(dataSpec, cacheRule) { fetchWhole(dataSpec) }
            cached = response
            cachedReadPosition = 0
            cachedUri = dataSpec.uri
            return response.body.size.toLong()
        }

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
        val read = cached?.let { readCached(it, buffer, offset, allowed) } ?: upstream.read(buffer, offset, allowed)
        if (read == C.RESULT_END_OF_INPUT) return read
        bytesDelivered += read
        // Paced on the bytes actually read rather than on the bytes asked for, and after the read
        // rather than before it: a caller that offers a 64 KiB buffer and is handed 4 KiB has been
        // sent 4 KiB, and charging it for the buffer would make the cap depend on the reader's
        // buffer size rather than on the link.
        if (throughputBps > 0) {
            wait.until(
                deliveryStartedAtMs + bytesDelivered * BITS_PER_BYTE * MILLIS_PER_SECOND / throughputBps,
                "a throughput cap of $throughputBps bps",
            )
        }
        return read
    }

    override fun getUri(): Uri? = if (cached != null) cachedUri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = cached?.headers ?: upstream.responseHeaders

    override fun close() {
        cached = null
        cachedUri = null
        transfer.closed()
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    private fun readCached(response: IntermediaryCache.Response, buffer: ByteArray, offset: Int, length: Int): Int {
        val remaining = response.body.size - cachedReadPosition
        if (remaining == 0) return C.RESULT_END_OF_INPUT
        val read = min(length, remaining)
        System.arraycopy(response.body, cachedReadPosition, buffer, offset, read)
        cachedReadPosition += read
        return read
    }

    /**
     * The whole of [dataSpec] from the upstream, as the cache fetches it to store: read to the end
     * and closed, so the upstream's transfer callbacks describe a complete fetch.
     */
    private fun fetchWhole(dataSpec: DataSpec): IntermediaryCache.Response {
        upstream.open(dataSpec)
        try {
            val body = ByteArrayOutputStream()
            val chunk = ByteArray(FETCH_CHUNK_BYTES)
            while (true) {
                val read = upstream.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                body.write(chunk, 0, read)
            }
            return IntermediaryCache.Response(body.toByteArray(), upstream.responseHeaders)
        } finally {
            upstream.close()
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
        private val wait: HarnessClockWait = HarnessClockWait(clock),
        /**
         * Whether the sources this makes register their transfers with [wait] as open.
         *
         * On by default, because the injector is usually the outermost wrapper under a player and the
         * count is what stops the harness's clock running ahead of a load. Off where something above
         * it already counts — a [ShapingDataSource] over it — since one load registered by two
         * wrappers can never be seen to catch up: the single thread carrying it waits for one
         * deadline, and the count would ask for two.
         */
        private val countsTransfers: Boolean = true,
    ) : DataSource.Factory {

        /** Shared by every source this makes, so indices count the session rather than the load. */
        val addresses: ResourceAddressBook = ResourceAddressBook()

        /** The one cache between this player and its origin, shared for the same reason. */
        private val intermediary = IntermediaryCache(clock)

        private val bypasses = AtomicInteger()

        /**
         * How many requests so far asked every cache on the way to step aside with
         * `Cache-Control: no-cache`, whether or not a cache was armed to hear it. Nothing in the
         * library reads it; a test does, because the difference between a stream that plays on
         * without asking and one that plays on *because* it asked is otherwise invisible.
         */
        val cacheBypassingRequests: Int get() = bypasses.get()

        /**
         * Whether any load is currently held by an injected delay.
         *
         * Nothing in the library reads it; a test does, to advance the clock at the moment a load is
         * actually waiting on it rather than racing to advance it first. Without that, "how long did
         * the delay take" would be answered by whichever thread got there first.
         */
        val isWaitingOnTheClock: Boolean get() = wait.isWaiting

        override fun createDataSource(): DataSource = wrap(upstream.createDataSource())

        /** For the one caller that already holds a source: Media3's `FakeChunkSource` builds its own. */
        fun wrap(source: DataSource): DataSource =
            FaultInjectingDataSource(source, script, clock, addresses, wait, intermediary, bypasses, countsTransfers)
    }

    private companion object {
        const val FETCH_CHUNK_BYTES = 16 * 1024
        const val BITS_PER_BYTE = 8L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * The shared cache [FaultScript.Builder.serveThroughCache] arms: one store per player, keyed by the
 * whole request URI as an HTTP cache keys it (// spec: RFC 9111 §4.1), aged on the harness's clock.
 */
internal class IntermediaryCache(private val clock: Clock) {

    /** A response as the player receives it: the bytes, and the headers saying where they came from. */
    class Response(val body: ByteArray, val headers: Map<String, List<String>>)

    private class Stored(val response: Response, val storedAtMs: Long)

    private val stored = HashMap<String, Stored>()

    /**
     * Answers [dataSpec] from the store while the stored copy is fresh and the request has not asked
     * past it, and from [fetch] otherwise — storing what it fetched. Serialised, so two loads of one
     * playlist do not both miss and race to fill it; nothing here is fast enough to care.
     */
    @Synchronized
    fun respond(dataSpec: DataSpec, rule: Effect.IntermediaryCache, fetch: () -> Response): Response {
        val key = dataSpec.uri.toString()
        val nowMs = clock.elapsedRealtime()
        val copy = stored[key]
        val ageSeconds = copy?.let { (nowMs - it.storedAtMs) / MILLIS_PER_SECOND }
        val fresh = ageSeconds != null && ageSeconds < rule.maxAgeSeconds
        val stepAside = rule.honoursNoCache && asksCachesToStepAside(dataSpec)
        if (copy != null && fresh && !stepAside) {
            return Response(copy.response.body, copy.response.headers + (AGE to listOf(ageSeconds.toString())))
        }
        val fetched = fetch()
        val served = Response(
            fetched.body,
            fetched.headers + (CACHE_CONTROL to listOf("public, max-age=${rule.maxAgeSeconds}")),
        )
        stored[key] = Stored(served, nowMs)
        return served
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val CACHE_CONTROL = "Cache-Control"
        private const val AGE = "Age"

        /** Whether [dataSpec] carries `Cache-Control: no-cache` (// spec: RFC 9111 §5.2.1.4). */
        fun asksCachesToStepAside(dataSpec: DataSpec): Boolean = dataSpec.httpRequestHeaders.any { (name, value) ->
            CACHE_CONTROL.equals(name, ignoreCase = true) &&
                value.split(',').any { it.trim().equals("no-cache", ignoreCase = true) }
        }
    }
}
