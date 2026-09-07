package com.superplayer.core

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.robolectric.RobolectricUtil
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * [PlaybackService]'s own lifecycle: what a service creates, what it registers, and what it takes
 * with it when it goes.
 *
 * Driven through Robolectric's service controller, which delivers the same `onCreate` and
 * `onDestroy` the system does. That is the only way to make the claims here: they are claims about
 * an Android component's lifecycle rather than about a player, and nothing a `MediaController` can
 * see distinguishes a service that registered its session from one that did not.
 *
 * The session's own behaviour — metadata, transport, content resolution — is `SuperPlayerSessionTest`
 * and deliberately not repeated here.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerServiceTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    private lateinit var player: SuperPlayer
    private var serviceController: ServiceController<TestPlaybackService>? = null

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.WAKE_LOCK)

        // Built here rather than inside the factory, so the test holds the same player the service
        // does and can ask it afterwards what the service left it in.
        player = harness.buildPlayer(
            fakeDataSet = SyntheticHlsStream.addTo(FakeDataSet()),
        )
        TestPlaybackService.playerFactory = { player }
        TestPlaybackService.catalog = null
    }

    @After
    fun tearDown() {
        // A session is published process-wide under an id, and two sharing one is rejected — so a
        // service a test left standing makes the *next* test fail, naming neither of them. Robolectric
        // does not tear a service down on its own.
        destroyService()

        // Static state on a service outlives the test that set it, for the same reason.
        TestPlaybackService.playerFactory = null
        TestPlaybackService.catalog = null
    }

    /** A created service, as the system would create it. */
    private fun createService(): TestPlaybackService {
        val controller = Robolectric.buildService(TestPlaybackService::class.java)
        serviceController = controller
        return controller.create().get()
    }

    /** Destroys the service this test created, if it has not been destroyed already. */
    private fun destroyService() {
        serviceController?.destroy()
        serviceController = null
    }

    @Test
    fun aCreatedServicePublishesAndRegistersOneSession() {
        val service = createService()

        // Registered, not merely built. Media3 learns about a session from `addSession` or from a
        // controller connecting, and only a registered session gets a notification — so an app whose
        // UI holds the player directly, and never connects a controller, would otherwise play in the
        // background with nothing on screen to stop it.
        assertThat(service.sessions).hasSize(1)
        assertThat(service.sessions.single().player).isSameInstanceAs(player)
    }

    @Test
    fun destroyingTheServiceReleasesTheSessionAndThePlayer() {
        createService()

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setTitle(EPISODE_TITLE)
                .build(),
        )
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        destroyService()
        harness.settle(player)

        // Said in observable terms, as everywhere else: nothing is playing, and the audio the
        // service was holding on the app's behalf has been given back. A service that released the
        // session but not the player would leave a live engine holding a codec with nothing on
        // screen; one that released them in the other order would crash the next controller to
        // touch it.
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.isPlaying).isFalse()
        assertThat(shadowOf(audioManager).lastAbandonedAudioFocusRequest).isNotNull()
    }

    @Test
    fun aControllerReachesTheServicesOwnCatalog() {
        TestPlaybackService.catalog = MediaRequestResolver { contentId ->
            MediaRequest.Builder(contentId)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setTitle(EPISODE_TITLE)
                .build()
                .takeIf { contentId == EPISODE }
        }
        val service = createService()

        val future = MediaController.Builder(context, service.sessions.single().token).buildAsync()
        RobolectricUtil.runMainLooperUntil { future.isDone }
        val controller = future.get()

        // The whole chain, end to end: a subclass's `onResolveContent`, the session
        // `onCreateSession` built around it, and a controller that knows only an id. An override of
        // `onCreateSession` that forgot to pass the resolver on fails here, rather than in somebody's
        // car six months later.
        controller.setMediaItem(MediaItem.Builder().setMediaId(EPISODE).build())
        controller.prepare()
        RobolectricUtil.runMainLooperUntil { player.currentMediaItem?.mediaId == EPISODE }

        assertThat(player.currentMediaItem?.localConfiguration?.uri)
            .isEqualTo(Uri.parse(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        assertThat(player.mediaMetadata.title.toString()).isEqualTo(EPISODE_TITLE)

        controller.release()
    }

    private companion object {
        const val EPISODE = "catalog:episode:1138"
        const val EPISODE_TITLE = "The Lost Cause"
    }
}
