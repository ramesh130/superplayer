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

/// The playback policy a player was running — the Kotlin `PlaybackDecision`, named here so that
/// ``TelemetryEvent/SessionStarted`` and ``TelemetryEvent/DecisionChanged`` carry the field
/// `docs/telemetry-schema.md` names, before the policy boundary exists.
///
/// **It has no halves yet, deliberately.** The buffer, track-selection, preload, retry, download
/// and output halves, the policy that answers them and `player.playbackDecision` are #415's
/// (`docs/ios-plan.md` §3.3, ADR-0005 and ADR-0009), and a half declared here first would be a
/// shape that ticket inherits rather than decides. Until it lands no iOS player decides anything,
/// every decision is this empty value, and nothing emits ``TelemetryEvent/DecisionChanged`` — the
/// vocabulary is ready for the event and the event has no source, which is the order the document
/// and the collector ticket (#409) need.
public struct PlaybackDecision: Sendable, Hashable {
    public init() {}
}

/// What made a policy be consulted again — the Kotlin `DecisionTrigger`'s closed list of five,
/// which ADR-0009 rule 4 names and `docs/telemetry-schema.md`'s *Decision changes* lists. Here
/// because ``TelemetryEvent/DecisionChanged`` carries one; what raises each is #415's.
public enum DecisionTrigger: Sendable, Hashable {
    /// The device moved to a different network transport.
    case transportChanged
    /// The playlist said what the content is — or, for a live playlist that ended, what it became.
    case streamTypeChanged
    /// A rebuffer ended and playback resumed.
    case rebufferEnded
    /// The playback rate changed.
    case playbackSpeedChanged
    /// The throughput estimate moved far enough to change a decision.
    case throughputChanged
}
