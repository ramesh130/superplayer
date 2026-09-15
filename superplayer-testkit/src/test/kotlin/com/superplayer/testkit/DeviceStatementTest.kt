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
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a statement is what the platform then reports, through the same platform calls
 * `superplayer-core` reads the device with. A statement Robolectric silently ignored would make
 * every gating test above it pass or fail for a reason it did not name.
 */
@RunWith(AndroidJUnit4::class)
class DeviceStatementTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aDeclaredDisplayIsTheDefaultDisplaysOnlyMode() {
        DeviceStatement.declareDisplay(1920, 1080)

        val display = checkNotNull(defaultDisplay())
        val modes = display.supportedModes.map { it.physicalWidth to it.physicalHeight }
        assertThat(modes).containsExactly(1920 to 1080)
        assertThat(display.mode.physicalHeight).isEqualTo(1080)
    }

    @Test
    fun declaredHdrTypesAreWhatTheDisplayReports() {
        DeviceStatement.declareDisplay(3840, 2160)
        DeviceStatement.declareDisplayHdrTypes(Display.HdrCapabilities.HDR_TYPE_HDR10, Display.HdrCapabilities.HDR_TYPE_HLG)

        val reported = checkNotNull(checkNotNull(defaultDisplay()).hdrCapabilities).supportedHdrTypes.toList()
        assertThat(reported).containsExactly(Display.HdrCapabilities.HDR_TYPE_HDR10, Display.HdrCapabilities.HDR_TYPE_HLG)
    }

    @Test
    fun aDeclaredDecoderReportsItsInstanceLimit() {
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, maxSupportedInstances = 3)

        val limits = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes }
            .map { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).maxSupportedInstances }
        assertThat(limits).containsExactly(3)
    }

    @Test
    fun aDeclaredDecoderReportsItsProfileLevels() {
        DeviceStatement.declareVideoDecoder(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            CodecProfileLevel.AVCProfileMain to CodecProfileLevel.AVCLevel41,
        )

        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
        val levels = decoders.flatMap { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels.toList() }
        assertThat(levels.map { it.profile to it.level })
            .containsExactly(CodecProfileLevel.AVCProfileMain to CodecProfileLevel.AVCLevel41)
    }

    private fun defaultDisplay(): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
}
