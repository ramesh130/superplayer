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

package com.superplayer.realtime

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.RealtimeTrack
import com.superplayer.core.UnsupportedRealtimeCodecException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The codec-string table, driven with string literals and nothing else.
 *
 * It is a pure function over a `String`, so there is no player here and no harness: the claims about
 * a *player* built on one of these formats are `RealtimePlaybackTest`'s, through the public `Player`
 * API, and this is the unit beneath them. Robolectric is present only because
 * `MediaCodecInfo.CodecProfileLevel`'s constants and Media3's reader are Android API.
 *
 * Three kinds of case, deliberately:
 *
 * - **the table**, one per codec family and one per SDP spelling, so that a family lost in a rewrite
 *   fails here rather than on a device;
 * - **#340's verbatim strings**, `avc1.42c01e` and `avc3.42c01e`, observed at the MoQ boundary
 *   rather than read off a registry — the acceptance criterion that the table covers what a real
 *   broadcast actually sent;
 * - **the controls**, which are what keep this from being "map everything": a string nobody mapped,
 *   a string of a mapped family whose parameters are malformed, and `mp4a.69`, which is a codec this
 *   table must *not* answer for.
 */
@RunWith(RobolectricTestRunner::class)
class RealtimeFormatsTest {

    // --- The table ------------------------------------------------------------------------------

