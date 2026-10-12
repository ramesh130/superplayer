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
                Text("frames through the output: \(playback?.frameOutput?.framesProcessed ?? 0)")
                    .font(.footnote.monospacedDigit())
                    .padding(8)
                    .background(.black.opacity(0.6))
                    .foregroundStyle(.white)
            }
            .padding()
        }
        .background(.black)
        .navigationTitle(DemoScreen.frameOutput.title)
        // A fresh output per player, so the count starts at zero with the playback it counts; and
        // guarded as ``PlaybackScreen`` is, for the same reason.
        .onAppear {
            if playback == nil {
                playback = DemoPlayback(stream: stream, frameOutput: CountingPassThrough())
            }
        }
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
/// Called on a serial queue of the player's own (`FrameOutput`'s contract), so the tally sits
/// behind a lock that the screen and the position log read from the main actor.
///
/// It also counts the frames it was **not** handed, which is what devicelab's `frames` scenario
/// reads off the position line (#435) — ``FrameTally`` says how, and what the count cannot see.
final class CountingPassThrough: FrameOutput {

    private let tally = OSAllocatedUnfairLock(initialState: FrameTally())

    func process(_ frame: CVPixelBuffer, presentationTime: CMTime) -> CVPixelBuffer {
        let width = CVPixelBufferGetWidth(frame)
        let height = CVPixelBufferGetHeight(frame)
        tally.withLock { $0.add(seconds: presentationTime.seconds, width: width, height: height) }
        return frame
    }

    /// How many frames have been handed over so far.
    var framesProcessed: Int { tally.withLock { $0.frames } }

    /// The whole tally, read once, so its fields describe one moment.
    var reading: FrameTally { tally.withLock { $0 } }
}

/// What ``CountingPassThrough`` has been handed, and the frames the content held between them that
/// it was not.
///
/// An `AVPlayerLayer` shows the content's frames at their presentation times, so the frames it
/// would have shown over a stretch of playback are the content's frames between two presentation
/// times — and the times the output *was* handed are enough to count those without a layer. Within
/// a stretch of one picture size the content's frame interval is constant, since an HLS variant
/// declares one `FRAME-RATE`, and the shortest gap between two handed frames is that interval. A
/// stretch from `first` to `last` therefore held `round((last − first) / shortest) + 1` frames, and
/// each one not handed is one the output dropped. The count is taken over the whole stretch rather
/// than gap by gap, so a gap that arrives before the interval is known is still counted once it is.
///
/// A new stretch starts where the picture size changes — a variant switch, where the frame rate may
/// change with it: Apple's BipBop example declares 30 frames a second on its lower variants and 60
/// on the rest — and where time goes backwards, which no ordinary playback does. **The gap across a
/// stretch boundary is judged by nothing**, so a frame lost exactly at a switch is not counted; and
/// this counts what reached the output, not whether the display layer then presented each frame on
/// time. `devicelab/README.md`'s *The frames scenario* carries both limits.
///
/// ref: https://www.rfc-editor.org/rfc/rfc8216#section-4.3.4.2 (`FRAME-RATE`, one per variant)
struct FrameTally: Sendable {

    /// Frames handed to the output.
    private(set) var frames = 0
    /// Frames handed again at the time the last one carried; not frames, and not counted as such.
    private(set) var repeated = 0
    /// Stretches of one picture size over which ``missing`` was counted: one more than the switches.
    private(set) var runs = 0

    private var closedMissing = 0
    private var run: Run?

    /// Frames the content held between the first and last handed frame of each stretch that the
    /// output was never handed.
    var missing: Int { closedMissing + (run?.missing ?? 0) }

    mutating func add(seconds: Double, width: Int, height: Int) {
        guard seconds.isFinite else { return }
        if var current = run, current.width == width, current.height == height,
            seconds >= current.last
        {
            if seconds == current.last {
                repeated += 1
            } else {
                current.add(seconds)
                run = current
                frames += 1
            }
            return
        }
        closedMissing += run?.missing ?? 0
        run = Run(width: width, height: height, first: seconds)
        runs += 1
        frames += 1
    }

    /// One stretch of a single picture size.
    private struct Run: Sendable {
        let width: Int
        let height: Int
        let first: Double
        var last: Double
        var count = 1
        var shortest = Double.infinity

        init(width: Int, height: Int, first: Double) {
            self.width = width
            self.height = height
            self.first = first
            self.last = first
        }

        mutating func add(_ seconds: Double) {
            shortest = min(shortest, seconds - last)
            last = seconds
            count += 1
        }

        /// Never negative, and nothing until two frames have shown the interval.
        var missing: Int {
            guard count > 1, shortest.isFinite, shortest > 0 else { return 0 }
            let held = Int(((last - first) / shortest).rounded()) + 1
            return max(0, held - count)
        }
    }
}
