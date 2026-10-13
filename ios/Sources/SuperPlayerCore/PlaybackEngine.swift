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
import UIKit

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
/// What is shared is the display, Picture in Picture, and a ``PlaybackSession`` (#461), each through
/// a requirement below. Two more shared pieces are reached through narrower protocols of their own,
/// because a `SuperPlayer` reaches them through something other than itself: the lifecycle's rules
/// act on a ``LifecycleTarget``, which for a `SuperPlayer` is its `AVPlayer`; and a telemetry
/// collector measures a `RealtimePlayer` through ``RealtimeMeasurable``, while it measures a
/// `SuperPlayer` by observing its `AVPlayer` as it always has.
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

    // MARK: - What a PlaybackSession needs

    /// The remote commands this engine can carry out, each a button the system draws while its
    /// session holds Now Playing — so a command an engine cannot honour is one it does not list.
    var remoteCommands: Set<RemoteCommand> { get }

    /// Carries out `event`, one of ``remoteCommands``, answering whether it could be.
    func perform(_ event: RemoteCommandEvent) -> Bool

    /// The request playing now, or nil when nothing a request named is: whose identity, title and
    /// artwork a session publishes.
    var nowPlayingRequest: MediaRequest? { get }

    /// The Now Playing dictionary for what this engine plays now with `artwork`, the request's
    /// fetched image; nil with nothing playing.
    func nowPlayingInfo(artwork: UIImage?) -> [String: Any]?

    /// Registers `changed` for every change to what ``nowPlayingInfo(artwork:)`` would answer, called
    /// a main-queue turn after the change; the registrations live as long as the array returned.
    func observeNowPlaying(_ changed: @escaping @MainActor @Sendable () -> Void) -> [AnyObject]

    /// Plays `request`, which a session's resolver answered for an id from outside the app, exactly
    /// as the engine's own `setMediaRequest` would; false where the engine refused it.
    func adopt(resolved request: MediaRequest) -> Bool

    /// Ends what this engine is doing, for good: the player's own `release()`.
    func release()
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

    /// All six: an `AVPlayer` seeks, so the scrubber and both skips are buttons it honours.
    package var remoteCommands: Set<RemoteCommand> { Set(RemoteCommand.allCases) }

    package func perform(_ event: RemoteCommandEvent) -> Bool {
        switch event {
        case .play: avPlayer.play()
        case .pause: avPlayer.pause()
        case .togglePlayPause: avPlayer.rate == 0 ? avPlayer.play() : avPlayer.pause()
        case .changePlaybackPosition(let seconds):
            return seek(to: seconds)
        case .skip(let seconds):
            let now = avPlayer.currentTime()
            return seek(to: (now.isNumeric ? now.seconds : 0) + seconds)
        }
        return true
    }

    package var nowPlayingRequest: MediaRequest? { currentRequestForCurrentItem }

    package func nowPlayingInfo(artwork: UIImage?) -> [String: Any]? {
        NowPlayingInfo.of(avPlayer, request: currentRequestForCurrentItem, artwork: artwork)
    }

    package func observeNowPlaying(_ changed: @escaping @MainActor @Sendable () -> Void)
        -> [AnyObject]
    {
        SessionObservation.registerAll(on: avPlayer, changed)
    }

    package func adopt(resolved request: MediaRequest) -> Bool {
        setMediaRequest(request)
        return true
    }

    /// Seeks to `seconds`, never before the start; false with no item to seek in.
    private func seek(to seconds: Double) -> Bool {
        guard avPlayer.currentItem != nil else { return false }
        avPlayer.seek(
            to: CMTime(value: Int64((max(0, seconds) * 1_000).rounded()), timescale: 1_000))
        return true
    }
}
