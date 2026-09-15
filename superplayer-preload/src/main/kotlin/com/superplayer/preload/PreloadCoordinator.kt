/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.preload

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlayerPool
import com.superplayer.core.PoolAttachment
import com.superplayer.core.PreloadDepth
import com.superplayer.core.PreloadPolicy
import com.superplayer.core.SharedComponents
import com.superplayer.core.SuperPlayer
import com.superplayer.core.WarmStart
import com.superplayer.core.toPreloadStatus
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * Prefetches the items of a feed around the row that is playing, in the order the viewer is scrolling
 * toward them, so a row that becomes current starts from what was fetched rather than from nothing.
 *
 * ```kotlin
 * // Once, for the screen, before the pool builds a player.
 * val pool = PlayerPool.Builder(context).setCache(cache).build()
 * val preload = PreloadCoordinator.Builder(pool).build()
 * preload.setItems(feed.map { it.request })
 *
 * // As the feed settles on a row, and as it scrolls. The position moves first, then the row is
 * // handed a player: that is how the pool hands it the player holding it warm.
 * preload.setScrollPosition(index, velocityItemsPerSecond)
 * pool.acquire()?.let { player ->
 *     player.setMediaRequest(feed[index].request)   // warm if it was prefetched, cold otherwise
 *     player.prepare()
 * }
 *
 * // When the screen goes away.
 * preload.release()
 * pool.release()
 * ```
 *
 * There is no call that hands a feed a prefetched source, and that is the design rather than an
 * omission (ADR-0010 rule 7): a row plays through `setMediaRequest` on a player from the pool, and the
 * player takes the warm source if there is one. Identity, start position, telemetry session and the
 * CMCD `cid` and `sid` are the ones `setMediaRequest` produces on any player; a prefetched item's
 * requests carried the `sid` its session later reports, because the id is minted before the item's
 * first request.
 *
 * ## What it holds, and in what order
 *
 * How many items and how far into each is the decision's [PreloadPolicy], read from the pool's
 * decision in force whenever the order is recomputed, so a policy that re-decides the depth is
 * honoured on the next scroll (ADR-0010 rule 10). *Which* items, and in what order, is the feed's
 * input: [setScrollPosition]'s index, the direction the feed is moving, and its velocity.
 *
 * - **Direction** is the sign of the velocity, or of the index's change when the velocity is zero, or
 *   the last direction seen when neither says; a feed starts moving forward. Items in the direction of
 *   travel come first, nearest first, then the items behind.
 * - **Velocity** moves where "ahead" starts. A feed moving fast will settle past the rows next to it,
 *   so the first item ahead is the one it is projected to reach [HEADING_HORIZON_MS] from now, and
 *   the rows it will pass on the way are not fetched at all.
 *
 * Media3's preload manager fetches one item at a time in that order, so reversing the scroll reverses
 * which first segment is fetched next. It is built over the pool's own components — its chain, cache
 * slot and CMCD included, its load control, meter, selector and playback thread — which is why a
 * coordinator is attached to a pool rather than built beside it (ADR-0010 rules 6, 8 and 9).
 *
 * ## Warm decoders
 *
 * At [PreloadDepth.DecoderWarmed] the nearest items in that order also get a decoder: a player from the
 * pool's idle set, prepared on the item's prefetched source with no surface, no session and no
 * `playWhenReady`. Media3's preload manager has no decoder stage, and a warm decoder *is* a prepared
 * player, which is the resource the pool bounds (ADR-0010 rule 8).
 *
 * - **The bound is the pool's.** Only players the pool has built and the feed has handed back are
 *   warmed, so warm decoders never exceed `pool.maxSize` less the players out with the feed — the
 *   playing row's among them. `maxSize` is `DeviceCapacity.kt`'s reading of the device's concurrent
 *   decoder instances, and nothing here reads the device again. A device that reports a limit of one
 *   warms nothing beside the row it plays. Which codecs that reading consults is #143's, and it is not
 *   decided here because it is a different question with a public answer of its own — which codecs a
 *   feed declares, on `PlayerPool.Builder` — that warm-up neither needs nor changes; a change there
 *   reaches this bound through `maxSize` untouched.
 * - **An unknown decoder table warms nothing beside a playing row.** The pool reads a device that
 *   reports no decoder as a pool of one, rather than as "unknown refuses nothing" the way the track
 *   selector does, because a decoder instance past the device's limit throws where a rung it cannot
 *   show is merely refused later. Warm-up inherits that.
 * - **Nothing is spent that the feed did not.** The coordinator never asks the pool to build a player;
 *   a feed that plays every row on one player has no idle player, and so no warm decoder.
 * - **Handing over.** [PlayerPool.acquire] prefers the idle player holding the current row warm, then
 *   one holding nothing, so a warm decoder is spent only when the feed wants more players than are
 *   cold. `setMediaRequest` on that player keeps what was prepared. A warm player handed out for any
 *   other row is reset first, so it can show no frame of the row it held.
 * - **Letting go.** A warm decoder is released when its item leaves the window or the feed, when the
 *   feed plays its row on a different player or takes the player for another row, and on [release].
 *   A decision that stops asking for one releases it at the next recomputation — a scroll, a new feed,
 *   an adoption or a recycle — because the depth is read rather than pushed (below). Media3 keeps a
 *   prefetched source prepared once a player releases it, so the prefetch survives its decoder.
 *
 * A warm player holds a source before `setMediaRequest`, but plays nothing and reports nothing until
 * `setMediaRequest` for that row adopts it; ADR-0010 rule 7's addendum records that reading, and rule
 * 8's that the coordinator drives the pool's idle players to do it.
 *
 * ## What it holds less than the decision says
 *
 * ADR-0010 rule 11's two platform rules apply after the decision, whatever the profile or policy, in
 * `PlatformRules.kt`:
 *
 * - **The memory guard.** The window is admitted in priority order until its estimated cost would pass a
 *   quarter of the app's heap budget. The rows cut are the ones the scroll is least likely to reach. A
 *   low-RAM device is admitted the next row only. A trim at `TRIM_MEMORY_RUNNING_LOW` or above releases
 *   every prefetch and every warm decoder that nobody is playing, and nothing is fetched again until the
 *   feed's next [setItems] or [setScrollPosition].
 * - **The data-saver rule.** While Data Saver restricts this app on a metered network, nothing is
 *   prefetched and nothing is warm. A row is fetched when it is played. A change of network is applied
 *   when it happens, which releases what a WiFi prefetch held once the link hands over to cellular.
 *
 * ## Lifetimes
 *
 * An item that leaves the window is removed, and its prefetch cancelled. An item a player took is left
 * with that player until the player moves on or is recycled, because the source is the player's
 * then; Media3 releases a source a player holds only once the player lets it go. [release] cancels
 * every prefetch, lets every warm decoder go and forgets every minted id; it does not stop a row that
 * is already playing.
 *
 * ## Threading
 *
 * Call it from the thread the pool belongs to — the main thread for a UI-owned pool. That is Media3's
 * rule for the preload manager and the pool's own.
 *
 * ## Where this stops short of ADR-0010, stated
 *
 * - **Rule 11's cap is an estimate, not a count.** Items are costed at a reference bitrate before any
 *   rendition is chosen, not measured from what the allocator holds. A warm player still buffers past its
 *   prefetch: once prepared it is a paused player on the pool's load control, which loads to the
 *   profile's `minBufferMs`. The guard costs a warm item at that depth rather than stopping the load.
 * - **Rule 6's `setCache` is not called.** It exists so the manager's cached stage writes where the
 *   chain reads, and no [PreloadDepth] decides a cached range yet: every prefetch is a load through the
 *   chain, whose cache slot writes a segment to the pool's cache like any other read. The call arrives
 *   with a cached depth.
 * - **Rule 10's depth is read, not pushed.** The half in force is read from the pool's decision each
 *   time the window is recomputed rather than through a `DecisionTarget`, which honours a changed depth
 *   on the next scroll instead of the next invalidation Media3 would make of its own accord — the same
 *   moment in a feed, which invalidates on every scroll.
 */
