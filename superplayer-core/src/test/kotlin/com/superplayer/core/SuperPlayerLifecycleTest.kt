package com.superplayer.core

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
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

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    private lateinit var player: SuperPlayer
    private lateinit var clock: FakeClock
    private var released = false

    @Before
    fun setUp() {
        // Media3 checks for `WAKE_LOCK` before taking a lock, and Robolectric grants an application
        // no permission unless a test says so — not even one its merged manifest declares. A
        // consumer's app gets this permission from `media3-exoplayer`'s own manifest, so granting it
        // here is what makes this runtime resemble the one the code actually runs in.
        shadowOf(context as Application).grantPermissions(Manifest.permission.WAKE_LOCK)

        clock = FakeClock(/* isAutoAdvancing= */ true)
        val fakeDataSourceFactory =
            FakeDataSource.Factory().setFakeDataSet(SyntheticHlsStream.addTo(FakeDataSet()))

        player =
            SuperPlayer.Builder(context)
                .setEngineConfigurator { engine ->
                    engine.setClock(clock)
                    engine.setMediaSourceFactory(DefaultMediaSourceFactory(fakeDataSourceFactory))
                }
                .build()
    }

    @After
    fun tearDown() {
        if (!released) player.release()
    }

    /** [SuperPlayer.release], once, so that a test may release and `tearDown` still be safe. */
    private fun releasePlayer() {
        player.release()
        released = true
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
    private fun settle() {
        TestPlayerRunHelper.advance(player)
            .untilPendingCommandsAreFullyHandled(clock, player.applicationLooper)
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

        val wakeLock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
        player.stop()
        settle()

        assertThat(shadowOf(audioManager).lastAbandonedAudioFocusRequest).isNotNull()
        // Stopping is not pausing: the engine goes idle, and everything it was holding on the
        // platform's behalf goes with it.
        assertThat(wakeLock.isHeld).isFalse()
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

        val wakeLock = checkNotNull(ShadowPowerManager.getLatestWakeLock()) {
            "The engine never took a wake lock"
        }
        assertThat(wakeLock.isHeld).isTrue()

        player.pause()
        settle()

        // A paused player needs neither the CPU nor the radio, and one that keeps the device awake
        // anyway is a battery complaint that never names the app causing it.
        //
        // The other half of Media3's rule — that buffering *with* the intent to play holds the lock,
        // which is where SuperPlayer departs from a literal "not while buffering" — is deliberate
        // and argued in ADR-0006, but it is not pinned here. The synthetic stream leaves BUFFERING
        // immediately and the lock is taken asynchronously, so any assertion on that window would be
        // a race rather than a check; it is Media3's rule, and Media3 tests it.
        assertThat(wakeLock.isHeld).isFalse()

        player.play()
        settle()
        assertThat(wakeLock.isHeld).isTrue()
    }

    @Test
    fun releasingThePlayerFreesTheLockAndTheFocusItHeld() {
        playUntilReady()
        val wakeLock = checkNotNull(ShadowPowerManager.getLatestWakeLock())

        releasePlayer()
        settle()

        // What "released" means, said in observable terms rather than in bookkeeping: nothing is
        // playing, the device may sleep, and another app may have the audio.
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.isPlaying).isFalse()
        assertThat(wakeLock.isHeld).isFalse()
        assertThat(shadowOf(audioManager).lastAbandonedAudioFocusRequest).isNotNull()
    }

    private companion object {
        const val EPISODE = "catalog:episode:1138"
    }
}
