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
import com.superplayer.core.HttpMethod
import com.superplayer.core.HttpRange
import com.superplayer.core.HttpRequest
import com.superplayer.core.HttpResponse
import com.superplayer.core.HttpTransport
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Runs an [HttpTransport] against the obligations ADR-0016 puts on one, and says which it broke.
 *
 * ```kotlin
 * @Test
 * fun `our transport satisfies SuperPlayer's contract`() {
 *     HttpTransportConformance(OkHttpTransport(app.okHttpClient)).verifyAll()
 * }
 * ```
 *
 * ## Why this is a test you run rather than a paragraph you read
 *
 * [HttpTransport]'s obligations are on code this library cannot see. An interface whose obligations
 * live only in its KDoc is an interface that gets implemented wrongly **once per adopter**, and
 * every one of these five is wrong *silently*: a client that gzips on its own behalf produces a
 * bandwidth estimate nobody can explain, one that drops a byte range produces what reads as a
 * corrupt stream, one that raises on a 403 produces sessions that end unclassified, one that
 * reports the address it asked for rather than the one it was redirected to sends a player looking
 * for its segments at the old edge, and one whose stream will not abandon a transfer holds a loader
 * thread across a seek and a player across a release. None of the five throws, logs, or shows up as
 * an error on a device.
 *
 * This repository has made the same bet twice already — `docs/testing.md`'s argument for the golden
 * traces and ADR-0011's for `FallbackRungCoverageTest` are both that a rule nothing executes is a
 * rule that goes stale. Here it is worse than stale: this class is the only form in which the
 * obligations can travel to the person who has to satisfy them (ADR-0016 rule 14).
 *
 * ## What you supply, and what this supplies
 *
 * **You supply the transport and nothing else.** The origin is this class's: an HTTP/1.1 server on
 * the loopback interface, started per check and shut down after it, which honours byte ranges,
 * redirects, offers gzip to a client that asks for it, refuses one address with a 403 and stalls
 * another. Pointing a consumer at "an origin you control" was the alternative and it is a worse
 * one twice over — it asks every adopter to stand up a server that misbehaves in five specific
 * ways before they can check anything, and what it would then measure is that server as much as
 * their client. What the checks read is therefore **both** ends of each exchange: what your
 * transport put on the wire and what it handed back.
 *
 * The consequence to know before you call it: your transport is handed `http://127.0.0.1:<port>/…`
 * URIs. A client pinned to one host, or refusing plaintext outright, is one to relax for this test
 * — as it would be for any test of it that is not a test of your CDN.
 *
 * ## What a failure is
 *
 * [HttpTransportConformanceException], an `AssertionError`, so that every test framework renders it
 * as a failed assertion rather than as an unexpected error — and no test framework is named here,
 * because `superplayer-testkit` declares none: the message is the deliverable, and it is carried by
 * a type rather than by JUnit so that a consumer on JUnit 5, Kotest, or an instrumentation runner
 * runs the same checks as a consumer on JUnit 4.
 *
 * Each message names the rule, says **what this transport did** and says **what the rule requires**,
 * in that order, because the reader of it is not the author of this code and has no copy of this
 * repository.
 */
public class HttpTransportConformance(private val transport: HttpTransport) {

    /**
     * Every obligation, in the order ADR-0016 numbers them, stopping at the first one broken.
     *
     * Stopping rather than collecting: the checks are independent of one another, but a transport
     * that fails one has a defect to fix before the rest of the report means anything, and a wall
     * of five failures is a worse first thing to read than one.
     */
    public fun verifyAll() {
        verifyByteRangeIsHonoured()
        verifyRedirectedUriIsReported()
        verifyNoContentCodingIsAdded()
        verifyStatusIsReportedRatherThanRaised()
        verifyCancelledRequestReturns()
    }

