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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The three obligations ADR-0016 leaves on a consumer's [HttpTransport] besides its byte ranges and
 * its statuses: the URI it reports after a redirect (rule 6), the content coding it must not
 * negotiate for itself (rule 7), and what a cancelled request does instead of blocking (rule 10).
 *
 * Each of the three fails **silently** in production, which is why each is written here as the
 * difference between a transport that keeps it and one that does not, rather than as an assertion
 * that the right thing happened. A test that only ever ran the correct transport would pass over an
 * adapter that had stopped asking for any of it.
 *
 * The gzip half of rule 7 is not here but in `superplayer-abr`'s `TransparentGzipEstimateTest`,
 * because its cost is a number only a bandwidth meter can show and this module has none.
 *
 * ## Why a test transport is the origin, again
 *
 * [SuperPlayerHttpStackTest]'s KDoc makes the argument in full and it holds unchanged here, with one
 * addition specific to this ticket: nothing in `superplayer-testkit` can express a **3xx** at all —
 * `FaultScript.failWithHttpStatus` takes a status in `400..599` — or a `Content-Encoding`, or a body
 * that does not return. A transport that is the origin can answer all three directly and offline.
 *
 * ## Where it reads past the facade, and why
 *
 * Three tests open the adapter's `DataSource` through the internal member on an [HttpStack], for
 * `docs/testing.md`'s stated reason: each asserts about the *request* — the headers composed onto
 * it, the URI a response reported, a `close` racing a `read` — and none of those is a claim about
 * playback that a player could make instead. The claims that *are* about playback — a redirected
 * stream that plays, a seek and a release that let a load go — are made through a real
 * [SuperPlayer] on the real transfer chain.
 */
