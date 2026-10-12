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

import Dispatch
import Network
import SuperPlayerCore

/// What the network is believed to deliver, per transport, as a ``ThroughputEstimate`` — the Kotlin
/// `BandwidthOracle`, `docs/ios-plan.md` §3.3's oracle row.
///
/// The Kotlin properties, and which of them hold here:
///
/// - **Per-transport estimates, reseeded on a transport change** — unchanged: the estimate is
///   ``EstimateMemory``'s for the transport the device is on, and a handover reads that transport's
///   own window or its ``ColdDefaults`` entry (ADR-0009 rules 8 and 9).
/// - **A spread and a conservative percentile beside the mean** — unchanged.
/// - **One estimator per player** — unchanged: on an adaptive player this object *is* what core asks
///   for ``PlaybackConditions/throughput`` and for ``DecisionTrigger/throughputChanged``.
/// - **Cache hits are not samples** — nothing to exclude. The Kotlin exclusion is of a transfer a
///   cache answered; here there is no transfer to see, and what AVFoundation reports is already its
///   own figure for what it fetched.
///
/// **The input is different, and that is the whole of the difference.** A Kotlin sample is one
/// transfer timed by the engine's `TransferListener`. AVFoundation tells an app about no transfer, so
/// a sample here is **one access-log entry's `observedBitrate`** — the SDK's own measure of what was
/// transferred while that entry was current, which spans a variant, a seek or a stall rather than a
/// segment — read by core on the occasions `DecisionReapplication.swift` names and handed over as a
/// number. An entry's figure moves while it is current, so the newest is revised in place
/// rather than counted again; an entry open across a transport change keeps on the old transport the
/// figure it had at the change and is neither revised nor counted on the new one, since what it
/// reports after the change mixes both links. `docs/telemetry-schema.md`'s *Decision changes*
/// says the same for a reader of the warehouse.
///
/// ```swift
/// let oracle = BandwidthOracle()
/// oracle.currentEstimate().meanBps
/// oracle.release()
/// ```
///
/// Built this way it watches the network path itself and reads the process's memory, which the
/// adaptive players in the process are filling; it records nothing of its own, because nothing
/// hands a standalone oracle an access log. An adaptive player makes its own, told the transport by
/// core's one path reading rather than by a second monitor.
@MainActor
public final class BandwidthOracle {

    private let memory: EstimateMemory
    private let now: @Sendable () -> Int64
    private var transport: NetworkTransport
    private var sampler = AccessLogSampler()
    private var lastAnnounced: ThroughputEstimate
    private var monitor: NWPathMonitor?

    /// An oracle that watches the network path itself, over the process's memory and the monotonic
    /// clock.
    public convenience init() {
        self.init(memory: .process, now: ConditionsBinding.monotonicNowMs)
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            // The translation is core's, in the one file ADR-0009 rule 3 confines it to; this reads
            // the platform here and hands the path straight there, as the Kotlin oracle does.
            let reading = ConditionsBinding.reading(of: path)
            MainActor.assumeIsolated {
                let generation =
                    reading.interface == .cellular
                    ? ConditionsBinding.cellularGeneration(
                        radioAccessTechnology: ConditionsBinding.currentRadioAccessTechnology())
                    : nil
                self?.transportChanged(
                    to: ConditionsBinding.transport(of: reading, cellularGeneration: generation),
                    atMs: ConditionsBinding.monotonicNowMs())
            }
        }
        monitor.start(queue: .main)
        self.monitor = monitor
    }

    /// An oracle told the transport by its owner, over `memory`, timed by `now` — what an adaptive
    /// player makes, and what a test states a clock with. ``NetworkTransport/unknown`` until told,
    /// the Kotlin oracle's start where the platform has said nothing yet.
    package init(memory: EstimateMemory, now: @escaping @Sendable () -> Int64) {
        self.memory = memory
        self.now = now
        self.transport = .unknown
        self.lastAnnounced = memory.estimate(for: .unknown, atMs: now())
    }

    /// The transport the device is on, as this oracle last observed it.
    public func currentTransport() -> NetworkTransport { transport }

    /// What that transport is believed to deliver, now. Never nil: a transport with no samples has a
    /// cold default, with a ``ThroughputEstimate/sampleCount`` of zero.
    public func currentEstimate() -> ThroughputEstimate {
        memory.estimate(for: transport, atMs: now())
    }

    /// Stops watching the network path. Idempotent. The memory behind the estimate is the process's
    /// and is not released.
    public func release() {
        monitor?.cancel()
        monitor = nil
    }

    /// Whether `estimate` describes a stable network: a spread of no more than
    /// ``stableSpreadFraction`` of the mean, and an unknown spread is not stable — the Kotlin
    /// `ThroughputEstimate.isStable()`, the one line both adaptive halves and the trigger read.
    nonisolated static func isStable(_ estimate: ThroughputEstimate) -> Bool {
        guard let spread = estimate.spreadBps else { return false }
        return Double(spread) <= Double(estimate.meanBps) * stableSpreadFraction
    }

    /// What "stable" means numerically: the Kotlin `OracleBandwidthMeter.STABLE_SPREAD_FRACTION`, a
    /// quarter, for its reason — discounting the mean by one spread then lands inside the gap to the
    /// next rung down of a ladder whose rungs differ by a third or more.
    /// ref: Yin, Jindal, Sekar and Sinopoli, SIGCOMM 2015, §5.1.
    nonisolated static let stableSpreadFraction = 0.25

    /// How far the mean moves before a policy is told: the Kotlin
    /// `OracleBandwidthMeter.MATERIAL_MOVE_FRACTION`, a fifth, and core's
    /// ``AccessLogThroughput/materialMoveFraction`` — one number for one reason.
    nonisolated static let materialMoveFraction = AccessLogThroughput.materialMoveFraction
}

