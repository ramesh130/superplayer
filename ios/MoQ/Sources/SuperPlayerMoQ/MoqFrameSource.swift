//
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
import SuperPlayerCore

/// A MoQ broadcast, as SuperPlayer's realtime seam sees it: subscribe, receive encoded frames,
/// cancel — the Kotlin `MoqFrameSource`, ported whole.
///
/// ```swift
/// let player = RealtimePlayer(
///     configuration: .init(
///         sources: Realtime.transport(scheme: MoqFrameSource.scheme) { MoqFrameSource(url: $0) }))
/// try player.setMediaRequest(
///     MediaRequest(contentId: "studio-a", sources: [URL(string: "moq://relay.example/anon/studio-a")!]))
/// ```
///
/// What it implements is core's public ``FrameSource``, and the test that it is a bridge rather than
/// a leak is that **no public signature here names a CoreMedia, AVFoundation or binding type**.
/// Nothing here decodes and nothing parses a container: the bindings strip the framing below the FFI
/// boundary (ADR-0018 rule 3, #340), so a payload arrives as codec bitstream and is handed on as is.
///
/// ## What one subscription does
///
/// One connect thread, and one pump thread per track. The connect thread opens the session, reads
/// the catalog **once**, turns it into tracks through ``MoqCatalogTracks``, subscribes to each,
/// declares them all in a single `onTracks(_:)`, and then hands each track to a pump. A thread per
/// track is forced by the seam being a **blocking pull**: one pump asking track 0 for its next frame
/// cannot also be asking track 1. What that costs is obligation 1, paid by `delivery`, the one lock
/// every callback goes through — the happens-before the obligation permits rather than the single
/// delivering thread it prefers.
///
/// ## The three obligations this class makes true rather than trusts
///
/// **2**, the declaration before the first frame, is structural: no pump exists until `onTracks(_:)`
/// has returned. **3**, each track starting on a keyframe, is enforced per track by the pump rather
/// than assumed of the publisher: a MoQ subscription may begin part-way through a group, and the
/// dependent frames before the first keyframe are dropped. **9**, cancellation, is ``cancel()``'s.
///
/// ## What reaches the consumer when something fails
///
/// Everything, as `onError(_:)`. Nothing throws out of ``subscribe(_:)`` or off a pump thread, and the
/// first failure wins, because a subscription ends once.
///
/// `@unchecked Sendable`: every `var` is read and written under `lifecycle` or `delivery` as its
/// comment says, and the rest is immutable.
public final class MoqFrameSource: FrameSource, @unchecked Sendable {

    /// The URL scheme this transport answers, which is what `Realtime.transport(scheme:)` is given:
    /// `moq`, the scheme MoQ's own tooling addresses a broadcast with, bare, without the `:`.
    public static let scheme = "moq"

    /// How often a subscription's session is asked for ``statistics()``: **one second**.
    ///
    /// MoQ exports statistics as a poll and not a stream, so the number is this library's, and the
    /// Kotlin constant's argument holds unchanged in both directions. **Not the ten seconds**
    /// `docs/telemetry-schema.md` states for the periodic telemetry events, which are time-weighted
    /// quantities a warehouse averages: this is a snapshot of a sub-second-latency link, and a round
    /// trip time ten seconds old describes a link that has since changed. **Not less**, because the
    /// readers — a HUD, a support tool — read at about this rate, and a poll is a round trip across
    /// the FFI boundary that buys nothing faster.
    public static let statisticsIntervalMs: Int64 = 1_000

    private let url: URL
    private let relay: any MoqRelay

    /// The polling cadence, on the internal initializer only: the cadence is not a consumer's to
    /// vary, and it is a parameter at all so the scripted relay can be polled without waiting seconds.
    private let statisticsIntervalMs: Int64

    /// The lock every callback on the sink is made under (obligation 1), and which also guards
    /// `terminated`, because "has this subscription ended" and "may this callback be made" are one
    /// question asked at one moment.
    private let delivery = NSLock()

    /// Whether a terminal callback has been made. Under `delivery` alone.
    private var terminated = false

    /// What has been opened and whether ``cancel()`` has run. Separate from `delivery` so that a
    /// cancel need not wait for a pump inside `onFrame(_:)` before it can begin closing the session.
    private let lifecycle = NSLock()

    /// Under `lifecycle`.
    private var cancelled = false
    private var session: (any MoqBroadcastSession)?
    private var streams: [any MoqTrackStream] = []
    private var threads: [Started] = []

    /// What the statistics thread waits on between polls and ``cancel()`` wakes it with — a wait
    /// rather than a sleep, so the join in ``cancel()`` costs a cancellation nothing.
    private let statisticsPacing = NSCondition()

    /// The last reading the poller took. Under `statisticsPacing`'s lock.
    private var latestStatistics: MoqSessionStatistics?

