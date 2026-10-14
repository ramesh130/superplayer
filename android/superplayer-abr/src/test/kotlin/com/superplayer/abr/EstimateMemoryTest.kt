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
import com.superplayer.core.CellularGeneration
import com.superplayer.core.NetworkTransport
import org.junit.Test

/** ADR-0009 rules 8 and 9, as arithmetic: what a transport remembers, and what an old memory is worth. */
class EstimateMemoryTest {

    private val memory = EstimateMemory()

    @Test
    fun aTransportWithNoSamplesStartsFromItsColdDefault() {
        val estimate = memory.estimate(NetworkTransport.Cellular(CellularGeneration.LTE), nowMs = 0)

        assertThat(estimate.meanBps).isEqualTo(ColdDefaults.CELLULAR_LTE_BPS)
        assertThat(estimate.sampleCount).isEqualTo(0)
        assertThat(estimate.spreadBps).isNull()
        assertThat(estimate.conservativeBps).isNull()
    }

    @Test
    fun eachTransportRemembersItsOwnSamplesAndNotAnothers() {
        memory.record(NetworkTransport.Wifi, 20 * MBPS, nowMs = 1_000)
        memory.record(NetworkTransport.Cellular(null), 5 * MBPS, nowMs = 2_000)

        assertThat(memory.estimate(NetworkTransport.Wifi, nowMs = 2_000).meanBps).isEqualTo(20 * MBPS)
        assertThat(memory.estimate(NetworkTransport.Cellular(null), nowMs = 2_000).meanBps).isEqualTo(5 * MBPS)
        // A generation is part of the key: LTE is not "cellular, generation unknown".
        assertThat(memory.estimate(NetworkTransport.Cellular(CellularGeneration.LTE), nowMs = 2_000).sampleCount)
            .isEqualTo(0)
    }

    @Test
    fun aFreshMemoryIsUsedAsMeasured() {
        memory.record(NetworkTransport.Wifi, 20 * MBPS, nowMs = 0)

        val atTheEdgeOfFresh = memory.estimate(NetworkTransport.Wifi, nowMs = EstimateMemory.FRESH_AGE_MS)

        assertThat(atTheEdgeOfFresh.meanBps).isEqualTo(20 * MBPS)
        assertThat(atTheEdgeOfFresh.newestSampleAgeMs).isEqualTo(EstimateMemory.FRESH_AGE_MS)
        assertThat(atTheEdgeOfFresh.sampleCount).isEqualTo(1)
    }

    @Test
    fun anAgeingMemoryDecaysLinearlyTowardTheColdDefault() {
        memory.record(NetworkTransport.Wifi, 20 * MBPS, nowMs = 0)
        val halfway = (EstimateMemory.FRESH_AGE_MS + EstimateMemory.STALE_AGE_MS) / 2

        val estimate = memory.estimate(NetworkTransport.Wifi, nowMs = halfway)

        // Half trust: (20 + 5) / 2 Mbit/s for the mean and, with one sample, for the percentile.
        assertThat(estimate.meanBps).isEqualTo((20 * MBPS + ColdDefaults.WIFI_BPS) / 2)
        assertThat(estimate.conservativeBps).isEqualTo((20 * MBPS + ColdDefaults.WIFI_BPS) / 2)
        // The scatter is what was measured, whatever the age.
        assertThat(estimate.spreadBps).isEqualTo(0)
        assertThat(estimate.sampleCount).isEqualTo(1)
    }

    @Test
    fun aStaleMemoryIsForgottenAndTheColdDefaultReturns() {
        memory.record(NetworkTransport.Wifi, 20 * MBPS, nowMs = 0)

        val stale = memory.estimate(NetworkTransport.Wifi, nowMs = EstimateMemory.STALE_AGE_MS)

        assertThat(stale.meanBps).isEqualTo(ColdDefaults.WIFI_BPS)
        assertThat(stale.sampleCount).isEqualTo(0)
        // And a sample recorded afterwards starts a window of its own rather than joining the old one.
        memory.record(NetworkTransport.Wifi, 8 * MBPS, nowMs = EstimateMemory.STALE_AGE_MS + 1)
        assertThat(memory.estimate(NetworkTransport.Wifi, nowMs = EstimateMemory.STALE_AGE_MS + 1).sampleCount).isEqualTo(1)
    }

    /** A clock that restarted — a test's, never a device's — makes the samples before it another timeline's. */
    @Test
    fun aMemoryTimedAfterNowIsForgottenRatherThanAgedBackwards() {
        memory.record(NetworkTransport.Wifi, 20 * MBPS, nowMs = 100_000)

        val restarted = memory.estimate(NetworkTransport.Wifi, nowMs = 1_000)

        assertThat(restarted.meanBps).isEqualTo(ColdDefaults.WIFI_BPS)
        assertThat(restarted.sampleCount).isEqualTo(0)
    }

    private companion object {
        const val MBPS = 1_000_000L
    }
}
