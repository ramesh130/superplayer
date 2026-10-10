// Copyright 2026 The SuperPlayer Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/// A complete, tiny HLS stream generated in memory: a multivariant playlist, one media playlist per
/// variant, and as many audio segments as the caller asks for, as file name to bytes.
///
/// The port of `superplayer-testmedia`'s `SyntheticHlsStream`, and **byte for byte** the same
/// stream: the Kotlin generator's output is committed under `ios/Tests/Fixtures/synthetic/`,
/// Gradle's `check` holds the Kotlin generator to those files and this package's tests hold this
/// one to them, so the two platforms play one corpus (ADR-0021 rule 6). Every constant below is
/// the Kotlin one under its Swift name, with the reason the Kotlin source gives beside it.
///
/// Generated rather than checked in as media so that what a test asserts about — the codec, the
/// bitrate, the duration — is visible in this file instead of hidden inside a binary blob, and so
/// the repository carries no media it would have to license.
///
/// The stream is audio-only on purpose: packed AAC in ADTS framing (RFC 8216 §3.4) is the smallest
/// thing an HLS player will parse and expose as a track, `AVPlayer` included, and every frame
/// decodes — to silence — in AVFoundation's AAC decoder.
///
/// **What is not ported, and why.** The Kotlin `resources` keys its map under `fake://` URIs, which
/// is how a Media3 `FakeDataSet` is addressed and which nothing on iOS can serve: `AVPlayer` takes
/// no fake transport, so an iOS test plays from a loopback origin (ADR-0021 rule 2) and a stream is
/// served by path under that origin's address. ``files(segmentCount:variantCount:)`` is therefore the
/// public form here, and it is the Kotlin generator's own `files` — the form whose every reference
/// is relative, and so the one form that is the same bytes wherever it is served. The second-host
/// variant, the live window and the Widevine-protected forms are not ported: the first two are the
/// harness's (#399), and protection on iOS is FairPlay, which is later.
///
/// The library names no type outside the standard library — not even Foundation — for
/// `superplayer-testmedia`'s reason: it sits below core and testkit, and a module with no edges
/// cannot break the phase rule.
public enum SyntheticHlsStream {

    /// The multivariant playlist's file name: what a player is pointed at, under wherever the
    /// stream is served.
    public static let multivariantPlaylistName = "master.m3u8"

    /// The first variant's media playlist.
    public static let mediaPlaylistName = "media.m3u8"

    /// The second variant's media playlist, present only when a caller asks for two.
    public static let secondMediaPlaylistName = "media-high.m3u8"

    /// What every segment's name ends in, so a test can tell a segment request from a playlist one.
    public static let segmentSuffix = ".aac"

    /// Declared in the multivariant playlist, and therefore what the selected track should report.
    public static let declaredBitrateBps = 128_000

    /// The second variant's declared bitrate, in the two-variant form of the stream.
    public static let higherDeclaredBitrateBps = 256_000

    /// `mp4a.40.2` — AAC-LC. RFC 6381 §3.3 codecs parameter, as used by RFC 8216 §4.3.4.2.
    public static let declaredCodecs = "mp4a.40.2"

    /// Two seconds, as a `Double` because it is written into `EXTINF` as one: Kotlin prints `2.0`
    /// and so does Swift, which is part of what keeps the playlists byte-equal.
    private static let segmentDurationSeconds = 2.0

    /// One segment's duration, as the playlists declare it in `EXTINF`.
    public static let segmentDurationMs: Int64 = 2_000

    /// The default stream: one segment, so the segment's own duration.
    public static let durationMs: Int64 = segmentDurationMs

    /// What a stream of `segmentCount` segments advertises as its duration.
    public static func durationMs(segmentCount: Int) -> Int64 {
        segmentDurationMs * Int64(segmentCount)
    }

    /// The name of segment `index`, relative to wherever the stream is served.
    public static func segmentName(_ index: Int) -> String { "segment\(index)\(segmentSuffix)" }

    // spec: ISO/IEC 13818-7 §6.2 — sampling_frequency_index 4 is 44100 Hz, channel_configuration 2
    // is stereo. They are encoded into every ADTS frame header below.
    private static let samplingFrequencyHz = 44_100
    private static let samplingFrequencyIndex = 4
    private static let channelConfiguration = 2

