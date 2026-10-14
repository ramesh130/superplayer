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

package com.superplayer.tv

import android.content.Context
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackOutput
import com.superplayer.core.SuperPlayer
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.DisplayMode
import com.superplayer.testkit.FrameRateRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame-rate matching on a player built with `TvOutput.standard` (#268, ADR-0014 rule 4).
 *
 * The content is the harness's described video, one rung declaring a frame rate, because the synthetic
 * HLS and DASH streams are audio-only and no protocol stream here reaches a video renderer. The display
 * is `DeviceStatement`'s, stated before the player is built. What is asserted is **what the player asked
 * the display for**, read at the surface the harness gave it (`PlaybackHarness.frameRateRequests`), which
 * is the call a compositor would receive on a device. That is where the observation stops: Robolectric
 * has no compositor, so the active mode never moves, and a panel refreshing at the requested rate — and
 * the blank a non-seamless switch costs — are the phase exit's to show on a device (#274).
 *
 * API 30's two-argument request is not driven here: the pinned runtime is 35 and no older runtime is
 * available offline, so that branch of `FrameRateMatching` is checked by lint's API gate alone.
 */
@RunWith(AndroidJUnit4::class)
class FrameRateMatchingTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun filmOnASixtyHertzPanelOfferingTwentyFourHertzAsksForTheFilmsRate() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_60)

        val player = play(film(24f), TvOutput.standard(context))

        assertThat(harness.frameRateRequests(player)).containsExactly(matching(24f))
    }

    @Test
    fun aFortyEightHertzModeIsAMatchForFilm() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_48), activeMode = FHD_60)

        val player = play(film(24f), TvOutput.standard(context))

        assertThat(harness.frameRateRequests(player)).containsExactly(matching(24f))
    }

    @Test
    fun aHundredAndTwentyHertzModeIsAMatchForFilm() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_120), activeMode = FHD_60)

        val player = play(film(24f), TvOutput.standard(context))

        assertThat(harness.frameRateRequests(player)).containsExactly(matching(24f))
    }

    @Test
    fun pulldownFilmMatchesATwentyFourHertzMode() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_60)

        val player = play(film(NTSC_FILM_FPS), TvOutput.standard(context))

        assertThat(harness.frameRateRequests(player)).containsExactly(matching(NTSC_FILM_FPS))
    }

    /** Control: a request no mode can honour is one the platform may still act on, badly. */
    @Test
    fun aPanelOfferingNoMatchingModeIsLeftAlone() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_50), activeMode = FHD_60)

        val player = play(film(24f), TvOutput.standard(context))

        assertThat(harness.frameRateRequests(player)).isEmpty()
    }

    /** Control: a frame-rate request does not change resolution, so a match at another size is none. */
    @Test
    fun aMatchingModeOnlyAtAnotherSizeIsNoMatch() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, UHD_24), activeMode = FHD_60)

        val player = play(film(24f), TvOutput.standard(context))

        assertThat(harness.frameRateRequests(player)).isEmpty()
    }

    /** Control: content the panel already shows without judder asks for nothing, a matching mode or not. */
    @Test
    fun contentAlreadyAtTheDisplaysRateAsksForNothing() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_60)

        val sixty = play(film(60f), TvOutput.standard(context))
        val thirty = play(film(30f), TvOutput.standard(context))

        assertThat(harness.frameRateRequests(sixty)).isEmpty()
        assertThat(harness.frameRateRequests(thirty)).isEmpty()
    }

    /** An undeclared rate is not matched: an estimate is known only once frames have been shown. */
    @Test
    fun contentDeclaringNoFrameRateAsksForNothing() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_60)

        val player = play(film(null), TvOutput.standard(context))

        assertThat(harness.frameRateRequests(player)).isEmpty()
    }

    /** Control: the same film on the same panel, on a player built without the module. */
    @Test
    fun aPlayerBuiltWithoutTheModuleAsksForNothing() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_60)

        val player = play(film(24f), output = null)

        assertThat(harness.frameRateRequests(player)).isEmpty()
    }

    @Test
    fun theRequestIsWithdrawnWhenTheSurfaceIsCleared() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_60)
        val player = play(film(24f), TvOutput.standard(context))

        player.clearVideoSurface()

        assertThat(harness.frameRateRequests(player)).containsExactly(matching(24f), matching(WITHDRAWN)).inOrder()
    }

    @Test
    fun theRequestIsWithdrawnWhenThePlayerIsReleased() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_60)
        val player = play(film(24f), TvOutput.standard(context))

        harness.release(player)

        assertThat(harness.frameRateRequests(player)).containsExactly(matching(24f), matching(WITHDRAWN)).inOrder()
    }

    /**
     * A surface recreated after the panel switched — a `SurfaceView` across a pause, say — finds the active
     * mode matching the request the player made, and asks again on the new surface rather than letting
     * the panel fall back to judder. The switch is stated, because Robolectric has no compositor to make it.
     */
    @Test
    fun aSurfaceRecreatedAfterTheSwitchKeepsTheRequestInForce() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_60)
        val player = play(film(24f), TvOutput.standard(context))
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_24), activeMode = FHD_24)

        harness.attachVideoOutput(player)

        assertThat(harness.frameRateRequests(player))
            .containsExactly(matching(24f), matching(WITHDRAWN), matching(24f))
            .inOrder()
    }

    /**
     * ADR-0014 rule 5, step 4: a new display is asked afresh. The first panel offers no mode matching
     * film, so nothing is asked; the one hotplugged in its place does, and the player asks it without
     * the item changing.
     */
    @Test
    fun aHotpluggedDisplayOfferingAMatchIsAskedForTheFilmsRate() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60, FHD_50), activeMode = FHD_60)
        val player = play(film(24f), TvOutput.standard(context))
        assertThat(harness.frameRateRequests(player)).isEmpty()

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareDisplayModes(listOf(UHD_60, UHD_24), activeMode = UHD_60) }
        harness.advanceTimeInStepsMs(player, HOTPLUG_SETTLE_MS)

        assertThat(harness.frameRateRequests(player)).containsExactly(matching(24f))
    }

    private fun play(content: TestContent, output: PlaybackOutput?): SuperPlayer {
        val player = harness.buildPlayer(content = content, output = output)
        player.setMediaRequest(request(content))
        harness.playToReady(player)
        return player
    }

    private fun request(content: TestContent): MediaRequest =
        MediaRequest.Builder("film:${content.hashCode()}").addSource(content.sourceUri).build()

    private fun film(framesPerSecond: Float?): TestContent =
        TestContent.ladder(listOf(TestContent.Rung(bitrateBps = 800_000, heightPx = 1080, frameRate = framesPerSecond)))

    private fun matching(framesPerSecond: Float) =
        FrameRateRequest(framesPerSecond, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)

    private companion object {
        val FHD_60 = DisplayMode(1920, 1080, 60f)
        val FHD_50 = DisplayMode(1920, 1080, 50f)
        val FHD_48 = DisplayMode(1920, 1080, 48f)
        val FHD_24 = DisplayMode(1920, 1080, 24f)
        val FHD_120 = DisplayMode(1920, 1080, 120f)
        val UHD_24 = DisplayMode(3840, 2160, 24f)
        val UHD_60 = DisplayMode(3840, 2160, 60f)

        /** Long enough for a scheduled change to be made and heard: one engine pass past it. */
        const val HOTPLUG_SETTLE_MS = 100L

        /** 24 × 1000/1001, film pulled down for a 59.94 Hz broadcast. */
        const val NTSC_FILM_FPS = 24_000f / 1_001f

        const val WITHDRAWN = 0f
    }
}
