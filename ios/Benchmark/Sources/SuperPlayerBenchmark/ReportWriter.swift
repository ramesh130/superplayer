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

import Foundation
import SuperPlayerTestkit

/// The matrix, as a document — the Kotlin `ReportWriter`, and its one design rule.
///
/// **There is no "highlight the wins" mode, because there are no modes.** ``write(_:)`` takes a
/// report and nothing else: no cell filter, no threshold, no ordering by how well an arm did. Every
/// cell that ran is printed, and the losses come **before** the wins in the same words — `PRD.md`
/// §6: a benchmark table with no losses in it is a marketing document and will be read as one.
/// Whether a difference is a loss is ``Comparison/verdict``'s, decided by one rule in advance.
enum ReportWriter {

    /// `PRD.md` §6's floor: fewer runs than this and a report says it is not a twenty-run baseline.
    static let minimumRuns = 20

    static func write(_ report: MatrixReport) -> String {
        var out = ""
        title(&out)
        exitCriterion(report, &out)
        conditions(report, &out)
        howToReadThis(report, &out)
        // Losses first: the rule, not a layout preference.
        lossesAndNeutrals(report, &out)
        wins(report, &out)
        theF1Trade(report, &out)
        fullTables(report, &out)
        deviceArm(&out)
        rawTraces(report, &out)
        return out
    }

    /// The arms the candidate is compared against, in ``Arm``'s order.
    private static var references: [Arm] {
        Arm.allCases.filter { $0 != Arm.candidate }
    }

    private static func title(_ out: inout String) {
        out += """
            # SuperPlayer benchmark — iOS (simulator)

            The fixed matrix of [`PRD.md`](../../PRD.md) §6 on the iOS simulator, run by \
            `ios/Benchmark/bench` over testkit's loopback origin and network shaper, with \
            **\(Arm.candidate.label)** as the arm under test. Every number is produced by this \
            harness against content this repository generates (§0.2); none is carried in from \
            anywhere else, and none is copied from the Android reports under `benchmark/`, which \
            measure a different engine on a fake clock and are not comparable to these cell for cell.


            """
    }

    private static func exitCriterion(_ report: MatrixReport, _ out: inout String) {
        let judgement = ExitCriterion.judge(report)
        out += """
            ## Exit criterion

            Phase 11's criterion — *measured improvement of the adaptive policy over the static \
            profile on the shaped-network suite, with no regression on stable WiFi* — judged by \
            `ExitCriterion.swift`'s rule, fixed before this run: **\(ExitCriterion.candidate.label)** \
            against **\(ExitCriterion.reference.label)**, a QoE score better in at least one scenario \
            and worse in none on each shaped profile, and no metric worse on stable WiFi.


            """
        if judgement.allSatisfy(\.met) {
            out += "**Met.**\n\n"
        } else if judgement.allSatisfy({ !$0.measured }) {
            out += """
                **Not met — not measured.** This run played no cell of both arms the criterion \
                compares, so no profile has the comparison it is about. Unmeasured is not met: \
                absence of evidence is not a pass.


                """
        } else {
            let failing = judgement.filter { !$0.met }.map { MatrixNetwork.label($0.network) }
            out += "**Not met.** The criterion fails on: \(failing.joined(separator: ", ")).\n\n"
        }
        out += "| Network | Judged on | Verdict | QoE better in | Worse in |\n"
        out += "| --- | --- | --- | --- | --- |\n"
        for network in judgement {
            let judgedOn = network.network == .stableWifi ? "every metric" : "QoE score"
            let verdict =
                !network.measured
                ? "**not met** — not measured" : network.met ? "met" : "**not met**"
            let better = network.improvements.map(\.0.scenario.label).joined(separator: ", ")
            let worse = (network.regressions + network.missing).map {
                "\($0.0.scenario.label): \($0.1.metric) (\($0.1.verdict.rawValue))"
            }.joined(separator: ", ")
            out += "| \(MatrixNetwork.label(network.network)) | \(judgedOn) | \(verdict) "
            out += "| \(better.isEmpty ? emDash : better) | \(worse.isEmpty ? emDash : worse) |\n"
        }
        out += "\n"
    }

