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
import Foundation
import SuperPlayerCore

/// What a codec string names, once the one table has read it.
struct RealtimeFormat: Hashable, Sendable {

    enum MediaKind: Hashable, Sendable { case video, audio }

    /// Which renderer takes the track: the display layer or the audio renderer.
    let kind: MediaKind

    /// The table's token: the fourcc of an RFC 6381 string or a lowercased SDP encoding name.
    let token: String

    /// What CoreMedia calls the codec: a `CMVideoCodecType` for video, an `AudioFormatID` for audio.
    let codecType: FourCharCode

    /// The RFC 6381 string as the transport sent it, or nil for an SDP encoding name, which states
    /// no profile and no level and so is not written where an RFC 6381 string goes — the Kotlin
    /// `Format.codecs` rule.
    let codecs: String?

    /// The profile and level the string states, for the families that state them.
    let profileAndLevel: ProfileAndLevel?

    struct ProfileAndLevel: Hashable, Sendable {
        let profile: Int
        let level: Int
    }
}

/// The one table from a codec string to what CoreMedia decodes it as — the Kotlin `RealtimeFormats`,
/// answering a `CMFormatDescription` where the Kotlin answers a Media3 `Format`.
///
/// Two vocabularies reach it, because the two realtime transports name their codecs differently and
/// neither should keep a mapping of its own:
///
/// - **RFC 6381 codecs strings** — `avc1.640028`, `avc3.42c01e`, `hev1.1.6.L93.B0`, `vp09.00.10.08`,
///   `av01.0.04M.08`, `mp4a.40.2`, `opus`. What a MoQ catalog's `codec` field carries.
/// - **SDP encoding names** — `H264`, `H265`, `VP9`, `AV1`, `opus`, `MPEG4-GENERIC`. What a WHEP
///   answer's `a=rtpmap` lines carry.
///
/// spec: RFC 6381 §3.3 — the `codecs` parameter, its fourcc-prefixed grammar and the per-codec
///   registrations it defers to. https://www.rfc-editor.org/rfc/rfc6381#section-3.3
/// spec: RFC 4566 §6 and RFC 4855 §3 — SDP's `rtpmap` encoding names, matched **case-insensitively**,
///   which is why every token is lowercased before it is looked up.
///
/// It is internal, as the Kotlin table is: what it answers is CoreMedia vocabulary a consumer never
/// needs to name, and what the public surface carries is the refusal, core's
/// ``UnsupportedRealtimeCodecError``.
///
/// **The profile and level are read here, not by an engine's reader.** Media3 has a reader the
/// Kotlin table defers to; CoreMedia has none for a codecs string, so each family's grammar is read
/// below against its own citation. What is read is only enough to refuse a string of a mapped family
/// whose parameters do not parse — the Kotlin rule that such a string is a refusal rather than a
/// format saying less than the transport did. Nothing here asks whether this device can decode the
/// result.
enum RealtimeFormats {

    /// `codec` as a ``RealtimeFormat``, or ``UnsupportedRealtimeCodecError`` for a string nothing
    /// here maps or one of a mapped family whose parameters are malformed. Pure: it reads nothing but
    /// the string.
    static func format(for codec: String) throws(UnsupportedRealtimeCodecError) -> RealtimeFormat {
        // RFC 6381 §3.3: the codecs string is dot-separated and its first element is the fourcc (a
        // sample entry name). An SDP encoding name carries no dot, so the same take answers both —
        // `MPEG4-GENERIC` included, whose hyphen is part of the name rather than a separator.
        let parts = codec.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        let token = parts[0].lowercased()
        let refusal = UnsupportedRealtimeCodecError(codec: codec)
        guard let entry = table[token] ?? aacEntry(token: token, parts: parts) else {
            throw refusal
        }
        let isSdpName = sdpEncodingNames.contains(token)
        var profileAndLevel: RealtimeFormat.ProfileAndLevel?
        if let read = entry.reader {
            // The extraction read as a refusal, for the Kotlin reason: a string of a mapped family
            // whose parameters do not parse would otherwise reach whatever reads the level as an
            // unknown, which refuses nothing — right for a constraint, wrong for mislabelled bytes.
            guard let pair = read(parts) else { throw refusal }
            profileAndLevel = pair
        }
        return RealtimeFormat(
            kind: entry.kind, token: token, codecType: entry.codecType,
            codecs: isSdpName ? nil : codec, profileAndLevel: profileAndLevel)
    }

