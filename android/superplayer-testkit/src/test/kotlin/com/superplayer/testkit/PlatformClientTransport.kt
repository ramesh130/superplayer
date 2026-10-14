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
import com.superplayer.core.HttpRequest
import com.superplayer.core.HttpResponse
import com.superplayer.core.HttpTransport
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A consumer's [HttpTransport], written over the client every Android app already has, with one
 * knob: which of ADR-0016's obligations it breaks.
 *
 * This is what [HttpTransportConformanceTest] scores the conformance suite against, and it is the
 * reason that suite can be believed. **A check nothing has ever failed is a check that does not
 * work**, so there is one [Defect] per obligation and the test drives each of them: the suite is
 * proven non-vacuous per rule rather than in aggregate.
 *
 * It is written over `HttpURLConnection` rather than as a stub answering canned values, because
 * every one of these defects is a *configuration* of a real client rather than an invention — a
 * `Range` header nobody composed, a client left to negotiate its own coding, a wrapper that turns a
 * refusal into an exception, a stream whose close does not disconnect, a reported address read off
 * the request instead of off the connection. A stub would prove the checks can be made to fail; this
 * proves they fail on the mistakes an adopter actually makes.
 */
internal class PlatformClientTransport(private val defect: Defect? = null) : HttpTransport {

    /** One obligation, broken the way a real client breaks it. */
    enum class Defect {

        /** Rule 5: the range on the request never becomes a `Range` header, so a 200 comes back. */
        IGNORES_RANGE,

        /** Rule 6: the address that was asked for is reported, not the one that answered. */
        REPORTS_THE_REQUESTED_URI,

        /** Rule 7: the client negotiates `gzip` on its own behalf and decompresses the answer. */
        NEGOTIATES_GZIP,

        /** Rule 8: a refusal becomes an exception of the transport's own. */
        RAISES_ON_A_REFUSAL,

        /** Rule 10: closing the body closes a stream and abandons no connection. */
        BLOCKS_PAST_CLOSE,
    }

    override fun open(request: HttpRequest): HttpResponse {
        val connection = URL(request.uri.toString()).openConnection() as HttpURLConnection
        connection.requestMethod = request.method.name
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (defect == Defect.NEGOTIATES_GZIP) {
            // The one configuration ADR-0016 rule 7 tells a consumer to switch off, switched on:
            // the coding the chain asked for is overridden and the answer is transparently inflated.
            connection.setRequestProperty("Accept-Encoding", "gzip")
        }
        request.range?.takeIf { defect != Defect.IGNORES_RANGE }?.let {
            connection.setRequestProperty("Range", it.headerValue())
        }

        val status = connection.responseCode
        if (status >= FIRST_ERROR_STATUS && defect == Defect.RAISES_ON_A_REFUSAL) {
            connection.disconnect()
            throw IOException("HTTP $status")
        }
        val stream = (if (status < FIRST_ERROR_STATUS) connection.inputStream else connection.errorStream)
            ?: ByteArrayInputStream(ByteArray(0))
        return HttpResponse(
            status = status,
            headers = connection.headerFields.filterKeys { it != null },
            body = bodyOf(connection, stream),
            uri = when (defect) {
                // Reported off the request, which is the same string whether or not anything
                // redirected — the defect being that the difference is exactly what rule 6 wants.
                Defect.REPORTS_THE_REQUESTED_URI -> request.uri

                // `HttpURLConnection.getURL()` is the address the response came from, after every
                // redirect the client followed.
                else -> Uri.parse(connection.url.toString())
            },
        )
    }

    private fun bodyOf(connection: HttpURLConnection, stream: InputStream): InputStream = when (defect) {
        Defect.NEGOTIATES_GZIP ->
            if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                GZIPInputStream(stream)
            } else {
                stream
            }

        // A close that closes the stream and leaves the connection to the platform's pool, which is
        // what a client does when it means to reuse a connection — and what holds a loader thread on
        // a transfer nobody is waiting for any more.
        Defect.BLOCKS_PAST_CLOSE -> object : FilterInputStream(stream) {
            override fun close() {}
        }

        else -> object : FilterInputStream(stream) {
            override fun close() {
                // Disconnect *first*: closing the stream of an unfinished response is what drains
                // it, and draining is the blocking rule 10 is about.
                connection.disconnect()
                super.close()
            }
        }
    }

    private companion object {
        /** // spec: RFC 9110 §15.5 — 4xx is the first band a response is a refusal in. */
        const val FIRST_ERROR_STATUS = 400
    }
}
