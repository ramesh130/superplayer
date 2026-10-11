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

/// What one playback session did, as a line-oriented value that outlives the player: the ordered
/// facts a ``SessionTraceRecorder`` observed, one per line, in a stable order and a fixed grammar —
/// the Kotlin `SessionTrace`, and **the same grammar** rather than a second one (ADR-0020 rule 4:
/// the platforms share the documents and the formats, never the code).
///
/// The purpose is to be diffed, and every choice serves that, as on Android:
///
/// - **One fact per line**, `+<ms> <kind> <fields>`: milliseconds since the recorder attached, what
///   kind of fact it is, then that kind's fields in a fixed order. No line depends on another.
/// - **Stably ordered**, by time, then by kind in a fixed rank, then by the fact's own key, and
///   only then by arrival.
/// - **Nothing that varies between runs** is in a line but the millisecond column and the readings
///   of time: no wall-clock time, no session id, no byte count.
/// - **Redacted by construction** — ``SessionTraceRecorder`` lists the seven rules.
///
/// The kinds, in rank order, are `item`, `state`, `playing`, `tracks`, `discontinuity`, `load`,
/// `bandwidth`, `error` and `telemetry`. The iOS recorder writes seven of them; the two it does not,
/// and why, are in its doc comment. A reader of either platform's trace reads both.
///
/// ## The one difference: what a comparison may hold equal
///
/// The Kotlin harness drives the engine on a fake clock, so a trace is byte-identical run after run
/// and a golden compares it whole. `AVPlayer` runs on real time (ADR-0021 rule 4), so a golden here
/// compares ``structural()`` — the same lines with every reading of time masked and the order
/// within each kind kept. That is a *reading* of this grammar, not a change to it, which is why
/// ``formatVersion`` is the Kotlin one and did not move.
public struct SessionTrace: Sendable, Hashable, CustomStringConvertible {

    /// The meaning of the lines — the Kotlin `SessionTrace.FORMAT_VERSION`, shared rather than
    /// restated: it bumps when a line's meaning changes, not when a kind is added, on either
    /// platform, and both platforms bump it together.
    public static let formatVersion = 1

    /// The first line of every formatted trace.
    public static let formatHeader = "superplayer-session-trace v\(formatVersion)"

    static let timingsRelative = "timings relative-ms"
    static let timingsOmitted = "timings omitted"

    /// Every fact, one per element, in trace order, without the header.
    public let lines: [String]

    /// Whether each line carries the `+<ms>` column.
    public let timed: Bool

    init(lines: [String], timed: Bool) {
        self.lines = lines
        self.timed = timed
    }

    /// The trace as text: two header lines, then ``lines``, each newline-terminated.
    public func format() -> String {
        ([Self.formatHeader, timed ? Self.timingsRelative : Self.timingsOmitted] + lines)
            .map { $0 + "\n" }.joined()
    }

    public var description: String { format() }

    /// This trace with the millisecond column removed and the order kept — the Kotlin
    /// `withoutTimings()`.
    public func withoutTimings() -> SessionTrace {
        guard timed else { return self }
        return SessionTrace(lines: lines.map(Self.droppingTime), timed: false)
    }

    /// The reading a golden compares on a real-time engine (ADR-0021 rule 4 and its #411 addendum):
    /// the millisecond column removed, every reading of time masked, and the lines regrouped by
    /// kind in rank order, each kind's lines in the order the trace has them.
    ///
    /// **Masked** is the value of every field whose name ends in `Ms` — a time or a duration, each a
    /// reading of the host's clock here — and of ``measuredRateFields``, which are bytes over such a
    /// duration. The field and its name stay, so a field that appeared or vanished is still a diff.
    ///
    /// **Dropped** are the periodic samples, ``periodicTelemetry``: how many a session has is its
    /// length over the sampling cadence, which on real time is a reading of the host's clock like
    /// any other — a start-up a second slower is a sample more — so their count cannot be held
    /// equal, and a line kept for some runs and not others is the flake rule 4 forbids.
    ///
    /// **Regrouped** because AVFoundation reports what a trace records through independent paths —
    /// key-value observation, notifications, the metric stream, the telemetry queue — and which of
    /// two of them delivers first within a few milliseconds is the host's scheduling, not the
    /// engine's behaviour. Within a kind the order is causal: a load completes after it starts, and
    /// `ENDED` follows `READY`. So a structural diff shows a kind that appeared or vanished, a line
    /// that appeared, vanished or moved within its kind, and a field that changed or was added; it
    /// cannot show a fact that moved relative to a fact of another kind, nor anything that only got
    /// slower — `docs/testing.md`'s *Golden traces on iOS* says the same.
    public func structural() -> SessionTrace {
        let untimed = withoutTimings().lines.filter { !Self.isPeriodicSample($0) }
            .map(Self.maskingTime)
        var byKind: [String: [String]] = [:]
        for line in untimed {
            byKind[Self.kind(ofUntimed: line), default: []].append(line)
        }
        let known = TraceKind.allCases.map(\.token)
        // A kind this version does not know — a later one's — keeps its place after the known ones,
        // in the order first seen, rather than being dropped from a comparison.
        let unknown = untimed.map(Self.kind(ofUntimed:)).filter { !known.contains($0) }
        var order = known
        for kind in unknown where !order.contains(kind) { order.append(kind) }
        return SessionTrace(lines: order.flatMap { byKind[$0] ?? [] }, timed: false)
    }

