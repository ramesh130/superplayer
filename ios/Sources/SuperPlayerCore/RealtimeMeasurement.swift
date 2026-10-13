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

// What a telemetry collector reads of a `RealtimePlayer` (#461) — the realtime half of ADR-0008
// rule 2's split. A `SuperPlayer` is measured by a collector observing its `AVPlayer`, whose
// `timeControlStatus`, item and access log say what happened; a realtime stream has no `AVPlayer`,
// and the one thing that knows where it is — its clock against what each renderer holds — is the
// engine under it. So the engine reports a *state*, as `AVPlayer` does, and the collector keeps the
// derivation: which state change is a first frame, which a rebuffer, which a failure before or
// after one. Core declares both halves because neither product may name the other: telemetry is
// phase 2 and `SuperPlayerRealtime` phase 13 (`docs/modules.md`).

/// Where a realtime stream's playback is — the counterpart of `AVPlayer.timeControlStatus`, read by
/// a collector exactly as that is (`QoeCollector`).
package enum RealtimePlaybackState: Sendable, Equatable {

    /// Not asked to play, or nothing left to play: no stream, or one that ended or failed.
    case paused

    /// Asked to play, before the clock has started at the first frame — start-up time, which is
    /// never a rebuffer.
    case waitingForFirstFrame

    /// The clock started and runs, and every renderer still holds something to present.
    case playing

    /// The clock has passed the end of everything a renderer holds — a declared track that has
    /// delivered ran dry — so that renderer presents nothing new: a held picture, or silence. The
    /// counterpart of `AVPlayer` waiting `.toMinimizeStalls`, and a rebuffer to a collector.
    case starved
}

/// A `RealtimePlayer` as a collector reads it. Main-actor isolated, as the player is.
@MainActor
package protocol RealtimeMeasurable: AnyObject {

    /// Where playback is now.
    var playbackState: RealtimePlaybackState { get }

    /// Why the stream stopped, if it failed — the player's public `failure`.
    var failure: (any Error)? { get }

    /// Whether the stream declared a picture: dropped frames are a video renderer's, as on Android.
    var hasVideo: Bool { get }

    /// Where the stream has got to on its own clock: zero at the first frame.
    var currentTime: CMTime { get }

    /// Media milliseconds buffered ahead of the clock: the least of what the declared tracks that
    /// have delivered hold past it, never negative — the Kotlin period's `getBufferedPositionUs`,
    /// the most conservative of the queues.
    var bufferedAheadMs: Int64 { get }

    /// The renderer the stream's video is enqueued to, whose performance metrics count the frames
    /// it dropped (iOS 17.4 and later).
    var videoRenderer: AVSampleBufferVideoRenderer { get }

    /// Registers `changed` for every change to the readings above, called on the main actor in the
    /// order the changes happened; the registration lives as long as the object returned.
    func observeMeasurement(_ changed: @escaping @MainActor () -> Void) -> AnyObject
}

/// A collector that measures a realtime stream too: `SuperPlayerTelemetry`'s `QoeCollector`.
///
/// **Package-scoped rather than a requirement of ``TelemetryCollector``**, because what it is handed
/// is a package seam: a public requirement could not name ``RealtimeMeasurable``, and making that
/// public would publish an engine's internals as API. So a collector of a consumer's own handed to
/// a `RealtimePlayer` is told the session's edges — ``TelemetryCollector/startSession(contentId:sessionId:)``,
/// ``TelemetryCollector/endSession()`` and the declared intent — and observes nothing, which is what
/// its `attach(to:)`, taking a `SuperPlayer`, could observe of a realtime stream anyway.
@MainActor
package protocol RealtimeMeasuringCollector: TelemetryCollector {

    /// Binds this collector to `engine` for its lifetime, in place of ``TelemetryCollector/attach(to:)``.
    func attach(toRealtime engine: any RealtimeMeasurable)
}
