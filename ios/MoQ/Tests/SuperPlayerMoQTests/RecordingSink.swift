//
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

/// A sink that keeps what it is handed, and lets a test wait for frames or for the end — the
/// Kotlin tests' `RecordingSink`, shared by this target's two test classes that subscribe.
///
/// Its waits are **events** with a ceiling, never a pace (ADR-0021 rule 4): a test asks for a count
/// and either gets it or reads what it got. `@unchecked Sendable`: every `var` is under `condition`.
final class RecordingSink: FrameSink, @unchecked Sendable {

    /// How long any wait here lasts at most: wide, because it is a ceiling a passing run never
    /// approaches, and finite so a defect fails as itself rather than as a hung test.
    static let waitBound: TimeInterval = 10

    private let condition = NSCondition()
    private var _tracks: [RealtimeTrack] = []
    private var _frames: [EncodedFrame] = []
    private var _terminal: String?
    private var _terminalCallbacks = 0
    private var _failure: (any Error)?
    private var _callbacks = 0

    var tracks: [RealtimeTrack] { condition.withLock { _tracks } }
    var frames: [EncodedFrame] { condition.withLock { _frames } }

    /// `"onEnded"` or `"onError"`, whichever came first, or nil.
    var terminal: String? { condition.withLock { _terminal } }
    var terminalCallbacks: Int { condition.withLock { _terminalCallbacks } }
    var failure: (any Error)? { condition.withLock { _failure } }

    /// Every callback of any kind, which is what "nothing arrived after cancel" is read off.
    var callbacks: Int { condition.withLock { _callbacks } }

    func onTracks(_ tracks: [RealtimeTrack]) {
        condition.withLock {
            _callbacks += 1
            _tracks = tracks
            condition.broadcast()
        }
    }

    func onFrame(_ frame: EncodedFrame) {
        condition.withLock {
            _callbacks += 1
            _frames.append(frame)
            condition.broadcast()
        }
    }

    func onEnded() { end("onEnded", nil) }

    func onError(_ error: any Error) { end("onError", error) }

    private func end(_ which: String, _ error: (any Error)?) {
        condition.withLock {
            _callbacks += 1
            _terminalCallbacks += 1
            if _terminal == nil {
                _terminal = which
                _failure = error
            }
            condition.broadcast()
        }
    }

    /// Waits for `count` frames or the end of the subscription, whichever is first, and answers
    /// whether either arrived: a subscription that is over delivers no more frames, so a test
    /// waiting for some stops waiting rather than paying the whole bound.
    @discardableResult
    func awaitFrames(_ count: Int) -> Bool {
        waitUntil { $0._frames.count >= count || $0._terminal != nil }
    }

    /// Waits for `onEnded()` or `onError(_:)`, and answers whether one arrived.
    @discardableResult
    func awaitTerminal() -> Bool { waitUntil { $0._terminal != nil } }

    private func waitUntil(_ done: (RecordingSink) -> Bool) -> Bool {
        let deadline = Date(timeIntervalSinceNow: Self.waitBound)
        return condition.withLock {
            while !done(self) {
                if !condition.wait(until: deadline) { return done(self) }
            }
            return true
        }
    }
}
