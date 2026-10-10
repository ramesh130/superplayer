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

/// The faults a test wants an origin to produce, declared up front as data — the port of
/// `superplayer-testkit`'s `FaultScript`, applied at the server rather than in a transport slot
/// (ADR-0021 rule 3), and handed to ``PlaybackHarness/serve(_:faults:network:)``.
///
/// ```swift
/// let origin = try await harness.serve(
///     .hls(),
///     faults: FaultScript.Builder()
///         .failWithHttpStatus(500, kind: .mediaSegment, index: 1, firstAttempts: 1)
///         .build())
/// ```
///
/// **The addressing is the Kotlin script's**, coordinate for coordinate, because it is the reason
/// the type exists: "fail the third segment" said without a URL.
///
/// - **Kind and index.** An index counts distinct resources of one ``ResourceKind`` in the order
///   they were first requested, from zero, and a resource fetched again keeps its index.
/// - **Attempts.** Counted per resource from one; `firstAttempts` makes a fault relent, which is the
///   only way to write "and the retry recovered".
/// - **Host.** An origin's listeners are its hosts — ``TestContent/hls(segmentCount:variantCount:hosts:)``
///   says how many — numbered from zero in ``LoopbackOrigin/url(path:host:)``. The Kotlin
///   coordinate is a host *name*; on iOS every host is `127.0.0.1` at a port of its own, so the
///   number is what tells them apart (ADR-0021 rule 2: a second listener is a second host). The
///   same segment on two hosts is two resources, numbered in one sequence per kind, as on Android.
///
/// A resource is the Kotlin injector's: its path without the query, at the byte offset read from.
/// So a signed URL whose signature changes is still the same segment — which is what a refreshable
/// token expiry needs — and a read resumed from an offset is a resource of its own. Like the Kotlin
/// builder, there is no *path* coordinate: a test that means one file names it by kind and index.
/// Several matched faults compose as they do there: latencies add, and the smallest truncation and
/// cap govern.
///
/// **What is not here is what a server cannot produce** (ADR-0021 rule 3), and it is said rather
/// than approximated. The Kotlin `failDnsResolution` and `failTlsHandshake` are failures inside the
/// client — a resolver, and a handshake a plaintext origin never offers — and `timeOutConnection` is
/// the client giving up, which a server can only provoke by silence: ``Builder/addLatencyMs(_:kind:index:firstAttempts:host:)``
/// is that silence, and how long a stock `AVPlayer` waits before it gives up is AVFoundation's.
///
/// **The kinds are enumerable** (``Kind``), because `FaultSweepTest`'s iOS counterpart plays every
/// one and has to know when one was added: the Kotlin sweep reads the builder by reflection, which
/// Swift has none of, so the list is a `CaseIterable` the sweep holds itself to instead.
public struct FaultScript: Sendable {

    /// The status a CDN answers an expired token with, and what
    /// ``Builder/expireTokenAtSegment(_:refreshable:)`` sends.
    public static let httpForbidden = 403

    /// The status a CDN answers a missing resource with — a mistyped or rolled-off segment.
    public static let httpNotFound = 404

    /// The status a CDN answers its own trouble with, which retries treat differently from a 404.
    public static let httpServerError = 500

    /// No faults at all: the origin then answers as it would without a script.
    public static let none = FaultScript(faults: [])

    /// One kind of fault a server produces — one per method of ``Builder``.
    public enum Kind: String, Sendable, Hashable, CaseIterable {
        /// ``Builder/addLatencyMs(_:kind:index:firstAttempts:host:)``.
        case latency
        /// ``Builder/capThroughputBps(_:kind:index:firstAttempts:host:)``.
        case throughputCap
        /// ``Builder/failWithHttpStatus(_:kind:index:firstAttempts:afterAttempts:host:)``.
        case httpStatus
        /// ``Builder/truncateAfterBytes(_:kind:index:firstAttempts:host:)``.
        case truncation
        /// ``Builder/resetConnection(kind:index:firstAttempts:host:)``.
        case connectionReset
        /// ``Builder/expireTokenAtSegment(_:refreshable:)``.
        case tokenExpiry
        /// ``Builder/serveThroughCache(maxAgeSeconds:honoursNoCache:kind:index:host:)``.
        case intermediaryCache
        /// ``Builder/addResponseHeaders(_:kind:index:host:)``.
        case responseHeaders
    }

    let faults: [Fault]

    /// The kinds this script carries, each once, in the order first added — what a sweep reads to
    /// know it built the script it meant to.
    public var kinds: [Kind] {
        faults.reduce(into: []) { kinds, fault in
            if !kinds.contains(fault.effect.kind) { kinds.append(fault.effect.kind) }
        }
    }

    /// Collects the faults, one call per fault, in any order — the Kotlin `Builder`. A value: each
    /// call returns the builder with one more fault.
    ///
    /// Every method takes the same optional address: a ``ResourceKind``, an index within it, how
    /// many attempts at each addressed resource the fault applies to, and which host served it.
    /// Leaving one unset addresses every value of it. A value out of range — a negative index, a
    /// status that is not an error — is a test written wrongly, and stops it at once, as the
    /// Kotlin `require` does.
    public struct Builder: Sendable {

