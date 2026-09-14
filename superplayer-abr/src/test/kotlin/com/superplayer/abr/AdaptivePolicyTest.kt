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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.CellularGeneration
import com.superplayer.core.NetworkTransport
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.StallHistory
import com.superplayer.core.StreamType
import com.superplayer.core.ThroughputEstimate
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composed policy's decisions, where one half depends on the other: branch 5's memory ceiling
 * is sized at the rate the buffer fills, and that rate is bounded by the selection ceiling *in
 * force* — the transport's cap and the post-rebuffer hold — not by the profile's static one.
 */
@RunWith(AndroidJUnit4::class)
class AdaptivePolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theCellularCapLowersTheFillRateSoTheSameHeapHoldsMoreSeconds() {
        val policy = dataSaver()
        val wifi = policy.decide(fastLink(NetworkTransport.Wifi))
        val lte = policy.decide(fastLink(NetworkTransport.Cellular(CellularGeneration.LTE)))

        assertThat(lte.trackSelection.maxVideoBitrateBps).isLessThan(wifi.trackSelection.maxVideoBitrateBps)
        assertThat(wifi.buffer.maxBufferMs).isEqualTo(ceilingMs(wifi.trackSelection.maxVideoBitrateBps.toLong()))
        assertThat(lte.buffer.maxBufferMs).isEqualTo(ceilingMs(lte.trackSelection.maxVideoBitrateBps.toLong()))
        assertThat(lte.buffer.maxBufferMs).isGreaterThan(wifi.buffer.maxBufferMs)
    }

    @Test
    fun aPostRebufferHoldLowersTheFillRateTheSameWay() {
        val policy = dataSaver()
        val cellular = fastLink(NetworkTransport.Cellular(CellularGeneration.LTE))
        val held = policy.decide(
            cellular.copy(
                throughput = estimate(meanBps = 900_000, conservativeBps = 500_000),
                stallHistory = StallHistory(rebufferCount = 1, msSinceLastRebufferEnded = 0, lastRebufferDurationMs = 1_500),
            ),
        )

        assertThat(held.trackSelection.maxVideoBitrateBps).isEqualTo(500_000)
        assertThat(held.buffer.maxBufferMs).isEqualTo(ceilingMs(500_000))
        assertThat(held.buffer.maxBufferMs).isGreaterThan(policy.decide(cellular).buffer.maxBufferMs)
    }

    @Test
    fun nothingObservedIsStillTheStaticProfile() {
        val static = PlaybackPolicy.forProfile(PlaybackProfile.DATA_SAVER).decide(PlaybackConditions())
        assertThat(dataSaver().decide(PlaybackConditions())).isEqualTo(static)
    }

    private fun dataSaver(): PlaybackPolicy = AdaptivePolicy.forProfile(context, PlaybackProfile.DATA_SAVER)

    /** A link far faster than any cap, so the selection ceiling alone sets the fill rate. */
    private fun fastLink(transport: NetworkTransport): PlaybackConditions = PlaybackConditions(
        transport = transport,
        throughput = estimate(meanBps = 20_000_000, conservativeBps = 18_000_000),
        streamType = StreamType.ON_DEMAND,
        heapBudgetBytes = SMALL_HEAP,
    )

    private fun estimate(meanBps: Long, conservativeBps: Long): ThroughputEstimate = ThroughputEstimate(
        meanBps = meanBps,
        spreadBps = meanBps / 20,
        conservativeBps = conservativeBps,
        sampleCount = 8,
        newestSampleAgeMs = 0,
    )

    /** Branch 5's arithmetic at [fillRateBps], a second derivation of it. */
    private fun ceilingMs(fillRateBps: Long): Int {
        val ms = SMALL_HEAP / AdaptiveBufferPolicy.HEAP_SHARE_DIVISOR * 8 * 1_000 / fillRateBps
        return (ms / AdaptiveBufferPolicy.CEILING_STEP_MS * AdaptiveBufferPolicy.CEILING_STEP_MS).toInt()
    }

    private companion object {
        /** 4 MiB: a ceiling of 10–16 s at these rates, under `DATA_SAVER`'s 20 s and over its 5 s resume floor. */
        const val SMALL_HEAP: Long = 4L * 1_024 * 1_024
    }
}
