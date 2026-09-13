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

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display

/**
 * The one place a device is asked how many players it can afford to have alive at once — and, since
 * #101, the one place it is asked what it can show and decode ([deviceConstraintsOf]).
 *
 * The counterpart of `EngineBinding.kt` and `LifecycleBinding.kt` for [PlayerPool]: the pool itself
 * decides nothing about size, it applies what this file reports. That split is what keeps the bound
 * honest — a constant chosen here would be a constant wherever it lived, and the whole point of a
 * pool bound is that a 2019 budget phone and a current flagship are not the same device.
 *
 * Deliberately not cached. Enumerating the codec list is a platform call that costs real time on a
 * low-end device, and a process-wide cache is the obvious optimisation — but the value would then be
 * fixed by whichever test ran first, and `PlayerPoolCapacityTest` exists to state a different device
 * per test. One enumeration per pool, and a pool is built once per screen, is the trade that keeps
 * the derivation testable. If a screen is ever found building pools often enough for this to show
 * up, the cache belongs there, with a way to reset it, rather than here.
 */

/**
 * How many [SuperPlayer] instances this device can plausibly keep alive at the same time.
 *
 * The smaller of two limits, never below one:
 *
 * - **Decoders.** A device has a fixed number of hardware decoder instances, and the eleventh
 *   `MediaCodec` on a device that offers ten does not fail slowly — it throws, or it silently falls
 *   back to a software decoder that drops frames. [decoderInstanceCapacity] asks the platform's own
 *   codec list rather than guessing.
 * - **Memory.** A player that has a decoder also has Media3's load-control allocation, which is
 *   Java heap and which the decoder count knows nothing about. [memoryCapacity] budgets that against
 *   the heap this app is actually allowed.
 *
 * The minimum of the two rather than either alone, because they fail differently and a pool has to
 * survive both: a cheap device with a generous codec table still runs out of heap, and a
 * memory-rich device still has the decoder count its silicon has.
 *
 * The floor of one is the answer to a device that reports nothing useful — an emulator with no codec
 * table, a low-RAM device, a heap too small to hold even one player. Reporting zero would make the
 * pool
 * hand back nothing at all and a feed show no video anywhere, which is a worse answer than one
 * player being recycled hard.
 */
internal fun concurrentPlayerCapacityOf(context: Context): Int =
    minOf(decoderInstanceCapacity(), memoryCapacity(context)).coerceAtLeast(MINIMUM_CAPACITY)

/**
 * The number of concurrent decoder instances the platform reports for the video codecs a feed
 * actually uses.
 *
 * `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances` is the platform's own answer to
 * "how many of these can I have at once", populated from the device's codec configuration.
 *
 * Two reductions, and the direction of each matters. Across the *decoders for one MIME type* the
 * answer is the **largest**, because a device that ships a hardware decoder reporting 16 and a
 * software fallback reporting 1 can run 16 — the fallback is not a ceiling on the hardware. Across
 * *MIME types* the answer is the **smallest**, because a pool does not know what a feed will contain
 * and a bound that only holds for H.264 is not a bound.
 *
 * Only H.264 and HEVC are consulted. They are what feed content is encoded in; folding in every
 * video MIME type a device declares would let some rarely-implemented format nobody is going to
 * play — reported by a stub decoder with a limit of one — set the bound for everything.
 *
 * A device that reports no usable video decoder at all falls to [MINIMUM_CAPACITY]. That is not a
 * theoretical case: it is what an emulator image with no codec table looks like, and the honest
 * reading of "I cannot tell you" is one, not many.
 *
 * ref: https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances()
 */