        private var faults: [Fault] = []

        public init() {}

        /// Holds the response `millis` milliseconds of real time before its first byte — charged
        /// once per request, where a real connection's setup cost lands. The Kotlin delay is on the
        /// harness's fake clock; this one is a real wait, because nothing else here has a clock.
        public func addLatencyMs(
            _ millis: Int64, kind: ResourceKind? = nil, index: Int? = nil,
            firstAttempts: Int? = nil, host: Int? = nil
        ) -> Builder {
            precondition(millis >= 0, "Latency is at least 0, not \(millis)")
            return adding(kind, index, firstAttempts, host, .latency(millis: millis))
        }

        /// Caps delivery at `bitsPerSecond`, paced at the socket. A network that varies is a
        /// ``ThroughputTrace``; the two compose, and the slower governs.
        public func capThroughputBps(
            _ bitsPerSecond: Int64, kind: ResourceKind? = nil, index: Int? = nil,
            firstAttempts: Int? = nil, host: Int? = nil
        ) -> Builder {
            precondition(bitsPerSecond >= 1, "A throughput cap is at least 1, not \(bitsPerSecond)")
            return adding(kind, index, firstAttempts, host, .throughputCap(bitsPerSecond))
        }

        /// Answers HTTP `status`, with an empty body, instead of the resource — a real status on a
        /// real connection, so a player classifies it as it would a CDN's.
        ///
        /// `firstAttempts` is "a 500 the retry recovers from"; `afterAttempts` its mirror, served
        /// that many times and refused from then on. The two are exclusive, for the Kotlin reason.
        public func failWithHttpStatus(
            _ status: Int, kind: ResourceKind? = nil, index: Int? = nil,
            firstAttempts: Int? = nil, afterAttempts: Int? = nil, host: Int? = nil
        ) -> Builder {
            precondition(
                (400...599).contains(status),
                "An injected HTTP failure status is 4xx or 5xx, not \(status)")
            precondition(
                firstAttempts == nil || afterAttempts == nil,
                "A fault relents after the first attempts or begins after them, not both")
            return adding(
                kind, index, firstAttempts, host, .httpStatus(status), afterAttempts: afterAttempts)
        }

        /// Ends the body after `bytes` while still declaring the full `Content-Length`, then closes
        /// the connection: the request succeeded, the length was promised, and the promise was
        /// broken — which a player meets as a short read rather than as a failed fetch.
        public func truncateAfterBytes(
            _ bytes: Int, kind: ResourceKind? = nil, index: Int? = nil,
            firstAttempts: Int? = nil, host: Int? = nil
        ) -> Builder {
            precondition(bytes >= 0, "A truncation point is at least 0, not \(bytes)")
            return adding(kind, index, firstAttempts, host, .truncate(afterBytes: bytes))
        }

        /// Drops the connection with no response at all, the way a peer's reset arrives: nothing
        /// was answered, so there is no status to classify. The connection is cancelled without a
        /// graceful close, so what the client reads is a reset where the stack sends one and an end
        /// of stream with no response where it does not — either way, no answer.
        public func resetConnection(
            kind: ResourceKind? = nil, index: Int? = nil, firstAttempts: Int? = nil,
            host: Int? = nil
        ) -> Builder {
            adding(kind, index, firstAttempts, host, .connectionReset)
        }

        /// Expires the session's token from media segment `index` onward: 403 from there to the end
        /// — the Kotlin `expireTokenAtSegment`, and its reason: an expired token stays expired, so
        /// every later segment fails too.
        ///
        /// `refreshable` makes the 403s stop once a request arrives bearing a different credential
        /// from the one that first met the fault — its `Authorization` header if it has one,
        /// otherwise its query string, where a signed URL carries its signature. A stock `AVPlayer`
        /// has no way to present a new credential — the playlist's relative URIs carry no query —
        /// so the refresh is something a SuperPlayer's resilience does and a stock player cannot.
        public func expireTokenAtSegment(_ index: Int, refreshable: Bool = false) -> Builder {
            precondition(index >= 0, "A segment index is at least 0, not \(index)")
            var builder = self
            builder.faults.append(
                Fault(
                    kind: .mediaSegment, indices: index...Int.max, attempts: Fault.everyAttempt,
                    host: nil,
                    effect: .expiredToken(refreshable: refreshable)))
            return builder
        }