    /// Every token this table answers for, `mp4a` included — the Kotlin `mappedTokens`, which #433's
    /// conformance suite is held to.
    static var mappedTokens: Set<String> { Set(table.keys).union(["mp4a"]) }

    /// The format description `track` is enqueued under: the codec ``format(for:)`` reads, with the
    /// track's record and coded size.
    ///
    /// **A record passes through**, which is the mirrored half of the Kotlin `CodecConfigurationRecords`
    /// (`docs/ios-plan.md` §3.10): CoreMedia's native shape *is* the record, so an `avcC`, `hvcC`,
    /// `vpcC` or `av1C` is handed to the description as its sample description extension atom,
    /// exactly as received, and the samples stay length-prefixed. **In-band parameter sets are not
    /// described here**: an `avc3` or `hev1` track has no description until its first keyframe, and
    /// `TrackFraming` (`CodecConfigurationRecords.swift`) makes it from the parameter sets that
    /// keyframe carries, re-frames the samples, and parses an `avcC` or `hvcC` before it reaches this
    /// call, with the refusals that go with each.
    static func formatDescription(for track: RealtimeTrack) throws -> CMFormatDescription {
        let format = try format(for: track.codec)
        switch format.kind {
        case .video:
            return try videoDescription(format, track)
        case .audio:
            return try audioDescription(format, track)
        }
    }

    // MARK: - The table

    private struct Entry {
        let kind: RealtimeFormat.MediaKind
        let codecType: FourCharCode
        /// The family's profile-and-level reader, or nil for a family that states neither.
        let reader: (@Sendable ([String]) -> RealtimeFormat.ProfileAndLevel?)?
    }

