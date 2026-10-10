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

import os

/// The iOS test harness: one per test, owning the loopback origins that test plays from and the
/// bounded wait every assertion on real time goes through (ADR-0021 rules 2 and 4).
///
/// The port of `superplayer-testkit`'s `PlaybackHarness` in what it is *for* — a test states
/// content, plays it, and counts what was fetched — and not in mechanism. The Android harness puts
/// fakes in the engine's transport slot and owns the clock; `AVPlayer` has neither a slot nor a
/// clock anyone else can drive, so this harness serves real HTTP on real time, and a test waits for
/// a condition rather than advancing to one.
///
/// ```swift
/// let harness = PlaybackHarness()
/// let origin = try await harness.serve(.hls())
/// let player = AVPlayer(url: origin.sourceURL)
/// player.play()
/// try await harness.until("the position passes one second", timeout: .seconds(30)) {
///     player.currentTime().seconds > 1
/// }
/// XCTAssertTrue(origin.networkRequests().contains { $0.kind == .mediaSegment })
/// harness.stop()
/// ```
///
/// **It names no AVFoundation type**, and builds no player: this ticket has no `SuperPlayer` to
/// build, and a stock `AVPlayer` takes nothing from a harness but a URL. That is the stronger form of
/// the Android harness's claim, whose public API names only Media3's stable `Player` and the
/// `ExoPlayer` its stock arm returns. When `SuperPlayer` exists, a `buildPlayer` here will name it,
/// and still not `AVPlayer`. The Foundation type it names is `URL`, because that is what a player is
/// handed.
public final class PlaybackHarness: Sendable {

    private let origins = OSAllocatedUnfairLock(initialState: [LoopbackOrigin]())

    public init() {}

    /// Starts an origin serving `content` on a port of its own, and returns it once it is listening.
    ///
    /// Each call is a new origin, so a test that needs two hosts serves twice; the origins stop with
    /// ``stop()``. `FaultScript` and `ThroughputTrace` arrive as arguments here with #410.
    public func serve(_ content: TestContent) async throws -> LoopbackOrigin {
        let origin = try await LoopbackOrigin.start(serving: content)
        origins.withLock { $0.append(origin) }
        return origin
    }

    /// Stops every origin this harness started, closing their connections. A test calls it in
    /// `tearDown`, so the next test's origins are the only ones listening.
    public func stop() {
        let started = origins.withLock { origins in
            defer { origins = [] }
            return origins
        }
        for origin in started { origin.stop() }
    }

    /// How often ``until(_:timeout:isolation:_:)`` reads its condition: a twentieth of a second,
    /// fine enough that a wait outlasts its condition by less than any step a test measures in —
    /// the media's own steps are two-second segments — and coarse enough that polling `AVPlayer`
    /// from the main actor costs nothing it would notice.
    public static let pollInterval: Duration = .milliseconds(50)

    /// Waits until `condition` holds, reading it every ``pollInterval`` on the caller's own actor,
    /// and throws ``ConditionTimeout`` naming `description` if `timeout` passes first.
    ///
    /// The one way a test here waits on real time (ADR-0021 rule 4): the bound is a ceiling a pass
    /// does not approach, never an assertion about how long anything took, so a loaded CI host makes
    /// a test slower and not red. The condition runs in the caller's isolation — the main actor, for
    /// a test reading an `AVPlayer` — which is why it need not be `Sendable`.
    public func until(
        _ description: String = "the condition",
        timeout: Duration,
        isolation: isolated (any Actor)? = #isolation,
        _ condition: () throws -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        while try !condition() {
            guard clock.now < deadline else {
                throw ConditionTimeout(description: description, timeout: timeout)
            }
            try await Task.sleep(for: Self.pollInterval)
        }
    }

    /// ``until(_:timeout:isolation:_:)`` for a condition that has to await something itself — a
    /// fetch from an origin, say — and therefore crosses isolation and must be `Sendable`. A wait on
    /// a player's state is the other one, whose condition runs on the caller's actor.
    public func until(
        _ description: String = "the condition",
        timeout: Duration,
        _ condition: @Sendable () async throws -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        while try await !condition() {
            guard clock.now < deadline else {
                throw ConditionTimeout(description: description, timeout: timeout)
            }
            try await Task.sleep(for: Self.pollInterval)
        }
    }

    deinit { stop() }
}

/// A bounded wait whose condition did not hold in time.
public struct ConditionTimeout: Error, CustomStringConvertible {

    /// What was waited for, in the test's words.
    public let condition: String

    /// The bound that passed.
    public let timeout: Duration

    init(description: String, timeout: Duration) {
        self.condition = description
        self.timeout = timeout
    }

    public var description: String { "\(condition) did not hold within \(timeout)" }
}
