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
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.NetworkRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.SyntheticDashChoice
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * #241, ADR-0013 rule 12: a download takes what a viewer will watch rather than the whole ladder — one
 * rendition, the audio languages declared, the subtitles declared — and a player of it selects exactly
 * that, with no request at all.
 *
 * Every claim is a count of what left the download's chain, per rendition and per language, which is why
 * the content puts each rendition and each language at a path or a host of its own.
 *
 * **What is not forced here is the policy's ceiling.** The synthetic streams are audio-only, so there is no
 * *video* ladder for `DownloadSelectionPolicy` to cut, and Media3 reads a video ceiling against video tracks
 * only. Which ceiling each profile decides is `StaticProfilePolicyTest`'s, and that the ceiling reaches
 * Media3's selector is `DownloadSelectionParametersTest`'s; that the selector then honours a ceiling it was
 * handed is Media3's own claim, and not re-asserted here.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSelectionTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    @Before
    fun anUnmeteredNetwork() {
        // As DownloadsTest says: Robolectric's own network is one Media3's default requirement refuses.
        DeviceStatement.declareNetworkMetered(false)
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
    }

    /** Two HLS renditions, the higher on a host of its own: one of them is downloaded, whole, and the other not at all. */
    @Test
    fun anHlsDownloadFetchesOneRenditionAndNotTheOther() {
        val content = TestContent.hls(SEGMENTS, variantCount = 2, secondVariantHost = HIGHER_RENDITION_HOST)
        val (environment, cache) = download(content)

        val segments = harness.networkRequests(environment).filter { it.kind == ResourceKind.MEDIA_SEGMENT }
        val byRendition = segments.groupBy { hostOf(it) }.mapValues { (_, requests) -> requests.map { it.uri }.toSet().size }
        // The higher one, because a download takes the highest rendition its ceiling allows.
        assertThat(byRendition).containsExactly(HIGHER_RENDITION_HOST, SEGMENTS)

        assertPlaysWithNoNetwork(content, cache)
    }

    /**
     * Three audio languages at two renditions each and two subtitle languages; the viewer declares two of
     * the audio languages and one of the subtitles. Exactly those are downloaded, each at one rendition.
     *
     * Neither declared language is English, which is the device's own: a player that was not narrowed to
     * the download would select the English audio it prefers and fetch it, so the count of zero afterwards
     * is the narrowing's and not a coincidence of preference.
     */
    @Test
    fun declaredAudioLanguagesAndSubtitlesAreDownloadedAndUndeclaredOnesAreNot() {
        val content = TestContent.dashWithChoice(listOf("en", "fr", "de"), listOf("en", "fr"), SEGMENTS)
        val (environment, cache) = download(content, audioLanguages = listOf("fr", "de"), subtitleLanguages = listOf("fr"))

        val requests = harness.networkRequests(environment)
        assertThat(renditionsFetched(requests)).containsExactly(
            SyntheticDashChoice.audioRenditionPath("fr", SyntheticDashChoice.HIGHER_BITRATE_BPS),
            SyntheticDashChoice.audioRenditionPath("de", SyntheticDashChoice.HIGHER_BITRATE_BPS),
        )
        assertThat(segmentsUnder(requests, SyntheticDashChoice.audioRenditionPath("de", SyntheticDashChoice.HIGHER_BITRATE_BPS))).isEqualTo(SEGMENTS)
        assertThat(segmentsUnder(requests, SyntheticDashChoice.audioRenditionPath("fr", SyntheticDashChoice.HIGHER_BITRATE_BPS))).isEqualTo(SEGMENTS)
        assertThat(subtitlesFetched(requests)).containsExactly(SyntheticDashChoice.subtitleUri("fr"))

        assertPlaysWithNoNetwork(content, cache)
    }

    /**
     * ADR-0013 rule 12's addendum: a declared language the content does not carry is skipped rather than
     * refused. The download completes; with no declared audio language carried it takes the one audio a
     * player of the content would have chosen — one language, not every one — and with no declared
     * subtitle carried it takes no subtitles, not one the viewer did not name.
     */
    @Test
    fun declaredLanguagesTheContentDoesNotCarryAreSkippedAndTheDownloadStillCompletes() {
        val content = TestContent.dashWithChoice(listOf("en", "fr"), listOf("en"), SEGMENTS)
        val (environment, cache) = download(content, audioLanguages = listOf("ja"), subtitleLanguages = listOf("ja"))

        val requests = harness.networkRequests(environment)
        assertThat(renditionsFetched(requests)).hasSize(1)
        assertThat(renditionsFetched(requests).single()).endsWith("-${SyntheticDashChoice.HIGHER_BITRATE_BPS}/")
        assertThat(subtitlesFetched(requests)).isEmpty()

        assertPlaysWithNoNetwork(content, cache)
    }

    /** The control: content with one rendition and nothing to choose downloads it whole. */
    @Test
    fun contentWithASingleRenditionDownloadsItWhole() {
        val content = TestContent.dash(SEGMENTS)
        val (environment, _) = download(content, audioLanguages = listOf("en"))

        val segments = harness.networkRequests(environment).filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.uri }.toSet()
        assertThat(segments).hasSize(SEGMENTS)
        assertThat(segments.all { it.startsWith("fake://${SyntheticDashStream.HOST}/dash/") }).isTrue()
    }

    /** A manifest that cannot be parsed leaves nothing to choose from: the item fails, nothing is pinned, and removing it forgets it. */
    @Test
    fun aDownloadWhoseManifestCannotBeReadFailsAndIsForgottenOnRemoval() {
        val content = TestContent.hls(SEGMENTS)
        val cache = openCache()
        val environment = harness.downloadEnvironment(
            content,
            // An empty body rather than a status: Media3 does not ask again for a manifest it could not parse,
            // while a refused one is retried on a timer this harness does not own.
            faults = FaultScript.Builder().truncateAfterBytes(0, ResourceKind.MANIFEST).build(),
        )
        val downloads = openStore(cache, environment)
        val removed = mutableListOf<String>()
        downloads.addListener(
            object : DownloadsListener {
                override fun onDownloadChanged(item: DownloadItem) = Unit
                override fun onDownloadRemoved(contentId: String) {
                    removed += contentId
                }
            },
        )

        downloads.enqueue(request(content))
        assertThat(downloads.download(CONTENT_ID)?.state).isEqualTo(DownloadState.QUEUED)
        harness.advanceUntil(environment, "the download failed") {
            // Media3's download helper looks for a failed preparation on a timer of its own thread, every
            // 100ms of the system clock, which Robolectric moves only when told to.
            ShadowSystemClock.advanceBy(Duration.ofMillis(HELPER_FAILURE_POLL_MS))
            downloads.download(CONTENT_ID)?.state == DownloadState.FAILED
        }

        assertThat(harness.networkRequests(environment).none { it.kind == ResourceKind.MEDIA_SEGMENT }).isTrue()
        assertThat(cache.isPinned(CONTENT_ID)).isFalse()
        assertThat(downloads.downloads().map { it.state }).containsExactly(DownloadState.FAILED)

        downloads.remove(CONTENT_ID)
        assertThat(downloads.download(CONTENT_ID)).isNull()
        assertThat(removed).containsExactly(CONTENT_ID)
    }

    private fun download(
        content: TestContent,
        audioLanguages: List<String> = emptyList(),
        subtitleLanguages: List<String> = emptyList(),
    ): Pair<DownloadEnvironment, ContentKeyedCache> {
        val cache = openCache()
        val environment = harness.downloadEnvironment(content)
        val downloads = openStore(cache, environment)
        downloads.enqueue(request(content), audioLanguages, subtitleLanguages)
        harness.advanceUntil(environment, "the download completed") {
            downloads.download(CONTENT_ID)?.state.let { it == DownloadState.COMPLETED || it == DownloadState.FAILED }
        }
        assertThat(downloads.download(CONTENT_ID)?.state).isEqualTo(DownloadState.COMPLETED)
        return environment to cache
    }

    private fun assertPlaysWithNoNetwork(content: TestContent, cache: ContentKeyedCache) {
        val player = harness.buildPlayer(content = content, cache = cache)
        player.setMediaRequest(request(content))
        playToEnd(player)
        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
        assertThat(harness.networkRequests(player)).isEmpty()
    }

    /** The audio rendition paths any request was made under. */
    private fun renditionsFetched(requests: List<NetworkRequest>): Set<String> =
        requests.mapNotNull { AUDIO_RENDITION.find(it.uri)?.value }.toSet()

    private fun segmentsUnder(requests: List<NetworkRequest>, renditionPath: String): Int = requests
        .filter { it.kind == ResourceKind.MEDIA_SEGMENT && renditionPath in it.uri }
        .map { it.uri }.toSet().size

    private fun subtitlesFetched(requests: List<NetworkRequest>): Set<String> =
        requests.map { it.uri }.filter { it.endsWith(".vtt") }.toSet()

    private fun hostOf(request: NetworkRequest): String = request.uri.substringAfter("://").substringBefore('/')

    private fun openCache(): ContentKeyedCache =
        CachePolicy.contentKeyed(folder.newFolder(), LARGE_BUDGET_BYTES).also { cache -> opened += AutoCloseable { cache.release() } }

    private fun openStore(cache: ContentKeyedCache, environment: DownloadEnvironment): Downloads =
        Downloads.Builder(context, cache).setEnvironment(environment).build().also { store -> opened += AutoCloseable { store.release() } }

    private fun request(content: TestContent): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    private fun playToEnd(player: SuperPlayer) {
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content") { it.playbackState == Player.STATE_ENDED || it.playerError != null }
        assertWithMessage("cause: ${player.playerError?.cause}").that(player.playerError).isNull()
    }

    private companion object {
        const val CONTENT_ID = "film/the-lady-vanishes"
        const val SEGMENTS = 4
        const val HELPER_FAILURE_POLL_MS = 100L
        const val HIGHER_RENDITION_HOST = "rendition-high.test"
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024
        val AUDIO_RENDITION = Regex("audio-[^/]+/")

        init {
            // The single-host form of the stream serves both renditions' segments under one name, which is why the test gives the higher one a host.
            check(SyntheticHlsStream.HOST != HIGHER_RENDITION_HOST)
        }
    }
}
