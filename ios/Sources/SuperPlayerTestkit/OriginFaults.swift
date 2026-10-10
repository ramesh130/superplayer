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

/// A ``FaultScript`` applied to one origin's requests: the addressing it keeps — which index each
/// resource got and how many attempts each has had — and the state two faults carry across requests,
/// the expired credential and the intermediary cache.
///
/// A value with no socket and no clock of its own, as `HttpExchange` is: the origin hands it each
/// request with the time it arrived, and gets back what to answer and how. So the addressing, which
/// is the part of the Kotlin injector that is easy to get wrong, is tested as a function of its
/// input in `OriginFaultsTests` rather than only through a player.
struct OriginFaults {

    /// What the origin is to do with one request once the script has been applied.
    struct Answer {
        /// The response before framing, or nil to drop the connection with none.
        var response: HttpExchange.Response?
        /// Real time to hold the response before its first byte, on top of a trace's round trip.
        var latencyMs: Int64 = 0
        /// A cap on the body's rate, which a trace's rate composes with — the slower governs.
        var capBps: Int64?
        /// Body bytes to send before closing the connection, `Content-Length` still declaring all.
        var truncateAfterBytes: Int?
        /// Headers to add after framing, a name already present left alone.
        var extraHeaders: [(String, String)] = []
        /// The kinds that acted, in the order the script declares them.
        var faults: [FaultScript.Kind] = []
        var servedFromCache = false
    }

    private let script: FaultScript

    /// The index each resource was given, in the order first requested.
    private var indices: [ResourceKey: Int] = [:]
    /// The next index to give, per kind — across hosts, as the Kotlin injector's `nextIndex` is, so
    /// a resource on the second host is numbered after the first host's of its kind.
    private var nextIndex: [ResourceKind: Int] = [:]
    /// Attempts so far, per resource.
    private var attempts: [ResourceKey: Int] = [:]
    /// The credential that first met a refreshable token expiry; a different one is served.
    private var expiredCredential: String?
    /// The intermediary cache's store: a whole 200 per request URI, and when it was stored.
    private var cache: [CacheKey: (response: HttpExchange.Response, storedAtMs: Int64)] = [:]

    init(_ script: FaultScript) { self.script = script }

    /// Gives `path` on `host`, read from byte `offset`, its place in the script's addressing,
    /// counting this as one attempt.
    ///
    /// A resource is the Kotlin injector's: its address without the query, at the position read
    /// from — so a signed URL whose signature changes is still the same segment, and a read resumed
    /// from an offset is a resource of its own, as it is on Android.
    mutating func address(host: Int, path: String, offset: Int = 0) -> ResourceAddress {
        let kind = ResourceKind.of(path: path)
        let key = ResourceKey(host: host, path: path, offset: offset)
        let index: Int
        if let known = indices[key] {
            index = known
        } else {
            index = nextIndex[kind, default: 0]
            nextIndex[kind] = index + 1
            indices[key] = index
        }
        attempts[key, default: 0] += 1
        return ResourceAddress(host: host, kind: kind, index: index, attempt: attempts[key]!)
    }

    /// What to answer `request`, at `address`, `nowMs` into the origin's life; `published` resolves
    /// what the origin itself would answer, and is called only when a fault does not replace it.
    ///
    /// The order is the Kotlin injector's: the reset first, since nothing is answered; then the
    /// latencies, *summed*; a status; the token, last of the refusals because it reads the request's
    /// credential; and then the cache. Of several truncations and caps the *smallest* governs, as
    /// there.
    mutating func answer(
        _ request: HttpExchange.Request, at address: ResourceAddress, nowMs: Int64,
        published: () -> HttpExchange.Response
    ) -> Answer {
        let matched = script.faults.filter { $0.matches(address) }.map(\.effect)
        var acted: Set<FaultScript.Kind> = []
        var answer = Answer()

        if matched.contains(where: { $0.kind == .connectionReset }) {
            acted.insert(.connectionReset)
            return finish(answer, acted, matched)
        }
        let latencies = matched.compactMap(\.latencyMs)
        if !latencies.isEmpty {
            answer.latencyMs = latencies.reduce(0, +)
            acted.insert(.latency)
        }

        if let status = matched.lazy.compactMap(\.httpStatus).first {
            answer.response = HttpExchange.refusal(status)
            acted.insert(.httpStatus)
        } else if let refreshable = matched.lazy.compactMap(\.expiredTokenRefreshable).first,
            tokenHasExpired(for: request, refreshable: refreshable)
        {
            answer.response = HttpExchange.refusal(FaultScript.httpForbidden)
            acted.insert(.tokenExpiry)
        } else if let cacheRule = matched.lazy.compactMap(\.cacheRule).first,
            request.method == "GET", request.headers["range"] == nil
        {
            let (response, fromCache) = throughCache(
                request, host: address.host, nowMs: nowMs, rule: cacheRule, published: published)
            answer.response = response
            answer.servedFromCache = fromCache
            acted.insert(.intermediaryCache)
        } else {
            answer.response = published()
        }

        if let bytes = matched.compactMap(\.truncation).min(),
            let body = answer.response?.body, request.method != "HEAD", bytes < body.count
        {
            answer.truncateAfterBytes = bytes
            acted.insert(.truncation)
        }
        if let cap = matched.compactMap(\.throughputCap).min() {
            answer.capBps = cap
            acted.insert(.throughputCap)
        }
        for headers in matched.compactMap(\.responseHeaders) {
            answer.extraHeaders += headers
            acted.insert(.responseHeaders)
        }
        return finish(answer, acted, matched)
    }

