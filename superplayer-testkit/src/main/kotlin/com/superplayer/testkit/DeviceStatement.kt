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

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * What the device under test reports about itself: its display, its decoders, its heap.
 *
 * A track selector that gates on the display and on the decoder table, and a pool whose bound is
 * derived from decoder instances and heap, are running on a device whether a test says so or not —
 * and Robolectric's default one reports a small display, an empty codec table and a 16 MB heap.
 * Each of those is a real case worth pinning once, and a silent trap for every other test: a small
 * display would truncate every ladder to its bottom rung before any estimate was consulted, which
 * looks like a selection and is not. So the device is *stated*, in the test, where a reader can see
 * it — the same move as granting `WAKE_LOCK`, and `docs/testing.md`'s *Stating the device*.
 *
 * Android types only in these signatures, and no Media3 type, for the reason the rest of this
 * module's API gives: a `Format` here would put Media3's opt-in marker on every test that named it.
 *
 * Declarations are read by SuperPlayer when a player is *built* (ADR-0009 rule 2: display and
 * decoder are constraints read once, not observations), so a test states its device before
 * `buildPlayer`, never after.
 */
public object DeviceStatement {

    /** A television-sized default display: 4K, so nothing under 2160p is ever refused by accident. */
    public const val DEFAULT_DISPLAY_WIDTH_PX: Int = 3840

    public const val DEFAULT_DISPLAY_HEIGHT_PX: Int = 2160

