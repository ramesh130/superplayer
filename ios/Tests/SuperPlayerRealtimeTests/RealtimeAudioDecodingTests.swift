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
import SuperPlayerTestMedia
import XCTest

@testable import SuperPlayerRealtime

/// An AAC track as this side describes it decodes in CoreAudio's AAC decoder (#432).
///
/// `RealtimeTracksTests` cannot say this on its own, and that is how #432 found out: an
/// `AVSampleBufferAudioRenderer` whose decoder will not initialise from the description's magic
/// cookie reports `rendering` with no error, and the decoder's complaint — "AudioCodecInitialize
/// failed" — reaches the system log and nowhere a test can read. It is #398's silent decoder,
/// arriving through the renderer. So the decoding is asserted here, the way
/// `SyntheticAacDecodingTests` asserts the HLS stream's: the description the engine makes, its
/// magic cookie handed to an AudioToolbox converter as the renderer's decoder is handed it, and the
/// packets the engine enqueues decompressed to PCM.
///
/// The control is the description #431 made, the AudioSpecificConfig as a bare cookie, which the
/// converter refuses with the same log line. **`AVAudioConverter` is not the instrument**: built from
/// an `AVAudioFormat` of either description it decodes both, so it cannot tell them apart — which is
/// why this reaches past it to `kAudioConverterDecompressionMagicCookie`.
///
/// `@testable` for the description and the sample buffers, which are internal: what a consumer
/// would observe instead is silence.
final class RealtimeAudioDecodingTests: XCTestCase {

    /// How many packets are decoded: a fifth of a second, enough that the decoder has run past its
    /// priming rather than answered once.
    private let packetCount = 10

    func testAnAacTrackAsDescribedHereDecodesToPcm() throws {
        let description = try XCTUnwrap(
            TrackFraming(track: ScriptedTrack.audio().track).description)

        let decoded = try decode(packetCount, under: description)

        guard case .completed(let frames) = decoded else {
            return XCTFail("the engine's AAC description did not decode: \(decoded)")
        }
        XCTAssertGreaterThan(frames, 0)
    }

    /// The control: the same rate, channels and packets, with the AudioSpecificConfig as the cookie
    /// bare — what #431 did — is refused at the cookie rather than decoded.
    func testTheSameTrackWithItsAudioSpecificConfigAsABareCookieIsRefused() throws {
        let ours = try XCTUnwrap(TrackFraming(track: ScriptedTrack.audio().track).description)
        var basic = try XCTUnwrap(ours.audioStreamBasicDescription)
        let bare = SyntheticHlsStream.audioSpecificConfig()
        var description: CMAudioFormatDescription?
        XCTAssertEqual(
            CMAudioFormatDescriptionCreate(
                allocator: kCFAllocatorDefault, asbd: &basic, layoutSize: 0, layout: nil,
                magicCookieSize: bare.count, magicCookie: bare, extensions: nil,
                formatDescriptionOut: &description), noErr)

        let decoded = try decode(packetCount, under: try XCTUnwrap(description))

        guard case .refusedCookie = decoded else {
            return XCTFail("a bare AudioSpecificConfig cookie was taken: \(decoded)")
        }
    }

    /// The cookie is the descriptor ISO/IEC 14496-1 writes, byte for byte, with the
    /// AudioSpecificConfig at its centre — the same descriptor the synthetic video stream's own
    /// `esds` carries, apart from the bitrates it states and this leaves unknown.
    func testTheCookieIsTheElementaryStreamDescriptorAroundTheAudioSpecificConfig() {
        let asc = Data(SyntheticHlsStream.audioSpecificConfig())
        XCTAssertEqual(
            [UInt8](RealtimeFormats.elementaryStreamDescriptor(wrapping: asc)),
            [
                0x03, 0x19, 0x00, 0x00, 0x00,  // ES_Descriptor: ES_ID 0, no flags
                0x04, 0x11, 0x40, 0x15,  // DecoderConfigDescriptor: 14496-3 audio, stream type 5
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // unknown sizes
                0x05, 0x02, 0x12, 0x10,  // DecoderSpecificInfo: the AudioSpecificConfig
                0x06, 0x01, 0x02,  // SLConfigDescriptor: predefined for MP4
            ])
    }

    enum Decoded: Equatable {
        case completed(frames: Int)
        case refusedCookie(OSStatus)
        case failed(OSStatus)
    }

