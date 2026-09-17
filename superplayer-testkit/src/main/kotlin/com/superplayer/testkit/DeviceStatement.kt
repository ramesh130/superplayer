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
    private fun addVideoDecoder(
        mimeType: String,
        maxSupportedInstances: Int?,
        profileLevels: Array<out Pair<Int, Int>>,
        secure: Boolean = false,
    ) {
        val declared = profileLevels.map { (profile, level) ->
            MediaCodecInfo.CodecProfileLevel().also {
                it.profile = profile
                it.level = level
            }
        }
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(
                MediaFormat().apply {
                    setString(MediaFormat.KEY_MIME, mimeType)
                    if (secure) setInteger(SECURE_PLAYBACK_FEATURE_KEY, 1)
                },
            )
            .setIsEncoder(false)
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
            .setProfileLevels(declared.toTypedArray())
            .build()
        // After `build`, which writes Robolectric's own 32 into the same field: the one the platform's
        // `getMaxSupportedInstances` returns, filled from `max-concurrent-instances` on a device.
        // ref: https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances()
        // ref: https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/media/java/android/media/MediaCodecInfo.java
        maxSupportedInstances?.let { ReflectionHelpers.setField(capabilities, "mMaxSupportedInstances", it) }
        val secureSuffix = if (secure) ".secure" else ""
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("test.decoder.${mimeType.substringAfterLast('/')}.${declaredDecoders++}$secureSuffix")
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

    /**
     * The device's network is metered — a mobile connection — or, with [metered] false, an unmetered
     * WiFi one. Connected and validated either way. The first of the three conditions a download is
     * scheduled under (ADR-0013 rule 10), and unlike every declaration above it may be restated
     * mid-test: a lapsed condition is what a download has to stop on and resume after.
     *
     * Robolectric's own device is a *metered* mobile network, so a test of a download that is not about
     * the network states it unmetered first.
     *
     * ref: https://developer.android.com/reference/android/net/NetworkCapabilities#NET_CAPABILITY_NOT_METERED
     */
    @JvmStatic
    public fun declareNetworkMetered(metered: Boolean) {
        DownloadConditions.stateNetwork(metered)
    }

    /**
     * The battery is low — unplugged at 5% — or, with [low] false, unplugged at 80%. The second
     * condition (ADR-0013 rule 10), restatable mid-test, and announced with `ACTION_BATTERY_LOW` or
     * `ACTION_BATTERY_OKAY` as a device announces it. Robolectric's device reports no battery at all,
     * which `WorkManager` reads as the battery-not-low constraint *unmet*, so a test states one.
     *
     * ref: https://developer.android.com/reference/android/content/Intent#ACTION_BATTERY_LOW
     */
    @JvmStatic
    public fun declareBatteryLow(low: Boolean) {
        DownloadConditions.stateBattery(low)
    }

    /**
     * Storage is low — the platform's sticky `ACTION_DEVICE_STORAGE_LOW` — or, with [low] false, it has
     * recovered. The third condition (ADR-0013 rule 10), restatable mid-test. Not the same thing as a
     * full disk, which is a write failing rather than a condition announced ([declareStorageFree]).
     *
     * ref: https://developer.android.com/reference/android/content/Intent#ACTION_DEVICE_STORAGE_LOW
     */
    @JvmStatic
    public fun declareStorageLow(low: Boolean) {
        DownloadConditions.stateStorage(low)
    }

    /**
     * The device's storage has [bytes] free to this app — `StatFs.getAvailableBytes` answers it for every
     * path — which is what a full disk is to a download: zero, or less than its next write (#244, ADR-0013
     * rule 9). Restatable mid-test, and read again at a download's next write, so a disk can fill partway
     * through an item and have room made on it afterwards.
     *
     * A reading and not a volume: it does not shrink as a download writes, so a test that wants a disk to
     * fill states it full rather than stating a size and waiting. Robolectric's device describes no volume
     * at all until this is said, which a download reads as nothing known and refuses nothing on. Unrelated
     * to [declareStorageLow], which is the platform's announcement that a disk is *nearly* full, and holds a
     * download rather than failing one.
     *
     * ref: https://developer.android.com/reference/android/os/StatFs#getAvailableBytes()
     */
    @JvmStatic
    public fun declareStorageFree(bytes: Long) {
        DownloadConditions.stateFreeStorage(bytes)
    }

    /**
     * A device with a working Widevine implementation at [securityLevel], running at most
     * [maxConcurrentSessions] DRM sessions at once.
     *
     * Stated rather than shadowed, and [WidevineStatement]'s KDoc says why there is no choice:
     * Robolectric 4.16 ships no `ShadowMediaDrm`, so what stands in for the device is the
     * `ExoMediaDrm` a session manager talks to. A test states it here anyway, beside its display and
     * its decoders, so that a reader finds the whole device in one place.
     *
     * [maxConcurrentSessions] is the limit a real implementation enforces and the reason a feed of
     * protected content cannot simply open a session per row. One is not the interesting number and
     * the default is deliberately more than one, so that a test which is not about the limit does not
     * meet it by accident.
     *
     * Declare before the test's first player is built, as every other declaration here is: the
     * statement is read when a player's DRM session manager is constructed. It **replaces** the
     * statement whole, so anything said about the device separately — [declareOfflineLicence]'s
     * durations, and the offline licences the device has persisted — is forgotten with it. Say the
     * device first and the rest after.
     */
    @JvmStatic
    @JvmOverloads
    public fun declareWidevine(
        securityLevel: SecurityLevel = SecurityLevel.L1,
        maxConcurrentSessions: Int = DEFAULT_MAX_CONCURRENT_DRM_SESSIONS,
        provisioningRequired: Boolean = false,
    ) {
        require(maxConcurrentSessions > 0) { "A device runs at least one DRM session, not $maxConcurrentSessions" }
        widevine = WidevineStatement(
            securityLevel,
            maxConcurrentSessions,
            provisioningRequired,
            provisioningFails = false,
        )
    }

    /**
     * A device whose Widevine implementation cannot be provisioned: the provisioning service refuses
     * it, so no licence at [securityLevel] can ever be acquired.
     *
     * The commonest reason a handset that reports L1 cannot play at L1 — a revoked or untrusted
     * implementation — and the case ADR-0012 rule 11's downgrade exists for. It is a *device*
     * refusal rather than a failed transfer: the provisioning request reaches the service and is
     * turned down, which is a different failure from one a [FaultScript] at [ResourceKind.LICENCE]
     * injects, and a test that means one should not write the other.
     */
    @JvmStatic
    @JvmOverloads
    public fun declareWidevineProvisioningFailure(securityLevel: SecurityLevel = SecurityLevel.L1) {
        widevine = WidevineStatement(
            securityLevel,
            DEFAULT_MAX_CONCURRENT_DRM_SESSIONS,
            provisioningRequired = true,
            provisioningFails = true,
        )
    }

    /**
     * How long a licence this device persists for offline use has left: the *licence* duration and
     * the *playback* duration, in seconds.
     *
     * ref: Widevine reports the two separately (`MediaDrm.queryKeyStatus` answers
     * `LicenseDurationRemaining` and `PlaybackDurationRemaining`, which Media3 reads in
     * `WidevineUtil`), and they mean different things: the licence duration is how long the
     * entitlement itself lives, while the playback duration is the viewing window that starts when
     * the download is first played. ADR-0012 rule 9 requires SuperPlayer to keep them apart and
     * report both before playback, so a test needs to be able to state them apart too.
     *
     * The defaults are an ordinary month-long rental with a two-day viewing window; state something
     * small — zero, for a licence that has died — to reach the expiry cases, and **null** for a
     * duration this device's licences do not carry at all, which is the commonest shape of a purchase
     * with no viewing window and of a rental with no separate entitlement clock. A real
     * `queryKeyStatus` simply omits the property, and Media3 answers `C.TIME_UNSET` for it. Note the one number
     * Media3 fixes: a restored licence within **sixty seconds** of expiry is re-requested from the
     * server during playback rather than played to a stop (`DefaultDrmSession.doLicense`), so a
     * duration under that turns an offline test into an online one.
     *
     * Declare after [declareWidevine], which builds the statement this writes into. Unlike every
     * other declaration here it may also be restated *after* a player exists, and a test of a
     * renewal does exactly that: the durations are read at each `queryKeyStatus`, so restating them
     * is how a licence server that now issues a longer licence is said.
     */
    @JvmStatic
    public fun declareOfflineLicence(licenceDurationSec: Long?, playbackDurationSec: Long?) {
        // Guarded like every sibling here: a negative duration is not a shorter licence, it is a
        // typo that would arrive as an expiry by a route the test did not write. Null is not a
        // negative duration — it is no duration, which is the case above.
        require((licenceDurationSec ?: 0) >= 0 && (playbackDurationSec ?: 0) >= 0) {
            "A licence has a duration of zero or more seconds, not $licenceDurationSec/$playbackDurationSec"
        }
        widevine.declareOfflineLicenceDurations(licenceDurationSec, playbackDurationSec)
    }

    /**
     * This device telling every DRM session it currently holds open that its keys must be renewed.
     *
     * The one declaration here that is not a statement about the device *before* a player is built,
     * and it could not be: a rotation is something that happens to a session in flight, which is the
     * whole of what makes it worth testing. Call it after a player has reached playback, then let the
     * harness's clock run — the renewal is a licence request like any other, and
     * `PlaybackHarness.networkRequests` counts it.
     *
     * ref: `MediaDrm.EVENT_KEY_REQUIRED`, and `WidevineStatement.signalKeyRotation` carries the
     * argument for why the *device* raises this rather than the licence server.
     */
    @JvmStatic
    public fun signalKeyRotation() {
        widevine.signalKeyRotation()
    }

    /**
     * As [declareVideoDecoder], and declaring the decoder able to operate on protected memory — the
     * kind a licence acquired at [SecurityLevel.L1] may only be used with.
     *
     * ref: `MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback` is the feature name
     * (`secure-playback`), and a capability is declared by the key `feature-` plus that name:
     * https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_SecurePlayback
     * A device's secure decoder is also conventionally the plain decoder's name with `.secure`
     * appended, which is how Media3 finds one when a codec list is read by name, so the declaration
     * carries both.
     *
     * [maxSupportedInstances] is stated separately from the plain decoder's for the reason the
     * distinction exists at all: a device commonly runs several ordinary video decoders and exactly
     * one secure one, so a feed of protected content has a different bound from the same feed in the
     * clear.
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun declareSecureVideoDecoder(
        mimeType: String,
        maxSupportedInstances: Int,
        vararg profileLevels: Pair<Int, Int>,
    ) {
        require(maxSupportedInstances > 0) { "A decoder runs at least one instance, not $maxSupportedInstances" }
        addVideoDecoder(mimeType, maxSupportedInstances, profileLevels, secure = true)
    }

    /**
     * This device's Widevine implementation: what a test last declared, or [ordinaryWidevineDevice].
     *
     * Never absent, for the reason the default display is never absent — a player built over
     * protected content needs *some* device, and a harness that made every such test declare one
     * would be charging every test for the case only a few are about.
     */
    internal var widevine: WidevineStatement = ordinaryWidevineDevice()
        private set

    /**
     * Forgets the DRM statement, so one test's device is not the next one's.
     *
     * Every other declaration here writes into a Robolectric shadow, and Robolectric resets those
     * between tests. This one is a field of this object, which outlives a test method, so the reset
     * has to be written down — [PlaybackHarness] calls it before each test, where it also states the
     * default display.
     */
    internal fun forgetWidevine() {
        widevine = ordinaryWidevineDevice()
    }

    /** An unremarkable modern handset: hardware-backed Widevine, already provisioned. */
    private fun ordinaryWidevineDevice() = WidevineStatement(
        SecurityLevel.L1,
        DEFAULT_MAX_CONCURRENT_DRM_SESSIONS,
        provisioningRequired = false,
        provisioningFails = false,
    )

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

    /**
     * More than one, so a test that is not about the session limit never meets it, and few enough
     * that a test which *is* about it can reach it without opening a hundred sessions.
     */
    private const val DEFAULT_MAX_CONCURRENT_DRM_SESSIONS = 4

    /**
     * ref: a codec capability is declared in the format under `feature-` plus the feature's name, and
     * `MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback` is `secure-playback`.
     */
    private const val SECURE_PLAYBACK_FEATURE_KEY = "feature-secure-playback"
}
