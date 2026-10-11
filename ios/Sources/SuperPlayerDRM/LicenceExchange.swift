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

/// One player's FairPlay exchange with its key server: the certificate fetched once, each key
/// request composed and `POST`ed, a failed request asked for again on ``RetryPolicy/licence`` with
/// ``Backoff``'s jitter, and one 401 or 403 per request repaired with a fresh credential —
/// `docs/ios-plan.md` §3.6's row, "the key request is **ours** to make".
///
/// On Android all of this is a licence *load* on the player's chain: Media3's `HttpMediaDrmCallback`
/// posts it, `RetryingLoadErrors` spends the licence budget, and the header-refresh layer repairs the
/// credential inside the transfer (#205). Here there is no chain — `AVPlayer`'s loading is its own
/// (ADR-0020 rule 6) and an `AVContentKeySession` hands the app a request to answer — so the three
/// halves are written once, here, with the Kotlin behaviour as their specification:
///
/// - **The budget is re-read at every retry** (the Kotlin `DecisionInForce`), so a re-consulted
///   decision governs the next wait; and it is **the licence budget alone**, so a licence cannot spend
///   a playlist's.
/// - **Every failed request is asked for again** until the budget is spent, whatever its status. This
///   product classifies nothing (ADR-0012 rule 5), and deciding which statuses are worth a second ask
///   would be a classification — so the classifier names what was finally refused, and the budget is
///   what bounds the asking.
/// - **A 401 or 403 is offered to the ``HeaderProvider`` first, once per request**, and asked again
///   with what it returned *without* spending the budget, which is the Kotlin "inside the transfer
///   that met the refusal": rung 1's budget is never spent on a credential that was always going to
///   be refused. Returning what was sent, or throwing, declines, and the refusal then spends the
///   budget as any failure does.
///
/// An actor, because a player's requests — two keys at start-up, a rotation later — run concurrently
/// and share the certificate and the refreshed credential. The certificate is fetched once however
/// many ask for it at once; a refusal is not coalesced, so two requests refused 401 together each ask
/// the provider once and the later answer is the one kept — the Kotlin repair is per transfer too,
/// and a provider minting a token twice for one expiry costs a token, never a licence.
actor LicenceExchange {

    private let config: FairPlayConfig
    private let provider: (any HeaderProvider)?

    /// One session per player, ephemeral: nothing about a licence exchange belongs in a cache or a
    /// cookie jar that outlives it, and an entitlement answer cached by `URLCache` would be the
    /// answer to the last viewer's question. Its own request timeout is `URLSession`'s, unchanged.
    private let session: URLSession

    /// The certificate, once fetched: the same bytes for every key request of this player.
    private var certificate: Data?

    /// The certificate fetch in flight, which a second key request arriving meanwhile waits on rather
    /// than fetching again — two keys at start-up are the ordinary case, and "once per player" is
    /// the claim.
    private var certificateFetch: Task<Result<Data, LicenceExchangeError>, any Error>?

    /// What the provider last returned, carried by every request from then on — empty until it has
    /// returned anything, as an asset's headers are.
    private var headers: [String: String] = [:]

    /// The jitter's source. The system's, because the property that matters is that two players
    /// never draw the same wait.
    private var random = SystemRandomNumberGenerator()

    init(config: FairPlayConfig, provider: (any HeaderProvider)?) {
        self.config = config
        self.provider = provider
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        self.session = URLSession(configuration: configuration)
    }

    /// The licence for `request`, or the error that says which step could not produce one. Throws
    /// only `CancellationError`, when the player was released while it was asked.
    ///
    /// - Parameter budget: the licence budget in force, read at each retry; nil once the player has
    ///   gone, which ends the asking.
    func licence(
        for request: FairPlayKeyRequest, budget: @escaping @MainActor @Sendable () -> RetryBudget?
    ) async throws -> Result<Data, LicenceExchangeError> {
        let certificate: Data
        switch try await self.certificate(budget: budget) {
        case .success(let fetched): certificate = fetched
        case .failure(let error): return .failure(error)
        }
        let spc: Data
        do {
            spc = try await request.makeServerPlaybackContext(
                certificate, Self.contentIdentifier(of: request.keyIdentifier))
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return .failure(
                LicenceExchangeError(
                    stage: .keyRequest, status: nil, attempts: 0, underlyingError: error as NSError)
            )
        }
        return try await fetch(.licence, budget: budget) { headers in
            var post = URLRequest(url: self.config.licenceURL)
            post.httpMethod = "POST"
            post.httpBody = spc
            post.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            for (name, value) in headers { post.setValue(value, forHTTPHeaderField: name) }
            return post
        }
    }

    /// The player was released: the certificate fetch, the one piece of work no key request's own
    /// task owns, is cancelled. Each key request's exchange is cancelled with its task.
    func cancel() {
        certificateFetch?.cancel()
        certificateFetch = nil
    }

    /// Invalidated here and never on release, because a request made on an invalidated session is
    /// an exception rather than an error, and a cancelled exchange may still be between a check and
    /// its next request when the player goes; nothing holds this actor once nothing is in flight.
    deinit {
        session.finishTasksAndInvalidate()
    }

    /// The certificate, fetched on first use and kept. A failed fetch is not kept, so the next key
    /// request asks again with a budget of its own.
    private func certificate(budget: @escaping @MainActor @Sendable () -> RetryBudget?)
        async throws -> Result<Data, LicenceExchangeError>
    {
        if let certificate { return .success(certificate) }
        let pending =
            certificateFetch
            ?? Task {
                try await self.fetch(.certificate, budget: budget) { headers in
                    var get = URLRequest(url: self.config.certificateURL)
                    for (name, value) in headers { get.setValue(value, forHTTPHeaderField: name) }
                    return get
                }
            }
        certificateFetch = pending
        let fetched = try await pending.value
        certificateFetch = nil
        if case .success(let bytes) = fetched { certificate = bytes }
        return fetched
    }

    /// One step's request, asked until it is answered 2xx or the budget is spent — the type's doc
    /// comment's three rules.
    private func fetch(
        _ stage: LicenceExchangeError.Stage,
        budget: @escaping @MainActor @Sendable () -> RetryBudget?,
        request: @Sendable ([String: String]) -> URLRequest
    ) async throws -> Result<Data, LicenceExchangeError> {
        var attempts = 0
        var retries = 0
        var repaired = false
        while true {
            try Task.checkCancellation()
            attempts += 1
            let sent = headers
            let urlRequest = request(sent)
            var status: Int?
            var underlying: NSError?
            do {
                let (body, response) = try await session.data(for: urlRequest)
                status = (response as? HTTPURLResponse)?.statusCode
                // ref: RFC 9110 §15.3 — a 2xx is the request having succeeded.
                if let status, (200..<300).contains(status) { return .success(body) }
            } catch let error as URLError where error.code == .cancelled {
                throw CancellationError()
            } catch {
                underlying = error as NSError
            }
            try Task.checkCancellation()
            if let status, !repaired, Self.credentialRefusals.contains(status), let provider,
                let url = urlRequest.url
            {
                // Recorded before the provider is asked, so the question is put once whatever it
                // answers — the Kotlin "asked once more".
                repaired = true
                let refusal = CredentialRefusal(uri: url, status: status, sentHeaders: sent)
                if let fresh = try? await provider.refreshedHeaders(for: refusal), fresh != sent {
                    headers = fresh
                    continue
                }
            }
            guard let budget = await budget(), retries < budget.maxRetries else {
                return .failure(
                    LicenceExchangeError(
                        stage: stage, status: status, attempts: attempts,
                        underlyingError: underlying))
            }
            retries += 1
            let waitMs = Backoff.delayMs(for: budget, retry: retries, using: &random)
            try await Task.sleep(for: .milliseconds(waitMs))
        }
    }

    /// The statuses a server answers a credential it will not accept with, and the only two the
    /// provider is asked about — `ErrorClassifier.credentialRefusals`' pair, which this product
    /// cannot reach and reads here as a fact of HTTP rather than as a class.
    ///
    /// ref: RFC 9110 §15.5.2 (401 Unauthorized) and §15.5.4 (403 Forbidden)
    static let credentialRefusals: Set<Int> = [401, 403]

    /// The content identifier a key request is composed with: the `skd://` URI's host — the asset
    /// identifier Apple's own FairPlay client sample derives and the convention key servers expect —
    /// or, for an identifier with no host, the identifier after its scheme.
    ///
    /// ref: Apple, FairPlay Streaming Server SDK, the `HLSCatalogWithFPS` client sample's
    /// `ContentKeyDelegate` — `URL(string: keyRequest.identifier).host` encoded as UTF-8
    static func contentIdentifier(of keyIdentifier: String) -> Data {
        if let host = URL(string: keyIdentifier)?.host, !host.isEmpty { return Data(host.utf8) }
        let scheme = "skd://"
        return Data(
            (keyIdentifier.hasPrefix(scheme)
                ? String(keyIdentifier.dropFirst(scheme.count)) : keyIdentifier).utf8)
    }
}
