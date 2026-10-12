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
import MoqFFI

/// ``MoqRelay`` over MoQ's own bindings: the one implementation of this product's seam that touches
/// the native library — the Kotlin `UniffiMoqRelay`.
///
/// **Nothing under `ios/check` runs a line of it.** No QUIC session is opened under the check, so
/// what stands here in every test is a scripted fake of ``MoqRelay``; what exercises this file is a
/// real session against a relay, on a device, which is the i9 device ticket's. The two corrections
/// #367 made to the Kotlin adapter — the namespace belongs to the **session URL**, and a fresh
/// session is refused `unroutable` for a broadcast that exists until it asks again — are ported
/// here as decisions already settled rather than rediscovered, each where it is made.
///
/// ## `async` becomes blocking, on this side of the seam
///
/// Every interesting binding call is `async`, and each is called here through ``blocking(_:)`` on a
/// thread ``MoqFrameSource`` owns — never the main actor and never a thread of the cooperative pool,
/// which a blocked waiter would starve. The seam's threading is the pump's own affair, and a fake of
/// the seam is a fake of a transport rather than of an executor.
struct UniffiMoqRelay: MoqRelay {

    func connect(_ url: URL) throws -> any MoqBroadcastSession {
        let client = MoqClient()
        let session: MoqSession
        do {
            let address = Self.connectURL(of: url)
            session = try blocking { try await client.connect(url: address) }
        } catch {
            client.cancel()
            throw MoqTransportError("Could not open a MoQ session for \(url): \(error)")
        }
        do {
            return UniffiBroadcastSession(
                client: client, session: session,
                broadcast: try Self.requestBroadcastWhenRoutable(
                    session, name: Self.broadcastPath(of: url)))
        } catch {
            session.cancel(code: 0)
            client.cancel()
            throw MoqTransportError(
                "Connected to the MoQ relay but could not request the broadcast \(url): \(error)")
        }
    }

    /// Requests `name`, retrying while the relay answers `unroutable`, until ``routableBoundMs``.
    ///
    /// #367 measured on Android that a freshly connected session cannot at once route a broadcast
    /// that exists, and that asking again a moment later on the same session resolves it; three arms
    /// showed that the **wait** is what matters and listening for announcements is not. What the
    /// relay is doing in that window is not known and is not guessed at here. A retry rather than a
    /// delay, because a ready relay then pays one round trip and no wait; only `unroutable` is
    /// retried, because every other refusal is a fact a second ask cannot change.
    private static func requestBroadcastWhenRoutable(
        _ session: MoqSession, name: String
    ) throws -> MoqBroadcastConsumer {
        let consumer = session.consumer()
        let deadline = monotonicNowMs() + routableBoundMs
        var wait = firstRetryMs
        while true {
            do {
                return try blocking { try await consumer.requestBroadcast(path: name) }
            } catch {
                let retryable = "\(error)".localizedCaseInsensitiveContains(unroutable)
                guard retryable, monotonicNowMs() + wait < deadline else { throw error }
                Thread.sleep(forTimeInterval: Double(wait) / 1_000)
                // Doubling, for core's `Backoff` reason: a fixed interval against a relay that needs
                // longer spends every attempt early and none late. No jitter, because this is one
                // subscription for one player rather than a fleet retrying in step.
                wait *= 2
            }
        }
    }

    /// How long a broadcast is asked for before `unroutable` is taken at its word: **5 s**, the
    /// Kotlin bound — chosen against a viewer's patience rather than the relay; the window #367
    /// observed was under three.
    static let routableBoundMs: Int64 = 5_000

    /// The first retry interval; it doubles from here. Short, because a ready relay never waits.
    static let firstRetryMs: Int64 = 100

    /// What MoQ calls the refusal a later ask can resolve.
    static let unroutable = "unroutable"

    /// The relay's own address: the URL's authority **and its namespace segment**, under `https`.
    ///
    /// // spec: draft-ietf-moq-transport §3.1 — a MoQ session over WebTransport is established by a
    /// // WebTransport `CONNECT` whose URI is an `https` one naming the relay, which is why the scheme
    /// // handed to the bindings is `https` and not ``MoqFrameSource/scheme``: `moq://` is how a
    /// // *broadcast* is written on this side, and nothing puts it on a wire.
    ///
    /// No public document states where one address divides between the bindings' URL and broadcast
    /// name. #367 settled it against a live relay: the namespace belongs to the **session** —
    /// connecting to the bare authority and requesting `anon/<name>` announced nothing and was
    /// refused `unroutable`, while connecting to `https://<authority>/anon` and requesting `<name>`
    /// resolved. A broadcast name may itself contain `/`, so the **first** path segment is the
    /// namespace and everything after it is the name; a two-segment namespace is not addressable
    /// by one `moq://` URL under this rule, which is stated rather than hidden.
    static func connectURL(of url: URL) -> String {
        var components = URLComponents()
        components.scheme = "https"
        components.host = url.host
        components.port = url.port
        let namespace = namespace(of: url)
        components.percentEncodedPath = namespace.isEmpty ? "" : "/" + namespace
        return components.string ?? ""
    }

    /// The broadcast's name at that relay: everything after the namespace segment, slashes kept.
    /// Empty where the URL carried no segment beyond the namespace, which the relay refuses as
    /// unroutable — the honest failure for an address that names no broadcast.
    static func broadcastPath(of url: URL) -> String {
        let path = trimmedPath(of: url)
        guard let slash = path.firstIndex(of: "/") else { return "" }
        return String(path[path.index(after: slash)...])
    }

    /// The one leading segment a relay publishes under, or empty.
    private static func namespace(of url: URL) -> String {
        String(trimmedPath(of: url).prefix { $0 != "/" })
    }

