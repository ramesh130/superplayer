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
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.test.utils.FakeTimeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.BufferPolicy
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The swap, driven the way the engine drives a load control: prepared, told its tracks, then
 * polled. What is asserted is that a re-target takes effect at the next poll and that the rebuilt
 * delegate has been told what the engine told the old one — the two things the class exists for
 * beyond delegation. What it decides is `AdaptiveBufferPolicy`'s and is not touched here.
 *
 * Under Robolectric only because Media3's fake timeline builds a `MediaItem`, which needs `Uri`.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLoadControlTest {

    private val playerId = PlayerId(/* playerName= */ "adaptive-load-control-test")

    // A real window: Media3's load control reads the media item's URI scheme from the timeline to
    // tell local playback from streaming, and an empty timeline has no period to read it from.
    private val timeline = FakeTimeline(/* windowCount= */ 1)
    private val mediaPeriodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))

    @Test
    fun aRetargetTakesEffectAtTheEnginesNextPoll() {
        val control = AdaptiveLoadControl()
        control.retarget(policy(bufferForPlaybackMs = 1_000))
        control.onPrepared(playerId)
        control.onTracksSelected(parameters(bufferedMs = 0), TrackGroupArray.EMPTY, arrayOfNulls(0))

        assertThat(control.shouldStartPlayback(parameters(bufferedMs = 1_500))).isTrue()

        control.retarget(policy(bufferForPlaybackMs = 2_000))

        assertThat(control.shouldStartPlayback(parameters(bufferedMs = 1_500))).isFalse()
        assertThat(control.shouldStartPlayback(parameters(bufferedMs = 2_500))).isTrue()
    }

    @Test
    fun theBackBufferFollowsTheDecisionToo() {
        val control = AdaptiveLoadControl()
        control.retarget(policy(backBufferMs = 0))
        control.onPrepared(playerId)
        assertThat(control.getBackBufferDurationUs(playerId)).isEqualTo(0)

        control.retarget(policy(backBufferMs = 4_000, retainFromKeyframe = true))
        assertThat(control.getBackBufferDurationUs(playerId)).isEqualTo(4_000_000)
        assertThat(control.retainBackBufferFromKeyframe(playerId)).isTrue()
    }

    @Test
    fun aRetargetBeforeAnyEngineCallIsAppliedByTheFirstOne() {
        val control = AdaptiveLoadControl()
        control.retarget(policy(bufferForPlaybackMs = 1_000))
        control.retarget(policy(bufferForPlaybackMs = 3_000))
        control.onPrepared(playerId)
        control.onTracksSelected(parameters(bufferedMs = 0), TrackGroupArray.EMPTY, arrayOfNulls(0))

        // The last policy wins; the one before it was never built.
        assertThat(control.shouldStartPlayback(parameters(bufferedMs = 2_000))).isFalse()
        assertThat(control.shouldStartPlayback(parameters(bufferedMs = 3_000))).isTrue()
    }

    @Test
    fun theAllocatorIsOneObjectAcrossEveryRetarget() {
        val control = AdaptiveLoadControl()
        control.retarget(policy(bufferForPlaybackMs = 1_000))
        control.onPrepared(playerId)
        val before = control.getAllocator(playerId)
        control.retarget(policy(bufferForPlaybackMs = 2_000))
        control.shouldContinueLoading(parameters(bufferedMs = 0))
        assertThat(control.getAllocator(playerId)).isSameInstanceAs(before)
    }

    @Test
    fun releaseRunsTheHookOnce() {
        var released = 0
        val control = AdaptiveLoadControl(onEngineReleased = { released++ })
        control.retarget(policy(bufferForPlaybackMs = 1_000))
        control.onPrepared(playerId)
        control.onReleased(playerId)
        assertThat(released).isEqualTo(1)
    }

    private fun policy(
        bufferForPlaybackMs: Int = 1_000,
        backBufferMs: Int = 0,
        retainFromKeyframe: Boolean = false,
    ): BufferPolicy = BufferPolicy(
        minBufferMs = 10_000,
        maxBufferMs = 20_000,
        bufferForPlaybackMs = bufferForPlaybackMs,
        bufferForPlaybackAfterRebufferMs = 5_000,
        backBufferMs = backBufferMs,
        retainBackBufferFromKeyframe = retainFromKeyframe,
    )

    private fun parameters(bufferedMs: Long): LoadControl.Parameters = LoadControl.Parameters(
        playerId,
        timeline,
        mediaPeriodId,
        /* playbackPositionUs= */ 0,
        /* bufferedDurationUs= */ bufferedMs * 1_000,
        /* playbackSpeed= */ 1f,
        /* playWhenReady= */ true,
        /* rebuffering= */ false,
        /* targetLiveOffsetUs= */ C.TIME_UNSET,
        /* lastRebufferRealtimeMs= */ C.TIME_UNSET,
    )
}
