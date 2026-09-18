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

package com.superplayer.core

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.test.core.app.ApplicationProvider
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A consumer's [HttpTransport], written the way [HttpTransport]'s own KDoc example is written, over
 * a map in memory instead of a client — and *is* the origin, which is the only arrangement in this
 * repository where both sides of one HTTP exchange are visible to a test.
 *
 * Shared by [SuperPlayerHttpStackTest], which plays real streams through it, and
 * [ConsumersTransportEvidenceTest], which refuses them: they need the same origin and the same
 * record of what crossed the wire, and two copies of it would be two places for the answer to
 * "what did the transport actually receive" to drift.
 *
 * [honourRanges] false is the broken implementation ADR-0016 rule 5 names: it reads the `Range` and
 * answers 200 with the whole resource anyway.
 *
 * [refusals] is what the rest of ADR-0016 rule 8 needs — a status *reported* for a named resource,
 * with the response headers a real CDN would send with it. Reported and not thrown, because that is
 * the whole obligation the rule puts on a consumer: the number is theirs and the evidence is ours.
 *
 * [redirects] is ADR-0016 rule 6's, and [reportsFinalUri] false is the broken implementation that
 * rule names: a transport that followed the redirect and then answers the address it was *asked*
 * for. Both halves belong to the same origin because the defect is only visible as the difference
 * between them.
 */
internal class ServingTransport(
    private val resources: Map<String, ByteArray>,
    private val honourRanges: Boolean = true,
    private val refusals: Map<String, Refusal> = emptyMap(),
    private val redirects: Map<String, String> = emptyMap(),
    private val reportsFinalUri: Boolean = true,
) : HttpTransport {

    /** What a refused resource answers: a status and the headers that came with it. */
    class Refusal(val status: Int, val headers: Map<String, List<String>> = emptyMap())

    /** One exchange as the transport saw it, which is where both sides of a request are visible. */
    class Exchange(
        val uri: Uri,
        val requestHeaders: Map<String, String>,
        val rangeHeaderValue: String?,
        val status: Int,
        val responseHeaders: Map<String, List<String>>,
    )

    /** Written from the engine's loader threads, read from the test's. */
    val requests: MutableList<Exchange> = CopyOnWriteArrayList()

    fun paths(): List<String> = requests.map { it.uri.path.orEmpty() }

    override fun open(request: HttpRequest): HttpResponse {
        val requested = request.uri.toString()
        // A redirect followed, as a client follows one: the response is the target's, and where it
        // ended is reported unless this transport is the one that forgets to (ADR-0016 rule 6).
        val address = redirects[requested] ?: requested
        val finalUri = Uri.parse(address).takeIf { address != requested && reportsFinalUri }
        refusals[address]?.let { return record(request, it.status, it.headers, ByteArray(0), finalUri) }
        val bytes = resources[address]
            ?: return record(request, NOT_FOUND, emptyMap(), ByteArray(0), finalUri)
        val range = request.range?.takeIf { honourRanges }
        if (range == null) {
            return record(
                request,
                OK,
                mapOf("Content-Length" to listOf(bytes.size.toString())),
                bytes,
                finalUri,
            )
        }
        val last = range.length?.let { range.offset + it - 1 } ?: (bytes.size - 1L)
        val slice = bytes.copyOfRange(range.offset.toInt(), (last + 1).toInt())
        val headers = mapOf(
            "Content-Length" to listOf(slice.size.toString()),
            // spec: RFC 9110 §14.4 — `Content-Range: bytes <first>-<last>/<complete-length>`.
            "Content-Range" to listOf("bytes ${range.offset}-$last/${bytes.size}"),
        )
        return record(request, PARTIAL_CONTENT, headers, slice, finalUri)
    }

    private fun record(
        request: HttpRequest,
        status: Int,
        headers: Map<String, List<String>>,
        body: ByteArray,
        finalUri: Uri?,
    ): HttpResponse {
        requests += Exchange(request.uri, request.headers, request.range?.headerValue(), status, headers)
        return HttpResponse(status, headers, ByteArrayInputStream(body), finalUri)
    }

    companion object {

        /** // spec: RFC 9110 §15.3.1. */
        const val OK = 200

        /** // spec: RFC 9110 §15.3.7. */
        const val PARTIAL_CONTENT = 206

        /** // spec: RFC 9110 §15.5.5. */
        const val NOT_FOUND = 404

        /**
         * The synthetic streams live at `fake:` URIs, which no real chain resolves and which is the
         * point of them; served over a consumer's transport they need a scheme `DefaultDataSource`
         * hands on rather than answers itself, and every reference inside either document is
         * relative, so rewriting the scheme is the whole of the move.
         */
        fun httpsFor(fakeUri: String): String = fakeUri.replaceFirst("fake://", "https://")

        fun hlsOverHttps(segmentCount: Int = 1): Map<String, ByteArray> =
            SyntheticHlsStream.resources(segmentCount).mapKeys { (uri, _) -> httpsFor(uri) }

        /**
         * The same stream served from [base] instead — a second address for one piece of content,
         * which is what a redirect to an edge points at.
         *
         * The last path segment is the whole of the move: the synthetic stream's resources are flat
         * under one base and every reference inside its documents is relative, so a document read
         * from [base] names its neighbours there without a byte of it changing. That is exactly the
         * property ADR-0016 rule 6 is about, which is why the test needs no second corpus.
         */
        fun hlsUnder(base: String): Map<String, ByteArray> =
            SyntheticHlsStream.resources().mapKeys { (uri, _) -> base + uri.substringAfterLast('/') }

        fun dashOverHttps(): Map<String, ByteArray> =
            SyntheticDashStream.resources().mapKeys { (uri, _) -> httpsFor(uri) }

        /** The one media segment of [hlsOverHttps], which is the only resource a range test needs. */
        fun segmentUriIn(resources: Map<String, ByteArray>): String =
            resources.keys.single { it.endsWith(SyntheticHlsStream.SEGMENT_SUFFIX) }
    }
}

/**
 * The adapter's own `DataSource`, for the handful of assertions that are about one HTTP exchange
 * rather than about playback (each of them argued where it is made).
 *
 * The chain asks a stack for its factory rather than holding one, because the platform's stack needs
 * a `Context` and an API level (`HttpStack.httpFactory`, #313); a test asking the same question needs
 * the same context, and this is the one line that supplies it.
 */
internal fun HttpStack.testDataSource(): DataSource =
    httpFactory(ApplicationProvider.getApplicationContext()).createDataSource()
