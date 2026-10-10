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

/// An HTTP/1.1 origin on `127.0.0.1` at a port the system chose, serving one ``TestContent`` and
/// counting every request it receives — ADR-0021 rule 2's harness, and the only one `AVPlayer`
/// admits, since it takes no fake transport.
///
/// Started by ``PlaybackHarness/serve(_:)`` and stopped with its harness, so every test gets
/// origins of its own: the port is ephemeral, two origins in one process never share one, and a
/// test's counts are its own whatever else is running.
///
/// **Network.framework rather than BSD sockets.** `NWListener` is a system framework, so it costs
/// no dependency; it binds the loopback address alone through `requiredLocalEndpoint`, which is the
/// rule's "no network but the loopback interface" stated where it is enforced; and its sends
/// complete asynchronously, one callback per write, which is the backpressure #410's shaping paces
/// a response with. BSD sockets would need a thread per connection or a `poll` loop of this
/// module's own, and `UnsafeMutableRawPointer` arithmetic at every read, to arrive at the same
/// thing.
///
/// **What it speaks** is `HttpExchange`'s: `GET` and `HEAD`, persistent connections, single byte
/// ranges, and the `Content-Type` RFC 8216 gives each resource. Requests on one connection are
/// answered in the order they arrived, one at a time.
///
/// **The seam #410 fills.** A request passes three stages, each one function below: it is
/// *recorded* (``networkRequests``), the published bytes at its path are *resolved* into a response,
/// and the response is *sent*. A `FaultScript` decides between the first two — a status, a latency,
/// a header set — and after the second — a truncation; a `ThroughputTrace` paces the third.
/// Recording comes first so that a refused request is still a counted one.
public final class LoopbackOrigin: Sendable {

    /// The port the system gave this origin's listener.
    public let port: UInt16

    /// The URL a player is pointed at to play this origin's content.
    public var sourceURL: URL { url(path: content.sourcePath) }

    private let content: TestContent
    private let listener: NWListener
    private let queue: DispatchQueue
    private let startedAt: ContinuousClock.Instant
    private let state = OSAllocatedUnfairLock(initialState: State())

    private struct State: Sendable {
        var requests: [NetworkRequest] = []
        var connections: [Int: NWConnection] = [:]
        var acceptedConnections = 0
        var stopped = false
    }

    /// The URL of `path` on this origin, `path` taken relative to its root.
    public func url(path: String) -> URL {
        let relative = path.hasPrefix("/") ? String(path.dropFirst()) : path
        // The address is a literal and the path one this module or its test wrote, so a URL that
        // does not parse is a test written wrongly, said at once.
        guard let url = URL(string: "http://127.0.0.1:\(port)/\(relative)") else {
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

    /// Starts an origin serving `content`, returning once its listener is ready on a port.
    static func start(serving content: TestContent) async throws -> LoopbackOrigin {
        let parameters = NWParameters.tcp
        // ADR-0021 rule 2: the loopback address and nothing else; `.any` is an ephemeral port.
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        let queue = DispatchQueue(label: "SuperPlayerTestkit.LoopbackOrigin")
        let port = try await ready(listener, on: queue)
        return LoopbackOrigin(content: content, listener: listener, queue: queue, port: port)
    }

    private init(content: TestContent, listener: NWListener, queue: DispatchQueue, port: UInt16) {
        self.content = content
        self.listener = listener
        self.queue = queue
        self.port = port
        self.startedAt = .now
        // On the listener's own queue, since the listener is already running and calls the handler
        // from there.
        queue.sync {
            listener.newConnectionHandler = { [weak self] connection in
                guard let self else { return connection.cancel() }
                self.accept(connection)
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
        listener.cancel()
        for connection in connections { connection.cancel() }
    }

    private func accept(_ connection: NWConnection) {
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
        read(connection, ordinal: ordinal, buffered: Data())
    }

    /// Answers every whole request at the front of `buffered`, one at a time, then reads more.
    private func read(_ connection: NWConnection, ordinal: Int, buffered: Data) {
        switch HttpExchange.parse(buffered) {
        case .request(let request, let consumed):
            let rest = Data(buffered.dropFirst(consumed))
            serve(request, on: connection, ordinal: ordinal) { [weak self] in
                guard request.keepAlive, let self else { return connection.cancel() }
                self.read(connection, ordinal: ordinal, buffered: rest)
            }
        case .malformed:
            send(HttpExchange.badRequest.serialized(forHead: false), on: connection) {
                connection.cancel()
            }
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
                self.read(connection, ordinal: ordinal, buffered: buffered)
            }
        }
    }

    /// The three stages the type's doc comment names: record, resolve, send.
    private func serve(
        _ request: HttpExchange.Request, on connection: NWConnection, ordinal: Int,
        then next: @escaping @Sendable () -> Void
    ) {
        state.withLock {
            $0.requests.append(
                NetworkRequest(
                    method: request.method, path: request.path, query: request.query,
                    kind: ResourceKind.of(path: request.path), headers: request.headers,
                    connection: ordinal))
        }
        let elapsedMs = Self.milliseconds(ContinuousClock.now - startedAt)
        let published = content.publication(elapsedMs)[String(request.path.dropFirst())]
        let response = HttpExchange.respond(to: request, body: published)
        send(response.serialized(forHead: request.method == "HEAD"), on: connection, then: next)
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

    private static func milliseconds(_ duration: Duration) -> Int64 {
        let (seconds, attoseconds) = duration.components
        return seconds * 1_000 + attoseconds / 1_000_000_000_000_000
    }

    deinit { listener.cancel() }
}
