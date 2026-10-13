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

import SuperPlayerCore

// A `PlaybackSession` over the second player type (#461, ADR-0020 rule 11): core's session publishes
// whatever engine it is handed, and these are the public way to hand it a `RealtimePlayer`, which
// core cannot name — the session's twin of `RealtimePlayerView.swift`'s initializers. The stream is
// published as live, and the system draws play, pause and toggle and no seek command.

extension PlaybackSession {

    /// Publishes `player`, resolving content named from outside the app through `resolver` — at the
    /// live edge only: a resolved request that starts anywhere else is declined, as the player
    /// refuses it.
    public convenience init(player: RealtimePlayer, resolver: (any MediaRequestResolver)? = nil) {
        self.init(engine: player, resolver: resolver)
    }

    /// The realtime player this session publishes, or nil on a session publishing a `SuperPlayer`
    /// (``PlaybackSession/player``).
    public var realtimePlayer: RealtimePlayer? { engine as? RealtimePlayer }

    /// Swaps `player` in behind the live session and releases the player that was there, of either
    /// type — the buttons drawn follow it, so a realtime stream withdraws the seek commands.
    public func setPlayer(_ player: RealtimePlayer) {
        setEngine(player)
    }
}
