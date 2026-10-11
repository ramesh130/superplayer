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

import AVFoundation
import Network

/// Observes the conditions, consults the policy on the five named triggers, and hands a changed
/// decision back to be laid on the engine: ADR-0009 rules 3, 4 and 5 as one object — the Kotlin
/// `DecisionReapplication`, `docs/ios-plan.md` §3.3's fourth row.
///
/// **Exists only on a player whose policy is not a profile's static one** (any profile's:
/// ``PlaybackProfile/policy`` handed in for another profile is just as unchanging). On that player none of
/// this is allocated and nothing below is registered — the static policy's answer cannot change, so
/// there is nothing to observe for — which `PolicyPaysNothingTests` counts through the observed
/// register (ADR-0009 rule 7). Every other policy has one, whoever wrote it, because every knob a
/// decision reaches is live-settable (`EngineBinding.swift`): the Kotlin object exists only beside
/// `superplayer-abr`'s retargetable components, and here the platform is those components.
///
/// ## What is observed, and from where
///
/// Every registration goes through the observed register (`ObservedSignals.swift`), in the two
/// scopes it gives this object, and every translation is `ConditionsBinding.swift`'s:
///
/// - for the player's life: the network path, the rate, and `timeControlStatus`;
/// - on each item the player is given, whatever it carries: its time jumping (a seek), its
///   duration, and its access log.
///
/// The heap is read once, here at construction. The first decision is made then too, before any
/// path has been delivered, so it is made with the transport unobserved; the monitor's first path
/// arrives as it starts and is a ``DecisionTrigger/transportChanged`` like any other, which is the
/// Kotlin rule that a value becoming known is a change.
///
/// **The history is the item's.** Every item the player is given starts a fresh stall history, an
/// unknown stream type and an empty throughput window — the Kotlin object's media-item transition,
/// observed here at the one place the facade replaces an item.
///
/// A consultation whose answer equals the decision in force changes nothing and is not reported, so
/// every report is a change with a trigger a reader can name.
@MainActor
final class DecisionReapplication {

    /// Told of every change, after it is the decision in force: the facade lays it on the engine
    /// and tells the collector.
    var onDecisionChanged: ((PlaybackDecision, DecisionTrigger) -> Void)?

    /// The decision in force.
    private(set) var decision: PlaybackDecision

    private let policy: any PlaybackPolicy
    private let heapBudgetBytes: Int64?

    private var transport: NetworkTransport?
    private var streamType: StreamType?
    private var playbackSpeed: Float?
    private var stalls = StallObservation()
    private var throughput = AccessLogThroughput()

    private weak var player: AVPlayer?
    private var playerObservations: [Observation] = []
    private var itemObservations: [Observation] = []

    /// Makes the first decision, with what can be read before anything is observed: the heap, and a
    /// rate of 1 — `AVPlayer`'s own default, as the Kotlin object reads Media3's default speed.
    init(policy: any PlaybackPolicy, availableMemoryBytes: UInt64) {
        self.policy = policy
        self.heapBudgetBytes = ConditionsBinding.heapBudgetBytes(
            availableMemoryBytes: availableMemoryBytes)
        self.playbackSpeed = 1
        self.decision = PlaybackDecision()
        self.decision = policy.decide(currentConditions())
    }

    /// The registrations live now, for the facade's count.
    var observations: [Observation] { playerObservations + itemObservations }

    /// The conditions as observed now — what the policy is handed.
    func currentConditions() -> PlaybackConditions {
        let now = ConditionsBinding.monotonicNowMs()
        return PlaybackConditions(
            transport: transport, throughput: throughput.estimate(atMs: now),
            stallHistory: stalls.current(atMs: now), streamType: streamType,
            heapBudgetBytes: heapBudgetBytes, playbackSpeed: playbackSpeed)
    }

    /// Registers the player's observers. Called once, as the facade is made.
    func start(on player: AVPlayer) {
        precondition(self.player == nil, "Already started")
        self.player = player
        playerObservations = [
            Observation.registerNetworkPath { [weak self] path in self?.pathChanged(path) },
            Observation.register(.decisionRateChanged, on: player) { [weak self] _ in
                self?.rateChanged()
            },
            Observation.register(.decisionTimeControlStatus, on: player) { [weak self] _ in
                self?.timeControlStatusChanged()
            },
        ]
    }