    /// Every token this library maps, lowercase, in both vocabularies. `mp4a` is absent on purpose —
    /// see ``aacEntry(token:parts:)``. The fourccs are the ones the transports publish; `avc2` and
    /// `avc4` are registered and deliberately not here, for the Kotlin reason: admitting one nothing
    /// sends adds a branch of ADR-0018 rule 4 that no transport and no test exercises.
    private static let table: [String: Entry] = [
        // H.264. spec: RFC 6381 §3.3 and ISO/IEC 14496-15 Annex A — `avcN.PPCCLL`, six hex digits of
        // profile_idc, constraint flags and level_idc. `avc1` and `avc3` are one entry because they
        // are one codec to a decoder: the sample entry name decides only where parameter sets live.
        "avc1": Entry(kind: .video, codecType: kCMVideoCodecType_H264, reader: readAvc),
        "avc3": Entry(kind: .video, codecType: kCMVideoCodecType_H264, reader: readAvc),
        // H.265. spec: ISO/IEC 14496-15 Annex E.3 — `hvc1.A.B.C.LXX.…`, the dot-separated general
        // profile space and profile, compatibility flags, tier and level. `hvc1` and `hev1` pair as
        // `avc1` and `avc3` do; CoreMedia describes both as `kCMVideoCodecType_HEVC`.
        "hvc1": Entry(kind: .video, codecType: kCMVideoCodecType_HEVC, reader: readHevc),
        "hev1": Entry(kind: .video, codecType: kCMVideoCodecType_HEVC, reader: readHevc),
        // VP9. spec: "VP Codec ISO Media File Format Binding", Codecs Parameter String —
        // `vp09.PP.LL.DD…`, profile, level and bit depth.
        // https://www.webmproject.org/vp9/mp4/#codecs-parameter-string
        "vp09": Entry(kind: .video, codecType: kCMVideoCodecType_VP9, reader: readVp9),
        // AV1. spec: "AV1 Codec ISO Media File Format Binding" v1.2.0 §5, Codecs Parameter String —
        // `av01.P.LLT.DD…`, profile, level, tier and bit depth.
        // https://aomediacodec.github.io/av1-isobmff/#codecsparam
        "av01": Entry(kind: .video, codecType: kCMVideoCodecType_AV1, reader: readAv1),
        // Opus, in both vocabularies at once, with no profile and no level — Opus has none.
        // spec: "Encapsulation of Opus in ISO Base Media File Format" §4.3, and RFC 7587 §7.
        "opus": Entry(kind: .audio, codecType: kAudioFormatOpus, reader: nil),
        // SDP encoding names: each is its RTP payload format's media subtype, and none carries a
        // profile or a level in the name — those live in `a=fmtp`, which this seam does not carry.
        // spec: RFC 6184 §8.1
        "h264": Entry(kind: .video, codecType: kCMVideoCodecType_H264, reader: nil),
        // spec: RFC 7798 §7.1
        "h265": Entry(kind: .video, codecType: kCMVideoCodecType_HEVC, reader: nil),
        // spec: RFC 9628 §5
        "vp9": Entry(kind: .video, codecType: kCMVideoCodecType_VP9, reader: nil),
        // spec: "RTP Payload Format For AV1" v1.0 §7
        "av1": Entry(kind: .video, codecType: kCMVideoCodecType_AV1, reader: nil),
        // spec: RFC 3640 §4.1 — `audio/mpeg4-generic`, which for realtime audio is AAC
        "mpeg4-generic": Entry(kind: .audio, codecType: kAudioFormatMPEG4AAC, reader: nil),
    ]

    /// Which of the table's tokens are SDP encoding names, whose string is not RFC 6381's.
    private static let sdpEncodingNames: Set<String> = [
        "h264", "h265", "vp9", "av1", "mpeg4-generic",
    ]

    /// The AAC entry for an `mp4a` string whose object type indication is one of AAC's, nil for any
    /// other `mp4a` string and for any other token.
    ///
    /// `mp4a` is the one fourcc that cannot be mapped by its first element alone: RFC 6381 §3.3 puts
    /// an MP4 Registration Authority object type indication in the second element, and `mp4a.69`
    /// and `mp4a.6B` are **MP3**. Mapping the whole of `mp4a` to AAC would be this file's own
    /// headline defect — a codec silently decoded as a different one.
    ///
    /// spec: RFC 6381 §3.3, and https://mp4ra.org/registered-types/object-types — `0x40` MPEG-4
    ///   Audio (ISO/IEC 14496-3) and `0x67` MPEG-2 AAC LC (ISO/IEC 13818-7), AAC's two.
    private static func aacEntry(token: String, parts: [String]) -> Entry? {
        guard token == "mp4a", parts.count > 1, let objectType = Int(parts[1], radix: 16),
            objectType == objectTypeMpeg4Audio || objectType == objectTypeMpeg2AacLc
        else { return nil }
        return Entry(kind: .audio, codecType: kAudioFormatMPEG4AAC, reader: readAac)
    }

    /// MP4RA object type indication `0x40`, MPEG-4 Audio — what every realtime publisher sending
    /// AAC writes.
    private static let objectTypeMpeg4Audio = 0x40

    /// MP4RA object type indication `0x67`, MPEG-2 AAC LC: the same decoder, and refusing it would
    /// refuse AAC for the spelling rather than for the codec.
    private static let objectTypeMpeg2AacLc = 0x67

    // MARK: - Profile and level, per family