    /// spec: ISO/IEC 14496-3 — an AAC-LC frame carries 1024 samples.
    private static let samplesPerFrame = 1024

    /// spec: ISO/IEC 13818-7 §6.2 — 7 bytes when protection_absent is 1 (no CRC).
    private static let adtsHeaderBytes = 7

    /// Arbitrary: on Android, where Robolectric's shadow codec never decodes the payload, its size
    /// only has to be plausible. Its contents are `silentRawDataBlock`.
    private static let adtsPayloadBytes = 64

    /// The start of every frame's payload: one AAC-LC `raw_data_block` that decodes to two channels
    /// of silence; the rest of the payload is zeros, which the decoder never reads.
    ///
    /// An all-zero payload — what both generators wrote until #398 — is refused frame by frame by
    /// AVFoundation's decoder while `AVPlayer` plays the stream to its end with no error it
    /// reports; `SyntheticAacDecodingTests` is the check that can tell. The Kotlin constant carries
    /// the bit-by-bit derivation.
    ///
    /// spec: ISO/IEC 14496-3 §4.4.2 — `ID_CPE`, `common_window` 1, `max_sfb` 0 (no spectral data),
    /// `ms_mask_present` 0, each channel's `global_gain` 0 with no pulse, TNS or gain control data,
    /// then `ID_END` and byte alignment.
    private static let silentRawDataBlock: [UInt8] = [0x21, 0x00, 0x00, 0x00, 0x00, 0x1C]

    /// spec: ISO/IEC 13818-1 — the MPEG-2 system clock PTS runs at 90 kHz.
    private static let mpeg2TimestampHz: Int64 = 90_000

    /// The whole stream as file name to bytes — everything a player will ask for, and nothing else,
    /// so an unknown request is a test failure.
    ///
    /// `segmentCount` is one by default, which is all a test of track formats needs; a test about
    /// buffering asks for more. Every segment is byte-identical apart from its timestamp tag.
    /// `variantCount` is one or two: the two-variant form is for a test whose subject is a
    /// selection. The two variants list the same segments — what varies between them is what they
    /// *declare*.
    public static func files(segmentCount: Int = 1, variantCount: Int = 1) -> [String: [UInt8]] {
        precondition(segmentCount >= 1, "A stream needs at least one segment, was \(segmentCount)")
        precondition(
            (1...2).contains(variantCount),
            "This stream has one or two variants, not \(variantCount)")

        var files: [String: [UInt8]] = [:]
        files[multivariantPlaylistName] = Array(multivariantPlaylist(variantCount).utf8)
        files[mediaPlaylistName] = Array(mediaPlaylist(segmentCount).utf8)
        if variantCount == 2 {
            files[secondMediaPlaylistName] = Array(mediaPlaylist(segmentCount).utf8)
        }
        for index in 0..<segmentCount {
            files[segmentName(index)] = adtsSegment(index)
        }
        return files
    }

    // spec: RFC 8216 §4.3.4.2 — EXT-X-STREAM-INF, with the required BANDWIDTH attribute; the URI
    // line that follows is a URI reference, relative in every form ported here.
    private static func multivariantPlaylist(_ variantCount: Int) -> String {
        let variants = [
            (declaredBitrateBps, mediaPlaylistName),
            (higherDeclaredBitrateBps, secondMediaPlaylistName),
        ].prefix(variantCount)
        let lines =
            ["#EXTM3U"]
            + variants.flatMap { bandwidth, playlist in
                ["#EXT-X-STREAM-INF:BANDWIDTH=\(bandwidth),CODECS=\"\(declaredCodecs)\"", playlist]
            }
        return lines.joined(separator: "\n")
    }

    // spec: RFC 8216 §4.3.3 — a VOD media playlist: EXT-X-TARGETDURATION is the rounded-up maximum
    // EXTINF, EXT-X-MEDIA-SEQUENCE the first segment's number (§4.3.3.2), and EXT-X-ENDLIST is what
    // makes the playlist finite rather than live. Lines joined with `\n` and no trailing newline,
    // as the Kotlin generator joins them.
    private static func mediaPlaylist(_ segmentCount: Int) -> String {
        let header = [
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:\(Int(segmentDurationSeconds.rounded(.up)))",
            "#EXT-X-MEDIA-SEQUENCE:0",
        ]
        let segments = (0..<segmentCount).flatMap { index in
            ["#EXTINF:\(segmentDurationSeconds),", segmentName(index)]
        }
        return (header + segments + ["#EXT-X-ENDLIST"]).joined(separator: "\n")
    }

