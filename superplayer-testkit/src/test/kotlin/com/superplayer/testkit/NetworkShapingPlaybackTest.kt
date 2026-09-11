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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shaper under a real load, driven by a real player through the public API.
 *
 * `NetworkShapingTest` pins which byte arrives when. What this pins is what only a player can show:
 * that a trace and a fault script are one harness for one session, that a slower profile is slower
 * where a player can feel it, and that a session plays across a handover.
 */
@RunWith(AndroidJUnit4::class)
class NetworkShapingPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aShapedNetworkWithAForbiddenSegmentFailsAtThatSegmentOnEveryRun() {
        val segmentsPerRun = (1..RUNS).map {
            val script = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT)
                .build()
            val player = harness.buildPlayer(network = NetworkProfile.LTE_WITH_DROPOUTS.trace, faults = script)
            player.setMediaRequest(request())

            harness.playToFailure(player)

            harness.requestedResources(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.index }
        }

        // One player, one trace, one script: the segments before the refused one arrived through the
        // shaped network, the refused one was reached, nothing past it was — the same every run.
        segmentsPerRun.forEach { assertThat(it).containsExactlyElementsIn(0..FAULTED_SEGMENT).inOrder() }
    }

    @Test
    fun aSlowerProfileIsSlowerToStart() {
        val wifi = timeToReadyMs(NetworkProfile.STABLE_WIFI)
        val threeG = timeToReadyMs(NetworkProfile.THREE_G)

        // The first 2-second segment of an 800 kbit/s rendition is 200 000 bytes, and at 1 Mbit/s
        // that is 1 600 ms before the player can have a frame to show. That is a floor derived from
        // the trace, not a tuned threshold; how far above it the player lands is the engine's.
        assertThat(threeG).isAtLeast(FIRST_SEGMENT_AT_THREE_G_MS)
        assertThat(wifi).isLessThan(threeG)
    }

    @Test
    fun aSessionPlaysOnAcrossTheHandoverAndKeepsLoadingOnTheNewTransport() {
        // Long enough that the buffer has not reached the end of the content by the handover, so
        // the loads after it are real loads over cellular rather than a buffer running down.
        val player = harness.buildPlayer(
            content = TestContent.video(durationMs = LONG_CONTENT_MS),
            network = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace,
        )
        val builtAtMs = harness.elapsedRealtimeMs()
        player.setMediaRequest(request())
        harness.playToReady(player)

        harness.advanceTimeInStepsMs(player, NetworkProfile.HANDOVER_AT_MS - (harness.elapsedRealtimeMs() - builtAtMs))
        val beforeHandover = segmentsRequested(player)
        val positionAtHandoverMs = player.currentPosition
        harness.advanceTimeInStepsMs(player, AFTER_HANDOVER_MS)
        val traceNowMs = harness.elapsedRealtimeMs() - builtAtMs

        // What "plays on" means, stated as what the session did rather than as the state it happens to
        // be in at one instant: no error, playback moved on past the handover, and new segments were
        // fetched while the trace said cellular. The last is the transfers crossing the transport
        // change; nothing reads the transport yet, so crossing it is all a player can show.
        assertThat(player.playerError).isNull()
        assertThat(player.currentPosition).isGreaterThan(positionAtHandoverMs)
        assertThat(NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace.transportAt(traceNowMs))
            .isEqualTo(NetworkTransport.CELLULAR)
        assertThat(segmentsRequested(player)).isGreaterThan(beforeHandover)
    }

    private fun timeToReadyMs(profile: NetworkProfile): Long {
        val player = harness.buildPlayer(network = profile.trace)
        player.setMediaRequest(request())
        val startedAtMs = harness.elapsedRealtimeMs()
        harness.playToReady(player)
        return harness.elapsedRealtimeMs() - startedAtMs
    }

    private fun segmentsRequested(player: SuperPlayer): Int =
        harness.requestedResources(player).count { it.kind == ResourceKind.MEDIA_SEGMENT }

    private fun request(): MediaRequest = MediaRequest.Builder(CONTENT).addSource(SOURCE).build()

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
        const val SOURCE = "fake://superplayer.test/never-fetched"
        const val FAULTED_SEGMENT = 2
        const val RUNS = 3

        /** 200 000 bytes at 1 000 000 bit/s. See [aSlowerProfileIsSlowerToStart]. */
        const val FIRST_SEGMENT_AT_THREE_G_MS = 1_600L

        const val LONG_CONTENT_MS = 180_000L
        const val AFTER_HANDOVER_MS = 10_000L
    }
}
