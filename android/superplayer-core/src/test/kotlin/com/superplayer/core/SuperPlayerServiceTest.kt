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
import android.app.NotificationManager
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
import com.superplayer.testmedia.SyntheticHlsStream
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
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.WAKE_LOCK,
            // Without it Android posts no notification and Media3 says nothing about why, which is
            // the same silence a real app hits when it forgets to ask at runtime.
            Manifest.permission.POST_NOTIFICATIONS,
        )

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
    fun playingPostsANotificationSayingWhatIsPlaying() {
        createService()

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setTitle(EPISODE_TITLE)
                .setSubtitle(EPISODE_SUBTITLE)
                .build(),
        )
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
        harness.settle(player)

        val notificationManager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val notification = notificationManager.allNotifications.single()

        // The end of the metadata path, at the far end from `MediaRequest.Builder.setTitle`: what a
        // viewer who has pressed home actually reads. Nothing between the two is asserted, and
        // nothing needs to be — a notification with the wrong title is the only failure that
        // matters, and this is where it is visible.
        assertThat(notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            .isEqualTo(EPISODE_TITLE)
        assertThat(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            .isEqualTo(EPISODE_SUBTITLE)

        // That there *are* transport actions, not how many or which. Media3 chooses the set from the
        // player's available commands, so pinning a count would pin a Media3 decision this library
        // does not make. That the controls work is `SuperPlayerSessionTest`'s subject.
        assertThat(notification.actions.asList()).isNotEmpty()

        // Started, not merely bound — this is what makes playback survive the Activity going away.
        assertThat(shadowOf(context as Application).nextStartedService?.component?.className)
            .isEqualTo(TestPlaybackService::class.java.name)
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
        const val EPISODE_SUBTITLE = "Season 2, Episode 4"
    }
}
