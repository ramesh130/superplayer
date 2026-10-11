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

/// The policy boundary: observed playback conditions in, a playback decision out — the Kotlin
/// `PlaybackPolicy` (`docs/ios-plan.md` §3.3's first row, ADR-0005 and ADR-0009).
///
/// Everything SuperPlayer decides about *how* to play — how far ahead to buffer, what to cap
/// variant selection at, how to hold a live edge — is decided here and nowhere else. The engine is
/// on the other side: no type in ``PlaybackConditions`` or ``PlaybackDecision`` is AVFoundation's,
/// and no implementation is handed an `AVPlayer` or an `AVPlayerItem`. `ConditionsBinding.swift` is
/// the one place platform readings become conditions and `EngineBinding.swift` the one place a
/// decision becomes engine configuration, so a policy is a pure function between two SuperPlayer
/// values that a test can drive by hand.
///
/// ```swift
/// var configuration = SuperPlayer.Configuration(profile: .liveLinear)
/// configuration.policy = MyPolicy()
/// ```
///
/// **The default is static.** A configuration with no ``SuperPlayer/Configuration/policy`` plays
/// its profile's documented decision, looked up once — `StaticProfilePolicy`, the Kotlin class of
/// that name — and its player observes nothing and is consulted again never, which
/// `PolicyPaysNothingTests` counts (ADR-0009 rule 7). The adaptive version is
/// `SuperPlayerABR`'s, behind this same protocol.
///
/// ## When a policy is consulted
///
/// At construction, always, and again on the five ``DecisionTrigger``s and nothing else — a
/// transport change, the stream type becoming known or changing, a rebuffer ending, the playback
/// rate changing, and the throughput estimate moving materially (ADR-0009 rule 4). Not on a
/// cadence, because a policy asked once per sample oscillates; hysteresis is the policy's own, read
/// off ``PlaybackConditions/stallHistory``. A consultation whose answer equals the decision in
/// force changes nothing and emits nothing.
///
/// **Every policy but the static one is consulted again, which is where iOS departs from
/// Android.** ADR-0009 rule 5 says a re-applied decision is honoured whole or the policy is not
/// re-consulted at all, and on Android only `superplayer-abr`'s retargetable components can honour
/// one, so a consumer's own policy on a core-only player is asked once. Every knob a decision
/// reaches here is live-settable on the `AVPlayerItem` and `AVPlayer` (`EngineBinding.swift`), so
/// the platform meets rule 5's condition for any policy, and a consumer's hand-written one is
/// re-consulted exactly as an adaptive one is. ``SuperPlayer/playbackDecision`` is the decision in
/// force, and each change is a `DecisionChanged` telemetry event.
public protocol PlaybackPolicy: Sendable {

    /// The decision to play with, given what is currently known about the playback.
    ///
    /// Must be a pure function of `conditions` — called on the main actor, the facade's, and
    /// expected to be cheap and to block on nothing.
    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision
}

extension PlaybackProfile {

    /// This profile's static per-profile lookup: its documented decision for every set of
    /// conditions — the Kotlin `PlaybackPolicy.forProfile(profile)`, and what a configuration with
    /// no ``SuperPlayer/Configuration/policy`` plays under.
    ///
    /// Public so an app can *read* what a profile asks for without making a player — to log it,
    /// show it beside the switch that chooses it, or start a policy of its own from it
    /// (``PlaybackDecision``). Handed back as a configuration's policy it is still the static one,
    /// and the player still observes nothing.
    public var policy: any PlaybackPolicy { StaticProfilePolicy(profile: self) }
}
