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

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * The origin [HttpTransportConformance] measures a transport against: an HTTP/1.1 server on the
 * loopback interface, in this process, answering four addresses and recording what it was asked.
 *
 * ## Why this is a socket and everything else in this repository is not
 *
 * Every other fake here stands in for the network *above* the client — `FakeDataSource`, the fault
 * injector, the shaper. None of them can be reached by an HTTP client at all, and an HTTP client is
 * exactly what a consumer's [com.superplayer.core.HttpTransport] is written over. The obligations
 * ADR-0016 rules 5 to 10 put on one are obligations about what goes **on the wire** and what comes
 * back off it — a `Range` header composed, a 302 followed, an `Accept-Encoding` left alone, a 403
 * carried back as a number, a socket abandoned — and none of the five is observable without both
 * ends of a real exchange. So the conformance suite stands one up, bound to
 * [InetAddress.getLoopbackAddress] on an ephemeral port: no name resolution, no route off the host,
 * no external service, and nothing shared between two runs. `docs/testing.md`'s *The conformance
 * test a consumer runs* carries the argument.
 *
 * ## What it answers
 *
 * [RESOURCE_PATH] serves [body] whole, or the slice a `Range` header names as a 206 with
 * `Content-Range` — the honouring half of rule 5, so that a transport which drops the range is the
 * only reason a 200 can come back. It serves **gzip** where the request asked for it, which is what
 * makes a client's transparent compression visible rather than merely undetected (rule 7): a CDN
 * that is offered `gzip` takes it.
 *
 * [REDIRECT_PATH] answers 302 to [RESOURCE_PATH] (rule 6), [REFUSED_PATH] answers 403 with the
 * `WWW-Authenticate` challenge a real refusal carries (rule 8), and [STALLING_PATH] answers a
 * header block and one byte and then nothing at all until the client goes away (rule 10).
 *
 * Every connection is answered `Connection: close` and closed after its response. Keep-alive would
 * buy a conformance run nothing and would make a stalled transfer's socket ambiguous between "the
 * client is waiting" and "the client is done with it", which is the one thing rule 10's check reads.
 */
internal class ConformanceOrigin : AutoCloseable {

    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

    /** Everything this origin has answered, in arrival order; written from its own threads. */
    private val exchanges = CopyOnWriteArrayList<Received>()

    /** Sockets still open, so that [close] unblocks a client parked on [STALLING_PATH]. */
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var running = true

    /** Where this origin is, as a consumer's transport would be given it: `http://127.0.0.1:<port>`. */
    val baseUri: String = "http://${server.inetAddress.hostAddress}:${server.localPort}"

    init {
        thread(isDaemon = true, name = "conformance-origin") { accept() }
    }

    /** One request as the origin received it, which is where "what the transport did" is read. */
    class Received(val method: String, val path: String, val headers: Map<String, String>) {

        /** Header lookup by the case-insensitive rule. // spec: RFC 9110 §5.1. */
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    }

    fun receivedFor(path: String): List<Received> = exchanges.filter { it.path == path }

    override fun close() {
        running = false
        closeQuietly(server)
        sockets.forEach(::closeQuietly)
        sockets.clear()
    }

