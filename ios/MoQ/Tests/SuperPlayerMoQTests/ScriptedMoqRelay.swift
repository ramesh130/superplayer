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

@testable import SuperPlayerMoQ

/// A ``MoqRelay`` that publishes a script, so ``MoqFrameSource`` can be driven with no relay, no
/// QUIC and no network — the Kotlin `ScriptedMoqRelay`.
///
/// **What running against this proves, and what it does not**: that this product's bridge honours
/// `FrameSource`'s obligations, and **nothing about MoQ's bindings or a relay** — no line of
/// ``UniffiMoqRelay`` runs here. It is hostile in the ways that matter: a track is **endless by
/// default**, because a fake that finishes before anything cancels it satisfies obligation 9 by
/// arithmetic; delivery is paced, because a publisher is and the conformance suite keeps every frame
/// it is handed; and each track's frames carry a non-zero transport epoch, so a missing subtraction
/// above is visible.
///
/// `@unchecked Sendable`: every `var` is read and written under `lock` (or `polled`), and the rest is
/// immutable.
final class ScriptedMoqRelay: MoqRelay, @unchecked Sendable {

    /// Where a scripted relay fails, one point per thing that can go wrong on the way to a frame.
    enum FailurePoint: CaseIterable, Sendable { case connect, catalog, subscribe, frame }

    /// A track that keeps publishing, which is what a live broadcast does.
    static let endless = -1

    /// How long a scripted frame takes to arrive: two milliseconds, the Kotlin pace and reason —
    /// under one video frame's own duration, so the sixteen frames a conformance check waits for
    /// cost well under a tenth of a second, and paced at all so an endless track does not fill the
    /// suite's recording sink at host speed.
    static let frameInterval: TimeInterval = 0.002

    /// Far enough in that a failing relay has declared its tracks and delivered before it fails.
    static let failingFrame = 3

    /// What a scripted failure says, so a test can assert the cause reached the sink intact.
    static let failureMessage = "the scripted relay was told to fail here"

    private let catalog: MoqCatalog
    private let tracks: [String: ScriptedMoqTrack]
    private let defaultTrack: ScriptedMoqTrack
    private let failsAt: FailurePoint?

    /// What a session answers on its nth poll — a function of the count, so a reading that *moves*
    /// can be asserted as movement.
    private let statisticsScript: @Sendable (Int) throws -> MoqSessionStatistics

    private let lock = NSLock()
    private var _sessionsOpened = 0
    private var _sessionsClosed = 0
    private var _streamsOpened = 0
    private var _streamsClosed = 0
    private var _subscribed: [String] = []
    private let polled = NSCondition()
    private var _statisticsPolls = 0

    init(
        catalog: MoqCatalog,
        tracks: [String: ScriptedMoqTrack] = [:],
        defaultTrack: ScriptedMoqTrack = ScriptedMoqTrack(),
        failsAt: FailurePoint? = nil,
        statisticsScript: @escaping @Sendable (Int) throws -> MoqSessionStatistics =
            scriptedStatistics
    ) {
        self.catalog = catalog
        self.tracks = tracks
        self.defaultTrack = defaultTrack
        self.failsAt = failsAt
        self.statisticsScript = statisticsScript
    }

    var sessionsOpened: Int { lock.withLock { _sessionsOpened } }
    var sessionsClosed: Int { lock.withLock { _sessionsClosed } }
    var streamsOpened: Int { lock.withLock { _streamsOpened } }
    var streamsClosed: Int { lock.withLock { _streamsClosed } }

    /// The track names this relay was asked to subscribe to, in the order asked.
    var subscribed: [String] { lock.withLock { _subscribed } }

    /// How many times a session of this relay has been asked for its statistics.
    var statisticsPolls: Int { polled.withLock { _statisticsPolls } }

    /// Waits until this relay's session has been polled `count` times, and answers whether it was —
    /// an **event** rather than a window of wall-clock time, with `bound` a ceiling and never a pace.
    func awaitPolls(_ count: Int, within bound: TimeInterval) -> Bool {
        let deadline = Date(timeIntervalSinceNow: bound)
        return polled.withLock {
            while _statisticsPolls < count {
                if !polled.wait(until: deadline) { return _statisticsPolls >= count }
            }
            return true
        }
    }

