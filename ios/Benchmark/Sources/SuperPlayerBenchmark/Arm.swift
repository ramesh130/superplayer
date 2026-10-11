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

/// The players `PRD.md` §6 compares, and the only place the iOS benchmark says what each one is —
/// the Kotlin `Arm`, on `AVPlayer`.
///
/// ```text
/// (a) stockDefaults      AVPlayer(playerItem:), play(), and nothing else
/// (b) stockNaiveTuning   the same, plus what an app writes from intuition
/// (c) superPlayer        SuperPlayer(configuration:) with the scenario's profile
/// (d) adaptive           the same plus the adaptive policy — an empty slot until i3 (#416)
/// ```
///
/// Arm (b) is what keeps arm (a) from being a straw man, for the Kotlin reason: the question an
/// adopter is choosing between is not whether configuring beats not configuring, but whether
/// configuring *by use case with the reasoning written down* beats configuring *by intuition*.
enum Arm: String, CaseIterable, Sendable {

    case stockDefaults
    case stockNaiveTuning
    case superPlayer
    case adaptive

    /// How the arm appears in a report's column heading and a raw trace's `arm` field — the Kotlin
    /// labels, so a reader moving between the two reports reads one vocabulary.
    var label: String {
        switch self {
        case .stockDefaults: "stock (defaults)"
        case .stockNaiveTuning: "stock (naive tuning)"
        case .superPlayer: "SuperPlayer"
        case .adaptive: "SuperPlayer (adaptive)"
        }
    }

    /// The slug a trace file is named with, the Kotlin file names' arm half.
    var slug: String {
        switch self {
        case .stockDefaults: "stock-defaults"
        case .stockNaiveTuning: "stock-naive-tuning"
        case .superPlayer: "superplayer"
        case .adaptive: "superplayer-adaptive"
        }
    }

    /// Whether this arm can be played yet. **Arm (d) is a slot, not an arm, until i3**: the iOS
    /// adaptive policy is #416's, and a report that ran arm (c) twice under two names would be the
    /// fabrication the report's honesty rules exist to prevent. The matrix skips it, and the report
    /// prints it as unmeasured rather than leaving it out — an absent column reads as one nobody
    /// needed. #416 fills it by building the player in `SessionRunner` and returning true here.
    var isBuilt: Bool { self != .adaptive }

    /// Whether this is one of the two stock arms, which is what decides how a player is built.
    var isStock: Bool { self == .stockDefaults || self == .stockNaiveTuning }

    /// The arm every built arm before it is compared against: the most capable one built. Arm (c)
    /// while (d) is a slot — the Phase 1 report's shape — and arm (d) from i3 on, the Phase 3 one's,
    /// with nothing to change here when it arrives.
    static var candidate: Arm { allCases.last { $0.isBuilt }! }

    /// What arm (b) configures: the two changes every such recipe makes, pulling against each
    /// other, in the vocabulary iOS offers for them.
    ///
    /// ref: derivation (CONTRIBUTING.md rule 4), stated rather than attributed, for the Kotlin
    /// reason: there is no one canonical forum answer to cite, and holding up a particular person's
    /// as the naive arm would be unfair and unverifiable both. What is citable is the baseline these
    /// depart from — `AVPlayerItem.preferredForwardBufferDuration`, whose default of zero lets the
    /// system choose, and `AVPlayer.play()`, which waits to minimise stalling.
    ///
    /// - **Buffer much more** — 120 s ahead, the Kotlin arm's ceiling, where Apple chooses. The
    ///   change made in response to "it keeps stalling".
    /// - **Start much sooner** — `playImmediately(atRate: 1)` rather than `play()`: Apple's own call
    ///   for "start now, even if it may stall", and the iOS analog of the Kotlin arm's one-second
    ///   start floor, since iOS has no start floor to set. It applies to the start only; a later
    ///   stall waits as arm (a)'s does. `automaticallyWaitsToMinimizeStalling = false` is the other
    ///   spelling and is deliberately not this arm: such a player *pauses* on a stall and needs the
    ///   app to resume it, so the arm would measure the runner's resume loop rather than the
    ///   player — and the collector's rebuffer rule reads a paused player as one that stopped
    ///   stalling.
    static let naiveForwardBufferMs = 120_000

    /// What a stock arm's `SessionStarted` says it ran with, in the value type arm (c) reports its
    /// own configuration in: Apple's choice of buffer (`maxBufferMs` nil) and waiting on for arm (a);
    /// ``naiveForwardBufferMs`` for arm (b), whose start is the `playImmediately` the decision has
    /// no field for and the report states beside it. No ceiling on either, which is what an
    /// unconfigured item applies (`preferredPeakBitRate` and `preferredMaximumResolution` zero).
    var stockDecision: PlaybackDecision {
        switch self {
        case .stockNaiveTuning:
            PlaybackDecision(buffer: BufferPolicy(maxBufferMs: Self.naiveForwardBufferMs))
        default:
            PlaybackDecision()
        }
    }
}
