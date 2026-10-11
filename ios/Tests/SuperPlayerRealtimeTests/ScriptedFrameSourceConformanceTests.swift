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
import SuperPlayerCore
import SuperPlayerTestkit
import XCTest

@testable import SuperPlayerRealtime

/// Holds this product's own fake to the contract it defines (#433) — the Kotlin
/// `ScriptedFrameSourceConformanceTest`.
///
/// ``ScriptedFrameSource`` is the reference implementation of ``FrameSource`` on this platform: it
/// is what `RealtimePlayerTests` and `RealtimeTracksTests` play, and what an adopter reads when they
/// want to see one written. A reference that does not satisfy the conformance suite is either a
/// broken reference or a broken suite, and `FrameSourceConformanceTests` cannot tell which, since it
/// scores the suite against a publisher of its own.
///
/// The fake stays a fixture of this target rather than moving into `SuperPlayerTestkit`, for the
/// Kotlin reason: moving it would publish it, and #340's bytes with it, as a versioned API surface
/// to save one import.
///
/// `@testable` for one reading: the formats table's `mappedTokens`, which the codec-family test
/// holds the suite's own list to. The table is internal because what it answers is CoreMedia
/// vocabulary (`RealtimeFormatsTests` argues it), and the suite, phase 2, cannot read it at all.
final class ScriptedFrameSourceConformanceTests: XCTestCase {

    /// The self-describing branch of ADR-0018 rule 4: `avc3` with in-band parameter sets and
    /// Annex-B samples, beside the AAC track.
    func testTheSelfDescribingFakeSatisfiesEveryObligation() throws {
        try conformance(.video(.avc3, frames: Self.scriptedFrames), audio()).verifyAll()
    }

    /// The other branch: `avc1` with the synthetic stream's `avcC` and the four-byte
    /// length-prefixed samples that record declares.
    func testTheRecordCarryingFakeSatisfiesEveryObligation() throws {
        try conformance(.video(.avc1, frames: Self.scriptedFrames), audio()).verifyAll()
    }

    /// The same branch at the width a hard-coded four would get wrong: the record rewritten to
    /// declare two-byte lengths and every sample re-framed at that width. The suite reads the width
    /// out of the record, as the player does, so both must pass it.
    func testTheTwoByteLengthFakeSatisfiesEveryObligation() throws {
        try conformance(.video(.avc1TwoByteLengths, frames: Self.scriptedFrames), audio())
            .verifyAll()
    }

    /// The drift guard between the suite's list of codec families and the table that maps them,
    /// written down twice because the table is this product's and internal while the suite is phase
    /// 2's. Agreement is about the **family**: the suite reads the fourcc or the encoding name,
    /// while the table also refuses a mapped family whose parameters do not parse, so `avc1` with no
    /// parameters is accepted there and refused here, deliberately.
    func testTheSuiteAcceptsEveryCodecFamilyTheTableMaps() throws {
        // The table's own keys and not a third list, so a family added to the table and to neither
        // the suite nor the row below fails here.
        XCTAssertEqual(
            Set(Self.mapped.map { String($0.prefix { $0 != "." }).lowercased() }),
            RealtimeFormats.mappedTokens)
        for codec in Self.mapped {
            XCTAssertNoThrow(try RealtimeFormats.format(for: codec), codec)
            XCTAssertNil(try refusal(for: codec), codec)
        }
        for codec in Self.unmapped {
            XCTAssertThrowsError(try RealtimeFormats.format(for: codec), codec) {
                XCTAssertTrue($0 is UnsupportedRealtimeCodecError, "\(codec): \($0)")
            }
            let refusal = try XCTUnwrap(try refusal(for: codec), codec)
            XCTAssertTrue(refusal.contains(codec), refusal)
        }
    }

    /// What the suite says about a track declared as `codec`, or nil where it says nothing.
    private func refusal(for codec: String) throws -> String? {
        let track = ScriptedTrack.video(.avc1, frames: Self.scriptedFrames)
        let declared = ScriptedTrack(
            track: RealtimeTrack(codec: codec), frames: track.frames, epochUs: track.epochUs,
            startsAtUs: track.startsAtUs, timeOfFrameUs: track.timeOfFrameUs, frame: track.frame)
        do {
            try conformance(declared).verify(.codecStringsAreMapped)
            return nil
        } catch {
            return error.message
        }
    }

    /// The AAC track, so every run here is the two-track case a transport carries.
    private func audio() -> ScriptedTrack { .audio(frames: Self.scriptedFrames) }

    /// The suite over a fresh ``ScriptedFrameSource`` per check, the lifecycle a player gives one.
    /// Paced, as the fake is by default: an asynchronous publisher still delivering when the suite
    /// cancels it is the only case in which cancellation passing means anything.
    private func conformance(_ tracks: ScriptedTrack...) -> FrameSourceConformance {
        FrameSourceConformance(
            sources: ScriptedTransport { _ in ScriptedFrameSource(tracks: tracks) },
            url: URL(string: "moq://relay.example/studio-a")!)
    }

    /// Comfortably more than the suite observes, so no check waits for a frame that is not coming,
    /// and few enough that a paced script is still delivering when the suite cancels it.
    private static let scriptedFrames = 24

    /// One string per family the table maps, in both vocabularies, each with the parameters its
    /// grammar states, because the table refuses a mapped family whose parameters do not parse.
    private static let mapped = [
        "avc1.42C01E", "avc3.42E01E", "hvc1.1.6.L93.B0", "hev1.1.6.L93.B0",
        "vp09.00.10.08", "av01.0.04M.08", "mp4a.40.2", "mp4a.67.2", "opus",
        "H264", "H265", "VP9", "AV1", "MPEG4-GENERIC",
    ]

    /// Strings neither side maps: two real codecs this library does not carry, the `mp4a` object
    /// types that are MP3 rather than AAC, and a fourcc one letter off. // spec: RFC 6381 §3.3.
    private static let unmapped = ["theora", "vorbis", "mp4a.69", "mp4a.6B", "avc9.42C01E"]
}
