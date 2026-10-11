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

import Foundation

/// A live playlist stopped advancing — the Kotlin `StaleLivePlaylistException`, under Swift's `Error`
/// suffix as ``UnplayableSourceSchemeError`` is, with the same fields and the same likely causes.
///
/// It is what the session ends on, underneath the ``SuperPlayerError`` that ``SuperPlayer/errors``
/// delivers — ``SuperPlayerError/underlyingError`` bridges back to it — and its
/// ``likelyCause`` is carried up as ``SuperPlayerError/likelyCause``, which is what an app branches on
/// to tell a viewer "this stream has stopped updating" rather than "check your connection":
///
/// ```swift
/// if let stale = (player.playerError as? SuperPlayerError)?.underlyingError
///     as? StaleLivePlaylistError {
///     showStreamStalled(stale.likelyCause)
/// }
/// ```
///
/// ## Detected, never repaired
///
/// The Kotlin layer reloads an overdue playlist with `Cache-Control: no-cache` before it gives up,
/// and in the common case that is the end of it. **No request can be reissued on Apple platforms**
/// — `AVPlayer` fetches its own playlists and nothing configures how (ADR-0020 rule 6) — so this is
/// raised where the Kotlin layer would give up, with nothing tried first, and
/// ``cacheBypassingReloads`` is always zero.
///
/// And it has to be raised, because AVFoundation does not fail: a playlist that stops changing
/// leaves an `AVPlayer` waiting at the live edge with no error, its error log gaining a
/// `CoreMediaErrorDomain` -12888 entry ("playlist unchanged for longer than 1.5 × target duration")
/// per reload that nothing surfaces (`HostileManifestCorpusTests`' frozen live row, #412). On a
/// player configured with resilience, `StaleLivePlaylistDetection.swift` watches for it and ends the
/// session here; `docs/ios-plan.md` §3.1's `LivePlaylistRevalidation` row is this split.
///
/// ## Coarser below iOS 18
///
/// On iOS and tvOS 18 the playlist's answers are read from `AVMetricHLSPlaylistRequestEvent` — when,
/// and with which `Cache-Control` and `Age` — and every field is evidence observed. Below 18 only
/// AVFoundation's own error log speaks, which names the playlist and nothing else, so
/// ``targetDurationMs``, ``unchangedForMs``, ``servedCacheControl`` and ``likelyCause`` are nil
/// there. A nil is "this platform could not see it", never zero.
public struct StaleLivePlaylistError: Error, Sendable, Hashable, CustomStringConvertible {

    /// Which party the evidence points at, from what the responses themselves said — the Kotlin
    /// `LikelyCause`, case for case.
    public enum LikelyCause: String, Sendable, Hashable, CaseIterable {

        /// An intermediary is answering with a copy it holds: the playlist was served with a
        /// shared-cache lifetime longer than a live playlist may stand still, or with an `Age` past
        /// that bound (spec: RFC 9111 §5.1 — only a stored response carries `Age`). The stream may be
        /// live at the origin; the copy reaching the player is not, and the fix is a cache rule at
        /// the CDN.
        case intermediaryCache = "INTERMEDIARY_CACHE"

        /// Nothing served says a cache is involved: what an origin that stopped publishing looks
        /// like — a stalled encoder or packager. Not proof, for the Kotlin reason: an intermediary
        /// that strips `Age` is indistinguishable from here.
        case origin = "ORIGIN"
    }

    /// The media playlist that stopped advancing, as AVFoundation requested it.
    public let playlistURL: URL

    /// The playlist's target duration — every bound this was raised against is a multiple of it — as
    /// read from its segments, or nil below iOS 18. `StaleLivePlaylistDetection.swift` says how it is
    /// read, since `AVPlayer` reports no `EXT-X-TARGETDURATION`.
    public let targetDurationMs: Int64?

    /// How long the live edge had gone without a new segment when this was raised, or nil below
    /// iOS 18.
    public let unchangedForMs: Int64?

    /// How many reloads asked every cache on the way to fetch the origin's copy: always zero on Apple
    /// platforms, where no request can be reissued — the Kotlin field's own reading of zero, "none
    /// was attempted", which here is true by construction rather than impossible.
    public let cacheBypassingReloads: Int

    /// The `Cache-Control` the playlist was last served with, or nil when it was served with none —
    /// and below iOS 18, where no response is readable.
    public let servedCacheControl: String?

    /// Which party the evidence points at, or nil below iOS 18, where no response is readable.
    public let likelyCause: LikelyCause?

    package init(
        playlistURL: URL, targetDurationMs: Int64?, unchangedForMs: Int64?,
        servedCacheControl: String?, likelyCause: LikelyCause?
    ) {
        self.playlistURL = playlistURL
        self.targetDurationMs = targetDurationMs
        self.unchangedForMs = unchangedForMs
        self.cacheBypassingReloads = 0
        self.servedCacheControl = servedCacheControl
        self.likelyCause = likelyCause
    }

    /// The Kotlin `describe`, saying what was observed and leaving out what was not.
    public var description: String {
        var observed = "The live playlist \(playlistURL.absoluteString) stopped gaining segments"
        if let unchangedForMs, let targetDurationMs {
            observed += " for \(unchangedForMs) ms (target duration \(targetDurationMs) ms)"
        }
        observed += "; no reload can ask past a cache on this platform."
        let served = servedCacheControl.map { " It was served Cache-Control: \($0)." } ?? ""
        let likely =
            switch likelyCause {
            case .intermediaryCache:
                " Likely a stale copy held by an intermediary cache rather than a dead stream."
            case .origin:
                " Nothing served points at a cache; likely the origin has stopped publishing."
            case nil: ""
            }
        return observed + served + likely
    }
}
