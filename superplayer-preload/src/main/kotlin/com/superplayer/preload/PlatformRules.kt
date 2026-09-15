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

package com.superplayer.preload

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.superplayer.core.BufferPolicy
import com.superplayer.core.PreloadDepth
import com.superplayer.core.PreloadPolicy
import com.superplayer.core.connectivityManager
import com.superplayer.core.heapBudgetBytesOf
import com.superplayer.core.isLowRamDeviceOf

/**
 * ADR-0010 rule 11's memory guard: how much of a decided window the app's heap admits, and the release of
 * all of it when the platform asks for memory back.
 *
 * A platform rule rather than policy, for ADR-0006 rule 1's reason: no content has a different right
 * answer to a trim. A policy sees the heap budget as a condition and may decide less; this is what stops
 * one deciding more.
 *
 * **The cap** admits the window in its priority order, costing each item at [bytesFor], until the next
 * would pass [PRELOAD_SHARE_OF_HEAP] of the heap budget `DeviceCapacity.kt` reads — so the items dropped are
 * the ones the scroll is least likely to reach. A device that declares itself low-RAM, or reports no heap,
 * is admitted [CONSTRAINED_DEVICE_ITEMS] instead.
 *
 * **The release** is on `onTrimMemory` at `TRIM_MEMORY_RUNNING_LOW` and above, and on `onLowMemory`. The
 * guard then admits nothing until [resume], which the coordinator calls on the feed's next input.
 * `TRIM_MEMORY_UI_HIDDEN` is included, unlike core's telemetry, which skips it because a player
 * playing in the background has nothing to give back. Prefetched rows and warm decoders do: they exist
 * for a screen that can no longer be seen. From API 34 that level and `TRIM_MEMORY_BACKGROUND` are also
 * the only two the platform delivers, so a guard that skipped it would release on nothing.
 *
 * ref: https://developer.android.com/reference/android/content/ComponentCallbacks2#onTrimMemory(int)
 */
internal class MemoryGuard(
    private val context: Context,
    private val onApplicationThread: (() -> Unit) -> Unit,
    private val onRelease: () -> Unit,
) {

    private val lowRam: Boolean = isLowRamDeviceOf(context)

    private val budgetBytes: Long? = heapBudgetBytesOf(context)?.let { it / PRELOAD_SHARE_OF_HEAP }

    /** Whether a trim has released everything and nothing is admitted until [resume]. */
    var trimmed: Boolean = false
        private set

    private val callbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trim()
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            trim()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    fun register() {
        context.registerComponentCallbacks(callbacks)
    }

    fun unregister() {
        context.unregisterComponentCallbacks(callbacks)
    }

    /** The feed is moving again: admit what the heap allows. */
    fun resume() {
        trimmed = false
    }

    /**
     * The prefix of [order] the heap admits. [warmable] is how many of its first items may be held on a
     * warm decoder, which costs more than a loaded range.
     */
    fun admit(order: List<Int>, policy: PreloadPolicy, buffer: BufferPolicy, warmable: Int): List<Int> {
        if (trimmed) return emptyList()
        val budget = budgetBytes
        if (lowRam || budget == null) return order.take(CONSTRAINED_DEVICE_ITEMS)
        var spent = 0L
        val admitted = ArrayList<Int>(order.size)
        for ((rank, index) in order.withIndex()) {
            spent += bytesFor(policy.depth, buffer, warm = rank < warmable)
            if (spent > budget) break
            admitted += index
        }
        return admitted
    }

    private fun trim() {
        onApplicationThread {
            if (trimmed) return@onApplicationThread
            trimmed = true
            onRelease()
        }
    }

    /**
     * What one prefetched item is costed at: [PREPARED_ITEM_BYTES] for the source, and the media it holds
     * at [REFERENCE_BYTES_PER_SECOND].
     *
     * A warm item holds more media than its depth says. A warm player is a paused player on the pool's
     * load control, which loads to `minBufferMs` rather than stopping at the prefetched range, so it is
     * costed at whichever of the two is longer.
     */
    private fun bytesFor(depth: PreloadDepth, buffer: BufferPolicy, warm: Boolean): Long {
        val mediaMs = when (depth) {
            PreloadDepth.SourcePrepared, PreloadDepth.TracksSelected -> 0
            is PreloadDepth.Loaded -> depth.durationMs
            is PreloadDepth.DecoderWarmed -> if (warm) maxOf(depth.durationMs, buffer.minBufferMs) else depth.durationMs
        }
        return PREPARED_ITEM_BYTES + mediaMs.toLong() * REFERENCE_BYTES_PER_SECOND / MS_PER_SECOND
    }
}