public class PreloadCoordinator private constructor(private val pool: PlayerPool) {

    private var requests: List<MediaRequest> = emptyList()
    private var position = 0
    private var direction = FORWARD
    private var heading = 0

    private var components: SharedComponents? = null
    private var manager: DefaultPreloadManager? = null
    private val ranking = ScrollRanking()

    /** Items registered with the manager, by the item a pooled player's adoption builds. */
    private val registered = LinkedHashMap<MediaItem, Registered>()

    /** The prefetched item each player took, until it takes another or is recycled. */
    private val adopted = IdentityHashMap<SuperPlayer, MediaItem>()

    /**
     * The item each warm player is prepared on. Idle in the pool, or handed out for that item and not
     * yet adopted it.
     */
    private val warm = IdentityHashMap<SuperPlayer, MediaItem>()

    /** ADR-0010 rule 11's two platform rules, from the moment the pool's engine exists until [release]. */
    private var memoryGuard: MemoryGuard? = null
    private var dataSaver: DataSaverRule? = null

    private var released = false

    /**
     * The feed, in feed order: every item a row may play, whether or not it will be prefetched.
     *
     * Replaces the previous list. Items still in the window at the same index keep what was fetched
     * for them; items that moved are fetched again under their new position.
     */
    public fun setItems(requests: List<MediaRequest>) {
        check(!released) { "This PreloadCoordinator has been released" }
        this.requests = requests.toList()
        memoryGuard?.resume()
        sync()
    }

