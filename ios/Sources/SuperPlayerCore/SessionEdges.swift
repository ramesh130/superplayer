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

/// The measurement session's edges, signalled by core because only core knows which item change
/// carried a ``MediaRequest`` (ADR-0008 rule 2; the Kotlin facade's `adopt`, `restoreSnapshot`,
/// `resetForReuse` and `release`).
///
/// Four edges, three callers today:
///
/// - **adopt** — ``SuperPlayer/setMediaRequest(_:)`` ends the open session and begins one for the
///   request's content, in the `load` it shares with restore.
/// - **restore** — ``SuperPlayer/restoreSnapshot(_:)`` is an adoption of the snapshot's request
///   through that same `load`; a snapshot with no request touches no session.
/// - **release** — ``SuperPlayer/release()`` ends the open session before the collector detaches.
/// - **reset for reuse** is `resetForReuse`'s, #404's pool: a recycled player's session ends and
///   none begins, so it calls ``SuperPlayer/endMeasurement()`` alone.
///
/// Both are no-ops on a player configured without a collector, which holds no session to begin.
extension SuperPlayer {

    /// Opens a measurement session for `contentId` and tells the collector. Call after the item is
    /// the player's, so a collector that reads the player on this signal reads the new content.
    ///
    /// The session's id is *read* off `asset`, the one the facade built for this adoption: its
    /// `httpSessionIdentifier` is what AVFoundation sends as CMCD's `sid` and as
    /// `X-Playback-Session-Id`, and cannot be set, so the telemetry id follows it rather than the
    /// other way round (`CmcdBinding.swift`). Read only past the guard, so a player measuring
    /// nothing reads nothing.
    func beginMeasurement(of contentId: String, playing asset: AVURLAsset) {
        guard !isReleased, let collector, measurement != nil else { return }
        let sessionId = CmcdBinding.sessionId(of: asset)
        measurement?.open(contentId: contentId, sessionId: sessionId)
        collector.startSession(contentId: contentId, sessionId: sessionId)
    }

    /// Closes the open measurement session, if there is one, and tells the collector. Call before
    /// the outgoing item is replaced, so the session ends while its content is still there to read.
    func endMeasurement() {
        guard measurement?.close() != nil else { return }
        collector?.endSession()
    }
}
