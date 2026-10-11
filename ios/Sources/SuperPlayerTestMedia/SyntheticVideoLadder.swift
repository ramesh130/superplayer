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

/// ``SyntheticVideoHlsStream``'s pictures as a **rendition ladder**: one variant per bitrate asked
/// for, each of whose segments really is as large as its rung declares — the content the benchmark
/// (#414) plays its matrix over, on demand and live.
///
/// The Kotlin `TestContent.videoLadder` and `liveVideoLadder` are *described* content: Media3's
/// fakes synthesize a sample of the declared size without a byte of media. `AVPlayer` plays only
/// what it fetches (ADR-0021, *Context*), so a ladder here has to be bytes, and a rung only means
/// something to `AVPlayer`'s variant selection — and to a shaped link — if fetching one of its
/// segments costs what its `BANDWIDTH` says. So every rung carries the same decodable picture and
/// silent audio as ``SyntheticVideoHlsStream``, and each segment is **padded** up to the rung's
/// bitrate over its two seconds with a top-level `free` box, which a reader skips by its size.
/// Encoding one picture at four qualities would make the rungs differ in what they show, and
/// nothing measured here reads a picture.
///
/// spec: ISO/IEC 14496-12 §8.1.2 — `free` (and `skip`) "may be ignored ... the contents of a free
/// space box are irrelevant"; it may occur at the file's top level, where a fragmented-MP4 segment's
/// `moof` and `mdat` are. RFC 8216 §4.3.4.2 — `BANDWIDTH` is the peak segment bitrate, which is
/// exactly what padding to it makes true.
///
/// **Lookup by name rather than a dictionary of files**, unlike the other streams here: a ladder at
/// `PRD.md` §6's rungs is a megabyte a segment at the top, and a few minutes of it on four rungs would
/// be a hundred megabytes held for every origin that serves it. A file is built when it is asked
/// for, which costs a few kilobytes of boxes and one zeroed buffer.
///
/// Like everything in this module it names no type outside the standard library, and it has no
/// Kotlin twin to hold a fixture to, for ``SyntheticVideoHlsStream``'s reason.
public enum SyntheticVideoLadder {

    /// The multivariant playlist's file name: what a player is pointed at.
    public static let multivariantPlaylistName = SyntheticVideoHlsStream.multivariantPlaylistName

    /// The one initialization segment every rung shares: the rungs differ only in padding, so their
    /// sample entries are one and the same (RFC 8216 §4.3.2.5 lets each playlist name its own; they
    /// all name this one).
    public static let initializationSegmentName = SyntheticVideoHlsStream.initializationSegmentName

    /// How many segments a live window lists: ``SyntheticHlsStream/liveWindowSegmentCount``, for
    /// its reason — three target durations of hold-back and media behind them.
    public static let liveWindowSegmentCount = SyntheticHlsStream.liveWindowSegmentCount

    /// One segment's duration: ``SyntheticVideoHlsStream/segmentDurationMs``.
    public static let segmentDurationMs = SyntheticVideoHlsStream.segmentDurationMs

    /// The media playlist of rung `rung`, counted from zero in the order the bitrates were given.
    public static func mediaPlaylistName(rung: Int) -> String { "rung\(rung).m3u8" }

    /// The name of segment `index` of rung `rung`, relative to wherever the ladder is served.
    public static func segmentName(rung: Int, index: Int) -> String {
        "rung\(rung)/" + SyntheticVideoHlsStream.segmentName(index)
    }

    /// How many bytes one segment of a rung declaring `bitrateBps` is: the rung's bitrate over one
    /// segment's duration, so the declared peak is the measured one.
    public static func segmentBytes(bitrateBps: Int) -> Int {
        Int(Int64(bitrateBps) * segmentDurationMs / 8_000)
    }

