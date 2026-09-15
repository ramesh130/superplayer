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
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter

/**
 * The engine a [PlayerPool] builds every one of its players on, when its players have to agree on
 * their components: ADR-0010 rules 7 to 9 on core's side.
 *
 * A pool has one of these when a [PoolAttachment] — `superplayer-preload`'s coordinator — is attached
 * to it, or when its policy is an [EnginePolicyExtension]. Every other pool has none, and builds each
 * player exactly as `SuperPlayer.Builder` builds a player of its own (rule 13).
 *
 * The second case is wider than ADR-0010 rule 9's text, which names a pool with a coordinator. It is
 * the same reason reached without one: an extension policy configures one engine, a pool's player
 * factory hands every player the same policy object, and before this a pool built with
 * `superplayer-abr`'s policy could not build a second player at all. "One policy serves one pool" is
 * rule 9's own phrase, and a pool is one pool whether or not it prefetches.
 *
 * ## What is shared, and why
 *
 * The first player built assembles its engine as any player does, and what it assembled becomes
 * [SharedComponents]: its load control, bandwidth meter, selection factory and decision target, so an
 * extension policy configures one engine per pool rather than refusing a second player, and a decision
 * re-applied on any player is re-applied for all of them ("one pool, one decision", rule 9). Every
 * later player takes those in place of its own. Each still gets its own transfer chain and its own
 * measurement session, because the chain's layers and the session hold one view's state.
 *
 * Sharing a load control means sharing a playback thread too, so every player of such a pool runs on
 * one: Media3's `DefaultLoadControl` pins itself to the first thread that uses it and refuses any other,
 * and a second player on a thread of its own would fail inside the engine — releasing included.
 *
 * With an attachment, the attachment is handed the components the moment they exist. Media3's
 * preload manager prepares sources on the thread that will play them and allocates their periods from
 * the load control that will buffer them, which is why it requires players built with the same
 * components — `DefaultPreloadManager.Builder.buildExoPlayer` is Media3's own statement of that, and
 * this class is the same statement reached through `SuperPlayer.Builder` so a pooled player is still
 * built with everything core composes.
 * ref: https://developer.android.com/reference/androidx/media3/exoplayer/source/preload/DefaultPreloadManager.Builder
 *
 * ## Threading
 *
 * Built, attached, read and released on the thread the pool belongs to.
 */
internal class PooledEngine(private val attachment: PoolAttachment?) {

    /** What the first player assembled, or null until a player has been built. */
    var components: SharedComponents? = null
        private set

    /** Ids a coordinator mints for the items it prefetches; read by every pooled player's session. */
    val sessionIds: PremintedSessionIds = PremintedSessionIds()

    private var playbackThread: HandlerThread? = null

    /** The playback looper every pooled player runs on, started with the first player. */
    fun playbackLooper(): Looper {
        val thread = playbackThread ?: HandlerThread(PLAYBACK_THREAD_NAME).also {
            it.start()
            playbackThread = it
        }
        return thread.looper
    }

    /** Records what the first player assembled, and hands it to the attachment. */
    fun share(assembled: SharedComponents) {
        check(components == null) { "A pool shares the components its first player assembled, once" }
        components = assembled
        attachment?.onEngineAssembled(assembled)
    }

    /** A warm source for [item], which [player] is adopting, or null to load it cold. */
    fun sourceFor(player: SuperPlayer, item: MediaItem): MediaSource? = attachment?.sourceFor(player, item)

    /** Tells the attachment [player] was handed back, once it has been reset. */
    fun onRecycled(player: SuperPlayer) {
        attachment?.onRecycled(player)
    }

    /** Before the pool releases its players: the attachment lets go of what it holds on their thread. */
    fun onPoolReleasing() {
        attachment?.onPoolReleasing()
    }

