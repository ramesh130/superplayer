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

import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MalformedRealtimeBitstreamException
import com.superplayer.core.RealtimeTrack.CodecConfiguration
import org.junit.Test

/**
 * The codec-specific-data branch, over literal bytes and nothing else (ADR-0018 rule 4, issue #345).
 *
 * ## Why the fixtures are what they are
 *
 * The `avcC` and the two keyframe payloads below are **#340's**, byte for byte: that spike drove
 * real MoQ code over a real encoder and dumped what arrived at the API boundary, which is the only
 * observation of this boundary anyone here has. Where a dump is a prefix of a longer record the
 * fixture says so and says where the rest came from, because a fixture that quietly invents bytes
 * and calls them observed is worse than one that invents them openly.
 *
 * Two of the four cases are the ones that catch an implementation that looks right: a record whose
 * NAL length field is **not** four bytes — #340's is, "which is exactly the value that makes a wrong
 * assumption invisible in testing and wrong later" — and a record that stops in the middle of a
 * parameter set, which a reader that trusts its own counts walks straight past.
 *
 * Nothing here is a player: what a frame carrying its parameter sets in band does to one is
 * `RealtimePlaybackTest`'s, through the public `Player` API, because `docs/testing.md` bars
 * asserting past the facade and this conversion is `internal`.
 */
class CodecConfigurationRecordsTest {

    @Test
    fun `an observed avcC becomes one Annex-B parameter set per entry`() {
        val configuration = CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(ObservedBytes.AVCC))

