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

/// A `RealtimePlayer` playing the shapes and the tracks #432 adds — the Kotlin
/// `RealtimePlaybackTest`'s second half: the video in both of ADR-0018 rule 4's shapes and at a
/// length width that is not four, audio beside it on one epoch and on two, the late and stalled
/// bounds, and audio alone as the control that keeps all of it from quietly requiring a picture.
///
/// **Sync is asserted over the presentation times each renderer was handed** (`@testable`, as
/// `RealtimePlayerTests` argues its readings): both renderers render against the one synchronizer
/// clock, so a sample's stamp *is* when it is rendered, and the simulator gives no per-sample
/// reading of a render — the video composites nothing offscreen and an audio renderer reports no
/// sample at all. What would break lip sync shows there exactly: a track rebased on its own first
/// frame moves its stamps by the publisher's skew, one left on another epoch by hours. Beside it,
/// the clock is read advancing, the video decodes with no failure, and the audio renderer reads as
/// rendering without error — which alone would not show the audio decoded, since an audio renderer
/// reports exactly that over a decoder that never started; `RealtimeAudioDecodingTests` is what
/// shows these packets under this description decode. This is one step short of Android's reading,
/// which records what each renderer accepted, and the simulator offers no nearer one.
///
/// **The bounds are forced unpaced**: they are media time against the leading track, so the verdict
/// a script reaches at speed is the one it reaches in real time, and the test waits for no clock.
/// What it waits for is the engine to have taken every frame the script sent, then one main-queue
/// turn, after which any failure has been reported — a bounded wait on a condition, never a sleep.
@MainActor
final class RealtimeTracksTests: XCTestCase {

    /// The bound on every wait, only a ceiling.
    private let bound: Duration = .seconds(30)

    /// One video frame's spacing, in microseconds: the slack two tracks interleaved by a publisher
    /// may legitimately differ by at any instant.
    private let videoFrameUs = Int64(1_000_000 / SyntheticVideoHlsStream.framesPerSecond)

    private let harness = PlaybackHarness()
    private var players: [RealtimePlayer] = []

