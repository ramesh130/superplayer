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

import SuperPlayerCore

/// What one session was, as the numbers `PRD.md` §6 reports — computed from a ``TelemetryEvent``
/// stream and from nothing else. The Kotlin `SessionMetrics`, definition for definition.
///
/// **This is the one implementation of the metric definitions, and every arm of the benchmark goes
/// through it** — the iOS matrix's (#414) as Android's. It cannot tell which collector produced the
/// events it is given, so the rebuffer denominator, the seek exclusion and the bitrate weighting are
/// shared by construction rather than by care; the benchmark's `StockTelemetry` and ``QoeCollector``
/// are the two *producers* of the vocabulary, and the benchmark's agreement test holds them to each
/// other.
///
/// **The definitions are `docs/telemetry-schema.md`, not this file**, and they are the same
/// definitions on both platforms: the iOS collector derives each event differently (that
/// document's *How derived on iOS* column) and reduces it identically. Every metric cites the
/// section it implements, and where a choice the document leaves open had to be made — a benchmark
/// needs a number and a schema describes an event stream — the Kotlin reason is carried with it.
///
/// **Why a `SuperPlayerTelemetry` type**, for the Kotlin reason: it was the benchmark's, and a QoE
/// regression gate in the package's own tests needs the same reduction, so it lives beside the
/// collector whose events it reads. ``QoeScore`` says the same for the objective.
public struct SessionMetrics: Sendable, Hashable {

    /// The session these numbers are of.
    public let sessionId: String

    /// Milliseconds from the start boundary to the first frame, or nil when no frame ever came — a
    /// startup failure, or an exit before video start.
    ///
    /// ref: `docs/telemetry-schema.md`, *Time to first frame*. Taken from the event rather than
    /// recomputed: the boundary is settled when the session opens, and the collector is the only
    /// thing that knows which one applied.
    public let timeToFirstFrameMs: Int64?

    /// Which boundary ``timeToFirstFrameMs`` was measured from, or nil when there is no measurement.
    /// Carried so a report can refuse to mix the two, which the schema says must never be
    /// aggregated together.
    public let startBoundary: TtffStartBoundary?

    /// Milliseconds of involuntary stall, **excluding seek-induced stalls**.
    ///
    /// ref: `docs/telemetry-schema.md`, *Rebuffering*. The numerator of ``rebufferRatio``.
    public let rebufferMs: Int64

    /// How many such stalls there were — `PRD.md` §6 reports the count beside the ratio, because one
    /// long stall and six short ones are different experiences with the same ratio.
    public let rebufferCount: Int

    /// Milliseconds during which the playback position was advancing: the span from the first frame
    /// to the session's end, less **every** stall in it, seek-induced ones included, since the
    /// position advances during neither.
    ///
    /// ref: `docs/telemetry-schema.md`, *Rebuffering*. *The one precondition this derivation has*, as
    /// on Android: the session was never paused and never ran past the end of its content, since both
    /// stop the position without a stall. The benchmark's runner does neither.
    public let playingMs: Int64

    /// The declared peak bitrate the session played, weighted by the time it played it, from the
    /// playing `PlaybackStateSampled` events that carry one, each weighted by its own
    /// `samplingIntervalMs`; nil when there was none.
    ///
    /// ref: `docs/telemetry-schema.md`, *Bitrate*. Sampled, therefore granular: a rendition held for
    /// less than one interval can fall between samples, identically for every arm.
    public let averageBitrateBps: Double?

    /// How many times the video rendition changed after the initial choice. `initial` is not a
    /// switch: there was nothing to switch from.
    ///
    /// ref: `docs/telemetry-schema.md`, *Bitrate*.
    public let switchCount: Int

    /// Of ``switchCount``, how many went up — `PRD.md` §3.4 asks for the directions separately.
    public let upshiftCount: Int

    /// Of ``switchCount``, how many went down.
    public let downshiftCount: Int

