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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InputStream

/**
 * The one adapter between a consumer's [HttpTransport] and Media3's `DataSource`, and the one place
 * every obligation ADR-0016 keeps on *our* side of the boundary is discharged.
 *
 * Internal, and it has to be: `DataSource`, `DataSpec`, `BaseDataSource` and `HttpDataSource` all
 * carry Media3's `@UnstableApi`, which ADR-0001 rule 2 and `verifyNoUnstableMedia3InPublicApi` keep
 * out of public API. That is exactly why a consumer hands over an [HttpTransport] and not a
 * `DataSource.Factory` (ADR-0016 rule 2): everything in this file is care that would otherwise be
 * redistributed to every adopter, to be got right once each.
 *
 * ## Why `BaseDataSource` and not a `DataSource` written out
 *
 * Every other wrapper in this repository — `LivePlaylistRevalidation`'s, the cache layer's stamping
 * source, the header-refresh layer's — implements `DataSource` directly around a *delegate*, because
 * each is a layer with an upstream to forward a `TransferListener` registration to. This one is the
 * bottom and has none, so it inherits Media3's own bookkeeping rather than reimplementing it
 * (ADR-0016 rule 9): `addTransferListener` and the `transferInitializing` / `transferStarted` /
 * `bytesTransferred` / `transferEnded` sequence are `BaseDataSource`'s, and a listener that never
 * reaches the bottom of the chain is a bandwidth estimate pinned at its default with nothing in the
 * logs — the failure `TransferChain`'s KDoc already warns the layers about.
 *
 * `isNetwork = true` is the other half of the same rule, and it is not decoration: Media3's
 * bandwidth meter drops every transfer that reports `false`, which is what keeps `BandwidthOracle`'s
 * cache-hit exclusion (ADR-0009 rule 8) meaning what it says. A transport reporting `false` would
 * tell ABR that the network runs at whatever speed this stack managed and would never be sampled
 * at all.
 */
