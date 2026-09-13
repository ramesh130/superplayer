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

package com.superplayer.testkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Replays a [ThroughputTrace]'s transport into the platform: as the harness's clock crosses a
 * stretch whose transport differs from the last, the device's default network becomes one of that
 * transport, and every callback registered with the connectivity service is told.
 *
 * The shaper replays the *rate*; this replays the *network*, which is the half of a handover that
 * `docs/throughput-traces.md` says is separate from the rate and that a bandwidth estimator keys
 * its memory on. Without it a trace's `WIFI → CELLULAR` would be a rate change the device never
 * noticed, and the reseed ADR-0009 rule 9 describes could not be asserted at a millisecond.
 *
 * Device-wide, as connectivity is: every player in the harness sees the change, whichever one's
 * clock advance crossed the stretch. The translation from the trace's vocabulary to the platform's
 * is here and nowhere else in the harness; core's own translation back into a
 * `NetworkTransport` is `ConditionsBinding.kt`'s, and it is what a test's assertion reaches.
 *
 * ref: Android `NetworkCapabilities.TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET` —
 * the vocabulary a trace's transport is written in, so that the platform can report exactly it.
 */
internal class TransportReplay(private val trace: ThroughputTrace, private val originMs: Long) {

    private var replayed: NetworkTransport? = null

    /** Makes the device's network the trace's at [nowMs] on the harness's clock, if it changed. */
    fun replayAt(nowMs: Long) {
        val transport = trace.transportAt(maxOf(0L, nowMs - originMs))
        if (transport == replayed) return
        replayed = transport
        setDeviceTransport(transport)
    }

    private fun setDeviceTransport(transport: NetworkTransport) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = checkNotNull(connectivity.activeNetwork) { "Robolectric's connectivity service has no active network" }
        val capabilities = ShadowNetworkCapabilities.newInstance()
        when (transport) {
            NetworkTransport.WIFI -> shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

            NetworkTransport.CELLULAR -> shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)

            NetworkTransport.ETHERNET -> shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)

            // A network with no transport the platform names, which is what core classifies as
            // unknown: connected, but to nothing SuperPlayer can key an estimate on.
            NetworkTransport.UNKNOWN -> Unit
        }
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
        // The shadow records the capabilities but tells nobody; a real connectivity service calls
        // back, and the callbacks are what the library observes with.
        shadowOf(connectivity).networkCallbacks.forEach { it.onCapabilitiesChanged(network, capabilities) }
    }
}
