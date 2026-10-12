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

/// What a doctor found in one piece of content, which chain it looked over, and — for a postmortem
/// — what the session ended on: the Kotlin `DiagnosticReport`, whose KDoc argues the shape.
///
/// **One report type, for preflight and for postmortem alike** (ADR-0015 rule 8). This is what
/// ``MediaSourceDoctor/examine(_:classification:)`` answers about a `MediaRequest` before a player
/// is built; examining the request a *failed* session was playing answers the same type, carrying
/// the classification that session already ended on beside — never instead of — these findings.
///
/// A report with no findings is the ordinary answer for healthy content and is not an absence of
/// information: it is the doctor saying that nothing it can name is wrong with this manifest.
///
/// **Not `Equatable`**, as the Kotlin class defines no equality either: ``classification`` carries
/// the engine's own `NSError`, whose equality is no statement about a session. A reader compares
/// what it reads — the ``findings``, which are `Hashable`, and the classification's class.
public struct DiagnosticReport: Sendable, CustomStringConvertible {

    /// The `MediaRequest.contentId` examined — the identity the app already speaks in, never a URL.
    public let contentId: String

    /// What was found, in the order the examination made it, at most one per ``Pathology``. No
    /// order is promised beyond that: severity is not a sort key, because a report is read as a list
    /// of facts rather than as a ranking.
    public let findings: [Finding]

    /// Which of the fetch's optional layers this examination travelled, as the doctor was configured
    /// — ADR-0015's *Consequences* asks a report to say so, because two apps configured differently
    /// can be told different things about one manifest, and that should read as the difference it is
    /// rather than as a defect of the doctor. See ``ChainLayer``.
    public let chain: Set<ChainLayer>

    /// What ended the session this report is the postmortem of, or nil where no session ended —
    /// which is every preflight, and is also a session that failed on a player configured without
    /// `SuperPlayerResilience`, whose `classify(_:)` answers nil (ADR-0011 rule 14).
    ///
    /// **Read, never re-derived** (ADR-0015 rule 5). It is exactly the `SuperPlayerError` handed to
    /// ``MediaSourceDoctor/examine(_:classification:)``, which a consumer got from
    /// `player.classify(error)` or off `player.errors`. The classifier stays the single place a
    /// failure acquires a meaning (ADR-0011 rule 1), and this product depends on no classifier and
    /// holds no taxonomy with which to recompute one.
    ///
    /// **It keeps its own vocabulary, and ``findings`` keep theirs.** A failure class says what to do
    /// about a session that ended; a ``Pathology`` says what is wrong with a stream. Nothing maps one
    /// onto the other: the two may disagree, and disagreement is the information a support engineer
    /// wants. A stream whose failure was the network's answers a classification and no finding; a
    /// stream that plays on while misconfigured answers findings and no classification at all.
    public let classification: SuperPlayerError?

    public var description: String {
        "DiagnosticReport(\(contentId), over \(chain.map(\.rawValue).sorted()), \(findings)"
            + (classification.map { ", ended on \($0.causeClass)" } ?? "") + ")"
    }
}

/// One defect of one stream: what is wrong, how far it departs from what real content does, the
/// clause it departs from, and the sentence that says why a packager or a CDN produces it — the
/// Kotlin `Finding`.
///
/// **A pathology is not a failure** (ADR-0015 rule 4). A finding carries no `FailureClass`, no
/// category, no retryability and no rung, because those are answers about acting on a session that
/// ended, and a stream with a defect has usually ended no session at all. This product does not
/// depend on `SuperPlayerResilience`, so it could not name one if it tried.
public struct Finding: Sendable, Hashable, CustomStringConvertible {

    /// Which defect this is: the stable name a log line, a support ticket and a corpus entry share.
    public let pathology: Pathology

    /// What this instance of the defect costs playback. Per finding rather than per ``pathology``,
    /// because a graded pathology's severity is a reading of *its magnitude*.
    public let severity: FindingSeverity

    /// The value the defect was found at, in words a report can print — "a 20× spread", "a window of
    /// 4 s" — or nil for a defect that is present or absent.
    public let magnitude: String?

    /// The clause the stream departs from, as a citation a reader can look up.
    public var specCitation: String { pathology.specCitation }

    /// Which misconfiguration, encoder or packager produces this, in plain language.
    public var cause: String { pathology.cause }

    public var description: String {
        "\(pathology.id) (\(severity)\(magnitude.map { ", \($0)" } ?? ""))"
    }
}

