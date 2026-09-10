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

/**
 * What is being fetched, as the thing a fault is addressed to.
 *
 * The addressing is the whole design problem this type exists for. "Fail the third segment" has to
 * be expressible without the test naming a URL, because the URL is a detail of whichever synthetic
 * stream the test happened to use — and a fault plan written against URLs is a fault plan that has
 * to be rewritten when the same scenario is run against the other protocol. A resource kind plus an
 * index within that kind is the same sentence under HLS and under DASH, which is what lets one
 * script mean one thing across both.
 *
 * The three kinds are the three that every adaptive protocol has and that every layer above the
 * transfer treats differently: a manifest failure is a session that never starts, an initialization
 * failure is a rendition that can never be decoded, and a media segment failure is a hole in
 * playback.
 */
public enum class ResourceKind {

    /** A multivariant or media playlist (HLS) or an MPD (DASH). */
    MANIFEST,

    /** An initialization segment: HLS's `EXT-X-MAP` target, DASH's `<Initialization>`. */
    INITIALIZATION,

    /** A segment carrying media samples — the kind a token expiry is measured in. */
    MEDIA_SEGMENT,
}

/**
 * The faults a test wants injected, declared up front as data.
 *
 * A script rather than a callback, deliberately: a fault plan stated next to the assertions it
 * explains can be read in one glance, while a lambda mutating shared state mid-run can only be
 * understood by simulating the run. It is also what makes a plan re-runnable — the same script
 * against HLS and against DASH is the same test.
 *
 * ```kotlin
 * val player = harness.buildPlayer(
 *     content = TestContent.videoLadder(),
 *     faults = FaultScript.Builder().expireTokenAtSegment(3).build(),
 * )
 * ```
 *
 * **Indices count distinct resources of one kind, in the order they are first requested**, from
 * zero. A resource fetched twice — a media playlist reloaded on a live stream, a segment retried
 * after a failure — keeps the index it was given, so "the third segment" stays the third segment
 * however many times the player asks for it.
 *
 * **No Media3 type appears here**, for the reason `docs/testing.md` gives about this module's public
 * API: naming an `@UnstableApi` type in a signature would put Media3's opt-in marker on every test
 * that wrote a fault plan. `DataSource` is such a type, which is why the injector this describes is
 * internal and reached through [PlaybackHarness] rather than handed out.
 */
public class FaultScript private constructor(internal val faults: List<Fault>) {

    internal fun isEmpty(): Boolean = faults.isEmpty()

    /**
     * Collects the faults, one call per fault, in any order.
     *
     * Every method takes the same optional address: a [ResourceKind] and an index within it. Leaving
     * [kind] unset addresses *every* request, which is what a network-wide condition — latency, a
     * throughput cap — usually means; leaving [index] unset addresses every resource of that kind.
     */
    public class Builder {

        private val faults = mutableListOf<Fault>()

        /**
         * Delays the response by [millis] of the harness's clock before a byte is delivered.
         *
         * Charged once per request, at open, which is where a real connection's setup cost lands.
         * It is fake time rather than wall-clock time: nothing sleeps, and the delay elapses when
         * the test advances the clock past it — see `PlaybackHarness.advanceTimeMs`.
         */
        public fun addLatencyMs(
            millis: Long,
            kind: ResourceKind? = null,
            index: Int? = null,
        ): Builder = add(kind, index, Effect.Latency(millis.requireAtLeast(0, "Latency")))

        /**
         * Caps delivery at [bitsPerSecond], paced against the harness's clock.
         *
         * A constant cap. Replaying a recorded throughput profile is `#40`'s subject, and the shape
         * of this call is what that one will extend.
         */
        public fun capThroughputBps(
            bitsPerSecond: Long,
            kind: ResourceKind? = null,
            index: Int? = null,
        ): Builder = add(kind, index, Effect.ThroughputCap(bitsPerSecond.requireAtLeast(1, "A throughput cap")))

        /**
         * Answers with HTTP [status] instead of the resource.
         *
         * The response is a real `HttpDataSource.InvalidResponseCodeException`, which is what a real
         * HTTP stack throws — so the load error handling policy above it classifies the failure the
         * way it would in production rather than seeing an exception type only tests produce.
         */
        public fun failWithHttpStatus(
            status: Int,
            kind: ResourceKind? = null,
            index: Int? = null,
        ): Builder {
            require(status in 400..599) { "An injected HTTP failure status is 4xx or 5xx, not $status" }
            return add(kind, index, Effect.HttpStatus(status))
        }

