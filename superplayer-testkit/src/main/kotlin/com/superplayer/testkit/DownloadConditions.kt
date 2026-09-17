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
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowInstrumentation
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers

/**
 * The three conditions a download is scheduled under (ADR-0013 rules 10 and 11) — an unmetered
 * network, a battery that is not low, storage that is not low — written into the platform where every
 * reader of them looks, and read back the way the scheduler's own trackers read them.
 *
 * **Written into the platform rather than into a flag**, because there is more than one reader and a
 * statement has to be true for all of them at once. Media3's `Requirements` reads the active
 * `NetworkInfo`, `isActiveNetworkMetered` and the `VALIDATED` capability for the network, and the
 * sticky `ACTION_DEVICE_STORAGE_LOW` broadcast for storage; a battery watcher reads the sticky
 * `ACTION_BATTERY_CHANGED`; and each also hears the transition broadcast a real device sends. Each
 * statement below sets every one of those, so a test that says "metered" cannot be read as unmetered
 * by one reader and metered by another.
 *
 * **What `WorkManager` reads is not exercised.** Its test driver does not evaluate constraints: work
 * with constraints runs when a test calls `setAllConstraintsMet`, and not otherwise. So the harness
 * evaluates a request's `Constraints` against [unmetered], [batteryLow] and [storageLow] and says
 * "met" only where all of them hold ([PlaybackHarness.runScheduledWork]). That this agrees with
 * WorkManager's own trackers on a device is the assumption `docs/testing.md`'s *Downloads* names.
 *
 * Robolectric resets every shadow and every sticky broadcast between tests, so a statement lasts one
 * test, and a test that states nothing gets Robolectric's device: a *metered* mobile network, storage
 * not low, and no battery reading at all, which `WorkManager` reads as the battery-not-low constraint
 * unmet.
 */
internal object DownloadConditions {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val connectivity: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun stateNetwork(metered: Boolean) {
        // Robolectric's `isActiveNetworkMetered` answers from the active `NetworkInfo`'s type — mobile is
        // metered, anything else is not — so the info is what decides it, and the capabilities are set to
        // agree for every reader that asks them instead. The info first: setting it makes a different
        // network the active one, and capabilities set on the previous one would describe nothing.
        val type = if (metered) ConnectivityManager.TYPE_MOBILE else ConnectivityManager.TYPE_WIFI
        shadowOf(connectivity).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true),
        )
        val network = checkNotNull(connectivity.activeNetwork) { "Robolectric's connectivity service has no active network" }
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // Media3's `Requirements` refuses a network the platform has not validated, however connected.
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (metered) {
            shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        } else {
            shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
        // A real connectivity service calls back; the shadow only records.
        shadowOf(connectivity).networkCallbacks.forEach { it.onCapabilitiesChanged(network, capabilities) }
        @Suppress("DEPRECATION")
        context.sendBroadcast(Intent(ConnectivityManager.CONNECTIVITY_ACTION))
    }

    fun stateBattery(low: Boolean) {
        val level = if (low) LOW_BATTERY_PERCENT else HEALTHY_BATTERY_PERCENT
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, level)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                .putExtra(BatteryManager.EXTRA_BATTERY_LOW, low),
        )
        context.sendBroadcast(Intent(if (low) Intent.ACTION_BATTERY_LOW else Intent.ACTION_BATTERY_OKAY))
    }

    fun stateStorage(low: Boolean) {
        @Suppress("DEPRECATION")
        if (low) {
            context.sendStickyBroadcast(Intent(Intent.ACTION_DEVICE_STORAGE_LOW))
            context.sendBroadcast(Intent(Intent.ACTION_DEVICE_STORAGE_LOW))
        } else {
            forgetStickyBroadcast(Intent.ACTION_DEVICE_STORAGE_LOW)
            context.sendBroadcast(Intent(Intent.ACTION_DEVICE_STORAGE_OK))
        }
    }

    /**
     * Withdraws the sticky broadcast for [action], as the platform does when the condition it announced
     * ends. Robolectric keeps sticky intents in a map keyed by action and does not shadow
     * `removeStickyBroadcast`, so the entry is removed from that map by reflection — the one private
     * Robolectric field this module names, named here so that a Robolectric upgrade that moves it fails
     * in one place. A storage-low statement that could not be withdrawn would leave every later reading
     * in the test low.
     */
    private fun forgetStickyBroadcast(action: String) {
        val instrumentation = Shadow.extract<ShadowInstrumentation>(ShadowInstrumentation.getInstrumentation())
        ReflectionHelpers.getField<MutableMap<String, Intent>>(instrumentation, "stickyIntents").remove(action)
    }

    /** Whether the active network is connected and unmetered, as `Requirements` would answer. */
    val unmetered: Boolean
        get() = connected && !connectivity.isActiveNetworkMetered

    @Suppress("DEPRECATION")
    val connected: Boolean
        get() = connectivity.activeNetworkInfo?.isConnected == true

    /**
     * Whether the battery-not-low constraint fails, decided exactly as `WorkManager` 2.11's
     * `BatteryNotLowTracker` decides it, read from its bytecode: **no reading at all fails it**, a status
     * of `BATTERY_STATUS_UNKNOWN` passes it, and otherwise the level over the scale must be above the
     * threshold. Whether the device is plugged in is not consulted. So Robolectric's own device, which
     * reports no battery, holds work requiring battery-not-low until a test states one.
     */
    val batteryLow: Boolean
        get() {
            val reading = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
            val status = reading.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (status == BatteryManager.BATTERY_STATUS_UNKNOWN) return false
            val level = reading.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = reading.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return level.toFloat() / scale <= WORK_MANAGER_BATTERY_LOW_FRACTION
        }

    /** Whether storage is low: the sticky broadcast is present, which is what every reader checks. */
    val storageLow: Boolean
        get() = context.registerReceiver(null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)) != null

    // Either side of the threshold with room to spare, so no reader's rounding can land on it.
    private const val LOW_BATTERY_PERCENT = 5
    private const val HEALTHY_BATTERY_PERCENT = 80

    // ref: https://developer.android.com/reference/androidx/work/Constraints.Builder#setRequiresBatteryNotLow(boolean)
    // — "the battery is not low", which the platform's `ACTION_BATTERY_LOW` puts at 15%, and which
    // WorkManager's tracker reads as that fraction of the reported scale.
    private const val WORK_MANAGER_BATTERY_LOW_FRACTION = 0.15f
}
