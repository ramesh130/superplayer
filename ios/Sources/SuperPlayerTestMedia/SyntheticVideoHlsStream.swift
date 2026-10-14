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

/// A tiny HLS stream of **real, decodable H.264 video**, generated in memory: a multivariant
/// playlist, a media playlist, a fragmented-MP4 initialization segment and as many two-second media
/// segments as the caller asks for, as file name to bytes.
///
/// It exists for the frame output (#405, ADR-0022): an effect is handed *decoded* frames, so a test
/// of one needs a stream `AVPlayer` decodes into pictures, and ``SyntheticHlsStream`` is audio.
/// Android needs no such stream — its frame-output test is about the effect list reaching Media3's
/// video renderer, which a *described* format reaches without a byte of video (`TestContent.Rung`)
/// — so **this stream has no Kotlin twin** and no committed fixture under ADR-0021 rule 6: there is
/// one generator and nothing to hold a second one to. `SyntheticVideoDecodingTests` is what holds
/// it to being decodable.
///
/// Generated rather than checked in, for ``SyntheticHlsStream``'s reasons: every number a test
/// asserts on — the frame rate, the frame count, the size — is a named constant here, and the
/// repository carries no media it would have to license. No encoder is involved: the bitstream is
/// written by hand, which is possible because the picture is trivial. Each segment opens with one
/// IDR picture whose macroblocks are all `I_PCM` — raw samples, the one macroblock type that needs
/// no transform, prediction or entropy coding to write — followed by P pictures whose every
/// macroblock is skipped, each a copy of the one before. So every frame decodes, every frame is a
/// distinct presentation time, and the whole of a segment is a few kilobytes. Each segment's IDR
/// is one grey level lighter than the last, so a person watching sees the segments change.
///
/// **A silent AAC track is muxed beside the video**, ``SyntheticHlsStream``'s own frames, because a
/// video-only item with nothing to draw it on never advances: an `AVPlayer` in an `xctest` host,
/// where no layer is ever in a window, sits at zero playing a video-only stream unless an
/// `AVPlayerItemVideoOutput` is on the item — observed on the iOS 27.1 simulator, where the same
/// item with an output plays. So a player *without* a frame output, the control every frame-output
/// test needs, could not play the stream at all; with audio, the audio renderer's clock carries it.
/// Real content carries audio too.
///
/// spec: ITU-T H.264 (08/2021) for the bitstream; ISO/IEC 14496-15 §5.3 for its carriage in ISO
/// BMFF (`avc1`, `avcC`, length-prefixed NAL units); ISO/IEC 14496-12 for the boxes, which follow
/// the Kotlin `SyntheticDashStream`'s; RFC 8216 §3.3 for fragmented MP4 as an HLS segment format.
///
/// Like everything in this module it names no type outside the standard library.
public enum SyntheticVideoHlsStream {

    /// The multivariant playlist's file name: what a player is pointed at.
    public static let multivariantPlaylistName = "master.m3u8"

    /// The one variant's media playlist.
    public static let mediaPlaylistName = "video.m3u8"

    /// The initialization segment, `EXT-X-MAP`'s target. Named with `init` so the harness's kind
    /// rule — the Kotlin `kindOf`'s — reads it as an initialization segment.
    public static let initializationSegmentName = "init.mp4"

    /// What every media segment's name ends in.
    public static let segmentSuffix = ".m4s"

    /// The picture's width and height in pixels: four macroblocks each way, the least that is
    /// still unmistakably a picture, and a whole number of macroblocks so no cropping is declared.
    ///
    /// spec: ITU-T H.264 §7.4.2.1.1 — a picture is a whole number of 16×16 macroblocks.
    public static let width = 64
    public static let height = 64

    /// Thirty frames a second: a common content rate, and below the simulator display's refresh,
    /// so a display link sees each frame at least once.
    public static let framesPerSecond = 30

    /// Two seconds, as ``SyntheticHlsStream/segmentDurationMs`` is, for its reason: the target
    /// duration the Kotlin streams were all written around.
    public static let segmentDurationMs: Int64 = 2_000

    /// How many frames one segment carries: sixty, one IDR and fifty-nine P pictures.
    public static let framesPerSegment = framesPerSecond * Int(segmentDurationMs) / 1_000

    /// `avc1.42c01e,mp4a.40.2` — RFC 6381 §3.3's codecs parameter for this stream. The video:
    /// `profile_idc` 66 (Baseline), constraint flags 0 and 1 set (Constrained Baseline), `level_idc`
    /// 30 (Level 3), generous for a 64×64 picture and chosen so no decoder limit is anywhere near — an
    /// all-`I_PCM` IDR is 6 KB, which Level 1's coded picture buffer would hold with little to spare.
    /// The audio: AAC-LC, ``SyntheticHlsStream/declaredCodecs``.
    ///
    /// spec: ITU-T H.264 Annex A.2.1 (Constrained Baseline) and Table A-1 (levels).
    public static let declaredCodecs = "avc1.42c01e,\(SyntheticHlsStream.declaredCodecs)"

