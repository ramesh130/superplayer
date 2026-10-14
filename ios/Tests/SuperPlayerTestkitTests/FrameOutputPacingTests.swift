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

import QuartzCore
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

// `@testable` for `framePath`, the one reading past the facade, argued as
// `FrameOutputPaysNothingTests` argues its own: a display link's rate is visible to nothing but its
// target, so what it was asked for cannot be read through any public API. It is apart from
// `FrameOutputPlaybackTests` so that class stays on the public API alone.
@testable import SuperPlayerCore

/// The frame output's display link ticks at twice the content's frame rate once frames arrive
/// (#491): a link at the content's own rate loses a frame whenever the display's clock drifts across
/// a frame boundary, which an iPhone showed as one frame in about 7,200 (`DisplayLinkPace`).
///
/// **What is read is what the link was asked for, not what it ticked at.** The rate it gets is the
/// display's to grant — an iPhone holds every link at 60 Hz unless the app declares
/// `CADisableMinimumFrameDurationOnPhone` — and the simulator's display proves nothing about a
/// phone's, so the loss itself is counted on a device (`devicelab/lab run frames --platform ios`).
@MainActor
final class FrameOutputPacingTests: XCTestCase {

    /// The bound on every wait, only a ceiling: two frames arrive within a second of playing at 1×,
    /// and the rest is room for a loaded host.
    private let bound: Duration = .seconds(60)

    private let harness = PlaybackHarness()

    private var players: [SuperPlayer] = []

    override func tearDown() async throws {
        for player in players {
            player.avPlayer.pause()
            player.release()
        }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    /// The synthetic video is thirty frames a second, so the link is asked for sixty — and, the
    /// control, nothing is asked before the first two frames, which is the system's own default.
    func testTheLinkIsAskedForTwiceTheContentsFrameRate() async throws {
        let output = PassThroughFrameOutput()
        let origin = try await harness.serve(.videoHls())
        var configuration = SuperPlayer.Configuration()
        configuration.frameOutput = output
        let player = SuperPlayer(
            configuration: configuration, lifecycle: SharedLifecycle(platform: QuietPlatform()))
        players.append(player)
        let view = PlayerLayerView(player: player)
        view.frame = CGRect(x: 0, y: 0, width: 64, height: 64)
        player.setMediaRequest(MediaRequest(contentId: "video", sources: [origin.sourceURL]))

        let path = try XCTUnwrap(player.framePath)
        let before = try XCTUnwrap(path.displayLinkFrameRateRange)
        let unasked = CAFrameRateRange.default
        XCTAssertEqual(before.minimum, unasked.minimum, "nothing asked before any frame")
        XCTAssertEqual(before.maximum, unasked.maximum)
        XCTAssertEqual(before.preferred, unasked.preferred)

        player.avPlayer.play()
        try await harness.until("two frames reach the output", timeout: bound) {
            output.presentationTimes.count >= 2
        }

        let range = try XCTUnwrap(path.displayLinkFrameRateRange)
        let content = Float(SyntheticVideoHlsStream.framesPerSecond)
        XCTAssertEqual(range.preferred ?? 0, content * 2, accuracy: 0.01)
        XCTAssertEqual(range.maximum, content * 2, accuracy: 0.01)
        XCTAssertEqual(range.minimum, content, accuracy: 0.01)
    }
}
