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

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a television stated through [DeviceStatement] is what the platform then reports, and that a
 * restatement scheduled on the harness's clock reaches a listener registered on the platform at the
 * moment it was scheduled for (#266).
 *
 * The listeners are the platform's own — a `DisplayManager.DisplayListener`, an `AudioDeviceCallback`
 * and the HDMI audio plug broadcast — and Media3's `AudioCapabilitiesReceiver`, the one reader of the
 * audio output ADR-0014 rule 6 relies on. Nothing here is SuperPlayer's: a later issue asserts what a
 * player does with the change, and this is what lets it.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class TelevisionDeviceTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theHarnessStillStatesItsTelevisionSizedDisplayAtSixtyHertz() {
        // The default device is the one every test before #266 ran on.
        assertThat(modesOf(defaultDisplay())).containsExactly(DEFAULT)
        assertThat(defaultDisplay().mode.physicalHeight).isEqualTo(DeviceStatement.DEFAULT_DISPLAY_HEIGHT_PX)
        assertThat(uiModeManager().currentModeType).isNotEqualTo(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Test
    fun aDisplayReportsEveryDeclaredModeAndTheActiveOne() {
        DeviceStatement.declareDisplayModes(listOf(UHD_60, UHD_24, FHD_60), activeMode = UHD_24)

        val display = defaultDisplay()
        assertThat(modesOf(display)).containsExactly(UHD_60, UHD_24, FHD_60).inOrder()
        assertThat(DisplayMode(display.mode.physicalWidth, display.mode.physicalHeight, display.mode.refreshRate)).isEqualTo(UHD_24)
    }

    @Test
    fun aRestatedDisplayIsOneChangeAndKeepsWhatWasNotRestated() {
        DeviceStatement.declareDisplayModes(listOf(UHD_60, FHD_60))
        DeviceStatement.declareDisplayHdrTypes(Display.HdrCapabilities.HDR_TYPE_HDR10)
        val heard = DisplayEvents().also { it.register() }

        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24))

        // One event, and the display already whole when it arrives: a listener that read it then would
        // read the new modes beside the HDR types it had, never a display with no HDR answer.
        assertThat(heard.events).containsExactly("changed 0")
        assertThat(heard.modesAtEvent.single()).containsExactly(FHD_60, FHD_24)
        assertThat(heard.hdrTypesAtEvent.single()).containsExactly(Display.HdrCapabilities.HDR_TYPE_HDR10)

        DeviceStatement.declareDisplayHdrTypes()

        assertThat(heard.events).containsExactly("changed 0", "changed 0")
        assertThat(checkNotNull(defaultDisplay().hdrCapabilities).supportedHdrTypes.toList()).isEmpty()
        assertThat(defaultDisplay().mode.supportedHdrTypes.toList()).isEmpty()
        assertThat(modesOf(defaultDisplay())).containsExactly(FHD_60, FHD_24)
    }

    @Test
    fun aRestatementThatChangesNothingIsHeardByNobody() {
        val heard = DisplayEvents().also { it.register() }

        DeviceStatement.declareDisplay(DeviceStatement.DEFAULT_DISPLAY_WIDTH_PX, DeviceStatement.DEFAULT_DISPLAY_HEIGHT_PX)

        assertThat(heard.events).isEmpty()
    }

    @Test
    fun aDisconnectedDisplayIsRemovedAndComesBackUnderTheDefaultId() {
        DeviceStatement.declareDisplayHdrTypes(Display.HdrCapabilities.HDR_TYPE_HLG)
        val heard = DisplayEvents().also { it.register() }

        DeviceStatement.declareDisplayDisconnected()
        assertThat(displayManager().getDisplay(Display.DEFAULT_DISPLAY)).isNull()
        DeviceStatement.declareDisplayModes(listOf(FHD_60))

        assertThat(heard.events).containsExactly("removed 0", "added 0").inOrder()
        assertThat(modesOf(defaultDisplay())).containsExactly(FHD_60)
        assertThat(checkNotNull(defaultDisplay().hdrCapabilities).supportedHdrTypes.toList()).containsExactly(Display.HdrCapabilities.HDR_TYPE_HLG)
    }

    @Test
    fun aTelevisionIsWhatTheUiModeAndThePackageManagerReport() {
        DeviceStatement.declareTelevision()

        assertThat(uiModeManager().currentModeType).isEqualTo(Configuration.UI_MODE_TYPE_TELEVISION)
        assertThat(context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)).isTrue()
    }

    @Test
    fun anAudioOutputIsWhatMedia3ReadsOnATelevision() {
        DeviceStatement.declareTelevision()
        assertAudioOutputReachesMedia3()
    }

    @Test
    fun anAudioOutputIsWhatMedia3ReadsOffATelevision() {
        // The control for the channel: off a television Media3 reads the HDMI plug broadcast rather than
        // the direct playback profiles, so a statement written to one channel alone passes one test.
        assertAudioOutputReachesMedia3()
    }

    @Test
    fun aScheduledChangeReachesThePlatformsListenersAtItsMomentMidSession() {
        DeviceStatement.declareTelevision()
        DeviceStatement.declareDisplayModes(listOf(UHD_60, UHD_24))
        DeviceStatement.declareDisplayHdrTypes(Display.HdrCapabilities.HDR_TYPE_HDR10)
        DeviceStatement.declareAudioOutput()
        val player = harness.buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)
        val display = DisplayEvents().also { it.register() }
        val audio = AudioEvents().also { it.register() }
        val startMs = harness.elapsedRealtimeMs()

        harness.scheduleDeviceChange(AUDIO_CHANGE_AFTER_MS) { DeviceStatement.declareAudioOutput(AudioFormat.ENCODING_AC3) }
        harness.scheduleDeviceChange(DISPLAY_CHANGE_AFTER_MS) { DeviceStatement.declareDisplayModes(listOf(FHD_60)) }

        // Short of both, so nothing is heard early.
        harness.advanceTimeMs(player, AUDIO_CHANGE_AFTER_MS - 1)
        assertThat(display.events).isEmpty()
        assertThat(audio.heardAtMs).isEmpty()

        // One advance across both, so each lands at its own moment rather than at the end of the step.
        harness.advanceTimeMs(player, SESSION_MS)

        assertThat(display.events).containsExactly("changed 0")
        assertThat(display.heardAtMs.single() - startMs).isEqualTo(DISPLAY_CHANGE_AFTER_MS)
        assertThat(audio.heardAtMs.distinct().map { it - startMs }).containsExactly(AUDIO_CHANGE_AFTER_MS)
        assertThat(audio.broadcastEncodings.last()).asList().containsExactly(AudioFormat.ENCODING_AC3)
        assertThat(audio.devicesAdded).isAtLeast(1)
        assertThat(harness.elapsedRealtimeMs() - startMs).isEqualTo(AUDIO_CHANGE_AFTER_MS - 1 + SESSION_MS)
        // And the session played through both, which is all a harness change may cost it.
        assertThat(player.playerError).isNull()
    }

    private fun assertAudioOutputReachesMedia3() {
        val heard = mutableListOf<AudioCapabilities>()
        val receiver = AudioCapabilitiesReceiver(context, { heard += it }, androidx.media3.common.AudioAttributes.DEFAULT, null)
        val initial = receiver.register()
        // Robolectric's audio service reports the devices already present inside the registration, before
        // Media3 holds a first reading, and Media3 reports that as a change. It is the registration's.
        heard.clear()
        assertThat(initial.isPassthroughPlaybackSupported(AC3_FORMAT, androidx.media3.common.AudioAttributes.DEFAULT)).isFalse()

        DeviceStatement.declareAudioOutput(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3)

        // One change: Media3 re-reads on every channel and ignores a reading equal to the last.
        assertThat(heard).hasSize(1)
        assertThat(heard.last().isPassthroughPlaybackSupported(AC3_FORMAT, androidx.media3.common.AudioAttributes.DEFAULT)).isTrue()

        DeviceStatement.declareAudioOutput()

        assertThat(heard).hasSize(2)
        assertThat(heard.last().isPassthroughPlaybackSupported(AC3_FORMAT, androidx.media3.common.AudioAttributes.DEFAULT)).isFalse()
        receiver.unregister()
    }

    /** What a `DisplayListener` heard, and what the display reported when it did. */
    private inner class DisplayEvents : DisplayManager.DisplayListener {
        val events = mutableListOf<String>()
        val heardAtMs = mutableListOf<Long>()
        val modesAtEvent = mutableListOf<List<DisplayMode>>()
        val hdrTypesAtEvent = mutableListOf<List<Int>>()

        fun register() = displayManager().registerDisplayListener(this, Handler(Looper.getMainLooper()))

        override fun onDisplayAdded(displayId: Int) = heard("added $displayId")

        override fun onDisplayRemoved(displayId: Int) = heard("removed $displayId")

        override fun onDisplayChanged(displayId: Int) {
            heard("changed $displayId")
            val display = defaultDisplay()
            modesAtEvent += modesOf(display)
            hdrTypesAtEvent += display.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
        }

        private fun heard(event: String) {
            events += event
            heardAtMs += harness.elapsedRealtimeMs()
        }
    }

    /** What the audio output's two platform channels heard. */
    private inner class AudioEvents {
        val heardAtMs = mutableListOf<Long>()
        val broadcastEncodings = mutableListOf<IntArray>()
        var devicesAdded = 0

        fun register() {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        devicesAdded++
                        heardAtMs += harness.elapsedRealtimeMs()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            // Registering reports the devices already there, as the platform does; that is not a change.
            devicesAdded = 0
            heardAtMs.clear()
            val sticky = context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (isInitialStickyBroadcast) return
                        broadcastEncodings += intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS) ?: IntArray(0)
                        heardAtMs += harness.elapsedRealtimeMs()
                    }
                },
                IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG),
            )
            checkNotNull(sticky) { "The stated output's sticky broadcast should already be there" }
        }
    }

    private fun modesOf(display: Display) = display.supportedModes.map { DisplayMode(it.physicalWidth, it.physicalHeight, it.refreshRate) }

    private fun defaultDisplay(): Display = checkNotNull(displayManager().getDisplay(Display.DEFAULT_DISPLAY)) { "No default display" }

    private fun displayManager() = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private fun uiModeManager() = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

    private companion object {
        val DEFAULT = DisplayMode(DeviceStatement.DEFAULT_DISPLAY_WIDTH_PX, DeviceStatement.DEFAULT_DISPLAY_HEIGHT_PX, 60f)
        val UHD_60 = DisplayMode(3840, 2160, 60f)
        val UHD_24 = DisplayMode(3840, 2160, 24f)
        val FHD_60 = DisplayMode(1920, 1080, 60f)
        val FHD_24 = DisplayMode(1920, 1080, 24f)

        val AC3_FORMAT: Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()

        const val CONTENT = "series/expanse/s01e01"
        const val SOURCE = "fake://superplayer.test/never-fetched"
        const val AUDIO_CHANGE_AFTER_MS = 1_500L
        const val DISPLAY_CHANGE_AFTER_MS = 3_000L
        const val SESSION_MS = 4_000L
    }
}
