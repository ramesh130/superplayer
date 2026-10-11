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
import Foundation
import SuperPlayerCore

/// One track's frames on their way to CoreMedia: the format description they are enqueued under,
/// and the framing their samples are put in — the Kotlin `TrackConfiguration`, **mirrored**.
///
/// ## Which way the conversion runs
///
/// **The other way from Android's** (`docs/ios-plan.md` §3.10). `MediaCodec` is fed Annex-B with
/// parameter sets as `csd-0`/`csd-1`, so the Kotlin `CodecConfigurationRecords` parses an `avcC` or
/// `hvcC` *into* Annex-B and re-frames length-prefixed samples *to* start codes. CoreMedia's native
/// shape is the record and length-prefixed samples, so here:
///
/// - a **record** (`avc1`, `hvc1`) passes into the format description as its atom, exactly as
///   received, and its samples stay length-prefixed at the width it declares — that width read out
///   of the record and used to walk every sample before it is enqueued; and
/// - **in-band** parameter sets (`avc3`, `hev1`) are what need converting: lifted out of the
///   keyframes that carry them into a description made by
///   `CMVideoFormatDescriptionCreateFromH264ParameterSets` (or its HEVC twin), and every Annex-B
///   sample re-framed to four-byte lengths.
///
/// The refusals are the Kotlin ones in the other direction, each a ``MalformedRealtimeBitstreamError``
/// naming its codec: a record that does not parse or declares a forbidden width, a record on a
/// self-describing fourcc and none on one that carries it, a sample whose length field runs past it,
/// and — this side's own, because only this side reads in-band parameter sets — an in-band sample
/// with no start code, or a first keyframe without the parameter sets a description needs.
///
/// Stateful only for an in-band track, whose description is made at its first keyframe and made
/// again when a later one carries different parameter sets; every other track's is fixed when it is
/// declared. Confined to the engine's queue, as every other piece of track state is.
struct TrackFraming {

    /// The codec string the track was declared as, carried so a refusal raised on a *sample* names
    /// the codec a refusal raised on the record would.
    let codec: String

    /// Which renderer takes the track.
    let kind: RealtimeFormat.MediaKind

    /// What the next sample is enqueued under: fixed at declaration for every track but an in-band
    /// one, whose description is nil until its first keyframe.
    private(set) var description: CMFormatDescription?

    /// How this track's samples are framed on the way in.
    let shape: Shape

    /// The parameter sets ``description`` was last made from, so a keyframe repeating them — every
    /// keyframe of an `avc3` stream does — makes no new description.
    private var parameterSets: [Data] = []

    enum Shape: Equatable {
        /// Not a NAL-unit codec: the payload is the sample, as #431 enqueued it.
        case passThrough
        /// Length-prefixed NAL units behind fields of this many bytes, which the record declared.
        case lengthPrefixed(nalLengthSize: Int)
        /// Annex-B NAL units carrying their own parameter sets, converted here.
        case annexB(NalFamily)
    }

    /// The framing `track` declares, or the refusal of a declaration that does not hold together.
    init(track: RealtimeTrack) throws {
        let format = try RealtimeFormats.format(for: track.codec)
        codec = track.codec
        kind = format.kind
        shape = try CodecConfigurationRecords.shape(
            of: track.codec, format: format, configuration: track.codecConfiguration)
        if case .annexB = shape {
            description = nil
        } else {
            // A record passes through as the description's atom (`RealtimeFormats`); a codec that
            // is not NAL units is described as #431 described it.
            description = try RealtimeFormats.formatDescription(for: track)
        }
    }

