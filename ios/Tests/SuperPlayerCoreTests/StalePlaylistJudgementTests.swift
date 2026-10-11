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

import XCTest

// `@testable` for `StalePlaylistJudgement`, the judgement over values: its bounds are stated here
// at the millisecond, which a playing test may not do (ADR-0021 rule 4), and the coarser judge below
// iOS 18 has no runtime here to play on. `StaleLivePlaylistPlaybackTests` is the playing half.
@testable import SuperPlayerCore

/// The stale-live-playlist judgement (#421, `StaleLivePlaylistDetection.swift`): the Kotlin
/// `LivePlaylistRevalidation`'s give-up bound and likely cause over AVFoundation's evidence, with
/// controls on each side of every bound.
final class StalePlaylistJudgementTests: XCTestCase {

    private let playlist = URL(string: "http://127.0.0.1/live/media.m3u8")!
    private let start = Date(timeIntervalSinceReferenceDate: 0)

    private func answer(
        atMs: Int64, multivariant: Bool = false, succeeded: Bool = true,
        cacheControl: String? = nil, age: String? = nil
    ) -> PlaylistAnswer {
        PlaylistAnswer(
            url: playlist, isMultivariant: multivariant,
            answeredAt: start.addingTimeInterval(Double(atMs) / 1_000), succeeded: succeeded,
            cacheControl: cacheControl, age: age)
    }

    /// Two-second segments read, and the edge last moving at zero.
    private func judgement() -> StalePlaylistJudgement {
        var judgement = StalePlaylistJudgement()
        judgement.segmentAnswered(durationSeconds: 2.0)
        judgement.liveEdgeRead(6.0, at: start)
        return judgement
    }

    func testTheBoundsAreTheKotlinOnesInOrder() {
        XCTAssertEqual(StalePlaylistJudgement.updateBoundTargetDurations, 1.5)
        XCTAssertEqual(StalePlaylistJudgement.giveUpBoundTargetDurations, 2.5)
        XCTAssertLessThan(
            StalePlaylistJudgement.updateBoundTargetDurations,
            StalePlaylistJudgement.giveUpBoundTargetDurations)
    }

    func testTheTargetDurationIsTheLongestSegmentRoundedAndNeverLess() {
        var judgement = StalePlaylistJudgement()
        XCTAssertNil(judgement.targetDurationMs)
        judgement.segmentAnswered(durationSeconds: 5.96)
        judgement.segmentAnswered(durationSeconds: 6.006)
        XCTAssertEqual(judgement.targetDurationMs, 6_006)
        var short = StalePlaylistJudgement()
        short.segmentAnswered(durationSeconds: 0.4)
        XCTAssertEqual(short.targetDurationMs, 400)
        var ragged = StalePlaylistJudgement()
        ragged.segmentAnswered(durationSeconds: 3.6)
        XCTAssertEqual(ragged.targetDurationMs, 4_000)
    }

    func testAnEdgeUnmovedPastTwoAndAHalfTargetDurationsEndsTheSession() throws {
        var judgement = judgement()
        XCTAssertNil(judgement.playlistAnswered(answer(atMs: 2_000), isLive: true))
        // At the bound exactly, not past it.
        XCTAssertNil(judgement.playlistAnswered(answer(atMs: 5_000), isLive: true))
        let stale = try XCTUnwrap(judgement.playlistAnswered(answer(atMs: 5_001), isLive: true))
        XCTAssertEqual(stale.playlistURL, playlist)
        XCTAssertEqual(stale.targetDurationMs, 2_000)
        XCTAssertEqual(stale.unchangedForMs, 5_001)
        XCTAssertEqual(stale.cacheBypassingReloads, 0)
        XCTAssertEqual(stale.likelyCause, .origin)
    }

    func testAnEdgeThatMovesStartsTheHistoryOver() {
        var judgement = judgement()
        _ = judgement.playlistAnswered(answer(atMs: 2_000), isLive: true)
        judgement.liveEdgeRead(8.0, at: start.addingTimeInterval(4))
        // An earlier or equal edge is no progress.
        judgement.liveEdgeRead(8.0, at: start.addingTimeInterval(7))
        XCTAssertNil(judgement.playlistAnswered(answer(atMs: 8_000), isLive: true))
        XCTAssertNil(judgement.playlistAnswered(answer(atMs: 9_000), isLive: true))
        XCTAssertNotNil(judgement.playlistAnswered(answer(atMs: 9_001), isLive: true))
    }

    func testTheFirstAnswerAfterTheEdgeMovedIsNeverTheOneJudged() {
        // A player away for a while: its first answer back may carry the segments it missed, and the
        // edge reads them a moment later.
        var judgement = judgement()
        XCTAssertNil(judgement.playlistAnswered(answer(atMs: 60_000), isLive: true))
        judgement.liveEdgeRead(64.0, at: start.addingTimeInterval(60.2))
        XCTAssertNil(judgement.playlistAnswered(answer(atMs: 62_000), isLive: true))
    }

