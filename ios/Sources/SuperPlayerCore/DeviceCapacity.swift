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

/// **The one place a device is asked how many players it can afford to have alive at once** — the
/// Kotlin `DeviceCapacity.kt`, read a different way (`docs/ios-plan.md` §3.4's first row).
///
/// The counterpart of `EngineBinding.swift` for ``PlayerPool``: the pool decides nothing about its
/// size, it applies what this file reports. That split is the Kotlin file's, for its reason — a
/// constant chosen in the pool would be a constant wherever it lived, and the point of a bound is
/// that a three-year-old phone and a current one are not the same device.
///
/// **Android reads two limits and iOS can read one.** The Kotlin bound is the smaller of the
/// device's concurrent-decoder limit for the feed's codecs and a per-player budget against the app's
/// heap. Apple's platform reports no concurrent-decoder limit — no API answers "how many hardware
/// decoder sessions may this process hold", and none is documented — so there is no decoder term to
/// read, and no `setFeedCodecs` to read it over. What is left is:
///
/// - **Memory**, a per-player budget (``bytesPerPlayer``) against `os_proc_available_memory()`,
///   which is the iOS reading of the app's own allowance as `ActivityManager.memoryClass` is
///   Android's — the process's, never the device's RAM, the correction the Kotlin file argues.
/// - **A ceiling** (``ceiling``) standing where the decoder term would, argued from what this
///   library would ask of a pool rather than measured, because nothing here can measure it.
///
/// **Both numbers are i10's to measure** (#439, `docs/ios-plan.md` §7): peak memory per pooled
/// player and the point at which a device's decoders refuse the next player are readings of the
/// device matrix, and neither the simulator nor any test under `ios/check` can take them. Until then
/// each carries the reasoning it was chosen on, and says which part is borrowed from Android.
///
/// Deliberately not cached, for the Kotlin file's reason and one of iOS's own: Apple's header says
/// the reading "is only a snapshot at the time of the call. Caching the result is not advised." One
/// reading per pool, and a pool is made once per screen.
///
/// ref: `superplayer-core/src/main/kotlin/com/superplayer/core/DeviceCapacity.kt`
package enum DeviceCapacity {

    /// Reads the bytes this process may still dirty before the system ends it, or zero where the
    /// platform will not say. A type of its own so that a test states the device — the pool's
    /// `package` initialiser takes one — and ``processAvailableMemory`` stays the only reader that
    /// asks the platform.
    package typealias MemoryReader = @Sendable () -> UInt64

    /// The platform's answer: the process's dirty-memory limit less its footprint, now.
    ///
    /// Zero is two things in Apple's header — "the calling process is not an app" (an `xctest`
    /// runner is not) and "the calling process exceeds its memory limit" — and
    /// ``concurrentPlayers(availableMemoryBytes:)`` reads both as one player, which is right for
    /// both.
    ///
    /// ref: `<os/proc.h>`, `os_proc_available_memory`
    package static let processAvailableMemory: MemoryReader = {
        UInt64(os_proc_available_memory())
    }

    /// How many ``SuperPlayer`` instances a process with `availableMemoryBytes` left can plausibly
    /// keep alive at once: that memory over ``bytesPerPlayer``, no more than ``ceiling``, and never
    /// below ``minimum``.
    ///
    /// The floor is the Kotlin `MINIMUM_CAPACITY`'s, for its reason: a reading of zero is "I cannot
    /// tell you" or "there is nothing left", and a pool of none would show no video anywhere, which
    /// is a worse answer than one player recycled hard.
    package static func concurrentPlayers(availableMemoryBytes: UInt64) -> Int {
        let memoryTerm = availableMemoryBytes / bytesPerPlayer
        return max(minimum, Int(min(memoryTerm, UInt64(ceiling))))
    }

    /// What one live player is budgeted at, against the memory the process has left: 32 MiB.
    ///
    /// **The Kotlin `HEAP_BYTES_PER_PLAYER`, borrowed rather than derived.** On Android it is
    /// grounded in Media3's own buffer floor, doubled for what sits beside it; `AVPlayer` publishes no
    /// such floor and no per-player cost, and its buffer is sized in seconds
    /// (`preferredForwardBufferDuration`) rather than bytes, so the iOS number has no grounding of
    /// its own until i10 measures one. What can be said for it now: a pooled player in a feed buffers
    /// the seconds of a short item rather than a long watch, as the Kotlin reasoning says, and the
    /// budget is per player rather than a pool size — it says what one costs, and the process's own
    /// allowance decides the rest.
    ///
    /// It also budgets against *what is left*, where Android's budgets against the whole heap
    /// allowance: `os_proc_available_memory()` is the limit less the footprint, so an app that has
    /// already spent its memory gets fewer players, which is the direction a bound should move.
    package static let bytesPerPlayer: UInt64 = 32 * 1024 * 1024

    /// The most players a pool is ever made with, standing where Android's decoder term stands:
    /// four.
    ///
    /// iOS reports no decoder limit, so this is not one; it is the most players this library has a
    /// use for, which a memory term alone would not stop at. On any current device that term is far
    /// past any plausible decoder count — 2 GiB left over 32 MiB is 64 players — so without a
    /// ceiling the pool's bound would be the memory's and every row of a long feed could hold a
    /// decoder until the hardware refused one. Four is the widest window the profile table prefetches
    /// for: `shortForm`'s preload row on Android (`StaticProfilePolicy.kt`) is the current item, two
    /// ahead and one behind, and that window is what a pool exists to keep warm. One number for
    /// every profile, because it stands for the device and not for the content — as the Kotlin
    /// decoder term reads the device whatever profile the pool was built with. A screen that shows
    /// more than four videos at once is one this ceiling refuses, deliberately, until i10 (#439)
    /// measures what a device's decoders sustain and either raises it or replaces it with a reading.
    package static let ceiling = 4

    /// The floor, and the answer wherever the platform reports nothing usable: the Kotlin
    /// `MINIMUM_CAPACITY`.
    package static let minimum = 1
}
