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
/// declares one `FRAME-RATE`, and the shortest gap between two handed frames is that interval. A gap
/// between two handed frames therefore held `round(gap / shortest) − 1` frames the output never
/// saw. Rounded rather than divided, so a stream whose timestamps alternate between two tick counts
/// — 59.94 frames a second on a timescale of 600 lands on gaps of 10 and 11 ticks — counts nothing:
/// a gap 1.1 times the shortest is one interval, and only one of at least 1.5 counts a frame.
///
/// A new stretch starts where the picture size changes — a variant switch, where the frame rate may
/// change with it: Apple's BipBop example declares 30 frames a second on its lower variants and 60
/// on the rest — and where time goes backwards, which no ordinary playback does. Three things this
/// cannot see, which `devicelab/README.md`'s *The frames scenario* carries: **the gap across a
/// stretch boundary** is judged by nothing, so a frame lost exactly at a switch is not counted; a
/// gap that arrives **before a stretch has shown its interval** is judged against a longer one, so a
/// stretch that begins with a loss undercounts it; and a frame rate that changes **at one picture
/// size** is not a new stretch, so the slower rate's gaps would count as losses — never a false
/// pass, and the reason ``largestGapMs`` is logged, since a real loss is a gap near a whole multiple
/// of the interval. And this counts what reached the output, not whether the display layer then
/// presented each frame on time.
///
/// ref: https://www.rfc-editor.org/rfc/rfc8216#section-4.3.4.2 (`FRAME-RATE`, one per variant)
struct FrameTally: Sendable {

    /// Frames handed to the output.
    private(set) var frames = 0
    /// Frames handed again at the time the last one carried; not frames, and not counted as such.
    private(set) var repeated = 0
    /// Stretches of one picture size over which ``missing`` was counted: one more than the switches.
    private(set) var runs = 0
    /// Frames the content held between two handed frames of one stretch that the output never saw.
    private(set) var missing = 0
    /// The longest gap between two handed frames of one stretch, and the presentation time it ended
    /// at, in milliseconds: where a loss happened, and whether it is a whole number of frames.
    private(set) var largestGapMs = 0.0
    private(set) var largestGapAtMs = 0.0

    private var run: Run?

    /// The current stretch's frame interval in milliseconds, or 0 before it has two frames.
    var intervalMs: Double { run.map { $0.shortest.isFinite ? $0.shortest * 1000 : 0 } ?? 0 }

    mutating func add(seconds: Double, width: Int, height: Int) {
        guard seconds.isFinite else { return }
        if var current = run, current.width == width, current.height == height,
            seconds >= current.last
        {
            if seconds == current.last {
                repeated += 1
                return
            }
            let gap = seconds - current.last
            current.shortest = min(current.shortest, gap)
            current.last = seconds
            run = current
            frames += 1
            missing += max(0, Int((gap / current.shortest).rounded()) - 1)
            if gap * 1000 > largestGapMs {
                largestGapMs = gap * 1000
                largestGapAtMs = seconds * 1000
            }
            return
        }
        run = Run(width: width, height: height, last: seconds)
        runs += 1
        frames += 1
    }

    /// One stretch of a single picture size.
    private struct Run: Sendable {
        let width: Int
        let height: Int
        var last: Double
        var shortest = Double.infinity
    }
}
