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
import com.superplayer.core.ThroughputEstimate
import com.superplayer.core.TrackSelectionPolicy
import org.junit.Test

/**
 * The selection half's rules, each asserted by constructing conditions by hand — no player, no
 * clock, no Robolectric — as `AdaptiveBufferPolicyTest` does for the buffer half.
 */
class AdaptiveSelectionPolicyTest {

    // Rule 1: with nothing observed, every profile answers as its static profile does, buffer
    // half included — so composing this with the buffer policy changes nothing at a cold start.
    @Test
    fun withNothingObservedEveryProfileAnswersAsItsStaticProfileDoes() {
        for (profile in PlaybackProfile.entries) {
            val static = PlaybackPolicy.forProfile(profile).decide(PlaybackConditions())
            assertThat(AdaptiveSelectionPolicy(profile).decide(PlaybackConditions())).isEqualTo(static.copy(trackSelection = static.trackSelection.paced(profile)))
        }
    }

    // Rule 2: the cellular cap, and that it is the F1 loss on the profile that saves data.
    @Test
    fun aCellularTransportCapsEveryProfileBelowItsWifiCeiling() {
        for (profile in PlaybackProfile.entries) {
            val onWifi = AdaptiveSelectionPolicy(profile).decide(PlaybackConditions(transport = NetworkTransport.Wifi))
            val onLte = AdaptiveSelectionPolicy(profile).decide(PlaybackConditions(transport = NetworkTransport.Cellular(CellularGeneration.LTE)))
            val static = PlaybackPolicy.forProfile(profile).decide(PlaybackConditions()).trackSelection

            assertWithMessage("$profile on WiFi").that(onWifi.trackSelection).isEqualTo(static.paced(profile))
            assertWithMessage("$profile on LTE").that(onLte.trackSelection.maxVideoBitrateBps).isLessThan(onWifi.trackSelection.maxVideoBitrateBps)
            assertWithMessage("$profile on LTE").that(onLte.trackSelection.maxVideoHeightPx).isAtMost(onWifi.trackSelection.maxVideoHeightPx)
        }
    }

    @Test
    fun theTransportCapNeverRaisesTheProfilesOwnCeiling() {
        val dataSaver = PlaybackPolicy.forProfile(PlaybackProfile.DATA_SAVER).decide(PlaybackConditions()).trackSelection
        for (transport in listOf(NetworkTransport.Wifi, NetworkTransport.Ethernet, NetworkTransport.Unknown, NetworkTransport.Cellular(CellularGeneration.NR))) {
            val decided = AdaptiveSelectionPolicy(PlaybackProfile.DATA_SAVER).decide(PlaybackConditions(transport = transport)).trackSelection
            assertWithMessage("$transport").that(decided).isEqualTo(dataSaver.paced(PlaybackProfile.DATA_SAVER))
        }
        // Older cellular is the tightest entry, and it lands under the data saver's own 480p.
        val older = AdaptiveSelectionPolicy(PlaybackProfile.DATA_SAVER)
            .decide(PlaybackConditions(transport = NetworkTransport.Cellular(CellularGeneration.OLDER))).trackSelection
        assertThat(older.maxVideoHeightPx).isLessThan(dataSaver.maxVideoHeightPx)
        assertThat(older.maxVideoBitrateBps).isLessThan(dataSaver.maxVideoBitrateBps)
    }

    @Test
    fun aGenerationThePlatformDidNotNameIsCappedAsLte() {
        for (profile in PlaybackProfile.entries) {
            val unnamed = AdaptiveSelectionPolicy(profile).decide(PlaybackConditions(transport = NetworkTransport.Cellular(generation = null)))
            val lte = AdaptiveSelectionPolicy(profile).decide(PlaybackConditions(transport = NetworkTransport.Cellular(CellularGeneration.LTE)))
            assertWithMessage("$profile").that(unnamed.trackSelection).isEqualTo(lte.trackSelection)
        }
    }

