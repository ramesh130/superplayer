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
import SuperPlayerCore

/// Prefetches the items of a feed around the row that is playing, in the order the viewer is
/// scrolling toward them, so a row that becomes current starts from what was fetched rather than
/// from nothing — the Kotlin `PreloadCoordinator` (`docs/ios-plan.md` §3.4, ADR-0010 rules 6 to 11).
///
/// ```swift
/// // Once, for the screen, before the pool makes a player.
/// let pool = PlayerPool()
/// let preload = PreloadCoordinator(pool: pool)
/// preload.setItems(feed.map(\.request))
///
/// // As the feed settles on a row, and as it scrolls. The position moves first, then the row is
/// // handed a player: that is how the pool hands it the player holding it warm.
/// preload.setScrollPosition(index: index, velocity: itemsPerSecond)
/// if let player = pool.acquire() {
///     layer.player = player.avPlayer
///     player.setMediaRequest(feed[index].request)   // warm if it was prefetched, cold otherwise
/// }
///
/// // When the screen goes away.
/// preload.release()
/// pool.release()
/// ```
///
/// There is no call that hands a feed a prefetched item, and that is the design rather than an
/// omission (ADR-0010 rule 7): a row plays through `setMediaRequest` on a player from the pool, and
/// the player takes the warm item if there is one. Identity, start position, telemetry session and
/// CMCD are the ones `setMediaRequest` produces on any player; a prefetched item's requests carry the
/// `sid` its session later reports, because the session id is the asset's `httpSessionIdentifier`,
/// minted as core makes the asset (`SuperPlayer.asset(for:configuration:)`).
///
/// ## What a prefetch is on iOS
///
/// AVFoundation has no preload manager, and two of its facts decide the shape:
///
/// - **An asset loads its playlists with no player.** Asking an `AVURLAsset` for its `duration`
///   fetches the multivariant playlist and a media playlist, and an item made on that asset later
///   fetches neither again — `PreloadCoordinatorPlaybackTests` counts both. That is every
///   prefetched row's floor, at every ``PreloadDepth``: ``PreloadDepth/sourcePrepared``'s whole
///   meaning, and ``PreloadDepth/tracksSelected``'s too, since no variant is chosen before an item
///   is a player's — AVFoundation chooses it then, and nothing selects ahead.
/// - **An item loads media only on a player, and only ever on one.** An `AVPlayerItem` buffers
///   nothing until it is some `AVPlayer`'s current item, and one that has been a player's can never
///   be another's — moving it raises. So a loaded range is a range *on the player that will play
///   it*, and the coordinator's only players are the pool's idle ones (rule 8): at
///   ``PreloadDepth/loaded(durationMs:)`` and ``PreloadDepth/decoderWarmed(durationMs:)`` the nearest
///   rows, as many as the pool has idle players, are each held by one, the item's
///   `preferredForwardBufferDuration` the depth — whole segments are the unit, so a depth under a
///   segment's length asks for one segment, the plan's "one segment" — and the rest of the window
///   is prepared as assets. At ``PreloadDepth/decoderWarmed(durationMs:)`` a held player is also
///   prerolled: `AVPlayer.preroll(atRate:)` once its item is ready, which is the decoder stage.
///
/// ## What it holds, and in what order
///
/// How many items and how far into each is the decision's ``PreloadPolicy``, read from the pool's
/// decision whenever the order is recomputed (ADR-0010 rule 10). *Which* items, and in what order,
/// is the feed's input: ``setScrollPosition(index:velocity:)``'s index, the direction the feed is
/// moving, and its velocity — the Kotlin rules exactly (`ScrollWindow`).
///
/// ## Held players
///
/// - **The bound is the pool's.** Only players the pool has made and the feed has handed back are
///   held, so held players never exceed `pool.maxSize` less the players out with the feed. The
///   coordinator never asks the pool to make a player, and never reads the device.
/// - **Handing over.** `PlayerPool.acquire()` prefers the idle player holding the current row,
///   then one holding nothing; `setMediaRequest` on it keeps the item it holds. A held player handed
///   out for any other row is reset first, so it can show nothing of the row it held.
/// - **Letting go.** A held player lets go when its row leaves the window or the feed, when the
///   feed plays its row on a different player, and on ``release()``. The row's asset stays
///   prefetched, and a new item is made on it, since the held item was that player's for life.
///
/// A held player has an item before `setMediaRequest`, but plays nothing and reports nothing until
/// `setMediaRequest` for that row adopts it — ADR-0010 rule 7's #159 addendum, read the same way.
///
/// ## What it holds less than the decision says
///
/// ADR-0010 rule 11's two platform rules apply after the decision, whatever the profile or policy,
/// in `PlatformRules.swift`: the **memory guard** admits the window in priority order against a
/// quarter of the memory the process has left and releases everything nobody is playing on a memory
/// warning, until the feed's next ``setItems(_:)`` or ``setScrollPosition(index:velocity:)``; and
/// the **Low Data Mode rule** prefetches nothing while the path is constrained, applied at the path
/// change.
///
/// ## Lifetimes and threading
///
/// A row that leaves the window is let go, its asset's loading cancelled. A row a player took is
/// left with that player until the player moves on or is recycled. ``release()`` lets everything go
/// and does not stop a row that is already playing. On the main actor, as the pool is.
///
/// ## Where this stops short of ADR-0010, stated
///
/// - **Rule 11's cap is an estimate, not a count**, as the Kotlin one is: rows are costed at a
///   reference bitrate before any variant is chosen.
/// - **Rule 6 has no cache to share**: there is no streaming cache over `AVPlayer`
///   (`docs/ios-plan.md` §3.4), so a prefetch is a load and nothing is written.
/// - **Rule 9 has no engine to share**: `AVPlayer` has no load control, meter or playback thread a
///   preload manager must be built over, so the pool's players are made as they always were.
@MainActor
public final class PreloadCoordinator {