    /// What a stream of `segmentCount` segments lasts.
    public static func durationMs(segmentCount: Int) -> Int64 {
        segmentDurationMs * Int64(segmentCount)
    }

    /// The name of segment `index`, relative to wherever the stream is served.
    public static func segmentName(_ index: Int) -> String { "segment\(index)\(segmentSuffix)" }

    /// The whole stream as file name to bytes — everything a player will ask for and nothing else.
    public static func files(segmentCount: Int = 4) -> [String: [UInt8]] {
        precondition(segmentCount >= 1, "A stream needs at least one segment, was \(segmentCount)")
        var files: [String: [UInt8]] = [:]
        files[initializationSegmentName] = initializationSegment()
        for index in 0..<segmentCount {
            files[segmentName(index)] = mediaSegment(index)
        }
        let peakBytes = (0..<segmentCount).map { files[segmentName($0)]!.count }.max()!
        files[multivariantPlaylistName] = Array(multivariantPlaylist(peakBytes: peakBytes).utf8)
        files[mediaPlaylistName] = Array(mediaPlaylist(segmentCount).utf8)
        return files
    }

    // MARK: - Playlists

    /// spec: RFC 8216 §4.3.4.2 — `BANDWIDTH` is the peak segment bitrate, computed here from the
    /// segments themselves rather than declared, so it cannot drift from them; `RESOLUTION` and
    /// `FRAME-RATE` describe the one variant.
    private static func multivariantPlaylist(peakBytes: Int) -> String {
        let bandwidth = peakBytes * 8 * 1_000 / Int(segmentDurationMs)
        return [
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-STREAM-INF:BANDWIDTH=\(bandwidth),CODECS=\"\(declaredCodecs)\","
                + "RESOLUTION=\(width)x\(height),FRAME-RATE=\(framesPerSecond).000",
            mediaPlaylistName,
        ].joined(separator: "\n")
    }

    /// spec: RFC 8216 §4.3.2.5 — `EXT-X-MAP` names the initialization segment every media segment
    /// needs; §7 — version 6 or later for `EXT-X-MAP` in a playlist without `EXT-X-I-FRAMES-ONLY`.
    private static func mediaPlaylist(_ segmentCount: Int) -> String {
        let seconds = Double(segmentDurationMs) / 1_000
        let header = [
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-TARGETDURATION:\(Int(seconds.rounded(.up)))",
            "#EXT-X-MEDIA-SEQUENCE:0",
            "#EXT-X-PLAYLIST-TYPE:VOD",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-MAP:URI=\"\(initializationSegmentName)\"",
        ]
        let segments = (0..<segmentCount).flatMap { ["#EXTINF:\(seconds),", segmentName($0)] }
        return (header + segments + ["#EXT-X-ENDLIST"]).joined(separator: "\n")
    }

    // MARK: - The H.264 bitstream

    /// The video track's timescale: any value is allowed (spec: ISO/IEC 14496-12 §8.4.2.3,
    /// `timescale` is "the number of time units that pass in one second"), and 90 kHz is chosen by
    /// convention — it is MPEG-2's presentation clock (ISO/IEC 13818-1 §2.4.3.7), which packagers
    /// carry into fragmented MP4 — and because a frame at 30 fps is then 3000 ticks exactly.
    private static let timescale = 90_000
    private static let frameDuration = timescale / framesPerSecond

    private static let macroblocksWide = width / 16
    private static let macroblocksHigh = height / 16

    /// spec: ITU-T H.264 §7.4.2.1.1 — `log2_max_frame_num_minus4` is 0, so `frame_num` is four
    /// bits and wraps at 16; every P picture is a reference picture, so it counts up by one.
    private static let frameNumBits = 4

    /// The sequence parameter set, as an RBSP before emulation prevention.
    ///
    /// spec: ITU-T H.264 §7.3.2.1.1. `pic_order_cnt_type` 2 derives the output order from
    /// `frame_num` (§8.2.1.3), which is decode order: there are no B pictures, so no picture waits
    /// for a later one and the slice headers carry no picture order count.
    static func sequenceParameterSet() -> [UInt8] {
        var bits = BitWriter()
        bits.write(66, count: 8)  // profile_idc: Baseline
        bits.write(0b1100_0000, count: 8)  // constraint_set0/1_flag: Constrained Baseline
        bits.write(30, count: 8)  // level_idc: Level 3
        bits.unsignedExpGolomb(0)  // seq_parameter_set_id
        bits.unsignedExpGolomb(UInt32(frameNumBits - 4))  // log2_max_frame_num_minus4
        bits.unsignedExpGolomb(2)  // pic_order_cnt_type
        bits.unsignedExpGolomb(1)  // max_num_ref_frames: each P refers to the one before
        bits.write(0, count: 1)  // gaps_in_frame_num_value_allowed_flag
        bits.unsignedExpGolomb(UInt32(macroblocksWide - 1))  // pic_width_in_mbs_minus1
        bits.unsignedExpGolomb(UInt32(macroblocksHigh - 1))  // pic_height_in_map_units_minus1
        bits.write(1, count: 1)  // frame_mbs_only_flag
        bits.write(1, count: 1)  // direct_8x8_inference_flag
        bits.write(0, count: 1)  // frame_cropping_flag
        bits.write(0, count: 1)  // vui_parameters_present_flag
        bits.trailingBits()
        return bits.bytes
    }