private fun decoderInstanceCapacity(): Int {
    val codecs = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    } catch (e: RuntimeException) {
        // A malformed `media_codecs.xml` makes the platform throw from here, and it is the OEM's
        // file rather than anything an app can fix. A pool that crashed on such a device would be
        // strictly worse than one that ran a single player on it.
        return MINIMUM_CAPACITY
    }

    val bestPerMimeType = mutableMapOf<String, Int>()
    for (codec in codecs) {
        if (codec.isEncoder) continue
        for (mimeType in codec.supportedTypes) {
            // Normalised once, and used as the key as well as for the test. Keying on the raw string
            // would undo the max-per-format reduction on exactly the devices the normalisation is
            // here for: a phone reporting `video/avc` from its hardware decoder and `VIDEO/AVC` from
            // the software fallback would get two entries, and the `minOrNull` below would then take
            // the fallback's limit of one as the bound for the whole feed.
            val format = mimeType.lowercase()
            if (format !in FEED_VIDEO_MIME_TYPES) continue
            val instances = try {
                codec.getCapabilitiesForType(mimeType).maxSupportedInstances
            } catch (e: IllegalArgumentException) {
                // Declared in `supportedTypes` but not answerable — a device inconsistency, and one
                // this codec simply does not get a vote on.
                continue
            }
            if (instances <= 0) continue
            bestPerMimeType[format] = maxOf(bestPerMimeType[format] ?: 0, instances)
        }
    }

    return bestPerMimeType.values.minOrNull() ?: MINIMUM_CAPACITY
}

/**
 * How many players this app's heap affords, at [HEAP_BYTES_PER_PLAYER] each.
 *
 * The *app's* heap, not the device's memory, and that correction is what makes this term mean
 * anything. Media3's `DefaultLoadControl` buffers samples in Java `byte[]` allocations, so a pool's
 * players compete for the process heap that [ActivityManager.getMemoryClass] caps — commonly
 * 96–256 MB — rather than for the device's RAM. Deriving from `MemoryInfo.totalMem` is what this
 * did first, and it was wrong in the direction that matters: on a 2 GB phone it returned eight
 * players against a heap that could not hold two, so the term either failed to bind at all or bound
 * at a number the process could not honour.
 *
 * [ActivityManager.getLargeMemoryClass] is used instead when the app has actually declared
 * `android:largeHeap`, read from its own manifest flags rather than assumed — an app that has not
 * declared it does not get that heap however large the device's.
 *
 * [ActivityManager.isLowRamDevice] short-circuits to one. It is the platform's own flag for a device
 * whose manufacturer declared it memory-constrained, and Android's guidance is to treat it as an
 * instruction rather than a hint.
 *
 * ref: https://developer.android.com/topic/performance/memory-overview
 */
private fun memoryCapacity(context: Context): Int {
    val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MINIMUM_CAPACITY

    if (activityManager.isLowRamDevice) return MINIMUM_CAPACITY

    val heapBytes = heapBudgetBytesOf(context) ?: return MINIMUM_CAPACITY

    return (heapBytes / HEAP_BYTES_PER_PLAYER).toInt()
}

/**
 * The heap this app is allowed, in bytes, or null when the platform does not say.
 *
 * The one reading of the app's heap budget in the library: [PlaybackConditions.heapBudgetBytes] is
 * this number, and the pool's memory capacity above is this number divided by a per-player budget,
 * so the two cannot disagree about how much memory there is. A budget rather than a free-heap
 * reading, because free heap under a garbage collector is noise.
 *
 * Null rather than zero for an environment that does not implement the reading, for the reason the
 * pool gives: "I do not know" is not "none", and a policy handed a zero would size a buffer for a
 * device with no memory.
 *
 * ref: https://developer.android.com/reference/android/app/ActivityManager#getMemoryClass()
 */
internal fun heapBudgetBytesOf(context: Context): Long? {
    val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null

    val declaresLargeHeap =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
    val heapMegabytes =
        if (declaresLargeHeap) activityManager.largeMemoryClass else activityManager.memoryClass
    if (heapMegabytes <= 0) return null

    return heapMegabytes.toLong() * BYTES_PER_MEGABYTE
}

