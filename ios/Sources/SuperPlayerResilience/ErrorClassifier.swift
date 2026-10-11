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

/// The entry AVFoundation's error log wrote for a failed request — the fields of an
/// `AVPlayerItemErrorLogEvent` the classifier reads, as a value a test and a consumer can make.
///
/// A value rather than the AVFoundation type, which has no public initializer: a classifier that
/// could be handed only what an item had logged could be tested only by failing an item, and the
/// rows below are each held to a real `NSError` without playing anything.
public struct LoggedFailure: Sendable, Hashable {

    /// The request that failed, or nil where AVFoundation logged none — which it does for a segment
    /// it asked for several times (observed, #419: a segment refused 404 or 500 on every attempt is
    /// logged with no URI, one refused 401, 403, 410 or 416 once is logged naming it).
    public let uri: URL?

    /// The entry's code: on the iOS 27 runtime the `CoreMediaErrorDomain` code naming the failure —
    /// `-12938` for an HTTP 404 — rather than the HTTP status itself.
    public let errorStatusCode: Int

    /// The domain ``errorStatusCode`` belongs to.
    public let errorDomain: String

    public init(uri: URL?, errorStatusCode: Int, errorDomain: String) {
        self.uri = uri
        self.errorStatusCode = errorStatusCode
        self.errorDomain = errorDomain
    }

    /// The fields of `event`, the error log's own entry.
    init(_ event: AVPlayerItemErrorLogEvent) {
        self.init(
            uri: event.uri.flatMap(URL.init(string:)), errorStatusCode: event.errorStatusCode,
            errorDomain: event.errorDomain)
    }
}

/// The single place a failure acquires a meaning (ADR-0011 rule 1) — the Kotlin `ErrorClassifier`,
/// over AVFoundation's evidence instead of Media3's.
///
/// Pure: a failure in, a ``FailureClass`` out, nothing read from a player and nothing remembered.
/// It never throws and always returns a class, for any input — rule 2's "zero unclassified" is a
/// property of this function, which is why there is no unknown class to fall into.
///
/// ## What it reads
///
/// In this order, each step stronger evidence than the one after it, as the Kotlin steps are:
///
/// 1. **What core already concluded** — the Kotlin first step: a ``StaleLivePlaylistError`` core
///    raised for a live playlist that stopped advancing (#421), *mapped* by reading the
///    ``StaleLivePlaylistError/likelyCause`` core filled in rather than re-derived — see
///    ``namedByCore(_:)``. Core's other Kotlin conclusion, `LiveWindowTooShortException`, is a DASH
///    judgement and has nothing to read here, since `AVPlayer` plays no DASH (ADR-0020 rule 6).
/// 2. **The load that failed.** Where Android reads the `LoadKind` core stamped on the request,
///    here the error log's entry is the evidence (`docs/ios-plan.md` §3.5): the HTTP status, from
///    the `CoreMediaErrorDomain` code AVFoundation reports it as, and *which* request it was, from
///    the entry's URI — see ``failedLoad(_:playlistURL:)``. A segment refused 401, 403 or 404 while
///    its playlist loaded is ``FailureClass/Transient/cdnEdge``; a playlist refused the same way says
///    nothing narrower than the network and falls through, as an unstamped Kotlin refusal does.
/// 3. **The engine's own code**, the last resort and the total one, over the whole error chain from
///    the cause up — ``fromCodes(_:)``, the only `switch` over engine codes in the package. A second
///    one anywhere is a bug against rule 1.
///
/// ``FailureClass/Fatal/unsupported`` is reached only from a code that says *unsupported*, never
/// from the fall-through, for the Kotlin reason: an unrecognised code is far likelier a transfer that
/// can be retried than content that can never play.
///
/// ## Which leaves this platform reaches
///
/// Both ``FailureClass/Transient`` leaves, both ``FailureClass/Content`` leaves,
/// ``FailureClass/Device/decoderInit`` and ``FailureClass/Device/decoderTransient``,
/// ``FailureClass/Drm/unsupported``, ``FailureClass/Storage/full`` and
/// ``FailureClass/Fatal/unsupported``, and two `Drm` leaves from core's ``LicenceExchangeError`` —
/// the FairPlay exchange `SuperPlayerDRM` makes (#422): ``FailureClass/Drm/licenceAcquisition`` for a
/// certificate or licence the server did not give, ``FailureClass/Drm/systemError`` for a key request
/// the device would not compose — and ``FailureClass/Drm/licenceExpired`` from core's
/// ``OfflineLicenceExpiredError``, a stored licence refused before playback (#423).
/// ``FailureClass/Drm/downgradeRefused`` has no evidence, FairPlay having no levels to refuse;
/// ``FailureClass/Drm/provisioning`` and ``FailureClass/Device/secureDecoderInit`` have none on this
/// platform at all, and the taxonomy's doc comment says why they are declared anyway.
public enum ErrorClassifier {

