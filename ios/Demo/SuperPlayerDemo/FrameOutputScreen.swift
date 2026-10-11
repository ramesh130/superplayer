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
import SuperPlayerCore
import SwiftUI
import os

/// The same stream through a pass-through `FrameOutput`, shown on the library's `PlayerView` — the
/// one surface that displays what an output returns (ADR-0022 rule 5). `VideoPlayer` and
/// `AVPlayerViewController` render the player's frames themselves and would show the unprocessed
/// picture, so a player with an output is shown here and on nothing else.
///
/// The count over the picture is how many frames the output has been handed: a number that climbs
/// at the stream's frame rate is frames reaching the output, which is what this screen shows.
/// Whether they arrive **on time** — the pass-through dropping no frame an `AVPlayerLayer` would
/// have shown — is a device count (#435), because a simulator's display timing proves nothing about
/// a phone's (ADR-0022 rule 8).
struct FrameOutputScreen: View {

    let stream: DemoStream

    @State private var output = CountingPassThrough()
    @State private var playback: DemoPlayback?

    var body: some View {
        ZStack(alignment: .topLeading) {
            if let playback {
                PlayerView(player: playback.player)
            } else {
                Color.black
            }
            // Read once a second rather than per frame: the count is a reading for a person, and a
            // view invalidated thirty times a second would compete with the picture it describes.
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                Text("frames through the output: \(output.framesProcessed)")
                    .font(.footnote.monospacedDigit())
                    .padding(8)
                    .background(.black.opacity(0.6))
                    .foregroundStyle(.white)
            }
            .padding()
        }
        .background(.black)
        .navigationTitle(DemoScreen.frameOutput.title)
        .onAppear { playback = DemoPlayback(stream: stream, frameOutput: output) }
        .onDisappear {
            playback?.release()
            playback = nil
        }
    }
}

/// The demo's pass-through: returns every frame it is handed, unchanged, and counts them.
///
/// The demo's own and not a library type, because ADR-0022 rule 8 keeps every effect — a
/// pass-through included — out of what an app plays with; testkit's `PassThroughFrameOutput` is a
/// test's for the same reason. Returning `frame` itself is the whole of passing through.
///
/// Called on a serial queue of the player's own (`FrameOutput`'s contract), so the count sits
/// behind a lock that the screen and the position log read from the main actor.
final class CountingPassThrough: FrameOutput {

    private let count = OSAllocatedUnfairLock(initialState: 0)

    func process(_ frame: CVPixelBuffer, presentationTime: CMTime) -> CVPixelBuffer {
        count.withLock { $0 += 1 }
        return frame
    }

    /// How many frames have been handed over so far.
    var framesProcessed: Int { count.withLock { $0 } }
}
