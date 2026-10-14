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

import SuperPlayerCore

/// The product's entry point: the ``PlaybackOutput`` a television player is configured with — the
/// Kotlin `TvOutput` (ADR-0014 rule 2, read for tvOS as `docs/ios-plan.md` §3.8 reads it).
///
/// ```swift
/// var configuration = SuperPlayer.Configuration()
/// configuration.profile = .tvLeanback
/// configuration.output = TvOutput.standard()
/// let player = SuperPlayer(configuration: configuration)
/// playerViewController.player = player.avPlayer
/// playerViewController.appliesPreferredDisplayCriteriaAutomatically = false
/// ```
///
/// **Display criteria matching** (ADR-0014 rule 4). Each item the player plays asks the display for
/// the asset's own `preferredDisplayCriteria` through the key window's `AVDisplayManager`, so film is
/// shown at its frame rate and HDR in its dynamic range, and the request is withdrawn at the item's
/// end, when a pool recycles the player, and when the player is released. Whether the display then
/// switches is the viewer's *Match Content* setting, which the system applies to the request; it is
/// correctness on every player configured with this, and no profile varies it. `DisplayCriteriaMatching`
/// says when it asks and why it never withdraws between two items.
///
/// **`AVPlayerViewController` does the same thing by itself**, in full screen, unless told not to:
/// its `appliesPreferredDisplayCriteriaAutomatically` is on by default. Turn it off where this output
/// is configured, as above, so that one party owns the display's criteria — two writers of one
/// property would each withdraw what the other asked for. This output also matches on hosts AVKit's
/// switch never reaches: the library's `PlayerView`, an `AVPlayerLayer`, a view that is not full
/// screen.
///
/// **The display's mode is reported to telemetry and never acted on.** A switch beginning and
/// ending — the panel may be blank meanwhile, and on a non-seamless sink that wait is inside time to
/// first frame — and whether the viewer's settings allow one, reach the player's collector as
/// `DisplayModeChanged`. Android re-selects renditions on a display change through its own selection
/// gate (ADR-0014 rule 5); on Apple platforms the system re-selects, so nothing here does.
///
/// **What is not built, each because the platform owns it** (`docs/ios-plan.md` §3.8):
/// - **Re-selection on a display change** — the Kotlin `DisplayWatch`. AVFoundation re-selects a
///   variant for the display it is now on.
/// - **Re-selection on an audio-capability change** (ADR-0014 rule 6). An AV receiver arriving or
///   leaving is an `AVAudioSession` route change, and AVFoundation re-selects the audio rendition.
/// - **Tunneling** (ADR-0014 rule 7). `PlaybackDecision.output` stays in the decision and is
///   ignored, as `PlaybackDecision` documents: Apple TV's output path is always tunneled and nothing
///   configures it, so `tvLeanback`'s row asking for it asks nothing here.
/// - **`TvPlaybackControls`.** `AVPlayerViewController` ships the transport controls, scrubbing, the
///   info panel and the Siri Remote's handling on tvOS (`docs/ios-plan.md` §8, item 3).
///
/// **On iOS it does nothing and costs nothing.** `AVDisplayManager` exists on tvOS alone, and an
/// external display reached by AirPlay or a cable is matched by the system there, so the value fills
/// the slot with nothing and a player configured with it is a player configured without one.
///
/// **What only a device shows**: whether the panel switched, how long it was blank, and what the
/// viewer's setting made of the request (`docs/testing.md`, *A television on tvOS*).
public enum TvOutput {

    /// A ``PlaybackOutput`` that matches the display to each item's criteria and reports the
    /// display's mode. One value may configure many players, a pool's included: each player gets a
    /// binding of its own, and the display — one per process — is shared between them.
    public static func standard() -> any PlaybackOutput {
        #if os(tvOS)
            StandardTvOutput { SystemDisplayManager.shared }
        #else
            StandardTvOutput { nil }
        #endif
    }

    /// The same output over `displays` rather than the system's — the seam a test states a display
    /// through (ADR-0021 rule 5), on either platform.
    package static func standard(displays: any DisplayManaging) -> any PlaybackOutput {
        StandardTvOutput { displays }
    }
}

/// What ``TvOutput/standard()`` returns: a ``PlaybackOutput`` that is also core's
/// ``VideoOutputting``, and so makes one binding per player — or none, where there is no display to
/// ask.
struct StandardTvOutput: VideoOutputting {

    /// The display a player made from this output asks, read as the player is made.
    let displays: @MainActor @Sendable () -> (any DisplayManaging)?

    @MainActor
    func videoOutputBinding(host: any VideoOutputHost) -> (any VideoOutputBinding)? {
        displays().map { DisplayCriteriaMatching(displays: $0, host: host) }
    }
}