    /// `item` is now the player's — or nothing is: a new session's state, and the item's observers.
    func itemChanged(to item: AVPlayerItem?) {
        itemObservations = []
        stalls = StallObservation()
        throughput = AccessLogThroughput()
        streamType = nil
        guard let item else { return }
        itemObservations = [
            Observation.register(.decisionItemTimeJumped, on: item) { [weak self] in
                self?.seeked()
            },
            Observation.register(.decisionItemDuration, on: item) { [weak self, weak item] in
                guard let item else { return }
                self?.durationChanged(item.duration)
            },
            Observation.register(.decisionAccessLogEntry, on: item) { [weak self, weak item] in
                guard let item else { return }
                self?.accessLogChanged(item)
            },
        ]
    }

    /// Unregisters everything. Safe to call twice.
    func stop() {
        itemObservations = []
        playerObservations = []
        player = nil
    }

    // MARK: - Readings

    private func pathChanged(_ path: NWPath) {
        let reading = ConditionsBinding.reading(of: path)
        let generation =
            reading.interface == .cellular
            ? ConditionsBinding.cellularGeneration(
                radioAccessTechnology: ConditionsBinding.currentRadioAccessTechnology())
            : nil
        let observed = ConditionsBinding.transport(of: reading, cellularGeneration: generation)
        guard observed != transport else { return }
        transport = observed
        reconsult(.transportChanged)
    }

    private func rateChanged() {
        guard let rate = player?.rate, let speed = ConditionsBinding.playbackSpeed(rate: rate),
            speed != playbackSpeed
        else { return }
        playbackSpeed = speed
        reconsult(.playbackSpeedChanged)
    }

    private func timeControlStatusChanged() {
        guard let player else { return }
        let activity = ConditionsBinding.activity(
            status: player.timeControlStatus, reason: player.reasonForWaitingToPlay)
        if stalls.onActivity(activity, atMs: ConditionsBinding.monotonicNowMs()) {
            reconsult(.rebufferEnded)
        }
        // A second occasion to read the access log, for the reason `readAccessLog()` gives.
        if let item = player.currentItem { accessLogChanged(item) }
    }

    private func seeked() {
        stalls.onSeek(
            atMs: ConditionsBinding.monotonicNowMs(),
            playing: player?.timeControlStatus == .playing)
    }

    private func durationChanged(_ duration: CMTime) {
        let observed = ConditionsBinding.streamType(duration: duration)
        guard observed != streamType else { return }
        streamType = observed
        // Becoming known counts as a change, for the Kotlin reason: the policy has been deciding
        // without it. Becoming unknown again does not, since nothing was learned.
        if observed != nil { reconsult(.streamTypeChanged) }
    }

    private func accessLogChanged(_ item: AVPlayerItem) {
        readAccessLog(of: item)
        if throughput.movedMaterially(atMs: ConditionsBinding.monotonicNowMs()) {
            reconsult(.throughputChanged)
        }
    }

    /// Reads `item`'s access log into the throughput window.
    ///
    /// **When is the limit of this reading, and it is stated rather than hidden.** An entry's
    /// `observedBitrate` keeps moving while the entry is current, and AVFoundation posts
    /// `AVPlayerItemNewAccessLogEntry` for a new entry — a variant switch, a seek, a stall — and
    /// promises nothing for an update to the current one. So the log is read on that notification,
    /// again on every `timeControlStatus` change, and again before every consultation whatever its
    /// trigger, so a policy is never handed a figure older than the occasion it was asked on. What it
    /// cannot do is notice a move on a steady session between those occasions: the Kotlin meter is
    /// told of every transfer, and nothing here is. A timer would close that gap at the cost of a
    /// registration on every policy player and a consultation cadence ADR-0009 rule 4 rules out, so
    /// ``DecisionTrigger/throughputChanged`` fires on those occasions only — the adaptive policy
    /// (i3's ABR half) is where a finer reading belongs if one is measured to matter.
    private func readAccessLog(of item: AVPlayerItem) {
        throughput.read(
            observedBitratesBps: ConditionsBinding.observedBitrates(of: item),
            atMs: ConditionsBinding.monotonicNowMs())
    }

    private func reconsult(_ trigger: DecisionTrigger) {
        if let item = player?.currentItem { readAccessLog(of: item) }
        let next = policy.decide(currentConditions())
        guard next != decision else { return }
        decision = next
        onDecisionChanged?(next, trigger)
    }
}
