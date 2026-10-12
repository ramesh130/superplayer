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

import Foundation

/// The doctor's own reading of an HLS playlist, written to RFC 8216 — **a lint, not a parser a
/// player runs**, and the one departure from ADR-0015 rule 6 that ADR-0020 rule 8 decides.
///
/// On Android the doctor parses with Media3's `HlsPlaylistParser`, the parser the engine is about to
/// run over the same bytes, so the two can never disagree about what a playlist *says*.
/// AVFoundation exposes no playlist parser at all, and the alternative to a reading of our own is no
/// doctor. The departure is bounded the three ways the rule names: **this reading decides nothing
/// about playback** — no player reads its output; a finding on iOS reads a document as the RFC
/// describes it rather than as AVFoundation does; and the thresholds the rules apply to it are the
/// Kotlin ones, ported with their sources rather than re-derived (`LadderPathologies`,
/// `HlsPathologies`). What keeps the two readings in step is the corpus register: a reading that
/// misses what Media3 reads in an entry fails that entry's row (`CorpusRegisterTests`).
///
/// It is not a stack either (`PRD.md` §0.1): it reads exactly the tags a rule asks about and
/// passes over every other, and it is **lenient everywhere the RFC does not tell a client to
/// refuse** — an attribute it cannot read is absent, a tag it does not know is skipped — because a
/// lint that refused what a player plays would report "unreadable" for a stream that starts.
///
/// The three refusals are the RFC's own, and they are what ``Pathology/manifestUnreadable`` means
/// on this platform:
///
/// - spec: RFC 8216 §4.1 — a playlist "MUST be encoded in UTF-8" and "MUST NOT contain any Byte
///   Order Mark"; "Clients SHOULD fail to parse Playlists that contain a BOM or do not parse as
///   UTF-8".
/// - spec: RFC 8216 §4.3.1.1 — `EXTM3U` "MUST be the first line of every Media Playlist and every
///   Multivariant Playlist".
/// - spec: RFC 8216 §4.3.4 — "clients MUST fail to parse any Playlist that contains both a
///   Multivariant Playlist tag and either a Media Playlist tag or a Media Segment tag".
enum PlaylistLint {

    /// A document that read as a playlist: one kind or the other, never both (§4.3.4).
    enum Playlist: Equatable {
        case multivariant(MultivariantPlaylist)
        case media(MediaPlaylist)
    }

    /// Why bytes are not a playlist — which of the three refusals above they met. Carried for a
    /// reader of this file and its tests; a finding prints none of it, because the bytes that failed
    /// may quote a URL and its token (`SessionTraceRecorder`'s first redaction rule).
    enum Unreadable: Error, Equatable {
        case notUtf8
        case byteOrderMark
        case noHeader
        case bothKinds
    }

    /// `bytes` read as a playlist fetched from `url`, every URI in it resolved against `url`.
    ///
    /// spec: RFC 8216 §4.1 — a relative URI in a playlist is "relative to the URI of the Playlist
    /// that contains it", which after a redirect is where the bytes came from, so a caller passes the
    /// response's URL and not the one it asked for.
    static func read(_ bytes: Data, from url: URL) -> Result<Playlist, Unreadable> {
        // EF BB BF, the UTF-8 encoding of U+FEFF.
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { return .failure(.byteOrderMark) }
        guard let text = String(data: bytes, encoding: .utf8) else { return .failure(.notUtf8) }

        // spec: RFC 8216 §4.1 — lines end in LF or CR LF, and blank lines are ignored. Swift reads
        // CR LF as one `Character`, so both terminators are named rather than LF alone.
        let lines = text.split(
            omittingEmptySubsequences: false, whereSeparator: { $0 == "\n" || $0 == "\r\n" }
        )
        .map { $0.trimmingCharacters(in: .whitespaces) }
        guard lines.first == "#EXTM3U" else { return .failure(.noHeader) }

        var reading = Reading(base: url)
        for line in lines.dropFirst() where !line.isEmpty {
            reading.take(line)
        }
        if reading.sawMultivariantTag && reading.sawMediaTag { return .failure(.bothKinds) }
        return .success(reading.playlist)
    }

    /// The tags a multivariant playlist alone may carry — §4.3.4's list, and RFC 8216bis's
    /// content-steering tag, which belongs to the same list there.
    static let multivariantTags: Set<String> = [
        "EXT-X-MEDIA", "EXT-X-STREAM-INF", "EXT-X-I-FRAME-STREAM-INF", "EXT-X-SESSION-DATA",
        "EXT-X-SESSION-KEY", "EXT-X-CONTENT-STEERING",
    ]

