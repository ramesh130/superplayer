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
import Foundation
import SuperPlayerTestkit

/// What the harness's own tests do to a stock `AVPlayer` and an origin, written once.
enum StockPlayback {

    /// A ceiling for the synthetic media, a few seconds long, to play at 1× on a loaded CI host:
    /// several times its length, and only a bound.
    static let bound: Duration = .seconds(60)

    /// Plays `origin`'s content in a stock `AVPlayer` until its item reports the end.
    @MainActor
    static func playToEnd(_ origin: LoopbackOrigin, with harness: PlaybackHarness) async throws {
        let item = AVPlayerItem(url: origin.sourceURL)
        let player = AVPlayer(playerItem: item)
        let ended = EndObserver(item)
        player.play()
        try await harness.until("the stream plays to its end", timeout: bound) { ended.hasEnded }
        player.pause()
    }

    /// Fetches `url` with a session of its own, so no connection is shared with another fetch,
    /// returning the body and how long it took to arrive whole.
    static func fetch(_ url: URL) async throws -> (body: Data, status: Int?, elapsed: Duration) {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let clock = ContinuousClock()
        let start = clock.now
        let (data, response) = try await session.data(from: url)
        return (data, (response as? HTTPURLResponse)?.statusCode, clock.now - start)
    }
}

/// Whether an `AVPlayerItem` has posted that it played to its end, readable from the main actor.
@MainActor
final class EndObserver {
    private(set) var hasEnded = false
    // Written once, in `init`, and read once more in `deinit`, which is not on the main actor.
    nonisolated(unsafe) private var token: NSObjectProtocol?

    init(_ item: AVPlayerItem) {
        token = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.hasEnded = true } }
    }

    deinit { token.map(NotificationCenter.default.removeObserver) }
}
