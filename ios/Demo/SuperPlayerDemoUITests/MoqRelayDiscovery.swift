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

/// What a relay says, asked directly through the bindings, for a report rather than for an
/// assertion — the Kotlin `MoqRelayDiscovery` (superplayer-moq's `androidTest`), ported arm for arm.
///
/// **Not part of the transport, and test-only**, for the Kotlin file's reasons: `MoqFrameSource`
/// subscribes to the broadcast a consumer named, and choosing a broadcast is a larger version of the
/// selection ADR-0018 rule 7 defers. It reaches the bindings directly rather than through
/// `SuperPlayerMoQ`'s `MoqRelay` seam, which is `internal` there and carries only the verbs the pump
/// needs; widening it so a test could enumerate broadcasts would put a method on a production seam
/// that production code never calls.
///
/// What it is for is the difference between reports that `unroutable` cannot tell apart: a relay that
/// is down, a relay that is up and empty, a name that is wrong, and a session that is wrong — which is
/// how #367's two defects were found on Android and how they are checked for here.
///
/// **Every probe varies one thing.** ``probeBroadcast(connectURL:name:boundMs:announceFirst:settleMs:)``
/// takes the wait and the listening separately, because #367's first comparison folded them together
/// and wrote the wrong one down as the mechanism (`docs/testing.md`, *The first real MoQ session*).
enum MoqRelayDiscovery {

    /// What an announce listing said, as something that cannot throw at the reporting site.
    enum Announced {
        /// The relay answered; `paths` may legitimately be empty, which is itself the finding.
        case paths([String])
        /// The relay could not be asked at all.
        case failed(String)

        var isEmptyAnswer: Bool {
            if case .paths(let paths) = self { return paths.isEmpty }
            return false
        }

        var described: String {
            switch self {
            case .paths(let paths) where paths.isEmpty:
                "the relay answered and is publishing nothing under this prefix"
            case .paths(let paths):
                "\(paths.count) announced: \(paths.joined(separator: ", "))"
            case .failed(let description):
                "could not be asked: \(description)"
            }
        }
    }

    /// The broadcast paths announced under `prefix` at `connectURL`, read until the stream goes quiet
    /// for `boundMs` — an announce stream on a live relay does not end, so the read is a snapshot —
    /// or until ``maxReported`` have arrived. Never throws: its caller is already reporting a
    /// failure, and a second error from a diagnostic would replace the finding with its own.
    static func announcedUnder(connectURL: String, prefix: String, boundMs: Int64) async
        -> Announced
    {
        let client = MoqClient()
        defer { client.cancel() }
        do {
            guard
                let session = try await bounded(
                    boundMs, { try await client.connect(url: connectURL) })
            else { return .failed("connect timed out after \(boundMs) ms") }
            defer { session.cancel(code: 0) }
            let announced = try session.consumer().announced(prefix: prefix)
            defer { announced.cancel() }
            var paths: [String] = []
            while paths.count < maxReported,
                let announcement = try await bounded(boundMs, { try await announced.next() }),
                let found = announcement
            {
                paths.append(found.path())
            }
            return .paths(paths)
        } catch {
            return .failed(describe(error))
        }
    }

