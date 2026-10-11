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

import SuperPlayerCore
import os

/// The per-transport throughput estimates a process keeps in memory: ADR-0009 rule 8's holder and
/// rule 9's reseed — the Kotlin `EstimateMemory`, and its rules unchanged.
///
/// **One per process** (``process``): the estimate is a property of the device's network rather than
/// of one player, so a pool's players share one memory. **Keyed by ``NetworkTransport`` and nothing
/// finer** — no SSID, no cell, no place — so the memory is bounded by the type and identifies no
/// network. **In memory, and nowhere else**: nothing is written anywhere and process death forgets
/// everything, so ADR-0006 rule 2 is intact. The key is the whole transport, generation included,
/// so a personal hotspot (`.cellular(nil)` on iOS) is a window apart from the device's own LTE.
///
/// ## What an old entry is worth
///
/// The Kotlin rule: a measurement is trusted as it stands for ``freshAgeMs`` after its newest
/// sample, forgotten once ``staleAgeMs`` old, and between the two its *level* — the mean and the
/// conservative percentile — decays linearly toward the transport's cold default, while the spread
/// and the sample count stay as measured.
///
/// Every operation takes the time as an argument, on the monotonic clock `ConditionsBinding`
/// measures on, so the aging is asserted on stated times rather than by waiting (`EstimateMemoryTests`).
/// Thread-safe behind one lock: players record on the main actor, and nothing promises a reader is
/// there.
package final class EstimateMemory: Sendable {

    /// Where a recorded sample is, so the reader that recorded it can revise it
    /// (``revise(_:to:atMs:)``).
    package struct SampleToken: Sendable, Hashable {
        let transport: NetworkTransport
        let id: UInt64
    }

    private struct State: Sendable {
        var windows: [NetworkTransport: SampleWindow] = [:]
        var nextId: UInt64 = 0
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    private let coldDefaultBps: @Sendable (NetworkTransport) -> Int64

    /// A memory of its own, for a test that must not share the process's; `coldDefaultBps` states
    /// the table it ages toward.
    package init(
        coldDefaultBps: @escaping @Sendable (NetworkTransport) -> Int64 = ColdDefaults.bps(for:)
    ) {
        self.coldDefaultBps = coldDefaultBps
    }

    /// The one memory the process keeps, which every adaptive player's oracle reads and writes.
    package static let process = EstimateMemory()

    /// Records a sample of `bps` taken on `transport` at `nowMs`.
    @discardableResult
    package func record(_ bps: Int64, on transport: NetworkTransport, atMs nowMs: Int64)
        -> SampleToken
    {
        state.withLock { state in
            let id = state.nextId
            state.nextId += 1
            var window = state.windows[transport] ?? SampleWindow()
            Self.forgetIfStale(&window, atMs: nowMs)
            window.add(bps, atMs: nowMs, id: id)
            state.windows[transport] = window
            return SampleToken(transport: transport, id: id)
        }
    }

    /// Replaces the figure `token` recorded with `bps`, measured at `nowMs`, and answers where it now
    /// is. A sample no longer in its window — pushed out, or forgotten as stale — is recorded again
    /// as a new one: the figure is a new measurement either way, and only where it sits differs.
    package func revise(_ token: SampleToken, to bps: Int64, atMs nowMs: Int64) -> SampleToken {
        let revised = state.withLock { state in
            guard var window = state.windows[token.transport] else { return false }
            Self.forgetIfStale(&window, atMs: nowMs)
            let found = window.revise(token.id, to: bps, atMs: nowMs)
            state.windows[token.transport] = window
            return found
        }
        return revised ? token : record(bps, on: token.transport, atMs: nowMs)
    }

    /// What `transport` is believed to deliver as of `nowMs`: the window's own readings when fresh,
    /// the cold default when there is nothing recent, and the decay between. Never nil, for the
    /// Kotlin reason: a transport nothing was measured on still has a cold default.
    package func estimate(for transport: NetworkTransport, atMs nowMs: Int64) -> ThroughputEstimate
    {
        let coldDefault = coldDefaultBps(transport)
        let window: SampleWindow? = state.withLock { state in
            guard var window = state.windows[transport] else { return nil }
            Self.forgetIfStale(&window, atMs: nowMs)
            state.windows[transport] = window
            return window
        }
        guard let window, let newestAtMs = window.newestAtMs, let readings = window.readings
        else {
            // Unknown spread rather than zero, for the Kotlin reason: a policy given a spread of zero
            // would size for a network that never varies.
            return ThroughputEstimate(
                meanBps: coldDefault, spreadBps: nil, conservativeBps: nil, sampleCount: 0,
                newestSampleAgeMs: 0)
        }
        let ageMs = nowMs - newestAtMs
        let trust = Self.trust(atAgeMs: ageMs)
        return ThroughputEstimate(
            meanBps: Self.decayed(readings.meanBps, toward: coldDefault, trust: trust),
            spreadBps: readings.spreadBps,
            conservativeBps: Self.decayed(
                readings.conservativeBps, toward: coldDefault, trust: trust),
            sampleCount: window.count, newestSampleAgeMs: ageMs)
    }

    /// Forgets every transport's samples — for the benchmark, whose adaptive sessions each start
    /// cold as the Kotlin runner's do after idling its clock past ``staleAgeMs``, and for a test.
    package func forget() {
        state.withLock { $0.windows.removeAll() }
    }

    /// Forgets `window` once its newest sample is ``staleAgeMs`` old — or taken after `nowMs`, which
    /// only a clock that restarted produces: the Kotlin `forgetIfStale`, for its reason.
    private static func forgetIfStale(_ window: inout SampleWindow, atMs nowMs: Int64) {
        guard let newestAtMs = window.newestAtMs else { return }
        if nowMs < newestAtMs || nowMs - newestAtMs >= staleAgeMs { window.clear() }
    }

    /// How far a measurement `ageMs` old is trusted over the cold default: 1 while fresh, 0 when
    /// stale, linear between.
    private static func trust(atAgeMs ageMs: Int64) -> Double {
        if ageMs <= freshAgeMs { return 1 }
        if ageMs >= staleAgeMs { return 0 }
        return Double(staleAgeMs - ageMs) / Double(staleAgeMs - freshAgeMs)
    }

    /// `measured` weighted by `trust` against `coldDefault`, rounded down.
    private static func decayed(_ measured: Int64, toward coldDefault: Int64, trust: Double)
        -> Int64
    {
        trust == 1 ? measured : Int64(Double(measured) * trust + Double(coldDefault) * (1 - trust))
    }

    /// How long a measurement is used as it stands: the Kotlin `FRESH_AGE_MS`, two minutes, for its
    /// reason — the gap between finishing one title and starting the next on the same sofa, where
    /// reseeding from what was just measured is clearly better than a table.
    /// ref: derivation (CONTRIBUTING.md rule 4), in `superplayer-abr`'s `EstimateMemory.kt`.
    package static let freshAgeMs: Int64 = 2 * 60_000

    /// How old a measurement is before it is worth nothing over the cold default: the Kotlin
    /// `STALE_AGE_MS`, fifteen minutes, for its reason — a cellular network at walking pace has
    /// crossed cells in that time, and a memory keyed by transport cannot tell WiFi's horizon from
    /// cellular's, so it takes the shorter for both.
    /// ref: derivation (CONTRIBUTING.md rule 4), in `superplayer-abr`'s `EstimateMemory.kt`.
    package static let staleAgeMs: Int64 = 15 * 60_000
}
