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
import com.superplayer.core.HttpStack
import com.superplayer.core.MediaRequest
import com.superplayer.drm.Drm
import com.superplayer.drm.OfflineLicences
import com.superplayer.drm.WidevineConfig
import com.superplayer.testkit.ChainBottom
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
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
 * ADR-0016 rule 13 for a download store (#314): `Downloads.Builder.setHttpStack` is the app's HTTP client,
 * and **both** of a store's chains travel it — the segments' ([com.superplayer.core.TransferChain.downloadChain])
 * and the offline licence exchange's ([com.superplayer.core.TransferChain.downloadLicenceChain]).
 *
 * ## The defect, stated as a count
 *
 * The rule exists to prevent one thing: *a player loading over the app's client while its downloads use the
 * platform's*. That is what the first test here states, and it states it as a count rather than as two
 * separate readings — one content id, one [HttpStack], handed to a `SuperPlayer.Builder` and to a
 * `Downloads.Builder`, with everything either of them sent arriving at the one transport. A library that
 * threaded the stack into only one of the two would leave the other's requests somewhere this count cannot
 * see, which is exactly the shape of the defect on a device: the segments on one connection pool, one
 * certificate pin and one signing interceptor, and the playback on another.
 *
 * ## Why the environment's slot is empty
 *
 * A [DownloadEnvironment]'s transport is the *network itself* replaced, and it wins over a consumer's stack
 * (ADR-0016 rule 3). So a store handed both would resolve its bottom at the slot, and a naive "the stack was
 * used" assertion would pass without the stack having carried a byte. Every test here therefore asks the
 * harness for [ChainBottom.CONSUMERS_HTTP_TRANSPORT], which leaves the slot empty and reaches the same
 * origin — the same synthetic stream, the same clock, the same counts — through an `HttpTransport` instead.
 * That is #310's treatment of the same problem for players, applied to the store's environment.
 */
@RunWith(AndroidJUnit4::class)
class DownloadHttpStackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<AutoCloseable>()

    @Before
    fun anUnmeteredNetworkAndAScheduler() {
        // Robolectric's own network is metered and unvalidated, which Media3's default requirement refuses.
        DeviceStatement.declareNetworkMetered(false)
        DeviceStatement.declareBatteryLow(false)
        harness.useScheduledWork()
    }

    @After
    fun release() {
        opened.asReversed().forEach { it.close() }
    }

    /**
     * The defect rule 13 prevents, in one place: one content id, a player and a store over the one stack,
     * and every request either made counted at that one transport.
     *
     * The player is deliberately built over **no cache**, so it really does fetch: a player reading the
     * download off disk would send nothing, and a count of what one transport carried would then be the
     * store's alone and would say nothing about the player at all.
     */
    @Test
    fun aPlayerAndItsStoreOfTheSameContentTravelTheOneStackTheAppNamed() {
        val content = TestContent.hls(SEGMENTS)
        val environment = harness.downloadEnvironment(content, bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT)
        val stack = harness.consumersHttpStack(environment)

        val downloads = openStore(environment, stack)
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the download completed") {
            downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }
        val afterTheDownload = harness.networkRequests(environment).size
        assertWithMessage("a download over the app's own client").that(afterTheDownload).isGreaterThan(0)

        // The same stack, handed to a player of the same content. Its bytes travel the store's transport
        // because the object naming that transport is the one the app holds — which is the whole of what
        // "hold one and pass it to every entry point" buys.
        val player = harness.buildPlayer(content = content, bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT, httpStack = stack)
        player.setMediaRequest(request(content))
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content") { it.playbackState == Player.STATE_ENDED || it.playerError != null }
        assertWithMessage("cause: ${player.playerError?.cause}").that(player.playerError).isNull()

        val everything = harness.networkRequests(environment)
        assertWithMessage("a player's requests arrived at the one transport as well")
            .that(everything.size).isGreaterThan(afterTheDownload)
        assertWithMessage("and the player really did fetch media over it")
            .that(everything.drop(afterTheDownload).map { it.kind })
            .contains(ResourceKind.MEDIA_SEGMENT)
    }

    /**
     * The store's **other** chain, asserted on its own rather than as a by-product of the segments: a licence
     * exchange is `downloadLicenceChain`, a separate function for ADR-0013 rules 13 and 14's reason, and it
     * gets the store's one stack for the reason the store's one header-refresh layer serves both — a licence
     * that travelled a different HTTP client than the segments it unlocks would be a new way to fail an
     * entitlement.
     *
     * Counted at the transport as a [ResourceKind.LICENCE] request, because that is the only reading that
     * distinguishes the two chains: nothing but `downloadLicenceChain` opens one.
     */
    @Test
    fun aStoresLicenceExchangeTravelsTheSameStackItsSegmentsDo() {
        val content = TestContent.protectedDash(SEGMENTS)
        val environment = harness.downloadEnvironment(content, bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT)
        val stack = harness.consumersHttpStack(environment)
        val licences = OfflineLicences.store(folder.newFolder()).also { opened += it }

        val downloads = openStore(environment, stack) {
            setDrm(Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)), licences)
        }
        downloads.enqueue(request(content))
        harness.advanceUntil(environment, "the protected download completed") {
            downloads.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }

        val requests = harness.networkRequests(environment)
        assertWithMessage("the licence exchange came out of the consumer's transport")
            .that(requests.count { it.kind == ResourceKind.LICENCE }).isEqualTo(1)
        // And the segments did too, so this is one store's transport rather than two that happen to agree.
        assertThat(requests.map { it.kind }).contains(ResourceKind.MEDIA_SEGMENT)
        assertThat(licences.contentIds()).containsExactly(CONTENT_ID)
    }

    /**
     * ADR-0016 rule 14, counted for a store as `SuperPlayerHttpStackTest` counts it for a player: a store
     * built without [Downloads.Builder.setHttpStack] asks the consumer's transport for nothing, and keeps
     * the stack that shipped before the seam.
     *
     * The counter is shown to see what it counts. With the slot empty and no stack named, the store resolves
     * `DefaultDataSource` over Media3's own `DefaultHttpDataSource` — which the synthetic stream's `fake:`
     * URIs reach and which cannot serve them — so the download fails and the consumer's transport records
     * nothing; the same store with the same stack named completes and the transport records the journey.
     * A bottom that resolved *nothing* would not tell the two apart, which is why the first half is a count
     * of zero on a transport that is present rather than an absence of an exception.
     */
    @Test
    fun aStoreBuiltWithNoStackAsksTheConsumersTransportNothing() {
        val content = TestContent.hls(SEGMENTS)

        val withoutEnvironment = harness.downloadEnvironment(content, bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT)
        val without = openStore(withoutEnvironment, stack = null)
        without.enqueue(request(content))
        harness.advanceUntil(withoutEnvironment, "the download ended one way or the other") {
            // Media3 waits between attempts at a manifest on a delayed message, and Robolectric's clock moves
            // only when a test moves it, as `DownloadRetryBudgetTest` does while it waits for a retry.
            ShadowSystemClock.advanceBy(Duration.ofMillis(RETRY_CLOCK_STEP_MS))
            without.download(CONTENT_ID)?.state in ENDED_STATES
        }
        assertWithMessage("a store told about no stack").that(harness.networkRequests(withoutEnvironment)).isEmpty()
        assertThat(without.download(CONTENT_ID)?.state).isEqualTo(DownloadState.FAILED)

        val withEnvironment = harness.downloadEnvironment(content, bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT)
        val with = openStore(withEnvironment, harness.consumersHttpStack(withEnvironment))
        with.enqueue(request(content))
        harness.advanceUntil(withEnvironment, "the download completed") {
            with.download(CONTENT_ID)?.state == DownloadState.COMPLETED
        }
        assertWithMessage("and the same store with one").that(harness.networkRequests(withEnvironment)).isNotEmpty()
    }

    private fun openStore(
        environment: DownloadEnvironment,
        stack: HttpStack?,
        alsoConfigure: Downloads.Builder.() -> Unit = {},
    ): Downloads {
        val cache: ContentKeyedCache = CachePolicy.contentKeyed(folder.newFolder(), LARGE_BUDGET_BYTES)
            .also { opened += AutoCloseable { it.release() } }
        return Downloads.Builder(context, cache)
            .setEnvironment(environment)
            .apply { stack?.let { setHttpStack(it) } }
            .apply(alsoConfigure)
            .build()
            .also { store -> opened += AutoCloseable { store.release() } }
    }

    private fun request(content: TestContent): MediaRequest =
        MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    private companion object {
        const val CONTENT_ID = "film/the-lady-vanishes"
        const val SEGMENTS = 3

        /** Far more than any synthetic stream here, so nothing is evicted. */
        const val LARGE_BUDGET_BYTES = 64L * 1024 * 1024

        /** How far a pass moves Robolectric's clock, so a wait Media3 posts as a delayed message comes due. */
        const val RETRY_CLOCK_STEP_MS = 1_000L

        /** What a download that is going nowhere else has reached, so a wait for one can end. */
        val ENDED_STATES = setOf(DownloadState.COMPLETED, DownloadState.FAILED)
    }
}
