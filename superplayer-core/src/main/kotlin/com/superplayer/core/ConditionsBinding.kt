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

package com.superplayer.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.media3.common.Player

/**
 * The one place Android's and Media3's vocabulary becomes a [PlaybackConditions] observation — the
 * inverse of `EngineBinding.kt`, and as confined (ADR-0009 rule 3).
 *
 * Each function here translates one reading and decides nothing. What to observe *when* is
 * [DecisionReapplication]'s; what to do with an observation is the policy's. This file is
 * deliberately the only importer of `NetworkCapabilities` and `TelephonyManager` on the policy
 * path, so that a reader asking "where does a transport come from" has one answer.
 */

/**
 * The transport of [capabilities], or [NetworkTransport.Unknown] for a network SuperPlayer does not
 * classify. Null capabilities — no network — are [NetworkTransport.Unknown] too: connected to
 * nothing is a fact about the transport, whereas *unobserved* is a fact about SuperPlayer, and the
 * caller that never asked is the only one that reports that.
 *
 * Checked in the order a VPN makes necessary: a VPN over WiFi reports both transports, and the
 * underlying one is the one the bytes cross.
 *
 * ref: https://developer.android.com/reference/android/net/NetworkCapabilities
 */
internal fun NetworkCapabilities?.toNetworkTransport(context: Context): NetworkTransport =
    when {
        this == null -> NetworkTransport.Unknown

        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.Wifi

        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.Ethernet

        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            NetworkTransport.Cellular(cellularGenerationOf(context))

        else -> NetworkTransport.Unknown
    }

/**
 * The generation of the current data network, or null if the app may not ask.
 *
 * Reading the data network type needs `READ_PHONE_STATE`, a runtime permission most apps do not
 * hold and which a library must not ask for on a consumer's behalf. Checked rather than caught,
 * because the check is the contract: an app that holds it gets the generation, and one that does
 * not gets *unobserved*, which is what null means here. (API 33's narrower
 * `READ_BASIC_PHONE_STATE` would also allow the read; it is not checked, so an app holding only
 * that one is treated as holding neither — a simpler contract, and one to widen when an app asks.) Every type the platform lists that is neither LTE nor NR collapses
 * to [CellularGeneration.OLDER], the distinction a policy can act on; `NETWORK_TYPE_UNKNOWN` is
 * null, because it is.
 *
 * ref: https://developer.android.com/reference/android/telephony/TelephonyManager#getDataNetworkType()
 */
// Suppressed rather than satisfied, and deliberately: lint's fix for this finding is to declare the
// permission in the manifest, and a runtime permission is the app's to declare and to ask for,
// never a library's (the manifest says so). The check below is the guard; the suppression only
// tells lint that its manifest-shaped remedy is the wrong one here.
@SuppressLint("MissingPermission")
internal fun cellularGenerationOf(context: Context): CellularGeneration? {
    val telephony = context.telephonyManager() ?: return null
    // The check is the guard lint follows: one permission, one `if`, immediately before the read.
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        return null
    }
    return when (telephony.dataNetworkType) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
        TelephonyManager.NETWORK_TYPE_NR -> CellularGeneration.NR
        TelephonyManager.NETWORK_TYPE_LTE -> CellularGeneration.LTE
        else -> CellularGeneration.OLDER
    }
}

/**
 * The transport of the device's current default network, read once rather than waited for — the
 * value a policy consulted at construction sees. Null when there is no connectivity service to
 * ask. `ACCESS_NETWORK_STATE` is core's own manifest's, and the manifest says why that is not the
 * permission ADR-0007 rule 4 keeps out of the library.
 */
internal fun currentNetworkTransportOf(context: Context): NetworkTransport? {
    val connectivity = context.connectivityManager() ?: return null
    return connectivity.getNetworkCapabilities(connectivity.activeNetwork).toNetworkTransport(context)
}

/**
 * What the manifest declared the current item to be, or null until there is a timeline to read it
 * from. Read off the engine's own window flag rather than the profile, for the reason
 * [StreamType] gives.
 */
internal fun Player.observedStreamType(): StreamType? {
    if (currentTimeline.isEmpty) return null
    return if (isCurrentMediaItemLive) StreamType.LIVE else StreamType.ON_DEMAND
}

internal fun Context.connectivityManager(): ConnectivityManager? =
    getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

internal fun Context.telephonyManager(): TelephonyManager? =
    getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