        /// Puts a shared cache between the player and the origin for the addressed resources,
        /// holding each response `maxAgeSeconds` of real time and serving it with the headers that
        /// say so — the Kotlin `serveThroughCache`: not a failure of any one request, but a CDN told
        /// to keep something longer than it stays true. Addressed at manifests of live content, the
        /// player keeps being handed the playlist the cache took first.
        ///
        /// spec: RFC 9111 — a stored response is served while its age is below its `max-age`
        /// (§4.2), with an `Age` header saying how old it is (§5.1); the response that fills the
        /// store goes out as `Cache-Control: public, max-age=…` (§5.2.2.9, §5.2.2.1). A request
        /// carrying `Cache-Control: no-cache` goes to the origin and its answer replaces the stored
        /// copy (§5.2.1.4), unless `honoursNoCache` is false — a CDN configured to disregard client
        /// directives, which conforms too. Whole responses only: a byte range passes through.
        public func serveThroughCache(
            maxAgeSeconds: Int64, honoursNoCache: Bool = true, kind: ResourceKind? = nil,
            index: Int? = nil, host: Int? = nil
        ) -> Builder {
            precondition(maxAgeSeconds >= 0, "A cache lifetime is at least 0, not \(maxAgeSeconds)")
            return adding(
                kind, index, nil, host,
                .intermediaryCache(maxAgeSeconds: maxAgeSeconds, honoursNoCache: honoursNoCache))
        }

        /// Adds `headers` to every addressed response — a CDN's header set, which is what the
        /// delivery pathologies (`Cache-Control`, a CORS pair) are made of. The Kotlin harness states
        /// these on the content (`TestContent.servedWithResponseHeaders`); here they are the
        /// origin's to send, so they are addressed like any other fault. A name the origin already
        /// sends — `Content-Length`, `Content-Type` — is not replaced: framing is not a fault this
        /// one means to produce.
        public func addResponseHeaders(
            _ headers: [String: String], kind: ResourceKind? = nil, index: Int? = nil,
            host: Int? = nil
        ) -> Builder {
            adding(
                kind, index, nil, host,
                .responseHeaders(headers.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }))
        }

        public func build() -> FaultScript { FaultScript(faults: faults) }

        private func adding(
            _ kind: ResourceKind?, _ index: Int?, _ firstAttempts: Int?, _ host: Int?,
            _ effect: Fault.Effect, afterAttempts: Int? = nil
        ) -> Builder {
            precondition((index ?? 0) >= 0, "A resource index is at least 0, not \(index ?? 0)")
            precondition((firstAttempts ?? 1) >= 1, "A count of attempts is at least 1")
            precondition((afterAttempts ?? 1) >= 1, "A count of attempts served is at least 1")
            precondition((host ?? 0) >= 0, "A host is at least 0, not \(host ?? 0)")
            // An unnamed index means every resource of the kind, and an unnamed attempt count every
            // attempt at it — both ranges, including the late window past the attempts served.
            let attempts: ClosedRange<Int> =
                if let firstAttempts { 1...firstAttempts } else if let afterAttempts {
                    (afterAttempts + 1)...Int.max
                } else { Fault.everyAttempt }
            var builder = self
            builder.faults.append(
                Fault(
                    kind: kind, indices: index.map { $0...$0 } ?? 0...Int.max, attempts: attempts,
                    host: host, effect: effect))
            return builder
        }
    }
}

/// One fault: what it does, and which requests it does it to — the Kotlin `Fault`. `indices` is a
/// range because a token expiry addresses a segment and every one after it; `attempts` is one for the
/// mirror-image reason. A nil `kind` is every kind, and a nil `host` every host.
struct Fault: Sendable {

    /// Attempts are counted from one, so this is every attempt: the fault never relents.
    static let everyAttempt = 1...Int.max

    let kind: ResourceKind?
    let indices: ClosedRange<Int>
    let attempts: ClosedRange<Int>
    let host: Int?
    let effect: Effect

    func matches(_ address: ResourceAddress) -> Bool {
        (kind == nil || kind == address.kind) && (host == nil || host == address.host)
            && indices.contains(address.index) && attempts.contains(address.attempt)
    }

    /// What a matched fault does to the response.
    enum Effect: Sendable {
        case latency(millis: Int64)
        case throughputCap(Int64)
        case httpStatus(Int)
        case truncate(afterBytes: Int)
        case connectionReset
        /// A 403 from the addressed segment on — lasting, when `refreshable`, only until the
        /// request's credential changes.
        case expiredToken(refreshable: Bool)
        case intermediaryCache(maxAgeSeconds: Int64, honoursNoCache: Bool)
        case responseHeaders([(String, String)])

        var kind: FaultScript.Kind {
            switch self {
            case .latency: .latency
            case .throughputCap: .throughputCap
            case .httpStatus: .httpStatus
            case .truncate: .truncation
            case .connectionReset: .connectionReset
            case .expiredToken: .tokenExpiry
            case .intermediaryCache: .intermediaryCache
            case .responseHeaders: .responseHeaders
            }
        }
    }
}

/// Where a request falls in a script's addressing: its host, kind, the index of its resource within
/// that kind on that host, and which attempt at that resource it is.
struct ResourceAddress: Sendable, Hashable {
    let host: Int
    let kind: ResourceKind
    let index: Int
    let attempt: Int
}