    /// `payload` in CoreMedia's framing, or nil where the frame carried parameter sets and nothing
    /// to decode. An in-band track's ``description`` is brought up to date first.
    mutating func sample(from payload: Data) throws -> Data? {
        switch shape {
        case .passThrough:
            return payload
        case .lengthPrefixed(let size):
            // Walked, not converted: CoreMedia reads the same width out of the record's atom, so a
            // sample that holds together at it passes byte for byte, and one that does not is
            // refused here rather than handed to a decoder that would render nothing.
            try CodecConfigurationRecords.validateLengthPrefixed(
                payload, lengthSize: size, codec: codec)
            return payload
        case .annexB(let family):
            let units = try CodecConfigurationRecords.annexBUnits(payload, codec: codec)
            let found = units.filter { family.isParameterSet($0) }
            if !found.isEmpty, found != parameterSets {
                description = try CodecConfigurationRecords.inBandDescription(
                    family, parameterSets: found, codec: codec)
                parameterSets = found
            }
            guard description != nil else {
                throw MalformedRealtimeBitstreamError(
                    codec: codec,
                    reason: "the track carries its parameter sets in band, and its first frame "
                        + "carried none, so there is nothing to describe it with "
                        + "(FrameSource obligations 3 and 7)")
            }
            let picture = units.filter { !family.isParameterSet($0) }
            return picture.isEmpty ? nil : CodecConfigurationRecords.lengthPrefixed(picture)
        }
    }
}

/// The two NAL-unit families, and which units of each are parameter sets.
enum NalFamily: Equatable {
    case h264, hevc

    /// Whether `unit` (a NAL unit with no start code and no length) is one a description is made
    /// from rather than one a decoder decodes.
    ///
    /// spec: ITU-T H.264 §7.3.1 and Table 7-1 — `nal_unit_type` is the low five bits of the first
    ///   byte; 7 is a sequence parameter set and 8 a picture parameter set.
    /// spec: ITU-T H.265 §7.3.1.2 and Table 7-1 — `nal_unit_type` is the six bits after the
    ///   forbidden zero bit; 32, 33 and 34 are the video, sequence and picture parameter sets.
    func isParameterSet(_ unit: Data) -> Bool {
        !unit.isEmpty && requiredTypes.contains(type(of: unit))
    }

    /// The parameter set types a description needs, in the order CoreMedia takes them.
    var requiredTypes: [UInt8] {
        switch self {
        case .h264: [7, 8]
        case .hevc: [32, 33, 34]
        }
    }

    /// `unit`'s `nal_unit_type`.
    func type(of unit: Data) -> UInt8 {
        guard let first = unit.first else { return 0 }
        switch self {
        case .h264: return first & 0x1F
        case .hevc: return (first >> 1) & 0x3F
        }
    }

    /// What a type is called, for a refusal's words.
    func name(of type: UInt8) -> String {
        switch (self, type) {
        case (.h264, 7), (.hevc, 33): "SPS"
        case (.h264, 8), (.hevc, 34): "PPS"
        case (.hevc, 32): "VPS"
        default: "NAL unit type \(type)"
        }
    }
}

/// The one place a codec's configuration is read, keyed on the codec's **fourcc** and never on the
/// container the frames arrived in (ADR-0018 rule 4) — the Kotlin `CodecConfigurationRecords`,
/// mirrored as ``TrackFraming`` says.
///
/// #340 observed that the container decides nothing: `avc1.*` carries an `avcC` and no in-band
/// parameter sets, `avc3.*` parameter sets before every keyframe and no record. **The H.264 half is
/// observed and the H.265 half is not** (that spike's caveat 2): what is written for `hvc1` and
/// `hev1` is ISO/IEC 14496-15 §8.3.3.1.2 and ITU-T H.265 read as documents, and the first transport
/// to carry HEVC is expected to confirm it, as ADR-0018 rule 4 asks.
///
/// **Why the record is parsed at all, when it passes through untouched:** for the Kotlin reason
/// turned round. CoreMedia takes an atom without reading it, so a truncated `avcC`, Annex-B handed
/// over where a record belongs, or a three-byte length width would make a description that looks
/// fine and a decoder that renders nothing; and the width it declares is what every sample is walked
/// at. A parse that refuses is the only place those surface as their cause.
enum CodecConfigurationRecords {