    /**
     * Where the feed is: [currentIndex] is the row playing or about to, and [velocityItemsPerSecond]
     * how fast the feed is moving through rows — positive toward later items, negative toward earlier,
     * zero once it has settled. The class KDoc says how each is used.
     *
     * Call it before handing the row at [currentIndex] a player, so the pool can hand it the player
     * holding it warm.
     */
    @JvmOverloads
    public fun setScrollPosition(currentIndex: Int, velocityItemsPerSecond: Float = 0f) {
        check(!released) { "This PreloadCoordinator has been released" }
        require(currentIndex >= 0) { "currentIndex must not be negative, was $currentIndex" }
        direction = when {
            velocityItemsPerSecond > 0f -> FORWARD
            velocityItemsPerSecond < 0f -> BACKWARD
            currentIndex > position -> FORWARD
            currentIndex < position -> BACKWARD
            else -> direction
        }
        position = currentIndex
        heading = currentIndex + (velocityItemsPerSecond * HEADING_HORIZON_MS / MS_PER_SECOND).roundToInt()
        memoryGuard?.resume()
        sync()
    }

    /**
     * Cancels every prefetch, lets every warm decoder go and lets go of everything prefetched that no
     * player took. Idempotent.
     *
     * Rows already playing carry on. The pool itself is not released, and still builds its players on
     * the engine it shares; a pool with a released coordinator loads every item cold.
     */
    public fun release() {
        if (released) return
        released = true
        memoryGuard?.unregister()
        dataSaver?.unregister()
        // Idle warm players only. One handed out for its row and not yet adopted is the feed's now: its
        // `setMediaRequest` finds no coordinator and sets the row cold, which replaces the warm source.
        val idle = pool.idlePlayers
        warm.keys.filter { it in idle }.forEach { it.resetForReuse() }
        warm.clear()
        manager?.release()
        manager = null
        components?.sessionIds?.clear()
        registered.clear()
        adopted.clear()
    }

    /** Recomputes the window and brings the manager's items, and the warm decoders, into line with it. */
    private fun sync() {
        val manager = manager ?: return
        val shared = checkNotNull(components)
        val policy = shared.decision.preload
        val idle = pool.idlePlayers
        val order = guarded(priorityOrder(policy), policy, shared, idle.size)
        ranking.ranks = order.withIndex().associate { (rank, index) -> index to rank }
        val wanted = order.associateBy { shared.itemOf(requests[it]) }
        val current = currentItem()
        val trimmed = memoryGuard?.trimmed == true

        // Decoders first, while every source they hold is still registered.
        val toWarm = if (trimmed) emptyList() else warmTargets(policy, order.map { shared.itemOf(requests[it]) }, current, idle.size)
        for ((player, item) in warm.toList()) {
            if (player in idle && item !in toWarm) coolDown(player)
        }

        for ((item, entry) in registered.toList()) {
            val stillWanted = wanted[item] == entry.index
            // The row the feed has just made current keeps what was fetched for it: a feed moves its
            // position before it hands the row a player, and the row is about to take the source. Not
            // through a trim, which releases everything nobody is playing.
            val aboutToPlay = !trimmed && entry.index == position && requests.getOrNull(position)?.contentId == entry.contentId
            if (!stillWanted && !aboutToPlay && !isAdopted(item) && !isWarm(item)) forget(item)
        }
        for ((item, index) in wanted) {
            if (item in registered) continue
            val contentId = requests[index].contentId
            // Before `add`, which creates the source: its CMCD configuration is fixed then.
            shared.sessionIds.mint(contentId)
            manager.add(item, index)
            registered[item] = Registered(index, contentId)
        }

        val free = idle.filter { it !in warm }.toMutableList()
        for (item in toWarm) {
            if (isWarm(item) || item !in registered) continue
            val source = manager.getMediaSource(item) ?: continue
            val player = free.removeLastOrNull() ?: break
            warm[player] = item
            player.holdWarm(source)
        }
        manager.invalidate()
    }

