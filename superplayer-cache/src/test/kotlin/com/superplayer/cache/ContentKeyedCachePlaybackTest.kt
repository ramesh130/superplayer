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

package com.superplayer.cache

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.abr.AdaptivePolicy
import com.superplayer.abr.BandwidthOracle
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.ChainBottom
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `PRD.md` F7 closed, from a consumer's side: real HLS played through a player with a
 * [ContentKeyedCache] in its chain, with what reached the network counted by the harness's origin —
 * which sits below every layer SuperPlayer composes, so a read the cache answered is never counted.
 */
@RunWith(AndroidJUnit4::class)
class ContentKeyedCachePlaybackTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val content = TestContent.hls(SEGMENTS)
    private val caches = mutableListOf<ContentKeyedCache>()

    @After
    fun releaseCaches() {
        caches.forEach { it.release() }
    }

    @Test
    fun theSameContentFromASecondHostIsServedFromTheCache() {
        val cache = openCache()
        val mirrored = content.servedFrom(MIRROR_HOST)

        val first = harness.buildPlayer(content = mirrored, cache = cache)
        first.setMediaRequest(request(CONTENT_ID, content.sourceUri))
        playToEnd(first)
        assertThat(segmentsFetched(first)).isEqualTo(SEGMENTS)
        assertWithMessage("hits on the cold play").that(cache.hitCount).isEqualTo(0)

        val second = harness.buildPlayer(content = mirrored, cache = cache)
        second.setMediaRequest(request(CONTENT_ID, mirrored.sourceUri))
        playToEnd(second)
        assertWithMessage("hits on the warm replay").that(cache.hitCount).isAtLeast(SEGMENTS.toLong())

        // The playlists came from the second host — a manifest is never a cache's to answer — and not
        // one segment did.
        val requests = harness.networkRequests(second)
        assertThat(requests.filter { it.kind == ResourceKind.MANIFEST }).isNotEmpty()
        assertThat(requests.all { it.uri.contains(MIRROR_HOST) }).isTrue()
        assertThat(segmentsFetched(second)).isEqualTo(0)
    }

    /**
     * The same for DASH, whose manifest and segments reach the cache through a different pair of
     * factories than HLS's: the MPD from the second host is fetched, and neither the initialization
     * segment nor a media segment is.
     */
    @Test
    fun theSameDashContentFromASecondHostIsServedFromTheCache() {
        val cache = openCache()
        val dash = TestContent.dash(SEGMENTS)
        val mirrored = dash.servedFrom(MIRROR_HOST)

        val first = harness.buildPlayer(content = mirrored, cache = cache)
        first.setMediaRequest(request(CONTENT_ID, dash.sourceUri))
        playToEnd(first)
        assertThat(segmentsFetched(first)).isEqualTo(SEGMENTS)

        val second = harness.buildPlayer(content = mirrored, cache = cache)
        second.setMediaRequest(request(CONTENT_ID, mirrored.sourceUri))
        playToEnd(second)

        val requests = harness.networkRequests(second)
        assertThat(requests.map { it.kind }.toSet()).containsExactly(ResourceKind.MANIFEST)
        assertThat(requests.all { it.uri.contains(MIRROR_HOST) }).isTrue()
    }

    @Test
    fun aDifferentContentIdAtAnIdenticalUrlIsAMiss() {
        val cache = openCache()

        val first = harness.buildPlayer(content = content, cache = cache)
        first.setMediaRequest(request("episode:one", content.sourceUri))
        playToEnd(first)

        val second = harness.buildPlayer(content = content, cache = cache)
        second.setMediaRequest(request("episode:two", content.sourceUri))
        playToEnd(second)

        assertThat(segmentsFetched(second)).isEqualTo(SEGMENTS)
    }

    /**
     * ADR-0010 rule 4's fallback: content set through `setMediaItem` has no content id, is keyed by its
     * URL — so the same URL again is a hit and another host's is not — and shares no entry with a
     * `MediaRequest` at the same URL, in either direction.
     */
    @Test
    fun contentSetAsAMediaItemIsKeyedByItsUrlAndNeverSharesAnEntryWithARequest() {
        val cache = openCache()
        val mirrored = content.servedFrom(MIRROR_HOST)

        val byUrl = harness.buildPlayer(content = mirrored, cache = cache)
        byUrl.setMediaItem(MediaItem.fromUri(content.sourceUri))
        playToEnd(byUrl)
        assertThat(segmentsFetched(byUrl)).isEqualTo(SEGMENTS)

        val byRequest = harness.buildPlayer(content = mirrored, cache = cache)
        byRequest.setMediaRequest(request(CONTENT_ID, content.sourceUri))
        playToEnd(byRequest)
        assertWithMessage("a request at a URL an item was cached under").that(segmentsFetched(byRequest)).isEqualTo(SEGMENTS)

        val sameUrl = harness.buildPlayer(content = mirrored, cache = cache)
        sameUrl.setMediaItem(MediaItem.fromUri(content.sourceUri))
        playToEnd(sameUrl)
        assertWithMessage("the same URL again").that(segmentsFetched(sameUrl)).isEqualTo(0)

        val otherHost = harness.buildPlayer(content = mirrored, cache = cache)
        otherHost.setMediaItem(MediaItem.fromUri(mirrored.sourceUri))
        playToEnd(otherHost)
        assertWithMessage("another host's URL").that(segmentsFetched(otherHost)).isEqualTo(SEGMENTS)
    }

    /**
     * ADR-0010 rule 4, proven with a real cache: a hit is read from a local file, which reports
     * itself as no network transfer, so an adaptive player's estimate after a warm replay is exactly
     * the estimate the cold play left. The cold play moving it first is what shows the observer sees
     * the estimate at all. One player plays both, so the replay is measured by the same policy's
     * oracle and meter that measured the cold play.
     *
     * The estimate is read through an oracle of the test's own, because the memory behind it is one
     * per process (ADR-0009 rule 8) — the policy's oracle and this one read the same windows.
     */
    @Test
    fun aWarmReplayLeavesTheThroughputEstimateWhereTheColdPlayLeftIt() {
        val cache = openCache()
        val player = harness.buildPlayer(
            content = content,
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = adaptivePolicy(),
            cache = cache,
        )
        val observer = BandwidthOracle.Builder(context).build()
        try {
            val untouched = observer.currentEstimate()
            player.setMediaRequest(request(CONTENT_ID, content.sourceUri))
            playToEnd(player)
            val afterColdPlay = observer.currentEstimate()
            val segmentRequestsAfterColdPlay = segmentRequests(player)
            assertThat(segmentsFetched(player)).isEqualTo(SEGMENTS)
            assertWithMessage("a cold play's segments are samples").that(afterColdPlay).isNotEqualTo(untouched)

            // The same request again, on the same player: a new source, whose segments the cache holds.
            player.setMediaRequest(request(CONTENT_ID, content.sourceUri))
            playToEnd(player)

            assertWithMessage("segment requests during the replay").that(segmentRequests(player)).isEqualTo(segmentRequestsAfterColdPlay)
            val afterWarmReplay = observer.currentEstimate()
            // Every number the estimate is made of is where the cold play left it. Only the newest
            // sample's age has moved, and it has only grown: a sample taken during the replay would
            // have reset it.
            assertThat(afterWarmReplay.copy(newestSampleAgeMs = afterColdPlay.newestSampleAgeMs)).isEqualTo(afterColdPlay)
            assertThat(afterWarmReplay.newestSampleAgeMs).isGreaterThan(afterColdPlay.newestSampleAgeMs)
        } finally {
            observer.release()
        }
    }

    /**
     * The cache-hit exclusion still means what it says over an HTTP client a consumer wrote —
     * ADR-0009 rule 8, and #310's other half of the estimate criterion.
     *
     * [aWarmReplayLeavesTheThroughputEstimateWhereTheColdPlayLeftIt] over the other bottom, and it is
     * a separate question from "the cache answered" because of *how* the exclusion works. A hit is
     * not a request that stopped short of the network: `CacheDataSource` reads it through a local
     * file, which is a `TransferListener` report like any other and is dropped only because it says
     * `isNetwork = false`. So the thing a consumer's transport could break is the *other* side of
     * that flag — an adapter reporting `false` would take every real fetch out of the estimate too,
     * and one reporting nothing at all would leave it at its cold default. The cold play moving the
     * estimate is what shows both halves are live before the replay is asked to leave it alone.
     */
    @Test
    fun aWarmReplayOverAConsumersTransportLeavesTheEstimateWhereItWas() {
        val cache = openCache()
        val player = harness.buildPlayer(
            content = content,
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = adaptivePolicy(),
            cache = cache,
            bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT,
        )
        val observer = BandwidthOracle.Builder(context).build()
        try {
            val untouched = observer.currentEstimate()
            player.setMediaRequest(request(CONTENT_ID, content.sourceUri))
            playToEnd(player)
            val afterColdPlay = observer.currentEstimate()
            assertThat(segmentsFetched(player)).isEqualTo(SEGMENTS)
            assertWithMessage("a cold play over a consumer's transport is samples")
                .that(afterColdPlay).isNotEqualTo(untouched)

            player.setMediaRequest(request(CONTENT_ID, content.sourceUri))
            playToEnd(player)

            assertWithMessage("hits on the warm replay").that(cache.hitCount).isAtLeast(SEGMENTS.toLong())
            val afterWarmReplay = observer.currentEstimate()
            // Every number the estimate is made of is where the cold play left it; only the newest
            // sample's age has moved, and only grown, because a sample taken on a hit would have
            // reset it.
            assertThat(afterWarmReplay.copy(newestSampleAgeMs = afterColdPlay.newestSampleAgeMs)).isEqualTo(afterColdPlay)
            assertThat(afterWarmReplay.newestSampleAgeMs).isGreaterThan(afterColdPlay.newestSampleAgeMs)
        } finally {
            observer.release()
        }
    }

    /**
     * The cache sits below live-playlist revalidation and never holds a playlist: a live stream behind
     * an intermediary that freezes its playlist for ten minutes still plays on by reloading past it,
     * with the content cache in the chain, and the cache holds segments and no playlist afterwards.
     */
    @Test
    fun liveHlsKeepsRevalidatingItsPlaylistAndNoPlaylistIsCached() {
        val cache = openCache()
        val live = TestContent.liveHls()
        val player = harness.buildPlayer(
            content = live,
            faults = FaultScript.Builder().serveThroughCache(TEN_MINUTES_S, kind = ResourceKind.MANIFEST).build(),
            cache = cache,
        )
        player.setMediaRequest(request("channel:live", live.sourceUri))
        harness.playToReady(player)

        harness.advanceUntil(player, "segments past the first live window", LIVE_PLAYED_MS) {
            segmentsFetched(it) > SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT
        }

        assertThat(player.playerError).isNull()
        assertThat(harness.networkRequests(player).count { it.headers[CACHE_CONTROL] == "no-cache" }).isGreaterThan(0)
        assertThat(cache.keys()).isNotEmpty()
        assertThat(cache.keys().filter { it.endsWith(".m3u8") }).isEmpty()
    }

    /**
     * CMCD rides the requests that still reach the network: every one a cold play and a warm replay
     * sent carries a `sid`, and each player's `sid` is its own telemetry session id.
     */
    @Test
    fun everyRequestThatReachesTheNetworkCarriesTheTelemetrySessionIdAsCmcd() {
        val cache = openCache()

        for (replay in listOf("cold", "warm")) {
            val events = CopyOnWriteArrayList<TelemetryEvent>()
            val player = harness.buildPlayer(content = content, telemetry = QoeCollector(TelemetrySink { events += it }), cache = cache)
            player.setMediaRequest(request(CONTENT_ID, content.sourceUri))
            playToEnd(player)
            // Delivery is off the engine's threads by design (ADR-0008 rules 3 and 4).
            runMainLooperUntil { events.any { it is TelemetryEvent.SessionStarted } }
            val sessionId = events.filterIsInstance<TelemetryEvent.SessionStarted>().single().sessionId

            val requests = harness.networkRequests(player)
            assertWithMessage("$replay requests").that(requests).isNotEmpty()
            requests.forEach { sent ->
                assertWithMessage("$replay: $sent").that(sent.headers[CMCD_SESSION].orEmpty()).contains("sid=\"$sessionId\"")
            }
        }
    }

    /**
     * Eviction on what a player stores, with a replay counting as use: in a budget two and a half plays
     * deep, content played, other content played, and the first played again — from the cache — keeps
     * the first whole when a third arrives, and the one not played since is what gives way.
     */
    @Test
    fun fillingPastTheBudgetEvictsTheContentNotReplayed() {
        val onePlay = storedByOnePlay()
        val cache = openCache(maxBytes = onePlay.bytes * 5 / 2)

        play(cache, "episode:one")
        play(cache, "episode:two")
        assertWithMessage("segments fetched replaying episode one").that(segmentsFetched(play(cache, "episode:one"))).isEqualTo(0)
        play(cache, "episode:three")

        val held = entriesByContent(cache)
        assertWithMessage("entries by content: $held").that(held["episode:one"]).isEqualTo(onePlay.entries)
        assertWithMessage("entries by content: $held").that(held["episode:three"]).isEqualTo(onePlay.entries)
        assertWithMessage("entries by content: $held").that(held["episode:two"] ?: 0).isLessThan(onePlay.entries)
        assertThat(cache.heldBytes()).isAtMost(cache.maxBytes)
    }

    /** A pin holds against what players store: pinned content still plays with no segment fetched. */
    @Test
    fun pinnedContentStillPlaysFromTheCacheAfterEvictionMadeRoomAroundIt() {
        val onePlay = storedByOnePlay()
        val cache = openCache(maxBytes = onePlay.bytes * 3 / 2)

        play(cache, "download:kept")
        cache.pin("download:kept")
        play(cache, "episode:one")
        play(cache, "episode:two")

        assertThat(entriesByContent(cache)["download:kept"]).isEqualTo(onePlay.entries)
        assertThat(segmentsFetched(play(cache, "download:kept"))).isEqualTo(0)
    }

    /**
     * A budget changed between players on one directory: reopened smaller, the cache keeps the content
     * played last and a player plays it with no segment fetched — read back from the stored bytes, so
     * nothing kept was corrupted — while the content evicted to fit is fetched again rather than failing.
     */
    @Test
    fun playersOnADirectoryReopenedUnderASmallerBudgetPlayWhatWasKept() {
        val onePlay = storedByOnePlay()
        val directory = folder.newFolder()
        CachePolicy.contentKeyed(directory, onePlay.bytes * 4).apply {
            play(this, "episode:one")
            play(this, "episode:two")
            release()
        }

        val smaller = CachePolicy.contentKeyed(directory, onePlay.bytes * 3 / 2).also { caches += it }
        assertThat(entriesByContent(smaller)["episode:two"]).isEqualTo(onePlay.entries)
        assertWithMessage("segments fetched playing kept content").that(segmentsFetched(play(smaller, "episode:two"))).isEqualTo(0)
        assertWithMessage("segments fetched playing evicted content").that(segmentsFetched(play(smaller, "episode:one"))).isGreaterThan(0)
    }

    /**
     * The cache is untouched by an HTTP client a consumer wrote (ADR-0016 rule 8's other half, and
     * #310's last acceptance criterion).
     *
     * A cache keys on the content id and the URI *path* — never the host, the query or Media3's own
     * `DataSpec` key — so which client fetched the bytes cannot be part of an entry's identity.
     * Asserted as an equality of the whole key set rather than as "it played twice": a bottom that
     * reworded a request on its way out would fill a second set of entries, every replay would be a
     * miss, and the only symptom would be a cache that never seemed to help.
     *
     * The warm replay is here to show the entries are read back and not merely written: every
     * segment is a hit and none is fetched again. What a hit does to the *estimate* is a separate
     * question and a separate test — [aWarmReplayOverAConsumersTransportLeavesTheEstimateWhereItWas]
     * — because the mechanism is not "the request never got this far": a hit is read through a local
     * file that reports `isNetwork = false`, which is a transfer the meter sees and drops.
     */
    @Test
    fun theSameContentBehindAConsumersTransportIsTheSameCacheEntry() {
        val overTheSlot = openCache()
        val first = harness.buildPlayer(content = content, cache = overTheSlot)
        first.setMediaRequest(request(CONTENT_ID, content.sourceUri))
        playToEnd(first)

        val overATransport = openCache()
        val cold = harness.buildPlayer(
            content = content,
            cache = overATransport,
            bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT,
        )
        cold.setMediaRequest(request(CONTENT_ID, content.sourceUri))
        playToEnd(cold)

        assertWithMessage("hits on the cold play over a consumer's transport")
            .that(overATransport.hitCount).isEqualTo(0)
        assertWithMessage("the entries a consumer's transport filled")
            .that(overATransport.keys().toSet()).isEqualTo(overTheSlot.keys().toSet())

        val warm = harness.buildPlayer(
            content = content,
            cache = overATransport,
            bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT,
        )
        warm.setMediaRequest(request(CONTENT_ID, content.sourceUri))
        playToEnd(warm)

        assertWithMessage("hits on the warm replay").that(overATransport.hitCount).isAtLeast(SEGMENTS.toLong())
        assertWithMessage("segments that reached the consumer's transport").that(segmentsFetched(warm)).isEqualTo(0)
    }

    private fun openCache(maxBytes: Long = MAX_BYTES): ContentKeyedCache =
        CachePolicy.contentKeyed(folder.newFolder(), maxBytes).also { caches += it }

    private fun play(cache: ContentKeyedCache, contentId: String): SuperPlayer =
        harness.buildPlayer(content = content, cache = cache).also {
            it.setMediaRequest(request(contentId, content.sourceUri))
            playToEnd(it)
        }

    /** What one play of [content] leaves in a cache with room for all of it. */
    private fun storedByOnePlay(): Stored {
        val probe = openCache()
        play(probe, "probe")
        return Stored(bytes = probe.heldBytes(), entries = probe.keys().size)
    }

    private data class Stored(val bytes: Long, val entries: Int)

    private fun entriesByContent(cache: ContentKeyedCache): Map<String?, Int> =
        cache.keys().groupingBy(ContentKeys::contentIdOf).eachCount()

    private fun adaptivePolicy() = AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND)

    private fun request(contentId: String, uri: String): MediaRequest = MediaRequest.Builder(contentId).addSource(uri).build()

    private fun playToEnd(player: SuperPlayer) {
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content") { it.playbackState == Player.STATE_ENDED || it.playerError != null }
        assertWithMessage("cause: ${player.playerError?.cause}").that(player.playerError).isNull()
    }

    /** Every segment request sent to the network so far, repeats included. */
    private fun segmentRequests(player: Player): Int =
        harness.networkRequests(player).count { it.kind == ResourceKind.MEDIA_SEGMENT }

    /** Distinct segments fetched from the network, so a re-opened range is not a second segment. */
    private fun segmentsFetched(player: Player): Int =
        harness.networkRequests(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.uri }.toSet().size

    private companion object {
        const val SEGMENTS = 4
        const val CONTENT_ID = "episode:cached"
        const val MIRROR_HOST = "mirror.superplayer.test"

        /** Far more than four short audio segments, so nothing in these tests is ever evicted. */
        const val MAX_BYTES = 16L * 1024 * 1024

        /** The intermediary's rule from `LivePlaylistRevalidationTest`: a whole path held for ten minutes. */
        const val TEN_MINUTES_S = 600L

        /** Long enough to play past the first live window by reloading past the frozen playlist. */
        const val LIVE_PLAYED_MS = 30_000L

        const val CACHE_CONTROL = "Cache-Control"

        // spec: CTA-5004 §2.1 — the header shard carrying the session keys, `sid` among them.
        const val CMCD_SESSION = "CMCD-Session"
    }
}
