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

/// The least HTTP/1.1 origin `AVPlayer` will play a stream from: `GET` of a path answers that
/// file's bytes or a 404, one request per connection, on `127.0.0.1` at an ephemeral port.
///
/// **A stopgap, and test-only.** ADR-0021 rule 2's origin is `SuperPlayerTestkit`'s, with faults
/// and shaping, and it is #399's to build; when it lands, `SyntheticHlsPlaybackTests` plays from it
/// and this file goes. It exists because `SyntheticHlsPlaybackTests` has to play over HTTP:
/// `AVPlayer` handed the same playlists by `file:` URL never leaves `AVPlayerItem.Status.unknown`
/// — HLS is loaded over HTTP or not at all — so the Kotlin `writeTo` route has no iOS twin. It
/// binds the loopback address alone, which is the rule's "no network but the loopback interface".
final class StaticLoopbackOrigin: Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "StaticLoopbackOrigin")
    /// The port the listener was given, once it is ready.
    let port: UInt16

    /// Starts serving `files`, keyed by path relative to the origin's root.
    init(files: [String: [UInt8]]) async throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        let queue = self.queue
        listener.newConnectionHandler = { connection in
            connection.start(queue: queue)
            Self.serve(connection, files: files, request: Data())
        }
        // The continuation is resumed by the first state that settles the question, and only once.
        let resumed = OSAllocatedUnfairLock(initialState: false)
        port = try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak listener] state in
                let outcome: Result<UInt16, Error>?
                switch state {
                case .ready:
                    // A ready listener with no port is not one to hand a URL to.
                    outcome =
                        listener?.port.map { .success($0.rawValue) }
                        ?? .failure(URLError(.cannotConnectToHost))
                case .failed(let error): outcome = .failure(error)
                case .cancelled: outcome = .failure(CancellationError())
                default: outcome = nil
                }
                guard let outcome else { return }
                let first = resumed.withLock { done in
                    defer { done = true }
                    return !done
                }
                if first { continuation.resume(with: outcome) }
            }
            listener.start(queue: queue)
        }
        self.listener = listener
    }

    /// The URL of `path` on this origin.
    func url(_ path: String) -> URL { URL(string: "http://127.0.0.1:\(port)/\(path)")! }

    func stop() { listener.cancel() }

    /// Reads until the end of the request head, answers it, and closes: `Connection: close` on
    /// every response, so no request ever shares a connection and no body is ever read.
    private static func serve(_ connection: NWConnection, files: [String: [UInt8]], request: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) {
            content, _, isComplete, error in
            var request = request
            if let content { request.append(content) }
            guard let head = String(data: request, encoding: .utf8), head.contains("\r\n\r\n")
            else {
                if error != nil || isComplete {
                    connection.cancel()
                } else {
                    serve(connection, files: files, request: request)
                }
                return
            }
            // spec: RFC 9112 §3 — the request line is method, target and version, space-separated.
            let target = head.split(separator: "\r\n").first?.split(separator: " ").dropFirst()
                .first
            let path = target.map { String($0.drop { $0 == "/" }) } ?? ""
            let body = files[path]
            var response = body == nil ? "HTTP/1.1 404 Not Found\r\n" : "HTTP/1.1 200 OK\r\n"
            response += "Content-Type: \(contentType(of: path))\r\n"
            response += "Content-Length: \(body?.count ?? 0)\r\n"
            response += "Connection: close\r\n\r\n"
            var bytes = Data(response.utf8)
            bytes.append(contentsOf: body ?? [])
            connection.send(
                content: bytes, completion: .contentProcessed { _ in connection.cancel() })
        }
    }

    /// spec: RFC 8216 §4 — a playlist is `application/vnd.apple.mpegurl`; packed audio is the
    /// elementary stream's own type, `audio/aac`.
    private static func contentType(of path: String) -> String {
        if path.hasSuffix(".m3u8") { return "application/vnd.apple.mpegurl" }
        if path.hasSuffix(".aac") { return "audio/aac" }
        return "application/octet-stream"
    }
}
