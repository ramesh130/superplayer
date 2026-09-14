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

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EnginePolicyExtension
import com.superplayer.core.MediaRequest
import com.superplayer.core.NetworkTransport
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SelectionPace
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The pace on core's own path: a policy that installs no selection factory and no decision target
 * is consulted once at construction, and `EngineBinding.kt` turns the decision's pace into Media3's
 * own adaptive factory. Here rather than in core's tests because a climb needs a ladder and a video
 * renderer, which the harness has and core's tests do not.
 *
 * The one engine slot the policy fills is the meter, with [BandwidthOracle]'s. Left empty, the
 * engine takes Media3's process-wide `DefaultBandwidthMeter`, whose estimate is carried from one
 * session into the next and moves only when its own clock and the loading threads happen to agree:
 * the second session then started on the top rung whatever its pace, and the first one failed to
 * climb on a slow CI runner. The oracle's meter is seeded per session, so both sessions start on
 * the bottom rung and differ only in their pace. Filling the meter slot alone neither installs a
 * selection factory nor registers a re-consultation, so the path under test is the plain one.
 */
@RunWith(AndroidJUnit4::class)
class SelectionPaceBindingPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
    private val oracles = mutableListOf<BandwidthOracle>()

    @After
    fun releaseOraclesAndForget() {
        oracles.forEach { it.release() }
        EstimateMemory.PROCESS.forget()
    }

    @Test
    fun aPaceDecidedByAPlainPolicyIsThePaceTheEngineClimbsAt() {
        // The control: the engine's own pace, installed through the same route, climbs.
        val atEnginePace = directionsAt(SelectionPace.ENGINE_DEFAULT)
        assertWithMessage("control").that(atEnginePace).contains(TrackSwitchDirection.UP)

        // A climb threshold no buffer of this profile reaches: the same session never climbs.
        val neverClimbs = directionsAt(SelectionPace.ENGINE_DEFAULT.copy(climbAfterBufferedMs = UNREACHABLE_CLIMB_MS))
        assertWithMessage("paced").that(neverClimbs).doesNotContain(TrackSwitchDirection.UP)
    }

    private fun directionsAt(pace: SelectionPace): List<TrackSwitchDirection> {
        events.clear()
        EstimateMemory.PROCESS.forget()
        EstimateMemory.PROCESS.record(NetworkTransport.Wifi, SLOW_WIFI_BPS, harness.elapsedRealtimeMs())
        val oracle = BandwidthOracle.Builder(ApplicationProvider.getApplicationContext()).build().also { oracles += it }
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            telemetry = QoeCollector(TelemetrySink { events += it }),
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = MeteredPolicy(oracle, pace),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)
        assertWithMessage("error").that(player.playerError).isNull()
        val switches = synchronized(events) { events.filterIsInstance<TelemetryEvent.TrackSwitched>() }
        player.release()
        // Without this, a session that starts at the top has nowhere to climb and proves nothing.
        assertWithMessage("start").that(switches.first().toBitrateBps).isEqualTo(BOTTOM_RUNG_BPS)
        return switches.map { it.direction }
    }

    /** The on-demand profile's decision with [pace] on it, over the oracle's meter and nothing else. */
    private class MeteredPolicy(
        private val oracle: BandwidthOracle,
        private val pace: SelectionPace,
    ) : PlaybackPolicy,
        EnginePolicyExtension {

        private val static = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND)

        override fun decide(conditions: PlaybackConditions): PlaybackDecision {
            val decision = static.decide(conditions)
            return decision.copy(trackSelection = decision.trackSelection.copy(pace = pace))
        }

        override fun configureEngine(configuration: EngineConfiguration) {
            oracle.configure(configuration)
        }
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e05"
        const val SOURCE = "fake://superplayer.test/never-fetched"
        const val LONG_CONTENT_MS = 240_000L
        const val PLAYED_MS = 60_000L

        /** Under the ladder's second rung at Media3's 0.7 fraction, so a session starts at the bottom. */
        const val SLOW_WIFI_BPS = 200_000L
        const val BOTTOM_RUNG_BPS = 300_000

        /** Ten minutes: past the on-demand profile's 60 s buffer ceiling many times over. */
        const val UNREACHABLE_CLIMB_MS = 600_000
    }
}
