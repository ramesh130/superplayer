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
import SuperPlayerTestkit
import XCTest

/// What a stock `AVPlayer` puts on the wire for CMCD (CTA-5004), with and without each candidate
/// API — #413's spike, kept as a test so the finding is pinned rather than remembered.
///
/// `docs/ios-cmcd-finding.md` is the finding these tests are the evidence for: the SDK emits CMCD v1
/// as request headers once `AVAssetResourceLoader.sendsCommonMediaClientDataAsHTTPHeaders` is set
/// (iOS 18), its `sid` is the asset's `httpSessionIdentifier`, and
/// `AVURLAssetPrimarySessionIdentifierKey` reaches no CMCD key at all. A failure here means an SDK
/// changed one of those three facts, and the document's decision has to be read again before the
/// test is changed to match.
///
/// Every observation is the origin's (ADR-0021 rule 2): a header or a query string a request
/// carried, never a property read off the engine, bar the asset's own `httpSessionIdentifier`, which
/// is the value under test.
@MainActor
final class CmcdFindingTests: XCTestCase {

    /// A ceiling for a few seconds of media at 1× on a loaded CI host, and only a bound.
    private let bound: Duration = .seconds(60)

    private let harness = PlaybackHarness()

    private static let finding = "see docs/ios-cmcd-finding.md before changing this expectation"

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    /// The control: an asset left alone sends no CMCD, in a header or a query, so the keys the next
    /// test sees are the flag's doing. It does send `X-Playback-Session-Id` — the same string the
    /// flag later puts in `sid`, and the one session identifier iOS 17 sends.
    func testAnAssetLeftAloneSendsNoCmcdAndNamesItsSessionInXPlaybackSessionId() async throws {
        let (asset, requests) = try await play(.hls(segmentCount: 3)) { _ in }

        for request in requests {
            XCTAssertTrue(
                Self.cmcdHeaders(request).isEmpty, "\(request) \(Self.finding)")
            XCTAssertFalse(
                request.query?.contains("CMCD") ?? false, "\(request) \(Self.finding)")
            XCTAssertEqual(
                request.headers["x-playback-session-id"], asset.httpSessionIdentifier.uuidString,
                "\(request)")
        }
    }

    /// The flag sends CMCD on every request, manifests and segments alike, as headers and never as a
    /// query — and the `sid` on every one is the asset's `httpSessionIdentifier`, which can be read
    /// before the first request leaves. That is what lets the iOS session id be *read* where Android's
    /// is minted: the join is an equality on a string the app already holds.
    func testTheFlagSendsCmcdHeadersOnEveryRequestWhoseSidIsTheAssetsSessionIdentifier()
        async throws
    {
        guard #available(iOS 18, tvOS 18, *) else {
            throw XCTSkip("sendsCommonMediaClientDataAsHTTPHeaders needs iOS 18")
        }
        let (asset, requests) = try await play(.hls(segmentCount: 3)) {
            $0.resourceLoader.sendsCommonMediaClientDataAsHTTPHeaders = true
        }
        let sid = asset.httpSessionIdentifier.uuidString