    /// `avcN.PPCCLL` (six hex digits), or the legacy `avcN.P.L` in decimal that RFC 6381 §3.3's
    /// earlier registrations used and Media3's reader also accepts.
    private static let readAvc: @Sendable ([String]) -> RealtimeFormat.ProfileAndLevel? = { parts in
        if parts.count == 2, parts[1].count == 6, parts[1].allSatisfy(\.isHexDigit),
            let value = Int(parts[1], radix: 16)
        {
            return .init(profile: value >> 16, level: value & 0xFF)
        }
        if parts.count >= 3, let profile = Int(parts[1]), let level = Int(parts[2]) {
            return .init(profile: profile, level: level)
        }
        return nil
    }

    /// `hvc1.A.B.C.LXX…`: at least four elements, the second an optional general profile space
    /// letter (`A`–`C`) and a decimal profile, the fourth a tier (`L` or `H`) and a decimal level.
    private static let readHevc: @Sendable ([String]) -> RealtimeFormat.ProfileAndLevel? = {
        parts in
        guard parts.count >= 4, Int(parts[2], radix: 16) != nil else { return nil }
        var profile = Substring(parts[1])
        if let first = profile.first, "ABC".contains(first) { profile = profile.dropFirst() }
        let tierAndLevel = parts[3]
        guard let tier = tierAndLevel.first, "LH".contains(tier),
            let profileIdc = Int(profile), let level = Int(tierAndLevel.dropFirst())
        else { return nil }
        return .init(profile: profileIdc, level: level)
    }

    /// `vp09.PP.LL.DD…`: at least three elements, profile and level two-digit decimals.
    private static let readVp9: @Sendable ([String]) -> RealtimeFormat.ProfileAndLevel? = { parts in
        guard parts.count >= 3, parts[1].count == 2, parts[2].count == 2,
            let profile = Int(parts[1]), let level = Int(parts[2])
        else { return nil }
        return .init(profile: profile, level: level)
    }

    /// `av01.P.LLT.DD…`: at least four elements — a one-digit profile, a two-digit level and a tier
    /// letter (`M` or `H`), a two-digit bit depth.
    private static let readAv1: @Sendable ([String]) -> RealtimeFormat.ProfileAndLevel? = { parts in
        guard parts.count >= 4, parts[1].count == 1, let profile = Int(parts[1]),
            parts[2].count == 3, let tier = parts[2].last, "MH".contains(tier),
            let level = Int(parts[2].prefix(2)), parts[3].count == 2, Int(parts[3]) != nil
        else { return nil }
        return .init(profile: profile, level: level)
    }

    /// `mp4a.OO.A`: exactly three elements, the third the decimal MPEG-4 audio object type (2 for
    /// AAC-LC, 5 for HE-AAC), which is the profile; AAC states no level in its codecs string.
    ///
    /// spec: RFC 6381 §3.3 — "the audio object type … as a decimal number".
    private static let readAac: @Sendable ([String]) -> RealtimeFormat.ProfileAndLevel? = { parts in
        guard parts.count == 3, let objectType = Int(parts[2]) else { return nil }
        return .init(profile: objectType, level: 0)
    }

    // MARK: - Descriptions

    /// The atom a record of each video family travels as, keyed on CoreMedia's codec type.
    ///
    /// spec: ISO/IEC 14496-15 §5.3.3.1 (`avcC`) and §8.3.3.1 (`hvcC`); "VP Codec ISO Media File
    ///   Format Binding" §2.2 (`vpcC`); "AV1 Codec ISO Media File Format Binding" §2.3 (`av1C`).
    private static let recordAtoms: [FourCharCode: String] = [
        kCMVideoCodecType_H264: "avcC", kCMVideoCodecType_HEVC: "hvcC",
        kCMVideoCodecType_VP9: "vpcC", kCMVideoCodecType_AV1: "av1C",
    ]