    private static func conditions(_ report: MatrixReport, _ out: inout String) {
        let conditions = report.conditions
        out += """
            ## What this was measured on

            | | |
            | --- | --- |
            | SuperPlayer commit | `\(conditions.commit)` |
            | Runs per cell | \(conditions.runsPerCell) |
            | Session length | \(Scenario.playbackMs / 1_000) s of real time past the first frame |
            | Simulator | \(conditions.simulator) |
            | Xcode | \(conditions.xcode) |
            | Host | \(conditions.host) |
            | Started | \(conditions.startedAt) |


            """
        if conditions.treeDirty {
            out += """
                > **The working tree had uncommitted changes when this ran.** These numbers describe \
                a state of the repository that is not the commit above and that nobody else can \
                reproduce. A report anything is graded against must be re-run on a clean tree.


                """
        }
        if let filter = conditions.cellFilter {
            out += """
                > **A filtered run** (`--cells "\(filter)"`), for working on the harness: a report of \
                whatever ran, not a baseline.


                """
        }
        if conditions.runsPerCell < minimumRuns {
            out += """
                > **\(conditions.runsPerCell) run(s) per cell, not \(minimumRuns).** `PRD.md` §6 asks \
                for at least \(minimumRuns), and on the simulator every session is \
                \(Scenario.playbackMs / 1_000) seconds of real time: twenty runs of this matrix would \
                be well over twelve hours of wall clock (`ios/Benchmark/README.md`, *The run count*). \
                The spreads below are estimated from \(conditions.runsPerCell) samples, so the \
                two-standard-error band is wide and most differences read as neutral — which is the \
                rule working, not the players agreeing.


                """
        }
    }

    private static func howToReadThis(_ report: MatrixReport, _ out: inout String) {
        out += "## How to read this\n\n**The four arms** (`PRD.md` §6, and `Arm.swift`):\n\n"
        for arm in Arm.allCases {
            let what =
                switch arm {
                case .stockDefaults:
                    "`AVPlayer(playerItem:)` and `play()`, and nothing else: Apple's own buffering and waiting."
                case .stockNaiveTuning:
                    "the same, plus `preferredForwardBufferDuration` of "
                        + "\(Arm.naiveForwardBufferMs / 1_000) s and `playImmediately(atRate: 1)` "
                        + "instead of `play()` — buffer much more, start much sooner: what an app "
                        + "writes when it has decided its player stalls and has not measured why."
                case .superPlayer:
                    "`SuperPlayer(configuration:)` with the profile each scenario names and its "
                        + "static policy. What it ran with is on every `sessionStarted` line of the traces."
                case .adaptive:
                    "the same with `SuperPlayerABR`'s `AdaptivePolicy.forProfile` for that profile: "
                        + "the five buffer branches, the transport caps on the expensive-network pair "
                        + "and the post-rebuffer hold, over the per-transport estimate memory, which "
                        + "is forgotten before each session so every one starts cold, as Android's do."
                }
            out += "- **\(arm.label)** — \(what)\n"
        }
        out += """

            **\(Arm.candidate.label) is the arm graded**, against each arm before it.

            **The definitions are [`docs/telemetry-schema.md`](../../docs/telemetry-schema.md)**, \
            and every metric is reduced from one event vocabulary by one function, \
            `SuperPlayerTelemetry`'s `SessionMetrics` — Android's, ported definition for \
            definition. Arm (c)'s events come from the shipped `QoeCollector`; the stock arms have \
            no `SuperPlayer`, so theirs come from `StockTelemetry`, which mirrors that collector \
            handler for handler and is held to it by `StockTelemetryAgreementTests`, in `ios/check`.

            - **Every mean carries its spread**, as `mean ± sd (p50, p95, n)`.
            - **A difference inside two standard errors is neutral, not a small win**, by a rule \
            fixed in advance and applied identically in both directions.
            - **Rebuffer ratio is pooled** — stalled over stalled-plus-playing, summed across \
            sessions — and its verdict comes from the spread of the per-session ratios.
            - **Bitrate is sampled at ten seconds**, `PlaybackStateSampled`'s cadence, so a \
            rendition held for less than one interval can fall between samples, identically for \
            every arm.

            **What the spread is a spread of.** Time here is real: the network is a deterministic \
            trace paced at a loopback socket, but the simulator, the host's scheduler and \
            AVFoundation's own timers are not deterministic, so a cell's spread is real run-to-run \
            variation on this host — and on a host shared with other work, some of it is that \
            work. It is not the variation a device on a real link would see, and a p95 here should \
            not be quoted as one.

            **Every session after a process's first starts warm.** `AVPlayer` carries a throughput \
            estimate between items in one process and nothing public clears it, so a session \
            begins where the one before it left the estimate. The runner cannot remove that and \
            balances it instead: a cell's arms play back to back on one network, their order \
            rotating each run (`MatrixRun.swift`). Read every start-up number as a returning \
            viewer's on that network, not a first-time one's.

            **\(report.cells.reduce(0) { $0 + $1.excludedSessions }) session(s) were excluded**, \
            out of \(report.cells.reduce(0) { $0 + $1.runs + $1.excludedSessions }) run. A session \
            is excluded when its event stream was incomplete (`SessionEnded.droppedEventCount` \
            non-zero) or it never ended; never for being slow or an outlier.


            """
    }

