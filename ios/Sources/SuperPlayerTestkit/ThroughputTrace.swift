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

/// What kind of link a stretch of a ``ThroughputTrace`` was measured over — the port of
/// `superplayer-testkit`'s `NetworkTransport`, with the same four values spelled the same way in a
/// trace file.
///
/// Separate from the bandwidth for the Kotlin type's reason: a WiFi-to-cellular handover is a change
/// of network and not only of rate. **On iOS it is reported and not switched.** The Android harness
/// replays a stretch's transport into Robolectric's connectivity shadow, so the platform itself
/// reports the handover; nothing in the simulator can move the host's interface, and `AVPlayer`
/// reads the real one, so here the transport is what ``OriginResponse/transport`` says the trace was
/// on when a request arrived — a reading a test can assert on, and nothing a player can observe.
///
/// ref: Android `NetworkCapabilities.TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET` —
/// the values a trace file names, kept as Android's because the format is shared
/// (`docs/throughput-traces.md`).
public enum NetworkTransport: String, Sendable, Hashable, CaseIterable {
    case wifi = "WIFI"
    case cellular = "CELLULAR"
    case ethernet = "ETHERNET"

    /// The source did not say. What a converted trace carries when its dataset records no transport.
    case unknown = "UNKNOWN"
}

/// A time series of network conditions — bandwidth, round-trip time and transport — that an
/// origin's responses are paced against: the port of `superplayer-testkit`'s `ThroughputTrace`,
/// reading and writing `docs/throughput-traces.md`'s format unchanged, so one file means one network
/// on both platforms (ADR-0021 rule 3).
///
/// Piecewise constant: a list of stretches, each lasting some milliseconds at one bandwidth, one
/// round-trip time and one transport. What happens after the last stretch is part of the trace: a
/// recorded trace loops (ref: Netravali et al., "Mahimahi: Accurate Record-and-Replay for HTTP",
/// USENIX ATC 2015 — `mm-link` wraps to the start of its trace), and a handover holds.
///
/// Time zero is the moment the ``LoopbackOrigin`` serving under it started — on Android it is the
/// moment the harness built the player, which on iOS is a moment no harness sees, since a test builds
/// its `AVPlayer` itself; the origin starts just before, so the two differ by the time a test takes
/// to make a player.
public struct ThroughputTrace: Sendable, Hashable, CustomStringConvertible {

    /// The first line of every trace: the format's name and its version.
    public static let formatHeader = "superplayer-throughput-trace 1"

    /// 100 Gbit/s: far past any link a player meets — the Kotlin `MAX_BANDWIDTH_BPS`. Overflow is
    /// guarded separately, at construction.
    static let maximumBandwidthBps: Int64 = 100_000_000_000

    static let bitMillisPerByte: Int64 = 8 * 1_000
    private static let atEndDirective = "at-end"
    private static let atEndLoop = "loop"
    private static let atEndHold = "hold"

    let stretches: [Stretch]

    /// Whether the trace starts again after its last stretch, rather than holding that stretch.
    public let loops: Bool

    /// One pass through the trace, in milliseconds.
    public var durationMs: Int64 { stretches[stretches.count - 1].endMs }

    /// The bits one pass can deliver, times a thousand — the unit ``arrivalMs(startMs:bytes:)``
    /// counts in, checked at construction so a trace that builds cannot overflow its replay.
    private let passCapacity: Int64

    private init(stretches: [Stretch], loops: Bool, passCapacity: Int64) {
        self.stretches = stretches
        self.loops = loops
        self.passCapacity = passCapacity
    }

    /// The bandwidth available at `timeMs` into the trace, in bits per second.
    public func bandwidthBps(at timeMs: Int64) -> Int64 { stretch(at: timeMs).bandwidthBps }

    /// The round-trip time at `timeMs` into the trace — what a request opened then waits before its
    /// first byte.
    public func rttMs(at timeMs: Int64) -> Int64 { stretch(at: timeMs).rttMs }

    /// The transport the trace is on at `timeMs` into it — reported on iOS, never switched (see
    /// ``NetworkTransport``).
    public func transport(at timeMs: Int64) -> NetworkTransport { stretch(at: timeMs).transport }

    /// The trace in the text format ``parse(_:)`` reads, so a built trace can be written out and
    /// diffed — character for character what the Kotlin `format()` writes.
    public func format() -> String {
        var text = "\(Self.formatHeader)\n"
        text += "\(Self.atEndDirective) \(loops ? Self.atEndLoop : Self.atEndHold)\n"
        for stretch in stretches {
            text += "\(stretch.durationMs) \(stretch.bandwidthBps) \(stretch.transport.rawValue)"
            if stretch.rttMs != 0 { text += " \(stretch.rttMs)" }
            text += "\n"
        }
        return text
    }

    public var description: String {
        "ThroughputTrace(\(stretches.count) stretches, \(durationMs) ms, loops=\(loops))"
    }

