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
import AVKit
import XCTest

// `@testable` for `framePath` and `liveObservations`, the one reading past the facade and argued
// as `TelemetryPaysNothingTests` argues its own: a display link is visible to nothing but its
// target, so whether one exists cannot be read through any public API. Everything else here — the
// item's outputs, the layer a view hosts, the off reason — is public.
@testable import SuperPlayerCore

/// ADR-0022 rules 6, 7 and 9 on iOS: a player configured without a frame output makes no
/// `AVPlayerItemVideoOutput`, no display link and no sample-buffer layer, and the view hosting it is
/// an `AVPlayerLayer` exactly as a stock app's; one configured with a hand-written stand-in makes
/// each of them, so the counter is shown to see what it counts (rule 9's two counts, ADR-0014 rule
/// 14's way); and one configured with protection as well makes none of them and says why.
///
/// No item here ever loads — the URL names a port nothing listens on — because everything counted
/// is made when an item is *set*, not when it plays. `FrameOutputPlaybackTests` is the playing half.
@MainActor
final class FrameOutputPaysNothingTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private var players: [SuperPlayer] = []

    override func tearDown() async throws {
        for player in players { player.release() }
        players = []
        try await super.tearDown()
    }

    private func player(output: (any FrameOutput)?, protected: Bool = false) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        configuration.frameOutput = output
        if protected { configuration.drm = StandInDrm() }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        return player
    }

    func testAPlayerWithoutAFrameOutputMakesNoOutputLinkOrLayer() throws {
        let player = player(output: nil)

        XCTAssertNil(player.framePath, "no display link, no sample-buffer layer")
        XCTAssertEqual(try XCTUnwrap(player.avPlayer.currentItem).outputs.count, 0)
        XCTAssertEqual(player.liveObservations[.frameOutputItemTimeJumped], nil)
        XCTAssertNil(player.frameOutputOffReason)
        XCTAssertTrue(PlayerLayerView(player: player).layer.sublayers?.first is AVPlayerLayer)
    }

    /// The control: the same reading with a stand-in output sees each thing it counts, once.
    func testAPlayerWithAFrameOutputMakesEachOfThemOnce() throws {
        let player = player(output: StandInOutput())

        let path = try XCTUnwrap(player.framePath)
        XCTAssertTrue(path.hasDisplayLink)
        let outputs = try XCTUnwrap(player.avPlayer.currentItem).outputs
        XCTAssertEqual(outputs.count, 1)
        XCTAssertTrue(outputs.first is AVPlayerItemVideoOutput)
        XCTAssertEqual(player.liveObservations[.frameOutputItemTimeJumped], 1)
        XCTAssertNil(player.frameOutputOffReason)
        let hosted = PlayerLayerView(player: player).layer.sublayers?.first
        XCTAssertTrue(hosted is AVSampleBufferDisplayLayer)
        XCTAssertTrue(hosted === path.layer, "the player's own layer, not one the view made")
    }

    /// The defect a per-item output invites: an output left on an item the player let go of. Each
    /// replacement moves the one output to the new item, through a bare URL too.
    func testReplacingTheContentMovesTheOneOutputToTheNewItem() throws {
        let player = player(output: StandInOutput())
        let first = try XCTUnwrap(player.avPlayer.currentItem)

        player.setMediaRequest(MediaRequest(contentId: "b", sources: [nowhere]))
        let second = try XCTUnwrap(player.avPlayer.currentItem)
        player.setMediaURL(nowhere)
        let third = try XCTUnwrap(player.avPlayer.currentItem)

        XCTAssertEqual(first.outputs.count, 0)
        XCTAssertEqual(second.outputs.count, 0)
        XCTAssertEqual(third.outputs.count, 1)
        XCTAssertEqual(player.liveObservations[.frameOutputItemTimeJumped], 1)
    }

    func testAReleasedPlayerHoldsNoOutputLinkOrObservation() throws {
        let player = player(output: StandInOutput())
        let item = try XCTUnwrap(player.avPlayer.currentItem)

        player.release()

        XCTAssertEqual(item.outputs.count, 0)
        XCTAssertFalse(try XCTUnwrap(player.framePath).hasDisplayLink)
        XCTAssertEqual(player.liveObservations, [:])
    }

    /// A recycled player keeps nothing of its output's last item (#404's `resetForReuse`, output
    /// first): no item output, no display link, no observation — and the next item it is given
    /// gets them back, once.
    func testARecycledPlayerKeepsNoOutputLinkOrObservationUntilItsNextItem() throws {
        let player = player(output: StandInOutput())
        let item = try XCTUnwrap(player.avPlayer.currentItem)

        player.resetForReuse()

        XCTAssertEqual(item.outputs.count, 0)
        XCTAssertFalse(try XCTUnwrap(player.framePath).hasDisplayLink)
        XCTAssertNil(player.liveObservations[.frameOutputItemTimeJumped])

        player.setMediaRequest(MediaRequest(contentId: "b", sources: [nowhere]))
        XCTAssertEqual(try XCTUnwrap(player.avPlayer.currentItem).outputs.count, 1)
        XCTAssertTrue(try XCTUnwrap(player.framePath).hasDisplayLink)
        XCTAssertEqual(player.liveObservations[.frameOutputItemTimeJumped], 1)
    }

    /// ADR-0022 rule 6 through `Configuration.drm`, set at all: protection and an output meet when the player is made,
    /// and the answer is a reason, with nothing of the output made — the view is an `AVPlayerLayer`.
    func testAProtectedPlayerHasItsOutputOffAndMakesNothingForIt() throws {
        let player = player(output: StandInOutput(), protected: true)

        XCTAssertEqual(player.frameOutputOffReason, .protectedContent)
        XCTAssertNil(player.framePath)
        XCTAssertEqual(try XCTUnwrap(player.avPlayer.currentItem).outputs.count, 0)
        XCTAssertTrue(PlayerLayerView(player: player).layer.sublayers?.first is AVPlayerLayer)
    }

    /// The control on the reason: protection alone, with no output, is no reason at all — a player
    /// that knows it has no output has nothing to report off.
    func testProtectionWithoutAnOutputReportsNoReason() {
        XCTAssertNil(player(output: nil, protected: true).frameOutputOffReason)
    }

    /// ADR-0022 rule 5's enforcement: the layer is a sample-buffer layer, and a Picture in Picture
    /// content source can be made from it — over the sample-buffer layer with core's playback
    /// delegate where the output runs, and over the `AVPlayerLayer` where there is none.
    func testAPictureInPictureContentSourceCanBeMadeFromEitherLayer() throws {
        let withOutput = PlayerLayerView(player: player(output: StandInOutput()))
        let without = PlayerLayerView(player: player(output: nil))

        let sampleBuffer = try XCTUnwrap(withOutput.makePictureInPictureContentSource())
        let playerLayer = try XCTUnwrap(without.makePictureInPictureContentSource())

        XCTAssertNotNil(sampleBuffer.sampleBufferDisplayLayer)
        XCTAssertNotNil(sampleBuffer.sampleBufferPlaybackDelegate)
        XCTAssertNotNil(playerLayer.playerLayer)
        XCTAssertNil(PlayerLayerView().makePictureInPictureContentSource())
    }
}

/// A frame output that is never called here: no item loads.
private struct StandInOutput: FrameOutput {
    func process(_ frame: CVPixelBuffer, presentationTime: CMTime) -> CVPixelBuffer { frame }
}

/// A `PlaybackDrm` that acquires nothing: still a declaration that the content is protected, which is all the frame output reads (ADR-0012 rule 13).
private struct StandInDrm: PlaybackDrm {}
