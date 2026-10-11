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

import SuperPlayerTelemetry
import SuperPlayerTestkit

/// Which cell of the matrix a result belongs to: one scenario, one network, one arm.
struct CellKey: Hashable, Sendable, CustomStringConvertible {
    let scenario: Scenario
    let network: NetworkProfile
    let arm: Arm

    var description: String {
        "\(scenario.label) / \(MatrixNetwork.label(network)) / \(arm.label)"
    }

    /// The trace file's name: the Kotlin `TraceWriter`'s, so a cell's file has one name on both.
    var traceFileName: String {
        "\(scenario.slug)__\(MatrixNetwork.slug(network))__\(arm.slug).jsonl"
    }
}

/// One cell: every run of one arm, one scenario and one network, summarised — the Kotlin
/// `CellResult`.
///
/// `PRD.md` §6's honesty rules land where the numbers are made, not where they are printed: no mean
/// without a spread (``Distribution``), a ratio aggregated the way the schema says, and a count of
/// what was thrown away rather than a quietly thinner sample.
struct CellResult: Sendable {
    let key: CellKey

    /// Every session of this cell fit to aggregate (`SessionMetrics.usable`).
    let sessions: [SessionMetrics]

    /// Sessions measured and then discarded, counted rather than silent.
    ///
    /// ref: `docs/telemetry-schema.md`, *The stream is lossy, and it says so*. A cell that threw
    /// away nineteen of twenty runs would otherwise look exactly like one that ran cleanly.
    let excludedSessions: Int

    /// Builds a cell from every session it ran, keeping the ones fit to aggregate. Nothing is
    /// dropped for being slow, an outlier, or spoiling a trend: a benchmark that trimmed its own
    /// tails would be measuring its author.
    init(key: CellKey, all: [SessionMetrics]) {
        self.key = key
        sessions = all.filter(\.usable)
        excludedSessions = all.count - sessions.count
    }

    var runs: Int { sessions.count }

    /// ref: `docs/telemetry-schema.md`, *Time to first frame*.
    var timeToFirstFrameMs: Distribution? {
        .of(sessions.compactMap { $0.timeToFirstFrameMs.map(Double.init) })
    }

    /// The cell's rebuffer ratio: **the sum of the numerators over the sum of the denominators**,
    /// never the mean of per-session ratios, which would weight a short session like a long one.
    ///
    /// ref: `docs/telemetry-schema.md`, *Rebuffering*.
    var rebufferRatio: Double? {
        let stalled = sessions.reduce(Int64(0)) { $0 + $1.rebufferMs }
        let playing = sessions.reduce(Int64(0)) { $0 + $1.playingMs }
        return stalled + playing <= 0 ? nil : Double(stalled) / Double(stalled + playing)
    }

    /// How the per-session ratios were spread — **not** the cell's ratio, and labelled so: it
    /// answers "consistently mediocre or occasionally awful", which a pooled ratio cannot.
    var rebufferRatioSpread: Distribution? { .of(sessions.compactMap(\.rebufferRatio)) }

    var rebufferCount: Distribution? { .of(sessions.map { Double($0.rebufferCount) }) }

    /// ref: `docs/telemetry-schema.md`, *Bitrate* — time-weighted, in bits per second.
    var averageBitrateBps: Distribution? { .of(sessions.compactMap(\.averageBitrateBps)) }

    /// ref: `docs/telemetry-schema.md`, *Bitrate* — the initial choice is not a switch.
    var switchCount: Distribution? { .of(sessions.map { Double($0.switchCount) }) }

    /// Startup failures over sessions that attempted playback, which is every session here.
    ///
    /// ref: `docs/telemetry-schema.md`, *Video start failure and mid-stream failure*.
    var startupFailureRate: Double? {
        runs == 0 ? nil : Double(sessions.filter(\.startupFailed).count) / Double(runs)
    }

    /// Sessions that ended with no frame and no failure: a runner defect, surfaced.
    var exitBeforeVideoStart: Int { sessions.filter(\.exitBeforeVideoStart).count }

    /// The QoE objective, per session (`QoeScore`).
    var qoeScore: Distribution? {
        .of(
            sessions.compactMap {
                QoeScore.of($0, ladderTopBitrateBps: key.scenario.ladderTopBitrateBps)
            })
    }
}

/// One metric, one cell, two arms: what the difference was and whether it was one at all — the
/// Kotlin `Comparison`, whose rule is decided here, once, before anything is printed.
struct Comparison: Sendable {
    let metric: String
    /// True when a *smaller* number is the better one.
    let lowerIsBetter: Bool
    let baseline: Distribution?
    let candidate: Distribution?
    /// What to print instead of a distribution's mean — set for rebuffer ratio only, whose verdict
    /// needs a distribution and whose printed value is the pooled ratio (``CellComparison``).
    var displayBaseline: Double?
    var displayCandidate: Double?

    /// Candidate minus baseline, in the metric's own units.
    var delta: Double? {
        guard let baseline, let candidate else { return nil }
        return candidate.mean - baseline.mean
    }

