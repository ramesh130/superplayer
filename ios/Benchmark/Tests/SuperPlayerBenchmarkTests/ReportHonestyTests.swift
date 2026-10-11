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
import XCTest

@testable import SuperPlayerBenchmark

/// `PRD.md` §6's honesty rules, held where the report is made — the Kotlin `ReportHonestyTest`,
/// against a fixture built so that SuperPlayer loses — plus the rule the iOS report adds: a run that
/// played no cell the exit criterion compares reads it as unmeasured, not met.
///
/// No playback: the fixture is `SessionMetrics` written by hand, so this costs `ios/check` nothing
/// a reader would notice.
final class ReportHonestyTests: XCTestCase {

    /// Arm (d) stalls more than stock on 3G, by far more than the noise: a loss, and printed first.
    func testALossIsPrintedBeforeAnyWinInTheSameWords() throws {
        let text = ReportWriter.write(fixture())

        let worse = try XCTUnwrap(
            text.range(of: "## Where SuperPlayer (adaptive) is worse, or no better"))
        let better = try XCTUnwrap(text.range(of: "## Where SuperPlayer (adaptive) is better"))
        XCTAssertLessThan(worse.lowerBound, better.lowerBound)
        let lossRow = try XCTUnwrap(
            text.range(of: "| VOD | 3G | rebuffer count |"), "the loss is in a table")
        XCTAssertLessThan(lossRow.lowerBound, better.lowerBound, "and in the losses section")
        XCTAssertTrue(text.contains("— **worse**"))
    }

    /// A difference inside two standard errors is neutral, never a small win — and the control: the
    /// same means with no spread at all are a verdict.
    func testADifferenceInsideTheNoiseIsNeutralAndOneOutsideItIsNot() {
        let noisy = Comparison(
            metric: Metric.qoe, lowerIsBetter: false,
            baseline: .of([1.0, 3.0, 1.0, 3.0]), candidate: .of([1.5, 3.5, 1.5, 3.5]))
        let exact = Comparison(
            metric: Metric.qoe, lowerIsBetter: false,
            baseline: .of([2.0, 2.0, 2.0]), candidate: .of([2.5, 2.5, 2.5]))

        XCTAssertEqual(noisy.verdict, .neutral)
        XCTAssertEqual(exact.verdict, .better)
    }

    /// Rebuffer ratio prints the pooled value — the sum of numerators over the sum of denominators —
    /// and not the mean of per-session ratios, which the schema forbids.
    func testTheCellRatioIsPooledNotAveraged() throws {
        let cell = CellResult(
            key: CellKey(scenario: .vod, network: .threeG, arm: .superPlayer),
            all: [
                metrics(rebufferMs: 1_000, playingMs: 9_000),
                metrics(rebufferMs: 0, playingMs: 90_000),
            ])

        XCTAssertEqual(try XCTUnwrap(cell.rebufferRatio), 1_000.0 / 100_000.0, accuracy: 1e-12)
        XCTAssertEqual(try XCTUnwrap(cell.rebufferRatioSpread).mean, 0.05, accuracy: 1e-12)
    }

    /// A session with dropped events is excluded, and counted rather than silent.
    func testAnIncompleteSessionIsExcludedAndCounted() {
        let cell = CellResult(
            key: CellKey(scenario: .vod, network: .threeG, arm: .superPlayer),
            all: [
                metrics(rebufferMs: 0, playingMs: 30_000),
                metrics(rebufferMs: 0, playingMs: 30_000, dropped: 3),
            ])

        XCTAssertEqual(cell.runs, 1)
        XCTAssertEqual(cell.excludedSessions, 1)
        XCTAssertTrue(
            ReportWriter.write(MatrixReport(conditions: conditions(), cells: [cell]))
                .contains("1 (+1 excluded)"))
    }

    /// The criterion compares arm (d) with arm (c), and the fixture played no arm (c): every profile
    /// reads not measured, never met — absence of evidence is not a pass.
    func testACriterionWithNothingToCompareIsNotMeasuredAndNotMet() {
        let report = fixture()
        let text = ReportWriter.write(report)

        XCTAssertEqual(Arm.candidate, .adaptive, "the candidate is the most capable arm")
        XCTAssertTrue(text.contains("**Not met — not measured.**"))
        XCTAssertTrue(ExitCriterion.judge(report).allSatisfy { !$0.measured && !$0.met })
    }

    /// Fewer runs than `PRD.md` §6's twenty, or a dirty tree, is said in prose at the top.
    func testAShortOrDirtyRunSaysSo() {
        let text = ReportWriter.write(fixture())

        XCTAssertTrue(text.contains("**3 run(s) per cell, not 20.**"))
        XCTAssertTrue(text.contains("**The working tree had uncommitted changes when this ran.**"))
    }

    // MARK: - The fixture

    /// VOD on 3G: stock stalls once a session, adaptive SuperPlayer four times, a little spread each.
    private func fixture() -> MatrixReport {
        let stock = CellResult(
            key: CellKey(scenario: .vod, network: .threeG, arm: .stockDefaults),
            all: [1, 1, 1].map { metrics(rebufferMs: 500, playingMs: 30_000, rebufferCount: $0) })
        let adaptive = CellResult(
            key: CellKey(scenario: .vod, network: .threeG, arm: .adaptive),
            all: [4, 4, 5].map { metrics(rebufferMs: 4_000, playingMs: 30_000, rebufferCount: $0) })
        return MatrixReport(conditions: conditions(), cells: [stock, adaptive])
    }

    private func conditions() -> RunConditions {
        RunConditions(
            commit: "fixture", treeDirty: true, xcode: "fixture", host: "fixture",
            simulator: "fixture", runsPerCell: 3, startedAt: "fixture", cellFilter: nil)
    }

    private func metrics(
        rebufferMs: Int64, playingMs: Int64, rebufferCount: Int = 1, dropped: Int = 0
    ) -> SessionMetrics {
        SessionMetrics(
            sessionId: "s", timeToFirstFrameMs: 500, startBoundary: .userIntent,
            rebufferMs: rebufferMs, rebufferCount: rebufferCount, playingMs: playingMs,
            averageBitrateBps: 730_000, switchCount: 0, upshiftCount: 0, downshiftCount: 0,
            switchMagnitudeBpsSum: 0, startupFailed: false, midStreamFailed: false,
            exitBeforeVideoStart: false, droppedEventCount: dropped, decisionChangeCount: 0,
            ended: true)
    }
}
