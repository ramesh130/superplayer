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
import MoqFFI
import SuperPlayerCore
import XCTest

@testable import SuperPlayerMoQ

/// What a publisher declared becomes what the realtime seam accepts — the Kotlin
/// `MoqCatalogTracksTest`, and where "both codec-description shapes are covered by a test" is
/// discharged on this platform. A pure mapping: no session, no frames, no native call.
final class MoqCatalogTracksTests: XCTestCase {

    /// The `avc1` shape: the record reaches the seam as the bytes the catalog declared. `Data` is a
    /// value, so "not a copy" — the Kotlin `assertSame` — has no meaning to assert here; equality of
    /// the bytes is the whole claim.
    func testAnAvc1RenditionCarriesItsRecordToTheSeamExactlyAsReceived() throws {
        let tracks = try declared(
            video: DeclaredCatalogs.video(
                DeclaredCatalogs.observedAvc1Codec,
                description: DeclaredCatalogs.observedAvccPrefix))

        XCTAssertEqual(tracks.count, 1)
        XCTAssertEqual(tracks[0].track.codec, DeclaredCatalogs.observedAvc1Codec)
        XCTAssertEqual(
            tracks[0].track.codecConfiguration, .record(DeclaredCatalogs.observedAvccPrefix))
    }

    /// The other shape: no `description`, so parameter sets in band and no record at all.
    func testAnAvc3RenditionCarriesNoRecordAtAll() throws {
        let tracks = try declared(
            video: DeclaredCatalogs.video(DeclaredCatalogs.observedAvc3Codec, description: nil))

        XCTAssertEqual(tracks[0].track.codec, DeclaredCatalogs.observedAvc3Codec)
        XCTAssertEqual(tracks[0].track.codecConfiguration, .inBand)
    }

    func testAnHvc1RenditionCarriesItsRecordTheSameWay() throws {
        let tracks = try declared(
            video: DeclaredCatalogs.video(
                DeclaredCatalogs.synthesizedHvc1Codec,
                description: DeclaredCatalogs.synthesizedHvcc))

        XCTAssertEqual(
            tracks[0].track.codecConfiguration, .record(DeclaredCatalogs.synthesizedHvcc))
    }

    /// A present but empty record is a record of no bytes rather than in band: what an empty record
    /// means is a judgement about bytes, and this product makes none.
    func testAnEmptyRecordIsStillARecord() throws {
        let tracks = try declared(
            video: DeclaredCatalogs.video(DeclaredCatalogs.observedAvc1Codec, description: Data()))

        XCTAssertEqual(tracks[0].track.codecConfiguration, .record(Data()))
    }

    func testAudioBesideVideoMapsToOneListWithVideoFirst() throws {
        let tracks = try MoqCatalogTracks.declaredTracks(
            of: DeclaredCatalogs.catalog(
                video: [
                    "video": DeclaredCatalogs.video(
                        DeclaredCatalogs.observedAvc1Codec,
                        description: DeclaredCatalogs.observedAvccPrefix)
                ],
                audio: ["audio": DeclaredCatalogs.audio(DeclaredCatalogs.synthesizedAacCodec)]))

        XCTAssertEqual(
            tracks.map(\.track.codec),
            [DeclaredCatalogs.observedAvc1Codec, DeclaredCatalogs.synthesizedAacCodec])
        XCTAssertEqual(tracks.map(\.trackName), ["video", "audio"])
    }

    func testAudioAloneIsTheOnlyTrackAndIsIndexedFirst() throws {
        let tracks = try MoqCatalogTracks.declaredTracks(
            of: DeclaredCatalogs.catalog(audio: [
                "audio": DeclaredCatalogs.audio(DeclaredCatalogs.synthesizedOpusCodec)
            ]))

        XCTAssertEqual(tracks.map(\.track.codec), [DeclaredCatalogs.synthesizedOpusCodec])
    }

    /// A codec string nothing maps survives verbatim, beside the one that maps: the realtime engine
    /// refuses the subscription by name, rather than this product playing a narrower stream.
    func testACodecStringNothingMapsSurvivesIntoTheListVerbatim() throws {
        let tracks = try MoqCatalogTracks.declaredTracks(
            of: DeclaredCatalogs.catalog(
                video: [
                    "video": DeclaredCatalogs.video(
                        DeclaredCatalogs.synthesizedUnmappedCodec, description: nil)
                ],
                audio: ["audio": DeclaredCatalogs.audio(DeclaredCatalogs.synthesizedAacCodec)]))

        XCTAssertEqual(
            tracks.map(\.track.codec),
            [DeclaredCatalogs.synthesizedUnmappedCodec, DeclaredCatalogs.synthesizedAacCodec])
    }