    /// Far past any chain AVFoundation builds — an item's error is two deep, the CoreMedia code
    /// under a Foundation one — so the bound costs no evidence; it is there so a chain that loops
    /// cannot turn a failure into a second one. The Kotlin `MAX_CAUSE_DEPTH`.
    static let maxCauseDepth = 32

    /// The statuses an edge returns for an object it will not or cannot serve while the playlist
    /// naming it is fine — the Kotlin `EDGE_REFUSALS`.
    ///
    /// ref: RFC 9110 §15.5.2 (401), §15.5.4 (403), §15.5.5 (404)
    static let edgeRefusals: Set<Int> = [401, 403, 404]

    /// ref: RFC 9110 §15.5.17, Range Not Satisfiable — the object shorter than the range the
    /// description asked for, which is the media not there as promised.
    static let rangeNotSatisfiable = 416

    /// What `error` is.
    ///
    /// - Parameters:
    ///   - error: AVFoundation's error for the failure — the item's `error`, or the one a
    ///     failed-to-play-to-end notification carried — or anything at all.
    ///   - loggedFailure: the error log's last entry at the failure, or nil where it has none,
    ///     which AVFoundation leaves for a refused multivariant playlist (observed, #419).
    ///   - playlistURL: the asset's own URL, the multivariant playlist, which is a playlist whatever
    ///     its path says.
    public static func classify(
        _ error: any Error, loggedFailure: LoggedFailure? = nil, playlistURL: URL? = nil
    ) -> FailureClass {
        let evidence = evidence(error, loggedFailure)
        return namedByCore(evidence) ?? namedByTheFailedLoad(evidence, loggedFailure, playlistURL)
            ?? fromCodes(evidence)
    }

    /// The class of a defect core detected and named, or nil when it named none — the Kotlin
    /// `namedByCore`, for the one conclusion core reaches on this platform.
    static func namedByCore(_ evidence: [NSError]) -> FailureClass? {
        guard let stale = staleLivePlaylist(in: evidence) else { return nil }
        switch stale.likelyCause {
        // An intermediary is serving a copy it holds while the origin carries on publishing: an
        // edge defect, and another source may hold a live copy.
        case .intermediaryCache: return .transient(.cdnEdge)
        // Nothing served points at a cache, so the segments the playlist promised are not being
        // produced, and asking the same origin again finds the same hole.
        case .origin: return .content(.segmentGap)
        // Below iOS 18 no response is readable, so which party is unknown: the edge, for the reason
        // the -12888 row in ``named(_:)`` gives — the commoner cause (`PRD.md` §3.3), and a class
        // that reaches rung 4 as the other does.
        case nil: return .transient(.cdnEdge)
        }
    }

    /// The likely party a ``StaleLivePlaylistError`` in `error`'s chain named, or nil — the Kotlin
    /// `likelyPartyIn`, read for ``SuperPlayerError/likelyCause`` and not a classification.
    static func likelyCause(in error: NSError) -> StaleLivePlaylistError.LikelyCause? {
        staleLivePlaylist(in: causeChain(error))?.likelyCause
    }

    /// The first ``StaleLivePlaylistError`` in `evidence`, which bridges back from the `NSError`
    /// core handed over.
    private static func staleLivePlaylist(in evidence: [NSError]) -> StaleLivePlaylistError? {
        evidence.lazy.compactMap { ($0 as any Error) as? StaleLivePlaylistError }.first
    }

    /// The statuses a CDN answers a credential it will not accept with, and the only two a
    /// ``HeaderProvider`` is asked about — the Kotlin `TokenRefreshLayer`'s pair.
    ///
    /// ref: RFC 9110 §15.5.2 (401) and §15.5.4 (403)
    static let credentialRefusals: Set<Int> = [401, 403]

    /// The status of a refused credential in `error`'s evidence — 401 or 403, on any request, a
    /// playlist's or a segment's — or nil where the failure was anything else. Read here, beside
    /// ``classify(_:loggedFailure:playlistURL:)``, because it is a reading of AVFoundation's codes
    /// and this is the one place they are read (ADR-0011 rule 1).
    static func refusedCredentialStatus(_ error: any Error, loggedFailure: LoggedFailure?) -> Int? {
        evidence(error, loggedFailure).lazy.compactMap(httpStatus).first {
            credentialRefusals.contains($0)
        }
    }