/**
 * What one live player is budgeted at, against the app's heap.
 *
 * A per-player budget rather than a pool size: this number does not say how many players a device
 * gets, it says what one costs, and the app's own heap decides the rest. That distinction is the
 * difference between this and the hardcoded pool size a bound exists to replace.
 *
 * Grounded in Media3's own allocation floor rather than chosen. `DefaultLoadControl`'s
 * `DEFAULT_MIN_BUFFER_SIZE` is 12.5 MiB — the smallest target buffer it holds for a stream — and a
 * feed's players sit near that floor rather than near the 137.6 MiB `DEFAULT_MUXED_BUFFER_SIZE` it
 * targets for a long watch, because a short-form item buffers seconds rather than the fifty that
 * target is sized for. Doubling the floor covers the decoder-adjacent heap and the row's own bitmap
 * alongside it.
 *
 * The effect is a bound the decoder count usually wins anyway: a 96 MB heap gives 3, a 256 MB heap
 * gives 8, and a large-heap app on a flagship gives more than any device has decoders for. Where it
 * binds is exactly where it should — the cheap phone shipping a flagship's codec table.
 *
 * ref: https://developer.android.com/reference/android/app/ActivityManager#getMemoryClass()
 */
private const val HEAP_BYTES_PER_PLAYER: Long = 32L * 1024L * 1024L

private const val BYTES_PER_MEGABYTE: Long = 1024L * 1024L

/** The floor, and the answer wherever a device reports nothing usable. */
private const val MINIMUM_CAPACITY: Int = 1

/**
 * The video codecs a bound is derived from. Lowercased, because `supportedTypes` is not
 * case-normalised across devices.
 */
private val FEED_VIDEO_MIME_TYPES: Set<String> = setOf(
    MediaFormat.MIMETYPE_VIDEO_AVC,
    MediaFormat.MIMETYPE_VIDEO_HEVC,
)

/**
 * What the device can show and decode, read once and handed to a track selector as a constraint.
 *
 * A *constraint* and not a [PlaybackConditions] observation, which is ADR-0009 rule 2's line: on a
 * phone the display and the decoder table do not change under a playing session, so the selector
 * reads them when it is built and a policy is never consulted about them. The day they can change
 * — an external display on a television — is Phase 8's, and it turns these into observations then.
 *
 * Every field is *unknown* rather than *none* where the platform does not answer, and an unknown
 * constrains nothing. That direction is load-bearing: Robolectric's default device reports an
 * empty codec table and a small display, and a gate that read "no decoder declared" as "nothing
 * decodable" would exclude every rung of every ladder under test and fall back to the bottom one,
 * which looks like a selection and is not. `MediaCodecVideoRenderer` still refuses a format the
 * device really cannot decode; this constraint only stops the selector *asking* for one.
 *
 * Nothing here reads a model string. `Build.MODEL` and its siblings are how a device-specific table
 * gets written, and a table by model is wrong on the next device the table has not met.
 */
internal class DeviceConstraints(
    /**
     * The shorter edge of the largest display mode, in physical pixels, or null when unknown.
     *
     * The shorter edge, because a rendition is judged by whether the display can show it at full
     * resolution in *some* orientation: a 1080 × 2400 phone shows a 1920 × 1080 rung edge to edge
     * in landscape, and what disqualifies a 2160p rung on it is that its own short edge is longer
     * than the display's.
     */
    val displayShortEdgePx: Int?,

    /**
     * The HDR types the display reports (`Display.HdrCapabilities.HDR_TYPE_*`), or null when the
     * platform does not say. An empty set is a display that answered and supports none.
     */
    val displayHdrTypes: Set<Int>?,

    /**
     * For each video MIME type (lowercased) a decoder is declared for, the profile and level pairs
     * it declares. A MIME type with no entry is *unknown*; an entry with an empty list is a decoder
     * that declared no profiles, which is also unknown.
     */
    val decodableProfileLevels: Map<String, List<ProfileLevel>>,
) {
    /** One `MediaCodecInfo.CodecProfileLevel`, as values: [profile] and [level] are its constants. */
    internal data class ProfileLevel(val profile: Int, val level: Int)

    /**
     * Whether a decoder for [mimeType] declares [profile] at [level] or above, or null when the
     * device says nothing about that MIME type.
     *
     * The comparison is the platform's own: equal profile, and a declared level at or above the
     * one asked for. Levels are declared as ascending flags for every codec Android names, so the
     * numeric comparison is the ordering.
     *
     * ref: https://developer.android.com/reference/android/media/MediaCodecInfo.CodecProfileLevel
     */
    fun canDecode(mimeType: String, profile: Int, level: Int): Boolean? {
        val declared = decodableProfileLevels[mimeType.lowercase()]?.takeIf { it.isNotEmpty() } ?: return null
        return declared.any { it.profile == profile && it.level >= level }
    }

    internal companion object {
        /** A device that answered nothing: constrains nothing. */
        val UNKNOWN: DeviceConstraints = DeviceConstraints(
            displayShortEdgePx = null,
            displayHdrTypes = null,
            decodableProfileLevels = emptyMap(),
        )
    }
}

