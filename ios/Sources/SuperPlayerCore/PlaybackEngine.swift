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

/// The one internal engine protocol both player types are reached through (ADR-0020 rule 11): what
/// the library's own view and lifecycle need of a player, whichever engine is under it.
///
/// **This is where the "one player" claim splits.** On Android a realtime URI reaches the same
/// `SuperPlayer` through `TransferChain`'s scheme dispatch (ADR-0018 rule 12); an `AVPlayer` cannot
/// take pushed frames, so on Apple platforms realtime is a second public type, `RealtimePlayer`, in
/// `SuperPlayerRealtime`. Core cannot name that type — it is phase 13 and core is phase 1 — so what
/// the two share is reached through this `package` protocol rather than through either class:
/// ``PlayerLayerView`` and ``PlayerView`` host whatever layer an engine shows, and
/// `SuperPlayerRealtime` adds the public initializers that take its player.
///
/// What is shared today is the display and the process's lifecycle (``SharedLifecycle``, which
/// `RealtimePlayer` states its needs to as a `SuperPlayer` does). The telemetry and session halves
/// are not yet: ``TelemetryCollector/attach(to:)`` and ``PlaybackSession`` name ``SuperPlayer``, and
/// what either would observe on a synchronizer is a derivation of its own — `docs/testing.md`'s
/// *A realtime transport on iOS* names the gap.
@MainActor
package protocol PlaybackEngine: AnyObject {

    /// The layer showing this engine's picture, made for a view that hosts it: an engine that owns
    /// its layer answers with that one, so it has one superlayer and moves to whichever view hosts
    /// it last.
    func makeHostedLayer() -> CALayer

    /// A Picture in Picture content source over `layer`, the one ``makeHostedLayer()`` answered, or
    /// nil where this engine offers none.
    func pictureInPictureContentSource(for layer: CALayer)
        -> AVPictureInPictureController.ContentSource?
}

extension SuperPlayer: PlaybackEngine {

    /// The frame output's sample-buffer layer on a player whose output runs, else an
    /// `AVPlayerLayer` over ``avPlayer`` (ADR-0022 rules 5 and 7).
    package func makeHostedLayer() -> CALayer {
        framePath?.layer ?? AVPlayerLayer(player: avPlayer)
    }

    package func pictureInPictureContentSource(for layer: CALayer)
        -> AVPictureInPictureController.ContentSource?
    {
        switch layer {
        case let layer as AVPlayerLayer:
            return AVPictureInPictureController.ContentSource(playerLayer: layer)
        case let layer as AVSampleBufferDisplayLayer:
            guard let playback = framePath?.pictureInPicturePlayback else { return nil }
            return AVPictureInPictureController.ContentSource(
                sampleBufferDisplayLayer: layer, playbackDelegate: playback)
        default:
            return nil
        }
    }
}
