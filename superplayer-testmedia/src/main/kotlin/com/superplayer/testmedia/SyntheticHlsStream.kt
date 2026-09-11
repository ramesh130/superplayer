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
import java.io.File
import kotlin.math.ceil

/**
 * A complete, tiny HLS stream generated in memory: a multivariant playlist, one media playlist, and
 * as many identical audio segments as the caller asks for, as [resources] or as files on disk.
 *
 * Generated rather than checked in as a fixture so that what the test asserts about — the codec, the
 * bitrate, the duration — is visible in this file instead of hidden inside a binary blob, and so the
 * repository carries no media it would have to license.
 *
 * The stream is audio-only on purpose. It is the smallest thing a real `HlsMediaSource` will parse,
 * demux, and expose as a track, which is all this slice needs to prove.
 */
public object SyntheticHlsStream {

    /** Any scheme works: a test serving these from a fake data source keys purely on the URI. */
    private const val BASE_URI = "fake://superplayer.test/"

    private const val MULTIVARIANT_PLAYLIST_NAME = "master.m3u8"
    internal const val MEDIA_PLAYLIST_NAME = "media.m3u8"

    /** The second variant's media playlist, present only when a caller asks for two. */
    private const val SECOND_MEDIA_PLAYLIST_NAME = "media-high.m3u8"

    public const val MULTIVARIANT_PLAYLIST_URI: String = BASE_URI + MULTIVARIANT_PLAYLIST_NAME

    /** What every segment's name ends in, so a test can tell a segment request from a playlist one. */
    public const val SEGMENT_SUFFIX: String = ".aac"

    internal fun segmentName(index: Int) = "segment$index$SEGMENT_SUFFIX"

    /** Declared in the multivariant playlist, and therefore what the selected track should report. */
    public const val DECLARED_BITRATE_BPS: Int = 128_000

    /**
     * The second variant's declared bitrate, in the two-variant form of the stream.
     *
     * It is this one that gets selected, not [DECLARED_BITRATE_BPS]: an audio-only renderer reports
     * no adaptive support, so the selector picks a single track rather than an adaptive set, and the
     * single track it picks is the highest-bitrate one it is allowed. A test asserting on the
     * selected format over the two-variant stream asserts on this constant.
     */
    public const val HIGHER_DECLARED_BITRATE_BPS: Int = 256_000

    /** `mp4a.40.2` — AAC-LC. RFC 6381 §3.3 codecs parameter, as used by RFC 8216 §4.3.4.2. */
    public const val DECLARED_CODECS: String = "mp4a.40.2"

    internal const val SEGMENT_DURATION_SECONDS = 2.0

    public const val SEGMENT_DURATION_MS: Long = (SEGMENT_DURATION_SECONDS * 1_000).toLong()

    /** The default stream: one segment, so the segment's own duration. */
    public const val DURATION_MS: Long = SEGMENT_DURATION_MS

    /** What a stream of [segmentCount] segments advertises as its duration. */
    public fun durationMs(segmentCount: Int): Long = SEGMENT_DURATION_MS * segmentCount

    // spec: ISO/IEC 13818-7 §6.2 — sampling_frequency_index 4 is 44100 Hz, channel_configuration 2
    // is stereo. They are encoded into every ADTS frame header below and are what Media3's
    // AdtsExtractor reads the track format out of.
    private const val SAMPLING_FREQUENCY_HZ = 44_100
    private const val SAMPLING_FREQUENCY_INDEX = 4
    private const val CHANNEL_CONFIGURATION = 2

    /** spec: ISO/IEC 14496-3 — an AAC-LC frame carries 1024 samples. */
    private const val SAMPLES_PER_FRAME = 1024

    /** spec: ISO/IEC 13818-7 §6.2 — 7 bytes when protection_absent is 1 (no CRC). */
    private const val ADTS_HEADER_BYTES = 7

    /**
     * Arbitrary. The payload is never decoded — Robolectric's shadow codec passes bytes through —
     * so it only has to be a plausible frame size.
     */
    private const val ADTS_PAYLOAD_BYTES = 64

    /**
     * Everything the player will ask for, keyed by URI, and nothing else: an unknown URI is a test
     * failure.
     *
     * A map rather than a populated fake data source, so that this module names no Media3 type — see
     * its build script for why that is what keeps it below every module that plays these streams. A
     * caller serves it in one line, and a test needing more than one stream — switching protocols
     * mid-session, say — composes two maps into one set.
     *
     * [segmentCount] is one by default, which is all a test of track formats or start positions
     * needs. A test about *buffering* needs a stream longer than the buffer it is asserting on, and
     * asks for more; every segment is byte-identical apart from its timestamp tag, because what
     * varies is how much of the stream exists and not what is in it.
     *
     * One variant, unlike [writeTo]: the two-variant form exists for the track-selection test that
     * plays through the real transfer chain, and nothing serving from memory has needed it.
     */
    public fun resources(segmentCount: Int = 1): Map<String, ByteArray> =
        files(segmentCount).mapKeys { (name, _) -> BASE_URI + name }