    private static func videoDescription(_ format: RealtimeFormat, _ track: RealtimeTrack) throws
        -> CMFormatDescription
    {
        var extensions: [CFString: Any] = [:]
        if case .record(let bytes) = track.codecConfiguration,
            let atom = recordAtoms[format.codecType]
        {
            extensions[kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms] = [
                atom: bytes
            ]
        }
        // A track that states no size is described as zero by zero rather than at a guessed size:
        // the decoder reads the true size off the parameter sets, and obligation 10 says what an
        // unstated one costs.
        var description: CMFormatDescription?
        let status = CMVideoFormatDescriptionCreate(
            allocator: kCFAllocatorDefault, codecType: format.codecType,
            width: Int32(track.codedSize?.width ?? 0), height: Int32(track.codedSize?.height ?? 0),
            extensions: extensions.isEmpty ? nil : extensions as CFDictionary,
            formatDescriptionOut: &description)
        guard status == noErr, let description else {
            throw RealtimeDescriptionError(codec: track.codec, status: status)
        }
        return description
    }

    /// Opus is always decoded at 48 kHz, whatever its input rate, and an SDP encoding name declares
    /// two channels for every Opus stream; a record states its own channel count. The record read
    /// is RFC 7845's OpusHead identification header and only that: ISOBMFF's `dOps` box carries the
    /// same fields at other offsets, and no transport here has been observed sending either, so the
    /// one shape is named rather than both guessed at.
    ///
    /// spec: RFC 7587 §4.1 (the RTP clock rate is always 48000) and §7 (`opus/48000/2`).
    private static let opusSampleRate: Float64 = 48_000

    /// spec: RFC 7845 §5.1 — an OpusHead is nineteen bytes before any channel mapping table.
    private static let opusHeadMinimumSize = 19

    /// An AAC frame is 1024 samples (ISO/IEC 14496-3 §4.5.2.1, `frameLengthFlag` 0 — what every AAC
    /// realtime publisher sends).
    private static let aacFramesPerPacket: UInt32 = 1024

    /// The sampling frequencies a four-bit `samplingFrequencyIndex` names, in order.
    ///
    /// spec: ISO/IEC 14496-3 §1.6.3.3, Table 1.18.
    private static let aacSamplingFrequencies: [Float64] = [
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025,
        8_000, 7_350,
    ]

    private static func audioDescription(_ format: RealtimeFormat, _ track: RealtimeTrack) throws
        -> CMFormatDescription
    {
        var basic = AudioStreamBasicDescription()
        basic.mFormatID = format.codecType
        var cookie: Data?
        switch format.codecType {
        case kAudioFormatOpus:
            basic.mSampleRate = opusSampleRate
            basic.mChannelsPerFrame = 2
            if case .record(let bytes) = track.codecConfiguration {
                // spec: RFC 7845 §5.1 — the OpusHead identification header's channel count is its
                // tenth byte, after the eight-byte magic and the version.
                guard bytes.count >= opusHeadMinimumSize else {
                    throw MalformedRealtimeBitstreamError(
                        codec: track.codec,
                        reason: "an Opus identification header is at least "
                            + "\(opusHeadMinimumSize) bytes; got \(bytes.count)")
                }
                basic.mChannelsPerFrame = UInt32(bytes[bytes.startIndex + 9])
                cookie = bytes
            }
        default:
            // AAC states its rate and channels only in its AudioSpecificConfig, which CoreMedia
            // needs before the first packet; describing a track with none at a guessed rate would
            // play it at the wrong speed, which is a refusal's case rather than a default's.
            guard case .record(let bytes) = track.codecConfiguration else {
                throw MalformedRealtimeBitstreamError(
                    codec: track.codec,
                    reason: "an AAC track states its sampling rate and channels only in its "
                        + "AudioSpecificConfig, and this one handed over none "
                        + "(CodecConfiguration.inBand)")
            }
            let config = try audioSpecificConfig(bytes, codec: track.codec)
            basic.mSampleRate = config.sampleRate
            basic.mChannelsPerFrame = config.channels
            basic.mFramesPerPacket = aacFramesPerPacket
            // An object type of 5 (SBR) or 29 (PS) is HE-AAC or HE-AAC v2, which CoreAudio names
            // apart. spec: ISO/IEC 14496-3 §1.5.1.1, Table 1.1 (audio object types).
            if config.objectType == 5 { basic.mFormatID = kAudioFormatMPEG4AAC_HE }
            if config.objectType == 29 { basic.mFormatID = kAudioFormatMPEG4AAC_HE_V2 }
            cookie = elementaryStreamDescriptor(wrapping: bytes)
        }
        var description: CMAudioFormatDescription?
        let status = cookie.withUnsafeOptionalBytes { cookieBytes in
            CMAudioFormatDescriptionCreate(
                allocator: kCFAllocatorDefault, asbd: &basic, layoutSize: 0, layout: nil,
                magicCookieSize: cookieBytes?.count ?? 0, magicCookie: cookieBytes?.baseAddress,
                extensions: nil, formatDescriptionOut: &description)
        }
        guard status == noErr, let description else {
            throw RealtimeDescriptionError(codec: track.codec, status: status)
        }
        return description
    }