    /// The tags a media playlist alone may carry: §4.3.2's media segment tags and §4.3.3's media
    /// playlist tags, with RFC 8216bis's low-latency additions, which are the same two kinds.
    static let mediaTags: Set<String> = [
        "EXTINF", "EXT-X-BYTERANGE", "EXT-X-DISCONTINUITY", "EXT-X-KEY", "EXT-X-MAP",
        "EXT-X-PROGRAM-DATE-TIME", "EXT-X-DATERANGE", "EXT-X-GAP", "EXT-X-BITRATE", "EXT-X-PART",
        "EXT-X-TARGETDURATION", "EXT-X-MEDIA-SEQUENCE", "EXT-X-DISCONTINUITY-SEQUENCE",
        "EXT-X-ENDLIST", "EXT-X-PLAYLIST-TYPE", "EXT-X-I-FRAMES-ONLY", "EXT-X-PART-INF",
        "EXT-X-SERVER-CONTROL", "EXT-X-SKIP", "EXT-X-PRELOAD-HINT", "EXT-X-RENDITION-REPORT",
    ]

    /// spec: RFC 8216 §4.2 — an attribute list: comma-separated `AttributeName=AttributeValue`
    /// pairs, where a quoted-string value is between double quotes and may itself hold commas. The
    /// quotes are taken off. A pair with no `=` ends the reading — what follows cannot be told apart
    /// from the rest of a value — and a name repeated keeps its first value, since "an
    /// AttributeName MUST NOT occur more than once" and the first is what was declared.
    static func attributes(_ list: Substring) -> [String: String] {
        var attributes: [String: String] = [:]
        var rest = list[...]
        while !rest.isEmpty {
            guard let equals = rest.firstIndex(of: "=") else { break }
            let name = rest[..<equals].trimmingCharacters(in: .whitespaces)
            rest = rest[rest.index(after: equals)...]
            let value: Substring
            if rest.first == "\"" {
                let afterQuote = rest.index(after: rest.startIndex)
                let close = rest[afterQuote...].firstIndex(of: "\"") ?? rest.endIndex
                value = rest[afterQuote..<close]
                rest = close == rest.endIndex ? rest[close...] : rest[rest.index(after: close)...]
                // Past the value to the next comma, which is where the next pair begins.
                rest = rest.firstIndex(of: ",").map { rest[rest.index(after: $0)...] } ?? ""
            } else {
                let comma = rest.firstIndex(of: ",") ?? rest.endIndex
                value = rest[..<comma]
                rest = comma == rest.endIndex ? "" : rest[rest.index(after: comma)...]
            }
            if attributes[name] == nil { attributes[name] = String(value) }
        }
        return attributes
    }

    /// One pass over a playlist's lines, collecting what both kinds of playlist could turn out to
    /// be until the end says which it was.
    private struct Reading {
        let base: URL
        var sawMultivariantTag = false
        var sawMediaTag = false

        // The multivariant half.
        var variants: [Variant] = []
        var renditions: [Rendition] = []
        var pendingVariant: [String: String]?

        // The media half.
        var segments: [Segment] = []
        var pendingDuration: Double?
        var discontinuities = 0
        var targetDurationSeconds: Int?
        var mediaSequence = 0
        var discontinuitySequence = 0
        var hasProgramDateTime = false
        var hasEndList = false
        var playlistType: String?

        init(base: URL) { self.base = base }

        var playlist: Playlist {
            if sawMultivariantTag {
                return .multivariant(
                    MultivariantPlaylist(variants: variants, renditions: renditions))
            }
            return .media(
                MediaPlaylist(
                    segments: segments, targetDurationSeconds: targetDurationSeconds,
                    mediaSequence: mediaSequence, discontinuitySequence: discontinuitySequence,
                    hasProgramDateTime: hasProgramDateTime, hasEndList: hasEndList,
                    playlistType: playlistType))
        }