    /**
     * The same stream on disk, returning the multivariant playlist's `file:` URI.
     *
     * For the one test that must *not* substitute a data source: the transfer chain
     * `SuperPlayer.Builder` assembles is what a consumer loads through, and a test that replaces the
     * whole chain with a fake data source cannot see it at all. A file the real chain resolves is
     * the nearest thing to a network fetch that a test with no network can ask for.
     *
     * Every reference inside the playlists is relative, which is what lets the identical bytes serve
     * from a `fake:` URI in [resources] and from a directory here.
     */
    public fun writeTo(directory: File, segmentCount: Int = 1, variantCount: Int = 1): String {
        files(segmentCount, variantCount).forEach { (name, bytes) ->
            File(directory, name).writeBytes(bytes)
        }
        return File(directory, MULTIVARIANT_PLAYLIST_NAME).toURI().toString()
    }

    /**
     * The whole stream as file name to bytes — everything the player will ask for, and nothing else,
     * so an unknown URI is a test failure.
     *
     * Names rather than URIs, because the two callers above disagree about where the stream lives
     * and agree about everything else.
     */
    private fun files(segmentCount: Int, variantCount: Int = 1): Map<String, ByteArray> {
        require(segmentCount >= 1) { "A stream needs at least one segment, was $segmentCount" }
        require(variantCount in 1..2) { "This stream has one or two variants, not $variantCount" }

        return buildMap {
            put(MULTIVARIANT_PLAYLIST_NAME, multivariantPlaylist(variantCount).toByteArray())
            put(MEDIA_PLAYLIST_NAME, mediaPlaylist(segmentCount).toByteArray())
            // The same segments under a second playlist: what varies between the variants is what
            // they *declare*, because nothing in these tests decodes a byte of them.
            if (variantCount == 2) {
                put(SECOND_MEDIA_PLAYLIST_NAME, mediaPlaylist(segmentCount).toByteArray())
            }
            repeat(segmentCount) { index -> put(segmentName(index), adtsSegment(index)) }
        }
    }

    // spec: RFC 8216 §4.3.4.2 — EXT-X-STREAM-INF, with the required BANDWIDTH attribute.
    private fun multivariantPlaylist(variantCount: Int): String {
        val variants = listOf(
            DECLARED_BITRATE_BPS to MEDIA_PLAYLIST_NAME,
            HIGHER_DECLARED_BITRATE_BPS to SECOND_MEDIA_PLAYLIST_NAME,
        ).take(variantCount)

        return (
            listOf("#EXTM3U") + variants.flatMap { (bandwidth, playlist) ->
                listOf("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,CODECS=\"$DECLARED_CODECS\"", playlist)
            }
            ).joinToString(separator = "\n")
    }

    // spec: RFC 8216 §4.3.3 — a VOD media playlist: EXT-X-TARGETDURATION is the rounded-up maximum
    // EXTINF, and EXT-X-ENDLIST is what makes the playlist finite rather than live.
    //
    // Built by joining lines rather than as an indented raw string: a `trimIndent` block whose
    // interpolated value is itself multi-line has no common indent to trim, and a playlist whose
    // lines are indented is not a playlist any parser will accept.
    private fun mediaPlaylist(segmentCount: Int): String {
        val header = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:${ceil(SEGMENT_DURATION_SECONDS).toInt()}",
            "#EXT-X-MEDIA-SEQUENCE:0",
        )
        val segments = (0 until segmentCount).flatMap { index ->
            listOf("#EXTINF:$SEGMENT_DURATION_SECONDS,", segmentName(index))
        }

