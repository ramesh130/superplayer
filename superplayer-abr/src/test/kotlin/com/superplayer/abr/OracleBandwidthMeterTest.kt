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

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.CellularGeneration
import com.superplayer.core.NetworkTransport
import com.superplayer.core.ThroughputSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Media3 half of the oracle, driven through the `TransferListener` the engine would hand it,
 * on a clock the test moves — so every sample is a number worked by hand from bytes and
 * milliseconds.
 *
 * A "trace" here is a sequence of transfers, each of so many bytes over so many milliseconds; the
 * values are chosen so that the readings come out whole.
 */
@RunWith(AndroidJUnit4::class)
class OracleBandwidthMeterTest {

    private val clock = FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ false)
    private val memory = EstimateMemory()
    private val meter = OracleBandwidthMeter(clock, memory, NetworkTransport.Wifi)
    private val source: DataSource = FakeDataSource()

    @Test
    fun aTransferIsSampledAsItsBytesOverItsActiveTime() {
        // 250 000 bytes in 100 ms: 250 000 × 8 000 / 100 = 20 000 000 bit/s.
        transfer(bytes = 250_000, elapsedMs = 100)

        val estimate = meter.currentEstimate()
        assertThat(estimate.meanBps).isEqualTo(20 * MBPS)
        assertThat(estimate.sampleCount).isEqualTo(1)
        assertThat(estimate.newestSampleAgeMs).isEqualTo(0)
        assertThat(meter.bitrateEstimate).isEqualTo(20 * MBPS)
    }

    @Test
    fun theMeanSpreadAndPercentileOfAKnownTraceAreTheHandComputedOnes() {
        // 100 ms each, so the sample in Mbit/s is the byte count / 12 500: 2, 4, 4, 4, 5, 5, 7, 9.
        listOf(2, 4, 4, 4, 5, 5, 7, 9).forEach { mbps ->
            transfer(bytes = mbps * 12_500L, elapsedMs = 100)
            clock.advanceTime(1_000)
        }

        val estimate = meter.currentEstimate()
        assertThat(estimate.meanBps).isEqualTo(5 * MBPS)
        // sqrt((9+1+1+1+0+0+4+16)/8) = 2.
        assertThat(estimate.spreadBps).isEqualTo(2 * MBPS)
        // The 25th percentile by nearest rank: the second-lowest of eight.
        assertThat(estimate.conservativeBps).isEqualTo(4 * MBPS)
        assertThat(estimate.sampleCount).isEqualTo(8)
        assertThat(estimate.newestSampleAgeMs).isEqualTo(1_000)
    }

    @Test
    fun aTransferNotServedByTheNetworkContributesNoSample() {
        transfer(bytes = 250_000, elapsedMs = 100, isNetwork = false)

        assertThat(meter.currentEstimate().sampleCount).isEqualTo(0)
        assertThat(meter.countedTransfers).isEqualTo(0)
        assertThat(meter.countedBytes).isEqualTo(0)
        // The cold default stands: what a cache answered says nothing about the network.
        assertThat(meter.currentEstimate().meanBps).isEqualTo(ColdDefaults.WIFI_BPS)
    }

    @Test
    fun aTransferThatMightNotUseTheFullNetworkSpeedContributesNoSample() {
        val throttled = DataSpec.Builder().setUri(URI).setFlags(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED).build()

        transfer(bytes = 250_000, elapsedMs = 100, dataSpec = throttled)

        assertThat(meter.currentEstimate().sampleCount).isEqualTo(0)
    }

    @Test
    fun aTransferTooSmallToBeASampleRollsIntoTheNextOne() {
        // A playlist: 1 000 bytes in 5 ms, then a second of nothing, then a segment.
        transfer(bytes = 1_000, elapsedMs = 5)
        assertThat(meter.currentEstimate().sampleCount).isEqualTo(0)
        clock.advanceTime(1_000)
        transfer(bytes = 249_000, elapsedMs = 95)

        // One sample over both transfers' bytes and both transfers' active time, and none of the
        // idle second between them: 250 000 × 8 000 / 100.
        val estimate = meter.currentEstimate()
        assertThat(estimate.sampleCount).isEqualTo(1)
        assertThat(estimate.meanBps).isEqualTo(20 * MBPS)
    }

    @Test
    fun concurrentTransfersAreOneSampleOverTheTimeEitherWasOpen() {
        val first = DataSpec(URI)
        val second = DataSpec(Uri.parse("fake://superplayer.test/second"))
        // Two transfers sharing 100 ms: each reports half the bytes, and the link delivered the sum.
        meter.onTransferStart(source, first, true)
        meter.onTransferStart(source, second, true)
        meter.onBytesTransferred(source, first, true, 125_000)
        meter.onBytesTransferred(source, second, true, 125_000)
        clock.advanceTime(100)
        meter.onTransferEnd(source, first, true)

        // The first end takes the sample; the second, ending at once, has nothing new to add.
        assertThat(meter.currentEstimate().meanBps).isEqualTo(20 * MBPS)
        meter.onTransferEnd(source, second, true)
        assertThat(meter.currentEstimate().sampleCount).isEqualTo(1)
    }

    @Test
    fun aPolicyHearsTheFirstSampleAndAMaterialMoveAndNothingBetween() {
        var told = 0
        meter.addListener(ThroughputSource.Listener { told++ })

        transfer(bytes = 250_000, elapsedMs = 100) // 20 Mbit/s: the first estimate.
        assertThat(told).isEqualTo(1)

        // The mean moves from 20 to 21 Mbit/s: a twentieth, and a policy is not asked about it.
        transfer(bytes = 275_000, elapsedMs = 100) // 22 Mbit/s
        assertThat(told).isEqualTo(1)

        // Then to 12 Mbit/s: {20, 22, 12} has a mean of 18, a tenth under the last announced 20 —
        // still under the fifth that is material.
        transfer(bytes = 150_000, elapsedMs = 100)
        assertThat(told).isEqualTo(1)

        // {20, 22, 12, 2} has a mean of 14: 30% under 20, which is material.
        transfer(bytes = 25_000, elapsedMs = 100)
        assertThat(told).isEqualTo(2)
    }

    @Test
    fun crossingTheStableLineIsMaterialEvenWhenTheMeanBarelyMoves() {
        var told = 0
        meter.addListener(ThroughputSource.Listener { told++ })
        // Four samples at 20 Mbit/s: stable, spread 0.
        repeat(4) { transfer(bytes = 250_000, elapsedMs = 100) }
        assertThat(told).isEqualTo(1)

        // {20, 20, 20, 20, 10, 30}: mean still 20, spread sqrt((0×4 + 100 + 100)/6) ≈ 5.77 — over
        // a quarter of the mean, so the network is no longer stable, and that is announced.
        transfer(bytes = 125_000, elapsedMs = 100)
        assertThat(told).isEqualTo(1)
        transfer(bytes = 375_000, elapsedMs = 100)
        assertThat(told).isEqualTo(2)
    }

    @Test
    fun aTransportChangeReseedsFromThatTransportsOwnMemoryOrItsColdDefault() {
        var told = 0
        meter.addListener(ThroughputSource.Listener { told++ })
        transfer(bytes = 250_000, elapsedMs = 100)
        assertThat(meter.currentEstimate().meanBps).isEqualTo(20 * MBPS)
        val toldOnWifi = told

        meter.onTransportChanged(NetworkTransport.Cellular(CellularGeneration.LTE))

        // Nothing measured on LTE in this process: its cold default, not WiFi's twenty carried over.
        val onCellular = meter.currentEstimate()
        assertThat(onCellular.meanBps).isEqualTo(ColdDefaults.CELLULAR_LTE_BPS)
        assertThat(onCellular.sampleCount).isEqualTo(0)
        assertThat(meter.currentTransport).isEqualTo(NetworkTransport.Cellular(CellularGeneration.LTE))
        assertThat(told).isEqualTo(toldOnWifi + 1)

        // Back on WiFi, WiFi's own memory is what returns.
        transfer(bytes = 62_500, elapsedMs = 100) // 5 Mbit/s, on LTE
        meter.onTransportChanged(NetworkTransport.Wifi)
        assertThat(meter.currentEstimate().meanBps).isEqualTo(20 * MBPS)
        assertThat(meter.currentEstimate().sampleCount).isEqualTo(1)
    }

    @Test
    fun aTransferStraddlingTheHandoverCountsOnlyWhatItMovedAfterIt() {
        meter.onTransferStart(source, DataSpec(URI), true)
        meter.onBytesTransferred(source, DataSpec(URI), true, 125_000)
        clock.advanceTime(50)
        meter.onTransportChanged(NetworkTransport.Cellular(null))
        meter.onBytesTransferred(source, DataSpec(URI), true, 125_000)
        clock.advanceTime(50)
        meter.onTransferEnd(source, DataSpec(URI), true)

        // The bytes before the change are gone; the 125 000 after it over 50 ms are a sample of the
        // new transport: 125 000 × 8 000 / 50 = 20 Mbit/s.
        val estimate = meter.currentEstimate()
        assertThat(meter.currentTransport).isEqualTo(NetworkTransport.Cellular(null))
        assertThat(estimate.sampleCount).isEqualTo(1)
        assertThat(estimate.meanBps).isEqualTo(20 * MBPS)
    }

    @Test
    fun theEngineHearsEverySampleOnItsOwnHandler() {
        val samples = mutableListOf<Triple<Int, Long, Long>>()
        meter.addEventListener(
            Handler(Looper.getMainLooper()),
            BandwidthMeter.EventListener { elapsedMs, bytes, bitrate -> samples += Triple(elapsedMs, bytes, bitrate) },
        )

        transfer(bytes = 250_000, elapsedMs = 100)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(samples).containsExactly(Triple(100, 250_000L, 20 * MBPS))
    }

    private fun transfer(
        bytes: Long,
        elapsedMs: Long,
        isNetwork: Boolean = true,
        dataSpec: DataSpec = DataSpec(URI),
    ) {
        meter.onTransferInitializing(source, dataSpec, isNetwork)
        meter.onTransferStart(source, dataSpec, isNetwork)
        meter.onBytesTransferred(source, dataSpec, isNetwork, bytes.toInt())
        clock.advanceTime(elapsedMs)
        meter.onTransferEnd(source, dataSpec, isNetwork)
    }

    private companion object {
        const val MBPS = 1_000_000L
        val URI: Uri = Uri.parse("fake://superplayer.test/segment0.ts")
    }
}