    // Rule 3, moved here from the buffer policy: the hold, and its lapse on the shared cooldown.
    @Test
    fun aRebufferHoldsTheCeilingAtTheConservativePercentileForTheCooldown() {
        val measured = estimate(meanBps = 4_000_000, spreadBps = 200_000, conservativeBps = 3_000_000)
        val justEnded = conditions(measured).copy(stallHistory = rebuffered(count = 1, msAgo = 1_000))

        assertThat(vod().decide(justEnded).trackSelection.maxVideoBitrateBps).isEqualTo(3_000_000)
        assertThat(vod().decide(justEnded).trackSelection.maxVideoHeightPx).isEqualTo(STATIC_VOD.trackSelection.maxVideoHeightPx)

        // Once the cooldown has lapsed it goes back: the history is still there, the hold is not.
        val lapsed = justEnded.copy(stallHistory = rebuffered(count = 1, msAgo = AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS))
        assertThat(vod().decide(lapsed).trackSelection).isEqualTo(STATIC_VOD.trackSelection.paced(PlaybackProfile.VIDEO_ON_DEMAND))
    }

    @Test
    fun theHeldCeilingIsTheMeanWhereThereIsNoPercentileAndNeverAboveTheCeilingInForce() {
        val noPercentile = conditions(estimate(meanBps = 2_000_000, spreadBps = 100_000, conservativeBps = null))
            .copy(stallHistory = rebuffered(count = 1, msAgo = 0))
        assertThat(vod().decide(noPercentile).trackSelection.maxVideoBitrateBps).isEqualTo(2_000_000)

        // Under the profile's own cap on a fast link…
        val fastNetwork = conditions(estimate(meanBps = 20_000_000, spreadBps = 100_000, conservativeBps = 18_000_000))
            .copy(stallHistory = rebuffered(count = 1, msAgo = 0))
        assertThat(AdaptiveSelectionPolicy(PlaybackProfile.DATA_SAVER).decide(fastNetwork).trackSelection)
            .isEqualTo(PlaybackPolicy.forProfile(PlaybackProfile.DATA_SAVER).decide(PlaybackConditions()).trackSelection.paced(PlaybackProfile.DATA_SAVER))
        // …and under the transport's cap where that is the tighter one.
        val fastLte = fastNetwork.copy(transport = NetworkTransport.Cellular(CellularGeneration.LTE))
        assertThat(vod().decide(fastLte).trackSelection).isEqualTo(TransportCaps.RUNG_1080P.paced(PlaybackProfile.VIDEO_ON_DEMAND))

        // Nothing measured, or a seeded estimate only: nothing to hold at.
        val unmeasured = PlaybackConditions(stallHistory = rebuffered(count = 1, msAgo = 0))
        assertThat(vod().decide(unmeasured).trackSelection).isEqualTo(STATIC_VOD.trackSelection.paced(PlaybackProfile.VIDEO_ON_DEMAND))
        val seeded = conditions(estimate(meanBps = 2_000_000, spreadBps = 0, sampleCount = 0)).copy(stallHistory = rebuffered(count = 1, msAgo = 0))
        assertThat(vod().decide(seeded).trackSelection).isEqualTo(STATIC_VOD.trackSelection.paced(PlaybackProfile.VIDEO_ON_DEMAND))
        // A meter reading zero holds nothing: there is no rung under it.
        val dead = conditions(estimate(meanBps = 0, spreadBps = 0, conservativeBps = 0)).copy(stallHistory = rebuffered(count = 1, msAgo = 0))
        assertThat(vod().decide(dead).trackSelection).isEqualTo(STATIC_VOD.trackSelection.paced(PlaybackProfile.VIDEO_ON_DEMAND))
    }

    // The buffer half is never this policy's to move.
    @Test
    fun theBufferHalfIsAlwaysTheStaticProfiles() {
        val busy = conditions(estimate(meanBps = 20_000_000, spreadBps = 100_000, conservativeBps = 18_000_000))
            .copy(stallHistory = rebuffered(count = 3, msAgo = 0), heapBudgetBytes = 64L * 1_024 * 1_024, playbackSpeed = 2f)
        assertThat(vod().decide(busy).buffer).isEqualTo(STATIC_VOD.buffer)
        assertThat(vod().decide(busy).liveLatency).isNull()
    }

    private fun vod() = AdaptiveSelectionPolicy(PlaybackProfile.VIDEO_ON_DEMAND)

    /** A ceiling at the pace this policy decides for [profile], which rule 4 adds to every ceiling. */
    private fun TrackSelectionPolicy.paced(profile: PlaybackProfile): TrackSelectionPolicy = copy(pace = SelectionPaces.forProfile(profile))

    private fun conditions(throughput: ThroughputEstimate?): PlaybackConditions = PlaybackConditions(
        transport = NetworkTransport.Wifi,
        throughput = throughput,
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

    private companion object {
        val STATIC_VOD: PlaybackDecision = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND).decide(PlaybackConditions())
    }
}