    /// The picture parameter set: CAVLC, one slice group, no weighted prediction, no deblocking
    /// control (there is nothing between two identical flat pictures for a filter to smooth).
    ///
    /// spec: ITU-T H.264 §7.3.2.2.
    static func pictureParameterSet() -> [UInt8] {
        var bits = BitWriter()
        bits.unsignedExpGolomb(0)  // pic_parameter_set_id
        bits.unsignedExpGolomb(0)  // seq_parameter_set_id
        bits.write(0, count: 1)  // entropy_coding_mode_flag: CAVLC
        bits.write(0, count: 1)  // bottom_field_pic_order_in_frame_present_flag
        bits.unsignedExpGolomb(0)  // num_slice_groups_minus1
        bits.unsignedExpGolomb(0)  // num_ref_idx_l0_default_active_minus1
        bits.unsignedExpGolomb(0)  // num_ref_idx_l1_default_active_minus1
        bits.write(0, count: 1)  // weighted_pred_flag
        bits.write(0, count: 2)  // weighted_bipred_idc
        bits.signedExpGolomb(0)  // pic_init_qp_minus26
        bits.signedExpGolomb(0)  // pic_init_qs_minus26
        bits.signedExpGolomb(0)  // chroma_qp_index_offset
        bits.write(0, count: 1)  // deblocking_filter_control_present_flag
        bits.write(0, count: 1)  // constrained_intra_pred_flag
        bits.write(0, count: 1)  // redundant_pic_cnt_present_flag
        bits.trailingBits()
        return bits.bytes
    }

    /// The grey level segment `index`'s pictures carry: a step lighter per segment, starting at
    /// mid-grey, so the segments are told apart on a screen. Never 0, which early editions of H.264
    /// forbade as a PCM sample (§7.4.5's `pcm_sample_luma` constraint, lifted later).
    private static func greyLevel(ofSegment index: Int) -> UInt8 {
        UInt8(0x60 + (index * 0x18) % 0x90)
    }

    /// The IDR picture opening segment `index`: one slice, every macroblock `I_PCM`.
    ///
    /// spec: ITU-T H.264 §7.3.3 (slice header), §7.3.4 (slice data) and §7.3.5 (macroblock layer):
    /// `mb_type` 25 in an I slice is `I_PCM` (Table 7-11), followed by zero bits to a byte boundary
    /// and then 256 luma and 2×64 chroma samples, raw. Chroma is 0x80, which is no colour.
    static func idrSlice(segment index: Int) -> [UInt8] {
        var bits = BitWriter()
        bits.unsignedExpGolomb(0)  // first_mb_in_slice
        bits.unsignedExpGolomb(7)  // slice_type: I, and every slice of the picture is
        bits.unsignedExpGolomb(0)  // pic_parameter_set_id
        bits.write(0, count: frameNumBits)  // frame_num: 0 at an IDR
        bits.unsignedExpGolomb(UInt32(index % 2))  // idr_pic_id: differs between adjacent IDRs
        bits.write(0, count: 1)  // dec_ref_pic_marking: no_output_of_prior_pics_flag
        bits.write(0, count: 1)  // dec_ref_pic_marking: long_term_reference_flag
        bits.signedExpGolomb(0)  // slice_qp_delta
        let luma = greyLevel(ofSegment: index)
        for _ in 0..<(macroblocksWide * macroblocksHigh) {
            bits.unsignedExpGolomb(25)  // mb_type: I_PCM
            bits.alignWithZeros()  // pcm_alignment_zero_bit
            for _ in 0..<256 { bits.write(UInt32(luma), count: 8) }
            for _ in 0..<128 { bits.write(0x80, count: 8) }
        }
        bits.trailingBits()
        return networkAbstractionLayerUnit(type: 5, referenceIdc: 3, payload: bits.bytes)
    }

    /// A P picture whose every macroblock is skipped: a copy of the picture before it.
    ///
    /// spec: ITU-T H.264 §7.3.4 — in a CAVLC P slice, `mb_skip_run` counts skipped macroblocks,
    /// and once it has covered the picture `more_rbsp_data()` is false and the slice ends.
    static func skippedSlice(frameNum: Int) -> [UInt8] {
        var bits = BitWriter()
        bits.unsignedExpGolomb(0)  // first_mb_in_slice
        bits.unsignedExpGolomb(5)  // slice_type: P, and every slice of the picture is
        bits.unsignedExpGolomb(0)  // pic_parameter_set_id
        bits.write(UInt32(frameNum % (1 << frameNumBits)), count: frameNumBits)  // frame_num
        bits.write(0, count: 1)  // num_ref_idx_active_override_flag
        bits.write(0, count: 1)  // ref_pic_list_modification_flag_l0
        bits.write(0, count: 1)  // dec_ref_pic_marking: adaptive_ref_pic_marking_mode_flag
        bits.signedExpGolomb(0)  // slice_qp_delta
        bits.unsignedExpGolomb(UInt32(macroblocksWide * macroblocksHigh))  // mb_skip_run
        bits.trailingBits()
        return networkAbstractionLayerUnit(type: 1, referenceIdc: 2, payload: bits.bytes)
    }

