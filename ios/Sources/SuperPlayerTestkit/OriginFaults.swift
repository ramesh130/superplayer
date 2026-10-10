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

    /// The index each (host, kind, path) was given, in the order first requested.
    private var indices: [ResourceKey: Int] = [:]
    /// The next index to give, per host and kind.
    private var nextIndex: [HostKind: Int] = [:]
    /// Attempts so far, per resource.
    private var attempts: [ResourceKey: Int] = [:]
    /// The credential that first met a refreshable token expiry; a different one is served.
    private var expiredCredential: String?
    /// The intermediary cache's store: a whole 200 per resource, and when it was stored.
    private var cache: [ResourceKey: (response: HttpExchange.Response, storedAtMs: Int64)] = [:]

    init(_ script: FaultScript) { self.script = script }

    /// Gives `path` on `host` its place in the script's addressing, counting this as one attempt.
    mutating func address(host: Int, path: String) -> ResourceAddress {
        let kind = ResourceKind.of(path: path)
        let key = ResourceKey(host: host, kind: kind, path: path)
        let index: Int
        if let known = indices[key] {
            index = known
        } else {
            let hostKind = HostKind(host: host, kind: kind)
            index = nextIndex[hostKind, default: 0]
            nextIndex[hostKind] = index + 1
            indices[key] = index
        }
        attempts[key, default: 0] += 1
        return ResourceAddress(host: host, kind: kind, index: index, attempt: attempts[key]!)
    }

    /// What to answer `request`, at `address`, `nowMs` into the origin's life; `published` resolves
    /// what the origin itself would answer, and is called only when a fault does not replace it.
    mutating func answer(
        _ request: HttpExchange.Request, at address: ResourceAddress, nowMs: Int64,
        published: () -> HttpExchange.Response
    ) -> Answer {
        let matched = script.faults.filter { $0.matches(address) }.map(\.effect)
        var acted: Set<FaultScript.Kind> = []
        var answer = Answer()

        if let millis = matched.lazy.compactMap(\.latencyMs).first {
            answer.latencyMs = millis
            acted.insert(.latency)
        }
        if matched.contains(where: { $0.kind == .connectionReset }) {
            acted.insert(.connectionReset)
            return finish(answer, acted, matched)
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
            answer.response = throughCache(
                request, at: address, nowMs: nowMs, rule: cacheRule, published: published,
                answer: &answer)
            acted.insert(.intermediaryCache)
        } else {
            answer.response = published()
        }

        if let bytes = matched.lazy.compactMap(\.truncation).first,
            let body = answer.response?.body, request.method != "HEAD", bytes < body.count
        {
            answer.truncateAfterBytes = bytes
            acted.insert(.truncation)
        }
        if let cap = matched.lazy.compactMap(\.throughputCap).first {
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

    /// The cache's answer: the stored copy while it is fresh, otherwise the origin's, stored when it
    /// is a whole 200 — RFC 9111 as ``FaultScript/Builder/serveThroughCache(maxAgeSeconds:honoursNoCache:kind:index:host:)``
    /// cites it.
    private mutating func throughCache(
        _ request: HttpExchange.Request, at address: ResourceAddress, nowMs: Int64,
        rule: (maxAgeSeconds: Int64, honoursNoCache: Bool), published: () -> HttpExchange.Response,
        answer: inout Answer
    ) -> HttpExchange.Response {
        let key = ResourceKey(host: address.host, kind: address.kind, path: request.path)
        // spec: RFC 9111 §5.2.1.4 — `no-cache` asks the cache to go to the origin.
        let revalidate =
            rule.honoursNoCache
            && (request.headers["cache-control"]?.lowercased().contains("no-cache") ?? false)
        if !revalidate, let stored = cache[key],
            nowMs - stored.storedAtMs < rule.maxAgeSeconds * 1_000
        {
            answer.servedFromCache = true
            return Self.cacheHeaders(
                stored.response, maxAge: rule.maxAgeSeconds,
                ageSeconds: (nowMs - stored.storedAtMs) / 1_000)
        }
        let response = published()
        guard response.status == 200 else { return response }
        cache[key] = (response, nowMs)
        return Self.cacheHeaders(response, maxAge: rule.maxAgeSeconds, ageSeconds: 0)
    }

    // spec: RFC 9111 §5.2.2.9 and §5.2.2.1 — `public, max-age`; §5.1 — `Age`, in whole seconds.
    private static func cacheHeaders(
        _ response: HttpExchange.Response, maxAge: Int64, ageSeconds: Int64
    ) -> HttpExchange.Response {
        var response = response
        response.headers += [
            ("Cache-Control", "public, max-age=\(maxAge)"), ("Age", String(ageSeconds)),
        ]
        return response
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
        let kind: ResourceKind
        let path: String
    }

    private struct HostKind: Hashable {
        let host: Int
        let kind: ResourceKind
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
