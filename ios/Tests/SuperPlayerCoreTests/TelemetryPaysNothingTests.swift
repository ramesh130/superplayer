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

// `@testable` for `collector`, `measurement` and `liveObservations`: what a player holds for
// telemetry is invisible through the public API until a collector reports it, and a player without
// one has nobody to report it — the same argument as `ObservedSignalRegisterTests`'.
@testable import SuperPlayerCore

/// ADR-0008 rule 2 and ADR-0020 rule 13 for telemetry: a player configured without a collector
/// makes none, mints no session id and registers nothing for one — counted, as rule 13 asks, once
/// with nothing configured and once with a hand-written stand-in, so the counter is shown to see what
/// it counts. No item here ever loads: the URL names a port nothing listens on, and every edge is
/// made when an item is set, not when it plays.
@MainActor
final class TelemetryPaysNothingTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let request = MediaRequest(
        contentId: "a", sources: [URL(string: "http://127.0.0.1:9/never.m3u8")!])

    func testAPlayerConfiguredWithoutTelemetryHoldsAndMintsNothing() {
        let player = SuperPlayer()
        player.setMediaRequest(request)

        XCTAssertNil(player.collector)
        XCTAssertNil(player.measurement, "no session to mint an id for")
        player.release()
    }

    func testAStandInCollectorIsMadeOnceAndItsSessionIsHeld() {
        let made = Count()
        let standIn = StandInCollector()
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = {
            made.value += 1
            return standIn
        }

        let player = SuperPlayer(configuration: configuration)
        player.setMediaRequest(request)

        XCTAssertEqual(made.value, 1)
        XCTAssertEqual(standIn.attached, 1)
        XCTAssertEqual(player.measurement?.current?.contentId, "a")
        player.release()
        XCTAssertNil(player.measurement?.current)
    }

    /// Core itself observes nothing *for* telemetry: what is registered is the collector's to
    /// register, so the facade's own register reads the same with and without one.
    func testCoreRegistersNothingMoreForACollector() {
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { StandInCollector() }
        let measured = SuperPlayer(configuration: configuration)
        let unmeasured = SuperPlayer()

        measured.setMediaRequest(request)
        unmeasured.setMediaRequest(request)

        XCTAssertEqual(measured.liveObservations, unmeasured.liveObservations)
        measured.release()
        unmeasured.release()
    }
}

@MainActor
private final class Count {
    var value = 0
}

/// A hand-written collector that counts what it is told.
@MainActor
private final class StandInCollector: TelemetryCollector {
    var attached = 0
    func attach(to player: SuperPlayer) { attached += 1 }
    func startSession(contentId: String, sessionId: String) {}
    func endSession() {}
    func declareIntent(monotonicTimeMs: Int64) {}
    func decisionChanged(_ decision: PlaybackDecision, trigger: DecisionTrigger) {}
    func detach() {}
}
