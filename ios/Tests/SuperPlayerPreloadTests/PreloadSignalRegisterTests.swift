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
import XCTest

// `@testable` for the register's process-wide count, which no public API can show: a registration
// is invisible until what it waits for arrives.
@testable import SuperPlayerPreload

/// The coordinator's observed register (ADR-0020 rule 13) and ADR-0010 rule 13's count: a pool with
/// no coordinator registers nothing for preload — however it is used — while one with a coordinator
/// registers each ``PreloadSignal/Scope/coordinator`` signal once, the counter shown to see what it
/// counts, and none once the coordinator is released; and no file in the product but the register's
/// calls a registration API.
///
/// No item here loads: the URL names a port nothing listens on, and the counts are of
/// registrations, not of content. What each rule *does* is `PreloadCoordinatorPlaybackTests`'.
@MainActor
final class PreloadSignalRegisterTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    func testAPoolWithNoCoordinatorRegistersNothingForPreload() {
        let pool = PlayerPool()
        let player = pool.acquire()
        player?.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        if let player { pool.recycle(player) }

        XCTAssertEqual(PreloadObservation.live, [:])
        pool.release()
    }

    func testACoordinatorRegistersEachRuleOnceUntilItIsReleased() {
        let pool = PlayerPool()
        let preload = PreloadCoordinator(pool: pool)
        preload.setItems([MediaRequest(contentId: "a", sources: [nowhere])])
        preload.setScrollPosition(index: 0)

        XCTAssertEqual(PreloadObservation.live, [.memoryWarning: 1, .networkPath: 1])
        preload.release()
        XCTAssertEqual(PreloadObservation.live, [:])
        pool.release()
    }

    /// Releasing the pool releases its coordinator, so a feed that lets go of the pool alone leaves
    /// nothing listening.
    func testReleasingThePoolReleasesItsCoordinator() {
        let pool = PlayerPool()
        let preload = PreloadCoordinator(pool: pool)

        pool.release()

        XCTAssertEqual(PreloadObservation.live, [:])
        XCTAssertEqual(preload.warmDecoderCount, 0)
    }

    /// "Nowhere else", held mechanically, as the other registers' tests hold it.
    func testNoPreloadSourceButTheRegisterRegistersAnObservation() throws {
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/SuperPlayerPreload")
        let files = try FileManager.default.contentsOfDirectory(
            at: sources, includingPropertiesForKeys: nil
        ).filter { $0.pathExtension == "swift" }
        XCTAssertTrue(
            files.contains { $0.lastPathComponent == "PreloadSignals.swift" },
            "the scan did not find the product's sources at \(sources.path)")

        let registrations = [
            "addObserver(", ".observe(", "addPeriodicTimeObserver(", "addBoundaryTimeObserver(",
            "NWPathMonitor(",
        ]
        for file in files where file.lastPathComponent != "PreloadSignals.swift" {
            let text = try String(contentsOf: file, encoding: .utf8)
            for call in registrations {
                XCTAssertFalse(
                    text.contains(call),
                    "\(file.lastPathComponent) calls \(call) outside the register")
            }
        }
    }
}
