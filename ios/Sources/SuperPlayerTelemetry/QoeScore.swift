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

/// The standard QoE objective `PRD.md` Part 5 names — bitrate utility, less a rebuffer penalty,
/// less a switch penalty — the Kotlin `QoeScore`, term for term and constant for constant.
///
/// ref: Yin, Jindal, Sekar and Sinopoli, *A Control-Theoretic Approach for Dynamic Adaptive Video
/// Streaming over HTTP*, SIGCOMM 2015 §2 — `Σ q(R_k) − μ Σ T_k − Σ |q(R_{k+1}) − q(R_k)|` over a
/// session's chunks. Mao, Netravali and Alizadeh, *Neural Adaptive Video Streaming with Pensieve*,
/// SIGCOMM 2017 §5, uses the same objective and is where `q(R) = R` and `μ` come from.
///
/// **One number written down in advance**, because a benchmark row's columns trade against one
/// another and a judgement made afresh each time can be made to come out however the reader hoped.
/// **It does not replace the columns**: ``breakdown(_:ladderTopBitrateBps:)`` is there so a score
/// can be taken apart, and the benchmark's report prints the components beside it.
///
/// The Kotlin type's two stated departures from the literature are kept, for its reasons: every
/// term is **per second of playing time** rather than summed over a session, since the sessions
/// differ in length precisely because some stalled; and the utility is **sampled** quality,
/// ``SessionMetrics/averageBitrateBps``, the same quantity at the vocabulary's resolution.
///
/// **Not a gate on iOS yet.** Android's `QoeRegressionGateTest` holds the adaptive policy to
/// committed floors; the iOS adaptive policy is i3's (#416), and a gate over the static profile
/// would protect nothing that can move.
public enum QoeScore {

    /// The score for one session, in Mbps-equivalent per second of playing time; nil when the
    /// session has no playing time or no bitrate — a startup failure, or a session shorter than one
    /// sampling interval. Nil rather than zero, since a zero would sit mid-range and flatter it.
    public static func of(_ metrics: SessionMetrics, ladderTopBitrateBps: Int) -> Double? {
        breakdown(metrics, ladderTopBitrateBps: ladderTopBitrateBps)?.score
    }

    /// The score and the three terms it is made of, or nil exactly when ``of(_:ladderTopBitrateBps:)``
    /// is.
    public static func breakdown(_ metrics: SessionMetrics, ladderTopBitrateBps: Int) -> Breakdown?
    {
        guard let bitrateBps = metrics.averageBitrateBps else { return nil }
        let playingSeconds = Double(metrics.playingMs) / msPerSecond
        guard playingSeconds > 0 else { return nil }
        return Breakdown(
            bitrateUtility: bitrateBps / bpsPerMbps,
            rebufferPenalty: rebufferPenaltyPerSecond(ladderTopBitrateBps: ladderTopBitrateBps)
                * (Double(metrics.rebufferMs) / msPerSecond) / playingSeconds,
            switchPenalty: (Double(metrics.switchMagnitudeBpsSum) / bpsPerMbps) / playingSeconds)
    }

    /// One session's score as its three terms, each in Mbps-equivalent per second played.
    public struct Breakdown: Sendable, Hashable {
        /// `Σ q(R_k)`, per second played: the time-weighted average bitrate, in Mbps.
        public let bitrateUtility: Double
        /// `μ Σ T_k`, per second played; see ``QoeScore/rebufferPenaltyPerSecond(ladderTopBitrateBps:)``.
        public let rebufferPenalty: Double
        /// `Σ |q(R_{k+1}) − q(R_k)|`, per second played.
        public let switchPenalty: Double

        public init(bitrateUtility: Double, rebufferPenalty: Double, switchPenalty: Double) {
            self.bitrateUtility = bitrateUtility
            self.rebufferPenalty = rebufferPenalty
            self.switchPenalty = switchPenalty
        }

        /// The objective: utility, less both penalties.
        public var score: Double { bitrateUtility - rebufferPenalty - switchPenalty }
    }

    /// `μ`: what one second of stalling costs, in Mbps.
    ///
    /// ref: Pensieve §5 instantiates `μ` at the **maximum bitrate of the ladder, in Mbps**, so one
    /// second stalled costs what one second at the top rung is worth. The rule rather than their
    /// number, for the Kotlin reason: these ladders are not theirs, and a copied constant would
    /// silently change how harshly stalling is punished relative to the quality on offer.
    public static func rebufferPenaltyPerSecond(ladderTopBitrateBps: Int) -> Double {
        Double(ladderTopBitrateBps) / bpsPerMbps
    }

    /// Bits per second in one megabit per second, as a `Double` so the divisions do not truncate.
    private static let bpsPerMbps = 1_000_000.0

    private static let msPerSecond = 1_000.0
}
