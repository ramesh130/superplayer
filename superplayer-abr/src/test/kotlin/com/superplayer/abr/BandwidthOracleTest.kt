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

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.media3.datasource.DataSpec
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.CellularGeneration
import com.superplayer.core.NetworkTransport
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * The oracle's one platform reading — which transport the device is on — through Robolectric's
 * connectivity service, and the memory every oracle in the process shares.
 */
@RunWith(AndroidJUnit4::class)
class BandwidthOracleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val built = mutableListOf<BandwidthOracle>()

    @Before
    fun startOnWifi() {
        EstimateMemory.PROCESS.forget()
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    @After
    fun releaseOracles() {
        built.forEach { it.release() }
        EstimateMemory.PROCESS.forget()
    }

    @Test
    fun theTransportIsReadAtConstructionAndFollowedAfterwards() {
        val oracle = build()
        assertThat(oracle.currentTransport()).isEqualTo(NetworkTransport.Wifi)
        assertThat(oracle.currentEstimate().meanBps).isEqualTo(ColdDefaults.WIFI_BPS)

        // The generation is read only with the app's own permission, which the library never asks
        // for; this app holds it.
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(telephonyManager()).setDataNetworkType(TelephonyManager.NETWORK_TYPE_NR)
        setActiveTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        assertThat(oracle.currentTransport()).isEqualTo(NetworkTransport.Cellular(CellularGeneration.NR))
        assertThat(oracle.currentEstimate().meanBps).isEqualTo(ColdDefaults.CELLULAR_NR_BPS)
    }

    @Test
    fun releasingUnregistersTheCallbackAndTwiceIsHarmless() {
        val before = shadowOf(connectivityManager()).networkCallbacks.size
        val oracle = build()
        assertThat(shadowOf(connectivityManager()).networkCallbacks.size).isEqualTo(before + 1)

        oracle.release()
        oracle.release()

        assertThat(shadowOf(connectivityManager()).networkCallbacks.size).isEqualTo(before)
    }

    @Test
    fun everyOracleInTheProcessReadsOneMemory() {
        val clock = FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ false)
        val first = BandwidthOracle(context, clock, EstimateMemory.PROCESS).also { built += it }
        val second = BandwidthOracle(context, clock, EstimateMemory.PROCESS).also { built += it }
        val spec = DataSpec(android.net.Uri.parse("fake://superplayer.test/segment0.ts"))

        // 250 000 bytes in 100 ms through the first oracle's meter.
        first.meter.onTransferStart(FakeDataSource(), spec, true)
        first.meter.onBytesTransferred(FakeDataSource(), spec, true, 250_000)
        clock.advanceTime(100)
        first.meter.onTransferEnd(FakeDataSource(), spec, true)

        // ADR-0009 rule 8: the estimate is the device's network's, not the player's.
        assertThat(second.currentEstimate().meanBps).isEqualTo(20_000_000L)
        assertThat(second.currentEstimate().sampleCount).isEqualTo(1)
    }

    private fun build(): BandwidthOracle = BandwidthOracle.Builder(context).build().also { built += it }

    /** Sets the device's default network to one of [transport], and tells every registered callback. */
    private fun setActiveTransport(transport: Int) {
        val connectivity = connectivityManager()
        val network = checkNotNull(connectivity.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
        shadowOf(connectivity).networkCallbacks.forEach { it.onCapabilitiesChanged(network, capabilities) }
    }

    private fun connectivityManager(): ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun telephonyManager(): TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
}
