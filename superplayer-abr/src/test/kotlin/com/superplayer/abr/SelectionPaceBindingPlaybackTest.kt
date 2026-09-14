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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The pace on core's own path: a consumer's policy that is not an extension installs no selection
 * factory, so `EngineBinding.kt` turns the decision's pace into Media3's own adaptive factory at
 * construction. Here rather than in core's tests because a climb needs a ladder and a video
 * renderer, which the harness has and core's tests do not; nothing of this module is built.
 */
@RunWith(AndroidJUnit4::class)
class SelectionPaceBindingPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

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
        val static = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND)
        val paced = PlaybackPolicy { conditions ->
            val decision = static.decide(conditions)
            decision.copy(trackSelection = decision.trackSelection.copy(pace = pace))
        }
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            telemetry = QoeCollector(TelemetrySink { events += it }),
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = paced,
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)
        assertWithMessage("error").that(player.playerError).isNull()
        val directions = synchronized(events) { events.filterIsInstance<TelemetryEvent.TrackSwitched>().map { it.direction } }
        player.release()
        return directions
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e05"
        const val SOURCE = "fake://superplayer.test/never-fetched"
        const val LONG_CONTENT_MS = 240_000L
        const val PLAYED_MS = 60_000L

        /** Ten minutes: past the on-demand profile's 60 s buffer ceiling many times over. */
        const val UNREACHABLE_CLIMB_MS = 600_000
    }
}
