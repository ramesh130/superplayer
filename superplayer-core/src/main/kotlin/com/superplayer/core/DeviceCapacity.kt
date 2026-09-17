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
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
 *   back to a software decoder that drops frames. [DecoderTable.instanceCapacityFor] asks the
 *   platform's own codec list rather than guessing.
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
 *
 * [feedCodecs] are the codecs the feed's content is in, which only the consumer knows; the decoder
 * term is read over those alone.
 *
 * [protectedPlayback] is whether the pool's players were built with `PlayerPool.Builder.setDrm`, and
 * where they were the decoder term is the *secure* decoder's limit wherever the device declared one.
 * That is the number such a feed runs out of: a device commonly declares several ordinary video
 * decoders and exactly one secure one, so a pool of protected players bounded by the ordinary limit
 * finds the second decoder missing partway down a scroll (ADR-0012 rule 12). A device that declared no
 * secure decoder is bounded exactly as a clear feed is — [DecoderTable.instanceCapacityFor] argues
 * why, and Widevine L3 is the case it is arguing about.
 */
internal fun concurrentPlayerCapacityOf(
    context: Context,
    feedCodecs: Set<VideoCodec>,
    protectedPlayback: Boolean,
): Int =
    minOf(readDecoderTable().instanceCapacityFor(feedCodecs, protectedPlayback), memoryCapacity(context))
        .coerceAtLeast(MINIMUM_CAPACITY)

/**
 * What one walk of the platform's decoder list reports: the pool's decoder bound and the selector's
 * profile levels, over every decoder and over the secure ones on their own.
 *
 * One walk producing all of it rather than one walk per reading, so that this file being the one
 * place the device is asked holds in the strong sense: `MediaCodecList` is constructed in exactly one
 * function, and the pool and the selector cannot read the table two different ways. Each caller
 * still walks it once per build — a pool's, or a selector's — for the reason the file's KDoc gives
 * against a cache.
 */
private class DecoderTable(
    /**
     * What every declared video decoder reports, the secure ones **included**, pooled by MIME type as
     * it always was — not "the plain decoders". The un-merge is one-sided deliberately: what a player
     * with no protection asks is whether *something* on the device takes the rung, and a secure
     * decoder is something, so narrowing this half would refuse rungs the renderer plays.
     */
    val all: Tally,
    /**
     * What the decoders declaring `FEATURE_SecurePlayback` report, on their own.
     *
     * A *second* tally rather than a filter over [all], because a device's secure decoder is
     * conventionally its plain decoder's name with `.secure` appended over the **same MIME type** —
     * so the pooling [readDecoderTable] argues for merges the two, and a protected player asking
     * [all] would be told what the plain sibling can do. The whole of ADR-0012 rule 12's reader is
     * that these two are asked apart.
     */
    val secure: Tally,
    /** [DeviceConstraints.secureDecodableMimeTypes]; null when the walk found no video decoder at all. */
    val secureMimeTypes: Set<String>?,
) {
    /** One kind of decoder's readings, keyed by video MIME type (lowercased). */
    class Tally(
        /**
         * For each MIME type, the largest instance limit any of its decoders of this kind reports; a
         * MIME type with no positive report has no entry.
         */
        val instancesPerMimeType: Map<String, Int>,
        /** [DeviceConstraints.decodableProfileLevels], pooled by MIME type as [readDecoderTable] argues. */
        val profileLevels: Map<String, List<DeviceConstraints.ProfileLevel>>,
    )

    /**
     * [concurrentPlayerCapacityOf]'s decoder term for a feed in [codecs]: the smallest of their
     * limits, with a codec the device reports nothing for left out — or [MINIMUM_CAPACITY] when that
     * leaves nothing.
     *
     * On [protectedPlayback] a codec's limit is its *secure* decoder's where the device declared one,
     * and otherwise the ordinary reading. The fallback is the same direction every unknown here reads
     * in, and it is not hypothetical: a Widevine **L3** device plays protected content on ordinary
     * decoders and declares no `FEATURE_SecurePlayback` at all, so reading a missing secure entry as a
     * limit of one would take a whole class of common devices from the bound they can honour down to a
     * single player. A device that really does run one secure decoder says so, and that is the number
     * taken.
     */
    fun instanceCapacityFor(codecs: Set<VideoCodec>, protectedPlayback: Boolean): Int {
        val preferred = if (protectedPlayback) secure.instancesPerMimeType else emptyMap()
        return codecs
            .mapNotNull { preferred[it.mimeType] ?: all.instancesPerMimeType[it.mimeType] }
            .minOrNull() ?: MINIMUM_CAPACITY
    }
}

