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

package com.superplayer.testmedia

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * A complete, tiny DASH stream generated in memory: an MPD, a fragmented-MP4 initialization
 * segment, and as many media segments as the caller asks for, as [resources] or as files on disk.
 *
 * The counterpart to [SyntheticHlsStream], and deliberately its mirror image: same codec, same
 * sample rate, same channel count, same declared bitrate, so that a test comparing what the facade
 * reports for the two is comparing the *protocols* and not two unrelated pieces of media.
 *
 * Generated rather than checked in for the same reasons as the HLS stream — what the test asserts
 * about is visible in this file rather than buried in a binary, and the repository carries no media
 * it would have to license.
 *
 * ## Why this one has to be fragmented MP4
 *
 * HLS can carry bare AAC in ADTS framing, which is why [SyntheticHlsStream] is a few hundred bytes
 * of header-and-payload. DASH cannot: Media3 chooses a DASH segment's extractor from the
 * representation's *container* MIME type, and `BundledChunkExtractor` maps everything that is not
 * Matroska, an image, or a subtitle format onto `FragmentedMp4Extractor`. So a synthetic DASH
 * stream has to be real ISO BMFF, which is what everything below builds.
 *
 * spec: ISO/IEC 14496-12 for the box structures, ISO/IEC 23009-1 §5.3 for the MPD. Every box is
 * cited at the function that writes it.
 */
public object SyntheticDashStream {

    /** Any scheme works: a test serving these from a fake data source keys purely on the URI. */
    private const val BASE_URI = "fake://superplayer.test/dash/"

    /** `.mpd` is load-bearing: Media3 infers the content type from the URI's extension. */
    public const val MANIFEST_URI: String = BASE_URI + "manifest.mpd"
    private const val INITIALIZATION_NAME = "init.mp4"
    private const val INITIALIZATION_URI = BASE_URI + INITIALIZATION_NAME

    private fun segmentName(index: Int) = "segment$index.m4s"

    /** Declared as the representation's `@bandwidth`, and therefore what the track should report. */
    public const val DECLARED_BITRATE_BPS: Int = 128_000

    /** `mp4a.40.2` — AAC-LC. RFC 6381 §3.3, as referenced by ISO/IEC 23009-1 §5.3.7.2. */
    public const val DECLARED_CODECS: String = "mp4a.40.2"

    public const val DECLARED_SAMPLE_RATE_HZ: Int = 44_100
    public const val DECLARED_CHANNEL_COUNT: Int = 2

    /** spec: ISO/IEC 14496-3 — an AAC-LC frame carries 1024 samples. */
    private const val SAMPLES_PER_FRAME = 1024

    /**
     * Arbitrary. The payload is never decoded — Robolectric's shadow codec passes bytes through —
     * so it only has to be a plausible frame size.
     */
    private const val SAMPLE_SIZE_BYTES = 64

    /** Roughly two seconds, matching [SyntheticHlsStream]'s single segment. */
    private const val FRAME_COUNT = 87

    private const val TRACK_ID = 1

    /** The media timescale is the sample rate, so a sample's duration is exactly its sample count. */
    private const val TIMESCALE = DECLARED_SAMPLE_RATE_HZ

    private const val MOVIE_TIMESCALE = 1_000

    private const val SEGMENT_DURATION_IN_TIMESCALE = FRAME_COUNT * SAMPLES_PER_FRAME

    /**
     * What a single-segment MPD advertises as `@mediaPresentationDuration`, in the milliseconds the
     * player reports a duration in.
     */
    public const val DURATION_MS: Long = SEGMENT_DURATION_IN_TIMESCALE * 1_000L / TIMESCALE

    /** What a stream of [segmentCount] segments advertises as its duration. */
    public fun durationMs(segmentCount: Int): Long = DURATION_MS * segmentCount

