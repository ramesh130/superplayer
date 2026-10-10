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
import XCTest

// `@testable` for two internals, listed in `docs/testing.md`'s *The iOS harness*: the snapshot's
// memberwise initializer, so the stored format can be pinned for a value with every field set — a
// public `saveSnapshot()` cannot produce a chosen position without playing, which is
// `SnapshotPlaybackTests`' subject — and its remembered-position list, whose *order* is what a
// restored memory evicts by, which `.resumeFromLastKnown` can show only one id at a time.
@testable import SuperPlayerCore

/// `PlaybackSnapshot` as a value and as a stored format: what a player with nothing to name saves,
/// the encoded document pinned as literal JSON, and a malformed document refused on decoding.
@MainActor
final class PlaybackSnapshotTests: XCTestCase {

    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private let full = PlaybackSnapshot(
        request: MediaRequest(
            contentId: "catalog:episode:1138",
            sources: [URL(string: "https://cdn-a.example.com/1138/master.m3u8")!],
            startPosition: .resumeFromLastKnown,
            title: "The Long Way Round",
            subtitle: "Season 2",
            artworkURL: URL(string: "https://img.example.com/1138.jpg")!),
        positionMs: 61_000,
        playWhenReady: true,
        rememberedPositions: [
            .init(contentId: "catalog:episode:1137", positionMs: 0),
            .init(contentId: "catalog:episode:1138", positionMs: 61_000),
        ])

    /// The stored format, whole. A change here is a change to what every app already wrote; the
    /// type's doc comment says what may and may not move it.
    func testTheEncodedDocumentIsPinned() throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]

        let json = String(decoding: try encoder.encode(full), as: UTF8.self)

        XCTAssertEqual(
            json,
            #"{"playWhenReady":true,"positionMs":61000,"rememberedPositions":["#
                + #"{"contentId":"catalog:episode:1137","positionMs":0},"#
                + #"{"contentId":"catalog:episode:1138","positionMs":61000}],"#
                + #""request":{"artworkURL":"https://img.example.com/1138.jpg","#
                + #""contentId":"catalog:episode:1138","#
                + #""sources":["https://cdn-a.example.com/1138/master.m3u8"],"#
                + #""startPosition":{"resumeFromLastKnown":{}},"#
                + #""subtitle":"Season 2","title":"The Long Way Round"}}"#)
    }

    /// The other two start positions' shapes, which the document above cannot show at once.
    func testTheStartPositionsEncodeByCaseName() throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]

        XCTAssertEqual(
            String(
                decoding: try encoder.encode(MediaRequest.StartPosition.beginning), as: UTF8.self),
            #"{"beginning":{}}"#)
        XCTAssertEqual(
            String(
                decoding: try encoder.encode(MediaRequest.StartPosition.at(positionMs: 5_000)),
                as: UTF8.self),
            #"{"at":{"positionMs":5000}}"#)
    }

    func testASnapshotSurvivesEncodingAndDecodingWhole() throws {
        let decoded = try JSONDecoder().decode(
            PlaybackSnapshot.self, from: JSONEncoder().encode(full))

        XCTAssertEqual(decoded, full)
        XCTAssertEqual(decoded.rememberedPositions, full.rememberedPositions, "the order")
    }

    /// A document whose request is not a request is refused rather than restored as something
    /// invented — the decoder's `DecodingError`, as `MediaRequest`'s own.
    func testADocumentWithABlankContentIdIsRefused() {
        let json =
            #"{"playWhenReady":false,"positionMs":0,"rememberedPositions":[],"#
            + #""request":{"contentId":" ","sources":["https://a.example/x.m3u8"],"#
            + #""startPosition":{"beginning":{}}}}"#

        XCTAssertThrowsError(
            try JSONDecoder().decode(PlaybackSnapshot.self, from: Data(json.utf8))
        ) { error in
            guard case DecodingError.dataCorrupted = error else {
                return XCTFail("expected dataCorrupted, got \(error)")
            }
        }
    }

    /// A player given nothing saves nothing to name, no position, no intent and no memory.
    func testAPlayerGivenNothingSavesAnEmptySnapshot() {
        let snapshot = SuperPlayer().saveSnapshot()

        XCTAssertNil(snapshot.request)
        XCTAssertNil(snapshot.positionMs)
        XCTAssertFalse(snapshot.playWhenReady)
        XCTAssertEqual(snapshot.rememberedPositions, [])
    }

    /// Content from a bare URL has no identity, so the snapshot names none — while the memory of
    /// content that had one before it still travels.
    func testABareURLItemIsNotNamed() {
        let player = SuperPlayer()
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        player.setMediaURL(nowhere)

        let snapshot = player.saveSnapshot()

        XCTAssertNil(snapshot.request)
        XCTAssertNil(snapshot.positionMs)
        XCTAssertEqual(snapshot.rememberedPositions.map(\.contentId), ["a"])
    }

    /// A request whose item was replaced through `avPlayer` directly describes nothing loaded, so
    /// it is not named — checked against the item, not trusted from the bookkeeping.
    func testARequestReplacedBehindTheFacadeIsNotNamed() {
        let player = SuperPlayer()
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        player.avPlayer.replaceCurrentItem(with: AVPlayerItem(url: nowhere))

        XCTAssertNil(player.saveSnapshot().request)
    }

    /// The current content is folded into the memory as its most recent use, and taking the
    /// snapshot changes nothing: a second snapshot is the first.
    func testTheCurrentContentIsTheMostRecentEntryAndSavingChangesNothing() {
        let player = SuperPlayer()
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))
        player.setMediaRequest(MediaRequest(contentId: "b", sources: [nowhere]))

        let first = player.saveSnapshot()

        XCTAssertEqual(first.request?.contentId, "b")
        XCTAssertEqual(first.rememberedPositions.map(\.contentId), ["a", "b"])
        XCTAssertEqual(player.saveSnapshot(), first)
    }

    /// Restoring puts the memory in place in the saved order and names the saved content — read
    /// back as a snapshot of the new player.
    func testARestoredPlayerSavesTheSameContentAndMemory() {
        let restored = SuperPlayer()
        let saved = PlaybackSnapshot(
            request: MediaRequest(contentId: "b", sources: [nowhere]), positionMs: 0,
            playWhenReady: false,
            rememberedPositions: [
                .init(contentId: "a", positionMs: 4_000), .init(contentId: "b", positionMs: 0),
            ])

        restored.restoreSnapshot(saved)

        XCTAssertEqual(restored.currentContentId, "b")
        XCTAssertEqual(restored.saveSnapshot().rememberedPositions, saved.rememberedPositions)
    }
}