/**
 * The decoder list, walked once for both of [DecoderTable]'s values.
 *
 * **Instance capacity** is the number of concurrent decoder instances the platform reports for the
 * video codecs a feed actually uses. `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances` is
 * the platform's own answer to "how many of these can I have at once", populated from the device's
 * codec configuration.
 *
 * Two reductions, and the direction of each matters. Across the *decoders for one MIME type* the
 * answer is the **largest**, because a device that ships a hardware decoder reporting 16 and a
 * software fallback reporting 1 can run 16 — the fallback is not a ceiling on the hardware. That one
 * is taken here, for every video MIME type. Across *codecs* the answer is the **smallest**, because a
 * bound that holds for H.264 is no bound for a feed that also plays AV1 — and that one is taken by
 * [DecoderTable.instanceCapacityFor], over the codecs the consumer declared the feed to contain.
 *
 * Declared, not every codec the device has. Which codecs a feed is encoded in is a fact about the
 * content, not the device — H.264 and HEVC for most, AV1 or VP9 for a growing share of short-form —
 * and folding in every video MIME type a device declares would let a codec the feed never touches,
 * reported by a stub decoder with a limit of one, set the bound for everything.
 *
 * A declared codec the device reports no decoder for is left out rather than read as a limit of
 * zero, which is the direction [DeviceConstraints] reads an unknown in: the renderer still refuses
 * what cannot be decoded, and one missing codec is no reason to make every other one's feed a pool of
 * one. A device that reports no usable decoder for *any* declared codec falls to [MINIMUM_CAPACITY].
 * That is not a theoretical case: it is what an emulator image with no codec table looks like, and
 * the honest reading of "I cannot tell you" is one, not many.
 *
 * **Profile levels** are every declared video decoder's, pooled by MIME type rather than kept per
 * decoder: what a selector asks is whether *some* decoder on the device takes the rung, and the
 * platform picks which.
 *
 * **Both readings are kept twice**: once over every decoder, and once over the decoders declaring
 * `FEATURE_SecurePlayback` alone. A secure decoder shares its plain sibling's MIME type, so the
 * pooling above merges them, and a protected player reading the merged answer would be told what the
 * *plain* decoder can sustain — which is exactly the reading ADR-0012 rule 12 exists to correct, and
 * the one `PRD.md` §3.1 blames for F4's frame drops. Two tallies on the one walk rather than a second
 * walk, because the platform caches its codec list on first read and a second walk is a second chance
 * to disagree with the first.
 *
 * ref: https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances()
 * ref: https://developer.android.com/reference/android/media/MediaCodecInfo.CodecProfileLevel
 */
