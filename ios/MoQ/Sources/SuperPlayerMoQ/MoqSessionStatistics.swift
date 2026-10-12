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

/// What one MoQ session reports about itself, as of the instant it was asked — the Kotlin
/// `MoqSessionStatistics`, field for field.
///
/// ## This is not telemetry, and that is the decision rather than an oversight
///
/// Nothing here is a `TelemetryEvent` and nothing here reaches a `TelemetrySink`. A MoQ session's
/// numbers are **one transport's account of its own connection**, and the telemetry vocabulary is a
/// playback session's that names no transport, so a MoQ rate on an existing field would be a second
/// measurement under a name that already means something else —
/// `PlaybackStateSampled.throughputEstimateBps` is that trap by name. Keeping the reading in this
/// product's own type, reachable only from the ``MoqFrameSource`` that opened the session, makes the
/// confusion unexpressible. `TelemetryEvent.schemaVersion` does not move, and under ADR-0008 rule 5
/// it could not; `docs/telemetry-schema.md`'s *Realtime sessions* says the same to a reader of the
/// warehouse.
///
/// ## A snapshot, and how to read two of them
///
/// MoQ exports statistics as a **poll**, not a stream, so the cadence is this library's
/// (``MoqFrameSource/statisticsIntervalMs``) and a caller reads whatever the last poll left.
/// ``sampledAtMs`` is what makes that legible. ``bytesSent``, ``bytesReceived``, ``packetsSent`` and
/// ``packetsReceived`` are **cumulative for the life of the session**, so a rate over an interval is
/// the difference of two readings over the difference of their ``sampledAtMs``;
/// ``roundTripTimeUs``, ``sendRateBps`` and ``receiveRateBps`` are instantaneous.
///
/// ## Every number is optional, and that is the bindings' own vocabulary
///
/// All nine are optional **because MoQ declares them so**, and the absence is carried across rather
/// than flattened to a zero at the boundary: the receive-rate estimate needs a peer speaking a recent
/// enough version of the protocol, so a session against an older relay reports none, and a zero there
/// would read as a link carrying nothing. **nil is a number nobody reported, and is never a
/// measurement of nothing.** The fields are flat rather than grouped, because the record is a
/// one-for-one restatement of the bindings' `MoqConnectionStats` and a reader checking the conversion
/// reads two lists of the same length in the same order.
public struct MoqSessionStatistics: Sendable, Hashable {

    /// When this snapshot was taken, on `CLOCK_MONOTONIC` in milliseconds — the clock every duration
    /// in this library is measured on, as core's `ConditionsBinding.monotonicNowMs()` reads it, and
    /// the iOS counterpart of the Kotlin field's `SystemClock.elapsedRealtime()`. The one field that
    /// is never nil, because it is this library's own reading rather than the session's.
    public let sampledAtMs: Int64

    /// The connection's smoothed round trip time, in microseconds.
    public let roundTripTimeUs: Int64?

    /// The connection's own estimate of what it can send, in bits per second.
    public let sendRateBps: Int64?

    /// The connection's own estimate of what it is receiving, in bits per second — the congestion
    /// controller's number, which nothing joins to any estimate of this library's. The likeliest of
    /// the nine to be nil, for the protocol-version reason above.
    public let receiveRateBps: Int64?

    /// Bytes this session has put on the wire.
    public let bytesSent: Int64?

    /// Bytes this session has taken off it.
    public let bytesReceived: Int64?

    /// Packets this session has put on the wire.
    public let packetsSent: Int64?

    /// Packets this session has taken off it.
    public let packetsReceived: Int64?

    // Named for the *connection* rather than carried under the bindings' own `bytesLost` and
    // `packetsLost`, and the rename is the most load-bearing line in this file: a reader who finds
    // "lost" on a MoQ record reads it as media that did not arrive, which ADR-0018 rule 8 says this
    // path cannot report.
    //
    // spec: RFC 9000 §13 — a QUIC endpoint detects lost packets and retransmits the frames they
    // carried, so a packet counted here is one the transport recovered from, not a frame the decoder
    // never saw. The two are different events and are never summed (ADR-0018 rule 8).

    /// Bytes in packets the **connection** declared lost.
    public let transportBytesLost: Int64?

    /// Packets the **connection** declared lost.
    public let transportPacketsLost: Int64?

    /// Media the viewer did not get to see. Always ``MoqUpstreamMediaLoss/notInstrumented``; the
    /// enum's documentation is why, and why that is a value here rather than a zero.
    public let upstreamMediaLoss: MoqUpstreamMediaLoss

    public init(
        sampledAtMs: Int64,
        roundTripTimeUs: Int64?,
        sendRateBps: Int64?,
        receiveRateBps: Int64?,
        bytesSent: Int64?,
        bytesReceived: Int64?,
        packetsSent: Int64?,
        packetsReceived: Int64?,
        transportBytesLost: Int64?,
        transportPacketsLost: Int64?,
        upstreamMediaLoss: MoqUpstreamMediaLoss = .notInstrumented
    ) {
        self.sampledAtMs = sampledAtMs
        self.roundTripTimeUs = roundTripTimeUs
        self.sendRateBps = sendRateBps
        self.receiveRateBps = receiveRateBps
        self.bytesSent = bytesSent
        self.bytesReceived = bytesReceived
        self.packetsSent = packetsSent
        self.packetsReceived = packetsReceived
        self.transportBytesLost = transportBytesLost
        self.transportPacketsLost = transportPacketsLost
        self.upstreamMediaLoss = upstreamMediaLoss
    }
}

/// Whether upstream **media** loss — a group the publisher's stream skipped, a frame the transport
/// dropped as stale — was counted for a session: the Kotlin `MoqUpstreamMediaLoss`.
///
/// One case, and the single case is the point. ADR-0018 rule 8 says a realtime session reports no
/// upstream loss and says so rather than reporting zero, and an integer field is exactly the shape
/// that cannot say so: every consumer of a counter reads absence as `0`. A value that is not a number
/// cannot be summed, averaged or compared against a threshold, which is what makes the absence
/// survive the trip into somebody else's pipeline.
///
/// // ref: `docs/telemetry-schema.md`, *Realtime sessions*, states the same absence to a reader of
/// // the warehouse, with which counter is missing and why.
public enum MoqUpstreamMediaLoss: Sendable, Hashable {

    /// Nothing counted it, on either side of the FFI boundary. The container consumer's group-skip
    /// count (`discontinuity`) exists in MoQ's Rust and is exported over no UniFFI binding — the
    /// Swift bindings are generated from the same revision as the Kotlin ones, so they stop at the
    /// same boundary — and a count of frames dropped as stale is instrumented nowhere in MoQ (#354
    /// reports both upstream). ``MoqSessionStatistics/transportPacketsLost`` is not this reading and
    /// is not a substitute for it.
    case notInstrumented
}
