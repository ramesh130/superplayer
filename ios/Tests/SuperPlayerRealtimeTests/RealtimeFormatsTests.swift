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

import AudioToolbox
import CoreMedia
import SuperPlayerCore
import XCTest

@testable import SuperPlayerRealtime

/// The formats table on string literals alone (#431) — the Kotlin `RealtimeFormatsTest`'s cases,
/// answering CoreMedia's codec types where that test answers Media3's MIME types: every family in
/// both spellings, `mp4a` read by its object type, #340's observed strings, and the controls that
/// keep the table from being "map everything" — an unmapped string, a malformed one of a mapped
/// family, and `mp4a.69`, which is MP3.
///
/// `@testable` because the table is internal, as the Kotlin one is: what it answers is CoreMedia
/// vocabulary a consumer never names, and its one public reading — a track refused on a playing
/// player — would test one string per subscription rather than the table (ADR-0021 rule 1).
final class RealtimeFormatsTests: XCTestCase {

    func testEveryFamilyMapsInItsRfc6381Spelling() throws {
        let expected: [(String, FourCharCode, RealtimeFormat.MediaKind)] = [
            ("avc1.640028", kCMVideoCodecType_H264, .video),
            ("avc3.42E01E", kCMVideoCodecType_H264, .video),
            ("hvc1.1.6.L93.B0", kCMVideoCodecType_HEVC, .video),
            ("hev1.2.4.H120.90", kCMVideoCodecType_HEVC, .video),
            ("vp09.00.10.08", kCMVideoCodecType_VP9, .video),
            ("av01.0.04M.08", kCMVideoCodecType_AV1, .video),
            ("mp4a.40.2", kAudioFormatMPEG4AAC, .audio),
            ("opus", kAudioFormatOpus, .audio),
        ]
        for (codec, codecType, kind) in expected {
            let format = try RealtimeFormats.format(for: codec)
            XCTAssertEqual(format.codecType, codecType, codec)
            XCTAssertEqual(format.kind, kind, codec)
            XCTAssertEqual(format.codecs, codec, "\(codec) keeps the string the transport sent")
        }
    }

    func testEveryFamilyMapsInItsSdpSpellingCaseInsensitivelyAndStatesNoCodecsString() throws {
        let expected: [(String, FourCharCode)] = [
            ("H264", kCMVideoCodecType_H264), ("h265", kCMVideoCodecType_HEVC),
            ("VP9", kCMVideoCodecType_VP9), ("Av1", kCMVideoCodecType_AV1),
            ("MPEG4-GENERIC", kAudioFormatMPEG4AAC), ("OPUS", kAudioFormatOpus),
        ]
        for (codec, codecType) in expected {
            let format = try RealtimeFormats.format(for: codec)
            XCTAssertEqual(format.codecType, codecType, codec)
            // `opus` is RFC 6381's spelling too, so it keeps its string; the rest name no profile.
            XCTAssertEqual(format.codecs, codec.lowercased() == "opus" ? codec : nil, codec)
        }
    }

    func testThe340ObservedStringsReadAsConstrainedBaselineLevelThree() throws {
        for codec in ["avc1.42c01e", "avc3.42c01e"] {
            let format = try RealtimeFormats.format(for: codec)
            XCTAssertEqual(format.profileAndLevel?.profile, 66, codec)
            XCTAssertEqual(format.profileAndLevel?.level, 30, codec)
        }
    }

    func testMp4aIsMappedByItsObjectTypeAndMp3IsRefused() throws {
        XCTAssertEqual(try RealtimeFormats.format(for: "mp4a.40.2").codecType, kAudioFormatMPEG4AAC)
        XCTAssertEqual(try RealtimeFormats.format(for: "mp4a.67.2").codecType, kAudioFormatMPEG4AAC)
        for mp3 in ["mp4a.69", "mp4a.6B", "mp4a.69.2"] {
            XCTAssertThrowsError(try RealtimeFormats.format(for: mp3), mp3) { error in
                XCTAssertEqual(error as? UnsupportedRealtimeCodecError, .init(codec: mp3))
            }
        }
    }

    func testAnUnmappedStringIsRefusedNamingItself() {
        for codec in ["theora", "avc2.42c01e", "mp4a", "ac-3", ""] {
            XCTAssertThrowsError(try RealtimeFormats.format(for: codec), codec) { error in
                let refusal = error as? UnsupportedRealtimeCodecError
                XCTAssertEqual(refusal?.codec, codec)
                XCTAssertTrue(refusal?.description.contains("\"\(codec)\"") ?? false)
            }
        }
    }

