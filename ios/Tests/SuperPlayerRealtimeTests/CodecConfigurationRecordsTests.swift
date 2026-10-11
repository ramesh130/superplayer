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

import CoreMedia
import SuperPlayerCore
import XCTest

@testable import SuperPlayerRealtime

/// The codec configuration, mirrored (#432) — the Kotlin `CodecConfigurationRecordsTest`'s cases
/// with the conversion running the other way: a record passes into the description's atom and its
/// samples are walked at the width it declares, while in-band parameter sets are lifted into a
/// description and Annex-B samples re-framed to four-byte lengths.
///
/// **#340's observed bytes are the fixtures** (`ObservedBytes`, the file both platforms read): the
/// `avcC` dump doubling as the truncation case, the `avc3` keyframe as the in-band one, the `avc1`
/// keyframe dump as a length field running past its frame. The `hvcC` is **synthesized and labelled
/// so**, because that spike's caveat 2 saw H.264 only; and there is no in-band HEVC description
/// here at all, since CoreMedia parses an SPS to make one and no real HEVC SPS exists in this
/// repository — the family's unit types are what is asserted for it.
///
/// Where CoreMedia is asked the same question — the parameter sets and the NAL length a description
/// holds — it is read back with `CMVideoFormatDescriptionGetH264ParameterSetAtIndex`, so "the
/// record passes through" is CoreMedia's reading and not only this side's.
///
/// `@testable` because the framing is internal, as the Kotlin object is: its one public reading is
/// a stream failing on a playing player, which tests one input per subscription
/// (`RealtimeTracksTests` does that for the shapes that play).
final class CodecConfigurationRecordsTests: XCTestCase {

    /// #340's `avc1` codec string, verbatim.
    private let avc1 = "avc1.42c01e"

    // MARK: - A record passes through

    func testAnObservedAvcCPassesIntoTheAtomAndCoreMediaReadsItsParameterSetsAndLength() throws {
        let record = try ObservedBytes.avcc()
        let framing = try TrackFraming(
            track: RealtimeTrack(codec: avc1, codecConfiguration: .record(record)))

        // `ff` is `111111` reserved and `lengthSizeMinusOne = 3`.
        XCTAssertEqual(framing.shape, .lengthPrefixed(nalLengthSize: 4))
        let description = try XCTUnwrap(framing.description)
        XCTAssertEqual(atoms(of: description)?["avcC"] as? Data, record, "exactly as received")
        let read = try parameterSets(of: description)
        XCTAssertEqual(
            read.sets, [try ObservedBytes.sps(), try ObservedBytes.row("SYNTHESIZED_PPS")])
        XCTAssertEqual(read.nalLengthSize, 4)
    }

    /// The fixture's provenance, asserted rather than promised, as the Kotlin test does: where the
    /// `avcC` dump and the `avc3` keyframe describe the same bytes, they agree.
    func testTheTwoObservedDumpsCarryTheSameSpsWhereTheyOverlap() throws {
        XCTAssertEqual(try ObservedBytes.sps().prefix(16), try ObservedBytes.avccDump()[8..<24])
    }

    /// The case a hard-coded four would pass every other test while failing on: #340's record with
    /// `ff` rewritten to `fd`, so what separates it from the test above is the declaration. Both
    /// halves: CoreMedia reads the width out of the atom, and a sample framed at it passes byte for
    /// byte — while the same sample walked at four bytes is refused.
    func testARecordWhoseNalLengthIsNotFourIsReadAsWhatItSaysAndUsedOnEverySample() throws {
        var framing = try TrackFraming(
            track: RealtimeTrack(codec: avc1, codecConfiguration: .record(try avcc(lengthSize: 2))))

        XCTAssertEqual(framing.shape, .lengthPrefixed(nalLengthSize: 2))
        XCTAssertEqual(try parameterSets(of: XCTUnwrap(framing.description)).nalLengthSize, 2)
        // Two units behind two-byte lengths; read as four, the first would declare 0x000365aa bytes.
        let sample = bytes("00 03 65 aa bb 00 02 41 cc")
        XCTAssertEqual(try framing.sample(from: sample), sample)

        var four = try TrackFraming(
            track: RealtimeTrack(codec: avc1, codecConfiguration: .record(try ObservedBytes.avcc()))
        )
        XCTAssertThrowsError(try four.sample(from: sample)) {
            XCTAssertTrue(reason($0).contains("runs past the end"), reason($0))
        }
    }