    /**
     * Everything the player will ask for, keyed by URI, and nothing else: an unknown URI is a test
     * failure.
     *
     * A map rather than a populated fake data source, so that this module names no Media3 type — see
     * its build script for why that is what keeps it below every module that plays these streams.
     *
     * [segmentCount] is one by default, which is the stream this file has always emitted: asking for
     * one produces byte-identical bytes to the single-segment form. More is what a test addressing a
     * *later* segment needs — a fault at media segment 1 has to have a segment 1 to land on — and
     * every extra segment differs from the first only in its fragment sequence number and its
     * decode time, which is what makes them play back to back rather than all at zero.
     */
    public fun resources(segmentCount: Int = 1): Map<String, ByteArray> {
        require(segmentCount >= 1) { "A stream needs at least one segment, was $segmentCount" }
        return buildMap {
            put(MANIFEST_URI, manifest(segmentCount).toByteArray())
            put(INITIALIZATION_URI, initializationSegment())
            repeat(segmentCount) { index -> put(BASE_URI + segmentName(index), mediaSegment(index)) }
        }
    }

    /**
     * spec: ISO/IEC 23009-1 §5.3 — a static, single-period, single-representation MPD.
     *
     * `SegmentList` rather than `SegmentTemplate` because the segments are listed one by one anyway:
     * a template would add a substitution grammar to read for no gain here.
     */
    private fun manifest(segmentCount: Int): String {
        val durationSeconds = SEGMENT_DURATION_IN_TIMESCALE.toDouble() * segmentCount / TIMESCALE
        val segmentUrls = (0 until segmentCount).map { index ->
            "          <SegmentURL media=\"${segmentName(index)}\"/>"
        }

        // Joined lines rather than an indented raw string, for the reason `SyntheticHlsStream`'s
        // media playlist gives and one more this document cannot survive: a `trimIndent` block whose
        // interpolated value is itself multi-line has no common indent left to trim, so every line
        // — the XML declaration included — would keep the source's own eight spaces, and an XML
        // declaration preceded by whitespace is not a document any parser will accept.
        return (
            listOf(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"",
                "     profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\"",
                "     type=\"static\"",
                "     mediaPresentationDuration=\"${xsDuration(durationSeconds)}\"",
                "     minBufferTime=\"PT1S\">",
                "  <Period id=\"0\">",
                "    <AdaptationSet mimeType=\"audio/mp4\" segmentAlignment=\"true\">",
                "      <Representation id=\"0\"",
                "                      bandwidth=\"$DECLARED_BITRATE_BPS\"",
                "                      codecs=\"$DECLARED_CODECS\"",
                "                      audioSamplingRate=\"$DECLARED_SAMPLE_RATE_HZ\">",
                "        <AudioChannelConfiguration",
                "            schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\"",
                "            value=\"$DECLARED_CHANNEL_COUNT\"/>",
                "        <SegmentList timescale=\"$TIMESCALE\" " +
                    "duration=\"$SEGMENT_DURATION_IN_TIMESCALE\">",
                "          <Initialization sourceURL=\"$INITIALIZATION_NAME\"/>",
            ) + segmentUrls + listOf(
                "        </SegmentList>",
                "      </Representation>",
                "    </AdaptationSet>",
                "  </Period>",
                "</MPD>",
            )
            ).joinToString(separator = "\n")
    }

    /** spec: ISO 8601 durations, as required by ISO/IEC 23009-1 §5.3.1.2 for `xs:duration`. */
    private fun xsDuration(seconds: Double): String = String.format(Locale.US, "PT%.6fS", seconds)

    /**
     * spec: ISO/IEC 14496-12 §8.16.2 — an initialization segment is `ftyp` followed by `moov`, and
     * carries no samples. The `moov`'s sample tables are all empty; `mvex` is what declares that the
     * samples arrive later, in fragments.
     */
    private fun initializationSegment(): ByteArray = bytes {
        write(fileTypeBox())
        write(movieBox())
    }

