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

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.cache.CachePolicy
import com.superplayer.cache.ContentKeyedCache
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.MediaRequest
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
import org.robolectric.Shadows.shadowOf

/**
 * #243, ADR-0013 rules 9 to 11: a download runs only while the network is unmetered, the battery is not low
 * and storage is not low. A condition that does not hold is a stopped item naming it, and nothing is fetched
 * — asserted as a count of requests, the manifest's included. A condition that lapses mid-download stops it
 * with that reason, and its return resumes it from what the cache holds. Pending downloads are scheduled as
 * `WorkManager` work under the same three constraints, run here by the harness where its statements say they
 * hold.
 *
 * Every test states all three conditions before it opens a store, because Robolectric's own device is a
 * metered network with no battery reading, and says which one it then takes away. Each unmet condition is a
 * test method of its own, so each starts from a device no other statement has touched.
 */
@RunWith(AndroidJUnit4::class)
class DownloadConditionsTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    @Before
    fun everyConditionHolds() {
        harness.useScheduledWork()
        DeviceStatement.declareNetworkMetered(false)
        DeviceStatement.declareBatteryLow(false)
        DeviceStatement.declareStorageLow(false)
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
    }

    @Test
    fun aMeteredNetworkFetchesNothingAndTheItemSaysSo() {
        DeviceStatement.declareNetworkMetered(true)
        assertHeldFromTheStart(DownloadStopReason.NO_UNMETERED_NETWORK)
    }

    @Test
    fun aLowBatteryFetchesNothingAndTheItemSaysSo() {
        DeviceStatement.declareBatteryLow(true)
        assertHeldFromTheStart(DownloadStopReason.BATTERY_LOW)
    }

    @Test
    fun lowStorageFetchesNothingAndTheItemSaysSo() {
        DeviceStatement.declareStorageLow(true)
        assertHeldFromTheStart(DownloadStopReason.STORAGE_LOW)
    }

    @Test
    fun aNetworkThatBecomesMeteredStopsTheDownloadAndAnUnmeteredOneResumesItWithoutRefetching() {
        assertLapseAndReturn(DownloadStopReason.NO_UNMETERED_NETWORK) { holds -> DeviceStatement.declareNetworkMetered(!holds) }
    }

    @Test
    fun aBatteryThatRunsLowStopsTheDownloadAndOneThatRecoversResumesItWithoutRefetching() {
        assertLapseAndReturn(DownloadStopReason.BATTERY_LOW) { holds -> DeviceStatement.declareBatteryLow(!holds) }
    }

    @Test
    fun storageThatRunsLowStopsTheDownloadAndStorageThatRecoversResumesItWithoutRefetching() {
        assertLapseAndReturn(DownloadStopReason.STORAGE_LOW) { holds -> DeviceStatement.declareStorageLow(!holds) }
    }

    @Test
    fun withEveryConditionMetTheDownloadRunsUninterrupted() {
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(openCache(), environment)
        val reports = record(downloads)
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the download completed") { downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED }

        assertThat(reports.map { it.state }).containsNoneOf(DownloadState.STOPPED, DownloadState.FAILED)
        assertThat(reports.map { it.stopReason }.distinct()).containsExactly(null)
        assertThat(segmentsFetched(environment)).hasSize(SEGMENTS)
    }

    @Test
    fun pendingWorkWaitsUnderAllThreeConstraintsAndRunsTheDownloadOnceTheyHold() {
        DeviceStatement.declareBatteryLow(true)
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(openCache(), environment)
        downloads.enqueue(request(content))
        idle()

        val scheduled = enqueuedWork()
        assertThat(scheduled).hasSize(1)
        val constraints = scheduled.single().constraints
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(constraints.requiresBatteryNotLow()).isTrue()
        assertThat(constraints.requiresStorageNotLow()).isTrue()

        // Held by each constraint in turn: the battery, then the network alone, then storage alone.
        assertThat(harness.runScheduledWork()).isEqualTo(0)
        DeviceStatement.declareBatteryLow(false)
        DeviceStatement.declareNetworkMetered(true)
        assertThat(harness.runScheduledWork()).isEqualTo(0)
        DeviceStatement.declareNetworkMetered(false)
        DeviceStatement.declareStorageLow(true)
        assertThat(harness.runScheduledWork()).isEqualTo(0)
        assertThat(enqueuedWork()).hasSize(1)
        assertThat(harness.networkRequests(environment)).isEmpty()

        DeviceStatement.declareStorageLow(false)
        assertThat(harness.runScheduledWork()).isEqualTo(1)
        harness.advanceUntil(environment, "the download completed") { downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED }

        assertThat(segmentsFetched(environment)).hasSize(SEGMENTS)
        assertWithMessage("work left scheduled once nothing is pending").that(enqueuedWork()).isEmpty()
    }

    @Test
    fun aStoreThatAllowsMeteredNetworksDownloadsOverOneAndSchedulesForAnyNetwork() {
        DeviceStatement.declareNetworkMetered(true)
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(openCache(), environment)
        downloads.meteredNetworksAllowed = true
        downloads.enqueue(request(content))
        // Scheduled as it is enqueued, before a download that nothing holds has had the moment it needs to finish.
        assertThat(enqueuedWork().single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)

        harness.advanceUntil(environment, "the download completed") { downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED }
        assertThat(segmentsFetched(environment)).hasSize(SEGMENTS)
    }

    @Test
    fun allowingMeteredNetworksAtRuntimeStartsADownloadAMeteredNetworkWasHolding() {
        DeviceStatement.declareNetworkMetered(true)
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(openCache(), environment)
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the item was held for its network") {
            downloads.download(CONTENT_ID)?.stopReason == DownloadStopReason.NO_UNMETERED_NETWORK
        }
        assertThat(enqueuedWork().single().constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)

        downloads.meteredNetworksAllowed = true
        assertThat(enqueuedWork().single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        harness.advanceUntil(environment, "the download completed") { downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED }
        assertThat(segmentsFetched(environment)).hasSize(SEGMENTS)
    }

    @Test
    fun dataSaverStillHoldsAStoreThatAllowsMeteredNetworksOnAMeteredOne() {
        DeviceStatement.declareNetworkMetered(true)
        DeviceStatement.declareDataSaverOn()
        assertHeldFromTheStart(DownloadStopReason.DATA_SAVER) { it.meteredNetworksAllowed = true }
    }

    @Test
    fun aStoreWithNothingPendingSchedulesNothingAndWatchesNoBattery() {
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val batteryWatchersBefore = batteryWatchers()
        val downloads = openStore(openCache(), environment)
        idle()
        assertThat(enqueuedWork()).isEmpty()
        assertThat(batteryWatchers()).isEqualTo(batteryWatchersBefore)

        // The counters see what they count: a pending download is both.
        DeviceStatement.declareBatteryLow(true)
        downloads.enqueue(request(content))
        idle()
        assertThat(enqueuedWork()).hasSize(1)
        assertThat(batteryWatchers()).isGreaterThan(batteryWatchersBefore)

        downloads.remove(CONTENT_ID)
        idle()
        assertThat(enqueuedWork()).isEmpty()
        assertThat(batteryWatchers()).isEqualTo(batteryWatchersBefore)
    }

    private fun assertHeldFromTheStart(reason: DownloadStopReason, configure: (Downloads) -> Unit = {}) {
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(openCache(), environment).also(configure)
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the item was stopped for $reason") {
            downloads.download(CONTENT_ID)?.let { it.state == DownloadState.STOPPED && it.stopReason == reason } == true
        }
        idle()
        assertWithMessage("requests while held by $reason").that(harness.networkRequests(environment)).isEmpty()
        assertThat(downloads.downloads().single().stopReason).isEqualTo(reason)
    }

    private fun assertLapseAndReturn(reason: DownloadStopReason, stateHolds: (Boolean) -> Unit) {
        val content = TestContent.hls(SEGMENTS)
        // The fourth segment's first request waits before its first byte, so the lapse lands between segments:
        // the three before it are whole in the cache, and it holds nothing yet.
        val faults = FaultScript.Builder()
            .addLatencyMs(INTERRUPTED_SEGMENT_LATENCY_MS, kind = ResourceKind.MEDIA_SEGMENT, index = HELD_SEGMENTS, firstAttempts = 1)
            .build()
        val environment = harness.downloadEnvironment(content, faults)
        val downloads = openStore(openCache(), environment)
        val reports = record(downloads)
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the fourth segment was asked for") { segmentsFetched(environment).size > HELD_SEGMENTS }
        val heldSegments = segmentsFetched(environment).take(HELD_SEGMENTS).toSet()

        stateHolds(false)
        harness.advanceUntil(environment, "the download stopped for $reason") {
            downloads.download(CONTENT_ID)?.let { it.state == DownloadState.STOPPED && it.stopReason == reason } == true
        }
        assertThat(downloads.download(CONTENT_ID)!!.bytesDownloaded).isGreaterThan(0L)
        val fetchedWhileRunning = segmentsFetched(environment)
        assertThat(fetchedWhileRunning.size).isLessThan(SEGMENTS)
        idle()
        assertWithMessage("segments fetched after the stop").that(segmentsFetched(environment)).isEqualTo(fetchedWhileRunning)

        stateHolds(true)
        harness.advanceUntil(environment, "the download completed") { downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED }

        val requestsForHeld = harness.networkRequests(environment)
            .filter { it.kind == ResourceKind.MEDIA_SEGMENT && it.uri in heldSegments }
        assertWithMessage("segments held before the lapse, fetched again").that(requestsForHeld.map { it.uri }).containsNoDuplicates()
        assertThat(requestsForHeld).hasSize(HELD_SEGMENTS)
        assertThat(segmentsFetched(environment)).hasSize(SEGMENTS)
        assertThat(reports.map { it.state }).doesNotContain(DownloadState.FAILED)
        assertThat(reports.map { it.bytesDownloaded }).isInOrder()
        assertThat(downloads.download(CONTENT_ID)!!.stopReason).isNull()
    }

    private fun idle() {
        repeat(IDLE_PASSES) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    private fun enqueuedWork(): List<WorkInfo> = WorkManager.getInstance(context)
        .getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
        .get()

    /** Receivers registered for the battery reading. */
    private fun batteryWatchers(): Int = shadowOf(context as Application).registeredReceivers
        .count { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) }

    /** The segments asked for, each once, in the order first asked. */
    private fun segmentsFetched(environment: DownloadEnvironment): Set<String> = harness.networkRequests(environment)
        .filter { it.kind == ResourceKind.MEDIA_SEGMENT }
        .mapTo(LinkedHashSet()) { it.uri }

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

    private fun openCache(): ContentKeyedCache =
        CachePolicy.contentKeyed(folder.newFolder(), LARGE_BUDGET_BYTES).also { cache -> opened += AutoCloseable { cache.release() } }

    private fun openStore(cache: ContentKeyedCache, environment: DownloadEnvironment): Downloads = Downloads.Builder(context, cache)
        .setEnvironment(environment)
        .build()
        .also { store -> opened += AutoCloseable { store.release() } }

    private fun request(content: TestContent): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    private companion object {
        const val CONTENT_ID = "film/the-thirty-nine-steps"
        const val SEGMENTS = 8

        // How many segments are whole in the cache when a condition lapses, and how long the next one waits
        // before its first byte: a minute of harness time, far longer than a stop takes to arrive.
        const val HELD_SEGMENTS = 3
        const val INTERRUPTED_SEGMENT_LATENCY_MS = 60_000L

        // Far more than any synthetic stream here, so nothing is evicted.
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024

        // Enough main-looper passes, with the download's threads given a moment between them, for anything a
        // held download was going to fetch to have been asked for.
        const val IDLE_PASSES = 50
    }
}
