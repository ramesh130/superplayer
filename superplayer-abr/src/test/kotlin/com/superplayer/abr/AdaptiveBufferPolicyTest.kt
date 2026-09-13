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

package com.superplayer.abr

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.CellularGeneration
import com.superplayer.core.NetworkTransport
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.StallHistory
import com.superplayer.core.StreamType
import com.superplayer.core.ThroughputEstimate
import org.junit.Test

/**
 * `PRD.md` §3.1's five branches, each asserted by constructing conditions by hand — no player, no
 * clock, no Robolectric. "Policy must be testable without a device; if it is not, the design is
 * wrong" (`PRD.md` Part 5).
 */
class AdaptiveBufferPolicyTest {

    // With nothing observed — or only a seeded estimate — the answer is the static profile's, for
    // every profile: the adaptive policy's cold start is exactly the one a consumer had before.
    @Test
    fun withNothingObservedEveryProfileAnswersAsItsStaticProfileDoes() {
        for (profile in PlaybackProfile.entries) {
            val static = PlaybackPolicy.forProfile(profile).decide(PlaybackConditions())
            assertThat(AdaptiveBufferPolicy(profile).decide(PlaybackConditions())).isEqualTo(static)
            val seeded = PlaybackConditions(
                transport = NetworkTransport.Wifi,
                throughput = estimate(meanBps = 20_000_000, spreadBps = 0, sampleCount = 0),
                heapBudgetBytes = LARGE_HEAP,
            )
            assertThat(AdaptiveBufferPolicy(profile).decide(seeded)).isEqualTo(static)
        }
    }

    // Branch 1.
    @Test
    fun aStableHighThroughputWifiNetworkGetsADeeperCushionThanTheStaticProfile() {
        val decision = vod().decide(stableFastWifi())

        assertThat(decision.buffer.maxBufferMs).isGreaterThan(STATIC_VOD.buffer.maxBufferMs)
        assertThat(decision.buffer.minBufferMs).isEqualTo(AdaptiveBufferPolicy.DEEP_CUSHION_MS.toInt())
        // The range above the cushion is as wide as the profile's own.
        assertThat(decision.buffer.maxBufferMs - decision.buffer.minBufferMs)
            .isEqualTo(STATIC_VOD.buffer.maxBufferMs - STATIC_VOD.buffer.minBufferMs)
        // Stable: the floors are the profile's.
        assertThat(decision.buffer.bufferForPlaybackMs).isEqualTo(STATIC_VOD.buffer.bufferForPlaybackMs)
    }

    @Test
    fun theDeepCushionNeedsAnUnmeteredTransportAStableSpreadAHighMeanAndAnOnDemandProfile() {
        val fast = stableFastWifi()
        val notDeepened = listOf(
            fast.copy(transport = NetworkTransport.Cellular(CellularGeneration.NR)),
            fast.copy(transport = NetworkTransport.Unknown),
            fast.copy(transport = null),
            fast.copy(throughput = estimate(meanBps = 20_000_000, spreadBps = 8_000_000)),
            fast.copy(throughput = estimate(meanBps = 20_000_000, spreadBps = null)),
            fast.copy(throughput = estimate(meanBps = 5_000_000, spreadBps = 100_000)),
        )
        for (conditions in notDeepened) {
            assertThat(vod().decide(conditions).buffer.maxBufferMs).isEqualTo(STATIC_VOD.buffer.maxBufferMs)
        }
        for (profile in listOf(PlaybackProfile.SHORT_FORM, PlaybackProfile.DATA_SAVER, PlaybackProfile.LIVE_LINEAR)) {
            val static = PlaybackPolicy.forProfile(profile).decide(PlaybackConditions())
            assertThat(AdaptiveBufferPolicy(profile).decide(fast).buffer.maxBufferMs).isEqualTo(static.buffer.maxBufferMs)
        }
        // Ethernet is the other unmetered transport.
        assertThat(vod().decide(fast.copy(transport = NetworkTransport.Ethernet)).buffer.maxBufferMs)
            .isGreaterThan(STATIC_VOD.buffer.maxBufferMs)
    }

