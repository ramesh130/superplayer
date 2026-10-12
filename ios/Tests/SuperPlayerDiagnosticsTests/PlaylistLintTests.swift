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
import XCTest

// `@testable`, argued: the lint is ADR-0020 rule 8's departure, and what bounds it is that it reads
// RFC 8216's grammar and refuses exactly the three things the RFC tells a client to refuse. Through
// the public API every refusal is one finding with no reason — deliberately, since the bytes may
// quote a token — and the loopback origin serves only the corpus and the synthetic streams, none of
// which carries a byte-order mark, a quoted comma or both kinds of tag. So the grammar is held here,
// on literals, and the doctor's behaviour over served content is `MediaSourceDoctorTests`'.
@testable import SuperPlayerDiagnostics

/// The doctor's RFC 8216 lint on string literals: the three refusals, the attribute-list grammar,
/// and the readings the rules depend on.
final class PlaylistLintTests: XCTestCase {

    private let base = URL(string: "https://cdn.example/film/master.m3u8?token=abc")!

    func testTheThreeRefusalsAreTheRfcsOwnAndNothingElseIsRefused() {
        XCTAssertEqual(read(Data([0xEF, 0xBB, 0xBF]) + Data("#EXTM3U\n".utf8)), .byteOrderMark)
        XCTAssertEqual(read(Data([0x23, 0x45, 0xFF, 0xFE])), .notUtf8)
        XCTAssertEqual(read("#EXT-X-VERSION:3\n#EXTM3U\n"), .noHeader)
        XCTAssertEqual(read("<html>consent</html>"), .noHeader)
        XCTAssertEqual(
            read("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n#EXTINF:2.0,\ns.ts\n"),
            .bothKinds)

        // The control: what the RFC does not tell a client to refuse is read, however odd — an
        // unknown tag, a comment, CR LF line ends, blank lines and an attribute with no value.
        let lenient =
            "#EXTM3U\r\n\r\n# a comment\r\n#EXT-X-FUTURE-TAG:X=1\r\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=128000,JUNK\r\nmedia.m3u8\r\n"
        guard case .success(.multivariant(let playlist)) = lint(lenient) else {
            return XCTFail("a lenient document was refused")
        }
        XCTAssertEqual(playlist.variants.map(\.bandwidthBps), [128_000])
    }