    /// A NAL unit: its one-byte header, then the RBSP with emulation prevention applied.
    ///
    /// spec: ITU-T H.264 §7.3.1 — wherever two zero bytes would be followed by a byte of 3 or less,
    /// an `emulation_prevention_three_byte` is inserted, so no start code can appear inside a unit.
    static func networkAbstractionLayerUnit(type: UInt8, referenceIdc: UInt8, payload: [UInt8])
        -> [UInt8]
    {
        var unit: [UInt8] = [(referenceIdc << 5) | type]
        var zeros = 0
        for byte in payload {
            if zeros >= 2 && byte <= 3 {
                unit.append(3)
                zeros = 0
            }
            unit.append(byte)
            zeros = byte == 0 ? zeros + 1 : 0
        }
        return unit
    }

    /// Segment `index`'s samples, in decode order, each a length-prefixed NAL unit.
    ///
    /// spec: ISO/IEC 14496-15 §5.3.2 — a sample is NAL units each preceded by its length, on
    /// `lengthSizeMinusOne + 1` bytes; four here, as `avcC` declares.
    private static func samples(ofSegment index: Int) -> [[UInt8]] {
        (0..<framesPerSegment).map { frame in
            elementaryFrame(index * framesPerSegment + frame).sample
        }
    }

    /// Frame `index` of the stream's video, counted from the first frame of segment 0, as the
    /// length-prefixed sample a segment carries, and whether it is the IDR opening its segment.
    ///
    /// The same bitstream without its container, for a realtime test (#431): a transport hands
    /// over codec bitstream and nothing else (ADR-0018 rule 3), and this is that bitstream —
    /// four-byte lengths, as ``avcDecoderConfigurationRecord()`` declares, which is CoreMedia's own
    /// framing and the `avc1` half of ADR-0018 rule 4.
    public static func elementaryFrame(_ index: Int) -> (sample: [UInt8], isKeyFrame: Bool) {
        let frame = index % framesPerSegment
        let unit =
            frame == 0
            ? idrSlice(segment: index / framesPerSegment) : skippedSlice(frameNum: frame)
        var writer = ByteWriter()
        writer.u32(UInt32(unit.count))
        writer.append(unit)
        return (writer.bytes, frame == 0)
    }

    /// The stream's `AVCDecoderConfigurationRecord` — the body of the `avcC` box its sample entry
    /// carries, and what a realtime transport hands over as an `avc1` track's record (#431).
    ///
    /// spec: ISO/IEC 14496-15 §5.3.3.1.
    public static func avcDecoderConfigurationRecord() -> [UInt8] {
        let sps = networkAbstractionLayerUnit(
            type: 7, referenceIdc: 3, payload: sequenceParameterSet())
        let pps = networkAbstractionLayerUnit(
            type: 8, referenceIdc: 3, payload: pictureParameterSet())
        var record = ByteWriter()
        record.u8(1)  // configurationVersion
        record.append(Array(sps[1...3]))  // profile, compatibility, level, from the SPS
        record.u8(0xFC | 3)  // reserved, lengthSizeMinusOne: four-byte lengths
        record.u8(0xE0 | 1)  // reserved, numOfSequenceParameterSets
        record.u16(UInt16(sps.count))
        record.append(sps)
        record.u8(1)  // numOfPictureParameterSets
        record.u16(UInt16(pps.count))
        record.append(pps)
        return record.bytes
    }

    // MARK: - Fragmented MP4

    private static let videoTrackId: UInt32 = 1
    private static let audioTrackId: UInt32 = 2

    /// The audio's timescale is its sample rate, so an AAC frame is 1024 ticks exactly.
    private static let audioTimescale = SyntheticHlsStream.samplingFrequencyHz
    private static let samplesPerAudioFrame = SyntheticHlsStream.samplesPerFrame

    /// The AAC frames segment `index` carries: those that *start* inside its two seconds. AAC frames
    /// are 1024 / 44100 s long and do not tile two seconds, so the count alternates between 86 and
    /// 87 and the next segment starts where this one's last frame ends, as a packager cuts them.
    private static func audioFrames(ofSegment index: Int) -> Range<Int> {
        let ticksPerSegment = Int(segmentDurationMs) * audioTimescale / 1_000
        let first = { (segment: Int) in
            (segment * ticksPerSegment + samplesPerAudioFrame - 1) / samplesPerAudioFrame
        }
        return first(index)..<first(index + 1)
    }

