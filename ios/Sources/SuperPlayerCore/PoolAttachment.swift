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

/// What attaches to a ``PlayerPool`` to change what its players adopt and which idle player it hands
/// out — the Kotlin `PoolAttachment`, the seam `SuperPlayerPreload`'s coordinator is attached through
/// (ADR-0010 rules 7 and 8, `docs/ios-plan.md` §3.4).
///
/// `package` rather than internal because a later product fills it, which is how a Kotlin friend
/// seam is spelt here (`ios/CLAUDE.md`), and rather than public because a consumer never sees it: a
/// feed writes `player.setMediaRequest(request)` and gets a warm start or a cold one, and there is
/// no call that hands it an item (rule 7).
///
/// What the Kotlin seam has and this one does not is `onEngineAssembled`: `AVPlayer` has no shared
/// engine to assemble — no load control, meter or playback thread a preload manager must be built
/// over (rule 9) — so an attachment is ready the moment it is attached. Every call is on the main
/// actor, the pool's.
@MainActor
package protocol PoolAttachment: AnyObject {

    /// How `player`, adopting `request` through ``SuperPlayer/setMediaRequest(_:)``, starts warm, or
    /// nil to load it cold. An item returned here is `player`'s from now on, and is on no other
    /// player.
    func warmStart(for player: SuperPlayer, adopting request: MediaRequest) -> WarmStart?

    /// Which of `idle` — never empty, newest last — the pool hands out on its next
    /// ``PlayerPool/acquire()``, or nil for the pool's own choice, the newest. One of `idle` if not
    /// nil.
    func preferredIdle(_ idle: [SuperPlayer]) -> SuperPlayer?

    /// `player` has left the idle set for a caller, who does not have it yet.
    func acquired(_ player: SuperPlayer)

    /// `player` was handed back to the pool and has been reset: it plays nothing, and an item it
    /// adopted has gone with the reset.
    func recycled(_ player: SuperPlayer)

    /// The pool is releasing, before its players are.
    func poolReleasing()
}

/// How a pooled player adopting a request starts, when it does not start cold — the Kotlin
/// `WarmStart`.
package enum WarmStart {

    /// From an item made ahead by ``SuperPlayer/item(for:on:)`` on an asset whose playlists were
    /// loaded ahead (``SuperPlayer/asset(for:configuration:)``), which the player plays from now on.
    /// An item that has never been any player's: `AVPlayerItem` belongs to one player for life.
    case item(AVPlayerItem)

    /// From the item the player already holds warm (``SuperPlayer/holdWarm(_:)``), prerolled:
    /// there is nothing to replace.
    case prepared
}