    /// Whether a token expiry refuses `request`: always, unless `refreshable` and the request bears
    /// a credential other than the one that first met the fault.
    private mutating func tokenHasExpired(for request: HttpExchange.Request, refreshable: Bool)
        -> Bool
    {
        guard refreshable else { return true }
        let credential = request.headers["authorization"] ?? request.query ?? ""
        if expiredCredential == nil { expiredCredential = credential }
        return credential == expiredCredential
    }

    /// The cache's answer, and whether it is the stored copy: that copy while it is fresh, otherwise
    /// the origin's, stored when it is a whole 200 — the Kotlin `IntermediaryCache.respond`, with
    /// RFC 9111 as ``FaultScript/Builder/serveThroughCache(maxAgeSeconds:honoursNoCache:kind:index:host:)``
    /// cites it.
    ///
    /// spec: RFC 9111 §4.1 — a cache keys a response by its whole request URI, query included, which
    /// is why this store does not share the addressing's query-blind key.
    private mutating func throughCache(
        _ request: HttpExchange.Request, host: Int, nowMs: Int64,
        rule: (maxAgeSeconds: Int64, honoursNoCache: Bool), published: () -> HttpExchange.Response
    ) -> (HttpExchange.Response, servedFromCache: Bool) {
        let key = CacheKey(host: host, path: request.path, query: request.query)
        if !(rule.honoursNoCache && Self.asksCachesToStepAside(request)), let stored = cache[key] {
            let ageSeconds = (nowMs - stored.storedAtMs) / 1_000
            if ageSeconds < rule.maxAgeSeconds {
                // spec: RFC 9111 §5.1 — `Age`, in whole seconds, on a response a cache served.
                var hit = stored.response
                hit.headers.append(("Age", String(ageSeconds)))
                return (hit, true)
            }
        }
        var response = published()
        guard response.status == 200 else { return (response, false) }
        // spec: RFC 9111 §5.2.2.9 and §5.2.2.1 — the response that fills the store says it may.
        response.headers.append(("Cache-Control", "public, max-age=\(rule.maxAgeSeconds)"))
        cache[key] = (response, nowMs)
        return (response, false)
    }

    /// Whether `request` carries `Cache-Control: no-cache`, read as directives rather than as text.
    ///
    /// spec: RFC 9111 §5.2.1.4, and §5.2 — the field is a comma-separated list of directives.
    private static func asksCachesToStepAside(_ request: HttpExchange.Request) -> Bool {
        (request.headers["cache-control"] ?? "").split(separator: ",").contains {
            $0.trimmingCharacters(in: .whitespaces).lowercased() == "no-cache"
        }
    }

    private func finish(
        _ answer: Answer, _ acted: Set<FaultScript.Kind>, _ matched: [Fault.Effect]
    ) -> Answer {
        var answer = answer
        answer.faults = matched.map(\.kind).reduce(into: []) { ordered, kind in
            if acted.contains(kind) && !ordered.contains(kind) { ordered.append(kind) }
        }
        return answer
    }

    private struct ResourceKey: Hashable {
        let host: Int
        let path: String
        let offset: Int
    }

    private struct CacheKey: Hashable {
        let host: Int
        let path: String
        let query: String?
    }
}

extension Fault.Effect {
    fileprivate var latencyMs: Int64? {
        if case .latency(let millis) = self { millis } else { nil }
    }
    fileprivate var httpStatus: Int? {
        if case .httpStatus(let status) = self { status } else { nil }
    }
    fileprivate var expiredTokenRefreshable: Bool? {
        if case .expiredToken(let refreshable) = self { refreshable } else { nil }
    }
    fileprivate var cacheRule: (maxAgeSeconds: Int64, honoursNoCache: Bool)? {
        if case .intermediaryCache(let maxAge, let honours) = self {
            (maxAge, honours)
        } else {
            nil
        }
    }
    fileprivate var truncation: Int? {
        if case .truncate(let bytes) = self { bytes } else { nil }
    }
    fileprivate var throughputCap: Int64? {
        if case .throughputCap(let bps) = self { bps } else { nil }
    }
    fileprivate var responseHeaders: [(String, String)]? {
        if case .responseHeaders(let headers) = self { headers } else { nil }
    }
}
