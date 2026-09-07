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
import org.robolectric.Shadows.shadowOf

/**
 * What a [PlaybackSession] publishes, driven the way the platform drives it: through a
 * [MediaController], which is the only thing a notification, a lock screen, a car head unit or a
 * watch ever is.
 *
 * These are the same seam `docs/testing.md` describes — synthetic streams over Media3's fake data
 * source, a fake clock, assertions through the public API — with one addition. A controller is not
 * an implementation detail reached past the facade: it is *the* consumer of a session, it speaks
 * only Media3's stable `Player` API, and every claim this file makes is a claim about what an
 * external surface can see and do. A test that asserted on the session object instead would be
 * asserting that SuperPlayer had called Media3 correctly, which is a different and much weaker
 * statement than that a controller works.
 *
 * The controller connects in-process, to a token this test holds. That is how an app's own Activity
 * connects to a session it published; a controller in another process reaches the same session
 * through [PlaybackService], and everything below it is identical, because Media3's controller
 * cannot tell the two apart.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerSessionTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    private lateinit var player: SuperPlayer
    private val sessions = mutableListOf<PlaybackSession>()
    private val controllers = mutableListOf<MediaController>()

    @Before
    fun setUp() {
        // The player's own lifecycle rules run here too, and Media3 checks for `WAKE_LOCK` before
        // taking a lock. See SuperPlayerLifecycleTest for why Robolectric needs to be told.
        shadowOf(context as Application).grantPermissions(Manifest.permission.WAKE_LOCK)

        // Two streams, so that a controller can move between two pieces of content — which is what
        // makes a resumed position something other than the only position there is.
        player = harness.buildPlayer(
            fakeDataSet = SyntheticDashStream.addTo(SyntheticHlsStream.addTo(FakeDataSet())),
        )
    }

    @After
    fun tearDown() {
        // Controllers first: one left connected holds a binder to a session the harness is about to
        // release, and the failure that produces names neither this test nor the next one.
        controllers.forEach { it.release() }
        sessions.forEach { it.release() }
    }

    /**
     * A session over [player], with [resolver] as its catalog lookup.
     *
     * Released in [tearDown] rather than by each test, for the reason `SuperPlayerHarness` is a rule:
     * a session a failing assertion left published survives into the next test, and Media3 rejects a
     * second session sharing its id with a loud but misleading error.
     */
    private fun buildSession(resolver: MediaRequestResolver? = null): PlaybackSession =
        PlaybackSession.Builder(context, player)
            .apply { resolver?.let { setMediaRequestResolver(it) } }
            .build()
            .also { sessions += it }

    /**
     * A controller connected to [session], as the platform would connect one.
     *
     * The connection completes on the application looper, so it is awaited rather than sampled —
     * `buildAsync` is done when the session has answered, and reading the future before that is
     * reading the question rather than the answer.
     */
    private fun connectController(session: PlaybackSession): MediaController {
        val future = MediaController.Builder(context, session.token).buildAsync()
        RobolectricUtil.runMainLooperUntil { future.isDone }
        return future.get().also { controllers += it }
    }

    /** The demo catalog: two pieces of content, the two synthetic streams. */
    private fun catalogRequest(contentId: String): MediaRequest? = when (contentId) {
        EPISODE -> MediaRequest.Builder(EPISODE)
            .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
            .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
            .setTitle(EPISODE_TITLE)
            .setSubtitle(EPISODE_SUBTITLE)
            .setArtworkUri(ARTWORK_URI)
            .build()

        TRAILER -> MediaRequest.Builder(TRAILER)
            .addSource(SyntheticDashStream.MANIFEST_URI)
            .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
            .setTitle(TRAILER_TITLE)
            .build()

        else -> null
    }

    private fun playUntilReady() {
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
    }

    @Test
    fun aControllerSeesWhatThePlayerIsPlayingAndWhatToCallIt() {
        val controller = connectController(buildSession())

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setTitle(EPISODE_TITLE)
                .setSubtitle(EPISODE_SUBTITLE)
                .setArtworkUri(ARTWORK_URI)
                .build(),
        )
        playUntilReady()

        RobolectricUtil.runMainLooperUntil { controller.isPlaying }

        // The identity travels: a controller knows *what* is playing, not merely that something is.
        assertThat(controller.currentMediaItem?.mediaId).isEqualTo(EPISODE)

        // ...and so does everything a notification, a lock screen or a car draws. This is the whole
        // of why a MediaRequest carries display fields: nothing outside the app can ask for them.
        val metadata = controller.mediaMetadata
        assertThat(metadata.title.toString()).isEqualTo(EPISODE_TITLE)
        assertThat(metadata.artist.toString()).isEqualTo(EPISODE_SUBTITLE)
        assertThat(metadata.artworkUri).isEqualTo(Uri.parse(ARTWORK_URI))
    }

    @Test
    fun aControllerSeesPlaybackStateFollowTheActualPlayer() {
        val controller = connectController(buildSession())

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )
        playUntilReady()
        RobolectricUtil.runMainLooperUntil { controller.isPlaying }

        player.pause()

        // A controller that kept saying "playing" after the app paused is the notification that
        // shows a pause button over silence. State is pushed to controllers, so this is awaited.
        RobolectricUtil.runMainLooperUntil { !controller.isPlaying }
        assertThat(controller.playWhenReady).isFalse()
        assertThat(controller.playbackState).isEqualTo(Player.STATE_READY)
    }

    @Test
    fun transportCommandsFromAControllerDrivePlayback() {
        val controller = connectController(buildSession())

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        // Play, pause and seek: the three controls Media3's notification draws, exercised from the
        // side the notification is on.
        controller.play()
        RobolectricUtil.runMainLooperUntil { player.isPlaying }

        controller.pause()
        RobolectricUtil.runMainLooperUntil { !player.playWhenReady }

        controller.seekTo(SEEK_POSITION_MS)
        RobolectricUtil.runMainLooperUntil { player.currentPosition >= SEEK_POSITION_MS }
        assertThat(player.currentPosition).isAtLeast(SEEK_POSITION_MS)
    }

    @Test
    fun playbackStartedByAControllerRequestsAudioFocusLikeAnyOther() {
        val controller = connectController(buildSession())

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
        assertThat(shadowOf(audioManager).lastAudioFocusRequest).isNull()

        controller.play()

        // Awaited rather than sampled. The engine requests focus on the playback thread, a post
        // behind the command that caused it, so reading the `AudioManager` straight after
        // `isPlaying` turns true answers the previous question — see `docs/testing.md`.
        RobolectricUtil.runMainLooperUntil {
            shadowOf(audioManager).lastAudioFocusRequest != null
        }

        // The session asks for no focus of its own: it publishes the player, and the player already
        // requests focus because SuperPlayer builds every one of them that way (ADR-0006 rule 1).
        // A service that duplicated this would be competing with the engine that holds it, and the
        // symptom is a player ducking itself.
        val request = checkNotNull(shadowOf(audioManager).lastAudioFocusRequest)
        assertThat(request.durationHint).isEqualTo(AudioManager.AUDIOFOCUS_GAIN)
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun aControllerCanStartContentByItsIdAlone() {
        val controller = connectController(buildSession(::catalogRequest))

        // What a car, a watch or Assistant sends: an id and nothing else. Neither a URL nor a title
        // is available to a controller — they are the app's, and the resolver is where the app
        // hands them over.
        controller.setMediaItem(MediaItem.Builder().setMediaId(EPISODE).build())
        controller.prepare()
        controller.play()

        RobolectricUtil.runMainLooperUntil { player.isPlaying }

        assertThat(player.currentMediaItem?.mediaId).isEqualTo(EPISODE)
        assertThat(player.currentMediaItem?.localConfiguration?.uri)
            .isEqualTo(Uri.parse(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        assertThat(player.mediaMetadata.title.toString()).isEqualTo(EPISODE_TITLE)
    }

    @Test
    fun contentStartedByAControllerResumesWhereTheAppLeftIt() {
        val controller = connectController(buildSession(::catalogRequest))

        // Watched in the app, and left part-way through.
        player.setMediaRequest(catalogRequest(EPISODE)!!)
        playUntilReady()
        player.seekTo(SEEK_POSITION_MS)
        harness.settle(player)

        // Something else plays, which is what pushes the position above into the player's memory.
        controller.setMediaItem(MediaItem.Builder().setMediaId(TRAILER).build())
        controller.prepare()
        RobolectricUtil.runMainLooperUntil { player.currentMediaItem?.mediaId == TRAILER }

        // ...and now the same content is asked for again from outside the app. A controller has no
        // position to send and never had one; the resume is the resolver's `ResumeFromLastKnown`
        // being honoured on the session's side of the boundary.
        controller.setMediaItem(MediaItem.Builder().setMediaId(EPISODE).build())
        controller.prepare()
        RobolectricUtil.runMainLooperUntil { player.currentMediaItem?.mediaId == EPISODE }

        assertThat(player.currentPosition).isAtLeast(SEEK_POSITION_MS)
    }

    @Test
    fun anIdThisAppDoesNotRecogniseIsDeclinedRatherThanInvented() {
        val controller = connectController(buildSession(::catalogRequest))

        player.setMediaRequest(catalogRequest(EPISODE)!!)
        playUntilReady()

        // A stale notification from a previous install, or a car naming something the app has since
        // dropped from its catalog. The resolver declines it, and declining means handing the item
        // back untouched rather than inventing a URL nobody has.
        controller.setMediaItem(MediaItem.Builder().setMediaId(UNKNOWN_CONTENT).build())
        controller.prepare()
        harness.settle(player)

        // Media3 refuses an item with no source, so what a viewer sees is the content that was
        // already playing, still playing. That is the outcome worth pinning: the failure mode this
        // guards against is not an error but a *plausible* wrong answer — a resolver that fell back
        // to treating the id as a URL would leave the player loading `catalog:episode:gone`.
        assertThat(player.currentMediaItem?.mediaId).isEqualTo(EPISODE)
        assertThat(player.playerError).isNull()
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun swappingThePlayerKeepsTheSessionAndTheControllersConnectedToIt() {
        val session = buildSession()
        val controller = connectController(session)

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setTitle(EPISODE_TITLE)
                .build(),
        )
        playUntilReady()
        player.seekTo(SEEK_POSITION_MS)
        harness.settle(player)

        // What an app with a data-saver switch does: a profile is fixed when a player is built, so
        // changing it means a new player — and the notification must not blink out of existence for
        // a settings change.
        val replacement = harness.buildPlayer(
            profile = PlaybackProfile.DATA_SAVER,
            fakeDataSet = SyntheticHlsStream.addTo(FakeDataSet()),
        )
        replacement.restoreSnapshot(session.player.saveSnapshot())
        replacement.prepare()
        session.setPlayer(replacement)

        RobolectricUtil.runMainLooperUntil {
            controller.currentMediaItem?.mediaId == EPISODE && controller.currentPosition > 0
        }

        // The same controller, never reconnected, now driving the new player — and looking at the
        // content the old one was playing, at the position it had reached.
        assertThat(controller.currentMediaItem?.mediaId).isEqualTo(EPISODE)
        assertThat(controller.currentPosition).isAtLeast(SEEK_POSITION_MS)
        assertThat(replacement.profile).isEqualTo(PlaybackProfile.DATA_SAVER)
    }

    @Test
    fun releasingTheSessionReleasesThePlayerItPublished() {
        val session = buildSession()
        connectController(session)

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )
        playUntilReady()

        session.release()
        harness.settle(player)

        // One lifetime, said in observable terms: nothing is playing, and the audio the session was
        // holding on the app's behalf has been given back.
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.isPlaying).isFalse()
        assertThat(shadowOf(audioManager).lastAbandonedAudioFocusRequest).isNotNull()

        // Twice, because an Activity and a service will each reasonably tidy up after themselves.
        session.release()
    }

    private companion object {
        const val EPISODE = "catalog:episode:1138"
        const val EPISODE_TITLE = "The Lost Cause"
        const val EPISODE_SUBTITLE = "Season 2, Episode 4"
        const val TRAILER = "catalog:trailer:2187"
        const val TRAILER_TITLE = "A trailer"
        const val ARTWORK_URI = "https://images.example.com/1138/cover.jpg"
        const val UNKNOWN_CONTENT = "catalog:episode:gone"

        /** Comfortably inside the synthetic streams, which are one two-second segment each. */
        const val SEEK_POSITION_MS = 1_000L
    }
}