    /// The good stream's segment `index`: two seconds long, starting where the one before it ends.
    ///
    /// An elementary AAC-LC stream in ADTS framing — the segment format HLS allows without a
    /// container (RFC 8216 §3.4, "Packed Audio"), preceded by the ID3 timestamp tag that section
    /// requires.
    ///
    /// spec: ISO/IEC 13818-7 §6.2, `adts_fixed_header` followed by `adts_variable_header`, packed
    /// MSB-first across 7 bytes — the Kotlin `adtsSegment` carries the byte-by-byte layout.
    /// `buffer_fullness` is all ones, which §6.2.2 defines as "variable rate".
    private static func adtsSegment(_ index: Int) -> [UInt8] {
        let startSeconds = Double(index) * segmentDurationSeconds
        let frameLength = adtsHeaderBytes + adtsPayloadBytes
        let frameCount = Int(
            (segmentDurationSeconds * Double(samplingFrequencyHz) / Double(samplesPerFrame))
                .rounded(.up))
        var out = id3TimestampTag(startSeconds: startSeconds)
        for _ in 0..<frameCount {
            out.append(0xFF)
            out.append(0xF1)
            out.append(
                UInt8((0b01 << 6) | (samplingFrequencyIndex << 2) | (channelConfiguration >> 2)))
            out.append(UInt8(((channelConfiguration & 0b011) << 6) | (frameLength >> 11)))
            out.append(UInt8((frameLength >> 3) & 0xFF))
            out.append(UInt8(((frameLength & 0b111) << 5) | 0b11111))
            out.append(0b1111_1100)
            out.append(contentsOf: silentRawDataBlock)
            out.append(
                contentsOf: repeatElement(
                    0, count: frameLength - adtsHeaderBytes - silentRawDataBlock.count))
        }
        return out
    }

    /// The ID3 tag that tells the player where in the stream a Packed Audio segment starts.
    ///
    /// spec: RFC 8216 §3.4 — "Each Packed Audio Segment MUST signal the timestamp of its first
    /// sample with an ID3 PRIV tag [ID3] with an owner identifier of
    /// `com.apple.streaming.transportStreamTimestamp`." The payload is the 33-bit MPEG-2 timestamp
    /// as a big-endian eight-octet number. Without it every segment's samples begin at zero and a
    /// stream of more than one segment does not play (the Kotlin KDoc tells that history).
    ///
    /// spec: ID3v2.3.0 §3.1 (tag header, size in syncsafe integers) and §4.28 (the PRIV frame).
    private static func id3TimestampTag(startSeconds: Double) -> [UInt8] {
        let presentationTimestamp = Int64(startSeconds * Double(mpeg2TimestampHz))

        var frameBody = Array("com.apple.streaming.transportStreamTimestamp".utf8)
        frameBody.append(0)  // The owner identifier is null-terminated.
        for byteIndex in 0..<8 {
            frameBody.append(UInt8((presentationTimestamp >> (8 * (7 - byteIndex))) & 0xFF))
        }

        var frame = Array("PRIV".utf8)
        // Frame sizes in ID3v2.3 are plain big-endian, unlike the tag size below.
        for byteIndex in 0..<4 {
            frame.append(UInt8((frameBody.count >> (8 * (3 - byteIndex))) & 0xFF))
        }
        frame.append(contentsOf: [0, 0])  // Frame flags, both bytes clear.
        frame.append(contentsOf: frameBody)

        var tag = Array("ID3".utf8)
        tag.append(contentsOf: [3, 0])  // Version 2.3.0, as major and revision.
        tag.append(0)  // Tag flags: no unsynchronisation, no extended header.
        // The tag size is a syncsafe integer: 7 bits per byte, so no byte can look like a sync word.
        for byteIndex in 0..<4 {
            tag.append(UInt8((frame.count >> (7 * (3 - byteIndex))) & 0x7F))
        }
        tag.append(contentsOf: frame)
        return tag
    }
}
