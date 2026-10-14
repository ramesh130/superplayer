/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.moq

/**
 * What one MoQ session reports about itself, as of the instant it was asked.
 *
 * ## This is not telemetry, and that is the decision rather than an oversight
 *
 * Nothing here is a `TelemetryEvent` and nothing here reaches a `TelemetrySink`. A MoQ session's
 * numbers are **one transport's account of its own connection**, and the telemetry vocabulary is a
 * playback-session vocabulary that names no transport at all — so putting a MoQ rate into it would
 * either need a field no other session can fill or, worse, an existing field two different
 * measurements now answer. `TelemetryEvent.PlaybackStateSampled.throughputEstimateBps` is exactly
 * that trap: it is the engine's bandwidth meter's smoothed estimate, the number *selection* acts on,
 * and on a realtime session it is **null**, because no load crosses a `DataSource` and
 * `BandwidthOracle` observes nothing (ADR-0018 rules 6 and 8). Two different numbers under one name
 * in a warehouse is the defect this decision exists to prevent, and keeping the MoQ reading in this
 * module's own type — reachable only from the object that opened the session — makes the confusion
 * unexpressible rather than merely discouraged.
 *
 * The consequence is stated rather than left to be noticed: `TelemetryEvent.SCHEMA_VERSION` does not
 * move, and under ADR-0008 rule 5 it could not — no event was added, no field was added, and no
 * existing metric's meaning changed. `docs/telemetry-schema.md`'s *Realtime sessions* says the same
 * thing to a reader of the warehouse rather than a reader of this file.
 *
 * ## A snapshot, and how to read two of them
 *
 * MoQ exports statistics as a **poll**, not a stream: there is no `statsFlow` in the bindings, so the
 * cadence is this library's ([MoqFrameSource.STATISTICS_INTERVAL_MS]) and a caller reads whatever the
 * last poll left. [sampledAtMs] is what makes that legible — a reading's age is the reader's to
 * judge, and a value with no timestamp cannot be told from a live one.
 *
 * [bytesSent], [bytesReceived], [packetsSent] and [packetsReceived] are **cumulative for the life of
 * the session**, so a rate over an interval is the difference between two readings divided by the
 * difference of their [sampledAtMs]. [roundTripTimeUs], [sendRateBps] and [receiveRateBps] are
 * instantaneous and are not differenced.
 *
 * ## Every number is optional, and that is the bindings' own vocabulary
 *
 * All nine are nullable **because MoQ declares them so**, and the nullability is carried across
 * rather than flattened to a zero at the boundary. It is not hypothetical: the receive-rate estimate
 * needs a peer speaking a recent enough version of the protocol, so a session against an older relay
 * reports none — and a zero there would read as a link carrying nothing, which is the opposite of
 * what happened. The rule the rest of this file is built on applies to each of them: **null is a
 * number nobody reported, and is never a measurement of nothing.**
 *
 * They are nine flat fields rather than grouped into the pairs they obviously form — sent beside
 * received, bytes beside packets — and the flatness is deliberate: this record is a **one-for-one
 * restatement of `uniffi.moq.MoqConnectionStats`**, so a reader checking the conversion is faithful
 * reads two lists of the same length in the same order. Grouping would buy symmetry and cost the one
 * property that matters at a boundary nothing under `check` exercises (`docs/testing.md`, *The MoQ
 * bindings*).
 *
 * @property sampledAtMs When this snapshot was taken, on `SystemClock.elapsedRealtime()` — the one
 *   clock this library measures durations on (`docs/telemetry-schema.md`, *Two clocks*). The one
 *   field that is never null, because it is this library's own reading rather than the session's.
 * @property roundTripTimeUs The connection's smoothed round trip time, in microseconds.
 * @property sendRateBps The connection's own estimate of what it can send, in bits per second.
 * @property receiveRateBps The connection's own estimate of what it is receiving, in bits per
 *   second. **This is the congestion controller's number and not a `BandwidthOracle` estimate**;
 *   nothing joins the two, and the class KDoc above is why. The likeliest of the nine to be null,
 *   for the protocol-version reason above.
 * @property bytesSent Bytes this session has put on the wire.
 * @property bytesReceived Bytes this session has taken off it.
 * @property packetsSent Packets this session has put on the wire.
 * @property packetsReceived Packets this session has taken off it.
 * @property transportBytesLost Bytes in packets the **connection** declared lost.
 * @property transportPacketsLost Packets the **connection** declared lost.
 * @property upstreamMediaLoss Media the viewer did not get to see. Always
 *   [MoqUpstreamMediaLoss.NOT_INSTRUMENTED]; the enum's KDoc is why, and why that is a value here
 *   rather than a zero.
 */
public data class MoqSessionStatistics(
    public val sampledAtMs: Long,
    public val roundTripTimeUs: Long?,
    public val sendRateBps: Long?,
    public val receiveRateBps: Long?,
    public val bytesSent: Long?,
    public val bytesReceived: Long?,
    public val packetsSent: Long?,
    public val packetsReceived: Long?,
    // Named for the *connection* rather than carried under the bindings' own `bytesLost` and
    // `packetsLost`, and the rename is the most load-bearing line in this file. A reader who finds
    // "lost" on a MoQ statistics record reads it as media that did not arrive, which is precisely
    // what ADR-0018 rule 8 says this path cannot report — and would then contradict the record with
    // a number that is true about something else.
    //
    // spec: RFC 9000 §13 — a QUIC endpoint detects lost packets and retransmits the frames they
    // carried, so a packet counted here is one the transport recovered from, not a frame the
    // decoder never saw. The two are different events and are never summed (ADR-0018 rule 8).
    public val transportBytesLost: Long?,
    public val transportPacketsLost: Long?,
    public val upstreamMediaLoss: MoqUpstreamMediaLoss = MoqUpstreamMediaLoss.NOT_INSTRUMENTED,
)

/**
 * Whether upstream **media** loss — a group the publisher's stream skipped, a frame the transport
 * dropped as stale — was counted for a session.
 *
 * One value, and the single value is the point. ADR-0018 rule 8 says a realtime session "reports no
 * upstream loss today and says so rather than reporting zero", and a `Long` field is exactly the
 * shape that cannot say so: every consumer of a counter reads absence as `0`, a dashboard averages
 * it, and the library has then asserted something it never measured. A value that is not a number
 * cannot be summed, averaged or compared against a threshold, which is what makes the absence
 * survive the trip into somebody else's pipeline.
 *
 * // ref: `docs/telemetry-schema.md`, *Realtime sessions*, states the same absence to a reader of
 * // the warehouse, with which counter is missing and why.
 */
public enum class MoqUpstreamMediaLoss {

    /**
     * Nothing counted it, on either side of the FFI boundary.
     *
     * Two counters would answer this and neither is reachable. The container consumer's
     * **group-skip count** (`discontinuity`) exists in MoQ's Rust and is exported over no UniFFI
     * binding at all, so it stops at the boundary this module reaches MoQ across; and a count of
     * frames the transport dropped as **stale** is instrumented nowhere in MoQ, in Rust or in
     * JavaScript, so it is upstream work rather than plumbing (#339's spike, #354 reports it).
     * Neither is inferrable here: a group that was skipped leaves no gap this module can see,
     * because frames arrive without the sequence numbers that would make one visible.
     *
     * A session's [MoqSessionStatistics.transportPacketsLost] is **not** this reading and is not a
     * substitute for it — a lost packet is one QUIC retransmitted, while a skipped group is media
     * that never reaches the decoder.
     */
    NOT_INSTRUMENTED,
}
