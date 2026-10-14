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

import Foundation
import SuperPlayerCore
import XCTest

// `@testable` for the register and `liveObservations`, as `CollectorSignalRegisterTests` reads the
// collector's.
@testable import SuperPlayerTelemetry

/// The recorder's observed register (ADR-0020 rule 13), held as the collector's is: nothing before
/// attaching, the player's signals once and the current item's once while attached — however many
/// items came before — and nothing once detached. The "nowhere else" half is
/// `CollectorSignalRegisterTests`' scan, which admits this register's file beside its own.
///
/// No item here loads: the URL names a port nothing listens on.
@MainActor
final class RecorderSignalRegisterTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private var playerSignalsOnce: [RecorderSignal: Int] {
        Dictionary(
            uniqueKeysWithValues: RecorderSignal.registered.filter { !$0.isItemScoped }.map {
                ($0, 1)
            })
    }

    private var everySignalOnce: [RecorderSignal: Int] {
        Dictionary(uniqueKeysWithValues: RecorderSignal.registered.map { ($0, 1) })
    }

    /// The control: a recorder not attached has registered nothing, so the counts below are not
    /// ones that read "everything" regardless.
    func testARecorderNotAttachedObservesNothing() {
        let recorder = SessionTraceRecorder()
        let player = SuperPlayer()
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))

        XCTAssertEqual(recorder.liveObservations, [:])
        player.release()
    }

    func testAnAttachedPlayerWithNoItemHasOnlyThePlayersSignals() {
        let recorder = SessionTraceRecorder()
        let player = SuperPlayer()

        recorder.attach(to: player)

        XCTAssertEqual(recorder.liveObservations, playerSignalsOnce)
        recorder.detach()
        player.release()
    }

    /// The defect a register exists for: an outgoing item's registrations never removed.
    func testReplacingTheItemLeavesOneOfEach() {
        let recorder = SessionTraceRecorder()
        let player = SuperPlayer()
        recorder.attach(to: player)

        for contentId in ["a", "b", "c"] {
            player.setMediaRequest(MediaRequest(contentId: contentId, sources: [nowhere]))
        }
        player.setMediaURL(nowhere)

        XCTAssertEqual(recorder.liveObservations, everySignalOnce)
        recorder.detach()
        player.release()
    }

    /// A released player has no item, so only the player's signals are left; detaching takes those.
    func testAReleaseDropsTheItemsSignalsAndADetachDropsTheRest() {
        let recorder = SessionTraceRecorder()
        let player = SuperPlayer()
        recorder.attach(to: player)
        player.setMediaRequest(MediaRequest(contentId: "a", sources: [nowhere]))

        player.release()
        XCTAssertEqual(recorder.liveObservations, playerSignalsOnce)

        recorder.detach()
        XCTAssertEqual(recorder.liveObservations, [:])
    }

    /// The request streams are iOS 18's, so below it the register expects one fewer.
    func testTheRequestStreamsAreRegisteredWhereTheyExist() {
        if #available(iOS 18, tvOS 18, *) {
            XCTAssertEqual(RecorderSignal.registered, RecorderSignal.allCases)
        } else {
            XCTAssertFalse(RecorderSignal.registered.contains(.requestMetrics))
        }
    }
}
