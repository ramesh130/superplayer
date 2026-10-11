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
import SuperPlayerCore
import SuperPlayerTelemetry

/// The matrix, run: every arm of every scenario on every network, `runs` times, written out
/// as a report and its raw traces — the Kotlin `BenchmarkMatrixTest.runsTheMatrixAndWritesTheReport`.
///
/// ## The order sessions run in, and why it is not the obvious one
///
/// **`AVPlayer` remembers a throughput estimate across items in one process**, and nothing public
/// clears it: on the iOS 27.1 simulator the first session of a process started on the ladder's
/// first-listed rung, and a session after one on a fast link started on the top rung of a 3G
/// link (`ios/Benchmark/README.md`, *What the simulator taught the harness*). So which session ran
/// just before is an input to the one after, as `superplayer-abr`'s estimate memory is on Android —
/// where the runner can idle the clock past the memory's lifetime, and here it cannot.
///
/// What is done about it is **balance**, since removal is unavailable: a cell's arms play back to
/// back on one network, so each arm's predecessor is usually a session on the same link, and their
/// order rotates by one place every run and every cell, so across a run each arm opens as many
/// cells as any other. No arm is always the one that inherits the previous cell's network. A returning viewer's
/// warm estimate is what every session after the first measures, which the report says.
@MainActor
struct MatrixRun {

    let runs: Int

    /// A substring of a cell's name to run only matching cells — for working on the harness, never
    /// for a baseline; a report written with one says so.
    let cellFilter: String?

    /// Where the report and its traces go.
    let output: URL

    /// Runs a cell when nobody says otherwise, and the committed baseline's count: the fewest that
    /// estimates a spread on more than one degree of freedom. It was also as many as a cell had arms
    /// while arm (d) was a slot; with four, the rotation's offset by cell is what balances the order. `ios/Benchmark/README.md`, *The run count*,
    /// argues it against the simulator's real time; `ios/Benchmark/bench` passes the same number.
    static let defaultRunsPerCell = 3

    /// The report's file name: the Android reports' own.
    static let reportFileName = "baseline-report.md"

    /// Plays the matrix and writes ``reportFileName`` and `traces/` into ``output``, returning the report.
    func run(log: (String) -> Void = { print($0) }) async throws -> MatrixReport {
        var sessions: [CellKey: [[TelemetryEvent]]] = [:]
        for run in 0..<runs {
            var position = 0
            for scenario in Scenario.allCases {
                for network in MatrixNetwork.profiles {
                    let keys = Arm.allCases.map {
                        CellKey(scenario: scenario, network: network, arm: $0)
                    }
                    .filter { key in cellFilter.map { "\(key)".contains($0) } ?? true }
                    guard !keys.isEmpty else { continue }
                    // Rotated by the run, so a cell's arms take each position once over as many runs
                    // as there are arms, and by the cell, so no arm opens every cell of a run.
                    let rotation = (run + position) % keys.count
                    position += 1
                    for key in Array(keys[rotation...] + keys[..<rotation]) {
                        let events = try await SessionRunner(key: key).play()
                        sessions[key, default: []].append(events)
                        log("run \(run + 1)/\(runs) \(key): \(Self.summary(events))")
                    }
                }
            }
        }
        let traces = output.appendingPathComponent("traces")
        try FileManager.default.createDirectory(at: traces, withIntermediateDirectories: true)
        var cells: [CellResult] = []
        for (key, runs) in sessions.sorted(by: { "\($0.key)" < "\($1.key)" }) {
            try TraceWriter.write(key, runs: runs, into: traces)
            cells.append(
                CellResult(
                    key: key,
                    all: runs.map { events in
                        SessionMetrics.from(
                            sessionId: events.first?.sessionId ?? "", events: events)
                    }))
        }
        let report = MatrixReport(
            conditions: .fromEnvironment(runsPerCell: runs, cellFilter: cellFilter), cells: cells)
        try ReportWriter.write(report).write(
            to: output.appendingPathComponent(Self.reportFileName), atomically: true,
            encoding: .utf8)
        return report
    }

    /// One session's line of progress: what a person watching an hour-long run wants to see move.
    private static func summary(_ events: [TelemetryEvent]) -> String {
        let metrics = SessionMetrics.from(sessionId: events.first?.sessionId ?? "", events: events)
        let ttff = metrics.timeToFirstFrameMs.map { "\($0) ms" } ?? "none"
        let bitrate = metrics.averageBitrateBps.map { "\(Int($0)) bit/s" } ?? "none"
        return "ttff \(ttff), \(metrics.rebufferCount) stall(s), bitrate \(bitrate), "
            + "\(metrics.switchCount) switch(es)\(metrics.usable ? "" : ", EXCLUDED")"
    }
}