    /** spec: ISO/IEC 14496-12 §4.3. `dash` is the brand ISO/IEC 23009-1 §8.1 requires. */
    private fun fileTypeBox(): ByteArray = box("ftyp") {
        ascii("iso6")
        int32(1)
        ascii("iso6")
        ascii("dash")
    }

    private fun movieBox(): ByteArray = box("moov") {
        write(movieHeaderBox())
        write(trackBox())
        write(movieExtendsBox())
    }

    /**
     * spec: ISO/IEC 14496-12 §8.2.2. Duration is 0 because a fragmented file's duration is not known
     * from the `moov`; `next_track_ID` is one past the only track.
     */
    private fun movieHeaderBox(): ByteArray = fullBox("mvhd", version = 0, flags = 0) {
        int32(0) // creation_time
        int32(0) // modification_time
        int32(MOVIE_TIMESCALE)
        int32(0) // duration
        int32(0x0001_0000) // rate: 1.0
        int16(0x0100) // volume: 1.0
        int16(0) // reserved
        zeros(8) // reserved
        unityMatrix()
        zeros(24) // pre_defined
        int32(TRACK_ID + 1) // next_track_ID
    }

    private fun trackBox(): ByteArray = box("trak") {
        write(trackHeaderBox())
        write(mediaBox())
    }

    /**
     * spec: ISO/IEC 14496-12 §8.3.2. Flags 0x7 are track_enabled | track_in_movie | track_in_preview;
     * `volume` is 1.0 for an audio track and width/height are 0 because it has no visual dimensions.
     */
    private fun trackHeaderBox(): ByteArray = fullBox("tkhd", version = 0, flags = 0x7) {
        int32(0) // creation_time
        int32(0) // modification_time
        int32(TRACK_ID)
        int32(0) // reserved
        int32(0) // duration
        zeros(8) // reserved
        int16(0) // layer
        int16(0) // alternate_group
        int16(0x0100) // volume: 1.0
        int16(0) // reserved
        unityMatrix()
        int32(0) // width
        int32(0) // height
    }

    private fun mediaBox(): ByteArray = box("mdia") {
        write(mediaHeaderBox())
        write(handlerBox())
        write(mediaInformationBox())
    }

    /** spec: ISO/IEC 14496-12 §8.4.2. The media timescale, and nothing this test depends on. */
    private fun mediaHeaderBox(): ByteArray = fullBox("mdhd", version = 0, flags = 0) {
        int32(0) // creation_time
        int32(0) // modification_time
        int32(TIMESCALE)
        int32(0) // duration
        int16(UNDETERMINED_LANGUAGE)
        int16(0) // pre_defined
    }

    /** spec: ISO/IEC 14496-12 §8.4.3. `soun` is the handler type for an audio track. */
    private fun handlerBox(): ByteArray = fullBox("hdlr", version = 0, flags = 0) {
        int32(0) // pre_defined
        ascii("soun")
        zeros(12) // reserved
        ascii("SoundHandler")
        write(0) // the name is a null-terminated string
    }

    private fun mediaInformationBox(): ByteArray = box("minf") {
        write(soundMediaHeaderBox())
        write(dataInformationBox())
        write(sampleTableBox())
    }

    /** spec: ISO/IEC 14496-12 §12.2.2. */
    private fun soundMediaHeaderBox(): ByteArray = fullBox("smhd", version = 0, flags = 0) {
        int16(0) // balance: centre
        int16(0) // reserved
    }

    /**
     * spec: ISO/IEC 14496-12 §8.7.1-2. A single `url ` entry with flags 0x1 means "the media is in
     * this same file", which is what makes the segment self-contained.
     */
    private fun dataInformationBox(): ByteArray = box("dinf") {
        write(
            fullBox("dref", version = 0, flags = 0) {
                int32(1) // entry_count
                write(fullBox("url ", version = 0, flags = 0x1) {})
            },
        )
    }

