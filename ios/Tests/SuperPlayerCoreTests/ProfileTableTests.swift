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
import XCTest

/// Each profile's row, read off the engine the player built: the item a request made, and the
/// `AVPlayer` itself for the one knob that is the player's.
///
/// **Read through `player.avPlayer`**, which ADR-0021 rule 1's #401 addendum admits: the numbers
/// are internal to `StaticProfilePolicy` by design, and although `playbackDecision` now says what
/// was decided, a decision is honoured only if it is on the engine's own properties — so those are
/// what a row is checked against. The expected
/// values are written out here rather than read from the table, so a retuned row fails a test that
/// says what it was.
///
/// No item here loads anything: every source names the discard port, refused at once.
@MainActor
final class ProfileTableTests: XCTestCase {

    /// A port of the loopback interface nothing listens on, so an item fetches nothing.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    /// The defaults the table's comments call Apple's, read off a stock item and player rather than
    /// trusted — the baseline every departure below is a departure from.
    func testAppleDefaultsAreWhatTheTableDepartsFrom() {
        XCTAssertEqual(Knobs(AVPlayerItem(url: nowhere), AVPlayer()), .appleDefaults)
    }

    /// On-demand departs from nothing, so its item is indistinguishable from a stock one.
    func testVideoOnDemandIsAppleDefaults() {
        XCTAssertEqual(knobs(under: .videoOnDemand), .appleDefaults)
    }

    /// The default configuration is on-demand's.
    func testTheDefaultProfileIsVideoOnDemand() {
        XCTAssertEqual(SuperPlayer.Configuration().profile, .videoOnDemand)
        XCTAssertEqual(knobs(under: SuperPlayer.Configuration()), .appleDefaults)
    }

    func testLiveLinearPreservesItsOffsetFromTheLiveEdge() {
        var expected = Knobs.appleDefaults
        expected.preservesLiveOffset = true

        XCTAssertEqual(knobs(under: .liveLinear), expected)
    }

    func testShortFormBuffersFifteenSecondsAndCapsAt1080Tall() {
        var expected = Knobs.appleDefaults
        expected.forwardBufferSeconds = 15
        expected.maxResolution = CGSize(width: Int(Int32.max), height: 1_080)

        XCTAssertEqual(knobs(under: .shortForm), expected)
    }

    func testTvLeanbackBuffersTwoMinutesAndCapsNothing() {
        var expected = Knobs.appleDefaults
        expected.forwardBufferSeconds = 120

        XCTAssertEqual(knobs(under: .tvLeanback), expected)
    }

    /// The one row with a bitrate ceiling, and with both ceilings, because either alone lets a
    /// high-bitrate encode of a low resolution through.
    func testDataSaverBuffersTwentySecondsAndCapsAt480TallAnd800Kbps() {
        var expected = Knobs.appleDefaults
        expected.forwardBufferSeconds = 20
        expected.peakBitRate = 800_000
        expected.maxResolution = CGSize(width: Int(Int32.max), height: 480)

        XCTAssertEqual(knobs(under: .dataSaver), expected)
    }

    /// The profile is the player's and not the request's: an item from a bare URL carries it too,
    /// and so does every later item, not only the first.
    func testEveryItemThePlayerMakesCarriesTheProfile() {
        let player = SuperPlayer(configuration: .init(profile: .dataSaver))

        player.setMediaURL(nowhere)
        let bare = Knobs(player.avPlayer.currentItem!, player.avPlayer)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        player.setMediaRequest(MediaRequest(contentId: "b", sources: [nowhere]))
        let third = Knobs(player.avPlayer.currentItem!, player.avPlayer)

        XCTAssertEqual(bare, knobs(under: .dataSaver))
        XCTAssertEqual(third, knobs(under: .dataSaver))
    }

    /// The control: five profiles, five different rows — so the per-profile tests above are not
    /// one configuration applied whatever the profile says.
    func testNoTwoProfilesShareARow() {
        let rows = PlaybackProfile.allCases.map { knobs(under: $0) }

        XCTAssertEqual(Set(rows).count, PlaybackProfile.allCases.count, "\(rows)")
    }

    private func knobs(under profile: PlaybackProfile) -> Knobs {
        knobs(under: SuperPlayer.Configuration(profile: profile))
    }

    /// The knobs on the item a request made, on a player made with `configuration`.
    private func knobs(under configuration: SuperPlayer.Configuration) -> Knobs {
        let player = SuperPlayer(configuration: configuration)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        return Knobs(player.avPlayer.currentItem!, player.avPlayer)
    }
}

/// Every knob `StaticProfilePolicy` sets, as read off an engine.
private struct Knobs: Hashable, CustomStringConvertible {
    var forwardBufferSeconds: TimeInterval
    var peakBitRate: Double
    var maxResolution: CGSize
    var expensivePeakBitRate: Double
    var expensiveMaxResolution: CGSize
    /// `configuredTimeOffsetFromLive` in seconds, or nil while it is invalid — the playlist's own.
    var liveOffsetSeconds: Double?
    var preservesLiveOffset: Bool
    var waitsToMinimizeStalling: Bool

    @MainActor
    init(_ item: AVPlayerItem, _ player: AVPlayer) {
        forwardBufferSeconds = item.preferredForwardBufferDuration
        peakBitRate = item.preferredPeakBitRate
        maxResolution = item.preferredMaximumResolution
        expensivePeakBitRate = item.preferredPeakBitRateForExpensiveNetworks
        expensiveMaxResolution = item.preferredMaximumResolutionForExpensiveNetworks
        let offset = item.configuredTimeOffsetFromLive
        liveOffsetSeconds = offset.isValid ? offset.seconds : nil
        preservesLiveOffset = item.automaticallyPreservesTimeOffsetFromLive
        waitsToMinimizeStalling = player.automaticallyWaitsToMinimizeStalling
    }

    private init() {
        forwardBufferSeconds = 0
        peakBitRate = 0
        maxResolution = .zero
        expensivePeakBitRate = 0
        expensiveMaxResolution = .zero
        liveOffsetSeconds = nil
        preservesLiveOffset = false
        waitsToMinimizeStalling = true
    }

    /// Apple's documented defaults: a forward buffer of zero (the system's choice), no ceiling on
    /// any network, the playlist's own live offset, none preserved, and a player that waits.
    static let appleDefaults = Knobs()

    func hash(into hasher: inout Hasher) {
        hasher.combine(forwardBufferSeconds)
        hasher.combine(peakBitRate)
        hasher.combine(maxResolution.height)
        hasher.combine(liveOffsetSeconds)
        hasher.combine(preservesLiveOffset)
    }

    var description: String {
        "buffer \(forwardBufferSeconds) s, peak \(peakBitRate), max \(maxResolution), "
            + "expensive peak \(expensivePeakBitRate), expensive max \(expensiveMaxResolution), "
            + "live offset \(liveOffsetSeconds.map { "\($0) s" } ?? "the playlist's"), "
            + "preserved \(preservesLiveOffset), waits \(waitsToMinimizeStalling)"
    }
}