    func connect(_ url: URL) throws -> any MoqBroadcastSession {
        if failsAt == .connect { throw ScriptedFailure() }
        lock.withLock { _sessionsOpened += 1 }
        return ScriptedSession(relay: self)
    }

    fileprivate func openStream(_ trackName: String) throws -> any MoqTrackStream {
        if failsAt == .subscribe { throw ScriptedFailure() }
        lock.withLock {
            _subscribed.append(trackName)
            _streamsOpened += 1
        }
        return ScriptedStream(
            relay: self, script: tracks[trackName] ?? defaultTrack,
            failsOnAFrame: failsAt == .frame)
    }

    fileprivate func catalogOrFail() throws -> MoqCatalog {
        if failsAt == .catalog { throw ScriptedFailure() }
        return catalog
    }

    fileprivate func poll() throws -> MoqSessionStatistics {
        // Counted and announced *before* the script runs, so a script that throws is still a poll a
        // waiting test sees.
        let ordinal = polled.withLock {
            defer {
                _statisticsPolls += 1
                polled.broadcast()
            }
            return _statisticsPolls
        }
        return try statisticsScript(ordinal)
    }

    fileprivate func sessionClosed() { lock.withLock { _sessionsClosed += 1 } }
    fileprivate func streamClosed() { lock.withLock { _streamsClosed += 1 } }
}

/// What a scripted failure throws: its message is ``ScriptedMoqRelay/failureMessage``.
struct ScriptedFailure: Error, CustomStringConvertible {
    var description: String { ScriptedMoqRelay.failureMessage }
}

private final class ScriptedSession: MoqBroadcastSession, @unchecked Sendable {
    private let relay: ScriptedMoqRelay
    private let lock = NSLock()
    private var closed = false

    init(relay: ScriptedMoqRelay) { self.relay = relay }

    func catalog() throws -> MoqCatalog { try relay.catalogOrFail() }

    func subscribe(trackName: String, container: MoqContainer) throws -> any MoqTrackStream {
        try relay.openStream(trackName)
    }

    func statistics() throws -> MoqSessionStatistics { try relay.poll() }

    func close() {
        let first = lock.withLock {
            defer { closed = true }
            return !closed
        }
        if first { relay.sessionClosed() }
    }
}

private final class ScriptedStream: MoqTrackStream, @unchecked Sendable {
    private let relay: ScriptedMoqRelay
    private let script: ScriptedMoqTrack
    private let failsOnAFrame: Bool

    /// Paced on a wait rather than a sleep, so ``close()`` wakes a reader at once: that is the
    /// obligation ``MoqTrackStream/close()`` carries, and a fake that did not honour it would make
    /// the source's join look like a hang rather than a defect.
    private let paced = NSCondition()
    private var closed = false
    private var delivered = 0

    init(relay: ScriptedMoqRelay, script: ScriptedMoqTrack, failsOnAFrame: Bool) {
        self.relay = relay
        self.script = script
        self.failsOnAFrame = failsOnAFrame
    }

    func next() throws -> MoqMediaFrame? {
        let ordinal: Int? = try paced.withLock {
            if !closed {
                _ = paced.wait(until: Date(timeIntervalSinceNow: ScriptedMoqRelay.frameInterval))
            }
            if closed { return nil }
            if failsOnAFrame && delivered == ScriptedMoqRelay.failingFrame {
                throw ScriptedFailure()
            }
            if script.frames != ScriptedMoqRelay.endless && delivered >= script.frames {
                return nil
            }
            defer { delivered += 1 }
            return delivered
        }
        guard let ordinal else { return nil }
        // One group: the frames before `leadingNonKeyFrames` are the dependent ones a subscription
        // joining part-way through a group receives, that index is its keyframe, and everything
        // after it depends on that keyframe.
        let keyFrame = ordinal == script.leadingNonKeyFrames
        return MoqMediaFrame(
            payload: script.payload(keyFrame),
            timestampUs: UInt64(script.epochUs + Int64(ordinal) * script.frameDurationUs),
            keyframe: keyFrame)
    }

    func close() {
        let first = paced.withLock {
            defer {
                closed = true
                paced.broadcast()
            }
            return !closed
        }
        if first { relay.streamClosed() }
    }
}

/// One track's script: what it sends, how much of it, and on what timeline — the Kotlin
/// `ScriptedMoqTrack`.
struct ScriptedMoqTrack: Sendable {