    /// An `hvcC` — **synthesized** from ISO/IEC 14496-15 §8.3.3.1.2, because #340 observed none —
    /// read to its end for the width it declares and passed into the atom untouched.
    func testASynthesizedHvcCIsReadForItsLengthAndPassesIntoTheAtomUntouched() throws {
        let framing = try TrackFraming(
            track: RealtimeTrack(
                codec: "hvc1.1.6.L93.B0", codecConfiguration: .record(Self.synthesizedHvcC)))

        XCTAssertEqual(framing.shape, .lengthPrefixed(nalLengthSize: 4))
        XCTAssertEqual(
            atoms(of: try XCTUnwrap(framing.description))?["hvcC"] as? Data, Self.synthesizedHvcC)
    }

    func testACodecWhoseRecordHoldsNoNalUnitsPassesItThroughAndItsSamplesUntouched() throws {
        let record = bytes("12 10")  // AudioSpecificConfig: AAC-LC, 44.1 kHz, stereo.
        var framing = try TrackFraming(
            track: RealtimeTrack(codec: "mp4a.40.2", codecConfiguration: .record(record)))

        XCTAssertEqual(framing.shape, .passThrough)
        let frame = bytes("21 00 00 00 00 1c")
        XCTAssertEqual(try framing.sample(from: frame), frame)
    }

    // MARK: - The same refusals, the other way

    /// The fixture is #340's dump **exactly as printed** — 24 bytes of a 40-byte record declaring a
    /// 24-byte SPS with 16 behind it — so the truncation is one that really happened.
    func testATruncatedRecordIsRefusedRatherThanPassedThrough() throws {
        let dump = try ObservedBytes.avccDump()
        XCTAssertThrowsError(
            try TrackFraming(track: RealtimeTrack(codec: avc1, codecConfiguration: .record(dump)))
        ) {
            XCTAssertEqual(($0 as? MalformedRealtimeBitstreamError)?.codec, avc1)
            XCTAssertTrue(reason($0).contains("runs past the end of 24 bytes"), reason($0))
        }
    }

    /// The branch is the fourcc's, and #340's two strings differ in nothing else.
    func testAvc1AndAvc3OfTheSameProfileTakeDifferentBranches() throws {
        let outOfBand = try TrackFraming(
            track: RealtimeTrack(codec: avc1, codecConfiguration: .record(try ObservedBytes.avcc()))
        )
        let inBand = try TrackFraming(track: RealtimeTrack(codec: "avc3.42c01e"))

        XCTAssertEqual(outOfBand.shape, .lengthPrefixed(nalLengthSize: 4))
        XCTAssertNotNil(outOfBand.description)
        XCTAssertEqual(inBand.shape, .annexB(.h264))
        XCTAssertNil(inBand.description, "nothing to describe it with until its first keyframe")
    }

    func testARecordOnASelfDescribingFourccIsRefused() throws {
        let record = try ObservedBytes.avcc()
        for codec in ["avc3.42c01e", "hev1.1.6.L93.B0"] {
            XCTAssertThrowsError(
                try TrackFraming(
                    track: RealtimeTrack(codec: codec, codecConfiguration: .record(record))),
                codec
            ) { XCTAssertTrue(reason($0).contains("in band"), reason($0)) }
        }
    }

    func testAFourccThatCarriesARecordIsRefusedWhenNoneWasHandedOver() {
        for codec in [avc1, "hvc1.1.6.L93.B0"] {
            XCTAssertThrowsError(try TrackFraming(track: RealtimeTrack(codec: codec)), codec) {
                XCTAssertTrue(reason($0).contains("out of band"), reason($0))
            }
        }
    }

