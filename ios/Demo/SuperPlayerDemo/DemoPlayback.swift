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
import SuperPlayerCore
import SuperPlayerTelemetry
import os

/// One screen's player and everything its life is tied to — **the lifecycle a consumer copies**.
///
/// 1. **Configure, then build.** A `SuperPlayer.Configuration` is a value fixed for the player's
///    lifetime (ADR-0020 rule 5): the profile (here the default, `videoOnDemand`), the telemetry
///    collector — `QoeCollector` over `OSLogSink`, one line per event under the category
///    `SuperPlayerQoE` — and, on the frame-output screen, the `FrameOutput`.
/// 2. **Publish it, if it is the one worth publishing.** A `PlaybackSession` puts the player on the
///    lock screen, Control Centre and a headset's buttons, and resolves content named from outside
///    the app through ``DemoCatalog``. It is a choice rather than a default (ADR-0007 rule 1): a
///    screen of many players publishes one of them.
/// 3. **Declare intent, then name the content.** `declarePlaybackIntent()` is where time to first
///    frame starts — the viewer's tap, which the library cannot see — and `setMediaRequest(_:)`
///    takes the content as an identity with its sources, never a bare URL. Playing is
///    `avPlayer.play()`: the facade adds no transport controls of its own, so everything that
///    already drives an `AVPlayer` drives this one.
/// 4. **Release once.** ``release()`` releases the session, which releases the player after it,
///    in the order that matters; nothing else is torn down by hand.
///
/// The position line is the demo's and not the library's: a screenshot cannot tell a rendered frame
/// from a stalled one (CLAUDE.md, *Running the demo on an emulator*), and a position that advances
/// between two lines can. Android reads that off the platform's own media-session log; iOS logs no
/// such thing, so the demo does, every ``positionLogIntervalSeconds``:
///
/// ```sh
/// xcrun simctl spawn booted log stream --predicate 'subsystem == "com.superplayer.demo"'
/// ```
@MainActor
final class DemoPlayback {

    /// How often the position is logged: often enough that two lines arrive within seconds of
    /// playback starting, rarely enough that a run of minutes stays a screenful.
    static let positionLogIntervalSeconds: Double = 2

    nonisolated private static let logger = Logger(
        subsystem: "com.superplayer.demo", category: "Playback")

    let player: SuperPlayer
    private let session: PlaybackSession
    private var positionObserver: Any?

    init(stream: DemoStream, frameOutput: CountingPassThrough? = nil) {
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: OSLogSink()) }
        configuration.frameOutput = frameOutput
        player = SuperPlayer(configuration: configuration)
        session = PlaybackSession(player: player, resolver: DemoCatalog())

        player.declarePlaybackIntent()
        player.setMediaRequest(stream.request())
        player.avPlayer.play()

        let contentId = stream.contentId
        // A timescale of 600 is the one AVFoundation's own examples use for video: a whole multiple
        // of 24, 25 and 30, so no common frame rate's interval rounds.
        positionObserver = player.avPlayer.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: Self.positionLogIntervalSeconds, preferredTimescale: 600),
            queue: .main
        ) { time in
            let positionMs = Int64(time.seconds * 1000)
            // `frames=` only where there is an output to count, so the two screens' lines are told
            // apart by their shape as well as by what they say.
            let frames = frameOutput.map { " frames=\($0.framesProcessed)" } ?? ""
            Self.logger.log(
                "content=\(contentId, privacy: .public) position=\(positionMs, privacy: .public)\(frames, privacy: .public)"
            )
        }
    }

    /// Ends the session and then the player. The screen calls it once, as it goes.
    func release() {
        if let positionObserver {
            player.avPlayer.removeTimeObserver(positionObserver)
            self.positionObserver = nil
        }
        session.release()
    }
}
