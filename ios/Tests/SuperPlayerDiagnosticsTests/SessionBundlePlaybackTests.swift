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
import Foundation
import SuperPlayerCore
import SuperPlayerDRM
import SuperPlayerDiagnostics
import SuperPlayerResilience
import SuperPlayerTelemetry
import SuperPlayerTestkit
import UIKit
import XCTest
import os

/// A ``SessionBundle`` of a real session on the loopback origin (ADR-0021 rule 2): the structural
/// golden that pins the format, and the redaction over a session that really carried everything a
/// bundle withholds — the Kotlin `SessionBundleGoldenTraceTest` and `SessionBundleRedactionTest`,
/// on real time.
@MainActor
final class SessionBundlePlaybackTests: XCTestCase {

    /// A segment refused on every attempt is asked for for some thirty seconds before the item
    /// fails (`TypedErrorPlaybackTests`, observed in #419), and the redaction session also repairs a
    /// credential first. Only a ceiling.
    private let bound: Duration = .seconds(120)

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

    // MARK: - The structural golden

    /// An on-demand session from adoption to release, bundled on a **stated** device and compared
    /// structurally with `ios/Tests/Fixtures/golden/hls-vod-bundle.trace` — the Kotlin golden's
    /// name, and the Kotlin golden's reason for stating the device: a snapshot read from whichever
    /// simulator ran the test would pin that host's OS version in a committed file, and the thing
    /// worth catching in review is a field of the snapshot changing meaning.
    ///
    /// The session is `SessionTraceRecorderPlaybackTests`' golden one, so the trace half of this
    /// file is `hls-vod.trace` under the capability lines, which is rule 9 — one layer richer, the
    /// same grammar — as a readable artifact.
    func testAnOnDemandSessionsBundleMatchesItsStructuralGolden() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 2))
        let recorder = SessionTraceRecorder()
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: recorder) }
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        recorder.attach(to: player)
        addTeardownBlock { @MainActor in recorder.detach() }

        player.setMediaRequest(
            MediaRequest(contentId: "series/expanse/s01e01", sources: [origin.sourceURL]))
        player.avPlayer.play()
        let ended = EndObserver(try XCTUnwrap(player.avPlayer.currentItem))
        try await harness.until("the content plays to its end", timeout: bound) { ended.hasEnded }
        player.release()
        let trace = try await harness.settledTrace(recorder, origin: origin, bound: bound)
        let bundle = SessionBundle(trace: trace, capabilities: .stated).structural()

        if #available(iOS 18, tvOS 18, *) {
            try goldens.check("hls-vod-bundle", bundle.format())
        } else {
            // Below 18 the request kinds are absent by design, as the trace golden's test reads it.
            let golden = try String(contentsOf: goldens.url(of: "hls-vod-bundle"), encoding: .utf8)
            XCTAssertEqual(
                withoutRequestKinds(bundle.format()), withoutRequestKinds(golden))
        }
    }

    // MARK: - Redaction

    /// One session driven through everything a bundle must not carry, each shown **present** where
    /// it travelled before it is shown **absent** from the bundle — the Kotlin test's design and
    /// its reason: a redaction test over a session that never had the material passes identically
    /// against a bundle that prints whole URLs.
    ///
    /// In order: a **signed URL** (a token in the multivariant playlist's query, and the same URL as
    /// the content id); a **credential header**, minted by the app's `HeaderProvider` into an
    /// `Authorization` after the origin expires the session's token at a segment, and carried by
    /// every request of the re-adoption that repairs it (#420); a **licence**, a FairPlay key
    /// request answered by the harness's key server through the player's own exchange (#422); and a
    /// **failure naming the host** — the re-adopted media playlist refused 404 on every attempt after
    /// its first, which ends the session typed (rung 6) with the playlist's address in the item's
    /// error log. Then the **device**: its name, model, vendor identifier, model identifier and OS
    /// build, each read where the platform holds it and refused by the seventh rule.
    ///
    /// The licence's key session is the DRM tests' stand-in, because the simulator has no FairPlay
    /// (`docs/testing.md`, *FairPlay on iOS*); what reaches the bundle is the same either way, since
    /// nothing of an exchange is recorded.
    func testNothingOfASessionThatCarriedEverythingReachesItsBundle() async throws {
        let token = "signed-\(UUID().uuidString)"
        let bearer = "Bearer credential-\(UUID().uuidString)"
        let keyIdentifier = "key-\(UUID().uuidString)"
        let origin = try await harness.serve(
            .hls(segmentCount: 6),
            faults: FaultScript.Builder()
                .expireTokenAtSegment(1, refreshable: true)
                // Index 1 is the media playlist; its first answer starts the session and every
                // later one — the re-adoption's — is refused, so the session ends on it.
                .failWithHttpStatus(
                    FaultScript.httpNotFound, kind: .manifest, index: 1, afterAttempts: 1)
                .build())
        let server = try await harness.serve(.licenceServer())
        var components = try XCTUnwrap(
            URLComponents(url: origin.sourceURL, resolvingAgainstBaseURL: false))
        components.query = "token=\(token)"
        let signed = try XCTUnwrap(components.url)

        let keySession = StandInKeySession()
        let recorder = SessionTraceRecorder()
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: recorder) }
        configuration.resilience = Resilience.standard(headers: FixedProvider(bearer))
        configuration.drm = Drm.fairPlay(
            FairPlayConfig(
                certificateURL: server.url(path: TestContent.licenceCertificatePath),
                licenceURL: server.sourceURL),
            keySession: { keySession })
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        recorder.attach(to: player)
        addTeardownBlock { @MainActor in recorder.detach() }
        let errors = ErrorRecord()
        player.errors.sink { error in MainActor.assumeIsolated { errors.errors.append(error) } }
            .store(in: &subscriptions)

        player.setMediaRequest(MediaRequest(contentId: signed.absoluteString, sources: [signed]))
        player.avPlayer.play()
        let licence = try await acquireLicence(keyIdentifier, from: keySession)
        try await harness.until("the session ends typed", timeout: bound) {
            !errors.errors.isEmpty
        }
        let failedPlaylist = try XCTUnwrap(player.avPlayer.currentItem?.errorLog()?.events.last?.uri)
        player.release()
        let bundle = SessionBundle(
            trace: try await harness.settledTrace(recorder, origin: origin, bound: bound)
        ).format()

        // Present where it travelled, first.
        let requests = origin.networkRequests()
        XCTAssertTrue(
            requests.contains { $0.query?.contains(token) == true },
            "the session never requested the signed URL: \(requests)")
        XCTAssertTrue(
            requests.contains { $0.headers["authorization"] == bearer },
            "the session never sent the credential: \(requests.map(\.headers))")
        XCTAssertTrue(
            server.networkRequests().contains {
                $0.body == Array(StandInKeySession.serverPlaybackContext(for: keyIdentifier))
            }, "the key request never reached the server")
        XCTAssertTrue(
            failedPlaylist.contains("127.0.0.1:\(origin.port)"),
            "the failure does not name the host: \(failedPlaylist)")
        XCTAssertTrue(
            origin.responses().contains { $0.status == FaultScript.httpNotFound },
            "the playlist was never refused")
        XCTAssertNotNil(errors.errors.first as? SuperPlayerError, "\(errors.errors)")
        let device = deviceIdentity()

        // Then absent from the bundle.
        let withheld =
            [
                token, bearer, String(bearer.dropFirst("Bearer ".count)), signed.absoluteString,
                "127.0.0.1", ":\(origin.port)", ":\(server.port)", ".m3u8", ".aac", "http://",
                failedPlaylist, keyIdentifier, "spc:", "ckc:", String(decoding: licence, as: UTF8.self),
                licence.base64EncodedString(),
            ] + device
        for value in withheld {
            XCTAssertFalse(bundle.contains(value), "the bundle carries \(value):\n\(bundle)")
        }
        // The controls: the lines those values would have been printed on are there, and so is
        // what the device can do.
        XCTAssertTrue(bundle.contains(" item id=uri-withheld reason=PLAYLIST_CHANGED\n"), bundle)
        XCTAssertTrue(bundle.contains(" error code="), bundle)
        XCTAssertTrue(bundle.contains("+0 capability decoder mime=video/avc hardware="), bundle)
        XCTAssertTrue(bundle.contains("+0 capability device platform=iOS osVersion="), bundle)
    }

    // MARK: - Helpers

    /// Asks the stand-in key session for `keyIdentifier`'s key, as AVFoundation would on a device,
    /// and returns the licence the server answered with.
    private func acquireLicence(_ keyIdentifier: String, from session: StandInKeySession)
        async throws -> Data
    {
        // The session starts as the player adopts its first item; the request is made once it has.
        try await harness.until("the key session starts", timeout: bound) {
            session.isStarted
        }
        let record = session.request(keyIdentifier)
        try await harness.until("the key request is answered", timeout: bound) {
            !record.answers.isEmpty
        }
        guard case .licence(let licence) = record.answers.first else {
            XCTFail("the key request was refused: \(record.answers)")
            return Data()
        }
        return licence
    }

    /// Every identifying reading of this device the platform offers, each asserted present — a
    /// non-empty value where it was read — so that its absence from the bundle means something.
    private func deviceIdentity() -> [String] {
        let modelIdentifier =
            ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? sysctl("hw.machine")
        let readings: [(String, String?)] = [
            ("the device's name", UIDevice.current.name),
            ("the model", UIDevice.current.model),
            ("the vendor identifier", UIDevice.current.identifierForVendor?.uuidString),
            ("the model identifier", modelIdentifier),
            ("the OS build", sysctl("kern.osversion")),
        ]
        for (what, value) in readings {
            XCTAssertFalse(value?.isEmpty ?? true, "\(what) was not read, so its absence proves nothing")
        }
        return readings.compactMap(\.1).filter { !$0.isEmpty }
    }

    private func sysctl(_ name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buffer = [UInt8](repeating: 0, count: size)
        guard sysctlbyname(name, &buffer, &size, nil, 0) == 0 else { return nil }
        return String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
    }

    private func withoutRequestKinds(_ text: String) -> [String] {
        text.components(separatedBy: "\n").filter {
            !$0.hasPrefix("load ") && !$0.hasPrefix("bandwidth ")
        }
    }
}

/// A provider with one credential to give, whatever was refused.
private struct FixedProvider: HeaderProvider {
    let authorization: String

    init(_ authorization: String) { self.authorization = authorization }

    func refreshedHeaders(for refusal: CredentialRefusal) async throws -> [String: String] {
        ["Authorization": authorization]
    }
}
