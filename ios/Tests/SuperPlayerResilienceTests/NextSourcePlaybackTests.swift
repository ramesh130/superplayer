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

import AVFoundation
import Combine
import SuperPlayerCore
import SuperPlayerResilience
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest

/// Rung 4 through the harness (#420): a request whose first source is refused falls to its second,
/// at the position playback held, as the same content and the same measurement session, with the
/// consumer told nothing — the Kotlin `NextSourcePlaybackTest`'s role. The two controls keep it from
/// being "always open the next source": a request with one source ends on rung 6 where it always
/// did, and a player without resilience never opens the second.
///
/// The two sources are two origins, so which one served a request is which origin recorded it. The
/// request starts at ``startMs``, so "at the held position" is a fact about *which segment* the
/// second origin was first asked for — nothing here reads a clock.
@MainActor
final class NextSourcePlaybackTests: XCTestCase {

    /// A ceiling on every wait, never an assertion: a 403 fails an item at once (#419), and the
    /// fallback then plays a few seconds of real time.
    private let bound: Duration = .seconds(60)

    /// Inside the third segment of two-second segments, so the first segment asked for is
    /// `segment2.aac` — distinct-resource index 0 — and the one refused, `segment3.aac`, index 1.
    private let startMs: Int64 = 5_000

    private let harness = PlaybackHarness()
    private var players: [SuperPlayer] = []
    private var subscriptions: Set<AnyCancellable> = []

    override func tearDown() async throws {
        subscriptions = []
        for player in players { player.release() }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    /// The first source, its second segment refused 403 on every attempt.
    private func refusedFirstSource() async throws -> LoopbackOrigin {
        try await harness.serve(
            .hls(segmentCount: 8),
            faults: FaultScript.Builder()
                .failWithHttpStatus(FaultScript.httpForbidden, kind: .mediaSegment, index: 1)
                .build())
    }

    private func play(
        _ sources: [URL], resilient: Bool, sink: RecordingSink? = nil
    ) -> (SuperPlayer, ErrorRecord) {
        var configuration = SuperPlayer.Configuration()
        if resilient { configuration.resilience = Resilience.standard() }
        if let sink { configuration.telemetry = { QoeCollector(sink: sink) } }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        let record = ErrorRecord()
        player.errors.sink { error in MainActor.assumeIsolated { record.errors.append(error) } }
            .store(in: &subscriptions)
        player.setMediaRequest(
            MediaRequest(contentId: "a", sources: sources, startPosition: .at(positionMs: startMs)))
        player.avPlayer.play()
        return (player, record)
    }

    /// The acceptance: the first source refused, the second opened at the held position, one session.
    func testARefusedFirstSourceFallsToTheSecondAtTheHeldPosition() async throws {
        let first = try await refusedFirstSource()
        let second = try await harness.serve(.hls(segmentCount: 8))
        let sink = RecordingSink()
        let (player, record) = play(
            [first.sourceURL, second.sourceURL], resilient: true, sink: sink)

        try await harness.until("the second source plays past the held position", timeout: bound) {
            // Which source plays is which origin served it, never the item the facade made.
            second.networkRequests().contains { $0.path == "/segment4.aac" }
                && player.avPlayer.currentTime().seconds > 6.5
        }

        XCTAssertTrue(
            first.responses().contains { $0.request.path == "/segment3.aac" && $0.status == 403 })
        // At the held position: the second source is never asked for the content before it.
        let secondSegments = second.networkRequests().filter { $0.kind == .mediaSegment }
        XCTAssertEqual(secondSegments.first?.path, "/segment2.aac", "\(secondSegments)")
        XCTAssertFalse(
            secondSegments.contains { ["/segment0.aac", "/segment1.aac"].contains($0.path) })
        // The same content, and nothing told to the consumer (ADR-0011 rule 10).
        XCTAssertEqual(player.currentContentId, "a")
        XCTAssertTrue(record.errors.isEmpty, "\(record.errors)")
        XCTAssertNil(player.playerError)
        // One session, its repaired failure still reported with its class (rule 3).
        XCTAssertEqual(sink.sessionsStarted.count, 1)
        XCTAssertEqual(sink.sessionsEnded, 0)
        try await harness.until("the repaired failure is delivered", timeout: bound) {
            sink.failures.count == 1
        }
        XCTAssertEqual(sink.failures.first?.classification, "Transient.CdnEdge")
    }

    /// The control: a request with one source ends where it always did, on rung 6, having tried no
    /// rung — so the fallback above is the second source's and not a change to every failure.
    func testARequestWithOneSourceEndsOnTheTypedError() async throws {
        let first = try await refusedFirstSource()
        let (player, record) = play([first.sourceURL], resilient: true)

        try await harness.until("the typed error arrives", timeout: bound) {
            !record.errors.isEmpty
        }

        XCTAssertEqual(record.errors.count, 1)
        let typed = try XCTUnwrap(record.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Transient.CdnEdge")
        XCTAssertEqual(typed.rungsTried, [])
        XCTAssertEqual(player.avPlayer.currentItem?.status, .failed)
    }

    /// ADR-0011 rule 14: without resilience the first source fails as on a stock player, and the
    /// second is never asked for anything.
    func testAPlayerWithoutResilienceOpensTheFirstSourceAndNothingElse() async throws {
        let first = try await refusedFirstSource()
        let second = try await harness.serve(.hls(segmentCount: 8))
        let (player, record) = play([first.sourceURL, second.sourceURL], resilient: false)

        try await harness.until("the item fails", timeout: bound) {
            player.avPlayer.currentItem?.status == .failed
        }

        XCTAssertEqual(second.networkRequests().count, 0)
        XCTAssertTrue(record.errors.isEmpty)
    }
}
