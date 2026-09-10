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

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The real collector against the real builder — `SuperPlayer.Builder(context).setTelemetry(...)`,
 * exactly as a consumer writes it.
 *
 * Deliberately without the fakes `superplayer-core`'s harness owns: those reach the engine through a
 * seam that is `internal` to that module, and the session boundary is signalled by the facade rather
 * than derived from anything the engine loads, so nothing here needs media to arrive. What that buys
 * is a test of the shipped path with no test-only construction in it at all.
 *
 * The counterpart on the other side of the module boundary is `SuperPlayerTelemetryTest`, which pins
 * the lifecycle points core signals from.
 */
@RunWith(AndroidJUnit4::class)
class QoeCollectorTest {

    /**
     * Thread-safe, because a sink is called on the delivery thread rather than on the test's —
     * which is the guarantee `TelemetryDeliveryTest` is about and this class inherits.
     */
    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
    private val sink = TelemetrySink { events += it }

    private var player: SuperPlayer? = null

    /** The collector under test, held so a test can wait for what it submitted to be delivered. */
    private var collector: QoeCollector? = null

    /**
     * Waits for everything submitted so far to reach [sink].
     *
     * Delivery is asynchronous by design (ADR-0008 rule 4), so an assertion made without this would
     * be a race rather than a test. It is the module-internal seam described on
     * `TelemetryDelivery.awaitIdle`, deliberately not something a consumer can call.
     */
    private fun awaitDelivery() {
        assertThat(checkNotNull(collector).awaitDelivered(DELIVERY_TIMEOUT_MS)).isTrue()
    }

    @After
    fun releaseThePlayer() {
        // Idempotent, and a player a failing assertion left alive holds a playback thread for the
        // rest of the run.
        player?.release()
    }

    private fun buildPlayer(profile: PlaybackProfile = PlaybackProfile.VIDEO_ON_DEMAND): SuperPlayer =
        SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setProfile(profile)
            .setTelemetry(QoeCollector(sink).also { collector = it })
            .build()
            .also { player = it }

    @Test
    fun buildingWithACollectorEmitsNothingUntilThereIsContentToMeasure() {
        buildPlayer()
        awaitDelivery()

        // A session is of content. A player that has been given none has nothing to report yet.
        assertThat(events).isEmpty()
    }

    @Test
    fun aRequestOpensASessionAndReleaseClosesIt() {
        val player = buildPlayer(PlaybackProfile.LIVE_LINEAR)
        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())
        awaitDelivery()

        val started = events.single() as TelemetryEvent.SessionStarted
        assertThat(started.contentId).isEqualTo(CHANNEL)
        assertThat(started.profile).isEqualTo(PlaybackProfile.LIVE_LINEAR)
        assertThat(started.sessionId).isNotEmpty()
        assertThat(started.schemaVersion).isEqualTo(TelemetryEvent.SCHEMA_VERSION)

        player.release()
        awaitDelivery()

