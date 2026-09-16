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
 * **Attempts are the third coordinate, and they are what lets a fault relent.** A fault addressed at
 * a kind and an index applies to every attempt at that resource by default, which is why a retry
 * meets the same fault a second time. Naming `firstAttempts` bounds it: the resource fails that many
 * times and then succeeds, which is the only way to write down "and the retry recovered". Attempts
 * are counted per resource on the addressing above, from one, so nothing about which resource a
 * fault addresses changes.
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
     * Every method takes the same optional address: a [ResourceKind], an index within it, and how
     * many attempts at each addressed resource the fault applies to. Leaving `kind` unset addresses
     * *every* request, which is what a network-wide condition — latency, a throughput cap — usually
     * means; leaving `index` unset addresses every resource of that kind; leaving `firstAttempts`
     * unset addresses every attempt, so the fault never relents.
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
            firstAttempts: Int? = null,
        ): Builder = add(kind, index, firstAttempts, Effect.Latency(millis.requireAtLeast(0, "Latency")))

        /**
         * Caps delivery at [bitsPerSecond], paced against the harness's clock.
         *
         * A constant cap on the addressed resources. A network that varies over time — a recorded
         * trace, or one of `PRD.md`'s profiles — is a [ThroughputTrace], replayed by
         * `PlaybackHarness.buildPlayer(network = …)`; the two compose, and the slower governs.
         */
        public fun capThroughputBps(
            bitsPerSecond: Long,
            kind: ResourceKind? = null,
            index: Int? = null,
            firstAttempts: Int? = null,
        ): Builder = add(
            kind,
            index,
            firstAttempts,
            Effect.ThroughputCap(bitsPerSecond.requireAtLeast(1, "A throughput cap")),
        )

        /**
         * Answers with HTTP [status] instead of the resource.
         *
         * The response is a real `HttpDataSource.InvalidResponseCodeException`, which is what a real
         * HTTP stack throws — so the load error handling policy above it classifies the failure the
         * way it would in production rather than seeing an exception type only tests produce.
         *
         * `firstAttempts` is how "a 500 that the retry recovers from" is written: the resource
         * answers that status that many times and is then served.
         */
        public fun failWithHttpStatus(
            status: Int,
            kind: ResourceKind? = null,
            index: Int? = null,
            firstAttempts: Int? = null,
        ): Builder {
            require(status in 400..599) { "An injected HTTP failure status is 4xx or 5xx, not $status" }
            return add(kind, index, firstAttempts, Effect.HttpStatus(status))
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
            firstAttempts: Int? = null,
        ): Builder = add(kind, index, firstAttempts, Effect.Truncate(bytes.requireAtLeast(0, "A truncation point")))

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
            firstAttempts: Int? = null,
        ): Builder = add(kind, index, firstAttempts, Effect.DnsFailure)

        /**
         * Fails the TLS handshake, after the name resolved and the socket connected.
         *
         * An `SSLHandshakeException` inside an `HttpDataSourceException`, which is the shape a real
         * certificate that does not verify — an expired one, a hostname that does not match, a CDN
         * edge misconfigured — reaches Media3 in. Worth injecting apart from [failDnsResolution] and
         * [resetConnection] because it is the one transport failure a different *host* usually does
         * not fix and a different *base URL* often does, so it is the rung the ladder takes next
         * that differs.
         */
        public fun failTlsHandshake(
            kind: ResourceKind? = null,
            index: Int? = null,
            firstAttempts: Int? = null,
        ): Builder = add(kind, index, firstAttempts, Effect.TlsFailure)

        /**
         * Drops the connection: a `SocketException` at open, the way a peer's RST arrives.
         *
         * Distinct from [addLatencyMs] and from [failWithHttpStatus] alike, and that is the point of
         * having it: nothing was answered, so there is no status to classify, and nothing is slow,
         * so no amount of waiting helps. It is the commonest transient network failure a phone sees
         * and the one a retry most often does recover from — which is why it and `firstAttempts`
         * arrive in the same change.
         */
        public fun resetConnection(
            kind: ResourceKind? = null,
            index: Int? = null,
            firstAttempts: Int? = null,
        ): Builder = add(kind, index, firstAttempts, Effect.ConnectionReset)

        /**
         * Times the connection out: a `SocketTimeoutException` at open.
         *
         * The failure, not the wait. An injected latency models how long a request takes; this
         * models the stack giving up on one, which is a different thing to classify — Media3 gives
         * it its own error code — and a test that wants both writes both, since they compose.
         */
        public fun timeOutConnection(
            kind: ResourceKind? = null,
            index: Int? = null,
            firstAttempts: Int? = null,
        ): Builder = add(kind, index, firstAttempts, Effect.ConnectionTimeout)

        /**
         * Expires the session's token from media segment [index] onward: 403 from there to the end.
         *
         * The fault that matters most, because it is the one `superplayer-resilience` is being built
         * for and the one almost no app handles. It is not a single failed request — a token that
         * has expired stays expired, so every later segment fails too, and a ladder that retried the
         * one segment would still be stuck. That "and every one after it" is why this is its own
         * call rather than a [failWithHttpStatus] a test could write by hand and get subtly wrong.
         *
         * [refreshable] is the other half of that story, and the half a rung above "it failed" needs:
         * a token that has expired can be *replaced*, and a session that replaces it plays on. Set
         * it, and the 403s stop the moment a request arrives bearing a different credential from the
         * one that first met the fault — its `Authorization` header if it has one, otherwise its
         * query string, which is where a signed URL carries its signature. Nothing here says how the
         * credential was obtained: whatever refreshed it, the CDN's answer is that the new one works
         * and the old one does not, and that is all this reproduces. Left unset, the token cannot be
         * refreshed at all, which is today's behaviour and still the right fault for "the ladder
         * cannot save this session".
         */
        public fun expireTokenAtSegment(index: Int, refreshable: Boolean = false): Builder {
            index.requireAtLeast(0, "A segment index")
            faults += Fault(
                ResourceKind.MEDIA_SEGMENT,
                index..Int.MAX_VALUE,
                EVERY_ATTEMPT,
                if (refreshable) Effect.ExpiredToken else Effect.HttpStatus(HTTP_FORBIDDEN),
            )
            return this
        }

        /**
         * Puts a shared cache between the player and the origin for the addressed resources, holding
         * each response for [maxAgeSeconds] of the harness's clock and serving it with the headers
         * that say so.
         *
         * Not a failure of any single request: every response is a well-formed one. What it
         * reproduces is a *cache rule* — a CDN told to keep something for longer than it stays
         * true. Addressed at [ResourceKind.MANIFEST] on live content it is
         * `HostileManifests.hlsCachedLivePlaylist`'s cause, served for real: the origin carries on
         * publishing and the player keeps being handed the copy the cache took first.
         *
         * spec: RFC 9111 — a stored response is served while its age is below its `max-age`
         * (§4.2), with an `Age` header saying how old it is (§5.1); the response that fills the
         * store goes out as `Cache-Control: public, max-age=…` (§5.2.2.9, §5.2.2.1). A request
         * carrying `Cache-Control: no-cache` goes to the origin, and the answer replaces the stored
         * copy (§5.2.1.4). That request directive states only that "the client prefers" a stored
         * response not be used, and a CDN configured to disregard client directives is common:
         * [honoursNoCache] set to false is that cache, and it conforms too.
         *
         * Whole responses only — a byte range passes through uncached — which is all a playlist is.
         * A hit is answered from memory and moves no bytes through the upstream, so it reports no
         * transfer to a bandwidth meter; address it at manifests, not at the segments an ABR test
         * measures.
         */
        public fun serveThroughCache(
            maxAgeSeconds: Long,
            honoursNoCache: Boolean = true,
            kind: ResourceKind? = null,
            index: Int? = null,
        ): Builder = add(
            kind,
            index,
            firstAttempts = null,
            Effect.IntermediaryCache(maxAgeSeconds.requireAtLeast(0, "A cache lifetime"), honoursNoCache),
        )

        public fun build(): FaultScript = FaultScript(faults.toList())

        private fun add(kind: ResourceKind?, index: Int?, firstAttempts: Int?, effect: Effect): Builder {
            index?.requireAtLeast(0, "A resource index")
            firstAttempts?.requireAtLeast(1, "A count of attempts")
            // An unnamed index means every resource of the kind, and an unnamed attempt count every
            // attempt at it. Both are ranges, and both read as one.
            faults += Fault(
                kind,
                index?.let { it..it } ?: EVERY_INDEX,
                firstAttempts?.let { FIRST_ATTEMPT..it } ?: EVERY_ATTEMPT,
                effect,
            )
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

        /** Attempts are counted from one: the first fetch of a resource is its first attempt. */
        internal const val FIRST_ATTEMPT = 1

        /** What an unnamed attempt count addresses: every attempt, so the fault never relents. */
        internal val EVERY_ATTEMPT = FIRST_ATTEMPT..Int.MAX_VALUE
    }
}

