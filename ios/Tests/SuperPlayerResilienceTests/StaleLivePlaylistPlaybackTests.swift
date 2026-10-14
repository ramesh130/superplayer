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
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// A live playlist that stopped advancing ends the session on core's ``StaleLivePlaylistError``,
/// named by its likely cause (#421) — the Kotlin `TypedErrorPlaybackTest`'s
/// `aFrozenLivePlaylistIsReportedAsWhatItIsRatherThanAsAnUnspecifiedIoError`, on a platform where the
/// alternative is not an unspecified error but no error at all: a stock `AVPlayer` waits at the
/// frozen edge (`HostileManifestCorpusTests`' row for the same entry).
///
/// The stream is the corpus's `hls-cached-live-playlist` — a live media playlist that never
/// changes — served with its declared headers, the ten-minute cache rule, and without them, which is
/// an origin that stopped publishing: the same bytes, two likely causes, two classes. The controls
/// keep it from being "end every live stream" and "end every stall": a healthy live stream plays on
/// well past where a frozen one ends, and a player without resilience is left exactly as a stock one
/// is.
///
/// iOS 18 and later, where the answers are readable; the coarser judgement below 18 is
/// `StalePlaylistJudgementTests`', over values, since nothing here runs an older runtime.
@MainActor
final class StaleLivePlaylistPlaybackTests: XCTestCase {

    /// A ceiling on every wait, never an assertion: a frozen stream ends past two and a half target
    /// durations unmoved, at an answer AVFoundation's backed-off reloads deliver late.
    ///
    /// The entry is the corpus row ADR-0021 rule 4's #459 addendum calls host-dependent, and these
    /// tests do not inherit that: its four readings come from nineteen players at once on a slow
    /// host (never reaching the edge) and from waiting long enough to rejoin the timeline, while
    /// here one player is judged at the edge it reached, well before a rejoin — its #421 addendum.
    private let bound: Duration = .seconds(90)

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

    private func play(
        _ origin: LoopbackOrigin, resilient: Bool = true, sink: RecordingSink? = nil
    ) -> (SuperPlayer, ErrorRecord) {
        var configuration = SuperPlayer.Configuration()
        if resilient { configuration.resilience = Resilience.standard() }
        if let sink { configuration.telemetry = { QoeCollector(sink: sink) } }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        let record = ErrorRecord()
        player.errors.sink { error in MainActor.assumeIsolated { record.errors.append(error) } }
            .store(in: &subscriptions)
        player.setMediaRequest(MediaRequest(contentId: "live/news", sources: [origin.sourceURL]))
        player.avPlayer.play()
        return (player, record)
    }

    /// The frozen entry, with its declared cache rule served or withheld.
    private func frozen(servingItsCacheRule: Bool) async throws -> LoopbackOrigin {
        let entry = HostileManifests.hlsCachedLivePlaylist()
        let faults =
            servingItsCacheRule
            ? FaultScript.Builder().addDeclaredResponseHeaders(of: entry).build() : .none
        return try await harness.serve(.hostile(entry), faults: faults)
    }

    /// The acceptance: a stuck live playlist served by the origin ends on the typed error, its cache
    /// rule named as the likely cause and the edge as the class.
    func testAPlaylistHeldByACacheEndsOnTheTypedErrorNamingTheIntermediary() async throws {
        guard #available(iOS 18, tvOS 18, *) else { throw XCTSkip("needs AVMetrics (iOS 18)") }
        let origin = try await frozen(servingItsCacheRule: true)
        let sink = RecordingSink()
        let (player, record) = play(origin, sink: sink)

        try await harness.until("the session ends", timeout: bound) { !record.errors.isEmpty }

        XCTAssertEqual(record.errors.count, 1)
        let typed = try XCTUnwrap(record.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Transient.CdnEdge")
        XCTAssertEqual(typed.likelyCause, .intermediaryCache)
        XCTAssertEqual(typed.rungsTried, [])
        let stale = try XCTUnwrap((typed.underlyingError as any Error) as? StaleLivePlaylistError)
        XCTAssertEqual(
            stale.playlistURL.path, "/hostile/hls-cached-live-playlist/severe/media.m3u8")
        XCTAssertEqual(stale.targetDurationMs, 2_000)
        // How long is a reading of the host's clock, held over values in
        // `StalePlaylistJudgementTests`; here only that it was read.
        XCTAssertNotNil(stale.unchangedForMs)
        XCTAssertEqual(stale.cacheBypassingReloads, 0)
        XCTAssertEqual(stale.servedCacheControl, "public, max-age=600")
        XCTAssertEqual(stale.likelyCause, .intermediaryCache)
        // The session ended: the player is no longer waiting at the frozen edge.
        XCTAssertEqual(player.avPlayer.rate, 0)
        XCTAssertTrue(player.playerError is SuperPlayerError)
        // And telemetry hears it as a failure with the same class, though no item failed.
        try await harness.until("the failure is reported", timeout: bound) {
            sink.failures.count == 1
        }
        XCTAssertEqual(sink.failures.first?.classification, "Transient.CdnEdge")
    }

    /// The same bytes with nothing served saying a cache holds them: an origin that stopped
    /// publishing, which is a hole in the content rather than an edge to try another of.
    func testAPlaylistNothingCachedEndsNamingTheOrigin() async throws {
        guard #available(iOS 18, tvOS 18, *) else { throw XCTSkip("needs AVMetrics (iOS 18)") }
        let origin = try await frozen(servingItsCacheRule: false)
        let (_, record) = play(origin)

        try await harness.until("the session ends", timeout: bound) { !record.errors.isEmpty }

        let typed = try XCTUnwrap(record.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Content.SegmentGap")
        XCTAssertEqual(typed.likelyCause, .origin)
        let stale = try XCTUnwrap((typed.underlyingError as any Error) as? StaleLivePlaylistError)
        XCTAssertNil(stale.servedCacheControl)
    }

    /// The control: a live stream that keeps publishing plays on past several times the point at
    /// which a frozen one ends, so the watch reads progress and not merely "live".
    func testALiveStreamThatKeepsPublishingPlaysOn() async throws {
        guard #available(iOS 18, tvOS 18, *) else { throw XCTSkip("needs AVMetrics (iOS 18)") }
        let origin = try await harness.serve(.liveHls())
        let (player, record) = play(origin)

        // The three segments a join fetches (three target durations back, RFC 8216 §6.3.3), then
        // eight published after it: eight target durations of new media, well past the two and a
        // half a frozen stream is given.
        try await harness.until("the live stream plays on", timeout: bound) {
            origin.networkRequests().filter { $0.kind == .mediaSegment }.count >= 11
        }

        XCTAssertTrue(record.errors.isEmpty, "\(record.errors)")
        XCTAssertNil(player.playerError)
    }

    /// ADR-0011 rule 14: without resilience the frozen stream is left to AVFoundation, which waits —
    /// asserted once AVFoundation has itself judged the playlist unchanged three times, which is
    /// past where a resilient player ends.
    func testAPlayerWithoutResilienceIsLeftWaitingAsAStockOneIs() async throws {
        let origin = try await frozen(servingItsCacheRule: true)
        let (player, record) = play(origin, resilient: false)

        try await harness.until("AVFoundation judges the playlist unchanged thrice", timeout: bound)
        {
            (player.avPlayer.currentItem?.errorLog()?.events ?? [])
                .filter { $0.errorStatusCode == -12888 }.count >= 3
        }

        XCTAssertTrue(record.errors.isEmpty, "\(record.errors)")
        XCTAssertNil(player.playerError)
        XCTAssertNotEqual(player.avPlayer.rate, 0)
    }
}
