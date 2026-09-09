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

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * The Android lifecycle rules, driven through the facade: audio focus, the becoming-noisy
 * broadcast, and the wake locks.
 *
 * These assert on *platform* state — what the `AudioManager` was asked for, whether a `PowerManager`
 * lock is held — rather than on anything inside SuperPlayer or Media3. That is what makes them worth
 * having: the behaviour they pin is a consumer's app being a well-behaved Android media app, and the
 * only place that is observable is the framework. See `LifecycleBinding.kt` for what is switched on
 * and why.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerLifecycleTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    private lateinit var player: SuperPlayer

    @Before
    fun setUp() {
        // Media3 checks for `WAKE_LOCK` before taking a lock, and Robolectric grants an application
        // no permission unless a test says so — not even one its merged manifest declares. A
        // consumer's app gets this permission from `media3-exoplayer`'s own manifest, so granting it
        // here is what makes this runtime resemble the one the code actually runs in.
        shadowOf(context as Application).grantPermissions(Manifest.permission.WAKE_LOCK)

        player = harness.buildPlayer()
    }

    private fun playUntilReady() {
        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
    }

    /** Lets the playback thread act on whatever was just delivered to the player. */
    private fun settle() = harness.settle(player)

    /**
     * Waits for the engine's wake lock to reach [held], failing with a timeout if it never does.
     *
     * `isHeld` is not a value a test may sample. Media3 takes and releases the lock on the playback
     * thread, one post behind the state change that caused it, so reading it straight after the
     * command that should have changed it is answering the previous question — which passes on an
     * idle laptop and fails on a loaded CI runner. It did exactly that.
     *
     * The wait covers the lock's existence too: it is created lazily, on the same thread, so a null
     * here means "not yet" rather than "never". [RobolectricUtil.runMainLooperUntil] is Media3's own
     * helper and the same mechanism [TestPlayerRunHelper] waits with — a run either reaches the
     * awaited state or fails naming what it was waiting for, which is what `docs/testing.md` asks
     * of anything asynchronous.
     */
    private fun awaitWakeLock(held: Boolean) {
        RobolectricUtil.runMainLooperUntil { ShadowPowerManager.getLatestWakeLock()?.isHeld == held }
    }

    /**
     * The focus listener the engine registered, invoked as the platform would invoke it.
     *
     * Robolectric records focus requests but does not act on them, so a test that wants to know what
     * losing focus does has to deliver the callback itself.
     */
    private fun deliverFocusChange(focusChange: Int) {
        val request = checkNotNull(shadowOf(audioManager).lastAudioFocusRequest) {
            "The engine never requested audio focus"
        }
        request.listener.onAudioFocusChange(focusChange)
        settle()
    }

    @Test
    fun playbackRequestsAudioFocusAndAbandonsItWhenPlaybackStops() {
        // Before, so that "requested before playback" is pinned as an ordering rather than inferred
        // from a request that could have been made at construction.
        assertThat(shadowOf(audioManager).lastAudioFocusRequest).isNull()

        playUntilReady()

        val request = checkNotNull(shadowOf(audioManager).lastAudioFocusRequest)
        assertThat(request.durationHint).isEqualTo(AudioManager.AUDIOFOCUS_GAIN)
        assertThat(shadowOf(audioManager).lastAbandonedAudioFocusRequest).isNull()

        player.stop()
        settle()

        assertThat(shadowOf(audioManager).lastAbandonedAudioFocusRequest).isNotNull()
        // Stopping is not pausing: the engine goes idle, and everything it was holding on the
        // platform's behalf goes with it.
        awaitWakeLock(held = false)
    }

    @Test
    fun transientFocusLossPausesAndFocusRegainResumes() {
        playUntilReady()

        deliverFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        // The intent to play is untouched — this is an interruption, not the user pausing — so the
        // pause shows up as a suppression rather than as a cleared `playWhenReady`. That difference
        // is the whole of why a transient loss can be resumed from and a permanent one cannot.
        assertThat(player.playWhenReady).isTrue()
        assertThat(player.isPlaying).isFalse()
        assertThat(player.playbackSuppressionReason)
            .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)

        deliverFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(player.playbackSuppressionReason)
            .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun transientFocusLossThatMayDuckKeepsPlaying() {
        playUntilReady()

        deliverFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        // A navigation prompt lowers the volume rather than stopping the film. The attenuation is
        // applied to the audio sink and is deliberately not reflected in `Player.getVolume`, which
        // reports what the *consumer* asked for — so what is observable here, and what matters, is
        // that playback did not stop.
        assertThat(player.isPlaying).isTrue()
        assertThat(player.playbackSuppressionReason)
            .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
    }

    @Test
    fun permanentFocusLossStopsPlaybackAndDoesNotResume() {
        playUntilReady()

        deliverFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        // Cleared rather than suppressed: another app owns the audio now, and the user asked for
        // that by starting it.
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.isPlaying).isFalse()

        deliverFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        // Regaining focus after a permanent loss must not put the sound back on: whatever the user
        // was doing instead has just finished, and an app that resumes here talks over what follows.
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.isPlaying).isFalse()
    }

    @Test
    fun unpluggingHeadphonesPausesPlayback() {
        playUntilReady()

        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        settle()

        assertThat(player.playWhenReady).isFalse()
        assertThat(player.isPlaying).isFalse()
    }

    @Test
    fun aWakeLockIsHeldWhilePlayingAndReleasedWhenPaused() {
        playUntilReady()

        awaitWakeLock(held = true)

        player.pause()
        settle()

        // A paused player needs neither the CPU nor the radio, and one that keeps the device awake
        // anyway is a battery complaint that never names the app causing it.
        //
        // The other half of Media3's rule — that buffering *with* the intent to play holds the lock,
        // which is where SuperPlayer departs from a literal "not while buffering" — is agreed and
        // documented in ADR-0006, but deliberately not pinned here. Asserting it needs the player
        // held in BUFFERING, and a stall long enough to assert in is a stall long enough for an
        // auto-advancing FakeClock to run Media3's own 1s unreactive-handler safety net, which
        // force-releases the very lock the test is waiting for. ADR-0006 records the attempt.
        awaitWakeLock(held = false)

        player.play()
        settle()
        awaitWakeLock(held = true)
    }

    @Test
    fun releasingThePlayerFreesTheLockAndTheFocusItHeld() {
        playUntilReady()
        awaitWakeLock(held = true)

        player.release()
        settle()

        // What "released" means, said in observable terms rather than in bookkeeping: nothing is
        // playing, the device may sleep, and another app may have the audio.
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.isPlaying).isFalse()
        awaitWakeLock(held = false)
        assertThat(shadowOf(audioManager).lastAbandonedAudioFocusRequest).isNotNull()
    }

    private companion object {
        const val EPISODE = "catalog:episode:1138"
    }
}
