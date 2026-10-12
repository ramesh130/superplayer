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
import SuperPlayerCore
import SuperPlayerTestkit
import XCTest

@testable import SuperPlayerMoQ

/// Holds ``MoqFrameSource`` to the contract every realtime transport is held to — the Kotlin
/// `MoqFrameSourceConformanceTest`, through testkit's shipped `FrameSourceConformance` (#433).
///
/// **What a green run proves**: that this bridge — the connect thread, the declaration, the pumps,
/// the delivery lock and the cancellation — honours the obligations. **What it does not**: anything
/// about MoQ. No QUIC session is opened and no line of ``UniffiMoqRelay`` runs; ``ScriptedMoqRelay``
/// stands behind the seam, and a broadcast on a device is the i9 device ticket's.
///
/// Both of ADR-0018 rule 4's branches, because the catalog decides which a track is in — a
/// `description` present is a record, absent is in band — and the two frame their samples
/// differently; both carry audio beside video, the ordinary MoQ broadcast.
final class MoqFrameSourceConformanceTests: XCTestCase {

    /// The self-describing branch: `avc3` with no `description`, Annex-B samples, beside AAC.
    func testTheBridgeSatisfiesEveryObligationOnASelfDescribingBroadcast() throws {
        try conformance(
            DeclaredCatalogs.catalog(
                video: [
                    Self.video: DeclaredCatalogs.video(
                        DeclaredCatalogs.observedAvc3Codec, description: nil)
                ],
                audio: [
                    Self.audio: DeclaredCatalogs.audio(
                        DeclaredCatalogs.synthesizedAacCodec,
                        description: DeclaredCatalogs.audioSpecificConfig)
                ])
        ).verifyAll()
    }

    /// The other branch: `avc1` carrying #340's observed `avcC`, its samples length-prefixed behind
    /// the four-byte field that record declares.
    func testTheBridgeSatisfiesEveryObligationOnARecordCarryingBroadcast() throws {
        var video = ScriptedMoqTrack()
        video.payload = lengthPrefixedH264
        try conformance(
            DeclaredCatalogs.catalog(
                video: [
                    Self.video: DeclaredCatalogs.video(
                        DeclaredCatalogs.observedAvc1Codec,
                        description: DeclaredCatalogs.observedAvccPrefix)
                ],
                audio: [
                    Self.audio: DeclaredCatalogs.audio(
                        DeclaredCatalogs.synthesizedAacCodec,
                        description: DeclaredCatalogs.audioSpecificConfig)
                ]),
            video: video
        ).verifyAll()
    }

    /// The control that keeps the two above from passing on a suite that accepts everything: the
    /// same bridge, over a catalog whose `avc1` rendition declared no record, is refused by name.
    func testTheSuiteRefusesARecordFourccWhoseCatalogDeclaredNone() {
        let suite = conformance(
            DeclaredCatalogs.catalog(video: [
                Self.video: DeclaredCatalogs.video(
                    DeclaredCatalogs.observedAvc1Codec, description: nil)
            ]))

        XCTAssertThrowsError(try suite.verify(.codecConfigurationFollowsTheFourcc)) {
            XCTAssertTrue("\($0)".contains("obligation 7"), "\($0)")
        }
    }

    /// The suite over a fresh ``MoqFrameSource`` per check, each over one scripted relay — the
    /// lifecycle a player gives a source.
    private func conformance(_ catalog: MoqCatalog, video: ScriptedMoqTrack = ScriptedMoqTrack())
        -> FrameSourceConformance
    {
        var audio = ScriptedMoqTrack()
        audio.payload = aacFrame
        audio.frameDurationUs = audioFrameDurationUs
        let relay = ScriptedMoqRelay(
            catalog: catalog, tracks: [Self.video: video, Self.audio: audio])
        return FrameSourceConformance(
            sources: ScriptedMoqTransport(relay: relay),
            url: URL(string: "\(MoqFrameSource.scheme)://relay.example/anon/studio-a")!)
    }

    private static let video = "video"
    private static let audio = "audio"
}

/// The factory the suite opens a source through: a ``MoqFrameSource`` over the scripted relay.
private struct ScriptedMoqTransport: FrameSourceFactory {
    let relay: ScriptedMoqRelay
    func open(_ url: URL) -> any FrameSource { MoqFrameSource(url: url, relay: relay) }
}
