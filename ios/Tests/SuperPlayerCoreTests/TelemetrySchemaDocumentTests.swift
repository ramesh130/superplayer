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

/// `docs/telemetry-schema.md` against the Swift vocabulary, in both directions (#408, ADR-0021's
/// shared-document rule): every event the document's *The events* table names exists as a
/// ``TelemetryEvent`` payload carrying exactly the fields that row lists, and every payload is a row.
/// `superplayer-core`'s `TelemetrySchemaDocumentTest` holds the Kotlin types to the same table, so
/// the two platforms agree because each agrees with the document.
///
/// The document is read from the source tree by `#filePath`, as `SyntheticHlsFixturesTests` reads
/// its fixtures: a simulator test process reads the host's file system, and a copy bundled as a
/// resource would be a second place for the table to go stale.
///
/// The Swift side is read by reflection over one sample of each case. A case added to
/// ``TelemetryEvent`` stops ``payload(of:)`` compiling until it has a ``Case`` of its own, and
/// `testThereIsASampleOfEveryCase` then fails until it has a sample — so no case can be added
/// that this test does not read.
final class TelemetrySchemaDocumentTests: XCTestCase {

    /// The four fields every event carries, which the table leaves out of each row's *Carries*.
    private let common: Set<String> = ["sessionId", "contentId", "timestampMs", "monotonicTimeMs"]

    func testEveryDocumentedEventAndFieldIsInTheVocabularyAndNothingElse() throws {
        let documented = try SchemaDocument.events()
        // A guard on the parser rather than a second authority on the count: a table it read none of
        // would otherwise compare as "nothing documented" and fail less legibly.
        XCTAssertEqual(
            documented.count, 15,
            "the parser found \(documented.count) rows; the table has fifteen events")

        XCTAssertEqual(Self.differences(documented: documented, carried: carried()), [])
    }

    func testThereIsASampleOfEveryCase() {
        XCTAssertEqual(Set(Self.samples.map { Self.payload(of: $0).case }), Set(Case.allCases))
    }

    func testEveryPayloadCarriesTheFourCommonFieldsUnderTheirNames() {
        for event in Self.samples {
            let payload = Self.payload(of: event).payload
            let fields = Set(Mirror(reflecting: payload).children.compactMap(\.label))
            XCTAssertTrue(
                common.isSubset(of: fields), "\(type(of: payload)) carries \(fields.sorted())")
        }
    }

    /// The control: the comparison is one that reports a difference in each direction, so an empty
    /// answer above is a reading and not a comparison that cannot fail.
    func testTheComparisonReportsAFieldOrAnEventMissingFromEitherSide() {
        let carried = ["SessionEnded": Set(["droppedEventCount", "securityLevel"])]
        XCTAssertEqual(
            Self.differences(
                documented: ["SessionEnded": ["droppedEventCount"], "Invented": []],
                carried: carried),
            [
                "Invented is documented and not in the vocabulary",
                "SessionEnded.securityLevel is carried and not documented",
            ])
    }

    func testTheSchemaVersionIsTheOneTheDocumentStates() throws {
        XCTAssertEqual(try SchemaDocument.currentVersion(), TelemetryEvent.schemaVersion)
        XCTAssertEqual(Self.samples.first?.schemaVersion, TelemetryEvent.schemaVersion)
    }

    /// The iOS derivation column exists and no row of it is empty: every event says where it comes
    /// from on iOS, or that nothing emits it yet and which ticket will (#409).
    func testEveryEventSaysHowItIsDerivedOnIos() throws {
        XCTAssertTrue(try SchemaDocument.eventTableHeader().contains("How derived on iOS"))
        let derivations = try SchemaDocument.iosDerivations()
        XCTAssertEqual(Set(derivations.keys), Set(try SchemaDocument.events().keys))
        for (event, cell) in derivations {
            XCTAssertFalse(cell.isEmpty, "\(event) does not say how it is derived on iOS")
        }
    }

    /// Each payload's own fields, by its type's name.
    private func carried() -> [String: Set<String>] {
        Dictionary(
            uniqueKeysWithValues: Self.samples.map { event in
                let payload = Self.payload(of: event).payload
                let fields = Mirror(reflecting: payload).children.compactMap(\.label)
                return ("\(type(of: payload))", Set(fields).subtracting(common))
            })
    }

    private static func differences(
        documented: [String: Set<String>], carried: [String: Set<String>]
    ) -> [String] {
        var found: [String] = []
        for (event, fields) in documented {
            guard let swift = carried[event] else {
                found.append("\(event) is documented and not in the vocabulary")
                continue
            }
            found += fields.subtracting(swift).map {
                "\(event).\($0) is documented and not carried"
            }
            found += swift.subtracting(fields).map {
                "\(event).\($0) is carried and not documented"
            }
        }
        found += carried.keys.filter { documented[$0] == nil }
            .map { "\($0) is carried and not documented" }
        return found.sorted()
    }

    /// One name per ``TelemetryEvent`` case, so that the samples can be held to all of them.
    private enum Case: CaseIterable {
        case sessionStarted, decisionChanged, sessionEnded, firstFrameRendered, rebufferStarted,
            rebufferEnded, startupFailed, midStreamFailed, trackSwitched, seekRequested,
            seekCompleted, licenceAcquisitionEnded, liveLatencySampled, playbackStateSampled,
            videoFramesDropped
    }

