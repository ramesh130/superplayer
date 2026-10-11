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

/// Every frame of the synthetic video decodes in AVFoundation's H.264 decoder, to the picture the
/// generator wrote.
///
/// `SyntheticAacDecodingTests`' lesson taken before it was needed: a stream that plays is not a
/// stream that decodes, and a frame output is handed only what decodes. So the initialization
/// segment and two media segments are decompressed to pixels with an `AVAssetReader`, which fails
/// where a player would only show nothing; the count, the times and the grey level are all read off
/// the decoded buffers. The control is the grey level: the two segments were written a step apart,
/// and they decode a step apart, so the reading is of the bytes and not of a constant.
final class SyntheticVideoDecodingTests: XCTestCase {

    func testEveryFrameOfTwoSegmentsDecodesInOrderAtTheDeclaredRate() async throws {
        let decoded = try await decodedFrames(segmentCount: 2)

        XCTAssertEqual(decoded.times.count, 2 * SyntheticVideoHlsStream.framesPerSegment)
        let frameInterval = 1.0 / Double(SyntheticVideoHlsStream.framesPerSecond)
        for (index, time) in decoded.times.enumerated() {
            XCTAssertEqual(time, Double(index) * frameInterval, accuracy: 1e-9)
        }
        XCTAssertEqual(
            decoded.size, [SyntheticVideoHlsStream.width, SyntheticVideoHlsStream.height])
    }

    func testEachSegmentDecodesToItsOwnGreyLevel() async throws {
        let decoded = try await decodedFrames(segmentCount: 2)

        // Mid-grey 0x60 and the next step, 0x78, as video-range luma (ITU-R BT.601 §2.5.3: 16 is
        // black and 235 white), so a full-range read is (Y - 16) × 255 / 219, within a level for
        // the decoder's rounding.
        let fullRange = { (luma: Double) in (luma - 16) * 255 / 219 }
        XCTAssertEqual(Double(decoded.firstFrameGrey[0]), fullRange(0x60), accuracy: 2)
        XCTAssertEqual(Double(decoded.firstFrameGrey[1]), fullRange(0x78), accuracy: 2)
    }

    /// The silent audio beside the video decodes too, and covers the same two segments: it is what
    /// carries the clock of a player with nothing to draw the video on (`SyntheticVideoHlsStream`).
    func testTheSilentAudioBesideItDecodesToPcmForTheSameSpan() async throws {
        let asset = AVURLAsset(url: try writtenStream(segmentCount: 2))
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

        XCTAssertEqual(reader.status, .completed, "\(String(describing: reader.error))")
        // spec: ISO/IEC 14496-3 — 1024 samples per AAC-LC frame; the frames starting inside four
        // seconds at 44.1 kHz are ⌈176400 / 1024⌉ = 173. Less the 2112 priming samples AVFoundation
        // takes off the front of AAC in an MP4 file that states no edit list — what it did here, and
        // not to the ADTS segments `SyntheticAacDecodingTests` reads, which are not MP4.
        // ref: Apple Technical Note TN2258, "AAC Audio - Encoder Delay and Synchronization"
        XCTAssertEqual(samples, 173 * 1024 - 2112)
    }

    private struct Decoded {
        var times: [Double] = []
        var size: [Int] = []
        /// The blue channel of the first pixel of each segment's first frame — every channel of a
        /// grey picture, read once.
        var firstFrameGrey: [UInt8] = []
    }

    /// The initialization segment and `segmentCount` media segments, one after the other, as a
    /// fragmented MP4 file `AVAssetReader` reads; removed when the test ends.
    private func writtenStream(segmentCount: Int) throws -> URL {
        let files = SyntheticVideoHlsStream.files(segmentCount: segmentCount)
        var bytes = try XCTUnwrap(files[SyntheticVideoHlsStream.initializationSegmentName])
        for index in 0..<segmentCount {
            bytes += try XCTUnwrap(files[SyntheticVideoHlsStream.segmentName(index)])
        }
        let file = FileManager.default.temporaryDirectory
            .appending(path: "SyntheticVideoDecodingTests-\(UUID().uuidString).mp4")
        try Data(bytes).write(to: file)
        addTeardownBlock { try? FileManager.default.removeItem(at: file) }
        return file
    }

    private func decodedFrames(segmentCount: Int) async throws -> Decoded {
        let asset = AVURLAsset(url: try writtenStream(segmentCount: segmentCount))
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output)
        XCTAssertTrue(reader.startReading(), "\(String(describing: reader.error))")

        var decoded = Decoded()
        while let sample = output.copyNextSampleBuffer() {
            guard let picture = CMSampleBufferGetImageBuffer(sample) else { continue }
            if decoded.times.count % SyntheticVideoHlsStream.framesPerSegment == 0 {
                CVPixelBufferLockBaseAddress(picture, .readOnly)
                defer { CVPixelBufferUnlockBaseAddress(picture, .readOnly) }
                let pixels = try XCTUnwrap(CVPixelBufferGetBaseAddress(picture))
                decoded.firstFrameGrey.append(pixels.load(as: UInt8.self))
                decoded.size = [
                    CVPixelBufferGetWidth(picture), CVPixelBufferGetHeight(picture),
                ]
            }
            decoded.times.append(CMSampleBufferGetPresentationTimeStamp(sample).seconds)
        }
        XCTAssertEqual(reader.status, .completed, "\(String(describing: reader.error))")
        return decoded
    }
}
