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

package com.superplayer.testkit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.media3.common.MimeTypes
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.TransferChain
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * The harness drives a download the way it drives a player (#239), so every Phase 7 test after this
 * one is writable under `check`.
 *
 * What downloads here is Media3's own `DownloadManager` rather than `superplayer-offline`'s store,
 * because testkit is phase 2 and cannot name a phase 7 type: over a `SimpleCache` in a temporary
 * directory, with the chain core assembles for a download (`TransferChain.downloadChain`, ADR-0013 rule 6) as its upstream and the harness's
 * [DownloadEnvironment] beneath that. What is under test is the harness: that a download crosses its
 * transport, that its faults reach one, that a run repeats, that a process can die under one, and that
 * the conditions a download is scheduled under can be stated. `docs/testing.md`'s *Downloads* is what
 * each stand-in cannot show.
 */
@RunWith(AndroidJUnit4::class)
class DownloadHarnessTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun anUnmeteredNetwork() {
        // Robolectric's own network is metered and unvalidated, which Media3's default requirement — any
        // validated network — already refuses. The tests that are not about the network say so here.
        DeviceStatement.declareNetworkMetered(false)
    }

    @Test
    fun anHlsDownloadCrossesTheHarnessTransportAndFetchesEachResourceOnce() {
        val content = TestContent.hls()
        val environment = harness.downloadEnvironment(content)

        Store(folder.newFolder(), environment).use { it.downloadToCompletion(content) }

        assertEachResourceFetchedOnce(harness.networkRequests(environment), content)
    }

    @Test
    fun aDashDownloadCrossesTheHarnessTransportAndFetchesEachResourceOnce() {
        val content = TestContent.dash()
        val environment = harness.downloadEnvironment(content)

        Store(folder.newFolder(), environment).use { it.downloadToCompletion(content) }

        assertEachResourceFetchedOnce(harness.networkRequests(environment), content)
    }

    @Test
    fun aFaultScriptReachesADownloadRequest() {
        val content = TestContent.hls()
        val environment = harness.downloadEnvironment(
            content,
            faults = FaultScript.Builder().failWithHttpStatus(404, kind = ResourceKind.MEDIA_SEGMENT, index = 2).build(),
        )

        Store(folder.newFolder(), environment).use { store ->
            store.start(content)
            harness.advanceUntil(environment, "the download failed") { store.state() == Download.STATE_FAILED }
        }

        // The refusal met the download: the one segment it addressed was asked for again on each of
        // Media3's own retries until the download gave up, and every other segment was fetched once.
        val segments = harness.networkRequests(environment)
            .filter { it.kind == ResourceKind.MEDIA_SEGMENT }
            .groupingBy { it.uri.substringAfterLast('/') }
            .eachCount()
        assertThat(segments["segment2.aac"]).isGreaterThan(1)
        assertThat(segments.filterKeys { it != "segment2.aac" }.values.toSet()).containsExactly(1)
    }

    @Test
    fun twoRunsOfOneDownloadAreTheSameRequestsAndTheSameStates() {
        val content = TestContent.dash()

        val first = harness.downloadEnvironment(content)
        val firstStates = Store(folder.newFolder(), first).use { it.downloadToCompletion(content) }
        val second = harness.downloadEnvironment(content)
        val secondStates = Store(folder.newFolder(), second).use { it.downloadToCompletion(content) }

        assertThat(harness.networkRequests(second).map { it.toString() })
            .containsExactlyElementsIn(harness.networkRequests(first).map { it.toString() })
            .inOrder()
        assertThat(secondStates).containsExactlyElementsIn(firstStates).inOrder()
        assertThat(firstStates.last()).isEqualTo(Download.STATE_COMPLETED)
    }

    @Test
    fun aStoreReopenedAfterProcessDeathFindsTheDownloadAndFinishesItWithoutStartingOver() {
        val content = TestContent.hls(segmentCount = 8)
        val directory = folder.newFolder()
        val beforeDeath = harness.downloadEnvironment(content)
        // Held at the fourth segment, so the process dies with part of the download on disk and the rest
        // not — deterministically, rather than wherever a thread happened to be.
        val dying = Store(directory, beforeDeath)
        dying.start(content)
        harness.advanceUntil(beforeDeath, "three segments were fetched") {
            harness.networkRequests(beforeDeath).count { it.kind == ResourceKind.MEDIA_SEGMENT } >= 3
        }

        val reopenedDirectory = harness.processDeath(beforeDeath, directory)
        // Every segment asked for before the death finished loading before the copy was taken.
        val storedBeforeDeath = harness.networkRequests(beforeDeath)
            .filter { it.kind == ResourceKind.MEDIA_SEGMENT }
            .map { it.uri }
            .distinct()
            .size

        val afterDeath = harness.downloadEnvironment(content)
        Store(reopenedDirectory, afterDeath).use { reopened ->
            // The index the dead process wrote is what the new one reads: the download is known and
            // unfinished, before this process has asked the network for anything.
            val found = reopened.manager.downloadIndex.getDownload(CONTENT_ID)
            assertThat(found).isNotNull()
            assertThat(found!!.state).isNotEqualTo(Download.STATE_COMPLETED)
            assertThat(harness.networkRequests(afterDeath)).isEmpty()

            harness.advanceUntil(afterDeath, "the download completed") { reopened.state() == Download.STATE_COMPLETED }
        }
        // Resumed rather than restarted: not one segment the dead process stored was fetched again.
        val fetchedAfter = harness.networkRequests(afterDeath).count { it.kind == ResourceKind.MEDIA_SEGMENT }
        assertThat(fetchedAfter).isAtMost(8 - storedBeforeDeath)
    }

    @Test
    fun anUnmeteredNetworkIsStatedMeteredAndBackAgain() {
        val unmetered = Requirements(Requirements.NETWORK_UNMETERED)
        assertThat(unmetered.checkRequirements(context)).isTrue()

        DeviceStatement.declareNetworkMetered(true)
        assertThat(unmetered.checkRequirements(context)).isFalse()
        // Still a network: a download allowed any network would run.
        assertThat(Requirements(Requirements.NETWORK).checkRequirements(context)).isTrue()

        DeviceStatement.declareNetworkMetered(false)
        assertThat(unmetered.checkRequirements(context)).isTrue()
    }

    @Test
    fun lowStorageIsStatedAndRecovers() {
        val storageNotLow = Requirements(Requirements.DEVICE_STORAGE_NOT_LOW)
        assertThat(storageNotLow.checkRequirements(context)).isTrue()

        DeviceStatement.declareStorageLow(true)
        assertThat(storageNotLow.checkRequirements(context)).isFalse()

        DeviceStatement.declareStorageLow(false)
        assertThat(storageNotLow.checkRequirements(context)).isTrue()
    }

    @Test
    fun scheduledWorkRunsOnlyOnceEachStatedConditionHolds() {
        harness.useScheduledWork()
        DeviceStatement.declareBatteryLow(false)
        val work = OneTimeWorkRequestBuilder<NothingWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(work).result.get()
        fun state() = workManager.getWorkInfoById(work.id).get()!!.state

        // Each condition unmet in turn, with the other two met: the work waits.
        DeviceStatement.declareNetworkMetered(true)
        assertThat(harness.runScheduledWork()).isEqualTo(0)
        DeviceStatement.declareNetworkMetered(false)
        DeviceStatement.declareBatteryLow(true)
        assertThat(harness.runScheduledWork()).isEqualTo(0)
        DeviceStatement.declareBatteryLow(false)
        DeviceStatement.declareStorageLow(true)
        assertThat(harness.runScheduledWork()).isEqualTo(0)
        assertThat(state()).isEqualTo(WorkInfo.State.ENQUEUED)

        // All three met: it runs.
        DeviceStatement.declareStorageLow(false)
        assertThat(harness.runScheduledWork()).isEqualTo(1)
        assertThat(state()).isEqualTo(WorkInfo.State.SUCCEEDED)
    }

    private fun assertEachResourceFetchedOnce(requests: List<NetworkRequest>, content: TestContent) {
        assertThat(requests).isNotEmpty()
        assertThat(requests.map { it.uri }).containsNoDuplicates()
        assertThat(requests.map { it.uri }).containsExactlyElementsIn(content.resources.keys)
        assertThat(requests.map { it.kind }).contains(ResourceKind.MEDIA_SEGMENT)
    }

    /**
     * Media3's download stack over a directory, the way a store will hold it: the cache and its index in
     * one directory, so that what [PlaybackHarness.processDeath] copies is the whole of it.
     */
    private inner class Store(directory: File, environment: DownloadEnvironment) : AutoCloseable {

        private val database = DirectoryDatabase(context, File(directory, "index.db"))
        private val cache = SimpleCache(File(directory, "media"), NoOpCacheEvictor(), database)
        private val states = mutableListOf<Int>()

        val manager = DownloadManager(
            context,
            DefaultDownloadIndex(database),
            DefaultDownloaderFactory(
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(TransferChain.downloadChain(context, environment)),
                environment.loadExecutor,
            ),
        ).apply {
            // A manager is built paused, and it is Media3's `DownloadService` that resumes it; there is no
            // service here, so the test does what it would.
            resumeDownloads()
            maxParallelDownloads = 1
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                        if (states.lastOrNull() != download.state) states += download.state
                    }
                },
            )
        }

        private val environment = environment

        fun start(content: TestContent) {
            val mimeType = if (content.sourceUri.endsWith(".mpd")) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
            manager.addDownload(DownloadRequest.Builder(CONTENT_ID, android.net.Uri.parse(content.sourceUri)).setMimeType(mimeType).build())
        }

        fun state(): Int? = manager.currentDownloads.firstOrNull { it.request.id == CONTENT_ID }?.state
            ?: manager.downloadIndex.getDownload(CONTENT_ID)?.state

        /** Downloads [content] to completion and answers the states it passed through. */
        fun downloadToCompletion(content: TestContent): List<Int> {
            start(content)
            harness.advanceUntil(environment, "the download completed") { state() == Download.STATE_COMPLETED }
            return states.toList()
        }

        override fun close() {
            manager.release()
            cache.release()
            database.close()
        }
    }

    /** A database file at an absolute path, which `SQLiteOpenHelper` takes as a name. */
    private class DirectoryDatabase(context: Context, file: File) :
        SQLiteOpenHelper(context, file.absolutePath, null, 1),
        DatabaseProvider {
        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** Scheduled work that does nothing: what is asserted is whether it ran. */
    class NothingWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.success()
    }

    private companion object {
        const val CONTENT_ID = "film/the-lady-vanishes"
    }
}
