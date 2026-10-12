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
import XCTest

@testable import SuperPlayerMoQ

/// What the bridge does that the conformance suite does not name — the Kotlin
/// `MoqFrameSourceTest`, case for case: the index a frame is stamped with, the keyframe gate, what a
/// cancel closes, and that a failure anywhere reaches the sink. Over ``ScriptedMoqRelay``, so it
/// proves the bridge and nothing about MoQ.
final class MoqFrameSourceTests: XCTestCase {

    /// The declaration's order is the index's: video 0, audio 1, each frame stamped with its own
    /// track's position — read off the payload sizes, the one thing that tells the two scripts apart,
    /// so a pump that numbered its subscriptions the other way round fails here.
    func testFramesAreStampedWithTheIndexTheDeclarationGaveTheirTrack() {
        let relay = relay(bothKinds())
        let sink = RecordingSink()
        let source = MoqFrameSource(url: broadcast, relay: relay)

        source.subscribe(sink)
        XCTAssertTrue(sink.awaitFrames(Self.framesWatched))
        source.cancel()

        XCTAssertEqual(
            sink.tracks.map(\.codec),
            [DeclaredCatalogs.observedAvc3Codec, DeclaredCatalogs.synthesizedAacCodec])
        XCTAssertEqual(relay.subscribed, [Self.video, Self.audio])
        XCTAssertEqual(Set(sink.frames.map(\.trackIndex)), [0, 1])
        XCTAssertEqual(
            Set(sink.frames.filter { $0.trackIndex == 0 }.map(\.payload.count)),
            [annexBH264(true).count])
        XCTAssertEqual(
            Set(sink.frames.filter { $0.trackIndex == 1 }.map(\.payload.count)),
            [aacFrame(true).count])
        // The epoch reaches the seam unchanged: the engine above anchors the whole subscription
        // (ADR-0018 rule 9), so this product subtracts nothing.
        XCTAssertEqual(sink.frames.first { $0.trackIndex == 0 }?.timestampUs, transportEpochUs)
    }

    func testAnAudioOnlyBroadcastIndexesItsOneTrackZero() {
        let sink = subscribeWatching(
            relay(
                DeclaredCatalogs.catalog(
                    audio: [
                        Self.audio: DeclaredCatalogs.audio(DeclaredCatalogs.synthesizedAacCodec)
                    ]
                )))

        XCTAssertEqual(sink.tracks.map(\.codec), [DeclaredCatalogs.synthesizedAacCodec])
        XCTAssertEqual(Set(sink.frames.map(\.trackIndex)), [0])
    }

    /// Obligation 3 made true: the dependent frames before the first keyframe are dropped, and the
    /// first frame handed over is the keyframe itself rather than a dependent frame relabelled.
    func testATrackStartsAtItsFirstKeyframeAndTheFramesBeforeItAreDropped() throws {
        var video = ScriptedMoqTrack()
        video.leadingNonKeyFrames = Self.dependentFramesFirst
        let sink = subscribeWatching(relay(videoOnly(), video: video))

        let first = try XCTUnwrap(sink.frames.first)
        XCTAssertTrue(first.keyFrame)
        XCTAssertEqual(
            first.timestampUs,
            transportEpochUs + Int64(Self.dependentFramesFirst) * videoFrameDurationUs)
    }

    /// The control: a publisher already starting on a keyframe loses nothing to the gate.
    func testATrackAlreadyStartingOnAKeyframeLosesNoFrame() {
        let sink = subscribeWatching(relay(videoOnly()))

        XCTAssertEqual(sink.frames.first?.timestampUs, transportEpochUs)
    }

    /// Obligation 9 and what it closes: nothing arrives after `cancel()` returns, every stream and
    /// the session are closed, and neither terminal callback is made — a cancelled subscription is a
    /// viewer who left, not a stream that ended or failed.
    func testCancelClosesTheSessionAndEveryStreamAndStopsDelivery() {
        let relay = relay(bothKinds())
        let sink = RecordingSink()
        let source = MoqFrameSource(url: broadcast, relay: relay)

        source.subscribe(sink)
        XCTAssertTrue(sink.awaitFrames(Self.framesWatched))
        source.cancel()
        let afterCancel = sink.callbacks
        // A window in which a pump that outlived the cancel would deliver, many frame intervals
        // long; what is asserted is a count, never the window's length.
        Thread.sleep(forTimeInterval: Self.quietAfterCancel)

        XCTAssertEqual(sink.callbacks, afterCancel, "no callback arrives after cancel() returned")
        XCTAssertEqual(relay.streamsOpened, 2)
        XCTAssertEqual(relay.streamsClosed, relay.streamsOpened)
        XCTAssertEqual(relay.sessionsClosed, relay.sessionsOpened)
        XCTAssertNil(sink.terminal)
    }