/// What a defect costs playback, which is what a consumer sorts their attention by — the Kotlin
/// `FindingSeverity`. Deliberately not the corpus's `HostileStream.Severity`, which grades how far a
/// property was pushed in test content, and never compared with one (ADR-0015 rule 4).
///
/// Ordered mildest first, so the worst of two readings is their `max`.
public enum FindingSeverity: String, Sendable, Hashable, CaseIterable, Comparable,
    CustomStringConvertible
{
    /// The stream plays correctly and something about it is untidy or fragile.
    case advisory
    /// The stream plays and something measurable is worse for the defect.
    case degraded
    /// No player of this stream can play it as its publisher intended.
    case blocking

    public static func < (lhs: FindingSeverity, rhs: FindingSeverity) -> Bool {
        lhs.rank < rhs.rank
    }

    /// Where the case sits, mildest first; the raw value is the printed name and not an order.
    private var rank: Int {
        switch self {
        case .advisory: 0
        case .degraded: 1
        case .blocking: 2
        }
    }

    public var description: String { rawValue }
}

/// An optional layer of the fetch a doctor travelled, as ``DiagnosticReport/chain`` reports it —
/// the Kotlin `ChainLayer`, with one value where that has two.
///
/// The Kotlin `CONTENT_CACHE` does not port: there is no streaming cache on Apple platforms
/// (ADR-0020 rule 6), so there is nothing a manifest could have been read from instead of the
/// network.
public enum ChainLayer: String, Sendable, Hashable, CaseIterable {

    /// A ``SuperPlayerCore/HeaderProvider`` was configured, so a 401 or 403 is offered to it once
    /// per request, as a player configured with the same provider offers one. It says the provider
    /// was there and not that it was asked.
    case headerRefresh
}

