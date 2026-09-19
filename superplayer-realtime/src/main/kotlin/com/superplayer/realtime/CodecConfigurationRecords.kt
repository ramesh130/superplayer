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

import com.superplayer.core.MalformedRealtimeBitstreamException
import com.superplayer.core.RealtimeTrack
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * What one track's [RealtimeTrack.codecConfiguration] means to a decoder: the initialization data a
 * `Format` carries, and the framing the samples of that track arrive in.
 *
 * @property codec The codec string the track was declared as, carried so that a refusal raised on a
 *   *sample* names the same codec a refusal raised on the record does.
 * @property initializationData What Media3 hands `MediaCodec` as `csd-0`, `csd-1`, … in order.
 *   Empty for a self-describing stream, which needs none.
 * @property nalLengthSize The size in bytes of the length field prefixing each NAL unit of this
 *   track's **samples**, or [SAMPLES_ARE_ANNEX_B] where they are not length-prefixed at all. It is
 *   read out of the configuration record rather than assumed (ADR-0018 rule 4): four is the common
 *   value and therefore the one whose assumption passes every test and fails in the field.
 */
internal class TrackConfiguration(
    val codec: String,
    val initializationData: List<ByteArray>,
    val nalLengthSize: Int,
) {

    /**
     * [payload] in the framing Media3's renderers read, which for a length-prefixed track means
     * converted and for every other track means untouched.
     *
     * Reading the length field size out of a record and then not applying it to the samples it
     * describes would leave the record's own statement unread, which is the defect ADR-0018 rule 4
     * names from the other end: an `avc1` publisher's frames are length-prefixed, and a decoder fed
     * them as though they were Annex-B configures cleanly and renders nothing.
     *
     * @throws MalformedRealtimeBitstreamException if a length field runs past the end of [payload].
     */
    fun toAnnexB(payload: ByteArray): ByteArray =
        if (nalLengthSize == SAMPLES_ARE_ANNEX_B) payload else annexBFromLengthPrefixed(codec, payload, nalLengthSize)

    companion object {

        /** [nalLengthSize] for a track whose samples already carry Annex-B start codes. */
        const val SAMPLES_ARE_ANNEX_B = 0

        /** A self-describing track: nothing out of band, and samples that need no conversion. */
        fun selfDescribing(codec: String) = TrackConfiguration(codec, emptyList(), SAMPLES_ARE_ANNEX_B)
    }
}

/**
 * The one place a codec's out-of-band configuration record is read, keyed on the codec's **fourcc**
 * and never on the container the frames arrived in (ADR-0018 rule 4).
 *
 * #340 observed all six combinations of container and codec shape and found the container decides
 * nothing: `avc1.*` and `hvc1.*` carry an `avcC`/`hvcC` record and no in-band parameter sets, while
 * `avc3.*` and `hev1.*` carry parameter sets before every keyframe and no record — the same media,
 * through three containers, either way. So the branch below reads the fourcc alone, and a
 * [RealtimeTrack.CodecConfiguration] that contradicts it is refused rather than reinterpreted.
 *
 * **The H.264 half is observed and the H.265 half is not.** #340's spike ran real MoQ code over a
 * real encoder, and its caveat 2 says plainly that `hvcC` and `av1C` behaviour there is derived from
 * source rather than seen. What is written below for `hvc1` is ISO/IEC 14496-15 §8.3.3.1.2 read as a
 * document; the first transport to carry HEVC is expected to confirm it, which is what ADR-0018
 * rule 4 asks for in as many words.
 */
internal object CodecConfigurationRecords {