    /**
     * spec: ISO/IEC 14496-12 §8.5.1. Every table but the sample description is empty: in a
     * fragmented file the samples are described by the `moof`, not here.
     */
    private fun sampleTableBox(): ByteArray = box("stbl") {
        write(sampleDescriptionBox())
        write(fullBox("stts", version = 0, flags = 0) { int32(0) })
        write(fullBox("stsc", version = 0, flags = 0) { int32(0) })
        write(fullBox("stsz", version = 0, flags = 0) { int32(0); int32(0) })
        write(fullBox("stco", version = 0, flags = 0) { int32(0) })
    }

    private fun sampleDescriptionBox(): ByteArray = fullBox("stsd", version = 0, flags = 0) {
        int32(1) // entry_count
        write(audioSampleEntryBox())
    }

    /**
     * spec: ISO/IEC 14496-12 §12.2.3 `AudioSampleEntry`, with the `mp4a` type ISO/IEC 14496-14 §5.6
     * assigns to MPEG-4 audio. The sample rate is a 16.16 fixed-point value in the low 16 bits of a
     * 32-bit field, which is why it is shifted rather than written directly.
     */
    private fun audioSampleEntryBox(): ByteArray = box("mp4a") {
        zeros(6) // reserved
        int16(1) // data_reference_index
        int32(0) // reserved
        int32(0) // reserved
        int16(DECLARED_CHANNEL_COUNT)
        int16(16) // samplesize
        int16(0) // pre_defined
        int16(0) // reserved
        int32(DECLARED_SAMPLE_RATE_HZ shl 16)
        write(elementaryStreamDescriptorBox())
    }

    /**
     * spec: ISO/IEC 14496-14 §5.6 `ESDBox`, wrapping the ISO/IEC 14496-1 §7.2.6 descriptors.
     *
     * This is where the decoder configuration actually lives, and it is what makes the extracted
     * format say AAC-LC: `objectTypeIndication` 0x40 is "Audio ISO/IEC 14496-3", and the two-byte
     * `AudioSpecificConfig` inside the decoder-specific info spells out the profile, sample rate and
     * channel count that the codec string `mp4a.40.2` promises.
     */
    private fun elementaryStreamDescriptorBox(): ByteArray = fullBox("esds", version = 0, flags = 0) {
        // spec: ISO/IEC 14496-3 §1.6.2.1 AudioSpecificConfig, packed MSB-first:
        //   audioObjectType = 2 (AAC-LC), 5 bits
        //   samplingFrequencyIndex = 4 (44100 Hz), 4 bits
        //   channelConfiguration = 2 (stereo), 4 bits
        //   GASpecificConfig's three leading flags, all 0, 3 bits
        val audioSpecificConfig = byteArrayOf(0x12, 0x10)

        val decoderSpecificInfo = descriptor(DECODER_SPECIFIC_INFO_TAG) { write(audioSpecificConfig) }

        val decoderConfig = descriptor(DECODER_CONFIG_DESCRIPTOR_TAG) {
            write(0x40) // objectTypeIndication: Audio ISO/IEC 14496-3
            write((STREAM_TYPE_AUDIO shl 2) or 0x1) // streamType, upStream = 0, reserved = 1
            int24(0) // bufferSizeDB
            int32(DECLARED_BITRATE_BPS) // maxBitrate
            int32(DECLARED_BITRATE_BPS) // avgBitrate
            write(decoderSpecificInfo)
        }

        // spec: ISO/IEC 14496-1 §7.3.2.3 — predefined 0x02 is the null SL packet header used when
        // the stream is carried in an MP4 file rather than over a transport.
        val slConfig = descriptor(SL_CONFIG_DESCRIPTOR_TAG) { write(0x02) }

        write(
            descriptor(ES_DESCRIPTOR_TAG) {
                int16(TRACK_ID) // ES_ID
                write(0) // no dependency, no URL, no OCR, stream priority 0
                write(decoderConfig)
                write(slConfig)
            },
        )
    }

