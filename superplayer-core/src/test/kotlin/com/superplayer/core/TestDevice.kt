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
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.util.ReflectionHelpers

/**
 * What the device under test reports about itself, for the tests that are about [PlayerPool].
 *
 * A pool's bound is derived from device facts — concurrent decoder instances and total memory — so a
 * test involving a pool is running on a device whether it says so or not. Robolectric's default one
 * reports an empty codec table and zero memory, which `DeviceCapacity.kt` correctly reads as "a pool
 * of one". That is a real case worth pinning, and it is also a silent trap for every *other* pool
 * test: a bound of one makes an assertion about handing out two players fail, and makes one about
 * never handing out three pass for the wrong reason.
 *
 * So the device is stated rather than inherited. `PlayerPoolCapacityTest` states a different one per
 * test, because the derivation is its subject; `PlayerPoolTest` states a capable one once, because
 * recycling is its subject and the device it runs on should not be part of the answer.
 *
 * This is the same move `SuperPlayerLifecycleTest` makes when it grants `WAKE_LOCK`: a Robolectric
 * default that does not resemble a real device is corrected explicitly, in the test, where a reader
 * can see it.
 */
internal object TestDevice {

    /**
     * What Robolectric's `CodecCapabilities` answers for `getMaxSupportedInstances`, and the
     * platform's own documented default for a codec that declares no limit.
     *
     * Robolectric's builder has no setter for it, so a test that needs a different limit passes one
     * to [declareVideoDecoder], which writes it past the builder.
     */
    const val REPORTED_DECODER_INSTANCES = 32

    /** A device with plenty of both, so that a pool's size is whatever the test asked for. */
    fun declareCapableDevice() {
        declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        declareAppHeap(megabytes = 2048)
    }

    /**
     * The heap `ActivityManager.getMemoryClass` reports — the app's, not the device's RAM.
     *
     * That is the reading `DeviceCapacity.kt` derives from, because Media3 buffers samples on the
     * Java heap: a phone with 8 GB of RAM still gives one app a 256 MB heap to hold players in.
     * Robolectric's default is 16 MB, which divides into no players at all and is why every pool test
     * that does not call this gets a pool of one.
     */
    fun declareAppHeap(megabytes: Int) {
        shadowOf(activityManager).setMemoryClass(megabytes)
    }

    fun declareLowRamDevice() {
        shadowOf(activityManager).setIsLowRamDevice(true)
    }

    /**
     * Puts a video decoder for [mimeType] into the device's codec list, reporting
     * [maxSupportedInstances] concurrent instances — Robolectric's own [REPORTED_DECODER_INSTANCES]
     * unless a test says otherwise.
     */
    fun declareVideoDecoder(mimeType: String, maxSupportedInstances: Int = REPORTED_DECODER_INSTANCES) {
        addVideoDecoder(mimeType, maxSupportedInstances, secure = false)
    }

    /**
     * As [declareVideoDecoder], and declaring the decoder able to operate on protected memory — what a
     * pool of players built with `PlayerPool.Builder.setDrm` is bounded by (ADR-0012 rule 12).
     *
     * Declared *beside* a plain decoder rather than instead of one, because that is the device: a
     * secure decoder shares its plain sibling's MIME type and is conventionally its name with
     * `.secure` appended, and a pool bounded by the merged answer is the defect. Testkit's
     * `DeviceStatement.declareSecureVideoDecoder` states the same device for the modules that have it.
     */
    fun declareSecureVideoDecoder(mimeType: String, maxSupportedInstances: Int = REPORTED_DECODER_INSTANCES) {
        addVideoDecoder(mimeType, maxSupportedInstances, secure = true)
    }

    private fun addVideoDecoder(mimeType: String, maxSupportedInstances: Int, secure: Boolean) {
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(
                MediaFormat().apply {
                    setString(MediaFormat.KEY_MIME, mimeType)
                    // ref: a capability is declared by the key `feature-` plus the feature name, and
                    // `FEATURE_SecurePlayback` is `secure-playback`:
                    // https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_SecurePlayback
                    if (secure) setInteger("feature-secure-playback", 1)
                },
            )
            .setIsEncoder(false)
            .setColorFormats(
                intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible),
            )
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel()))
            .build()
        // After `build`, which writes Robolectric's 32 into the field `getMaxSupportedInstances`
        // returns; testkit's `DeviceStatement.declareVideoDecoder` does the same, and says why.
        ReflectionHelpers.setField(capabilities, "mMaxSupportedInstances", maxSupportedInstances)

        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("test.decoder.${mimeType.substringAfterLast('/')}${if (secure) ".secure" else ""}")
                .setIsEncoder(false)
                .setCapabilities(capabilities)
                .build(),
        )
    }

    private val activityManager: ActivityManager
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
}
