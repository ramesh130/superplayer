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
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.view.Display
import androidx.media3.common.Player

/**
 * The one place Android's and Media3's vocabulary becomes a [PlaybackConditions] observation — the
 * inverse of `EngineBinding.kt`, and as confined (ADR-0009 rule 3).
 *
 * Each function here translates one reading and decides nothing. What to observe *when* is
 * [DecisionReapplication]'s; what to do with an observation is the policy's. Every translation is
 * here — the transport, the generation, the stream type, the stall state machine in
 * [StallObservation], and the display's [DisplayCapability] (ADR-0014 rule 5) — so that a reader
 * asking "where does an observation come from" has one answer. [DecisionReapplication] names `NetworkCapabilities` only in the callback signature the
 * platform dictates, and hands it straight here.
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

/**
 * The default display's [DisplayCapability] now, or [DisplayCapability.UNKNOWN] where there is no
 * display or the platform does not answer (ADR-0014 rule 9).
 *
 * The one translation of `Display.Mode` and `Display.HdrCapabilities` into SuperPlayer's vocabulary:
 * every player reads it once at construction into its [DisplayInForce], and a player built with
 * `superplayer-tv` reads it again on each change its [DisplayWatch] hears.
 *
 * ref: https://developer.android.com/reference/android/view/Display#getMode()
 * ref: https://developer.android.com/reference/android/view/Display.Mode#getSupportedHdrTypes()
 * ref: https://developer.android.com/reference/android/view/Display#getHdrCapabilities()
 */
internal fun displayCapabilityOf(context: Context): DisplayCapability = displayCapabilityOf(context.defaultDisplay())

/** [displayCapabilityOf] for a display already in hand, or for none. */
internal fun displayCapabilityOf(display: Display?): DisplayCapability {
    display ?: return DisplayCapability.UNKNOWN
    val mode = try {
        display.mode
    } catch (e: RuntimeException) {
        return DisplayCapability.UNKNOWN
    }
    return DisplayCapability(
        shortEdgePx = minOf(mode.physicalWidth, mode.physicalHeight).takeIf { it > 0 },
        hdrTypes = hdrTypesOf(display, mode),
    )
}

/** The default display, or null where there is none or the service does not answer. */
internal fun Context.defaultDisplay(): Display? = try {
    (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
} catch (e: RuntimeException) {
    null
}

/**
 * The HDR types [mode] shows: the mode's own from API 34, where the platform moved them, and the
 * display-wide capabilities below it.
 *
 * A display reporting no capabilities at all is *unknown* on every API level, its modes included.
 * From API 34 a mode with nothing declared answers an empty array, which would read as "supports no
 * HDR" on a display that said nothing, and the capabilities being absent is what tells the two apart.
 */
private fun hdrTypesOf(display: Display, mode: Display.Mode): Set<HdrType>? {
    val capabilities = try {
        display.hdrCapabilities
    } catch (e: RuntimeException) {
        return null
    } ?: return null
    val declared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        mode.supportedHdrTypes
    } else {
        @Suppress("DEPRECATION")
        capabilities.supportedHdrTypes
    }
    return declared.asList().mapNotNull(::hdrTypeOf).toSet()
}

/** The type SuperPlayer names for a platform one, or null for a type nothing here reads, which refuses nothing. */
private fun hdrTypeOf(platformType: Int): HdrType? = when (platformType) {
    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrType.DOLBY_VISION
    Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrType.HDR10
    Display.HdrCapabilities.HDR_TYPE_HLG -> HdrType.HLG
    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrType.HDR10_PLUS
    else -> null
}

/**
 * The translation of the engine's state transitions into a [StallHistory].
 *
 * A rebuffer is a buffering state entered after this item was first ready, ended by playback
 * becoming ready again. A stall that ends in idle or ended is discarded: it did not *end*, and a
 * policy's post-rebuffer hysteresis is about recoveries. A stall a seek caused is not a rebuffer,
 * on **exactly the rule `docs/telemetry-schema.md` states** for `RebufferStarted.seekInduced`, so
 * that what a policy is told and what a pipeline is told agree: a stall is seek-induced when it
 * starts between a seek's request and its completion, or within [SEEK_EXCLUSION_WINDOW_MS] after
 * that completion. The document is where that number is argued; it is repeated here rather than
 * shared with `superplayer-telemetry` because core cannot depend on it, and a divergence would be a
 * change to the document first.
 *
 * All times are on the monotonic clock the caller passes in, which keeps this class a translation
 * with no clock of its own. Driven from the application thread only.
 */
internal class StallObservation {

    private var readyOnce = false
    private var stallStartedAtMs: Long? = null
    private var seekRequested = false
    private var seekCompletedAtMs: Long? = null
    private var rebufferCount = 0
    private var lastRebufferEndedAtMs: Long? = null
    private var lastRebufferDurationMs: Long? = null

    /** New content: the history is the next item's, and its first ready is start-up again. */
    fun reset() {
        readyOnce = false
        stallStartedAtMs = null
        seekRequested = false
        seekCompletedAtMs = null
        rebufferCount = 0
        lastRebufferEndedAtMs = null
        lastRebufferDurationMs = null
    }

    /**
     * A seek was issued at [nowMs]. [alreadyReady] is whether playback was ready at that moment: a
     * seek into buffered data never leaves the ready state, so it completes as it is requested.
     */
    fun onSeekRequested(nowMs: Long, alreadyReady: Boolean) {
        if (alreadyReady) {
            seekCompletedAtMs = nowMs
        } else {
            seekRequested = true
        }
    }

    /** The engine's playback state became [state] at [nowMs]. Returns true when a rebuffer ended. */
    fun onPlaybackStateChanged(state: Int, nowMs: Long): Boolean {
        when (state) {
            Player.STATE_BUFFERING -> {
                if (readyOnce && stallStartedAtMs == null && !isSeekInducedAt(nowMs)) {
                    stallStartedAtMs = nowMs
                }
                return false
            }

            Player.STATE_READY -> {
                readyOnce = true
                if (seekRequested) {
                    seekRequested = false
                    seekCompletedAtMs = nowMs
                }
                val startedAt = stallStartedAtMs ?: return false
                stallStartedAtMs = null
                rebufferCount++
                lastRebufferEndedAtMs = nowMs
                lastRebufferDurationMs = nowMs - startedAt
                return true
            }

            else -> {
                stallStartedAtMs = null
                seekRequested = false
                return false
            }
        }
    }

    /** The history as of [nowMs]: the "since" is a duration, and durations age. */
    fun current(nowMs: Long): StallHistory {
        val endedAt = lastRebufferEndedAtMs ?: return StallHistory.NONE
        return StallHistory(
            rebufferCount = rebufferCount,
            msSinceLastRebufferEnded = nowMs - endedAt,
            lastRebufferDurationMs = lastRebufferDurationMs,
        )
    }

    private fun isSeekInducedAt(nowMs: Long): Boolean {
        if (seekRequested) return true
        val completedAt = seekCompletedAtMs ?: return false
        return nowMs - completedAt <= SEEK_EXCLUSION_WINDOW_MS
    }

    private companion object {
        /** `docs/telemetry-schema.md`, *Rebuffering* — the number, and why it is one second. */
        const val SEEK_EXCLUSION_WINDOW_MS = 1_000L
    }
}
