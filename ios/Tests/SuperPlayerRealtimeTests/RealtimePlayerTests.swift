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

@testable import SuperPlayerRealtime

/// `RealtimePlayer` driven by a scripted transport on the simulator (#431): frames pushed through
/// the seam reach the display layer, which decodes and presents them against a clock that starts at
/// the first frame and advances; a request the player cannot honour is refused typed and leaves it
/// as it was; and a track the table cannot map fails the stream naming its codec.
///
/// **In real time, with bounded waits** (ADR-0021 rule 4): nothing asserts a duration. "Rendering"
/// is read three ways, each necessary and none sufficient alone — the clock moving, the layer's
/// renderer `rendering` without a failure, and **no decode failure** for frames a control shows the
/// renderer does decode: the same framing with noise for slices fails frame by frame. What the
/// screen shows is not read: the simulator decodes offscreen but composites nothing there, so
/// `copyDisplayedPixelBuffer()` answers nil with or without a window, and a device's screen is
/// #438's.
///
/// **`@testable`, for readings with no public form** (ADR-0021 rule 1, `docs/testing.md`'s *The iOS
/// harness*): the display layer's renderer, whose decode-failure notification and error are the
/// only reading of a decode the simulator gives; and the presentation time the first frame was
/// stamped with, the subtraction made visible. A consumer has no use for either — a decode failure
/// shows on the screen — so neither is published. Everything else here is the public player.
///
/// **The synchronizer advances with no audio and no window**: #405 found that a video-only
/// `AVPlayerItem` never advances in an `xctest` host, which is why `SyntheticVideoHlsStream` carries
/// silent audio. A render synchronizer with only a video renderer added is driven by its own clock
/// and does advance — the first test is where that is established.
@MainActor
final class RealtimePlayerTests: XCTestCase {

    /// The bound on every wait, only a ceiling.
    private let bound: Duration = .seconds(30)

    private let harness = PlaybackHarness()
    private var players: [RealtimePlayer] = []

