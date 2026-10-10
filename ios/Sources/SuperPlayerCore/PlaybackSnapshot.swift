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

/// What a ``SuperPlayer`` was doing, as a value that outlives the player.
///
/// A scene that is discarded and rebuilt, a view whose state is restored, a process the system
/// ended in the background: each loses the player, and a `SuperPlayer` deliberately remembers
/// nothing beyond its own lifetime — so without this, coming back restarts the video. The port of
/// `superplayer-core`'s `PlaybackSnapshot` (`docs/ios-plan.md` §3.1), whose reasons are this type's:
/// every app otherwise writes the same bookkeeping, and each gets a slightly different answer for
/// content that had ended, content that was paused, and everything the session had already watched.
///
/// ```swift
/// // Wherever the app keeps state — SceneStorage, state restoration, its own store:
/// let saved = try JSONEncoder().encode(player.saveSnapshot())
///
/// // ...and on the way back, on a new player:
/// let player = SuperPlayer(configuration: .init(profile: .videoOnDemand))
/// player.restoreSnapshot(try JSONDecoder().decode(PlaybackSnapshot.self, from: saved))
/// ```
///
/// **SuperPlayer chooses no storage** (ADR-0006 rule 2): it hands back a `Codable` value and the
/// app decides where it lives. The Kotlin type's `toBundle`/`fromBundle` are `Codable` here, the
/// round trip every Swift store already takes.
///
/// ## What survives, and what does not
///
/// It carries what was playing (``request``), where it had got to (``positionMs``), whether the
/// viewer wanted it playing (``playWhenReady``), and every resume position the player had
/// accumulated — so ``MediaRequest/StartPosition/resumeFromLastKnown`` keeps working in the new
/// player rather than quietly falling back to the beginning, the failure hardest to notice.
///
/// It does not carry the ``PlaybackProfile``. A profile is half applied to the `AVPlayer` as the
/// player is made, so it belongs to the *construction* of the replacement rather than to the state
/// poured into it, and an app that offers the choice already remembers it. Nor does it name content
/// played through ``SuperPlayer/setMediaURL(_:)``: a bare URL has no identity to restore under —
/// the reason ``MediaRequest`` exists — so ``request`` is nil for one, and restoring such a snapshot
/// restores the positions and the intent to play but no content.
///
/// ## The stored format
///
/// Its encoding is Swift's synthesized one, and it is a **stored format**: what an app wrote with
/// one release, the next must read. So the keys are the property names and the case names of
/// ``MediaRequest/StartPosition`` — `{"at":{"positionMs":5000}}`, `{"beginning":{}}`,
/// `{"resumeFromLastKnown":{}}` — and `PlaybackSnapshotTests` pins the whole document as literal
/// JSON, so renaming a property or a case fails a test rather than silently forgetting every saved
/// snapshot. The case names, unlike an ordinal, survive a reordering, which is the Kotlin bundle's
/// reason for writing its start-kind constants by hand.
///
/// A document that is malformed — a blank content id, an empty source list, a negative start —
/// throws `DecodingError` from the decoder, as ``MediaRequest``'s does. The Kotlin `fromBundle`
/// restores what it can instead, because there an exception is a crash on the way back into an
/// Activity; here a throw is an error the caller handles, and `try?` is the caller's own, visible
/// choice to start from nothing.
public struct PlaybackSnapshot: Codable, Sendable, Hashable {

    /// What was playing, or nil if nothing was — including an item played from a bare URL, which
    /// has no ``MediaRequest/contentId`` to restore under.
    ///
    /// Carries the ``MediaRequest/StartPosition`` the request was made with, a record of what was
    /// asked rather than of what happened: where playback had reached is ``positionMs``, and that is
    /// what ``SuperPlayer/restoreSnapshot(_:)`` resumes from.
    public let request: MediaRequest?

