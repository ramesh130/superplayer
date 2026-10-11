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

/// What a matrix ran on — the Kotlin `RunConditions`, for its reason: a number without its
/// conditions cannot be compared to anything.
///
/// The commit, the tree's state, Xcode and the host are the runner script's to read and pass in
/// (`ios/Benchmark/bench`), because a process on the simulator can run no `git` and cannot see the
/// host's toolchain; the OS is read here, since the simulator's runtime is what the players ran on.
struct RunConditions: Sendable {
    let commit: String
    let treeDirty: Bool
    let xcode: String
    let host: String
    let simulator: String
    let runsPerCell: Int
    let startedAt: String
    /// The cell filter the run was given, or nil for the whole matrix.
    let cellFilter: String?

    /// The conditions as the runner script passed them through the test runner's environment, every
    /// missing value saying so rather than defaulting to something plausible.
    static func fromEnvironment(runsPerCell: Int, cellFilter: String?) -> RunConditions {
        let environment = ProcessInfo.processInfo.environment
        let unknown = "unknown (not passed by ios/Benchmark/bench)"
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        let device = environment["SIMULATOR_MODEL_IDENTIFIER"] ?? "unknown device"
        return RunConditions(
            commit: environment["SP_BENCH_COMMIT"] ?? unknown,
            treeDirty: environment["SP_BENCH_DIRTY"] != "0",
            xcode: environment["SP_BENCH_XCODE"] ?? unknown,
            host: environment["SP_BENCH_HOST"] ?? unknown,
            simulator: "iOS \(os), \(device)",
            runsPerCell: runsPerCell,
            startedAt: ISO8601DateFormatter().string(from: Date()),
            cellFilter: cellFilter)
    }
}