    // Branch 2: the same mean, two spreads — different floors, the same ceiling.
    @Test
    fun twoNetworksOfEqualMeanAndUnequalSpreadGetDifferentFloorsAndTheSameCeiling() {
        val steadier = conditions(estimate(meanBps = 4_000_000, spreadBps = 1_500_000))
        val noisier = conditions(estimate(meanBps = 4_000_000, spreadBps = 3_000_000))

        val steady = vod().decide(steadier).buffer
        val noisy = vod().decide(noisier).buffer

        assertThat(steady.bufferForPlaybackMs).isGreaterThan(STATIC_VOD.buffer.bufferForPlaybackMs)
        assertThat(noisy.bufferForPlaybackMs).isGreaterThan(steady.bufferForPlaybackMs)
        assertThat(noisy.bufferForPlaybackAfterRebufferMs).isGreaterThan(steady.bufferForPlaybackAfterRebufferMs)
        assertThat(noisy.maxBufferMs).isEqualTo(steady.maxBufferMs)
        assertThat(noisy.maxBufferMs).isEqualTo(STATIC_VOD.buffer.maxBufferMs)
        assertThat(noisy.minBufferMs).isEqualTo(STATIC_VOD.buffer.minBufferMs)
    }

    @Test
    fun theVarianceFloorIsCappedAtTwiceTheProfilesAndAnUnknownSpreadRaisesNothing() {
        val wild = vod().decide(conditions(estimate(meanBps = 1_000_000, spreadBps = 5_000_000))).buffer
        assertThat(wild.bufferForPlaybackMs).isEqualTo(STATIC_VOD.buffer.bufferForPlaybackMs * 2)

        val unknown = vod().decide(conditions(estimate(meanBps = 1_000_000, spreadBps = null))).buffer
        assertThat(unknown).isEqualTo(STATIC_VOD.buffer)
    }

    // Branch 3: keyed on what the manifest said, whatever profile the consumer named.
    @Test
    fun contentObservedLiveGetsTheLatencyPriorityBufferAndASpeedRangeUnderAnyProfile() {
        val liveConditions = stableFastWifi().copy(streamType = StreamType.LIVE)
        val latencyPriority = PlaybackPolicy.forProfile(PlaybackProfile.LIVE_LINEAR).decide(PlaybackConditions()).buffer

        for (profile in PlaybackProfile.entries) {
            val decision = AdaptiveBufferPolicy(profile).decide(liveConditions)
            assertThat(decision.buffer).isEqualTo(latencyPriority)
            assertThat(decision.liveLatency).isEqualTo(AdaptiveBufferPolicy.LIVE_LATENCY)
            // The selection half stays the profile's: a data saver watching live is still saving data.
            assertThat(decision.trackSelection)
                .isEqualTo(PlaybackPolicy.forProfile(profile).decide(PlaybackConditions()).trackSelection)
        }
        assertThat(vod().decide(stableFastWifi().copy(streamType = StreamType.ON_DEMAND)).liveLatency).isNull()
        assertThat(AdaptiveBufferPolicy(PlaybackProfile.LIVE_LINEAR).decide(PlaybackConditions()).liveLatency).isNull()
    }

    // Branch 4.
    @Test
    fun aRebufferRaisesTheAfterRebufferFloorAndHoldsTheCeilingForTheCooldown() {
        val measured = estimate(meanBps = 4_000_000, spreadBps = 200_000, conservativeBps = 3_000_000)
        val justEnded = conditions(measured).copy(stallHistory = rebuffered(count = 1, msAgo = 1_000))

        val held = vod().decide(justEnded)

        assertThat(held.buffer.bufferForPlaybackAfterRebufferMs)
            .isEqualTo(STATIC_VOD.buffer.bufferForPlaybackAfterRebufferMs * 2)
        assertThat(held.buffer.bufferForPlaybackMs).isEqualTo(STATIC_VOD.buffer.bufferForPlaybackMs)
        assertThat(held.trackSelection.maxVideoBitrateBps).isEqualTo(3_000_000)

        // A second rebuffer raises it further; the range's minimum bounds it.
        val twice = vod().decide(justEnded.copy(stallHistory = rebuffered(count = 2, msAgo = 1_000)))
        assertThat(twice.buffer.bufferForPlaybackAfterRebufferMs)
            .isEqualTo(STATIC_VOD.buffer.bufferForPlaybackAfterRebufferMs * 3)
        val many = vod().decide(justEnded.copy(stallHistory = rebuffered(count = 40, msAgo = 1_000)))
        assertThat(many.buffer.bufferForPlaybackAfterRebufferMs).isEqualTo(many.buffer.minBufferMs)

        // Once the cooldown has lapsed both go back: the history is still there, the hold is not.
        val lapsed = vod().decide(justEnded.copy(stallHistory = rebuffered(count = 1, msAgo = AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS)))
        assertThat(lapsed.buffer).isEqualTo(vod().decide(conditions(measured)).buffer)
        assertThat(lapsed.trackSelection).isEqualTo(STATIC_VOD.trackSelection)
    }