    /// The total size of the rendition changes, in bits per second summed over every switch: how
    /// far quality moved, which the QoE objective penalises rather than how often (``QoeScore``).
    /// `initial` contributes nothing, having no `fromBitrateBps`.
    public let switchMagnitudeBpsSum: Int64

    /// Whether playback failed before the first frame.
    ///
    /// ref: `docs/telemetry-schema.md`, *Video start failure and mid-stream failure*.
    public let startupFailed: Bool

    /// Whether playback failed after the first frame.
    public let midStreamFailed: Bool

    /// Whether the session ended having produced neither a frame nor a startup failure.
    ///
    /// ref: `docs/telemetry-schema.md`, *Exit before video start* — derived, not reported. A
    /// benchmark never abandons a session, so a cell reporting any is reporting a runner defect.
    public let exitBeforeVideoStart: Bool

    /// How many of this session's events the delivery path discarded under pressure.
    ///
    /// ref: `docs/telemetry-schema.md`, *The stream is lossy, and it says so* — a session with a
    /// non-zero count is excluded from aggregates rather than averaged in; ``usable`` is that rule.
    public let droppedEventCount: Int

    /// How many times the decision in force changed during the session.
    ///
    /// ref: `docs/telemetry-schema.md`, *Decision changes*. Zero for a static profile, which is
    /// consulted once; counted so an adaptive arm is compared on how often it moved as well as on
    /// where it landed.
    public let decisionChangeCount: Int

    /// Whether the session produced a `SessionEnded` at all. One that did not has no end, so no
    /// playing span and no ratio, and ``usable`` excludes it.
    public let ended: Bool

    /// Every field, for a test or a report that has its numbers some other way.
    public init(
        sessionId: String, timeToFirstFrameMs: Int64?, startBoundary: TtffStartBoundary?,
        rebufferMs: Int64, rebufferCount: Int, playingMs: Int64, averageBitrateBps: Double?,
        switchCount: Int, upshiftCount: Int, downshiftCount: Int, switchMagnitudeBpsSum: Int64,
        startupFailed: Bool, midStreamFailed: Bool, exitBeforeVideoStart: Bool,
        droppedEventCount: Int, decisionChangeCount: Int, ended: Bool
    ) {
        self.sessionId = sessionId
        self.timeToFirstFrameMs = timeToFirstFrameMs
        self.startBoundary = startBoundary
        self.rebufferMs = rebufferMs
        self.rebufferCount = rebufferCount
        self.playingMs = playingMs
        self.averageBitrateBps = averageBitrateBps
        self.switchCount = switchCount
        self.upshiftCount = upshiftCount
        self.downshiftCount = downshiftCount
        self.switchMagnitudeBpsSum = switchMagnitudeBpsSum
        self.startupFailed = startupFailed
        self.midStreamFailed = midStreamFailed
        self.exitBeforeVideoStart = exitBeforeVideoStart
        self.droppedEventCount = droppedEventCount
        self.decisionChangeCount = decisionChangeCount
        self.ended = ended
    }

    /// The rebuffer ratio, or nil for a session that never started playing.
    ///
    /// ref: `docs/telemetry-schema.md`, *Rebuffering*:
    /// `rebufferRatio = totalRebufferMs / (totalRebufferMs + totalPlayingMs)`. **Per session, and not
    /// the thing to average across sessions** — a cell's ratio is the sum of numerators over the sum
    /// of denominators, which the benchmark's `CellResult` computes.
    public var rebufferRatio: Double? {
        let denominator = rebufferMs + playingMs
        return denominator <= 0 ? nil : Double(rebufferMs) / Double(denominator)
    }

    /// Whether this session may be aggregated at all: its stream is complete and it ended. The
    /// schema's rule, not a benchmark's judgement about outliers.
    public var usable: Bool { droppedEventCount == 0 && ended }