/**
 * The device's constraints, read from the platform now: the default display's modes and HDR
 * capabilities through `DisplayManager`, and the video decoders' profile levels through the same
 * `MediaCodecList` [concurrentPlayerCapacityOf] reads.
 *
 * Read on demand rather than cached, for the reason the file's KDoc gives about the pool: a test
 * states a different device per test.
 *
 * ref: https://developer.android.com/reference/android/view/Display#getSupportedModes()
 * ref: https://developer.android.com/reference/android/view/Display#getHdrCapabilities()
 */
internal fun deviceConstraintsOf(context: Context): DeviceConstraints {
    val display = try {
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
    } catch (e: RuntimeException) {
        null
    }
    val shortEdgePx = display?.supportedModes
        ?.map { minOf(it.physicalWidth, it.physicalHeight) }
        ?.filter { it > 0 }
        ?.maxOrNull()
    val hdrTypes = display?.let { readHdrTypes(it) }
    return DeviceConstraints(
        displayShortEdgePx = shortEdgePx,
        displayHdrTypes = hdrTypes,
        decodableProfileLevels = decodableProfileLevels(),
    )
}

private fun readHdrTypes(display: Display): Set<Int>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
    val capabilities = try {
        display.hdrCapabilities
    } catch (e: RuntimeException) {
        return null
    } ?: return null
    return capabilities.supportedHdrTypes.toSet()
}

/**
 * The profile levels every declared video decoder reports, by MIME type.
 *
 * Every decoder's entries for a MIME type are pooled rather than kept per decoder: what a selector
 * asks is whether *some* decoder on the device takes the rung, and the platform picks which. The
 * same `media_codecs.xml` failure mode as [decoderInstanceCapacity] is caught the same way, and
 * answers "unknown" rather than "none".
 */
private fun decodableProfileLevels(): Map<String, List<DeviceConstraints.ProfileLevel>> {
    val codecs = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    } catch (e: RuntimeException) {
        return emptyMap()
    }
    val pooled = mutableMapOf<String, MutableList<DeviceConstraints.ProfileLevel>>()
    for (codec in codecs) {
        if (codec.isEncoder) continue
        for (mimeType in codec.supportedTypes) {
            val format = mimeType.lowercase()
            if (!format.startsWith(VIDEO_MIME_PREFIX)) continue
            val levels = try {
                codec.getCapabilitiesForType(mimeType).profileLevels
            } catch (e: IllegalArgumentException) {
                continue
            } ?: continue
            val declared = pooled.getOrPut(format) { mutableListOf() }
            // A profile of zero is no profile: it is what an entry constructed and never filled
            // reports, and it names nothing a rung could ask for.
            levels.filter { it.profile > 0 }.mapTo(declared) { DeviceConstraints.ProfileLevel(it.profile, it.level) }
        }
    }
    return pooled
}

private const val VIDEO_MIME_PREFIX = "video/"