    /// The on-demand ladder's file `name`, or nil for a name it does not serve: a multivariant
    /// playlist listing `bitratesBps` in the order given, a media playlist per rung of
    /// `segmentCount` segments, the shared initialization segment, and each rung's segments.
    ///
    /// The smallest rung must still be larger than an unpadded segment, which a ladder starting at
    /// a few dozen kilobits per second is not; such a ladder is refused rather than served with
    /// rungs that understate themselves.
    public static func file(_ name: String, bitratesBps: [Int], segmentCount: Int) -> [UInt8]? {
        precondition(segmentCount >= 1, "A ladder needs at least one segment, was \(segmentCount)")
        return file(name, bitratesBps: bitratesBps, segments: 0..<segmentCount, live: false)
    }

    /// The live ladder's file `name` once `publishedSegmentCount` segments have been published, or
    /// nil for a name it does not serve — ``SyntheticHlsStream/liveFiles(publishedSegmentCount:)``'s
    /// sliding window on every rung: the last ``liveWindowSegmentCount`` segments listed, no
    /// `EXT-X-ENDLIST`, and a segment that has slid out of the window gone, as from a real origin.
    public static func liveFile(_ name: String, bitratesBps: [Int], publishedSegmentCount: Int)
        -> [UInt8]?
    {
        precondition(
            publishedSegmentCount >= liveWindowSegmentCount,
            "A live window of \(liveWindowSegmentCount) segments needs that many published, "
                + "not \(publishedSegmentCount)")
        let window = (publishedSegmentCount - liveWindowSegmentCount)..<publishedSegmentCount
        return file(name, bitratesBps: bitratesBps, segments: window, live: true)
    }

    private static func file(_ name: String, bitratesBps: [Int], segments: Range<Int>, live: Bool)
        -> [UInt8]?
    {
        precondition(!bitratesBps.isEmpty, "A ladder needs at least one rung")
        if name == multivariantPlaylistName {
            return Array(multivariantPlaylist(bitratesBps).utf8)
        }
        if name == initializationSegmentName {
            return SyntheticVideoHlsStream.initializationSegment()
        }
        for (rung, bitrate) in bitratesBps.enumerated() {
            if name == mediaPlaylistName(rung: rung) {
                return Array(mediaPlaylist(rung: rung, segments: segments, live: live).utf8)
            }
            for index in segments where name == segmentName(rung: rung, index: index) {
                return paddedSegment(index, toBytes: segmentBytes(bitrateBps: bitrate))
            }
        }
        return nil
    }

    // MARK: - Playlists

    /// The frame height a rung of `bitrateBps` declares — the Kotlin `TestContent.Rung.of`'s
    /// pairing, threshold for threshold, so a ladder of the same bitrates declares the same rungs on
    /// both platforms.
    ///
    /// ref: Apple, *HLS Authoring Specification for Apple Devices*, whose recommended tier tables pair
    /// each bitrate with a frame size:
    /// https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
    /// What has to be true is that a higher rung is a larger picture: a resolution ceiling then
    /// selects rather than excludes, and — found on the iOS 27.1 simulator — `AVPlayer` climbs a
    /// ladder only where the rung above declares a larger picture; a ladder of one declared size
    /// plays its first-listed rung for good, however fast the link.
    public static func declaredHeightPx(bitrateBps: Int) -> Int {
        switch bitrateBps {
        case ..<600_000: 360
        case ..<1_200_000: 480
        case ..<3_000_000: 720
        default: 1080
        }
    }

    /// The width beside ``declaredHeightPx(bitrateBps:)``: sixteen by nine, rounded to an even
    /// number as a 4:2:0 picture's dimensions are (ITU-T H.264 §6.2, chroma at half each way).
    public static func declaredWidthPx(bitrateBps: Int) -> Int {
        let height = declaredHeightPx(bitrateBps: bitrateBps)
        return (height * 16 / 9 + 1) / 2 * 2
    }

