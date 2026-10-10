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

/// A bounded, recycling set of ``SuperPlayer`` instances, for screens that show many videos at once
/// — the Kotlin `PlayerPool`, and its reasons (`docs/ios-plan.md` §3.4, ADR-0010 rule 4: the pool
/// owns the players and the budget).
///
/// A feed has no fixed number of videos in it, and a player per row is the implementation that
/// fails: decoder sessions and memory are device resources, and a long scroll finds the end of
/// both. The bound is the whole of what makes this a pool.
///
/// ```swift
/// let pool = PlayerPool()                         // once, for the screen
///
/// if let player = pool.acquire() {                // as a row scrolls in; nil shows the artwork
///     layer.player = player.avPlayer
///     player.setMediaRequest(request)
/// }
///
/// layer.player = nil                              // as it scrolls out
/// pool.recycle(player)
///
/// pool.release()                                  // when the screen goes away
/// ```
///
/// **The bound is derived, not chosen.** ``maxSize`` is what `DeviceCapacity.swift` reads from the
/// device — on iOS a memory budget under a ceiling, since the platform reports no decoder limit —
/// lowered by ``Configuration/maxSize`` if that asks for fewer, and never raised by it.
///
/// **Why ``acquire()`` returns nil.** A pool that grew past its bound would be a pool in name only,
/// and one that threw would make every caller write the check a nil makes plainly: nil means every
/// player this device can afford is out, so show the row's artwork.
///
/// **Reuse is clean.** A recycled player is reset before it is handed out again —
/// ``SuperPlayer``'s `resetForReuse`, which says what goes and in what order.
///
/// **Concurrently playing pooled players share one audio session**, which is the app's: two pooled
/// players playing at once are two sources mixed or interrupting one another as that session's
/// category says. A feed gives a player to the row being watched and leaves the rest to preload,
/// which is the Kotlin KDoc's advice for the audio-focus token, for the same reason.
///
/// **On the main actor**, as ``SuperPlayer`` is: a pool whose players may only be touched there
/// gains nothing from being reachable elsewhere.
@MainActor
public final class PlayerPool {

    /// What a pool is made with, fixed for its lifetime.
    public struct Configuration: Sendable {

        /// What every player the pool makes is made with.
        ///
        /// Its profile defaults to ``PlaybackProfile/shortForm`` rather than to
        /// ``SuperPlayer/Configuration``'s ``PlaybackProfile/videoOnDemand``, and the difference is
        /// the Kotlin `PlayerPool.Builder`'s: a pool exists for feeds, short-form content fills them,
        /// and a pool of on-demand players would each buffer for a long watch a scroll is about to
        /// end. Its ``SuperPlayer/Configuration/telemetry`` factory is called once per player, which
        /// is why it is a factory (`setTelemetry { QoeCollector(sink) }`, a collector per player).
        public var player: SuperPlayer.Configuration = {
            var configuration = SuperPlayer.Configuration()
            configuration.profile = .shortForm
            return configuration
        }()

        /// At most this many players, or nil — the default — for the device's answer.
        ///
        /// A ceiling on top of the device's and never a way past it: a two-up grid that will never
        /// show more than two asks for two, and one asking for twenty on a device that affords four
        /// gets four. At least one, and the pool refuses to be made with less.
        public var maxSize: Int?

        public init() {}
    }

    /// The configuration this pool was made with.
    public let configuration: Configuration

    /// The most players this pool will ever have alive at once: the device's answer, possibly
    /// lowered by ``Configuration/maxSize``. Public because a feed sizes its own behaviour against
    /// it, as the Kotlin property's KDoc says.
    public let maxSize: Int

    /// Players made and handed back, newest last. ``acquire()`` takes the newest, so a run of
    /// acquire and recycle pairs keeps returning to the same player — the Kotlin `idle` deque's
    /// order, for its reason.
    private var idle: [SuperPlayer] = []

    /// Players out with a caller, by identity: ``SuperPlayer`` declares no equality, so two players
    /// are one entry only if they are one object, which is what lets ``recycle(_:)`` refuse a player
    /// this pool never handed out.
    private var inUse: [ObjectIdentifier: SuperPlayer] = [:]