    /// The pool this coordinator is attached to. Weak, because the pool holds the coordinator as
    /// its attachment, and a feed holds the pool.
    private weak var pool: PlayerPool?

    private var requests: [MediaRequest] = []
    private var window = ScrollWindow()

    /// Every row prefetched or held, by its request.
    private var prefetched: [MediaRequest: Prefetch] = [:]

    /// The prefetched request each player took, until it takes another or is recycled.
    private var adopted: [ObjectIdentifier: MediaRequest] = [:]

    /// The players holding a row, idle in the pool or handed out for that row and not yet adopted
    /// it.
    private var held: [ObjectIdentifier: Held] = [:]

    private let memoryGuard: MemoryGuard
    private let lowDataMode = LowDataModeRule()
    private var isReleased = false

    /// How many players hold a row right now — idle in the pool on a prefetched row, or handed out
    /// for that row and not yet adopted it — prerolled or about to be. Never more than
    /// `pool.maxSize` less the players out with the feed. The Kotlin `warmDecoderCount`, a reading
    /// for a screen that shows what preload is doing: zero below ``PreloadDepth/loaded(durationMs:)``,
    /// after a memory warning until the feed next moves, and once released.
    public var warmDecoderCount: Int { held.count }

    /// How many prerolls this coordinator has asked of a held player: what tells
    /// ``PreloadDepth/decoderWarmed(durationMs:)`` from ``PreloadDepth/loaded(durationMs:)`` in a
    /// test, since a preroll changes nothing the public API reads.
    package private(set) var prerollsAsked = 0

    /// Attaches a coordinator to `pool`. Make it before the pool makes its first player: a player
    /// already made adopts through nothing, and the pool traps, naming ADR-0010 rule 8, on one that
    /// has. One coordinator per pool.
    public convenience init(pool: PlayerPool) {
        self.init(
            pool: pool, availableMemory: DeviceCapacity.processAvailableMemory,
            watchesNetworkPath: true)
    }

    /// A coordinator whose memory guard reads `availableMemory`, and which watches the network path
    /// only if `watchesNetworkPath` — the seam a test states a device through, as the pool's own
    /// `package` initialiser is, with ``pathChanged(isConstrained:)`` the reading it states a path
    /// with. `package` because a consumer has no device to state but the one it runs on.
    package init(
        pool: PlayerPool, availableMemory: @Sendable () -> UInt64, watchesNetworkPath: Bool
    ) {
        self.pool = pool
        self.memoryGuard = MemoryGuard(availableMemory: availableMemory)
        pool.attach(self)
        memoryGuard.register { [weak self] in self?.resync() }
        if watchesNetworkPath {
            lowDataMode.register { [weak self] in self?.resync() }
        }
    }

    /// The feed, in feed order: every request a row may play, whether or not it will be prefetched.
    /// Replaces the previous list; rows still in the window at the same index keep what was fetched
    /// for them.
    public func setItems(_ requests: [MediaRequest]) {
        guard !isReleased else { return }
        self.requests = requests
        memoryGuard.resume()
        sync()
    }