    /// `count` of the synthetic stream's AAC frames, made into sample buffers as the engine makes
    /// them, decompressed to linear PCM by a converter given `description`'s magic cookie.
    private func decode(_ count: Int, under description: CMFormatDescription) throws -> Decoded {
        var input = try XCTUnwrap(description.audioStreamBasicDescription)
        let bytesPerFrame = UInt32(MemoryLayout<Float32>.size) * input.mChannelsPerFrame
        var output = AudioStreamBasicDescription(
            mSampleRate: input.mSampleRate, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: bytesPerFrame, mFramesPerPacket: 1, mBytesPerFrame: bytesPerFrame,
            mChannelsPerFrame: input.mChannelsPerFrame, mBitsPerChannel: 32, mReserved: 0)
        var made: AudioConverterRef?
        XCTAssertEqual(AudioConverterNew(&input, &output, &made), noErr)
        let converter = try XCTUnwrap(made)
        defer { AudioConverterDispose(converter) }

        var cookieSize = 0
        let cookie = try XCTUnwrap(
            CMAudioFormatDescriptionGetMagicCookie(description, sizeOut: &cookieSize))
        let taken = AudioConverterSetProperty(
            converter, kAudioConverterDecompressionMagicCookie, UInt32(cookieSize), cookie)
        guard taken == noErr else { return .refusedCookie(taken) }

        let frame = Data(SyntheticHlsStream.elementaryAudioFrame())
        let feeder = PacketFeeder(
            packets: try (0..<count).map { index in
                // Through the engine's own sample buffer, so what is decoded is what is enqueued.
                let sample = try RealtimeEngine.sampleBuffer(
                    frame, keyFrame: true, description: description, presentationUs: Int64(index),
                    codec: "mp4a.40.2")
                return try XCTUnwrap(sample.dataBuffer).dataBytes()
            })
        let capacity = UInt32(SyntheticHlsStream.samplesPerFrame)
        var pcm = [UInt8](repeating: 0, count: Int(capacity * bytesPerFrame))
        var decoded = 0
        while true {
            var frames = capacity
            let status = pcm.withUnsafeMutableBytes { bytes in
                var list = AudioBufferList(
                    mNumberBuffers: 1,
                    mBuffers: AudioBuffer(
                        mNumberChannels: output.mChannelsPerFrame,
                        mDataByteSize: UInt32(bytes.count), mData: bytes.baseAddress))
                return AudioConverterFillComplexBuffer(
                    converter, PacketFeeder.supply, Unmanaged.passUnretained(feeder).toOpaque(),
                    &frames, &list, nil)
            }
            decoded += Int(frames)
            if status == PacketFeeder.exhausted || (status == noErr && frames == 0) {
                return .completed(frames: decoded)
            }
            guard status == noErr else { return .failed(status) }
        }
    }
}

/// Hands a converter one packet per call, keeping each alive until the next — AudioToolbox's input
/// callback contract — and says it has none left with a status of its own. The packet and its
/// description live in memory this object allocates, so the pointers handed out outlive the call.
private final class PacketFeeder {
    /// The status the feeder answers with once it has nothing left, so running dry is told from a
    /// decoder's failure: chosen by this test, outside every `kAudioConverterErr_…` and
    /// `kAudioCodec…Error` value AudioToolbox's headers declare, all of which are four-character
    /// codes or small negatives near -50.
    static let exhausted: OSStatus = -50_001

    private var packets: [Data]
    private var current = UnsafeMutableRawBufferPointer(start: nil, count: 0)
    private let description = UnsafeMutablePointer<AudioStreamPacketDescription>.allocate(
        capacity: 1)

    init(packets: [Data]) { self.packets = packets }

    deinit {
        current.deallocate()
        description.deallocate()
    }

    static let supply: AudioConverterComplexInputDataProc = { _, count, data, descriptions, user in
        let feeder = Unmanaged<PacketFeeder>.fromOpaque(user!).takeUnretainedValue()
        guard !feeder.packets.isEmpty else {
            count.pointee = 0
            return exhausted
        }
        let packet = feeder.packets.removeFirst()
        feeder.current.deallocate()
        feeder.current = .allocate(byteCount: packet.count, alignment: 1)
        feeder.current.copyBytes(from: packet)
        feeder.description.pointee = AudioStreamPacketDescription(
            mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count))
        count.pointee = 1
        data.pointee.mNumberBuffers = 1
        data.pointee.mBuffers.mData = feeder.current.baseAddress
        data.pointee.mBuffers.mDataByteSize = UInt32(packet.count)
        descriptions?.pointee = feeder.description
        return noErr
    }
}
