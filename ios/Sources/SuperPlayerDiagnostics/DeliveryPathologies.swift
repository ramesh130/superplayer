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
import SuperPlayerCore

/// What a stream's *delivery* can get wrong: the defects that are in no document and that no parse
/// can see — the Kotlin `DeliveryPathologies`, rule for rule, whose KDoc argues each.
///
/// A `Cache-Control` the playlist and its segments disagree about, a token scoped to the manifest
/// and not to the media, a CORS configuration that refuses the request a player has to make — each
/// is a fact about a transfer rather than about a manifest, so each is readable only because the
/// doctor fetches what a player of this request would fetch, carrying the credential that player
/// carries (ADR-0015 rule 7, which on Apple platforms is a `URLSession` of the doctor's own:
/// `ManifestExamination` says why).
///
/// ## What it reads, and what it costs
///
/// The response headers of the playlists the examination was fetching anyway, and — for the one
/// rule that needs two parties before it can name the wrong one — the headers of a *single*
/// segment, read without reading any of its bytes. That probe is rule 7's #289 addendum, and this
/// file is where the decision to spend it is made: the supplier arrives as a closure, and a rule
/// calls it only once it already has a defect to attribute. So a stream whose playlist is cached
/// correctly costs exactly what it cost before.
///
/// Every header map here has its names lowercased, as `ManifestExamination` keeps them: a field
/// name is case-insensitive (RFC 9110 §5.1), so lowering it once is the whole of reading it so.
enum DeliveryPathologies {

    /// What the delivery of one media playlist got wrong, given the headers it arrived with.
    ///
    /// `source` is the URL the app handed the doctor — the one a signing step is applied to — and
    /// `playlist`'s segments are already resolved against the URL it was read from.
    /// `segmentHeaders` answers the headers of a segment, or nil where that transfer was refused; it
    /// is called at most once per media playlist, and only by a rule that already has something to
    /// attribute.
    static func inDelivery(
        source: URL, playlist: MediaPlaylist, playlistHeaders: [String: String],
        now: Date = Date(),
        segmentHeaders: (URL) async -> [String: String]?
    ) async -> [Finding] {
        var findings: [Finding] = []
        if let cached = await cachedLivePlaylist(playlist, playlistHeaders, segmentHeaders) {
            findings.append(cached)
        }
        if let scoped = tokenScopedToManifest(source: source, playlist: playlist) {
            findings.append(scoped)
        }
        if let expiring = tokenExpiringInWindow(source: source, playlist: playlist, now: now) {
            findings.append(expiring)
        }
        return findings
    }

    /// A live media playlist a shared cache may hold past the point at which it is late, and the
    /// segments that say the opposite — the Kotlin `cachedLivePlaylist`.
    ///
    /// **The comparison is core's**: `StalePlaylistJudgement.cachedPastTheUpdateBoundSeconds`
    /// already decides, for the player's own stale-playlist watch (#421), whether a served
    /// `Cache-Control` outlives RFC 8216 §6.2.1's update bound, and ADR-0015 rule 6 makes a copy of
    /// it a bug — ADR-0020 rule 8's #428 addendum is where this platform's doctor stopped holding the
    /// judgement alone. What this rule adds is the second party: a player meets the defect as a
    /// playlist that stopped advancing, while a doctor can say *which of the two responses is
    /// misconfigured*, which is the whole of the fix.
    ///
    /// **The playlist is the wrong one, always, and the segment's reading is the evidence rather
    /// than the test.** A segment is immutable once published, so caching it long is correct; a live
    /// media playlist is the one document that changes. Nothing therefore branches on what the
    /// segment said, and what its reading changes is the sentence: the pair backwards, both held, or
    /// the segment silent about caching.
    ///
    /// ``FindingSeverity/blocking``: the client reloads a playlist that cannot change, and on a
    /// player configured with resilience the session ends on a `StaleLivePlaylistError` naming this
    /// very header — the same defect met from the other end. A playlist that is not live, or that
    /// states no target duration to bound it by, is passed over.
    private static func cachedLivePlaylist(
        _ playlist: MediaPlaylist, _ headers: [String: String],
        _ segmentHeaders: (URL) async -> [String: String]?
    ) async -> Finding? {
        guard playlist.isLive, let targetSeconds = playlist.targetDurationSeconds,
            let heldSeconds = StalePlaylistJudgement.cachedPastTheUpdateBoundSeconds(
                headers[cacheControl], targetDurationMs: Int64(targetSeconds) * 1_000)
        else { return nil }
        var served: String?
        if let segment = playlist.segments.first?.url {
            served = await segmentHeaders(segment)?[cacheControl]
        }
        let held =
            "the playlist, not its segments: held for \(Magnitudes.seconds(Double(heldSeconds))) "
            + "against a \(Magnitudes.seconds(Double(targetSeconds))) target duration"
        return Finding(
            pathology: .hlsCachedLivePlaylist, severity: .blocking,
            magnitude: served.map { "\(held), while its segments are served \"\($0)\"" }
                ?? "\(held); its segments said nothing about caching")
    }

