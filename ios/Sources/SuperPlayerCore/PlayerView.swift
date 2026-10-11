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
import SwiftUI
import UIKit

/// The UIKit view an app places to show a ``SuperPlayer``: an `AVPlayerLayer` over
/// ``SuperPlayer/avPlayer`` for a player without a frame output, and the player's
/// `AVSampleBufferDisplayLayer` for one whose output runs (ADR-0022 rules 5 and 7).
///
/// ```swift
/// let view = PlayerLayerView(player: player)
/// view.videoGravity = .resizeAspectFill
/// ```
///
/// **On a player without a frame output it is exactly what a stock app builds**: an `AVPlayerLayer`
/// the view makes over `avPlayer`, so nothing about the display path changes for the player — and
/// `AVPlayerViewController` and SwiftUI's `VideoPlayer` still take `avPlayer` unchanged, as they
/// always did (rule 7). **On a player whose frame output runs it is the recipe**, because those two
/// render the player's frames themselves and would show the unprocessed picture (rule 5's cost). A
/// player whose output is off (``SuperPlayer/frameOutputOffReason``) is the first kind.
///
/// A player's sample-buffer layer has one superlayer, so it is shown by one view at a time: handing
/// the player to a second view moves it there. ``PlayerView`` is the SwiftUI form.
@MainActor
public final class PlayerLayerView: UIView {

    /// The player shown, or nil for an empty view.
    public var player: SuperPlayer? {
        didSet {
            guard player !== oldValue else { return }
            host(player)
        }
    }

    /// How the picture fills the view; `AVPlayerLayer`'s and `AVSampleBufferDisplayLayer`'s own
    /// property, applied to whichever is hosted. `.resizeAspect` unless set, which is both layers'
    /// default. The platform's `AVLayerVideoGravity` rather than a SuperPlayer enum, because it is
    /// the value both layers take and an app already holds for its other video views; a wrapper
    /// would add three cases' worth of mapping and nothing a consumer could not say already.
    public var videoGravity: AVLayerVideoGravity = .resizeAspect {
        didSet { applyVideoGravity() }
    }

    /// The layer showing the picture, made or adopted for the current player.
    private var hosted: CALayer?

    public init(player: SuperPlayer? = nil) {
        super.init(frame: .zero)
        self.player = player
        host(player)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("PlayerLayerView is made in code, with a player")
    }

    /// A Picture in Picture content source for the picture this view shows: over the
    /// `AVPlayerLayer` it hosts, or over the sample-buffer layer with a playback delegate core
    /// answers over the `AVPlayer` (ADR-0022 rule 5 — the reason the sample-buffer layer was chosen
    /// over a bare Metal one). Nil for an empty view.
    ///
    /// Making one starts nothing: an app hands it to `AVPictureInPictureController(contentSource:)`
    /// and starts Picture in Picture itself, as it would for any player. That Picture in Picture then
    /// runs is a device's to show (#435).
    public func makePictureInPictureContentSource() -> AVPictureInPictureController.ContentSource? {
        switch hosted {
        case let layer as AVPlayerLayer:
            return AVPictureInPictureController.ContentSource(playerLayer: layer)
        case let layer as AVSampleBufferDisplayLayer:
            guard let playback = player?.framePath?.pictureInPicturePlayback else { return nil }
            return AVPictureInPictureController.ContentSource(
                sampleBufferDisplayLayer: layer, playbackDelegate: playback)
        default:
            return nil
        }
    }

    override public func layoutSubviews() {
        super.layoutSubviews()
        ownHosted?.frame = bounds
    }

    /// ``hosted``, while it is still this view's: a player's sample-buffer layer handed to a second
    /// view has moved there, and this view must neither size it nor take it back out.
    private var ownHosted: CALayer? {
        hosted?.superlayer === layer ? hosted : nil
    }

    private func host(_ player: SuperPlayer?) {
        ownHosted?.removeFromSuperlayer()
        hosted = nil
        guard let player else { return }
        let layer: CALayer = player.framePath?.layer ?? AVPlayerLayer(player: player.avPlayer)
        layer.frame = bounds
        self.layer.addSublayer(layer)
        hosted = layer
        applyVideoGravity()
    }

    private func applyVideoGravity() {
        switch hosted {
        case let layer as AVPlayerLayer: layer.videoGravity = videoGravity
        case let layer as AVSampleBufferDisplayLayer: layer.videoGravity = videoGravity
        default: break
        }
    }
}

/// The SwiftUI view an app places to show a ``SuperPlayer``: ``PlayerLayerView`` in a
/// representable, with the same two display paths and the same reason to prefer it over
/// `VideoPlayer` on a player whose frame output runs.
///
/// ```swift
/// PlayerView(player: player)
///     .aspectRatio(16 / 9, contentMode: .fit)
/// ```
public struct PlayerView: UIViewRepresentable {

    private let player: SuperPlayer
    private let videoGravity: AVLayerVideoGravity

    public init(player: SuperPlayer, videoGravity: AVLayerVideoGravity = .resizeAspect) {
        self.player = player
        self.videoGravity = videoGravity
    }

    public func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView(player: player)
        view.videoGravity = videoGravity
        return view
    }

    public func updateUIView(_ view: PlayerLayerView, context: Context) {
        view.player = player
        view.videoGravity = videoGravity
    }
}
