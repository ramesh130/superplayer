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

// `@testable` for the register and `liveObservations`, the internal seam core's own register test
// reads for the same reason: a registration is invisible through any public API until what it
// waits for arrives.
@testable import SuperPlayerTelemetry

/// The collector's observed register (ADR-0020 rule 13), held as core's is by
/// `ObservedSignalRegisterTests`: nothing registered without a session, each signal once with
/// one, none again once it ends — however many sessions came before — and no registration call
/// anywhere in the product but the register's own file.
///
/// No item here ever loads: the URL names a port nothing listens on, and a session's registrations
/// are made as it opens, not as its content plays. What each signal is *for* is asserted through
/// the harness in `QoeCollectorPlaybackTests`.
@MainActor
final class CollectorSignalRegisterTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private var everySignalOnce: [CollectorSignal: Int] {
        Dictionary(uniqueKeysWithValues: CollectorSignal.registered.map { ($0, 1) })
    }

    private func measured(drm: (any PlaybackDrm)? = nil) -> (SuperPlayer, QoeCollector) {
        let collector = QoeCollector(sink: RecordingSink())
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { collector }
        configuration.drm = drm
        return (SuperPlayer(configuration: configuration), collector)
    }

    /// The control: an attached collector with no session open has registered nothing, so the count
    /// is not one that reads "everything" regardless.
    func testACollectorWithNoSessionObservesNothing() {
        let (player, collector) = measured()

        XCTAssertEqual(collector.liveObservations, [:])
        XCTAssertFalse(collector.isSampling)
        player.release()
    }

    func testEverySignalIsObservedOnceWhileASessionIsOpen() {
        let (player, collector) = measured()

        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))

        XCTAssertEqual(collector.liveObservations, everySignalOnce)
        XCTAssertTrue(collector.isSampling)
        player.release()
    }

    /// The defect a register exists for: an outgoing session's registrations never removed.
    func testReplacingTheContentLeavesOneOfEach() {
        let (player, collector) = measured()

        for contentId in ["a", "b", "c", "a"] {
            player.setMediaRequest(MediaRequest(contentId: contentId, sources: [nowhere]))
        }

        XCTAssertEqual(collector.liveObservations, everySignalOnce)
        player.release()
    }

    /// Content with no identity opens no session, so nothing of the collector's is left on it; and
    /// a released player has nothing of the collector's either.
    func testABareItemAndAReleaseLeaveNothingRegistered() {
        let (player, collector) = measured()
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))

        player.setMediaURL(nowhere)
        XCTAssertEqual(collector.liveObservations, [:])

        XCTAssertFalse(collector.isSampling)

        player.setMediaRequest(MediaRequest(contentId: "b", sources: [nowhere]))
        player.release()
        XCTAssertEqual(collector.liveObservations, [:])
        XCTAssertFalse(collector.isSampling, "a session's end stops its timer")
    }

    /// The output's protection reading is observed on a protected player alone (#422): a clear
    /// player's session — every test above — registers none, and a protected one's one of each.
    func testTheOutputIsObservedOnAProtectedPlayerAlone() {
        let (player, collector) = measured(drm: Protected())

        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))

        XCTAssertEqual(
            collector.liveObservations, everySignalOnce.merging([.outputObscured: 1]) { $1 })
        XCTAssertNil(everySignalOnce[.outputObscured])
        player.release()
        XCTAssertEqual(collector.liveObservations, [:])
    }

    /// The metric stream is iOS 18's, so below it the register expects one fewer — and on this
    /// runtime, which is newer, it expects all of them but the protected player's own.
    func testTheMetricStreamIsRegisteredWhereItExists() {
        if #available(iOS 18, tvOS 18, *) {
            XCTAssertEqual(
                CollectorSignal.registered(protected: true), CollectorSignal.allCases)
        } else {
            XCTAssertFalse(CollectorSignal.registered.contains(.metricEvents))
        }
    }

    /// "Nowhere else", held mechanically, as core's register test holds it: every registration
    /// call in this product's sources is in a register's own file — this one's, or the session
    /// trace recorder's, which `RecorderSignals.swift` argues is a register of its own.
    func testNoTelemetrySourceButARegisterRegistersAnObservation() throws {
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/SuperPlayerTelemetry")
        let files = try FileManager.default.contentsOfDirectory(
            at: sources, includingPropertiesForKeys: nil
        ).filter { $0.pathExtension == "swift" }
        XCTAssertTrue(
            files.contains { $0.lastPathComponent == "CollectorSignals.swift" },
            "the scan did not find the product's sources at \(sources.path)")

        let registrations = [
            "addObserver(", ".observe(", "addPeriodicTimeObserver(", "addBoundaryTimeObserver(",
            "metrics(forType:", "allMetrics(",
        ]
        let registers: Set = ["CollectorSignals.swift", "RecorderSignals.swift"]
        XCTAssertTrue(
            files.contains { $0.lastPathComponent == "RecorderSignals.swift" },
            "the recorder's register moved; this scan names it")
        for file in files where !registers.contains(file.lastPathComponent) {
            let text = try String(contentsOf: file, encoding: .utf8)
            for call in registrations {
                XCTAssertFalse(
                    text.contains(call),
                    "\(file.lastPathComponent) calls \(call) outside a register")
            }
        }
    }
}

/// A player's declared protection, as far as the collector can tell: set, and nothing more.
private struct Protected: PlaybackDrm {}