    /**
     * The default display's one mode: [widthPx] by [heightPx] physical pixels, at a density where a
     * pixel is a pixel.
     *
     * Two declarations, because Robolectric keeps them apart. The size the *configuration* reports
     * is declared through resource qualifiers, in density-independent pixels at `mdpi` where the
     * two are equal; the *mode* `Display.getSupportedModes` reports — which is what SuperPlayer
     * reads, because a mode is a physical size and a configuration is a logical one — is declared
     * separately, and Robolectric's qualifier change leaves it empty. `Display.Mode` has no public
     * constructor, so the mode is built through Robolectric's own reflection helper: the one place
     * in this module a hidden platform constructor is named, and it is here rather than in a test
     * so that it is named once.
     */
    @JvmStatic
    public fun declareDisplay(widthPx: Int, heightPx: Int) {
        require(widthPx > 0 && heightPx > 0) { "A display needs a positive size, was $widthPx × $heightPx" }
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "+w${widthPx}dp-h${heightPx}dp-mdpi")
        val mode: Display.Mode = ReflectionHelpers.callConstructor(
            Display.Mode::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, DECLARED_MODE_ID),
            ClassParameter.from(Int::class.javaPrimitiveType, widthPx),
            ClassParameter.from(Int::class.javaPrimitiveType, heightPx),
            ClassParameter.from(Float::class.javaPrimitiveType, DECLARED_REFRESH_RATE_HZ),
        )
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, mode)
    }

    /**
     * The HDR types the default display supports: `Display.HdrCapabilities.HDR_TYPE_*` constants.
     * None declared is a display that answers with an empty list, which is a display that shows
     * SDR only — not one that has not answered.
     */
    @JvmStatic
    public fun declareDisplayHdrTypes(vararg hdrTypes: Int) {
        val display = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY)) { "No default display" }
        shadowOf(display).setDisplayHdrCapabilities(
            Display.DEFAULT_DISPLAY,
            REFERENCE_MAX_LUMINANCE,
            REFERENCE_MAX_AVERAGE_LUMINANCE,
            REFERENCE_MIN_LUMINANCE,
            *hdrTypes,
        )
    }

    /**
     * Puts a video decoder for [mimeType] into the device's codec list, declaring [profileLevels]
     * as `(profile, level)` pairs of `MediaCodecInfo.CodecProfileLevel` constants — the highest of
     * each profile is what the platform reports and what a selector compares a rung against.
     *
     * No pairs is a decoder that declares no profiles, which SuperPlayer reads as *unknown* rather
     * than as *nothing*: a ladder played on it is gated on nothing.
     *
     * Robolectric's codec builder exists from API 29, which is why the declaration says so; the
     * pinned test runtime is above it, and a test that lowers its level cannot state a decoder.
     *
     * Declare before the test's first player is built, and not only before the one it is about:
     * the platform caches its codec list on first read, so a decoder declared after any player has
     * read it is one no later player in the same test sees.
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun declareVideoDecoder(mimeType: String, vararg profileLevels: Pair<Int, Int>) {
        addVideoDecoder(mimeType, maxSupportedInstances = null, profileLevels)
    }

    /**
     * As [declareVideoDecoder], and reporting [maxSupportedInstances] from
     * `CodecCapabilities.getMaxSupportedInstances` — how many of this decoder the device runs at once,
     * which is what a pool's bound, and so a coordinator's warm decoders, are derived from.
     *
     * Robolectric's codec builder has no setter for it and answers 32 without one, so the value is
     * written into the built capabilities through Robolectric's reflection helper — the second hidden
     * platform member this module names, here for the reason [declareDisplay] names the first.
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun declareVideoDecoder(mimeType: String, maxSupportedInstances: Int, vararg profileLevels: Pair<Int, Int>) {
        require(maxSupportedInstances > 0) { "A decoder runs at least one instance, not $maxSupportedInstances" }
        addVideoDecoder(mimeType, maxSupportedInstances, profileLevels)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun addVideoDecoder(mimeType: String, maxSupportedInstances: Int?, profileLevels: Array<out Pair<Int, Int>>) {
        val declared = profileLevels.map { (profile, level) ->
            MediaCodecInfo.CodecProfileLevel().also {
                it.profile = profile
                it.level = level
            }
        }
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat().apply { setString(MediaFormat.KEY_MIME, mimeType) })
            .setIsEncoder(false)
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
            .setProfileLevels(declared.toTypedArray())
            .build()
        // After `build`, which writes Robolectric's own 32 into the same field: the one the platform's
        // `getMaxSupportedInstances` returns, filled from `max-concurrent-instances` on a device.
        // ref: https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances()
        // ref: https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/media/java/android/media/MediaCodecInfo.java
        maxSupportedInstances?.let { ReflectionHelpers.setField(capabilities, "mMaxSupportedInstances", it) }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("test.decoder.${mimeType.substringAfterLast('/')}.${declaredDecoders++}")
                .setIsEncoder(false)
                .setCapabilities(capabilities)
                .build(),
        )
    }

    /**
     * The heap `ActivityManager.getMemoryClass` reports — the app's, not the device's RAM.
     *
     * That is the reading `DeviceCapacity.kt` derives from, because Media3 buffers samples on the
     * Java heap: a phone with 8 GB of RAM still gives one app a 256 MB heap to hold players in.
     * Robolectric's default is 16 MB, which divides into no players at all.
     */
    @JvmStatic
    public fun declareAppHeap(megabytes: Int) {
        shadowOf(activityManager).setMemoryClass(megabytes)
    }

    /**
     * A device whose manufacturer declared it memory-constrained: `ActivityManager.isLowRamDevice`
     * answers true. SuperPlayer treats the flag as the platform's instruction rather than as a hint,
     * whatever heap [declareAppHeap] states beside it.
     */
    @JvmStatic
    public fun declareLowRamDevice() {
        shadowOf(activityManager).setIsLowRamDevice(true)
    }

    /**
     * The user has turned Data Saver on and not exempted this app:
     * `ConnectivityManager.getRestrictBackgroundStatus` answers `RESTRICT_BACKGROUND_STATUS_ENABLED`.
     *
     * A setting, not a network. Whether the platform applies it depends on whether the network in use
     * is metered, which a `ThroughputTrace`'s transport says as the harness replays it.
     *
     * ref: https://developer.android.com/develop/connectivity/network-ops/data-saver
     */
    @JvmStatic
    public fun declareDataSaverOn() {
        shadowOf(connectivityManager).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
    }

    private var declaredDecoders = 0

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val activityManager: ActivityManager
        get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val displayManager: DisplayManager
        get() = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    // Luminance figures for the declared HDR capabilities. SuperPlayer reads the *types* only, so
    // these are a plausible HDR10 panel's numbers rather than anything a test asserts on.
    private const val REFERENCE_MAX_LUMINANCE = 1_000f
    private const val REFERENCE_MAX_AVERAGE_LUMINANCE = 500f
    private const val REFERENCE_MIN_LUMINANCE = 0.005f

    /** A mode id nothing else on the simulated device uses, and the refresh rate every display has. */
    private const val DECLARED_MODE_ID = 1
    private const val DECLARED_REFRESH_RATE_HZ = 60f
}