    /**
     * [configuration] read as the fourcc of [codec] says it must be read.
     *
     * @throws MalformedRealtimeBitstreamException if the shape contradicts the fourcc, or if a
     *   record of a fourcc that carries one does not parse.
     */
    fun of(codec: String, configuration: RealtimeTrack.CodecConfiguration): TrackConfiguration {
        // RFC 6381 §3.3: the first dot-separated element is the sample entry name. An SDP encoding
        // name carries no dot and falls through the same take, which is what puts `h264` in the
        // `else` branch below: SDP states no sample entry, so it states no expectation either.
        val fourcc = codec.substringBefore('.').lowercase(Locale.ROOT)
        return when (fourcc) {
            "avc1" -> parseAvcConfigurationRecord(codec, recordOf(codec, fourcc, configuration))

            "hvc1" -> parseHevcConfigurationRecord(codec, recordOf(codec, fourcc, configuration))

            in SELF_DESCRIBING_FOURCCS -> {
                // Asserted rather than assumed: a record here is obligation 7 broken in the
                // direction that used to be silent. Media3 would take the bytes as `csd-0`, the
                // decoder would be configured from parameter sets it is about to be sent again in
                // band, and what a viewer sees depends on which of the two the decoder believes.
                if (configuration is RealtimeTrack.CodecConfiguration.Record) {
                    throw MalformedRealtimeBitstreamException(
                        codec,
                        "the fourcc $fourcc carries its parameter sets in band, so no configuration record is expected, " +
                            "but one of ${configuration.bytes.size} bytes was handed over",
                    )
                }
                TrackConfiguration.selfDescribing(codec)
            }

            else -> when (configuration) {
                // Every other codec this library maps — VP9, AV1, AAC, Opus — states its
                // configuration in a record with no NAL units in it at all, and Media3 hands such a
                // record to `MediaCodec` as `csd-0` unchanged: AV1's `av1C` (AV1 ISOBMFF binding
                // §2.3), AAC's AudioSpecificConfig (ISO/IEC 14496-3 §1.6.2) and Opus's
                // identification header (RFC 7845 §5.1). There is nothing to convert, so nothing
                // is, and neither is anything demanded: a codec with no record simply has none.
                // Audio beside video is #346's, and this branch is where it starts.
                is RealtimeTrack.CodecConfiguration.Record ->
                    TrackConfiguration(codec, listOf(configuration.bytes.copyOf()), TrackConfiguration.SAMPLES_ARE_ANNEX_B)

                RealtimeTrack.CodecConfiguration.InBand -> TrackConfiguration.selfDescribing(codec)
            }
        }
    }

    /** The record [fourcc] requires, or the refusal for a transport that handed over none. */
    private fun recordOf(
        codec: String,
        fourcc: String,
        configuration: RealtimeTrack.CodecConfiguration,
    ): ByteArray = when (configuration) {
        is RealtimeTrack.CodecConfiguration.Record -> configuration.bytes

        // The other direction of the same refusal, and the one that would otherwise strand a player
        // in `STATE_BUFFERING`: a decoder configured with no parameter sets for a stream that sends
        // none in band has nothing to decode the first keyframe against.
        RealtimeTrack.CodecConfiguration.InBand -> throw MalformedRealtimeBitstreamException(
            codec,
            "the fourcc $fourcc carries its parameter sets out of band, so a configuration record is required, " +
                "but the track declared CodecConfiguration.InBand",
        )
    }

    /**
     * An `AVCDecoderConfigurationRecord` as the initialization data a decoder is configured with,
     * plus the NAL length field size it declares for this track's samples.
     *
     * spec: ISO/IEC 14496-15 §5.3.3.1.2 — `configurationVersion`, `AVCProfileIndication`,
     *   `profile_compatibility`, `AVCLevelIndication`, six reserved bits and `lengthSizeMinusOne`,
     *   three reserved bits and `numOfSequenceParameterSets`, then that many
     *   `(unsigned int(16) length, NAL unit)` pairs, then `numOfPictureParameterSets` and that many
     *   more. The parameter sets' own lengths are **16-bit by the record's grammar**, which is not
     *   `lengthSizeMinusOne`: that field describes the *samples*, and conflating the two is the
     *   first way to read this record wrongly.
     *
     * The parameter sets come out as one initialization-data entry each, SPS before PPS, because
     * that is what `MediaCodec` reads as `csd-0` and `csd-1` for `video/avc`.
     */
    private fun parseAvcConfigurationRecord(codec: String, record: ByteArray): TrackConfiguration {
        val reader = RecordReader(codec, record)
        reader.expectConfigurationVersion()
        reader.skip(AVC_PROFILE_AND_LEVEL_BYTES)
        val nalLengthSize = (reader.readByte() and LENGTH_SIZE_MINUS_ONE_MASK) + 1
        val parameterSets = mutableListOf<ByteArray>()
        // The low five bits: the three above them are reserved and set to 1 by the grammar.
        repeat(reader.readByte() and SPS_COUNT_MASK) { parameterSets += reader.readAnnexBUnit() }
        // A whole byte, unlike the SPS count, which is the asymmetry in the record rather than a
        // transcription slip.
        repeat(reader.readByte()) { parameterSets += reader.readAnnexBUnit() }
        return TrackConfiguration(codec, parameterSets, nalLengthSize)
    }

