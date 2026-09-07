package com.superplayer.core

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Surviving a configuration change, driven through the facade.
 *
 * A rotation is modelled as what it actually is: the player is released and a second one is built in
 * its place, with nothing carried between them but a [Bundle] — the same round trip an
 * `onSaveInstanceState` makes. Nothing here reaches into either player, and the second player is
 * built by the same public [SuperPlayer.Builder] a consumer would use, so what these tests pin is
 * that the *API* is sufficient rather than that some internal state was copied.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerSnapshotTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val players = mutableListOf<SuperPlayer>()
    private lateinit var clock: FakeClock

    @After
    fun tearDown() {
        players.forEach { it.release() }
    }

    /** A player built as a consumer would build one, with the test seam's fakes underneath. */
    private fun buildPlayer(): SuperPlayer {
        clock = FakeClock(/* isAutoAdvancing= */ true)
        val fakeDataSourceFactory =
            FakeDataSource.Factory().setFakeDataSet(
                SyntheticDashStream.addTo(SyntheticHlsStream.addTo(FakeDataSet())),
            )

        return SuperPlayer.Builder(context)
            .setEngineConfigurator { engine ->
                engine.setClock(clock)
                engine.setMediaSourceFactory(DefaultMediaSourceFactory(fakeDataSourceFactory))
            }
            .build()
            .also { players += it }
    }

    private fun SuperPlayer.prepareUntilReady() {
        prepare()
        TestPlayerRunHelper.advance(this)
            .untilPendingCommandsAreFullyHandled(clock, applicationLooper)
        TestPlayerRunHelper.advance(this).untilState(Player.STATE_READY)
    }

    private fun hlsRequest(
        contentId: String = EPISODE,
        startPosition: MediaRequest.StartPosition = MediaRequest.StartPosition.Beginning,
    ) = MediaRequest.Builder(contentId)
        .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
        .setStartPosition(startPosition)
        .build()

    /** The whole of a configuration change, as far as the library is concerned. */
    private fun rotate(player: SuperPlayer): SuperPlayer {
        val saved = player.saveSnapshot().toBundle()
        player.release()

        return buildPlayer().apply { restoreSnapshot(PlaybackSnapshot.fromBundle(saved)) }
    }

    @Test
    fun aRotationResumesTheSameContentAtTheSamePositionRatherThanRestartingIt() {
        val player = buildPlayer()
        player.setMediaRequest(hlsRequest(startPosition = MediaRequest.StartPosition.At(600L)))
        player.playWhenReady = true
        player.prepareUntilReady()
        val positionBefore = player.currentPosition

        val restored = rotate(player)

        // Loaded, positioned and still wanting to play, before `prepare` and before a single frame
        // has been decoded. That is what "does not restart the video" has to mean at this seam.
        assertThat(restored.currentMediaItem?.mediaId).isEqualTo(EPISODE)
        assertThat(restored.currentPosition).isEqualTo(positionBefore)
        assertThat(restored.playWhenReady).isTrue()

        restored.prepareUntilReady()

        assertThat(restored.currentPosition).isAtLeast(positionBefore)
        assertThat(restored.isPlaying).isTrue()
    }

    @Test
    fun aRotationDoesNotUndoADeliberatePause() {
        val player = buildPlayer()
        player.setMediaRequest(hlsRequest())
        player.playWhenReady = true
        player.prepareUntilReady()
        player.pause()

        val restored = rotate(player)

        // The intent is the user's, not the Activity's. A device that starts playing again because
        // it was turned sideways is the same bug as one that stops.
        assertThat(restored.playWhenReady).isFalse()
    }

    @Test
    fun theResumePositionsOfOtherContentSurviveTheRotation() {
        val player = buildPlayer()
        player.setMediaRequest(hlsRequest(contentId = EPISODE))
        player.playWhenReady = true
        player.prepareUntilReady()
        val watchedPosition = player.currentPosition

        // Move away from it, so its position becomes something the player merely remembers rather
        // than something it is currently reporting.
        player.setMediaRequest(
            MediaRequest.Builder(TRAILER)
                .addSource(SyntheticDashStream.MANIFEST_URI)
                .build(),
        )
        player.prepareUntilReady()

        val restored = rotate(player)
        restored.setMediaRequest(
            hlsRequest(
                contentId = EPISODE,
                startPosition = MediaRequest.StartPosition.ResumeFromLastKnown,
            ),
        )

        // The failure this rules out is the quiet one: a rotation that silently empties the player's
        // memory looks fine until someone switches back to what they were watching an hour ago.
        assertThat(restored.currentPosition).isEqualTo(watchedPosition)
    }

    @Test
    fun aSnapshotOfAPlayerThatWasGivenARawMediaItemNamesNoContent() {
        val player = buildPlayer()
        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.playWhenReady = true
        player.prepareUntilReady()

        val snapshot = player.saveSnapshot()

        // A `MediaItem` carries no identity to restore under — the whole reason `MediaRequest`
        // exists — so the snapshot says so rather than inventing one.
        assertThat(snapshot.request).isNull()
        assertThat(snapshot.positionMs).isEqualTo(C.TIME_UNSET)
        assertThat(snapshot.playWhenReady).isTrue()
    }

    @Test
    fun aSnapshotOfContentThatHasEndedRestoresFromTheBeginning() {
        val player = buildPlayer()
        player.setMediaRequest(hlsRequest())
        player.playWhenReady = true
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_ENDED)

        val restored = rotate(player)

        // Restoring to the end would produce a player that immediately ends again, which is the same
        // answer `ResumeFromLastKnown` already gives for finished content. The content is still
        // named, so this is a restore that happened rather than one that quietly did nothing.
        assertThat(restored.currentMediaItem?.mediaId).isEqualTo(EPISODE)
        assertThat(restored.currentPosition).isEqualTo(0L)
    }

    @Test
    fun aSnapshotSurvivesTheBundleRoundTripIntact() {
        val player = buildPlayer()
        val request = MediaRequest.Builder(EPISODE)
            .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
            .addSource(UNSERVED_SOURCE_URI)
            .setStartPosition(MediaRequest.StartPosition.At(400L))
            .build()
        player.setMediaRequest(request)
        player.prepareUntilReady()

        val restored = PlaybackSnapshot.fromBundle(player.saveSnapshot().toBundle())

        // Every part of the request, not only the part that is played: the unused mirrors are what a
        // failover will reach for, and a snapshot that dropped them would quietly halve a restored
        // player's resilience.
        val restoredRequest = checkNotNull(restored.request)
        assertThat(restoredRequest.contentId).isEqualTo(EPISODE)
        assertThat(restoredRequest.sources.map { it.toString() })
            .containsExactly(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI, UNSERVED_SOURCE_URI)
            .inOrder()
        assertThat(restoredRequest.startPosition).isEqualTo(MediaRequest.StartPosition.At(400L))
    }

    @Test
    fun everyKindOfStartPositionSurvivesTheBundleRoundTrip() {
        val positions = listOf(
            MediaRequest.StartPosition.Beginning,
            MediaRequest.StartPosition.At(1_234L),
            MediaRequest.StartPosition.ResumeFromLastKnown,
        )

        // Exhaustive on purpose. Writing a `StartPosition` and reading one back are two `when`
        // cascades that have to stay in lockstep, and a fourth kind added to only one of them would
        // otherwise fail silently — as the wrong start position, on a restore, in someone's app.
        for (startPosition in positions) {
            val player = buildPlayer()
            player.setMediaRequest(hlsRequest(startPosition = startPosition))

            val restored = PlaybackSnapshot.fromBundle(player.saveSnapshot().toBundle())

            assertThat(restored.request?.startPosition).isEqualTo(startPosition)
        }
    }

    @Test
    fun restoringASnapshotThatNamesNoContentStillRestoresTheIntentToPlay() {
        val player = buildPlayer()
        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.playWhenReady = true
        player.prepareUntilReady()

        val restored = rotate(player)

        // The documented answer for content with no identity: the intent survives even though the
        // content cannot, because the viewer did ask for playback and the app is about to load its
        // own item. What must not happen is the content being invented — see the null assertion.
        assertThat(restored.currentMediaItem).isNull()
        assertThat(restored.playWhenReady).isTrue()
    }

    @Test
    fun aBundleThatLostItsContentsRestoresWhatIsLeftRatherThanThrowing() {
        // What a foreign or truncated bundle looks like. Restoring one happens on the way back into
        // an app, where an exception is a crash the user sees and a lost position is not.
        val snapshot = PlaybackSnapshot.fromBundle(android.os.Bundle())

        assertThat(snapshot.request).isNull()
        assertThat(snapshot.playWhenReady).isFalse()

        val player = buildPlayer()
        player.restoreSnapshot(snapshot)

        assertThat(player.currentMediaItem).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
    }

    private companion object {
        const val EPISODE = "catalog:episode:1138"
        const val TRAILER = "catalog:trailer:1138"
        const val UNSERVED_SOURCE_URI = "https://cdn-b.example.com/1138/master.m3u8"
    }
}
