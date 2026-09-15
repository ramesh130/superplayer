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

package com.superplayer.core

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player

/**
 * A bounded, recycling set of [SuperPlayer] instances, for screens that show many videos at once.
 *
 * A feed or a grid has no fixed number of videos in it — a viewer scrolls, and the number of items
 * that have *ever* been on screen only goes up. Building a player per item is the obvious
 * implementation and it fails in a specific, well-known way: hardware decoder instances are a fixed
 * device resource, and the app that asks for one too many does not degrade, it throws from
 * `MediaCodec` or falls back to a software decoder and drops frames on every item after. The pool is
 * the answer, and its bound is the whole of what makes it one.
 *
 * ```kotlin
 * // Once, for the screen.
 * val pool = PlayerPool.Builder(context).setProfile(PlaybackProfile.SHORT_FORM).build()
 *
 * // As an item scrolls in. Null means every player is in use — show the item's artwork.
 * val player = pool.acquire()
 * player?.let {
 *     playerView.player = it
 *     it.setMediaRequest(request)
 *     it.prepare()
 * }
 *
 * // As it scrolls out.
 * playerView.player = null
 * player?.let { pool.recycle(it) }
 *
 * // When the screen goes away.
 * pool.release()
 * ```
 *
 * ## The bound is derived, not chosen
 *
 * [maxSize] comes from what the *device* reports — its concurrent decoder limits for the codecs the
 * feed is in ([Builder.setFeedCodecs]) and its app heap — and `DeviceCapacity.kt` is where that
 * reading happens and why each half of it is read the way it is. A constant here would be a constant
 * that is wrong on most phones: too high on the cheap device that is the reason a pool exists, and
 * too low on the flagship where it costs a feature nobody gets back. A device that reports nothing usable yields a pool of one, which still works.
 *
 * [Builder.setMaxSize] lets a consumer ask for *fewer* — a two-up grid needs two — but never for
 * more. The device's answer is a ceiling rather than a suggestion.
 *
 * ## Why [acquire] can return null
 *
 * Because the bound is real. A pool that blocked would block the main thread; a pool that grew past
 * its bound would be a pool in name only; a pool that threw would make the caller write the same
 * `try`/`catch` that a null check writes more plainly. Null means "every player this device can
 * afford is in use, show the artwork" — the still-frame placeholder a feed needs anyway for the
 * items that are off screen.
 *
 * In practice a caller that acquires on bind and recycles on unbind never sees null while fewer
 * items are visible than the device can afford, which on any modern phone is more than fit on it.
 *
 * ## Reuse is clean, and that is not free
 *
 * A recycled player is reset before it is handed out again — its surface detached, its content and
 * its playback state cleared, and every listener the previous holder registered removed. Without the
 * surface detach the recycled view flashes the previous item's last frame, which is the defect that
 * makes hand-rolled pools obviously hand-rolled; without the listener removal the previous holder
 * keeps being called about content it no longer shows, and keeps being reachable from the player,
 * which is a leak of exactly the size of the feed. [SuperPlayer.resetForReuse] is the whole of it.
 *
 * ## Concurrently *playing* pooled players contend for audio focus
 *
 * Every SuperPlayer requests audio focus while it plays — a platform rule rather than a policy, and
 * ADR-0006 rule 1 is why it is not a profile's to switch off. Audio focus, though, is a single
 * token: a second player calling [Player.play] takes it from the first, which Media3 answers by
 * clearing that player's [Player.getPlayWhenReady]. Two pooled players playing at once is therefore
 * one pooled player playing and one that stopped.
 *
 * That is the correct behaviour for two players that both want to be *heard*, and it is why a feed
 * does not have two. The shape that works is the shape real feeds use: one item plays, the rest hold
 * a prepared first frame, and the pool is what makes that first frame cost a recycled player rather
 * than a new one — which is what the demo's feed screen does. A screen that genuinely wants several
 * playing at once has one audio source among them by definition, and says so with Media3's own
 * [Player.setAudioAttributes], passing `handleAudioFocus = false` for the silent ones. [recycle]
 * puts that back, so the next item to use that player is not silently exempt from the rule.
 *
 * ## Threading
 *
 * Call it from the thread the players belong to, which for a UI-owned pool is the main thread. That
 * is the same rule Media3 puts on [Player] itself, and a pool whose players may only be touched from
 * one thread gains nothing from being reachable from others.
 */