    /**
     * ADR-0010 rule 11, applied after the decision and here, where a reviewer can find it: nothing under
     * Data Saver on a metered link, and otherwise no more of [order] than the memory guard admits.
     */
    private fun guarded(order: List<Int>, policy: PreloadPolicy, shared: SharedComponents, idlePlayers: Int): List<Int> {
        if (dataSaver?.restrictsPrefetch() == true) return emptyList()
        return memoryGuard?.admit(order, policy, shared.decision.buffer, warmable = idlePlayers) ?: order
    }

    /**
     * The items to hold a decoder warm on, nearest first: the current row if an idle player already
     * holds it — the feed is about to take that player — then the window in order, none a player has
     * adopted, and no more than [idlePlayers], which is the pool's bound less the players the feed has out.
     */
    private fun warmTargets(policy: PreloadPolicy, window: List<MediaItem>, current: MediaItem?, idlePlayers: Int): List<MediaItem> {
        if (policy.depth !is PreloadDepth.DecoderWarmed) return emptyList()
        val held = current?.takeIf { isWarm(it) }
        return (listOfNotNull(held) + window).distinct().filter { !isAdopted(it) }.take(idlePlayers)
    }

    // By equality, not by the maps' own `values.contains`: an identity map compares its values by
    // identity too, and `itemOf` builds an equal item, never the same one, each time it is asked.
    private fun isWarm(item: MediaItem): Boolean = warm.values.any { it == item }

    private fun isAdopted(item: MediaItem): Boolean = adopted.values.any { it == item }

    /**
     * Lets [player]'s warm decoder go: the player lets its source go, which stays the manager's, prepared.
     *
     * A `PreloadMediaSource` a player releases keeps its prepared timeline and returns to the preload
     * manager's player id, unless the manager itself has released it — which is why the prefetch
     * survives its decoder and a later player starts from the same manifest.
     *
     * ref: https://developer.android.com/reference/androidx/media3/exoplayer/source/preload/PreloadMediaSource
     */
    private fun coolDown(player: SuperPlayer) {
        warm.remove(player) ?: return
        player.resetForReuse()
    }

    /**
     * Cancels [item]'s prefetch, or releases what was prefetched, and forgets the id minted for it.
     *
     * Safe for an item a player took: Media3 defers releasing a source a player holds until the player
     * lets it go.
     */
    private fun forget(item: MediaItem) {
        manager?.remove(item)
        registered.remove(item)?.let { components?.sessionIds?.retire(it.contentId) }
    }

    /** The items to prefetch, first to last: ahead of the heading in the direction of travel, then behind. */
    private fun priorityOrder(policy: PreloadPolicy): List<Int> {
        if (policy.isNone || requests.isEmpty()) return emptyList()
        val indices = requests.indices
        val firstAhead = if (direction == FORWARD) maxOf(position + 1, heading) else minOf(position - 1, heading)
        val ahead = generateSequence(firstAhead) { it + direction }.takeWhile { it in indices }.take(policy.itemsAhead)
        val behind = generateSequence(position - direction) { it - direction }.takeWhile { it in indices }.take(policy.itemsBehind)
        return (ahead + behind).filter { it != position }.distinct().toList()
    }

    private fun statusFor(index: Int): PreloadStatus {
        if (index !in ranking.ranks) return PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        // The translation is core's, in the one file ADR-0005 confines it to.
        return checkNotNull(components).decision.preload.depth.toPreloadStatus()
    }

    private fun currentItem(): MediaItem? = requests.getOrNull(position)?.let { components?.itemOf(it) }