        mutating func take(_ line: String) {
            guard line.hasPrefix("#") else { return takeURI(line) }
            // spec: RFC 8216 §4.1 — a line beginning `#EXT` is a tag, and any other line beginning
            // `#` is a comment, which is ignored.
            guard line.hasPrefix("#EXT") else { return }
            let body = line.dropFirst()
            let colon = body.firstIndex(of: ":")
            let name = String(body[..<(colon ?? body.endIndex)])
            let value = colon.map { body[body.index(after: $0)...] } ?? ""

            if PlaylistLint.multivariantTags.contains(name) { sawMultivariantTag = true }
            if PlaylistLint.mediaTags.contains(name) { sawMediaTag = true }

            switch name {
            case "EXT-X-STREAM-INF":
                // spec: RFC 8216 §4.3.4.2 — the tag's attributes, then the variant's URI on the
                // next line.
                pendingVariant = PlaylistLint.attributes(value)
            case "EXT-X-MEDIA":
                takeRendition(PlaylistLint.attributes(value))
            case "EXTINF":
                // spec: RFC 8216 §4.3.2.1 — `#EXTINF:<duration>,[<title>]`, a decimal number of
                // seconds before the comma.
                let duration = value.prefix { $0 != "," }
                pendingDuration = Double(duration.trimmingCharacters(in: .whitespaces))
            case "EXT-X-DISCONTINUITY":
                discontinuities += 1
            case "EXT-X-PROGRAM-DATE-TIME":
                hasProgramDateTime = true
            case "EXT-X-TARGETDURATION":
                targetDurationSeconds = Int(value)
            case "EXT-X-MEDIA-SEQUENCE":
                mediaSequence = Int(value) ?? 0
            case "EXT-X-DISCONTINUITY-SEQUENCE":
                // spec: RFC 8216 §4.3.3.3 — absent, it "SHALL be considered to be 0".
                discontinuitySequence = Int(value) ?? 0
            case "EXT-X-ENDLIST":
                hasEndList = true
            case "EXT-X-PLAYLIST-TYPE":
                playlistType = String(value)
            default:
                break
            }
        }

        /// A URI line: the variant the `EXT-X-STREAM-INF` before it described, or a media segment.
        /// A URI that cannot be resolved is passed over, as an unknown tag is.
        mutating func takeURI(_ line: String) {
            guard let url = URL(string: line, relativeTo: base)?.absoluteURL else { return }
            if let attributes = pendingVariant {
                variants.append(Variant(url: url, attributes: attributes))
                pendingVariant = nil
            } else {
                segments.append(
                    Segment(
                        url: url, durationSeconds: pendingDuration,
                        discontinuitiesBefore: discontinuities))
                pendingDuration = nil
            }
        }

        mutating func takeRendition(_ attributes: [String: String]) {
            guard let type = attributes["TYPE"], let groupId = attributes["GROUP-ID"] else {
                return
            }
            renditions.append(
                Rendition(
                    type: type, groupId: groupId,
                    url: attributes["URI"].flatMap { URL(string: $0, relativeTo: base) }?
                        .absoluteURL))
        }
    }
}

/// A multivariant playlist, as far as a rule reads one.
struct MultivariantPlaylist: Equatable {
    var variants: [Variant]
    var renditions: [Rendition]

    /// The audio renditions with their own playlist — Media3's `audios`, which is what the Kotlin
    /// rules iterate. One without a `URI` is muxed into the variant's own stream (§4.3.4.1), and
    /// Media3 keeps it as `muxedAudioFormat` rather than in that list.
    var audioRenditions: [Rendition] { renditions.filter { $0.isAudio && $0.url != nil } }

    /// Whether an audio rendition is muxed into the variants — Media3's `muxedAudioFormat != null`.
    var hasMuxedAudio: Bool { renditions.contains { $0.isAudio && $0.url == nil } }

    /// The audio codecs of the first variant that names `groupId` as its `AUDIO` group — what Media3
    /// writes onto a rendition as its format's codecs, because §4.3.4.1 gives `EXT-X-MEDIA` no
    /// `CODECS` of its own and the referencing variant's is the group's only declaration.
    func audioCodecs(ofGroup groupId: String) -> String? {
        variants.first { $0.audioGroupId == groupId }?.codecs.flatMap(Codecs.audio)
    }
}

/// One `EXT-X-STREAM-INF` and the URI after it.
struct Variant: Equatable {
    var url: URL
    /// `BANDWIDTH`, the declared peak rate in bits per second (§4.3.4.2), or nil where absent or not
    /// a decimal integer.
    var bandwidthBps: Int?
    /// `CODECS`, the RFC 6381 list, unparsed.
    var codecs: String?
    /// Whether `RESOLUTION` was declared — all a rule asks of it.
    var declaresResolution: Bool
    /// `AUDIO`, the group id of the variant's audio renditions.
    var audioGroupId: String?

