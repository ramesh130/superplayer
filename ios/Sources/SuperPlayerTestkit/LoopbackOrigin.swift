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
import Network
import os

/// An HTTP/1.1 origin on `127.0.0.1` at ports the system chose, serving one ``TestContent`` and
/// counting every request it receives — ADR-0021 rule 2's harness, and the only one `AVPlayer`
/// admits, since it takes no fake transport.
///
/// Started by ``PlaybackHarness/serve(_:faults:network:)`` and stopped with its harness, so every
/// test gets origins of its own: the ports are ephemeral, two origins in one process never share
/// one, and a test's counts are its own whatever else is running.
///
/// **Network.framework rather than BSD sockets.** `NWListener` is a system framework, so it costs
/// no dependency; it binds the loopback address alone through `requiredLocalEndpoint`, which is the
/// rule's "no network but the loopback interface" stated where it is enforced; and its sends
/// complete asynchronously, one callback per write, which is the backpressure the shaping below
/// paces a response with. BSD sockets would need a thread per connection or a `poll` loop of this
/// module's own, and `UnsafeMutableRawPointer` arithmetic at every read, to arrive at the same
/// thing.
///
/// **What it speaks** is `HttpExchange`'s: `GET` and `HEAD`, persistent connections, single byte
/// ranges, and the `Content-Type` RFC 8216 gives each resource. Requests on one connection are
/// answered in the order they arrived, one at a time.
///
/// **One listener per host.** Content served on several hosts (``TestContent/hosts``) opens a
/// listener for each, every one answering from the same publication clock and counted in the same
/// ``networkRequests()``, each request carrying the ``NetworkRequest/host`` it arrived at.
///
/// **Three stages, each one function below.** A request is *recorded* (``networkRequests()``),
/// *answered* — the ``FaultScript`` is applied to its address, and what the origin publishes at its
/// path is resolved where no fault replaces it (``responses()``) — and *sent*, held for the trace's
/// round trip and any injected latency, then paced by the ``ThroughputTrace`` and any cap, and cut
/// short or dropped where a fault says so. Recording comes first so that a refused request is still
/// a counted one.
///
/// **The shaping is per connection**, as the Android replay's is per transfer: two connections in
/// flight each see the whole link (ADR-0021 rule 3, `docs/throughput-traces.md`'s *The replay*).
/// And it paces the *body*, at the moment each chunk's last byte is due by the trace — computed from
/// when the response could first arrive and the bytes sent so far, never from how long the last
/// wait took, so a late wake delays one chunk and is not carried into the next.
public final class LoopbackOrigin: Sendable {

    /// The port the system gave this origin's first host.
    public var port: UInt16 { ports[0] }

    /// How many hosts — listeners, each on a port of its own — serve this origin's content.
    public var hosts: Int { ports.count }

    /// The URL a player is pointed at to play this origin's content, on its first host.
    public var sourceURL: URL { url(path: content.sourcePath) }

    /// The trace this origin paces its responses against, or nil when it paces nothing.
    public let network: ThroughputTrace?

    private let ports: [UInt16]
    private let content: TestContent
    private let listeners: [NWListener]
    private let queue: DispatchQueue
    private let startedAt: ContinuousClock.Instant
    private let state: OSAllocatedUnfairLock<State>

    private struct State: Sendable {
        var requests: [NetworkRequest] = []
        var responses: [OriginResponse] = []
        var connections: [Int: NWConnection] = [:]
        var acceptedConnections = 0
        var stopped = false
        var faults: OriginFaults
    }

    /// The URL a player is pointed at to play this origin's content from `host`.
    public func sourceURL(host: Int) -> URL { url(path: content.sourcePath, host: host) }

    /// The URL of `path` on `host` of this origin, `path` taken relative to its root.
    public func url(path: String, host: Int = 0) -> URL {
        precondition(
            ports.indices.contains(host), "This origin has \(hosts) hosts, not \(host + 1)")
        let relative = path.hasPrefix("/") ? String(path.dropFirst()) : path
        // The address is a literal and the path one this module or its test wrote, so a URL that
        // does not parse is a test written wrongly, said at once.
        guard let url = URL(string: "http://127.0.0.1:\(ports[host])/\(relative)") else {
            preconditionFailure("\(relative) is not a path a URL can carry")
        }
        return url
    }

