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
import XCTest

@testable import SuperPlayerBenchmark

/// The matrix run itself — a measurement and not a check, hosted in an XCTest only because that is
/// how a process reaches the simulator. `ios/Benchmark/bench` is the one thing that runs it: it
/// passes `SP_BENCH_OUT` and the rest through the test runner's environment, and `ios/check` names
/// this class in `-skip-testing`, so the hour of real time it costs never reaches the suite.
///
/// Without `SP_BENCH_OUT` it skips, so a person running the whole scheme from Xcode does not start
/// an hour of playback by accident.
///
/// It fails only when it could not measure — a session that threw, a report that could not be
/// written, a cell with no session at all — and never because of *what* it measured, the Kotlin
/// runner's rule (`benchmark/README.md`, *Why this is not a test*).
@MainActor
final class BenchmarkMatrixRun: XCTestCase {

    func testRunTheMatrix() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let out = environment["SP_BENCH_OUT"], !out.isEmpty else {
            throw XCTSkip("A measurement, run by ios/Benchmark/bench: SP_BENCH_OUT is not set")
        }
        let runs = environment["SP_BENCH_RUNS"].flatMap(Int.init) ?? 3
        let filter = environment["SP_BENCH_CELLS"].flatMap { $0.isEmpty ? nil : $0 }

        let report = try await MatrixRun(
            runs: runs, cellFilter: filter, output: URL(fileURLWithPath: out)
        ).run()

        XCTAssertFalse(report.cells.isEmpty, "the filter matched no cell")
        if filter == nil {
            // The completeness assertion the Kotlin runner makes: every built arm of every scenario
            // on every network, so a cell quietly missing cannot read as one nobody needed.
            let built = Arm.allCases.filter(\.isBuilt).count
            XCTAssertEqual(
                report.cells.count,
                Scenario.allCases.count * MatrixNetwork.profiles.count * built)
        }
        for cell in report.cells {
            XCTAssertEqual(
                cell.runs + cell.excludedSessions, runs, "\(cell.key) ran a session per run")
            XCTAssertEqual(cell.exitBeforeVideoStart, 0, "\(cell.key): the runner abandoned one")
        }
    }
}
