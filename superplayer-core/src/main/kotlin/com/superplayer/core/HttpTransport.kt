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
import java.io.IOException
import java.io.InputStream

/**
 * The HTTP client a player loads over, written by the consumer against whatever stack their app
 * already ships — OkHttp, Cronet, Ktor, the platform's `HttpEngine`, or one this project has never
 * heard of.
 *
 * `SuperPlayer.Builder.setHttpStack(HttpStack.of(transport))` is how one is chosen, and an app that
 * chooses none keeps exactly the stack that shipped before (ADR-0016 rule 14).
 *
 * ## It is about bytes, and deliberately about nothing else
 *
 * Open a request for a URI with headers and an optional byte range; answer a status, the response
 * headers and a stream; close. Nothing about HLS, DASH, manifests, caching, retry, CMCD, tokens or
 * measurement crosses this interface (ADR-0016 rule 4), because every one of those is a layer above
 * it that already exists in this library and that a consumer must not be able to get wrong. A
 * transport that tried to retry a failed request would spend a budget [PlaybackDecision.retry]
 * already owns; one that cached a response would key it by URL under a cache that keys by content.
 *
 * The other half of the same rule is what an implementation is spared. It does not classify a
 * failure, compose a `Range` header's meaning into a stream position, count bytes for the bandwidth
 * estimate, or decide what a 403 means: those are discharged once, in core's adapter, over every
 * transport rather than once per transport.
 *
 * ## What an implementation owes, and what getting it wrong costs
 *
 * Each of these fails **silently**. None of them throws, logs, or shows up as an error on a device,
 * which is why each is written down with its cost rather than as an instruction.
 *
 * **A byte range must be honoured** (ADR-0016 rule 5). A request carrying a [HttpRequest.range] asks
 * for part of a resource, and an implementation composes it as a `Range: bytes=…` header and expects
 * the origin to answer **206** with `Content-Range`.
 * // spec: RFC 9110 §14.2 — a `Range` request a server honours answers 206 Partial Content.
 * A DASH initialization or index segment is fetched this way, so a transport that drops the range
 * and returns 200 with the whole resource is **not a slow transport, it is a broken one**: the
 * player reads a segment's bytes where it expected a fragment's, every read after the first lands at
 * the wrong offset, and what a viewer sees is content that will not start — with the whole resource
 * paid for on their data plan on the way there. Core's adapter refuses a request it asked to start
 * past byte zero and that came back 200, rather than quietly skipping the bytes, so that this one
 * fails loudly instead.
 *
 * **A status must be reported rather than raised** ([HttpResponse.status]). Answer the number the
 * origin answered, for a 404 and a 403 exactly as for a 200, and let core turn it into the evidence
 * the rest of the library reads. An implementation that threw its own exception on a non-2xx instead
 * would disable the credential repair that heals a 401 inside the transfer that met it, and every
 * classification downstream of it — nothing would fail, sessions would simply end unclassified
 * (ADR-0016 rule 8, ADR-0011 rule 1).
 *
 * ## The lifecycle
 *
 * One [HttpTransport] serves every request a player opens, concurrently: manifests, playlists and
 * segments load on loader threads of the engine's, so an implementation holds no per-request state
 * of its own — the per-request state is the [HttpResponse] it returns. Core closes
 * [HttpResponse.body] when it is done with a response, including when it abandons one part-read.
 *
 * ## An implementation, whole
 *
 * ```kotlin
 * class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {
 *     override fun open(request: HttpRequest): HttpResponse {
 *         val builder = Request.Builder().url(request.uri.toString())
 *         request.headers.forEach { (name, value) -> builder.header(name, value) }
 *         request.range?.let { builder.header("Range", it.headerValue()) }
 *         val body = request.body?.toRequestBody()
 *         val response = client.newCall(builder.method(request.method.name, body).build()).execute()
 *         return HttpResponse(
 *             status = response.code,
 *             headers = response.headers.toMultimap(),
 *             body = response.body!!.byteStream(),
 *         )
 *     }
 * }
 * ```
 */
