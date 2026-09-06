package com.superplayer.core

import androidx.media3.test.utils.FakeDataSet
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/**
 * A complete, tiny HLS stream generated in memory: a multivariant playlist, one media playlist, and
 * one audio segment, served by Media3's [FakeDataSet].
 *
 * Generated rather than checked in as a fixture so that what the test asserts about — the codec, the
 * bitrate, the duration — is visible in this file instead of hidden inside a binary blob, and so the
 * repository carries no media it would have to license.
 *
 * The stream is audio-only on purpose. It is the smallest thing a real `HlsMediaSource` will parse,
 * demux, and expose as a track, which is all this slice needs to prove.
 */
internal object SyntheticHlsStream {

    /** Any scheme works: [androidx.media3.test.utils.FakeDataSource] keys purely on the URI. */
    private const val BASE_URI = "fake://superplayer.test/"

    const val MULTIVARIANT_PLAYLIST_URI: String = BASE_URI + "master.m3u8"
    private const val MEDIA_PLAYLIST_URI = BASE_URI + "media.m3u8"
    private const val SEGMENT_URI = BASE_URI + "segment0.aac"

    /** Declared in the multivariant playlist, and therefore what the selected track should report. */
    const val DECLARED_BITRATE_BPS: Int = 128_000

    /** `mp4a.40.2` — AAC-LC. RFC 6381 §3.3 codecs parameter, as used by RFC 8216 §4.3.4.2. */
    const val DECLARED_CODECS: String = "mp4a.40.2"

    private const val SEGMENT_DURATION_SECONDS = 2.0

    /** The whole stream: one segment, so the segment's own duration. */
    const val DURATION_MS: Long = (SEGMENT_DURATION_SECONDS * 1_000).toLong()

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
     * Everything the player will ask for, and nothing else: an unknown URI is a test failure.
     *
     * Adds to a caller-supplied [FakeDataSet] rather than returning its own, so that a test needing
     * more than one stream — switching protocols mid-session, say — composes them into one set.
     */
    fun addTo(fakeDataSet: FakeDataSet): FakeDataSet =
        fakeDataSet
            .setData(MULTIVARIANT_PLAYLIST_URI, multivariantPlaylist().toByteArray())
            .setData(MEDIA_PLAYLIST_URI, mediaPlaylist().toByteArray())
            .setData(SEGMENT_URI, adtsSegment())

    // spec: RFC 8216 §4.3.4.2 — EXT-X-STREAM-INF, with the required BANDWIDTH attribute.
    private fun multivariantPlaylist(): String =
        """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=$DECLARED_BITRATE_BPS,CODECS="$DECLARED_CODECS"
        media.m3u8
        """.trimIndent()

    // spec: RFC 8216 §4.3.3 — a VOD media playlist: EXT-X-TARGETDURATION is the rounded-up maximum
    // EXTINF, and EXT-X-ENDLIST is what makes the playlist finite rather than live.
    private fun mediaPlaylist(): String =
        """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:${ceil(SEGMENT_DURATION_SECONDS).toInt()}
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:$SEGMENT_DURATION_SECONDS,
        segment0.aac
        #EXT-X-ENDLIST
        """.trimIndent()

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
     */
    private fun adtsSegment(): ByteArray {
        val frameLength = ADTS_HEADER_BYTES + ADTS_PAYLOAD_BYTES
        val out = ByteArrayOutputStream()
        repeat(frameCount()) {
            out.write(0xFF)
            out.write(0xF1)
            out.write((0b01 shl 6) or (SAMPLING_FREQUENCY_INDEX shl 2) or (CHANNEL_CONFIGURATION shr 2))
            out.write(((CHANNEL_CONFIGURATION and 0b011) shl 6) or (frameLength shr 11))
            out.write((frameLength shr 3) and 0xFF)
            out.write(((frameLength and 0b111) shl 5) or 0b11111)
            out.write(0b11111100)
            repeat(ADTS_PAYLOAD_BYTES) { out.write(0) }
        }
        return out.toByteArray()
    }

    /** Enough frames to cover the duration the media playlist advertises. */
    private fun frameCount(): Int =
        ceil(SEGMENT_DURATION_SECONDS * SAMPLING_FREQUENCY_HZ / SAMPLES_PER_FRAME).toInt()
}
