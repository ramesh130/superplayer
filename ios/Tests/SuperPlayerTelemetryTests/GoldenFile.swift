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

/// The check-or-update comparison a golden test calls — the Kotlin testkit's `GoldenFile`, on the
/// same contract as `docs/api-surface.md`'s: **nothing regenerates a golden implicitly**.
///
/// In check mode, the default and what `ios/check` runs, a difference fails with the diff in the
/// message, as does a golden that does not exist. In update mode the golden is written and the
/// test passes; the mode is `SP_UPDATE_GOLDEN=1` in the test process's environment, which
/// `ios/check --update-golden` and nothing else sets — `xcodebuild` hands a variable named
/// `TEST_RUNNER_<NAME>` to the test runner as `<NAME>`. The goldens live in
/// `ios/Tests/Fixtures/golden/`, beside the other fixtures, read and written through `#filePath`
/// as the fixture tests read theirs; the simulator's test process runs on the host's file system.
///
/// In this test target rather than in `SuperPlayerTestkit`, where the Kotlin one is, because one
/// product calls it: putting it on testkit's tracked API surface waits for the second — the
/// session bundle's, #429.
enum GoldenFile {

    static let directory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Fixtures/golden")

    static var isUpdating: Bool {
        ProcessInfo.processInfo.environment["SP_UPDATE_GOLDEN"] == "1"
    }

    /// Compares `text` with the golden `name`.trace, or rewrites it in update mode.
    static func check(
        _ name: String, _ text: String, file: StaticString = #filePath, line: UInt = #line
    ) throws {
        let url = directory.appendingPathComponent("\(name).trace")
        if isUpdating {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true)
            try text.write(to: url, atomically: true, encoding: .utf8)
            return
        }
        guard let golden = try? String(contentsOf: url, encoding: .utf8) else {
            XCTFail(
                "No golden at \(url.path); run ios/check --update-golden, review the file, and "
                    + "commit it", file: file, line: line)
            return
        }
        guard golden != text else { return }
        XCTFail(
            "\(name).trace differs from what the session now records. If the change is "
                + "deliberate, run ios/check --update-golden and commit the diff with it.\n"
                + diff(golden, text), file: file, line: line)
    }

    /// A line diff, `-` the golden's and `+` the session's, in the order the golden reads.
    static func diff(_ golden: String, _ actual: String) -> String {
        let before = golden.components(separatedBy: "\n")
        let after = actual.components(separatedBy: "\n")
        let changes = after.difference(from: before)
        var removed = Set<Int>()
        var inserted: [Int: String] = [:]
        for change in changes {
            switch change {
            case .remove(let offset, _, _): removed.insert(offset)
            case .insert(let offset, let element, _): inserted[offset] = element
            }
        }
        var lines: [String] = []
        var newIndex = 0
        for (oldIndex, line) in before.enumerated() {
            while let added = inserted[newIndex] {
                lines.append("+ \(added)")
                newIndex += 1
            }
            if removed.contains(oldIndex) {
                lines.append("- \(line)")
            } else {
                lines.append("  \(line)")
                newIndex += 1
            }
        }
        while let added = inserted[newIndex] {
            lines.append("+ \(added)")
            newIndex += 1
        }
        return lines.joined(separator: "\n")
    }
}
