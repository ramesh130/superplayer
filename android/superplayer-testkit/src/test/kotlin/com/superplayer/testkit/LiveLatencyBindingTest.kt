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

package com.superplayer.testkit

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.LiveLatencyPolicy
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A decision's live half reaches the engine through `EngineBinding.kt`, asserted through the
 * facade: the speed a live player is caught holding its window at is one inside the range the
 * decision named, and a player whose decision has no live half is caught at exactly real time —
 * because on a plain live stream Media3 adjusts speed only when the item it plays says it may, and
 * the decision's half is what makes the item say so.
 *
 * Here rather than in core's tests because only this module has a live origin that advances with
 * the clock (`TestContent.liveHls`), the same reason `LivePlaylistRevalidationTest` lives here.
 *
 * What provokes a speed other than 1× is the harness's own clock split, which `TestContent.liveHls`
 * documents: on a dated window the engine measures "now" on the real wall clock, the media clock
 * runs on the harness's, and so the player sees itself drifting ahead of the live edge and slows to
 * the *minimum* of its range to let the edge catch up. The range chosen is deliberately one no
 * policy would ship, so that what is observed cannot be a default.
 */
@RunWith(AndroidJUnit4::class)
class LiveLatencyBindingTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun thePlayerHoldsTheWindowAtTheSlowestSpeedTheDecisionAllows() {
        val wide = LIVE.copy(liveLatency = LiveLatencyPolicy(minPlaybackSpeed = 0.5f, maxPlaybackSpeed = 2f))
        val advanced = mediaAdvancedOverTheWindow(policy = PlaybackPolicy { wide })

        // Slower than real time — which Media3 does on this stream only because the item says it
        // may — and never below the floor the decision named. Not pinned to the floor itself: the
        // control also walks its target toward the offset it finds itself at, so how far below
        // real time it settles is the engine's own control loop rather than the policy's number.
        assertThat(advanced).isAtMost(WINDOW_MS - VISIBLY_SLOWER_MS)
        assertThat(advanced).isAtLeast(WINDOW_MS / 2 - STEP_MS)
    }

    @Test
    fun withoutALiveHalfThePlayerIsNotSpeedAdjustedAtAll() {
        val advanced = mediaAdvancedOverTheWindow(policy = PlaybackPolicy { LIVE })

        assertThat(advanced).isAtLeast(WINDOW_MS - STEP_MS)
        assertThat(advanced).isAtMost(WINDOW_MS + STEP_MS)
    }

    /** Media time advanced over [WINDOW_MS] of the clock, once the control has had [SETTLE_MS] to act. */
    private fun mediaAdvancedOverTheWindow(policy: PlaybackPolicy): Long {
        val content = TestContent.liveHls(dated = true)
        val player: SuperPlayer = harness.buildPlayer(
            content = content,
            profile = PlaybackProfile.LIVE_LINEAR,
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = policy,
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        assertThat(player.isCurrentMediaItemLive).isTrue()
        // In steps, because the control revises the speed once a second and a single jump would
        // give it one pass; every step is a pass, as every render is on a device.
        harness.advanceTimeInStepsMs(player, SETTLE_MS)

        val before = periodPositionMs(player)
        harness.advanceTimeInStepsMs(player, WINDOW_MS)
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        return periodPositionMs(player) - before
    }

    /**
     * Where the player is in the stream. `currentPosition` is relative to a window that slides under
     * it on a live stream, and so stands still while the stream plays; the position in the period
     * does not.
     */
    private fun periodPositionMs(player: Player): Long {
        val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        return window.positionInFirstPeriodMs + player.currentPosition
    }

    private companion object {
        const val CONTENT = "channels/news"
        const val SETTLE_MS = 2_000L

        /** A second lost in ten is a slowdown no rounding of the clock can produce. */
        const val VISIBLY_SLOWER_MS = 1_000L
        const val WINDOW_MS = 10_000L
        const val STEP_MS = 250L

        val LIVE: PlaybackDecision = PlaybackPolicy.forProfile(PlaybackProfile.LIVE_LINEAR).decide(PlaybackConditions())
    }
}