extension BandwidthOracle: ThroughputSource {

    package func itemChanged() {
        sampler = AccessLogSampler()
    }

    /// The Kotlin `onTransportChanged`: the estimate is now the new transport's, and a reseed is
    /// material by definition — which core has already decided, since it re-consults on
    /// ``DecisionTrigger/transportChanged``, so the reseeded estimate is recorded as announced here
    /// rather than announced a second time on the next access-log read.
    package func transportChanged(to transport: NetworkTransport, atMs nowMs: Int64) {
        guard transport != self.transport else { return }
        self.transport = transport
        sampler.closeEntriesSeenSoFar()
        lastAnnounced = memory.estimate(for: transport, atMs: nowMs)
    }

    package func read(observedBitratesBps: [Double], atMs nowMs: Int64) {
        sampler.read(observedBitratesBps, on: transport, atMs: nowMs, into: memory)
    }

    package func estimate(atMs nowMs: Int64) -> ThroughputEstimate? {
        memory.estimate(for: transport, atMs: nowMs)
    }

    /// The Kotlin meter's announcement rule: the mean moved by ``materialMoveFraction`` of the last
    /// estimate announced, or the spread crossed the ``stableSpreadFraction`` line either way —
    /// because a policy reading a different number on each side of that line must be asked again
    /// when the side changes.
    package func movedMaterially(atMs nowMs: Int64) -> Bool {
        let next = memory.estimate(for: transport, atMs: nowMs)
        let last = lastAnnounced
        let moved =
            Double(abs(next.meanBps - last.meanBps)) >= Double(last.meanBps)
            * Self.materialMoveFraction || Self.isStable(next) != Self.isStable(last)
        if moved { lastAnnounced = next }
        return moved
    }
}

/// One item's access log as samples: each entry's `observedBitrate` recorded once, and revised while
/// it moves.
///
/// Entries are addressed by their position in the log, which only grows: an entry once recorded is
/// revised when its figure changes — the current entry, whose transfers are still being counted —
/// and left alone when it does not, so a closed entry keeps the time it was last measured at. Only
/// the newest ``SampleWindow/windowSamples`` entries are read, since older ones would be pushed out
/// of the window as they arrived.
struct AccessLogSampler {

    private var recorded: [Int: (bps: Int64, token: EstimateMemory.SampleToken)] = [:]
    private var entriesSeen = 0

    /// Entries before this position belong to a transport the device has since left.
    private var firstEntryOnThisTransport = 0

    /// Ends the current transport's claim on every entry seen so far, the open one included: its
    /// sample keeps the figure it had at the change, on the old link, and what the entry reports
    /// afterwards covers both links, so it is revised on neither and counted on neither.
    mutating func closeEntriesSeenSoFar() {
        firstEntryOnThisTransport = entriesSeen
        recorded = [:]
    }

    mutating func read(
        _ observedBitratesBps: [Double], on transport: NetworkTransport, atMs nowMs: Int64,
        into memory: EstimateMemory
    ) {
        entriesSeen = observedBitratesBps.count
        let first = max(
            firstEntryOnThisTransport, observedBitratesBps.count - SampleWindow.windowSamples)
        for index in first..<observedBitratesBps.count {
            // A negative figure is the SDK's "not measured", and is not a sample.
            let figure = observedBitratesBps[index]
            guard figure.isFinite, figure > 0 else { continue }
            let bps = Int64(figure)
            if let previous = recorded[index] {
                guard previous.bps != bps else { continue }
                recorded[index] = (bps, memory.revise(previous.token, to: bps, atMs: nowMs))
            } else {
                recorded[index] = (bps, memory.record(bps, on: transport, atMs: nowMs))
            }
        }
    }
}