    /// What `configuration` means for `codec`, whose fourcc decides.
    static func shape(
        of codec: String, format: RealtimeFormat,
        configuration: RealtimeTrack.CodecConfiguration
    ) throws -> TrackFraming.Shape {
        switch format.token {
        case "avc1", "hvc1":
            let record = try recordOf(codec, fourcc: format.token, configuration)
            return .lengthPrefixed(nalLengthSize: try nalLengthSize(of: record, format, codec))
        case "avc3", "hev1":
            // Asserted rather than assumed, as the Kotlin branch is: a record here is obligation 7
            // broken in the direction that used to be silent — a description made from parameter
            // sets that are about to arrive again in band.
            if case .record(let bytes) = configuration {
                throw MalformedRealtimeBitstreamError(
                    codec: codec,
                    reason: "the fourcc \(format.token) carries its parameter sets in band, so no "
                        + "configuration record is expected, but one of \(bytes.count) bytes was "
                        + "handed over")
            }
            return .annexB(format.codecType == kCMVideoCodecType_HEVC ? .hevc : .h264)
        case "h264", "h265":
            // An SDP encoding name states no sample entry and so no expectation, as on Android;
            // what differs is that CoreMedia needs a description either way, so the shape handed
            // over decides — a record is read as the fourcc that carries one, in-band as the
            // fourcc that does not. Android passes in-band Annex-B to `MediaCodec` unconverted;
            // here it is converted, because CoreMedia takes length-prefixed samples only.
            let family: NalFamily = format.token == "h265" ? .hevc : .h264
            switch configuration {
            case .record(let record):
                return .lengthPrefixed(nalLengthSize: try nalLengthSize(of: record, format, codec))
            case .inBand:
                return .annexB(family)
            }
        default:
            // No codec but H.264 and H.265 carries its configuration as NAL units, so a record
            // passes through as #431 passed it — `av1C`, `vpcC`, an AudioSpecificConfig, an
            // OpusHead — and an in-band one needs nothing. Pass-through is the honest default and
            // not a claim that it is right for every codec, for the Kotlin comment's reason.
            return .passThrough
        }
    }

    /// The record `fourcc` requires, or the refusal for a transport that handed over none.
    private static func recordOf(
        _ codec: String, fourcc: String, _ configuration: RealtimeTrack.CodecConfiguration
    ) throws -> Data {
        guard case .record(let record) = configuration else {
            // The other direction of the same refusal: a description with no parameter sets for a
            // stream that sends none in band has nothing to decode the first keyframe against.
            throw MalformedRealtimeBitstreamError(
                codec: codec,
                reason: "the fourcc \(fourcc) carries its parameter sets out of band, so a "
                    + "configuration record is required, but the track declared "
                    + "CodecConfiguration.inBand")
        }
        return record
    }

    private static func nalLengthSize(
        of record: Data, _ format: RealtimeFormat, _ codec: String
    ) throws -> Int {
        format.codecType == kCMVideoCodecType_HEVC
            ? try hevcNalLengthSize(record, codec: codec)
            : try avcNalLengthSize(record, codec: codec)
    }

    /// An `AVCDecoderConfigurationRecord` read to its end, answering the NAL length width it
    /// declares for the track's samples.
    ///
    /// spec: ISO/IEC 14496-15 §5.3.3.1.2 — `configurationVersion`, `AVCProfileIndication`,
    ///   `profile_compatibility`, `AVCLevelIndication`, six reserved bits and `lengthSizeMinusOne`,
    ///   three reserved bits and `numOfSequenceParameterSets`, that many `(unsigned int(16) length,
    ///   NAL unit)` pairs, then `numOfPictureParameterSets` and that many more. The parameter sets'
    ///   own lengths are **16-bit by the record's grammar**, which is not `lengthSizeMinusOne`: that
    ///   field describes the *samples*, and conflating the two is the first way to read this record
    ///   wrongly. Anything after the last PPS — the profile extension of high profiles — is left to
    ///   CoreMedia, which reads it from the atom.
    static func avcNalLengthSize(_ record: Data, codec: String) throws -> Int {
        var reader = RecordReader(codec: codec, source: record)
        try reader.expectConfigurationVersion()
        try reader.skip(avcProfileAndLevelBytes)
        let size = try nalLengthSize(&reader, codec: codec)
        // The low five bits: the three above them are reserved and set to 1 by the grammar.
        for _ in 0..<(try reader.readByte() & spsCountMask) { _ = try reader.readUnit() }
        // A whole byte, unlike the SPS count — the asymmetry is the record's, not a slip.
        for _ in 0..<(try reader.readByte()) { _ = try reader.readUnit() }
        return size
    }