    /// Where ``request`` had got to, in milliseconds, or nil when there is no ``request`` — the
    /// Kotlin `C.TIME_UNSET`, as an optional.
    ///
    /// Content that had played to its end is recorded at the beginning, for the reason
    /// ``MediaRequest/StartPosition/resumeFromLastKnown`` gives.
    ///
    /// **Live content is the case to be careful with.** A live window slides, so a position saved
    /// against it means something different by the time it is restored, and a long gap can put it
    /// outside the window entirely. An app that would rather rejoin at the edge should ignore this
    /// field and request the content again with ``MediaRequest/StartPosition/beginning``, which *is*
    /// the live edge for a live stream.
    public let positionMs: Int64?

    /// Whether the player was playing or trying to — its `timeControlStatus` was anything but
    /// paused, so waiting for media counts. The Kotlin `playWhenReady`: the viewer's intent rather
    /// than the engine's state, and restoring it is what makes coming back invisible to someone
    /// watching and what stops a deliberate pause being undone.
    public let playWhenReady: Bool

    /// Every position the player had remembered, ``request``'s own included, least recently used
    /// first — an ordered list rather than a map keyed by content id, because the order is what a
    /// bounded memory evicts by and a JSON object has none.
    ///
    /// Internal, as the Kotlin field is: it is the player's memory rather than a description of what
    /// was on screen, and a consumer who wants a position for other content asks the player it
    /// restored this into.
    let rememberedPositions: [RememberedPosition]

    /// One entry of ``rememberedPositions``.
    struct RememberedPosition: Codable, Sendable, Hashable {
        let contentId: String
        let positionMs: Int64
    }
}

extension SuperPlayer {

    /// What this player is doing, as a value that outlives it. Taking one changes nothing.
    ///
    /// The snapshot names what is playing only if this player can name it: content played through
    /// ``setMediaURL(_:)`` has no identity, and a request whose item was since replaced through
    /// ``avPlayer`` directly would describe something no longer loaded — so the request is checked
    /// against the identity the current item carries rather than trusted from the bookkeeping, as
    /// the Kotlin facade checks it against `currentMediaItem`.
    public func saveSnapshot() -> PlaybackSnapshot {
        let request = currentRequest.flatMap { $0.contentId == currentContentId ? $0 : nil }
        let positionMs = request.map { _ in positionOfCurrentContentMs }
        // The current content's own position folded in as the most recent use, so the memory a
        // restored player starts with is complete: without it, restoring and then switching away
        // and back would resume the one thing the viewer was watching from the beginning. On a
        // copy, because taking a snapshot changes nothing.
        var memory = rememberedPositions
        if let request, let positionMs { memory.remember(positionMs, for: request.contentId) }
        return PlaybackSnapshot(
            request: request,
            positionMs: positionMs,
            playWhenReady: avPlayer.timeControlStatus != .paused,
            rememberedPositions: memory.entries.map {
                PlaybackSnapshot.RememberedPosition(
                    contentId: $0.contentId, positionMs: $0.positionMs)
            })
    }

    /// Puts this player back where `snapshot` left off: the content, the position within it, the
    /// intent to play, and the resume positions the saved player had accumulated.
    ///
    /// Meant for a new player; restoring into one already playing replaces what it plays. A
    /// snapshot with no ``PlaybackSnapshot/request`` restores the positions and the intent and leaves
    /// the current content alone, the honest answer for content that had no identity to save.
    public func restoreSnapshot(_ snapshot: PlaybackSnapshot) {
        guard !isReleased else { return }
        // Ahead of the item, so the restored memory is in place before anything reads it, and in the
        // saved order, so least-recently-used order is inherited. Clamped, because a stored negative
        // position is corruption rather than intent.
        for entry in snapshot.rememberedPositions {
            rememberedPositions.remember(max(0, entry.positionMs), for: entry.contentId)
        }
        if let request = snapshot.request {
            // At the first source, not whichever the saved player had fallen back to: a snapshot
            // carries no rung, because a restored player is a new engine on a new network. And not
            // through `setMediaRequest`, which starts where the request's own `StartPosition` says
            // — what was asked — where a snapshot carries what was reached.
            load(request, startingAtMs: snapshot.positionMs.map { max(0, $0) })
        }
        if snapshot.playWhenReady { avPlayer.play() } else { avPlayer.pause() }
    }
}
