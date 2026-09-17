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
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.cache.CachePolicy
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.MediaRequest
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * #246, ADR-0013 rules 3 and 11: the app's `DownloadsService` holds the process in the foreground while a
 * download of its store can run and stops once none can; the store starts it when one can, and the scheduled
 * work starts it in a process with no store open, which is what lets a download resume after a reboot.
 *
 * Every claim has its control beside it, so the service is shown neither to stay up regardless nor to stop
 * regardless: a download held by a condition starts nothing and stops a running service, and a store that names
 * no service is never the reason one starts.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsServiceTest {

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
        shadowOf(context as Application).clearStartedServices()
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
        TestDownloadsService.store = null
    }

    @Test
    fun theServiceStaysInTheForegroundWhileADownloadRunsAndStopsOnceItHasCompleted() {
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment, withService = true)
        downloads.enqueue(request(content))
        assertWithMessage("the store started its service").that(startedServices()).contains(TestDownloadsService::class.java.name)

        val service = startService()
        assertThat(shadowOf(service.get()).lastForegroundNotification).isNotNull()
        assertWithMessage("stopped while the download runs").that(shadowOf(service.get()).isStoppedBySelf).isFalse()

        harness.advanceUntil(environment, "the download completed") { downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED }
        idle()
        assertThat(shadowOf(service.get()).isForegroundStopped).isTrue()
        assertThat(shadowOf(service.get()).isStoppedBySelf).isTrue()
    }

    @Test
    fun aServiceStartedWithNothingToRunAnnouncesItselfAndStopsAtOnce() {
        openStore(harness.downloadEnvironment(TestContent.hls(SEGMENTS)), withService = true)

        val service = startService()

        // Announced first, as a foreground start obliges, and then let go: Robolectric forgets a removed notification but not its id.
        assertThat(shadowOf(service.get()).lastForegroundNotificationId).isEqualTo(DownloadsService.DEFAULT_NOTIFICATION_ID)
        assertThat(shadowOf(service.get()).isForegroundStopped).isTrue()
        assertThat(shadowOf(service.get()).isStoppedBySelf).isTrue()
        assertWithMessage("a store with nothing to run starts nothing").that(startedServices()).isEmpty()
    }

    @Test
    fun aDownloadHeldByAConditionStartsNoServiceUntilTheConditionHolds() {
        DeviceStatement.declareBatteryLow(true)
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment, withService = true)
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the item was held for the battery") {
            downloads.download(CONTENT_ID)?.stopReason == DownloadStopReason.BATTERY_LOW
        }
        idle()
        assertWithMessage("started for a held download").that(startedServices()).isEmpty()
        val service = startService()
        assertWithMessage("a service over a held download").that(shadowOf(service.get()).isStoppedBySelf).isTrue()

        DeviceStatement.declareBatteryLow(false)
        harness.advanceUntil(environment, "the store started its service") { TestDownloadsService::class.java.name in startedServices() }
    }

    @Test
    fun theScheduledWorkStartsTheServiceInAProcessWithNoStoreOpen() {
        assertThat(startedByScheduledWorkAfterRelease(withService = true)).containsExactly(TestDownloadsService::class.java.name)
    }

    @Test
    fun theScheduledWorkStartsNothingForAStoreThatNamedNoService() {
        assertThat(startedByScheduledWorkAfterRelease(withService = false)).isEmpty()
    }

    /**
     * A download held by a low battery, its store released as a dead process's would be, and the battery then
     * recovered: the services the scheduled work started once it ran.
     */
    private fun startedByScheduledWorkAfterRelease(withService: Boolean): List<String> {
        DeviceStatement.declareBatteryLow(true)
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment, withService)
        downloads.enqueue(request(content))
        idle()
        downloads.release()
        assertThat(startedServices()).isEmpty()

        DeviceStatement.declareBatteryLow(false)
        assertThat(harness.runScheduledWork()).isEqualTo(1)
        idle()
        return startedServices()
    }

    private fun startService(): ServiceController<TestDownloadsService> =
        Robolectric.buildService(TestDownloadsService::class.java).create().startCommand(0, 1)
            .also { controller -> opened += AutoCloseable { controller.destroy() } }

    /** The class names of the services started since the test began, consumed. */
    private fun startedServices(): List<String> {
        val application = shadowOf(context as Application)
        return generateSequence { application.nextStartedService }.mapNotNull { it.component?.className }.toList()
    }

    private fun idle() {
        repeat(IDLE_PASSES) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    private fun openStore(environment: DownloadEnvironment, withService: Boolean): Downloads {
        val cache = CachePolicy.contentKeyed(folder.newFolder(), BUDGET_BYTES).also { cache -> opened += AutoCloseable { cache.release() } }
        return Downloads.Builder(context, cache)
            .setEnvironment(environment)
            .apply { if (withService) setService(TestDownloadsService::class.java) }
            .build()
            .also { store ->
                opened += AutoCloseable { store.release() }
                TestDownloadsService.store = store
            }
    }

    private fun request(content: TestContent): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    /** A consumer's subclass, as small as one can be. */
    class TestDownloadsService : DownloadsService() {

        override fun onDownloads(): Downloads = checkNotNull(store) { "The test opened no store" }

        override val notificationChannelName: Int = android.R.string.untitled

        override val notificationSmallIcon: Int = android.R.drawable.stat_sys_download

        companion object {
            var store: Downloads? = null
        }
    }

    private companion object {
        const val CONTENT_ID = "film/the-lady-vanishes"
        const val SEGMENTS = 4
        const val BUDGET_BYTES = 64L * 1024 * 1024
        const val IDLE_PASSES = 20
    }
}