    /// An `HEVCDecoderConfigurationRecord`, read the same way.
    ///
    /// spec: ISO/IEC 14496-15 §8.3.3.1.2 — a 22-byte header whose last byte carries
    ///   `lengthSizeMinusOne` in its low two bits, then `numOfArrays`, then per array a byte of
    ///   completeness and NAL unit type, a 16-bit count and that many `(length, NAL unit)` pairs.
    static func hevcNalLengthSize(_ record: Data, codec: String) throws -> Int {
        var reader = RecordReader(codec: codec, source: record)
        try reader.expectConfigurationVersion()
        try reader.skip(hevcHeaderBytesBeforeLengthSize)
        let size = try nalLengthSize(&reader, codec: codec)
        for _ in 0..<(try reader.readByte()) {
            try reader.skip(1)  // array_completeness, a reserved bit and NAL_unit_type
            let count = try reader.readLength(hevcNalUnitCountBytes, "a two-byte count")
            for _ in 0..<count { _ = try reader.readUnit() }
        }
        return size
    }

    /// `lengthSizeMinusOne + 1`, or a refusal for the one value the grammar does not allow.
    ///
    /// spec: ISO/IEC 14496-15 §5.3.3.1.3 (and §8.3.3.1.3) — the field "shall be 0, 1 or 3", so a
    ///   NAL length field is one, two or four bytes wide and never three. A record declaring three
    ///   was written by nothing legal, and walking its samples at that width would look like it
    ///   worked.
    private static func nalLengthSize(_ reader: inout RecordReader, codec: String) throws -> Int {
        let declared = Int(try reader.readByte() & lengthSizeMinusOneMask) + 1
        guard declared != forbiddenNalLengthSize else {
            throw MalformedRealtimeBitstreamError(
                codec: codec,
                reason: "the record declares a \(declared)-byte NAL length field, which ISO/IEC "
                    + "14496-15 does not allow (1, 2 and 4 are its widths)")
        }
        return declared
    }

    /// Walks a length-prefixed sample at `lengthSize` and refuses it where a field or the unit it
    /// measures runs past the end — the iOS half of the Kotlin `annexBFromLengthPrefixed`, which
    /// walks the same way and converts as it goes; here nothing needs converting.
    ///
    /// spec: ISO/IEC 14496-15 §5.3.2 (and §8.3.2) — a sample of an `avc1` or `hvc1` track is a run
    ///   of NAL units, each behind an unsigned big-endian length of `lengthSizeMinusOne + 1` bytes.
    static func validateLengthPrefixed(_ sample: Data, lengthSize: Int, codec: String) throws {
        var reader = RecordReader(codec: codec, source: sample)
        // A trailing byte too few for a length field is a truncation like any other, and refused
        // rather than ignored as padding: a sample that ends mid-field is missing its last unit.
        while !reader.exhausted { _ = try reader.readUnit(lengthSize: lengthSize) }
    }

    /// The NAL units of an Annex-B sample, without their start codes.
    ///
    /// spec: ITU-T H.264 Annex B.2 (and H.265 Annex B.2) — a byte stream is NAL units each behind a
    ///   three-byte `00 00 01` start code, optionally preceded by a `zero_byte`, with any number of
    ///   `trailing_zero_8bits` after a unit; emulation prevention (§7.4.1) guarantees `00 00 01` and
    ///   `00 00 00` never occur inside a unit, so the next one found is the next boundary. Trailing
    ///   zeros are stripped from each unit, since a unit's last byte is never zero (§7.4.1's
    ///   `rbsp_trailing_bits` end with a one bit, and `cabac_zero_word`s with `03`).
    ///
    /// A payload not opening with a start code is refused: it is a length-prefixed sample sent on a
    /// self-describing track, obligation 7 broken in the half nothing else can check.
    static func annexBUnits(_ payload: Data, codec: String) throws -> [Data] {
        let bytes = [UInt8](payload)
        var starts: [(code: Int, unit: Int)] = []
        var index = 0
        while index + 2 < bytes.count {
            if bytes[index] == 0, bytes[index + 1] == 0, bytes[index + 2] == 1 {
                starts.append((index, index + 3))
                index += 3
            } else {
                index += 1
            }
        }
        guard let first = starts.first, bytes[..<first.code].allSatisfy({ $0 == 0 }) else {
            throw MalformedRealtimeBitstreamError(
                codec: codec,
                reason: "a sample of a track carrying its parameter sets in band does not begin "
                    + "with an Annex-B start code; a length-prefixed sample sent on a self-describing "
                    + "fourcc reads this way (FrameSource obligation 7)")
        }
        var units: [Data] = []
        for (position, start) in starts.enumerated() {
            var end = position + 1 < starts.count ? starts[position + 1].code : bytes.count
            while end > start.unit, bytes[end - 1] == 0 { end -= 1 }
            if end > start.unit { units.append(Data(bytes[start.unit..<end])) }
        }
        return units
    }

