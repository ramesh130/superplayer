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
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.AudioProfileBuilder
import org.robolectric.shadows.ShadowLooper.shadowMainLooper

/**
 * Writes the audio output [DeviceStatement] states into the platform, through every channel Media3's
 * `AudioCapabilitiesReceiver` reads it from.
 *
 * ref: Media3 1.11's `AudioCapabilities.getCapabilitiesInternal` reads a **television** on API 33 and
 * later from `AudioManager.getDirectProfilesForAttributes`, and any other device from the sticky
 * `AudioManager.ACTION_HDMI_AUDIO_PLUG` broadcast's `EXTRA_ENCODINGS`; its receiver re-reads on that
 * broadcast and on every `AudioDeviceCallback`. So the output is stated both ways, and a listener of
 * either kind hears it:
 * https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioCapabilities.java
 *
 * One HDMI output device stands for the output, and a restatement replaces it: the old device is
 * removed and the new one added, each heard by the device callbacks, with the broadcast sent before
 * either so that every re-read sees the new encodings. Removal first means a reader between the two
 * sees PCM alone, which is the output with nothing passed through — so an AV receiver powered on or off
 * is one change of capabilities to Media3, which ignores a re-read equal to the last. A swap from one
 * passthrough set straight to another is heard as PCM first, as a sink that leaves before its
 * replacement arrives.
 *
 * Not stated: `AudioTrack.isDirectPlaybackSupported`, which Media3 reads for a television on API 29 to
 * 32 only. The pinned runtime is 35, and the attributes it matches on are a player's rather than the
 * device's.
 */
internal object StatedAudioOutput {

    /**
     * The device last stated. It outlives a test as a field of this object while the shadow it was
     * added to does not, which is harmless: removing it from a fresh shadow removes nothing.
     */
    private var output: AudioDeviceInfo? = null

    fun publish(encodings: IntArray) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION") // The platform's own HDMI audio broadcast is sticky, so it is sent as one.
        context.sendStickyBroadcast(
            Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, PLUGGED)
                .putExtra(AudioManager.EXTRA_ENCODINGS, encodings)
                .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, MAX_CHANNEL_COUNT),
        )
        // Robolectric's profile and device builders exist from API 31; the direct-profile query they
        // answer exists from 33, so below that the broadcast is the whole statement.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val audioManager = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            val next = AudioDeviceInfoBuilder.newBuilder()
                .setType(AudioDeviceInfo.TYPE_HDMI)
                .setProfiles(profilesFor(encodings))
                .build()
            output?.let { audioManager.removeOutputDeviceWithDirectProfiles(it) }
            audioManager.addOutputDeviceWithDirectProfiles(next)
            output = next
        }
        shadowMainLooper().idle()
    }

    /**
     * Stereo PCM, which is exactly what Media3 assumes of an output that lists nothing — so the device
     * between removal and addition and an output stating no encodings read the same — and each passthrough
     * encoding at stereo and 5.1.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun profilesFor(encodings: IntArray) =
        listOf(profile(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_OUT_STEREO)) +
            encodings.filter { it != AudioFormat.ENCODING_PCM_16BIT }.distinct().map {
                profile(it, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_5POINT1)
            }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun profile(encoding: Int, vararg channelMasks: Int) = AudioProfileBuilder.newBuilder()
        .setFormat(encoding)
        .setSamplingRates(intArrayOf(SAMPLE_RATE_HZ))
        .setChannelMasks(channelMasks)
        .build()

    private const val PLUGGED = 1

    /**
     * 5.1, the channel count the commonest passthrough formats (AC-3, E-AC-3) carry; Media3 reads a
     * passthrough format's channel count against it.
     */
    private const val MAX_CHANNEL_COUNT = 6

    /** 48 kHz, the rate broadcast audio and every HDMI sink carries. Media3 reads the encodings and channels, not the rate. */
    private const val SAMPLE_RATE_HZ = 48_000
}
