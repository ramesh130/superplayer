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

/// The check-or-update comparison a golden test calls — the Kotlin testkit's `GoldenFile`, on the
/// same contract as `docs/api-surface.md`'s: **nothing regenerates a golden implicitly**.
///
/// ```swift
/// let goldens = GoldenFile(directory: fixtures.appendingPathComponent("golden"))
/// try goldens.check("hls-vod", trace.structural().format())
/// ```
///
/// In check mode, the default and what `ios/check` runs, a difference throws
/// ``GoldenFileMismatchError`` with the diff in its message, as does a golden that does not exist.
/// In update mode the golden is written and nothing is thrown; the mode is `SP_UPDATE_GOLDEN=1` in
/// the test process's environment, which `ios/check --update-golden` and nothing else sets —
/// `xcodebuild` hands a variable named `TEST_RUNNER_<NAME>` to the test runner as `<NAME>`. A golden
/// is `<name>.trace` in ``directory``; the simulator's test process runs on the host's file system,
/// so a test names its directory through `#filePath`.
///
/// In testkit since its second caller, the session bundle's (#429) — #411 kept it in the telemetry
/// tests until then. It **throws** rather than calling a test framework, for
/// ``FrameSourceConformanceError``'s reason: testkit names no test framework, and a thrown error is a
/// failure to XCTest and Swift Testing alike.
public struct GoldenFile: Sendable {

    /// Where the goldens are read and written.
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    /// Whether this run rewrites goldens rather than checking them.
    public static var isUpdating: Bool {
        ProcessInfo.processInfo.environment["SP_UPDATE_GOLDEN"] == "1"
    }

    /// The golden `name`.trace.
    public func url(of name: String) -> URL {
        directory.appendingPathComponent("\(name).trace")
    }

    /// Compares `text` with the golden `name`.trace, or rewrites it in update mode.
    public func check(_ name: String, _ text: String) throws {
        let url = url(of: name)
        if Self.isUpdating {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true)
            try text.write(to: url, atomically: true, encoding: .utf8)
            return
        }
        guard let golden = try? String(contentsOf: url, encoding: .utf8) else {
            throw GoldenFileMismatchError(
                "No golden at \(url.path); run ios/check --update-golden, review the file, and "
                    + "commit it")
        }
        guard golden != text else { return }
        throw GoldenFileMismatchError(
            "\(name).trace differs from what the session now records. If the change is "
                + "deliberate, run ios/check --update-golden and commit the diff with it.\n"
                + Self.diff(golden, text))
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

/// A golden that differs from what was recorded, or that does not exist, from
/// ``GoldenFile/check(_:_:)``: the message carries the diff. A plain `Error`, so whichever test
/// framework runs the check reports it as the failure.
public struct GoldenFileMismatchError: Error, CustomStringConvertible, LocalizedError {

    /// The message, the diff included.
    public let message: String

    init(_ message: String) { self.message = message }

    public var description: String { message }
    public var errorDescription: String? { message }
}
