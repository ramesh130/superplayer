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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build

/**
 * The two conditions a download runs under that Media3's download requirements cannot state (ADR-0013
 * rules 10 and 11): a battery that is not low, and Data Saver, which still holds a store that accepts a
 * metered network while that network is metered. The network and storage are the download manager's own
 * requirements, and not read here.
 *
 * Read on demand with [read], which is what a store does before it decides anything, and heard while
 * [watch] is in force, which a store keeps only while it has a download pending — so a store with nothing
 * to download registers nothing (rule 15). Each change is handed to [onChange] on whichever thread the
 * platform called back on.
 */
internal class DownloadConditions(private val context: Context, private val onChange: () -> Unit) {

    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    /** Whether the battery was low at the last [read]. */
    var batteryLow: Boolean = false
        private set

    /** Whether Data Saver restricted the metered network in use at the last [read]. */
    var dataSaverRestricts: Boolean = false
        private set

    private var receiver: BroadcastReceiver? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun read() {
        batteryLow = isLow(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        // The pair the platform's own guidance checks together: the restriction applies to a metered network
        // only. `isActiveNetworkMetered` answers true where it cannot tell, the direction that costs a viewer a
        // slower download rather than their data allowance.
        // ref: https://developer.android.com/develop/connectivity/network-ops/data-saver
        dataSaverRestricts = connectivity != null &&
            connectivity.isActiveNetworkMetered &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    fun watch() {
        if (receiver != null) return
        val heard = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onChange()
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED) }
        // Both are protected broadcasts only the system sends, which a receiver not exported still hears.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(heard, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(heard, filter)
        }
        receiver = heard
        // A network that turns metered under Data Saver changes no download requirement when any network is
        // accepted, so the download manager does not hear it and this has to.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChange()
        }
        try {
            connectivity?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            // Past the platform's cap of 100 network callbacks per process: a handover under Data Saver is then
            // applied at the next battery reading rather than at once, a lost observation rather than a lost app.
        }
    }

    fun unwatch() {
        receiver?.let(context::unregisterReceiver)
        receiver = null
        networkCallback?.let { connectivity?.unregisterNetworkCallback(it) }
        networkCallback = null
    }

    private companion object {

        /**
         * Whether [reading] says the battery is low, decided as `WorkManager`'s battery-not-low constraint
         * decides it, so the process that is downloading and the schedule that restarts one agree: a status of
         * unknown is not low, and otherwise the level over the scale at or under [LOW_FRACTION].
         *
         * One departure, argued: **no reading at all is not low here**, where `WorkManager` holds work on it. A
         * device always has a sticky battery broadcast, a television included, so no reading is a platform
         * that says nothing, and a store that refused to download on no evidence would refuse on every such
         * platform with nothing to show the viewer why.
         */
        fun isLow(reading: Intent?): Boolean {
            reading ?: return false
            if (reading.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_UNKNOWN) return false
            val level = reading.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = reading.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return false
            return level.toFloat() / scale <= LOW_FRACTION
        }

        // ref: https://developer.android.com/reference/androidx/work/Constraints.Builder#setRequiresBatteryNotLow(boolean)
        // — the platform's `ACTION_BATTERY_LOW` threshold, which WorkManager's tracker reads as this fraction.
        const val LOW_FRACTION = 0.15f
    }
}
