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
import com.superplayer.cache.CachePolicy
import com.superplayer.cache.ContentKeyedCache
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.MediaRequest
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
import java.time.Duration

/**
 * #251, ADR-0013 rule 8's last sentence: live content is refused at enqueue. The store reads the manifest it
 * reads anyway to choose tracks, over the one chain, and a live one — an HLS media playlist with no
 * `EXT-X-ENDLIST`, a DASH MPD of `type="dynamic"` — ends the item [DownloadState.FAILED] with
 * [DownloadRefusal.LIVE_CONTENT] on it, having asked for no media and taken no pin.
 *
 * Counted as what left the download's chain, never as "it failed": a download that fetched a live window and
 * then failed would also be failed. The controls are on-demand content of both protocols, which completes
 * with no refusal, and an unreadable live manifest, which is a failure and not a refusal — the store cannot
 * know content is live without having read it.
 */
@RunWith(AndroidJUnit4::class)
class DownloadLiveRefusalTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    @Before
    fun anUnmeteredNetwork() {
        DeviceStatement.declareNetworkMetered(false)
        harness.useScheduledWork()
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
    }

    /** A store built without a resilience still shows the refusal and its reason: a refusal is not a failure to name. */
    @Test
    fun aLiveHlsStreamIsRefusedHavingFetchedNoMediaAndTakenNoPin() {
        assertRefused(TestContent.liveHls(), withResilience = false)
    }

    /** And one built with a resilience names no failure beside it, because nothing failed. */
    @Test
    fun aLiveDashStreamIsRefusedHavingFetchedNoMediaAndTakenNoPin() {
        assertRefused(TestContent.liveDash(), withResilience = true)
    }

    @Test
    fun anOnDemandHlsStreamStillDownloads() {
        assertDownloads(TestContent.hls())
    }

    @Test
    fun anOnDemandDashStreamStillDownloads() {
        assertDownloads(TestContent.dash())
    }

    /**
     * The refusal is decided off the manifest the chain fetched, so a fault the harness puts on that chain
     * reaches it: a live playlist that arrives empty is a failure to read, reported as one, and not a
     * refusal the store could not have known to make.
     */
    @Test
    fun aLiveManifestThatCannotBeReadIsAFailureAndNotARefusal() {
        val content = TestContent.liveHls()
        val environment = harness.downloadEnvironment(
            content,
            // An empty body rather than a status, for DownloadSelectionTest's reason: Media3 does not ask again
            // for a manifest it could not parse, while a refused one is retried on a timer this harness does not own.
            FaultScript.Builder().truncateAfterBytes(0, ResourceKind.MANIFEST).build(),
        )
        val cache = openCache()
        val downloads = openStore(cache, environment, withResilience = false)

        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the download failed") {
            // Media3's download helper looks for a failed preparation every 100ms of the system clock.
            ShadowSystemClock.advanceBy(Duration.ofMillis(HELPER_FAILURE_POLL_MS))
            downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
        }

        val failed = downloads.download(CONTENT_ID)!!
        assertThat(failed.refusal).isNull()
        assertThat(failed.bytesDownloaded).isEqualTo(0L)
        assertThat(harness.networkRequests(environment).map { it.kind }.toSet()).containsExactly(ResourceKind.MANIFEST)
        assertThat(cache.isPinned(CONTENT_ID)).isFalse()
    }

    private fun assertRefused(content: TestContent, withResilience: Boolean) {
        val environment = harness.downloadEnvironment(content)
        val cache = openCache()
        val downloads = openStore(cache, environment, withResilience)
        val reports = mutableListOf<DownloadItem>()
        downloads.addListener(Recorder { reports += it })

        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the download was refused") { downloads.download(CONTENT_ID)?.state == DownloadState.FAILED }

        val refused = downloads.download(CONTENT_ID)!!
        assertThat(refused.refusal).isEqualTo(DownloadRefusal.LIVE_CONTENT)
        assertThat(refused.failure).isNull()
        assertThat(refused.bytesDownloaded).isEqualTo(0L)
        assertThat(reports.last()).isEqualTo(refused)
        assertThat(downloads.downloads()).containsExactly(refused)
        // The manifest was read — that is how the store knew — and nothing else was asked for.
        val kinds = harness.networkRequests(environment).map { it.kind }
        assertThat(kinds).contains(ResourceKind.MANIFEST)
        assertThat(kinds).containsNoneOf(ResourceKind.MEDIA_SEGMENT, ResourceKind.INITIALIZATION)
        assertThat(cache.isPinned(CONTENT_ID)).isFalse()
    }

    private fun assertDownloads(content: TestContent) {
        val environment = harness.downloadEnvironment(content)
        val cache = openCache()
        val downloads = openStore(cache, environment, withResilience = true)

        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the download completed") { downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED }

        assertThat(downloads.download(CONTENT_ID)!!.refusal).isNull()
        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.MEDIA_SEGMENT }).isGreaterThan(0)
        assertThat(cache.isPinned(CONTENT_ID)).isTrue()
    }

    private fun openCache(): ContentKeyedCache =
        CachePolicy.contentKeyed(folder.newFolder(), LARGE_BUDGET_BYTES).also { cache -> opened += AutoCloseable { cache.release() } }

    private fun openStore(cache: ContentKeyedCache, environment: DownloadEnvironment, withResilience: Boolean): Downloads =
        Downloads.Builder(context, cache).setEnvironment(environment)
            .apply { if (withResilience) setResilience(Resilience.standard()) }
            .build()
            .also { store -> opened += AutoCloseable { store.release() } }

    private fun request(content: TestContent): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    private class Recorder(private val onChanged: (DownloadItem) -> Unit) : DownloadsListener {
        override fun onDownloadChanged(item: DownloadItem) = onChanged(item)
        override fun onDownloadRemoved(contentId: String) = Unit
    }

    private companion object {
        const val CONTENT_ID = "radio/the-shipping-forecast"

        // How often Media3's download helper looks for a failed preparation, on the system clock.
        const val HELPER_FAILURE_POLL_MS = 100L

        // Far more than any synthetic stream here, so nothing is evicted.
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024
    }
}