    private static func lossesAndNeutrals(_ report: MatrixReport, _ out: inout String) {
        out += """
            ## Where \(Arm.candidate.label) is worse, or no better

            Before the wins, deliberately: `PRD.md` §6 — *publish the cases where SuperPlayer is \
            neutral or worse.* A loss against \(Arm.stockNaiveTuning.label) is a cell where \
            configuring by intuition did as well, and is stated as plainly as any other.


            """
        for reference in references {
            let cells = report.comparisons(against: reference)
            out += "### Against \(reference.label)\n\n"
            guard !cells.isEmpty else {
                out += "_No cells ran for this comparison._\n\n"
                continue
            }
            let total = cells.reduce(0) { $0 + $1.comparisons.count }
            let losses = cells.flatMap { cell in cell.losses.map { (cell, $0) } }
            let neutrals = cells.flatMap { cell in cell.neutrals.map { (cell, $0) } }
            let noData = cells.flatMap { cell in cell.noData.map { (cell, $0) } }
            let wins = cells.reduce(0) { $0 + $1.wins.count }
            out += "**Worse (\(losses.count) of \(total) comparisons):**\n\n"
            if losses.isEmpty {
                out += """
                    _None._ Treat that with suspicion rather than satisfaction: at this sample size, \
                    no losses is as likely to mean the noise band swallowed them as that there are none.


                    """
            } else {
                table(losses, reference: reference, &out)
            }
            out += "**Neutral — inside the noise (\(neutrals.count)):**\n\n"
            tableOrNone(neutrals, reference: reference, &out)
            out += "**No data — one arm produced no measurement (\(noData.count)):**\n\n"
            tableOrNone(noData, reference: reference, &out)
            precondition(
                losses.count + neutrals.count + wins + noData.count == total,
                "The summary sections do not account for every comparison against \(reference)")
        }
    }

    private static func wins(_ report: MatrixReport, _ out: inout String) {
        out += "## Where \(Arm.candidate.label) is better\n\n"
        for reference in references {
            let wins = report.comparisons(against: reference).flatMap { cell in
                cell.wins.map { (cell, $0) }
            }
            out += "### Against \(reference.label)\n\n"
            tableOrNone(wins, reference: reference, &out)
        }
    }

    /// ``table(_:reference:_:)``, or a "none" that says the section is empty rather than missing.
    private static func tableOrNone(
        _ rows: [(CellComparison, Comparison)], reference: Arm, _ out: inout String
    ) {
        if rows.isEmpty {
            out += "_None._\n\n"
        } else {
            table(rows, reference: reference, &out)
        }
    }

