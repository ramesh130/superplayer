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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlayerPool
import com.superplayer.testkit.ChainBottom
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.PlaybackHarness
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of ADR-0016 rule 13 that no per-player test can reach (#314): a `PreloadCoordinator`'s
 * prefetches travel the **pool's own** chain bottom, so a feed built over a consumer's HTTP client
 * prefetches over it too.
 *
 * ADR-0010 rule 6 is why there is something to check and also why there is so little code here: a
 * coordinator builds its sources from the `MediaSource.Factory` the pool's *first player* was composed
 * with, so a pool whose players resolve their bottom through the app's client has a coordinator that
 * does. What would break it is a preload manager that composed a transport of its own, and the reading
 * that would catch that is a prefetch arriving over the consumer's transport rather than nowhere.
 *
 * The observation is [ChainBottom.CONSUMERS_HTTP_TRANSPORT] on the harness's pool, which leaves the
 * environment's transport slot **empty** and reaches the same origin through an `HttpTransport` instead
 * (ADR-0016 rule 3 — a filled slot wins over a stack). With the slot empty, a request that arrives at
 * all can only have been carried by the consumer's transport: nothing else here serves a `fake:` URI.
 *
 * What this is **not** is a test of `PlayerPool.Builder.setHttpStack`. The harness substitutes the pool's
 * player factory to give its players the harness clock, which makes that builder call dead here exactly
 * as it makes `setCache` and `setResilience` dead; core's `PlayerPoolTest` is where the pool's own setter
 * is counted, once per player it builds.
 */
@RunWith(AndroidJUnit4::class)
class PreloadCoordinatorHttpStackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val feed = Feed(rows = ROWS, segments = SEGMENTS)

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

    @Test
    fun aCoordinatorsPrefetchesTravelTheSameBottomThePoolsPlayersDo() {
        val pool = harness.buildPool(
            maxSize = 2,
            profile = PlaybackProfile.SHORT_FORM,
            content = feed.content,
            bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT,
        ).also { pools += it }
        val preload = PreloadCoordinator.Builder(pool).build().also { coordinators += it }
        val player = checkNotNull(pool.acquire())

        preload.setItems(feed.requests)
        player.setMediaRequest(feed.requests[0])
        harness.playToReady(player)
        // `SHORT_FORM` prefetches two rows ahead, so row 1 is a row nobody is playing: what reaches the
        // transport for it is the coordinator's and can be no player's.
        harness.advanceUntil(player, "row 1's first segment, fetched ahead") {
            feed.firstSegmentOrder(harness.networkRequests(pool)).contains(1)
        }

        val requests = harness.networkRequests(pool)
        assertWithMessage("the row being played came out of the consumer's transport")
            .that(feed.firstSegmentOrder(requests)).contains(0)
        assertThat(feed.manifestsFor(1, requests)).isGreaterThan(0)
    }

    private companion object {
        const val ROWS = 12

        /** Longer than this test plays, so the row being watched never ends under the prefetch. */
        const val SEGMENTS = 12
    }
}