/**
 * One fault: what it does, and which resources it does it to.
 *
 * [indices] is a range rather than a single index because a token expiry addresses a segment *and
 * every one after it* — the one addressing shape that cannot be written as a single index, and the
 * reason this is a range at all. [attempts] is a range for the mirror-image reason: a fault that
 * relents applies to the first few attempts at a resource and not to the rest. A null [kind] is
 * every kind.
 */
internal class Fault(
    val kind: ResourceKind?,
    val indices: IntRange,
    val attempts: IntRange,
    val effect: Effect,
) {
    fun matches(attempt: ResourceAttempt): Boolean =
        (kind == null || kind == attempt.address.kind) &&
            attempt.address.index in indices &&
            attempt.number in attempts
}

/** What a matched [Fault] does to the transfer. */
internal sealed interface Effect {
    class Latency(val millis: Long) : Effect
    class ThroughputCap(val bitsPerSecond: Long) : Effect
    class HttpStatus(val code: Int) : Effect
    class Truncate(val afterBytes: Long) : Effect
    object DnsFailure : Effect
    object TlsFailure : Effect
    object ConnectionReset : Effect
    object ConnectionTimeout : Effect

    /** A 403 that lasts until the request's credential changes — see `expireTokenAtSegment`. */
    object ExpiredToken : Effect
    class IntermediaryCache(val maxAgeSeconds: Long, val honoursNoCache: Boolean) : Effect
}
