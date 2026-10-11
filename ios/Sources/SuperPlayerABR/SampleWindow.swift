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

/// The last few throughput samples taken on one transport — the Kotlin `SampleWindow`, and the three
/// readings a ``ThroughputEstimate`` takes of them, which are core's `WindowReadings` so that this
/// window and an item's access-log window summarise samples one way.
///
/// One distribution, three readings, for the Kotlin reason (#99): a mean and a percentile of the
/// *same* window cannot disagree about what was measured, only about how to summarise it.
///
/// **A sample here is one access-log entry's `observedBitrate`**, where the Kotlin sample is one
/// transfer timed (`docs/ios-plan.md` §3.3's "a different input"). An entry's figure keeps moving
/// while the entry is current, so a sample can be *revised* — its figure replaced and its time moved
/// to the revision — which a transfer, timed once at its end, never needed. Each sample therefore
/// carries an id, so the one reader that recorded it, and nobody else, can revise it.
///
/// A value type: ``EstimateMemory`` holds one per transport behind its lock.
struct SampleWindow: Sendable {

    private struct Sample: Sendable {
        let id: UInt64
        var bps: Int64
        var atMs: Int64
    }

    private let capacity: Int

    /// Oldest recorded first.
    private var samples: [Sample] = []

    init(capacity: Int = SampleWindow.windowSamples) {
        precondition(capacity >= 1, "A window holds at least one sample, not \(capacity)")
        self.capacity = capacity
    }

    var count: Int { samples.count }

    var isEmpty: Bool { samples.isEmpty }

    /// When the newest sample was taken or last revised, or nil for an empty window. The latest
    /// time rather than the last sample's, because a revision moves an older sample's time forward.
    var newestAtMs: Int64? { samples.map(\.atMs).max() }

    /// Adds a sample of `bps` taken at `atMs` under `id`, dropping the oldest once the window is full.
    mutating func add(_ bps: Int64, atMs: Int64, id: UInt64) {
        precondition(bps >= 0, "A throughput sample cannot be negative, was \(bps)")
        samples.append(Sample(id: id, bps: bps, atMs: atMs))
        if samples.count > capacity { samples.removeFirst() }
    }

    /// Replaces sample `id`'s figure with `bps`, taken at `atMs`. False where the sample is no longer
    /// in the window — pushed out by newer ones, or forgotten as stale.
    mutating func revise(_ id: UInt64, to bps: Int64, atMs: Int64) -> Bool {
        precondition(bps >= 0, "A throughput sample cannot be negative, was \(bps)")
        guard let index = samples.firstIndex(where: { $0.id == id }) else { return false }
        samples[index].bps = bps
        samples[index].atMs = atMs
        return true
    }

    mutating func clear() {
        samples.removeAll()
    }

    /// The mean, the spread and the conservative percentile of the window, or nil when it is empty.
    var readings: WindowReadings? { WindowReadings(of: samples.map(\.bps)) }

    /// How many samples the window holds: the Kotlin `SampleWindow.WINDOW_SAMPLES`, eight, for its
    /// reason — long enough to see the variability, short enough to track a change, and a spread of
    /// eight values a less noisy fact than one of five. Core's ``AccessLogThroughput/windowEntries``
    /// is the same number for the same reason; an entry spans more than one segment, so eight reach
    /// further back here than eight Android samples do.
    /// ref: Mao, Netravali and Alizadeh, *Neural Adaptive Video Streaming with Pensieve*, SIGCOMM
    /// 2017, §4.
    static let windowSamples = AccessLogThroughput.windowEntries
}