    /// `error`'s cause chain, with the error log's entry after it as the last word.
    private static func evidence(_ error: any Error, _ loggedFailure: LoggedFailure?) -> [NSError] {
        var evidence = causeChain(error as NSError)
        if let loggedFailure {
            evidence.append(
                NSError(domain: loggedFailure.errorDomain, code: loggedFailure.errorStatusCode))
        }
        return evidence
    }

    /// `error` and its underlying errors, outermost first, bounded and cycle-safe.
    static func causeChain(_ error: NSError) -> [NSError] {
        var chain: [NSError] = []
        var current: NSError? = error
        while let next = current, chain.count < maxCauseDepth,
            !chain.contains(where: { $0 === next })
        {
            chain.append(next)
            current = next.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return chain
    }

    /// Which kind of request a failure with `logged` was — the iOS reading of the Kotlin
    /// `LoadKind` stamp, from the only evidence AVFoundation leaves.
    ///
    /// - **No entry** is the asset's own URL: AVFoundation logs nothing for the multivariant
    ///   playlist it was handed, which fails the item before any rendition is loaded (observed,
    ///   #419).
    /// - **An entry naming a playlist** — the asset's URL, or a path ending `.m3u8` or `.m3u` — is a
    ///   playlist. ref: RFC 8216 §4, a playlist is identified by that path extension or by its
    ///   `Content-Type`; a server naming playlists by type alone is read as serving segments, which
    ///   is the reading's one gap.
    /// - **An entry naming anything else** is a segment.
    /// - **An entry naming nothing** is a segment too: observed, #419, AVFoundation logs a segment
    ///   it asked for repeatedly without its URI, while every playlist entry seen named its playlist.
    ///   An observation of the platform, held by `TypedErrorPlaybackTests` so a runtime that changes
    ///   it fails a test rather than a classification.
    static func failedLoad(_ logged: LoggedFailure?, playlistURL: URL?) -> FailedLoad {
        guard let logged else { return .playlist }
        guard let uri = logged.uri else { return .segment }
        let playlistExtensions: Set = ["m3u8", "m3u"]
        if uri == playlistURL || playlistExtensions.contains(uri.pathExtension.lowercased()) {
            return .playlist
        }
        return .segment
    }

    /// The two kinds of request ``failedLoad(_:playlistURL:)`` tells apart.
    enum FailedLoad { case playlist, segment }

    /// The class the failed load's status and kind settle, or nil when they settle nothing — the
    /// Kotlin `namedByTheFailedLoad`, row for row.
    static func namedByTheFailedLoad(
        _ evidence: [NSError], _ logged: LoggedFailure?, _ playlistURL: URL?
    ) -> FailureClass? {
        guard let status = evidence.lazy.compactMap(httpStatus).first,
            failedLoad(logged, playlistURL: playlistURL) == .segment
        else { return nil }
        // A segment refused or missing while the playlist that named it loaded fine: an expired
        // token or an edge miss (`PRD.md` §3.3).
        if edgeRefusals.contains(status) { return .transient(.cdnEdge) }
        // The object is shorter than the range asked for: the media not there as promised.
        if status == rangeNotSatisfiable { return .content(.segmentGap) }
        // A 5xx, a 410, a 429: the CDN unwell rather than this object wrong, as the codes say too.
        return nil
    }

    /// The HTTP status `error` reports, or nil — the one place an AVFoundation code is read as a
    /// status, so a status is read the same way whether it arrived on the item's error or in its log.
    ///
    /// `CoreMediaErrorDomain` reports a refused request as a code of its own per status — in every
    /// failure #419 forced, never the status itself — and only the codes observed are read: each was forced
    /// by #419 at the loopback origin on the iOS 27.1 simulator, with the status in the error's own
    /// description beside it (`"CoreMediaErrorDomain error -12938 - HTTP 404: File Not Found"`). A
    /// status no row names says nothing narrower, and the codes decide.
    static func httpStatus(_ error: NSError) -> Int? {
        guard error.domain == coreMediaErrorDomain else { return nil }
        switch error.code {
        case -16840: return 401
        case -12660: return 403
        case -12938: return 404
        case -12668: return 410
        case -12939: return 416
        case -16847: return 500
        default: return nil
        }
    }

    /// `CoreMediaErrorDomain`, which no SDK header declares as a constant.
    static let coreMediaErrorDomain = "CoreMediaErrorDomain"

    /// The class the engine's codes decide: the first error in `evidence`, from the cause up, that a
    /// row below names, else the fall-through — the Kotlin `fromBand`.
    ///
    /// From the cause up rather than from the outermost error, which is the Kotlin order reversed and
    /// for the same reason it has its order: the more informed error wins. AVFoundation wraps the
    /// CoreMedia code that says what went wrong in a Foundation error that translates it coarsely —
    /// a malformed playlist's `-12642` reaches the item under a generic code — so the cause is the
    /// informed one here, where Media3's outer exception was there.
    ///
    /// Apple numbers no bands, so there is no band fall-through to land a new code beside its
    /// neighbours; every code no row names is ``FailureClass/Transient/network``, the Kotlin `else`
    /// branch and rule 2's honest default.
    static func fromCodes(_ evidence: [NSError]) -> FailureClass {
        for error in evidence.reversed() {
            if let named = named(error) { return named }
        }
        return .transient(.network)
    }

    /// The class `error`'s own domain and code name, or nil when they name none.
    ///
    /// ref: https://developer.apple.com/documentation/avfoundation/averror/code — each
    /// `AVFoundationErrorDomain` row's meaning; https://developer.apple.com/documentation/foundation/urlerror/code
    /// for `NSURLErrorDomain`'s. The `CoreMediaErrorDomain` rows are observed, each where it is read.
    static func named(_ error: NSError) -> FailureClass? {
        switch error.domain {
        case AVFoundationErrorDomain:
            switch AVError.Code(rawValue: error.code) {
            // The engine naming content it will never play: the only door to `Fatal.Unsupported`
            // from AVFoundation's own codes.
            case .fileFormatNotRecognized, .formatUnsupported: return .fatal(.unsupported)
            // Malformed media is the object, not the description.
            case .failedToParse: return .content(.segmentGap)
            // The media changed under a player that had started it: not there as promised.
            case .noLongerPlayable: return .content(.segmentGap)
            // No decoder for this format: another variant or source may have one.
            case .decoderNotFound: return .device(.decoderInit)
            // A decoder that was working stopped, or the media services under every decoder were
            // reset — the case `docs/ios-plan.md` §3.5 names for rung 5.
            case .decoderTemporarilyUnavailable, .decodeFailed, .mediaServicesWereReset,
                .videoCompositorFailed:
                return .device(.decoderTransient)
            // Protected content this app or device may not play: asking again changes nothing,
            // another source may be protected differently.
            case .contentIsProtected, .contentIsNotAuthorized, .applicationIsNotAuthorized:
                return .drm(.unsupported)
            case .diskFull: return .storage(.full)
            default: return nil
            }
        case NSURLErrorDomain:
            // A scheme the loading system cannot fetch is the one Foundation code that says
            // unsupported. A `SuperPlayer` refuses such a source before it loads
            // (`UnplayableSourceSchemeError`), so this row is reached from a stock player's error.
            return error.code == NSURLErrorUnsupportedURL ? .fatal(.unsupported) : nil
        case NSPOSIXErrorDomain:
            // ref: POSIX.1-2017 `<errno.h>`, ENOSPC — no space left on the device.
            return error.code == Int(ENOSPC) ? .storage(.full) : nil
        case LicenceExchangeError.errorDomain:
            // The exchange is ours, so its evidence is core's error rather than an engine code
            // (ADR-0012 rule 5: `SuperPlayerDRM` raises it and classifies nothing). Read cause first
            // like everything else, so a protection stack's own AVFoundation code under a refused
            // key request — `.contentIsNotAuthorized`, say — is the more informed answer and wins.
            switch LicenceExchangeError.Stage(rawValue: error.code) {
            // The device would not compose the key request: its protection stack, with no
            // entitlement asked about — the Kotlin `SystemError` leaf's reason.
            case .keyRequest: return .drm(.systemError)
            // The round trip did not produce a certificate or a licence: retrying the same bytes
            // later commonly succeeds, which is the leaf's `isRetryable`.
            case .certificate, .licence: return .drm(.licenceAcquisition)
            case nil: return nil
            }
        case OfflineLicenceExpiredError.errorDomain:
            // A stored licence that ran out, refused before any server was asked (#423): the domain
            // says exactly what happened, as the Kotlin `ERROR_CODE_DRM_LICENSE_EXPIRED` does, so the
            // fields are not read.
            return .drm(.licenceExpired)
        case coreMediaErrorDomain:
            switch error.code {
            // A playlist AVFoundation would not parse: the corpus's dangling audio group fails with
            // it (`HostileManifestCorpusTests`, #412).
            case -12642: return .content(.manifestInvalid)
            // A live playlist that stopped changing: logged by the corpus's frozen live entry
            // (#412). Which party froze it — an intermediary's copy or an origin that stopped — is
            // the Kotlin `likelyCause`, which only the transfer chain could read; the edge is the
            // commoner cause (`PRD.md` §3.3) and both classes reach rung 4, so the edge it is.
            case -12888: return .transient(.cdnEdge)
            default: return nil
            }
        default:
            return nil
        }
    }
}
