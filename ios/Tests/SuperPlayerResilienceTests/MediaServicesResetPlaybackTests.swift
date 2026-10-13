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

/// Rung 5 through the harness (#420): the media services reset, posted as the system posts it —
/// `AVAudioSession.mediaServicesWereResetNotification` from `AVAudioSession.sharedInstance()`, the
/// object `AVAudioSession.h` says posts it, as `LifecyclePlaybackTests` posts an interruption — and
/// the item rebuilt where playback held, at most ``SuperPlayer/maxDecoderRecreations`` times at one
/// position, the next reset there ending on rung 6. The Kotlin
/// `SuperPlayerDecoderRecreationTest`'s role.
///
/// The simulator's media services are not reset by the post — nothing here can reset them — so what
/// is shown is the ladder's half and the player's: the classification of the event, the rebuild at
/// the held position, the bound, and the count starting over. What a real reset does to the decoders
/// under an item is a device's, and `docs/testing.md`'s *Rungs 4 and 5 on iOS* lists it.
///
/// The player is paused at a position before each post, so "the held position" is a number the test
/// read rather than one it guessed, and is compared as a position, never timed.
@MainActor
final class MediaServicesResetPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(60)

    /// How far a rebuilt item may report from the position it was rebuilt at: one AAC frame either
    /// way at 44.1 kHz is 23 ms, and the rebuild seeks with zero tolerance, so a reading off by more
    /// than this is a different position rather than the same one read through a frame boundary.
    private let samePositionMs: Int64 = 50

    /// The first ask for the multivariant playlist dropped with no answer, which `AVPlayer` repairs
    /// by asking again for the same item, unseen by the facade (#505). It is the control that keeps
    /// ``itemsBuilt(_:)`` honest: a count of playlist requests reads two items here, as it would
    /// wherever a slow host leaves AVFoundation asking again.
    private let droppedFirstAsk = FaultScript.Builder()
        .resetConnection(kind: .manifest, index: 0, firstAttempts: 1).build()

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

    private func player(resilient: Bool, sink: RecordingSink? = nil) -> (SuperPlayer, ErrorRecord) {
        var configuration = SuperPlayer.Configuration()
        if resilient { configuration.resilience = Resilience.standard() }
        if let sink { configuration.telemetry = { QoeCollector(sink: sink) } }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        let record = ErrorRecord()
        player.errors.sink { error in MainActor.assumeIsolated { record.errors.append(error) } }
            .store(in: &subscriptions)
        return (player, record)
    }

    /// Plays until the position passes `seconds`, pauses, and answers where it held.
    private func playPast(_ seconds: Double, _ player: SuperPlayer) async throws -> Int64 {
        player.avPlayer.play()
        try await harness.until("the position passes \(seconds)s", timeout: bound) {
            player.avPlayer.currentTime().seconds > seconds
        }
        player.avPlayer.pause()
        try await harness.until("the player pauses", timeout: bound) {
            player.avPlayer.timeControlStatus == .paused
        }
        return milliseconds(player.avPlayer.currentTime())
    }

    /// Posts the reset and waits for the item to be rebuilt and ready. The rebuild is seen where a
    /// consumer's CDN would see it, as the multivariant playlist asked for under a new playback
    /// session, rather than by reading which item the facade made current (ADR-0021 rule 1).
    private func resetAndAwaitRebuild(_ player: SuperPlayer, _ origin: LoopbackOrigin)
        async throws
    {
        let before = itemsBuilt(origin)
        postMediaServicesReset()
        try await harness.until("the item is rebuilt and ready", timeout: bound) {
            self.itemsBuilt(origin) == before + 1
                && player.avPlayer.currentItem?.status == .readyToPlay
        }
    }

    /// How many items asked the origin for the multivariant playlist: the distinct
    /// `X-Playback-Session-Id`s those requests carried. AVFoundation sends the asset's
    /// `httpSessionIdentifier` there on every request and a rebuild is a fresh asset
    /// (`PlayerStateRungs.swift`, `reopen`), so an item is one id however often it asked, where a
    /// count of the requests reads `AVPlayer`'s own retry as a rebuild (``droppedFirstAsk``). A
    /// request with no id counts as an item of its own, so a runtime that stopped sending the header
    /// fails these tests rather than passing them on a collapsed count.
    private func itemsBuilt(_ origin: LoopbackOrigin) -> Int {
        let sessions = playlistAsks(origin)
            .map { $0.headers["x-playback-session-id"] ?? UUID().uuidString }
        return Set(sessions).count
    }

    /// Every ask the origin received for the multivariant playlist, retries included.
    private func playlistAsks(_ origin: LoopbackOrigin) -> [NetworkRequest] {
        origin.networkRequests().filter { $0.path == "/master.m3u8" }
    }

    /// That ``droppedFirstAsk`` fired and `AVPlayer` asked again for the same item, so the asks
    /// outnumber the items and a count of asks would have failed where ``itemsBuilt(_:)`` held.
    private func assertTheDroppedAskWasAskedAgain(_ origin: LoopbackOrigin) {
        XCTAssertTrue(
            origin.responses().contains { $0.faults.contains(.connectionReset) },
            "the origin dropped no ask, so nothing was controlled")
        XCTAssertGreaterThan(
            playlistAsks(origin).count, itemsBuilt(origin), "\(playlistAsks(origin))")
    }

    private func postMediaServicesReset() {
        NotificationCenter.default.post(
            name: AVAudioSession.mediaServicesWereResetNotification,
            object: AVAudioSession.sharedInstance())
    }

    // MARK: The acceptance

    func testAResetIsRepairedTwiceAtOnePositionAndTheThirdEndsOnRungSix() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 8), faults: droppedFirstAsk)
        let sink = RecordingSink()
        let (player, record) = player(resilient: true, sink: sink)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        let heldMs = try await playPast(1.5, player)

        for rebuild in 1...SuperPlayer.maxDecoderRecreations {
            try await resetAndAwaitRebuild(player, origin)
            XCTAssertEqual(
                Double(milliseconds(player.avPlayer.currentTime())), Double(heldMs),
                accuracy: Double(samePositionMs), "rebuild \(rebuild)")
            XCTAssertEqual(player.currentContentId, "a")
            XCTAssertTrue(record.errors.isEmpty, "\(record.errors)")
        }
        postMediaServicesReset()
        try await harness.until("the typed error arrives", timeout: bound) {
            !record.errors.isEmpty
        }

        XCTAssertEqual(record.errors.count, 1)
        let typed = try XCTUnwrap(record.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Device.DecoderTransient")
        XCTAssertEqual(typed.rungsTried, ["RECREATE_DECODER"])
        XCTAssertEqual(
            Double(try XCTUnwrap(typed.positionMs)), Double(heldMs),
            accuracy: Double(samePositionMs))
        XCTAssertEqual(typed.underlyingError.code, AVError.Code.mediaServicesWereReset.rawValue)
        // What each rebuild cost: an item asking for the playlist again, once per rebuild (the
        // re-adoption's price), and nothing for the third, which rung 6 rebuilds nothing for.
        XCTAssertEqual(
            itemsBuilt(origin), 1 + SuperPlayer.maxDecoderRecreations, "\(playlistAsks(origin))")
        assertTheDroppedAskWasAskedAgain(origin)
        // One viewing, one session, however many items it took.
        XCTAssertEqual(sink.sessionsStarted.count, 1)
        XCTAssertEqual(sink.sessionsEnded, 0)
    }

    /// The control that keeps the bound from being "two rebuilds and then never again": playback
    /// that advanced after the second rebuild starts the count over, so a third reset — at a new
    /// position — is rebuilt too.
    func testPlaybackThatAdvancedStartsTheCountOver() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 8))
        let (player, record) = player(resilient: true)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        _ = try await playPast(1.5, player)
        for _ in 1...SuperPlayer.maxDecoderRecreations {
            try await resetAndAwaitRebuild(player, origin)
        }

        let laterMs = try await playPast(3.5, player)
        try await resetAndAwaitRebuild(player, origin)

        XCTAssertTrue(record.errors.isEmpty, "\(record.errors)")
        XCTAssertEqual(
            Double(milliseconds(player.avPlayer.currentTime())), Double(laterMs),
            accuracy: Double(samePositionMs))
    }

    /// ADR-0011 rule 14: without resilience nothing hears the reset, and nothing is rebuilt.
    func testAPlayerWithoutResilienceRebuildsNothing() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 8), faults: droppedFirstAsk)
        let (player, record) = player(resilient: false)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [origin.sourceURL]))
        _ = try await playPast(1.5, player)

        postMediaServicesReset()
        // A later play proves the main queue has run past the post, which a rebuild would have used.
        player.avPlayer.play()
        try await harness.until("playback resumes", timeout: bound) {
            player.avPlayer.currentTime().seconds > 2.5
        }

        XCTAssertTrue(record.errors.isEmpty)
        XCTAssertEqual(itemsBuilt(origin), 1, "\(playlistAsks(origin))")
        assertTheDroppedAskWasAskedAgain(origin)
    }

    private func milliseconds(_ time: CMTime) -> Int64 {
        Int64((time.seconds * 1_000).rounded())
    }
}