    /// The case and payload of `event`. Exhaustive on purpose: a new case does not compile here
    /// until it has a ``Case``, and ``samples`` must then carry one.
    private static func payload(of event: TelemetryEvent) -> (case: Case, payload: Any) {
        switch event {
        case .sessionStarted(let payload): (.sessionStarted, payload)
        case .decisionChanged(let payload): (.decisionChanged, payload)
        case .sessionEnded(let payload): (.sessionEnded, payload)
        case .firstFrameRendered(let payload): (.firstFrameRendered, payload)
        case .rebufferStarted(let payload): (.rebufferStarted, payload)
        case .rebufferEnded(let payload): (.rebufferEnded, payload)
        case .startupFailed(let payload): (.startupFailed, payload)
        case .midStreamFailed(let payload): (.midStreamFailed, payload)
        case .trackSwitched(let payload): (.trackSwitched, payload)
        case .seekRequested(let payload): (.seekRequested, payload)
        case .seekCompleted(let payload): (.seekCompleted, payload)
        case .licenceAcquisitionEnded(let payload): (.licenceAcquisitionEnded, payload)
        case .liveLatencySampled(let payload): (.liveLatencySampled, payload)
        case .playbackStateSampled(let payload): (.playbackStateSampled, payload)
        case .videoFramesDropped(let payload): (.videoFramesDropped, payload)
        }
    }

    /// One of each case; the values are arbitrary, since only the shape is read.
    private static let samples: [TelemetryEvent] = {
        let (s, c, t, m) = ("session", "content", Int64(1), Int64(2))
        let failure = PlaybackFailure(category: .network, code: nil, message: nil)
        return [
            .sessionStarted(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    profile: .videoOnDemand, decision: PlaybackDecision())),
            .decisionChanged(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    decision: PlaybackDecision(), trigger: .rebufferEnded)),
            .sessionEnded(.init(sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m)),
            .firstFrameRendered(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    timeToFirstFrameMs: 0, startBoundary: .contentAdopted)),
            .rebufferStarted(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    seekInduced: false)),
            .rebufferEnded(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    durationMs: 0, seekInduced: false)),
            .startupFailed(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    failure: failure)),
            .midStreamFailed(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    failure: failure, positionMs: 0)),
            .trackSwitched(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    fromBitrateBps: nil, toBitrateBps: 1, direction: .initial)),
            .seekRequested(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    fromPositionMs: 0, toPositionMs: 1)),
            .seekCompleted(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    toPositionMs: 1, seekLatencyMs: 0)),
            .licenceAcquisitionEnded(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    durationMs: 0, outcome: .acquiredFromServer)),
            .liveLatencySampled(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    liveLatencyMs: 0, targetLiveLatencyMs: nil)),
            .playbackStateSampled(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    samplingIntervalMs: 10_000, videoBitrateBps: nil, bufferedDurationMs: 0,
                    playing: false)),
            .videoFramesDropped(
                .init(
                    sessionId: s, contentId: c, timestampMs: t, monotonicTimeMs: m,
                    droppedFrames: 0, repeatedFrames: 0, elapsedPlayingMs: 0)),
        ]
    }()
}

/// `docs/telemetry-schema.md`, read from the source tree.
private enum SchemaDocument {

    /// Found from this file's own place: `ios/Tests/SuperPlayerCoreTests/` is three below the root.
    static let path = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "docs/telemetry-schema.md")

    /// The lines of the table under `## The events`, its header first.
    static func eventTable() throws -> [Substring] {
        let lines = try String(contentsOf: path, encoding: .utf8).split(
            separator: "\n", omittingEmptySubsequences: false)
        guard let heading = lines.firstIndex(of: "## The events") else {
            throw SchemaDocumentError.missing("the heading '## The events'")
        }
        let table = lines[heading...].drop { !$0.hasPrefix("|") }.prefix { $0.hasPrefix("|") }
        guard !table.isEmpty else { throw SchemaDocumentError.missing("the events table") }
        return Array(table)
    }

    static func eventTableHeader() throws -> Substring {
        try eventTable()[0]
    }

    /// Each row's event and the fields its *Carries* cell lists, every name read from between
    /// backticks.
    static func events() throws -> [String: Set<String>] {
        var events: [String: Set<String>] = [:]
        for row in try eventTable().dropFirst(2) {
            let cells = row.split(separator: "|", omittingEmptySubsequences: false)
                .map { $0.trimmingCharacters(in: .whitespaces) }
            // A row opens and closes with a pipe: cells[1] is the event, cells[3] what it carries.
            guard cells.count >= 4, let event = backticked(cells[1]).first else {
                throw SchemaDocumentError.missing("an event in the row '\(row)'")
            }
            events[event] = Set(backticked(cells[3]))
        }
        return events
    }

    /// Each row's event and its *How derived on iOS* cell, trimmed.
    static func iosDerivations() throws -> [String: String] {
        var derivations: [String: String] = [:]
        for row in try eventTable().dropFirst(2) {
            let cells = row.split(separator: "|", omittingEmptySubsequences: false)
                .map { $0.trimmingCharacters(in: .whitespaces) }
            guard cells.count >= 5, let event = backticked(cells[1]).first else {
                throw SchemaDocumentError.missing("an iOS derivation in the row '\(row)'")
            }
            derivations[event] = cells[4]
        }
        return derivations
    }

    /// The number the *Versioning* section states: "The current version is **2**."
    static func currentVersion() throws -> Int {
        let text = try String(contentsOf: path, encoding: .utf8)
        let marker = "The current version is **"
        guard let start = text.range(of: marker)?.upperBound,
            let end = text[start...].range(of: "**")?.lowerBound,
            let version = Int(text[start..<end])
        else { throw SchemaDocumentError.missing("the sentence stating the current version") }
        return version
    }

    private static func backticked(_ cell: String) -> [String] {
        cell.split(separator: "`", omittingEmptySubsequences: false).enumerated()
            .filter { $0.offset % 2 == 1 }.map { String($0.element) }
    }
}

private enum SchemaDocumentError: Error {
    case missing(String)
}
