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

package com.superplayer.abr

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.superplayer.core.BufferPolicy
import com.superplayer.core.toLoadControl

/**
 * The Media3 half of [AdaptiveBufferPolicy]: a `LoadControl` whose targets can change after
 * construction, which is the constraint ADR-0009 rule 5 names and the thing `PRD.md` §3.1 asks
 * this module to lift.
 *
 * It decides nothing. Every duration comes in as a [BufferPolicy] through [retarget] and becomes a
 * `DefaultLoadControl` through core's own `EngineBinding.kt`, so there is not one buffer number in
 * this file (ADR-0005 rule 2) and the engine runs Media3's own load logic over the policy's
 * numbers rather than a re-implementation of it (ADR-0001). What this class adds is only the
 * *swap*.
 *
 * ## Why the swap waits for the engine's next poll
 *
 * A decision arrives on the application thread; the engine polls its load control on the playback
 * thread, and `DefaultLoadControl` pins itself to the first thread that prepares it and refuses
 * any other. So [retarget] only records the policy, and the next call the engine makes — to
 * continue loading, to start playback, to read the back buffer — is where the delegate is rebuilt,
 * on the thread that will use it. That is at most one poll late, some ten milliseconds, and it is
 * what keeps the whole class free of locks on the engine's path.
 *
 * ## What a rebuilt delegate has to be told
 *
 * A `DefaultLoadControl` keeps per-player state from `onPrepared` and `onTracksSelected`, and
 * asserts on it in every poll; a fresh one that has not been told is an exception on the playback
 * thread. Both calls are therefore recorded per player and replayed into every rebuilt delegate.
 * The allocator is shared across every delegate for the reason `EngineBinding.kt` gives at
 * `toLoadControl`: the sample queues account against the one they were handed at prepare time.
 *
 * [onEngineReleased] runs when the engine releases this control: it is the one hook Media3 offers
 * for "this player is gone", and it is how the policy's oracle is released with the player it
 * measured for rather than leaking a connectivity callback.
 */
internal class AdaptiveLoadControl(
    private val onEngineReleased: () -> Unit = {},
) : LoadControl {

    // Media3's own segment size, which `DefaultLoadControl.Builder` uses when built without one:
    // a size, not a duration, and not a choice of this file.
    private val allocator = DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    private val lock = Any()

    // Guarded by lock: the policy the next poll will apply, if one arrived since the last.
    private var pending: BufferPolicy? = null

    // Playback thread only, once the engine has made its first call.
    private var delegate: LoadControl? = null
    private val prepared = LinkedHashSet<PlayerId>()
    private val selections = HashMap<PlayerId, Selection>()

    /** The policy the next poll runs under. Any thread. */
    fun retarget(policy: BufferPolicy) {
        synchronized(lock) { pending = policy }
    }

    override fun getAllocator(playerId: PlayerId): Allocator = allocator

    override fun onPrepared(playerId: PlayerId) {
        current().onPrepared(playerId)
        prepared += playerId
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) {
        selections[parameters.playerId] = Selection(parameters, trackGroups, trackSelections)
        current().onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: PlayerId) {
        selections -= playerId
        current().onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        selections -= playerId
        prepared -= playerId
        current().onReleased(playerId)
        onEngineReleased()
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = current().getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = current().retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = current().shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = current().shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = current().shouldStartPlayback(parameters)

    /** The delegate for this poll, rebuilt first if a policy is waiting. Playback thread. */
    private fun current(): LoadControl {
        val next = synchronized(lock) { pending.also { pending = null } }
        if (next != null) {
            val rebuilt = next.toLoadControl(allocator)
            for (playerId in prepared) {
                rebuilt.onPrepared(playerId)
                selections[playerId]?.let { rebuilt.onTracksSelected(it.parameters, it.trackGroups, it.trackSelections) }
            }
            delegate = rebuilt
        }
        return checkNotNull(delegate) { "No decision has reached this load control yet" }
    }

    private class Selection(
        val parameters: LoadControl.Parameters,
        val trackGroups: TrackGroupArray,
        val trackSelections: Array<out ExoTrackSelection?>,
    )
}