    private static func trimmedPath(of url: URL) -> String {
        let path =
            URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? ""
        return String(path.drop { $0 == "/" })
    }
}

/// One `MoqSession` and the `MoqBroadcastConsumer` over it, closed in that order.
private final class UniffiBroadcastSession: MoqBroadcastSession {
    private let client: MoqClient
    /// Held because it is what the statistics are polled from: `stats()` is a snapshot of the
    /// session.
    private let session: MoqSession
    private let broadcast: MoqBroadcastConsumer

    init(client: MoqClient, session: MoqSession, broadcast: MoqBroadcastConsumer) {
        self.client = client
        self.session = session
        self.broadcast = broadcast
    }

    /// The first catalog the publisher sends. The catalog subscription is cancelled as soon as it
    /// has been read: `onTracks(_:)` is called once, so a republished catalog has nowhere to go and a
    /// consumer left open would be a subscription nothing drains. A catalog track closed without ever
    /// publishing one is refused as itself, not read as an empty catalog.
    func catalog() throws -> MoqCatalog {
        let broadcast = broadcast
        let consumer = try blocking { try await broadcast.subscribeCatalog() }
        defer { consumer.cancel() }
        guard let catalog = try blocking({ try await consumer.next() }) else {
            throw MoqTransportError(
                "The MoQ broadcast closed its catalog track without publishing a catalog")
        }
        return catalog
    }

    func subscribe(trackName: String, container: MoqContainer) throws -> any MoqTrackStream {
        let broadcast = broadcast
        // At the live edge: every field the bindings' own default, which is what `MoqSubscription()`
        // is. A realtime stream is live and unseekable (ADR-0018 rule 5) and the player refuses a
        // start position before this product is reached, so there is no group range to ask for and
        // no latency target this library has any business choosing on a publisher's behalf.
        return UniffiTrackStream(
            try blocking {
                try await broadcast.subscribeMedia(
                    name: trackName, container: container, subscription: MoqSubscription())
            })
    }

    /// `MoqSession.stats()`, stamped and renamed: `bytesLost` and `packetsLost` cross as
    /// `transportBytesLost` and `transportPacketsLost` (``MoqSessionStatistics`` argues it), and a
    /// field the session did not report stays nil across this line rather than becoming a zero. The
    /// counters are unsigned across the FFI and signed here; exact for every value a session can
    /// reach, since 2^63 bytes is more than any connection moves.
    func statistics() throws -> MoqSessionStatistics {
        let stats = session.stats()
        return MoqSessionStatistics(
            sampledAtMs: monotonicNowMs(),
            roundTripTimeUs: stats.rttUs.map(Int64.init(truncatingIfNeeded:)),
            sendRateBps: stats.sendRateBps.map(Int64.init(truncatingIfNeeded:)),
            receiveRateBps: stats.recvRateBps.map(Int64.init(truncatingIfNeeded:)),
            bytesSent: stats.bytesSent.map(Int64.init(truncatingIfNeeded:)),
            bytesReceived: stats.bytesReceived.map(Int64.init(truncatingIfNeeded:)),
            packetsSent: stats.packetsSent.map(Int64.init(truncatingIfNeeded:)),
            packetsReceived: stats.packetsReceived.map(Int64.init(truncatingIfNeeded:)),
            transportBytesLost: stats.bytesLost.map(Int64.init(truncatingIfNeeded:)),
            transportPacketsLost: stats.packetsLost.map(Int64.init(truncatingIfNeeded:)))
    }

    func close() {
        session.cancel(code: 0)
        client.cancel()
    }
}

/// One rendition's `MoqMediaConsumer`, pulled one frame at a time.
private final class UniffiTrackStream: MoqTrackStream {
    private let consumer: MoqMediaConsumer

    init(_ consumer: MoqMediaConsumer) { self.consumer = consumer }

    func next() throws -> MoqMediaFrame? {
        let consumer = consumer
        return try blocking { try await consumer.next() }
    }

    /// `cancel()`, which is what unblocks a `next` parked on another thread — the half of this
    /// class that makes ``MoqFrameSource``'s join finite and obligation 9 true. The handle itself is
    /// freed when the last reference goes, which is ARC's and needs no call.
    func close() { consumer.cancel() }
}

/// Runs `operation` on a task and blocks the calling thread until it finishes — the Kotlin
/// `runBlocking`. Called only on threads ``MoqFrameSource`` started, which are no executor's, so the
/// wait starves nothing.
private func blocking<T: Sendable>(
    _ operation: @escaping @Sendable () async throws -> T
) throws -> T {
    let outcome = Outcome<T>()
    let done = DispatchSemaphore(value: 0)
    Task {
        do {
            outcome.set(.success(try await operation()))
        } catch {
            outcome.set(.failure(error))
        }
        done.signal()
    }
    done.wait()
    return try outcome.get()
}

/// The result ``blocking(_:)`` hands from the task to the waiting thread. `@unchecked Sendable`:
/// written once before the semaphore is signalled and read once after it is waited on, under a lock
/// besides.
private final class Outcome<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Result<T, any Error>?

    func set(_ value: Result<T, any Error>) { lock.withLock { result = value } }

    func get() throws -> T {
        guard let result = lock.withLock({ result }) else {
            throw MoqTransportError("A MoQ binding call returned without a result")
        }
        return try result.get()
    }
}

/// `CLOCK_MONOTONIC` in milliseconds, the clock core's `ConditionsBinding.monotonicNowMs()` reads —
/// written again because that one is core's `package` API, which a package of its own cannot reach.
func monotonicNowMs() -> Int64 {
    Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
}