    /**
     * **ADR-0016 rule 5** — a request carrying an [HttpRange] is composed as a `Range` header, and
     * the 206 the origin answers comes back with the bytes that were asked for.
     * // spec: RFC 9110 §14.2.
     */
    public fun verifyByteRangeIsHonoured(): Unit = withOrigin { origin ->
        val range = HttpRange(ConformanceOrigin.RANGE_OFFSET, ConformanceOrigin.RANGE_LENGTH)
        val response = open(origin, ConformanceOrigin.RESOURCE_PATH, range = range)
        val body = drain(response, RULE_5)
        val sent = origin.receivedFor(ConformanceOrigin.RESOURCE_PATH).lastOrNull()?.header("Range")
        if (sent != range.headerValue()) {
            refuse(
                RULE_5,
                "sent `Range: ${sent ?: "<no Range header at all>"}` for a request whose " +
                    "HttpRequest.range asked for ${range.headerValue()}",
                "the range on the request composed as a `Range` header, exactly as " +
                    "HttpRange.headerValue() spells it",
            )
        }
        if (response.status != ConformanceOrigin.PARTIAL_CONTENT) {
            refuse(
                RULE_5,
                "answered ${response.status} with ${body.size} bytes for a request for " +
                    "${range.headerValue()}",
                "the ${ConformanceOrigin.PARTIAL_CONTENT} the origin answered, reported as it " +
                    "stands; core refuses a request it asked to start past byte zero and that came " +
                    "back ${ConformanceOrigin.OK}, rather than reading a whole resource as a slice",
            )
        }
        if (response.header(CONTENT_RANGE) == null) {
            refuse(
                RULE_5,
                "answered ${ConformanceOrigin.PARTIAL_CONTENT} with no `$CONTENT_RANGE` among its " +
                    "response headers",
                "the response headers the origin sent, `$CONTENT_RANGE` included",
            )
        }
        val wantedLength = ConformanceOrigin.RANGE_LENGTH
        val expected = ConformanceOrigin.body.copyOfRange(
            range.offset.toInt(),
            (range.offset + wantedLength).toInt(),
        )
        if (!body.contentEquals(expected)) {
            refuse(
                RULE_5,
                "answered ${body.size} bytes that are not the ${expected.size} asked for",
                "the bytes of the range and no others; every read after the first lands at the " +
                    "wrong offset otherwise, which a viewer sees as content that will not start",
            )
        }
    }

    /**
     * **ADR-0016 rule 6** — a redirect is followed and the address it ended at is reported on
     * [HttpResponse.uri], because Media3 resolves a manifest's relative references against it.
     */
    public fun verifyRedirectedUriIsReported(): Unit = withOrigin { origin ->
        val requested = origin.baseUri + ConformanceOrigin.REDIRECT_PATH
        val response = open(origin, ConformanceOrigin.REDIRECT_PATH)
        val body = drain(response, RULE_6)
        if (response.status != ConformanceOrigin.OK || !body.contentEquals(ConformanceOrigin.body)) {
            refuse(
                RULE_6,
                "answered ${response.status} with ${body.size} bytes for an address the origin " +
                    "redirected to another one",
                "the redirect followed, as Media3's own stacks follow one, and the target's " +
                    "response answered",
            )
        }
        val reported = response.uri?.toString() ?: requested
        if (reported != origin.baseUri + ConformanceOrigin.RESOURCE_PATH) {
            refuse(
                RULE_6,
                "reported `$reported` after following a redirect to " +
                    "`${origin.baseUri}${ConformanceOrigin.RESOURCE_PATH}`",
                "the URI the bytes were actually read from on HttpResponse.uri; a player resolves " +
                    "a manifest's relative references against it, so a transport that answers the " +
                    "address it was asked for sends the player to the old edge for every playlist " +
                    "and every segment",
            )
        }
    }

    /**
     * **ADR-0016 rule 7** — the request headers are sent as given and none of the transport's own
     * are added, in particular no content coding the client negotiated for itself.
     * // spec: RFC 9110 §12.5.3 (`Accept-Encoding`), §8.4 (`Content-Encoding`).
     */
    public fun verifyNoContentCodingIsAdded(): Unit = withOrigin { origin ->
        val response = open(origin, ConformanceOrigin.RESOURCE_PATH)
        val body = drain(response, RULE_7)
        val asked = origin.receivedFor(ConformanceOrigin.RESOURCE_PATH).lastOrNull()
            ?.header(ACCEPT_ENCODING)
        if (asked != IDENTITY) {
            refuse(
                RULE_7,
                "sent `$ACCEPT_ENCODING: ${asked ?: "<none>"}` for a request core had already " +
                    "given `$ACCEPT_ENCODING: $IDENTITY`",
                "the headers on the request and no others. Nothing fails when a client overrides " +
                    "this: the bytes core counts become the decompressed ones while the bytes the " +
                    "link moved are the compressed ones, so every throughput sample overstates the " +
                    "link by the compression ratio and ABR climbs a ladder the link cannot carry",
            )
        }
        val coding = response.header(CONTENT_ENCODING)
        if (coding != null && !coding.equals(IDENTITY, ignoreCase = true)) {
            refuse(
                RULE_7,
                "answered a body coded `$coding`",
                "the identity coding the request asked for",
            )
        }
        if (!body.contentEquals(ConformanceOrigin.body)) {
            refuse(
                RULE_7,
                "answered ${body.size} bytes where the origin sent ${ConformanceOrigin.body.size}",
                "the bytes the origin sent, neither decompressed nor otherwise transformed",
            )
        }
    }