private fun readDecoderTable(): DecoderTable {
    val codecs = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    } catch (e: RuntimeException) {
        // A malformed `media_codecs.xml` makes the platform throw from here, and it is the OEM's
        // file rather than anything an app can fix. A pool that crashed on such a device would be
        // strictly worse than one that ran a single player on it, and the selector reads the same
        // failure as "unknown" rather than "none".
        return DecoderTable(all = emptyTally(), secure = emptyTally(), secureMimeTypes = null)
    }

    val bestPerMimeType = mutableMapOf<String, Int>()
    val pooled = mutableMapOf<String, MutableList<DeviceConstraints.ProfileLevel>>()
    // The same two readings over the secure decoders alone, for the reason this function's KDoc gives.
    val bestSecurePerMimeType = mutableMapOf<String, Int>()
    val pooledSecure = mutableMapOf<String, MutableList<DeviceConstraints.ProfileLevel>>()
    // Every video MIME type a decoder was seen for, and the subset of those a *secure* decoder was
    // seen for. The first exists only so that "no secure decoder" can be told from "no decoder table
    // at all" below: those are the two readings [DeviceConstraints] must never confuse.
    val videoMimeTypes = mutableSetOf<String>()
    val secureMimeTypes = mutableSetOf<String>()
    for (codec in codecs) {
        if (codec.isEncoder) continue
        for (mimeType in codec.supportedTypes) {
            // Normalised once, and used as the key as well as for the test. Keying on the raw string
            // would undo the max-per-format reduction on exactly the devices the normalisation is
            // here for: a phone reporting `video/avc` from its hardware decoder and `VIDEO/AVC` from
            // the software fallback would get two entries, and the minimum across codecs would then
            // take the fallback's limit of one as the bound for the whole feed.
            val format = mimeType.lowercase()
            if (!format.startsWith(VIDEO_MIME_PREFIX)) continue
            val capabilities = try {
                codec.getCapabilitiesForType(mimeType)
            } catch (e: IllegalArgumentException) {
                // Declared in `supportedTypes` but not answerable — a device inconsistency, and one
                // this codec simply does not get a vote on.
                continue
            }

            videoMimeTypes += format
            // ref: `MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback` (`secure-playback`) is
            // the feature a decoder able to operate on protected memory declares, and it is the only
            // honest answer to "can this device play an L1 licence's keys" — a name ending `.secure`
            // is a convention rather than a guarantee, so the feature is read and the name is not.
            // https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_SecurePlayback
            val isSecure = capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
            if (isSecure) secureMimeTypes += format

            val instances = capabilities.maxSupportedInstances
            if (instances > 0) {
                bestPerMimeType[format] = maxOf(bestPerMimeType[format] ?: 0, instances)
                if (isSecure) bestSecurePerMimeType[format] = maxOf(bestSecurePerMimeType[format] ?: 0, instances)
            }

            val levels = capabilities.profileLevels ?: continue
            // A profile of zero is no profile: it is what an entry constructed and never filled
            // reports, and it names nothing a rung could ask for.
            val declared = levels.filter { it.profile > 0 }.map { DeviceConstraints.ProfileLevel(it.profile, it.level) }
            pooled.getOrPut(format) { mutableListOf() }.addAll(declared)
            if (isSecure) pooledSecure.getOrPut(format) { mutableListOf() }.addAll(declared)
        }
    }

    return DecoderTable(
        all = DecoderTable.Tally(bestPerMimeType, pooled),
        secure = DecoderTable.Tally(bestSecurePerMimeType, pooledSecure),
        // Null rather than empty where no video decoder was declared at all: an empty set is a device
        // that answered and has none, which is a fact a session may be refused over, and a device
        // that answered nothing must refuse nothing (the [DeviceConstraints] KDoc's direction).
        secureMimeTypes = if (videoMimeTypes.isEmpty()) null else secureMimeTypes,
    )
}

/** What a device that answered nothing reports, for either kind of decoder. */
private fun emptyTally(): DecoderTable.Tally = DecoderTable.Tally(emptyMap(), emptyMap())

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

    if (isLowRamDeviceOf(context)) return MINIMUM_CAPACITY

    val heapBytes = heapBudgetBytesOf(context) ?: return MINIMUM_CAPACITY

    return (heapBytes / HEAP_BYTES_PER_PLAYER).toInt()
}

/**
 * Whether the manufacturer declared this device memory-constrained. Read here, beside the heap budget,
 * so the pool and a preload coordinator's memory guard (ADR-0010 rules 8 and 11) share one reading of
 * the device rather than each making their own.
 *
 * ref: https://developer.android.com/reference/android/app/ActivityManager#isLowRamDevice()
 */