    func testAQuotedAttributeValueMayHoldCommas() {
        let attributes = PlaylistLint.attributes(
            #"BANDWIDTH=1280000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,AUDIO="aac""#)
        XCTAssertEqual(
            attributes,
            [
                "BANDWIDTH": "1280000", "CODECS": "avc1.4d401f,mp4a.40.2",
                "RESOLUTION": "1280x720", "AUDIO": "aac",
            ])
        // A name repeated keeps the first value: the one declared.
        XCTAssertEqual(PlaylistLint.attributes("BANDWIDTH=1,BANDWIDTH=2"), ["BANDWIDTH": "1"])
    }

    func testEveryUriIsResolvedAgainstThePlaylistItCameFrom() {
        let text =
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio/en.m3u8\"\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"a\"\nlow/media.m3u8\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=2\nhttps://other.example/high.m3u8\n"
        guard case .success(.multivariant(let playlist)) = lint(text) else {
            return XCTFail("not read as a multivariant playlist")
        }
        // spec: RFC 3986 §5.3 — the base's query is not carried onto a relative reference.
        XCTAssertEqual(
            playlist.variants.map(\.url.absoluteString),
            ["https://cdn.example/film/low/media.m3u8", "https://other.example/high.m3u8"])
        XCTAssertEqual(
            playlist.audioRenditions.map(\.url?.absoluteString),
            ["https://cdn.example/film/audio/en.m3u8"])
        XCTAssertEqual(playlist.audioCodecs(ofGroup: "a"), nil, "the variant declares no CODECS")
    }

    func testAMediaPlaylistIsReadForWhatTheRulesAsk() {
        let text = [
            "#EXTM3U", "#EXT-X-TARGETDURATION:6", "#EXT-X-MEDIA-SEQUENCE:7",
            "#EXTINF:5.5,first", "s0.aac", "#EXT-X-DISCONTINUITY", "#EXTINF:6,", "s1.aac",
        ].joined(separator: "\n")
        guard case .success(.media(let playlist)) = lint(text) else {
            return XCTFail("not read as a media playlist")
        }
        XCTAssertEqual(playlist.targetDurationSeconds, 6)
        XCTAssertEqual(playlist.mediaSequence, 7)
        XCTAssertEqual(playlist.discontinuitySequence, 0, "absent, so zero (§4.3.3.3)")
        XCTAssertTrue(playlist.isLive, "no EXT-X-ENDLIST")
        XCTAssertEqual(playlist.segments.map(\.durationSeconds), [5.5, 6])
        XCTAssertEqual(playlist.segments.map(\.discontinuitiesBefore), [0, 1])
    }

    func testCodecsAreSortedByKindAsMedia3SortsThem() {
        XCTAssertTrue(Codecs.hasVideo("avc1.4d401f,mp4a.40.2"))
        XCTAssertFalse(Codecs.hasVideo("mp4a.40.2"))
        XCTAssertEqual(Codecs.audio("avc1.4d401f,mp4a.40.5"), "mp4a.40.5")
        XCTAssertNil(Codecs.audio("avc1.4d401f"))
        XCTAssertEqual(LadderPathologies.aacObjectType("mp4a.40.29"), 29)
        XCTAssertNil(LadderPathologies.aacObjectType("mp4a.40.2,ac-3"))
        XCTAssertNil(LadderPathologies.aacObjectType("ac-3"))
    }

    func testARepeatedVariantUriIsOneRungAsMedia3ReadsIt() {
        let text =
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nmedia.m3u8\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=6000000\nmedia.m3u8\n"
        guard case .success(.multivariant(let playlist)) = lint(text) else {
            return XCTFail("not read as a multivariant playlist")
        }
        XCTAssertEqual(playlist.variants.map(\.bandwidthBps), [128_000])
    }

    func testTheLiveWindowRuleWaitsForAWindowTheServerHasSlid() {
        // The live-window rule's gate, which no served stream reaches: the origin's short window
        // has always slid. Two segments against a two-second target are short in all three, and
        // only the playlist that has removed something — and is no EVENT — breaks RFC 8216 §6.2.2.
        func window(sequence: Int, type: String? = nil, durations: [String] = ["2.0", "2.0"])
            -> [Pathology]
        {
            let lines =
                ["#EXTM3U", "#EXT-X-TARGETDURATION: 2", "#EXT-X-MEDIA-SEQUENCE:\(sequence)"]
                + (type.map { ["#EXT-X-PLAYLIST-TYPE:\($0)"] } ?? [])
                + durations.enumerated().flatMap { ["#EXTINF:\($1),", "s\($0).aac"] }
            guard case .success(.media(let playlist)) = lint(lines.joined(separator: "\n")) else {
                XCTFail("not read as a media playlist")
                return []
            }
            return HlsPathologies.inMediaPlaylist(playlist).map(\.pathology)
        }
        XCTAssertEqual(window(sequence: 4), [.hlsShortLiveWindow], "slid, and short")
        XCTAssertEqual(window(sequence: 0), [], "a stream that has only just begun")
        XCTAssertEqual(window(sequence: 4, type: "EVENT"), [], "an EVENT playlist only appends")
        XCTAssertEqual(
            window(sequence: 4, durations: ["2.0", "x"]), [], "a duration the lint could not read")
    }

    private func lint(_ text: String) -> Result<PlaylistLint.Playlist, PlaylistLint.Unreadable> {
        PlaylistLint.read(Data(text.utf8), from: base)
    }

    /// Why `bytes` were refused, or nil where they were read.
    private func read(_ bytes: Data) -> PlaylistLint.Unreadable? {
        if case .failure(let why) = PlaylistLint.read(bytes, from: base) { return why }
        return nil
    }

    private func read(_ text: String) -> PlaylistLint.Unreadable? { read(Data(text.utf8)) }
}