    /**
     * **ADR-0016 rule 8** — a refusal the origin answered is *reported* as a number with its
     * response headers, never raised, because the typed failure the fallback ladder and
     * `ErrorClassifier` read is core's to build from it.
     */
    public fun verifyStatusIsReportedRatherThanRaised(): Unit = withOrigin { origin ->
        val response = try {
            open(origin, ConformanceOrigin.REFUSED_PATH)
        } catch (failure: IOException) {
            refuse(
                RULE_8,
                "raised ${failure.javaClass.name} for a ${ConformanceOrigin.FORBIDDEN} the origin " +
                    "answered",
                "the status reported on HttpResponse.status, for a " +
                    "${ConformanceOrigin.FORBIDDEN} exactly as for a ${ConformanceOrigin.OK}. An " +
                    "IOException is for a request that could not be made at all. Raising here " +
                    "switches off the credential repair that heals a 401 or 403 inside the " +
                    "transfer that met it, and every classification downstream of it: nothing " +
                    "fails, sessions simply end unclassified",
            )
        }
        drain(response, RULE_8)
        if (response.status != ConformanceOrigin.FORBIDDEN) {
            refuse(
                RULE_8,
                "reported ${response.status} for a ${ConformanceOrigin.FORBIDDEN} the origin " +
                    "answered",
                "the number the origin answered, reported and never interpreted",
            )
        }
        if (response.header(WWW_AUTHENTICATE) == null) {
            refuse(
                RULE_8,
                "reported the status with none of the response headers that came with it",
                "the response headers on a refusal as much as on a 200; they are what says which " +
                    "credential a challenge wanted and are the second of the three things core " +
                    "puts on the typed failure",
            )
        }
    }

    /**
     * **ADR-0016 rule 10** — closing [HttpResponse.body] abandons the transfer, so a read blocked
     * in it returns rather than waiting for an origin nobody is listening to any more.
     *
     * The one check here that needs two threads, for the reason the obligation exists: core closes
     * a response from the thread that decided to abandon it — a seek, a track switch, a release —
     * while a loader thread is blocked reading it.
     */
    public fun verifyCancelledRequestReturns(): Unit = withOrigin { origin ->
        val body = AtomicReference<InputStream>()
        val reading = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val opening = AtomicReference<Throwable>()
        thread(isDaemon = true, name = "conformance-reader") {
            try {
                val response = open(origin, ConformanceOrigin.STALLING_PATH)
                body.set(response.body)
                response.body.read()
                reading.countDown()
                // The origin sends nothing more, so this blocks until the close below cancels it.
                var byte = response.body.read()
                while (byte != -1) {
                    byte = response.body.read()
                }
            } catch (failure: Throwable) {
                opening.compareAndSet(null, failure)
            } finally {
                reading.countDown()
                returned.countDown()
            }
        }
        if (!reading.await(OPEN_BOUND_MS, TimeUnit.MILLISECONDS)) {
            refuse(
                RULE_10,
                "had not delivered a byte of a response the origin had already begun after " +
                    "$OPEN_BOUND_MS ms",
                "the response's status and headers as soon as they arrive, with the body read " +
                    "incrementally rather than buffered whole",
            )
        }
        opening.get()?.let { throw it }
        val stream = body.get() ?: refuse(
            RULE_10,
            "returned no body to close",
            "an HttpResponse carrying the body, unread",
        )
        stream.close()
        if (!returned.await(CANCELLATION_BOUND_MS, TimeUnit.MILLISECONDS)) {
            refuse(
                RULE_10,
                "left a read blocked $CANCELLATION_BOUND_MS ms after the response body was closed",
                "a cancelled request that returns. Core closes the body to abandon a load — Media3 " +
                    "does that on a seek, on a track switch and on release — and puts no timeout " +
                    "around it deliberately, so a stream that instead waits for the origin holds a " +
                    "loader thread for the length of a transfer nobody wants, and at the end holds " +
                    "a player and its buffers past release",
            )
        }
    }

