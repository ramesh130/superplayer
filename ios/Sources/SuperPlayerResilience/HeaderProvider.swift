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

/// Where a request's credential comes from when the one it carried was refused: the app's, because
/// only the app can mint one — the Kotlin `HeaderProvider`, its contract kept and its mechanism
/// changed.
///
/// `PRD.md` §3.3 calls the failure this closes among the commonest in production and almost never
/// handled in an app: a CDN token expires mid-session, every segment after it is refused, and the
/// session ends on an authorization refresh nobody performed. Hand one of these to
/// ``Resilience/standard(headers:)`` and the refusal is repaired before any rung is climbed for it
/// (ADR-0011 rule 12).
///
/// ```swift
/// struct Tokens: HeaderProvider {
///     func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String] {
///         ["Authorization": "Bearer \(try await session.freshToken())"]
///     }
/// }
/// configuration.resilience = Resilience.standard(headers: Tokens())
/// ```
///
/// ## The contract
///
/// **It is asked only on a refusal** — a 401 or a 403 and nothing else — so a session whose token
/// never expires never asks it. What it returns is carried by the content re-adopted to repair the
/// refusal *and* by every later adoption on that player, because a token is the session's.
///
/// **Returning what was already sent — ``CredentialRefusal/sentHeaders`` — declines**, and so does
/// throwing; the refusal then climbs the ladder, and what reaches the consumer is the CDN's refusal,
/// because that is what the session failed on.
///
/// **How the repair differs from Android's**, and what it costs. There the request is asked again
/// inside the transfer that met the refusal. `AVPlayer` has no such transfer to sit inside: an
/// `AVURLAsset`'s headers are fixed when it is made and nothing re-asks a request (ADR-0020 rule 6).
/// So the content is **re-adopted at the position playback had reached**, on a new asset carrying
/// what this returned — the same identity and the same measurement session, the mechanism of rung 4
/// — which **costs a fetch of every playlist again** before the segment at that position, where
/// the Kotlin repair cost one request. Each refused request is asked about once, so a refreshed
/// credential refused again by the same request ends the climb rather than loops it.
///
/// **The headers reach the wire through an asset option the SDK does not declare**,
/// `AVURLAssetHTTPHeaderFieldsKey`, because no declared option carries a request header and a
/// resource-loader delegate is the in-process proxy ADR-0020 rule 6 forbids. ADR-0020 rule 6's #420
/// addendum argues the choice. An app that would rather depend on nothing undeclared signs its URLs
/// instead, a query AVFoundation carries on its own, and passes no provider. Its assets are then
/// built exactly as before.
///
/// **It is asked off the main actor**, and may take its time: fetching a token over the network is
/// the expected use, and playback has already stopped for the refusal. Content replaced or a player
/// released while it is asked is not repaired. Nothing bounds the wait, so a provider that never
/// returns leaves the failure unanswered. Give a token fetch a timeout of your own, and throw when
/// it passes.
///
/// spec: the headers it returns are ordinary request headers (RFC 9110 §5), and the credential a
/// CDN checks is conventionally `Authorization` (RFC 9110 §11.6.2). A signed URL carries its
/// signature in the query instead, and has no header to replace: a provider for one has nothing to
/// return here, and the app re-signs the URL in a new ``MediaRequest`` of its own.
public protocol HeaderProvider: Sendable {

    /// The complete set of headers the player should carry from now on, given that `refusal`'s
    /// request was refused with the ones it had — complete rather than additional, so a header
    /// dropped is dropped. Returning ``CredentialRefusal/sentHeaders`` unchanged, or throwing,
    /// declines the refresh.
    func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String]
}

/// One request a CDN would not serve with the credential it carried, as a ``HeaderProvider`` is
/// told about it — the Kotlin `CredentialRefusal`.
public struct CredentialRefusal: Sendable, CustomStringConvertible {

    /// The address that was refused, query included: the request AVFoundation's error log named,
    /// or the multivariant playlist where it named none — which it does for that playlist itself.
    public let uri: URL

    /// 401 or 403, and no other — the two a CDN uses for a credential it will not accept.
    ///
    /// ref: RFC 9110 §15.5.2 (401 Unauthorized) and §15.5.4 (403 Forbidden)
    public let status: Int

    /// The headers the refused request carried: what this provider returned last time, and empty
    /// until it has returned anything. Returning them unchanged declines.
    public let sentHeaders: [String: String]

    init(uri: URL, status: Int, sentHeaders: [String: String]) {
        self.uri = uri
        self.status = status
        self.sentHeaders = sentHeaders
    }

    public var description: String { "\(status) for \(uri)" }
}
