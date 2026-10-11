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
import os

/// The credential repair through the harness (#420): an expired token, refused 403 by
/// ``FaultScript/Builder/expireTokenAtSegment(_:refreshable:)``, is repaired by re-adopting the
/// content at the held position with what the app's ``HeaderProvider`` returned — the Kotlin
/// `TokenRefreshPlaybackTest`'s role, over iOS's mechanism. One that the origin will not accept
/// whatever is presented ends classified, and a provider that returns what was refused, or throws,
/// declines: the controls that keep the repair from being "re-adopt on every 403".
///
/// What the re-adoption costs is counted rather than asserted away: the multivariant playlist is
/// fetched a second time, carrying the new credential (`docs/ios-plan.md` §3.5). And the header
/// reaching the origin at all is what holds `AVURLAssetHTTPHeaderFieldsKey` — an option the SDK does
/// not declare — to doing what core relies on it for (`PlayerStateRungs.swift`).
@MainActor
final class CredentialRepairPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(60)

    /// Inside the second two-second segment, so the first segment asked for is `segment1.aac`
    /// (distinct-resource index 0) and the token expires at `segment2.aac` (index 1).
    private let startMs: Int64 = 3_000

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

    private func serve(refreshable: Bool) async throws -> LoopbackOrigin {
        try await harness.serve(
            .hls(segmentCount: 6),
            faults: FaultScript.Builder().expireTokenAtSegment(1, refreshable: refreshable).build())
    }

    private func play(
        _ origin: LoopbackOrigin, provider: any HeaderProvider, sink: RecordingSink? = nil
    ) -> (SuperPlayer, ErrorRecord) {
        var configuration = SuperPlayer.Configuration()
        configuration.resilience = Resilience.standard(headers: provider)
        if let sink { configuration.telemetry = { QoeCollector(sink: sink) } }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        let record = ErrorRecord()
        player.errors.sink { error in MainActor.assumeIsolated { record.errors.append(error) } }
            .store(in: &subscriptions)
        player.setMediaRequest(
            MediaRequest(
                contentId: "a", sources: [origin.sourceURL], startPosition: .at(positionMs: startMs)
            ))
        player.avPlayer.play()
        return (player, record)
    }

    // MARK: The acceptance

    func testARefreshableExpiryHealsByReadoptionAtTheHeldPosition() async throws {
        let origin = try await serve(refreshable: true)
        let provider = MintingProvider()
        let sink = RecordingSink()
        let (player, record) = play(origin, provider: provider, sink: sink)

        try await harness.until("playback passes the expired segment", timeout: bound) {
            player.avPlayer.currentTime().seconds > 5.5
                && player.avPlayer.currentItem?.status == .readyToPlay
        }

        // Asked once, about the refused segment, with nothing sent before.
        let refusals = provider.refusals
        XCTAssertEqual(refusals.count, 1, "\(refusals)")
        XCTAssertEqual(refusals.first?.status, 403)
        XCTAssertEqual(refusals.first?.uri.lastPathComponent, "segment2.aac")
        XCTAssertEqual(refusals.first?.sentHeaders, [:])
        // The new credential reached the origin and was served.
        let fresh = "Bearer fresh-1"
        XCTAssertTrue(
            origin.responses().contains {
                $0.request.path == "/segment2.aac" && $0.status == 200
                    && $0.request.headers["authorization"] == fresh
            })
        // The cost, counted: every playlist again, the multivariant one carrying the credential.
        let playlists = origin.networkRequests().filter { $0.path == "/master.m3u8" }
        XCTAssertEqual(playlists.count, 2)
        XCTAssertEqual(playlists.last?.headers["authorization"], fresh)
        // At the held position: nothing before the start was fetched under the new credential.
        XCTAssertFalse(
            origin.networkRequests().contains {
                $0.path == "/segment0.aac" && $0.headers["authorization"] == fresh
            })
        // Repaired, so withheld; one viewing, one session.
        XCTAssertTrue(record.errors.isEmpty, "\(record.errors)")
        XCTAssertEqual(player.currentContentId, "a")
        XCTAssertEqual(sink.sessionsStarted.count, 1)
        XCTAssertEqual(sink.sessionsEnded, 0)
    }

    func testAnUnrefreshableExpiryEndsClassified() async throws {
        let origin = try await serve(refreshable: false)
        let provider = MintingProvider()
        let (_, record) = play(origin, provider: provider)

        try await harness.until("the typed error arrives", timeout: bound) {
            !record.errors.isEmpty
        }

        // Asked once: the fresh credential refused again by the same request climbs on.
        XCTAssertEqual(provider.refusals.count, 1)
        XCTAssertEqual(record.errors.count, 1)
        let typed = try XCTUnwrap(record.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Transient.CdnEdge")
        XCTAssertEqual(typed.rungsTried, [])
    }

    // MARK: A provider declines

    /// Returning what was refused declines: nothing is re-adopted, and the CDN's refusal ends it.
    func testAProviderWithNothingNewerDeclines() async throws {
        try await assertDeclines(EchoingProvider())
    }

    /// Throwing declines the same way, and what surfaces is the CDN's failure, not the provider's.
    func testAProviderThatThrowsDeclines() async throws {
        try await assertDeclines(ThrowingProvider())
    }

    private func assertDeclines(_ provider: any HeaderProvider) async throws {
        let origin = try await serve(refreshable: true)
        let (_, record) = play(origin, provider: provider)

        try await harness.until("the typed error arrives", timeout: bound) {
            !record.errors.isEmpty
        }

        let typed = try XCTUnwrap(record.errors.first as? SuperPlayerError)
        XCTAssertEqual(typed.causeClass, "Transient.CdnEdge")
        XCTAssertEqual(origin.networkRequests().filter { $0.path == "/master.m3u8" }.count, 1)
        XCTAssertFalse(origin.networkRequests().contains { $0.headers["authorization"] != nil })
    }
}

/// A provider minting a new token per question, keeping every refusal it was told about.
private final class MintingProvider: HeaderProvider {

    private let asked = OSAllocatedUnfairLock(initialState: [CredentialRefusal]())

    var refusals: [CredentialRefusal] { asked.withLock { $0 } }

    func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String] {
        let count = asked.withLock { refusals in
            refusals.append(refusal)
            return refusals.count
        }
        return ["Authorization": "Bearer fresh-\(count)"]
    }
}

/// A provider with nothing newer: what was sent, back.
private struct EchoingProvider: HeaderProvider {
    func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String] {
        refusal.sentHeaders
    }
}

/// A provider whose own token service is down.
private struct ThrowingProvider: HeaderProvider {
    struct TokenServiceDown: Error {}
    func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String] {
        throw TokenServiceDown()
    }
}