    /**
     * An `HEVCDecoderConfigurationRecord`, read the same way and answered as **one** entry.
     *
     * spec: ISO/IEC 14496-15 §8.3.3.1.2 — a 22-byte header whose last byte carries
     *   `lengthSizeMinusOne` in its low two bits, then `numOfArrays`, then per array a byte of
     *   completeness and NAL unit type, a 16-bit count and that many `(length, NAL unit)` pairs.
     *
     * Every array's units are concatenated into a single buffer, VPS, SPS and PPS in the order the
     * record lists them, because `MediaCodec` for `video/hevc` takes all of them as `csd-0`. That is
     * the one place the two families differ here, and it is the decoder's convention rather than the
     * record's.
     */
    private fun parseHevcConfigurationRecord(codec: String, record: ByteArray): TrackConfiguration {
        val reader = RecordReader(codec, record)
        reader.expectConfigurationVersion()
        reader.skip(HEVC_HEADER_BYTES_BEFORE_LENGTH_SIZE)
        val nalLengthSize = (reader.readByte() and LENGTH_SIZE_MINUS_ONE_MASK) + 1
        var parameterSets = ByteArray(0)
        repeat(reader.readByte()) {
            reader.skip(1) // array_completeness, a reserved bit and NAL_unit_type: none of it configures anything.
            repeat(reader.readUnsignedShort()) { parameterSets += reader.readAnnexBUnit() }
        }
        return TrackConfiguration(codec, listOf(parameterSets), nalLengthSize)
    }

    /** Fourccs whose streams carry their parameter sets in the bitstream (#340's observed pair, and HEVC's twin). */
    private val SELF_DESCRIBING_FOURCCS = setOf("avc3", "hev1")

    /** `AVCProfileIndication`, `profile_compatibility` and `AVCLevelIndication`: read by the codec string, not here. */
    private const val AVC_PROFILE_AND_LEVEL_BYTES = 3

    /** Bytes 1 to 20 of an `hvcC`: profile, tier, level, chroma and frame-rate fields none of which configure a decoder. */
    private const val HEVC_HEADER_BYTES_BEFORE_LENGTH_SIZE = 20

    /** `lengthSizeMinusOne` is the low two bits of its byte; the six above it are reserved. */
    private const val LENGTH_SIZE_MINUS_ONE_MASK = 0x03

    /** `numOfSequenceParameterSets` is the low five bits of its byte; the three above it are reserved. */
    private const val SPS_COUNT_MASK = 0x1F
}

/**
 * A run of length-prefixed NAL units as one Annex-B buffer, with a start code before each.
 *
 * spec: ISO/IEC 14496-15 §5.3.3.1.2 — a record's parameter sets and an `avc1`/`hvc1` track's samples
 *   both carry each NAL unit behind an unsigned big-endian length, of [lengthSize] bytes.
 * spec: ITU-T H.264 Annex B / ISO/IEC 14496-10 Annex B — the byte stream format a `MediaCodec`
 *   configured by hand is fed: each NAL unit preceded by `00 00 00 01`. Four bytes rather than
 *   three, because the four-byte start code is legal everywhere the three-byte one is and the
 *   three-byte one is not legal before the first unit of an access unit.
 *
 * Pure, and the reason it is: this is the conversion whose failures are invisible. A length field
 * read at the wrong width produces a buffer that is structurally a bitstream and semantically
 * noise — which is why every departure from the bytes handed in is refused here, at a point a unit
 * test can reach with literal arrays, rather than absorbed and handed to a decoder.
 *
 * @throws MalformedRealtimeBitstreamException if a length field or the unit it measures runs past
 *   the end of [source].
 */
