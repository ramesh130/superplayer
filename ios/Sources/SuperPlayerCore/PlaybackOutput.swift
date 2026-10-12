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

/// What a player's picture is shown on, and what `SuperPlayerTV` does about it on the player's
/// behalf: `TvOutput.standard()`, which ``SuperPlayer/Configuration/output`` takes — the Kotlin
/// `PlaybackOutput` (ADR-0014 rule 2, `docs/ios-plan.md` §3.8).
///
/// Public and empty, ``PlaybackDrm``'s shape and the Kotlin marker's: what core asks of it is the
/// `package` protocol ``VideoOutputting``, which the TV product's value also conforms to, so no
/// AVKit type reaches a public signature and a consumer has nothing to implement. A value a consumer
/// wrote conforming to this alone fills nothing and registers nothing — the contract a
/// ``PlaybackPolicy`` that is not core's own extension has on Android.
///
/// **Fixed for the player's lifetime**, as every configuration is (ADR-0020 rule 5), and **one value
/// may serve many players**: a pool configures every pooled player from it, and what it makes is per
/// player, because which item is playing is one player's fact.
public protocol PlaybackOutput: Sendable {}

/// What core asks a ``PlaybackOutput`` for, once per player: the binding that player tells its
/// items to — the Kotlin `EngineOutputExtension` filling `EngineConfiguration.videoOutput`, and the
/// seam a Kotlin friend module reaches as `internal`, which on Apple platforms is `package`
/// (`ios/CLAUDE.md`). The slot sits beside ``SuperPlayer/Configuration/frameOutput``, the second
/// output slot ADR-0022 rule 3 shaped, and neither reads the other.
package protocol VideoOutputting: PlaybackOutput {

    /// A fresh binding for the player `host` is, made as that player is made, on the main actor —
    /// or nil where this platform has nothing for one to do, which then costs the player exactly
    /// what no output costs. The binding holds `host` weakly: the player owns its binding.
    @MainActor func videoOutputBinding(host: any VideoOutputHost) -> (any VideoOutputBinding)?
}

/// One player's video output, as the TV product sees it: the item the player is showing, and the
/// player's end — the Kotlin `VideoOutputBinding`.
///
/// Core tells the binding *facts*; what it asks of the platform about them — the display's criteria
/// — is the binding's, so nothing in core names a display. Android tells its binding a surface and
/// a frame rate, because there the request attaches to a surface and the rate is read off a selected
/// format; on tvOS the request is the window's display manager's and the criteria are the asset's
/// own, so the item is the whole fact (`docs/ios-plan.md` §3.8).
@MainActor
package protocol VideoOutputBinding: AnyObject {

    /// `item` is the player's current item from now on, or nil for none — every replacement the
    /// facade makes, an item from a bare URL and a rung's re-adoption included, and nil when a pool
    /// recycles the player. Not an item ``SuperPlayer/holdWarm(_:)`` makes current on an idle
    /// pooled player: a row nobody is watching asks nothing of the display.
    func itemChanged(to item: AVPlayerItem?)

    /// The player is released: whatever was asked of the platform is withdrawn and nothing more is.
    func release()
}

/// What a ``VideoOutputBinding`` tells the player it serves — core's half, so that the TV product
/// holds no collector of its own.
@MainActor
package protocol VideoOutputHost: AnyObject {

    /// The display's mode reading, for the collector if the player has one
    /// (``TelemetryCollector/displayModeChanged(switchInProgress:criteriaMatchingEnabled:)``):
    /// reported and never acted on, because on Apple platforms the system re-selects for a display
    /// change itself (`docs/ios-plan.md` §3.8).
    func displayModeChanged(switchInProgress: Bool, criteriaMatchingEnabled: Bool)
}

extension SuperPlayer: VideoOutputHost {

    package func displayModeChanged(switchInProgress: Bool, criteriaMatchingEnabled: Bool) {
        guard !isReleased else { return }
        collector?.displayModeChanged(
            switchInProgress: switchInProgress, criteriaMatchingEnabled: criteriaMatchingEnabled)
    }
}
