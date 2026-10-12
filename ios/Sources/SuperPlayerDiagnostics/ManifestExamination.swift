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
/// `ManifestExamination`. This type owns the *fetching*, and `HlsPathologies` and
/// `DeliveryPathologies` the *judgement*, so that what goes on the wire is decided in one place
/// (ADR-0015 rule 7).
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
        case .read(.multivariant(let playlist), let headers):
            let read = await examine(playlist)
            let delivered = await delivery(
                source, mediaPlaylists: read.mediaPlaylists,
                responses: [headers] + read.mediaPlaylists.map(\.headers))
            return Self.worstPerPathology(read.findings + delivered)
        case .read(.media(let playlist), let headers):
            // A source that is itself a media playlist: legal HLS, and the shape a single-rendition
            // live stream is usually published in.
            let delivered = await delivery(
                source, mediaPlaylists: [(playlist, headers)], responses: [headers])
            return Self.worstPerPathology(HlsPathologies.inMediaPlaylist(playlist) + delivered)
        }
    }

    /// What a multivariant playlist's examination read: the findings of the documents, and every
    /// media playlist it fetched with the headers it arrived with, which is what the delivery rules
    /// read afterwards.
    private struct Read {
        var findings: [Finding]
        var mediaPlaylists: [(playlist: MediaPlaylist, headers: [String: String])]
    }

    /// A multivariant playlist and **every media playlist it names**, variants and audio
    /// renditions alike — more fetches than a player makes, deliberately, because a defect in a
    /// rung nobody started on is one every viewer whose link climbs to it meets. All of it is
    /// playlists, so a preflight costs no media. One that cannot be fetched or read is its own
    /// finding and does not stop the others.
    private func examine(_ multivariant: MultivariantPlaylist) async -> Read {
        var findings = HlsPathologies.inMultivariant(multivariant)
        var mediaPlaylists: [URL: MediaPlaylist] = [:]
        var read: [(playlist: MediaPlaylist, headers: [String: String])] = []
        var named: [URL] = []
        for url in multivariant.variants.map(\.url) + multivariant.audioRenditions.compactMap(\.url)
        where !named.contains(url) {
            named.append(url)
        }
        for url in named {
            switch await fetch(url) {
            case .refused(let finding):
                findings.append(finding)
            case .read(.media(let playlist), let headers):
                mediaPlaylists[url] = playlist
                read.append((playlist, headers))
                findings += HlsPathologies.inMediaPlaylist(playlist)
            case .read(.multivariant, _):
                // A multivariant playlist where a media playlist belongs: it parsed, so it is not
                // unreachable, and it is not the document the tag said it was.
                findings.append(Self.unreadable)
            }
        }
        findings += HlsPathologies.inAudioGroups(multivariant, mediaPlaylists: mediaPlaylists)
        return Read(findings: findings, mediaPlaylists: read)
    }

    /// What the *delivery* of this stream got wrong, read after the documents rather than beside
    /// them — the Kotlin `delivery`. After, because the rules need what the fetching produced: the
    /// headers every playlist arrived with, and the media playlists, which are where the segments a
    /// token has to cover are declared.
    private func delivery(
        _ source: URL, mediaPlaylists: [(playlist: MediaPlaylist, headers: [String: String])],
        responses: [[String: String]]
    ) async -> [Finding] {
        var findings = DeliveryPathologies.inResponses(responses)
        for (playlist, headers) in mediaPlaylists {
            findings += await DeliveryPathologies.inDelivery(
                source: source, playlist: playlist, playlistHeaders: headers,
                segmentHeaders: probeSegment)
        }
        return findings
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
    /// with, names lowercased, which is what the delivery rules read, since a delivery defect is a
    /// fact about a response rather than a document — or the finding that replaces it.
    enum Fetched {
        case read(PlaylistLint.Playlist, headers: [String: String])
        case refused(Finding)
    }

    /// `url`'s bytes, read — or the finding that says why they are not.
    func fetch(_ url: URL) async -> Fetched {
        guard let answer = await exchange(url, method: "GET") else {
            // No host answered, or the transfer broke off: nothing to read and no status to print,
            // and the error's own text may quote the URL and its token.
            return .refused(Self.unreachable(nil))
        }
        // ref: RFC 9110 §15.3 — a 2xx is the request having succeeded.
        guard (200..<300).contains(answer.response.statusCode) else {
            return .refused(Self.unreachable(answer.response.statusCode))
        }
        switch PlaylistLint.read(answer.body, from: answer.response.url ?? url) {
        case .success(let playlist):
            return .read(playlist, headers: Self.headers(of: answer.response))
        case .failure: return .refused(Self.unreadable)
        }
    }

    /// The headers one segment is served with, read without reading a byte of it — or nil where
    /// the request was refused, which is a fact the cache rule reports nothing about.
    ///
    /// **This is the one request a doctor makes that is not a playlist**, and ADR-0015 rule 7's
    /// #289 addendum is what admits it: a `Cache-Control` disagreement is a disagreement between two
    /// responses, so naming which is wrong needs the second one. It is called only by a rule that
    /// already has a defect to attribute, and at most once per media playlist, so a correctly
    /// delivered stream costs playlists alone.
    ///
    /// **A `HEAD` where Android asks for one byte.** Media3's `DataSource` has no `HEAD`, so the
    /// Kotlin probe opens a one-byte range and closes it unread; `URLSession` has one, and a `HEAD`
    /// answer carries no body by definition (RFC 9110 §9.3.2) while the server "SHOULD send the same
    /// header fields" as for a `GET` — a caching directive above all, which is a property of the
    /// resource. A range would cost a whole segment wherever an origin ignores `Range` and answers
    /// 200, which RFC 9110 §14.2 permits it to do; a `HEAD` costs a round trip everywhere. It carries
    /// the credential every other request carries, and is offered to the provider on a refusal as
    /// they are, because a segment refused for want of a token says nothing about its caching.
    private func probeSegment(_ segment: URL) async -> [String: String]? {
        guard let answer = await exchange(segment, method: "HEAD"),
            (200..<300).contains(answer.response.statusCode)
        else { return nil }
        return Self.headers(of: answer.response)
    }

    /// One request and its answer, the credential carried — or nil where nothing answered.
    ///
    /// A 401 or 403 is offered to the configured provider **once per request**, and asked again
    /// with what it returned unless that is what was already sent, which declines — the
    /// ``SuperPlayerCore/HeaderProvider`` contract, word for word. What it returned is then this
    /// doctor's for every later request, because a token is the session's.
    private func exchange(_ url: URL, method: String) async
        -> (response: HTTPURLResponse, body: Data)?
    {
        var asked = false
        while true {
            let sent = credentials.headers
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
            request.httpMethod = method
            for (name, value) in sent { request.setValue(value, forHTTPHeaderField: name) }

            guard let answered = try? await session.data(for: request),
                let response = answered.1 as? HTTPURLResponse
            else { return nil }
            let body = answered.0

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
            return (response, body)
        }
    }

    /// `response`'s header fields with their names lowercased: a field name is case-insensitive
    /// (RFC 9110 §5.1), so lowering it once is the whole of reading it so.
    private static func headers(of response: HTTPURLResponse) -> [String: String] {
        response.allHeaderFields.reduce(into: [String: String]()) { headers, field in
            if let name = field.key as? String, let value = field.value as? String {
                headers[name.lowercased()] = value
            }
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
/// by every adoption of one player, so a doctor asked twice in turn pays one refusal rather than
/// two — the Kotlin doctor's one chain, which holds its header-refresh layer's state for the same
/// reason. Two examinations refused at once may each ask the provider, and the later answer is the
/// one kept: either is a credential the provider minted, so nothing is lost but a second question.
final class Credentials: Sendable {

    let provider: (any HeaderProvider)?
    private let current = OSAllocatedUnfairLock(initialState: [String: String]())

    init(provider: (any HeaderProvider)?) { self.provider = provider }

    var headers: [String: String] {
        get { current.withLock { $0 } }
        set { current.withLock { $0 = newValue } }
    }
}