    /// One table of comparisons in one shape whatever the verdict: there is no second, gentler
    /// table for the bad news.
    private static func table(
        _ rows: [(CellComparison, Comparison)], reference: Arm, _ out: inout String
    ) {
        out +=
            "| Scenario | Network | Metric | \(reference.label) | \(Arm.candidate.label) | Change |\n"
        out += "| --- | --- | --- | --- | --- | --- |\n"
        for (cell, comparison) in rows {
            out += "| \(cell.scenario.label) | \(MatrixNetwork.label(cell.network)) "
            out += "| \(comparison.metric) "
            out += "| \(value(comparison.metric, comparison.baseline, comparison.displayBaseline)) "
            out +=
                "| \(value(comparison.metric, comparison.candidate, comparison.displayCandidate)) "
            out += "| \(change(comparison)) |\n"
        }
        out += "\n"
    }

    private static func theF1Trade(_ report: MatrixReport, _ out: inout String) {
        out += """
            ## The F1 trade, as a loss

            `PRD.md` F1's trade is *lower bitrate on a constrained link in exchange for fewer \
            stalls*, and §6 requires it to appear **as a bitrate loss beside the rebuffer win**. \
            The adaptive policy makes it on cellular, so this is \(Arm.candidate.label) against \
            \(ExitCriterion.reference.label) on every scenario of every cellular profile — bitrate \
            beside the stalls and the score, each by the same rule as everywhere else, as Android's \
            section is. A bitrate cut with no stall win beside it is a trade that bought nothing, \
            and reads that way. **On the simulator the link is shaped but the path is not \
            cellular**: testkit reports a trace's transport on each response rather than switching \
            the device's (`ios/CLAUDE.md`), so the transport caps never bind here and what this \
            section shows is the hold and the buffer branches on a cellular *rate*.


            """
        let traded: Set = [Metric.bitrate, Metric.rebufferRatio, Metric.rebufferCount, Metric.qoe]
        let rows = f1Comparisons(report).flatMap { cell in
            cell.comparisons.filter { traded.contains($0.metric) }.map { (cell, $0) }
        }
        if rows.isEmpty {
            out +=
                "_This run measured no cellular data-saver cell for both arms, so the trade is not shown._\n\n"
        } else {
            table(rows, reference: f1Reference, &out)
        }
    }

    /// Which arm the F1 section compares the candidate against: the static profile, on every
    /// scenario — Android's Phase 3 section. (#414's baseline, with no adaptive arm, compared stock on
    /// the data-saver row.)
    private static var f1Reference: Arm { ExitCriterion.reference }

    /// The cellular cells the F1 section shows, in ``MatrixNetwork/cellular``'s order.
    private static func f1Comparisons(_ report: MatrixReport) -> [CellComparison] {
        report.comparisons(against: f1Reference)
            .filter {
                MatrixNetwork.cellular.contains($0.network)
            }
            .sorted {
                MatrixNetwork.cellular.firstIndex(of: $0.network)!
                    < MatrixNetwork.cellular.firstIndex(of: $1.network)!
            }
    }

    private static func fullTables(_ report: MatrixReport, _ out: inout String) {
        out += "## Every cell\n\nAll of it, whatever it says.\n\n"
        for scenario in Scenario.allCases {
            let ladder = scenario.ladderBitratesBps.map { "\($0 / 1_000) kbit/s" }
            out += "### \(scenario.label)\n\n"
            out += "Profile for arm (c): `\(scenario.profile)`. Ladder: "
            out += "\(ladder.joined(separator: ", ")).\n\n"
            out += "| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count "
            out += "| Bitrate (bit/s) | Switches | Startup failures | QoE score |\n"
            out += "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n"
            for network in MatrixNetwork.profiles {
                for arm in Arm.allCases {
                    let label = "| \(MatrixNetwork.label(network)) | \(arm.label) "
                    guard let cell = report.cell(scenario, network, arm) else { continue }
                    let runs =
                        cell.excludedSessions == 0
                        ? "\(cell.runs)" : "\(cell.runs) (+\(cell.excludedSessions) excluded)"
                    out += label + "| \(runs) | \(ttff(cell.timeToFirstFrameMs)) "
                    out += "| \(ratio(cell.rebufferRatio)) | \(plain(cell.rebufferCount)) "
                    out += "| \(bitrate(cell.averageBitrateBps)) | \(plain(cell.switchCount)) "
                    out += "| \(percentage(cell.startupFailureRate)) | \(plain(cell.qoeScore)) |\n"
                }
            }
            out += "\n"
        }
    }

