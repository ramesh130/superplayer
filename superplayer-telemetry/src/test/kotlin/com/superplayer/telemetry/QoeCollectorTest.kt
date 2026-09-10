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

    private val events = mutableListOf<TelemetryEvent>()
    private val sink = TelemetrySink { events += it }

    private var player: SuperPlayer? = null

    @After
    fun releaseThePlayer() {
        // Idempotent, and a player a failing assertion left alive holds a playback thread for the
        // rest of the run.
        player?.release()
    }

    private fun buildPlayer(profile: PlaybackProfile = PlaybackProfile.VIDEO_ON_DEMAND): SuperPlayer =
        SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setProfile(profile)
            .setTelemetry(QoeCollector(sink))
            .build()
            .also { player = it }

    @Test
    fun buildingWithACollectorEmitsNothingUntilThereIsContentToMeasure() {
        buildPlayer()

        // A session is of content. A player that has been given none has nothing to report yet.
        assertThat(events).isEmpty()
    }

    @Test
    fun aRequestOpensASessionAndReleaseClosesIt() {
        val player = buildPlayer(PlaybackProfile.LIVE_LINEAR)
        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())

        val started = events.single() as TelemetryEvent.SessionStarted
        assertThat(started.contentId).isEqualTo(CHANNEL)
        assertThat(started.profile).isEqualTo(PlaybackProfile.LIVE_LINEAR)
        assertThat(started.sessionId).isNotEmpty()
        assertThat(started.schemaVersion).isEqualTo(TelemetryEvent.SCHEMA_VERSION)

        player.release()

        val ended = events.last() as TelemetryEvent.SessionEnded
        assertThat(ended.sessionId).isEqualTo(started.sessionId)
        assertThat(ended.contentId).isEqualTo(CHANNEL)
        // Zero until issue #37 makes delivery lossy; the field is part of the contract now so that a
        // consumer's pipeline has somewhere to put it when it stops being zero.
        assertThat(ended.droppedEventCount).isEqualTo(0)
    }

    @Test
    fun releasingAfterTheSessionHasAlreadyEndedDoesNotEndItTwice() {
        val player = buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())

        player.release()
        player.release()

        assertThat(events.filterIsInstance<TelemetryEvent.SessionEnded>()).hasSize(1)
    }

    @Test
    fun differentContentGetsADifferentSession() {
        val player = buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CHANNEL).addSource(SOURCE).build())
        player.setMediaRequest(MediaRequest.Builder(EPISODE).addSource(SOURCE).build())

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

    private companion object {
        const val CHANNEL = "channel/news"
        const val EPISODE = "series/expanse/s01e01"
        const val SOURCE = "https://example.invalid/never-loaded.m3u8"
    }
}
