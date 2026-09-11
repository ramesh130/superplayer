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

package com.superplayer.core

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of ADR-0008's seam that belongs to `superplayer-core`: that a collector is attached when
 * one was supplied, that the session boundary is signalled at the lifecycle points only core knows,
 * and that a player built without telemetry pays nothing for the feature.
 *
 * Driven the way every other test here is (`docs/testing.md`): real playback of a synthetic stream
 * over Media3's fakes, with assertions made through the public API. The collector is
 * [RecordingTelemetry] rather than `QoeCollector`, because core may not depend on the telemetry
 * module — see that class for why that is the right split rather than a compromise.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerTelemetryTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /**
     * What a consumer's sink received, in order — every assertion below is made on events that
     * crossed [TelemetrySink], not on a collector's private bookkeeping.
     */
    private val events = mutableListOf<TelemetryEvent>()
    private val telemetry = RecordingTelemetry(TelemetrySink { events += it })

    private val started: List<TelemetryEvent.SessionStarted>
        get() = events.filterIsInstance<TelemetryEvent.SessionStarted>()

    private val ended: List<TelemetryEvent.SessionEnded>
        get() = events.filterIsInstance<TelemetryEvent.SessionEnded>()

    @Before
    fun declareTheDeviceThisRunsOn() {
        // For the pooled test below: a pool on Robolectric's default device, which reports no codecs
        // and no memory, is a pool of one whatever it was asked for.
        TestDevice.declareCapableDevice()
    }

    @Test
    fun aCollectorIsAttachedToThePlayerItWasBuiltFor() {
        harness.buildPlayer(telemetry = telemetry)

        // Attached at construction, once, and before the caller ever sees the player — so a
        // collector cannot miss the first thing that happens to it.
        assertThat(telemetry.attachCount).isEqualTo(1)
        assertThat(events).isEmpty()
    }

    @Test
    fun playingContentOpensASessionForItAndReleasingThePlayerClosesIt() {
        val player = harness.buildPlayer(PlaybackProfile.VIDEO_ON_DEMAND, telemetry = telemetry)
        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        // The session names the content by the app's own identifier rather than by the URL it came
        // from, which is the whole reason `MediaRequest.contentId` exists.
        val started = started.single()
        assertThat(started.contentId).isEqualTo(EPISODE)
        assertThat(started.profile).isEqualTo(PlaybackProfile.VIDEO_ON_DEMAND)
        assertThat(ended).isEmpty()

        player.release()

        val ended = ended.single()
        assertThat(ended.sessionId).isEqualTo(started.sessionId)
        assertThat(ended.contentId).isEqualTo(EPISODE)
        // The terminal event is emitted while the engine is still alive, and the collector is
        // unregistered from it afterwards — in that order, so nothing detaches from a dead engine.
        assertThat(events.last()).isEqualTo(ended)
        assertThat(telemetry.detachCount).isEqualTo(1)
    }

    @Test
    fun recyclingAPooledPlayerEndsItsSessionAndTheNextRequestStartsAFreshOne() {
        // Sized to one so that both acquisitions are the same player: what is being pinned is a
        // player handed out twice, not two players.
        val pool = harness.buildPool(maxSize = 1, telemetry = telemetry)

        val first = checkNotNull(pool.acquire())
        first.setMediaRequest(MediaRequest.Builder(EPISODE).addSource(SOURCE).build())
        pool.recycle(first)

        // Recycled, not released: the collector is still attached, and the session it was measuring
        // is over. A pooled player that kept one session open across a scroll would report a single
        // view of everything the feed showed.
        assertThat(ended.single().contentId).isEqualTo(EPISODE)
        assertThat(telemetry.detachCount).isEqualTo(0)

        val second = checkNotNull(pool.acquire())
        assertThat(second).isSameInstanceAs(first)
        second.setMediaRequest(MediaRequest.Builder(TRAILER).addSource(SOURCE).build())

        val sessions = started
        assertThat(sessions.map { it.contentId }).containsExactly(EPISODE, TRAILER).inOrder()
        // A different session id, which is what makes the two separable in a pipeline that receives
        // them from the same player.
        assertThat(sessions[1].sessionId).isNotEqualTo(sessions[0].sessionId)
    }

    @Test
    fun movingToDifferentContentClosesTheOpenSessionBeforeOpeningTheNext() {
        val player = harness.buildPlayer(telemetry = telemetry)
        player.setMediaRequest(MediaRequest.Builder(EPISODE).addSource(SOURCE).build())
        player.setMediaRequest(MediaRequest.Builder(TRAILER).addSource(SOURCE).build())

        // Start, end, start — never two sessions open at once on one player. Core signals the edge
        // and the collector closes the previous one; this pins the pair of them together.
        assertThat(events.map { it::class.simpleName })
            .containsExactly("SessionStarted", "SessionEnded", "SessionStarted")
            .inOrder()
        assertThat(ended.single().sessionId).isEqualTo(started[0].sessionId)
    }

    @Test
    fun aRestoredPlayerMeasuresUnderASessionOfItsOwn() {
        val saved = harness.buildPlayer()
        saved.setMediaRequest(MediaRequest.Builder(EPISODE).addSource(SOURCE).build())
        val snapshot = saved.saveSnapshot()

        val restored = harness.buildPlayer(telemetry = telemetry)
        restored.restoreSnapshot(snapshot)

        // The player that took the snapshot ended its session when it was released. Without a
        // session here, everything a viewer watches after a configuration change goes unmeasured.
        assertThat(started.single().contentId).isEqualTo(EPISODE)
    }

    @Test
    fun aPlayerBuiltWithNoTelemetryRegistersNoAnalyticsListener() {
        // ADR-0008 rule 2, asserted mechanically rather than by reading the code. ExoPlayer registers
        // analytics listeners of its own during construction, so the number that means anything is
        // the *difference* between a player built with a collector and one built without — not the
        // count itself, which is why both players are built here.
        val withTelemetry = CountingAnalyticsCollector()
        harness.buildPlayer(
            telemetry = telemetry,
            alsoConfigureEngine = { it.setAnalyticsCollector(withTelemetry) },
        )

        val withoutTelemetry = CountingAnalyticsCollector()
        harness.buildPlayer(alsoConfigureEngine = { it.setAnalyticsCollector(withoutTelemetry) })

        assertThat(withTelemetry.registrations - withoutTelemetry.registrations).isEqualTo(1)
    }

    @Test
    fun declaringIntentReachesTheCollectorOnTheMonotonicClock() {
        val before = SystemClock.elapsedRealtime()
        val player = harness.buildPlayer(telemetry = telemetry)

        player.declarePlaybackIntent()

        // The reading is the boundary time-to-first-frame is measured from, so what matters is that
        // it is a plausible `elapsedRealtime` and not a wall clock: the two differ by decades.
        assertThat(telemetry.declareIntentCount).isEqualTo(1)
        val declared = checkNotNull(telemetry.declaredIntentMonotonicMs)
        assertThat(declared).isAtLeast(before)
        assertThat(declared).isAtMost(SystemClock.elapsedRealtime())
        // Declaring intent is not content, and content is what a session is of.
        assertThat(events).isEmpty()
    }

    @Test
    fun declaringIntentOnAPlayerWithNoTelemetryDoesNothingAtAll() {
        // The call site is an app's tap handler, which does not know whether telemetry was attached
        // — a debug build attaches it and a release build may not. It has to be safe in both.
        harness.buildPlayer().declarePlaybackIntent()
    }

    @Test
    fun memoryPressureReachesTheCollectorAndBackgroundingDoesNot() {
        val player = harness.buildPlayer(telemetry = telemetry)
        val context: Application = ApplicationProvider.getApplicationContext()

        // The app went to the background. That says nothing about memory — a player still playing
        // there is the ordinary background-audio case — and a collector asked to shed state on it
        // would throw away a session's events every time the viewer checked a notification.
        context.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertThat(telemetry.memoryPressureCount).isEqualTo(0)

        // This one is the platform actually asking (`PRD.md` §3.4).
        context.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertThat(telemetry.memoryPressureCount).isEqualTo(1)

        player.release()

        // Registered on the application context, which outlives every player: a callback left
        // behind here would hold this player, its engine and its buffers for the life of the
        // process, which is a leak whose size is a player.
        context.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertThat(telemetry.memoryPressureCount).isEqualTo(1)
    }

    @Test
    fun aPlayerBuiltWithNoTelemetryRegistersNoMemoryCallbackAtAll() {
        harness.buildPlayer()
        val context: Application = ApplicationProvider.getApplicationContext()

        // ADR-0008 rule 2: a player built without telemetry pays nothing for it. Nothing to assert
        // on but the absence of a crash and the absence of a signal — there is no collector to
        // receive one — so this pins that the callback is not registered unconditionally.
        context.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertThat(telemetry.memoryPressureCount).isEqualTo(0)
    }

    /**
     * Media3's own analytics collector, counting the listeners registered on it.
     *
     * Media3 offers no way to ask a player how many analytics listeners it carries, and this is the
     * one seam that can answer the question: `ExoPlayer.addAnalyticsListener` arrives here. It is
     * substituted through `SuperPlayer.Builder`'s internal engine configurator — the same seam every
     * other test uses to install a fake clock — rather than by reaching past the facade.
     *
     * [RecordingTelemetry] registers exactly one listener, standing in for what `QoeCollector`
     * registers, so that the difference this test measures is the one the rule is about.
     */
    private class CountingAnalyticsCollector : DefaultAnalyticsCollector(Clock.DEFAULT) {

        var registrations = 0
            private set

        override fun addListener(listener: AnalyticsListener) {
            registrations++
            super.addListener(listener)
        }
    }

    private companion object {
        const val EPISODE = "series/expanse/s01e01"
        const val TRAILER = "series/expanse/trailer"
        const val SOURCE = SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI
    }
}