    private fun open(
        origin: ConformanceOrigin,
        path: String,
        range: HttpRange? = null,
    ): HttpResponse {
        val request = HttpRequest(
            uri = Uri.parse(origin.baseUri + path),
            method = HttpMethod.GET,
            // Every request carries the coding core composes onto every request of its own
            // (ADR-0016 rule 7), and carries nothing else: a header the origin sees that is not
            // this one is the transport's own addition, which is what rule 7's check reads.
            headers = mapOf(ACCEPT_ENCODING to IDENTITY),
            body = null,
            range = range,
        )
        return transport.open(request)
    }

    /** The body, read to its end and closed, which is what core does with one it keeps. */
    private fun drain(response: HttpResponse, rule: String): ByteArray = try {
        response.body.use { it.readBytes() }
    } catch (failure: IOException) {
        refuse(
            rule,
            "raised ${failure.javaClass.name} (${failure.message}) while its body was being read",
            "a body that reads to its end, or an IOException only where the transfer really failed",
        )
    }

    /**
     * A response header by the case-insensitive rule. // spec: RFC 9110 §5.1.
     *
     * The key is read as nullable although the type says it cannot be: `HttpURLConnection` files
     * the status line under a null key and hands the map straight out, so a transport written over
     * the platform's client passes one through. A conformance suite that threw on it would report a
     * `NullPointerException` in itself where the transport's actual answer was perfectly good.
     */
    private fun HttpResponse.header(name: String): String? {
        val wanted = name.lowercase(Locale.ROOT)
        return headers.entries
            .firstOrNull { (it.key as String?)?.lowercase(Locale.ROOT) == wanted }
            ?.value?.firstOrNull()
    }

    private fun <T> withOrigin(check: (ConformanceOrigin) -> T): T =
        ConformanceOrigin().use { check(it) }

    private fun refuse(rule: String, did: String, requires: String): Nothing =
        throw HttpTransportConformanceException(
            buildString {
                append("This HttpTransport does not satisfy $rule.\n")
                append("  What this transport did: $did.\n")
                append("  What the rule requires: $requires.\n")
                append("  The obligation is written out in full in HttpTransport's KDoc.")
            },
        )
}

/**
 * An obligation [HttpTransportConformance] found broken, naming the rule, what the transport did and
 * what the rule requires.
 *
 * An `AssertionError` so that a test framework renders it as a failed assertion rather than as an
 * error in the test itself, which is what it is: the transport under test is the thing that failed.
 */
public class HttpTransportConformanceException internal constructor(message: String) : AssertionError(message)

/**
 * The names a failure gives the obligation it found broken, and the two bounds the checks apply.
 *
 * Top level and private rather than in a companion, because a `const val` in a companion reaches
 * the tracked public API even where the companion itself is private (`docs/api-surface.md`), and a
 * dozen of this class's own spellings are not API for anyone to be held to.
 */
private const val RULE_5 = "ADR-0016 rule 5 (a byte range must be honoured)"
private const val RULE_6 = "ADR-0016 rule 6 (a redirect that was followed must be reported)"
private const val RULE_7 = "ADR-0016 rule 7 (no content coding of the transport's own)"
private const val RULE_8 = "ADR-0016 rule 8 (a status must be reported rather than raised)"
private const val RULE_10 = "ADR-0016 rule 10 (a cancelled request returns rather than blocking)"

private const val CONTENT_RANGE = "Content-Range"
private const val CONTENT_ENCODING = "Content-Encoding"
private const val ACCEPT_ENCODING = "Accept-Encoding"
private const val WWW_AUTHENTICATE = "WWW-Authenticate"
private const val IDENTITY = "identity"

/**
 * How long a status and a first byte are waited for over the loopback interface. Wide
 * enough that a slow machine is never the finding, and finite so that a transport which
 * never answers fails as itself rather than as a suite that hung.
 */
private const val OPEN_BOUND_MS = 10_000L

/**
 * How long a blocked read may take to return after its body was closed. Cancelling a
 * loopback transfer is immediate on every client this contract was written against, so this
 * bound is about telling *cancelled* from *not cancelled at all* rather than about being
 * fast. Core itself puts no bound here at all, deliberately, which
 * `HttpTransportDataSource.close`'s KDoc argues; a test may, because a test that hangs
 * reports nothing.
 */
private const val CANCELLATION_BOUND_MS = 2_000L
