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

/// `SuperPlayerABR`'s entry point: the adaptive ``PlaybackPolicy`` for a profile — the Kotlin
/// `AdaptivePolicy.forProfile`, `docs/ios-plan.md` §3.3.
///
/// ```swift
/// var configuration = SuperPlayer.Configuration(profile: .videoOnDemand)
/// configuration.policy = AdaptivePolicy.forProfile(.videoOnDemand)
/// let player = SuperPlayer(configuration: configuration)
/// ```
///
/// What comes back composes ``AdaptiveSelectionPolicy`` and ``AdaptiveBufferPolicy`` — the selection
/// half first, because the buffer's memory ceiling is sized at the rate the selection in force lets
/// it fill (#115) — and brings a ``BandwidthOracle`` per player through core's `package` seam
/// (`ThroughputSource.swift`), so the estimate it decides on is per transport and outlives an item.
/// That is the one component it installs: the Kotlin extension also installs a load control and a
/// selection factory because Media3 fixes both at construction, and here every knob a decision
/// reaches is laid live by core (`EngineBinding.swift`).
///
/// **One policy may serve many players**, unlike the Kotlin object that installs one engine's
/// components: it holds no player's state, and each player core makes asks it for an oracle of its
/// own, all over the process's one ``EstimateMemory``. A `PlayerPool` configured with it is
/// therefore what the Kotlin pool is — one memory — without being one engine.
public enum AdaptivePolicy {

    /// The adaptive policy for `profile`, for ``SuperPlayer/Configuration/policy``.
    public static func forProfile(_ profile: PlaybackProfile) -> any PlaybackPolicy {
        forProfile(
            profile, rebufferCooldownMs: AdaptiveBufferPolicy.defaultRebufferCooldownMs,
            memory: .process)
    }

    /// The same, with the hysteresis cooldown and the memory stated — the seam a harness test
    /// watches a hold lapse through without the real half-minute, on a memory no other test fills.
    package static func forProfile(
        _ profile: PlaybackProfile, rebufferCooldownMs: Int64, memory: EstimateMemory
    ) -> any PlaybackPolicy {
        AdaptiveEnginePolicy(
            buffer: AdaptiveBufferPolicy(profile: profile, rebufferCooldownMs: rebufferCooldownMs),
            selection: AdaptiveSelectionPolicy(
                profile: profile, rebufferCooldownMs: rebufferCooldownMs),
            memory: memory)
    }
}

/// The two halves composed, and the oracle each player is given.
struct AdaptiveEnginePolicy: ThroughputEstimatingPolicy {

    let buffer: AdaptiveBufferPolicy
    let selection: AdaptiveSelectionPolicy
    let memory: EstimateMemory

    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision {
        let trackSelection = selection.decide(conditions).trackSelection
        var decided = buffer.decide(conditions, selectionInForce: trackSelection)
        decided.trackSelection = trackSelection
        return decided
    }

    @MainActor
    func makeThroughputSource() -> any ThroughputSource {
        BandwidthOracle(memory: memory, now: ConditionsBinding.monotonicNowMs)
    }
}