    /// Every request this origin has received, in the order their heads arrived, repeats included —
    /// every request that *is* one: bytes that do not parse as HTTP are answered 400 and not
    /// recorded, since they name no path or kind to count them under.
    ///
    /// The `networkRequests` of `superplayer-testkit`'s harness, counted at the server rather than
    /// at a transport slot: on iOS the origin is the only place a request is visible from outside
    /// the engine.
    public func networkRequests() -> [NetworkRequest] { state.withLock { $0.requests } }

    /// How the origin answered each request, in the order it decided — what a test reads to show a
    /// fault happened (``OriginResponse``).
    public func responses() -> [OriginResponse] { state.withLock { $0.responses } }

    /// The transport the trace is on now — reported, never switched (``NetworkTransport``) — or nil
    /// when the origin paces nothing.
    public func transport() -> NetworkTransport? { network?.transport(at: elapsedMs()) }

    /// Starts an origin serving `content` on each of its hosts, returning once every listener is
    /// ready on a port.
    static func start(
        serving content: TestContent, faults: FaultScript, network: ThroughputTrace?
    ) async throws -> LoopbackOrigin {
        let queue = DispatchQueue(label: "SuperPlayerTestkit.LoopbackOrigin")
        var listeners: [NWListener] = []
        var ports: [UInt16] = []
        do {
            for _ in 0..<content.hosts {
                let parameters = NWParameters.tcp
                // ADR-0021 rule 2: the loopback address and nothing else; `.any` is an ephemeral
                // port.
                parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
                let listener = try NWListener(using: parameters)
                listeners.append(listener)
                ports.append(try await ready(listener, on: queue))
            }
        } catch {
            for listener in listeners { listener.cancel() }
            throw error
        }
        return LoopbackOrigin(
            content: content, faults: faults, network: network, listeners: listeners,
            queue: queue, ports: ports)
    }

    private init(
        content: TestContent, faults: FaultScript, network: ThroughputTrace?,
        listeners: [NWListener], queue: DispatchQueue, ports: [UInt16]
    ) {
        self.content = content
        self.network = network
        self.listeners = listeners
        self.queue = queue
        self.ports = ports
        self.state = OSAllocatedUnfairLock(initialState: State(faults: OriginFaults(faults)))
        self.startedAt = .now
        // On the listeners' own queue, since they are already running and call the handler from
        // there.
        queue.sync {
            for (host, listener) in listeners.enumerated() {
                listener.newConnectionHandler = { [weak self] connection in
                    guard let self else { return connection.cancel() }
                    self.accept(connection, host: host)
                }
            }
        }
    }