public class PlayerPool private constructor(
    /**
     * The most players this pool will ever have alive at once — the device's answer, possibly
     * lowered by [Builder.setMaxSize].
     *
     * Public because a caller has to size its own behaviour against it: a feed decides how many
     * items around the viewport to prepare, and a screen that wants to show video on every visible
     * row needs to know when it cannot.
     */
    public val maxSize: Int,
    private val newPlayer: (PooledEngine?) -> SuperPlayer,
    /** Whether the pool's policy is an extension, whose components one engine per pool has to share. */
    private val policyBringsComponents: Boolean,
) {

    /**
     * The engine every player is built on, when they have to share one (ADR-0010 rule 9) — see
     * [PooledEngine] — or null for a pool whose players are each built as a player of their own.
     */
    private var pooledEngine: PooledEngine? = if (policyBringsComponents) PooledEngine(attachment = null) else null

    private var attached = false

    /**
     * Attaches [attachment] to this pool, so every player it builds from now on is built on one shared
     * engine and adopts through it (ADR-0010 rule 8). `superplayer-preload`'s `PreloadCoordinator.Builder`
     * is the caller.
     *
     * Before the first player, because the components a preload manager requires are the ones every
     * player is built with, and a player already built was built with its own: throws, naming the rule,
     * rather than serving a pool half of whose players the attachment cannot use. One attachment per pool.
     */
    internal fun attach(attachment: PoolAttachment) {
        check(!isReleased) { "This PlayerPool has been released" }
        check(!attached) { "A PlayerPool takes one attachment; this one already has a PreloadCoordinator" }
        check(size == 0) {
            "A PreloadCoordinator is attached before its pool builds a player (ADR-0010 rule 8); " +
                "this pool has already built $size"
        }
        attached = true
        pooledEngine = PooledEngine(attachment)
    }

    /**
     * Players that have been built and handed back, newest first.
     *
     * Newest first — [ArrayDeque.removeLast] on [acquire] — so a run of acquire/recycle pairs keeps
     * returning to the same player rather than cycling through all of them. The ones at the far end
     * stay warm without being touched, which is what makes a mostly-idle grid cost fewer decoders
     * than its bound rather than exactly its bound.
     */
    private val idle = ArrayDeque<SuperPlayer>()

    /**
     * Players currently out with a caller.
     *
     * Identity-compared, which is what a set of [SuperPlayer] gives: the class declares no `equals`,
     * so two players are the same entry only if they are the same object. [recycle] relies on that
     * to reject a player this pool never handed out.
     */
    private val inUse = mutableSetOf<SuperPlayer>()

    private var isReleased = false

    /**
     * How many players exist right now — in use plus idle.
     *
     * Never above [maxSize], and below it until demand has actually asked for that many: players are
     * built on the first [acquire] that has no idle one to give, so a pool of eight on a screen
     * showing three has built three.
     */
    public val size: Int
        get() = inUse.size + idle.size

    /** How many players are out with callers and have not been handed back. */
    public val inUseCount: Int
        get() = inUse.size

    /** The players built and handed back, newest last: what an attachment may hold warm. */
    internal val idlePlayers: List<SuperPlayer>
        get() = idle.toList()

    /**
     * A player to show one item with, or null if every player this device can afford is already out.
     *
     * Reuses an idle player if there is one and builds a new one otherwise, up to [maxSize]. The
     * player comes back in the state a freshly built one is in — see [SuperPlayer.resetForReuse] for
     * what that means and what it deliberately does not carry over.
     *
     * Hand it back with [recycle] when the item leaves. Do not call [Player.release] on it: the pool
     * owns its players' lifetimes, and a released player handed back would be handed out again.
     */
    public fun acquire(): SuperPlayer? {
        check(!isReleased) { "This PlayerPool has been released" }

        if (idle.isNotEmpty()) {
            // Newest first, unless an attachment holds a player warm on the row the feed has just
            // made current — or holds the others warm, and would rather hand out one holding nothing.
            val reused = pooledEngine?.preferredIdle(idle.toList()) ?: idle.last()
            check(idle.remove(reused)) { "An attachment preferred a player that is not idle in this pool" }
            // Before the caller has it, so a warm decoder held for some other row is let go first.
            pooledEngine?.onAcquired(reused)
            inUse += reused
            return reused
        }
        if (inUse.size >= maxSize) return null

        return newPlayer(pooledEngine).also { inUse += it }
    }

    /**
     * Takes [player] back, resets it, and makes it available to the next [acquire].
     *
     * Detach it from its view first — `playerView.player = null` — so that Media3 is not holding a
     * surface belonging to a view that is about to show something else. The pool clears the player's
     * own side of that regardless, which is what stops a recycled view from flashing the previous
     * item's last frame, but only the caller knows about the view.
     *
     * Rejects a player this pool did not hand out, rather than adopting it. A pool that accepted
     * anything would silently take on the lifetime of a player somebody else releases, and the
     * crash that produces surfaces in the next item's playback rather than here.
     *
     * Does nothing after [release], rather than throwing. That is the teardown [release] already
     * documents as normal — it releases players that are still out — and the callers holding those
     * players hand them back on their own schedule: a `RecyclerView` dispatches
     * `onViewDetachedFromWindow` for still-attached holders *during* the teardown that released the
     * pool. Throwing there would crash an app for doing exactly what this class tells it to do, and
     * the player in question has already been released, so there is nothing left to refuse.
     */
    public fun recycle(player: SuperPlayer) {
        if (isReleased) return
        require(inUse.remove(player)) {
            "That player did not come from this pool, or has already been recycled"
        }

        player.resetForReuse()
        idle.addLast(player)
        // After the reset, once the player has let its item go: a prefetched source the attachment
        // releases now is released behind the player's own release of it, on the same thread. And
        // once it is idle, so an attachment can hold the next row warm on it.
        pooledEngine?.onRecycled(player)
    }

    /**
     * Releases every player this pool built, in use or not, and refuses further use.
     *
     * Call it when the screen goes away. Players in use are released too, deliberately: a caller
     * that still holds one at this point has a player whose screen no longer exists, and leaving it
     * alive to keep a decoder and a playback thread would be the leak this class exists to prevent.
     * Detach any views first, for the same reason [recycle] says to.
     *
     * Idempotent, so a screen that releases in both `onDestroy` and a disposal effect is not a bug.
     */
    public fun release() {
        if (isReleased) return
        isReleased = true

        // The attachment first, while the playback thread its prefetches run on is still alive.
        pooledEngine?.onPoolReleasing()
        // In-use first: those are the ones a caller may still be touching, and the sooner they stop
        // holding a decoder the better. Order is otherwise immaterial — release is per player.
        (inUse + idle).forEach { it.release() }
        inUse.clear()
        idle.clear()
        // Last, once no player is left to run on the shared thread.
        pooledEngine?.release()
    }

    /**
     * Builds a [PlayerPool]. Media3's construction idiom, like [SuperPlayer.Builder], and it takes
     * the same [PlaybackProfile] because every player in one pool plays the same kind of content —
     * that is what makes them interchangeable.
     */
    public class Builder(private val context: Context) {

        private var profile: PlaybackProfile = PlaybackProfile.SHORT_FORM
        private var requestedMaxSize: Int? = null
        private var feedCodecs: Set<VideoCodec> = DEFAULT_FEED_CODECS
        private var policy: PlaybackPolicy? = null
        private var cache: ContentCache? = null
        private var telemetry: (() -> TelemetryCollector)? = null
        private var playerFactory: ((PooledEngine?) -> SuperPlayer)? = null

        /**
         * The kind of playback every player in this pool is for.
         *
         * Defaults to [PlaybackProfile.SHORT_FORM] rather than to [SuperPlayer.Builder]'s
         * [PlaybackProfile.VIDEO_ON_DEMAND], and the difference is not an oversight. A pool exists
         * for feeds and grids, short-form content is what fills them, and a pool of on-demand
         * players would have every one of them buffering for a long watch that a scroll is about to
         * end. A consumer with a grid of full-length titles says so here.
         */
        public fun setProfile(profile: PlaybackProfile): Builder = apply { this.profile = profile }

        /**
         * Asks for a pool of at most [maxSize] players.
         *
         * A ceiling on top of the device's, never a way past it: the result is the smaller of this
         * and what the device reports, so a screen that knows it will never show more than two
         * videos gets two, and one that asks for twenty on a device that can afford four gets four.
         *
         * Most screens should not call this. The device's answer is the one that is right on the
         * device it is running on, and a number here is a number that was right on somebody's desk.
         */
        public fun setMaxSize(maxSize: Int): Builder = apply {
            require(maxSize >= 1) { "A pool needs room for at least one player, not $maxSize" }
            requestedMaxSize = maxSize
        }

        /**
         * The video codecs this pool's content is encoded in — [VideoCodec.H264] and
         * [VideoCodec.HEVC] unless this says otherwise.
         *
         * The device's decoder limit is read for these codecs and no others, and the smallest of them
         * is the bound: an AV1 feed on a device whose AV1 decoder runs fewer instances than its HEVC
         * one gets the AV1 limit, rather than a pool its decoder cannot honour partway down a scroll.
         * Only the consumer knows what the feed contains; the device still says what each costs.
         *
         * Replaces the default rather than adding to it, so a feed that is AV1 alone names AV1 alone.
         * A codec the device has no decoder for is left out of the bound rather than shrinking it to
         * one — `DeviceCapacity.kt` argues the direction.
         */
        public fun setFeedCodecs(vararg codecs: VideoCodec): Builder = apply {
            require(codecs.isNotEmpty()) { "A feed is encoded in at least one codec" }
            feedCodecs = codecs.toSet()
        }

        /**
         * The policy every player in this pool decides with — what [SuperPlayer.Builder.setPolicy]
         * takes, for each of them.
         *
         * One policy object serves the whole pool. A policy that brings engine components of its
         * own — `superplayer-abr`'s — configures one engine, and every player the pool builds shares
         * it, so a decision re-applied on one row is re-applied for all: the rows are one screen on
         * one network (ADR-0010 rule 9).
         */
        public fun setPolicy(policy: PlaybackPolicy): Builder = apply { this.policy = policy }

        /**
         * The cache every player in this pool loads through — what [SuperPlayer.Builder.setCache]
         * takes, for each of them. Opened and released by the consumer (ADR-0010 rule 1); a pool
         * built without it has no cache.
         */
        public fun setCache(cache: ContentCache): Builder = apply { this.cache = cache }

        /**
         * Measures every player this pool builds, each with the collector [collectorFactory] returns
         * for it — what [SuperPlayer.Builder.setTelemetry] takes, once per player.
         *
         * A factory rather than a collector because a collector measures one player, and a pool builds
         * several. A recycled player keeps its collector: recycling ends the measurement session and
         * the next `setMediaRequest` opens another, so every row a player shows is its own session. A
         * pool built without this call builds its players without a collector, so each pays what
         * `SuperPlayerTelemetryTest.aPlayerBuiltWithNoTelemetryRegistersNoAnalyticsListener` counts:
         * nothing (ADR-0008 rule 2).
         */
        public fun setTelemetry(collectorFactory: () -> TelemetryCollector): Builder =
            apply { telemetry = collectorFactory }

        /**
         * How the pool builds a player — the seam tests reach construction through.
         *
         * The same seam as [SuperPlayer.Builder.setEngineConfigurator] rather than a second one:
         * tests hand this a lambda that builds a player on the existing harness, so a pooled player
         * gets the fake clock and fake data source every other test player gets, from the one place
         * that decides those. Without it a test would have to fake the pool's construction and the
         * engine's separately, and the two would drift. The lambda is handed the pool's shared engine,
         * or null, for [SuperPlayer.Builder.setPooledEngine].
         */
        @VisibleForTesting
        internal fun setPlayerFactory(factory: (PooledEngine?) -> SuperPlayer): Builder =
            apply { playerFactory = factory }

        public fun build(): PlayerPool {
            val deviceCapacity = concurrentPlayerCapacityOf(context, feedCodecs)
            val maxSize = requestedMaxSize?.coerceAtMost(deviceCapacity) ?: deviceCapacity
            val profile = profile
            val policy = policy
            val cache = cache
            val telemetry = telemetry
            val factory = playerFactory ?: { pooled ->
                SuperPlayer.Builder(context)
                    .setProfile(profile)
                    .apply { policy?.let { setPolicy(it) } }
                    .apply { cache?.let { setCache(it) } }
                    .apply { telemetry?.let { setTelemetry(it()) } }
                    .setPooledEngine(pooled)
                    .build()
            }

            return PlayerPool(maxSize, factory, policyBringsComponents = policy is EnginePolicyExtension)
        }
    }
}
