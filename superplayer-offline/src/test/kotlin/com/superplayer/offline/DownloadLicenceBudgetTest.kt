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
import android.os.SystemClock
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
import com.superplayer.drm.Drm
import com.superplayer.drm.OfflineLicenceStore
import com.superplayer.drm.OfflineLicences
import com.superplayer.drm.WidevineConfig
import com.superplayer.resilience.FailureClass
import com.superplayer.resilience.HeaderProvider
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #260, ADR-0013 rule 14: a download's licence exchange spends the store's `RetryPolicy.licence`, and its
 * resilience's `HeaderProvider` repairs a licence server's refused credential inside the transfer — as a
 * player's licence load does (`superplayer-drm`'s `LicenceLoadTest`, #205).
 *
 * Counted as licence requests at the transport, because a retry is only a retry if the bytes were asked for
 * again. Media3 waits between a session's licence asks on that session's request thread, whose clock is
 * Robolectric's system clock and moves only when told, so a test that waits through a retry moves it a step a
 * pass ([advanceLicenceClock]) — and one of them reads that clock at each ask to show the wait was the
 * budget's rather than Media3's.
 */
@RunWith(AndroidJUnit4::class)
class DownloadLicenceBudgetTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    private val content = TestContent.protectedDash()

    private lateinit var cache: ContentKeyedCache

    private lateinit var licences: OfflineLicenceStore

    @Before
    fun anUnmeteredNetworkACacheAndALicenceStore() {
        // Robolectric's own network is metered and unvalidated, which the store's default requirement refuses.
        DeviceStatement.declareNetworkMetered(false)
        // Stated, because WorkManager holds work that needs a battery not low on a device reporting none.
        DeviceStatement.declareBatteryLow(false)
        harness.useScheduledWork()
        cache = CachePolicy.contentKeyed(folder.newFolder(), LARGE_BUDGET_BYTES).also { opened += AutoCloseable { it.release() } }
        licences = OfflineLicences.store(folder.newFolder()).also { opened += it }
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
    }

    /**
     * Two refusals and a third ask that carried the licence, under a budget of three retries. Each ask's time on
     * the licence thread's clock is read as it is seen: the first retry comes no sooner than half the budget's
     * base, which is the least `Backoff`'s jitter draws, where Media3's own policy asks again at once.
     */
    @Test
    fun aLicenceThatFailsFewerTimesThanItsBudgetRecoversWithNoFailureReportedAndWaitsTheBudgetsBackoff() {
        val environment = harness.downloadEnvironment(content, licenceFails(firstAttempts = 2))
        val downloads = openStore(environment, Resilience.standard(), policyOf(licence = budget(maxRetries = 3)))
        val reports = record(downloads)
        downloads.enqueue(request())

        val askedAtMs = mutableListOf<Long>()
        harness.advanceUntil(environment, "the download completed") {
            repeat(licenceRequests(environment).size - askedAtMs.size) { askedAtMs += SystemClock.uptimeMillis() }
            advanceLicenceClock()
            downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }

        assertThat(licenceRequests(environment)).hasSize(3)
        assertThat(reports.map { it.state }).doesNotContain(DownloadState.FAILED)
        assertThat(licences.contentIds()).containsExactly(CONTENT_ID)
        askedAtMs.zipWithNext { before, after -> after - before }.forEach { waitedMs ->
            assertWithMessage("the wait before a licence retry").that(waitedMs).isAtLeast(BACKOFF_BASE_MS / 2)
        }
    }

    /** Past the budget the item fails, named, after exactly one ask and the budget's retries, having fetched and pinned nothing. */
    @Test
    fun aLicenceThatFailsPastItsBudgetFailsNamedAfterExactlyTheBudgetedAsksWithNothingPinned() {
        val environment = harness.downloadEnvironment(content, licenceFails(firstAttempts = null))
        val downloads = openStore(environment, Resilience.standard(), policyOf(licence = budget(maxRetries = 2)))
        val reports = record(downloads)
        downloads.enqueue(request())

        harness.advanceUntil(environment, "the download failed") {
            advanceLicenceClock()
            downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
        }
        // Well past any further wait the budget could have drawn, so "gave up" is told from "not yet".
        repeat(SETTLE_STEPS) { advanceLicenceClock() }
        harness.advanceUntil(environment, "the store settled") { true }

        assertThat(licenceRequests(environment)).hasSize(1 + 2)
        val failed = downloads.download(CONTENT_ID)!!
        assertThat(failed.failure?.causeClass).isEqualTo(FailureClass.Drm.LicenceAcquisition.stableName)
        assertThat(cache.isPinned(CONTENT_ID)).isFalse()
        assertThat(harness.networkRequests(environment).filter { it.kind == ResourceKind.MEDIA_SEGMENT }).isEmpty()
        assertThat(licences.contentIds()).isEmpty()
        assertThat(reports.count { it.state == DownloadState.FAILED }).isEqualTo(1)
    }

    /** The control that the budget is the store's decision: two profiles whose licence budgets differ ask a different number of times. */
    @Test
    fun twoProfilesWithDifferentLicenceBudgetsAskADifferentNumberOfTimes() {
        val licenceBudget = { profile: PlaybackProfile -> PlaybackPolicy.forProfile(profile).decide(NOTHING_OBSERVED).retry.licence.maxRetries }
        assertThat(licenceBudget(PlaybackProfile.VIDEO_ON_DEMAND)).isNotEqualTo(licenceBudget(PlaybackProfile.SHORT_FORM))

        listOf(PlaybackProfile.VIDEO_ON_DEMAND, PlaybackProfile.SHORT_FORM).forEach { profile ->
            val environment = harness.downloadEnvironment(content, licenceFails(firstAttempts = null))
            val downloads = openStore(environment, Resilience.standard(), PlaybackPolicy.forProfile(profile), newLicenceStore = true)
            downloads.enqueue(request())
            harness.advanceUntil(environment, "the $profile download failed") {
                advanceLicenceClock()
                downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
            }
            assertWithMessage("$profile licence asks").that(licenceRequests(environment)).hasSize(1 + licenceBudget(profile))
        }
    }

    /** A refused credential is repaired inside the transfer that met it, with a licence budget of nothing to spend. */
    @Test
    fun aLicenceRefusedForItsCredentialIsRepairedByTheStoresHeaderProviderSpendingNoRetry() {
        val environment = harness.downloadEnvironment(content, credentialRefused())
        val minted = CopyOnWriteArrayList<Int>()
        val provider = HeaderProvider { refusal ->
            minted += refusal.status
            mapOf(AUTHORIZATION to "Bearer minted-${minted.size}")
        }
        val downloads = openStore(environment, Resilience.standard(headers = provider), policyOf(licence = NO_RETRIES))
        val reports = record(downloads)
        downloads.enqueue(request())

        harness.advanceUntil(environment, "the download ended") { downloads.download(CONTENT_ID)?.state in ENDED }

        assertThat(downloads.download(CONTENT_ID)!!.state).isEqualTo(DownloadState.COMPLETED)
        assertThat(minted).containsExactly(FaultScript.HTTP_FORBIDDEN)
        // The refused ask and its repair, one transfer: with no retry to spend, nothing else could have healed it.
        val asks = licenceRequests(environment)
        assertThat(asks).hasSize(2)
        assertThat(asks.first().headers).doesNotContainKey(AUTHORIZATION)
        assertThat(asks.last().headers).containsEntry(AUTHORIZATION, "Bearer minted-1")
        assertThat(reports.map { it.state }).doesNotContain(DownloadState.FAILED)
    }

    /** The control for the repair: the same refusal on a store whose resilience has no provider fails, having asked once. */
    @Test
    fun aLicenceRefusedForItsCredentialWithNoProviderToRepairItFails() {
        val environment = harness.downloadEnvironment(content, credentialRefused())
        val downloads = openStore(environment, Resilience.standard(), policyOf(licence = NO_RETRIES))
        downloads.enqueue(request())

        harness.advanceUntil(environment, "the download failed") {
            advanceLicenceClock()
            downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
        }

        assertThat(licenceRequests(environment)).hasSize(1)
        assertThat(downloads.download(CONTENT_ID)!!.failure?.causeClass).isEqualTo(FailureClass.Drm.LicenceAcquisition.stableName)
    }

    /**
     * The control for the resilience: a store built without one reads no licence budget, and Media3's own licence
     * handling answers. A budget of nothing and a licence refused twice: without a resilience it completes, asked
     * three times, which is Media3's own patience; with one it fails, asked once.
     */
    @Test
    fun aStoreWithoutResilienceKeepsMedia3sOwnLicenceHandlingWhateverThePolicysBudget() {
        val policy = policyOf(licence = NO_RETRIES)

        val withoutResilience = harness.downloadEnvironment(content, licenceFails(firstAttempts = 2))
        val plain = openStore(withoutResilience, resilience = null, policy = policy)
        plain.enqueue(request())
        harness.advanceUntil(withoutResilience, "the download without a resilience completed") {
            advanceLicenceClock()
            plain.download(CONTENT_ID)?.state in ENDED
        }
        assertThat(plain.download(CONTENT_ID)!!.state).isEqualTo(DownloadState.COMPLETED)
        assertThat(licenceRequests(withoutResilience)).hasSize(3)

        val withResilience = harness.downloadEnvironment(content, licenceFails(firstAttempts = 2))
        val resilient = openStore(withResilience, Resilience.standard(), policy, newLicenceStore = true)
        resilient.enqueue(request())
        harness.advanceUntil(withResilience, "the download with a resilience failed") {
            advanceLicenceClock()
            resilient.download(CONTENT_ID)?.state == DownloadState.FAILED
        }
        assertThat(licenceRequests(withResilience)).hasSize(1)
    }

    private fun licenceRequests(environment: DownloadEnvironment): List<NetworkRequest> =
        harness.networkRequests(environment).filter { it.kind == ResourceKind.LICENCE }

    /** A licence server that answers 5xx to [firstAttempts] asks, or to every one when null. */
    private fun licenceFails(firstAttempts: Int?): FaultScript = FaultScript.Builder()
        .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.LICENCE, firstAttempts = firstAttempts)
        .build()

    /** A licence server that refuses the first ask's credential. */
    private fun credentialRefused(): FaultScript = FaultScript.Builder()
        .failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, kind = ResourceKind.LICENCE, firstAttempts = 1)
        .build()

    /** One step of the clock a licence retry's wait runs on: Robolectric's system clock, on the session's request thread. */
    private fun advanceLicenceClock() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(LICENCE_CLOCK_STEP_MS))
    }

    /**
     * The on-demand profile's decision with [licence] for its licence budget. Hand-written wherever the count is
     * the point, for `LicenceLoadTest`'s reason: the rows are argued in `StaticProfilePolicy`, not here.
     */
    private fun policyOf(licence: RetryBudget): PlaybackPolicy {
        val profile = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND)
        return PlaybackPolicy { conditions -> profile.decide(conditions).copy(retry = RetryPolicy(licence = licence)) }
    }

    // One base for every wait, so the least a jittered wait can be is a known number.
    private fun budget(maxRetries: Int) = RetryBudget(maxRetries = maxRetries, initialBackoffMs = BACKOFF_BASE_MS, maxBackoffMs = BACKOFF_BASE_MS)

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

    /**
     * A store over this test's cache, or over a cache and a licence store of its own where [newLicenceStore], for
     * a test that downloads the same content twice and must not find the first licence in force.
     */
    private fun openStore(
        environment: DownloadEnvironment,
        resilience: PlaybackResilience?,
        policy: PlaybackPolicy,
        newLicenceStore: Boolean = false,
    ): Downloads {
        val storeCache = if (newLicenceStore) {
            CachePolicy.contentKeyed(folder.newFolder(), LARGE_BUDGET_BYTES).also { opened += AutoCloseable { it.release() } }
        } else {
            cache
        }
        val storeLicences = if (newLicenceStore) OfflineLicences.store(folder.newFolder()).also { opened += it } else licences
        return Downloads.Builder(context, storeCache)
            .setEnvironment(environment)
            .setPolicy(policy)
            .setDrm(Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)), storeLicences)
            .apply { resilience?.let(::setResilience) }
            .build()
            .also { store -> opened += AutoCloseable { store.release() } }
    }

    private fun request(): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    private companion object {
        const val CONTENT_ID = "film/the-lady-vanishes"

        const val AUTHORIZATION = "Authorization"

        // Far more than the synthetic stream, so nothing is evicted.
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024

        // Long against the clock's step, so a wait of half of it is many passes and could not pass unnoticed.
        const val BACKOFF_BASE_MS = 400L

        // Short against the shortest wait drawn here (200 ms, half of the base), so no retry is skipped past; and
        // Media3's own waits, a second more per retry, are reached in a few dozen passes on the store without one.
        const val LICENCE_CLOCK_STEP_MS = 25L

        // Twice the longest wait the budget could draw, in steps.
        const val SETTLE_STEPS = (2 * BACKOFF_BASE_MS / LICENCE_CLOCK_STEP_MS).toInt()

        val NO_RETRIES = RetryBudget(maxRetries = 0, initialBackoffMs = 0, maxBackoffMs = 0)

        val ENDED = setOf(DownloadState.COMPLETED, DownloadState.FAILED)

        val NOTHING_OBSERVED = PlaybackConditions()
    }
}
