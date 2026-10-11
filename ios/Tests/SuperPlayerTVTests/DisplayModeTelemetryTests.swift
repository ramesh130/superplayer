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

import SuperPlayerCore
import SuperPlayerTV
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest
import os

/// The display's mode reported to telemetry and never acted on (#426, `docs/ios-plan.md` §3.8): a
/// session's first reading as its content is asked for, then each switch beginning and ending and
/// each change of the viewer's *Match Content* settings, as `DisplayModeChanged` under the open
/// session through `QoeCollector` — read where a consumer reads it, at a sink. A reading heard again
/// unchanged is not a second event, and a player configured without an output reports none.
@MainActor
final class DisplayModeTelemetryTests: XCTestCase {

    private static let nowhere = URL(string: "http://127.0.0.1:9/a.m3u8")!

    private let harness = PlaybackHarness()
    private var players: [SuperPlayer] = []

    override func tearDown() async throws {
        for player in players { player.release() }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    private func player(output: (any PlaybackOutput)?, sink: DisplayModeSink) -> SuperPlayer {
        var configuration = SuperPlayer.Configuration()
        configuration.profile = .tvLeanback
        configuration.output = output
        configuration.telemetry = { QoeCollector(sink: sink) }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [Self.nowhere]))
        return player
    }

    private func until(_ sink: DisplayModeSink, reads expected: [String], _ description: String)
        async throws
    {
        try await harness.until(description, timeout: .seconds(10)) {
            sink.readings == expected
        }
    }

    func testTheSessionsReadingThenEachSwitchAndSettingsChangeAreReported() async throws {
        let display = StandInDisplay()
        let sink = DisplayModeSink()
        _ = player(output: TvOutput.standard(displays: display), sink: sink)
        try await until(sink, reads: ["idle matching"], "the session's first reading")

        display.isDisplayModeSwitchInProgress = true
        display.post(.modeSwitchStarted)
        display.isDisplayModeSwitchInProgress = false
        display.post(.modeSwitchEnded)
        display.isDisplayCriteriaMatchingEnabled = false
        display.post(.matchingSettingsChanged)
        try await until(
            sink, reads: ["idle matching", "switching matching", "idle matching", "idle off"],
            "the switch and the settings change")
        XCTAssertEqual(Set(sink.sessions).count, 1, "the readings left their session")
    }

    /// The control: a notification that changes nothing reported is not an event, and the next
    /// change still is — so the sink is shown to hear what it would have heard.
    func testAReadingHeardAgainUnchangedIsNotASecondEvent() async throws {
        let display = StandInDisplay()
        let sink = DisplayModeSink()
        _ = player(output: TvOutput.standard(displays: display), sink: sink)
        try await until(sink, reads: ["idle matching"], "the session's first reading")

        display.post(.matchingSettingsChanged)
        display.post(.modeSwitchEnded)
        display.isDisplayModeSwitchInProgress = true
        display.post(.modeSwitchStarted)
        try await until(sink, reads: ["idle matching", "switching matching"], "the one change")
    }

    func testAPlayerWithoutAnOutputReportsNoDisplay() async throws {
        let sink = DisplayModeSink()
        _ = player(output: nil, sink: sink)
        // Waited on the session's own start rather than on time: the reading would follow it.
        try await harness.until("the session started", timeout: .seconds(10)) { sink.started }
        let player = try XCTUnwrap(players.first)
        player.release()
        try await harness.until("the session ended", timeout: .seconds(10)) { sink.ended }
        XCTAssertEqual(sink.readings, [])
    }
}

/// The `DisplayModeChanged` events a collector delivered, as `idle`/`switching` and
/// `matching`/`off`, readable from any thread.
final class DisplayModeSink: TelemetrySink {

    private let recorded = OSAllocatedUnfairLock(initialState: [TelemetryEvent]())

    func onEvent(_ event: TelemetryEvent) {
        recorded.withLock { $0.append(event) }
    }

    private var changes: [TelemetryEvent.DisplayModeChanged] {
        recorded.withLock { events in
            events.compactMap { event in
                if case .displayModeChanged(let change) = event { return change }
                return nil
            }
        }
    }

    var readings: [String] {
        changes.map {
            "\($0.switchInProgress ? "switching" : "idle") "
                + "\($0.criteriaMatchingEnabled ? "matching" : "off")"
        }
    }

    var sessions: [String] { changes.map(\.sessionId) }

    var started: Bool {
        recorded.withLock { events in
            events.contains { if case .sessionStarted = $0 { true } else { false } }
        }
    }

    var ended: Bool {
        recorded.withLock { events in
            events.contains { if case .sessionEnded = $0 { true } else { false } }
        }
    }
}