    @Test
    fun theHeldCeilingIsTheMeanWhereThereIsNoPercentileAndNeverAboveTheProfilesOwnCap() {
        val noPercentile = conditions(estimate(meanBps = 2_000_000, spreadBps = 100_000, conservativeBps = null))
            .copy(stallHistory = rebuffered(count = 1, msAgo = 0))
        assertThat(vod().decide(noPercentile).trackSelection.maxVideoBitrateBps).isEqualTo(2_000_000)

        val fastNetwork = conditions(estimate(meanBps = 20_000_000, spreadBps = 100_000, conservativeBps = 18_000_000))
            .copy(stallHistory = rebuffered(count = 1, msAgo = 0))
        val dataSaver = AdaptiveBufferPolicy(PlaybackProfile.DATA_SAVER).decide(fastNetwork)
        assertThat(dataSaver.trackSelection)
            .isEqualTo(PlaybackPolicy.forProfile(PlaybackProfile.DATA_SAVER).decide(PlaybackConditions()).trackSelection)

        // Nothing measured: nothing to hold at, and the floor is still raised.
        val unmeasured = PlaybackConditions(stallHistory = rebuffered(count = 1, msAgo = 0))
        assertThat(vod().decide(unmeasured).trackSelection).isEqualTo(STATIC_VOD.trackSelection)
        assertThat(vod().decide(unmeasured).buffer.bufferForPlaybackAfterRebufferMs)
            .isEqualTo(STATIC_VOD.buffer.bufferForPlaybackAfterRebufferMs * 2)
    }

    // Branch 5, on every branch above.
    @Test
    fun aLowHeapCapsTheCeilingOnEveryBranch() {
        val heap = 64L * 1_024 * 1_024
        val branches = mapOf(
            "static" to conditions(null),
            "deep cushion" to stableFastWifi(),
            "variance" to conditions(estimate(meanBps = 4_000_000, spreadBps = 3_000_000)),
            "live" to stableFastWifi().copy(streamType = StreamType.LIVE),
            "cooldown" to conditions(estimate(meanBps = 4_000_000, spreadBps = 200_000))
                .copy(stallHistory = rebuffered(count = 1, msAgo = 0)),
            "double speed" to stableFastWifi().copy(playbackSpeed = 2f),
        )
        for ((name, conditions) in branches) {
            val unconstrained = vod().decide(conditions.copy(heapBudgetBytes = LARGE_HEAP)).buffer
            val capped = vod().decide(conditions.copy(heapBudgetBytes = heap)).buffer
            val ceiling = expectedCeilingMs(heap, conditions)
            assertWithMessage(name).that(capped.maxBufferMs).isEqualTo(minOf(unconstrained.maxBufferMs, ceiling))
            assertWithMessage(name).that(capped.maxBufferMs).isLessThan(unconstrained.maxBufferMs)
            assertWithMessage(name).that(capped.minBufferMs).isAtMost(capped.maxBufferMs)
        }
    }

    @Test
    fun theMemoryCeilingNeverGoesUnderWhatAResumeNeeds() {
        val tiny = vod().decide(conditions(null).copy(heapBudgetBytes = 1_024L)).buffer
        assertThat(tiny.maxBufferMs).isEqualTo(STATIC_VOD.buffer.bufferForPlaybackAfterRebufferMs)
        assertThat(tiny.minBufferMs).isEqualTo(tiny.maxBufferMs)
        assertThat(tiny.bufferForPlaybackMs).isAtMost(tiny.minBufferMs)
    }

