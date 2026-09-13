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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer

/**
 * Observes the conditions, consults the policy on the named triggers, and hands a changed decision
 * to the engine's retargetable components: ADR-0009 rules 3, 4 and 5 as one object.
 *
 * Exists only on a player whose engine was built with a [DecisionTarget]. On every other player none
 * of this is allocated and none of these listeners is registered, which is the "pays nothing"
 * claim of rule 7, and `SuperPlayerPolicyTest` counts it rather than assuming it.
 *
 * ## What is observed, and from where
 *
 * - The transport, from [ConnectivityManager]'s default-network callback; the initial value is
 *   read at construction.
 * - The stream type, from the engine's timeline as it arrives and changes.
 * - The stall history, from the engine's own state transitions: a rebuffer is a buffering state
 *   entered after playback was ready, ended by playback becoming ready again, and not caused by a
 *   seek. [StallHistory] states that rule.
 * - The playback speed, from the engine's playback parameters.
 * - The throughput, from the meter if it is a [ThroughputSource]; otherwise unobserved.
 * - The heap budget, once, from `DeviceCapacity.kt`'s reading.
 *
 * Every translation is `ConditionsBinding.kt`'s; this class only decides *when* to read.
 *
 * ## When the policy is asked
 *
 * On the five [DecisionTrigger]s and nothing else. Engine callbacks arrive on the application
 * thread already; the connectivity callback and the meter's arrive on their own threads and are
 * posted to it, so [PlaybackPolicy.decide] always runs on the thread that drives the player and
 * the target is always re-targeted from that thread. A consultation whose answer equals the
 * decision in force changes nothing and emits nothing.
 *
 * The stall history is *this session's*: it resets on every media item transition that is not a
 * repeat, which is the engine-observed shape of a session boundary and covers content set by any
 * route.
 */
