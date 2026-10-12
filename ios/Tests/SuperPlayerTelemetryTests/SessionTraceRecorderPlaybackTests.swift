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
import SuperPlayerCore
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest

/// A ``SessionTraceRecorder`` on a real `SuperPlayer` playing from the loopback origin (ADR-0021
/// rule 2): the structural golden of an on-demand session, and the redaction rules over a session
/// that really carried the material they withhold — the Kotlin `GoldenTraceTest` and
/// `SessionTraceRecorderTest`'s claims, on real time.
///
/// Every session plays to its end and is released, and its trace is taken only once its last
/// event and — on iOS 18 — every request the origin answered has reached the trace, so no load is
/// in flight when a test returns and no line is missing for want of waiting.
@MainActor
final class SessionTraceRecorderPlaybackTests: XCTestCase {

    private let bound: Duration = .seconds(90)
    private let harness = PlaybackHarness()

    /// `ios/Tests/Fixtures/golden/`, beside the other fixtures.
    private let goldens = GoldenFile(
        directory: URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Fixtures/golden"))

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    // MARK: - The structural golden

    /// An on-demand session from adoption to release, compared structurally with
    /// `ios/Tests/Fixtures/golden/hls-vod.trace` (ADR-0021 rule 4). Two segments, so the session
    /// ends well inside the collector's ten-second sampling interval and no `PlaybackStateSampled`
    /// lands on one run and not the next.
    ///
    /// The golden is the iOS 18 reading, `load` and `bandwidth` lines included; below 18 those two
    /// kinds are absent by design, so on such a runtime the comparison is of the trace with them
    /// removed against the golden with them removed.
    func testAnOnDemandSessionMatchesItsStructuralGolden() async throws {
        let origin = try await harness.serve(.hls(segmentCount: 2))
        let (player, recorder) = recordedPlayer()

        player.setMediaRequest(
            MediaRequest(contentId: "series/expanse/s01e01", sources: [origin.sourceURL]))
        player.avPlayer.play()
        try await playToEndAndRelease(player)
        let trace = try await settledTrace(recorder, origin: origin)

        if #available(iOS 18, tvOS 18, *) {
            try goldens.check("hls-vod", trace.structural().format())
        } else {
            let golden = try SessionTrace.parse(
                String(
                    contentsOf: goldens.url(of: "hls-vod"),
                    encoding: .utf8))
            XCTAssertEqual(
                withoutRequestKinds(trace.structural()), withoutRequestKinds(golden))
        }
    }

    // MARK: - Redaction

