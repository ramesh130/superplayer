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

import CoreMedia
import CoreVideo

/// What an effect does to the decoded picture of a playing video: handed one frame and its
/// presentation time, it returns the frame to display at that same time (ADR-0022 rule 1).
///
/// The one SuperPlayer type both platforms share, under the Kotlin `FrameOutput`'s name, one method
/// and timing contract. The frame is the platform's own picture: a GL texture on Android, here a
/// `CVPixelBuffer` — a stable CoreVideo type, the case ADR-0020 rule 3 admits — and the time a
/// `CMTime`, CoreMedia's own, in the item's timeline. Neither `AVVideoCompositing` nor any other
/// engine effect API crosses the boundary, and no effect written for one platform runs on the other.
///
/// ```swift
/// var configuration = SuperPlayer.Configuration()
/// configuration.frameOutput = Segmentation(model: model)
/// let player = SuperPlayer(configuration: configuration)
/// // and on screen: PlayerView(player: player), never AVPlayerViewController
/// ```
///
/// **How a frame reaches it** (ADR-0022 rule 5): on a player configured with one, core adds an
/// `AVPlayerItemVideoOutput` to each item it plays, a display link asks it for the buffer due at
/// each host time, this method processes it, and core enqueues the result to an
/// `AVSampleBufferDisplayLayer` stamped with the time it was handed in. The layer presents each frame
/// at that time against the item's own timebase, so display timing stays the system's. The frames
/// are 32-bit BGRA and Metal-compatible (``SuperPlayer/frameOutputPixelFormat``).
///
/// **Called on a serial queue of the player's own**, never the main actor, once per frame the
/// output yields, in presentation order. The method is on the path to the screen, so a frame it is
/// late with is a frame not shown: while one call is running, the display link does not copy the
/// next frame, and a frame that came and went in the meantime is dropped — which the dropped-frame
/// count already measures (ADR-0022 rule 10).
///
/// **The frame's time cannot be changed**: the method returns a buffer and not a time, which is
/// Media3's refusal of timestamp-changing effects met by the signature on both platforms (rule 2).
/// Returning `frame` itself is a pass-through. A buffer written fresh — through a
/// `CVMetalTextureCache` if the effect renders with Metal — is what an effect returns otherwise.
///
/// **What it does with a frame is its own affair.** The output is fixed for the player's lifetime
/// (``SuperPlayer/Configuration/frameOutput``); an effect that switches its own processing on or off,
/// or changes its model, does it inside this method, and nothing about the player is reconfigured.
/// That is how "the effect list may change during playback" is met — by the output, not by the
/// player re-laying a pipeline (rule 2).
///
/// **Whether an effect may change resolution is not decided** (rule 10): the layer shows whatever
/// buffer comes back, scaled to the view, and nothing here promises more.
public protocol FrameOutput: Sendable {

    /// Returns the frame to display at `presentationTime` in place of `frame`.
    func process(_ frame: CVPixelBuffer, presentationTime: CMTime) -> CVPixelBuffer
}

/// Why a player configured with a frame output is not running it (ADR-0022 rule 6) — the Kotlin
/// `FrameOutputOffReason`, under Swift's case spelling.
///
/// Read from ``SuperPlayer/frameOutputOffReason``. **Never an error**: a player off for one of these
/// plays every item exactly as it would have without the output, on an `AVPlayerLayer`, and nothing
/// reaches the item's error or error log — nothing failed.
public enum FrameOutputOffReason: Sendable, Hashable {

    /// The player was configured for protected content. The decrypted frames of FairPlay content are
    /// what protection exists to keep from app code — and developers report that the output yields
    /// no pixel buffer under FairPlay at all (Apple Developer Forums thread 726854, a report that
    /// i6's device run verifies). Protection is fixed per player (ADR-0012 rule 1) and so is the
    /// output, so the answer is given when the player is made, and it covers the player's *clear*
    /// items too: switching per item would swap the displayed layer between items, which shows. A
    /// catalogue that wants effects on its clear content makes a second player for it.
    case protectedContent

    /// The player plays realtime content, where no decoded frame exists to tap:
    /// `AVSampleBufferDisplayLayer` decodes the pushed frames itself (ADR-0022 rule 6). No player
    /// reports it before the realtime phases arrive (`docs/ios-plan.md` §3.10), and it is in the
    /// vocabulary now because the vocabulary is the one both platforms share (rule 1).
    case realtime
}

/// Why a player configured with `output` is not running it, or nil where it is or where there is
/// none to run: protection first, the stronger of the two statements — the Kotlin
/// `frameOutputOffFor`, with the realtime half absent until a realtime player exists to ask.
///
/// Protection is ``SuperPlayer/Configuration/drm`` being *set*, whatever it is: a value that conforms
/// to ``PlaybackDrm`` and acquires nothing is still a declaration that the content is protected, as a
/// Kotlin provider that answers `DRM_UNSUPPORTED` is not nothing (ADR-0012 rule 13).
func frameOutputOff(output: (any FrameOutput)?, drm: (any PlaybackDrm)?) -> FrameOutputOffReason? {
    guard output != nil else { return nil }
    return drm != nil ? .protectedContent : nil
}