    override func tearDown() async throws {
        for player in players { player.release() }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    // MARK: - The two shapes, decoded

    /// `avc3`: no record, Annex-B start codes, the parameter sets before every keyframe — converted
    /// here into a description and four-byte lengths, and decoded with no failure.
    func testAnInBandStreamIsConvertedAndDecodes() async throws {
        try await assertDecodes(.video(.avc3))
    }

    /// The record declaring two-byte lengths and every sample framed at two: CoreMedia reads the
    /// width out of the atom this side passed through, which a decoder fed noise would refuse frame
    /// by frame (`RealtimePlayerTests`' control).
    func testARecordDeclaringTwoByteLengthsDecodesItsTwoByteSamples() async throws {
        try await assertDecodes(.video(.avc1TwoByteLengths))
    }

    func testARecordOnASelfDescribingFourccFailsTheStreamNamingTheContradiction() async throws {
        let track = RealtimeTrack(
            codec: "avc3.42c01e", codecConfiguration: .record(Data(SyntheticH264.record)))
        let player = makePlayer { DeclaringFrameSource(tracks: [track]) }

        try player.setMediaRequest(request())

        try await harness.until("the stream fails", timeout: bound) { player.failure != nil }
        let refusal = try XCTUnwrap(player.failure as? MalformedRealtimeBitstreamError)
        XCTAssertEqual(refusal.codec, "avc3.42c01e")
        XCTAssertTrue(refusal.reason.contains("in band"), refusal.reason)
    }

    // MARK: - Two tracks, one clock

    /// Audio and video on one epoch, the audio starting 40 ms after the video as a publisher's skew:
    /// the 40 ms arrives as 40 ms, because both tracks are rebased by the one anchor.
    func testTwoTracksOnOneEpochKeepThePublishersSkewAndPlayInStep() async throws {
        let skewUs: Int64 = 40_000
        let (player, _) = try await playUntilBothRender([
            .video(), .audio(startsAtUs: skewUs),
        ])

        let (video, audio) = try presentations(of: player)
        XCTAssertEqual(video.firstUs, 0, "the first frame anchors the stream")
        XCTAssertEqual(audio.firstUs - video.firstUs, skewUs, "the skew survives, exactly")
        XCTAssertLessThanOrEqual(abs(audio.largestUs - video.largestUs), videoFrameUs + skewUs)
    }

    /// The audio's timeline an hour **ahead** of the video's — two origins, as RTP's random initial
    /// timestamps make routine (RFC 3550 §5.1). Unreconciled, every audio sample would be stamped an
    /// hour past the clock and wait for ever.
    func testTracksOnDifferentEpochsAreReconciledWhenTheAudiosIsAhead() async throws {
        try await assertReconciled(audioEpochOffsetUs: Self.oneHourUs)
    }

    /// The audio's timeline an hour **behind** — a different failure, not the same one mirrored:
    /// unreconciled, every audio sample is late on arrival and dropped, and the picture plays on in
    /// silence with nothing reporting it.
    func testTracksOnDifferentEpochsAreReconciledWhenTheAudiosIsBehind() async throws {
        try await assertReconciled(audioEpochOffsetUs: -Self.oneHourUs)
    }

    /// Audio alone: a legitimate configuration, and the control that keeps everything above from
    /// requiring a video track. The clock runs on the audio renderer and it renders.
    func testASourceCarryingAudioAlonePlays() async throws {
        let player = makePlayer { ScriptedFrameSource(tracks: [.audio()]) }

        try player.setMediaRequest(request())
        player.play()

        try await harness.until("the audio renders on an advancing clock", timeout: bound) {
            let audio = player.engine.trackRenderers.first as? AVSampleBufferAudioRenderer
            return audio?.status == .rendering && player.currentTime.seconds > 0.5
        }
        XCTAssertNil((player.engine.trackRenderers.first as? AVSampleBufferAudioRenderer)?.error)
        XCTAssertNil(player.failure)
        XCTAssertFalse(player.isRendering, "no picture is presented, and none is claimed")
    }

    // MARK: - The bounds

    /// A track that delivered and went quiet while the other kept arriving: one video frame against
    /// four seconds of audio passes the three-second stall bound, and the failure names the video.
    func testATrackThatStallsBehindTheOthersFailsTheStreamNamingIt() async throws {
        let failure = try await failureOf([
            .video(frames: 1), .audio(frames: ScriptedTrack.audioFrames(spanning: 4)),
        ])

        let stall = try XCTUnwrap(
            failure as? RealtimeTrackStalledError, "\(String(describing: failure))")
        XCTAssertEqual(stall.codec, "avc1.42c01e")
        XCTAssertEqual(stall.boundMs, 3_000)
        XCTAssertGreaterThan(stall.behindByMs, stall.boundMs)
    }

    /// A track declared and never delivered, while the other carries six seconds: past the
    /// five-second late-start bound, which is the longer one because connecting is slower than
    /// continuing.
    func testATrackThatNeverStartsFailsTheStreamPastTheLateStartBound() async throws {
        let failure = try await failureOf([
            .video(frames: 0), .audio(frames: ScriptedTrack.audioFrames(spanning: 6)),
        ])

        let stall = try XCTUnwrap(
            failure as? RealtimeTrackStalledError, "\(String(describing: failure))")
        XCTAssertEqual(stall.codec, "avc1.42c01e")
        XCTAssertEqual(stall.boundMs, 5_000)
        XCTAssertGreaterThan(stall.behindByMs, stall.boundMs)
    }

    /// The late-start control: four seconds of audio while the video has sent nothing is inside the
    /// five-second allowance — longer than the stall bound, so this also shows a track that never
    /// started is not judged by the shorter one.
    func testATrackStillConnectingInsideTheBoundFailsNothing() async throws {
        let failure = try await failureOf([
            .video(frames: 0), .audio(frames: ScriptedTrack.audioFrames(spanning: 4)),
        ])
        XCTAssertNil(failure)
    }

    /// The stall bound pinned from below: one video frame and then nothing, against two and a half
    /// seconds of audio, is inside the three seconds — so a bound shorter than it argues fails here.
    func testATrackQuietForLessThanTheStallBoundFailsNothing() async throws {
        let failure = try await failureOf([
            .video(frames: 1), .audio(frames: ScriptedTrack.audioFrames(spanning: 2.5)),
        ])
        XCTAssertNil(failure)
    }

    /// The stall control: every track going quiet together is a live stream that went quiet — the
    /// leader stops too, nobody falls behind — so ten seconds of both, then nothing, fails nothing.
    func testEveryTrackGoingQuietTogetherFailsNothing() async throws {
        let failure = try await failureOf([
            .video(frames: 10 * SyntheticVideoHlsStream.framesPerSecond),
            .audio(frames: ScriptedTrack.audioFrames(spanning: 10)),
        ])
        XCTAssertNil(failure)
    }

    // MARK: - Helpers

    private static let url = URL(string: "scripted://relay.example/studio-a")!
    private static let oneHourUs: Int64 = 3_600_000_000

    private func request() -> MediaRequest {
        MediaRequest(contentId: "studio-a", sources: [Self.url])
    }

    private func makePlayer(_ source: @escaping @Sendable () -> any FrameSource) -> RealtimePlayer {
        let player = RealtimePlayer(
            configuration: .init(
                sources: Realtime.transport(
                    scheme: "scripted", factory: ScriptedTransport { _ in source() })),
            lifecycle: SharedLifecycle(platform: QuietPlatform()))
        players.append(player)
        return player
    }

    /// Plays `track` alone and holds it to `RealtimePlayerTests`' three readings of a decode: the
    /// clock advancing, the layer rendering, and not one frame failing to decode.
    private func assertDecodes(_ track: ScriptedTrack) async throws {
        let source = ScriptedFrameSource(tracks: [track])
        let player = makePlayer { source }
        let decodeFailures = DecodeFailures(of: player)

        try player.setMediaRequest(request())
        player.play()

        try await harness.until("the layer renders with the clock running", timeout: bound) {
            player.isRendering && player.currentTime.seconds > 0
        }
        try await harness.until("a second's frames past the preroll", timeout: bound) {
            source.deliveredFrames > SyntheticVideoHlsStream.framesPerSecond * 2
        }
        XCTAssertEqual(decodeFailures.count, 0, "every frame enqueued decoded")
        XCTAssertNil(player.engine.videoRenderer.error)
        XCTAssertNil(player.failure)
    }

    /// Plays video and audio until the clock is a second in with the picture rendering and the
    /// audio rendering, every frame decoded.
    private func playUntilBothRender(_ tracks: [ScriptedTrack]) async throws
        -> (RealtimePlayer, ScriptedFrameSource)
    {
        let source = ScriptedFrameSource(tracks: tracks)
        let player = makePlayer { source }
        let decodeFailures = DecodeFailures(of: player)

        try player.setMediaRequest(request())
        player.play()

        try await harness.until("both tracks render a second in", timeout: bound) {
            let audio = player.engine.trackRenderers.last as? AVSampleBufferAudioRenderer
            return player.isRendering && audio?.status == .rendering
                && player.currentTime.seconds > 1
        }
        XCTAssertEqual(decodeFailures.count, 0)
        XCTAssertNil((player.engine.trackRenderers.last as? AVSampleBufferAudioRenderer)?.error)
        XCTAssertNil(player.failure)
        return (player, source)
    }

    private func assertReconciled(audioEpochOffsetUs: Int64) async throws {
        let (player, _) = try await playUntilBothRender([
            .video(), .audio(epochUs: ScriptedFrameSource.epochUs + audioEpochOffsetUs),
        ])

        let (video, audio) = try presentations(of: player)
        // Placed where the stream had got to when its first frame arrived — the video's first,
        // since a publisher's tracks start together here — rather than an hour away from it.
        XCTAssertLessThanOrEqual(abs(audio.firstUs - video.firstUs), videoFrameUs)
        XCTAssertLessThanOrEqual(abs(audio.largestUs - video.largestUs), videoFrameUs)
    }

    /// The video's and the audio's enqueued presentation times, in that order.
    private func presentations(of player: RealtimePlayer) throws
        -> (video: (firstUs: Int64, largestUs: Int64), audio: (firstUs: Int64, largestUs: Int64))
    {
        let both = player.engine.enqueuedPresentations
        XCTAssertEqual(both.count, 2)
        return (try XCTUnwrap(both.first ?? nil), try XCTUnwrap(both.last ?? nil))
    }

    /// Delivers `tracks` unpaced, waits until the engine has taken every frame and any failure has
    /// been reported, and answers that failure — nil where the stream did not fail.
    private func failureOf(_ tracks: [ScriptedTrack]) async throws -> (any Error)? {
        let total = tracks.reduce(0) { $0 + ($1.frames ?? 0) }
        let source = ScriptedFrameSource(tracks: tracks, paced: false)
        let player = makePlayer { source }

        try player.setMediaRequest(request())

        try await harness.until("every frame is delivered, or the stream failed", timeout: bound) {
            source.deliveredFrames == total || player.failure != nil
        }
        // The engine takes frames on a serial queue in the order they were sent, so a read through
        // it returns once every frame before it was taken; and it reports on the main queue in
        // order, so one more main-queue turn after that has heard any failure those frames caused.
        _ = player.engine.enqueuedPresentations
        await withCheckedContinuation { continuation in
            DispatchQueue.main.async { continuation.resume() }
        }
        return player.failure
    }
}