    private static func deviceArm(_ out: inout String) {
        out += """
            ## Peak RSS and battery — the device arm

            `PRD.md` §6 reports peak resident memory and battery over a thirty-minute session, and \
            **neither is in the tables above**: a simulator process's memory is the host's and it \
            has no battery, so either number from here would be a plausible wrong one. Both are a \
            device's, and on iOS they are i10's (#439), which also measures `DeviceCapacity`'s \
            bound. Printed as dashes so an unmeasured cell cannot be mistaken for a measured one.

            | Metric | Arm (a) | Arm (b) | Arm (c) | Arm (d) |
            | --- | --- | --- | --- | --- |
            | Peak RSS (MiB) | — | — | — | — |
            | Battery delta over 30 min (%) | — | — | — | — |

            **Not covered either**, and written down so an absent row does not read as a passing \
            one: protected content (FairPlay is i6's and needs a device), and public streams over a \
            real network (`docs/testing.md` bars the network; the device arm is where they belong).


            """
    }

    private static func rawTraces(_ report: MatrixReport, _ out: inout String) {
        let sessions = report.cells.reduce(0) { $0 + $1.runs }
        out += """
            ## Raw traces

            Every event of every run, one JSONL file per cell in `traces/`, named as the Android \
            report's are, each line carrying its scenario, network, arm and run beside the event's \
            own fields (`TraceWriter.swift`). Every number above can be recomputed from them.

            ```text
            grep '"evt":"rebufferEnded"' traces/*.jsonl                 # every stall in the matrix
            grep '"run":1' traces/vod__3g__superplayer.jsonl            # one session, end to end
            ```

            \(report.cells.count) cell(s), \(sessions) aggregated session(s).

            """
    }

    // MARK: - Formatting: "no measurement" is an em dash, never a zero

    private static let emDash = "—"

    private static func ttff(_ distribution: Distribution?) -> String {
        guard let d = distribution else { return emDash }
        return "\(Int(d.p50.rounded())) / \(Int(d.p95.rounded())) "
            + "(mean \(Int(d.mean.rounded())) ± \(Int(d.standardDeviation.rounded())), n=\(d.count))"
    }

    private static func plain(_ distribution: Distribution?) -> String {
        distribution?.summary { String(format: "%.2f", $0) } ?? emDash
    }

    private static func bitrate(_ distribution: Distribution?) -> String {
        distribution?.summary { "\(Int($0.rounded()))" } ?? emDash
    }

    private static func ratio(_ value: Double?) -> String {
        value.map { String(format: "%.4f", $0) } ?? emDash
    }

    private static func percentage(_ value: Double?) -> String {
        value.map { String(format: "%.1f%%", $0 * 100) } ?? emDash
    }

    private static func value(_ metric: String, _ distribution: Distribution?, _ display: Double?)
        -> String
    {
        if let display { return ratio(display) }
        guard let distribution else { return emDash }
        if metric.contains("bit/s") { return bitrate(distribution) }
        if metric.contains("(ms)") { return distribution.summary { "\(Int($0.rounded()))" } }
        return plain(distribution)
    }

    /// The signed difference — always candidate minus reference, never flipped so every row reads
    /// as a positive number — its relative size, and the verdict word that says which way is good.
    private static func change(_ comparison: Comparison) -> String {
        guard let delta = comparison.delta else {
            return "\(emDash) (\(comparison.verdict.rawValue))"
        }
        let sign = delta >= 0 ? "+" : "−"
        let magnitude =
            abs(delta) >= 100 ? "\(Int(abs(delta).rounded()))" : String(format: "%.3f", abs(delta))
        let percent = comparison.relativeDelta.map {
            String(format: " (%@%.1f%%)", $0 >= 0 ? "+" : "−", abs($0) * 100)
        }
        return "\(sign)\(magnitude)\(percent ?? "") — **\(comparison.verdict.rawValue)**"
    }
}
