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
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * #240, the tracer bullet of ADR-0013: a store opened over the cache a consumer already opened downloads
 * a `MediaRequest`, reports its progress, and a player built over that cache then plays the content with
 * no request at all — asserted as a count of what left the player's chain, never as "it played". Removing
 * the download gives the bytes back.
 *
 * The download and the player each get their own origin from the harness over the same content, so what
 * each counts is what each sent: a download that stored everything is a player that asks for nothing.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    @Before
    fun anUnmeteredNetwork() {
        // Robolectric's own network is metered and unvalidated, which Media3's default requirement — a
        // validated network — refuses. Nothing here is about the network.
        DeviceStatement.declareNetworkMetered(false)
    }

    @After
    fun release() {
        // Stores before caches, which is the order a consumer releases them in.
        opened.asReversed().forEach { it.close() }
    }

    @Test
    fun anHlsDownloadCompletesWithItsProgressReportedInOrder() {
        assertProgressReportedInOrder(TestContent.hls(SEGMENTS))
    }

    @Test
    fun aDashDownloadCompletesWithItsProgressReportedInOrder() {
        assertProgressReportedInOrder(TestContent.dash(SEGMENTS))
    }

    @Test
    fun aDownloadedHlsItemPlaysWithZeroNetworkRequests() {
        assertPlaysWithNoNetwork(TestContent.hls(SEGMENTS))
    }

    @Test
    fun aDownloadedDashItemPlaysWithZeroNetworkRequests() {
        assertPlaysWithNoNetwork(TestContent.dash(SEGMENTS))
    }

    /**
     * The control for the zero: the same player over the same cache, for content that was streamed rather
     * than downloaded, still fetches its manifests — so the count of zero above is the download's doing,
     * and a cache does not start answering manifests for content nobody downloaded (ADR-0013 rule 8).
     */
    @Test
    fun streamedContentStillFetchesItsManifestsFromTheNetwork() {
        val content = TestContent.hls(SEGMENTS)
        val cache = openCache()

        playToEnd(harness.buildPlayer(content = content, cache = cache).also { it.setMediaRequest(request(CONTENT_ID, content)) })
        val replay = harness.buildPlayer(content = content, cache = cache)
        replay.setMediaRequest(request(CONTENT_ID, content))
        playToEnd(replay)

        val requests = harness.networkRequests(replay)
        assertThat(requests.map { it.kind }.toSet()).containsExactly(ResourceKind.MANIFEST)
    }

    /**
     * ADR-0013 rule 7 on ADR-0010 rule 12's answer: a budget far smaller than the download, and other
     * content streamed through the cache past it, evicts none of the download — while the other content,
     * which nothing pinned, is evicted, which is what shows the budget was really exceeded.
     */
    @Test
    fun fillingTheCachePastItsBudgetWithOtherContentEvictsNoneOfTheDownload() {
        val content = TestContent.hls(SEGMENTS)
        val cache = openCache(maxBytes = 1)
        val downloads = openStore(cache, harness.downloadEnvironment(content))
        downloadToCompletion(downloads, content)
        assertThat(cache.isPinned(CONTENT_ID)).isTrue()

        val other = harness.buildPlayer(content = content, cache = cache)
        other.setMediaRequest(request(OTHER_CONTENT_ID, content))
        playToEnd(other)

        val otherAgain = harness.buildPlayer(content = content, cache = cache)
        otherAgain.setMediaRequest(request(OTHER_CONTENT_ID, content))
        playToEnd(otherAgain)
        assertWithMessage("unpinned content under a one-byte budget").that(segmentsFetched(otherAgain)).isGreaterThan(0)

        val downloaded = harness.buildPlayer(content = content, cache = cache)
        downloaded.setMediaRequest(request(CONTENT_ID, content))
        playToEnd(downloaded)
        assertThat(harness.networkRequests(downloaded)).isEmpty()
    }

    @Test
    fun removingADownloadUnpinsItAndGivesItsBytesBack() {
        val content = TestContent.hls(SEGMENTS)
        val cache = openCache()
        val downloads = openStore(cache, harness.downloadEnvironment(content))
        downloadToCompletion(downloads, content)
        val removed = mutableListOf<String>()
        downloads.addListener(Recorder(onRemoved = { removed += it }))

        downloads.remove(CONTENT_ID)
        harness.advanceUntil(environmentOf(downloads), "the download was removed") { CONTENT_ID in removed }

        assertThat(downloads.download(CONTENT_ID)).isNull()
        assertThat(downloads.downloads()).isEmpty()
        assertThat(cache.isPinned(CONTENT_ID)).isFalse()
        // Every segment has to be fetched again: the removal deleted what the download stored rather than
        // leaving it for eviction to find.
        val afterwards = harness.buildPlayer(content = content, cache = cache)
        afterwards.setMediaRequest(request(CONTENT_ID, content))
        playToEnd(afterwards)
        assertThat(segmentsFetched(afterwards)).isEqualTo(SEGMENTS)
    }

    /**
     * ADR-0013 rule 5, on ADR-0010 rule 2: the download's media, its progress and its pin are in the
     * directory the consumer named, and nothing lands in the application's own storage — no database in
     * its database directory, which is where Media3's standalone provider would have put the index, and no
     * file anywhere else under its data directory.
     */
    @Test
    fun everythingADownloadWritesLandsInsideTheConsumersDirectory() {
        val content = TestContent.dash(SEGMENTS)
        val applicationStorage = context.filesDir.parentFile!!
        val before = filesUnder(applicationStorage)
        val databasesBefore = context.databaseList().toSet()

        val directory = folder.newFolder()
        val cache = openCache(directory = directory)
        val downloads = openStore(cache, harness.downloadEnvironment(content))
        downloadToCompletion(downloads, content)
        // At completion, before a removal could delete a stray file again.
        assertWithMessage("at completion").that(filesUnder(applicationStorage)).isEqualTo(before)
        assertWithMessage("at completion").that(context.databaseList().toSet()).isEqualTo(databasesBefore)
        assertThat(filesUnder(directory).size).isGreaterThan(SEGMENTS)

        downloads.remove(CONTENT_ID)
        harness.advanceUntil(environmentOf(downloads), "the download was removed") { downloads.downloads().isEmpty() }
        downloads.release()

        assertWithMessage("after removal").that(filesUnder(applicationStorage)).isEqualTo(before)
        assertWithMessage("after removal").that(context.databaseList().toSet()).isEqualTo(databasesBefore)
    }

    private fun assertProgressReportedInOrder(content: TestContent) {
        val cache = openCache()
        val downloads = openStore(cache, harness.downloadEnvironment(content))
        val reports = mutableListOf<DownloadItem>()
        downloads.addListener(Recorder(onChanged = { reports += it }))

        downloads.enqueue(request(CONTENT_ID, content))
        harness.advanceUntil(environmentOf(downloads), "the download completed") { reports.lastOrNull()?.state == DownloadState.COMPLETED }

        assertThat(reports.map { it.contentId }.toSet()).containsExactly(CONTENT_ID)
        // Progress only ever moves forward, a step at a time, and the reports between the first and the last
        // show it moving rather than jumping from nothing to done.
        val percents = reports.mapNotNull { it.percentDownloaded }
        assertThat(percents).isInOrder()
        assertThat(percents.filter { it > 0f && it < 100f }).isNotEmpty()
        assertThat(reports.map { it.bytesDownloaded }).isInOrder()
        assertThat(reports.map { it.state }.distinct()).containsAtLeast(DownloadState.DOWNLOADING, DownloadState.COMPLETED).inOrder()
        assertThat(reports).containsNoDuplicates()

        val completed = reports.last()
        assertThat(completed.percentDownloaded).isEqualTo(100f)
        assertThat(completed.bytesDownloaded).isGreaterThan(0L)
        assertThat(downloads.download(CONTENT_ID)).isEqualTo(completed)
        assertThat(downloads.downloads()).containsExactly(completed)
    }

    private fun assertPlaysWithNoNetwork(content: TestContent) {
        val cache = openCache()
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(cache, environment)
        downloadToCompletion(downloads, content)
        assertThat(harness.networkRequests(environment).count { it.kind == ResourceKind.MEDIA_SEGMENT }).isAtLeast(SEGMENTS)

        val player = harness.buildPlayer(content = content, cache = cache)
        player.setMediaRequest(request(CONTENT_ID, content))
        playToEnd(player)

        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
        assertThat(harness.networkRequests(player)).isEmpty()
    }

    private fun downloadToCompletion(downloads: Downloads, content: TestContent) {
        downloads.enqueue(request(CONTENT_ID, content))
        harness.advanceUntil(environmentOf(downloads), "the download completed") {
            downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }
    }

    private val environments = mutableMapOf<Downloads, DownloadEnvironment>()

    private fun environmentOf(downloads: Downloads): DownloadEnvironment = environments.getValue(downloads)

    private fun openCache(maxBytes: Long = LARGE_BUDGET_BYTES, directory: File = folder.newFolder()): ContentKeyedCache =
        CachePolicy.contentKeyed(directory, maxBytes).also { cache -> opened += AutoCloseable { cache.release() } }

    private fun openStore(cache: ContentKeyedCache, environment: DownloadEnvironment): Downloads =
        Downloads.Builder(context, cache).setEnvironment(environment).build().also { store ->
            environments[store] = environment
            opened += AutoCloseable { store.release() }
        }

    private fun request(contentId: String, content: TestContent): MediaRequest =
        MediaRequest.Builder(contentId).addSource(content.sourceUri).build()

    private fun playToEnd(player: SuperPlayer) {
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content") { it.playbackState == Player.STATE_ENDED || it.playerError != null }
        assertWithMessage("cause: ${player.playerError?.cause}").that(player.playerError).isNull()
    }

    private fun segmentsFetched(player: Player): Int =
        harness.networkRequests(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.uri }.toSet().size

    private fun filesUnder(directory: File): Set<String> =
        directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).path }.toSet()

    private class Recorder(
        private val onChanged: (DownloadItem) -> Unit = {},
        private val onRemoved: (String) -> Unit = {},
    ) : DownloadsListener {
        override fun onDownloadChanged(item: DownloadItem) = onChanged(item)
        override fun onDownloadRemoved(contentId: String) = onRemoved(contentId)
    }

    private companion object {
        const val CONTENT_ID = "film/the-lady-vanishes"
        const val OTHER_CONTENT_ID = "film/the-thirty-nine-steps"
        const val SEGMENTS = 6

        // Far more than any synthetic stream here, so nothing is evicted unless a test says so.
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024
    }
}
