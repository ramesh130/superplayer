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
import com.superplayer.core.SuperPlayer
import com.superplayer.drm.Drm
import com.superplayer.drm.OfflineLicence
import com.superplayer.drm.OfflineLicenceStore
import com.superplayer.drm.OfflineLicences
import com.superplayer.drm.WidevineConfig
import com.superplayer.resilience.ErrorClassifier
import com.superplayer.resilience.FailureClass
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
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
 * A protected download carries its offline licence (ADR-0013 rule 13, #245): acquired with the download,
 * played with no network, expired visibly, never renewed on its own, and released when the download is
 * removed — with or without a network at the moment of removal.
 *
 * Every claim a viewer cares about is a count at the transport rather than an inference from state: one
 * licence request for a download, before its first segment; **zero** requests of any kind for its playback;
 * one more licence request, the release, for its removal. What stands in for a Widevine device and a licence
 * server is the harness's, as in `superplayer-drm`'s `OfflineLicenceTest`, and a download environment over
 * protected content carries the same device a player of it is given.
 */
@RunWith(AndroidJUnit4::class)
class DownloadLicenceTest {

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

    @Test
    fun downloadingProtectedContentAcquiresOneLicenceBeforeItsFirstSegmentAndStoresItUnderTheContentId() {
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment)

        val item = downloadToCompletion(downloads, environment)

        val requests = harness.networkRequests(environment)
        assertThat(requests.count { it.kind == ResourceKind.LICENCE }).isEqualTo(1)
        // After the manifest and before the first media byte: a refused licence then costs no media.
        val licenceAt = requests.indexOfFirst { it.kind == ResourceKind.LICENCE }
        assertThat(licenceAt).isGreaterThan(requests.indexOfFirst { it.kind == ResourceKind.MANIFEST })
        assertThat(licenceAt).isLessThan(requests.indexOfFirst { it.kind == ResourceKind.MEDIA_SEGMENT })
        assertThat(licences.contentIds()).containsExactly(CONTENT_ID)
        assertThat(licences.licenceFor(CONTENT_ID)!!.isExpired).isFalse()
        assertThat(item.licence!!.isExpired).isFalse()
        assertThat(item.licence!!.expiry).isNull()
    }

    /**
     * Recorded rather than asserted as desirable. A licence is requested for the protection the manifest read
     * sees, and Media3 reads an HLS download's multivariant playlist and no media playlist: a stream that
     * declares its key only in an `EXT-X-KEY` of each media playlist, as this synthetic one does, downloads
     * with no licence and cannot play offline. One that declares it in the multivariant playlist's
     * `EXT-X-SESSION-KEY` (// spec: RFC 8216 §4.3.4.5) shows it to the read, as a DASH MPD's
     * `ContentProtection` does. `docs/testing.md`'s *Downloads* says so too.
     */
    @Test
    fun anHlsStreamDeclaringItsKeyOnlyInItsMediaPlaylistsDownloadsWithNoLicence() {
        val hls = TestContent.protectedHls()
        val environment = harness.downloadEnvironment(hls)
        val downloads = openStore(environment)

        downloads.enqueue(MediaRequest.Builder(CONTENT_ID).addSource(hls.sourceUri).build())
        harness.advanceUntil(environment, "the download ended") {
            downloads.download(CONTENT_ID)?.state in setOf(DownloadState.COMPLETED, DownloadState.FAILED)
        }

        assertThat(downloads.download(CONTENT_ID)!!.state).isEqualTo(DownloadState.COMPLETED)
        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.LICENCE }).isEqualTo(0)
        assertThat(downloads.download(CONTENT_ID)!!.licence).isNull()
    }

    @Test
    fun aDownloadPlaysWithTheNetworkOffMakingNoLicenceAndNoMediaRequest() {
        val environment = harness.downloadEnvironment(content)
        downloadToCompletion(openStore(environment), environment)

        val player = offlinePlayer(checkNotNull(licences.licenceFor(CONTENT_ID)))
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content") { it.playbackState == Player.STATE_ENDED || it.playerError != null }

        assertWithMessage("cause: ${player.playerError?.cause}").that(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
        // Every request would have failed, so any one of them would have been counted here and very likely
        // ended the session: none was made.
        val requests = harness.networkRequests(player)
        assertThat(requests.filter { it.kind == ResourceKind.LICENCE }).isEmpty()
        assertThat(requests.filter { it.kind == ResourceKind.MEDIA_SEGMENT }).isEmpty()
        assertThat(requests).isEmpty()
    }

    @Test
    fun anExpiredLicenceIsReportedOnTheDownloadBeforeAnyPlayerAndPlayingItAnywayEndsLicenceExpired() {
        DeviceStatement.declareOfflineLicence(licenceDurationSec = 0, playbackDurationSec = 0)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment, resilient = true)

        val item = downloadToCompletion(downloads, environment)

        // Completed, because every byte is there, and dead, which is the other thing a list screen shows.
        assertThat(item.state).isEqualTo(DownloadState.COMPLETED)
        val licence = checkNotNull(item.licence)
        assertThat(licence.isExpired).isTrue()
        assertThat(licence.expiry!!.causeClass).isEqualTo(FailureClass.Drm.LicenceExpired.stableName)
        assertThat(licence.expiry!!.userMessageKey).isEqualTo(FailureClass.LICENCE_EXPIRED_MESSAGE_KEY)

        val player = offlinePlayer(checkNotNull(licences.licenceFor(CONTENT_ID)), resilient = true)
        harness.playToFailure(player)

        assertThat(ErrorClassifier.classify(checkNotNull(player.playerError))).isEqualTo(FailureClass.Drm.LicenceExpired)
        assertThat(harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }).isEmpty()
    }

    @Test
    fun renewalDueIsReportedOnTheDownloadAndNothingRenewsIt() {
        // An hour, well inside the licence store's day.
        DeviceStatement.declareOfflineLicence(licenceDurationSec = 3600, playbackDurationSec = 3600)
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment)

        val item = downloadToCompletion(downloads, environment)
        assertThat(item.licence!!.renewalDue).isTrue()
        assertThat(item.licence!!.isExpired).isFalse()

        // The store's own chances to act on its own: a change of conditions, and its scheduled work.
        DeviceStatement.declareStorageLow(false)
        harness.runScheduledWork()
        harness.advanceUntil(environment, "the store has read the download again") { downloads.download(CONTENT_ID)?.licence?.renewalDue == true }

        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.LICENCE }).isEqualTo(1)
        assertThat(licences.licenceFor(CONTENT_ID)!!.renewalDue).isTrue()
    }

    @Test
    fun removingADownloadReleasesItsLicenceAtTheServerAndTheStoreNoLongerHoldsIt() {
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment)
        downloadToCompletion(downloads, environment)

        downloads.remove(CONTENT_ID)
        harness.advanceUntil(environment, "the download was removed and its licence released") {
            downloads.download(CONTENT_ID) == null && licences.contentIdsAwaitingRelease().isEmpty()
        }

        assertThat(licences.licenceFor(CONTENT_ID)).isNull()
        assertThat(licences.contentIds()).isEmpty()
        assertThat(cache.isPinned(CONTENT_ID)).isFalse()
        // The acquisition and the release, and no more: a release the server never heard of leaves the licence
        // counted against the viewer's device.
        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.LICENCE }).isEqualTo(2)
    }

    @Test
    fun aRemovalWithNoNetworkDeletesTheBytesAtOnceAndReleasesTheLicenceOnceTheNetworkIsBack() {
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment)
        downloadToCompletion(downloads, environment)
        // Only a metered network, which the store does not download over: the release waits for the same.
        DeviceStatement.declareNetworkMetered(true)

        downloads.remove(CONTENT_ID)
        harness.advanceUntil(environment, "the download was removed") { downloads.download(CONTENT_ID) == null }

        // The storage is the viewer's at once, and the licence is given to no player from the moment they asked.
        assertThat(cache.isPinned(CONTENT_ID)).isFalse()
        assertThat(licences.licenceFor(CONTENT_ID)).isNull()
        assertThat(licences.contentIdsAwaitingRelease()).containsExactly(CONTENT_ID)
        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.LICENCE }).isEqualTo(1)

        DeviceStatement.declareNetworkMetered(false)
        harness.advanceUntil(environment, "the licence was released") { licences.contentIdsAwaitingRelease().isEmpty() }

        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.LICENCE }).isEqualTo(2)
    }

    @Test
    fun aReleaseAClosedStoreStillOwedIsMadeByTheNextStoreOpenedOverTheSameLicences() {
        val environment = harness.downloadEnvironment(content)
        val first = openStore(environment)
        downloadToCompletion(first, environment)
        DeviceStatement.declareNetworkMetered(true)
        first.remove(CONTENT_ID)
        harness.advanceUntil(environment, "the download was removed") { first.download(CONTENT_ID) == null }
        first.release()
        assertThat(licences.contentIdsAwaitingRelease()).containsExactly(CONTENT_ID)

        DeviceStatement.declareNetworkMetered(false)
        openStore(environment)
        harness.advanceUntil(environment, "the next store released the licence") { licences.contentIdsAwaitingRelease().isEmpty() }

        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.LICENCE }).isEqualTo(2)
    }

    @Test
    fun aReleaseTheLicenceServerNeverReceivedIsOwedUntilOneGetsThrough() {
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment)
        downloadToCompletion(downloads, environment)
        // The platform calls the network connected and nothing reaches the origin: a captive portal, a tunnel.
        harness.loseNetwork(environment)

        downloads.remove(CONTENT_ID)
        harness.advanceUntil(environment, "the release was tried and given up on") {
            advanceLicenceRetryClock()
            downloads.download(CONTENT_ID) == null && !downloads.isReleasingLicences()
        }
        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.LICENCE }).isGreaterThan(1)
        assertThat(licences.contentIdsAwaitingRelease()).containsExactly(CONTENT_ID)

        harness.restoreNetwork(environment)
        assertThat(harness.runScheduledWork()).isGreaterThan(0)
        harness.advanceUntil(environment, "the licence was released by the scheduled work's run") {
            licences.contentIdsAwaitingRelease().isEmpty()
        }
        assertThat(licences.contentIds()).isEmpty()
    }

    @Test
    fun anOwedReleaseLeavesTheLicenceOfTheSameContentDownloadedAgainInForce() {
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(environment)
        downloadToCompletion(downloads, environment)
        harness.loseNetwork(environment)
        downloads.remove(CONTENT_ID)
        harness.advanceUntil(environment, "the release was tried and given up on") {
            advanceLicenceRetryClock()
            downloads.download(CONTENT_ID) == null && !downloads.isReleasingLicences()
        }
        harness.restoreNetwork(environment)

        // Downloaded again before anything retried the release: the old licence is still owed, the new one is not.
        downloadToCompletion(downloads, environment)
        harness.runScheduledWork()
        harness.advanceUntil(environment, "the owed release was made") { licences.contentIdsAwaitingRelease().isEmpty() }

        assertThat(licences.licenceFor(CONTENT_ID)).isNotNull()
        assertThat(downloads.download(CONTENT_ID)!!.licence).isNotNull()
    }

    @Test
    fun aLicenceRefusedDuringTheDownloadFailsTheItemTypedAndLeavesNoPinnedBytes() {
        val environment = harness.downloadEnvironment(
            content,
            faults = FaultScript.Builder().failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, kind = ResourceKind.LICENCE).build(),
        )
        val downloads = openStore(environment, resilient = true)

        downloads.enqueue(request())
        harness.advanceUntil(environment, "the download failed") {
            advanceLicenceRetryClock()
            downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
        }

        val failure = checkNotNull(downloads.download(CONTENT_ID)!!.failure)
        assertThat(failure.causeClass).isEqualTo(FailureClass.Drm.LicenceAcquisition.stableName)
        assertThat(cache.isPinned(CONTENT_ID)).isFalse()
        assertThat(harness.networkRequests(environment).filter { it.kind == ResourceKind.MEDIA_SEGMENT }).isEmpty()
        assertThat(licences.contentIds()).isEmpty()
    }

    /**
     * ADR-0013 rule 1: a clear-only app adds this module and `superplayer-cache`, and nothing that knows what
     * Widevine is. Checked against this module's build file and main sources, which is where a dependency on
     * `superplayer-drm` would arrive: everything protection needs is a core type the consumer hands in.
     */
    @Test
    fun aClearOnlyConsumerCarriesNoProtectionModule() {
        val mainDependencies = Regex("""^\s*(api|implementation|compileOnly|runtimeOnly)\(project\("([^"]+)"\)\)""", RegexOption.MULTILINE)
            .findAll(File("build.gradle.kts").readText())
            .map { it.groupValues[2] }
            .toList()
        assertThat(mainDependencies).containsExactly(":superplayer-core")
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { source ->
            assertWithMessage("${source.path} names superplayer-drm").that(source.readText()).doesNotContain("com.superplayer.drm")
        }
    }

    private fun openStore(environment: DownloadEnvironment, resilient: Boolean = false): Downloads =
        Downloads.Builder(context, cache)
            .setEnvironment(environment)
            .setDrm(Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)), licences)
            .apply { if (resilient) setResilience(Resilience.standard()) }
            .build()
            .also { store -> opened += AutoCloseable { store.release() } }

    private fun downloadToCompletion(downloads: Downloads, environment: DownloadEnvironment): DownloadItem {
        downloads.enqueue(request())
        harness.advanceUntil(environment, "the download completed") {
            downloads.download(CONTENT_ID)?.state in setOf(DownloadState.COMPLETED, DownloadState.FAILED)
        }
        return checkNotNull(downloads.download(CONTENT_ID)).also { assertThat(it.state).isEqualTo(DownloadState.COMPLETED) }
    }

    /** A player of the download with the licence store's licence, and every request it could make failing. */
    private fun offlinePlayer(licence: OfflineLicence, resilient: Boolean = false): SuperPlayer =
        harness.buildPlayer(
            content = content,
            cache = cache,
            faults = FaultScript.Builder().failDnsResolution().build(),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI), licence),
            resilience = if (resilient) Resilience.standard() else null,
        ).also { it.setMediaRequest(request()) }

    /**
     * One step of the clock a licence retry waits on, which is Robolectric's system clock on the session's request
     * thread and not the harness's: Media3's own waits, or the store's licence budget where it has a resilience (#260).
     */
    private fun advanceLicenceRetryClock() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(LICENCE_RETRY_CLOCK_STEP_MS))
    }

    private fun request(): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    private companion object {
        const val CONTENT_ID = "film/the-third-man"

        // Far more than the synthetic stream, so nothing is evicted.
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024

        // Media3 waits a second more before each licence retry, so a second a step reaches every one.
        const val LICENCE_RETRY_CLOCK_STEP_MS = 1_000L
    }
}
