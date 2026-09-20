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

package com.superplayer.moq

import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import uniffi.moq.MoqBroadcastConsumer
import uniffi.moq.MoqCatalog
import uniffi.moq.MoqCatalogConsumer
import uniffi.moq.MoqClient
import uniffi.moq.MoqContainer
import uniffi.moq.MoqException
import uniffi.moq.MoqMediaConsumer
import uniffi.moq.MoqMediaFrame
import uniffi.moq.MoqSession
import uniffi.moq.MoqSubscription
import java.io.IOException

/**
 * [MoqRelay] over MoQ's own bindings: the one implementation of this module's seam that touches the
 * native library.
 *
 * Everything below is a round trip, and **nothing under `check` runs a line of it**: Robolectric
 * cannot load an Android `.so` on the JVM and `docs/testing.md` bars the network, so what stands
 * here in every test is a scripted fake of [MoqRelay]. What exercises this file is
 * `MoqLiveSessionSmokeTest`, on a device, against a real relay.
 *
 * **Two things in here were wrong until that run existed**, and both were wrong in the same quiet
 * way — a session that connected, then refused every broadcast with `unroutable`, which reads
 * identically to a name nobody publishes. [connectUrlOf] put the namespace on the wrong side of the
 * split, and [connect] asked once, at a moment the relay was not yet able to answer. Each is
 * corrected where it is made, with what settled it — and the second was first "fixed" the wrong
 * way, which [requestBroadcastWhenRoutable] records rather than quietly drops. They are the reason
 * that run is worth its carve-out from the no-network rule.
 *
 * ## Suspend becomes blocking, on this side of the seam
 *
 * Every interesting binding call is a `suspend` function, and every one of them is called here
 * inside `runBlocking` on a thread [MoqFrameSource] owns. That is deliberate and is where the seam
 * falls: the pump's threading is its own affair and a fake of the seam is a fake of a *transport*
 * rather than of a coroutine dispatcher. The threads being blocked are this source's own — never
 * the playback thread, and never the thread that prepared the player.
 */
internal object UniffiMoqRelay : MoqRelay {

    override fun connect(uri: Uri): MoqBroadcastSession {
        val client = MoqClient()
        val session = try {
            runBlocking { client.connect(connectUrlOf(uri)) }
        } catch (failure: Exception) {
            client.closeQuietly()
            throw IOException("Could not open a MoQ session for $uri", failure)
        }
        return try {
            UniffiBroadcastSession(
                client,
                session,
                requestBroadcastWhenRoutable(session, broadcastPathOf(uri)),
            )
        } catch (failure: Exception) {
            session.closeQuietly()
            client.closeQuietly()
            throw IOException("Connected to the MoQ relay but could not request the broadcast $uri", failure)
        }
    }

    /**
     * Requests [name], retrying while the relay answers `unroutable`, until [ROUTABLE_BOUND_MS].
     *
     * ## What was measured, and what is still unknown
     *
     * A freshly connected session cannot immediately route a broadcast that demonstrably exists:
     * asked at once, `cdn.moq.pro` answers `unroutable` for a name its own announce stream lists;
     * asked again a moment later, on the same session, the same name resolves and its catalog
     * arrives.
     *
     * #367 measured this with three arms differing in one thing each — request at once, wait then
     * request, subscribe to announcements then request. **The second and third behaved
     * identically**, so the wait is what matters and listening is not. That is worth writing down
     * because this code briefly did the opposite: it held an announce subscription open for the
     * session's life, on the strength of a two-arm comparison in which the listening arm also
     * waited and the silent one did not. Two explanations predicted the same result and the wrong
     * one was written down as mechanism.
     *
     * **What the relay is doing in that window is not known**, and is deliberately not guessed at
     * here. What is known is the shape of the remedy: ask again.
     *
     * ## Why a retry and not a delay
     *
     * A delay charges every subscription the worst case. A relay that is ready pays one round trip
     * and no wait at all, which is the common case and the one a viewer sits through.
     *
     * Only `unroutable` is retried. Every other refusal is a fact a second ask cannot change — an
     * unauthorized session, a malformed name — and retrying those turns a clear failure into a slow
     * one.
     */
    private fun requestBroadcastWhenRoutable(session: MoqSession, name: String): MoqBroadcastConsumer {
        val consumer = session.consumer()
        val deadline = SystemClock.elapsedRealtime() + ROUTABLE_BOUND_MS
        var wait = FIRST_RETRY_MS
        while (true) {
            try {
                return runBlocking { consumer.requestBroadcast(name) }
            } catch (failure: MoqException) {
                val retryable = failure.message?.contains(UNROUTABLE, ignoreCase = true) == true
                if (!retryable || SystemClock.elapsedRealtime() + wait >= deadline) throw failure
                runBlocking { delay(wait) }
                // Doubling, for `Backoff`'s reason in core: a fixed interval against a relay that
                // needs longer spends every attempt early and none late. No jitter, because this is
                // one subscription for one player rather than a fleet retrying in step.
                wait *= 2
            }
        }
    }

