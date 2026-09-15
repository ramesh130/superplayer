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

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
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
 * // As the feed settles on a row, and as it scrolls.
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
 * ## Lifetimes
 *
 * An item that leaves the window is removed, and its prefetch cancelled. An item a player took is left
 * with that player until the player moves on or is recycled, because the source is the player's
 * then; Media3 releases a source a player holds only once the player lets it go. [release] cancels
 * every prefetch and forgets every minted id; it does not stop a row that is already playing.
 *
 * ## Threading
 *
 * Call it from the thread the pool belongs to — the main thread for a UI-owned pool. That is Media3's
 * rule for the preload manager and the pool's own.
 *
 * ## Where this stops short of ADR-0010, stated
 *
 * - **Rule 11's memory guard and data-saver rule are not here yet**; they are #160's, and until then a
 *   coordinator prefetches what the decision says whatever the heap or the data-saver setting.
 * - **Rule 8's bound on warm decoders, and holding an item at its first rendered frame, are not here
 *   yet**; decoder warm-up is #159's. Nothing a coordinator holds today owns a decoder — the deepest
 *   [PreloadDepth] is media loaded into memory — so there is nothing yet for `pool.maxSize` to bound.
 * - **Rule 6's `setCache` is not called.** It exists so the manager's cached stage writes where the
 *   chain reads, and no [PreloadDepth] decides a cached range yet: every prefetch is a load through the
 *   chain, whose cache slot writes a segment to the pool's cache like any other read. The call arrives
 *   with a cached depth.
 * - **Rule 10's depth is read, not pushed.** The half in force is read from the pool's decision each
 *   time the window is recomputed rather than through a `DecisionTarget`, which honours a changed depth
 *   on the next scroll instead of the next invalidation Media3 would make of its own accord — the same
 *   moment in a feed, which invalidates on every scroll.
 */
public class PreloadCoordinator private constructor() {

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
        sync()
    }

    /**
     * Where the feed is: [currentIndex] is the row playing or about to, and [velocityItemsPerSecond]
     * how fast the feed is moving through rows — positive toward later items, negative toward earlier,
     * zero once it has settled. The class KDoc says how each is used.
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
        sync()
    }

    /**
     * Cancels every prefetch and lets go of everything prefetched that no player took. Idempotent.
     *
     * Rows already playing carry on. The pool itself is not released, and still builds its players on
     * the engine it shares; a pool with a released coordinator loads every item cold.
     */
    public fun release() {
        if (released) return
        released = true
        manager?.release()
        manager = null
        components?.sessionIds?.clear()
        registered.clear()
        adopted.clear()
    }

    /** Recomputes the window and brings the manager's items into line with it. */
    private fun sync() {
        val manager = manager ?: return
        val shared = checkNotNull(components)
        val order = priorityOrder(shared.decision.preload)
        ranking.ranks = order.withIndex().associate { (rank, index) -> index to rank }
        val wanted = order.associateBy { shared.itemOf(requests[it]) }

        for ((item, entry) in registered.toList()) {
            val stillWanted = wanted[item] == entry.index
            // The row the feed has just made current keeps what was fetched for it: a feed moves its
            // position before it hands the row a player, and the row is about to take the source.
            val aboutToPlay = entry.index == position && requests.getOrNull(position)?.contentId == entry.contentId
            if (!stillWanted && !aboutToPlay && item !in adopted.values) forget(item)
        }
        for ((item, index) in wanted) {
            if (item in registered) continue
            val contentId = requests[index].contentId
            // Before `add`, which creates the source: its CMCD configuration is fixed then.
            shared.sessionIds.mint(contentId)
            manager.add(item, index)
            registered[item] = Registered(index, contentId)
        }
        manager.invalidate()
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

    /** Core's side of the attachment, kept off the public type: `PoolAttachment` is internal. */
    private val attachment = object : PoolAttachment {

        override fun onEngineAssembled(components: SharedComponents) {
            if (released) return
            this@PreloadCoordinator.components = components
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

        override fun sourceFor(player: SuperPlayer, item: MediaItem): MediaSource? {
            val warm = if (released || item !in registered) null else manager?.getMediaSource(item)
            val previous = if (warm != null) adopted.put(player, item) else adopted.remove(player)
            // What the player moves on from was one view's: its source carries that view's CMCD `sid`,
            // so it is released rather than handed to the next view, and fetched afresh if still wanted.
            if (previous != null && previous != item && !released) {
                forget(previous)
                sync()
            }
            return warm
        }

        override fun onRecycled(player: SuperPlayer) {
            val taken = adopted.remove(player) ?: return
            if (released) return
            forget(taken)
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

        public fun build(): PreloadCoordinator = PreloadCoordinator().also { pool.attach(it.attachment) }
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