    @Test
    fun `h264 is mapped under both of its sample entry names`() {
        assertThat(RealtimeFormats.formatFor("avc1.640028", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264)
        assertThat(RealtimeFormats.formatFor("avc3.42E01E", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264)
    }

    @Test
    fun `h265 is mapped under both of its sample entry names`() {
        assertThat(RealtimeFormats.formatFor("hvc1.1.6.L93.B0", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265)
        assertThat(RealtimeFormats.formatFor("hev1.1.6.L93.B0", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265)
    }

    @Test
    fun `vp9 av1 aac and opus are mapped`() {
        assertThat(RealtimeFormats.formatFor("vp09.00.10.08", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_VP9)
        assertThat(RealtimeFormats.formatFor("av01.0.04M.08", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_AV1)
        assertThat(RealtimeFormats.formatFor("mp4a.40.2", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC)
        assertThat(RealtimeFormats.formatFor("opus", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.AUDIO_OPUS)
    }

    @Test
    fun `mpeg-2 aac reaches the same mime type as mpeg-4 aac`() {
        // Object type indication 0x67 rather than 0x40: the same decoder and the same Media3 MIME
        // type, so refusing it would be refusing AAC for its spelling.
        assertThat(RealtimeFormats.formatFor("mp4a.67.2", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC)
    }

    @Test
    fun `sdp encoding names reach the same table as the rfc 6381 strings`() {
        // WHEP names codecs in SDP and MoQ in RFC 6381, and the point of one table is that the two
        // answer alike. Case is not significant in SDP (RFC 4855 §3), which the spellings vary.
        assertThat(RealtimeFormats.formatFor("H264", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264)
        assertThat(RealtimeFormats.formatFor("H265", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265)
        assertThat(RealtimeFormats.formatFor("VP9", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_VP9)
        assertThat(RealtimeFormats.formatFor("AV1", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_AV1)
        assertThat(RealtimeFormats.formatFor("MPEG4-GENERIC", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC)
        assertThat(RealtimeFormats.formatFor("opus", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.AUDIO_OPUS)
    }

    @Test
    fun `an sdp encoding name states no rfc 6381 codecs string`() {
        // The field's vocabulary is RFC 6381's, and a bare SDP encoding name is not one of its
        // strings: it states no profile and no level, so the honest value is none.
        assertThat(RealtimeFormats.formatFor("H264", codedSize = null).codecs).isNull()
        assertThat(RealtimeFormats.formatFor("avc1.640028", codedSize = null).codecs).isEqualTo("avc1.640028")
    }

    // --- #340's observed strings ----------------------------------------------------------------

    @Test
    fun `the strings observed in the moq spike map and carry their profile and level`() {
        // Verbatim from #340's finding: one string per sample entry shape, both from the same
        // broadcast of the same media. `42` is profile_idc 66, Baseline; `c0` the constraint flags;
        // `1e` level_idc 30, level 3.0. Both strings answer alike, which is the point of the
        // substitution that lets the `avc3` spelling be read at all.
        for (codec in listOf("avc1.42c01e", "avc3.42c01e")) {
            assertThat(RealtimeFormats.formatFor(codec, codedSize = null).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264)
            assertThat(RealtimeFormats.profileAndLevelOf(codec))
                .isEqualTo(CodecProfileLevel.AVCProfileBaseline to CodecProfileLevel.AVCLevel3)
        }
    }

    // --- Profile and level ----------------------------------------------------------------------

    @Test
    fun `profile and level are extracted from every family whose string carries them`() {
        assertThat(RealtimeFormats.profileAndLevelOf("avc1.640028"))
            .isEqualTo(CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel4)
        assertThat(RealtimeFormats.profileAndLevelOf("hev1.1.6.L93.B0"))
            .isEqualTo(CodecProfileLevel.HEVCProfileMain to CodecProfileLevel.HEVCMainTierLevel31)
        assertThat(RealtimeFormats.profileAndLevelOf("vp09.00.10.08"))
            .isEqualTo(CodecProfileLevel.VP9Profile0 to CodecProfileLevel.VP9Level1)
        // `0` is profile Main, `04M` seq_level_idx 4 — level 3.0 — at the Main tier, `08` 8-bit.
        assertThat(RealtimeFormats.profileAndLevelOf("av01.0.04M.08"))
            .isEqualTo(CodecProfileLevel.AV1ProfileMain8 to CodecProfileLevel.AV1Level3)
        // AAC states an audio object type and no level, which Media3's reader answers as zero.
        assertThat(RealtimeFormats.profileAndLevelOf("mp4a.40.2"))
            .isEqualTo(CodecProfileLevel.AACObjectLC to 0)
    }

    @Test
    fun `a codec that states no profile or level extracts none and is still mapped`() {
        // Opus defines neither, and an SDP encoding name states neither: nothing to extract is not
        // a malformed string, which is why these are mapped and the malformed ones below are not.
        assertThat(RealtimeFormats.profileAndLevelOf("opus")).isNull()
        assertThat(RealtimeFormats.profileAndLevelOf("H264")).isNull()
        assertThat(RealtimeFormats.formatFor("opus", codedSize = null).sampleMimeType).isEqualTo(MimeTypes.AUDIO_OPUS)
    }

    // --- The controls ---------------------------------------------------------------------------

    @Test
    fun `an unrecognised codec string refuses and names itself`() {
        val refusal = assertThrows(UnsupportedRealtimeCodecException::class.java) {
            RealtimeFormats.formatFor("theora", codedSize = null)
        }
        assertThat(refusal.codec).isEqualTo("theora")
        // In the message as well as on the property, because the message is what a bug report
        // carries and "unsupported codec" with no string in it is what makes this hard to report.
        assertThat(refusal.message).contains("theora")
    }

    @Test
    fun `a mapped family with malformed parameters refuses rather than dropping them`() {
        // `avc1` with six characters that are not hexadecimal: the family is mapped and the string
        // is not, and answering a `Format` that merely states less than the transport did would
        // reach the device gate as an unknown, which refuses nothing.
        assertThrows(UnsupportedRealtimeCodecException::class.java) { RealtimeFormats.formatFor("avc1.zzzzzz", codedSize = null) }
        assertThrows(UnsupportedRealtimeCodecException::class.java) { RealtimeFormats.formatFor("avc1", codedSize = null) }
        assertThrows(UnsupportedRealtimeCodecException::class.java) { RealtimeFormats.formatFor("mp4a.40", codedSize = null) }
    }

    @Test
    fun `an mp4a string that is not aac refuses rather than being decoded as aac`() {
        // Object type indication 0x69 is MPEG-2 Part 3 audio — MP3. Mapping the whole of the `mp4a`
        // fourcc to AAC is the defect this table exists to not have.
        val refusal = assertThrows(UnsupportedRealtimeCodecException::class.java) {
            RealtimeFormats.formatFor("mp4a.69.2", codedSize = null)
        }
        assertThat(refusal.codec).isEqualTo("mp4a.69.2")
    }

    @Test
    fun `a registered fourcc this table does not claim refuses`() {
        // `avc2` is a real RFC 6381 sample entry name and is deliberately absent: a fourcc carries
        // ADR-0018 rule 4's codec-specific-data obligation, so admitting one nothing publishes would
        // add a branch of that rule no transport exercises.
        assertThrows(UnsupportedRealtimeCodecException::class.java) { RealtimeFormats.formatFor("avc2.42c01e", codedSize = null) }
    }

    @Test
    fun `an empty codec string refuses`() {
        val refusal = assertThrows(UnsupportedRealtimeCodecException::class.java) { RealtimeFormats.formatFor("", codedSize = null) }
        assertThat(refusal.codec).isEqualTo("")
    }

    // --- The coded size (#353) ------------------------------------------------------------------

    @Test
    fun `a stated size reaches the format, because a decoder cannot be configured without one`() {
        val format = RealtimeFormats.formatFor(OBSERVED_HEV1, RealtimeTrack.CodedSize(3520, 3520))

        assertThat(format.width).isEqualTo(3520)
        assertThat(format.height).isEqualTo(3520)
    }

    @Test
    fun `an unstated size leaves the format unsized rather than zeroed`() {
        val format = RealtimeFormats.formatFor(OBSERVED_HEV1, codedSize = null)

        // Media3's own "not set", which is the same -1 the seam carries. A zero would be a claim
        // that the picture has no area, and `MediaCodec` refuses that everywhere.
        assertThat(format.width).isEqualTo(Format.NO_VALUE)
        assertThat(format.height).isEqualTo(Format.NO_VALUE)
    }

    @Test
    fun `half a size cannot be expressed, and a non-positive one is refused where it is built`() {
        // The test this replaces asserted that a width without a height produced an unsized format.
        // That combination is now unrepresentable — `CodedSize` takes both or is absent — so what is
        // left to pin is the refusal that replaced the silent degradation.
        listOf(0 to 720, 1280 to 0, -1 to 720, 1280 to -1).forEach { (width, height) ->
            assertThrows("${width}x$height is not a size", IllegalArgumentException::class.java) {
                RealtimeTrack.CodedSize(width, height)
            }
        }
    }

    @Test
    fun `an unmapped codec is still refused when a size is stated`() {
        // The size is not a way past the codec table: a track can be perfectly well measured and
        // still carry a string nothing maps, and the refusal that matters is the codec's.
        assertThrows(UnsupportedRealtimeCodecException::class.java) {
            RealtimeFormats.formatFor("nonsense.1", RealtimeTrack.CodedSize(1280, 720))
        }
    }

    private companion object {
        /** The codec string #353 observed on a live broadcast: HEVC, configured in band. */
        const val OBSERVED_HEV1 = "hev1.1.6.L180.80"
    }
}