    /** Core's side of the attachment, kept off the public type: `PoolAttachment` is internal. */
    private val attachment = object : PoolAttachment {

        override fun onEngineAssembled(components: SharedComponents) {
            if (released) return
            this@PreloadCoordinator.components = components
            // The thread the engine is assembled on, which is the pool's: every rule's callback is brought
            // back to it. A callback queued before `release` runs after it, so each checks.
            val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
            val onApplicationThread: (() -> Unit) -> Unit = { action ->
                if (handler.looper.isCurrentThread) action() else handler.post(action)
            }
            val resync = { if (!released) sync() }
            memoryGuard = MemoryGuard(components.applicationContext, onApplicationThread, resync).also { it.register() }
            dataSaver = DataSaverRule(components.applicationContext, onApplicationThread, resync).also { it.register() }
            manager = DefaultPreloadManager.Builder(
                components.applicationContext,
                ranking,
                TargetPreloadStatusControl<Int, PreloadStatus> { index -> statusFor(index) },
            )
                .setMediaSourceFactory(components.mediaSourceFactory)
                .setLoadControl(components.loadControl)
                .setTrackSelectorFactory(components.trackSelectorFactory())
                .apply {
                    components.bandwidthMeter?.let { setBandwidthMeter(it) }
                    components.renderersFactory?.let { setRenderersFactory(it) }
                    components.clock?.let { setClock(it) }
                }
                .setPreloadLooper(components.playbackLooper)
                .build()
            sync()
        }

        override fun sourceFor(player: SuperPlayer, item: MediaItem): WarmStart? {
            if (released) return null
            val heldWarm = warm.remove(player)
            val start = if (heldWarm == item) {
                WarmStart.Prepared
            } else {
                // A row another player holds warm, played on a player of the feed's own choosing: that
                // player lets it go first, so one source is never on two players.
                warm.entries.firstOrNull { it.value == item }?.key?.let { coolDown(it) }
                if (item in registered) manager?.getMediaSource(item)?.let { WarmStart.Source(it) } else null
            }
            val previous = if (start != null) adopted.put(player, item) else adopted.remove(player)
            // What the player moves on from was one view's: its source carries that view's CMCD `sid`,
            // so it is released rather than handed to the next view, and fetched afresh if still wanted.
            if (previous != null && previous != item) forget(previous)
            sync()
            return start
        }

        override fun preferredIdle(idle: List<SuperPlayer>): SuperPlayer? {
            if (released) return null
            val current = currentItem()
            return idle.lastOrNull { current != null && warm[it] == current } ?: idle.lastOrNull { it !in warm }
        }

        override fun onAcquired(player: SuperPlayer) {
            if (released) return
            val held = warm[player] ?: return
            if (held == currentItem()) return
            coolDown(player)
            sync()
        }

        override fun onRecycled(player: SuperPlayer) {
            warm.remove(player)
            val taken = adopted.remove(player)
            if (released) return
            taken?.let { forget(it) }
            // A player back in the pool can hold the next row warm.
            sync()
        }

        override fun onPoolReleasing() {
            release()
        }
    }

    /** Orders the manager's items by their place in the window; an item outside it sorts last. */
    private class ScrollRanking : DefaultPreloadManager.SimpleRankingDataComparator() {
        var ranks: Map<Int, Int> = emptyMap()

        override fun compare(o1: Int, o2: Int): Int = rankOf(o1).compareTo(rankOf(o2))

        private fun rankOf(index: Int): Int = ranks[index] ?: Int.MAX_VALUE
    }

    private class Registered(val index: Int, val contentId: String)

    /**
     * Builds a [PreloadCoordinator] attached to [pool].
     *
     * Build it before the pool builds its first player: the players a coordinator can hand a warm
     * source to are the ones built on the engine it shares, and [build] throws, naming ADR-0010 rule 8,
     * on a pool that has already built one. One coordinator per pool.
     */
    public class Builder(private val pool: PlayerPool) {

        public fun build(): PreloadCoordinator = PreloadCoordinator(pool).also { pool.attach(it.attachment) }
    }
}

// File-level rather than in a companion, where Kotlin emits `const` values as public static fields of
// the class and the tracked API surface would carry them.
private const val FORWARD = 1
private const val BACKWARD = -1
private const val MS_PER_SECOND = 1_000f

/**
 * How far ahead the velocity projects the heading: half a second.
 *
 * Long enough that a fling's projection lands past the rows it will pass, short enough that a slow
 * drag still counts the next row as the next row. A starting value named for tuning in `PRD.md` Part
 * 4's tuning phase, not a measured one.
 */
private const val HEADING_HORIZON_MS = 500f