    /// Where the feed is: `index` is the row playing or about to, and `velocity` how fast the feed
    /// is moving through rows, in rows a second — positive toward later rows, negative toward
    /// earlier, zero once it has settled. Call it before handing the row at `index` a player, so the
    /// pool can hand it the player holding it.
    public func setScrollPosition(index: Int, velocity: Double = 0) {
        guard !isReleased else { return }
        precondition(index >= 0, "index must not be negative, was \(index)")
        window.move(to: index, velocity: velocity)
        memoryGuard.resume()
        sync()
    }

    /// Lets every prefetch go, every held player go, and everything prefetched that no player took.
    /// Rows already playing carry on, and the pool is not released; it loads every row cold from
    /// now on. Calling it again does nothing.
    public func release() {
        guard !isReleased else { return }
        isReleased = true
        memoryGuard.unregister()
        lowDataMode.unregister()
        pool?.detach(self)
        // Idle held players only. One handed out for its row and not yet adopted is the feed's now:
        // its `setMediaRequest` finds no coordinator and plays the row cold, replacing the item.
        let idle = Set((pool?.idlePlayers ?? []).map(ObjectIdentifier.init))
        for (id, entry) in held where idle.contains(id) { entry.player.letGoOfWarmItem() }
        held = [:]
        for prefetch in prefetched.values { prefetch.cancel() }
        prefetched = [:]
        adopted = [:]
    }

    /// Low Data Mode's reading: what the path monitor hands over, and what a test states.
    package func pathChanged(isConstrained: Bool) {
        lowDataMode.pathChanged(isConstrained: isConstrained) { resync() }
    }

    private func resync() {
        guard !isReleased else { return }
        sync()
    }

    /// Recomputes the window and brings the prefetched rows and the held players into line with it.
    private func sync() {
        guard !isReleased, let pool else { return }
        let decision = pool.playbackDecision
        let policy = decision.preload
        let idle = pool.idlePlayers
        let holds = policy.depth.isHeld ? idle.count : 0
        let order = guarded(
            window.priorityOrder(count: requests.count, policy: policy), policy, holds)
        let wanted = Dictionary(
            order.map { (requests[$0], $0) }, uniquingKeysWith: { first, _ in first })
        let current = currentRequest
        let trimmed = memoryGuard.trimmed

        // Players first, while every row they hold is still prefetched.
        let toHold = trimmed ? [] : holdTargets(policy, order.map { requests[$0] }, current, holds)
        let idleIds = Set(idle.map(ObjectIdentifier.init))
        for (id, entry) in held where idleIds.contains(id) && !toHold.contains(entry.request) {
            letGo(id)
        }

        for (request, prefetch) in prefetched {
            let stillWanted = wanted[request] == prefetch.index
            // The row the feed has just made current keeps what was fetched for it: a feed moves
            // its position before it hands the row a player. Not through a memory warning, which
            // releases everything nobody is playing.
            let aboutToPlay =
                !trimmed && prefetch.index == window.position && current == request
            if !stillWanted && !aboutToPlay && !isAdopted(request) && !isHeld(request) {
                forget(request)
            }
        }
        for (request, index) in wanted where prefetched[request] == nil {
            let asset = SuperPlayer.asset(for: request, configuration: pool.configuration.player)
            prefetched[request] = Prefetch(request: request, index: index, asset: asset)
        }

        // A held player whose item was ready before the player was is asked again here.
        for id in held.keys { prerollIfReady(id) }

        var free = idle.filter { held[ObjectIdentifier($0)] == nil }
        for request in toHold where !isHeld(request) {
            guard let prefetch = prefetched[request], !isAdopted(request) else { continue }
            guard let player = free.popLast() else { break }
            hold(prefetch, on: player, depth: policy.depth)
        }
    }

    /// ADR-0010 rule 11, applied after the decision and here, where a reviewer can find it: nothing
    /// on a constrained path, and otherwise no more of `order` than the memory guard admits.
    private func guarded(_ order: [Int], _ policy: PreloadPolicy, _ holds: Int) -> [Int] {
        if lowDataMode.isConstrained { return [] }
        return memoryGuard.admit(order, policy: policy, held: holds)
    }

    /// The rows to hold on an idle player, nearest first: the current row if a player already holds
    /// it — the feed is about to take that player — then the window in order, none a player has
    /// adopted, and no more than `holds`, the pool's bound less the players the feed has out.
    private func holdTargets(
        _ policy: PreloadPolicy, _ window: [MediaRequest], _ current: MediaRequest?, _ holds: Int
    ) -> [MediaRequest] {
        guard policy.depth.isHeld else { return [] }
        var targets: [MediaRequest] = []
        for request in ([current].compactMap { $0 }.filter(isHeld) + window)
        where !targets.contains(request) && !isAdopted(request) {
            targets.append(request)
        }
        return Array(targets.prefix(holds))
    }

