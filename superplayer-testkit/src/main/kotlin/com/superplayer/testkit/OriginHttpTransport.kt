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

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.superplayer.core.HttpMethod
import com.superplayer.core.HttpRequest
import com.superplayer.core.HttpResponse
import com.superplayer.core.HttpTransport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * This harness's origin, wearing the interface a consumer writes: [ChainBottom.CONSUMERS_HTTP_TRANSPORT]'s
 * half of the bottom of the chain.
 *
 * It is deliberately *thin*. Everything that makes a harness transfer what it is — the fake origin,
 * the fault injector, the shaper over a replayed trace, the clock wait that keeps a load and the
 * clock in step — is [origin], unchanged and shared with the slot. What this class adds is the one
 * translation ADR-0016 rule 8 is about, and nothing else: a Media3 `DataSource` exchange expressed
 * as the [HttpRequest] / [HttpResponse] pair a consumer's HTTP client speaks.
 *
 * ## A refusal is *reported*, because that is the whole claim
 *
 * The origin raises a Media3 `InvalidResponseCodeException` for an injected status. A real HTTP
 * client raises nothing for a 403 — it hands back a response whose code is 403 — so that is what
 * this does: the exception is caught here and its status and headers become an [HttpResponse]. The
 * typed exception the layers above read can therefore only have been built by core's adapter, which
 * is exactly the thing under test. A transport that re-raised it would be testing the origin.
 *
 * ## A failure that is not a status is an `IOException`, because that is all there is
 *
 * A name that will not resolve, a handshake that fails, a connection reset: the injector expresses
 * each as a Media3 `HttpDataSourceException` carrying an error code *it* chose and the `IOException`
 * a real stack would have raised. An `HttpTransport` has no channel for an error code (rule 4), so
 * what travels is the `IOException` — unwrapped, because that is what a consumer's client throws —
 * and core's adapter types it as an open failure. The consequence is stated in [ChainBottom]: the
 * two bottoms agree about statuses and need not agree about the band below them.
 */
internal class OriginHttpTransport(private val origin: DataSource.Factory) : HttpTransport {

    override fun open(request: HttpRequest): HttpResponse {
        val source = origin.createDataSource()
        val dataSpec = specFor(request)
        val announcedLength = try {
            source.open(dataSpec)
        } catch (refusal: HttpDataSource.InvalidResponseCodeException) {
            // The status the origin refused with, handed back as a number rather than thrown. The
            // source is closed here because nothing else will: core's adapter closes the response
            // body it is given, and on this path the body carries no transfer at all.
            closeQuietly(source)
            return HttpResponse(refusal.responseCode, refusal.headerFields, ByteArrayInputStream(EMPTY))
        } catch (failure: HttpDataSource.HttpDataSourceException) {
            closeQuietly(source)
            // The cause is the exception a real client raises; the wrapper around it is Media3's
            // vocabulary, which is core's to apply on the other side of this boundary and not a
            // transport's to pre-empt.
            throw failure.cause as? IOException ?: failure
        } catch (failure: IOException) {
            closeQuietly(source)
            throw failure
        }

        val ranged = request.range != null
        return HttpResponse(
            // spec: RFC 9110 §15.3.7 — a range a server honours is answered 206, and the fake
            // origins honour one by construction: they serve the slice a `DataSpec`'s position and
            // length name. Answering 200 here would be refused by core's adapter under rule 5, and
            // rightly, since nothing would then be reading the bytes it asked for.
            status = if (ranged) PARTIAL_CONTENT else OK,
            headers = headersFor(source, dataSpec, announcedLength, ranged),
            body = SourceStream(source),
        )
    }

    /**
     * What a response says about itself, on top of whatever the origin declared.
     *
     * `Content-Length` is derived from the length the source announced rather than invented: core's
     * adapter reads it where the `DataSpec` named no length of its own, and a manifest fetch is
     * exactly that case. // spec: RFC 9110 §8.6.
     *
     * `Content-Range`'s complete length is `*`, which the grammar allows for a sender that does not
     * know the whole resource's size — and this one does not, because it asked for a slice. It is
     * omitted altogether where the source announced no length, because `last-pos` is *not* optional
     * in `byte-range-resp` and a header that cannot state the range is a header not to send.
     * // spec: RFC 9110 §14.4.
     */
    private fun headersFor(
        source: DataSource,
        dataSpec: DataSpec,
        announcedLength: Long,
        ranged: Boolean,
    ): Map<String, List<String>> {
        val headers = LinkedHashMap<String, List<String>>(source.responseHeaders)
        if (announcedLength != C.LENGTH_UNSET.toLong()) {
            headers[CONTENT_LENGTH] = listOf(announcedLength.toString())
        }
        if (ranged && announcedLength != C.LENGTH_UNSET.toLong()) {
            val last = dataSpec.position + announcedLength - 1
            headers[CONTENT_RANGE] = listOf("bytes ${dataSpec.position}-$last/*")
        }
        return headers
    }

    private fun specFor(request: HttpRequest): DataSpec = DataSpec.Builder()
        .setUri(request.uri)
        .setHttpMethod(
            when (request.method) {
                HttpMethod.GET -> DataSpec.HTTP_METHOD_GET
                HttpMethod.POST -> DataSpec.HTTP_METHOD_POST
                HttpMethod.HEAD -> DataSpec.HTTP_METHOD_HEAD
            },
        )
        .setHttpBody(request.body)
        .setHttpRequestHeaders(request.headers)
        .setPosition(request.range?.offset ?: 0L)
        .setLength(request.range?.length ?: C.LENGTH_UNSET.toLong())
        .build()

    private fun closeQuietly(source: DataSource) {
        try {
            source.close()
        } catch (_: IOException) {
            // The refusal being reported is what the caller acts on; a close that failed on a
            // request already refused adds nothing to it. The close itself is not optional, because
            // it is what balances the harness's count of transfers in flight.
        }
    }

    /**
     * The response body: the source's own bytes, read as a stream.
     *
     * Closing it closes the source, because core's adapter closes the body and nothing else here
     * holds the transfer — which is what makes the harness's "a load is in flight" bookkeeping
     * balance over this bottom exactly as it does over the slot.
     */
    private class SourceStream(private val source: DataSource) : InputStream() {

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = source.read(b, off, len)
            return if (read == C.RESULT_END_OF_INPUT) -1 else read
        }

        override fun close() {
            source.close()
        }

        private companion object {
            const val BYTE_MASK = 0xFF
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val OK = 200
        const val PARTIAL_CONTENT = 206
        const val CONTENT_LENGTH = "Content-Length"
        const val CONTENT_RANGE = "Content-Range"
    }
}
