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

/// ADR-0010 rule 11's memory guard: how much of a decided window the process's memory admits, and
/// the release of all of it when the platform asks for memory back — the Kotlin `MemoryGuard`, and
/// a platform rule rather than policy for its reason (ADR-0006 rule 1): no content has a different
/// right answer to a memory warning. A policy sees the memory reading as a condition and may decide
/// less; this is what stops one deciding more.
///
/// **The cap** admits the window in its priority order, costing each item at
/// ``bytes(for:held:)``, until the next would pass ``preloadShareOfMemory`` of the memory the
/// process has left — `os_proc_available_memory()`, the reading `DeviceCapacity.swift` bounds the
/// pool with, where the Kotlin guard reads the heap budget — so the items dropped are the ones the
/// scroll is least likely to reach. A reading of zero, which Apple's header gives both for "not an
/// app" and for "over the limit", is admitted ``constrainedDeviceItems`` instead: the Kotlin
/// guard's answer for a device reporting no heap, since iOS has no low-RAM flag to read beside it.
///
/// **The release** is on `UIApplication.didReceiveMemoryWarningNotification`, the one memory signal
/// an iOS app is sent, where the Kotlin guard listens for `onTrimMemory` at
/// `TRIM_MEMORY_RUNNING_LOW` and above. The guard then admits nothing until ``resume()``, which the
/// coordinator calls on the feed's next input.
///
/// ref: https://developer.apple.com/documentation/uikit/uiapplication/didreceivememorywarningnotification
/// ref: `<os/proc.h>`, `os_proc_available_memory`
@MainActor
final class MemoryGuard {

    /// What prefetch may spend, or nil where the reading is zero.
    private let budgetBytes: UInt64?

    /// Whether a memory warning has released everything and nothing is admitted until
    /// ``resume()``.
    private(set) var trimmed = false

    private var observation: PreloadObservation?

    /// Reads `availableMemory` once, as the coordinator is made — the Kotlin guard reads its budget
    /// once too — so the window a feed is admitted does not shrink with each row it plays.
    init(availableMemory: @Sendable () -> UInt64) {
        let available = availableMemory()
        budgetBytes = available == 0 ? nil : available / Self.preloadShareOfMemory
    }

    /// Starts listening for memory warnings; `onRelease` runs at each one the guard was not already
    /// trimmed for.
    func register(onRelease: @escaping @MainActor @Sendable () -> Void) {
        observation = PreloadObservation.registerMemoryWarning { [weak self] in
            guard let self, !self.trimmed else { return }
            self.trimmed = true
            onRelease()
        }
    }

    func unregister() {
        observation = nil
    }

    /// The feed is moving again: admit what memory allows.
    func resume() {
        trimmed = false
    }

    /// The prefix of `order` memory admits. `held` is how many of its first items an idle player
    /// will hold, which costs their loaded media where the rest cost their playlists alone.
    func admit(_ order: [Int], policy: PreloadPolicy, held: Int) -> [Int] {
        if trimmed { return [] }
        guard let budgetBytes else { return Array(order.prefix(Self.constrainedDeviceItems)) }
        var spent: UInt64 = 0
        var admitted: [Int] = []
        for (rank, index) in order.enumerated() {
            spent += Self.bytes(for: policy.depth, held: rank < held)
            if spent > budgetBytes { break }
            admitted.append(index)
        }
        return admitted
    }

    /// What one prefetched item is costed at: ``preparedItemBytes`` for its asset, and, where an
    /// idle player holds it, the media its depth loads at ``referenceBytesPerSecond``.
    ///
    /// An item no player holds loads its playlists and nothing else, whatever its depth — an
    /// `AVPlayerItem` buffers only on a player (`PreloadCoordinator`'s *What a prefetch is on iOS*)
    /// — so it is costed at its asset alone. A held item is costed at its depth, where the Kotlin
    /// guard costs a warm item at the longer of its depth and the load control's `minBufferMs`:
    /// here the held item keeps the prefetch's forward-buffer preference until a request adopts it
    /// (`SuperPlayer.holdWarm`), so it does not buffer on to a profile's floor.
    static func bytes(for depth: PreloadDepth, held: Bool) -> UInt64 {
        let mediaMs: Int
        switch depth {
        case .sourcePrepared, .tracksSelected: mediaMs = 0
        case .loaded(let durationMs), .decoderWarmed(let durationMs):
            mediaMs = held ? durationMs : 0
        }
        return preparedItemBytes + UInt64(mediaMs) * referenceBytesPerSecond / 1_000
    }