    /// An AudioSpecificConfig as the magic cookie CoreAudio's AAC decoder reads: wrapped in the
    /// MPEG-4 `ES_Descriptor` an `esds` box carries, not handed over bare.
    ///
    /// **Found on the simulator by #432**, the first test to enqueue audio: with the bare
    /// AudioSpecificConfig as its cookie, `AVSampleBufferAudioRenderer` logs "AudioCodecInitialize
    /// failed" and still reports `rendering` with no error — the renderer's version of #398's
    /// silent decoder — while the same bytes inside this descriptor decode
    /// (`RealtimeAudioDecodingTests` holds both). The record a transport hands over stays the bare
    /// AudioSpecificConfig, which is what ISO BMFF and RFC 3640's `config` carry; this wrapping is
    /// CoreAudio's vocabulary and is done here.
    ///
    /// spec: ISO/IEC 14496-1 §7.2.6.5 (`ES_Descriptor`, tag 3: an `ES_ID`, a flags byte, then a
    ///   `DecoderConfigDescriptor`, tag 4: `objectTypeIndication` 0x40 for ISO/IEC 14496-3 audio,
    ///   the stream type 5 with its reserved bit, a 24-bit buffer size and two 32-bit bitrates left
    ///   at zero as unknown, and a `DecoderSpecificInfo`, tag 5, holding the AudioSpecificConfig),
    ///   then §7.2.6.8's `SLConfigDescriptor`, tag 6, predefined as 2 for MP4; §8.3.3 the size
    ///   encoding, seven bits a byte with the high bit set on every byte but the last.
    static func elementaryStreamDescriptor(wrapping audioSpecificConfig: Data) -> Data {
        func descriptor(_ tag: UInt8, _ body: Data) -> Data {
            var size: [UInt8] = [UInt8(body.count & 0x7F)]
            var rest = body.count >> 7
            while rest > 0 {
                size.insert(UInt8(rest & 0x7F) | 0x80, at: 0)
                rest >>= 7
            }
            return Data([tag] + size) + body
        }
        let decoderConfig =
            Data([objectTypeMpeg4AudioIndication, audioStreamTypeAndReservedBit])
            + Data(count: unknownBufferSizeAndBitrateBytes)
            + descriptor(decoderSpecificInfoTag, audioSpecificConfig)
        let elementaryStream =
            Data(count: esIdAndFlagsBytes) + descriptor(decoderConfigTag, decoderConfig)
            + descriptor(slConfigTag, Data([slConfigPredefinedMp4]))
        return descriptor(esDescriptorTag, elementaryStream)
    }

    // The descriptor tags of ISO/IEC 14496-1 §7.2.2.1, Table 1.
    private static let esDescriptorTag: UInt8 = 0x03
    private static let decoderConfigTag: UInt8 = 0x04
    private static let decoderSpecificInfoTag: UInt8 = 0x05
    private static let slConfigTag: UInt8 = 0x06