internal class HttpTransportDataSource(
    private val transport: HttpTransport,
) : BaseDataSource(/* isNetwork= */ true) {

    /** What the chain names one of these per request; one [HttpTransport] serves every one of them. */
    class Factory(private val transport: HttpTransport) : DataSource.Factory {
        override fun createDataSource(): DataSource = HttpTransportDataSource(transport)
    }

    private var dataSpec: DataSpec? = null
    private var response: HttpResponse? = null

    /**
     * The body being read, and the one field of this class another thread touches: [close] cancels
     * an in-flight request (ADR-0016 rule 10), and Media3 calls it from the thread abandoning the
     * load rather than from the one parked inside [read]. Volatile so that the reader sees the
     * cancellation rather than a stale reference, and read into a local in [read] so that a close
     * landing mid-call is a *typed* failure rather than the `IllegalStateException` a
     * `checkNotNull` would raise — an untyped one would escape the `HttpDataSourceException`
     * wrapping that ADR-0016 rule 8 and `ErrorClassifier` both read.
     *
     * Nothing else here is guarded, and nothing else needs to be: every other field is written by
     * [open] before a reader exists and read by the thread that called it.
     */
    @Volatile
    private var stream: InputStream? = null

    /**
     * The URI the bytes are coming from — the one the transport reported after following whatever
     * redirects it followed, or the requested one where it reported none (ADR-0016 rule 6).
     *
     * Held here rather than read off [response] on demand because [closeQuietly] lets go of the
     * response and [getUri] has to keep answering for the whole of the transfer either way.
     *
     * Assigned after the status and range checks, deliberately, so a **refused** open answers null —
     * the same answer Media3's own stacks give, and the one that agrees with [getResponseHeaders],
     * which [closeQuietly] has emptied by then. The URI a refusal is diagnosed from is the one on
     * the `DataSpec` the typed exception carries, not this.
     */
    private var readFrom: Uri? = null

    /**
     * How many bytes are still to come, or [C.LENGTH_UNSET] where the response did not say. Unset is
     * not "none": a chunked response has no length and is read until the stream ends.
     */
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    /** Whether [transferStarted] has been reported and [transferEnded] has not; see [close]. */
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRemaining = C.LENGTH_UNSET.toLong()
        readFrom = null
        transferInitializing(dataSpec)

        val range = rangeOf(dataSpec)
        val request = HttpRequest(
            uri = dataSpec.uri,
            method = methodOf(dataSpec),
            headers = requestHeadersFor(dataSpec),
            body = dataSpec.httpBody,
            range = range,
        )
        val response = try {
            transport.open(request)
        } catch (e: IOException) {
            // A request that could not be made at all — no route, no name, a handshake that failed.
            // Typed as an open failure so that the layers above see the same shape they see from
            // Media3's own stacks, whatever client raised it.
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        this.response = response
        this.stream = response.body

        if (response.status !in HTTP_OK_RANGE) {
            // ADR-0016 rule 8: the consumer reports a number and core turns it into the evidence the
            // rest of the library reads — `TokenRefreshLayer` decides whether a credential may be
            // repaired off `responseCode`, and `ErrorClassifier` decides what the failure *is* off
            // the same field. A status swallowed here would end sessions unclassified and repair
            // nothing, with nothing failing to say so.
            //
            // The `DataSpec` is the one handed down, never a fresh one and never one re-addressed at
            // a redirect's target: `ErrorClassifier` reads `LoadKind` off it, and rebuilding it
            // would reclassify every refused segment as a refused manifest. So a refusal at an edge
            // a redirect landed on is reported against the address the chain asked for, which is
            // also what Media3's own stacks report.
            closeQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                response.status,
                /* responseMessage= */ null,
                /* cause= */ null,
                response.headers,
                dataSpec,
                /* responseBody= */ ByteArray(0),
            )
        }
        if (range != null && range.offset > 0 && response.status != HTTP_PARTIAL_CONTENT) {
            // ADR-0016 rule 5, failing loudly rather than quietly. The alternative — reading and
            // discarding `range.offset` bytes to get to the ones asked for — is what makes a
            // transport that ignores ranges look merely slow: the player plays, the viewer pays for
            // every byte of every resource twice over, and the bandwidth estimate describes a link
            // nobody is on. A range answered 200 from offset zero is not this case and is let
            // through, because the bytes then start where the reader expects them to.
            // spec: RFC 9110 §14.2 — a range a server honours answers 206 with `Content-Range`.
            closeQuietly()
            throw HttpDataSource.HttpDataSourceException(
                "Range request from byte ${range.offset} answered ${response.status}, not 206: " +
                    "the transport did not honour it (ADR-0016 rule 5)",
                dataSpec,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        // What the reader is allowed to take. The `DataSpec`'s own length wins where it has one:
        // that is the number the layer above asked for, and a response that offers more of the
        // resource than was asked for is still only read that far.
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            contentLengthOf(response) ?: C.LENGTH_UNSET.toLong()
        }

        // Where the bytes are really coming from (ADR-0016 rule 6). A transport that followed no
        // redirect says nothing and is taken at the requested URI, which is the same answer Media3's
        // own stacks give for an exchange that did not move.
        // spec: RFC 9110 §15.4 — a 3xx names a different URI to take the request to.
        // spec: RFC 3986 §5.1.3 — a relative reference inside a document resolves against the URI
        // the document was *retrieved* from, which is why this and not the requested one.
        readFrom = response.uri ?: dataSpec.uri

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        // Taken once: a close from another thread may null it between here and the read below, and
        // this is the reference that request was opened on either way.
        val body = stream ?: throw HttpDataSource.HttpDataSourceException(
            "The request was cancelled while a read was in flight",
            checkNotNull(dataSpec),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            HttpDataSource.HttpDataSourceException.TYPE_READ,
        )
        val read = try {
            body.read(buffer, offset, wanted)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                checkNotNull(dataSpec),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        // The count the whole of measurement is derived from, reported through `BaseDataSource`
        // rather than to a listener this class keeps (ADR-0016 rule 9).
        bytesTransferred(read)
        return read
    }

    /**
     * The URI the bytes came from: the post-redirect one where the transport reported one, and the
     * requested one otherwise (ADR-0016 rule 6).
     *
     * What reads it is not diagnostics. Media3's `ParsingLoadable` parses a manifest against
     * `getUri()`, and a manifest's references are relative, so this answer is the base every media
     * playlist, every `BaseURL` and every segment of a redirected document resolves against. The
     * requested URI returned here for a document served from somewhere else is a stream that will
     * not start, with nothing on the wire to say why.
     */
    override fun getUri(): Uri? = readFrom

    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers ?: emptyMap()

    /**
     * Lets the request go, whether it was read out or abandoned part-way — and abandoning one is the
     * ordinary case, not the exceptional one (ADR-0016 rule 10). Media3 closes a `DataSource` to
     * give up a load on a seek, on a track switch and on release, and [closeQuietly] closing the
     * body is what cancels the call underneath.
     *
     * ## Why there is no timeout around it, which is a decision and not an omission
     *
     * A transport whose body blocks until the origin finishes rather than returning when it is
     * closed holds a loader thread, and the cost is real: a seek that waits for the segment being
     * left behind, and a release that does not complete while a dead screen's buffers are still
     * held. Bounding it here was considered and refused, for three reasons.
     *
     * The number does not exist. A bound would be a duration in this file, and this repository's
     * rule is that a constant carries its reason where it is chosen; the only reason available for
     * this one would be that it felt long enough, measured against no network, no device and no
     * origin. A wrong bound is worse than none, because it fires on the slow links this library
     * exists to play well on.
     *
     * Enforcing it would cost everybody. A blocking read cannot be timed out from the thread that is
     * inside it, so a bound means a watchdog thread — one per player, or one per process holding a
     * registry of transfers in flight — allocated for every consumer to insure against a bug in one
     * of them. ADR-0016 rule 14's "a consumer who names none pays nothing" is counted by a test, and
     * this would be the first thing the seam charged everyone for.
     *
     * And it would hide the thing worth seeing. Cancelled-then-timed-out arrives at the layers above
     * as a load that failed slowly: `RetryingLoadErrors` spends a budget on it, the ladder climbs, a
     * session ends classified as a transfer failure, and the actual cause — a `read` that does not
     * return — is nowhere in the evidence. Left unbounded it is a hang whose stack trace names the
     * consumer's own `InputStream.read` on the frame below ours, which is the diagnosis. Where this
     * is *meant* to be caught is the public conformance test ADR-0016 rule 14 asks
     * `superplayer-testkit` for, on a developer's machine rather than by a timeout on a viewer's
     * device — and that test is **not written yet**, which is the honest weakness of this argument's
     * third leg and is said here rather than left for a reader to discover.
     */
    override fun close() {
        closeQuietly()
        if (opened) {
            opened = false
            transferEnded()
        }
        // Forgotten last, so that [transferEnded] is reported for the request it belonged to. A
        // closed source answers null from [getUri], which is what Media3's own stacks do and what
        // keeps it from disagreeing with [getResponseHeaders], already emptied above.
        dataSpec = null
        readFrom = null
    }

    /**
     * Lets go of the response, whether it was read out or abandoned part-way.
     *
     * Closing the body is what cancels an in-flight request on every client this library knows of,
     * and a failure to close is deliberately swallowed: the caller is either already unwinding a
     * failure whose cause this would replace, or releasing a player, and neither is improved by a
     * second exception from a stream nobody will read again.
     */
    private fun closeQuietly() {
        try {
            stream?.close()
        } catch (_: IOException) {
            // See above.
        }
        stream = null
        response = null
    }

    private companion object {

        /** // spec: RFC 9110 §15.3 — 2xx is successful; everything else is core's to type. */
        val HTTP_OK_RANGE = 200..299

        /** // spec: RFC 9110 §15.3.7. */
        const val HTTP_PARTIAL_CONTENT = 206

        /**
         * Lower-cased, because it is only ever *looked up* and the lookup is case-insensitive;
         * [ACCEPT_ENCODING] beside it is title-cased because it is *composed* and goes out on the
         * wire spelled as written. // spec: RFC 9110 §8.6.
         */
        const val CONTENT_LENGTH = "content-length"

        /** // spec: RFC 9110 §12.5.3. */
        const val ACCEPT_ENCODING = "Accept-Encoding"

        /**
         * // spec: RFC 9110 §8.4.1 — `identity` is the "no transformation" coding, and asking for it
         * is how a request says it wants the bytes the resource is made of.
         */
        const val IDENTITY = "identity"

        /**
         * What the transport is asked to send: what the chain composed, plus the content coding the
         * chain wants (ADR-0016 rule 7 — *the chain* says so, and this is the chain's bottom).
         *
         * Asking for `identity` explicitly is not belt-and-braces, it is the mechanism: an HTTP
         * client adds transparent compression only where the caller named no `Accept-Encoding` of
         * its own — OkHttp's rule, and the reason Media3's own `DefaultHttpDataSource` sends the
         * same header — so naming one is what switches a whole class of client back off. What it
         * buys is measurement: the bytes a decompressing client hands back are not the bytes the
         * link moved, and every throughput sample taken from them overstates the network by the
         * compression ratio (ADR-0009 rule 8, and ADR-0002's argument through a third door).
         *
         * A coding the chain named itself wins, in whatever case it spelled the header
         * (// spec: RFC 9110 §5.1 — field names are case-insensitive). Nothing in this library
         * composes one today; a layer that starts to — a consumer's `HeaderProvider` reaching an
         * origin that requires it — is making a decision about its own transfer, and having it
         * silently overwritten from down here is the harder failure to find of the two.
         */
        fun requestHeadersFor(dataSpec: DataSpec): Map<String, String> {
            val composed = dataSpec.httpRequestHeaders
            if (composed.keys.any { it.equals(ACCEPT_ENCODING, ignoreCase = true) }) return composed
            return composed + (ACCEPT_ENCODING to IDENTITY)
        }

        /**
         * The range [dataSpec] asks for, or null where it wants the resource whole.
         *
         * Position zero with no length is the whole resource and asks for no range at all, which is
         * what keeps a plain manifest fetch a plain request: an origin answering 206 to something
         * nobody scoped is a needless difference between this stack and Media3's own.
         */
        fun rangeOf(dataSpec: DataSpec): HttpRange? {
            val length = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
            if (dataSpec.position == 0L && length == null) return null
            return HttpRange(dataSpec.position, length)
        }

        fun methodOf(dataSpec: DataSpec): HttpMethod = when (dataSpec.httpMethod) {
            DataSpec.HTTP_METHOD_POST -> HttpMethod.POST

            DataSpec.HTTP_METHOD_HEAD -> HttpMethod.HEAD

            // `DataSpec` has exactly three, and GET is its own default.
            else -> HttpMethod.GET
        }

        /**
         * What the response says is coming, or null where it said nothing.
         *
         * Read case-insensitively, because // spec: RFC 9110 §5.1 makes a field name
         * case-insensitive and clients disagree about the case they hand one back in — OkHttp's
         * multimap is lower-cased, `HttpURLConnection`'s keeps the wire's spelling.
         */
        fun contentLengthOf(response: HttpResponse): Long? =
            response.headers.entries
                .firstOrNull { it.key.equals(CONTENT_LENGTH, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.toLongOrNull()
    }
}