    private fun accept() {
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return // The origin was closed; there is nothing left to serve.
            }
            sockets += socket
            thread(isDaemon = true, name = "conformance-origin-connection") { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val request = readRequest(input) ?: return
            exchanges += request
            answer(request, socket.getOutputStream())
        } catch (_: IOException) {
            // A client that went away mid-exchange is the point of one of these addresses, and on
            // the other three it is a transport under test failing rather than this origin.
        } finally {
            closeQuietly(socket)
        }
    }

    /** The request line and the header block, which is all of a request this origin reads. */
    private fun readRequest(input: InputStream): Received? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.take(separator).trim().lowercase(Locale.ROOT)] =
                line.substring(separator + 1).trim()
        }
        return Received(parts[0], parts[1].substringBefore('?'), headers)
    }

    /**
     * One line of the header block. Read a byte at a time deliberately: a buffered reader would
     * consume past the blank line into a request body this origin never reads, and the socket is
     * one exchange long, so there is nothing to be gained by buffering it.
     * // spec: RFC 9112 §2.2 — CRLF terminates each line, and a bare LF is tolerated on receipt.
     */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            // `String(bytes, charset)` rather than `ByteArrayOutputStream.toString(charset)`,
            // which the platform only offers from API 33 and this module's floor is lower.
            if (byte == -1) return if (line.size() == 0) null else String(line.toByteArray(), CHARSET)
            if (byte == '\n'.code) return String(line.toByteArray(), CHARSET).removeSuffix("\r")
            line.write(byte)
        }
    }

    private fun answer(request: Received, output: OutputStream) {
        when (request.path) {
            REFUSED_PATH -> write(
                output,
                FORBIDDEN,
                // The challenge a refusal carries, which is the response header ADR-0016 rule 8
                // asks a transport to hand back beside the number.
                // spec: RFC 9110 §11.6.1.
                mapOf("WWW-Authenticate" to "Bearer realm=\"conformance\""),
                ByteArray(0),
            )

            REDIRECT_PATH -> write(
                output,
                FOUND,
                // Absolute, because a relative `Location` is resolved by the client and this check
                // is about what the client *reports*, not about how well it resolves a reference.
                // spec: RFC 9110 §15.4.3, §10.2.2.
                mapOf("Location" to "$baseUri$RESOURCE_PATH"),
                ByteArray(0),
            )

            STALLING_PATH -> stall(output)

            else -> serveResource(request, output)
        }
    }

    private fun serveResource(request: Received, output: OutputStream) {
        val range = request.header("Range")?.let(::parseRange)
        val wanted = if (range == null) body else body.copyOfRange(range.first, range.last + 1)
        // A client that offered gzip is served gzip, as an origin that has it configured would be.
        // Nothing here negotiates on quality values: the one reading that matters is whether the
        // token appears at all, because core sends `identity` and a transport that added `gzip` is
        // the defect rule 7 names. // spec: RFC 9110 §12.5.3.
        val gzip = request.header("Accept-Encoding")?.contains("gzip", ignoreCase = true) == true
        val sent = if (gzip) gzipped(wanted) else wanted
        val headers = LinkedHashMap<String, String>()
        if (gzip) headers["Content-Encoding"] = "gzip"
        if (range != null) {
            // spec: RFC 9110 §14.4 — `Content-Range: bytes <first>-<last>/<complete-length>`.
            headers["Content-Range"] = "bytes ${range.first}-${range.last}/${body.size}"
        }
        write(output, if (range == null) OK else PARTIAL_CONTENT, headers, sent)
    }

    /**
     * A header block, one byte, and then silence: the shape rule 10 is about, where the client has
     * a response in hand and abandons the rest of it.
     *
     * It gives up after [STALL_BOUND_MS] rather than waiting forever, so that a conformance run
     * against a transport that never cancels leaves no thread of this origin's behind it.
     */
    private fun stall(output: OutputStream) {
        writeStatusLine(output, OK)
        writeHeader(output, "Content-Length", body.size.toString())
        writeHeader(output, "Connection", "close")
        output.write(CRLF)
        output.write(body, 0, 1)
        output.flush()
        val deadline = System.nanoTime() + STALL_BOUND_MS * NANOS_PER_MILLI
        while (running && System.nanoTime() < deadline) {
            Thread.sleep(STALL_POLL_MS)
        }
    }

    private fun write(output: OutputStream, status: Int, headers: Map<String, String>, body: ByteArray) {
        writeStatusLine(output, status)
        headers.forEach { (name, value) -> writeHeader(output, name, value) }
        writeHeader(output, "Content-Length", body.size.toString())
        writeHeader(output, "Connection", "close")
        output.write(CRLF)
        output.write(body)
        output.flush()
    }

    private fun writeStatusLine(output: OutputStream, status: Int) {
        // spec: RFC 9112 §4 — `HTTP-version SP status-code SP [reason-phrase]`; the reason phrase
        // may be empty, and no client here reads one.
        output.write("HTTP/1.1 $status \r\n".toByteArray(CHARSET))
    }

    private fun writeHeader(output: OutputStream, name: String, value: String) {
        output.write("$name: $value\r\n".toByteArray(CHARSET))
    }

    /** `bytes=<first>-<last>`, the one form core composes ([com.superplayer.core.HttpRange]). */
    private fun parseRange(headerValue: String): IntRange? {
        val spec = headerValue.substringAfter("bytes=", "").takeIf { it.isNotEmpty() } ?: return null
        val first = spec.substringBefore('-').trim().toIntOrNull() ?: return null
        val last = spec.substringAfter('-').trim().toIntOrNull() ?: (body.size - 1)
        return first..minOf(last, body.size - 1)
    }

    private fun gzipped(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Shutting an origin down is not a claim any check makes.
        }
    }

    companion object {

        const val RESOURCE_PATH = "/conformance/resource"
        const val REDIRECT_PATH = "/conformance/redirected"
        const val REFUSED_PATH = "/conformance/refused"
        const val STALLING_PATH = "/conformance/stalling"

        /** // spec: RFC 9110 §15.3.1, §15.3.7, §15.4.3, §15.5.4. */
        const val OK = 200
        const val PARTIAL_CONTENT = 206
        const val FOUND = 302
        const val FORBIDDEN = 403

        /**
         * The bytes every address serves. Long enough that a range is a real slice of it and that
         * gzip makes a visible difference, and **compressible**, because a body of random bytes
         * gzips to more than itself and rule 7's check would then read the same length either way.
         */
        val body: ByteArray = ByteArray(BODY_LENGTH) { ('a' + (it % ALPHABET)).code.toByte() }

        /** The slice rule 5's check asks for: inside the body, and starting past byte zero. */
        const val RANGE_OFFSET = 64L
        const val RANGE_LENGTH = 128L
    }
}

private const val BACKLOG = 8
private const val BODY_LENGTH = 4096
private const val ALPHABET = 26
private const val CRLF_TEXT = "\r\n"
private val CRLF = CRLF_TEXT.toByteArray()
private val CHARSET = Charsets.ISO_8859_1

/**
 * How long a stalled response is held open. Generous against the cancellation bound the check
 * applies, so that a transport under test is the thing the check times and never this origin, and
 * bounded at all so that a failing run ends rather than leaving a thread parked for the suite.
 */
private const val STALL_BOUND_MS = 30_000L
private const val STALL_POLL_MS = 20L
private const val NANOS_PER_MILLI = 1_000_000L