    override func tearDown() async throws {
        for player in players { player.release() }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    // MARK: - Rendering

    func testTheFakeDrivesThePlayerToRenderingDecodedFramesWithAnAdvancingClock() async throws {
        let source = ScriptedFrameSource()
        let transport = ScriptedTransport { _ in source }
        let player = makePlayer(transport)
        let decodeFailures = DecodeFailures(of: player)

        try player.setMediaRequest(request())
        player.play()

        try await harness.until("the layer renders with the clock running", timeout: bound) {
            player.isRendering && player.currentTime.seconds > 0
        }
        let first = player.currentTime.seconds
        try await harness.until("the clock advances", timeout: bound) {
            player.currentTime.seconds > first
        }

        // A second's frames past the preroll, so the count below covers paced frames too.
        try await harness.until("frames keep arriving", timeout: bound) {
            source.deliveredFrames > SyntheticVideoHlsStream.framesPerSecond
        }
        XCTAssertEqual(decodeFailures.count, 0, "every frame enqueued decoded")
        XCTAssertNil(player.engine.videoRenderer.error)
        XCTAssertNil(player.failure)
        // The first frame's timestamp, two hours in, is subtracted: it was stamped at the stream's
        // zero, where the clock starts, rather than two hours ahead of it.
        XCTAssertEqual(player.engine.firstPresentationTime, .zero)
        XCTAssertEqual(transport.opened.map(\.url), [Self.url])
    }

    /// The control for the decode: the same framing with noise for slices fails to decode, frame by
    /// frame, so a stream that reports no failure was decoded rather than ignored. The renderer
    /// decodes with no window — what the simulator does not do offscreen is composite, so the
    /// displayed picture cannot be read back here (`copyDisplayedPixelBuffer` answers nil either way).
    func testFramesWhoseSlicesAreNoiseFailToDecodeOneByOne() async throws {
        let player = makePlayer(
            ScriptedTransport { _ in ScriptedFrameSource(corruptsSlices: true) })
        let decodeFailures = DecodeFailures(of: player)

        try player.setMediaRequest(request())
        player.play()

        try await harness.until("the layer reports failures to decode", timeout: bound) {
            decodeFailures.count > SyntheticVideoHlsStream.framesPerSecond / 2
        }
    }

    func testAPlayerNotAskedToPlayDecodesNothingOnAClockThatNeverStarts() async throws {
        let source = ScriptedFrameSource()
        let player = makePlayer(ScriptedTransport { _ in source })

        try player.setMediaRequest(request())
        try await harness.until("frames have been delivered", timeout: bound) {
            source.deliveredFrames > ScriptedFrameSource.prerollFrames
        }

        XCTAssertFalse(player.isRendering)
        XCTAssertEqual(player.currentTime.seconds, 0)
    }

    func testTheLibrarysViewHostsTheRealtimePlayersDisplayLayer() throws {
        let player = makePlayer(ScriptedTransport())

        let view = PlayerLayerView(player: player)

        XCTAssertTrue(view.layer.sublayers?.contains(player.engine.displayLayer) ?? false)
        XCTAssertTrue(view.realtimePlayer === player)
        XCTAssertNil(view.player, "a view of a RealtimePlayer shows no SuperPlayer")
        XCTAssertEqual(player.frameOutputOffReason, .realtime)
    }

    // MARK: - Refusals

    func testAStartPositionThatIsNotTheEdgeIsRefusedTypedAndLeavesThePlayerAsItWas() async throws {
        let transport = ScriptedTransport()
        let player = makePlayer(transport)
        try player.setMediaRequest(request(contentId: "playing"))
        let playing = try XCTUnwrap(transport.opened.first?.source as? ScriptedFrameSource)

        let refused: [(MediaRequest.StartPosition, String)] = [
            (.at(positionMs: 12_000), "at"), (.resumeFromLastKnown, "resumeFromLastKnown"),
        ]
        for (start, name) in refused {
            XCTAssertThrowsError(
                try player.setMediaRequest(request(contentId: "seek", start: start))
            ) {
                XCTAssertEqual(
                    $0 as? RealtimeStreamNotSeekableError,
                    RealtimeStreamNotSeekableError(scheme: "scripted", startPosition: name))
            }
        }

        XCTAssertEqual(player.currentContentId, "playing", "the refused request adopted nothing")
        XCTAssertEqual(transport.opened.count, 1, "no source was opened for a refused request")
        XCTAssertEqual(playing.cancelCount, 0, "the stream playing was not cancelled")
        XCTAssertNil(player.failure)
    }

    func testTheEdgeIsAcceptedAndReplacesTheStreamPlaying() throws {
        let transport = ScriptedTransport()
        let player = makePlayer(transport)
        try player.setMediaRequest(request(contentId: "first"))

        try player.setMediaRequest(request(contentId: "second", start: .beginning))

        XCTAssertEqual(player.currentContentId, "second")
        XCTAssertEqual(transport.opened.count, 2)
        let first = try XCTUnwrap(transport.opened.first?.source as? ScriptedFrameSource)
        XCTAssertEqual(first.cancelCount, 1, "the replaced stream was cancelled, once")
    }

    func testASchemeNoTransportAnswersIsRefusedNamingTheOtherPlayerAndTheSchemeIsCaseBlind() throws
    {
        let transport = ScriptedTransport()
        let player = makePlayer(transport)

        XCTAssertThrowsError(
            try player.setMediaRequest(
                MediaRequest(
                    contentId: "vod", sources: [URL(string: "https://cdn.example/a.m3u8")!]))
        ) { error in
            let refusal = error as? UnansweredRealtimeSchemeError
            XCTAssertEqual(refusal?.scheme, "https")
            XCTAssertTrue(refusal?.description.contains("SuperPlayer") ?? false)
        }
        XCTAssertTrue(transport.opened.isEmpty)
        XCTAssertNil(player.currentContentId)

        let shouting = URL(string: "SCRIPTED://relay.example/studio-a")!
        try player.setMediaRequest(MediaRequest(contentId: "live", sources: [shouting]))
        XCTAssertEqual(transport.opened.map(\.url), [shouting])
    }

    func testATrackTheTableCannotMapFailsTheStreamNamingItsCodec() async throws {
        let player = makePlayer(
            ScriptedTransport { _ in DeclaringFrameSource(tracks: [RealtimeTrack(codec: "theora")])
            })

        try player.setMediaRequest(request())

        try await harness.until("the stream fails", timeout: bound) { player.failure != nil }
        XCTAssertEqual(player.failure as? UnsupportedRealtimeCodecError, .init(codec: "theora"))
    }

    func testReleaseCancelsTheSourceOnceAndStopsTheClock() async throws {
        let source = ScriptedFrameSource()
        let player = makePlayer(ScriptedTransport { _ in source })
        try player.setMediaRequest(request())
        player.play()
        try await harness.until("the clock runs", timeout: bound) { player.isRendering }

        player.release()
        player.release()

        XCTAssertEqual(source.cancelCount, 1)
        XCTAssertFalse(player.isRendering)
        XCTAssertNil(player.currentContentId)
    }

    // MARK: - Helpers

    private static let url = URL(string: "scripted://relay.example/studio-a")!

    private func request(
        contentId: String = "studio-a", start: MediaRequest.StartPosition = .beginning
    )
        -> MediaRequest
    {
        MediaRequest(contentId: contentId, sources: [Self.url], startPosition: start)
    }

    private func makePlayer(_ transport: ScriptedTransport) -> RealtimePlayer {
        let player = RealtimePlayer(
            configuration: .init(
                sources: Realtime.transport(scheme: "scripted", factory: transport)),
            lifecycle: SharedLifecycle(platform: QuietPlatform()))
        players.append(player)
        return player
    }
}

/// Counts the display layer's decode failures — the renderer's own notification, posted per frame
/// it could not decode, which is the one reading of a decode the simulator gives without a screen.
@MainActor
private final class DecodeFailures {
    private let counter = Counter()
    /// `nonisolated(unsafe)` for `deinit`, which is not isolated on the iOS 17 floor; it is written
    /// once in `init` and never again.
    nonisolated(unsafe) private let token: any NSObjectProtocol

    init(of player: RealtimePlayer) {
        let counter = self.counter
        token = NotificationCenter.default.addObserver(
            forName: AVSampleBufferVideoRenderer.didFailToDecodeNotification,
            object: player.engine.videoRenderer, queue: nil
        ) { _ in counter.increment() }
    }

    var count: Int { counter.value }

    deinit { NotificationCenter.default.removeObserver(token) }

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func increment() { lock.withLock { count += 1 } }
        var value: Int { lock.withLock { count } }
    }
}

/// A lifecycle platform that does nothing: an `xctest` host has no `UIApplication` whose idle timer
/// a video could hold (#402).
private final class QuietPlatform: LifecyclePlatform {
    func activateAudioSession() {}
    func deactivateAudioSession() {}
    func setIdleTimerDisabled(_ disabled: Bool) {}
}