internal class DecisionReapplication(
    private val context: Context,
    private val policy: PlaybackPolicy,
    private val target: DecisionTarget,
    private val throughput: ThroughputSource?,
) {

    /** Told of every change: the facade updates what it reports and signals telemetry. */
    var onDecisionChanged: ((PlaybackDecision, DecisionTrigger) -> Unit)? = null

    @Volatile
    var decision: PlaybackDecision? = null
        private set

    private val heapBudgetBytes: Long? = heapBudgetBytesOf(context)

    // Written on the application thread only, once started; read there too.
    private var transport: NetworkTransport? = currentNetworkTransportOf(context)
    private var streamType: StreamType? = null
    private var playbackSpeed: Float = PlaybackParameters.DEFAULT.speed
    private var stallHistory: StallHistory = StallHistory.NONE

    private var engine: ExoPlayer? = null
    private var handler: Handler? = null

    // The stall state machine. `readyOnce` is whether this item has ever been ready, which is what
    // separates a rebuffer from start-up; `stallStartedAtMs` is the open stall's start on the
    // monotonic clock, or null; `seekPending` marks a stall that a seek caused.
    private var readyOnce = false
    private var stallStartedAtMs: Long? = null
    private var seekPending = false
    private var lastRebufferEndedAtMs: Long? = null
    private var lastRebufferDurationMs: Long? = null

    /** The conditions as observed now — what the policy is handed. */
    fun currentConditions(): PlaybackConditions = PlaybackConditions(
        transport = transport,
        throughput = throughput?.currentEstimate(),
        stallHistory = stallHistory.aged(),
        streamType = streamType,
        heapBudgetBytes = heapBudgetBytes,
        playbackSpeed = playbackSpeed,
    )

    /**
     * The first consultation, at construction, before the engine exists: its answer is handed to
     * the target so the components carry it from their first poll.
     */
    fun decideInitially(): PlaybackDecision {
        val initial = policy.decide(currentConditions())
        decision = initial
        initial.applyTo(target)
        return initial
    }

    /** Registers every observer. Called once the engine is built and the facade constructed. */
    fun start(engine: ExoPlayer) {
        check(this.engine == null) { "Already started" }
        this.engine = engine
        // Before any registration, because a real ConnectivityManager calls back immediately on
        // registration and that callback posts here.
        handler = Handler(engine.applicationLooper)
        engine.addListener(engineListener)
        // The timeline may already be known if content was set before this ran; it is not, in
        // practice, because the facade starts this before returning from build(), but reading it
        // costs nothing and guessing would.
        streamType = engine.observedStreamType()
        playbackSpeed = engine.playbackParameters.speed
        registerNetworkCallback()
        throughput?.addListener(throughputListener)
    }

    /** Unregisters everything [start] registered. Safe to call without a start, and twice. */
    fun stop() {
        val engine = engine ?: return
        engine.removeListener(engineListener)
        throughput?.removeListener(throughputListener)
        networkCallback?.let { callback ->
            try {
                context.connectivityManager()?.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Already unregistered, or never registered: nothing to undo.
            }
        }
        networkCallback = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        this.engine = null
    }

    private fun reconsult(trigger: DecisionTrigger) {
        val next = policy.decide(currentConditions())
        if (next == decision) return
        decision = next
        next.applyTo(target)
        onDecisionChanged?.invoke(next, trigger)
    }

    /** Runs [action] on the application thread, from a callback that may be on any other. */
    private fun onApplicationThread(action: () -> Unit) {
        val handler = handler ?: return
        if (handler.looper.isCurrentThread) action() else handler.post(action)
    }

    private val engineListener = object : Player.Listener {

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val engine = engine ?: return
            val observed = engine.observedStreamType()
            if (observed == streamType) return
            streamType = observed
            // Becoming known counts as a change: the policy has been deciding without it.
            if (observed != null) reconsult(DecisionTrigger.STREAM_TYPE_CHANGED)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            // New content, new session: the history and the start-up boundary begin again, and
            // the stream type is unknown until its manifest is read.
            readyOnce = false
            stallStartedAtMs = null
            seekPending = false
            stallHistory = StallHistory.NONE
            lastRebufferEndedAtMs = null
            lastRebufferDurationMs = null
            streamType = null
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seekPending = true
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (readyOnce && stallStartedAtMs == null && !seekPending) {
                        stallStartedAtMs = SystemClock.elapsedRealtime()
                    }
                }

                Player.STATE_READY -> {
                    readyOnce = true
                    seekPending = false
                    val startedAt = stallStartedAtMs ?: return
                    stallStartedAtMs = null
                    val endedAt = SystemClock.elapsedRealtime()
                    lastRebufferEndedAtMs = endedAt
                    lastRebufferDurationMs = endedAt - startedAt
                    stallHistory = StallHistory(
                        rebufferCount = stallHistory.rebufferCount + 1,
                        msSinceLastRebufferEnded = 0,
                        lastRebufferDurationMs = endedAt - startedAt,
                    )
                    reconsult(DecisionTrigger.REBUFFER_ENDED)
                }

                // Idle or ended: a stall that did not resume is not a rebuffer that ended.
                else -> {
                    stallStartedAtMs = null
                    seekPending = false
                }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (playbackParameters.speed == playbackSpeed) return
            playbackSpeed = playbackParameters.speed
            reconsult(DecisionTrigger.PLAYBACK_SPEED_CHANGED)
        }
    }

    private val throughputListener = ThroughputSource.Listener {
        onApplicationThread { reconsult(DecisionTrigger.THROUGHPUT_CHANGED) }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        val connectivity = context.connectivityManager() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onTransportObserved(capabilities.toNetworkTransport(context))
            }

            override fun onLost(network: Network) {
                onTransportObserved(NetworkTransport.Unknown)
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            // The platform caps network callbacks per process and throws past the cap; a pool at
            // the limit loses an observation rather than the app. The transport then stays what
            // construction read.
        }
    }

    private fun onTransportObserved(observed: NetworkTransport) {
        onApplicationThread {
            if (observed == transport) return@onApplicationThread
            transport = observed
            reconsult(DecisionTrigger.TRANSPORT_CHANGED)
        }
    }

    /** The history with its "since" refreshed to now: it is a duration, and durations age. */
    private fun StallHistory.aged(): StallHistory {
        val endedAt = lastRebufferEndedAtMs ?: return this
        return copy(msSinceLastRebufferEnded = SystemClock.elapsedRealtime() - endedAt)
    }
}
