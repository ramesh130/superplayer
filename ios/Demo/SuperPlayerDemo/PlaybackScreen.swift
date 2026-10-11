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
import SwiftUI

/// Public HLS through a `SuperPlayer` on SwiftUI's stock `VideoPlayer` — **with no adapter**: the
/// facade owns an `AVPlayer` and hands it out as `avPlayer`, so every surface that already takes one
/// (`VideoPlayer`, `AVPlayerViewController`, an `AVPlayerLayer`) takes SuperPlayer's unchanged, as
/// the Android demo hands its player to a stock `PlayerView` (ADR-0020 rule 2).
///
/// This is the screen for a player **without** a frame output. With one, `VideoPlayer` would show
/// the unprocessed picture, which is why ``FrameOutputScreen`` uses the library's `PlayerView`
/// instead (ADR-0022 rule 5).
struct PlaybackScreen: View {

    let stream: DemoStream

    @State private var playback: DemoPlayback?

    var body: some View {
        Group {
            if let playback {
                VideoPlayer(player: playback.player.avPlayer)
            } else {
                Color.black
            }
        }
        .navigationTitle(DemoScreen.playback.title)
        // Guarded because `onAppear` can fire again without an `onDisappear` between (a tvOS focus
        // change, a re-entered navigation), and a second player would orphan the first unreleased.
        .onAppear { if playback == nil { playback = DemoPlayback(stream: stream) } }
        .onDisappear {
            playback?.release()
            playback = nil
        }
    }
}