    func testACatalogDeclaringNeitherKindIsRefused() {
        XCTAssertThrowsError(try MoqCatalogTracks.declaredTracks(of: DeclaredCatalogs.catalog())) {
            XCTAssertTrue("\($0)".contains("no video track and no audio track"), "\($0)")
        }
    }

    /// One rendition per kind and nothing preferred — on this platform the one whose name sorts
    /// first, because the bindings' maps have no order to take a "first" from
    /// (``MoqCatalogTracks``). Asserted over many renditions so a hash order agreeing by chance
    /// cannot pass it, with the stalled flag on the chosen one to show it is read by nothing.
    func testALadderIsTakenAtItsFirstRenditionByNameAndNothingPrefers() throws {
        var ladder: [String: MoqVideo] = [:]
        for rung in 0..<16 {
            ladder["video-\(String(format: "%02d", rung))"] = DeclaredCatalogs.video(
                DeclaredCatalogs.observedAvc3Codec, description: nil, stalled: rung == 0)
        }

        let tracks = try MoqCatalogTracks.declaredTracks(
            of: DeclaredCatalogs.catalog(video: ladder))

        XCTAssertEqual(tracks.count, 1)
        XCTAssertEqual(tracks[0].trackName, "video-00")
    }

    func testTheTrackNameAndContainerAreCarriedForTheSubscription() throws {
        let container = MoqContainer.cmaf(init: Data([0, 0, 0, 8, 0x66, 0x74, 0x79, 0x70]))
        let tracks = try MoqCatalogTracks.declaredTracks(
            of: DeclaredCatalogs.catalog(video: [
                "video/1": DeclaredCatalogs.video(
                    DeclaredCatalogs.observedAvc3Codec, description: nil, container: container)
            ]))

        XCTAssertEqual(tracks[0].trackName, "video/1")
        XCTAssertEqual(tracks[0].container, container)
    }

    func testTheCodedSizeIsCarriedSoADecoderCanBeConfigured() throws {
        let tracks = try declared(
            video: DeclaredCatalogs.video(
                DeclaredCatalogs.observedAvc3Codec, description: nil,
                coded: MoqDimensions(width: 3520, height: 3520)))

        // The observed broadcast's own square size: a rendition is not obliged to be 16:9.
        XCTAssertEqual(
            tracks[0].track.codedSize, RealtimeTrack.CodedSize(width: 3520, height: 3520))
    }

    func testARenditionDeclaringNoSizeCarriesTheUnknownRatherThanAZero() throws {
        let tracks = try declared(
            video: DeclaredCatalogs.video(DeclaredCatalogs.observedAvc3Codec, description: nil))

        XCTAssertNil(tracks[0].track.codedSize)
    }

    /// A zero, and a dimension past `CMVideoDimensions`' `Int32`, are refused with the track named.
    func testADimensionNoDecoderCouldUseIsRefusedWithTheTrackNamed() {
        let cases: [(String, MoqDimensions)] = [
            ("a zero", MoqDimensions(width: 0, height: 720)),
            ("a height of zero", MoqDimensions(width: 1280, height: 0)),
            ("a width past Int32.max", MoqDimensions(width: UInt32.max, height: 720)),
        ]
        for (what, dimensions) in cases {
            XCTAssertThrowsError(
                try declared(
                    video: DeclaredCatalogs.video(
                        DeclaredCatalogs.observedAvc3Codec, description: nil, coded: dimensions)),
                what
            ) {
                XCTAssertTrue("\($0)".contains("video/1"), "\(what): \($0)")
            }
        }
    }

    func testAnAudioTrackCarriesNoCodedSize() throws {
        let tracks = try MoqCatalogTracks.declaredTracks(
            of: DeclaredCatalogs.catalog(audio: [
                "audio": DeclaredCatalogs.audio(DeclaredCatalogs.synthesizedAacCodec)
            ]))

        XCTAssertNil(tracks[0].track.codedSize)
    }

    /// The tracks of a catalog carrying `video` alone, under the name `video/1`.
    private func declared(video: MoqVideo) throws -> [MoqDeclaredTrack] {
        try MoqCatalogTracks.declaredTracks(
            of: DeclaredCatalogs.catalog(video: ["video/1": video]))
    }
}
