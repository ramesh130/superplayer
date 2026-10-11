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

import XCTest

@testable import SuperPlayerTestMedia

/// `SyntheticVideoLadder` as bytes: every rung's segment is exactly its declared bitrate over two
/// seconds, the padding is a `free` box after the media a reader skips, the declared sizes are the
/// Kotlin `Rung.of` pairing, and a live window slides. Whether `AVPlayer` plays and climbs it is
/// `VideoLadderPlaybackTests`', in testkit, which has an origin to serve it from.
final class SyntheticVideoLadderTests: XCTestCase {

    private let ladder = [365_000, 730_000, 2_000_000, 4_500_000]

    func testEverySegmentIsItsRungsBitrateOverTwoSecondsEndingInAFreeBox() throws {
        for (rung, bitrate) in ladder.enumerated() {
            let segment = try XCTUnwrap(
                SyntheticVideoLadder.file(
                    SyntheticVideoLadder.segmentName(rung: rung, index: 1), bitratesBps: ladder,
                    segmentCount: 4))
            XCTAssertEqual(segment.count, bitrate * 2 / 8, "rung \(rung)")

            // The media first, unchanged, and then one box covering the rest.
            let media = SyntheticVideoHlsStream.mediaSegment(1)
            XCTAssertEqual(Array(segment.prefix(media.count)), media)
            let box = Array(segment.dropFirst(media.count))
            let size = box.prefix(4).reduce(0) { $0 << 8 | Int($1) }
            XCTAssertEqual(size, box.count)
            XCTAssertEqual(String(decoding: box[4..<8], as: UTF8.self), "free")
        }
    }

    /// The control on the padding: the media is the same on every rung, so the rungs differ in what
    /// fetching them costs and in nothing else.
    func testTheRungsCarryOneAndTheSameMedia() throws {
        let segments = try ladder.indices.map { rung in
            try XCTUnwrap(
                SyntheticVideoLadder.file(
                    SyntheticVideoLadder.segmentName(rung: rung, index: 2), bitratesBps: ladder,
                    segmentCount: 4))
        }
        let media = SyntheticVideoHlsStream.mediaSegment(2).count
        XCTAssertEqual(Set(segments.map { Array($0.prefix(media)) }).count, 1)
        XCTAssertEqual(Set(segments.map(\.count)).count, ladder.count)
    }

    /// The Kotlin `TestContent.Rung.of` thresholds: below 600 kbit/s 360p, below 1.2 Mbit/s 480p,
    /// below 3 Mbit/s 720p, and 1080p from there — each sixteen by nine.
    func testTheMultivariantPlaylistDeclaresEachRungAtItsKotlinSize() throws {
        let playlist = String(
            decoding: try XCTUnwrap(
                SyntheticVideoLadder.file(
                    SyntheticVideoLadder.multivariantPlaylistName, bitratesBps: ladder,
                    segmentCount: 4)), as: UTF8.self)

        for (bitrate, size) in zip(ladder, ["640x360", "854x480", "1280x720", "1920x1080"]) {
            XCTAssertTrue(playlist.contains("BANDWIDTH=\(bitrate),"), playlist)
            XCTAssertTrue(playlist.contains("RESOLUTION=\(size),"), "\(bitrate): \(playlist)")
        }
        XCTAssertEqual(SyntheticVideoLadder.declaredHeightPx(bitrateBps: 599_999), 360)
        XCTAssertEqual(SyntheticVideoLadder.declaredHeightPx(bitrateBps: 600_000), 480)
        XCTAssertEqual(SyntheticVideoLadder.declaredHeightPx(bitrateBps: 3_000_000), 1080)
    }

    func testAnOnDemandPlaylistListsEverySegmentAndEnds() throws {
        let playlist = String(
            decoding: try XCTUnwrap(
                SyntheticVideoLadder.file(
                    SyntheticVideoLadder.mediaPlaylistName(rung: 1), bitratesBps: ladder,
                    segmentCount: 3)), as: UTF8.self)

        XCTAssertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        XCTAssertTrue(playlist.hasSuffix("#EXT-X-ENDLIST"))
        XCTAssertTrue(playlist.contains("rung1/segment2.m4s"))
        XCTAssertFalse(playlist.contains("segment3"))
        XCTAssertNil(
            SyntheticVideoLadder.file("rung1/segment3.m4s", bitratesBps: ladder, segmentCount: 3))
    }

    /// The live window: the last six published, numbered from where it has slid to, no end, and
    /// the segment that slid out gone — with the on-demand form as the control on the end tag.
    func testALiveWindowSlidesAndListsNoEnd() throws {
        let published = SyntheticVideoLadder.liveWindowSegmentCount + 3
        let playlist = String(
            decoding: try XCTUnwrap(
                SyntheticVideoLadder.liveFile(
                    SyntheticVideoLadder.mediaPlaylistName(rung: 0), bitratesBps: ladder,
                    publishedSegmentCount: published)), as: UTF8.self)

        XCTAssertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:3"), playlist)
        XCTAssertFalse(playlist.contains("ENDLIST"))
        XCTAssertFalse(playlist.contains("PLAYLIST-TYPE"))
        XCTAssertNil(
            SyntheticVideoLadder.liveFile(
                "rung0/segment2.m4s", bitratesBps: ladder, publishedSegmentCount: published))
        XCTAssertNotNil(
            SyntheticVideoLadder.liveFile(
                "rung0/segment\(published - 1).m4s", bitratesBps: ladder,
                publishedSegmentCount: published))
    }
}
