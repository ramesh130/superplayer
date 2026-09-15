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

package com.superplayer.preload

import android.media.MediaFormat
import android.net.Uri
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlayerPool
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.NetworkRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A feed under the harness: a [PlayerPool] with a [PreloadCoordinator] attached, one clock and one
 * link, and a scripted scroll. Every row is the same synthetic HLS stream served from a host of its
 * own, so which row a request was for is its host.
 *
 * The profile is `SHORT_FORM`, whose decision prefetches two rows ahead and one behind, each loaded to
 * one second — the first segment, since a segment here is two.
 */
@RunWith(AndroidJUnit4::class)
class PreloadCoordinatorPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val feed = Feed(rows = ROWS)
    private val coordinators = mutableListOf<PreloadCoordinator>()
    private val pools = mutableListOf<PlayerPool>()

    /** Room for two players, declared before any is built: the platform caches its codec list on first read. */
    @Before
    fun declareTheDeviceThisRunsOn() {
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        DeviceStatement.declareAppHeap(megabytes = 2048)
    }

    @After
    fun releaseTheScreen() {
        coordinators.forEach { it.release() }
        pools.forEach { it.release() }
    }

    /**
     * The claim the module exists for, measured the way a consumer measures it: the time to first
     * frame `QoeCollector` reports for the second row, from declared intent, on a shaped link. The
     * same row on the same link started cold takes longer.
     */
    @Test
    fun aPreloadedRowReachesItsFirstFrameInLessMediaTimeThanTheSameRowStartedCold() {
        val warm = timeToFirstFrameOfTheSecondRow(preloaded = true)
        val cold = timeToFirstFrameOfTheSecondRow(preloaded = false)

        assertWithMessage("warm $warm ms, cold $cold ms").that(warm).isLessThan(cold)
    }

    /**
     * Fetch priority follows the scroll direction and nothing else: at the same row 3, a feed moving
     * forward fetches the rows below it first, nearest first, then the one above; a feed moving back
     * fetches the rows above it first and the one below last. Observed as the order of each row's first
     * segment request, on two screens that differ only in direction.
     */
    @Test
    fun reversingTheScrollReordersWhichFirstSegmentIsFetchedNext() {
        assertThat(firstSegmentOrderAtRowThree(velocityItemsPerSecond = 1f)).containsExactly(4, 5, 2).inOrder()
        assertThat(firstSegmentOrderAtRowThree(velocityItemsPerSecond = -1f)).containsExactly(2, 1, 4).inOrder()
    }

    /** Velocity: a fling from row 3 at six rows a second is headed three rows on, and the rows it passes are not fetched. */
    @Test
    fun aFlingFetchesWhereItWillLandRatherThanTheRowsItPasses() {
        assertThat(firstSegmentOrderAtRowThree(velocityItemsPerSecond = 6f)).containsExactly(6, 7, 2).inOrder()
    }

    private fun firstSegmentOrderAtRowThree(velocityItemsPerSecond: Float): List<Int> {
        val (pool, preload, player) = feedScreen()
        preload.setScrollPosition(3, velocityItemsPerSecond)
        preload.setItems(feed.requests)
        harness.advanceUntil(player, "three rows' first segments") { feed.firstSegmentOrder(harness.networkRequests(pool)).size >= 3 }
        // A step more, so a fourth would show if the window were wider than the decision.
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)
        return feed.firstSegmentOrder(harness.networkRequests(pool))
    }

    /** Advances until each of [rows] has had its first segment requested since request [after]. */
    private fun awaitFirstSegments(player: SuperPlayer, pool: PlayerPool, rows: Set<Int>, after: Int = 0) {
        harness.advanceUntil(player, "first segments of rows $rows") {
            feed.firstSegmentOrder(harness.networkRequests(pool).drop(after)).containsAll(rows)
        }
    }

    /**
     * A prefetched row handed to a player is the row `setMediaRequest` would have played: it starts from
     * what was fetched — no manifest of it is fetched again — its prefetched requests carried the CMCD
     * `cid` of its content and the `sid` its telemetry session then reports, and a prefetched row
     * returned to later resumes where it was left.
     */
    @Test
    fun aPreloadedRowHandedToAPlayerCarriesItsIdentity() {
        val events = CopyOnWriteArrayList<TelemetryEvent>()
        val (pool, preload, player) = feedScreen(telemetry = { QoeCollector(TelemetrySink { events += it }) })
        preload.setItems(feed.requests)

        player.setMediaRequest(feed.requests[0])
        harness.playToReady(player)
        awaitFirstSegments(player, pool, rows = setOf(1, 2))
        val prefetched = harness.networkRequests(pool).filter { feed.rowOf(it) == 1 }
        val manifestsFetchedAhead = feed.manifestsFor(1, harness.networkRequests(pool))

        preload.setScrollPosition(1)
        player.setMediaRequest(feed.requests[1])
        harness.playToReady(player)
        assertWithMessage("row 1's manifests, fetched again on a warm start").that(feed.manifestsFor(1, harness.networkRequests(pool)))
            .isEqualTo(manifestsFetchedAhead)
        runMainLooperUntil { events.any { it is TelemetryEvent.SessionStarted && it.contentId == feed.contentId(1) } }
        val session = events.filterIsInstance<TelemetryEvent.SessionStarted>().single { it.contentId == feed.contentId(1) }

        assertThat(prefetched.map { cmcdKey(it, "sid") }.toSet()).containsExactly(session.sessionId)
        assertThat(prefetched.map { cmcdKey(it, "cid") }.toSet()).containsExactly(feed.contentId(1))

        // Row 1 plays a while, row 2 takes over, and row 1 — behind the feed now, so prefetched again —
        // resumes where it was left. Its manifest is fetched again only by that prefetch: the player
        // playing it had no reason to refetch an on-demand playlist, and marks the log before the move.
        harness.advanceTimeInStepsMs(player, ROW_PLAYED_MS)
        val leftRowOneAt = player.currentPosition
        val beforeTheMove = harness.networkRequests(pool).size
        preload.setScrollPosition(2)
        player.setMediaRequest(feed.requests[2])
        harness.playToReady(player)
        harness.advanceUntil(player, "row 1 prefetched again behind the feed") {
            feed.manifestsFor(1, harness.networkRequests(pool).drop(beforeTheMove)) > 0
        }
        player.setMediaRequest(feed.resuming(1))
        assertThat(player.currentPosition).isEqualTo(leftRowOneAt)
    }

    /**
     * Recycling the row that took a prefetched source lets that prefetch go: brought back into the
     * window, the row is prefetched again, manifest and all, rather than found still held.
     */
    @Test
    fun recyclingARowReleasesItsPreload() {
        val (pool, preload, player) = feedScreen()
        preload.setItems(feed.requests)
        awaitFirstSegments(player, pool, rows = setOf(1, 2))
        preload.setScrollPosition(1)
        player.setMediaRequest(feed.requests[1])
        harness.playToReady(player)
        val manifestsBefore = feed.manifestsFor(1, harness.networkRequests(pool))

        pool.recycle(player)
        preload.setScrollPosition(0)

        // Bounded: a preload still held is never fetched again, and the wait fails saying so.
        harness.advanceUntil(checkNotNull(pool.acquire()), "row 1 prefetched again") {
            feed.manifestsFor(1, harness.networkRequests(pool)) > manifestsBefore
        }
    }

    /**
     * Nothing prefetched outlives [PreloadCoordinator.release]: a prefetch in flight on a slow link
     * fetches nothing more, and a row it had prefetched then plays cold.
     */
    @Test
    fun nothingPreloadedOutlivesTheCoordinatorsRelease() {
        val (pool, preload, player) = feedScreen(network = NetworkProfile.THREE_G)
        preload.setItems(feed.requests)
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)
        val manifestsForRowOne = feed.manifestsFor(1, harness.networkRequests(pool))
        assertThat(manifestsForRowOne).isGreaterThan(0)

        preload.release()
        val atRelease = harness.networkRequests(pool).size
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)
        assertThat(harness.networkRequests(pool).drop(atRelease)).isEmpty()

        player.setMediaRequest(feed.requests[1])
        harness.playToReady(player)
        assertThat(feed.manifestsFor(1, harness.networkRequests(pool))).isGreaterThan(manifestsForRowOne)
    }

    /**
     * A pool without a coordinator fetches only the row it plays; the same pool with one fetches the
     * rows ahead too, so the count is shown to see what it counts (ADR-0010 rule 13).
     */
    @Test
    fun aPoolWithoutACoordinatorFetchesNothingAhead() {
        val plain = buildPool()
        val plainPlayer = checkNotNull(plain.acquire())
        plainPlayer.setMediaRequest(feed.requests[0])
        harness.playToReady(plainPlayer)
        harness.advanceTimeInStepsMs(plainPlayer, PREFETCH_MS)
        assertThat(harness.networkRequests(plain).mapNotNull { feed.rowOf(it) }.toSet()).containsExactly(0)
        plain.release()

        val (pool, preload, player) = feedScreen()
        preload.setItems(feed.requests)
        player.setMediaRequest(feed.requests[0])
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)
        assertThat(harness.networkRequests(pool).mapNotNull { feed.rowOf(it) }.toSet()).containsAtLeast(0, 1, 2)
    }

    /** ADR-0010 rule 8, from a consumer's side. */
    @Test
    fun aCoordinatorAttachedAfterThePoolsFirstPlayerIsRefused() {
        val pool = buildPool()
        checkNotNull(pool.acquire())

        val refused = assertThrows(IllegalStateException::class.java) { PreloadCoordinator.Builder(pool).build() }
        assertThat(refused).hasMessageThat().contains("ADR-0010 rule 8")
    }

    /**
     * The second row's time to first frame. Over the harness's described video, because the synthetic
     * HLS stream the other tests serve is audio and renders no frame; under a trace, described content
     * loads its chunks through the shaped link, so a cold start pays for them and a warm one does not.
     */
    private fun timeToFirstFrameOfTheSecondRow(preloaded: Boolean): Long {
        val events = CopyOnWriteArrayList<TelemetryEvent>()
        val video = TestContent.video()
        val rows = (0 until 3).map { MediaRequest.Builder("feed:video$it").addSource(video.sourceUri).build() }
        val pool = buildPool(network = NetworkProfile.STABLE_WIFI, content = video, telemetry = { QoeCollector(TelemetrySink { events += it }) })
        val preload = if (preloaded) PreloadCoordinator.Builder(pool).build().also { coordinators += it } else null
        preload?.setItems(rows)

        val first = checkNotNull(pool.acquire())
        first.setMediaRequest(rows[0])
        harness.playToReady(first)
        // The same span in both arms, not a wait for anything: the time a viewer spends on a row before
        // swiping is the scenario, and a warm arm that waited longer would be measuring a different one.
        harness.advanceTimeInStepsMs(first, PREFETCH_MS)
        pool.recycle(first)

        // The feed's order: the position moves, then the row is handed a player.
        preload?.setScrollPosition(1)
        val second = checkNotNull(pool.acquire())
        harness.attachVideoOutput(second)
        second.declarePlaybackIntent()
        second.setMediaRequest(rows[1])
        harness.playToReady(second)
        val contentId = rows[1].contentId
        harness.advanceUntil(second, "the second row's first frame") {
            events.any { it is TelemetryEvent.FirstFrameRendered && it.contentId == contentId }
        }
        return events.filterIsInstance<TelemetryEvent.FirstFrameRendered>().single { it.contentId == contentId }.timeToFirstFrameMs
    }

    private fun buildPool(
        network: NetworkProfile? = null,
        telemetry: () -> com.superplayer.core.TelemetryCollector? = { null },
        content: TestContent = feed.content,
    ): PlayerPool = harness.buildPool(
        maxSize = 2,
        profile = PlaybackProfile.SHORT_FORM,
        content = content,
        telemetry = telemetry,
        network = network?.trace,
    ).also { pools += it }

    /** A pool, a coordinator attached to it, and the one player that assembles the pool's engine. */
    private fun feedScreen(
        network: NetworkProfile? = null,
        telemetry: () -> com.superplayer.core.TelemetryCollector? = { null },
    ): Triple<PlayerPool, PreloadCoordinator, SuperPlayer> {
        val pool = buildPool(network, telemetry)
        val preload = PreloadCoordinator.Builder(pool).build().also { coordinators += it }
        return Triple(pool, preload, checkNotNull(pool.acquire()))
    }

    /** A CMCD key's value as a request carried it, from whichever CMCD header holds it. */
    private fun cmcdKey(request: NetworkRequest, key: String): String? =
        request.headers.values.firstNotNullOfOrNull { Regex("""(?:^|,)$key="([^"]*)"""").find(it)?.groupValues?.get(1) }

    /** [rows] rows, each the same stream served from a host of its own. */
    private class Feed(rows: Int) {
        val hosts: List<String> = (0 until rows).map { "row$it.feed.test" }
        val content: TestContent
        val requests: List<MediaRequest>

        init {
            var served = TestContent.hls(SEGMENTS)
            val uris = hosts.map { host ->
                served = served.servedFrom(host)
                served.sourceUri
            }
            content = served
            requests = uris.mapIndexed { row, uri -> MediaRequest.Builder(contentId(row)).addSource(uri).build() }
        }

        fun contentId(row: Int): String = "feed:row$row"

        fun resuming(row: Int): MediaRequest = MediaRequest.Builder(contentId(row))
            .addSource(requests[row].sources.first())
            .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
            .build()

        fun rowOf(request: NetworkRequest): Int? = hosts.indexOf(Uri.parse(request.uri).host).takeIf { it >= 0 }

        /** Each row's first media segment request, in the order they were made. */
        fun firstSegmentOrder(requests: List<NetworkRequest>): List<Int> =
            requests.filter { it.kind == ResourceKind.MEDIA_SEGMENT }.mapNotNull { rowOf(it) }.distinct()

        fun manifestsFor(row: Int, requests: List<NetworkRequest>): Int =
            requests.count { it.kind == ResourceKind.MANIFEST && rowOf(it) == row }
    }

    private companion object {
        const val ROWS = 12

        /** Longer than a row plays in any test here, so a row left mid-play has a position to resume from. */
        const val SEGMENTS = 12

        /**
         * A span a viewer might spend on one row, and the span a test that is *about* a span waits: that
         * nothing further is fetched, that a pool without a coordinator fetched nothing ahead.
         */
        const val PREFETCH_MS = 8_000L

        /** How long a row plays before the feed moves on, well inside its length. */
        const val ROW_PLAYED_MS = 2_000L
    }
}