    func testAMappedFamilyWhoseParametersDoNotParseIsRefused() {
        for codec in [
            "avc1.42", "avc1.zzzzzz", "hvc1.1.6", "hvc1.1.6.X93", "vp09.0", "av01.0.04X.08",
            "mp4a.40", "mp4a.40.x",
        ] {
            XCTAssertThrowsError(try RealtimeFormats.format(for: codec), codec) { error in
                XCTAssertEqual(error as? UnsupportedRealtimeCodecError, .init(codec: codec))
            }
        }
    }

    func testTheMappedTokensAreEveryFamilyInBothVocabularies() {
        XCTAssertEqual(
            RealtimeFormats.mappedTokens,
            [
                "avc1", "avc3", "hvc1", "hev1", "vp09", "av01", "opus", "mp4a", "h264", "h265",
                "vp9", "av1", "mpeg4-generic",
            ])
    }

    // MARK: - Descriptions

    func testA340RecordPassesThroughAsTheDescriptionsAvcCAtomExactlyAsReceived() throws {
        let record = try ObservedBytes.avcc()
        let description = try RealtimeFormats.formatDescription(
            for: RealtimeTrack(
                codec: "avc1.42c01e", codecConfiguration: .record(record),
                codedSize: RealtimeTrack.CodedSize(width: 640, height: 480)))

        XCTAssertEqual(description.mediaSubType, .h264)
        XCTAssertEqual(atoms(of: description)?["avcC"] as? Data, record)
        let dimensions = CMVideoFormatDescriptionGetDimensions(description)
        XCTAssertEqual([dimensions.width, dimensions.height], [640, 480])
    }

    func testAnInBandTrackIsDescribedWithNoAtomAndAnUnstatedSizeWithNone() throws {
        let description = try RealtimeFormats.formatDescription(
            for: RealtimeTrack(codec: "hev1.1.6.L93.B0"))

        XCTAssertEqual(description.mediaSubType, .hevc)
        XCTAssertNil(atoms(of: description))
        let dimensions = CMVideoFormatDescriptionGetDimensions(description)
        XCTAssertEqual([dimensions.width, dimensions.height], [0, 0])
    }

    func testAnAacRecordStatesTheRateAndChannelsTheDescriptionCarries() throws {
        // spec: ISO/IEC 14496-3 §1.6.2.1 — `00010 0100 0010 …`: AAC-LC, index 4 (44.1 kHz), stereo.
        let description = try RealtimeFormats.formatDescription(
            for: RealtimeTrack(codec: "mp4a.40.2", codecConfiguration: .record(Data([0x12, 0x10]))))

        let basic = try XCTUnwrap(description.audioStreamBasicDescription)
        XCTAssertEqual(basic.mFormatID, kAudioFormatMPEG4AAC)
        XCTAssertEqual(basic.mSampleRate, 44_100)
        XCTAssertEqual(basic.mChannelsPerFrame, 2)
    }

    func testAnAacTrackWithNoRecordIsRefusedRatherThanDescribedAtAGuessedRate() {
        XCTAssertThrowsError(
            try RealtimeFormats.formatDescription(for: RealtimeTrack(codec: "mp4a.40.2"))
        ) { error in
            XCTAssertEqual((error as? MalformedRealtimeBitstreamError)?.codec, "mp4a.40.2")
        }
        XCTAssertThrowsError(
            try RealtimeFormats.formatDescription(
                for: RealtimeTrack(codec: "mp4a.40.2", codecConfiguration: .record(Data([0x12]))))
        ) { error in
            XCTAssertNotNil(error as? MalformedRealtimeBitstreamError, "a record that ends early")
        }
    }

    func testOpusIsDescribedAtFortyEightKilohertz() throws {
        let description = try RealtimeFormats.formatDescription(for: RealtimeTrack(codec: "opus"))

        let basic = try XCTUnwrap(description.audioStreamBasicDescription)
        XCTAssertEqual(basic.mFormatID, kAudioFormatOpus)
        XCTAssertEqual(basic.mSampleRate, 48_000)
        XCTAssertEqual(basic.mChannelsPerFrame, 2)
    }

    private func atoms(of description: CMFormatDescription) -> [String: Any]? {
        CMFormatDescriptionGetExtension(
            description, extensionKey: kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms
        )
            as? [String: Any]
    }
}