    /// The session carries a **signed URL** — a token in the multivariant playlist's query, and the
    /// same URL as the content id, the common app pattern rule 1 names — and a **credential
    /// header**, an `Authorization` the session answered the origin's challenge with. Each is shown
    /// present in what the origin received before it is shown absent from the trace, so the test
    /// cannot pass on a session that never carried them.
    ///
    /// The header is an answer to a challenge because that is the one credential header a consumer
    /// can make this player send without building its asset: core builds the asset itself and has
    /// no header seam yet — the Kotlin `HeaderProvider`'s port is `SuperPlayerResilience`'s — so
    /// the credential travels in the source URL's user information and the origin's first answer to
    /// the multivariant playlist is a `401` with a `Basic` challenge, which the loader answers with
    /// it. A cookie in the shared storage was tried first and is not sent by this test host's player.
    /// The challenge and its answer are one load to AVFoundation — one request event, one
    /// `load completed manifest` line — so the refusal leaves no line of its own.
    ///
    /// ref: RFC 9110 §11.6.1 (WWW-Authenticate); RFC 7617 (the Basic scheme)
    func testATraceCarriesNoSignedUrlNoCredentialAndNoUriShapedContentId() async throws {
        let token = "signed-\(UUID().uuidString)"
        let password = "credential-\(UUID().uuidString)"
        let credential = Data("viewer:\(password)".utf8).base64EncodedString()
        let origin = try await harness.serve(
            .hls(segmentCount: 2),
            faults: FaultScript.Builder()
                .failWithHttpStatus(401, kind: .manifest, index: 0, firstAttempts: 1)
                .addResponseHeaders(
                    ["WWW-Authenticate": "Basic realm=\"superplayer\""], kind: .manifest, index: 0
                )
                .build())
        var components = try XCTUnwrap(
            URLComponents(url: origin.sourceURL, resolvingAgainstBaseURL: false))
        components.user = "viewer"
        components.password = password
        components.query = "token=\(token)"
        let signed = try XCTUnwrap(components.url)
        let (player, recorder) = recordedPlayer()

        player.setMediaRequest(
            MediaRequest(contentId: signed.absoluteString, sources: [signed]))
        player.avPlayer.play()
        try await playToEndAndRelease(player)
        let text = try await settledTrace(recorder, origin: origin).format()

        // Present in the raw input first.
        let requests = origin.networkRequests()
        XCTAssertTrue(
            requests.contains { $0.query?.contains(token) == true },
            "the session never requested the signed URL: \(requests)")
        XCTAssertTrue(
            requests.contains { $0.headers["authorization"] == "Basic \(credential)" },
            "the session never sent the credential: \(requests.map(\.headers))")

        // Then absent from the trace.
        for withheld in [
            token, password, credential, "viewer", signed.absoluteString, "127.0.0.1",
            ":\(origin.port)", origin.sourceURL.lastPathComponent, ".m3u8", ".aac", "http://",
        ] {
            XCTAssertFalse(text.contains(withheld), "the trace carries \(withheld):\n\(text)")
        }
        // The control: the item line the content id would have been printed on is there.
        XCTAssertTrue(text.contains(" item id=uri-withheld reason=PLAYLIST_CHANGED\n"), text)
    }

    // MARK: - Helpers

    private func recordedPlayer() -> (SuperPlayer, SessionTraceRecorder) {
        let recorder = SessionTraceRecorder()
        var configuration = SuperPlayer.Configuration()
        configuration.telemetry = { QoeCollector(sink: recorder) }
        let player = SuperPlayer(configuration: configuration)
        recorder.attach(to: player)
        addTeardownBlock { @MainActor in recorder.detach() }
        return (player, recorder)
    }

    /// Waits for the item to report its end, so nothing is left loading, then releases the player,
    /// which ends the session.
    private func playToEndAndRelease(_ player: SuperPlayer) async throws {
        let item = try XCTUnwrap(player.avPlayer.currentItem)
        let ended = EndObserver(item)
        try await harness.until("the content plays to its end", timeout: bound) { ended.hasEnded }
        player.release()
    }

    /// The trace once `SessionEnded` has reached it and, on iOS 18, once every resource the origin
    /// served has a finished `load` line — the metric stream delivers after the request ends,
    /// and delivery of telemetry is asynchronous, so both are waited for rather than assumed.
    private func settledTrace(
        _ recorder: SessionTraceRecorder, origin: LoopbackOrigin
    ) async throws -> SessionTrace {
        try await harness.until("the session's end reached the trace", timeout: bound) {
            recorder.trace().lines.contains { $0.contains(" telemetry SessionEnded ") }
        }
        if #available(iOS 18, tvOS 18, *) {
            // One per resource rather than per request: a request the loader repeated within one
            // load — a challenge answered — is one event, and on-demand content fetches each
            // resource once.
            let resources = Set(origin.networkRequests().map(\.path)).count
            try await harness.until("every request reached the trace", timeout: bound) {
                recorder.trace().lines.filter {
                    $0.contains(" load completed ") || $0.contains(" load error ")
                }.count >= resources
            }
        }
        return recorder.trace()
    }

    private func withoutRequestKinds(_ trace: SessionTrace) -> [String] {
        trace.lines.filter { !$0.hasPrefix("load ") && !$0.hasPrefix("bandwidth ") }
    }
}
