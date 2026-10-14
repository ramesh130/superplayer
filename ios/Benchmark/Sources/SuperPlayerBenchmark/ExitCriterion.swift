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

import SuperPlayerTestkit

/// Phase 11's exit criterion for the adaptive policy, decided by one rule fixed before a matrix
/// ran — the Kotlin `ExitCriterion`, rule for rule, so the iOS report opens with the same verdict.
///
/// *Measured improvement of the adaptive policy over the static profile on the shaped-network
/// suite, with no regression on stable WiFi*: on a shaped profile, a QoE score better than the
/// static profile's by more than two standard errors in at least one scenario and worse in none; on
/// stable WiFi, no metric of any scenario worse. **Unmeasured is not met** — a filtered run that
/// played no cell of both arms on a profile reads that way: absence of evidence is not a pass.
enum ExitCriterion {

    /// The arm the candidate must improve on: the static profile.
    static let reference = Arm.superPlayer

    /// The arm being graded.
    static let candidate = Arm.adaptive

    struct NetworkJudgement: Sendable {
        let network: NetworkProfile
        let measured: Bool
        let improvements: [(CellComparison, Comparison)]
        let regressions: [(CellComparison, Comparison)]
        let missing: [(CellComparison, Comparison)]

        var met: Bool {
            measured && regressions.isEmpty && missing.isEmpty
                && (network == .stableWifi || !improvements.isEmpty)
        }
    }

    static func judge(_ report: MatrixReport) -> [NetworkJudgement] {
        let all = report.comparisons(against: reference, candidate: candidate)
        return MatrixNetwork.profiles.map { network in
            let cells = all.filter { $0.network == network }
            let judged = cells.flatMap { cell in
                cell.comparisons
                    .filter { network == .stableWifi || $0.metric == Metric.qoe }
                    .map { (cell, $0) }
            }
            return NetworkJudgement(
                network: network, measured: !cells.isEmpty,
                improvements: judged.filter {
                    $0.1.verdict == .better && $0.1.metric == Metric.qoe
                },
                regressions: judged.filter { $0.1.verdict == .worse },
                missing: judged.filter { !$0.1.hasData })
        }
    }
}
