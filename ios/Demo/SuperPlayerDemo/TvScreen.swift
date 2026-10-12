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

import AVKit
import SuperPlayerCore
import SuperPlayerTV
import SwiftUI

/// The television screen — **the recipe a tvOS app copies**, the Android demo's `TvScreen` read for
/// Apple TV (`docs/ios-plan.md` §3.8). On tvOS the app opens here; on a phone it is one row of the
/// list, and plays as any other screen, since the output asks nothing on iOS.
///
/// 1. **Configure for a television**: the `tvLeanback` profile and `TvOutput.standard()`, which asks
///    the display for each item's own criteria — its frame rate and dynamic range — and withdraws the
///    request at the item's end and when the player is released. Whether the panel switches is the
///    viewer's *Match Content* setting; the switch, and the setting, reach telemetry as
///    `DisplayModeChanged` and nothing else, because the system re-selects for a display change
///    itself. Everything else is ``DemoPlayback``'s lifecycle unchanged: build, publish a
///    `PlaybackSession`, declare intent, set the request, release once.
/// 2. **Show it on `AVPlayerViewController`, with the system's controls.** On tvOS that controller
///    *is* the television's player interface — transport controls, scrubbing, the info panel, the
///    Siri Remote — so SuperPlayer ships no controls of its own and the Kotlin `TvPlaybackControls`
///    is not ported (§8, item 3). It takes `player.avPlayer` with no adapter.
/// 3. **Turn off AVKit's own criteria matching**: `appliesPreferredDisplayCriteriaAutomatically`,
///    on by default, makes the controller ask the display for the same criteria in full screen, and
///    two writers of one property withdraw each other's requests. One owner — the output, which also
///    matches on hosts AVKit's switch never reaches — is the rule; an app that wants AVKit's instead
///    configures no output.
///
/// The surface is the controller's own `AVPlayerLayer`, where the Android TV screen needs a
/// `SurfaceView` rule; secure output is the system's there (§3.8, last row). Tunneling, which
/// `tvLeanback`'s row asks for, is ignored: Apple TV's output path is always tunneled.
struct TvScreen: View {

    let stream: DemoStream

    @State private var playback: DemoPlayback?

    var body: some View {
        Group {
            if let playback {
                SystemPlayerController(player: playback.player.avPlayer)
                    .ignoresSafeArea()
            } else {
                Color.black
            }
        }
        // Guarded for `PlaybackScreen`'s reason: a focus change can fire `onAppear` again.
        .onAppear {
            if playback == nil {
                playback = DemoPlayback(
                    stream: stream, profile: .tvLeanback, output: TvOutput.standard())
            }
        }
        .onDisappear {
            playback?.release()
            playback = nil
        }
    }
}

/// `AVPlayerViewController` in SwiftUI, with AVKit's own display criteria matching off where the
/// platform has it — tvOS alone declares the property.
private struct SystemPlayerController: UIViewControllerRepresentable {

    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        #if os(tvOS)
            controller.appliesPreferredDisplayCriteriaAutomatically = false
        #endif
        controller.player = player
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = player
    }
}
