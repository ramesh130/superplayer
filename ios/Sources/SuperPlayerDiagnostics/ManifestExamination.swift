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
import os

/// One stream's playlists, fetched and read for the defects the doctor can name — the Kotlin
/// `ManifestExamination`. This type owns the *fetching*, and `HlsPathologies` the *judgement*, so
/// that what goes on the wire is decided in one place (ADR-0015 rule 7).
///
/// **What it fetches over.** On Android the fetch enters the chain a player would load through. On
/// Apple platforms there is no such chain to enter — an `AVPlayer` loads inside the engine and
/// takes no transport (ADR-0020 rule 6) — so the doctor fetches over a `URLSession` of its own,
/// carrying what a player configured the same way carries: no headers until a request is refused
/// 401 or 403, then what the ``SuperPlayerCore/HeaderProvider`` returned, on that request and every
/// later one (the provider's own contract, as `Resilience.standard(headers:)` applies it). The
/// session is ephemeral and skips any local cache, because a doctor that answered from one would be
/// reading a playlist older than the one a player would be served. Nothing it fetches is
/// measured: no meter and no CMCD, for rule 7's reason.
struct ManifestExamination {

    let session: URLSession
    let credentials: Credentials

    /// What is wrong with the playlist at `source`, or an empty list where nothing this doctor knows
    /// is — never a throw, because "the doctor threw" is the least useful thing a support ticket can
    /// say (ADR-0015 rule 7).
    ///
    /// At most one finding per ``Pathology``, at the highest severity any playlist showed it at: a
    /// defect is a fact about the stream, and a ladder examined through four renditions is one
    /// ladder.
    func examine(_ source: URL) async -> [Finding] {
        switch await fetch(source) {
        case .refused(let finding):
            return [finding]
        case .read(.multivariant(let playlist), _):
            return Self.worstPerPathology(await examine(playlist))
        case .read(.media(let playlist), _):
            // A source that is itself a media playlist: legal HLS, and the shape a single-rendition
            // live stream is usually published in.
            return Self.worstPerPathology(HlsPathologies.inMediaPlaylist(playlist))
        }
    }

    /// A multivariant playlist and **every media playlist it names**, variants and audio
    /// renditions alike — more fetches than a player makes, deliberately, because a defect in a
    /// rung nobody started on is one every viewer whose link climbs to it meets. All of it is
    /// playlists, so a preflight costs no media. One that cannot be fetched or read is its own
    /// finding and does not stop the others.
    private func examine(_ multivariant: MultivariantPlaylist) async -> [Finding] {
        var findings = HlsPathologies.inMultivariant(multivariant)
        var mediaPlaylists: [URL: MediaPlaylist] = [:]
        var named: [URL] = []
        for url in multivariant.variants.map(\.url) + multivariant.audioRenditions.compactMap(\.url)
        where !named.contains(url) {
            named.append(url)
        }
        for url in named {
            switch await fetch(url) {
            case .refused(let finding):
                findings.append(finding)
            case .read(.media(let playlist), _):
                mediaPlaylists[url] = playlist
                findings += HlsPathologies.inMediaPlaylist(playlist)
            case .read(.multivariant, _):
                // A multivariant playlist where a media playlist belongs: it parsed, so it is not
                // unreachable, and it is not the document the tag said it was.
                findings.append(Self.unreadable)
            }
        }
        return findings
            + HlsPathologies.inAudioGroups(multivariant, mediaPlaylists: mediaPlaylists)
    }

    /// One finding per pathology, at the highest severity any playlist showed it at, in the order
    /// each pathology was first found.
    static func worstPerPathology(_ findings: [Finding]) -> [Finding] {
        var order: [Pathology] = []
        var worst: [Pathology: Finding] = [:]
        for finding in findings {
            guard let held = worst[finding.pathology] else {
                order.append(finding.pathology)
                worst[finding.pathology] = finding
                continue
            }
            if finding.severity > held.severity { worst[finding.pathology] = finding }
        }
        return order.compactMap { worst[$0] }
    }