public interface HttpTransport {

    /**
     * Sends [request] and answers the origin's response, with its body unread.
     *
     * Called on one of the engine's loader threads, and blocking until the response's status and
     * headers have arrived is what is expected of it — the body is then read incrementally through
     * [HttpResponse.body], never buffered whole.
     *
     * @throws IOException if the request could not be made at all: no route, no name, a handshake
     *   that failed, a connection that dropped before a status arrived. A *status* is not one of
     *   these; a refusal the origin answered is reported through [HttpResponse.status] so that core
     *   can type it.
     */
    @Throws(IOException::class)
    public fun open(request: HttpRequest): HttpResponse
}

/**
 * One request core asks an [HttpTransport] to make.
 *
 * Built by core, never by a consumer's production code — the public constructor is for an
 * implementation's own tests.
 */
public class HttpRequest(
    /** Where to fetch from, absolute and `http:` or `https:`. */
    public val uri: Uri,
    /** `GET` for every load of media; `POST` carries a [body], which is how a licence is acquired. */
    public val method: HttpMethod,
    /**
     * The headers to send, and the whole of them: what a layer above composed — a credential a
     * `HeaderProvider` minted, CMCD keys, a content coding the chain asked for. An implementation
     * sends these and adds nothing of its own beyond what its protocol requires.
     */
    public val headers: Map<String, String>,
    /** The request body for a [HttpMethod.POST], or null for a request that carries none. */
    public val body: ByteArray?,
    /** The part of the resource wanted, or null for the whole of it; see [HttpRange]. */
    public val range: HttpRange?,
)

/** The HTTP methods a player's loads use; there are no others. */
public enum class HttpMethod {
    GET,
    POST,
    HEAD,
}

/**
 * The part of a resource a request wants: [length] bytes from [offset], or everything from [offset]
 * where the length is not known ahead of the fetch.
 *
 * A separate value rather than a `Range` header already composed into [HttpRequest.headers], because
 * the header's *spelling* is the transport's business and the fact that only part of the resource is
 * wanted is the chain's. [headerValue] is the spelling, offered so that no implementation has to
 * re-derive it.
 */
public class HttpRange(
    /** The first byte wanted, counted from zero. */
    public val offset: Long,
    /** How many bytes are wanted, or null for everything from [offset] to the end of the resource. */
    public val length: Long?,
) {

    /**
     * This range as the value of a `Range` request header — `bytes=<first>-<last>`, or
     * `bytes=<first>-` where the length is open.
     *
     * // spec: RFC 9110 §14.1.2 — an int-range's last-pos is *inclusive*, which is why one is
     * subtracted: a length of 10 from offset 0 is `bytes=0-9`, and `bytes=0-10` would ask for
     * eleven bytes and answer a `Content-Range` an offset out from what the player expects.
     */
    public fun headerValue(): String =
        if (length == null) "bytes=$offset-" else "bytes=$offset-${offset + length - 1}"
}

/**
 * What an [HttpTransport] answered: the status, the response headers and the body, unread.
 *
 * A non-2xx is a response like any other and is reported here rather than thrown — see
 * [HttpTransport]'s KDoc for what an implementation that raises instead silently switches off.
 */
public class HttpResponse(
    /** The status code the origin answered, reported and never interpreted: 200, 206, 403, 502. */
    public val status: Int,
    /**
     * The response headers, lower-cased or not — core matches them case-insensitively, because
     * // spec: RFC 9110 §5.1 makes a field name case-insensitive and HTTP clients disagree about
     * which case they hand back.
     */
    public val headers: Map<String, List<String>>,
    /**
     * The body, unread. Core reads it incrementally and closes it, including when it abandons a
     * response part-read; an implementation does not close it itself.
     */
    public val body: InputStream,
)