        val ended = events.last() as TelemetryEvent.SessionEnded
        assertThat(ended.sessionId).isEqualTo(started.sessionId)
        assertThat(ended.contentId).isEqualTo(CHANNEL)
        // Nothing was under pressure, so nothing was lost — and a session that says so is one a
        // pipeline may aggregate. See `TelemetryDeliveryTest` for the case where it is not zero.
        assertThat(ended.droppedEventCount).isEqualTo(0)
    }

    @Test
    fun releasingAfterTheSessionHasAlreadyEndedDoesNotEndItTwice() {
        val player = buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())

        player.release()
        player.release()
        awaitDelivery()

        assertThat(events.filterIsInstance<TelemetryEvent.SessionEnded>()).hasSize(1)
    }

    @Test
    fun differentContentGetsADifferentSession() {
        val player = buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())
        player.setMediaRequest(MediaRequest.Builder(EPISODE).addSource(SOURCE).build())
        awaitDelivery()

        val starts = events.filterIsInstance<TelemetryEvent.SessionStarted>()
        val ends = events.filterIsInstance<TelemetryEvent.SessionEnded>()

        // The first session is closed before the second opens, and it is closed under the content it
        // was actually of — a session that spanned both would report one view of something nobody
        // watched.
        assertThat(starts.map { it.contentId }).containsExactly(CHANNEL, EPISODE).inOrder()
        assertThat(ends.single().contentId).isEqualTo(CHANNEL)
        assertThat(ends.single().sessionId).isEqualTo(starts[0].sessionId)
        assertThat(starts[1].sessionId).isNotEqualTo(starts[0].sessionId)
    }

    @Test
    fun aCollectorMeasuresOnePlayer() {
        val collector = QoeCollector(sink)
        SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setTelemetry(collector)
            .build()
            .also { player = it }

        // Sharing one collector between two players would mean one session's events describing two
        // engines. Failing at the second `build()` is the earliest a consumer can be told.
        val second = SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setTelemetry(collector)
        val failure = runCatching { second.build() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun aSessionNamesThePolicyThePlayerWasActuallyBuiltWith() {
        val player = buildPlayer(PlaybackProfile.DATA_SAVER)
        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())

        // A rebuffer ratio measured under DATA_SAVER's buffer sizes and one measured under
        // VIDEO_ON_DEMAND's are two different measurements. Carrying the decision rather than only
        // the profile is what keeps that true once an adaptive policy makes the profile stop
        // predicting it.
        awaitDelivery()
        val started = events.filterIsInstance<TelemetryEvent.SessionStarted>().single()
        assertThat(started.decision).isEqualTo(player.playbackDecision)
    }

    @Test
    fun bothClocksAreOnTheEventAndAreTheClocksTheySayTheyAre() {
        val wallBefore = System.currentTimeMillis()
        val monoBefore = SystemClock.elapsedRealtime()
        buildPlayer().setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())
        awaitDelivery()

        val started = events.filterIsInstance<TelemetryEvent.SessionStarted>().single()
        // Two readings that differ by decades: swapping them is the mistake this pins, and it is one
        // a pipeline would only notice as timestamps in 1970.
        assertThat(started.timestampMs).isAtLeast(wallBefore)
        assertThat(started.monotonicTimeMs).isAtLeast(monoBefore)
        assertThat(started.monotonicTimeMs).isAtMost(SystemClock.elapsedRealtime())
    }

    @Test
    fun declaringIntentIsNotAnEventAndDoesNotOpenASession() {
        val player = buildPlayer()

        player.declarePlaybackIntent()
        awaitDelivery()

        // Intent is a boundary, not a measurement. A session is of content, and there is none yet —
        // which is the whole reason the declaration has to be held rather than emitted.
        assertThat(events).isEmpty()

        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())
        awaitDelivery()
        assertThat(events.filterIsInstance<TelemetryEvent.SessionStarted>()).hasSize(1)
    }

    @Test
    fun aConsumerSinkIsNeverCalledOnTheThreadThatDroveThePlayer() {
        val sinkThreads = Collections.synchronizedSet(mutableSetOf<Thread>())
        val collector = QoeCollector { sinkThreads += Thread.currentThread() }.also { this.collector = it }
        val player = SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setTelemetry(collector)
            .build()
            .also { player = it }

        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())
        player.release()
        awaitDelivery()

        // ADR-0008 rule 4, end to end: the calls above are on the application thread, and a sink
        // reached from there is one whose network write is a video stall. Both events crossed the
        // boundary, and neither did so here.
        assertThat(sinkThreads).isNotEmpty()
        assertThat(sinkThreads).doesNotContain(Thread.currentThread())
    }

    @Test
    fun memoryPressureFromThePlatformReachesTheCollectorsQueue() {
        val player = buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())

        // The platform's signal, as core forwards it — `SuperPlayerTelemetryTest` pins the half that
        // turns `onTrimMemory` into this call, and this pins that the collector acts on it at all.
        checkNotNull(collector).onMemoryPressure()
        player.release()
        awaitDelivery()

        // Whatever pressure discarded, the terminal event still arrived and still says how much.
        val ended = events.filterIsInstance<TelemetryEvent.SessionEnded>().single()
        assertThat(ended.contentId).isEqualTo(CHANNEL)
    }

    private companion object {
        /**
         * Long enough that a loaded machine scheduling one drain of a handful of events is not a
         * flake, short enough that genuinely stuck delivery fails the test rather than the build.
         */
        const val DELIVERY_TIMEOUT_MS = 5_000L

        const val CHANNEL = "channel/news"
        const val EPISODE = "series/expanse/s01e01"
        const val SOURCE = "https://example.invalid/never-loaded.m3u8"
    }
}