    /**
     * After the pool has released its players: the shared thread ends once they have finished.
     *
     * Posted through the engines' own clock rather than quit here. An engine's release is a message on
     * this thread, delivered by that clock; quitting straight away can drop one requested but not yet
     * run, and a load control whose `onReleased` never ran is a connectivity callback never
     * unregistered. The clock delivers the quit after everything posted before it — a coordinator's
     * release included — whether it is the platform's or a test's.
     */
    fun release() {
        val thread = playbackThread ?: return
        playbackThread = null
        (components?.clock ?: Clock.DEFAULT).createHandler(thread.looper, /* callback= */ null).post { thread.quitSafely() }
    }

    companion object {
        /** Named so a thread dump can tell a pool's playback thread from a player's own. */
        const val PLAYBACK_THREAD_NAME: String = "SuperPlayer:PooledPlayback"
    }
}

/**
 * The engine components the first player of a [PooledEngine]'s pool assembled, handed to every later
 * player and to the attachment.
 */
internal class SharedComponents(
    /** The application's context, which a preload manager is built with. */
    val applicationContext: Context,
    /** The first player's loading path: the chain `TransferChain` composed, cache slot included. */
    val mediaSourceFactory: MediaSource.Factory,
    val loadControl: LoadControl,
    val bandwidthMeter: BandwidthMeter?,
    /** The selection factory installed or decided, or null for Media3's own adaptive selection. */
    val trackSelectionFactory: ExoTrackSelection.Factory?,
    val decisionTarget: DecisionTarget?,
    val renderersFactory: RenderersFactory?,
    val clock: Clock?,
    /** The pool's one playback looper, which a preload manager prepares on too. */
    val playbackLooper: Looper,
    /** Whether items are built carrying their identity: true when the pool's players have a cache. */
    private val identifiesContent: Boolean,
    /** The pool's ids minted ahead of adoption, which every pooled player's session reads (ADR-0010 rule 7). */
    val sessionIds: PremintedSessionIds,
    initialDecision: PlaybackDecision,
) {

    /**
     * The decision in force on the pool: the first player's at construction, then whichever pooled
     * player's policy answered last — one pool, one decision (ADR-0010 rule 9). Written on the
     * application thread; readable from any.
     */
    @Volatile
    var decision: PlaybackDecision = initialDecision
        internal set

    /**
     * [request] as the item a pooled player adopting it builds, so a source registered under it is
     * found by equality when the player asks (ADR-0010 rule 7). Built with the live half of the
     * decision in force; a player whose decision has moved since builds a different item and loads cold.
     */
    fun itemOf(request: MediaRequest): MediaItem =
        request.toMediaItem(identified = identifiesContent).withLiveLatency(decision.liveLatency)

    /**
     * How a preload manager builds the track selector it selects with: Media3's own selector over this
     * pool's selection factory, carrying the selection half of the decision when no target owns it —
     * the same selector `SuperPlayer.Builder` builds for each player.
     */
    fun trackSelectorFactory(): TrackSelector.Factory = TrackSelector.Factory { selectorContext ->
        val selector = trackSelectionFactory?.let { DefaultTrackSelector(selectorContext, it) } ?: DefaultTrackSelector(selectorContext)
        if (decisionTarget == null) selector.setParameters(decision.trackSelection.applyTo(selector.parameters))
        selector
    }
}

/**
 * What attaches to a [PlayerPool] to change how its players are built and what they adopt — the seam
 * `superplayer-preload`'s coordinator is attached through, as core's fourth Kotlin friend (ADR-0010
 * rules 6 to 8). Every call is on the pool's thread.
 */
internal interface PoolAttachment {

    /** The pool's first player has assembled the components every player and the manager share. */
    fun onEngineAssembled(components: SharedComponents)

    /**
     * A warm source for [item], which [player] is adopting through `setMediaRequest`, or null to load
     * it cold. A source returned here is [player]'s from now on.
     */
    fun sourceFor(player: SuperPlayer, item: MediaItem): MediaSource?

    /**
     * [player] was handed back to the pool and has been reset: it plays nothing, and a source it
     * adopted may be released.
     */
    fun onRecycled(player: SuperPlayer)

    /** The pool is releasing, before its players are. */
    fun onPoolReleasing()
}