    /// The earliest time into the trace by which `bytes` have arrived, for a transfer whose first
    /// byte could arrive at `startMs`.
    ///
    /// The Kotlin `arrivalMs`, line for line, and held to it by the shared fixtures: integer
    /// arithmetic in bits times milliseconds per second, rounded up only at the one division that
    /// finds the millisecond the last byte lands in, so the answer is the same on both platforms. A
    /// stretch at zero bandwidth carries nothing, which is what makes a dropout hold a transfer
    /// until the link returns rather than fail it.
    func arrivalMs(startMs: Int64, bytes: Int64) -> Int64 {
        precondition(
            startMs >= 0, "A transfer cannot start before the trace does, at \(startMs) ms")
        precondition(bytes >= 0, "A transfer cannot deliver \(bytes) bytes")
        var needed = bytes * Self.bitMillisPerByte
        var now = startMs
        while needed > 0 {
            if !loops && now >= durationMs {
                return now + Self.ceilDiv(needed, stretches[stretches.count - 1].bandwidthBps)
            }
            let stretch = stretch(at: now)
            let left = stretch.endMs - offsetInPass(now)
            let capacity = stretch.bandwidthBps * left
            if capacity >= needed { return now + Self.ceilDiv(needed, stretch.bandwidthBps) }
            needed -= capacity
            now += left
            // Whole passes at a time once aligned to the start of one, for the Kotlin reason: a
            // transfer that outlasts a short trace many times over costs a division, not a walk.
            if loops && offsetInPass(now) == 0 && needed > passCapacity {
                let passes = (needed - 1) / passCapacity
                needed -= passes * passCapacity
                now += passes * durationMs
            }
        }
        return now
    }

    private func offsetInPass(_ timeMs: Int64) -> Int64 {
        loops ? timeMs % durationMs : min(timeMs, durationMs - 1)
    }

    private func stretch(at timeMs: Int64) -> Stretch {
        precondition(timeMs >= 0, "The trace starts at 0 ms, not \(timeMs)")
        let offset = offsetInPass(timeMs)
        var low = 0
        var high = stretches.count - 1
        while low < high {
            let mid = (low + high + 1) / 2
            if stretches[mid].startMs <= offset { low = mid } else { high = mid - 1 }
        }
        return stretches[low]
    }

    static func ceilDiv(_ numerator: Int64, _ denominator: Int64) -> Int64 {
        (numerator + denominator - 1) / denominator
    }

    /// Collects a trace one stretch at a time, in playback order — the Kotlin `Builder`. A value:
    /// each call returns the builder with one more stretch, so a trace reads as one expression.
    public struct Builder: Sendable {

        private var stretches: [Stretch] = []
        private var loops = true

        public init() {}

        /// Appends `durationMs` at `bandwidthBps` over `transport`, with requests opened in it paying
        /// `rttMs` before their first byte. `transport` has no default for the Kotlin reason: the one
        /// thing a trace author must not forget is what kind of link they are describing.
        ///
        /// A value out of range is a test written wrongly and stops it at once, as the Kotlin
        /// `require` does; ``ThroughputTrace/parse(_:)`` refuses the same values from a file with a
        /// typed error naming the line instead.
        public func add(
            durationMs: Int64, bandwidthBps: Int64, transport: NetworkTransport, rttMs: Int64 = 0
        ) -> Builder {
            if let refusal = Stretch.refusal(durationMs, bandwidthBps, rttMs) {
                preconditionFailure(refusal)
            }
            var builder = self
            let startMs = stretches.last?.endMs ?? 0
            builder.stretches.append(
                Stretch(
                    startMs: startMs, durationMs: durationMs, bandwidthBps: bandwidthBps,
                    rttMs: rttMs, transport: transport))
            return builder
        }

        /// Holds the last stretch forever instead of starting the trace again.
        public func holdAtEnd() -> Builder {
            var builder = self
            builder.loops = false
            return builder
        }

        /// The trace, or a ``ThroughputTraceError`` saying why these stretches are not one: none at
        /// all, no bandwidth to deliver with, or a length the replay's arithmetic cannot hold.
        public func build() throws -> ThroughputTrace {
            guard let last = stretches.last else {
                throw ThroughputTraceError("A trace has at least one stretch")
            }
            // Both are the same promise — every byte eventually arrives — broken in the two ways
            // each ending can break it. A replay that could wait forever would hang a test rather
            // than fail it.
            if loops {
                guard stretches.contains(where: { $0.bandwidthBps > 0 }) else {
                    throw ThroughputTraceError(
                        "A looping trace with no bandwidth anywhere delivers nothing, ever")
                }
            } else if last.bandwidthBps == 0 {
                throw ThroughputTraceError(
                    "A trace that holds its last stretch needs bandwidth in it, or nothing after "
                        + "the end arrives")
            }
            var capacity: Int64 = 0
            for stretch in stretches {
                let (product, productOverflows) = stretch.bandwidthBps.multipliedReportingOverflow(
                    by: stretch.durationMs)
                let (sum, sumOverflows) = capacity.addingReportingOverflow(product)
                guard !productOverflows, !sumOverflows else {
                    throw ThroughputTraceError(
                        "A trace this long at these rates overflows the replay's arithmetic")
                }
                capacity = sum
            }
            return ThroughputTrace(stretches: stretches, loops: loops, passCapacity: capacity)
        }
    }