    /// Makes `prefetch`'s item `player`'s current item at the depth's forward buffer, and prerolls
    /// it once ready where the depth warms a decoder.
    private func hold(_ prefetch: Prefetch, on player: SuperPlayer, depth: PreloadDepth) {
        let item = prefetch.takeItem()
        item.preferredForwardBufferDuration = depth.forwardBufferSeconds
        player.holdWarm(item)
        let id = ObjectIdentifier(player)
        var entry = Held(player: player, request: prefetch.request, item: item)
        if depth.warmsDecoder {
            entry.readiness = PreloadObservation.registerStatus(of: item) { [weak self] in
                self?.prerollIfReady(id)
            }
        }
        held[id] = entry
    }

    /// Prerolls the player `id` names once its held item is ready, and stops listening: the
    /// decoder stage, asked only of a player that is paused and still holds that item, because
    /// `preroll(atRate:)` raises on a player whose status is not ready and does nothing useful on
    /// one that is playing.
    ///
    /// ref: https://developer.apple.com/documentation/avfoundation/avplayer/preroll(atrate:completionhandler:)
    private func prerollIfReady(_ id: ObjectIdentifier) {
        guard var entry = held[id], entry.readiness != nil else { return }
        let player = entry.player.avPlayer
        guard entry.item.status == .readyToPlay, player.status == .readyToPlay,
            player.currentItem === entry.item, player.rate == 0
        else { return }
        entry.readiness = nil
        held[id] = entry
        prerollsAsked += 1
        player.preroll(atRate: 1, completionHandler: nil)
    }

    /// Lets the held player `id` names go: it is reset, and its row stays prefetched.
    private func letGo(_ id: ObjectIdentifier) {
        guard let entry = held.removeValue(forKey: id) else { return }
        entry.player.letGoOfWarmItem()
    }

    /// Cancels `request`'s prefetch and forgets it.
    private func forget(_ request: MediaRequest) {
        prefetched.removeValue(forKey: request)?.cancel()
    }

    private func isHeld(_ request: MediaRequest) -> Bool {
        held.values.contains { $0.request == request }
    }

    private func isAdopted(_ request: MediaRequest) -> Bool {
        adopted.values.contains(request)
    }

    private var currentRequest: MediaRequest? {
        requests.indices.contains(window.position) ? requests[window.position] : nil
    }

    /// One prefetched row: its asset, loading its playlists from the moment it is made, and the item
    /// a player will take, made on it.
    private final class Prefetch {
        let request: MediaRequest
        let index: Int
        let asset: AVURLAsset
        private let loading: Task<Void, Never>

        init(request: MediaRequest, index: Int, asset: AVURLAsset) {
            self.request = request
            self.index = index
            self.asset = asset
            // The duration needs the media playlist, so asking for it fetches the multivariant
            // playlist and one media playlist: what `sourcePrepared` means. A failure is the item's
            // to report once a player has it, so it is not reported here.
            loading = Task { _ = try? await asset.load(.duration) }
        }

        /// An item on the prefetched asset, for one player: each call makes another, since an item
        /// is the player's that takes it for life.
        @MainActor
        func takeItem() -> AVPlayerItem {
            SuperPlayer.item(for: request, on: asset)
        }

        func cancel() {
            loading.cancel()
            asset.cancelLoading()
        }
    }

    /// A player holding a row, and its readiness observation until it is prerolled.
    private struct Held {
        let player: SuperPlayer
        let request: MediaRequest
        let item: AVPlayerItem
        var readiness: PreloadObservation?
    }
}

extension PreloadCoordinator: PoolAttachment {

    package func warmStart(for player: SuperPlayer, adopting request: MediaRequest) -> WarmStart? {
        guard !isReleased else { return nil }
        let id = ObjectIdentifier(player)
        let wasHolding = held.removeValue(forKey: id)
        let start: WarmStart?
        if wasHolding?.request == request {
            start = .prepared
        } else {
            // A row another player holds, played on a player of the feed's own choosing: that
            // player lets it go first, so the row's items are never on two players.
            // One the feed has out keeps its item, which is the feed's now; it is only forgotten.
            if let other = held.first(where: { $0.value.request == request })?.key {
                if pool?.idlePlayers.contains(where: { ObjectIdentifier($0) == other }) == true {
                    letGo(other)
                } else {
                    held.removeValue(forKey: other)
                }
            }
            let takenElsewhere = adopted.contains { $0.key != id && $0.value == request }
            start = prefetched[request].flatMap { takenElsewhere ? nil : .item($0.takeItem()) }
        }
        let previous = adopted[id]
        adopted[id] = start == nil ? nil : request
        // What the player moves on from was one view's: its asset carries that view's session id,
        // so it is let go rather than handed to the next view, and prefetched afresh if wanted.
        if let previous, previous != request { forget(previous) }
        sync()
        return start
    }

