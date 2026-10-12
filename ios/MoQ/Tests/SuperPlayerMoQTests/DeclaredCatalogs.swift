//
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

/// The catalogs this product's tests map, each assembled from fields #340 observed or written here
/// — the Kotlin `DeclaredCatalogs`, whose provenance rules bind this file.
///
/// #340 saw an H.264 broadcast's codec strings, the presence or absence of a `description`, and the
/// first 24 bytes of its `avcC`; it did **not** exercise the catalog path (its caveat 4), so no
/// `MoqCatalog` here was ever received from a broadcast, and everything that is not H.264 is
/// **synthesized** (its caveat 2). Unlike the Kotlin object, the `avcC` is not restated: it is read
/// from `ios/Tests/Fixtures/realtime/observed-bytes.tsv`, the one file both platforms' realtime tests
/// read, which a separate package can reach by path where it could not reach another target's
/// sources.
enum DeclaredCatalogs {

    /// #340's observed video codec string for the out-of-band shape: an `avc1` sample entry.
    static let observedAvc1Codec = "avc1.42c01e"

    /// #340's observed video codec string for the in-band shape: the same media, an `avc3` entry.
    static let observedAvc3Codec = "avc3.42c01e"

    /// #340's `description` dump, **verbatim**: the first 24 bytes of the 40-byte `avcC` it saw. A
    /// prefix, which is why nothing here parses it — this product is forbidden to (ADR-0018 rule 4).
    static let observedAvccPrefix = observedBytes("AVCC_DUMP")

    /// An `hvcC`-shaped record, **synthesized**, the Kotlin fixture's bytes.
    ///
    /// // spec: the shape is the `HEVCDecoderConfigurationRecord`, ISO/IEC 14496-15 §8.3.3.1.2.
    /// // Nothing here reads a byte of it; any bytes would serve the assertion it is used for.
    static let synthesizedHvcc = bytes(
        "01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0f 03")

    /// An `hvc1` codec string, **synthesized**.
    static let synthesizedHvc1Codec = "hvc1.1.6.L93.B0"

    /// An AAC-LC codec string, **synthesized**: #340 saw no audio track at all.
    static let synthesizedAacCodec = "mp4a.40.2"

    /// An Opus codec string, **synthesized**.
    static let synthesizedOpusCodec = "opus"

    /// A codec string the realtime product's table does not carry, **synthesized**: VP8, one a
    /// publisher plausibly sends. Nothing mechanical holds it unmapped from here; a phase that maps
    /// VP8 has to move it.
    static let synthesizedUnmappedCodec = "vp08.00.41.08"

    /// An AAC-LC `AudioSpecificConfig`, **synthesized**.
    ///
    /// // spec: ISO/IEC 14496-3 §1.6.2.1 — object type 2 (AAC-LC), sampling frequency index 3
    /// // (48 kHz), channel configuration 2, which is `12 10`.
    static let audioSpecificConfig = bytes("12 10")

    /// A catalog of the given renditions, with nothing in its presentation half.
    static func catalog(
        video: [String: MoqVideo] = [:], audio: [String: MoqAudio] = [:]
    ) -> MoqCatalog {
        MoqCatalog(
            video: video, audio: audio, display: nil, rotation: nil, flip: nil, sections: [:])
    }

    /// One video rendition: a codec, its record or none, its coded size if declared, a container.
    static func video(
        _ codec: String, description: Data?, coded: MoqDimensions? = nil,
        container: MoqContainer = .legacy, stalled: Bool = false
    ) -> MoqVideo {
        MoqVideo(
            codec: codec, description: description, coded: coded, displayAspect: nil,
            bitrate: nil, stalled: stalled, framerate: nil, container: container)
    }

    /// One audio rendition.
    static func audio(
        _ codec: String, description: Data? = nil, container: MoqContainer = .legacy
    ) -> MoqAudio {
        MoqAudio(
            codec: codec, description: description, sampleRate: 48_000, channelCount: 2,
            bitrate: nil, container: container)
    }

    /// Space-separated hex as bytes.
    static func bytes(_ hex: String) -> Data {
        Data(hex.split(separator: " ").map { UInt8($0, radix: 16)! })
    }

    /// The row `name` of the shared fixture, as bytes.
    private static func observedBytes(_ name: String) -> Data {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("../../../Tests/Fixtures/realtime/observed-bytes.tsv")
            .standardizedFileURL
        let text = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
        for line in text.split(separator: "\n") where !line.hasPrefix("#") {
            let fields = line.split(separator: "\t")
            if fields.count == 3, fields[0] == name { return bytes(String(fields[2])) }
        }
        preconditionFailure("\(file.path) has no row \(name)")
    }
}
