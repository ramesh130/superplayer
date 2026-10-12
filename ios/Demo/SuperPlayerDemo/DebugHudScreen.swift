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
import SuperPlayerDiagnostics
import SwiftUI

/// The playback screen with `SuperPlayerDiagnostics`' debug HUD over it — **the recipe a consumer
/// copies**, the Android demo's `DebugHudScreen`:
///
/// 1. **One `DebugHudTelemetry` per player, composed beside the app's own sink**, made with the
///    player and dropped with it, so the overlay and the app's pipeline read one collector's
///    derivation rather than two (``DemoPlayback``'s `alsoTelling:`).
/// 2. **The overlay placed by the app**, over its own surface and where it chooses — here the top
///    leading corner, clear of `VideoPlayer`'s controls. No configuration attaches a HUD to a player.
/// 3. **Nothing in a release build**: `DebugHud` renders `EmptyView` unless the build is Debug and
///    development-signed (`isDebugHudAvailable()`), so this screen in a Release build is the
///    playback screen. An app that must not even attach the sink in Release puts steps 1 and 2
///    under its own `#if DEBUG`.
///
/// The estimate row fills on the collector's first ten-second sample; the rest move twice a second.
struct DebugHudScreen: View {

    let stream: DemoStream

    /// The player and its HUD sink, made and released together.
    @State private var watched: (playback: DemoPlayback, telemetry: DebugHudTelemetry)?

    var body: some View {
        Group {
            if let watched {
                ZStack(alignment: .topLeading) {
                    VideoPlayer(player: watched.playback.player.avPlayer)
                    DebugHud(player: watched.playback.player, telemetry: watched.telemetry)
                        .padding()
                }
            } else {
                Color.black
            }
        }
        .navigationTitle(DemoScreen.debugHud.title)
        // Guarded for ``PlaybackScreen``'s reason: `onAppear` can fire again without an
        // `onDisappear` between, and a second player would orphan the first unreleased.
        .onAppear {
            if watched == nil {
                let telemetry = DebugHudTelemetry()
                watched = (DemoPlayback(stream: stream, alsoTelling: telemetry), telemetry)
            }
        }
        .onDisappear {
            watched?.playback.release()
            watched = nil
        }
    }
}