    /// The mistake the seam invites: a transport that converted the record itself. Annex-B handed
    /// over where a record belongs begins `00 00 00 01`, and the version byte names it.
    func testAnnexBParameterSetsHandedOverAsARecordAreRefusedByTheVersionByte() throws {
        let annexB = try ObservedBytes.avc3Keyframe()
        XCTAssertThrowsError(
            try TrackFraming(track: RealtimeTrack(codec: avc1, codecConfiguration: .record(annexB)))
        ) { XCTAssertTrue(reason($0).contains("configurationVersion is 0"), reason($0)) }
    }

    func testARecordDeclaringAThreeByteNalLengthIsRefused() throws {
        let record = try avcc(lengthSize: 3)
        XCTAssertThrowsError(
            try TrackFraming(track: RealtimeTrack(codec: avc1, codecConfiguration: .record(record)))
        ) { XCTAssertTrue(reason($0).contains("3-byte NAL length field"), reason($0)) }
    }

    /// #340's `avc1` keyframe as printed: a `00 00 02 6f` length declaring 623 bytes in front of a
    /// dump carrying 28. Walking the sample at the record's width is what catches it here, where on
    /// Android the conversion does.
    func testASampleWhoseLengthFieldRunsPastTheFrameIsRefused() throws {
        var framing = try observedAvc1()
        let dump = try ObservedBytes.row("AVC1_KEYFRAME_DUMP")
        XCTAssertThrowsError(try framing.sample(from: dump)) {
            XCTAssertTrue(reason($0).contains("a 623-byte NAL unit"), reason($0))
        }
    }

    func testASampleEndingInsideALengthFieldOrWithItsTopBitSetIsRefused() throws {
        var framing = try observedAvc1()
        // One whole unit, then three bytes where a four-byte length should be.
        XCTAssertThrowsError(try framing.sample(from: bytes("00 00 00 02 65 aa 00 00 00")))
        // `ff ff ff ff` is a length past any frame, never a negative one.
        XCTAssertThrowsError(try framing.sample(from: bytes("ff ff ff ff 65 aa"))) {
            XCTAssertTrue(reason($0).contains("runs past the end"), reason($0))
        }
    }

    // MARK: - In-band parameter sets are converted

    /// #340's `avc3` keyframe as printed: a start code, the SPS, and the next start code with
    /// nothing after it — the dump stopped before the PPS. Read as Annex-B it is one unit, the SPS;
    /// and a first keyframe carrying an SPS and no PPS is refused naming what is missing, where
    /// CoreMedia would refuse with a status that names nothing.
    func testTheObservedAvc3KeyframeYieldsItsSpsAndIsRefusedForTheMissingPps() throws {
        let keyframe = try ObservedBytes.avc3Keyframe()
        XCTAssertEqual(
            try CodecConfigurationRecords.annexBUnits(keyframe, codec: "avc3.42c01e"),
            [try ObservedBytes.sps()])

        var framing = try TrackFraming(track: RealtimeTrack(codec: "avc3.42c01e"))
        XCTAssertThrowsError(try framing.sample(from: keyframe)) {
            XCTAssertTrue(reason($0).contains("SPS and no PPS"), reason($0))
        }
    }