    /// Starts `listener` and waits for the state that settles whether it has a port.
    ///
    /// Connections that arrive before the handler is set are refused rather than queued — nothing
    /// can address this origin before its port is returned, so none can.
    private static func ready(_ listener: NWListener, on queue: DispatchQueue) async throws
        -> UInt16
    {
        listener.newConnectionHandler = { $0.cancel() }
        let resumed = OSAllocatedUnfairLock(initialState: false)
        return try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak listener] state in
                let outcome: Result<UInt16, Error>
                switch state {
                case .ready:
                    guard let port = listener?.port?.rawValue else {
                        outcome = .failure(URLError(.cannotConnectToHost))
                        break
                    }
                    outcome = .success(port)
                case .failed(let error): outcome = .failure(error)
                case .cancelled: outcome = .failure(CancellationError())
                default: return
                }
                // Resumed once, by the first state that settles it.
                let first = resumed.withLock { done in
                    defer { done = true }
                    return !done
                }
                if first { continuation.resume(with: outcome) }
            }
            listener.start(queue: queue)
        }
    }

    /// Stops listening and closes every connection, open or idle. Idempotent.
    func stop() {
        let connections = state.withLock { state -> [NWConnection] in
            guard !state.stopped else { return [] }
            state.stopped = true
            defer { state.connections = [:] }
            return Array(state.connections.values)
        }
        for listener in listeners { listener.cancel() }
        for connection in connections { connection.cancel() }
    }

    private func accept(_ connection: NWConnection, host: Int) {
        let ordinal = state.withLock { state -> Int? in
            guard !state.stopped else { return nil }
            defer { state.acceptedConnections += 1 }
            state.connections[state.acceptedConnections] = connection
            return state.acceptedConnections
        }
        guard let ordinal else { return connection.cancel() }
        connection.stateUpdateHandler = { [weak self] connectionState in
            switch connectionState {
            case .failed:
                // Network.framework still expects a failed connection to be cancelled.
                connection.cancel()
            case .cancelled:
                _ = self?.state.withLock { $0.connections.removeValue(forKey: ordinal) }
            default: break
            }
        }
        connection.start(queue: queue)
        read(connection, at: Arrival(ordinal: ordinal, host: host), buffered: Data())
    }

    /// Which connection a request came in on, and at which host.
    private struct Arrival: Sendable {
        let ordinal: Int
        let host: Int
    }

    /// Answers every whole request at the front of `buffered`, one at a time, then reads more.
    private func read(_ connection: NWConnection, at arrival: Arrival, buffered: Data) {
        switch HttpExchange.parse(buffered) {
        case .request(let request, let consumed):
            let rest = Data(buffered.dropFirst(consumed))
            serve(request, on: connection, at: arrival) { [weak self] in
                guard request.keepAlive, let self else { return connection.cancel() }
                self.read(connection, at: arrival, buffered: rest)
            }
        case .malformed:
            let bytes = HttpExchange.badRequest.serialized(forHead: false)
            connection.send(
                content: bytes, completion: .contentProcessed { _ in connection.cancel() })
        case .incomplete:
            // A read size and not a bound on a request — `HttpExchange.maximumHeadBytes` is that —
            // large enough that any request a player sends arrives in one read.
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
                [weak self] content, _, _, error in
                guard let self, error == nil, let content, !content.isEmpty else {
                    return connection.cancel()
                }
                var buffered = buffered
                buffered.append(content)
                self.read(connection, at: arrival, buffered: buffered)
            }
        }
    }

    /// The three stages the type's doc comment names: record, answer, send.
    private func serve(
        _ request: HttpExchange.Request, on connection: NWConnection, at arrival: Arrival,
        then next: @escaping @Sendable () -> Void
    ) {
        let nowMs = elapsedMs()
        let transport = network?.transport(at: nowMs)
        let (answer, framed) = state.withLock { state in
            let recorded = NetworkRequest(
                method: request.method, path: request.path, query: request.query,
                kind: ResourceKind.of(path: request.path), headers: request.headers,
                connection: arrival.ordinal, host: arrival.host)
            state.requests.append(recorded)
            let address = state.faults.address(host: arrival.host, path: request.path)
            let answer = state.faults.answer(request, at: address, nowMs: nowMs) {
                let published = content.publication(nowMs, ports)[String(request.path.dropFirst())]
                return HttpExchange.resolve(request, body: published)
            }
            let framed = answer.response.map {
                Self.withExtraHeaders(HttpExchange.frame($0, for: request), answer.extraHeaders)
            }
            state.responses.append(
                OriginResponse(
                    request: recorded, status: framed?.status,
                    headers: Dictionary(
                        (framed?.headers ?? []).map { ($0.0.lowercased(), $0.1) },
                        uniquingKeysWith: { _, last in last }),
                    bodyBytesSent: request.method == "HEAD"
                        ? 0 : min(answer.truncateAfterBytes ?? Int.max, framed?.body.count ?? 0),
                    faults: answer.faults, servedFromCache: answer.servedFromCache,
                    transport: transport))
            return (answer, framed)
        }

        let pacing = Pacing(
            firstByteMs: nowMs + (network?.rttMs(at: nowMs) ?? 0) + answer.latencyMs,
            trace: network, capBps: answer.capBps)
        guard let framed else {
            // A reset: nothing is answered, after whatever a response would have waited for.
            return at(pacing.firstByteMs) { connection.forceCancel() }
        }
        let whole = request.method == "HEAD" ? [] : framed.body
        let body = answer.truncateAfterBytes.map { Array(whole.prefix($0)) } ?? whole
        let head = framed.serialized(forHead: true)
        let finish: @Sendable () -> Void
        if answer.truncateAfterBytes != nil {
            finish = { connection.cancel() }
        } else {
            finish = next
        }
        if pacing.isUnpaced {
            let bytes = head + body
            return at(pacing.firstByteMs) { self.send(bytes, on: connection, then: finish) }
        }
        at(pacing.firstByteMs) {
            self.send(head, on: connection) {
                self.sendBody(body, from: 0, pacing: pacing, on: connection, then: finish)
            }
        }
    }

    /// The chunk a paced body is sent in: four kilobytes, a sixtieth of a second at 2 Mbit/s and a
    /// thirtieth at 1 — fine enough that the bytes in flight between two wakes are a small part of
    /// any transfer a test measures, and coarse enough that a 20 Mbit/s profile wakes every 1.6 ms
    /// at most rather than per packet.
    static let pacedChunkBytes = 4 * 1024

    /// Sends `body` from `offset` on, one chunk at a time, each when the pacing says its last byte
    /// is due — the next chunk only once the last has been handed to the connection, which is the
    /// backpressure the type's doc comment names.
    private func sendBody(
        _ body: [UInt8], from offset: Int, pacing: Pacing, on connection: NWConnection,
        then finish: @escaping @Sendable () -> Void
    ) {
        guard offset < body.count else { return finish() }
        let end = min(offset + Self.pacedChunkBytes, body.count)
        at(pacing.dueMs(afterBytes: end)) {
            self.send(Data(body[offset..<end]), on: connection) {
                self.sendBody(body, from: end, pacing: pacing, on: connection, then: finish)
            }
        }
    }

    /// Runs `work` on the origin's queue once `traceMs` of the origin's life have passed — at once
    /// when they already have.
    private func at(_ traceMs: Int64, _ work: @escaping @Sendable () -> Void) {
        let waitMs = traceMs - elapsedMs()
        if waitMs <= 0 { return queue.async(execute: work) }
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(waitMs)), execute: work)
    }

    private func send(
        _ bytes: Data, on connection: NWConnection, then next: @escaping @Sendable () -> Void
    ) {
        connection.send(
            content: bytes,
            completion: .contentProcessed { error in
                if error == nil { next() } else { connection.cancel() }
            })
    }

    /// `response` with `extra` added after its own fields, a name it already carries left alone.
    private static func withExtraHeaders(
        _ response: HttpExchange.Response, _ extra: [(String, String)]
    ) -> HttpExchange.Response {
        var response = response
        for (name, value) in extra
        where !response.headers.contains(where: { $0.0.lowercased() == name.lowercased() }) {
            response.headers.append((name, value))
        }
        return response
    }

    /// How long this origin has been serving: trace time, and the clock a live stream publishes on.
    private func elapsedMs() -> Int64 {
        let (seconds, attoseconds) = (ContinuousClock.now - startedAt).components
        return seconds * 1_000 + attoseconds / 1_000_000_000_000_000
    }

    deinit { for listener in listeners { listener.cancel() } }
}

/// When a response's body bytes may go: the trace's arrival time and a cap's, whichever is later,
/// from the moment its first byte could arrive.
struct Pacing: Sendable {
    let firstByteMs: Int64
    let trace: ThroughputTrace?
    let capBps: Int64?

    var isUnpaced: Bool { trace == nil && capBps == nil }

    /// The origin time by which the first `bytes` of the body are due — the Android shaper's
    /// `arrivalMs` for the trace, and the same integer arithmetic for a cap, so the slower governs.
    func dueMs(afterBytes bytes: Int) -> Int64 {
        var due = firstByteMs
        if let trace { due = max(due, trace.arrivalMs(startMs: firstByteMs, bytes: Int64(bytes))) }
        if let capBps {
            let bitMillis = Int64(bytes) * 8_000
            due = max(due, firstByteMs + (bitMillis + capBps - 1) / capBps)
        }
        return due
    }
}