        // Two entries, SPS then PPS: what `MediaCodec` reads as `csd-0` and `csd-1` for video/avc.
        assertThat(configuration.initializationData).hasSize(2)
        assertThat(configuration.initializationData[0]).isEqualTo(ANNEX_B_START_CODE + ObservedBytes.SPS)
        assertThat(configuration.initializationData[1]).isEqualTo(ANNEX_B_START_CODE + ObservedBytes.SYNTHESIZED_PPS)
    }

    /**
     * The fixture's provenance, asserted rather than promised.
     *
     * #340 printed the first 24 bytes of a 40-byte record and, separately, an `avc3` keyframe from
     * the same encoder whose first NAL unit is the same SPS. [ObservedBytes.AVCC] is built from both, and
     * this is the overlap that makes that legitimate: where the two dumps describe the same bytes,
     * they agree.
     */
    @Test
    fun `the two observed dumps carry the same SPS where they overlap`() {
        assertThat(ObservedBytes.SPS.copyOfRange(0, 16)).isEqualTo(ObservedBytes.AVCC_DUMP.copyOfRange(8, 24))
    }

    @Test
    fun `an observed avcC declares the four-byte NAL length its ff says`() {
        val configuration = CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(ObservedBytes.AVCC))

        // `ff` is `111111` reserved and `lengthSizeMinusOne = 3`.
        assertThat(configuration.nalLengthSize).isEqualTo(4)
    }

    /**
     * The case a hard-coded four would pass every other test while failing on.
     *
     * The record is #340's with one byte changed — `ff` to `fd`, `lengthSizeMinusOne = 1` — so what
     * separates this from the test above is the declaration and nothing else. Both halves are
     * asserted: the size that is read, and a sample framed that way converting correctly, because a
     * size read and then not applied is a number nobody uses.
     */
    @Test
    fun `a record whose NAL length size is not four is read as what it says`() {
        val configuration =
            CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(avcCDeclaringLengthSize(2)))

        assertThat(configuration.nalLengthSize).isEqualTo(2)
        // Two units behind two-byte lengths. Read as four-byte lengths the first would declare
        // 0x000365aa bytes and the conversion would refuse; read as two, it is exactly this.
        val sample = bytes("00 03 65 aa bb 00 02 41 cc")
        assertThat(configuration.toAnnexB(sample))
            .isEqualTo(ANNEX_B_START_CODE + bytes("65 aa bb") + ANNEX_B_START_CODE + bytes("41 cc"))
    }

    /**
     * An `hvcC`'s arrays as the single entry `MediaCodec` takes for video/hevc.
     *
     * **Source-derived and not observed**: #340's caveat 2 says its spike saw H.264 only, so this
     * fixture is ISO/IEC 14496-15 §8.3.3.1.2 written out by hand — a 22-byte header, three arrays of
     * one NAL unit each. ADR-0018 rule 4 asks that the first transport to carry HEVC confirm the
     * behaviour rather than assume this test already did.
     */
    @Test
    fun `an hvcC's three arrays become one csd entry in the order the record lists them`() {
        val configuration = CodecConfigurationRecords.of("hvc1.1.6.L93.B0", CodecConfiguration.Record(SYNTHESIZED_HVCC))

        assertThat(configuration.initializationData).hasSize(1)
        assertThat(configuration.initializationData.single()).isEqualTo(
            ANNEX_B_START_CODE + bytes("40 01 0c") +
                ANNEX_B_START_CODE + bytes("42 01 01 01") +
                ANNEX_B_START_CODE + bytes("44 01"),
        )
        assertThat(configuration.nalLengthSize).isEqualTo(4)
    }

    /**
     * A record that ends inside a parameter set is refused, not read to wherever it happens to stop.
     *
     * The fixture is #340's dump **exactly as printed** — 24 bytes of a 40-byte record, declaring a
     * 24-byte SPS with 16 bytes behind it — so the truncation under test is one that really
     * happened, in a bug report rather than in an imagination.
     */
    @Test
    fun `a truncated record is refused rather than read past`() {
        val refusal = runCatching {
            CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(ObservedBytes.AVCC_DUMP))
        }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(MalformedRealtimeBitstreamException::class.java)
        assertThat((refusal as MalformedRealtimeBitstreamException).codec).isEqualTo(OBSERVED_AVC1_CODEC)
        assertThat(refusal.reason).contains("runs past the end of 24 bytes")
    }

    /**
     * The branch is the fourcc's, and these two strings differ in nothing else.
     *
     * #340 observed exactly this pair from one encoder over one container — `avc1.42c01e` with a
     * 40-byte record and no in-band parameter sets, `avc3.42c01e` with none and parameter sets
     * before every keyframe — which is the observation that moved this branch off the container.
     */
    @Test
    fun `avc1 and avc3 of the same profile take different branches`() {
        val outOfBand = CodecConfigurationRecords.of("avc1.42c01e", CodecConfiguration.Record(ObservedBytes.AVCC))
        val inBand = CodecConfigurationRecords.of("avc3.42c01e", CodecConfiguration.InBand)

        assertThat(outOfBand.initializationData).hasSize(2)
        assertThat(outOfBand.nalLengthSize).isEqualTo(4)
        // Asserted rather than assumed, which is the acceptance criterion in as many words: a
        // self-describing stream is configured with nothing and its samples are converted by nothing.
        assertThat(inBand.initializationData).isEmpty()
        assertThat(inBand.nalLengthSize).isEqualTo(TrackConfiguration.SAMPLES_ARE_ANNEX_B)
        assertThat(inBand.toAnnexB(ObservedBytes.AVC3_KEYFRAME)).isSameInstanceAs(ObservedBytes.AVC3_KEYFRAME)
    }

    @Test
    fun `a record on a self-describing fourcc is refused rather than set as csd`() {
        val refusal = runCatching {
            CodecConfigurationRecords.of("avc3.42c01e", CodecConfiguration.Record(ObservedBytes.AVCC))
        }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(MalformedRealtimeBitstreamException::class.java)
        assertThat((refusal as MalformedRealtimeBitstreamException).reason).contains("in band")
    }

    @Test
    fun `a fourcc that carries a record is refused when none was handed over`() {
        val refusal = runCatching {
            CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.InBand)
        }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(MalformedRealtimeBitstreamException::class.java)
        assertThat((refusal as MalformedRealtimeBitstreamException).reason).contains("out of band")
    }

    /**
     * The mistake the old seam invited: a transport that converted the record itself.
     *
     * Annex-B parameter sets handed over where a record belongs begin `00 00 00 01`, so
     * `configurationVersion` reads zero and the refusal names the record. This is the one direction
     * of obligation 7 that a byte can catch, and catching it is half of why the shape is sealed.
     */
    @Test
    fun `Annex-B parameter sets handed over as a record are refused by the version byte`() {
        val refusal = runCatching {
            CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(ObservedBytes.AVC3_KEYFRAME))
        }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(MalformedRealtimeBitstreamException::class.java)
        assertThat((refusal as MalformedRealtimeBitstreamException).reason).contains("configurationVersion is 0")
    }

    /**
     * A sample whose length field overruns its own buffer is refused.
     *
     * The fixture is #340's `avc1` keyframe as printed: a `00 00 02 6f` length field declaring 623
     * bytes in front of a dump that carries 28. A conversion that trusted the field would read the
     * whole frame plus whatever follows it in memory.
     */
    @Test
    fun `a sample whose length field runs past the frame is refused`() {
        val configuration = CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(ObservedBytes.AVCC))

        val refusal = runCatching { configuration.toAnnexB(ObservedBytes.AVC1_KEYFRAME_DUMP) }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(MalformedRealtimeBitstreamException::class.java)
        assertThat((refusal as MalformedRealtimeBitstreamException).reason).contains("a 623-byte NAL unit")
    }

    @Test
    fun `a sample that ends inside a length field is refused rather than treated as padding`() {
        val configuration = CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(ObservedBytes.AVCC))

        // One whole unit, then three bytes where a four-byte length field should be.
        val refusal = runCatching { configuration.toAnnexB(bytes("00 00 00 02 65 aa 00 00 00")) }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(MalformedRealtimeBitstreamException::class.java)
    }

    /**
     * A four-byte length field with its top bit set is refused, not read as a negative length.
     *
     * The case a bounds check written as `offset + length > size` walks straight through: `ff ff ff
     * ff` is -1 as a signed `Int`, the check reads as satisfied, and what a consumer sees is a
     * crash inside SuperPlayer naming an array rather than the transport's contract broken. It is
     * reachable from noise on an `avc1` track, which is exactly the input this file exists for.
     */
    @Test
    fun `a length field whose top bit is set is refused rather than read as negative`() {
        val configuration = CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(ObservedBytes.AVCC))

        val refusal = runCatching { configuration.toAnnexB(bytes("ff ff ff ff 65 aa")) }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(MalformedRealtimeBitstreamException::class.java)
        assertThat((refusal as MalformedRealtimeBitstreamException).reason).contains("runs past the end")
    }

    /**
     * The one NAL length width the grammar forbids is refused rather than honoured.
     *
     * spec: ISO/IEC 14496-15 §5.3.3.1.3 — `lengthSizeMinusOne` shall be 0, 1 or 3, so three-byte
     *   length fields do not exist. A record declaring one was written by nothing legal, and
     *   framing every sample of the track at that width would look like it worked.
     */
    @Test
    fun `a record declaring a three-byte NAL length field is refused`() {
        val refusal = runCatching {
            CodecConfigurationRecords.of(OBSERVED_AVC1_CODEC, CodecConfiguration.Record(avcCDeclaringLengthSize(3)))
        }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(MalformedRealtimeBitstreamException::class.java)
        assertThat((refusal as MalformedRealtimeBitstreamException).reason).contains("3-byte NAL length field")
    }

    /**
     * The control that keeps the conversion off every codec that is not H.264 or H.265.
     *
     * An AAC track's record is an AudioSpecificConfig with no NAL unit in it, and Media3 hands it to
     * `MediaCodec` as `csd-0` exactly as received; a reader that converted it would hand a decoder a
     * start code and two bytes of noise. Audio beside video is #346's, and this is the branch it
     * starts from.
     */
    @Test
    fun `a codec whose record holds no NAL units passes it through unchanged`() {
        val record = bytes("12 10") // AudioSpecificConfig: AAC-LC, 44.1 kHz, stereo.

        val configuration = CodecConfigurationRecords.of("mp4a.40.2", CodecConfiguration.Record(record))

        assertThat(configuration.initializationData.single()).isEqualTo(record)
        assertThat(configuration.nalLengthSize).isEqualTo(TrackConfiguration.SAMPLES_ARE_ANNEX_B)
    }

    private companion object {

        /** #340's `avc1` codec string, verbatim. */
        const val OBSERVED_AVC1_CODEC = "avc1.42c01e"

        /** ITU-T H.264 Annex B's four-byte start code. */
        val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)

        /**
         * An `hvcC`, **synthesized** from ISO/IEC 14496-15 §8.3.3.1.2 because #340 observed none: 22
         * header bytes whose last carries `lengthSizeMinusOne = 3`, then three arrays — VPS, SPS and
         * PPS, one NAL unit each.
         */
        val SYNTHESIZED_HVCC = bytes(
            "01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 ff" +
                " 03" +
                " a0 00 01 00 03 40 01 0c" +
                " a1 00 01 00 04 42 01 01 01" +
                " a2 00 01 00 02 44 01",
        )

        /** [ObservedBytes.AVCC] with its `lengthSizeMinusOne` byte rewritten and nothing else touched. */
        fun avcCDeclaringLengthSize(size: Int): ByteArray = ObservedBytes.AVCC.copyOf().also {
            // The six bits above `lengthSizeMinusOne` are reserved and set to 1, which is the `f` of
            // the observed `ff`.
            it[4] = (0xFC or (size - 1)).toByte()
        }

        /** A hex dump as bytes, so a fixture can be pasted out of a bug report and read back in it. */
        fun bytes(hex: String): ByteArray =
            hex.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
    }
}
