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

package com.superplayer.resilience

import android.net.Uri
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.cache.CachePolicy
import com.superplayer.cache.ContentKeyedCache
import com.superplayer.core.BufferPolicy
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EnginePolicyExtension
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.NetworkRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Rungs 2 and 3 through a player: the next CDN location, and the failing rendition taken out of the
 * ladder (ADR-0011 rule 7, issue #180).
 *
 * `PRD.md` Part 5's rule is that every rung has a test that *forces* that rung, and the forcing is
 * what the shape of each test here is about. A rung above 1 is only ever reached when rung 1 has
 * spent its budget, so every player below is built with a budget of one retry and a fault that never
 * relents at the address it names; what is asserted is the traffic — `networkRequests` is what left
 * the chain, host and all — and the position, because every other reading of a fallback is an
 * inference.
 *
 * **Why the two rungs use different protocols, and why that is not an accident.** Rung 2 is DASH's:
 * more than one `BaseURL` is how the DASH specification says the same content sits at two locations
 * (ISO/IEC 23009-1 §5.6.4), and Media3 gives an HLS chunk source no location dimension at all — it
 * reports one location always, so an HLS failure escalates *through* rung 2 to rung 3, which is the
 * order rather than a gap. Rung 3 is HLS's for the mirror-image reason: one HLS stream carries its
 * renditions as separate playlists with URLs of their own, which is what lets a fault be addressed at
 * one of them (`TestContent.hls(secondVariantHost = …)`).
 *
 * Nothing reaches past the facade: every player is built the way a consumer builds one, with
 * `Resilience.standard()` in the builder.
 */
@RunWith(AndroidJUnit4::class)
class FallbackPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val caches = mutableListOf<ContentKeyedCache>()

    @After
    fun releaseCaches() {
        caches.forEach { it.release() }
    }

    @Test
    fun aLocationThatFailsEveryAttemptIsLeftForTheOneTheManifestAlsoNames() {
        val content = dashAcrossTwoLocations()
        val player = harness.buildPlayer(
            content = content,
            faults = failEveryAttemptAt(SyntheticDashStream.HOST, index = FAULTED_SEGMENT),
            policy = retryPolicyOf(segment = ONE_RETRY),
            resilience = Resilience.standard(),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        // Rung 1 first: the origin was asked for the faulted segment once and then once more, which
        // is the whole of the budget, and no more than that — a rung 2 reached early would show as
        // fewer.
        assertWithMessage("attempts at the faulted segment on the origin")
            .that(segments(player).count { it.host == SyntheticDashStream.HOST && it.uri.endsWith(faultedSegmentName()) })
            .isEqualTo(ATTEMPTS_ALLOWED)
        // Then rung 2: the mirror served the segment the origin would not, and went on serving.
        assertThat(segments(player).filter { it.host == MIRROR_HOST }.map { it.uri }.distinct().size)
            .isGreaterThan(1)
        // And the session got past the fault rather than stopping on the recovery.
        assertThat(segments(player).map { it.uri }.distinct().size).isGreaterThan(FAULTED_SEGMENT + 1)
    }

    @Test
    fun theLocationFallbackResumesWherePlaybackHadReached() {
        // ADR-0011 rule 9 for a rung Media3 performs inside a load: the position is untouched by
        // construction and the rule is that nothing SuperPlayer adds re-seeks. Sampled across the
        // whole span rather than at its ends, because a re-prepare that restarted at zero and caught
        // up again would be invisible to a before-and-after pair.
        val content = dashAcrossTwoLocations()
        val player = harness.buildPlayer(
            content = content,
            faults = failEveryAttemptAt(SyntheticDashStream.HOST, index = FAULTED_SEGMENT),
            policy = retryPolicyOf(segment = ONE_RETRY),
            resilience = Resilience.standard(),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)

        assertPositionNeverGoesBackwards(player)
        assertThat(player.playerError).isNull()
        assertThat(segments(player).any { it.host == MIRROR_HOST }).isTrue()
    }

    @Test
    fun aLocationFallbackDoesNotChangeTheCacheKey() {
        // The content is the same content at both locations, so the entries the mirror filled are
        // the entries the origin's URLs read back: `ContentKeys` keys on the content id and the URI
        // *path* and never on the host (ADR-0010, and `ContentKeysTest` pins the rule itself). Here
        // it is pinned as the thing that makes rung 2 free rather than a second download — a
        // failover that changed the key would turn every later play into a cold one.
        val cache = openCache()
        val content = dashAcrossTwoLocations()

        val failedOver = harness.buildPlayer(
            content = content,
            faults = failEveryAttemptAt(SyntheticDashStream.HOST, index = FAULTED_SEGMENT),
            policy = retryPolicyOf(segment = ONE_RETRY),
            resilience = Resilience.standard(),
            cache = cache,
        )
        failedOver.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(failedOver)
        harness.advanceTimeInStepsMs(failedOver, PLAYED_MS)
        assertThat(failedOver.playerError).isNull()
        // The session really did read from both locations, or the replay below would prove nothing.
        assertThat(segments(failedOver).map { it.host }.toSet())
            .containsExactly(SyntheticDashStream.HOST, MIRROR_HOST)

        // A healthy origin now, so every segment resolves to the location the mirror stood in for.
        val replay = harness.buildPlayer(content = content, cache = cache, resilience = Resilience.standard())
        replay.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(replay)
        harness.advanceTimeInStepsMs(replay, PLAYED_MS)

        assertThat(replay.playerError).isNull()
        assertWithMessage("segments re-fetched on the warm replay").that(segments(replay)).isEmpty()
    }

    @Test
    fun aRenditionThatKeepsFailingIsExcludedAndPlaybackGoesOnAtAnotherBitrate() {
        // Rung 3. The stream's two renditions carry identical bytes and differ in what they declare,
        // so the higher one sits on a host of its own and the fault is addressed there; a rendition
        // fetched from somewhere is the only rendition a fault can name. Media3 offers an HLS chunk
        // source no location to move to, so rung 2 declines and this is rung 3 acting.
        val content = TestContent.hls(SEGMENTS, variantCount = 2, secondVariantHost = HIGHER_RENDITION_HOST)
        val player = harness.buildPlayer(
            content = content,
            faults = failEveryAttemptAt(HIGHER_RENDITION_HOST),
            policy = AdaptiveAudioPolicy(retryPolicyOf(segment = ONE_RETRY)),
            resilience = Resilience.standard(),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        // The higher rendition was tried, rung 1 spent its budget on it, and then it was gone: the
        // exclusion is what stops the count there, and its duration is what keeps it stopped —
        // `ExcludeVariant.TRACK_EXCLUSION_MS` is a minute, tens of evaluations, so a rendition
        // blacklisted here does not reappear at the next one.
        assertWithMessage("asks at the higher rendition").that(segments(player).count { it.host == HIGHER_RENDITION_HOST })
            .isEqualTo(ATTEMPTS_ALLOWED)
        // And playback went on at the other bitrate: the lower rendition is the one on the stream's
        // own host, and it served the session from the exclusion onward.
        assertThat(segments(player).filter { it.host == SyntheticHlsStream.HOST }.map { it.uri }.distinct().size)
            .isGreaterThan(1)
    }

    @Test
    fun theRenditionExclusionResumesWherePlaybackHadReached() {
        // Rule 9 for rung 3, the same way and for the same reason as the location fallback above.
        val content = TestContent.hls(SEGMENTS, variantCount = 2, secondVariantHost = HIGHER_RENDITION_HOST)
        val player = harness.buildPlayer(
            content = content,
            faults = failEveryAttemptAt(HIGHER_RENDITION_HOST),
            policy = AdaptiveAudioPolicy(retryPolicyOf(segment = ONE_RETRY)),
            resilience = Resilience.standard(),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)

        assertPositionNeverGoesBackwards(player)
        assertThat(player.playerError).isNull()
    }

    @Test
    fun aPlayerWithoutResilienceIsLeftToMedia3SOwnFallbackTable() {
        // ADR-0011 rule 14 for these two rungs, counted as behaviour because that is the only
        // reading of the slot available from outside the facade. Media3's own policy offers a
        // fallback for a fixed set of statuses and a bad gateway is not among them, so the stock
        // player spends its retries at the origin and ends the session there; the same script on a
        // player with resilience moves location and plays on, which is the test above. A player
        // that had silently kept Media3's table, or silently lost SuperPlayer's, would collapse the
        // two — which is also what makes every other test in this file a test of the ladder rather
        // than of what Media3 would have done anyway.
        val content = dashAcrossTwoLocations()
        val script = failEveryAttemptAt(SyntheticDashStream.HOST, index = FAULTED_SEGMENT)

        val stock = harness.buildPlayer(content = content, faults = script)
        stock.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToFailure(stock)
        harness.advanceTimeInStepsMs(stock, PLAYED_MS)

        assertThat(stock.playerError).isNotNull()
        assertWithMessage("locations the stock player used").that(segments(stock).map { it.host }.toSet())
            .containsExactly(SyntheticDashStream.HOST)
    }

    /**
     * The same media at two locations, the way DASH says it: the MPD names both and the manifest
     * itself stays on the origin, since a document naming its own alternatives from a location that
     * could not be fetched would describe nothing.
     */
    private fun dashAcrossTwoLocations(): TestContent = TestContent.dash(SEGMENTS, mirrorHost = MIRROR_HOST)

    /**
     * A 502 at [host] that never relents, on media segments only.
     *
     * Media segments only, because the manifest and the initialization segment are what a session
     * needs before there is any playback to rescue.
     *
     * **502 rather than 500, and that is the whole of what makes these tests about SuperPlayer.**
     * Media3's own fallback table is a fixed set of statuses — 403, 404, 410, 416, 500 and 503
     * (// ref: `DefaultLoadErrorHandlingPolicy.isEligibleForFallback`) — so a fallback forced with
     * one of those would have happened on a stock player too and would prove nothing about the
     * ladder. A bad gateway (// ref: RFC 9110 §15.6.3) is not on that list and is an edge being
     * unwell in exactly the way a fallback is for, which is the disagreement ADR-0011 rule 7's
     * "driven by the class rather than by Media3's defaults" is about.
     */
    private fun failEveryAttemptAt(host: String, index: Int? = null): FaultScript = FaultScript.Builder()
        .failWithHttpStatus(BAD_GATEWAY, ResourceKind.MEDIA_SEGMENT, index, host = host)
        .build()

    private fun segments(player: SuperPlayer): List<NetworkRequest> =
        harness.networkRequests(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }

    /**
     * Which CDN served a request, which is what a fallback moves and therefore the whole of what
     * these tests read. [NetworkRequest] carries the URL it went to and nothing addresses a host, so
     * it is parsed back out here rather than being a field the harness would have to keep.
     */
    private val NetworkRequest.host: String?
        get() = Uri.parse(uri).host

    private fun faultedSegmentName(): String = "segment$FAULTED_SEGMENT.m4s"

    private fun assertPositionNeverGoesBackwards(player: SuperPlayer) {
        var previousMs = player.currentPosition
        var advanced = 0L
        while (advanced < PLAYED_MS) {
            harness.advanceTimeInStepsMs(player, SAMPLE_MS)
            advanced += SAMPLE_MS
            assertThat(player.currentPosition).isAtLeast(previousMs)
            previousMs = player.currentPosition
        }
        assertThat(previousMs).isGreaterThan(0L)
    }

    private fun openCache(): ContentKeyedCache =
        CachePolicy.contentKeyed(folder.newFolder(), CACHE_BYTES).also { caches += it }

    /**
     * A policy that decides the profile's numbers for everything but the retry half.
     *
     * Hand-written for the reason `RetryPlaybackTest`'s is: the point of each test is a specific
     * budget, and the profile rows are argued in `StaticProfilePolicy` rather than here.
     */
    private fun retryPolicyOf(segment: RetryBudget): PlaybackPolicy = object : PlaybackPolicy {
        override fun decide(conditions: PlaybackConditions): PlaybackDecision = PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 10_000,
                maxBufferMs = 20_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
            retry = RetryPolicy(segment = segment),
        )
    }

    /**
     * [decided], plus the one engine setting rung 3 cannot be forced without.
     *
     * Excluding a rendition is `ExoTrackSelection.excludeTrack`, which only an *adaptive* selection
     * has anything to exclude from; the synthetic stream's two renditions declare a codec and a
     * bitrate and nothing else, and Media3's selector will not adapt between audio tracks whose
     * channel count and sample rate it cannot compare unless it is told that it may. Told, the
     * selection over them is a real `AdaptiveTrackSelection` and rung 3 acts on the ladder a
     * consumer's video player would have without being asked. Reached through core's engine seam,
     * which this module is a Kotlin friend of already (ADR-0011 rule 13) — the same seam and the
     * same recipe `superplayer-abr`'s `BandwidthOraclePlaybackTest` uses for its own two-rendition
     * stream.
     */
    private class AdaptiveAudioPolicy(private val decided: PlaybackPolicy) :
        PlaybackPolicy by decided,
        EnginePolicyExtension {

        override fun configureEngine(configuration: EngineConfiguration) {
            configuration.engine.setTrackSelector(
                DefaultTrackSelector(ApplicationProvider.getApplicationContext()).apply {
                    parameters = buildUponParameters()
                        .setAllowAudioMixedChannelCountAdaptiveness(true)
                        .setAllowAudioMixedSampleRateAdaptiveness(true)
                        .build()
                },
            )
        }
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e02"

        /** // ref: RFC 9110 §15.6.3, Bad Gateway. See [failEveryAttemptAt] for why this one. */
        const val BAD_GATEWAY = 502

        /** Enough segments that playback runs on well past the fault. */
        const val SEGMENTS = 8

        /**
         * Late enough that segments play before the fault, so a fallback taken at the very first
         * request would be visible as a position that never moved, and inside [SEGMENTS].
         */
        const val FAULTED_SEGMENT = 2

        /** The first ask and the one retry the budget allows, and then the rung above. */
        const val ATTEMPTS_ALLOWED = 2

        val ONE_RETRY = RetryBudget(maxRetries = 1, initialBackoffMs = 50, maxBackoffMs = 200)

        /** The second location the DASH manifest names, and the only other host its media is at. */
        const val MIRROR_HOST = "mirror.superplayer.test"

        /** Where the HLS stream's higher rendition is served from, so a fault can address it. */
        const val HIGHER_RENDITION_HOST = "higher.superplayer.test"

        /** Far enough past the fault that every retry a budget allows has been made. */
        const val PLAYED_MS = 20_000L

        /** One advance of [assertPositionNeverGoesBackwards]'s sampling loop. */
        const val SAMPLE_MS = 1_000L

        /** Far larger than this stream, so nothing is evicted and a miss is a miss. */
        const val CACHE_BYTES = 8L * 1024 * 1024
    }
}
