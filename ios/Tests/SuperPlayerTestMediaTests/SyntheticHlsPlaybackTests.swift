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
import SuperPlayerTestMedia
import XCTest

/// The synthetic stream plays to its end in a stock `AVPlayer` on the simulator — the claim #398
/// asks of a port whose bytes were chosen for a decoder that never decodes them (Robolectric's
/// shadow codec passes bytes through, so on Android the ADTS payload is "a plausible frame size"
/// and nothing more).
///
/// Here rather than in the harness ticket (#399) because the answer decides the bytes, and it did:
/// the frames' payload was all zeros, and `AVPlayer` played that stream to its end too — while its
/// decoder refused every packet. Playing to the end is therefore necessary and not sufficient, and
/// `SyntheticAacDecodingTests` is the half that says the frames decode; both generators now write a
/// silent `raw_data_block`.
///
/// The stream is served from `StaticLoopbackOrigin`, a stopgap in this test target until #399's
/// origin exists, because `AVPlayer` loads HLS over HTTP and not from a `file:` URL — the Kotlin
/// `writeTo` route has no iOS twin, which that file says. Time is real and nothing is asserted
/// about it: the assertions are an outcome — the end was reached, an audio track was presented and
/// nothing failed — and the duration the playlists declare, which is a count of segments.
@MainActor
final class SyntheticHlsPlaybackTests: XCTestCase {

    /// Long enough for six seconds of media to play at 1× on a loaded CI host, and only a bound:
    /// a pass takes as long as the media does.
    private let playbackBound: TimeInterval = 60

    /// Three times the six seconds of media: long enough that a stream which could end would have.
    ///
    /// Shorter than `playbackBound` on purpose, because an inverted wait always spends its whole
    /// bound and `playbackBound` is a ceiling a pass never approaches — the two positive tests in
    /// this class finish in about the media's own length. A host slow enough to need more than
    /// eighteen seconds for six of media would weaken this control, and it would say so beside it:
    /// those two tests' own durations are in the same result bundle.
    private let missingEndBound: TimeInterval = 18

    func testTheOneVariantStreamPlaysToItsEndInAVPlayer() async throws {
        try await assertPlaysToItsEnd(SyntheticHlsStream.files(segmentCount: 3, variantCount: 1))
    }

    func testTheTwoVariantLadderPlaysToItsEndInAVPlayer() async throws {
        try await assertPlaysToItsEnd(SyntheticHlsStream.files(segmentCount: 3, variantCount: 2))
    }

    /// The control: the same origin and the same player, with one segment the playlist names not
    /// served, does not reach its end — so reaching it above is the bytes' doing and not a player
    /// that ends whatever it is given. `AVPlayer` stalls at the gap, raising no failure and logging
    /// no error a test can read, so what is waited for is an end that must not come, for a bound
    /// three times the media's length.
    func testAStreamWithASegmentMissingDoesNotPlayToItsEnd() async throws {
        var files = SyntheticHlsStream.files(segmentCount: 3, variantCount: 1)
        files[SyntheticHlsStream.segmentName(1)] = nil

        _ = try await play(files, untilEnd: false, bound: missingEndBound)
    }

    private func assertPlaysToItsEnd(_ files: [String: [UInt8]]) async throws {
        let item = try await play(files, untilEnd: true, bound: playbackBound)

        XCTAssertEqual(item.status, .readyToPlay, "\(String(describing: item.error))")
        XCTAssertNil(item.error)
        XCTAssertEqual(item.errorLog()?.events.count ?? 0, 0, "the access errors AVPlayer logged")
        XCTAssertTrue(
            item.tracks.contains { $0.assetTrack?.mediaType == .audio },
            "no audio track was presented")
        XCTAssertTrue(item.duration.isNumeric, "the item has no duration")
        if item.duration.isNumeric {
            XCTAssertEqual(
                Int64((item.duration.seconds * 1_000).rounded()),
                SyntheticHlsStream.durationMs(segmentCount: 3))
        }
    }

    /// Serves `files`, plays the multivariant playlist in a stock `AVPlayer`, and waits up to
    /// `bound` for the end — which must come when `untilEnd` and must not otherwise.
    private func play(_ files: [String: [UInt8]], untilEnd: Bool, bound: TimeInterval)
        async throws -> AVPlayerItem
    {
        let origin = try await StaticLoopbackOrigin(files: files)
        defer { origin.stop() }

        let item = AVPlayerItem(url: origin.url(SyntheticHlsStream.multivariantPlaylistName))
        let player = AVPlayer(playerItem: item)
        let ended = XCTNSNotificationExpectation(
            name: AVPlayerItem.didPlayToEndTimeNotification, object: item)
        ended.isInverted = !untilEnd
        player.play()
        await fulfillment(of: [ended], timeout: bound)
        player.pause()
        return item
    }
}