    /// spec: RFC 8216 §4.3.4.2 — one `EXT-X-STREAM-INF` per rung, its `BANDWIDTH` the rung's own
    /// bitrate, which padding makes every segment's, and its `RESOLUTION` what
    /// ``declaredHeightPx(bitrateBps:)`` pairs with it. Every rung carries the same 64×64 picture
    /// whatever it declares — the declaration is what selection reads, and nothing measured here
    /// reads a picture.
    private static func multivariantPlaylist(_ bitratesBps: [Int]) -> String {
        let stream = SyntheticVideoHlsStream.self
        let variants = bitratesBps.enumerated().flatMap { rung, bitrate in
            [
                "#EXT-X-STREAM-INF:BANDWIDTH=\(bitrate),CODECS=\"\(stream.declaredCodecs)\","
                    + "RESOLUTION=\(declaredWidthPx(bitrateBps: bitrate))x"
                    + "\(declaredHeightPx(bitrateBps: bitrate)),"
                    + "FRAME-RATE=\(stream.framesPerSecond).000",
                mediaPlaylistName(rung: rung),
            ]
        }
        return (["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS"] + variants)
            .joined(separator: "\n")
    }

    /// spec: RFC 8216 §4.3.3.2 — `EXT-X-MEDIA-SEQUENCE` is the first listed segment's number, which
    /// for a live window is how far it has slid; §4.3.3.5 — `PLAYLIST-TYPE:VOD` and §4.3.3.4 —
    /// `EXT-X-ENDLIST` only on demand, since a live playlist is one that may still grow (§6.2.1).
    private static func mediaPlaylist(rung: Int, segments: Range<Int>, live: Bool) -> String {
        let seconds = Double(segmentDurationMs) / 1_000
        let header =
            [
                "#EXTM3U",
                "#EXT-X-VERSION:7",
                "#EXT-X-TARGETDURATION:\(Int(seconds.rounded(.up)))",
                "#EXT-X-MEDIA-SEQUENCE:\(segments.lowerBound)",
            ] + (live ? [] : ["#EXT-X-PLAYLIST-TYPE:VOD"])
            + [
                "#EXT-X-INDEPENDENT-SEGMENTS",
                // Relative to the media playlist, which is at the ladder's root.
                "#EXT-X-MAP:URI=\"\(initializationSegmentName)\"",
            ]
        let listed = segments.flatMap {
            ["#EXTINF:\(seconds),", segmentName(rung: rung, index: $0)]
        }
        return (header + listed + (live ? [] : ["#EXT-X-ENDLIST"])).joined(separator: "\n")
    }

    // MARK: - Padding

    /// ``SyntheticVideoHlsStream``'s segment `index`, followed by a `free` box making the whole
    /// `bytes` long.
    ///
    /// spec: ISO/IEC 14496-12 §4.2 — a box is a 32-bit size covering itself, its four-character type
    /// and its body; eight bytes is the least one can be, so a gap smaller than that cannot be filled
    /// and is refused, as a rung below the media's own size is.
    static func paddedSegment(_ index: Int, toBytes bytes: Int) -> [UInt8] {
        let media = SyntheticVideoHlsStream.mediaSegment(index)
        let gap = bytes - media.count
        precondition(
            gap >= freeBoxHeaderBytes,
            "A rung of \(bytes) bytes a segment is smaller than the \(media.count)-byte media "
                + "plus a box header; its bitrate would understate what it costs to fetch")
        var padded = media
        padded.reserveCapacity(bytes)
        let size = UInt32(gap)
        padded += [UInt8(size >> 24), UInt8((size >> 16) & 0xFF), UInt8((size >> 8) & 0xFF)]
        padded += [UInt8(size & 0xFF)]
        padded += Array("free".utf8)
        padded += [UInt8](repeating: 0, count: gap - freeBoxHeaderBytes)
        return padded
    }

    /// A box's size and type, the header every box has.
    private static let freeBoxHeaderBytes = 8
}
