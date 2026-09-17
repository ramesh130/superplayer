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
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Looper
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.VideoRendererEventListener
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

    @Test
    fun aDeclaredSecureDecoderIsReportedSecureAndWithItsOwnInstanceLimit() {
        // A device commonly runs several ordinary video decoders and exactly one secure one, so the
        // two limits are stated apart and have to *stay* apart: a declaration that quietly produced
        // one decoder answering for both would make a protected feed's bound the clear feed's.
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 4)
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)

        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .map { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }
        val secure = decoders.filter {
            it.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
        }
        assertThat(secure.map { it.maxSupportedInstances }).containsExactly(1)
        assertThat(decoders.map { it.maxSupportedInstances }).containsExactly(4, 1)
    }

    @Test
    fun aDeclaredSecureDecoderKeepsItsOwnProfilesAndLevels() {
        // #211: the same separation for what a decoder can *sustain*, which is the reading a
        // protected player's track selection asks. The two decoders share the MIME type — that is
        // the device — so a declaration that let the plain decoder's ceiling answer for the secure
        // one would make a protected ladder gated on a decoder it will never open.
        DeviceStatement.declareVideoDecoder(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel51,
        )
        DeviceStatement.declareSecureVideoDecoder(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            maxSupportedInstances = 1,
            CodecProfileLevel.AVCProfileMain to CodecProfileLevel.AVCLevel41,
        )

        val declared = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .map { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }
            .associate { capabilities ->
                capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) to
                    capabilities.profileLevels.map { it.profile to it.level }
            }
        assertThat(declared[false]).containsExactly(CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel51)
        assertThat(declared[true]).containsExactly(CodecProfileLevel.AVCProfileMain to CodecProfileLevel.AVCLevel41)
    }

    @Test
    fun aDeclaredTunnelingDecoderReportsTunneledPlaybackAndAnOrdinaryOneDoesNot() {
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC)
        DeviceStatement.declareTunnelingVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)

        val tunnels = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .associate { info ->
                val type = info.supportedTypes.single()
                type to info.getCapabilitiesForType(type).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
            }
        assertThat(tunnels).containsExactly(MediaFormat.MIMETYPE_VIDEO_AVC, true, MediaFormat.MIMETYPE_VIDEO_HEVC, false)
    }

    /**
     * ADR-0014 rule 7's protected half, on the harness's stand-in for Media3's video renderer: protected
     * content is answered for by the *secure* decoder, so a device whose ordinary decoder tunnels and whose
     * secure one does not tunnels clear video only. No described stream carries protection, so this is
     * where the rule is asserted rather than through a player (`superplayer-tv`'s `TunneledPlaybackTest`).
     */
    @Test
    fun theHarnessVideoRendererAnswersTunnelingForProtectedVideoFromTheSecureDecoder() {
        DeviceStatement.declareTunnelingVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        val renderer = videoRenderer()

        assertThat(tunnelingOf(renderer.supportsFormat(avc(protected = false)))).isEqualTo(RendererCapabilities.TUNNELING_SUPPORTED)
        assertThat(tunnelingOf(renderer.supportsFormat(avc(protected = true)))).isEqualTo(RendererCapabilities.TUNNELING_NOT_SUPPORTED)
    }

    /** The case and its control: the same protected video, on a device whose secure decoder tunnels. */
    @Test
    fun theHarnessVideoRendererTunnelsProtectedVideoWhereTheSecureDecoderDeclaresIt() {
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        DeviceStatement.declareTunnelingVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, secure = true)
        val renderer = videoRenderer()

        assertThat(tunnelingOf(renderer.supportsFormat(avc(protected = true)))).isEqualTo(RendererCapabilities.TUNNELING_SUPPORTED)
        assertThat(tunnelingOf(renderer.supportsFormat(avc(protected = false)))).isEqualTo(RendererCapabilities.TUNNELING_NOT_SUPPORTED)
    }

    @Test
    fun aDeclaredSecurityLevelIsWhatTheImplementationAnswers() {
        // The property a Widevine implementation answers `L1` or `L3` to. Nothing in the library
        // reads it yet — #208 and #211 are where it starts to matter — so it is asserted here, on
        // the statement itself, rather than through a player that would not yet notice.
        listOf(SecurityLevel.L1, SecurityLevel.L3).forEach { level ->
            DeviceStatement.declareWidevine(level)

            val drm = DeviceStatement.widevine.exoMediaDrm()
            assertThat(drm.getPropertyString("securityLevel")).isEqualTo(level.name)
            // And the half of the distinction that is not a string: only L1 keys demand a decoder
            // operating on protected memory.
            assertThat(drm.requiresSecureDecoder(drm.openSession(), MediaFormat.MIMETYPE_VIDEO_AVC))
                .isEqualTo(level == SecurityLevel.L1)
            drm.release()
        }
    }

    private fun defaultDisplay(): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    private fun videoRenderer() = ControllableVideoRenderer(
        Clock.DEFAULT.createHandler(Looper.getMainLooper(), /* callback= */ null),
        object : VideoRendererEventListener {},
        DecoderInitFault(),
    )

    private fun avc(protected: Boolean): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(1920)
        .setHeight(1080)
        .apply {
            if (protected) {
                setDrmInitData(DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, byteArrayOf(0))))
            }
        }
        .build()

    private fun tunnelingOf(capabilities: Int): Int = RendererCapabilities.getTunnelingSupport(capabilities)
}