    /**
     * spec: ISO/IEC 14496-12 §8.8.1-3. `trex` carries the sample defaults for every fragment; the
     * `tfhd` below overrides them, but a reader is entitled to expect them here.
     */
    private fun movieExtendsBox(): ByteArray = box("mvex") {
        write(
            fullBox("trex", version = 0, flags = 0) {
                int32(TRACK_ID)
                int32(1) // default_sample_description_index
                int32(SAMPLES_PER_FRAME) // default_sample_duration
                int32(SAMPLE_SIZE_BYTES) // default_sample_size
                int32(SYNC_SAMPLE_FLAGS) // default_sample_flags
            },
        )
    }

    /**
     * spec: ISO/IEC 14496-12 §8.16.3 — a media segment: an optional `styp`, then one `moof` and the
     * `mdat` its `trun` points into.
     *
     * The `trun`'s `data_offset` is measured from the start of the `moof`, so it cannot be known
     * until the `moof` has been built. Rather than write the value in by hand and have it rot the
     * next time a box is added, the `moof` is built twice: once to measure, once for real.
     */
    private fun mediaSegment(index: Int): ByteArray {
        val measured = movieFragmentBox(index, dataOffset = 0)
        val moof = movieFragmentBox(index, dataOffset = measured.size + BOX_HEADER_BYTES)

        return bytes {
            write(segmentTypeBox())
            write(moof)
            write(mediaDataBox())
        }
    }

    /** spec: ISO/IEC 14496-12 §8.16.2 — `styp`, the segment's own brand declaration. */
    private fun segmentTypeBox(): ByteArray = box("styp") {
        ascii("msdh")
        int32(0)
        ascii("msdh")
        ascii("dash")
    }

    private fun movieFragmentBox(index: Int, dataOffset: Int): ByteArray = box("moof") {
        // spec: ISO/IEC 14496-12 §8.8.5 — sequence_number counts fragments from 1.
        write(fullBox("mfhd", version = 0, flags = 0) { int32(index + 1) })
        write(trackFragmentBox(index, dataOffset))
    }

    private fun trackFragmentBox(index: Int, dataOffset: Int): ByteArray = box("traf") {
        // spec: ISO/IEC 14496-12 §8.8.7. Flags: default-base-is-moof (0x020000), which anchors
        // offsets to this `moof` rather than to the file, plus the three sample defaults present.
        write(
            fullBox("tfhd", version = 0, flags = 0x020038) {
                int32(TRACK_ID)
                int32(SAMPLES_PER_FRAME) // default_sample_duration
                int32(SAMPLE_SIZE_BYTES) // default_sample_size
                int32(SYNC_SAMPLE_FLAGS) // default_sample_flags
            },
        )

        // spec: ISO/IEC 14496-12 §8.8.12 — the fragment's first sample's decode time. Version 1 for
        // the 64-bit field, which is what every DASH packager emits.
        // Every segment carries the same sample count, so the nth begins exactly n segments in. A
        // fragment that reported zero here would place its samples on top of the first segment's:
        // the buffer would never extend past one segment and the player would never be ready.
        write(
            fullBox("tfdt", version = 1, flags = 0) {
                int64(index.toLong() * SEGMENT_DURATION_IN_TIMESCALE)
            },
        )

        // spec: ISO/IEC 14496-12 §8.8.8. Flags 0x1 is data-offset-present and nothing else: every
        // sample takes the `tfhd` defaults, so the run carries no per-sample fields at all.
        write(
            fullBox("trun", version = 0, flags = 0x1) {
                int32(FRAME_COUNT) // sample_count
                int32(dataOffset)
            },
        )
    }

    /** spec: ISO/IEC 14496-12 §8.1.1 — the samples themselves, back to back and unframed. */
    private fun mediaDataBox(): ByteArray = box("mdat") {
        zeros(FRAME_COUNT * SAMPLE_SIZE_BYTES)
    }