internal fun isLowRamDeviceOf(context: Context): Boolean =
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

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
 * The codecs a pool's bound is derived from when its consumer declares none: H.264 and HEVC, which
 * is what every pool was bounded by before a feed could say otherwise (#143), and what most feeds
 * are still delivered in.
 */
internal val DEFAULT_FEED_CODECS: Set<VideoCodec> = setOf(VideoCodec.H264, VideoCodec.HEVC)

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

    /**
     * The video MIME types a decoder able to operate on **protected memory** is declared for, or null
     * where the device declared no video decoder at all.
     *
     * ADR-0012 rule 12's reading, and here rather than in `superplayer-drm` for the reason that rule
     * gives: it does not change under a playing session, so it is a *constraint* and not a
     * [PlaybackConditions] observation, and the decoder list is walked in exactly one place
     * ([readDecoderTable]) because the platform caches it on first read.
     *
     * Null and empty are different answers and the difference is load-bearing here as everywhere else
     * in this class: an empty set is a device that listed its decoders and has no secure one — which
     * is what makes an L1 licence unusable on it — while null is a device that listed nothing, and
     * nothing is refused over an unknown.
     *
     * Defaulted — as [secureDecodableProfileLevels] is, to its own empty-is-unknown value — so that a
     * caller which has nothing to say about protection says nothing rather than being made to write it
     * down. That is exactly the reading it wants: a test about track selection states a display and a
     * decoder table, and the absence of a secure decoder in it is not a statement that the device has
     * none.
     */
    val secureDecodableMimeTypes: Set<String>? = null,

    /**
     * [decodableProfileLevels] over the decoders declaring `FEATURE_SecurePlayback` alone — what a
     * **protected** player's rungs are judged against (ADR-0012 rule 12, `PRD.md` §3.1).
     *
     * A separate map rather than a flag on the other one because a secure decoder shares its plain
     * sibling's MIME type: `readDecoderTable` pools every decoder of a MIME type together, so the
     * merged answer is what *something* on the device reaches, and on a device whose secure decoder
     * stops at 1080p while its plain one reaches 4K that answer is the wrong one for a protected
     * player. It is the variant `PRD.md` §3.1 says the secure decoder cannot sustain, selected
     * because the plain decoder could.
     *
     * Unknown is read the way every other field here reads it, and here it is what keeps Robolectric's
     * empty codec table from refusing every rung of every protected ladder: a MIME type with no entry,
     * or an entry with an empty list, refuses nothing.
     */
    val secureDecodableProfileLevels: Map<String, List<ProfileLevel>> = emptyMap(),
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
    fun canDecode(mimeType: String, profile: Int, level: Int): Boolean? =
        reaches(decodableProfileLevels, mimeType, profile, level)

    /**
     * [canDecode] asked of the **secure** decoders alone: whether one able to operate on protected
     * memory declares [profile] at [level] or above, or null where the device says nothing about that
     * MIME type's secure decoders.
     *
     * The question a *protected* player's track selection asks in place of [canDecode], and the whole
     * of ADR-0012 rule 12's reader. A player with no `setDrm` asks [canDecode], because refusing
     * clear content on a secure decoder's limits would be the same defect in the other direction.
     */
    fun canDecodeProtected(mimeType: String, profile: Int, level: Int): Boolean? =
        reaches(secureDecodableProfileLevels, mimeType, profile, level)

    private fun reaches(
        declaredPerMimeType: Map<String, List<ProfileLevel>>,
        mimeType: String,
        profile: Int,
        level: Int,
    ): Boolean? {
        val declared = declaredPerMimeType[mimeType.lowercase()]?.takeIf { it.isNotEmpty() } ?: return null
        return declared.any { it.profile == profile && it.level >= level }
    }

    /**
     * Whether this device can decode *anything* on protected memory, or null where it said nothing.
     *
     * Asked of the device rather than of a MIME type, because the question it answers is asked before
     * any content is known: a licence acquired at `L1` may only be used by a secure decoder
     * (// ref: `MediaDrm.requiresSecureDecoderComponent`), so a device with none cannot use one at
     * all, whatever the stream turns out to be encoded in.
     */
    fun hasSecureVideoDecoder(): Boolean? = secureDecodableMimeTypes?.isNotEmpty()

    internal companion object {
        /** A device that answered nothing: constrains nothing. */
        val UNKNOWN: DeviceConstraints = DeviceConstraints(
            displayShortEdgePx = null,
            displayHdrTypes = null,
            decodableProfileLevels = emptyMap(),
            secureDecodableMimeTypes = null,
            secureDecodableProfileLevels = emptyMap(),
        )
    }
}

/**
 * The device's constraints, read from the platform now: the default display's modes and HDR
 * capabilities through `DisplayManager`, and the video decoders' profile levels through the same
 * walk of the decoder list [concurrentPlayerCapacityOf] reads, [readDecoderTable].
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
    // One walk for both readings, because the platform caches the codec list on first read and a
    // second walk would be a second chance to disagree with the first.
    val decoders = readDecoderTable()
    return DeviceConstraints(
        displayShortEdgePx = shortEdgePx,
        displayHdrTypes = hdrTypes,
        decodableProfileLevels = decoders.all.profileLevels,
        secureDecodableMimeTypes = decoders.secureMimeTypes,
        secureDecodableProfileLevels = decoders.secure.profileLevels,
    )
}

private fun readHdrTypes(display: Display): Set<Int>? {
    val capabilities = try {
        display.hdrCapabilities
    } catch (e: RuntimeException) {
        return null
    } ?: return null
    return capabilities.supportedHdrTypes.toSet()
}

private const val VIDEO_MIME_PREFIX = "video/"
