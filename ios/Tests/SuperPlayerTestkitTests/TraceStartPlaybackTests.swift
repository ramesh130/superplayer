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
import SuperPlayerTestkit
import XCTest

/// A shaped link's clock starts at the session's first media segment, not at the origin's start:
/// how long the host takes to get a player that far is not network time (#474).
///
/// On a freshly booted simulator a stock `AVPlayer` was seen to take nine seconds between the
/// multivariant playlist and the media playlist, and only then to ask for a segment. With the
/// trace's clock at the origin's start, a link scripted fast for ten seconds and slow after it was
/// already slow by then, so whether a session ever met its fast stretch depended on how cold the host
/// was — which is every stall #474 recorded on CI's slower simulator. The Kotlin harness has the
/// meaning this restores: its time zero is the player built, on a fake clock that the host's own
/// work never moves (`docs/throughput-traces.md`, *On iOS*).
///
/// The race is lost on purpose rather than waited for: the media playlist is held for longer than
/// the link's fast stretch lasts, which is where the cold simulator spent its time.
///
/// The control is that the slow stretch still comes: the anchor moves the trace and does not make
/// it a fast link, so once the fast stretch has run out playback waits. Waits, rather than a
/// segment asked for again: on 27.1 a started session abandons the segment it cannot get, as one
/// that has not started does (`SlowLinkPlaybackTests`), while CI's 26.5 was seen to ask for no
/// segment again inside a minute, which reads as a wait that grows with what is buffered.
@MainActor
final class TraceStartPlaybackTests: XCTestCase {

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    func testASessionSlowToReachItsFirstSegmentStillMeetsTheLinksFirstStretch() async throws {
        let origin = try await harness.serve(
            .videoHls(segmentCount: 30),
            faults: FaultScript.Builder()
                .addLatencyMs(Self.hostStartMs, kind: .manifest, index: Self.mediaPlaylist)
                .build(),
            network: try Self.fastThenTooSlow())
        let player = AVPlayer(url: origin.sourceURL)
        player.currentItem?.add(AVPlayerItemVideoOutput(pixelBufferAttributes: nil))
        // Two segments ahead, so the fast stretch cannot fetch the whole stream and leave the slow
        // one nothing to carry.
        player.currentItem?.preferredForwardBufferDuration = Self.forwardBufferSeconds
        player.play()
        defer { player.replaceCurrentItem(with: nil) }

        try await harness.until("playback starts", timeout: StockPlayback.bound) {
            player.timeControlStatus == .playing && player.currentTime().seconds > 0
        }
        XCTAssertEqual(VideoPlayback.attempts(at: VideoPlayback.firstSegmentPath, origin), 1)

        // The control: the fast stretch ends a few seconds after the first segment, and the link
        // after it is too slow for the stream, so the buffer drains and the player waits.
        try await harness.until("playback waits on the slow link", timeout: StockPlayback.bound) {
            player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        }
    }

    /// The same session with nothing held: it passed before the anchor too, and is here so that the
    /// anchor is shown to cost a host that was quick nothing. What the anchor decides is where the
    /// trace's clock stands as each request arrives, so that is what is read, off the origin: every
    /// request up to and including the first segment answered on the fast stretch, and the first
    /// segment whole inside it.
    ///
    /// Not a start (#503): `AVPlayer` asks for one segment at a time and is ready only with two, so
    /// whether the second is asked for inside the fast stretch is how quickly the host turns the
    /// first one round. A host at a load of 160 did not, and its session met the slow stretch and
    /// never started. That is the host's, wherever the trace is anchored, and the sibling's start
    /// keeps ADR-0021 rule 4's argued margin for it.
    func testASessionQuickToReachItsFirstSegmentMeetsTheSameStretch() async throws {
        let origin = try await harness.serve(
            .videoHls(segmentCount: 30), network: try Self.fastThenTooSlow())
        let player = AVPlayer(url: origin.sourceURL)
        player.currentItem?.add(AVPlayerItemVideoOutput(pixelBufferAttributes: nil))
        player.play()
        defer { player.replaceCurrentItem(with: nil) }

        try await harness.until("the first segment is answered", timeout: StockPlayback.bound) {
            origin.responses().contains { $0.request.path == VideoPlayback.firstSegmentPath }
        }
        let responses = origin.responses()
        let first = try XCTUnwrap(
            responses.firstIndex { $0.request.path == VideoPlayback.firstSegmentPath })
        for response in responses[...first] {
            XCTAssertEqual(response.transport, Self.fastTransport, response.request.path)
        }
        let segment = responses[first]
        XCTAssertEqual(segment.status, 200)
        // Its first byte goes at the stretch's start, so its last is due this long into it.
        let deliveryMs = Int64(segment.bodyBytesSent) * 8 * 1_000 / Self.fastBandwidthBps
        XCTAssertLessThan(deliveryMs, Self.fastStretchMs)
        XCTAssertEqual(VideoPlayback.attempts(at: VideoPlayback.firstSegmentPath, origin), 1)
    }

    /// How long the media playlist is held: longer than the fast stretch, so a trace whose clock
    /// started at the origin is slow before any segment is asked for.
    private static let hostStartMs: Int64 = 2 * fastStretchMs

    /// How far ahead the item buffers: two of the stream's segments.
    private static let forwardBufferSeconds: TimeInterval = 4

    /// The media playlist's index among the manifests: the multivariant one is the first asked for.
    private static let mediaPlaylist = 1

    /// The fast stretch: four times the second or so a first frame took on the simulator once its
    /// first segment was asked for, and short enough that the held playlist outlasts it twice over
    /// (ADR-0021 rule 4's #474 addendum).
    private static let fastStretchMs: Int64 = 4_000

    /// The fast stretch's rate: enough that a segment of the stream is whole well inside the
    /// stretch, which the quick session's test asserts rather than assumes.
    private static let fastBandwidthBps: Int64 = 2_000_000

    /// The fast stretch's transport, the slow one being on another, so that a response says which
    /// stretch it arrived on. Reported and never switched (``NetworkTransport``), so no player sees
    /// a handover.
    private static let fastTransport: NetworkTransport = .wifi

    /// ``fastBandwidthBps`` for ``fastStretchMs``, then a quarter of the stream's own bitrate for good
    /// — a rate at which no segment of the stream arrives inside AVFoundation's wait.
    private static func fastThenTooSlow() throws -> ThroughputTrace {
        try ThroughputTrace.Builder()
            .add(
                durationMs: fastStretchMs, bandwidthBps: fastBandwidthBps, transport: fastTransport
            )
            .add(
                durationMs: 1_000, bandwidthBps: VideoPlayback.streamBitrateBps / 4,
                transport: .cellular
            )
            .holdAtEnd()
            .build()
    }
}