@RunWith(AndroidJUnit4::class)
class TransportContractTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    // ADR-0016 rule 6 — redirects.

    /**
     * The claim, end to end: a multivariant playlist a CDN redirects plays, because its relative
     * references are resolved against the address it was **read from** rather than the address it
     * was asked for.
     *
     * This is not an assertion about a getter. Media3's `ParsingLoadable` parses a document against
     * `DataSource.getUri()`, so what `HttpResponse.uri` decides is where the media playlist and the
     * segment are looked for — and the paths the transport records are that decision on the wire:
     * the first request goes to the address the item named, and every one after it to the edge the
     * redirect landed on.
     */
    @Test
    fun aRedirectedPlaylistResolvesItsReferencesAgainstWhereItWasRead() {
        val transport = redirectingTransport(reportsFinalUri = true)
        val player = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.of(transport))

        player.setMediaItem(MediaItem.fromUri(REQUESTED_PLAYLIST))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(player.playerError).isNull()
        assertThat(player.duration).isEqualTo(SyntheticHlsStream.DURATION_MS)
        assertThat(transport.paths())
            .containsExactly("/master.m3u8", "/edge/v2/media.m3u8", "/edge/v2/segment0.aac")
            .inOrder()
    }

    /**
     * The same stream over the same CDN, served by a transport that followed the redirect and then
     * answered the URI it was *asked* for — the whole of the defect rule 6 names.
     *
     * Nothing about it looks like an error from the transport's side: the redirect was followed, the
     * playlist arrived, the status was 200. What the player does with it is go looking for
     * `/media.m3u8` at the address the content was moved away from, and end on the 404 that is not
     * there. A viewer sees content that will not start on a stream every byte of which was served
     * correctly.
     */
    @Test
    fun aTransportThatAnswersTheRequestedUriSendsThePlayerToTheOldAddress() {
        val transport = redirectingTransport(reportsFinalUri = false)
        val player = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.of(transport))

        player.setMediaItem(MediaItem.fromUri(REQUESTED_PLAYLIST))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilPlayerError()

        assertThat(player.playerError).isNotNull()
        // The second address is the tell: asked for at the address the playlist was *not* read from,
        // and answered 404 because the content is at the edge. Distinct, because Media3 spends the
        // media playlist's retries on it before giving up, and how many is not this test's claim.
        assertThat(transport.paths().distinct())
            .containsExactly("/master.m3u8", "/media.m3u8")
            .inOrder()
        assertThat(transport.requests.last().status).isEqualTo(ServingTransport.NOT_FOUND)
    }

    /**
     * Rule 6 at the seam it is discharged at: the adapter answers the URI the transport reported for
     * as long as the transfer is open, and null once it is closed — which is what Media3's own
     * stacks do, and what keeps `getUri` from disagreeing with `getResponseHeaders`.
     */
    @Test
    fun theAdapterReportsTheUriTheBytesCameFrom() {
        val transport = redirectingTransport(reportsFinalUri = true)

        val source = HttpStack.of(transport).factory.createDataSource()
        source.open(DataSpec.Builder().setUri(REQUESTED_PLAYLIST).build())
        val whileOpen = source.uri
        source.close()

        assertThat(whileOpen).isEqualTo(Uri.parse(EDGE_BASE + "master.m3u8"))
        assertThat(source.uri).isNull()
    }

    /**
     * And the ordinary case, which is the one that must not have become a redirect: a transport that
     * reports no URI of its own is taken at the URI it was asked for.
     */
    @Test
    fun aTransportThatFollowedNoRedirectIsTakenAtTheRequestedUri() {
        val resources = ServingTransport.hlsOverHttps()
        val uri = ServingTransport.httpsFor(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)

        val source = HttpStack.of(ServingTransport(resources)).factory.createDataSource()
        source.open(DataSpec.Builder().setUri(uri).build())
        val whileOpen = source.uri
        source.close()

        assertThat(whileOpen).isEqualTo(Uri.parse(uri))
    }

    // ADR-0016 rule 7 — content coding.

    /**
     * Rule 7's request half, asserted rather than described: the transport is handed the headers the
     * chain composed, plus the one the chain composes *for* it, and nothing else at all.
     *
     * `Accept-Encoding: identity` is the chain's own — "if the chain wants identity coding, the
     * chain says so" — and it is what switches off the transparent compression an HTTP client
     * otherwise negotiates on its own behalf. The exact-equality is the point: a `User-Agent`, a
     * `Connection` or a second coding added down here would each be a header no layer above decided,
     * and CMCD's are composed above and arrive in this same map.
     * // spec: RFC 9110 §12.5.3.
     */
    @Test
    fun theTransportIsSentTheChainsHeadersAndTheCodingTheChainAsksFor() {
        val resources = ServingTransport.hlsOverHttps()
        val transport = ServingTransport(resources)
        val uri = ServingTransport.httpsFor(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)

        val source = HttpStack.of(transport).factory.createDataSource()
        source.open(
            DataSpec.Builder()
                .setUri(uri)
                .setHttpRequestHeaders(mapOf("Authorization" to "Bearer token"))
                .build(),
        )
        source.close()

        assertThat(transport.requests.single().requestHeaders)
            .containsExactlyEntriesIn(
                mapOf("Authorization" to "Bearer token", "Accept-Encoding" to "identity"),
            )
    }

    /**
     * A coding a layer above named wins over the chain's default, in whatever case it spelled the
     * header (// spec: RFC 9110 §5.1 — field names are case-insensitive).
     *
     * Nothing in this library composes one today. The rule is here because the failure of the other
     * choice is silent in the same way everything else in this file is: a `HeaderProvider` reaching
     * an origin that requires a coding, quietly overwritten from the bottom of the chain, is a
     * harder defect to find than one asked-for coding reaching a transport.
     */
    @Test
    fun aCodingTheChainNamedItselfIsNotOverwritten() {
        val resources = ServingTransport.hlsOverHttps()
        val transport = ServingTransport(resources)
        val uri = ServingTransport.httpsFor(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)

        val source = HttpStack.of(transport).factory.createDataSource()
        source.open(
            DataSpec.Builder()
                .setUri(uri)
                .setHttpRequestHeaders(mapOf("accept-encoding" to "br"))
                .build(),
        )
        source.close()

        assertThat(transport.requests.single().requestHeaders)
            .containsExactlyEntriesIn(mapOf("accept-encoding" to "br"))
    }

    /**
     * And over a whole playback rather than one call: every request a real stream makes — the
     * multivariant playlist, the media playlist, the segment — asks for identity coding, because a
     * manifest gzips at least as well as media does and an estimate corrupted by a playlist is
     * corrupted just the same.
     */
    @Test
    fun everyRequestOfAPlaybackAsksForIdentityCoding() {
        val transport = ServingTransport(ServingTransport.hlsOverHttps())
        val player = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.of(transport))

        player.setMediaItem(
            MediaItem.fromUri(ServingTransport.httpsFor(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)),
        )
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(transport.requests).hasSize(3)
        transport.requests.forEach { exchange ->
            assertThat(exchange.requestHeaders["Accept-Encoding"]).isEqualTo("identity")
        }
    }

    // ADR-0016 rule 10 — cancellation.

    /**
     * The mechanism, on two threads because that is what it is: a request in flight on one thread is
     * cancelled by `close` on another, and the read that was waiting returns instead of finishing.
     *
     * The loader thread here is a real one, as Media3's is. Without the close the read would not
     * return at all — [StallingBody] answers nothing until it is closed or interrupted — so the
     * worker completing is the whole assertion, and the bound on the wait is what turns a regression
     * into a failing test rather than a hung build.
     */
    @Test
    fun closingAnInFlightRequestReturnsRatherThanWaitingForTheOrigin() {
        val body = StallingBody()
        val transport = StallingTransport(ServingTransport(ServingTransport.hlsOverHttps()), body)
        val source = HttpStack.of(transport).factory.createDataSource()
        val uri = ServingTransport.segmentUriIn(ServingTransport.hlsOverHttps())
        val finished = CountDownLatch(1)

        val loader = Thread {
            source.open(DataSpec.Builder().setUri(uri).build())
            runCatching { source.read(ByteArray(BUFFER_BYTES), 0, BUFFER_BYTES) }
            finished.countDown()
        }
        loader.start()
        assertThat(body.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

        source.close()

        assertThat(finished.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(body.closes.get()).isEqualTo(1)
    }

    /**
     * A **seek** abandons the load it was on, over a consumer's transport: the response the segment
     * was arriving on is closed while its read is still waiting.
     *
     * "While still waiting" is what makes it an abandonment rather than a finish, and it is why
     * [StallingBody] answers nothing: a close that reaches a body which never delivered a byte can
     * only have come from a load being let go. The player is asserted un-errored with it, so that a
     * cancel is being counted and not a failure that happened to close the same response.
     */
    @Test
    fun aSeekAbandonsAnInFlightLoadOverAConsumersTransport() {
        val body = StallingBody()
        val player = playerStallingOnItsSegment(body)

        player.prepare()
        RobolectricUtil.runMainLooperUntil { body.entered.count == 0L }
        // Into the *last* segment, so the seek leaves the chunk being loaded behind: a seek landing
        // inside the chunk in flight is one Media3 rightly finishes rather than cancels, and would
        // be a test of nothing.
        player.seekTo(SyntheticHlsStream.durationMs(SEGMENT_COUNT) - SyntheticHlsStream.SEGMENT_DURATION_MS)

        // Waited for by pumping rather than by blocking: a seek is handled on the playback thread,
        // whose fake clock only advances while this thread is running the main looper, so a bare
        // latch here would be a test that deadlocked itself.
        RobolectricUtil.runMainLooperUntil { body.closed.count == 0L }
        assertThat(body.closes.get()).isEqualTo(1)
        assertThat(player.playerError).isNull()
    }

    /**
     * And a **release**: the same claim at the end of a player's life, which is the one rule 10
     * names its cost against. A transport that held the thread here would hold the player, its
     * buffers and its surface for the length of a transfer belonging to a screen the viewer has
     * already left.
     */
    @Test
    fun aReleaseAbandonsAnInFlightLoadOverAConsumersTransport() {
        val body = StallingBody()
        val player = playerStallingOnItsSegment(body)

        player.prepare()
        RobolectricUtil.runMainLooperUntil { body.entered.count == 0L }
        player.release()

        assertThat(body.closed.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(body.closes.get()).isEqualTo(1)
    }

    private fun playerStallingOnItsSegment(body: StallingBody): SuperPlayer {
        val resources = ServingTransport.hlsOverHttps(SEGMENT_COUNT)
        val transport = StallingTransport(ServingTransport(resources), body)
        val player = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.of(transport))
        player.setMediaItem(
            MediaItem.fromUri(ServingTransport.httpsFor(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)),
        )
        return player
    }

    /**
     * A CDN that moves one document: the playlist is asked for at [REQUESTED_PLAYLIST] and the whole
     * stream lives under [EDGE_BASE], where the redirect lands.
     *
     * Nothing is served at the requested address but that one redirect, which is what makes the
     * second request's path the answer to "which URI did the player resolve against".
     */
    private fun redirectingTransport(reportsFinalUri: Boolean) = ServingTransport(
        resources = ServingTransport.hlsUnder(EDGE_BASE),
        redirects = mapOf(REQUESTED_PLAYLIST to EDGE_BASE + "master.m3u8"),
        reportsFinalUri = reportsFinalUri,
    )

    /**
     * A consumer's transport whose body for one resource does not come back — everything else is
     * [delegate]'s, because a stream still has to load its playlists to get as far as a segment.
     *
     * The stall is armed once: the load that meets it is the one being cancelled, and the retry
     * Media3 makes afterwards is served normally so that the test ends on a player in an ordinary
     * state rather than on a second blocked thread.
     */
    private class StallingTransport(
        private val delegate: ServingTransport,
        private val body: StallingBody,
    ) : HttpTransport {

        private val armed = AtomicInteger(1)

        override fun open(request: HttpRequest): HttpResponse {
            val answer = delegate.open(request)
            val isSegment = request.uri.toString().endsWith(SyntheticHlsStream.SEGMENT_SUFFIX)
            if (!isSegment || armed.getAndDecrement() <= 0) return answer
            answer.body.close()
            return HttpResponse(answer.status, answer.headers, body, answer.uri)
        }
    }

    /**
     * A response body that answers nothing until the request is cancelled: the transport rule 10 is
     * written for, minus the socket.
     *
     * It returns on either of the two things that cancel a load — the `close` core's adapter makes,
     * and the interrupt Media3 sends the loader thread it is cancelling — because a correct client's
     * stream answers both and this stands in for one. What it never does is deliver a byte, which
     * is what makes a [close] reaching it evidence that a load was let go rather than finished.
     */
    private class StallingBody : InputStream() {

        /** Counted down once a read is waiting, which is when a load is genuinely in flight. */
        val entered: CountDownLatch = CountDownLatch(1)

        /** Counted down by [close], which is the adapter reaching this transport. */
        val closed: CountDownLatch = CountDownLatch(1)

        /** How many times the adapter closed it; one is a cancellation, none is a held thread. */
        val closes: AtomicInteger = AtomicInteger()

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            entered.countDown()
            try {
                closed.await()
            } catch (_: InterruptedException) {
                // Restored, because swallowing it would hide the cancellation from the loader that
                // sent it, and then thrown as the I/O failure a cancelled read really is.
                Thread.currentThread().interrupt()
                throw InterruptedIOException("The request was cancelled")
            }
            return -1
        }

        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
        }

        private companion object {
            const val BYTE_MASK = 0xFF
        }
    }

    private companion object {

        /** Where the item points, and where nothing but the redirect lives. */
        const val REQUESTED_PLAYLIST = "https://superplayer.test/master.m3u8"

        /** Where the redirect lands, and where every byte of the stream actually is. */
        const val EDGE_BASE = "https://superplayer.test/edge/v2/"

        /**
         * Long enough that a seek can land in a segment other than the one being loaded, which is
         * what makes the load an abandoned one rather than a finished one.
         */
        const val SEGMENT_COUNT = 4

        /** Big enough that a read asks for more than the stalled body will ever give it. */
        const val BUFFER_BYTES = 1_024

        /**
         * How long a bounded wait waits before failing. Generous, because it is a *host* timeout and
         * not a network one: nothing here is waiting for bytes, only for a thread to be scheduled,
         * and the only thing this number decides is whether a regression reads as a failing test or
         * as a hung build.
         */
        const val WAIT_SECONDS = 20L
    }
}