    /// The fields whose value is a rate measured on the host's clock, masked by ``structural()``
    /// beside every `…Ms` field: a `bandwidth` line's sample, and Apple's own smoothed estimate on a
    /// state sample.
    static let measuredRateFields: Set<String> = ["bitrateBps", "throughputEstimateBps"]

    /// The telemetry events emitted on the collector's sampling timer rather than on something the
    /// engine did — every event `docs/telemetry-schema.md` gives a `samplingIntervalMs` cadence.
    static let periodicTelemetry: Set<String> = [
        "PlaybackStateSampled", "LiveLatencySampled", "VideoFramesDropped",
    ]

    private static func isPeriodicSample(_ untimedLine: String) -> Bool {
        let columns = untimedLine.split(separator: " ", maxSplits: 2)
        return columns.count >= 2 && columns[0] == "telemetry"
            && periodicTelemetry.contains(String(columns[1]))
    }

    /// The inverse of ``format()`` — the Kotlin `SessionTrace.parse`, which is what lets one
    /// platform read the other's traces.
    public static func parse(_ text: String) throws(SessionTraceFormatError) -> SessionTrace {
        var lines = text.components(separatedBy: "\n")
        while lines.last == "" { lines.removeLast() }
        guard lines.first == formatHeader else {
            throw SessionTraceFormatError(
                "Not a session trace this version reads; expected '\(formatHeader)' first, was "
                    + "'\(lines.first ?? "")'")
        }
        let timed: Bool
        switch lines.dropFirst().first {
        case timingsRelative: timed = true
        case timingsOmitted: timed = false
        case let other:
            throw SessionTraceFormatError("Unknown timings declaration '\(other ?? "")'")
        }
        return SessionTrace(lines: Array(lines.dropFirst(2)), timed: timed)
    }

    private static func droppingTime(_ line: String) -> String {
        guard line.hasPrefix("+"), let space = line.firstIndex(of: " ") else { return line }
        return String(line[line.index(after: space)...])
    }

    private static func kind(ofUntimed line: String) -> String {
        String(line.prefix { $0 != " " })
    }

    private static func maskingTime(_ line: String) -> String {
        line.split(separator: " ", omittingEmptySubsequences: false).map { field in
            guard let equals = field.firstIndex(of: "=") else { return String(field) }
            let name = field[..<equals]
            guard name.hasSuffix("Ms") || measuredRateFields.contains(String(name)) else {
                return String(field)
            }
            return "\(name)=*"
        }.joined(separator: " ")
    }
}

/// Text that is not a session trace this version reads, from ``SessionTrace/parse(_:)``.
public struct SessionTraceFormatError: Error, Sendable, CustomStringConvertible {
    public let description: String

    init(_ description: String) { self.description = description }
}

/// The kinds of the grammar, in the order they rank within one millisecond — the Kotlin `Kind`,
/// with its order and its reason for `bandwidth` after `load`: a sample is a fact about the
/// transfer that just finished and reads as a continuation of the line above it.
enum TraceKind: Int, CaseIterable, Comparable, Sendable {
    case item
    case state
    case playing
    /// The grammar's, and not recorded on iOS: ``SessionTraceRecorder`` says why.
    case tracks
    /// The grammar's, and not recorded on iOS: ``SessionTraceRecorder`` says why.
    case discontinuity
    case load
    case bandwidth
    case error
    case telemetry

    /// The column printed, spelt out rather than read off the case name, so renaming a case cannot
    /// change the format.
    var token: String {
        switch self {
        case .item: "item"
        case .state: "state"
        case .playing: "playing"
        case .tracks: "tracks"
        case .discontinuity: "discontinuity"
        case .load: "load"
        case .bandwidth: "bandwidth"
        case .error: "error"
        case .telemetry: "telemetry"
        }
    }

    static func < (lhs: TraceKind, rhs: TraceKind) -> Bool { lhs.rawValue < rhs.rawValue }
}
