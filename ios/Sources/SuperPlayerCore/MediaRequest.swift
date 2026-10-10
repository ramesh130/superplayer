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

/// What to play, described as content rather than as a URL.
///
/// A URL answers *where* the bytes are. A `MediaRequest` answers *what is being played*, and keeps
/// that answer separate from the location it currently happens to be served from:
///
/// ```swift
/// player.setMediaRequest(
///     MediaRequest(
///         contentId: "catalog:episode:1138",
///         sources: [
///             URL(string: "https://cdn-a.example.com/1138/master.m3u8")!,
///             URL(string: "https://cdn-b.example.com/1138/master.m3u8")!,
///         ],
///         startPosition: .resumeFromLastKnown))
/// ```
///
/// The port of `superplayer-core`'s `MediaRequest` (`docs/ios-plan.md` §3.1, *Ports*), and its
/// reasons are that type's, said again here in brief because a reader of this file has no copy of
/// the Kotlin:
///
/// - **Identity is not the URL.** One piece of content has many URLs — a second CDN host after a
///   failover, a re-signed query string — so anything keyed on the URL misses what it already
///   holds, and a pipeline sessionized on it reports one viewing as several. ``contentId`` is the
///   caller's own stable identifier, and it is what resume positions are keyed on today.
/// - **More than one source.** ``sources`` is ordered and playback begins at the first; the rest are
///   what rung 4 of ADR-0011's ladder opens, which on iOS arrives with `SuperPlayerResilience`. A
///   player without that product opens the first source and nothing else.
/// - **It carries what to display**, because content that leaves the app — the lock screen,
///   CarPlay, the Now Playing centre — has to describe itself, and none of those surfaces can ask
///   the app.
///
/// A value type, as `docs/ios-plan.md` §3.1 has it, and therefore `Codable`, `Sendable` and
/// `Hashable` where the Kotlin class is none of them: a ``PlaybackSnapshot`` carries one, and a consumer
/// stores that wherever it chooses (ADR-0006 rule 2). It names no AVFoundation type, because its
/// meaning is a shared document's (ADR-0020 rule 3).
public struct MediaRequest: Codable, Sendable, Hashable {

    /// The caller's stable identifier for the content, in whatever namespace the caller already
    /// uses. Never blank. It should be stable across CDN hosts, bitrates and re-signed URLs — that
    /// stability is the whole value of it — which is why a source URL makes a poor one, though
    /// nothing here can check that a caller has chosen well.
    public let contentId: String

    /// Candidate locations for the content, most preferred first. Never empty: playback starts at
    /// the first and falls back to the next only when every rung below it has failed.
    public let sources: [URL]

    /// Where playback of this request begins. Defaults to ``StartPosition/beginning``.
    public let startPosition: StartPosition

    /// What to call this content wherever it is displayed outside the app. Nil shows nothing.
    public let title: String?

    /// The second line beneath ``title`` — a series name, a channel, a byline.
    public let subtitle: String?

    /// Artwork for the content, as a URL the system fetches rather than the app.
    ///
    /// Carried and not yet displayed: the item's external metadata takes artwork as image *data*,
    /// which nothing here fetches, and the surface that takes a URL is the Now Playing session's
    /// (#403), which is where this one is read.
    public let artworkURL: URL?

    /// Describes `contentId`, played from `sources` in order of preference.
    ///
    /// A blank `contentId`, an empty `sources` or a negative ``StartPosition/at(positionMs:)`` is a
    /// programming error and traps, as the Kotlin builder's `require` throws; decoding the same
    /// value throws `DecodingError.dataCorrupted` instead, because a stored value is input rather
    /// than code.
    public init(
        contentId: String,
        sources: [URL],
        startPosition: StartPosition = .beginning,
        title: String? = nil,
        subtitle: String? = nil,
        artworkURL: URL? = nil
    ) {
        if let problem = Self.problem(contentId: contentId, sources: sources, start: startPosition)
        {
            preconditionFailure(problem)
        }
        self.contentId = contentId
        self.sources = sources
        self.startPosition = startPosition
        self.title = title
        self.subtitle = subtitle
        self.artworkURL = artworkURL
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let contentId = try container.decode(String.self, forKey: .contentId)
        let sources = try container.decode([URL].self, forKey: .sources)
        let startPosition = try container.decode(StartPosition.self, forKey: .startPosition)
        if let problem = Self.problem(contentId: contentId, sources: sources, start: startPosition)
        {
            throw DecodingError.dataCorrupted(
                DecodingError.Context(codingPath: decoder.codingPath, debugDescription: problem))
        }
        self.contentId = contentId
        self.sources = sources
        self.startPosition = startPosition
        self.title = try container.decodeIfPresent(String.self, forKey: .title)
        self.subtitle = try container.decodeIfPresent(String.self, forKey: .subtitle)
        self.artworkURL = try container.decodeIfPresent(URL.self, forKey: .artworkURL)
    }

    /// What is wrong with a request made of these, or nil — the Kotlin builder's three `require`s,
    /// in one place so that constructing and decoding cannot disagree about what a request is.
    private static func problem(contentId: String, sources: [URL], start: StartPosition)
        -> String?
    {
        if contentId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "A MediaRequest needs a non-blank content id"
        }
        if sources.isEmpty { return "A MediaRequest for '\(contentId)' needs at least one source" }
        if case .at(let positionMs) = start, positionMs < 0 {
            return "A start position must not be negative, was \(positionMs)"
        }
        return nil
    }

    /// Where playback begins, as a decision rather than as a number a caller has to compute.
    ///
    /// The alternative — an optional start time — cannot express "resume", so every consumer ends up
    /// hand-rolling the same seek-when-ready observation, and each gets a slightly different answer
    /// for content that had already finished.
    ///
    /// Its encoded shape is Swift's synthesized one, keyed by case name, and it is a stored format:
    /// a ``PlaybackSnapshot`` persists a request, and `PlaybackSnapshotTests` pins the document, so
    /// renaming a case is a format change that fails a test.
    public enum StartPosition: Codable, Sendable, Hashable {

        /// The content's own default position: the start of on-demand content, and wherever
        /// `AVPlayer` joins a live stream. Deliberately not "zero", which is not where a live stream
        /// should begin.
        case beginning

        /// An explicit position, in milliseconds from the start of the content; never negative.
        ///
        /// Milliseconds rather than a `CMTime` or a `Duration`, because the shared documents and the
        /// Kotlin type state positions in milliseconds, and a value a snapshot carries between the
        /// two platforms' documents should read the same on both. A position past the content's end
        /// is not reinterpreted as the start: content that instantly ends shows a stored position
        /// was wrong, where content that quietly restarts looks like a successful playback of the
        /// wrong thing.
        case at(positionMs: Int64)

        /// Resume where this request's ``MediaRequest/contentId`` was last left, or
        /// ``beginning`` if this player has no position for it.
        ///
        /// "Last known" means what this ``SuperPlayer`` observed: positions are remembered in
        /// memory, for the life of the player, and not persisted — outliving the player is what the
        /// ``PlaybackSnapshot`` is for, and where it is kept is the consumer's decision (ADR-0006 rule 2).
        /// The memory is bounded to the ``SuperPlayer/maxRememberedPositions`` most recently used
        /// content ids, so a session moving through more content than that resumes the oldest of it
        /// from the beginning: a best effort rather than a guarantee, which is the honest contract
        /// for something a consumer has not asked to be stored anywhere.
        ///
        /// Content that played to its end resumes from the beginning: resuming to the end would
        /// produce a player that immediately ends again, which is nobody's intent.
        case resumeFromLastKnown
    }
}
