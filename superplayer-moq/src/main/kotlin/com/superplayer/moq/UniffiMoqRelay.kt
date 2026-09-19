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
import kotlinx.coroutines.runBlocking
import uniffi.moq.MoqBroadcastConsumer
import uniffi.moq.MoqCatalog
import uniffi.moq.MoqCatalogConsumer
import uniffi.moq.MoqClient
import uniffi.moq.MoqContainer
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
 * here in every test is a scripted fake of [MoqRelay]. #367 is the ticket that first runs this
 * against a public relay from a device, and until it has, the address mapping below is read off
 * MoQ's own tooling rather than observed.
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
                runBlocking { session.consumer().requestBroadcast(broadcastPathOf(uri)) },
            )
        } catch (failure: Exception) {
            session.closeQuietly()
            client.closeQuietly()
            throw IOException("Connected to the MoQ relay but could not request the broadcast $uri", failure)
        }
    }

    /**
     * The relay's own address: the broadcast URI's authority under `https`.
     *
     * // spec: draft-ietf-moq-transport §3.1 — a MoQ session over WebTransport is established by a
     * // WebTransport `CONNECT`, whose URI is an `https` one naming the relay. That is why the
     * // scheme handed to the bindings is `https` and not [MoqFrameSource.SCHEME]: `moq://` is the
     * // spelling a *broadcast* is written in on this side, and nothing puts it on a wire.
     *
     * **The split between that origin and the broadcast name is not cited, because no public
     * document states it.** The bindings take the two separately — a URL to `connect` and a name to
     * `requestBroadcast` — and taking the authority for the first and the path for the second is a
     * derivation from that signature and from how a broadcast address is written, not a rule read
     * anywhere. It is the one thing in this module no test here can confirm, which is why it is
     * labelled rather than asserted: #367 is the first run against a real relay, and its report is
     * what corrects this or keeps it.
     */
    private fun connectUrlOf(uri: Uri): String = Uri.Builder()
        .scheme("https")
        .encodedAuthority(uri.encodedAuthority.orEmpty())
        .build()
        .toString()

    /** The broadcast's name at that relay: the URI's path, without its leading separator. */
    private fun broadcastPathOf(uri: Uri): String = uri.encodedPath.orEmpty().trimStart('/')
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