    /// ``delta`` as a fraction of the baseline, or nil when that is zero or missing.
    var relativeDelta: Double? {
        guard let delta, let from = baseline?.mean, from != 0 else { return nil }
        return delta / from
    }

    /// Two standard errors of the difference of the means: the band inside which a difference is
    /// not distinguishable from run-to-run noise.
    ///
    /// ref: the standard error of a difference of two independent means is
    /// `sqrt(sd_a²/n_a + sd_b²/n_b)`; two of them is the conventional rough 95% interval.
    /// NIST/SEMATECH e-Handbook of Statistical Methods §7.3.1. The rough form deliberately, for the
    /// Kotlin reason: the point is a rule fixed in advance, not a p-value.
    var noiseBand: Double? {
        guard let a = baseline, let b = candidate, a.count >= 2, b.count >= 2 else { return nil }
        return 2
            * (a.standardDeviation * a.standardDeviation / Double(a.count)
            + b.standardDeviation * b.standardDeviation / Double(b.count)).squareRoot()
    }

    var verdict: Verdict {
        guard let delta else { return .noData }
        // A difference smaller than the noise is not a difference: neutral, never a small win.
        if let noiseBand, abs(delta) <= noiseBand { return .neutral }
        if delta == 0 { return .neutral }
        return (delta < 0) == lowerIsBetter ? .better : .worse
    }

    var hasData: Bool { verdict != .noData }

    enum Verdict: String, Sendable {
        case better, worse, neutral
        case noData = "no data"
    }
}

/// One cell's comparisons of the candidate against one reference arm, one per reported metric, in
/// `PRD.md` §6's order — the Kotlin `CellComparison`.
struct CellComparison: Sendable {
    let baseline: CellResult
    let candidate: CellResult

    var scenario: Scenario { baseline.key.scenario }
    var network: NetworkProfile { baseline.key.network }

    /// Rebuffer ratio takes its **verdict** from the per-session spread and its **printed value**
    /// from the pooled ratio — the Kotlin split, and the correction its report records: printing
    /// the spread's mean under a heading promising the pooled value is the aggregation the schema
    /// forbids.
    var comparisons: [Comparison] {
        [
            Comparison(
                metric: Metric.timeToFirstFrame, lowerIsBetter: true,
                baseline: baseline.timeToFirstFrameMs, candidate: candidate.timeToFirstFrameMs),
            Comparison(
                metric: Metric.rebufferRatio, lowerIsBetter: true,
                baseline: baseline.rebufferRatioSpread, candidate: candidate.rebufferRatioSpread,
                displayBaseline: baseline.rebufferRatio, displayCandidate: candidate.rebufferRatio),
            Comparison(
                metric: Metric.rebufferCount, lowerIsBetter: true,
                baseline: baseline.rebufferCount, candidate: candidate.rebufferCount),
            Comparison(
                metric: Metric.bitrate, lowerIsBetter: false,
                baseline: baseline.averageBitrateBps, candidate: candidate.averageBitrateBps),
            Comparison(
                metric: Metric.switchCount, lowerIsBetter: true,
                baseline: baseline.switchCount, candidate: candidate.switchCount),
            Comparison(
                metric: Metric.qoe, lowerIsBetter: false,
                baseline: baseline.qoeScore, candidate: candidate.qoeScore),
        ]
    }

    var losses: [Comparison] { comparisons.filter { $0.verdict == .worse } }
    var neutrals: [Comparison] { comparisons.filter { $0.verdict == .neutral } }
    var wins: [Comparison] { comparisons.filter { $0.verdict == .better } }
    var noData: [Comparison] { comparisons.filter { !$0.hasData } }
}

/// The metric names a comparison carries: the Kotlin strings, so the two reports' tables read
/// alike.
enum Metric {
    static let timeToFirstFrame = "time to first frame (ms)"
    static let rebufferRatio = "rebuffer ratio"
    static let rebufferCount = "rebuffer count"
    static let bitrate = "average bitrate (bit/s)"
    static let switchCount = "switch count"
    static let qoe = "QoE score"
}

/// Everything one matrix run produced: its conditions and every cell — the Kotlin `MatrixReport`.
/// It holds cells for every arm rather than precomputed differences, so a reader who decides
/// differences differently can take the cells and do so.
struct MatrixReport: Sendable {
    let conditions: RunConditions
    let cells: [CellResult]

    func cell(_ scenario: Scenario, _ network: NetworkProfile, _ arm: Arm) -> CellResult? {
        cells.first { $0.key == CellKey(scenario: scenario, network: network, arm: arm) }
    }

    /// Every metric of every cell, `candidate` against `baseline`, produced whole: there is no
    /// parameter for which comparisons to include and no ordering by how favourable they are.
    func comparisons(against baseline: Arm, candidate: Arm = Arm.candidate) -> [CellComparison] {
        Scenario.allCases.flatMap { scenario in
            MatrixNetwork.profiles.compactMap { network in
                guard let reference = cell(scenario, network, baseline),
                    let graded = cell(scenario, network, candidate)
                else { return nil }
                return CellComparison(baseline: reference, candidate: graded)
            }
        }
    }
}