    package func preferredIdle(_ idle: [SuperPlayer]) -> SuperPlayer? {
        guard !isReleased else { return nil }
        let current = currentRequest
        return idle.last { current != nil && held[ObjectIdentifier($0)]?.request == current }
            ?? idle.last { held[ObjectIdentifier($0)] == nil }
    }

    package func acquired(_ player: SuperPlayer) {
        guard !isReleased, let entry = held[ObjectIdentifier(player)] else { return }
        if entry.request == currentRequest { return }
        letGo(ObjectIdentifier(player))
        sync()
    }

    package func recycled(_ player: SuperPlayer) {
        let id = ObjectIdentifier(player)
        held.removeValue(forKey: id)
        let taken = adopted.removeValue(forKey: id)
        guard !isReleased else { return }
        if let taken { forget(taken) }
        // A player back in the pool can hold the next row.
        sync()
    }

    package func poolReleasing() {
        release()
    }
}

/// Which rows to prefetch, first to last, from where the feed is and how it is moving — the Kotlin
/// coordinator's `priorityOrder`, its direction and heading rules unchanged.
///
/// - **Direction** is the sign of the velocity, or of the index's change when the velocity is zero,
///   or the last direction seen when neither says; a feed starts moving forward. Rows in the
///   direction of travel come first, nearest first, then the rows behind.
/// - **Velocity** moves where "ahead" starts: a feed moving fast settles past the rows next to it,
///   so the first row ahead is the one it is projected to reach ``headingHorizonSeconds`` from now,
///   and the rows it will pass on the way are not fetched at all.
struct ScrollWindow {

    private(set) var position = 0
    private(set) var direction = 1
    private var heading = 0

    mutating func move(to index: Int, velocity: Double) {
        precondition(velocity.isFinite, "velocity must be finite, was \(velocity)")
        if velocity > 0 {
            direction = 1
        } else if velocity < 0 {
            direction = -1
        } else if index > position {
            direction = 1
        } else if index < position {
            direction = -1
        }
        position = index
        heading = index + Int((velocity * Self.headingHorizonSeconds).rounded())
    }

    /// The rows of a feed of `count` to prefetch, first to last.
    func priorityOrder(count: Int, policy: PreloadPolicy) -> [Int] {
        guard !policy.isNone, count > 0 else { return [] }
        let indices = 0..<count
        let firstAhead =
            direction == 1 ? max(position + 1, heading) : min(position - 1, heading)
        var order: [Int] = []
        for (start, step, limit) in [
            (firstAhead, direction, policy.itemsAhead),
            (position - direction, -direction, policy.itemsBehind),
        ] {
            var index = start
            var taken = 0
            while taken < limit, indices.contains(index) {
                if index != position, !order.contains(index) { order.append(index) }
                taken += 1
                index += step
            }
        }
        return order
    }

    /// How far ahead the velocity projects the heading: half a second — the Kotlin
    /// `HEADING_HORIZON_MS`, for its reason: long enough that a fling's projection lands past the
    /// rows it will pass, short enough that a slow drag still counts the next row as the next row. A
    /// starting value for tuning, not a measured one.
    static let headingHorizonSeconds = 0.5
}

extension PreloadDepth {

    /// Whether this depth loads media, which on iOS only a player can: an idle pooled player holds
    /// the row (``PreloadCoordinator``'s *What a prefetch is on iOS*).
    var isHeld: Bool {
        switch self {
        case .sourcePrepared, .tracksSelected: false
        case .loaded, .decoderWarmed: true
        }
    }

    /// Whether a held row's player is also prerolled.
    var warmsDecoder: Bool {
        if case .decoderWarmed = self { true } else { false }
    }

    /// The forward buffer a held item asks for: the depth's duration, and never zero for a held
    /// depth, since `preferredForwardBufferDuration` reads zero as "the system's choice". Zero for
    /// a depth no player holds, which sets no item's buffer.
    var forwardBufferSeconds: TimeInterval {
        switch self {
        case .sourcePrepared, .tracksSelected: 0
        case .loaded(let durationMs), .decoderWarmed(let durationMs):
            max(Double(durationMs), 1) / 1_000
        }
    }
}
