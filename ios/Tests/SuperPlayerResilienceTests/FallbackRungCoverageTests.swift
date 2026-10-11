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
import SuperPlayerResilience
import XCTest

/// The register of the ladder on Apple platforms (#421): for each of ``FallbackRung``'s six rungs,
/// either the test that *forces* it and the test that counts what a player without resilience pays
/// for it, or why the rung has none here — the Kotlin `FallbackRungCoverageTest`, with the rows it
/// cannot have written down rather than left out.
///
/// **Rungs 1 to 3 are not ported, by rule**, not by omission: AVFoundation retries a request, moves
/// to a redundant stream and drops a failing variant inside the engine, and nothing configures any
/// of it (ADR-0020 rule 6) — so there is no SuperPlayer code on this platform for a test to force,
/// and a test that played a 500 which relented would be testing AVFoundation. Their rows say so, and
/// a rung moved from "not ported" to "forced" is a row edited here in the change that ports it.
///
/// The tests are collected as names, and the names are checked against the source tree, for the
/// Kotlin reason: a renamed test fails here rather than leaving a rung silently unforced, and a rung
/// added to ``FallbackRung`` with no row fails ``testEveryRungHasARowInLadderOrder`` the day it is
/// added. The sources are read from the host through `#filePath`, as `TelemetrySchemaDocumentTests`
/// reads its document. It asserts nothing about playback: what each named test proves is that
/// test's business.
final class FallbackRungCoverageTests: XCTestCase {

    /// A test by its target, class and method — the class named for its file, as every test file in
    /// this repository is.
    struct TestMethod: CustomStringConvertible {
        let target: String
        let className: String
        let methodName: String

        var sourceFile: URL {
            FallbackRungCoverageTests.testsDirectory
                .appendingPathComponent(target).appendingPathComponent("\(className).swift")
        }

        var description: String { "\(className).\(methodName)" }
    }

    /// What is claimed about one rung.
    enum Coverage {
        /// The test that forces the rung, and the one that counts its absence (ADR-0011 rule 14).
        case forced(by: TestMethod, absenceCountedBy: TestMethod)
        /// Why this platform has no code of SuperPlayer's at the rung, and so no test of it.
        case notPorted(because: String)
    }

    /// `ios/Tests/`, from this file.
    static let testsDirectory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()

    /// Rungs 1 to 3's reason, once: the same rule keeps all three AVFoundation's.
    static let theEnginesOwn =
        "AVFoundation's own, inside the engine, and nothing configures it (ADR-0020 rule 6)"

    static let coverage: [(FallbackRung, Coverage)] = [
        // Rung 1: AVFoundation retries a failed request itself — a segment refused 404 or 500 is
        // asked for several times before the item fails (observed, #419).
        (.retrySameUrl, .notPorted(because: theEnginesOwn)),
        // Rung 2: a redundant stream on another host, which AVFoundation moves to unaided
        // (`MultiHostOriginTests` records it); content steering is the server's.
        (.nextHost, .notPorted(because: theEnginesOwn)),
        // Rung 3: AVFoundation drops a variant that keeps failing and plays another.
        (.excludeVariant, .notPorted(because: theEnginesOwn)),
        // Rung 4: the second entry of `MediaRequest.sources`, opened by core.
        (
            .nextSource,
            .forced(
                by: resilience(
                    "NextSourcePlaybackTests",
                    "testARefusedFirstSourceFallsToTheSecondAtTheHeldPosition"),
                absenceCountedBy: resilience(
                    "NextSourcePlaybackTests",
                    "testAPlayerWithoutResilienceOpensTheFirstSourceAndNothingElse"))
        ),
        // Rung 5: the item rebuilt where it stood after the media services were reset.
        (
            .recreateDecoder,
            .forced(
                by: resilience(
                    "MediaServicesResetPlaybackTests",
                    "testAResetIsRepairedTwiceAtOnePositionAndTheThirdEndsOnRungSix"),
                absenceCountedBy: resilience(
                    "MediaServicesResetPlaybackTests", "testAPlayerWithoutResilienceRebuildsNothing"
                ))
        ),
        // Rung 6: a session nothing rescued, ending on a `SuperPlayerError` on `player.errors`.
        (
            .typedError,
            .forced(
                by: resilience("TypedErrorPlaybackTests", "testASegmentRefused404EndsOnTheCdnEdge"),
                absenceCountedBy: resilience(
                    "TypedErrorPlaybackTests", "testAPlayerWithoutResilienceClassifiesNothing"))
        ),
    ]

    static func resilience(_ className: String, _ methodName: String) -> TestMethod {
        TestMethod(
            target: "SuperPlayerResilienceTests", className: className, methodName: methodName)
    }

    func testEveryRungHasARowInLadderOrder() {
        // The roster is the assertion: a rung added to the ladder with no row fails here.
        XCTAssertEqual(Self.coverage.map(\.0), FallbackRung.allCases)
    }

    func testEveryNamedTestExistsAndIsATest() throws {
        for (rung, coverage) in Self.coverage {
            guard case .forced(let forces, let countsAbsence) = coverage else { continue }
            for named in [forces, countsAbsence] {
                let source = try String(contentsOf: named.sourceFile, encoding: .utf8)
                XCTAssertTrue(
                    source.contains("final class \(named.className): XCTestCase"),
                    "\(rung): \(named.sourceFile.path) declares no test class \(named.className)")
                // XCTest runs an instance method whose name begins `test`; one renamed away from
                // that prefix still exists and is no longer run, which is how a rung would stop
                // being forced without anyone noticing.
                XCTAssertTrue(named.methodName.hasPrefix("test"), "\(rung): \(named)")
                XCTAssertTrue(
                    source.contains("    func \(named.methodName)("), "\(rung): \(named)")
            }
        }
    }

    func testOnlyTheRungsTheEngineOwnsAreNotPortedAndEachSaysWhy() {
        let notPorted = Self.coverage.compactMap { rung, coverage -> FallbackRung? in
            guard case .notPorted(let reason) = coverage else { return nil }
            XCTAssertTrue(reason.contains("ADR-0020 rule 6"), "\(rung): \(reason)")
            return rung
        }
        XCTAssertEqual(notPorted, [.retrySameUrl, .nextHost, .excludeVariant])
    }
}
