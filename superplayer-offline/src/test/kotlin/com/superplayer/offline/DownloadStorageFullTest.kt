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

package com.superplayer.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.cache.CachePolicy
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackResilience
import com.superplayer.resilience.FailureClass
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.net.URI

/**
 * #244, ADR-0013 rule 9: a download that meets a full disk fails, named, and the queue does not. Two items
 * share one store, each served from a host of its own so a fault can pace one without the other: the first
 * fetches its segments a short latency apart, so the disk can be stated full between two of them, and the
 * second waits long enough on each that it has written none of its media while the disk is full.
 *
 * What the harness cannot state is a write that fails *because* the disk filled: a full disk here is the
 * platform's reading of free space ([DeviceStatement.declareStorageFree]), which the cache's download half
 * reads before each write. The platform's own `ENOSPC`, which a device raises when another writer took the
 * space between that reading and the write, is translated to the same exception and not forced here.
 */
@RunWith(AndroidJUnit4::class)
class DownloadStorageFullTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    private val filling = TestContent.hls(SEGMENTS).servedFrom(FILLING_HOST)

    private val roomy = TestContent.hls(SEGMENTS).servedFrom(ROOMY_HOST)

    @Before
    fun aDeviceWithRoom() {
        harness.useScheduledWork()
        DeviceStatement.declareNetworkMetered(false)
        DeviceStatement.declareStorageFree(ROOM_BYTES)
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
    }

    @Test
    fun aDiskThatFillsPartwayFailsThatItemNamedWhileAnotherQueuedItemCompletes() {
        val environment = harness.downloadEnvironment(filling.alsoServing(roomy), pacing())
        val downloads = openStore(environment, Resilience.standard())
        downloads.enqueue(request(FILLING_ID, filling))
        downloads.enqueue(request(ROOMY_ID, roomy))
        harness.advanceUntil(environment, boundMs = TWO_ITEMS_BOUND_MS, wanted = "the filling item asked for its fourth segment") {
            segmentsFrom(environment, FILLING_HOST).size > HELD_SEGMENTS
        }
        val held = segmentsFrom(environment, FILLING_HOST).take(HELD_SEGMENTS)

        DeviceStatement.declareStorageFree(0)
        harness.advanceUntil(environment, boundMs = TWO_ITEMS_BOUND_MS, wanted = "the filling item failed") { downloads.download(FILLING_ID)?.state == DownloadState.FAILED }
        val failed = downloads.download(FILLING_ID)!!
        assertThat(failed.failure?.causeClass).isEqualTo(FailureClass.Storage.Full.stableName)
        assertThat(failed.failure?.userMessageKey).isEqualTo(FailureClass.STORAGE_FULL_MESSAGE_KEY)
        assertThat(failed.stopReason).isNull()
        assertThat(failed.bytesDownloaded).isGreaterThan(0L)
        // Failed at once rather than after Media3's retries, which would each have asked for the segment again.
        assertWithMessage("requests for the segment the full disk met")
            .that(segmentRequestsFrom(environment, FILLING_HOST).count { it == segmentsFrom(environment, FILLING_HOST)[HELD_SEGMENTS] })
            .isEqualTo(1)
        assertThat(downloads.download(ROOMY_ID)!!.state).isNotEqualTo(DownloadState.FAILED)

        DeviceStatement.declareStorageFree(ROOM_BYTES)
        harness.advanceUntil(environment, boundMs = TWO_ITEMS_BOUND_MS, wanted = "the roomy item completed") { downloads.download(ROOMY_ID)?.state == DownloadState.COMPLETED }
        assertThat(downloads.download(FILLING_ID)!!.state).isEqualTo(DownloadState.FAILED)

        // What the failed item wrote is kept, pinned, for an enqueue once there is room to continue from.
        downloads.enqueue(request(FILLING_ID, filling))
        harness.advanceUntil(environment, boundMs = TWO_ITEMS_BOUND_MS, wanted = "the filling item completed") { downloads.download(FILLING_ID)?.state == DownloadState.COMPLETED }
        assertWithMessage("segments the failed item had written, fetched again")
            .that(segmentRequestsFrom(environment, FILLING_HOST).filter { it in held })
            .containsExactlyElementsIn(held)
        assertThat(downloads.download(FILLING_ID)!!.failure).isNull()
    }

    /** The control: the same queue, paced the same way, on a disk with room completes every item. */
    @Test
    fun theSameQueueWithRoomCompletesEveryItem() {
        val environment = harness.downloadEnvironment(filling.alsoServing(roomy), pacing())
        val downloads = openStore(environment, Resilience.standard())
        downloads.enqueue(request(FILLING_ID, filling))
        downloads.enqueue(request(ROOMY_ID, roomy))
        harness.advanceUntil(environment, boundMs = TWO_ITEMS_BOUND_MS, wanted = "both items completed") {
            downloads.downloads().count { it.state == DownloadState.COMPLETED } == 2
        }
        assertThat(downloads.downloads().map { it.failure }).containsExactly(null, null)
    }

    /**
     * Failing one item on a full disk is correctness rather than something a resilience buys (rule 9): a
     * store built without one fails the item at once too, and only its name is missing, as every failure of
     * such a store's is.
     */
    @Test
    fun aStoreWithoutResilienceFailsTheItemAtOnceUnnamed() {
        val environment = harness.downloadEnvironment(filling, pacing())
        val downloads = openStore(environment)
        downloads.enqueue(request(FILLING_ID, filling))
        harness.advanceUntil(environment, "the item asked for its fourth segment") {
            segmentsFrom(environment, FILLING_HOST).size > HELD_SEGMENTS
        }
        DeviceStatement.declareStorageFree(0)
        harness.advanceUntil(environment, "the item failed") { downloads.download(FILLING_ID)?.state == DownloadState.FAILED }
        assertThat(downloads.download(FILLING_ID)!!.failure).isNull()
        assertThat(segmentRequestsFrom(environment, FILLING_HOST).count { it == segmentsFrom(environment, FILLING_HOST)[HELD_SEGMENTS] })
            .isEqualTo(1)
    }

    private fun pacing(): FaultScript = FaultScript.Builder()
        .addLatencyMs(FILLING_LATENCY_MS, kind = ResourceKind.MEDIA_SEGMENT, host = FILLING_HOST)
        .addLatencyMs(ROOMY_LATENCY_MS, kind = ResourceKind.MEDIA_SEGMENT, host = ROOMY_HOST)
        .build()

    /** Every segment request to [host], in the order made, a retry included. */
    private fun segmentRequestsFrom(environment: DownloadEnvironment, host: String): List<String> = harness.networkRequests(environment)
        .filter { it.kind == ResourceKind.MEDIA_SEGMENT && URI(it.uri).host == host }
        .map { it.uri }

    /** The distinct segments asked of [host], in the order first asked for. */
    private fun segmentsFrom(environment: DownloadEnvironment, host: String): List<String> =
        segmentRequestsFrom(environment, host).distinct()

    private fun openStore(environment: DownloadEnvironment, resilience: PlaybackResilience? = null): Downloads {
        val cache = CachePolicy.contentKeyed(folder.newFolder(), LARGE_BUDGET_BYTES).also { opened += AutoCloseable { it.release() } }
        return Downloads.Builder(context, cache)
            .setEnvironment(environment)
            .apply { resilience?.let(::setResilience) }
            .build()
            .also { store -> opened += AutoCloseable { store.release() } }
    }

    private fun request(contentId: String, content: TestContent): MediaRequest =
        MediaRequest.Builder(contentId).addSource(content.sourceUri).build()

    private companion object {
        const val FILLING_ID = "film/the-thirty-nine-steps"
        const val ROOMY_ID = "film/sabotage"
        const val FILLING_HOST = "filling.test"
        const val ROOMY_HOST = "roomy.test"
        const val SEGMENTS = 8

        // The segments the filling item has written whole when the disk is stated full: its fourth has been
        // asked for and is still waiting out its latency.
        const val HELD_SEGMENTS = 3

        // Different, so the two items' segments interleave on the one loading thread rather than one item
        // finishing before the other starts: each waits out the other's delay as well as its own.
        const val FILLING_LATENCY_MS = 1_000L
        const val ROOMY_LATENCY_MS = 5_000L

        // Far more than both items, as a reading; the reading does not shrink as they write.
        const val ROOM_BYTES = 1L shl 30

        // Harness time for two items whose segments each wait out both delays: a few multiples of what they need.
        const val TWO_ITEMS_BOUND_MS = 300_000L

        // Far more than any synthetic stream here, so nothing is evicted.
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024
    }
}