    /// `units` behind four-byte big-endian lengths, CoreMedia's framing for a description made from
    /// parameter sets with a ``inBandNalLengthSize``-byte header.
    static func lengthPrefixed(_ units: [Data]) -> Data {
        var out = Data()
        for unit in units {
            withUnsafeBytes(of: UInt32(unit.count).bigEndian) { out.append(contentsOf: $0) }
            out.append(unit)
        }
        return out
    }

    /// The length width an in-band track's samples are re-framed to: four, the widest the grammar
    /// allows, because a unit of an in-band stream has no upper size anyone stated.
    static let inBandNalLengthSize = 4

    /// A description made from the parameter sets an in-band keyframe carried.
    ///
    /// Every type the family needs must be present — an SPS and a PPS, and for HEVC a VPS too — or
    /// the frame is refused naming what was missing: CoreMedia would refuse too, with a status that
    /// names nothing. What CoreMedia refuses after that (an SPS it cannot parse) carries its status.
    ///
    /// ref: https://developer.apple.com/documentation/coremedia/cmvideoformatdescriptioncreatefromh264parametersets(allocator:parametersetcount:parametersetpointers:parametersetsizes:nalunitheaderlength:formatdescriptionout:)
    static func inBandDescription(
        _ family: NalFamily, parameterSets found: [Data], codec: String
    ) throws -> CMFormatDescription {
        let missing = family.requiredTypes.filter { type in
            !found.contains { family.type(of: $0) == type }
        }
        guard missing.isEmpty else {
            throw MalformedRealtimeBitstreamError(
                codec: codec,
                reason: "a keyframe carried "
                    + found.map { family.name(of: family.type(of: $0)) }.joined(separator: " and ")
                    + " and no " + missing.map(family.name(of:)).joined(separator: " or ")
                    + ", so no description can be made from it")
        }
        // CoreMedia reads them in the order the family names them: VPS, then SPS, then PPS.
        let ordered = family.requiredTypes.flatMap { type in
            found.filter { family.type(of: $0) == type }
        }
        let copies = ordered.map { [UInt8]($0) }
        var description: CMFormatDescription?
        let status = withPointers(to: copies) { pointers in
            let sizes = copies.map(\.count)
            return family == .h264
                ? CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: copies.count,
                    parameterSetPointers: pointers, parameterSetSizes: sizes,
                    nalUnitHeaderLength: Int32(inBandNalLengthSize),
                    formatDescriptionOut: &description)
                : CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: copies.count,
                    parameterSetPointers: pointers, parameterSetSizes: sizes,
                    nalUnitHeaderLength: Int32(inBandNalLengthSize), extensions: nil,
                    formatDescriptionOut: &description)
        }
        guard status == noErr, let description else {
            throw RealtimeDescriptionError(codec: codec, status: status)
        }
        return description
    }

    /// Calls `body` with a pointer to each of `arrays`' storage, all valid at once.
    private static func withPointers<Result>(
        to arrays: [[UInt8]], _ body: ([UnsafePointer<UInt8>]) -> Result
    ) -> Result {
        func step(_ index: Int, _ collected: [UnsafePointer<UInt8>]) -> Result {
            guard index < arrays.count else { return body(collected) }
            // Never nil: every array is a parameter set, and `annexBUnits` yields no empty unit.
            return arrays[index].withUnsafeBufferPointer { buffer in
                step(index + 1, collected + [buffer.baseAddress!])
            }
        }
        return step(0, [])
    }

    /// `AVCProfileIndication`, `profile_compatibility` and `AVCLevelIndication`: read by the codec
    /// string, not here.
    private static let avcProfileAndLevelBytes = 3

    /// Bytes 1 to 20 of an `hvcC`: profile, tier, level, chroma and frame-rate fields, none of which
    /// this side reads.
    private static let hevcHeaderBytesBeforeLengthSize = 20

    /// `unsigned int(16) numNalus`, an `hvcC` array's unit count: 16-bit by §8.3.3.1.2's grammar.
    private static let hevcNalUnitCountBytes = 2

    /// `lengthSizeMinusOne` is the low two bits of its byte; the six above it are reserved.
    private static let lengthSizeMinusOneMask: UInt8 = 0x03

    /// `numOfSequenceParameterSets` is the low five bits of its byte; the three above are reserved.
    private static let spsCountMask: UInt8 = 0x1F

    /// `lengthSizeMinusOne = 2`, the one width of the four the grammar does not allow.
    private static let forbiddenNalLengthSize = 3
}