    /**
     * How long a broadcast is asked for before `unroutable` is taken at its word: **5 s**.
     *
     * It bounds a wait a viewer sits through, so it is chosen against their patience rather than
     * against the relay: a broadcast that will not route in five seconds is one to report rather
     * than keep asking about. The window observed in #367 was under three.
     */
    private const val ROUTABLE_BOUND_MS = 5_000L

    /** The first retry interval; it doubles from here. Short, because a ready relay never waits. */
    private const val FIRST_RETRY_MS = 100L

    /** What MoQ calls the refusal a later ask can resolve. */
    private const val UNROUTABLE = "unroutable"

    /**
     * The relay's own address: the URI's authority **and its namespace segment**, under `https`.
     *
     * // spec: draft-ietf-moq-transport §3.1 — a MoQ session over WebTransport is established by a
     * // WebTransport `CONNECT`, whose URI is an `https` one naming the relay. That is why the
     * // scheme handed to the bindings is `https` and not [MoqFrameSource.SCHEME]: `moq://` is the
     * // spelling a *broadcast* is written in on this side, and nothing puts it on a wire.
     *
     * ## Where the split falls, and how that was settled
     *
     * The bindings take the two apart — a URL to `connect` and a name to `requestBroadcast` — and
     * **no public document states where a single address divides between them**, which is why this
     * was labelled as a derivation rather than cited when it was written.
     *
     * #367 settled it against a live relay, and settled it the other way: the namespace belongs to
     * the **session** and not to the broadcast name. Asked both ways against `cdn.moq.pro` with
     * `anon` as the namespace, connecting to the bare authority and requesting `anon/<name>`
     * announced **nothing** and answered `unroutable`, while connecting to `https://<authority>/anon`
     * and requesting `<name>` announced the ten broadcasts that were live. MoQ's own web player
     * takes the same two inputs the same way round, which is the corroboration rather than the
     * evidence.
     *
     * ## Why the first path segment, and what that costs
     *
     * A broadcast name may itself contain `/` — `anon/doom/game/e1m1` was one of the ten — so the
     * split cannot be "the last segment is the name". What a relay publishes under is one namespace
     * segment, so the first one is taken and **everything after it is the name**, slashes included.
     *
     * The cost is stated rather than hidden: a deployment whose namespace is two segments deep is
     * not addressable by a single `moq://` URI under this rule, and would need the two handed over
     * separately the way the bindings take them. No such relay has been seen, and inventing a
     * spelling for one before it exists would be a guess of exactly the kind this KDoc used to
     * carry. A URI with no path at all connects to the bare authority and requests the empty name,
     * which is what the relay will refuse as unroutable — the honest failure for an address that
     * names no broadcast.
     */
    private fun connectUrlOf(uri: Uri): String = Uri.Builder()
        .scheme("https")
        .encodedAuthority(uri.encodedAuthority.orEmpty())
        .encodedPath(namespaceOf(uri))
        .build()
        .toString()

    /**
     * The broadcast's name at that relay: everything after the namespace segment, slashes kept.
     *
     * Empty where the URI carried no segment beyond the namespace, which the relay refuses. See
     * [connectUrlOf] for why the division falls here.
     */
    private fun broadcastPathOf(uri: Uri): String =
        pathOf(uri).substringAfter('/', missingDelimiterValue = "")

    /** The one leading segment a relay publishes under, with its separator, or empty. */
    private fun namespaceOf(uri: Uri): String =
        pathOf(uri).substringBefore('/').let { if (it.isEmpty()) "" else "/$it" }

    private fun pathOf(uri: Uri): String = uri.encodedPath.orEmpty().trimStart('/')
}

