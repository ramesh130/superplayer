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

/// Where a player's throughput estimate comes from — the seam `SuperPlayerABR`'s `BandwidthOracle`
/// fills, the Kotlin `ThroughputSource` (ADR-0009 rules 3, 7 and 8).
///
/// Core makes the estimate itself on every policy player, from the playing item's access log alone
/// (``AccessLogThroughput``). A policy that also conforms to ``ThroughputEstimatingPolicy`` brings an
/// estimator of its own instead, and `DecisionReapplication.swift` then hands it every reading it
/// would have kept — each access-log read, each item change, each transport change — and asks it,
/// rather than the item's window, what ``PlaybackConditions/throughput`` is and whether it has moved
/// far enough to be a ``DecisionTrigger/throughputChanged``. **One estimator per player**, for the
/// Kotlin reason: two estimators disagreeing is how "quality drops randomly" happens, so the reading
/// a policy is handed and the one that decides when it is asked again are the same object's.
///
/// The readings stay core's, in `ConditionsBinding.swift` (rule 3): an estimator is told the
/// translated transport and the access log's figures as plain numbers, and reads no platform type.
/// `package` rather than public, because what a consumer names is the policy; the Kotlin seam is a
/// friend module's internal interface for the same reason.
@MainActor
package protocol ThroughputSource {

    /// The player was given a new item, or none: the access log about to be read is a new one.
    mutating func itemChanged()

    /// The device moved to `transport` at `nowMs`. Called before the policy is re-consulted on
    /// ``DecisionTrigger/transportChanged``, so the decision made on that trigger reads the new one.
    mutating func transportChanged(to transport: NetworkTransport, atMs nowMs: Int64)

    /// The playing item's access log was read at `nowMs`: each entry's `observedBitrate`, oldest
    /// first, unfiltered — a negative figure is the SDK's "not measured" and is the reader's to drop.
    mutating func read(observedBitratesBps: [Double], atMs nowMs: Int64)

    /// The estimate at `nowMs`, or nil where there is none.
    func estimate(atMs nowMs: Int64) -> ThroughputEstimate?

    /// Whether the estimate has moved far enough since this last said yes that a policy should be
    /// asked again. Saying yes records the estimate as announced.
    mutating func movedMaterially(atMs nowMs: Int64) -> Bool
}

/// A ``PlaybackPolicy`` that brings its own throughput estimator — the meter slot of the Kotlin
/// `EnginePolicyExtension`, and the one part of that extension iOS needs: every other component it
/// installs on Android is a knob `EngineBinding.swift` already lays live (`docs/ios-plan.md` §3.3).
/// Core asks for one estimator per player, as the player is made.
package protocol ThroughputEstimatingPolicy: PlaybackPolicy {

    /// A new estimator for one player.
    @MainActor
    func makeThroughputSource() -> any ThroughputSource
}

extension AccessLogThroughput: ThroughputSource {

    /// A new item's log is a new window: the item's history is the item's, which is
    /// `DecisionReapplication`'s rule for every observation it keeps.
    package mutating func itemChanged() {
        self = AccessLogThroughput()
    }

    /// Nothing: this window is one item's, not one transport's. Keeping estimates per transport is
    /// the oracle's (ADR-0009 rule 8), and a player without one reads an item's log across a
    /// handover as one window, as it did before there was an oracle.
    package mutating func transportChanged(to transport: NetworkTransport, atMs nowMs: Int64) {}
}