    /// Opens `url` over MoQ's own bindings, which is what a consumer wants.
    ///
    /// `url` is `moq://<relay>/<namespace>/<broadcast>`: the relay's authority, the one namespace
    /// segment the relay publishes under, and the broadcast's name after it, slashes and all
    /// (``UniffiMoqRelay`` says where that split was settled).
    public convenience init(url: URL) {
        self.init(url: url, relay: UniffiMoqRelay())
    }

    init(
        url: URL, relay: any MoqRelay,
        statisticsIntervalMs: Int64 = MoqFrameSource.statisticsIntervalMs
    ) {
        self.url = url
        self.relay = relay
        self.statisticsIntervalMs = statisticsIntervalMs
    }

    /// What this subscription's MoQ session last said about itself, or **nil** where it has said
    /// nothing — a subscription not yet connected, one whose first poll is still to happen, or a
    /// session that answered no statistics. Never stood in for by a record of zeroes, and **kept
    /// after ``cancel()``**, since a session's final numbers are the ones a bug report wants and
    /// ``MoqSessionStatistics/sampledAtMs`` tells a stale reading from a live one.
    ///
    /// Safe from any thread and cheap: it answers the poller's last snapshot rather than crossing the
    /// FFI boundary on the caller's thread. Not telemetry; ``MoqSessionStatistics`` says why.
    public func statistics() -> MoqSessionStatistics? {
        statisticsPacing.withLock { latestStatistics }
    }

    /// Connects on a thread of this source's own and returns: the caller is the main actor, and a
    /// QUIC handshake against a relay on the other side of the world is not a thing to block it on.
    public func subscribe(_ sink: any FrameSink) {
        start("moq-connect", sink) { [self] in try connect(sink) }
    }

    /// Ends delivery: closes the streams and the session, and **joins every thread this source
    /// started** before returning (obligation 9).
    ///
    /// The join is the whole of it: closing stops the bytes, but a pump already inside `onFrame(_:)`
    /// would go round its loop once more. Closing first is what makes the join finite — a pump parked
    /// in a blocking ``MoqTrackStream/next()`` is woken by its stream closing. Safe to call twice and
    /// from any thread; a call from one of this source's own threads does not join itself.
    public func cancel() {
        let (openStreams, openSession) = lifecycle.withLock {
            cancelled = true
            defer {
                streams.removeAll()
                session = nil
            }
            return (streams, session)
        }
        openStreams.forEach { $0.close() }
        openSession?.close()
        // The poller is parked on this condition rather than asleep, so the join below costs a
        // cancellation nothing instead of up to a whole polling interval.
        statisticsPacing.withLock { statisticsPacing.broadcast() }
        let running = lifecycle.withLock { threads }
        for started in running where started.thread !== Thread.current {
            started.finished.wait()
            started.finished.signal()
        }
    }

    // MARK: - The connect thread

    /// The connect thread's whole body: session, poller, catalog, subscriptions, declaration, pumps.
    private func connect(_ sink: any FrameSink) throws {
        guard let connected = register(session: try relay.connect(url)) else { return }
        // Before the catalog is read, because a session that never publishes one is exactly the
        // session whose round trip time and byte counters say what went wrong. Unconditional: the
        // cost is a thread per MoQ subscription, paid only by a consumer who opened one (the Kotlin
        // reason, written out at that `start`).
        start("moq-stats", sink) { [self] in poll(connected) }
        let declared = try MoqCatalogTracks.declaredTracks(of: try connected.catalog())
        var pumps: [TrackPump] = []
        for (index, track) in declared.enumerated() {
            guard
                let stream = register(
                    stream: try connected.subscribe(
                        trackName: track.trackName, container: track.container))
            else { return }
            pumps.append(TrackPump(index: index, stream: stream))
        }
        // Declared before any pump exists: obligation 2 by construction. The order is
        // `MoqCatalogTracks`' and a pump stamps its position in that same list.
        guard deliver(sink, { $0.onTracks(declared.map(\.track)) }) else { return }
        let running = Countdown(pumps.count)
        for pump in pumps {
            start("moq-track-\(pump.index)", sink) { [self] in
                try pump.run(self, sink)
                // The last track to end ends the subscription; a publisher that stops its audio and
                // keeps sending video has not ended the broadcast, and SuperPlayer bounds a silent
                // track itself, in media time against the leading one.
                if running.decrement() { _ = deliver(sink, terminal: true) { $0.onEnded() } }
            }
        }
    }