/** One `MoqSession` and the `MoqBroadcastConsumer` over it, closed in that order. */
private class UniffiBroadcastSession(
    private val client: MoqClient,
    /** Held because it is what #368 polls: `stats()` is a snapshot taken from the session. */
    private val session: MoqSession,
    private val broadcast: MoqBroadcastConsumer,
) : MoqBroadcastSession {

    override fun catalog(): MoqCatalog {
        val consumer = runBlocking { broadcast.subscribeCatalog() }
        // Closed as soon as the first catalog has been read: `FrameSink.onTracks` is called once
        // with every track a subscription delivers (obligation 2), so a republished catalog has
        // nowhere to go and a consumer left open would be a subscription nothing drains.
        return consumer.use { firstCatalogOf(it) }
    }

    override fun subscribe(trackName: String, container: MoqContainer): MoqTrackStream =
        UniffiTrackStream(runBlocking { broadcast.subscribeMedia(trackName, container, liveEdge()) })

    /**
     * `MoqSession::stats()`, stamped and renamed.
     *
     * The only binding call on this path that is **not** `suspend`, so there is no `runBlocking`
     * here and the poll is a plain FFI round trip. What the record's own KDoc argues is the naming:
     * the bindings' `bytesLost` and `packetsLost` are the connection's, and cross this line as
     * `transportBytesLost` and `transportPacketsLost` so that nothing downstream reads them as media
     * that failed to arrive. The counters are unsigned across the FFI and signed on this side; the
     * conversion is exact for every value a session can reach, since 2^63 bytes is more than any
     * connection moves. A field the session did not report stays **null** across this line rather
     * than becoming a zero, which is [MoqSessionStatistics]' rule about absence applied to each of
     * the nine.
     */
    override fun statistics(): MoqSessionStatistics = session.stats().let { stats ->
        MoqSessionStatistics(
            sampledAtMs = SystemClock.elapsedRealtime(),
            roundTripTimeUs = stats.rttUs?.toLong(),
            sendRateBps = stats.sendRateBps?.toLong(),
            receiveRateBps = stats.recvRateBps?.toLong(),
            bytesSent = stats.bytesSent?.toLong(),
            bytesReceived = stats.bytesReceived?.toLong(),
            packetsSent = stats.packetsSent?.toLong(),
            packetsReceived = stats.packetsReceived?.toLong(),
            transportBytesLost = stats.bytesLost?.toLong(),
            transportPacketsLost = stats.packetsLost?.toLong(),
        )
    }

    override fun close() {
        broadcast.closeQuietly()
        session.closeQuietly()
        client.closeQuietly()
    }

    /**
     * The first catalog the publisher sends, blocking until it does.
     *
     * `next()` answering null is a publisher that closed the catalog track without ever populating
     * it, which is a broadcast with nothing to play and is refused as itself rather than read as an
     * empty catalog — `MoqCatalogTracks` would then report "the catalog declares nothing", which is
     * a different fact about a different failure.
     */
    private fun firstCatalogOf(consumer: MoqCatalogConsumer): MoqCatalog =
        runBlocking { consumer.next() }
            ?: throw IOException("The MoQ broadcast closed its catalog track without publishing a catalog")
}

/** One rendition's `MoqMediaConsumer`, pulled one frame at a time. */
private class UniffiTrackStream(private val consumer: MoqMediaConsumer) : MoqTrackStream {

    override fun next(): MoqMediaFrame? = runBlocking { consumer.next() }

    override fun close() {
        // `cancel()` before `close()`, and that order is the half of this class that matters: it is
        // what unblocks a `next` parked on another thread, which is what makes `MoqFrameSource`'s
        // join finite and therefore obligation 9 true. Closing alone would release the handle and
        // leave the reader waiting for a frame that is never coming.
        consumer.cancel()
        consumer.closeQuietly()
    }
}

/**
 * A subscription at the live edge.
 *
 * Every field is the bindings' own default, which is what `MoqSubscription()` is: ADR-0018 rule 5
 * makes a realtime stream live and unseekable and core refuses a start position before this module
 * is reached, so there is no group range to ask for and no latency target this library has any
 * business choosing on a publisher's behalf. It is written down once, here, so that the *absence* of
 * a choice is a stated thing rather than one implied at each subscription.
 */
private fun liveEdge(): MoqSubscription = MoqSubscription()
