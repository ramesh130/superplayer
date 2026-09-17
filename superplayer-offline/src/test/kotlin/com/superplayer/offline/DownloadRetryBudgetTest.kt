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
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.cache.CachePolicy
import com.superplayer.cache.ContentKeyedCache
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlaybackResilience
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.resilience.CredentialRefusal
import com.superplayer.resilience.HeaderProvider
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.NetworkRequest
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #254, ADR-0013 rule 14: a download spends the store's policy's retry budgets, and its resilience's
 * `HeaderProvider` repairs a refused credential inside the transfer.
 *
 * Counted as asks at the one address a fault is injected at, which is what a budget bounds. A retry waits on the
 * store's thread, whose clock Robolectric moves only when told, so the tests that wait through one move it a
 * step per pass ([advanceStoreClock]) — and one of them shows that nothing is asked for again until it moves.
 */
@RunWith(AndroidJUnit4::class)
class DownloadRetryBudgetTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    @Before
    fun anUnmeteredNetwork() {
        // Robolectric's own network is metered and unvalidated, which the store's default requirement refuses.
        DeviceStatement.declareNetworkMetered(false)
        // A store with a download pending schedules it, and WorkManager is initialized by nothing else here.
        harness.useScheduledWork()
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
    }

    @Test
    fun aSegmentThatFailsFewerTimesThanItsBudgetRecoversWithNoFailureReportedAndWaitsOnTheStoresClock() {
        val content = TestContent.hls(SEGMENTS)
        val faults = FaultScript.Builder()
            .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.MEDIA_SEGMENT, index = FAULTED, firstAttempts = 3)
            .build()
        val environment = harness.downloadEnvironment(content, faults)
        val downloads = openStore(environment, Resilience.standard(), policyOf(segment = budget(maxRetries = 3)))
        val reports = record(downloads)
        downloads.enqueue(request(content))

        // Media3's own retries sleep the download thread; the store's wait is a post on its looper, so with that
        // looper's clock held the downloader parks, having asked once, and asks nothing more until it moves.
        harness.advanceUntil(environment, "the downloader waited to retry") { downloads.isWaitingToRetry() }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(faultedSegmentAsks(environment)).hasSize(1)
        assertThat(downloads.download(CONTENT_ID)!!.state).isEqualTo(DownloadState.DOWNLOADING)

        harness.advanceUntil(environment, "the download completed") {
            advanceStoreClock()
            downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }

        assertThat(faultedSegmentAsks(environment)).hasSize(4)
        assertThat(reports.map { it.state }).containsNoneOf(DownloadState.FAILED, DownloadState.STOPPED)
        assertThat(reports.map { it.bytesDownloaded }).isInOrder()
        assertThat(reports.mapNotNull { it.percentDownloaded }).isInOrder()
    }

    /**
     * Past the budget the item fails, named, after exactly one ask and the budget's retries. The fault is a 5xx,
     * which is `Transient.Network` as a lost network is, and it is not stopped and waited out as one: a server
     * answered, so there was a network (ADR-0013 rule 9's addendum for #254).
     */
    @Test
    fun aSegmentThatFailsPastItsBudgetFailsNamedAfterExactlyTheBudgetedAsksRatherThanStopping() {
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content, alwaysFailing(FaultScript.HTTP_SERVER_ERROR))
        val downloads = openStore(environment, Resilience.standard(), policyOf(segment = budget(maxRetries = 2)))
        val reports = record(downloads)
        downloads.enqueue(request(content))

        harness.advanceUntil(environment, "the download failed") {
            advanceStoreClock()
            downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
        }

        assertThat(faultedSegmentAsks(environment)).hasSize(1 + 2)
        val failed = downloads.download(CONTENT_ID)!!
        assertThat(failed.failure?.causeClass).isEqualTo("Transient.Network")
        assertThat(failed.stopReason).isNull()
        // Failed once, at the end, and never on the way there.
        assertThat(reports.map { it.state }).doesNotContain(DownloadState.STOPPED)
        assertThat(reports.count { it.state == DownloadState.FAILED }).isEqualTo(1)
        assertThat(reports.last().state).isEqualTo(DownloadState.FAILED)
    }

    /** The control that the budget is the decision's: two profiles whose segment budgets differ ask a different number of times. */
    @Test
    fun twoProfilesWithDifferentSegmentBudgetsAskADifferentNumberOfTimes() {
        val asks = listOf(PlaybackProfile.VIDEO_ON_DEMAND, PlaybackProfile.SHORT_FORM).associateWith { profile ->
            val content = TestContent.hls(SEGMENTS)
            val environment = harness.downloadEnvironment(content, alwaysFailing(FaultScript.HTTP_NOT_FOUND))
            val downloads = openStore(environment, Resilience.standard(), PlaybackPolicy.forProfile(profile))
            downloads.enqueue(request(content))
            harness.advanceUntil(environment, "the $profile download failed") {
                advanceStoreClock()
                downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
            }
            assertThat(downloads.download(CONTENT_ID)!!.failure?.causeClass).isEqualTo("Transient.CdnEdge")
            faultedSegmentAsks(environment).size
        }

        val segmentBudget = { profile: PlaybackProfile -> PlaybackPolicy.forProfile(profile).decide(NOTHING_OBSERVED).retry.segment.maxRetries }
        assertThat(segmentBudget(PlaybackProfile.VIDEO_ON_DEMAND)).isNotEqualTo(segmentBudget(PlaybackProfile.SHORT_FORM))
        asks.forEach { (profile, count) -> assertWithMessage("$profile asks").that(count).isEqualTo(1 + segmentBudget(profile)) }
    }

    /** A manifest spends the manifest budget, not the segment one: a failure's kind decides which it draws on. */
    @Test
    fun aManifestTheDownloaderCannotReadSpendsTheManifestBudget() {
        val content = TestContent.hls(SEGMENTS)
        // Served to the manifest read that chooses the tracks, and refused to the downloader from then on.
        val faults = FaultScript.Builder()
            .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.MANIFEST, afterAttempts = 1)
            .build()
        val environment = harness.downloadEnvironment(content, faults)
        val policy = policyOf(manifest = budget(maxRetries = 1), segment = budget(maxRetries = 4))
        val downloads = openStore(environment, Resilience.standard(), policy)
        downloads.enqueue(request(content))

        harness.advanceUntil(environment, "the download failed") {
            advanceStoreClock()
            downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
        }

        val manifestAsks = harness.networkRequests(environment).filter { it.kind == ResourceKind.MANIFEST }.groupBy { it.uri }.values
        // The first manifest the downloader asks for: once by the read, once by the downloader, and once again.
        assertThat(manifestAsks.maxOf { it.size }).isEqualTo(1 + 1 + 1)
        assertThat(harness.networkRequests(environment).filter { it.kind == ResourceKind.MEDIA_SEGMENT }).isEmpty()
    }

    /** A refused credential is repaired inside the transfer that met it, with a budget of nothing to spend. */
    @Test
    fun aRefusedCredentialIsRepairedByTheStoresHeaderProviderSpendingNoRetry() {
        val content = TestContent.hls(SEGMENTS)
        val faults = FaultScript.Builder().expireTokenAtSegment(FAULTED, refreshable = true).build()
        val environment = harness.downloadEnvironment(content, faults)
        val provider = MintingProvider()
        val downloads = openStore(environment, Resilience.standard(headers = provider), policyOf(segment = budget(maxRetries = 0)))
        val reports = record(downloads)
        downloads.enqueue(request(content))

        harness.advanceUntil(environment, "the download ended") { downloads.download(CONTENT_ID)?.state in ENDED }
        assertThat(downloads.download(CONTENT_ID)!!.state).isEqualTo(DownloadState.COMPLETED)

        // The refused ask and its repair, one transfer: with no retry to spend, nothing else could have healed it.
        val faulted = faultedSegmentAsks(environment)
        assertThat(faulted).hasSize(2)
        assertThat(faulted.first().headers).doesNotContainKey(AUTHORIZATION)
        assertThat(faulted.last().headers).containsEntry(AUTHORIZATION, provider.minted.single())
        assertThat(reports.map { it.state }).doesNotContain(DownloadState.FAILED)
    }

    /** The control for the repair: the same refusal on a store whose resilience has no provider fails, having asked once. */
    @Test
    fun aRefusedCredentialWithNoProviderToRepairItSpendsTheBudgetAndFails() {
        val content = TestContent.hls(SEGMENTS)
        val faults = FaultScript.Builder().expireTokenAtSegment(FAULTED, refreshable = true).build()
        val environment = harness.downloadEnvironment(content, faults)
        val downloads = openStore(environment, Resilience.standard(), policyOf(segment = budget(maxRetries = 0)))
        downloads.enqueue(request(content))

        harness.advanceUntil(environment, "the download failed") { downloads.download(CONTENT_ID)?.state == DownloadState.FAILED }

        assertThat(faultedSegmentAsks(environment)).hasSize(1)
        assertThat(downloads.download(CONTENT_ID)!!.failure?.causeClass).isEqualTo("Transient.CdnEdge")
    }

    /**
     * The control for the resilience: a store built without one reads no budget, and its manager retries as Media3
     * ships it. A budget of nothing and a segment that fails three times: without a resilience it completes, asked
     * four times; with one it fails, asked once.
     */
    @Test
    fun aStoreWithoutResilienceRetriesAsMedia3DoesWhateverThePolicysBudget() {
        val policy = policyOf(segment = budget(maxRetries = 0))
        val oneFailure = FaultScript.Builder()
            .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.MEDIA_SEGMENT, index = FAULTED, firstAttempts = MEDIA3_RETRIES_SHOWN)
            .build()

        val content = TestContent.hls(SEGMENTS)
        val withoutResilience = harness.downloadEnvironment(content, oneFailure)
        val plain = openStore(withoutResilience, resilience = null, policy = policy)
        plain.enqueue(request(content))
        harness.advanceUntil(withoutResilience, "the download without a resilience completed") {
            plain.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }
        assertThat(faultedSegmentAsks(withoutResilience)).hasSize(1 + MEDIA3_RETRIES_SHOWN)

        val withResilience = harness.downloadEnvironment(content, oneFailure)
        val resilient = openStore(withResilience, Resilience.standard(), policy)
        resilient.enqueue(request(content))
        harness.advanceUntil(withResilience, "the download with a resilience failed") {
            resilient.download(CONTENT_ID)?.state == DownloadState.FAILED
        }
        assertThat(faultedSegmentAsks(withResilience)).hasSize(1)
    }

    /** Every ask at the segment the faults are injected at: the third distinct one, since a download asks for them in order. */
    private fun faultedSegmentAsks(environment: DownloadEnvironment): List<NetworkRequest> {
        val segments = harness.networkRequests(environment).filter { it.kind == ResourceKind.MEDIA_SEGMENT }
        val faultedUri = segments.map { it.uri }.distinct().getOrNull(FAULTED) ?: return emptyList()
        return segments.filter { it.uri == faultedUri }
    }

    private fun alwaysFailing(status: Int): FaultScript = FaultScript.Builder()
        .failWithHttpStatus(status, kind = ResourceKind.MEDIA_SEGMENT, index = FAULTED)
        .build()

    /** One step of the store's thread's clock, which is the clock a retry's wait runs on. */
    private fun advanceStoreClock() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(STORE_CLOCK_STEP_MS))
    }

    /**
     * The on-demand profile's decision with [manifest] and [segment] for its retry half. Hand-written budgets rather
     * than a profile's rows wherever the count is the point, for `TokenRefreshPlaybackTest`'s reason: the rows are
     * argued in `StaticProfilePolicy`, not here.
     */
    private fun policyOf(manifest: RetryBudget = RetryBudget.MEDIA3_DEFAULT, segment: RetryBudget): PlaybackPolicy {
        val profile = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND)
        return PlaybackPolicy { conditions -> profile.decide(conditions).copy(retry = RetryPolicy(manifest = manifest, segment = segment)) }
    }

    // Waits short against the store clock's step, so a budget is spent in a handful of passes.
    private fun budget(maxRetries: Int) = RetryBudget(maxRetries = maxRetries, initialBackoffMs = 100, maxBackoffMs = 400)

    private fun record(downloads: Downloads): MutableList<DownloadItem> = CopyOnWriteArrayList<DownloadItem>().also { reports ->
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

    private fun openStore(environment: DownloadEnvironment, resilience: PlaybackResilience?, policy: PlaybackPolicy): Downloads =
        Downloads.Builder(context, openCache())
            .setEnvironment(environment)
            .setPolicy(policy)
            .apply { resilience?.let(::setResilience) }
            .build()
            .also { store -> opened += AutoCloseable { store.release() } }

    private fun request(content: TestContent): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    /** Mints a new bearer token per refusal, and remembers each. */
    private class MintingProvider : HeaderProvider {

        val minted = CopyOnWriteArrayList<String>()

        override fun refreshedHeaders(refusal: CredentialRefusal): Map<String, String> {
            val token = "Bearer minted-${minted.size + 1}"
            minted += token
            return mapOf(AUTHORIZATION to token)
        }
    }

    private companion object {
        const val CONTENT_ID = "film/the-thirty-nine-steps"
        const val SEGMENTS = 6

        // The third segment, so the download has progress to keep when the fault meets it.
        const val FAULTED = 2

        const val AUTHORIZATION = "Authorization"

        // Failures Media3's manager retries past on a store without a resilience: more than any budget the test
        // decides, and short of Media3's five, whose linear waits sleep real seconds (0, 1 and 2 here).
        const val MEDIA3_RETRIES_SHOWN = 3

        // Far more than any synthetic stream here, so nothing is evicted.
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024

        // Short against the shortest wait these tests draw (50 ms, half of 100), so no retry is skipped past.
        const val STORE_CLOCK_STEP_MS = 25L

        val ENDED = setOf(DownloadState.COMPLETED, DownloadState.FAILED)

        val NOTHING_OBSERVED = PlaybackConditions()
    }
}