        for request in requests {
            let session = Self.keys(request.headers["cmcd-session"])
            // spec: CTA-5004 §3.1 — `sid` is a quoted string.
            XCTAssertEqual(session["sid"], "\"\(sid)\"", "\(request) \(Self.finding)")
            XCTAssertEqual(session["sf"], "h", "streaming format HLS: \(request)")
            XCTAssertFalse(
                request.query?.contains("CMCD") ?? false, "headers only: \(request) \(Self.finding)"
            )
            // Absent today, and the one key the Android half sends that this one does not; an SDK
            // that starts sending it closes a gap the finding names rather than breaking anything.
            XCTAssertNil(session["cid"], "\(request) \(Self.finding)")
        }
        let segments = requests.filter { $0.kind == .mediaSegment }
        XCTAssertFalse(segments.isEmpty, "\(requests)")
        for segment in segments {
            // spec: CTA-5004 §3.1 — `st=v` is on-demand, `ot=a` an audio-only object.
            XCTAssertEqual(Self.keys(segment.headers["cmcd-session"])["st"], "v", "\(segment)")
            XCTAssertEqual(Self.keys(segment.headers["cmcd-object"])["ot"], "a", "\(segment)")
        }
        for manifest in requests.filter({ $0.kind == .manifest }) {
            XCTAssertEqual(Self.keys(manifest.headers["cmcd-object"])["ot"], "m", "\(manifest)")
        }
    }

    /// The other candidate the plan named: `AVURLAssetPrimarySessionIdentifierKey` takes a UUID the
    /// app chooses, which would have let the app *set* `sid`. It does not reach CMCD at all — `sid`
    /// stays the asset's own identifier — and what it does send is an `_HLS_primary_id` query on the
    /// multivariant playlist alone, which is the interstitial-matching key its header documents.
    func testThePrimarySessionIdentifierKeyDoesNotReachSid() async throws {
        guard #available(iOS 18, tvOS 18, *) else {
            throw XCTSkip("sendsCommonMediaClientDataAsHTTPHeaders needs iOS 18")
        }
        let chosen = UUID()
        let (asset, requests) = try await play(
            .hls(segmentCount: 3),
            options: [AVURLAssetPrimarySessionIdentifierKey: chosen]
        ) {
            $0.resourceLoader.sendsCommonMediaClientDataAsHTTPHeaders = true
        }

        XCTAssertNotEqual(asset.httpSessionIdentifier, chosen)
        for request in requests {
            XCTAssertEqual(
                Self.keys(request.headers["cmcd-session"])["sid"],
                "\"\(asset.httpSessionIdentifier.uuidString)\"", "\(request) \(Self.finding)")
        }
        let carrying = requests.filter {
            $0.query == "_HLS_primary_id=\(chosen.uuidString)"
        }
        XCTAssertEqual(carrying.map(\.path), ["/master.m3u8"], "\(requests)")
    }

    /// A live stream carries the same `sid` on every request and says it is live, so the join holds
    /// for the content where a CDN's view matters most.
    func testALiveStreamCarriesTheSameSidAndSaysItIsLive() async throws {
        guard #available(iOS 18, tvOS 18, *) else {
            throw XCTSkip("sendsCommonMediaClientDataAsHTTPHeaders needs iOS 18")
        }
        let (asset, requests) = try await play(.liveHls()) {
            $0.resourceLoader.sendsCommonMediaClientDataAsHTTPHeaders = true
        }

        let sids = Set(requests.map { Self.keys($0.headers["cmcd-session"])["sid"] })
        XCTAssertEqual(sids, ["\"\(asset.httpSessionIdentifier.uuidString)\""], "\(requests)")
        for segment in requests.filter({ $0.kind == .mediaSegment }) {
            // spec: CTA-5004 §3.1 — `st=l` is live.
            XCTAssertEqual(Self.keys(segment.headers["cmcd-session"])["st"], "l", "\(segment)")
        }
    }

    /// Plays `content` from a stock player over an asset `configure` was given first, until two
    /// segments have been fetched, and answers the asset and everything the origin saw.
    private func play(
        _ content: TestContent, options: [String: any Sendable] = [:],
        configure: (AVURLAsset) -> Void
    ) async throws -> (AVURLAsset, [NetworkRequest]) {
        let origin = try await harness.serve(content)
        let asset = AVURLAsset(url: origin.sourceURL, options: options)
        configure(asset)
        let player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
        player.play()
        try await harness.until("two segments are fetched", timeout: bound) {
            origin.networkRequests().filter { $0.kind == .mediaSegment }.count >= 2
        }
        // Emptied rather than only paused, so it fetches nothing more from an origin the next test
        // has already stopped.
        player.replaceCurrentItem(with: nil)
        return (asset, origin.networkRequests())
    }

    /// The four CMCD header fields a request carried, by lowercased name.
    private static func cmcdHeaders(_ request: NetworkRequest) -> [String: String] {
        request.headers.filter { $0.key.hasPrefix("cmcd-") }
    }

    /// One CMCD header's keys: comma-separated within a header group (spec: CTA-5004 §3.2), each
    /// `key=value` or a bare key for a valueless true (§3.1). A value holding a comma inside quotes is not split correctly, and no
    /// key read here can carry one.
    private static func keys(_ field: String?) -> [String: String] {
        guard let field else { return [:] }
        var keys: [String: String] = [:]
        for pair in field.split(separator: ",") {
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            keys[parts[0]] = parts.count == 2 ? parts[1] : "true"
        }
        return keys
    }
}