    /// Reduces one session's events to its metrics.
    ///
    /// `events` is every event of one session, in the order the sink received them. Events of other
    /// sessions are ignored rather than rejected, so one list a process of players wrote to can be
    /// passed whole.
    public static func from(sessionId: String, events: [TelemetryEvent]) -> SessionMetrics {
        let own = events.filter { $0.sessionId == sessionId }
        let firstFrame = own.lazy.compactMap(\.firstFrameRenderedPayload).first
        let ended = own.compactMap(\.sessionEndedPayload).last
        let switches = own.compactMap(\.trackSwitchedPayload)
        let stalls = own.compactMap(\.rebufferEndedPayload)
        let startupFailed = own.contains { if case .startupFailed = $0 { true } else { false } }

        // Only the stalls the schema counts. The seek-induced ones are excluded here and subtracted
        // from the playing span below — the two halves of one rule: a viewer who drags a scrub bar
        // expects a wait, and it is neither a rebuffer nor time the position was advancing.
        let counted = stalls.filter { !$0.seekInduced }

        return SessionMetrics(
            sessionId: sessionId,
            timeToFirstFrameMs: firstFrame?.timeToFirstFrameMs,
            startBoundary: firstFrame?.startBoundary,
            rebufferMs: counted.reduce(0) { $0 + $1.durationMs },
            rebufferCount: counted.count,
            playingMs: playingMs(firstFrame: firstFrame, ended: ended, stalls: stalls),
            averageBitrateBps: timeWeightedBitrateBps(own),
            switchCount: switches.filter { $0.direction != .initial }.count,
            upshiftCount: switches.filter { $0.direction == .up }.count,
            downshiftCount: switches.filter { $0.direction == .down }.count,
            switchMagnitudeBpsSum: switches.reduce(0) { sum, change in
                guard let from = change.fromBitrateBps else { return sum }
                return sum + abs(Int64(change.toBitrateBps) - Int64(from))
            },
            startupFailed: startupFailed,
            midStreamFailed: own.contains {
                if case .midStreamFailed = $0 { true } else { false }
            },
            exitBeforeVideoStart: ended != nil && firstFrame == nil && !startupFailed,
            droppedEventCount: ended?.droppedEventCount ?? 0,
            decisionChangeCount: own.filter {
                if case .decisionChanged = $0 { true } else { false }
            }.count,
            ended: ended != nil)
    }

    /// See ``playingMs``, which is where this derivation is argued.
    private static func playingMs(
        firstFrame: TelemetryEvent.FirstFrameRendered?, ended: TelemetryEvent.SessionEnded?,
        stalls: [TelemetryEvent.RebufferEnded]
    ) -> Int64 {
        guard let firstFrame, let ended else { return 0 }
        let span = ended.monotonicTimeMs - firstFrame.monotonicTimeMs
        // Every stall, seek-induced included: none of it is time the position advanced.
        return max(0, span - stalls.reduce(0) { $0 + $1.durationMs })
    }

    /// See ``averageBitrateBps``, which is where this derivation is argued.
    private static func timeWeightedBitrateBps(_ own: [TelemetryEvent]) -> Double? {
        var weightedSum = 0.0
        var weight: Int64 = 0
        for case .playbackStateSampled(let sample) in own {
            guard let bitrate = sample.videoBitrateBps, sample.playing else { continue }
            weightedSum += Double(bitrate) * Double(sample.samplingIntervalMs)
            weight += sample.samplingIntervalMs
        }
        return weight == 0 ? nil : weightedSum / Double(weight)
    }
}

extension TelemetryEvent {
    fileprivate var firstFrameRenderedPayload: FirstFrameRendered? {
        if case .firstFrameRendered(let event) = self { event } else { nil }
    }
    fileprivate var sessionEndedPayload: SessionEnded? {
        if case .sessionEnded(let event) = self { event } else { nil }
    }
    fileprivate var trackSwitchedPayload: TrackSwitched? {
        if case .trackSwitched(let event) = self { event } else { nil }
    }
    fileprivate var rebufferEndedPayload: RebufferEnded? {
        if case .rebufferEnded(let event) = self { event } else { nil }
    }
}