    @Test
    fun theMemoryCeilingIsSizedAtTheRateTheBufferCanFillNotAtAReferenceRate() {
        val heap = 64L * 1_024 * 1_024
        // A slow measured link buffers cheaply: the same heap holds more seconds of it.
        val slow = vod().decide(conditions(estimate(meanBps = 1_000_000, spreadBps = 10_000)).copy(heapBudgetBytes = heap)).buffer
        val unmeasured = vod().decide(conditions(null).copy(heapBudgetBytes = heap)).buffer
        assertThat(slow.maxBufferMs).isGreaterThan(unmeasured.maxBufferMs)
        // A profile cap bounds the fill rate the same way a slow link does.
        val dataSaver = AdaptiveBufferPolicy(PlaybackProfile.DATA_SAVER)
            .decide(stableFastWifi().copy(heapBudgetBytes = 4L * 1_024 * 1_024)).buffer
        assertThat(dataSaver.maxBufferMs).isEqualTo(expectedCeilingMs(4L * 1_024 * 1_024, stableFastWifi(), capBps = 800_000))
    }

    @Test
    fun aPlaybackSpeedAboveRealTimeScalesEveryDuration() {
        val doubled = vod().decide(PlaybackConditions(playbackSpeed = 2f)).buffer
        assertThat(doubled.minBufferMs).isEqualTo(STATIC_VOD.buffer.minBufferMs * 2)
        assertThat(doubled.maxBufferMs).isEqualTo(STATIC_VOD.buffer.maxBufferMs * 2)
        assertThat(doubled.bufferForPlaybackMs).isEqualTo(STATIC_VOD.buffer.bufferForPlaybackMs * 2)
        assertThat(doubled.bufferForPlaybackAfterRebufferMs).isEqualTo(STATIC_VOD.buffer.bufferForPlaybackAfterRebufferMs * 2)
        // Slower than real time drains slower; nothing is shrunk for it.
        assertThat(vod().decide(PlaybackConditions(playbackSpeed = 0.5f)).buffer).isEqualTo(STATIC_VOD.buffer)
    }

    private fun vod() = AdaptiveBufferPolicy(PlaybackProfile.VIDEO_ON_DEMAND)

    private fun stableFastWifi(): PlaybackConditions =
        conditions(estimate(meanBps = 20_000_000, spreadBps = 1_000_000, conservativeBps = 18_000_000))

    private fun conditions(throughput: ThroughputEstimate?): PlaybackConditions = PlaybackConditions(
        transport = NetworkTransport.Wifi,
        throughput = throughput,
        streamType = StreamType.ON_DEMAND,
        heapBudgetBytes = LARGE_HEAP,
    )

    private fun estimate(
        meanBps: Long,
        spreadBps: Long?,
        conservativeBps: Long? = null,
        sampleCount: Int = 8,
    ): ThroughputEstimate = ThroughputEstimate(
        meanBps = meanBps,
        spreadBps = spreadBps,
        conservativeBps = conservativeBps,
        sampleCount = sampleCount,
        newestSampleAgeMs = 0,
    )

    private fun rebuffered(count: Int, msAgo: Long): StallHistory =
        StallHistory(rebufferCount = count, msSinceLastRebufferEnded = msAgo, lastRebufferDurationMs = 1_500)

    /** Branch 5's arithmetic, written out once here so the test is a second derivation of it. */
    private fun expectedCeilingMs(heap: Long, conditions: PlaybackConditions, capBps: Long? = null): Int {
        val measured = conditions.throughput?.takeIf { it.sampleCount > 0 }?.meanBps
        val rate = minOf(measured ?: AdaptiveBufferPolicy.REFERENCE_BITRATE_BPS, capBps ?: Long.MAX_VALUE)
        val ms = heap / AdaptiveBufferPolicy.HEAP_SHARE_DIVISOR * 8 * 1_000 / rate
        return (ms / AdaptiveBufferPolicy.CEILING_STEP_MS * AdaptiveBufferPolicy.CEILING_STEP_MS).toInt()
    }

    private companion object {
        const val LARGE_HEAP: Long = 2_048L * 1_024 * 1_024
        val STATIC_VOD: PlaybackDecision = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND).decide(PlaybackConditions())
    }
}