    func testNothingIsJudgedThatIsNotALiveMediaPlaylistAnsweredWithAnEdgeAndATarget() {
        var judgement = judgement()
        _ = judgement.playlistAnswered(answer(atMs: 1_000), isLive: true)
        XCTAssertNil(judgement.playlistAnswered(answer(atMs: 9_000), isLive: false))
        XCTAssertNil(
            judgement.playlistAnswered(answer(atMs: 9_000, multivariant: true), isLive: true))
        XCTAssertNil(
            judgement.playlistAnswered(answer(atMs: 9_000, succeeded: false), isLive: true))

        var noEdge = StalePlaylistJudgement()
        noEdge.segmentAnswered(durationSeconds: 2.0)
        _ = noEdge.playlistAnswered(answer(atMs: 1_000), isLive: true)
        XCTAssertNil(noEdge.playlistAnswered(answer(atMs: 60_000), isLive: true))

        var noTarget = StalePlaylistJudgement()
        noTarget.liveEdgeRead(6.0, at: start)
        _ = noTarget.playlistAnswered(answer(atMs: 1_000), isLive: true)
        XCTAssertNil(noTarget.playlistAnswered(answer(atMs: 60_000), isLive: true))
    }

    func testACacheLifetimePastTheUpdateBoundNamesTheIntermediary() {
        var judgement = judgement()
        _ = judgement.playlistAnswered(
            answer(atMs: 2_000, cacheControl: "public, max-age=600"), isLive: true)
        // Sticky: the answer that ends the session need not repeat it.
        let stale = judgement.playlistAnswered(answer(atMs: 6_000), isLive: true)
        XCTAssertEqual(stale?.likelyCause, .intermediaryCache)
        XCTAssertNil(stale?.servedCacheControl)
    }

    func testAnAgePastTheUpdateBoundNamesTheIntermediaryAndOneWithinItDoesNot() {
        var aged = judgement()
        _ = aged.playlistAnswered(answer(atMs: 2_000), isLive: true)
        XCTAssertEqual(
            aged.playlistAnswered(answer(atMs: 6_000, age: " 4 "), isLive: true)?.likelyCause,
            .intermediaryCache)

        var fresh = judgement()
        _ = fresh.playlistAnswered(answer(atMs: 2_000), isLive: true)
        XCTAssertEqual(
            fresh.playlistAnswered(answer(atMs: 6_000, age: "3"), isLive: true)?.likelyCause,
            .origin)
    }

    func testTheCacheLifetimeRuleIsTheKotlinOne() {
        let rule = StalePlaylistJudgement.cachedPastTheUpdateBoundSeconds
        XCTAssertEqual(rule("public, max-age=600", 2_000), 600)
        XCTAssertNil(rule("max-age=3", 2_000), "within one and a half target durations")
        XCTAssertEqual(rule("max-age=1, s-maxage=60", 2_000), 60, "s-maxage governs a CDN")
        XCTAssertNil(rule("s-maxage=1, max-age=600", 2_000))
        XCTAssertNil(rule("max-age=600, no-cache", 2_000))
        XCTAssertNil(rule("no-store, max-age=600", 2_000))
        XCTAssertEqual(rule("MAX-AGE=\"600\"", 2_000), 600)
        XCTAssertNil(rule(nil, 2_000))
    }

    func testBelowEighteenTheThirdUnchangedEntryEndsTheSessionWithWhatTheLogNames() throws {
        var judgement = StalePlaylistJudgement()
        judgement.liveEdgeRead(6.0, at: start)
        XCTAssertNil(judgement.unchangedLogged(playlist: playlist, isLive: true))
        XCTAssertNil(judgement.unchangedLogged(playlist: playlist, isLive: true))
        let stale = try XCTUnwrap(judgement.unchangedLogged(playlist: playlist, isLive: true))
        XCTAssertEqual(stale.playlistURL, playlist)
        XCTAssertNil(stale.targetDurationMs)
        XCTAssertNil(stale.unchangedForMs)
        XCTAssertNil(stale.servedCacheControl)
        XCTAssertNil(stale.likelyCause)
    }

    func testBelowEighteenAnEdgeThatMovesForgivesTheEntriesBeforeIt() {
        var judgement = StalePlaylistJudgement()
        _ = judgement.unchangedLogged(playlist: playlist, isLive: true)
        _ = judgement.unchangedLogged(playlist: playlist, isLive: true)
        judgement.liveEdgeRead(8.0, at: start)
        XCTAssertNil(judgement.unchangedLogged(playlist: playlist, isLive: true))
        XCTAssertNil(judgement.unchangedLogged(playlist: nil, isLive: true))
        XCTAssertNil(judgement.unchangedLogged(playlist: playlist, isLive: false))
    }

    func testTheDescriptionSaysWhatWasObservedAndOnlyThat() {
        let full = StaleLivePlaylistError(
            playlistURL: playlist, targetDurationMs: 2_000, unchangedForMs: 6_000,
            servedCacheControl: "public, max-age=600", likelyCause: .intermediaryCache)
        XCTAssertTrue(full.description.contains("6000 ms (target duration 2000 ms)"))
        XCTAssertTrue(full.description.contains("Cache-Control: public, max-age=600"))
        XCTAssertTrue(full.description.contains("intermediary cache"))
        let coarse = StaleLivePlaylistError(
            playlistURL: playlist, targetDurationMs: nil, unchangedForMs: nil,
            servedCacheControl: nil, likelyCause: nil)
        XCTAssertFalse(coarse.description.contains("ms"))
        XCTAssertFalse(coarse.description.contains("Likely"))
    }
}
