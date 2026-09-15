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

import android.app.Application
import android.content.ComponentCallbacks2
import android.media.MediaFormat
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlayerPool
import com.superplayer.core.SuperPlayer
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * ADR-0010 rule 11's two platform rules, applied after the decision: the memory guard and the data-saver
 * rule. Every test here keeps `SHORT_FORM`'s decision — two rows ahead, one behind, each loaded to one
 * second — and changes only what the device or the user has said, so what is prefetched less is the rule's
 * doing and not the policy's.
 *
 * The feed is `PreloadCoordinatorPlaybackTest`'s: the same stream per row from a host of its own, so what a
 * row fetched is read off the requests by host. Warm decoders under a trim are `DecoderWarmupTest`'s,
 * because a decoder needs video.
 */
@RunWith(AndroidJUnit4::class)
class PreloadPlatformRulesTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val feed = Feed(rows = ROWS, segments = SEGMENTS)
    private val coordinators = mutableListOf<PreloadCoordinator>()
    private val pools = mutableListOf<PlayerPool>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun declareADecoder() {
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    @After
    fun releaseTheScreen() {
        coordinators.forEach { it.release() }
        pools.forEach { it.release() }
    }

    // --- The memory guard -------------------------------------------------------------------------

    /**
     * A heap that holds `SHORT_FORM`'s whole window prefetches it all: at row 3, moving forward, rows 4, 5
     * and 2. The control for the two tests below it, on the same scroll.
     */
    @Test
    fun aPlentifulHeapPrefetchesTheWholeDecidedWindow() {
        DeviceStatement.declareAppHeap(megabytes = 2048)

        assertThat(firstSegmentOrderAtRowThree()).containsExactly(4, 5, 2).inOrder()
    }

    /**
     * A 12 MB heap: the guard's share of it holds two rows' first second, not three, so the window is cut
     * from its far end — the row behind, which the scroll is least likely to reach — rather than from
     * the rows ahead.
     */
    @Test
    fun aSmallHeapLowersHowManyRowsArePrefetched() {
        DeviceStatement.declareAppHeap(megabytes = 12)

        assertThat(firstSegmentOrderAtRowThree()).containsExactly(4, 5).inOrder()
    }

    /** Declared low-RAM: the next row only, however large the heap the app is allowed. */
    @Test
    fun aLowRamDevicePrefetchesOnlyTheNextRow() {
        DeviceStatement.declareAppHeap(megabytes = 2048)
        DeviceStatement.declareLowRamDevice()

        assertThat(firstSegmentOrderAtRowThree()).containsExactly(4)
    }

    /**
     * A trim at `TRIM_MEMORY_RUNNING_LOW` releases what was prefetched: nothing more is fetched for a row
     * ahead until the feed moves, and a row prefetched before the trim is fetched again, manifest and
     * all, when it plays — it was released, not held.
     */
    @Test
    fun aMemoryTrimReleasesWhatWasPrefetched() {
        DeviceStatement.declareAppHeap(megabytes = 2048)
        val (pool, preload, player) = feedScreen()
        preload.setItems(feed.requests)
        player.setMediaRequest(feed.requests[0])
        harness.playToReady(player)
        awaitFirstSegments(player, pool, rows = setOf(1, 2))
        val manifestsBeforeTheTrim = feed.manifestsFor(1, harness.networkRequests(pool))

        application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        val atTheTrim = harness.networkRequests(pool).size
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)
        assertWithMessage("rows ahead fetched after the trim")
            .that(harness.networkRequests(pool).drop(atTheTrim).mapNotNull { feed.rowOf(it) }.filter { it != 0 })
            .isEmpty()

        preload.setScrollPosition(1)
        player.setMediaRequest(feed.requests[1])
        harness.playToReady(player)
        assertThat(feed.manifestsFor(1, harness.networkRequests(pool))).isGreaterThan(manifestsBeforeTheTrim)
    }

    /** Below `TRIM_MEMORY_RUNNING_LOW` nothing is released: the row prefetched plays without its manifest fetched again. */
    @Test
    fun aTrimBelowRunningLowReleasesNothing() {
        DeviceStatement.declareAppHeap(megabytes = 2048)
        val (pool, preload, player) = feedScreen()
        preload.setItems(feed.requests)
        player.setMediaRequest(feed.requests[0])
        harness.playToReady(player)
        awaitFirstSegments(player, pool, rows = setOf(1, 2))
        val manifestsBeforeTheTrim = feed.manifestsFor(1, harness.networkRequests(pool))

        application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        preload.setScrollPosition(1)
        player.setMediaRequest(feed.requests[1])
        harness.playToReady(player)

        assertThat(feed.manifestsFor(1, harness.networkRequests(pool))).isEqualTo(manifestsBeforeTheTrim)
    }

    // --- The data-saver rule ----------------------------------------------------------------------

    /**
     * Data Saver on, on a metered link: nothing for a row leaves before the row is current. Then the row
     * becomes current and plays, which is a fetch the viewer asked for rather than one made on their behalf.
     */
    @Test
    fun withDataSaverOnAMeteredLinkNoRowIsFetchedBeforeItBecomesCurrent() {
        DeviceStatement.declareAppHeap(megabytes = 2048)
        DeviceStatement.declareDataSaverOn()
        val (pool, preload, player) = feedScreen(network = NetworkProfile.LTE_WITH_DROPOUTS)
        preload.setItems(feed.requests)
        player.setMediaRequest(feed.requests[0])
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)

        assertThat(rowsFetched(pool)).containsExactly(0)

        preload.setScrollPosition(1)
        player.setMediaRequest(feed.requests[1])
        harness.playToReady(player)
        assertThat(feed.firstSegmentOrder(harness.networkRequests(pool))).contains(1)
    }

    /** Data Saver restricts metered networks only; on WiFi a feed prefetches as decided. */
    @Test
    fun withDataSaverOnAnUnmeteredLinkPrefetchProceeds() {
        DeviceStatement.declareAppHeap(megabytes = 2048)
        DeviceStatement.declareDataSaverOn()

        assertThat(rowsPrefetchedBesideRowZero(NetworkProfile.STABLE_WIFI)).containsAtLeast(1, 2)
    }

    /** A metered link alone is not the rule: without Data Saver, cellular prefetches as decided. */
    @Test
    fun withoutDataSaverAMeteredLinkPrefetches() {
        DeviceStatement.declareAppHeap(megabytes = 2048)

        assertThat(rowsPrefetchedBesideRowZero(NetworkProfile.LTE_WITH_DROPOUTS)).containsAtLeast(1, 2)
    }

    /**
     * The rule follows the network rather than the scroll: a feed on WiFi prefetches rows 1 and 2, the
     * link hands over to cellular with Data Saver on, and what was prefetched is let go *at the change*.
     *
     * Observed through what happens after it. Row 1, played next, fetches its manifest again. That tells
     * the change apart from the scroll that follows it, because a row the feed has just made current keeps
     * its prefetch through a scroll; only a release before the scroll leaves nothing to keep. And once the
     * feed is on row 1, rows 2 and 3 fetch nothing.
     */
    @Test
    fun aHandoverToAMeteredLinkAppliesDataSaverAtTheChange() {
        DeviceStatement.declareDataSaverOn()
        val (pool, manifestsOnWifi, atTheScroll) = playRowOneAfterAHandover()

        assertWithMessage("row 1's manifests, fetched again once the handover released its prefetch")
            .that(feed.manifestsFor(1, harness.networkRequests(pool))).isGreaterThan(manifestsOnWifi)
        assertThat(harness.networkRequests(pool).drop(atTheScroll).mapNotNull { feed.rowOf(it) }.toSet()).containsExactly(1)
    }

    /**
     * The control for the test above: the same handover without Data Saver releases nothing, so row 1
     * starts from its WiFi prefetch. A refetch there is the rule's doing, not the handover's.
     */
    @Test
    fun aHandoverWithoutDataSaverKeepsWhatWasPrefetched() {
        val (pool, manifestsOnWifi, _) = playRowOneAfterAHandover()

        assertThat(feed.manifestsFor(1, harness.networkRequests(pool))).isEqualTo(manifestsOnWifi)
    }

    /**
     * A feed on WiFi prefetches rows 1 and 2, the link hands over to cellular, and the feed moves to row 1
     * and plays it. Returns the pool, row 1's manifest count on WiFi, and the request count at the scroll.
     */
    private fun playRowOneAfterAHandover(): Triple<PlayerPool, Int, Int> {
        DeviceStatement.declareAppHeap(megabytes = 2048)
        val (pool, preload, player) = feedScreen(network = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER)
        preload.setItems(feed.requests)
        player.setMediaRequest(feed.requests[0])
        harness.playToReady(player)
        awaitFirstSegments(player, pool, rows = setOf(1, 2))
        val manifestsOnWifi = feed.manifestsFor(1, harness.networkRequests(pool))

        // Past the handover whatever time the wait above took.
        harness.advanceTimeInStepsMs(player, NetworkProfile.HANDOVER_AT_MS + HANDOVER_MARGIN_MS)
        preload.setScrollPosition(1)
        val atTheScroll = harness.networkRequests(pool).size
        player.setMediaRequest(feed.requests[1])
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)
        return Triple(pool, manifestsOnWifi, atTheScroll)
    }

    // --- What the rules cost ----------------------------------------------------------------------

    /**
     * ADR-0010 rule 13, for rule 11's two callbacks: a pool without a coordinator registers no trim callback
     * and no connectivity callback, one with a coordinator registers one of each, and releasing the
     * coordinator takes both away. Counted on the platform, as `SuperPlayerPolicyTest` counts network
     * callbacks, so the zero is shown to be a count that can see one.
     */
    @Test
    fun theRulesRegisterOneCallbackEachOnlyWhileACoordinatorIsAttached() {
        DeviceStatement.declareAppHeap(megabytes = 2048)
        val plain = harness.buildPool(maxSize = 2, profile = PlaybackProfile.SHORT_FORM, content = feed.content).also { pools += it }
        checkNotNull(plain.acquire())
        val beforeAny = registeredCallbacks()

        val (_, preload, _) = feedScreen()
        val attached = registeredCallbacks()
        preload.release()

        assertWithMessage("trim and network callbacks with a coordinator attached").that(attached).isEqualTo(beforeAny.plusOne())
        assertWithMessage("trim and network callbacks once it is released").that(registeredCallbacks()).isEqualTo(beforeAny)
    }

    /** The application's component callbacks and the connectivity service's network callbacks, counted. */
    private fun registeredCallbacks(): Pair<Int, Int> {
        val connectivity = application.getSystemService(ConnectivityManager::class.java)
        // No shadow lists component callbacks, so they are read where the platform keeps them: from API 34
        // in the application's `ComponentCallbacksController`, whose list is null until the first
        // registration. The one hidden platform member this test names.
        // ref: https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/core/java/android/content/ComponentCallbacksController.java
        val controller = ReflectionHelpers.getField<Any>(application, "mCallbacksController")
        val componentCallbacks = ReflectionHelpers.getField<List<*>?>(controller, "mComponentCallbacks")
        return (componentCallbacks?.size ?: 0) to shadowOf(connectivity).networkCallbacks.size
    }

    private fun Pair<Int, Int>.plusOne(): Pair<Int, Int> = (first + 1) to (second + 1)

    private fun firstSegmentOrderAtRowThree(): List<Int> {
        val (pool, preload, player) = feedScreen()
        preload.setScrollPosition(3, velocityItemsPerSecond = 1f)
        preload.setItems(feed.requests)
        harness.advanceUntil(player, "a first segment") { feed.firstSegmentOrder(harness.networkRequests(pool)).isNotEmpty() }
        // Long enough that every row the decision asks for is fetched, so a row missing is one refused.
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)
        return feed.firstSegmentOrder(harness.networkRequests(pool))
    }

    private fun rowsPrefetchedBesideRowZero(network: NetworkProfile): Set<Int> {
        val (pool, preload, player) = feedScreen(network = network)
        preload.setItems(feed.requests)
        player.setMediaRequest(feed.requests[0])
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PREFETCH_MS)
        return rowsFetched(pool)
    }

    private fun rowsFetched(pool: PlayerPool): Set<Int> = harness.networkRequests(pool).mapNotNull { feed.rowOf(it) }.toSet()

    /** Advances until each of [rows] has had its first segment requested. */
    private fun awaitFirstSegments(player: SuperPlayer, pool: PlayerPool, rows: Set<Int>) {
        harness.advanceUntil(player, "first segments of rows $rows") {
            feed.firstSegmentOrder(harness.networkRequests(pool)).containsAll(rows)
        }
    }

    /** A pool, a coordinator attached to it, and the one player that assembles the pool's engine. */
    private fun feedScreen(network: NetworkProfile? = null): Triple<PlayerPool, PreloadCoordinator, SuperPlayer> {
        val pool = harness.buildPool(
            maxSize = 2,
            profile = PlaybackProfile.SHORT_FORM,
            content = feed.content,
            network = network?.trace,
        ).also { pools += it }
        val preload = PreloadCoordinator.Builder(pool).build().also { coordinators += it }
        return Triple(pool, preload, checkNotNull(pool.acquire()))
    }

    private companion object {
        const val ROWS = 12
        const val SEGMENTS = 12

        /** A span in which every prefetch the decision asks for completes, so one missing is one refused. */
        const val PREFETCH_MS = 8_000L

        /** A step past the handover, so the network the scroll after it sees is cellular. */
        const val HANDOVER_MARGIN_MS = 1_000L
    }
}