    /// What one fetch came back with: a document to read rules over — with the headers it arrived
    /// with, which is what the delivery rules (#428) read, since a delivery defect is a fact about a
    /// response rather than a document — or the finding that replaces it.
    enum Fetched {
        case read(PlaylistLint.Playlist, headers: [String: String])
        case refused(Finding)
    }

    /// `url`'s bytes, read — or the finding that says why they are not.
    ///
    /// A 401 or 403 is offered to the configured provider **once per request**, and asked again
    /// with what it returned unless that is what was already sent, which declines — the
    /// ``SuperPlayerCore/HeaderProvider`` contract, word for word. What it returned is then this
    /// doctor's for every later request, because a token is the session's.
    func fetch(_ url: URL) async -> Fetched {
        var asked = false
        while true {
            let sent = credentials.headers
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
            for (name, value) in sent { request.setValue(value, forHTTPHeaderField: name) }

            let body: Data
            let response: HTTPURLResponse
            do {
                let (data, answer) = try await session.data(for: request)
                guard let http = answer as? HTTPURLResponse else {
                    return .refused(Self.unreachable(nil))
                }
                body = data
                response = http
            } catch {
                // No host answered, or the transfer broke off: nothing to read and no status to
                // print, and the error's own text may quote the URL and its token.
                return .refused(Self.unreachable(nil))
            }

            // ref: RFC 9110 §15.3 — a 2xx is the request having succeeded.
            if (200..<300).contains(response.statusCode) {
                let headers = response.allHeaderFields.reduce(into: [String: String]()) {
                    headers, field in
                    if let name = field.key as? String, let value = field.value as? String {
                        headers[name.lowercased()] = value
                    }
                }
                switch PlaylistLint.read(body, from: response.url ?? url) {
                case .success(let playlist): return .read(playlist, headers: headers)
                case .failure: return .refused(Self.unreadable)
                }
            }
            if !asked, Self.credentialRefusals.contains(response.statusCode),
                let provider = credentials.provider
            {
                asked = true
                let refusal = CredentialRefusal(
                    uri: url, status: response.statusCode, sentHeaders: sent)
                if let fresh = try? await provider.refreshedHeaders(for: refusal), fresh != sent {
                    credentials.headers = fresh
                    continue
                }
            }
            return .refused(Self.unreachable(response.statusCode))
        }
    }

    /// A playlist that could not be fetched, as the finding rule 7 asks for: blocking, because
    /// there is nothing to play, with the status where a server answered — "refused with 403" and
    /// "no host answered" send a support engineer to two different teams, and the status is the one
    /// part of a refusal safe to print.
    static func unreachable(_ status: Int?) -> Finding {
        Finding(
            pathology: .manifestUnreachable, severity: .blocking,
            magnitude: status.map { "HTTP \($0)" })
    }

    /// Bytes that arrived and are not a playlist: blocking, and no magnitude. What the lint objected
    /// to is not carried, since the line it stopped at may quote a URL and its token.
    static let unreadable = Finding(
        pathology: .manifestUnreadable, severity: .blocking, magnitude: nil)

    /// The statuses a server answers a credential it will not accept with, and the only two the
    /// provider is asked about — the pair `SuperPlayerDRM`'s licence exchange asks about too.
    ///
    /// ref: RFC 9110 §15.5.2 (401 Unauthorized) and §15.5.4 (403 Forbidden)
    static let credentialRefusals: Set<Int> = [401, 403]
}

/// The credential a doctor carries across its examinations: the provider it was configured with,
/// and what that provider last returned. Shared by every examination of one doctor, as a token is
/// by every adoption of one player, so a doctor asked twice pays one refusal rather than two — the
/// Kotlin doctor's one chain, which holds its header-refresh layer's state for the same reason.
final class Credentials: Sendable {

    let provider: (any HeaderProvider)?
    private let current = OSAllocatedUnfairLock(initialState: [String: String]())

    init(provider: (any HeaderProvider)?) { self.provider = provider }

    var headers: [String: String] {
        get { current.withLock { $0 } }
        set { current.withLock { $0 = newValue } }
    }
}
