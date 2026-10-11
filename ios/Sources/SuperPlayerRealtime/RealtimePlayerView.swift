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

// The library's one view, taking the second player type (ADR-0020 rule 11): core's `PlayerView` and
// `PlayerLayerView` host whatever layer an engine shows, and these initializers are the public way
// to hand them a `RealtimePlayer`, which core cannot name. The layer hosted is the player's own
// `AVSampleBufferDisplayLayer`, so — as for a `SuperPlayer` with a frame output — it has one
// superlayer and is shown by one view at a time.

extension PlayerView {

    /// A view of `player`'s picture.
    ///
    /// ```swift
    /// PlayerView(player: realtimePlayer)
    ///     .aspectRatio(16 / 9, contentMode: .fit)
    /// ```
    public init(player: RealtimePlayer, videoGravity: AVLayerVideoGravity = .resizeAspect) {
        self.init(engine: player, videoGravity: videoGravity)
    }
}

extension PlayerLayerView {

    /// A view of `player`'s picture.
    public convenience init(player: RealtimePlayer) {
        self.init(engine: player)
    }

    /// The realtime player shown, or nil for an empty view or one showing a `SuperPlayer`
    /// (``PlayerLayerView/player``).
    public var realtimePlayer: RealtimePlayer? {
        get { engine as? RealtimePlayer }
        set { engine = newValue }
    }
}
