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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    /**
     * The host every URI of this stream is served from.
     *
     * Named rather than buried in [BASE_URI] because a fault can be addressed at a host, and a test
     * that wants one rendition to fail while another serves has to be able to say which is which.
     */
    public const val HOST: String = "superplayer.test"

    /** Any scheme works: a test serving these from a fake data source keys purely on the URI. */
    private const val BASE_URI = "fake://$HOST/"

    /** Where this stream's resources sit when served from [host] instead of [HOST]. */
    public fun baseUriOn(host: String): String = "fake://$host/"

    private const val MULTIVARIANT_PLAYLIST_NAME = "master.m3u8"
    internal const val MEDIA_PLAYLIST_NAME = "media.m3u8"

    /** The second variant's media playlist, present only when a caller asks for two. */
    private const val SECOND_MEDIA_PLAYLIST_NAME = "media-high.m3u8"

    public const val MULTIVARIANT_PLAYLIST_URI: String = BASE_URI + MULTIVARIANT_PLAYLIST_NAME

    /** Where [liveResources] lives: a directory of its own, so it shares a data set with [resources]. */
    private const val LIVE_BASE_URI = BASE_URI + "live/"

    /** What a player is pointed at to play [liveResources]. */
    public const val LIVE_MULTIVARIANT_PLAYLIST_URI: String = LIVE_BASE_URI + MULTIVARIANT_PLAYLIST_NAME

    /** Where [protectedResources] lives: its own directory, so it shares a data set with [resources]. */
    private const val PROTECTED_BASE_URI = BASE_URI + "protected/"

    /** What a player is pointed at to play [protectedResources]. */
    public const val PROTECTED_MULTIVARIANT_PLAYLIST_URI: String = PROTECTED_BASE_URI + MULTIVARIANT_PLAYLIST_NAME

    /**
     * Where the protected form of [liveResources] lives: again its own directory, so a data set can
     * hold the plain live stream and the protected one at once.
     */
    private const val PROTECTED_LIVE_BASE_URI = BASE_URI + "live-protected/"

    /** What a player is pointed at to play [liveResources] in its protected form. */
    public const val PROTECTED_LIVE_MULTIVARIANT_PLAYLIST_URI: String =
        PROTECTED_LIVE_BASE_URI + MULTIVARIANT_PLAYLIST_NAME

    /**
     * How many segments a live playlist from [liveResources] lists at once: twelve seconds.
     *
     * spec: RFC 8216 §6.2.2 — a server must not remove a segment while the playlist would then last
     * less than three target durations, so a window of four two-second segments is the least a
     * conforming live playlist can be. Six leaves the player's own three-target-duration hold-back
     * from the live edge (§6.3.3) some media behind it to start on.
     */
    public const val LIVE_WINDOW_SEGMENT_COUNT: Int = 6

    /** What every segment's name ends in, so a test can tell a segment request from a playlist one. */
    public const val SEGMENT_SUFFIX: String = ".aac"

    /**
     * spec: RFC 8216 §4.3.2.6 — the date is ISO/IEC 8601:2004 with millisecond precision and a time
     * zone; UTC, written as `Z`. `java.text` rather than `java.time`, which needs API 26 and this
     * module's floor is lower.
     */
    private fun iso8601(unixMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(unixMs))

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
     * [variantCount] is one by default, as [writeTo]'s is: the two-variant form is for a test whose
     * subject is a *selection* — which rendition was chosen, or what the selection reports about
     * its own estimate — and a stream with one variant is selected by a fixed selection that
     * reports nothing.
     *
     * [secondVariantHost] is null by default, and naming one is the one way this stream can say
     * "*that* rendition, and not the other" to something addressing resources rather than URLs: the
     * multivariant playlist then names the second variant's media playlist absolutely on that host,
     * and every segment of that variant is served from it. The two variants carry identical bytes
     * either way — what a rendition declares is all that ever differs here — so the host is the
     * whole of the difference, which is exactly what makes it usable as an address.
     */
    public fun resources(
        segmentCount: Int = 1,
        variantCount: Int = 1,
        secondVariantHost: String? = null,
    ): Map<String, ByteArray> {
        if (secondVariantHost == null) {
            return files(segmentCount, variantCount).mapKeys { (name, _) -> BASE_URI + name }
        }
        require(variantCount == 2) { "Only a second variant can be served from a second host" }
        require(secondVariantHost != HOST) { "A second host is not $HOST over again" }
        require(segmentCount >= 1) { "A stream needs at least one segment, was $segmentCount" }
        val secondBase = baseUriOn(secondVariantHost)
        val secondPlaylistUri = secondBase + SECOND_MEDIA_PLAYLIST_NAME
        return buildMap {
            put(MULTIVARIANT_PLAYLIST_URI, multivariantPlaylist(variantCount = 2, secondPlaylistUri).toByteArray())
            put(BASE_URI + MEDIA_PLAYLIST_NAME, mediaPlaylist(segmentCount).toByteArray())
            put(secondPlaylistUri, mediaPlaylist(segmentCount).toByteArray())
            repeat(segmentCount) { index ->
                put(BASE_URI + segmentName(index), adtsSegment(index))
                put(secondBase + segmentName(index), adtsSegment(index))
            }
        }
    }

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

    // spec: RFC 8216 §4.3.4.2 — EXT-X-STREAM-INF, with the required BANDWIDTH attribute. The URI
    // line that follows a tag is a URI reference (§4.3.4.2), so it may be relative — which is what
    // every form of this stream but the second-host one uses — or absolute.
    private fun multivariantPlaylist(
        variantCount: Int,
        secondVariantPlaylist: String = SECOND_MEDIA_PLAYLIST_NAME,
    ): String {
        val variants = listOf(
            DECLARED_BITRATE_BPS to MEDIA_PLAYLIST_NAME,
            HIGHER_DECLARED_BITRATE_BPS to secondVariantPlaylist,
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
    private fun mediaPlaylist(segmentCount: Int): String = mediaPlaylist(0 until segmentCount, live = false)

    /**
     * The Widevine-protected form of this stream: the same segments, under a media playlist carrying
     * the `EXT-X-KEY` tag that names Widevine and [WidevineProtection]'s `pssh` box.
     *
     * The segments are byte-identical to [resources]'s and are not encrypted, which
     * [WidevineProtection]'s KDoc argues at length: the tag is what makes a player acquire a licence
     * before it reads a sample, and sample-level encryption is a step nothing in a test with no
     * `MediaCrypto` could undo.
     */
    public fun protectedResources(segmentCount: Int = 1): Map<String, ByteArray> {
        require(segmentCount >= 1) { "A stream needs at least one segment, was $segmentCount" }
        return buildMap {
            put(MULTIVARIANT_PLAYLIST_NAME, multivariantPlaylist(variantCount = 1).toByteArray())
            put(MEDIA_PLAYLIST_NAME, mediaPlaylist(0 until segmentCount, live = false, protected = true).toByteArray())
            repeat(segmentCount) { index -> put(segmentName(index), adtsSegment(index)) }
        }.mapKeys { (name, _) -> PROTECTED_BASE_URI + name }
    }

    // spec: RFC 8216 §4.3.3.2 — EXT-X-MEDIA-SEQUENCE is the sequence number of the first segment
    // listed, which is what lets a live playlist slide its window and still name every segment once.
    private fun mediaPlaylist(
        indices: IntRange,
        live: Boolean,
        firstSegmentDateTimeMs: Long? = null,
        protected: Boolean = false,
    ): String {
        val header = listOf(
            "#EXTM3U",
            // spec: RFC 8216 §7 — SAMPLE-AES needs a playlist at version 5 or above, so a protected
            // stream declares 5 where the plain one declares 3. Nothing else about the two differs.
            "#EXT-X-VERSION:${if (protected) 5 else 3}",
            "#EXT-X-TARGETDURATION:${ceil(SEGMENT_DURATION_SECONDS).toInt()}",
            "#EXT-X-MEDIA-SEQUENCE:${indices.first}",
        ) + encryptionKeyTag(protected)
        // spec: RFC 8216 §4.3.2.6 — EXT-X-PROGRAM-DATE-TIME dates the first sample of the segment
        // that follows it, and one tag is enough: the date of every later segment follows from the
        // EXTINF durations between. It is what lets a player measure its distance from the live
        // edge at all; a live playlist without one has a window but no clock to hold it against.
        val dated = firstSegmentDateTimeMs?.let { listOf("#EXT-X-PROGRAM-DATE-TIME:${iso8601(it)}") }.orEmpty()
        val segments = indices.flatMapIndexed { position, index ->
            (if (position == 0) dated else emptyList()) +
                listOf("#EXTINF:$SEGMENT_DURATION_SECONDS,", segmentName(index))
        }
        val tail = if (live) emptyList() else listOf("#EXT-X-ENDLIST")

        return (header + segments + tail).joinToString(separator = "\n")
    }

    /**
     * The `EXT-X-KEY` tag a protected media playlist carries before its first segment, or nothing.
     *
     * spec: RFC 8216 §4.3.2.4 — `EXT-X-KEY` applies to every segment that follows it until the next
     * one, which is why a single tag in the header protects the whole playlist. `METHOD=SAMPLE-AES`
     * is the sample-level scheme, the one whose keys a DRM system holds; `KEYFORMAT` identifies that
     * system, and a `KEYFORMAT` other than the default `identity` means the `URI` is not a key file
     * to fetch but the system's own initialization data. `KEYFORMATVERSIONS` is that system's
     * versioning of the format, and Widevine's is `1`.
     *
     * The `URI` is a `data:` URL (// spec: RFC 2397) carrying the base64 of [WidevineProtection]'s
     * `pssh` box, which is the form Media3's HLS parser reads a Widevine `EXT-X-KEY` in and the form
     * every Widevine packager writes.
     *
     * SAMPLE-AES rather than `AES-128`: §4.3.2.4 makes `AES-128` whole-segment encryption the client
     * itself performs with a key it fetches, which would mean really encrypting these segments and
     * would say nothing about a licence server. SAMPLE-AES leaves the decryption to the platform,
     * which is what a DRM stream does and what makes the licence the thing under test.
     */
    private fun encryptionKeyTag(protected: Boolean): List<String> = if (!protected) {
        emptyList()
    } else {
        listOf(
            "#EXT-X-KEY:METHOD=SAMPLE-AES," +
                "URI=\"data:text/plain;base64,${WidevineProtection.psshBase64()}\"," +
                "KEYFORMAT=\"${WidevineProtection.SYSTEM_ID_URN}\"," +
                "KEYFORMATVERSIONS=\"1\"",
        )
    }

    /**
     * The live form of this stream, as it stands once [publishedSegmentCount] segments have been
     * published: a media playlist with no `EXT-X-ENDLIST` listing the newest
     * [LIVE_WINDOW_SEGMENT_COUNT] of them, and those segments.
     *
     * A function of how much has been published rather than of a clock, because this module names
     * no clock: whoever serves it asks again with a larger count as time passes, and a player
     * reloading the playlist sees exactly what RFC 8216 §6.2.1 promises it — a new version each time
     * a segment is published. Segments that have slid out of the window are gone, as they are from
     * a real origin.
     *
     * Served from its own directory, so it and [resources] can sit in one data set. Every segment is
     * [resources]'s segment of the same index, timestamp tag and all, so the timeline runs on across
     * the window as a real live stream's does.
     *
     * [firstSegmentDateTimeMs], when given, is the wall-clock time in Unix milliseconds at which
     * segment 0 began, and dates the window with `EXT-X-PROGRAM-DATE-TIME` — the tag a player needs
     * before it can measure its live offset and hold it. Whoever serves this decides what "wall
     * clock" means, because this module names no clock; without it the window carries no date and
     * a player reports no live offset, as it does for many real origins.
     *
     * [protected] declares Widevine over the window, exactly as [protectedResources] declares it over
     * the on-demand playlist — the same `EXT-X-KEY`, the same `pssh`, at an address of its own. A
     * live stream is where a licence outlives more than one segment and where a real origin rotates
     * its keys, so the two facts a session has to survive — a window that slides and a key that
     * changes — can only be put to a player over this form.
     */
    @JvmOverloads
    public fun liveResources(
        publishedSegmentCount: Int,
        firstSegmentDateTimeMs: Long? = null,
        protected: Boolean = false,
    ): Map<String, ByteArray> {
        require(publishedSegmentCount >= LIVE_WINDOW_SEGMENT_COUNT) {
            "A live window of $LIVE_WINDOW_SEGMENT_COUNT segments needs that many published, " +
                "not $publishedSegmentCount"
        }
        val window = (publishedSegmentCount - LIVE_WINDOW_SEGMENT_COUNT) until publishedSegmentCount
        val windowDateTimeMs = firstSegmentDateTimeMs?.let { it + window.first * SEGMENT_DURATION_MS }
        val base = if (protected) PROTECTED_LIVE_BASE_URI else LIVE_BASE_URI
        return buildMap {
            put(MULTIVARIANT_PLAYLIST_NAME, multivariantPlaylist(variantCount = 1).toByteArray())
            put(MEDIA_PLAYLIST_NAME, mediaPlaylist(window, live = true, windowDateTimeMs, protected).toByteArray())
            window.forEach { index -> put(segmentName(index), adtsSegment(index)) }
        }.mapKeys { (name, _) -> base + name }
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