    func testCancelIsSafeToCallTwice() {
        let relay = relay(bothKinds())
        let sink = RecordingSink()
        let source = MoqFrameSource(url: broadcast, relay: relay)

        source.subscribe(sink)
        XCTAssertTrue(sink.awaitFrames(1))
        source.cancel()
        source.cancel()

        XCTAssertEqual(relay.sessionsClosed, relay.sessionsOpened)
    }

    /// A cancel racing the connect thread leaks nothing: whatever was opened after it is closed by
    /// the registration that found it cancelled.
    func testCancelDuringConnectionLeaksNothing() {
        let relay = relay(bothKinds())
        let source = MoqFrameSource(url: broadcast, relay: relay)

        source.subscribe(RecordingSink())
        source.cancel()

        XCTAssertEqual(relay.sessionsClosed, relay.sessionsOpened)
        XCTAssertEqual(relay.streamsClosed, relay.streamsOpened)
    }

    /// Every point of failure, from the connection to a frame, ends the subscription on `onError`
    /// carrying what failed — never a throw on a thread nobody owns, never a silent stall.
    func testARelayThatFailsAnywhereReachesTheSinkAsOnError() {
        for point in ScriptedMoqRelay.FailurePoint.allCases {
            let sink = RecordingSink()
            let source = MoqFrameSource(
                url: broadcast, relay: ScriptedMoqRelay(catalog: bothKinds(), failsAt: point))

            source.subscribe(sink)
            XCTAssertTrue(sink.awaitTerminal(), "\(point)")
            source.cancel()

            XCTAssertEqual(sink.terminal, "onError", "\(point)")
            let message = sink.failure.map { "\($0)" } ?? ""
            XCTAssertTrue(message.contains(ScriptedMoqRelay.failureMessage), "\(point): \(message)")
        }
    }

    /// A broadcast whose tracks all end ends the subscription **once**, after its last track, with
    /// every scripted frame of both delivered first; a track ending earlier says nothing.
    func testABroadcastThatEndsEndsTheSubscriptionOnceAfterItsLastTrack() {
        var video = ScriptedMoqTrack()
        video.frames = Self.scriptedFrames
        var audio = audioTrack()
        audio.frames = Self.scriptedFrames / 2
        let relay = ScriptedMoqRelay(
            catalog: bothKinds(), tracks: [Self.video: video, Self.audio: audio])
        let sink = RecordingSink()
        let source = MoqFrameSource(url: broadcast, relay: relay)

        source.subscribe(sink)
        XCTAssertTrue(sink.awaitTerminal())
        let whenItEnded = sink.frames.count
        Thread.sleep(forTimeInterval: Self.quietAfterCancel)
        source.cancel()

        XCTAssertEqual(sink.terminal, "onEnded")
        XCTAssertEqual(sink.terminalCallbacks, 1)
        XCTAssertEqual(sink.frames.count, whenItEnded, "no frame after the end")
        XCTAssertEqual(whenItEnded, Self.scriptedFrames + Self.scriptedFrames / 2)
    }

    // MARK: - Fixtures

    private func subscribeWatching(_ relay: ScriptedMoqRelay) -> RecordingSink {
        let sink = RecordingSink()
        let source = MoqFrameSource(url: broadcast, relay: relay)
        source.subscribe(sink)
        XCTAssertTrue(sink.awaitFrames(Self.framesWatched))
        source.cancel()
        return sink
    }

    private func relay(_ catalog: MoqCatalog, video: ScriptedMoqTrack = ScriptedMoqTrack())
        -> ScriptedMoqRelay
    {
        ScriptedMoqRelay(catalog: catalog, tracks: [Self.video: video, Self.audio: audioTrack()])
    }

    private func audioTrack() -> ScriptedMoqTrack {
        var audio = ScriptedMoqTrack()
        audio.payload = aacFrame
        audio.frameDurationUs = audioFrameDurationUs
        return audio
    }

    private func bothKinds() -> MoqCatalog {
        DeclaredCatalogs.catalog(
            video: [
                Self.video: DeclaredCatalogs.video(
                    DeclaredCatalogs.observedAvc3Codec, description: nil)
            ],
            audio: [Self.audio: DeclaredCatalogs.audio(DeclaredCatalogs.synthesizedAacCodec)])
    }

    private func videoOnly() -> MoqCatalog {
        DeclaredCatalogs.catalog(video: [
            Self.video: DeclaredCatalogs.video(DeclaredCatalogs.observedAvc3Codec, description: nil)
        ])
    }

    private let broadcast = URL(string: "\(MoqFrameSource.scheme)://relay.example/anon/studio-a")!

    private static let video = "video"
    private static let audio = "audio"

    /// Enough frames to see both tracks interleave, few enough to arrive in well under a second.
    private static let framesWatched = 8

    /// A finite script's length, so the end can be reached.
    private static let scriptedFrames = 12

    /// How many dependent frames precede the first keyframe.
    private static let dependentFramesFirst = 3

    /// Twenty-five scripted frame intervals: a pump that outlived its cancel would deliver inside it.
    private static let quietAfterCancel: TimeInterval = 0.05
}
