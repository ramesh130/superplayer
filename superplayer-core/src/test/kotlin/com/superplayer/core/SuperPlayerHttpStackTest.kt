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

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.net.MalformedURLException

/**
 * Phase 10's tracer bullet: HLS and DASH played over an [HttpTransport] a *consumer* wrote.
 *
 * ## Why the test transport is the origin
 *
 * Every other playback test in this repository substitutes Media3's `FakeDataSource` exactly where
 * the HTTP stack goes (`docs/testing.md`), which makes this seam invisible to all of them — the
 * substitution replaces the thing under test. So this class builds its players on the real transfer
 * chain, as [SuperPlayerTransferChainTest] does, and puts the synthetic streams behind an
 * [HttpTransport] that serves them from memory under `https:` URIs.
 *
 * That is not a shortcut, it is the only route. Nothing in `superplayer-testkit` can express a
 * **206** — `FaultScript.failWithHttpStatus` takes a status in `400..599`, and the fake origins
 * serve a `DataSpec`'s slice, which is a length-aware read rather than a protocol-level partial
 * response. A transport that *is* the origin controls both sides of the exchange, so a range can be
 * answered as the protocol answers one and asserted as what the wire carried rather than as "it
 * played".
 *
 * ## Where it reads past the facade, and why
 *
 * Two of the five tests open the adapter's `DataSource` directly, through the internal member on an
 * [HttpStack] a consumer holds. `docs/testing.md`'s rule is that nothing asserts past the facade
 * about *playback*, and these assert about a range request, which no playback here can produce:
 * Media3 asks for a byte range when a DASH representation carries an index or an HLS segment an
 * `EXT-X-BYTERANGE`, and the synthetic streams — a `SegmentList` of whole segments, a playlist of
 * whole segments — carry neither. Adding one to `superplayer-testmedia` to reach the assertion
 * through a player would change the corpus every other module's tests are recorded against, to
 * observe something one call states exactly.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerHttpStackTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /** Where the one `file:` stream of the pay-nothing count is written, and what removes it. */
    @get:Rule
    val streamDirectory: TemporaryFolder = TemporaryFolder()

    @Test
    fun hlsPlaysOverATransportTheConsumerWrote() {
        val transport = ServingTransport(ServingTransport.hlsOverHttps())
        val player = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.of(transport))

        player.setMediaItem(MediaItem.fromUri(ServingTransport.httpsFor(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(player.playerError).isNull()
        assertThat(player.duration).isEqualTo(SyntheticHlsStream.DURATION_MS)
        // Ready means the multivariant playlist, the media playlist and the segment all arrived, and
        // every one of them came out of the consumer's transport: nothing else served an `https:`
        // URI on this player.
        assertThat(transport.paths()).containsExactly("/master.m3u8", "/media.m3u8", "/segment0.aac")
    }

    @Test
    fun dashPlaysOverATransportTheConsumerWrote() {
        val transport = ServingTransport(ServingTransport.dashOverHttps())
        val player = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.of(transport))

        player.setMediaItem(MediaItem.fromUri(ServingTransport.httpsFor(SyntheticDashStream.MANIFEST_URI)))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(player.playerError).isNull()
        assertThat(player.duration).isEqualTo(SyntheticDashStream.DURATION_MS)
        // The MPD, the initialization segment and the media segment — the second protocol over the
        // same fifty lines of consumer code, which is the whole claim of the ticket.
        assertThat(transport.paths())
            .containsExactly("/dash/manifest.mpd", "/dash/init.mp4", "/dash/segment0.m4s")
    }

    /**
     * ADR-0016 rule 5, on the wire rather than by inference: a request for part of a resource is
     * asked for as a `Range` header, answered **206** with `Content-Range`, and the bytes that come
     * back are the bytes asked for.
     *
     * // spec: RFC 9110 §14.2 — a `Range` a server honours answers 206 Partial Content.
     */
    @Test
    fun aByteRangeIsAskedForAsARangeAndAnsweredAsPartialContent() {
        val resources = ServingTransport.hlsOverHttps()
        val transport = ServingTransport(resources)
        val uri = ServingTransport.segmentUriIn(resources)
        val segment = resources.getValue(uri)

        val source = HttpStack.of(transport).factory.createDataSource()
        val offset = 10L
        val length = 20L
        val announced = source.open(
            DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build(),
        )

        val read = ByteArray(length.toInt())
        var filled = 0
        while (filled < read.size) {
            val n = source.read(read, filled, read.size - filled)
            check(n > 0) { "The range ended after $filled of ${read.size} bytes" }
            filled += n
        }
        source.close()

        val asked = transport.requests.single()
        assertThat(asked.rangeHeaderValue).isEqualTo("bytes=10-29")
        assertThat(asked.status).isEqualTo(206)
        assertThat(asked.responseHeaders["Content-Range"])
            .containsExactly("bytes 10-29/${segment.size}")
        assertThat(announced).isEqualTo(length)
        assertThat(read).isEqualTo(segment.copyOfRange(offset.toInt(), (offset + length).toInt()))
    }

    /**
     * The other half of rule 5, and the reason it is worth a test: a transport that answers 200 and
     * the whole resource to a range asked from byte ten is refused rather than read past.
     *
     * Silently skipping the bytes is what would make such a transport look merely slow — the viewer
     * pays for every resource whole and the bandwidth estimate describes a link nobody is on. Here
     * it fails at `open`, which is where a broken implementation is cheapest to find.
     */
    @Test
    fun aTransportThatIgnoresARangeIsRefusedRatherThanReadPast() {
        val resources = ServingTransport.hlsOverHttps()
        val transport = ServingTransport(resources, honourRanges = false)
        val uri = ServingTransport.segmentUriIn(resources)

        val source = HttpStack.of(transport).factory.createDataSource()
        val failure = runCatching {
            source.open(DataSpec.Builder().setUri(uri).setPosition(10).setLength(20).build())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(HttpDataSource.HttpDataSourceException::class.java)
        assertThat(failure).hasMessageThat().contains("not 206")
        assertThat(transport.requests.single().status).isEqualTo(200)
    }

    /**
     * ADR-0016 rule 3: the harness's transport slot wins over a stack a consumer set, because the
     * two substitute for different things — the slot for the *network itself*, the stack for the
     * HTTP client over a real one.
     *
     * Counted rather than described: the stream is served from `fake:` URIs no real data source
     * could open, so reaching `STATE_READY` is only possible through the slot, and the consumer's
     * transport is asked nothing at all.
     */
    @Test
    fun theHarnessesTransportSlotWinsOverAStackAConsumerSet() {
        val transport = ServingTransport(ServingTransport.hlsOverHttps())
        val player = harness.buildPlayer(httpStack = HttpStack.of(transport))

        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(player.playerError).isNull()
        assertThat(transport.requests).isEmpty()
    }

    /**
     * ADR-0016 rule 14, counted: a player built without `setHttpStack` resolves to exactly the
     * factory composed before this seam existed — `DefaultDataSource.Factory(context,
     * DefaultHttpDataSource.Factory())`, both halves — and nothing of the consumer's is reached.
     *
     * Naming that factory is what the count has to do, and "the player did not play" would not do
     * it: a bottom that resolved *nothing* would fail the same way. So each half is named by
     * something only it can answer, and neither question touches a network.
     *
     * The **`DefaultDataSource`** half is named by a `file:` URI, which no HTTP stack of any kind
     * serves and which this player plays to `STATE_READY`.
     *
     * The **`DefaultHttpDataSource`** half is named by the exception the same player ends on for a
     * URI whose scheme `DefaultDataSource` answers itself for none of: it hands the request to
     * whatever base factory it was built with, and Media3's own builds a `java.net.URL` from the
     * URI, which raises `MalformedURLException` for a protocol the JVM has no handler for. Nothing
     * else in this chain calls `URL`, so that exception can have come from nowhere else, and a
     * bottom that resolved nothing would have named an unsupported scheme instead. The synthetic
     * streams' own `fake:` URIs are exactly such a scheme, which is why the probe needs no
     * vocabulary of its own — and why it costs no socket, no DNS query and no wall-clock second.
     *
     * The same URI handed to a player *with* a stack is the counter shown to see what it counts:
     * the consumer's transport is asked for all three resources and the player is ready.
     */
    @Test
    fun aPlayerBuiltWithoutAnHttpStackAsksNoConsumerTransportAndKeepsMediaThreesOwn() {
        val local = harness.buildPlayerOnItsOwnTransferChain()
        local.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.writeTo(streamDirectory.root)))
        local.prepare()
        TestPlayerRunHelper.advance(local).untilState(Player.STATE_READY)
        assertThat(local.playerError).isNull()

        val uri = SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI
        val without = harness.buildPlayerOnItsOwnTransferChain()
        without.setMediaItem(MediaItem.fromUri(uri))
        without.prepare()
        TestPlayerRunHelper.advance(without).untilPlayerError()

        assertThat(causesOf(without.playerError)).contains(MalformedURLException::class.java)

        val serving = ServingTransport(SyntheticHlsStream.resources())
        val with = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.of(serving))
        with.setMediaItem(MediaItem.fromUri(uri))
        with.prepare()
        TestPlayerRunHelper.advance(with).untilState(Player.STATE_READY)

        assertThat(with.playerError).isNull()
        assertThat(serving.paths()).containsExactly("/master.m3u8", "/media.m3u8", "/segment0.aac")
    }

    private companion object {

        /** Every class on a failure's cause chain, which is where the stack that raised it is named. */
        fun causesOf(error: Throwable?): List<Class<*>> =
            generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
                .map { it.javaClass }
                .toList()
    }
}
