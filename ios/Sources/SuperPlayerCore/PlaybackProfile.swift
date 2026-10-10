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

/// What kind of playback this is — the Kotlin `PlaybackProfile`, a name for a use case rather than
/// a bag of settings.
///
/// Declared here by the telemetry vocabulary (#408), because ``TelemetryEvent/SessionStarted``
/// carries the `profile` field `docs/telemetry-schema.md` names. The rest is #401's —
/// `Configuration.profile`, `StaticProfilePolicy`'s numbers and the argument for each — and the
/// cases are the ones that ticket names, in the Kotlin enum's order, so that whichever lands second
/// takes this declaration rather than a second one.
public enum PlaybackProfile: Sendable, Hashable {
    /// Long-form on-demand content — a film, an episode. The default.
    case videoOnDemand
    /// A live linear channel, whose viewer expects to be at the moving edge.
    case liveLinear
    /// Short-form feed content — clips of seconds, swiped through.
    case shortForm
    /// Lean-back viewing on a television.
    case tvLeanback
    /// Playback whose first cost is the viewer's data.
    case dataSaver
}