    /// spec: ISO/IEC 14496-12 §8.16.2 — `ftyp` then `moov`, with no samples; `mvex` declares that
    /// the samples arrive in fragments.
    static func initializationSegment() -> [UInt8] {
        // spec: ISO/IEC 14496-12 §4.3; `iso6` is the brand whose features a fragmented file uses.
        let fileType = box("ftyp") { body in
            body.ascii("iso6")
            body.u32(0)
            body.ascii("iso6")
            body.ascii("mp41")
        }
        let movie = box("moov") { body in
            body.append(movieHeaderBox())
            body.append(videoTrackBox())
            body.append(audioTrackBox())
            body.append(
                box("mvex") { mvex in
                    // spec: ISO/IEC 14496-12 §8.8.3 — the fragment defaults, overridden per run.
                    mvex.append(
                        fullBox("trex", flags: 0) { trex in
                            trex.u32(videoTrackId)
                            trex.u32(1)  // default_sample_description_index
                            trex.u32(UInt32(frameDuration))
                            trex.u32(0)  // default_sample_size
                            trex.u32(nonSyncSampleFlags)
                        })
                    mvex.append(
                        fullBox("trex", flags: 0) { trex in
                            trex.u32(audioTrackId)
                            trex.u32(1)
                            trex.u32(UInt32(samplesPerAudioFrame))
                            trex.u32(UInt32(SyntheticHlsStream.silentRawDataBlock.count))
                            trex.u32(audioSampleFlags)
                        })
                })
        }
        return fileType + movie
    }

    /// spec: ISO/IEC 14496-12 §8.2.2.
    private static func movieHeaderBox() -> [UInt8] {
        fullBox("mvhd", flags: 0) { body in
            body.u32(0)  // creation_time
            body.u32(0)  // modification_time
            body.u32(1_000)  // timescale
            body.u32(0)  // duration: unknown from the moov of a fragmented file
            body.u32(0x0001_0000)  // rate: 1.0
            body.u16(0x0100)  // volume: 1.0
            body.zeros(10)  // reserved
            body.unityMatrix()
            body.zeros(24)  // pre_defined
            body.u32(audioTrackId + 1)  // next_track_ID
        }
    }

    private static func videoTrackBox() -> [UInt8] {
        box("trak") { body in
            // spec: ISO/IEC 14496-12 §8.3.2 — enabled, in movie, in preview; a visual track's
            // volume is 0 and its size is 16.16 fixed point.
            body.append(
                fullBox("tkhd", flags: 0x7) { tkhd in
                    tkhd.u32(0)  // creation_time
                    tkhd.u32(0)  // modification_time
                    tkhd.u32(videoTrackId)
                    tkhd.u32(0)  // reserved
                    tkhd.u32(0)  // duration
                    tkhd.zeros(8)  // reserved
                    tkhd.u16(0)  // layer
                    tkhd.u16(0)  // alternate_group
                    tkhd.u16(0)  // volume
                    tkhd.u16(0)  // reserved
                    tkhd.unityMatrix()
                    tkhd.u32(UInt32(width) << 16)
                    tkhd.u32(UInt32(height) << 16)
                })
            body.append(
                box("mdia") { mdia in
                    // spec: ISO/IEC 14496-12 §8.4.2 — language "und", three five-bit letters.
                    mdia.append(
                        fullBox("mdhd", flags: 0) { mdhd in
                            mdhd.u32(0)
                            mdhd.u32(0)
                            mdhd.u32(UInt32(timescale))
                            mdhd.u32(0)
                            mdhd.u16(UInt16((21 << 10) | (14 << 5) | 4))  // "und"
                            mdhd.u16(0)
                        })
                    // spec: ISO/IEC 14496-12 §8.4.3 — `vide` is a video track's handler type.
                    mdia.append(
                        fullBox("hdlr", flags: 0) { hdlr in
                            hdlr.u32(0)
                            hdlr.ascii("vide")
                            hdlr.zeros(12)
                            hdlr.ascii("VideoHandler")
                            hdlr.zeros(1)
                        })
                    mdia.append(mediaInformationBox())
                })
        }
    }