    /// A manifest fetched from a signed URL whose segments are addressed without one — the Kotlin
    /// `tokenScopedToManifest`.
    ///
    /// ref: RFC 3986 §5.3 — a relative reference is resolved against the base URI's path, and the
    /// query component is not part of it. A packager that signs the URL the app is handed and names
    /// its segments relatively publishes media addressed with no credential at all, which a CDN
    /// enforcing the signature refuses (RFC 9110 §15.5.4).
    ///
    /// ``FindingSeverity/blocking``: the stream opens and then serves nothing, and no rung repairs
    /// it. The magnitude counts the segments, because the count says it was the whole rendition.
    /// **A playlist where even one segment is signed is passed over**, deliberately and as on
    /// Android: a signing step that is running and partly wrong is a different report, and naming it
    /// here would make the magnitude's whole-rendition claim untrue.
    private static func tokenScopedToManifest(source: URL, playlist: MediaPlaylist) -> Finding? {
        guard expirySeconds(of: source) != nil, !playlist.segments.isEmpty,
            !playlist.segments.contains(where: { expirySeconds(of: $0.url) != nil })
        else { return nil }
        return Finding(
            pathology: .hlsTokenScopedToManifest, severity: .blocking,
            magnitude: "the manifest is signed and its \(playlist.segments.count) segments are not")
    }

    /// A token whose own expiry falls inside the content it was minted for — the Kotlin
    /// `tokenExpiringInWindow`.
    ///
    /// ref: RFC 3986 §3.4 — a signed URL states its expiry in the clear, as a query parameter, so an
    /// intermediary can refuse an expired request without asking the signer. That shared part of the
    /// convention is the only part read: nothing is verified and no scheme is reproduced.
    ///
    /// **The window is the content, doubled** (``viewingContentDurations``): less life left than the
    /// content is ``FindingSeverity/blocking`` — no viewer reaches the end — and less than twice it
    /// is ``FindingSeverity/degraded``, lost to a viewer who pauses for as long again. Blocking on a
    /// magnitude, unlike every document defect, because at that value the media stops arriving for
    /// every viewer: the number decides *whether* the stream ends, not how well it plays.
    ///
    /// Live playlists are passed over, and that is the rule rather than a gap: a live stream has no
    /// end to cover, so every finite token expires inside it.
    private static func tokenExpiringInWindow(source: URL, playlist: MediaPlaylist, now: Date)
        -> Finding?
    {
        guard !playlist.isLive, let expiry = expirySeconds(of: source) else { return nil }
        let content = playlist.segments.reduce(0) { $0 + ($1.durationSeconds ?? 0) }
        let remaining = Double(expiry) - now.timeIntervalSince1970.rounded(.down)
        let severity: FindingSeverity
        if remaining < content {
            severity = .blocking
        } else if remaining < viewingContentDurations * content {
            severity = .degraded
        } else {
            return nil
        }
        return Finding(
            pathology: .hlsTokenExpiringInWindow, severity: severity,
            magnitude: "\(Magnitudes.seconds(remaining)) of token left over "
                + "\(Magnitudes.seconds(content)) of content")
    }