    /// One frame's bytes, given whether it is a keyframe: Annex-B for a self-describing track,
    /// length-prefixed for one that declared a record.
    var payload: @Sendable (Bool) -> Data = annexBH264

    /// How many frames, or ``ScriptedMoqRelay/endless``.
    var frames: Int = ScriptedMoqRelay.endless

    /// How many dependent frames arrive before the first keyframe.
    var leadingNonKeyFrames: Int = 0

    /// The step between this track's frames.
    var frameDurationUs: Int64 = videoFrameDurationUs

    /// This track's first frame's transport timestamp — non-zero on purpose.
    var epochUs: Int64 = transportEpochUs
}

/// 30 fps.
let videoFrameDurationUs: Int64 = 33_333

/// One AAC-LC access unit: 1024 samples at 44.1 kHz.
///
/// // spec: ISO/IEC 14496-3 §4.5.2.1 — an AAC-LC frame carries 1024 samples. Deliberately not the
/// // video step, so two tracks' frames do not land on the same instants.
let audioFrameDurationUs: Int64 = 23_220

/// Arbitrary and non-zero, which is the whole point of it.
let transportEpochUs: Int64 = 987_654_321

/// One NAL unit of the right shape and no picture data.
///
/// // spec: ITU-T H.264 §7.3.1 — `nal_unit_type` is the low five bits of the header's first byte:
/// // 5 is an IDR slice, 1 a non-IDR one.
private func h264NalUnit(_ keyFrame: Bool) -> Data {
    Data([keyFrame ? 0x65 : 0x41]) + Data(count: 64)
}

/// One frame of a self-describing track: the unit behind an Annex-B start code. The Kotlin builders,
/// written again for the Kotlin reason — the realtime product's own fake is a test fixture no other
/// target can see — and held honest by the same citation.
@Sendable func annexBH264(_ keyFrame: Bool) -> Data {
    Data([0, 0, 0, 1]) + h264NalUnit(keyFrame)
}

/// One frame of an `avc1` track: the unit behind the four-byte length field #340's `avcC` declares.
///
/// // spec: ISO/IEC 14496-15 §5.3.3.1.2 — a sample whose entry carries an `avcC` is a run of NAL
/// // units each prefixed by its length, `lengthSizeMinusOne + 1` bytes wide.
@Sendable func lengthPrefixedH264(_ keyFrame: Bool) -> Data {
    let unit = h264NalUnit(keyFrame)
    return Data([0, 0, 0, UInt8(unit.count)]) + unit
}

/// One frame of audio: bytes of the right size and no meaning, since nothing here decodes.
@Sendable func aacFrame(_ keyFrame: Bool) -> Data { Data(count: 96) }

/// What a scripted session says on its `poll`th poll: every number non-zero and distinct, and the
/// cumulative counters advancing with the count, as a session's own do.
@Sendable func scriptedStatistics(_ poll: Int) -> MoqSessionStatistics {
    let n = Int64(poll + 1)
    return MoqSessionStatistics(
        sampledAtMs: monotonicNowMs(),
        roundTripTimeUs: 37_000,
        sendRateBps: 128_000,
        receiveRateBps: 4_800_000,
        bytesSent: 1_024 * n,
        bytesReceived: 600_000 * n,
        packetsSent: 9 * n,
        packetsReceived: 420 * n,
        transportBytesLost: 1_500 * n,
        transportPacketsLost: n)
}

/// A session against a relay too old to estimate its receive rate — the one statistic optional in
/// practice, scripted to catch a mapping that turned an absent field into a zero.
@Sendable func statisticsMissingTheReceiveRate(_ poll: Int) -> MoqSessionStatistics {
    let reading = scriptedStatistics(poll)
    return MoqSessionStatistics(
        sampledAtMs: reading.sampledAtMs, roundTripTimeUs: reading.roundTripTimeUs,
        sendRateBps: reading.sendRateBps, receiveRateBps: nil, bytesSent: reading.bytesSent,
        bytesReceived: reading.bytesReceived, packetsSent: reading.packetsSent,
        packetsReceived: reading.packetsReceived,
        transportBytesLost: reading.transportBytesLost,
        transportPacketsLost: reading.transportPacketsLost)
}

/// A session whose statistics call fails, which must cost the subscription nothing.
@Sendable func statisticsThatFail(_ poll: Int) throws -> MoqSessionStatistics {
    throw ScriptedFailure()
}