    /// The silent audio's track: the Kotlin `SyntheticDashStream`'s audio boxes, which describe the
    /// same AAC-LC at the same rate.
    private static func audioTrackBox() -> [UInt8] {
        box("trak") { body in
            // spec: ISO/IEC 14496-12 §8.3.2 — an audio track's volume is 1.0 and its size 0.
            body.append(
                fullBox("tkhd", flags: 0x7) { tkhd in
                    tkhd.zeros(8)  // creation_time, modification_time
                    tkhd.u32(audioTrackId)
                    tkhd.zeros(4 + 4 + 8)  // reserved, duration, reserved
                    tkhd.u16(0)  // layer
                    tkhd.u16(1)  // alternate_group: the audio's own
                    tkhd.u16(0x0100)  // volume: 1.0
                    tkhd.u16(0)  // reserved
                    tkhd.unityMatrix()
                    tkhd.zeros(8)  // width, height
                })
            body.append(
                box("mdia") { mdia in
                    mdia.append(
                        fullBox("mdhd", flags: 0) { mdhd in
                            mdhd.zeros(8)
                            mdhd.u32(UInt32(audioTimescale))
                            mdhd.u32(0)
                            mdhd.u16(UInt16((21 << 10) | (14 << 5) | 4))  // "und"
                            mdhd.u16(0)
                        })
                    // spec: ISO/IEC 14496-12 §8.4.3 — `soun` is an audio track's handler type.
                    mdia.append(
                        fullBox("hdlr", flags: 0) { hdlr in
                            hdlr.u32(0)
                            hdlr.ascii("soun")
                            hdlr.zeros(12)
                            hdlr.ascii("SoundHandler")
                            hdlr.zeros(1)
                        })
                    mdia.append(
                        box("minf") { minf in
                            // spec: ISO/IEC 14496-12 §12.2.2 — balance centre.
                            minf.append(fullBox("smhd", flags: 0) { $0.zeros(4) })
                            minf.append(dataInformationBox())
                            minf.append(sampleTableBox(entry: audioSampleEntry()))
                        })
                })
        }
    }

    /// spec: ISO/IEC 14496-12 §12.2.3 `AudioSampleEntry` as `mp4a` (ISO/IEC 14496-14 §5.6), and its
    /// `esds` (ISO/IEC 14496-1 §7.2.6) — the Kotlin `SyntheticDashStream`'s, in shape: object type
    /// 0x40 (Audio ISO/IEC 14496-3), stream type 5 (audio), and the two-byte `AudioSpecificConfig`
    /// for AAC-LC at 44.1 kHz in stereo (ISO/IEC 14496-3 §1.6.2.1).
    private static func audioSampleEntry() -> [UInt8] {
        let descriptor = { (tag: UInt8, body: [UInt8]) -> [UInt8] in
            // spec: ISO/IEC 14496-1 §8.3.3 — every descriptor here is under 128 bytes, so its
            // size is one byte.
            precondition(body.count < 0x80)
            return [tag, UInt8(body.count)] + body
        }
        var decoderConfig = ByteWriter()
        decoderConfig.u8(0x40)  // objectTypeIndication: Audio ISO/IEC 14496-3
        decoderConfig.u8((0x05 << 2) | 0x1)  // streamType audio, upStream 0, reserved 1
        decoderConfig.zeros(3)  // bufferSizeDB
        decoderConfig.u32(UInt32(SyntheticHlsStream.declaredBitrateBps))  // maxBitrate
        decoderConfig.u32(UInt32(SyntheticHlsStream.declaredBitrateBps))  // avgBitrate
        decoderConfig.append(descriptor(0x05, SyntheticHlsStream.audioSpecificConfig()))
        var elementaryStream = ByteWriter()
        elementaryStream.u16(UInt16(audioTrackId))  // ES_ID
        elementaryStream.u8(0)  // no dependency, URL or OCR; priority 0
        elementaryStream.append(descriptor(0x04, decoderConfig.bytes))
        elementaryStream.append(descriptor(0x06, [0x02]))  // SLConfig: predefined for MP4
        return box("mp4a") { entry in
            entry.zeros(6)  // reserved
            entry.u16(1)  // data_reference_index
            entry.zeros(8)  // reserved
            entry.u16(UInt16(SyntheticHlsStream.channelConfiguration))  // channelcount
            entry.u16(16)  // samplesize
            entry.zeros(4)  // pre_defined, reserved
            entry.u32(UInt32(audioTimescale) << 16)  // samplerate, 16.16
            entry.append(
                fullBox("esds", flags: 0) { $0.append(descriptor(0x03, elementaryStream.bytes)) })
        }
    }

    /// spec: ISO/IEC 14496-12 §8.7.1-2 — one `url ` with flags 1: the media is in this file.
    private static func dataInformationBox() -> [UInt8] {
        box("dinf") { dinf in
            dinf.append(
                fullBox("dref", flags: 0) { dref in
                    dref.u32(1)
                    dref.append(fullBox("url ", flags: 1) { _ in })
                })
        }
    }

    /// spec: ISO/IEC 14496-12 §8.5.1 — every table but the description is empty, since the samples
    /// are described by each `moof`.
    private static func sampleTableBox(entry: [UInt8]) -> [UInt8] {
        box("stbl") { stbl in
            stbl.append(
                fullBox("stsd", flags: 0) { stsd in
                    stsd.u32(1)
                    stsd.append(entry)
                })
            stbl.append(fullBox("stts", flags: 0) { $0.u32(0) })
            stbl.append(fullBox("stsc", flags: 0) { $0.u32(0) })
            stbl.append(fullBox("stsz", flags: 0) { $0.zeros(8) })
            stbl.append(fullBox("stco", flags: 0) { $0.u32(0) })
        }
    }