        /**
         * Ends the body after [bytes] while still declaring the full length.
         *
         * A different failure from a status code, and treated differently by every layer above: the
         * request succeeded, the length was promised, and the promise was broken. That is what makes
         * it worth injecting separately — an extractor sees a truncated container rather than a
         * failed fetch.
         */
        public fun truncateAfterBytes(
            bytes: Long,
            kind: ResourceKind? = null,
            index: Int? = null,
        ): Builder = add(kind, index, Effect.Truncate(bytes.requireAtLeast(0, "A truncation point")))

        /**
         * Fails to resolve the host, before any connection is attempted.
         *
         * An `UnknownHostException` inside an `HttpDataSourceException`, which is exactly the shape a
         * real DNS failure reaches Media3 in — and it is synthesized rather than provoked, because
         * `docs/testing.md` bars the network from these tests and a fault injector that needed a
         * real resolver to inject a resolver failure would have misunderstood the assignment.
         */
        public fun failDnsResolution(
            kind: ResourceKind? = null,
            index: Int? = null,
        ): Builder = add(kind, index, Effect.DnsFailure)

        /**
         * Expires the session's token from media segment [index] onward: 403 from there to the end.
         *
         * The fault that matters most, because it is the one `superplayer-resilience` is being built
         * for and the one almost no app handles. It is not a single failed request — a token that
         * has expired stays expired, so every later segment fails too, and a ladder that retried the
         * one segment would still be stuck. That "and every one after it" is why this is its own
         * call rather than a [failWithHttpStatus] a test could write by hand and get subtly wrong.
         */
        public fun expireTokenAtSegment(index: Int): Builder {
            index.requireAtLeast(0, "A segment index")
            faults += Fault(
                ResourceKind.MEDIA_SEGMENT,
                index..Int.MAX_VALUE,
                Effect.HttpStatus(HTTP_FORBIDDEN),
            )
            return this
        }

        public fun build(): FaultScript = FaultScript(faults.toList())

        private fun add(kind: ResourceKind?, index: Int?, effect: Effect): Builder {
            index?.requireAtLeast(0, "A resource index")
            // An unnamed index means every resource of the kind, which is a range and reads as one.
            faults += Fault(kind, index?.let { it..it } ?: EVERY_INDEX, effect)
            return this
        }

        private fun Long.requireAtLeast(minimum: Long, what: String): Long {
            require(this >= minimum) { "$what is at least $minimum, not $this" }
            return this
        }

        private fun Int.requireAtLeast(minimum: Int, what: String): Int {
            require(this >= minimum) { "$what is at least $minimum, not $this" }
            return this
        }
    }

    public companion object {

        /** No faults at all: the injector is then transparent, which is its other tested claim. */
        @JvmField
        public val NONE: FaultScript = FaultScript(emptyList())

        /** The status a CDN answers an expired token with, and what [Builder.expireTokenAtSegment] sends. */
        public const val HTTP_FORBIDDEN: Int = 403

        /** The status a CDN answers a missing resource with — a mistyped or rolled-off segment. */
        public const val HTTP_NOT_FOUND: Int = 404

        /** The status a CDN answers its own trouble with, which retries treat differently from a 404. */
        public const val HTTP_SERVER_ERROR: Int = 500

        /** What an unnamed index addresses: every resource of the kind. */
        private val EVERY_INDEX = 0..Int.MAX_VALUE
    }
}

/**
 * One fault: what it does, and which resources it does it to.
 *
 * [indices] is a range rather than a single index because a token expiry addresses a segment *and
 * every one after it* — the one addressing shape that cannot be written as a single index, and the
 * reason this is a range at all. A null [kind] is every kind.
 */
internal class Fault(
    val kind: ResourceKind?,
    val indices: IntRange,
    val effect: Effect,
) {
    fun matches(address: ResourceAddress): Boolean =
        (kind == null || kind == address.kind) && address.index in indices
}

/** What a matched [Fault] does to the transfer. */
internal sealed interface Effect {
    class Latency(val millis: Long) : Effect
    class ThroughputCap(val bitsPerSecond: Long) : Effect
    class HttpStatus(val code: Int) : Effect
    class Truncate(val afterBytes: Long) : Effect
    object DnsFailure : Effect
}