/// A bounds-checked walk over a record or a sample, refusing at the first byte that is not there —
/// the Kotlin `RecordReader`, for its reason: the whole family of defects this file exists for is
/// "read past the end and carry on".
private struct RecordReader {
    let codec: String
    let source: [UInt8]
    private var offset = 0

    init(codec: String, source: Data) {
        self.codec = codec
        self.source = [UInt8](source)
    }

    var exhausted: Bool { offset >= source.count }

    mutating func skip(_ count: Int) throws {
        try require(count, "a \(count)-byte field")
        offset += count
    }

    mutating func readByte() throws -> UInt8 {
        try require(1, "a one-byte field")
        defer { offset += 1 }
        return source[offset]
    }

    /// The next length-prefixed NAL unit's bytes. `lengthSize` defaults to the two bytes a
    /// *record's* parameter sets carry by its grammar; a sample passes the width its record
    /// declared.
    mutating func readUnit(lengthSize: Int = RecordReader.parameterSetLengthBytes) throws
        -> ArraySlice<UInt8>
    {
        let start = offset
        let length = try readLength(
            lengthSize, "a \(lengthSize)-byte NAL unit length at offset \(start)")
        guard length > 0 else {
            throw MalformedRealtimeBitstreamError(
                codec: codec,
                reason: "a NAL unit length field at offset \(start) declares zero bytes")
        }
        try require(length, "a \(length)-byte NAL unit declared at offset \(start)")
        defer { offset += length }
        return source[offset..<offset + length]
    }

    /// `size` bytes as one unsigned big-endian integer. At most four bytes, so an `Int` — 64 bits on
    /// every platform this runs on — holds it with no sign to go wrong, which is the Kotlin `Long`'s
    /// whole reason.
    mutating func readLength(_ size: Int, _ describedAs: String) throws -> Int {
        try require(size, describedAs)
        var value = 0
        for _ in 0..<size {
            value = (value << 8) | Int(source[offset])
            offset += 1
        }
        return value
    }

    /// `configurationVersion`, which both records fix at 1 — checked rather than skipped, because
    /// Annex-B parameter sets handed over in place of a record begin `00 00 00 01`, and this byte is
    /// where that mistake is named rather than surfacing as a plausible count four fields later.
    mutating func expectConfigurationVersion() throws {
        let version = try readByte()
        guard version == Self.configurationVersion else {
            throw MalformedRealtimeBitstreamError(
                codec: codec,
                reason: "configurationVersion is \(version) rather than "
                    + "\(Self.configurationVersion), so these bytes are not a configuration record "
                    + "— Annex-B parameter sets handed over in place of one begin 00 00 00 01")
        }
    }

    private func require(_ count: Int, _ describedAs: String) throws {
        // As a subtraction, so a huge declared length cannot overflow into reading as satisfied.
        guard count >= 0, count <= source.count - offset else {
            throw MalformedRealtimeBitstreamError(
                codec: codec, reason: "\(describedAs) runs past the end of \(source.count) bytes")
        }
    }

    /// ISO/IEC 14496-15 §5.3.3.1.2 and §8.3.3.1.2: both records fix `configurationVersion` at 1.
    static let configurationVersion: UInt8 = 1

    /// `unsigned int(16) sequenceParameterSetLength`, and its PPS and HEVC-array twins.
    static let parameterSetLengthBytes = 2
}
