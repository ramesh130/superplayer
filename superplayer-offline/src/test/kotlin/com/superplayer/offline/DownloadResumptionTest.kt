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
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.cache.CachePolicy
import com.superplayer.cache.ContentKeyedCache
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackResilience
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * #242, ADR-0013 rule 9: a download survives the two interruptions the harness can state. A process that
 * dies mid-download leaves a directory a new store resumes from, fetching no segment it already held; and a
 * network that goes away stops a download rather than failing it, which then completes once the network is
 * back — told apart, by `ErrorClassifier` through the store's resilience, from a segment the origin has
 * lost, which fails named.
 *
 * A stopped download waits on the store's thread, whose clock Robolectric moves only when told, so the tests
 * that wait for a resumption move it a step per pass ([advanceStoreClock]).
 */
@RunWith(AndroidJUnit4::class)
class DownloadResumptionTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    @Before
    fun anUnmeteredNetwork() {
        // Robolectric's own network is metered and unvalidated, which Media3's default requirement refuses.
        // What goes away here is the transport, which the platform's readings do not show.
        DeviceStatement.declareNetworkMetered(false)
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
    }

    @Test
    fun aStoreReopenedAfterProcessDeathFetchesNoSegmentItHadCompletedAndContinuesItsProgress() {
        val content = TestContent.hls(SEGMENTS)
        val directory = folder.newFolder()
        val beforeDeath = harness.downloadEnvironment(content)
        val dying = openStore(openCache(directory), beforeDeath)
        val reportsBeforeDeath = record(dying)
        dying.enqueue(request(content))
        harness.advanceUntil(beforeDeath, "three segments were fetched") { segmentsFetched(beforeDeath).size >= 3 }

        val reopenedDirectory = harness.processDeath(beforeDeath, directory)
        // Every segment asked for before the death had finished loading when the directory was copied.
        val heldSegments = segmentsFetched(beforeDeath)
        val heldBytes = reportsBeforeDeath.maxOf { it.bytesDownloaded }

        val afterDeath = harness.downloadEnvironment(content)
        val cache = openCache(reopenedDirectory)
        val reopened = openStore(cache, afterDeath)
        val found = reopened.download(CONTENT_ID)
        assertThat(found).isNotNull()
        assertThat(found!!.state).isNotEqualTo(DownloadState.COMPLETED)
        val reportsAfterDeath = record(reopened)
        harness.advanceUntil(afterDeath, "the download completed") {
            reopened.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }

        assertWithMessage("segments the dead process had stored, fetched again")
            .that(segmentsFetched(afterDeath).intersect(heldSegments))
            .isEmpty()
        assertThat(segmentsFetched(afterDeath).size).isEqualTo(SEGMENTS - heldSegments.size)
        // From the held bytes, not from zero: no progress this process reports is behind what the dead one
        // had reported.
        val progress = reportsAfterDeath.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.COMPLETED }
        assertThat(progress).isNotEmpty()
        assertThat(progress.map { it.bytesDownloaded }.min()).isAtLeast(heldBytes)
        assertThat(heldBytes).isGreaterThan(0L)
        assertPlaysWithNoNetwork(content, cache)
    }

    @Test
    fun aDownloadWhoseNetworkIsLostStopsKeepingItsProgressAndCompletesOnceItReturns() {
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val cache = openCache()
        val downloads = openStore(cache, environment, Resilience.standard())
        val reports = record(downloads)
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "three segments were fetched") { segmentsFetched(environment).size >= 3 }

        harness.loseNetwork(environment)
        harness.advanceUntil(environment, "the download stopped") { downloads.download(CONTENT_ID)?.state == DownloadState.STOPPED }
        val stopped = downloads.download(CONTENT_ID)!!
        assertThat(stopped.stopReason).isEqualTo(DownloadStopReason.NETWORK_LOST)
        assertThat(stopped.failure).isNull()
        assertThat(stopped.bytesDownloaded).isGreaterThan(0L)

        // A stretch long enough for the store to try again, and find the network still gone, twice more.
        val requestsAtStop = harness.networkRequests(environment).size
        harness.advanceUntil(environment, "two more attempts found the network gone") {
            advanceStoreClock()
            harness.networkRequests(environment).size >= requestsAtStop + 2 &&
                downloads.download(CONTENT_ID)?.state == DownloadState.STOPPED
        }
        assertThat(downloads.download(CONTENT_ID)!!.bytesDownloaded).isAtLeast(stopped.bytesDownloaded)

        harness.restoreNetwork(environment)
        harness.advanceUntil(environment, "the download completed") {
            advanceStoreClock()
            downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }

        assertThat(reports.map { it.state }).doesNotContain(DownloadState.FAILED)
        assertThat(reports.map { it.bytesDownloaded }).isInOrder()
        assertThat(downloads.download(CONTENT_ID)!!.stopReason).isNull()
        assertPlaysWithNoNetwork(content, cache)
    }

    /**
     * A download a process stopped for its network, and then died with, is tried again as soon as the next
     * process opens the store — with no wait, since the one it was serving died too — and completes.
     */
    @Test
    fun aDownloadStoppedForItsNetworkWhenItsProcessDiedResumesWhenTheStoreIsOpenedAgain() {
        val content = TestContent.hls(SEGMENTS)
        val directory = folder.newFolder()
        val beforeDeath = harness.downloadEnvironment(content)
        val dying = openStore(openCache(directory), beforeDeath, Resilience.standard())
        dying.enqueue(request(content))
        harness.advanceUntil(beforeDeath, "three segments were fetched") { segmentsFetched(beforeDeath).size >= 3 }
        // Taken before the network goes: a request that then failed to resolve stored nothing.
        val heldSegments = segmentsFetched(beforeDeath)
        harness.loseNetwork(beforeDeath)
        harness.advanceUntil(beforeDeath, "the download stopped") { dying.download(CONTENT_ID)?.state == DownloadState.STOPPED }

        val reopenedDirectory = harness.processDeath(beforeDeath, directory)
        val afterDeath = harness.downloadEnvironment(content)
        val reopened = openStore(openCache(reopenedDirectory), afterDeath, Resilience.standard())

        harness.advanceUntil(afterDeath, "the download completed") { reopened.download(CONTENT_ID)?.state == DownloadState.COMPLETED }
        assertThat(segmentsFetched(afterDeath).intersect(heldSegments)).isEmpty()
    }

    /**
     * The control for the stop: a segment the origin answers 404 for is not a lost network, and the item
     * ends failed, named, rather than stopped and tried for ever. Media3's own retries run first, a few
     * seconds of real time apart, which is why this test is slower than the rest.
     */
    @Test
    fun aSegmentTheOriginHasLostFailsTheDownloadNamedRatherThanStoppingIt() {
        val content = TestContent.hls(SEGMENTS)
        val faults = FaultScript.Builder()
            .failWithHttpStatus(FaultScript.HTTP_NOT_FOUND, kind = ResourceKind.MEDIA_SEGMENT, index = 2)
            .build()
        val environment = harness.downloadEnvironment(content, faults)
        val downloads = openStore(openCache(), environment, Resilience.standard())
        val reports = record(downloads)
        downloads.enqueue(request(content))

        harness.advanceUntil(environment, "the download failed") { downloads.download(CONTENT_ID)?.state == DownloadState.FAILED }

        val failed = downloads.download(CONTENT_ID)!!
        assertThat(failed.failure?.causeClass).isEqualTo("Transient.CdnEdge")
        assertThat(failed.stopReason).isNull()
        assertThat(reports.map { it.state }).doesNotContain(DownloadState.STOPPED)
    }

    /**
     * The control for the resilience: the same lost network under a store built without one fails the item,
     * unnamed, as Media3's download manager does — so the stop above is the classification's doing.
     */
    @Test
    fun aStoreWithoutResilienceFailsADownloadWhoseNetworkIsLost() {
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(openCache(), environment)
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "three segments were fetched") { segmentsFetched(environment).size >= 3 }

        harness.loseNetwork(environment)
        harness.advanceUntil(environment, "the download failed") { downloads.download(CONTENT_ID)?.state == DownloadState.FAILED }

        assertThat(downloads.download(CONTENT_ID)!!.failure).isNull()
    }

    private fun assertPlaysWithNoNetwork(content: TestContent, cache: ContentKeyedCache) {
        val player = harness.buildPlayer(content = content, cache = cache)
        player.setMediaRequest(request(content))
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content") { it.playbackState == Player.STATE_ENDED || it.playerError != null }
        assertWithMessage("cause: ${player.playerError?.cause}").that(player.playerError).isNull()
        assertThat(harness.networkRequests(player)).isEmpty()
    }

    /** One step of the store's thread's clock, which is the clock a stopped download's wait runs on. */
    private fun advanceStoreClock() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(STORE_CLOCK_STEP_MS))
    }

    private fun segmentsFetched(environment: DownloadEnvironment): Set<String> = harness.networkRequests(environment)
        .filter { it.kind == ResourceKind.MEDIA_SEGMENT }
        .map { it.uri }
        .toSet()

    private fun record(downloads: Downloads): MutableList<DownloadItem> = mutableListOf<DownloadItem>().also { reports ->
        downloads.addListener(
            object : DownloadsListener {
                override fun onDownloadChanged(item: DownloadItem) {
                    reports += item
                }

                override fun onDownloadRemoved(contentId: String) = Unit
            },
        )
    }

    private fun openCache(directory: File = folder.newFolder()): ContentKeyedCache =
        CachePolicy.contentKeyed(directory, LARGE_BUDGET_BYTES).also { cache -> opened += AutoCloseable { cache.release() } }

    private fun openStore(
        cache: ContentKeyedCache,
        environment: DownloadEnvironment,
        resilience: PlaybackResilience? = null,
    ): Downloads = Downloads.Builder(context, cache)
        .setEnvironment(environment)
        .apply { resilience?.let(::setResilience) }
        .build()
        .also { store -> opened += AutoCloseable { store.release() } }

    private fun request(content: TestContent): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    private companion object {
        const val CONTENT_ID = "film/the-lady-vanishes"
        const val SEGMENTS = 8

        // Far more than any synthetic stream here, so nothing is evicted.
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024

        // Short against the shortest wait before a resumption, so no attempt is skipped past, and long enough
        // that the longest wait these tests reach passes in a few hundred passes.
        const val STORE_CLOCK_STEP_MS = 250L
    }
}
