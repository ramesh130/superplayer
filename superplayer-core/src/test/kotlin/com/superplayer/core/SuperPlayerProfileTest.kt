package com.superplayer.core

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a [PlaybackProfile] is worth, asserted where a consumer can see it: through the facade.
 *
 * Two things are being pinned, and they are different claims. The first is that each profile
 * *produces* the configuration its documentation promises — the numbers are written out again here,
 * literally, so that changing one in [StaticProfilePolicy] without meaning to fails, and changing one
 * on purpose is a visible diff in two places rather than a silent retune of every consumer's
 * playback.
 *
 * The second is that the decision actually *reaches the engine*: the selection half of it is
 * observable as Media3's own [TrackSelectionParameters], read back off the player. That is the half
 * Media3 exposes; the buffer half is handed to a `LoadControl` at construction and Media3 offers no
 * way to read it back, so what is asserted there is what the facade reports it applied. See
 * [SuperPlayer.playbackDecision].
 *
 * Nothing here reaches for [SuperPlayer.exoPlayer] or for [PlaybackPolicy] itself — per
 * `docs/testing.md`, and because a test that asserted on the policy implementation would pass just
 * as happily if the facade never consulted it.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerProfileTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test
    fun theDefaultProfileIsVideoOnDemand() {
        val player = buildPlayer()

        try {
            assertThat(player.profile).isEqualTo(PlaybackProfile.VIDEO_ON_DEMAND)
        } finally {
            player.release()
        }
    }

    @Test
    fun videoOnDemandProducesItsDocumentedConfiguration() {
        assertProfileProduces(
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            buffer = BufferPolicy(
                minBufferMs = 30_000,
                maxBufferMs = 60_000,
                bufferForPlaybackMs = 2_500,
                bufferForPlaybackAfterRebufferMs = 5_000,
                backBufferMs = 30_000,
                retainBackBufferFromKeyframe = true,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
        )
    }

    @Test
    fun liveLinearProducesItsDocumentedConfiguration() {
        assertProfileProduces(
            profile = PlaybackProfile.LIVE_LINEAR,
            buffer = BufferPolicy(
                minBufferMs = 10_000,
                maxBufferMs = 30_000,
                bufferForPlaybackMs = 1_500,
                bufferForPlaybackAfterRebufferMs = 3_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
        )
    }

    @Test
    fun shortFormProducesItsDocumentedConfiguration() {
        assertProfileProduces(
            profile = PlaybackProfile.SHORT_FORM,
            buffer = BufferPolicy(
                minBufferMs = 2_500,
                maxBufferMs = 15_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = 1_080,
            ),
        )
    }

    @Test
    fun dataSaverProducesItsDocumentedConfiguration() {
        assertProfileProduces(
            profile = PlaybackProfile.DATA_SAVER,
            buffer = BufferPolicy(
                minBufferMs = 10_000,
                maxBufferMs = 20_000,
                bufferForPlaybackMs = 2_500,
                bufferForPlaybackAfterRebufferMs = 5_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = 800_000,
                maxVideoHeightPx = 480,
            ),
        )
    }

    /**
     * The profiles differ from each other, which is the only reason to have four of them.
     *
     * Written as a set comparison rather than as pairwise assertions so that a future profile added
     * as a copy of an existing one — the way a fifth profile most plausibly goes wrong — fails here.
     */
    @Test
    fun noTwoProfilesProduceTheSameConfiguration() {
        val decisions = PlaybackProfile.entries.map { profile ->
            val player = buildPlayer(profile)
            try {
                player.playbackDecision
            } finally {
                player.release()
            }
        }

        assertThat(decisions.toSet()).hasSize(PlaybackProfile.entries.size)
    }

    /**
     * A profile is playback policy, not a playback path: every profile still plays the same content
     * through the same code, and the most restrictive one does not stop content from playing.
     *
     * Note what this does *not* prove. The synthetic stream is audio-only and has one rendition, so
     * data-saver's 480p and 800 kbps ceilings exclude nothing here — they are asserted where they are
     * observable, on the engine's [TrackSelectionParameters]. Proving that a ceiling actually
     * *excludes a rendition* needs a fixture with a video ladder in it, which is `superplayer-testkit`'s
     * subject rather than something to hand-roll here.
     */
    @Test
    fun contentPlaysUnderTheMostRestrictiveProfile() {
        val player = buildPlayer(PlaybackProfile.DATA_SAVER)

        try {
            player.setMediaRequest(
                MediaRequest.Builder("test:hls")
                    .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                    .build(),
            )
            player.prepare()

            TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

            val audioGroup = player.currentTracks.groups.single { it.type == C.TRACK_TYPE_AUDIO }
            assertThat(audioGroup.isSelected).isTrue()
        } finally {
            player.release()
        }
    }

    /**
     * The buffer half of a decision reaches the engine, asserted by what the engine *does* with it.
     *
     * This is the assertion that costs something to write, and the only one that could not be
     * satisfied by a facade that reported a decision it never applied. Media3 gives no way to read a
     * `LoadControl` back, so the observable is behavioural: a player stops loading when it holds
     * `maxBufferMs` of media, and how much it holds is [Player.getTotalBufferedDuration].
     *
     * Short-form caps the buffer at 15s, video-on-demand at 60s, and the stream is 40s long — so the
     * one stops part-way through content it could have finished loading and the other does not. Both
     * halves matter: without the second, a player that failed to load for any reason would pass, and
     * without the first, Media3's own 50s default would pass too.
     */
    @Test
    fun aProfilesBufferCeilingIsWhatTheEngineLoadsTo() {
        val streamDurationMs = SyntheticHlsStream.durationMs(SEGMENTS_FOR_BUFFER_TEST)

        val shortFormBuffer = bufferedDurationAfterLoading(PlaybackProfile.SHORT_FORM)
        val videoOnDemandBuffer = bufferedDurationAfterLoading(PlaybackProfile.VIDEO_ON_DEMAND)

        // Loading stops after the chunk that crosses the ceiling, so the buffer overshoots it by up
        // to one segment. Asserted as a range rather than a number, because the segment boundary is
        // the stream's property and not the policy's.
        assertThat(shortFormBuffer).isAtLeast(SHORT_FORM_MAX_BUFFER_MS)
        assertThat(shortFormBuffer)
            .isAtMost(SHORT_FORM_MAX_BUFFER_MS + SyntheticHlsStream.SEGMENT_DURATION_MS)
        assertThat(videoOnDemandBuffer).isEqualTo(streamDurationMs)
    }

    /** How much media a player of [profile] holds once it has stopped asking for more. */
    private fun bufferedDurationAfterLoading(profile: PlaybackProfile): Long {
        val player = buildPlayer(profile, segmentCount = SEGMENTS_FOR_BUFFER_TEST)

        try {
            player.setMediaRequest(
                MediaRequest.Builder("test:hls")
                    .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                    .build(),
            )
            player.prepare()

            // Both waits are needed, in this order. "Not loading" is also the state the player
            // begins in, so waiting for it alone would return before anything had been fetched;
            // waiting for loading to start first makes the second wait mean "has stopped again".
            //
            // Note what is *not* waited for: STATE_READY. A player that is paused and has stopped
            // loading short of the end of its content has nothing left to drive its playback state
            // forward, so the state is not the observable here — the loader is, and the loader is
            // what the buffer policy governs.
            TestPlayerRunHelper.advance(player).untilLoadingIs(true)
            TestPlayerRunHelper.advance(player).untilLoadingIs(false)

            return player.totalBufferedDuration
        } finally {
            player.release()
        }
    }

    /**
     * Track selection parameters a consumer sets are theirs, and the profile does not take them back.
     *
     * The profile applies its ceilings once, at construction. Today's policy is a static per-profile
     * lookup, so nothing it observes later can change its answer — and this is what says so from
     * outside: play content, let the timeline and tracks arrive, and the consumer's own parameters
     * are still the ones in force. An implementation that re-applied the profile's decision on every
     * player event would fail here, which is the regression worth catching, because it would silently
     * undo a consumer's own quality choice.
     */
    @Test
    fun aConsumersOwnTrackSelectionParametersSurvivePlayback() {
        val player = buildPlayer(PlaybackProfile.DATA_SAVER)

        try {
            player.trackSelectionParameters =
                player.trackSelectionParameters.buildUpon().setMaxVideoBitrate(1_500_000).build()

            player.setMediaRequest(
                MediaRequest.Builder("test:hls")
                    .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                    .build(),
            )
            player.prepare()

            TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

            assertThat(player.trackSelectionParameters.maxVideoBitrate).isEqualTo(1_500_000)
        } finally {
            player.release()
        }
    }

    /**
     * Asserts a profile's whole decision: what the facade reports it applied, and — for the half
     * Media3 exposes — what the engine is actually selecting tracks with.
     */
    private fun assertProfileProduces(
        profile: PlaybackProfile,
        buffer: BufferPolicy,
        trackSelection: TrackSelectionPolicy,
    ) {
        val player = buildPlayer(profile)

        try {
            assertThat(player.profile).isEqualTo(profile)
            assertThat(player.playbackDecision).isEqualTo(PlaybackDecision(buffer, trackSelection))

            val parameters = player.trackSelectionParameters
            assertThat(parameters.maxVideoBitrate).isEqualTo(trackSelection.maxVideoBitrateBps)
            assertThat(parameters.maxVideoHeight).isEqualTo(trackSelection.maxVideoHeightPx)
        } finally {
            player.release()
        }
    }

    private companion object {
        /**
         * Long enough that a short-form player stops loading part-way through it and a
         * video-on-demand player does not: 40s, against ceilings of 15s and 60s.
         */
        const val SEGMENTS_FOR_BUFFER_TEST = 20

        /** [PlaybackProfile.SHORT_FORM]'s documented ceiling, asserted against above. */
        const val SHORT_FORM_MAX_BUFFER_MS = 15_000L
    }

    private fun buildPlayer(
        profile: PlaybackProfile? = null,
        segmentCount: Int = 1,
    ): SuperPlayer {
        val fakeDataSourceFactory = FakeDataSource.Factory()
            .setFakeDataSet(SyntheticHlsStream.addTo(FakeDataSet(), segmentCount))

        return SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .apply { profile?.let { setProfile(it) } }
            .setEngineConfigurator { engine ->
                engine.setClock(FakeClock(/* isAutoAdvancing= */ true))
                engine.setMediaSourceFactory(DefaultMediaSourceFactory(fakeDataSourceFactory))
            }
            .build()
    }
}