    init(url: URL, attributes: [String: String]) {
        self.url = url
        bandwidthBps = attributes["BANDWIDTH"].flatMap { Int($0) }
        codecs = attributes["CODECS"]
        declaresResolution = attributes["RESOLUTION"] != nil
        audioGroupId = attributes["AUDIO"]
    }
}

/// One `EXT-X-MEDIA`.
struct Rendition: Equatable {
    var type: String
    var groupId: String
    var url: URL?

    var isAudio: Bool { type == "AUDIO" }
}

/// A media playlist, as far as a rule reads one.
struct MediaPlaylist: Equatable {
    var segments: [Segment]
    /// `EXT-X-TARGETDURATION`, whole seconds (§4.3.3.1), or nil where absent.
    var targetDurationSeconds: Int?
    /// `EXT-X-MEDIA-SEQUENCE`, zero where absent (§4.3.3.2).
    var mediaSequence: Int
    /// `EXT-X-DISCONTINUITY-SEQUENCE`, zero where absent (§4.3.3.3).
    var discontinuitySequence: Int
    var hasProgramDateTime: Bool
    var hasEndList: Bool
    /// `EXT-X-PLAYLIST-TYPE`, `EVENT` or `VOD`, or nil.
    var playlistType: String?

    /// spec: RFC 8216 §6.3.4 — a playlist with no `EXT-X-ENDLIST` is one a client reloads, because
    /// the server may add to it; `VOD` says it will not (§4.3.3.5) and is read the same way here.
    var isLive: Bool { !hasEndList && playlistType != "VOD" }
}

/// One media segment.
struct Segment: Equatable {
    var url: URL
    /// Its `EXTINF` duration in seconds, or nil where it had none to read.
    var durationSeconds: Double?
    /// How many `EXT-X-DISCONTINUITY` tags came before it in this playlist — Media3's
    /// `relativeDiscontinuitySequence`.
    var discontinuitiesBefore: Int
}

/// What an RFC 6381 `CODECS` list says, read the way Media3's `Util.getCodecsOfType` reads it: each
/// entry's sample entry name decides its kind, and an entry of no known kind is neither.
enum Codecs {

    // ref: Media3 `MimeTypes.getMediaMimeType`, which maps these sample entry names to a video
    // MIME type — ISO/IEC 14496-15's AVC and HEVC entries, Dolby Vision's, VP8 and VP9's (both
    // spellings), AV1's and MPEG-4 Part 2's — so a list naming one of them declares a picture.
    static let videoSampleEntries: Set<String> = [
        "avc1", "avc2", "avc3", "avc4", "hev1", "hvc1", "dvav", "dva1", "dvhe", "dvh1", "vp08",
        "vp09", "vp8", "vp9", "av01", "mp4v", "s263", "vvc1", "vvi1",
    ]

    // ref: the same map's audio half — AAC and the other MPEG-4 audio object types under `mp4a`,
    // AC-3, E-AC-3, AC-4, Opus, FLAC, ALAC, MPEG-H and DTS.
    static let audioSampleEntries: Set<String> = [
        "mp4a", "ac-3", "ec-3", "ec+3", "ac-4", "opus", "flac", "alac", "mhm1", "mha1", "dtsc",
        "dtse", "dtsh", "dtsl", "dtsx",
    ]

    /// Whether `codecs` names a video format.
    static func hasVideo(_ codecs: String) -> Bool {
        !entries(codecs).filter { videoSampleEntries.contains(sampleEntry($0)) }.isEmpty
    }

    /// The audio entries of `codecs`, joined as the list was, or nil where it names none — Media3's
    /// `getCodecsOfType(codecs, C.TRACK_TYPE_AUDIO)`.
    static func audio(_ codecs: String) -> String? {
        let audio = entries(codecs).filter { audioSampleEntries.contains(sampleEntry($0)) }
        return audio.isEmpty ? nil : audio.joined(separator: ",")
    }

    private static func entries(_ codecs: String) -> [String] {
        codecs.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    /// An entry's sample entry name, lowercased: what comes before its first dot.
    private static func sampleEntry(_ entry: String) -> String {
        String(entry.prefix { $0 != "." }).lowercased()
    }
}