    /// The mirror made explicit: the observed SPS and the PPS, sent in band ahead of a slice, make a
    /// description CoreMedia reads the **same parameter sets** out of as it reads out of the
    /// observed `avcC` — at a four-byte width — and the sample that goes on is the slice alone,
    /// re-framed to a four-byte length. A later keyframe repeating them makes no new description.
    func testInBandParameterSetsMakeTheDescriptionTheRecordWouldAndTheSampleIsReframed() throws {
        let sps = try ObservedBytes.sps()
        let pps = try ObservedBytes.row("SYNTHESIZED_PPS")
        let slice = bytes("65 88 84 00 33")
        let keyframe = annexB([sps, pps, slice])
        var framing = try TrackFraming(track: RealtimeTrack(codec: "avc3.42c01e"))

        XCTAssertEqual(try framing.sample(from: keyframe), bytes("00 00 00 05") + slice)

        let inBand = try XCTUnwrap(framing.description)
        let fromRecord = try XCTUnwrap(try observedAvc1().description)
        XCTAssertEqual(try parameterSets(of: inBand).sets, [sps, pps])
        XCTAssertEqual(try parameterSets(of: inBand).sets, try parameterSets(of: fromRecord).sets)
        XCTAssertEqual(try parameterSets(of: inBand).nalLengthSize, 4)

        let dependent = bytes("41 9a 00 11")
        XCTAssertEqual(
            try framing.sample(from: bytes("00 00 01") + dependent + bytes("00 00")),
            bytes("00 00 00 04") + dependent,
            "a three-byte start code read, trailing zeros stripped")
        _ = try framing.sample(from: keyframe)
        XCTAssertTrue(
            framing.description === inBand, "the same parameter sets describe nothing new")
    }

    /// A keyframe whose parameter sets differ — the observed pair, then the synthetic stream's —
    /// re-describes the track, since the stream it heads is no longer the one described.
    func testAParameterSetChangeAtAKeyframeMakesANewDescription() throws {
        var framing = try TrackFraming(track: RealtimeTrack(codec: "avc3.42c01e"))
        let observed = [try ObservedBytes.sps(), try ObservedBytes.row("SYNTHESIZED_PPS")]
        _ = try framing.sample(from: annexB(observed + [bytes("65 88")]))
        let first = try XCTUnwrap(framing.description)

        let synthetic = SyntheticH264.parameterSets.map { Data($0) }
        _ = try framing.sample(from: annexB(synthetic + [bytes("65 88")]))

        XCTAssertFalse(framing.description === first)
        XCTAssertEqual(try parameterSets(of: XCTUnwrap(framing.description)).sets, synthetic)
    }

    func testAnInBandTracksFirstFrameWithNoParameterSetsIsRefused() throws {
        var framing = try TrackFraming(track: RealtimeTrack(codec: "avc3.42c01e"))
        XCTAssertThrowsError(try framing.sample(from: annexB([bytes("65 88 84")]))) {
            XCTAssertTrue(reason($0).contains("carried none"), reason($0))
        }
    }

    /// Obligation 7's sample half broken: a length-prefixed sample sent on a self-describing
    /// fourcc. `00 00 00 05` is no start code, and the refusal says what it probably was.
    func testALengthPrefixedSampleOnAnInBandTrackIsRefused() throws {
        var framing = try TrackFraming(track: RealtimeTrack(codec: "avc3.42c01e"))
        XCTAssertThrowsError(try framing.sample(from: bytes("00 00 00 05 65 88 84 00 33"))) {
            XCTAssertTrue(
                reason($0).contains("does not begin with an Annex-B start code"), reason($0))
        }
    }

    /// The HEVC family's units, read by H.265's own header layout — the six bits after the
    /// forbidden zero bit — on the synthesized `hvcC`'s VPS, SPS and PPS and an IDR slice; and a
    /// keyframe carrying a VPS and an SPS and no PPS refused naming the PPS.
    func testHevcParameterSetsAreReadByTheirOwnHeaderAndAMissingOneIsNamed() throws {
        let vps = bytes("40 01 0c")
        let sps = bytes("42 01 01 01")
        let pps = bytes("44 01")
        let idr = bytes("26 01 af")
        XCTAssertEqual(
            [vps, sps, pps, idr].map(NalFamily.hevc.isParameterSet), [true, true, true, false])
        XCTAssertFalse(NalFamily.h264.isParameterSet(vps), "0x40 is an H.264 slice of type 0")

        var framing = try TrackFraming(track: RealtimeTrack(codec: "hev1.1.6.L93.B0"))
        XCTAssertEqual(framing.shape, .annexB(.hevc))
        XCTAssertThrowsError(try framing.sample(from: annexB([vps, sps, idr]))) {
            XCTAssertTrue(reason($0).contains("VPS and SPS and no PPS"), reason($0))
        }
    }

