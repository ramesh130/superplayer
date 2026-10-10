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
        let (asset, requests) = try await play(.hls(segmentCount: 3), sendingCmcd: false)

        for request in requests {
            XCTAssertTrue(Self.cmcdHeaders(request).isEmpty, "\(request) \(Self.finding)")
            XCTAssertNil(request.query, "\(request) \(Self.finding)")
            XCTAssertEqual(
                request.headers["x-playback-session-id"], asset.httpSessionIdentifier.uuidString,
                "\(request) \(Self.finding)")
        }
    }

    /// The flag sends CMCD on every request, manifests and segments alike, as headers and never as
    /// a query — and the `sid` on every one is the asset's `httpSessionIdentifier`, which can be
    /// read before the first request leaves. That is what lets the iOS session id be *read* where
    /// Android's is minted: the join is an equality on a string the app already holds.
    func testTheFlagSendsCmcdHeadersOnEveryRequestWhoseSidIsTheAssetsSessionIdentifier()
        async throws
    {
        let (asset, requests) = try await play(.hls(segmentCount: 3), sendingCmcd: true)

        for request in requests {
            // spec: CTA-5004 §3.2 — the keys travel in header groups; these three carry a key on
            // every request here, and no request carries a fourth, `CMCD-Status`.
            XCTAssertEqual(
                Set(Self.cmcdHeaders(request).keys),
                ["cmcd-object", "cmcd-request", "cmcd-session"], "\(request) \(Self.finding)")
            XCTAssertNil(request.query, "headers only: \(request) \(Self.finding)")
            let session = Self.keys(request.headers["cmcd-session"])
            XCTAssertEqual(session["sid"], Self.sid(of: asset), "\(request) \(Self.finding)")
            XCTAssertEqual(
                request.headers["x-playback-session-id"], asset.httpSessionIdentifier.uuidString,
                "\(request) \(Self.finding)")
            // spec: CTA-5004 §3.1 — `sf=h` is the streaming format HLS.
            XCTAssertEqual(session["sf"], "h", "\(request) \(Self.finding)")
            // Absent today, and the one key the Android half sends that this one does not; an SDK
            // that starts sending it closes a gap the finding names rather than breaking anything.
            XCTAssertNil(session["cid"], "\(request) \(Self.finding)")
        }
        let segments = requests.filter { $0.kind == .mediaSegment }
        XCTAssertFalse(segments.isEmpty, "\(requests)")
        for segment in segments {
            // spec: CTA-5004 §3.1 — `st=v` is on-demand, `ot=a` an audio-only object.
            XCTAssertEqual(
                Self.keys(segment.headers["cmcd-session"])["st"], "v", "\(segment) \(Self.finding)")
            XCTAssertEqual(
                Self.keys(segment.headers["cmcd-object"])["ot"], "a", "\(segment) \(Self.finding)")
        }
        for manifest in requests.filter({ $0.kind == .manifest }) {
            // spec: CTA-5004 §3.1 — `ot=m` is a manifest or playlist.
            XCTAssertEqual(
                Self.keys(manifest.headers["cmcd-object"])["ot"], "m",
                "\(manifest) \(Self.finding)")
        }
    }

    /// The other candidate the plan named: `AVURLAssetPrimarySessionIdentifierKey`, whose value the
    /// header says is to equal a primary item's asset's `httpSessionIdentifier`, so that
    /// interstitial assets can be matched to it. Given a UUID of the test's own, it reaches no CMCD
    /// key — `sid` stays the asset's own identifier — and what it does send is an
    /// `_HLS_primary_id` query on the multivariant playlist alone.
    func testThePrimarySessionIdentifierKeyDoesNotReachSid() async throws {
        let chosen = UUID()
        let (asset, requests) = try await play(
            .hls(segmentCount: 3), sendingCmcd: true,
            options: [AVURLAssetPrimarySessionIdentifierKey: chosen])

        XCTAssertNotEqual(asset.httpSessionIdentifier, chosen, Self.finding)
        for request in requests {
            XCTAssertEqual(
                Self.keys(request.headers["cmcd-session"])["sid"], Self.sid(of: asset),
                "\(request) \(Self.finding)")
        }
        let carrying = requests.filter { $0.query == "_HLS_primary_id=\(chosen.uuidString)" }
        XCTAssertEqual(carrying.map(\.path), ["/master.m3u8"], "\(requests) \(Self.finding)")
    }

    /// A live stream carries the same `sid` on every request and says it is live, so the join holds
    /// for the content where a CDN's view matters most.
    func testALiveStreamCarriesTheSameSidAndSaysItIsLive() async throws {
        let (asset, requests) = try await play(.liveHls(), sendingCmcd: true)

        let sids = Set(requests.map { Self.keys($0.headers["cmcd-session"])["sid"] })
        XCTAssertEqual(sids, [Self.sid(of: asset)], "\(requests) \(Self.finding)")
        for segment in requests.filter({ $0.kind == .mediaSegment }) {
            // spec: CTA-5004 §3.1 — `st=l` is live.
            XCTAssertEqual(
                Self.keys(segment.headers["cmcd-session"])["st"], "l", "\(segment) \(Self.finding)")
        }
    }

    /// Plays `content` from a stock player, with or without the CMCD flag set on its asset first,
    /// until two segments have been fetched, and answers the asset and everything the origin saw.
    /// Skips where the flag does not exist, which is the one gate on iOS 18 the suite needs.
    private func play(
        _ content: TestContent, sendingCmcd: Bool, options: [String: any Sendable] = [:]
    ) async throws -> (AVURLAsset, [NetworkRequest]) {
        let origin = try await harness.serve(content)
        let asset = AVURLAsset(url: origin.sourceURL, options: options)
        if sendingCmcd {
            guard #available(iOS 18, tvOS 18, *) else {
                throw XCTSkip("sendsCommonMediaClientDataAsHTTPHeaders needs iOS 18 and tvOS 18")
            }
            asset.resourceLoader.sendsCommonMediaClientDataAsHTTPHeaders = true
        }
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

    /// `sid` as it arrives for `asset`: its session identifier, quoted (spec: CTA-5004 §3.1 sends a
    /// string-valued key quoted).
    private static func sid(of asset: AVURLAsset) -> String {
        "\"\(asset.httpSessionIdentifier.uuidString)\""
    }

    /// The CMCD header fields a request carried, by lowercased name.
    private static func cmcdHeaders(_ request: NetworkRequest) -> [String: String] {
        request.headers.filter { $0.key.hasPrefix("cmcd-") }
    }

    /// One CMCD header's keys: comma-separated within a header group (spec: CTA-5004 §3.2), each
    /// `key=value` or a bare key for a valueless true (§3.1). A quoted value holding a comma — a
    /// `nor` URL in `CMCD-Request` can — would be split wrongly, which is why only `CMCD-Session`
    /// and `CMCD-Object` are read through it.
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
