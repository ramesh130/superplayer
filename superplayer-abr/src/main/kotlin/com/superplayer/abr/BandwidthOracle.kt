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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.common.util.Clock
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.NetworkTransport
import com.superplayer.core.ThroughputEstimate
import com.superplayer.core.connectivityManager
import com.superplayer.core.currentNetworkTransportOf
import com.superplayer.core.toNetworkTransport

/**
 * The throughput estimator every other decision in `superplayer-abr` reads from: what the network is
 * believed to deliver, per transport, as a [ThroughputEstimate].
 *
 * Four properties, each a defect it closes (`PRD.md` §3.1, #99):
 *
 * - **Per-transport estimates, reseeded on a transport change.** A WiFi estimate carried onto
 *   cellular asks for a rendition the new link cannot carry until the average catches up, seconds
 *   later; this keys its estimate by [NetworkTransport] and, on a change, reseeds from that
 *   transport's own last estimate or from its documented cold default. Where an estimate may live
 *   is ADR-0009 rules 8 and 9: in memory, for the process, and nowhere else.
 * - **Cache hits are not samples.** A read a cache answered says nothing about the network, and
 *   what excludes it is the flag every source carries rather than where measurement sits in the
 *   chain — `TransferChain` has the argument.
 * - **A spread and a conservative percentile beside the mean**, because the error in a prediction,
 *   not its level, is what produces a rebuffer (ADR-0009, context section).
 * - **One estimator.** The estimate Media3's selector reads is this one, and so is the
 *   [com.superplayer.core.PlaybackConditions.throughput] a policy reads — two estimators
 *   disagreeing is how "quality drops randomly" happens.
 *
 * This is the engine-agnostic face; the Media3 half — the meter the engine is built with, the
 * listener the transfer chain reports to — is internal, and reaches `SuperPlayer.Builder.build()`
 * through the seam ADR-0009 rule 7 describes. Nothing here opens a socket: the one platform call in
 * the module is the connectivity service, read for which transport the device is on.
 *
 * ```kotlin
 * val oracle = BandwidthOracle.Builder(context).build()
 * oracle.currentEstimate().meanBps
 * oracle.release()
 * ```
 *
 * One per player, [release]d with it: the estimate memory behind every oracle in the process is
 * shared, so building several is cheap and forgetting to release one leaks a connectivity callback
 * rather than an estimate.
 */
public class BandwidthOracle internal constructor(
    context: Context,
    clock: Clock,
    memory: EstimateMemory,
) {

    private val applicationContext = context.applicationContext

    internal val meter: OracleBandwidthMeter = OracleBandwidthMeter(
        clock,
        memory,
        initialTransport = currentNetworkTransportOf(applicationContext) ?: NetworkTransport.Unknown,
    )

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkCallback()
    }

    /** The transport the device is on, as this oracle last observed it. */
    public fun currentTransport(): NetworkTransport = meter.currentTransport

    /** What that transport is believed to deliver, right now. Never null: a transport with no samples has a cold default. */
    public fun currentEstimate(): ThroughputEstimate = meter.currentEstimate()

    /** Stops observing the transport. Idempotent. The memory behind the estimate is the process's and is not released. */
    public fun release() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            applicationContext.connectivityManager()?.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Already unregistered: nothing to undo.
        }
    }

    /** Puts the meter in the engine's slot, so the estimate the engine selects on is this oracle's. */
    internal fun configure(configuration: EngineConfiguration) {
        configuration.bandwidthMeter = meter
    }

    private fun registerNetworkCallback() {
        val connectivity = applicationContext.connectivityManager() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // The translation is core's, in the one file ADR-0009 rule 3 confines it to; this
                // module reads the platform in this one place and hands it straight there.
                meter.onTransportChanged(capabilities.toNetworkTransport(applicationContext))
            }

            override fun onLost(network: Network) {
                meter.onTransportChanged(NetworkTransport.Unknown)
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            // The platform caps network callbacks at 100 per process and throws past the cap
            // (`TooManyRequestsException`, API 26, which a minSdk 24 build cannot name in a catch).
            // A pool at the limit keeps the transport construction read rather than losing the
            // player; `DecisionReapplication` makes the same choice for the same reason.
            // ref: https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback)
        }
    }

    /**
     * Builds a [BandwidthOracle] over the process's estimate memory and the platform clock.
     *
     * The clock is the monotonic one every duration in `docs/telemetry-schema.md` is measured on,
     * which is also why a harness test is deterministic without a clock of its own: `PlaybackHarness`
     * moves the platform clock and the engine's fake one together (`docs/testing.md`, *Its two
     * clocks move together*), so a sample timed here is timed on the harness's clock.
     */
    public class Builder(private val context: Context) {

        public fun build(): BandwidthOracle = BandwidthOracle(context, Clock.DEFAULT, EstimateMemory.PROCESS)
    }
}