    /// Requests `name` at `connectURL` and reads its catalog, reporting how far it got.
    ///
    /// The two things that might make a request route are varied **independently**: `settleMs` waits
    /// after connecting and before requesting, announcing nothing — the control #367's first version
    /// lacked — and `announceFirst` opens the announce stream on the same consumer and reads one
    /// announcement from it, bounded by `settleMs`, so the two arms wait alike and differ only in
    /// whether anything listened. One `MoqOriginConsumer` serves both, as in `UniffiMoqRelay`.
    static func probeBroadcast(
        connectURL: String, name: String, boundMs: Int64, announceFirst: Bool, settleMs: Int64
    ) async -> String {
        let client = MoqClient()
        defer { client.cancel() }
        do {
            guard
                let session = try await bounded(
                    boundMs, { try await client.connect(url: connectURL) })
            else { return "connect timed out after \(boundMs) ms" }
            defer { session.cancel(code: 0) }
            let consumer = session.consumer()
            var listening: MoqAnnounced?
            if announceFirst {
                do {
                    listening = try consumer.announced(prefix: "")
                } catch {
                    // An arm that could not listen is neither a silent arm nor a listening one, and
                    // labelling it either would make the comparison meaningless.
                    return "could not open the announce stream: \(describe(error))"
                }
            }
            defer { listening?.cancel() }
            if let listening, settleMs > 0 {
                _ = try? await bounded(settleMs, { try await listening.next() })
            } else if settleMs > 0 {
                try await Task.sleep(for: .milliseconds(settleMs))
            }
            return await requestAndRead(consumer, name: name, boundMs: boundMs)
        } catch {
            return "threw: \(describe(error))"
        }
    }

    /// The request and catalog half, so the arms above differ only in what precedes it.
    private static func requestAndRead(
        _ consumer: MoqOriginConsumer, name: String, boundMs: Int64
    ) async -> String {
        let broadcast: MoqBroadcastConsumer
        do {
            guard
                let requested = try await bounded(
                    boundMs, { try await consumer.requestBroadcast(path: name) })
            else { return "connected, then requestBroadcast timed out" }
            broadcast = requested
        } catch {
            return "connected, but requestBroadcast refused: \(describe(error))"
        }
        do {
            guard
                let catalogs = try await bounded(
                    boundMs, { try await broadcast.subscribeCatalog() })
            else { return "requested, then subscribeCatalog timed out" }
            defer { catalogs.cancel() }
            guard let next = try await bounded(boundMs, { try await catalogs.next() }),
                let catalog = next
            else { return "catalog subscribed, but none published within \(boundMs) ms" }
            return "catalog read: \(catalog.video.count) video, \(catalog.audio.count) audio "
                + "(video=\(catalog.video.keys.sorted().joined(separator: "|"))"
                + " audio=\(catalog.audio.keys.sorted().joined(separator: "|")))"
        } catch {
            return "requested, then the catalog failed: \(describe(error))"
        }
    }

    /// `operation`'s result, or nil once `boundMs` has passed first.
    ///
    /// Raced on a continuation rather than in a task group, because a group waits for every child and
    /// a binding call that never returns would hold the race open forever; the loser is cancelled and
    /// left, and the session it was waiting on is cancelled by the caller's `defer` a moment later,
    /// which is what ends it.
    static func bounded<T: Sendable>(
        _ boundMs: Int64, _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T? {
        let race = Race<T>()
        return try await withCheckedThrowingContinuation { continuation in
            race.begin(continuation)
            let work = Task {
                do { race.finish(.success(try await operation())) } catch {
                    race.finish(.failure(error))
                }
            }
            Task {
                try? await Task.sleep(for: .milliseconds(boundMs))
                race.finish(.success(nil))
                work.cancel()
            }
        }
    }

    /// The one resumption of ``bounded(_:_:)``'s continuation, whichever side gets there first.
    /// `@unchecked Sendable`: every field is under `lock`.
    private final class Race<T: Sendable>: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<T?, any Error>?

        func begin(_ continuation: CheckedContinuation<T?, any Error>) {
            lock.withLock { self.continuation = continuation }
        }

        func finish(_ result: Result<T?, any Error>) {
            let waiting = lock.withLock {
                defer { continuation = nil }
                return continuation
            }
            waiting?.resume(with: result)
        }
    }

    static func describe(_ error: any Error) -> String { "\(type(of: error)): \(error)" }

    /// How many announcements are reported before the stream is cancelled: the Kotlin bound and its
    /// reason — a public relay's anonymous namespace is whatever its visitors left running, and a
    /// report wants the shape rather than every row.
    static let maxReported = 12
}
