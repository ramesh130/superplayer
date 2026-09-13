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

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.DecisionTrigger
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryCollector
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.NetworkTransport
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testkit.ThroughputTrace
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The adaptive policy under a real player, on the harness's clock, over a replayed network: the
 * Media3 half exercised through the facade, as `PRD.md` Part 5 and `docs/testing.md` require.
 * Every assertion is on `playbackDecision`, on the `Player` API, or on what the telemetry was
 * told; the pure branches are `AdaptiveBufferPolicyTest`'s and are not re-derived here.
 *
 * The heap is declared per test because Robolectric's default is 16 MB, which branch 5 would cap
 * every other branch under — exactly as it should on a device that small, and exactly not what a
 * test of the other branches is about.
 */
@RunWith(AndroidJUnit4::class)
class AdaptivePolicyPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun aCapableDeviceAndACleanMemory() {
        declareAppHeap(LARGE_HEAP_MB)
        EstimateMemory.PROCESS.forget()
    }

    @After
    fun forgetTheProcessMemory() {
        EstimateMemory.PROCESS.forget()
    }

    // Branch 1, and the cold start it departs from.
    @Test
    fun aStableWifiSessionStartsOnTheStaticNumbersAndDeepensItsCushionOnceMeasured() {
        val telemetry = RecordingCollector()
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND),
            telemetry = telemetry,
        )
        // Nothing measured yet: the profile's own numbers, so the cold start is no worse than before.
        assertThat(player.playbackDecision.buffer).isEqualTo(STATIC_VOD.buffer)

        player.setMediaRequest(request())
        harness.playToReady(player)
        harness.advanceUntil(player, "the cushion to deepen") {
            player.playbackDecision.buffer.maxBufferMs > STATIC_VOD.buffer.maxBufferMs
        }

        val deepened = player.playbackDecision.buffer
        assertThat(deepened.minBufferMs).isEqualTo(AdaptiveBufferPolicy.DEEP_CUSHION_MS.toInt())
        assertThat(deepened.bufferForPlaybackMs).isEqualTo(STATIC_VOD.buffer.bufferForPlaybackMs)
        assertThat(telemetry.changes.map { it.trigger }).contains(DecisionTrigger.THROUGHPUT_CHANGED)
        assertThat(telemetry.changes.last().decision).isEqualTo(player.playbackDecision)
        assertThat(player.playerError).isNull()
    }

    // Branch 3: keyed on the manifest, and the window held under a shaped link with dropouts.
    @Test
    fun aLiveStreamGetsTheLatencyPriorityDecisionAndHoldsItsWindow() {
        val telemetry = RecordingCollector()
        val player = harness.buildPlayer(
            content = TestContent.liveHls(),
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            network = NetworkProfile.LTE_WITH_DROPOUTS.trace,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND),
            telemetry = telemetry,
        )
        assertThat(player.playbackDecision.liveLatency).isNull()

        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(TestContent.liveHls().sourceUri).build())
        harness.playToReady(player)

        // The manifest said live, under a profile that said on demand: the decision is the live
        // one — the live profile's buffer and the speed range — and the trigger names why.
        assertThat(player.isCurrentMediaItemLive).isTrue()
        assertThat(player.playbackDecision.liveLatency).isEqualTo(AdaptiveBufferPolicy.LIVE_LATENCY)
        assertThat(player.playbackDecision.buffer).isEqualTo(STATIC_LIVE.buffer)
        assertThat(telemetry.changes.map { it.trigger }).contains(DecisionTrigger.STREAM_TYPE_CHANGED)
        // And the range reached the engine by the one route Media3 honours on a plain live stream:
        // the item that is playing, replaced in place, now declares it.
        harness.settle(player)
        val live = checkNotNull(player.currentMediaItem).liveConfiguration
        assertThat(live.minPlaybackSpeed).isEqualTo(AdaptiveBufferPolicy.LIVE_LATENCY.minPlaybackSpeed)
        assertThat(live.maxPlaybackSpeed).isEqualTo(AdaptiveBufferPolicy.LIVE_LATENCY.maxPlaybackSpeed)

        // Across two dropout periods: still playing, still inside the sliding window, and as far
        // along the stream as the time that passed — the window is being held, not fallen out of.
        // The position is window-relative on a live stream and the window slides under it, so
        // what says "still moving" is the position in the period, which does not.
        val before = periodPositionMs(player)
        harness.advanceTimeInStepsMs(player, HELD_WINDOW_MS)
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.currentPosition).isAtLeast(0)
        assertThat(player.currentPosition).isAtMost(player.duration)
        assertThat(periodPositionMs(player) - before).isAtLeast(HELD_WINDOW_MS - DROPOUT_ALLOWANCE_MS)
    }

    // Branch 4, through the facade: the rebuffer, the raised floor, the held ceiling, the release.
    @Test
    fun aRebufferRaisesTheFloorAndHoldsTheCeilingUntilATriggerAfterTheCooldown() {
        val telemetry = RecordingCollector()
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            profile = PlaybackProfile.SHORT_FORM,
            network = outageThenRecovery(),
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.SHORT_FORM),
            telemetry = telemetry,
        )
        val rebuffers = RebufferWatch().also(player::addListener)
        val builtAtMs = harness.elapsedRealtimeMs()
        player.setMediaRequest(request())
        harness.playToReady(player)

        // The rebuffer the outage causes, seen on the `Player` API rather than as a decision
        // change: a re-consultation whose answer is the decision already in force is not a change,
        // and a short-form start floor of one second can stall once more on its first chunks,
        // which is a rebuffer with its own raise and hold and is not this test's. Played through
        // the outage in the harness's load steps, which is the cadence the trace is sized against.
        val outageEndsAtMs = builtAtMs + BEFORE_OUTAGE_MS + OUTAGE_MS
        harness.advanceTimeInStepsMs(player, outageEndsAtMs - harness.elapsedRealtimeMs())
        harness.advanceUntil(player, "the outage's rebuffer to end", RECOVERY_MARGIN_MS) {
            rebuffers.endedAtMs.any { it >= outageEndsAtMs }
        }
        // The first rebuffer of the session, whichever it was, changed the decision on its own
        // trigger: the floor it raises is above the profile's, so the change cannot be silent.
        assertThat(telemetry.changes.map { it.trigger }).contains(DecisionTrigger.REBUFFER_ENDED)
        val held = player.playbackDecision
        assertThat(held.buffer.bufferForPlaybackAfterRebufferMs)
            .isGreaterThan(STATIC_SHORT_FORM.buffer.bufferForPlaybackAfterRebufferMs)
        assertThat(held.trackSelection.maxVideoBitrateBps).isLessThan(TrackSelectionPolicy.UNLIMITED)
        assertThat(held.buffer.maxBufferMs).isEqualTo(STATIC_SHORT_FORM.buffer.maxBufferMs)

        // Time alone releases nothing: through a whole cooldown every re-consultation keeps a
        // hold, whatever the link did meanwhile — a hold lapses on a trigger, never on time. The
        // cooldown counted is the one since the *last* rebuffer, should recovery stall once more.
        harness.advanceUntil(player, "a cooldown to pass with no further rebuffer", 4 * AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS) {
            harness.elapsedRealtimeMs() - rebuffers.endedAtMs.last() >= AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS - STEP_MS
        }
        val lastRebufferEndedAtMs = rebuffers.endedAtMs.last()
        val duringCooldown = telemetry.changes.filter { it.atMs > lastRebufferEndedAtMs }
        assertWithMessage("changes during the cooldown: ${duringCooldown.map { "${it.trigger}:${it.decision.trackSelection.maxVideoBitrateBps}" }}")
            .that(duringCooldown.map { it.decision.trackSelection.maxVideoBitrateBps })
            .doesNotContain(TrackSelectionPolicy.UNLIMITED)
        assertThat(player.playbackDecision.trackSelection.maxVideoBitrateBps).isLessThan(TrackSelectionPolicy.UNLIMITED)

        // Past the cooldown, the next trigger lifts both halves. A speed change is the one trigger
        // a test can raise on the `Player` API at a chosen moment; the link's own move would do
        // the same, on its own schedule.
        harness.advanceTimeInStepsMs(player, 2 * STEP_MS)
        player.setPlaybackSpeed(FASTER)
        harness.settle(player)
        val releasedAtMs = harness.elapsedRealtimeMs()
        assertThat(releasedAtMs - lastRebufferEndedAtMs).isAtLeast(AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS)
        assertThat(telemetry.changes.last().trigger).isEqualTo(DecisionTrigger.PLAYBACK_SPEED_CHANGED)
        // The ceiling is the profile's own again. The floor is not asserted lower here: the raise
        // is bounded by the range's minimum, which a short-form after-rebuffer floor reaches on the
        // first rebuffer, and the speed scales the floors it returns to — its lapse is the pure
        // policy's to show.
        val released = player.playbackDecision
        assertThat(released.trackSelection).isEqualTo(STATIC_SHORT_FORM.trackSelection)
        assertThat(player.playerError).isNull()
    }

    // Branch 4's other half: a trace that swings every second must not swing the decision.
    @Test
    fun aCongestedLinkThatSwingsEverySecondProducesABoundedNumberOfDecisionChanges() {
        val telemetry = RecordingCollector()
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            network = NetworkProfile.CONGESTED_WIFI.trace,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND),
            telemetry = telemetry,
        )
        player.setMediaRequest(request())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, OSCILLATION_WINDOW_MS)

        // The trace changes rate once a second; a policy keyed on the instantaneous rate would
        // answer differently on most of them. What bounds this one is the meter's own threshold —
        // a fifth of the mean, or the stable line crossed — and the rounding of every floor.
        val changes = telemetry.changes.filter { it.trigger == DecisionTrigger.THROUGHPUT_CHANGED }
        assertWithMessage("decision changes: ${changes.map { it.decision.buffer.bufferForPlaybackMs }}")
            .that(changes.size)
            .isAtMost(MAX_CHANGES_IN_WINDOW)
        assertThat(player.playbackDecision.buffer.maxBufferMs).isEqualTo(STATIC_VOD.buffer.maxBufferMs)
        assertThat(player.playerError).isNull()
    }

    // Branch 5, from construction: the heap core read is the heap the policy capped on.
    @Test
    fun aSmallHeapCapsTheCeilingFromTheFirstDecision() {
        declareAppHeap(SMALL_HEAP_MB)
        val player = harness.buildPlayer(
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND),
        )
        val capped = player.playbackDecision.buffer
        // 64 MB, a quarter of it, at the reference 8 Mbit/s, to the second: 16 s.
        val expectedMs = SMALL_HEAP_MB * 1_024L * 1_024L / AdaptiveBufferPolicy.HEAP_SHARE_DIVISOR * 8 * 1_000 /
            AdaptiveBufferPolicy.REFERENCE_BITRATE_BPS / AdaptiveBufferPolicy.CEILING_STEP_MS * AdaptiveBufferPolicy.CEILING_STEP_MS
        assertThat(capped.maxBufferMs.toLong()).isEqualTo(expectedMs)
        assertThat(capped.maxBufferMs).isLessThan(STATIC_VOD.buffer.maxBufferMs)
        assertThat(capped.minBufferMs).isAtMost(capped.maxBufferMs)
    }

    @Test
    fun onePolicyObjectServesOnePlayer() {
        val policy = AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND)
        harness.buildPlayer(policy = policy)
        val second = runCatching { harness.buildPlayer(policy = policy) }
        assertThat(second.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    private fun request(): MediaRequest = MediaRequest.Builder(CONTENT).addSource(SOURCE).build()

    /** Where the player is in the stream, on a live window that slides under `currentPosition`. */
    private fun periodPositionMs(player: Player): Long {
        val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        return window.positionInFirstPeriodMs + player.currentPosition
    }

    private fun declareAppHeap(megabytes: Int) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activityManager).setMemoryClass(megabytes)
    }

    /**
     * Enough link to fill a short-form buffer, an outage long enough to drain it, the same link
     * back for longer than the cooldown, and then a faster one. The step up is a material move
     * the policy is consulted on; whether it lands inside or after the cooldown depends on when
     * the rebuffer ended, which is why the test raises its own trigger past the cooldown rather
     * than waiting for this one.
     */
    private fun outageThenRecovery(): ThroughputTrace = ThroughputTrace.Builder()
        .add(BEFORE_OUTAGE_MS, LINK_BPS, NetworkTransport.WIFI)
        .add(OUTAGE_MS, 0, NetworkTransport.WIFI)
        .add(RECOVERED_MS, LINK_BPS, NetworkTransport.WIFI)
        .add(IMPROVED_MS, LINK_BPS * 4, NetworkTransport.WIFI)
        .holdAtEnd()
        .build()

    /** The rebuffers a consumer sees: a buffering state entered after the first ready, then left. */
    private class RebufferWatch : Player.Listener {
        val endedAtMs = mutableListOf<Long>()
        private var readyOnce = false
        private var stalled = false

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> if (readyOnce) stalled = true

                Player.STATE_READY -> {
                    readyOnce = true
                    if (stalled) {
                        stalled = false
                        endedAtMs += SystemClock.elapsedRealtime()
                    }
                }

                else -> stalled = false
            }
        }
    }

    /**
     * A collector that records what core tells it, synchronously, the way core's own test double
     * does — so a decision change is asserted the moment the facade signalled it, with no delivery
     * queue to wait on. Nothing here reads the engine.
     */
    private class RecordingCollector : TelemetryCollector {

        class Change(val decision: PlaybackDecision, val trigger: DecisionTrigger, val atMs: Long)

        val changes = mutableListOf<Change>()

        override fun attach(player: SuperPlayer) = Unit

        override fun startSession(contentId: String, sessionId: String) = Unit

        override fun endSession() = Unit

        override fun declareIntent(monotonicTimeMs: Long) = Unit

        override fun detach() = Unit

        override fun decisionChanged(decision: PlaybackDecision, trigger: DecisionTrigger) {
            changes += Change(decision, trigger, SystemClock.elapsedRealtime())
        }
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e03"
        const val SOURCE = "fake://superplayer.test/never-fetched"
        const val LONG_CONTENT_MS = 240_000L
        const val LARGE_HEAP_MB = 2_048
        const val SMALL_HEAP_MB = 64

        /** The harness's own load step, which is the granularity a change is observed at. */
        const val STEP_MS = 250L

        /** Two dropout periods of [NetworkProfile.LTE_WITH_DROPOUTS], so the window is held across at least one. */
        const val HELD_WINDOW_MS = 2 * NetworkProfile.LTE_DROPOUT_PERIOD_MS

        /**
         * A dropout the buffer does not quite cover costs playback time: the profile's outage is two
         * seconds (its own KDoc), and the window spans two of them.
         */
        const val DROPOUT_ALLOWANCE_MS = 2 * 2_000L

        const val LINK_BPS = 5_000_000L
        const val BEFORE_OUTAGE_MS = 6_000L
        const val OUTAGE_MS = 20_000L
        const val RECOVERED_MS = 40_000L
        const val IMPROVED_MS = 120_000L
        const val RECOVERY_MARGIN_MS = 15_000L

        /** A speed above real time: a trigger, and one whose scaled floors are still below the held one. */
        const val FASTER = 1.25f

        const val OSCILLATION_WINDOW_MS = 60_000L

        /**
         * One change per ten seconds, against a trace that changes rate sixty times in the window.
         * ref: derivation — the meter announces a move of a fifth of the mean, and an eight-sample
         * mean over a ±50% swing moves that far only as the window's composition shifts; the bound
         * is loose against that and tight against a naive policy, which is the comparison.
         */
        const val MAX_CHANGES_IN_WINDOW = 6

        val STATIC_VOD: PlaybackDecision = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND).decide(PlaybackConditions())
        val STATIC_LIVE: PlaybackDecision = PlaybackPolicy.forProfile(PlaybackProfile.LIVE_LINEAR).decide(PlaybackConditions())
        val STATIC_SHORT_FORM: PlaybackDecision = PlaybackPolicy.forProfile(PlaybackProfile.SHORT_FORM).decide(PlaybackConditions())
    }
}