    private static func mediaInformationBox() -> [UInt8] {
        box("minf") { minf in
            // spec: ISO/IEC 14496-12 §12.1.2 — `vmhd` carries flags 1 by definition.
            minf.append(
                fullBox("vmhd", flags: 1) { vmhd in
                    vmhd.u16(0)  // graphicsmode: copy
                    vmhd.zeros(6)  // opcolor
                })
            minf.append(dataInformationBox())
            minf.append(sampleTableBox(entry: visualSampleEntry()))
        }
    }

    /// spec: ISO/IEC 14496-12 §12.1.3 `VisualSampleEntry`, as `avc1` (ISO/IEC 14496-15 §5.4.2.1),
    /// with the `avcC` decoder configuration record (§5.3.3.1) carrying the parameter sets — so
    /// `avc1` and not `avc3`, and every sample carries slices alone.
    private static func visualSampleEntry() -> [UInt8] {
        box("avc1") { entry in
            entry.zeros(6)  // reserved
            entry.u16(1)  // data_reference_index
            entry.zeros(16)  // pre_defined, reserved, pre_defined[3]
            entry.u16(UInt16(width))
            entry.u16(UInt16(height))
            entry.u32(0x0048_0000)  // horizresolution: 72 dpi
            entry.u32(0x0048_0000)  // vertresolution: 72 dpi
            entry.u32(0)  // reserved
            entry.u16(1)  // frame_count: one frame per sample
            entry.zeros(32)  // compressorname: empty
            entry.u16(0x0018)  // depth: colour, no alpha
            entry.u16(0xFFFF)  // pre_defined: -1
            entry.append(box("avcC") { $0.append(avcDecoderConfigurationRecord()) })
        }
    }

    /// spec: ISO/IEC 14496-12 §8.8.3.1 — `sample_depends_on` 2 for an IDR, which depends on
    /// nothing; 1 with `sample_is_non_sync_sample` for a P picture, which depends on the one before.
    private static let syncSampleFlags: UInt32 = 0x0200_0000
    private static let nonSyncSampleFlags: UInt32 = 0x0101_0000

    /// An AAC frame's flags: the IDR's, because an AAC-LC frame depends on no other either.
    private static let audioSampleFlags = syncSampleFlags

    /// spec: ISO/IEC 14496-12 §8.16.3 — one `moof` with a fragment per track, and the `mdat` both
    /// runs point into: the video's samples, then the audio's. A run's `data_offset` is measured from
    /// the `moof`'s start, so the `moof` is built once to measure and once for real, as the Kotlin
    /// `mediaSegment` does.
    static func mediaSegment(_ index: Int) -> [UInt8] {
        let samples = samples(ofSegment: index)
        let videoBytes = samples.reduce(0) { $0 + $1.count }
        let measured = movieFragment(index, samples: samples, dataOffset: 0, audioOffset: 0)
        let fragment = movieFragment(
            index, samples: samples, dataOffset: measured.count + 8,
            audioOffset: measured.count + 8 + videoBytes)
        return fragment
            + box("mdat") { mdat in
                for sample in samples { mdat.append(sample) }
                for _ in audioFrames(ofSegment: index) {
                    mdat.append(SyntheticHlsStream.silentRawDataBlock)
                }
            }
    }

    private static func movieFragment(
        _ index: Int, samples: [[UInt8]], dataOffset: Int, audioOffset: Int
    ) -> [UInt8] {
        box("moof") { moof in
            // spec: ISO/IEC 14496-12 §8.8.5 — sequence_number counts fragments from 1.
            moof.append(fullBox("mfhd", flags: 0) { $0.u32(UInt32(index + 1)) })
            moof.append(
                box("traf") { traf in
                    // spec: ISO/IEC 14496-12 §8.8.7 — default-base-is-moof (0x020000), a default
                    // duration (0x08) and default flags (0x20): every frame is one frame long, and
                    // every one but the first is a P picture.
                    traf.append(
                        fullBox("tfhd", flags: 0x02_0028) { tfhd in
                            tfhd.u32(videoTrackId)
                            tfhd.u32(UInt32(frameDuration))
                            tfhd.u32(nonSyncSampleFlags)
                        })
                    // spec: ISO/IEC 14496-12 §8.8.12 — where this segment's first frame is decoded:
                    // exactly `index` segments in, so the segments tile the timeline.
                    traf.append(
                        fullBox("tfdt", version: 1, flags: 0) { tfdt in
                            tfdt.u64(UInt64(index * framesPerSegment * frameDuration))
                        })
                    // spec: ISO/IEC 14496-12 §8.8.8 — data-offset (0x1), first-sample-flags (0x4)
                    // for the IDR, and a size per sample (0x200), since an IDR is kilobytes and a
                    // skipped picture a dozen bytes.
                    traf.append(
                        fullBox("trun", flags: 0x0205) { trun in
                            trun.u32(UInt32(samples.count))
                            trun.u32(UInt32(dataOffset))
                            trun.u32(syncSampleFlags)
                            for sample in samples { trun.u32(UInt32(sample.count)) }
                        })
                })
            moof.append(
                box("traf") { traf in
                    // Every AAC frame takes the defaults — 1024 ticks, the silent block's size, a
                    // sync sample (0x08, 0x10 and 0x20 present) — so the run lists no sample.
                    traf.append(
                        fullBox("tfhd", flags: 0x02_0038) { tfhd in
                            tfhd.u32(audioTrackId)
                            tfhd.u32(UInt32(samplesPerAudioFrame))
                            tfhd.u32(UInt32(SyntheticHlsStream.silentRawDataBlock.count))
                            tfhd.u32(audioSampleFlags)
                        })
                    let frames = audioFrames(ofSegment: index)
                    traf.append(
                        fullBox("tfdt", version: 1, flags: 0) { tfdt in
                            tfdt.u64(UInt64(frames.lowerBound * samplesPerAudioFrame))
                        })
                    traf.append(
                        fullBox("trun", flags: 0x1) { trun in
                            trun.u32(UInt32(frames.count))
                            trun.u32(UInt32(audioOffset))
                        })
                })
        }
    }