    /// The statistics thread: ask, keep, wait, repeat until the subscription ends. **A failed poll
    /// ends the polling and never the subscription** — the likeliest failure is a cancellation
    /// closing the session under the poll, and measurement is not a reason to stop a viewer's stream
    /// — so a poll that stopped is legible as a reading that stopped advancing. Asked before the
    /// first wait, so a reading exists one interval sooner.
    private func poll(_ session: any MoqBroadcastSession) {
        while !isCancelled {
            guard let reading = try? session.statistics() else { return }
            statisticsPacing.withLock {
                latestStatistics = reading
                guard !isCancelled else { return }
                _ = statisticsPacing.wait(
                    until: Date(timeIntervalSinceNow: Double(statisticsIntervalMs) / 1_000))
            }
        }
    }

    /// One track's pull loop, and the keyframe gate in front of it (obligation 3).
    private final class TrackPump: Sendable {
        let index: Int
        let stream: any MoqTrackStream

        init(index: Int, stream: any MoqTrackStream) {
            self.index = index
            self.stream = stream
        }

        func run(_ source: MoqFrameSource, _ sink: any FrameSink) throws {
            var started = false
            while !source.isCancelled {
                guard let frame = try stream.next() else { return }
                // A subscription may begin part-way through a group, so the frames before its first
                // keyframe reference a picture it never received; a decoder fed them renders
                // corruption and reports nothing, which is obligation 3's cost.
                if !started && !frame.keyframe { continue }
                started = true
                let encoded = EncodedFrame(
                    // The bindings carry an unsigned 64-bit microsecond count and the seam a signed
                    // one; exact for every value a broadcast can reach (2^63 µs is some 292,000
                    // years), so `truncatingIfNeeded` changes nothing that can arise.
                    timestampUs: Int64(truncatingIfNeeded: frame.timestampUs),
                    // Handed on as received: the bindings answer a fresh `Data` per frame, which is
                    // obligations 6 and 8 both.
                    payload: frame.payload, keyFrame: frame.keyframe, trackIndex: index)
                guard source.deliver(sink, { $0.onFrame(encoded) }) else { return }
            }
        }
    }

    // MARK: - Threads, registration and delivery

    /// A thread this source started, and the semaphore its body signals as it exits, which is what
    /// ``cancel()`` joins on.
    private struct Started: @unchecked Sendable {
        let thread: Thread
        let finished: DispatchSemaphore
    }

    private var isCancelled: Bool { lifecycle.withLock { cancelled } }

    /// Runs `body` on a thread of this source's own, turning anything it throws into the one terminal
    /// failure the sink is told about — unless the subscription was cancelled, since a cancellation is
    /// what most transports report as a failure on the way down and showing it would show a viewer an
    /// error for a screen they had already left.
    ///
    /// Registered **and started** under the one lock, so ``cancel()`` cannot read the list, join a
    /// thread that had not yet started and return while its body was still to run.
    private func start(_ name: String, _ sink: any FrameSink, _ body: @escaping @Sendable () throws -> Void)
    {
        let finished = DispatchSemaphore(value: 0)
        let thread = Thread { [self] in
            defer { finished.signal() }
            do {
                try body()
            } catch {
                if !isCancelled { _ = deliver(sink, terminal: true) { $0.onError(error) } }
            }
        }
        thread.name = "\(name)-\(url.host ?? "")"
        lifecycle.withLock {
            guard !cancelled else { return }
            threads.append(Started(thread: thread, finished: finished))
            thread.start()
        }
    }

    /// Keeps the session so ``cancel()`` can close it, or closes it at once and answers nil where a
    /// cancellation has already run — the window every connecting transport has.
    private func register(session opened: any MoqBroadcastSession) -> (any MoqBroadcastSession)? {
        let kept = lifecycle.withLock {
            guard !cancelled else { return false }
            session = opened
            return true
        }
        if !kept { opened.close() }
        return kept ? opened : nil
    }

    /// The same for one track's subscription, the other thing a cancel must close.
    private func register(stream opened: any MoqTrackStream) -> (any MoqTrackStream)? {
        let kept = lifecycle.withLock {
            guard !cancelled else { return false }
            streams.append(opened)
            return true
        }
        if !kept { opened.close() }
        return kept ? opened : nil
    }

    /// Makes one callback on `sink` under the lock obligation 1 needs, and answers whether the
    /// subscription is still running; false is the caller's signal to stop. `terminal` is set
    /// *before* the call, so a sink re-entering from inside `onEnded()` finds the subscription over.
    fileprivate func deliver(
        _ sink: any FrameSink, terminal: Bool = false, _ callback: (any FrameSink) -> Void
    ) -> Bool {
        delivery.withLock {
            guard !isCancelled, !terminated else { return false }
            terminated = terminal
            callback(sink)
            return true
        }
    }
}

/// How many pumps are still running; the one that takes it to zero ends the subscription.
private final class Countdown: @unchecked Sendable {
    private let lock = NSLock()
    private var remaining: Int

    init(_ count: Int) { remaining = count }

    /// Takes one away and answers whether that was the last.
    func decrement() -> Bool {
        lock.withLock {
            remaining -= 1
            return remaining == 0
        }
    }
}
