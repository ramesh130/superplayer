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
import Foundation
import SuperPlayerCore
import SuperPlayerTelemetry
import SuperPlayerTestkit
import os

/// Plays one session of one arm and returns every event it produced — the Kotlin
/// `BenchmarkMatrixTest`'s `playStockSession` and `playSuperPlayerSession`, on real time.
///
/// **What keeps the arms comparable**, the Kotlin three on iOS's terms:
///
/// 1. **One transport.** Every session gets a fresh `LoopbackOrigin` from testkit's harness, serving
///    the scenario's content over the network profile's trace, paced at the socket from the moment
///    the session asks for its first media segment, so every arm meets the same stretch of the
///    trace at the same point in its session however long the host took to get it there (#474).
/// 2. **One reducer.** Every arm's events are reduced by `SessionMetrics`.
/// 3. **One derivation, checked.** Arm (c)'s events are `QoeCollector`'s and the stock arms'
///    ``StockTelemetry``'s, held to each other by the agreement test.
///
/// **One stand-in for a screen, on every arm**: an `AVPlayerItemVideoOutput` on the item. In
/// `xctest` no layer is ever in a window, and `AVPlayer` with nobody consuming its video was seen
/// on the iOS 27.1 simulator never to climb above its first rung, however fast the link
/// (`SyntheticVideoLadder.declaredHeightPx(bitrateBps:)`). An output nobody reads is the least that
/// makes the video consumed, and it is the same object on every arm, so it moves no comparison.
///
/// **Arm (c) runs on a lifecycle platform that does nothing**, the one place a `SuperPlayer` here
/// differs from an adopter's: its video holds the idle timer, and `xctest` has no `UIApplication`
/// (`ios/CLAUDE.md`, *The harness*). The audio session and idle timer it skips are not inputs to
/// anything measured, and the stock arms touch neither.
@MainActor
struct SessionRunner {

    /// The one content id every session reports, the Kotlin `CONTENT_ID`: a stock player has none,
    /// so it is the runner's, and arm (c)'s request carries the same one.
    static let contentId = "benchmark:ladder"

    /// How long to wait for a first frame or a startup failure before the wait is a defect of the
    /// run rather than a measurement — a ceiling, never a number reported. The slowest start this
    /// matrix can produce is the top rung's two-second segment, 1.1 MB, on 3G's megabit: nine
    /// seconds, so a minute is far past any start that is going to happen.
    static let startBound: Duration = .seconds(60)

    /// How long to wait for arm (c)'s asynchronous delivery to hand over `SessionEnded`, the Kotlin
    /// `DELIVERY_TIMEOUT_MS`: a session that misses it has no end and `SessionMetrics.usable`
    /// excludes it, counted.
    static let deliveryBound: Duration = .seconds(10)

    let key: CellKey

    /// Plays the session and returns its events in the order the sink received them.
    func play() async throws -> [TelemetryEvent] {
        let harness = PlaybackHarness()
        defer { harness.stop() }
        let origin = try await harness.serve(key.scenario.content, network: key.network.trace)
        let recorder = EventRecorder()
        if key.arm.isStock {
            try await playStock(origin.sourceURL, into: recorder, harness: harness)
        } else {
            try await playSuperPlayer(origin.sourceURL, into: recorder, harness: harness)
        }
        return recorder.events
    }

    /// Arms (a) and (b): a bare `AVPlayer`, measured by ``StockTelemetry``.
    private func playStock(_ url: URL, into recorder: EventRecorder, harness: PlaybackHarness)
        async throws
    {
        let telemetry = StockTelemetry { recorder.record($0) }
        // Intent first, at the tap, as arm (c)'s `declarePlaybackIntent()` is: both arms measure
        // time to first frame from the same boundary, the one the schema prefers.
        telemetry.declareIntent(monotonicTimeMs: Self.now())
        let item = AVPlayerItem(url: url)
        item.add(AVPlayerItemVideoOutput(pixelBufferAttributes: nil))
        if key.arm == .stockNaiveTuning {
            item.preferredForwardBufferDuration = Double(Arm.naiveForwardBufferMs) / 1_000
        }
        let player = AVPlayer(playerItem: item)
        telemetry.attach(to: player)
        telemetry.startSession(
            contentId: Self.contentId, sessionId: UUID().uuidString,
            profile: key.scenario.profile, decision: key.arm.stockDecision)
        if key.arm == .stockNaiveTuning {
            player.playImmediately(atRate: 1)
        } else {
            player.play()
        }
        try await playSpan(recorder, harness: harness)
        telemetry.endSession()
        telemetry.detach()
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    /// Arm (c): a `SuperPlayer` with the scenario's profile, measured by the shipped collector.
    private func playSuperPlayer(
        _ url: URL, into recorder: EventRecorder, harness: PlaybackHarness
    ) async throws {
        var configuration = SuperPlayer.Configuration()
        configuration.profile = key.scenario.profile
        configuration.telemetry = { QoeCollector(sink: recorder) }
        let player = SuperPlayer(
            configuration: configuration, lifecycle: SharedLifecycle(platform: QuietPlatform()))
        player.declarePlaybackIntent()
        player.setMediaRequest(MediaRequest(contentId: Self.contentId, sources: [url]))
        player.avPlayer.currentItem?.add(AVPlayerItemVideoOutput(pixelBufferAttributes: nil))
        player.avPlayer.play()
        try await playSpan(recorder, harness: harness)
        player.release()
        try? await harness.until("SessionEnded is delivered", timeout: Self.deliveryBound) {
            recorder.events.contains { if case .sessionEnded = $0 { true } else { false } }
        }
    }

    /// Waits for the first frame or a startup failure, then plays ``Scenario/playbackMs`` of real
    /// time past it. A session that never starts is ended at the bound, and reads as a startup
    /// failure or an exit before video start, never as a number.
    private func playSpan(_ recorder: EventRecorder, harness: PlaybackHarness) async throws {
        do {
            try await harness.until("a first frame or a failure", timeout: Self.startBound) {
                recorder.events.contains {
                    switch $0 {
                    case .firstFrameRendered, .startupFailed: true
                    default: false
                    }
                }
            }
        } catch is ConditionTimeout {
            return
        }
        try await Task.sleep(for: .milliseconds(Scenario.playbackMs))
    }

    /// `CLOCK_MONOTONIC` in milliseconds: the clock `QoeCollector` and ``StockTelemetry`` both read.
    static func now() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
    }
}

/// A sink both collectors write to — arm (c)'s on the delivery queue, the stock arms' on the main
/// actor — and the runner reads, so behind a lock.
final class EventRecorder: TelemetrySink {

    private let recorded = OSAllocatedUnfairLock(initialState: [TelemetryEvent]())

    func onEvent(_ event: TelemetryEvent) { record(event) }

    func record(_ event: TelemetryEvent) {
        recorded.withLock { $0.append(event) }
    }

    var events: [TelemetryEvent] { recorded.withLock { $0 } }
}

/// The lifecycle platform arm (c) runs on: one that does nothing (``SessionRunner``'s *Arm (c)*).
@MainActor
final class QuietPlatform: LifecyclePlatform {
    func activateAudioSession() {}
    func deactivateAudioSession() {}
    func setIdleTimerDisabled(_ disabled: Bool) {}
}
