package com.superplayer.core

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DASH goes through the same public API as HLS, and this is where that stops being a claim.
 *
 * The interesting thing about these tests is what is *not* in them: no DASH-specific entry point, no
 * protocol argument, no source factory chosen by the caller. `setMediaItem` and `prepare` are the
 * same two calls the HLS test makes, and the only difference between the two streams is the URI.
 * Consuming code therefore never branches on streaming protocol, which is the whole acceptance
 * criterion.
 *
 * Same seam as everything else here: Robolectric, a fake clock, Media3's fake data source over a
 * synthetic manifest, and every assertion made through the facade. See `docs/testing.md`.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerDashPlaybackTest {

    /** As in [SuperPlayerPlaybackTest]: Robolectric has no real codecs, so shadow ones stand in. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private lateinit var player: SuperPlayer

    @Before
    fun setUp() {
        // Both streams in one data set, so that switching protocols mid-session needs nothing more
        // than a different URI — no second player, and no second data source either.
        player = harness.buildPlayer(
            fakeDataSet = SyntheticDashStream.addTo(SyntheticHlsStream.addTo(FakeDataSet())),
        )
    }

    /** The same three public calls the HLS test makes, given a different URI. */
    private fun prepareUntilReady(uri: String): Tracks {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        return player.currentTracks
    }

    @Test
    fun preparingADashStreamReachesReadyAndReportsTheSelectedTrack() {
        val tracks = prepareUntilReady(SyntheticDashStream.MANIFEST_URI)

        // One group, and only one. HLS reports an in-band ID3 metadata track alongside its audio;
        // DASH has no such thing, so asserting the whole set rather than just the audio group is
        // what keeps this test honest about the protocol it is actually driving.
        assertThat(tracks.groups.map { it.type }).containsExactly(C.TRACK_TYPE_AUDIO)

        val audioGroup = tracks.groups.single()
        assertThat(audioGroup.isSelected).isTrue()
        assertThat(audioGroup.isTrackSelected(0)).isTrue()

        // What the MPD declared, reported back through the facade. Deliberately no claim about the
        // segments here: unlike HLS, where the group format is derived from the extracted sample
        // format, DASH builds its track groups out of the manifest, so this block would pass over a
        // garbage initialization segment. `aDashStreamPlaysThroughToTheEnd` is what covers those.
        val format = audioGroup.getTrackFormat(0)
        assertThat(format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC)
        assertThat(format.codecs).isEqualTo(SyntheticDashStream.DECLARED_CODECS)
        assertThat(format.bitrate).isEqualTo(SyntheticDashStream.DECLARED_BITRATE_BPS)
        assertThat(format.sampleRate).isEqualTo(SyntheticDashStream.DECLARED_SAMPLE_RATE_HZ)
        assertThat(format.channelCount).isEqualTo(SyntheticDashStream.DECLARED_CHANNEL_COUNT)
    }

    @Test
    fun aDashStreamPlaysThroughToTheEnd() {
        prepareUntilReady(SyntheticDashStream.MANIFEST_URI)

        assertThat(player.duration).isEqualTo(SyntheticDashStream.DURATION_MS)

        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_ENDED)

        // Reaching ENDED is what makes the rest of this class non-vacuous. DASH reports its tracks
        // from the manifest alone, so every format assertion above would survive an initialization
        // segment that was nonsense; getting here means the fragmented MP4 was actually fetched,
        // parsed, and fed through the renderer.
        assertThat(player.currentPosition).isEqualTo(SyntheticDashStream.DURATION_MS)
    }

    @Test
    fun switchingBetweenHlsAndDashReusesTheSamePlayer() {
        val hlsTracks = prepareUntilReady(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
        assertThat(hlsTracks.groups.map { it.type })
            .containsExactly(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA)

        // No release, no rebuild, no new PlayerView binding — the same instance, given a new item.
        // An app that has to tear its player down to change protocol has to tear its surface down
        // with it, which is a visible black frame the user pays for.
        val dashTracks = prepareUntilReady(SyntheticDashStream.MANIFEST_URI)
        assertThat(dashTracks.groups.map { it.type }).containsExactly(C.TRACK_TYPE_AUDIO)

        // And back again, because a one-way switch would pass while leaving a real app's second
        // switch broken.
        val hlsTracksAgain = prepareUntilReady(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
        assertThat(hlsTracksAgain.groups.map { it.type })
            .containsExactly(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA)
    }

    @Test
    fun theTwoProtocolsReportTheSameTrackForEquivalentMedia() {
        val hlsFormat =
            prepareUntilReady(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .groups.single { it.type == C.TRACK_TYPE_AUDIO }
                .getTrackFormat(0)

        val dashFormat =
            prepareUntilReady(SyntheticDashStream.MANIFEST_URI)
                .groups.single { it.type == C.TRACK_TYPE_AUDIO }
                .getTrackFormat(0)

        // The two synthetic streams carry the same codec at the same bitrate on purpose. A consumer
        // reading a track's format must not be able to tell which protocol delivered it — that is
        // what "the same public API" has to mean past the entry point, not just at it.
        assertThat(dashFormat.sampleMimeType).isEqualTo(hlsFormat.sampleMimeType)
        assertThat(dashFormat.codecs).isEqualTo(hlsFormat.codecs)
        assertThat(dashFormat.bitrate).isEqualTo(hlsFormat.bitrate)
    }
}