    /// Reads a trace in the format `docs/throughput-traces.md` specifies.
    ///
    /// Strict, for the Kotlin `parse`'s reason: a trace is a test input, so a malformed line is a
    /// ``ThroughputTraceError`` naming its line number, never a line skipped — a reader that
    /// tolerated one would replay a different network from the one the file describes. The messages
    /// are the Kotlin reader's.
    public static func parse(_ text: String) throws -> ThroughputTrace {
        // Kotlin's `lines()` splits on CR LF, LF and CR alike, and so does this.
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: false)
            .flatMap { $0.split(separator: "\r", omittingEmptySubsequences: false) }
            .map(String.init)
        guard lines.first.map(trimmed) == formatHeader else {
            throw ThroughputTraceError(
                "Line 1: a trace starts with \"\(formatHeader)\", not \"\(lines.first ?? "")\"")
        }
        var builder = Builder()
        var sawStretch = false
        var sawAtEnd = false
        for (index, raw) in lines.enumerated() {
            let line = trimmed(raw)
            if index == 0 || line.isEmpty || line.hasPrefix("#") { continue }
            let fields = line.split(whereSeparator: \.isWhitespace).map(String.init)
            func fail(_ why: String) -> ThroughputTraceError {
                ThroughputTraceError("Line \(index + 1): \(why): \"\(raw)\"")
            }
            if fields[0] == atEndDirective {
                if sawStretch || sawAtEnd {
                    throw fail("\"\(atEndDirective)\" appears once, before the first stretch")
                }
                sawAtEnd = true
                switch fields.count > 1 ? fields[1] : nil {
                case atEndLoop: break
                case atEndHold: builder = builder.holdAtEnd()
                default:
                    throw fail(
                        "\"\(atEndDirective)\" is followed by \"\(atEndLoop)\" or \"\(atEndHold)\"")
                }
                if fields.count != 2 { throw fail("\"\(atEndDirective)\" takes one value") }
                continue
            }
            guard (3...4).contains(fields.count) else {
                throw fail("a stretch is duration_ms bandwidth_bps transport [rtt_ms]")
            }
            guard let durationMs = Int64(fields[0]) else {
                throw fail("duration_ms is not a whole number")
            }
            guard let bandwidthBps = Int64(fields[1]) else {
                throw fail("bandwidth_bps is not a whole number")
            }
            guard let transport = NetworkTransport(rawValue: fields[2]) else {
                throw fail(
                    "transport is one of "
                        + NetworkTransport.allCases.map(\.rawValue).joined(separator: ", "))
            }
            var rttMs: Int64 = 0
            if fields.count == 4 {
                guard let value = Int64(fields[3]) else {
                    throw fail("rtt_ms is not a whole number")
                }
                rttMs = value
            }
            if let refusal = Stretch.refusal(durationMs, bandwidthBps, rttMs) {
                throw fail(refusal)
            }
            builder = builder.add(
                durationMs: durationMs, bandwidthBps: bandwidthBps, transport: transport,
                rttMs: rttMs)
            sawStretch = true
        }
        return try builder.build()
    }

    /// Kotlin's `trim()`: whitespace and control characters at either end.
    private static func trimmed(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// A trace that is not one, and why — a file's line number first when it came from a file.
public struct ThroughputTraceError: Error, CustomStringConvertible, Equatable {

    /// The reason, in the Kotlin reader's words.
    public let description: String

    init(_ description: String) { self.description = description }
}

/// One stretch of a ``ThroughputTrace``: `durationMs` from `startMs` at one set of conditions.
struct Stretch: Sendable, Hashable {
    let startMs: Int64
    let durationMs: Int64
    let bandwidthBps: Int64
    let rttMs: Int64
    let transport: NetworkTransport

    var endMs: Int64 { startMs + durationMs }

    /// Why these values are not a stretch, or nil when they are — one rule for the builder, which
    /// stops on it, and the reader, which reports it against a line.
    static func refusal(_ durationMs: Int64, _ bandwidthBps: Int64, _ rttMs: Int64) -> String? {
        if durationMs <= 0 { return "A stretch lasts at least 1 ms, not \(durationMs)" }
        if !(0...ThroughputTrace.maximumBandwidthBps).contains(bandwidthBps) {
            return "A bandwidth is between 0 and \(ThroughputTrace.maximumBandwidthBps) bit/s, "
                + "not \(bandwidthBps)"
        }
        if rttMs < 0 { return "A round-trip time is at least 0 ms, not \(rttMs)" }
        return nil
    }
}