        return (header + segments + "#EXT-X-ENDLIST").joinToString(separator = "\n")
    }

    /**
     * An elementary AAC-LC stream in ADTS framing — the segment format HLS allows without a
     * container (RFC 8216 §3.4, "Packed Audio").
     *
     * spec: ISO/IEC 13818-7 §6.2, `adts_fixed_header` followed by `adts_variable_header`, packed
     * MSB-first across 7 bytes:
     *
     * ```
     * byte 0  syncword high 8 bits (0xFF)
     * byte 1  syncword low 4 bits | ID=0 (MPEG-4) | layer=00 | protection_absent=1 (no CRC)
     * byte 2  profile=01 (AAC-LC) | sampling_frequency_index (4) | private=0 | channel_config high bit
     * byte 3  channel_config low 2 bits | original=0 | home=0 | copyright bits=00 | frame_length high 2 bits
     * byte 4  frame_length middle 8 bits
     * byte 5  frame_length low 3 bits | buffer_fullness high 5 bits
     * byte 6  buffer_fullness low 6 bits | number_of_raw_data_blocks_in_frame - 1 = 00
     * ```
     *
     * `buffer_fullness` is set to all ones, which §6.2.2 defines as "variable rate" — the correct
     * value when there is no encoder buffer state to report.
     *
     * The segment is preceded by the ID3 tag RFC 8216 §3.4 requires — see [id3TimestampTag], and note
     * that a stream of more than one segment does not play without it.
     *
     * [startSeconds], [durationSeconds] and [sizeScale] exist for `HostileManifests`, whose defects
     * are sometimes in the media's shape rather than in its manifest — a segment ten seconds long, a
     * timestamp that restarts at a splice, a rung whose real bitrate is a multiple of this one's. The
     * good stream never passes them: [adtsSegment] by index is what it serves, byte for byte.
     */
    internal fun adtsSegment(startSeconds: Double, durationSeconds: Double, sizeScale: Int = 1): ByteArray {
        val frameLength = (ADTS_HEADER_BYTES + ADTS_PAYLOAD_BYTES) * sizeScale
        // spec: ISO/IEC 13818-7 §6.2 — frame_length is a 13-bit field.
        require(frameLength < (1 shl 13)) { "An ADTS frame of $frameLength bytes does not fit its header" }
        val out = ByteArrayOutputStream()
        out.write(id3TimestampTag(startSeconds))
        repeat(frameCount(durationSeconds)) {
            out.write(0xFF)
            out.write(0xF1)
            out.write((0b01 shl 6) or (SAMPLING_FREQUENCY_INDEX shl 2) or (CHANNEL_CONFIGURATION shr 2))
            out.write(((CHANNEL_CONFIGURATION and 0b011) shl 6) or (frameLength shr 11))
            out.write((frameLength shr 3) and 0xFF)
            out.write(((frameLength and 0b111) shl 5) or 0b11111)
            out.write(0b11111100)
            repeat(frameLength - ADTS_HEADER_BYTES) { out.write(0) }
        }
        return out.toByteArray()
    }

    /** The good stream's segment [index]: two seconds long, starting where the one before it ends. */
    internal fun adtsSegment(index: Int): ByteArray =
        adtsSegment(index * SEGMENT_DURATION_SECONDS, SEGMENT_DURATION_SECONDS)

    /**
     * The ID3 tag that tells the player where in the stream a Packed Audio segment starts.
     *
     * spec: RFC 8216 §3.4 — "Each Packed Audio Segment MUST signal the timestamp of its first sample
     * with an ID3 PRIV tag [ID3] with an owner identifier of
     * `com.apple.streaming.transportStreamTimestamp`. The ID3 payload MUST be a 33-bit MPEG-2 Program
     * Elementary Stream timestamp expressed as a big-endian eight-octet number, with the upper 31
     * bits set to zero."
     *
     * This is not decoration. Packed audio carries no container timestamps of its own, so without the
     * tag every segment's samples begin at zero: the player loads segment after segment, none of them
     * extends the buffer past the first, and it never reaches [Player.STATE_READY] for any profile
     * whose minimum buffer is longer than one segment. A single-segment stream hides this completely,
     * which is why it went unnoticed until a test needed a long one.
     *
     * spec: ID3v2.3.0 §3.1 (tag header, size in syncsafe integers) and §4.27 (the PRIV frame).
     */
    private fun id3TimestampTag(startSeconds: Double): ByteArray {
        val owner = "com.apple.streaming.transportStreamTimestamp".toByteArray(Charsets.US_ASCII)
        val presentationTimestamp = (startSeconds * MPEG2_TIMESTAMP_HZ).toLong()

        val frameBody = ByteArrayOutputStream()
        frameBody.write(owner)
        frameBody.write(0) // The owner identifier is null-terminated.
        repeat(8) { byteIndex ->
            frameBody.write(((presentationTimestamp shr (8 * (7 - byteIndex))) and 0xFF).toInt())
        }

        val frame = ByteArrayOutputStream()
        frame.write("PRIV".toByteArray(Charsets.US_ASCII))
        // Frame sizes in ID3v2.3 are plain big-endian, unlike the tag size below.
        repeat(4) { byteIndex ->
            frame.write((frameBody.size() shr (8 * (3 - byteIndex))) and 0xFF)
        }
        frame.write(0) // Frame flags, both bytes clear.
        frame.write(0)
        frame.write(frameBody.toByteArray())

        val tag = ByteArrayOutputStream()
        tag.write("ID3".toByteArray(Charsets.US_ASCII))
        tag.write(3) // Version 2.3.0, as major and revision.
        tag.write(0)
        tag.write(0) // Tag flags: no unsynchronisation, no extended header.
        // The tag size is a syncsafe integer: 7 bits per byte, so no byte can look like a sync word.
        repeat(4) { byteIndex ->
            tag.write((frame.size() shr (7 * (3 - byteIndex))) and 0x7F)
        }
        tag.write(frame.toByteArray())

        return tag.toByteArray()
    }

    /** spec: ISO/IEC 13818-1 — the MPEG-2 system clock PTS runs at 90 kHz. */
    private const val MPEG2_TIMESTAMP_HZ = 90_000L

    /** Enough frames to cover [durationSeconds] of audio. */
    private fun frameCount(durationSeconds: Double): Int =
        ceil(durationSeconds * SAMPLING_FREQUENCY_HZ / SAMPLES_PER_FRAME).toInt()
}