internal fun annexBFromLengthPrefixed(codec: String, source: ByteArray, lengthSize: Int): ByteArray {
    val reader = RecordReader(codec, source)
    val converted = ByteArrayOutputStream(source.size)
    // A trailing byte too few for a length field is a truncation like any other, and is refused by
    // the reader rather than ignored as padding: a sample that ends mid-field is one whose last
    // unit is missing, and a decoder told nothing about it renders the frame without it.
    while (!reader.exhausted) converted.write(reader.readAnnexBUnit(lengthSize))
    return converted.toByteArray()
}

/**
 * A bounds-checked walk over a record or a sample, refusing at the first byte that is not there.
 *
 * Every read is checked because the whole family of defects this file exists for is "read past the
 * end and carry on": Kotlin would raise an `IndexOutOfBoundsException` naming an array, which
 * reaches a consumer as a crash inside SuperPlayer rather than as the transport's contract broken.
 */
private class RecordReader(private val codec: String, private val source: ByteArray) {

    private var offset: Int = 0

    val exhausted: Boolean get() = offset >= source.size

    fun skip(count: Int) {
        requireBytes(count, "a $count-byte field")
        offset += count
    }

    fun readByte(): Int {
        requireBytes(1, "a one-byte field")
        return source[offset++].toInt() and 0xFF
    }

    fun readUnsignedShort(): Int = readLength(2, "a two-byte count")

    /**
     * The next length-prefixed NAL unit, behind an Annex-B start code.
     *
     * [lengthSize] defaults to the two bytes a *configuration record*'s parameter sets carry by its
     * own grammar; a track's samples pass the size the record declared for them.
     */
    fun readAnnexBUnit(lengthSize: Int = PARAMETER_SET_LENGTH_BYTES): ByteArray {
        val start = offset
        val length = readLength(lengthSize, "a $lengthSize-byte NAL unit length at offset $start")
        if (length == 0) {
            throw MalformedRealtimeBitstreamException(codec, "a NAL unit length field at offset $start declares zero bytes")
        }
        requireBytes(length, "a $length-byte NAL unit declared at offset $start")
        val unit = ANNEX_B_START_CODE + source.copyOfRange(offset, offset + length)
        offset += length
        return unit
    }

    /** [size] bytes as one unsigned big-endian integer. */
    private fun readLength(size: Int, describedAs: String): Int {
        requireBytes(size, describedAs)
        var value = 0
        repeat(size) { value = (value shl 8) or (source[offset++].toInt() and 0xFF) }
        return value
    }

    /**
     * `configurationVersion`, which both records fix at 1.
     *
     * Checked rather than skipped because it is the one byte that catches the mistake a transport is
     * most likely to make here: a record already converted to Annex-B starts `00 00 00 01`, so its
     * first byte is zero and the refusal names the record instead of the parse failing four fields
     * later with a count that happens to be plausible.
     */
    fun expectConfigurationVersion() {
        val version = readByte()
        if (version != CONFIGURATION_VERSION) {
            throw MalformedRealtimeBitstreamException(
                codec,
                "configurationVersion is $version rather than $CONFIGURATION_VERSION, so these bytes are not a " +
                    "configuration record — Annex-B parameter sets handed over in place of one begin 00 00 00 01",
            )
        }
    }

    private fun requireBytes(bytes: Int, describedAs: String) {
        if (offset + bytes > source.size) {
            throw MalformedRealtimeBitstreamException(
                codec,
                "$describedAs runs past the end of ${source.size} bytes",
            )
        }
    }

    private companion object {

        /** ISO/IEC 14496-15 §5.3.3.1.2 and §8.3.3.1.2: both records fix `configurationVersion` at 1. */
        const val CONFIGURATION_VERSION = 1

        /** `unsigned int(16) sequenceParameterSetLength`, and its PPS and HEVC-array twins. */
        const val PARAMETER_SET_LENGTH_BYTES = 2

        /** ITU-T H.264 Annex B's four-byte start code prefix. */
        val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
