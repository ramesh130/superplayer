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
import SuperPlayerCore
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// The frame output on a playing `SuperPlayer` (#405, ADR-0022): a pass-through receives decoded
/// frames in presentation order, off the main thread, while playback advances, and its frames reach
/// the sample-buffer layer; a seek flushes and the frames follow the new position; and a player
/// configured for protected content plays to its end with the output off and never called.
///
/// **What is counted is frames against what played, as a bound** (ADR-0021 rule 4): every frame is
/// one the stream holds, none arrives twice, and at least half of those that played arrive. Half,
/// because the simulator's display link and main actor are the host's — a busy CI host can let a
/// frame come and go between two refreshes, which is the drop ADR-0022 rule 10 says telemetry counts
/// — while the stream is thirty frames a second under a display that refreshes faster, so a frame
/// is missed only when the host stalls. That a pass-through drops **no** frame `AVPlayerLayer` would
/// have shown is a claim about a phone's display timing, and it is #435's, on a device.
///
/// **What the screen shows is not read**: the view is never in a window, and the simulator's
/// compositor proves nothing about a device's. What is read is that the layer took the frames —
/// its renderer `rendering`, with no error — which is the last thing core does with one.
///
/// Every player here reaches its lifecycle through a platform that does nothing, because a video
/// item holds the idle timer and an `xctest` host has no `UIApplication` to hold it on (#402).
@MainActor
final class FrameOutputPlaybackTests: XCTestCase {

    /// The bound on every wait, only a ceiling: the stream plays at 1× on a loaded host.
    private let bound: Duration = .seconds(60)

    private let harness = PlaybackHarness()

    private var players: [SuperPlayer] = []

    /// Every player is paused, emptied and released before the origin stops, so no load is left in
    /// flight for AVFoundation to wind down after the test.
    override func tearDown() async throws {
        for player in players {
            player.avPlayer.pause()
            player.release()
        }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    func testAPassThroughReceivesTheFramesThatPlayInOrderOffTheMainThread() async throws {
        let output = PassThroughFrameOutput()
        let (player, view) = try await playing(output: output)
        let durationSeconds = Double(SyntheticVideoHlsStream.durationMs(segmentCount: 4)) / 1_000

        try await harness.until("the item plays to its end", timeout: bound) {
            player.avPlayer.currentTime().seconds >= durationSeconds - frameInterval
        }

        let times = output.presentationTimes.map(\.seconds)
        let frames = framesIn(durationSeconds)
        XCTAssertEqual(times, times.sorted(), "in presentation order")
        XCTAssertEqual(Set(times).count, times.count, "no frame handed over twice")
        for time in times {
            let index = (time / frameInterval).rounded()
            XCTAssertEqual(time, index * frameInterval, accuracy: 1e-6, "a frame of the stream")
            XCTAssertTrue((0..<Double(frames)).contains(index), "\(time) is inside the stream")
        }
        XCTAssertGreaterThanOrEqual(times.count, frames / 2, "half of \(frames) that played")
        XCTAssertLessThanOrEqual(times.count, frames)
        XCTAssertEqual(output.framesOnMainThread, 0)

        let layer = try XCTUnwrap(view.layer.sublayers?.first as? AVSampleBufferDisplayLayer)
        XCTAssertEqual(layer.sampleBufferRenderer.status, .rendering)
        XCTAssertNil(layer.sampleBufferRenderer.error)
        XCTAssertNil(player.avPlayer.currentItem?.error)
        XCTAssertNil(player.frameOutputOffReason)
    }

    /// After a seek back, frames go on arriving and they are the new position's — from where it was
    /// sought to, in order again. A frame of the old position copied while the seek was in flight
    /// may still be handed over after it, so the reading starts at the first frame before two
    /// seconds. What the flush keeps off the *screen* is not readable here (docs/testing.md).
    func testAfterASeekBackTheFramesFollowTheNewPosition() async throws {
        let output = PassThroughFrameOutput()
        let (player, _) = try await playing(output: output)
        try await harness.until("a frame past three seconds arrives", timeout: bound) {
            (output.presentationTimes.last?.seconds ?? 0) > 3
        }

        let before = output.presentationTimes.count
        let target = 0.5
        _ = await player.avPlayer.seek(
            to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero,
            toleranceAfter: .zero)
        try await harness.until("a frame past two seconds arrives again", timeout: bound) {
            output.presentationTimes.dropFirst(before).drop { $0.seconds >= 2 }.contains {
                $0.seconds > 2
            }
        }

        let handed = output.presentationTimes.dropFirst(before).map(\.seconds)
        let after = Array(handed.drop { $0 >= 2 })
        let first = try XCTUnwrap(after.first)
        XCTAssertGreaterThanOrEqual(first, target - frameInterval)
        XCTAssertEqual(after, after.sorted())
    }

    /// ADR-0022 rule 6 through #422's stub: a player configured for protected content and with a
    /// frame output plays its item to the end, exactly as without the output, and the output is
    /// never called — the reason is a reading, and nothing failed.
    func testAProtectedPlayerPlaysToItsEndWithTheOutputOffAndNeverCalled() async throws {
        let output = PassThroughFrameOutput()
        let (player, view) = try await playing(output: output, protected: true)
        let durationSeconds = Double(SyntheticVideoHlsStream.durationMs(segmentCount: 4)) / 1_000

        try await harness.until("the item plays to its end", timeout: bound) {
            player.avPlayer.currentTime().seconds >= durationSeconds - frameInterval
        }

        XCTAssertEqual(player.frameOutputOffReason, .protectedContent)
        XCTAssertEqual(output.presentationTimes.count, 0)
        XCTAssertTrue(view.layer.sublayers?.first is AVPlayerLayer)
        XCTAssertNil(player.avPlayer.currentItem?.error)
    }

    private let frameInterval = 1.0 / Double(SyntheticVideoHlsStream.framesPerSecond)

    private func framesIn(_ seconds: Double) -> Int {
        Int((seconds / frameInterval).rounded())
    }

    /// A player configured with `output`, and protection when `protected`, playing the synthetic
    /// video from a fresh origin and shown in a view, once its position has started to move.
    private func playing(output: PassThroughFrameOutput, protected: Bool = false) async throws
        -> (SuperPlayer, PlayerLayerView)
    {
        let origin = try await harness.serve(.videoHls())
        var configuration = SuperPlayer.Configuration()
        configuration.frameOutput = output
        if protected { configuration.protection = StandInProtection() }
        let player = SuperPlayer(
            configuration: configuration, lifecycle: SharedLifecycle(platform: QuietPlatform()))
        players.append(player)
        let view = PlayerLayerView(player: player)
        view.frame = CGRect(x: 0, y: 0, width: 64, height: 64)
        player.setMediaRequest(MediaRequest(contentId: "video", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await harness.until("the position starts to move", timeout: bound) {
            player.avPlayer.currentTime().seconds > 0
        }
        return (player, view)
    }
}

/// What #422's configuration will be, as far as the frame output can tell.
private struct StandInProtection: PlaybackProtection {}

/// A lifecycle platform that does nothing: the session is left alone and the idle timer unheld.
private final class QuietPlatform: LifecyclePlatform {
    func activateAudioSession() {}
    func deactivateAudioSession() {}
    func setIdleTimerDisabled(_ disabled: Bool) {}
}
