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

/// What kind of playback this is, chosen once, as a player is made:
///
/// ```swift
/// let player = SuperPlayer(configuration: .init(profile: .liveLinear))
/// ```
///
/// The port of `superplayer-core`'s `PlaybackProfile`, the identical enum (`docs/ios-plan.md` §3.1),
/// and its reasons are that type's: a profile is a *name for a use case*, not a bag of settings. The
/// numbers it produces — how far ahead to buffer, what to cap a variant at, how to hold a live edge —
/// live with the reasoning for each in `StaticProfilePolicy`'s table, so that the profile a consumer
/// names does not change meaning when the table behind it is retuned. Knowing *which* numbers a use
/// case wants is the hard part, and that knowledge is what a profile carries: a consumer who picks
/// ``videoOnDemand`` over ``shortForm`` can read why the two differ and defend the choice in review,
/// which `item.preferredForwardBufferDuration = 15` at a call site does not allow.
///
/// It is not a quality setting a viewer picks, and it is not adaptive: adapting within the shape a
/// profile names is the policy's subject (`docs/ios-plan.md` §3.3), behind the same boundary.
///
/// Fixed for a player's lifetime (ADR-0020 rule 5), because half of it is applied to the
/// `AVPlayer` as the player is made. It names no AVFoundation type, because its meaning is a shared
/// document's (ADR-0020 rule 3).
public enum PlaybackProfile: CaseIterable, Sendable, Hashable {

    /// Long-form on-demand content — a film, an episode, a lecture.
    ///
    /// The default, because it is the case with the widest tolerance: the viewer is committed, and a
    /// stall half an hour in costs far more than a slightly slower start. Quality is uncapped.
    case videoOnDemand

    /// A live linear channel — a stream with a moving edge the viewer expects to be *at*.
    ///
    /// Falling behind the edge is the cost here: every second lost to a stall is a second of latency
    /// that playback does not give back on its own unless it is told to hold its distance.
    case liveLinear

    /// Short-form feed content — clips of seconds, in a list the viewer swipes through.
    ///
    /// The dominant costs are time to first frame and data spent on clips nobody watched: a viewer
    /// who swipes away after two seconds has paid for whatever was buffered ahead.
    case shortForm

    /// Lean-back viewing on a television — a title chosen with a remote and watched across a room.
    ///
    /// The long-form case with both of its costs moved further the same way: holding media ahead
    /// costs neither battery nor a metered link, and a stall happens on the largest screen in the
    /// house in front of everyone watching. It names the kind of viewing and not the device: a tvOS
    /// app that also plays short clips plays them under ``shortForm``.
    case tvLeanback

    /// The viewer has asked to spend less data, or the app has decided on their behalf.
    ///
    /// The only profile that caps quality rather than only shaping buffering, and the only one whose
    /// ceilings a viewer can *see* in the picture — the trade of resolution for bytes they asked for.
    case dataSaver
}

extension SuperPlayer.Configuration {

    /// A configuration playing under `profile` — the Android `SuperPlayer.Builder.setProfile`, as a
    /// property set at construction (ADR-0020 rule 5).
    public init(profile: PlaybackProfile) {
        self.init()
        self.profile = profile
    }
}