    /// spec: ISO/IEC 14496-12 §4.2 `Box`: a 32-bit size, a four-character type and a body; the size
    /// is computed, never written by hand.
    private static func box(_ type: String, _ build: (inout ByteWriter) -> Void) -> [UInt8] {
        var body = ByteWriter()
        build(&body)
        var out = ByteWriter()
        out.u32(UInt32(body.bytes.count + 8))
        out.ascii(type)
        out.append(body.bytes)
        return out.bytes
    }

    /// spec: ISO/IEC 14496-12 §4.2 `FullBox`: a box whose body opens with a version and 24 flag bits.
    private static func fullBox(
        _ type: String, version: UInt8 = 0, flags: UInt32, _ build: (inout ByteWriter) -> Void
    ) -> [UInt8] {
        box(type) { body in
            body.u8(version)
            body.u8(UInt8((flags >> 16) & 0xFF))
            body.u16(UInt16(flags & 0xFFFF))
            build(&body)
        }
    }
}

/// Big-endian bytes, for the boxes.
private struct ByteWriter {
    var bytes: [UInt8] = []

    mutating func append(_ more: [UInt8]) { bytes.append(contentsOf: more) }
    mutating func u8(_ value: UInt8) { bytes.append(value) }
    mutating func u16(_ value: UInt16) {
        bytes.append(UInt8(value >> 8))
        bytes.append(UInt8(value & 0xFF))
    }
    mutating func u32(_ value: UInt32) {
        u16(UInt16(value >> 16))
        u16(UInt16(value & 0xFFFF))
    }
    mutating func u64(_ value: UInt64) {
        u32(UInt32(value >> 32))
        u32(UInt32(value & 0xFFFF_FFFF))
    }
    mutating func ascii(_ value: String) { bytes.append(contentsOf: Array(value.utf8)) }
    mutating func zeros(_ count: Int) { bytes.append(contentsOf: repeatElement(0, count: count)) }

    /// spec: ISO/IEC 14496-12 §8.2.2 — the identity transform, in 16.16 and 2.30 fixed point.
    mutating func unityMatrix() {
        for value: UInt32 in [0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000] {
            u32(value)
        }
    }
}

/// Most-significant-bit-first bits, for the H.264 syntax.
private struct BitWriter {
    private(set) var bytes: [UInt8] = []
    private var pending: UInt8 = 0
    private var pendingCount = 0

    /// Writes the low `count` bits of `value`, most significant first.
    mutating func write(_ value: UInt32, count: Int) {
        for shift in stride(from: count - 1, through: 0, by: -1) {
            pending = (pending << 1) | UInt8((value >> UInt32(shift)) & 1)
            pendingCount += 1
            if pendingCount == 8 {
                bytes.append(pending)
                pending = 0
                pendingCount = 0
            }
        }
    }

    /// spec: ITU-T H.264 §9.1 — `ue(v)`: `codeNum + 1` in binary, preceded by one fewer zeros than
    /// it has bits.
    mutating func unsignedExpGolomb(_ codeNum: UInt32) {
        let value = codeNum + 1
        let bitCount = 32 - value.leadingZeroBitCount
        write(0, count: bitCount - 1)
        write(value, count: bitCount)
    }

    /// spec: ITU-T H.264 §9.1.1 — `se(v)`: positive values to odd code numbers, the rest to even.
    mutating func signedExpGolomb(_ value: Int32) {
        unsignedExpGolomb(value > 0 ? UInt32(2 * value - 1) : UInt32(-2 * value))
    }

    /// Zero bits to the next byte boundary.
    mutating func alignWithZeros() {
        if pendingCount > 0 { write(0, count: 8 - pendingCount) }
    }

    /// spec: ITU-T H.264 §7.3.2.11 — `rbsp_stop_one_bit`, then zeros to a byte boundary.
    mutating func trailingBits() {
        write(1, count: 1)
        alignWithZeros()
    }
}