/**
 * ADR-0010 rule 11's data-saver rule: no prefetch while the user's Data Saver restricts this app's
 * background data on a metered network, whatever the profile and whatever the policy decided.
 *
 * Both readings are the pair the platform's own guidance checks together. The restriction is read
 * whenever the coordinator recomputes its window. Whether the network is metered is watched through the
 * default network's capabilities, so a handover mid-feed is applied at the change rather than at the
 * next scroll.
 *
 * A network whose capabilities are not reported counts as metered, which is the platform's own
 * reading: `isActiveNetworkMetered` answers true when it cannot tell. That is the right direction here,
 * unlike ADR-0009's "unknown refuses nothing", because the rule only applies once the user has turned
 * Data Saver on. Refusing a prefetch the user did not need to lose costs a slower start, while making
 * one they asked the OS to prevent costs them money.
 *
 * Data Saver switched on or off while the network stays the same is applied at the feed's next input,
 * not at the moment it is switched. Hearing that change needs a broadcast receiver, and nothing here
 * registers one.
 *
 * ref: https://developer.android.com/develop/connectivity/network-ops/data-saver
 * ref: https://developer.android.com/reference/android/net/ConnectivityManager#getRestrictBackgroundStatus()
 */
internal class DataSaverRule(
    context: Context,
    private val onApplicationThread: (() -> Unit) -> Unit,
    private val onChange: () -> Unit,
) {

    private val connectivity: ConnectivityManager? = context.connectivityManager()

    private var metered: Boolean = connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) }.isMetered()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun restrictsPrefetch(): Boolean =
        metered && connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    fun register() {
        val connectivity = connectivity ?: return
        val watching = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val observed = capabilities.isMetered()
                onApplicationThread {
                    if (observed == metered) return@onApplicationThread
                    metered = observed
                    onChange()
                }
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(watching)
            callback = watching
        } catch (_: RuntimeException) {
            // Past the platform's cap of 100 network callbacks per process, for the reason core's
            // `DecisionReapplication` gives: a lost observation rather than a lost app. The metered
            // reading stays what construction saw.
        }
    }

    fun unregister() {
        val registered = callback ?: return
        callback = null
        connectivity?.unregisterNetworkCallback(registered)
    }
}

private fun NetworkCapabilities?.isMetered(): Boolean =
    this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true

/**
 * The share of the app's heap prefetch may hold: a quarter, as a divisor.
 *
 * The rest is for what is on screen. That means the playing players, which `DeviceCapacity.kt` budgets
 * against the same heap, and the app's own views and bitmaps. Prefetch is the only speculative
 * allocation here, so it takes the smaller share: a row the viewer never reaches is memory spent for
 * nothing, and on a small heap it competes with the row they are watching. This is a starting value
 * for `PRD.md` Part 4's tuning phase, not a measured one.
 */
private const val PRELOAD_SHARE_OF_HEAP: Long = 4

/**
 * What a second of prefetched media is costed at: 8 Mbps, in bytes.
 *
 * The admission is made before any rendition is selected, so the cost is estimated rather than read. It
 * is estimated high: Apple's HLS authoring specification tops its 1080p H.264 ladder at 7.8 Mbps, and
 * 1080p is `SHORT_FORM`'s ceiling. Erring high means a small heap holds a row too few rather than a row
 * too many.
 *
 * ref: https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
 */
private const val REFERENCE_BYTES_PER_SECOND: Long = 8_000_000L / 8L

/**
 * What a prefetched source is costed at before any media: 256 KiB.
 *
 * That is a parsed playlist or manifest, its timeline, and the first allocations of its sample queues.
 * Media3 allocates in 64 KiB units (`C.DEFAULT_BUFFER_SEGMENT_SIZE`), and four of them leave room for a
 * feed item's parsed media playlist. It is not zero, so a window of prepared sources still counts: a
 * manifest held for every row of a long feed is memory too.
 */
private const val PREPARED_ITEM_BYTES: Long = 256L * 1024L

/**
 * How many items a low-RAM device, or one that reports no heap, is admitted: the next row.
 *
 * `DeviceCapacity.kt` gives such a device a pool of one for the same reason. The next row is the
 * prefetch a swipe pays back most often, and its first second is about a megabyte at the reference rate.
 * Admitting none would start every row cold on the slowest phones, which is exactly where a cold start
 * is slowest.
 */
private const val CONSTRAINED_DEVICE_ITEMS: Int = 1

private const val MS_PER_SECOND: Long = 1_000L
