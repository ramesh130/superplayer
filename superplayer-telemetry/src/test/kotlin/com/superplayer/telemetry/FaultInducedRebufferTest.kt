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

package com.superplayer.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * A rebuffer with a cause, rather than a rebuffer with a stub.
 *
 * Every other stall in this module's tests is provoked by making the video renderer stop being
 * ready: it reproduces the engine's buffering state machine exactly and the *cause* of a real
 * rebuffer not at all — no bytes are late and no decoder is behind. Here the bytes are late, which
 * is what a rebuffer is in production, and the collector is measured on the thing it will actually
 * see.
 *
 * That distinction is why the fault injector was built before the ladder it exists to test
 * (`PRD.md` Part 5): the collector cannot be shown to measure rebuffers correctly without a way to
 * cause one on purpose, at a known moment.
 */
@RunWith(AndroidJUnit4::class)
class FaultInducedRebufferTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /** Written from the delivery thread, read from the test's; see `TelemetryDelivery`. */
    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
    private val sink = TelemetrySink { events += it }

    @Test
    fun aSegmentThatArrivesLateIsMeasuredAsARebuffer() {
        val faults = FaultScript.Builder()
            .addLatencyMs(LATE_BY_MS, ResourceKind.MEDIA_SEGMENT, index = LATE_SEGMENT)
            .build()
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(),
            telemetry = QoeCollector(sink),
            faults = faults,
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        harness.playToReady(player)

        advanceInSteps(player, PLAYED_MS)

        val started = events.filterIsInstance<TelemetryEvent.RebufferStarted>()
        val ended = events.filterIsInstance<TelemetryEvent.RebufferEnded>()
        // The segment did not arrive, the buffer ran dry, and the session reported it as what it is.
        assertThat(started).isNotEmpty()
        // Nothing seeked, so nothing may be attributed to a seek: a rebuffer wrongly tagged
        // seek-induced is dropped from the rebuffer ratio, and the metric would then read best
        // exactly when the network went worst.
        assertThat(started.first().seekInduced).isFalse()
        // And it ended, once the bytes arrived — a stall the collector opened and never closed would
        // leave the session's rebuffer time unbounded.
        assertThat(ended).isNotEmpty()
        assertThat(ended.first().durationMs).isAtLeast(1)
    }

    /** Time moves in steps, because a load is asynchronous and one jump gives the engine one pass. */
    private fun advanceInSteps(player: SuperPlayer, totalMs: Long) {
        repeat((totalMs / STEP_MS).toInt()) { harness.advanceTimeMs(player, STEP_MS) }
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
        const val SOURCE = "fake://superplayer.test/never-fetched"

        /** Late enough that segments before it play, so the stall is mid-stream and not a startup one. */
        const val LATE_SEGMENT = 3

        /** Longer than the content already buffered, so the buffer actually runs dry. */
        const val LATE_BY_MS = 30_000L

        const val PLAYED_MS = 60_000L
        const val STEP_MS = 250L
    }
}