    /// The share of the process's remaining memory prefetch may hold: a quarter, as a divisor.
    ///
    /// The Kotlin `PRELOAD_SHARE_OF_HEAP`, for its reason: the rest is for what is on screen — the
    /// playing players, which `DeviceCapacity.swift` budgets against the same reading, and the app's
    /// own views — and prefetch is the only speculative allocation here, so it takes the smaller
    /// share. A starting value for i10 (#439) to measure, as the Kotlin one is for `PRD.md` Part 4's
    /// tuning phase.
    static let preloadShareOfMemory: UInt64 = 4

    /// What a second of prefetched media is costed at: 8 Mbps, in bytes — the Kotlin
    /// `REFERENCE_BYTES_PER_SECOND`, whose reason is Apple's own: the HLS authoring specification
    /// tops its 1080p H.264 ladder at 7.8 Mbps, and 1080p is `shortForm`'s ceiling. Estimated high,
    /// so a small allowance holds a row too few rather than a row too many.
    ///
    /// ref: https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
    static let referenceBytesPerSecond: UInt64 = 8_000_000 / 8

    /// What a prefetched asset is costed at before any media: 256 KiB — the Kotlin
    /// `PREPARED_ITEM_BYTES`, borrowed rather than derived. On Android it is four of Media3's 64 KiB
    /// allocation units; AVFoundation publishes no cost for a parsed asset, so the number is kept
    /// for the reason it is not zero — a playlist held for every row of a long feed is memory too —
    /// until i10 measures one.
    static let preparedItemBytes: UInt64 = 256 * 1024

    /// How many items a process whose memory reading is zero is admitted: the next row — the
    /// Kotlin `CONSTRAINED_DEVICE_ITEMS`, for its reason. It is the prefetch a swipe pays back most
    /// often, and admitting none would start every row cold exactly where a cold start is slowest.
    static let constrainedDeviceItems = 1
}

/// ADR-0010 rule 11's data-saver rule on iOS: no prefetch while the path is **constrained** — the
/// user's Low Data Mode, `NWPath.isConstrained` — whatever the profile and whatever the policy
/// decided. The Kotlin `DataSaverRule`, with one reading where Android has two: Android prefetches
/// nothing while Data Saver is on *and* the network is metered, because Data Saver is a setting
/// that applies only to metered links, while Low Data Mode is set per network and `isConstrained`
/// is true exactly where it is on, so the path's one bit is both halves.
///
/// Watched through an `NWPathMonitor`, so a change of network or of the setting is applied at the
/// change rather than at the next scroll; that is one better than the Kotlin rule, which hears a
/// network change but not Data Saver switched on while the network stays the same. Until the first
/// path arrives, which the monitor delivers as it starts, the path is read as unconstrained: the
/// Kotlin rule's "unknown is metered" is right where Data Saver is a second condition, while here
/// it would refuse every prefetch on the one reading that never says yes.
///
/// ref: https://developer.apple.com/documentation/network/nwpath/isconstrained
@MainActor
final class LowDataModeRule {

    private(set) var isConstrained = false

    private var observation: PreloadObservation?

    /// Starts watching the path; `onChange` runs whenever whether it is constrained changes.
    func register(onChange: @escaping @MainActor @Sendable () -> Void) {
        observation = PreloadObservation.registerNetworkPath { [weak self] constrained in
            self?.pathChanged(isConstrained: constrained, onChange: onChange)
        }
    }

    func unregister() {
        observation = nil
    }

    /// The reading: what the monitor hands over, and what a test states by hand.
    func pathChanged(isConstrained: Bool, onChange: () -> Void) {
        guard isConstrained != self.isConstrained else { return }
        self.isConstrained = isConstrained
        onChange()
    }
}
