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

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import com.superplayer.core.HttpRequest
import com.superplayer.core.HttpResponse
import com.superplayer.core.HttpStack
import com.superplayer.core.HttpTransport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * `HttpStack.default()` — the stack that ships when a consumer names none — wearing the interface a
 * consumer writes, so that [HttpTransportConformance] can be run against it.
 *
 * ADR-0016 rule 14's contract has to describe something this library actually ships, and the only
 * way to say so is to hold the default to it. What this class adds around the default's own
 * `DataSource` is exactly the adaptation an adopter writes around their client and nothing else:
 * the status reported rather than raised, the headers handed back with it, the URI the connection
 * ended at, and a close that abandons the transfer.
 *
 * It is **not** `OriginHttpTransport`, which wears the same interface over the harness's fake
 * origin: that one answers 206 for a ranged request by construction, because the origin below it
 * cannot express a partial-content *response* at all, and it reports no redirect because nothing
 * there can redirect. Running the conformance suite over it would be scoring a stand-in.
 *
 * The factory is reached through core's own `httpFactory`, which this module may call because it
 * compiles as a friend of core — so what is under test is the stack `default()` resolves and not a
 * second `DefaultHttpDataSource.Factory()` written here to look like it.
 */
internal class DefaultStackTransport(context: Context) : HttpTransport {

    private val factory: DataSource.Factory = HttpStack.default().httpFactory(context)

    override fun open(request: HttpRequest): HttpResponse {
        val source = factory.createDataSource() as HttpDataSource
        val spec = DataSpec.Builder()
            .setUri(request.uri)
            .setHttpRequestHeaders(request.headers)
            .setPosition(request.range?.offset ?: 0L)
            .setLength(request.range?.length ?: C.LENGTH_UNSET.toLong())
            .build()
        try {
            source.open(spec)
        } catch (refusal: HttpDataSource.InvalidResponseCodeException) {
            // A real client hands a 403 back as a number; Media3's data source raises it, so the
            // adapter that makes one look like the other is where the translation belongs — on this
            // side of the boundary, which is the half ADR-0016 rule 8 leaves to the consumer.
            source.close()
            return HttpResponse(refusal.responseCode, named(refusal.headerFields), emptyBody())
        }
        return HttpResponse(
            status = statusOf(source),
            headers = named(source.responseHeaders),
            body = SourceStream(source),
            uri = source.uri,
        )
    }

    /**
     * The headers, less the one `HttpURLConnection` files under a **null** key — the status line,
     * which is not a header and which the interface's `Map<String, List<String>>` says cannot be
     * there — the map's own type says so too, and the platform hands one over regardless, which is
     * why the key is read back as nullable. Filtering it is the adapter's, as everything else
     * between a client's shape and this interface's is.
     */
    private fun named(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.entries.mapNotNull { (name, values) -> (name as String?)?.let { it to values } }.toMap()

    /**
     * The status the connection answered.
     *
     * `DefaultHttpDataSource` keeps it, which is what makes this adapter honest about a 206: a
     * transport that returned 200 or 206 according to whether it had *asked* for a range would pass
     * rule 5's check without the origin ever having honoured one.
     */
    private fun statusOf(source: HttpDataSource): Int =
        (source as? DefaultHttpDataSource)?.responseCode ?: OK

    private class SourceStream(private val source: DataSource) : InputStream() {

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = source.read(b, off, len)
            return if (read == C.RESULT_END_OF_INPUT) -1 else read
        }

        /** Closing the source disconnects the connection, which is rule 10's whole obligation. */
        override fun close() {
            try {
                source.close()
            } catch (_: IOException) {
                // A transfer abandoned mid-flight is what this close is for; the failure it reports
                // on the way out is the cancellation and not a second defect.
            }
        }

        private companion object {
            const val BYTE_MASK = 0xFF
        }
    }

    private fun emptyBody(): InputStream = ByteArrayInputStream(ByteArray(0))

    private companion object {
        const val OK = 200
    }
}