    /// A response whose CORS headers refuse a credentialed request, over every playlist response
    /// this examination read — the Kotlin `inResponses`.
    ///
    /// spec: WHATWG Fetch §3.3.5 — where a request's credentials mode is "include", §4.10's CORS
    /// check fails unless `Access-Control-Allow-Origin` is the request's own origin, so a wildcard
    /// fails it whatever `Access-Control-Allow-Credentials` says. The pair is a refusal by
    /// construction, which is why it can be called wrong without knowing which origin is asking.
    ///
    /// **A response that says nothing about CORS is not a misconfiguration**: most origins serving
    /// native players emit no CORS headers and are correct, and flagging them would be the false
    /// positive ADR-0015 rule 12 scores a doctor on. ``FindingSeverity/blocking`` and no magnitude:
    /// a configuration refuses the request or it does not.
    ///
    /// **It reads the playlists' responses and not the media's**, the Kotlin rule's named gap: a
    /// segment's response is seen only where the cache rule spent its probe, so reading it here
    /// would make the CORS answer depend on whether an unrelated rule fired.
    static func inResponses(_ responses: [[String: String]]) -> [Finding] {
        let refuses = responses.contains { headers in
            headers[allowOrigin]?.trimmingCharacters(in: .whitespaces) == anyOrigin
                && headers[allowCredentials]?.trimmingCharacters(in: .whitespaces)
                    .caseInsensitiveCompare(allowed) == .orderedSame
        }
        return refuses
            ? [Finding(pathology: .hlsCorsRefusesCredentials, severity: .blocking, magnitude: nil)]
            : []
    }

    /// The instant a signed `url` says it stops being accepted, in seconds since the epoch, or nil.
    private static func expirySeconds(of url: URL) -> Int64? {
        URLComponents(url: url, resolvingAgainstBaseURL: true)?.queryItems?
            .first { $0.name == expires }?.value.flatMap { Int64($0) }
    }

    private static let cacheControl = "cache-control"
    private static let allowOrigin = "access-control-allow-origin"
    private static let allowCredentials = "access-control-allow-credentials"

    /// spec: WHATWG Fetch §3.2.3 — the wildcard an origin emits when it means to allow everyone.
    private static let anyOrigin = "*"

    /// spec: WHATWG Fetch §3.2.4 — `Access-Control-Allow-Credentials` has exactly this one true
    /// value.
    private static let allowed = "true"

    /// The query parameter a signed URL states its own expiry in, and this file's whole definition
    /// of "signed at all" — the Kotlin `EXPIRES`, whose KDoc carries the derivation and its limit:
    /// an expiry an intermediary can enforce without asking the signer has to be readable in the
    /// clear, and seconds since the epoch is the form a time takes in a URL. Matched **exactly and
    /// in lower case**, so a URL signed under any other spelling, or with its expiry inside an opaque
    /// blob, is invisible to both rules — reported as nothing rather than as healthy. A list of
    /// guessed names would be reproducing particular CDNs' schemes, which `CONTRIBUTING.md` forbids.
    private static let expires = "expires"

    /// How many of the content's own durations a token has to cover before it is not worth
    /// reporting: the content, and as long again for the pauses a viewer takes inside it — derived
    /// rather than cited, because a signed URL's lifetime is a CDN's setting and no document speaks
    /// to it. The Kotlin `VIEWING_CONTENT_DURATIONS`.
    private static let viewingContentDurations = 2.0
}