    /// `objectTypeIndication` 0x40, ISO/IEC 14496-3 audio — the syntax an AudioSpecificConfig is
    /// written in, whichever of AAC's two object types the codec string named.
    private static let objectTypeMpeg4AudioIndication = UInt8(objectTypeMpeg4Audio)

    /// `streamType` 5 (audio) in six bits, `upStream` 0, and the reserved bit, which is 1.
    private static let audioStreamTypeAndReservedBit: UInt8 = (0x05 << 2) | 0x01

    /// `bufferSizeDB` (24 bits), `maxBitrate` and `avgBitrate` (32 each): zero, as unknown, because a
    /// realtime track states none of them and the decoder reads none of them.
    private static let unknownBufferSizeAndBitrateBytes = 3 + 4 + 4

    /// `ES_ID` (16 bits, zero for a stream outside a scene) and the flags byte, every flag clear.
    private static let esIdAndFlagsBytes = 3

    /// `SLConfigDescriptor.predefined` 2, "reserved for use in MP4 files" (§7.3.2.3.2).
    private static let slConfigPredefinedMp4: UInt8 = 0x02

    /// The object type, sampling rate and channel count an AudioSpecificConfig opens with.
    ///
    /// spec: ISO/IEC 14496-3 §1.6.2.1 — `audioObjectType` in five bits (31 escaping to six more),
    /// `samplingFrequencyIndex` in four (15 escaping to a 24-bit frequency), then
    /// `channelConfiguration` in four.
    static func audioSpecificConfig(_ bytes: Data, codec: String) throws
        -> (objectType: Int, sampleRate: Float64, channels: UInt32)
    {
        var reader = BitReader(bytes)
        let malformed = MalformedRealtimeBitstreamError(
            codec: codec,
            reason: "an AudioSpecificConfig of \(bytes.count) bytes ends before its channel "
                + "configuration, or names a reserved sampling frequency index")
        guard var objectType = reader.read(5) else { throw malformed }
        if objectType == 31 {
            guard let extended = reader.read(6) else { throw malformed }
            objectType = 32 + extended
        }
        guard let index = reader.read(4) else { throw malformed }
        let rate: Float64
        if index == 15 {
            guard let explicit = reader.read(24) else { throw malformed }
            rate = Float64(explicit)
        } else {
            guard index < aacSamplingFrequencies.count else { throw malformed }
            rate = aacSamplingFrequencies[index]
        }
        guard let channels = reader.read(4) else { throw malformed }
        return (objectType, rate, UInt32(channels))
    }
}

/// CoreMedia refused to make a format description out of a track SuperPlayer had already read — the
/// one failure here that is CoreMedia's word rather than the transport's, so it carries the status.
struct RealtimeDescriptionError: Error, CustomStringConvertible {
    let codec: String
    let status: OSStatus
    var description: String {
        "CoreMedia made no format description for realtime codec \"\(codec)\" (OSStatus \(status))"
    }
}

/// Reads big-endian bit fields, most significant bit first, as ISO/IEC 14496-3 writes them.
private struct BitReader {
    private let bytes: [UInt8]
    private var position = 0

    init(_ data: Data) { bytes = Array(data) }

    mutating func read(_ count: Int) -> Int? {
        guard position + count <= bytes.count * 8 else { return nil }
        var value = 0
        for _ in 0..<count {
            let bit = (bytes[position / 8] >> (7 - UInt8(position % 8))) & 1
            value = (value << 1) | Int(bit)
            position += 1
        }
        return value
    }
}

extension Optional where Wrapped == Data {
    /// The bytes, or nil, for a C call taking an optional pointer and a size.
    fileprivate func withUnsafeOptionalBytes<Result>(
        _ body: (UnsafeRawBufferPointer?) -> Result
    ) -> Result {
        switch self {
        case .some(let data): data.withUnsafeBytes { body($0) }
        case .none: body(nil)
        }
    }
}
