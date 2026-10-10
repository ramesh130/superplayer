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

import AVFoundation
import SuperPlayerTestMedia
import XCTest

/// Every frame of a synthetic segment decodes in AVFoundation's AAC decoder.
///
/// `SyntheticHlsPlaybackTests` cannot say this on its own, and that is how #398 found out: an
/// `AVPlayer` plays an audio stream whose every packet the decoder rejects to its end, on time,
/// with no item error and nothing in its error log — the decoder's complaints reach the system log
/// and nowhere a test can read. The generators' frames were all-zero payloads until #398, which is
/// such a stream. So the decoding is asserted here, by decompressing a segment to PCM with an
/// `AVAssetReader`, which fails where the player only goes quiet; the control is the old payload.
final class SyntheticAacDecodingTests: XCTestCase {

    func testEveryFrameOfASegmentDecodesToPcm() async throws {
        let segment = try XCTUnwrap(SyntheticHlsStream.files()[SyntheticHlsStream.segmentName(0)])

        let decoded = try await decodedFrames(segment)

        // spec: ISO/IEC 14496-3 — 1024 samples per AAC-LC frame; two seconds at 44.1 kHz is 87
        // frames, rounded up, as the generator's frameCount has it.
        XCTAssertEqual(decoded, .completed(samples: 87 * 1024))
    }

    /// The control: the same frames with the payload every byte zero, which is what both
    /// generators wrote before #398, are refused by the decoder rather than read as silence.
    func testTheSameFramesWithAnAllZeroPayloadAreRefused() async throws {
        var segment = try XCTUnwrap(
            SyntheticHlsStream.files()[SyntheticHlsStream.segmentName(0)])
        zeroEveryPayload(&segment)

        let decoded = try await decodedFrames(segment)

        guard case .failed = decoded else {
            return XCTFail("an all-zero payload decoded: \(decoded)")
        }
    }

    enum Decoded: Equatable {
        case completed(samples: Int)
        case failed(String)
    }

    /// Decompresses `segment`, an ADTS stream behind its ID3 tag, to linear PCM.
    private func decodedFrames(_ segment: [UInt8]) async throws -> Decoded {
        let file = FileManager.default.temporaryDirectory
            .appending(path: "SyntheticAacDecodingTests-\(UUID().uuidString).aac")
        try Data(segment).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }

        let asset = AVURLAsset(url: file)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        let track = try XCTUnwrap(tracks.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(
            track: track, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM])
        reader.add(output)
        XCTAssertTrue(reader.startReading(), "\(String(describing: reader.error))")

        var samples = 0
        while let buffer = output.copyNextSampleBuffer() {
            samples += CMSampleBufferGetNumSamples(buffer)
        }
        if reader.status == .completed { return .completed(samples: samples) }
        return .failed(String(describing: reader.error))
    }

    /// Zeroes every byte after each 7-byte ADTS header, walking frames by `frame_length`
    /// (ISO/IEC 13818-7 §6.2) from the end of the ID3 tag (whose size is syncsafe, ID3v2.3.0 §3.1).
    private func zeroEveryPayload(_ segment: inout [UInt8]) {
        let tagSize = segment[6...9].reduce(0) { $0 << 7 | Int($1) }
        var offset = 10 + tagSize
        while offset + 7 <= segment.count {
            let frameLength =
                Int(segment[offset + 3] & 0b11) << 11 | Int(segment[offset + 4]) << 3
                | Int(segment[offset + 5]) >> 5
            for index in (offset + 7)..<(offset + frameLength) { segment[index] = 0 }
            offset += frameLength
        }
    }
}