    /// An SDP encoding name states no sample entry and so no expectation: the shape handed over
    /// decides, and in-band is converted as `avc3` is, since CoreMedia takes length-prefixed samples
    /// alone.
    func testAnSdpEncodingNameIsFramedByTheShapeHandedOver() throws {
        XCTAssertEqual(try TrackFraming(track: RealtimeTrack(codec: "H264")).shape, .annexB(.h264))
        XCTAssertEqual(try TrackFraming(track: RealtimeTrack(codec: "h265")).shape, .annexB(.hevc))
        XCTAssertEqual(
            try TrackFraming(
                track: RealtimeTrack(
                    codec: "H264", codecConfiguration: .record(ObservedBytes.avcc()))
            ).shape,
            .lengthPrefixed(nalLengthSize: 4))
    }

    // MARK: - Helpers

    private func observedAvc1() throws -> TrackFraming {
        try TrackFraming(
            track: RealtimeTrack(codec: avc1, codecConfiguration: .record(try ObservedBytes.avcc()))
        )
    }

    /// #340's record with its `lengthSizeMinusOne` byte rewritten and nothing else touched. The six
    /// bits above it are reserved and set to 1, the `f` of the observed `ff`.
    private func avcc(lengthSize: Int) throws -> Data {
        var record = try ObservedBytes.avcc()
        record[record.startIndex + 4] = 0xFC | UInt8(lengthSize - 1)
        return record
    }

    /// An `hvcC`, **synthesized** from ISO/IEC 14496-15 §8.3.3.1.2 because #340 observed none — the
    /// Kotlin test's `SYNTHESIZED_HVCC`, byte for byte: 22 header bytes whose last carries
    /// `lengthSizeMinusOne = 3`, then three arrays, VPS, SPS and PPS, one NAL unit each.
    private static let synthesizedHvcC = Data(
        [
            0x01, 0x01, 0x60, 0x00, 0x00, 0x00, 0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 0x5d, 0xf0,
            0x00,
            0xfc, 0xfd, 0xf8, 0xf8, 0x00, 0x00, 0xff, 0x03,
            0xa0, 0x00, 0x01, 0x00, 0x03, 0x40, 0x01, 0x0c,
            0xa1, 0x00, 0x01, 0x00, 0x04, 0x42, 0x01, 0x01, 0x01,
            0xa2, 0x00, 0x01, 0x00, 0x02, 0x44, 0x01,
        ])

    /// `units` behind four-byte Annex-B start codes.
    private func annexB(_ units: [Data]) -> Data {
        units.reduce(Data()) { $0 + bytes("00 00 00 01") + $1 }
    }

    /// The H.264 parameter sets a description holds, and its NAL length width, as CoreMedia reads
    /// them back.
    private func parameterSets(of description: CMFormatDescription) throws
        -> (sets: [Data], nalLengthSize: Int)
    {
        var count = 0
        var nalLength: Int32 = 0
        XCTAssertEqual(
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                description, parameterSetIndex: 0, parameterSetPointerOut: nil,
                parameterSetSizeOut: nil, parameterSetCountOut: &count,
                nalUnitHeaderLengthOut: &nalLength), noErr)
        let sets = try (0..<count).map { index in
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            let status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                description, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
            return Data(bytes: try XCTUnwrap(status == noErr ? pointer : nil), count: size)
        }
        return (sets, Int(nalLength))
    }

    private func atoms(of description: CMFormatDescription) -> [String: Any]? {
        CMFormatDescriptionGetExtension(
            description, extensionKey: kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms
        ) as? [String: Any]
    }

    private func reason(_ error: any Error) -> String {
        (error as? MalformedRealtimeBitstreamError)?.reason
            ?? "not a malformed-bitstream refusal: \(error)"
    }

    /// A hex dump as bytes, so a fixture can be pasted out of a bug report and read back in it.
    private func bytes(_ hex: String) -> Data {
        Data(hex.split(separator: " ").map { UInt8($0, radix: 16)! })
    }
}
