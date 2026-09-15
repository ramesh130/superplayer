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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The core half of ADR-0010: the transfer chain's cache slot, the content identity its key seam can
 * read, and the preload entry into engine construction — driven with a hand-written cache here,
 * because `superplayer-cache`'s real one is a later issue and core's seam has to hold before it.
 *
 * The recording cache below answers nothing. It sits in the slot, sees every request the engine makes
 * through it, and forwards everything, which is exactly what the slot has to let a real cache see.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerContentCacheTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private val bothProtocols = SyntheticDashStream.addTo(SyntheticHlsStream.addTo(FakeDataSet()))

    /**
     * ADR-0010 rule 13, counted: a player built without a cache stamps no request with an identity
     * and lays none on its item. The same count on a player *with* one is non-zero, so the counter is
     * shown to see what it counts.
     *
     * The rule's other half — that a core-only chain is unchanged — is held by the golden traces in
     * `superplayer-telemetry`, which are core-only sessions and which this change leaves byte-identical.
     * Preload's half is counted in `PooledEngineTest`.
     */
    @Test
    fun aPlayerBuiltWithoutACacheStampsNothingAndACachedOneStampsEveryRequest() {
        val uncachedMeter = RecordingBandwidthMeter()
        val uncached = harness.buildPlayer(alsoConfigureEngine = { it.setBandwidthMeter(uncachedMeter) })
        playUntilReady(uncached, hlsRequest("episode:uncached"))

        assertThat(uncachedMeter.openedRequests()).isNotEmpty()
        assertThat(uncachedMeter.openedRequests().mapNotNull { ContentIdentity.of(it) }).isEmpty()
        assertThat(uncached.currentMediaItem?.localConfiguration?.tag).isNull()

        val cachedMeter = RecordingBandwidthMeter()
        val cached = harness.buildPlayer(
            cache = RecordingContentCache(),
            alsoConfigureEngine = { it.setBandwidthMeter(cachedMeter) },
        )
        playUntilReady(cached, hlsRequest("episode:cached"))

        assertThat(cachedMeter.openedRequests().map { ContentIdentity.of(it) }).isNotEmpty()
        assertThat(cachedMeter.openedRequests().map { ContentIdentity.of(it) }.toSet())
            .containsExactly("episode:cached")
    }

    @Test
    fun theCacheSlotSeesTheContentIdOfEveryHlsLoad() {
        val cache = RecordingContentCache()
        val player = harness.buildPlayer(fakeDataSet = bothProtocols, cache = cache)

        playUntilReady(player, hlsRequest("episode:hls"))

        val seen = cache.recorder.seen()
        assertThat(seen.map { it.contentId }.toSet()).containsExactly("episode:hls")
        // The playlist and a media segment both: the key seam sees manifests and representations.
        assertThat(seen.map { it.uri.toString() }).contains(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
        assertThat(seen.any { it.uri.path.orEmpty().endsWith(SyntheticHlsStream.SEGMENT_SUFFIX) }).isTrue()
    }

    @Test
    fun theCacheSlotSeesTheContentIdOfEveryDashLoad() {
        val cache = RecordingContentCache()
        val player = harness.buildPlayer(fakeDataSet = bothProtocols, cache = cache)

        playUntilReady(player, MediaRequest.Builder("episode:dash").addSource(SyntheticDashStream.MANIFEST_URI).build())

        val seen = cache.recorder.seen()
        assertThat(seen.map { it.contentId }.toSet()).containsExactly("episode:dash")
        assertThat(seen.map { it.uri.toString() }).contains(SyntheticDashStream.MANIFEST_URI)
        assertThat(seen.any { it.uri.toString() != SyntheticDashStream.MANIFEST_URI }).isTrue()
    }

    /**
     * What a cache may answer is on the request: the playlists and the MPD are manifests, the
     * segments are media — for both protocols, and for content set as a `MediaItem` too, so a live
     * playlist is never a cache's to store whichever way the content arrived.
     */
    @Test
    fun theCacheSlotIsToldWhichLoadsAreManifestsAndWhichAreMedia() {
        val cache = RecordingContentCache()
        val hls = harness.buildPlayer(fakeDataSet = bothProtocols, cache = cache)
        playUntilReady(hls, hlsRequest("episode:hls"))
        val dash = harness.buildPlayer(fakeDataSet = bothProtocols, cache = cache)
        dash.setMediaItem(MediaItem.fromUri(SyntheticDashStream.MANIFEST_URI))
        dash.prepare()
        TestPlayerRunHelper.advance(dash).untilState(Player.STATE_READY)

        val seen = cache.recorder.seen()
        val manifests = seen.filter { it.kind == LoadKind.MANIFEST }.map { it.uri.toString() }.toSet()
        assertThat(manifests).containsAtLeast(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI, SyntheticDashStream.MANIFEST_URI)
        assertThat(manifests.all { it.endsWith(".m3u8") || it.endsWith(".mpd") }).isTrue()
        val media = seen.filter { it.kind == LoadKind.MEDIA }.map { it.uri.toString() }
        assertThat(media.any { it.endsWith(SyntheticHlsStream.SEGMENT_SUFFIX) }).isTrue()
        assertThat(media.any { it.startsWith(SyntheticDashStream.MANIFEST_URI.substringBeforeLast('/')) }).isTrue()
        assertThat(media.none { it.endsWith(".m3u8") || it.endsWith(".mpd") }).isTrue()
        assertThat(seen.filter { it.kind == LoadKind.UNCLASSIFIED }).isEmpty()
    }

    /** ADR-0010 rule 4: content with no `MediaRequest` has no identity, and the key seam is told so. */
    @Test
    fun contentSetAsAMediaItemReachesTheCacheSlotWithNoIdentity() {
        val cache = RecordingContentCache()
        val player = harness.buildPlayer(cache = cache)

        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(cache.recorder.seen()).isNotEmpty()
        assertThat(cache.recorder.seen().mapNotNull { it.contentId }).isEmpty()
    }

    /**
     * `TransferChain`'s rule for every layer: the engine's transfer listener reaches the transport
     * through the slot and the identity stamp above it, so a meter still sees each media transfer —
     * and sees the same requests the cache did.
     */
    @Test
    fun transfersThroughTheCacheSlotStillReachTheBandwidthMeter() {
        val cache = RecordingContentCache()
        val meter = RecordingBandwidthMeter()
        val player = harness.buildPlayer(cache = cache, alsoConfigureEngine = { it.setBandwidthMeter(meter) })

        playUntilReady(player, hlsRequest("episode:measured"))

        assertThat(meter.segmentRequest()).isNotNull()
        assertThat(cache.recorder.seen().map { it.uri })
            .containsAtLeastElementsIn(meter.openedRequests().map { it.uri })
    }

    private fun hlsRequest(contentId: String): MediaRequest =
        MediaRequest.Builder(contentId).addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI).build()

    private fun playUntilReady(player: SuperPlayer, request: MediaRequest) {
        player.setMediaRequest(request)
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
    }

    /** A cache that caches nothing: it fills the slot with [recorder] and is otherwise empty. */
    private class RecordingContentCache(val recorder: RecordingCacheLayer = RecordingCacheLayer()) : ContentCache(recorder)

    /** One request as the key seam saw it. */
    private data class SeenRequest(val uri: Uri, val contentId: String?, val kind: LoadKind)

    /** Records what each request carries on its way to the transport, and changes nothing about it. */
    private class RecordingCacheLayer : CacheLayer {

        /** Guarded because loads open on loader threads and assertions run on the test's. */
        private val seen = mutableListOf<SeenRequest>()

        fun seen(): List<SeenRequest> = synchronized(seen) { seen.toList() }

        override fun over(upstream: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { Recording(upstream.createDataSource()) }

        // Every method written out rather than delegated with `by`: `getResponseHeaders` is a Java
        // default method, which Kotlin delegation would not forward (ADR-0003's trap).
        private inner class Recording(private val upstream: DataSource) : DataSource {

            override fun addTransferListener(transferListener: TransferListener) {
                upstream.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                synchronized(seen) { seen += SeenRequest(dataSpec.uri, ContentIdentity.of(dataSpec), LoadKind.of(dataSpec)) }
                return upstream.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

            override fun getUri(): Uri? = upstream.uri

            override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

            override fun close() {
                upstream.close()
            }
        }
    }
}