    /** spec: ISO/IEC 14496-12 §8.2.2 — the identity transform, in 16.16 and 2.30 fixed point. */
    private fun ByteArrayOutputStream.unityMatrix() {
        int32(0x0001_0000); int32(0); int32(0)
        int32(0); int32(0x0001_0000); int32(0)
        int32(0); int32(0); int32(0x4000_0000)
    }

    /**
     * spec: ISO/IEC 14496-1 §8.3.3 — a descriptor is a tag, a variable-length size, and a body. Every
     * descriptor written here is well under 128 bytes, so the size is a single byte with its
     * continuation bit clear.
     */
    private fun descriptor(tag: Int, build: ByteArrayOutputStream.() -> Unit): ByteArray {
        val body = bytes(build)
        check(body.size < 0x80) { "Descriptor 0x${tag.toString(16)} needs a multi-byte size" }
        return bytes {
            write(tag)
            write(body.size)
            write(body)
        }
    }

    /**
     * spec: ISO/IEC 14496-12 §8.4.2 — `mdhd`'s language field: the ISO-639-2/T code "und", packed as
     * three five-bit values each biased by 0x60.
     */
    private const val UNDETERMINED_LANGUAGE =
        (('u'.code - 0x60) shl 10) or (('n'.code - 0x60) shl 5) or ('d'.code - 0x60)

    /**
     * spec: ISO/IEC 14496-12 §8.8.3 — `sample_depends_on` = 2 means "does not depend on others", the
     * encoding of a sync sample. Every AAC frame is one.
     */
    private const val SYNC_SAMPLE_FLAGS = 0x0200_0000

    /** spec: ISO/IEC 14496-1 Table 9 — AudioStream. */
    private const val STREAM_TYPE_AUDIO = 0x05

    /** spec: ISO/IEC 14496-1 Table 1, class tags. */
    private const val ES_DESCRIPTOR_TAG = 0x03
    private const val DECODER_CONFIG_DESCRIPTOR_TAG = 0x04
    private const val DECODER_SPECIFIC_INFO_TAG = 0x05
    private const val SL_CONFIG_DESCRIPTOR_TAG = 0x06

    /** spec: ISO/IEC 14496-12 §4.2 — a 32-bit size followed by a four-character type. */
    private const val BOX_HEADER_BYTES = 8
}

private fun bytes(build: ByteArrayOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().apply(build).toByteArray()

/** spec: ISO/IEC 14496-12 §4.2 `Box`. Sizes are computed, never written by hand. */
private fun box(type: String, build: ByteArrayOutputStream.() -> Unit): ByteArray {
    val body = bytes(build)
    return bytes {
        int32(body.size + 8)
        ascii(type)
        write(body)
    }
}

/** spec: ISO/IEC 14496-12 §4.2 `FullBox`: a [box] whose body starts with a version and 24 flag bits. */
private fun fullBox(
    type: String,
    version: Int,
    flags: Int,
    build: ByteArrayOutputStream.() -> Unit,
): ByteArray = box(type) {
    write(version)
    int24(flags)
    build()
}

private fun ByteArrayOutputStream.int16(value: Int) {
    write(value ushr 8 and 0xFF)
    write(value and 0xFF)
}

private fun ByteArrayOutputStream.int24(value: Int) {
    write(value ushr 16 and 0xFF)
    write(value ushr 8 and 0xFF)
    write(value and 0xFF)
}

private fun ByteArrayOutputStream.int32(value: Int) {
    int16(value ushr 16)
    int16(value)
}

private fun ByteArrayOutputStream.int64(value: Long) {
    int32((value ushr 32).toInt())
    int32(value.toInt())
}

private fun ByteArrayOutputStream.ascii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.zeros(count: Int) {
    repeat(count) { write(0) }
}