/// Every defect the doctor can name on Apple platforms, with the citation and the cause it names it
/// by — the HLS half of the Kotlin `Pathology`, its four delivery defects, its two defects of the
/// fetch, and one of its own.
///
/// The raw value is the stable kebab-case ``id``: the corpus entry's id exactly, which is the join
/// the register (ADR-0015 rule 12) scores against `SuperPlayerTestMedia`'s `HostileManifests`. The
/// words are here rather than read from the corpus, which carries the same three facts, because
/// that product is test-only and a library cannot depend on it.
///
/// **What is not here, and why.** The DASH pathologies, because DASH is not ported (ADR-0020 rule
/// 6). ``hlsShortLiveWindow`` is this platform's own: `LiveWindowDepthCheck` is a DASH judgement on
/// Android, and its HLS analog moves here (`docs/ios-plan.md` §3.1). The four *delivery*
/// pathologies (#428) — ``hlsCachedLivePlaylist``, the two token defects and
/// ``hlsCorsRefusesCredentials`` — are in no document at all, and are read off the responses the
/// examination fetched (`DeliveryPathologies`).
///
/// **On Apple platforms a finding reads a document as RFC 8216 describes it**, because the doctor's
/// parser is its own rather than the engine's (ADR-0020 rule 8): AVFoundation exposes none.
public enum Pathology: String, Sendable, Hashable, CaseIterable, Identifiable,
    CustomStringConvertible
{
    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant
    // Stream", and nothing in the RFC constrains how far apart two variants' rates may be.
    // `LadderPathologies.gap` argues the step the doctor calls a gap.
    case hlsLadderGap = "hls-ladder-gap"

    // spec: RFC 8216 §4.3.4.2 — a BANDWIDTH above the real rate is false, and no MUST requires it
    // to be accurate. `LadderPathologies.overstatedBitrate` argues what the doctor can read without
    // transferring media.
    case hlsOverstatedBitrate = "hls-overstated-bitrate"

    // spec: RFC 8216 §4.3.4.2 — "Every EXT-X-STREAM-INF tag SHOULD include a CODECS attribute".
    case hlsMissingCodecs = "hls-missing-codecs"

    // spec: RFC 8216 §4.3.4.1 and §4.3.4.2 — CODECS lists formats present in the Renditions the
    // variant names, and AUDIO names the group they are in.
    case hlsAudioGroupCodecMismatch = "hls-audio-group-codec-mismatch"

    // spec: RFC 8216 §4.3.4.2 — the AUDIO attribute's "value MUST match the value of the GROUP-ID
    // attribute of an EXT-X-MEDIA tag elsewhere in the Multivariant Playlist".
    case hlsDanglingAudioGroup = "hls-dangling-audio-group"

    // spec: RFC 8216 §4.3.2.1 and §4.3.3.1 — EXTINF need only be accurate, and nothing requires
    // segments of similar length. `HlsPathologies.raggedSegmentDurations` argues the spread.
    case hlsInconsistentSegmentDurations = "hls-inconsistent-segment-durations"

    // spec: RFC 8216 §4.3.2.3 — the EXT-X-DISCONTINUITY is required, and the tags that would place
    // the timeline after it (§4.3.2.6, §4.3.3.3) are optional.
    case hlsDiscontinuityWithoutTimeline = "hls-discontinuity-without-timeline"

    // spec: RFC 8216 §6.2.1 — a server MUST publish a new version of a live Media Playlist, carrying
    // at least one new segment, within 1.5 target durations — and RFC 9111 §5.2.2.1, which is what
    // an intermediary obeys instead. A `max-age` longer than that bound lets a shared cache answer
    // with a version of the playlist that is already late, and every actor conforms. The comparison
    // is core's own (`StalePlaylistJudgement`), asked rather than restated (ADR-0015 rule 6, ADR-0020
    // rule 8's #428 addendum), which is how the doctor's reading before playback and the
    // `StaleLivePlaylistError` a player ends on cannot drift apart.
    case hlsCachedLivePlaylist = "hls-cached-live-playlist"

    // ref: RFC 3986 §3.4 — a signed URL carries its credential in the *query component*, so it
    // belongs to that one URI; §5.3 — a relative reference is resolved against the base URI's path,
    // and the query is not part of it, so a segment named relatively is addressed with no credential
    // at all. That the CDN then refuses it is RFC 9110 §15.5.4 (403). A signing scheme is a CDN
    // convention rather than a standard, and this is the part every scheme shares.
    case hlsTokenScopedToManifest = "hls-token-scoped-to-manifest"

    // ref: RFC 3986 §3.4 again — a signed URL states its own expiry in the clear, so an intermediary
    // can refuse an expired request without asking the signer. A token whose expiry falls inside the
    // content it was minted for is refused (RFC 9110 §15.5.4) part-way through, which no rung of the
    // fallback ladder can repair: every source of one piece of content is signed by one service.
    case hlsTokenExpiringInWindow = "hls-token-expiring-in-window"

    // spec: WHATWG Fetch §3.3.5 — an `Access-Control-Allow-Origin` of `*` fails §4.10's CORS check
    // whenever the request's credentials mode is "include", whatever
    // `Access-Control-Allow-Credentials` says: a refusal by construction, and the one CORS
    // configuration that can be called wrong without knowing which origin is asking. A native
    // player never notices, which is why it needs a doctor to be seen at all.
    case hlsCorsRefusesCredentials = "hls-cors-refuses-credentials"

    // spec: RFC 8216 §6.2.2 — "The server MUST NOT remove a Media Segment from a Playlist file
    // without an EXT-X-ENDLIST tag if that would produce a Playlist whose duration is less than three
    // times the target duration" — and §6.3.3, why: a client "SHOULD NOT choose a segment that starts
    // less than three target durations from the end of the Playlist file", so a shorter window has no
    // segment a client may start at. The HLS analog of `dash-short-time-shift-buffer-depth`, which
    // is core's `LiveWindowDepthCheck` on Android; `HlsPathologies.shortLiveWindow` argues it.
    case hlsShortLiveWindow = "hls-short-live-window"

    // spec: RFC 9110 §15 — the status class of the response is what the origin or the edge said
    // about the request. Not a defect of a document, because there is none: the finding ADR-0015
    // rule 7 asks for in place of an exception.
    case manifestUnreachable = "manifest-unreachable"

    // spec: RFC 8216 §4 — the bytes arrived and are not a playlist the grammar admits. On Apple
    // platforms the reading is the doctor's own parser's (ADR-0020 rule 8), held to the three
    // refusals RFC 8216 itself tells a client to make: `PlaylistLint` lists them.
    case manifestUnreadable = "manifest-unreadable"

    /// The stable kebab-case name, protocol first: the corpus entry's id, and the join between the
    /// two.
    public var id: String { rawValue }

    /// The clause this stretches or violates — RFC 8216 for every document defect — or, for the two
    /// defects of the fetch, the clause that defines what went wrong instead. The corpus's `spec`,
    /// verbatim, for every pathology the corpus carries.
    public var specCitation: String {
        switch self {
        case .hlsLadderGap, .hlsOverstatedBitrate, .hlsMissingCodecs, .hlsDanglingAudioGroup:
            "RFC 8216 §4.3.4.2"
        case .hlsAudioGroupCodecMismatch: "RFC 8216 §4.3.4.1, §4.3.4.2"
        case .hlsInconsistentSegmentDurations: "RFC 8216 §4.3.2.1, §4.3.3.1"
        case .hlsDiscontinuityWithoutTimeline: "RFC 8216 §4.3.2.3"
        case .hlsCachedLivePlaylist: "RFC 8216 §6.2.1, §6.3.4; RFC 9111 §5.2.2.1"
        case .hlsTokenScopedToManifest: "RFC 3986 §3.4, §5.3; RFC 9110 §15.5.4"
        case .hlsTokenExpiringInWindow: "RFC 3986 §3.4; RFC 9110 §15.5.4"
        case .hlsCorsRefusesCredentials: "WHATWG Fetch §3.3.5, §4.10"
        case .hlsShortLiveWindow: "RFC 8216 §6.2.2, §6.3.3"
        case .manifestUnreachable: "RFC 9110 §15"
        case .manifestUnreadable: "RFC 8216 §4"
        }
    }

    /// Which misconfiguration, encoder or packager produces this in the field, in plain language —
    /// the corpus's `cause`, verbatim, for every pathology the corpus carries.
    public var cause: String {
        switch self {
        case .hlsLadderGap:
            "A ladder whose middle rungs were dropped to save encoding cost, or a profile "
                + "template that was only ever tested on wifi and on 3G."
        case .hlsOverstatedBitrate:
            "A packager that writes the encoder's configured *peak* rate rather than the "
                + "measured one, or a ladder whose bitrates were copied from a different mezzanine."
        case .hlsMissingCodecs:
            "A hand-written or templated multivariant playlist — the CODECS string is the one "
                + "attribute a human cannot produce without reading RFC 6381, so it is the one left out."
        case .hlsAudioGroupCodecMismatch:
            "An encoder ladder migrated from HE-AAC to AAC-LC without the manifest template "
                + "being updated — the audio group still advertises what last year's profile produced."
        case .hlsDanglingAudioGroup:
            "A packaging job whose audio rendition failed after the variant lines were "
                + "written, leaving the AUDIO reference behind with nothing to point at."
        case .hlsInconsistentSegmentDurations:
            "Segmentation driven by scene-change keyframes rather than by a fixed GOP, or an "
                + "ad-insertion pass that split one segment and left its neighbours alone."
        case .hlsDiscontinuityWithoutTimeline:
            "Mid-roll ad insertion by a stitcher that marks the splice and emits neither a "
                + "discontinuity sequence nor a program date time — the common case for server-side ads."
        case .hlsCachedLivePlaylist:
            "One CDN cache rule applied to the whole path. The live media playlist is then "
                + "held for ten minutes while the segments are never held at all, so the client reloads "
                + "a playlist that cannot change and the stream appears to freeze about thirty seconds "
                + "in — a surprising share of \"live stream freezes\" tickets are this."
        case .hlsTokenScopedToManifest:
            "A signing step applied to the URL the app was handed and to nothing below it — "
                + "the manifest is signed, its segments are addressed relatively and reach the CDN with "
                + "no credential at all, so the stream opens and then serves nothing."
        case .hlsTokenExpiringInWindow:
            "A signing service whose lifetime was set from how long a request takes rather "
                + "than from how long a viewing lasts — every URL is signed, and the signature dies "
                + "part-way through the content, so playback stops where the token ran out."
        case .hlsCorsRefusesCredentials:
            "An origin configured to \"allow everyone\" — a wildcard allowed origin emitted "
                + "beside allowed credentials — which is the one pair the CORS protocol refuses outright, "
                + "so every credentialed player of this stream is turned away and the native app never "
                + "notices."
        case .hlsShortLiveWindow:
            "A packager configured for the lowest possible latency, keeping a window of one or two "
                + "segments — or a window counted in segments by a template written for shorter ones."
        case .manifestUnreachable:
            "The manifest could not be fetched with this app's credential — an origin or an edge "
                + "that refused it, a credential it would not accept, or a host that did not answer."
        case .manifestUnreadable:
            "What arrived is not a playlist — a truncated or half-written document, an error or "
                + "consent page served with a 200, or a packager that emitted something else entirely."
        }
    }

    public var description: String { rawValue }
}