    private var isReleased = false

    /// A pool bounded by what this device reports.
    public convenience init(configuration: Configuration = Configuration()) {
        self.init(
            configuration: configuration, availableMemory: DeviceCapacity.processAvailableMemory)
    }

    /// A pool bounded by what `availableMemory` reports — the seam a test states a device through,
    /// as the Kotlin `PlayerPoolCapacityTest` does with its device statements. `package` because a
    /// consumer has no device to state but the one it runs on.
    package init(configuration: Configuration, availableMemory: DeviceCapacity.MemoryReader) {
        if let requested = configuration.maxSize {
            precondition(
                requested >= 1, "A pool needs room for at least one player, not \(requested)")
        }
        self.configuration = configuration
        let deviceBound = DeviceCapacity.concurrentPlayers(availableMemoryBytes: availableMemory())
        self.maxSize = min(deviceBound, configuration.maxSize ?? deviceBound)
    }

    /// How many players exist right now, in use and idle. Never above ``maxSize``, and below it
    /// until demand has asked for that many: a player is made on the first ``acquire()`` that finds
    /// none idle.
    public var size: Int { inUse.count + idle.count }

    /// How many players are out with callers and have not been handed back.
    public var inUseCount: Int { inUse.count }

    /// A player to show one item with, or nil if every player this device can afford is out.
    ///
    /// Reuses the newest idle player if there is one and makes one otherwise, up to ``maxSize``.
    /// The player comes back as a freshly made one is. Hand it back with ``recycle(_:)`` and never
    /// call ``SuperPlayer/release()`` on it: the pool owns its players' lifetimes.
    ///
    /// Nil after ``release()`` too: a released pool affords nothing, and a caller already handles
    /// nil. The Kotlin `acquire` throws there; a feed's cell asking during the teardown that released
    /// the screen is the same race ``recycle(_:)`` forgives, and a trap there would crash an app for
    /// it.
    public func acquire() -> SuperPlayer? {
        guard !isReleased else { return nil }
        if let reused = idle.popLast() {
            inUse[ObjectIdentifier(reused)] = reused
            return reused
        }
        guard inUse.count < maxSize else { return nil }
        let player = SuperPlayer(configuration: configuration.player)
        inUse[ObjectIdentifier(player)] = player
        return player
    }

    /// Takes `player` back, resets it, and makes it available to the next ``acquire()``.
    ///
    /// Detach it from its view first — `layer.player = nil` — because only the caller knows about
    /// the view; the reset clears the player's own side regardless.
    ///
    /// Refuses a player this pool did not hand out, or one already handed back, by trapping: a pool
    /// that adopted it would take on the lifetime of a player somebody else releases, and the fault
    /// would surface in the next row's playback rather than here. The Kotlin `require` throws; a
    /// Swift precondition is the same refusal of a programming error.
    ///
    /// A player its holder released against that rule is let go here rather than kept, so the next
    /// ``acquire()`` makes one that plays.
    ///
    /// Does nothing after ``release()``, for the Kotlin method's reason: the callers still holding
    /// players hand them back on their own schedule, during the very teardown that released the pool.
    public func recycle(_ player: SuperPlayer) {
        guard !isReleased else { return }
        let wasInUse = inUse.removeValue(forKey: ObjectIdentifier(player)) != nil
        precondition(
            wasInUse, "That player did not come from this pool, or has already been recycled")
        // A player its holder released is let go rather than kept: handed out again it would play
        // nothing, and the pool owns its players' lifetimes precisely so this does not happen.
        guard !player.isReleased else { return }
        player.resetForReuse()
        idle.append(player)
    }

    /// Releases every player this pool made, in use or not, and refuses further use. Players in use
    /// go too, deliberately: a caller still holding one has a player whose screen no longer exists.
    /// Calling it again does nothing.
    public func release() {
        guard !isReleased else { return }
        isReleased = true
        // In use first, for the Kotlin order's reason: those are the ones a caller may still be
        // touching.
        for player in Array(inUse.values) + idle { player.release() }
        inUse = [:]
        idle = []
    }
}
