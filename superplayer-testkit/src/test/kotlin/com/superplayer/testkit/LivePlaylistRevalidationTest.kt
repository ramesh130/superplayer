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

package com.superplayer.testkit

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.core.StaleLivePlaylistException
import com.superplayer.core.SuperPlayer
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A live HLS playlist held by a cache in front of an origin that keeps publishing — issue #66's
 * cause, with the `Cache-Control` headers actually served rather than declared.
 *
 * `HostileManifestCorpusTest` plays the *consequence*: a playlist whose bytes never change, which is
 * what a client behind that cache rule sees and cannot be recovered from, because there is nothing
 * newer anywhere. What only this class can show is the recovery, which needs the two things the
 * corpus cannot have — an origin that is still publishing, and a cache between it and the player
 * that honours, or ignores, a request to step aside.
 */
@RunWith(AndroidJUnit4::class)
class LivePlaylistRevalidationTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aLiveStreamWithNoCacheInTheWayIsNeverReloadedPastOne() {
        // The control, and the claim that keeps this on for every player: a playlist that advances
        // on time costs a CDN nothing, because no request of it is changed.
        val player = play(FaultScript.NONE)

        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(segmentsFetched(player)).isGreaterThan(SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT)
        assertThat(harness.cacheBypassingRequests(player)).isEqualTo(0)
    }

    @Test
    fun aPlaylistACacheHoldsForTenMinutesKeepsPlayingByReloadingPastIt() {
        val player = play(FaultScript.Builder().serveThroughCache(TEN_MINUTES_S, kind = ResourceKind.MANIFEST).build())

        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        // Still playing, and playing segments the cached copy never listed: the only way to learn of
        // one is a playlist fetched past the cache, and the requests that did it are counted.
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(segmentsFetched(player)).isGreaterThan(SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT)
        assertThat(harness.cacheBypassingRequests(player)).isGreaterThan(0)
    }

    @Test
    fun aCacheThatIgnoresTheReloadEndsTheSessionNamingItself() {
        // The cache RFC 9111 also permits: `no-cache` in a request is a preference, and many CDNs
        // are configured to disregard it. Nothing recovers this from the client, so what is left to
        // get right is the error — typed, and pointing at the cache rather than at a dead stream.
        val script = FaultScript.Builder()
            .serveThroughCache(TEN_MINUTES_S, honoursNoCache = false, kind = ResourceKind.MANIFEST)
            .build()
        val player = harness.buildPlayer(content = TestContent.liveHls(), faults = script)
        player.setMediaRequest(request())

        harness.playToFailure(player)

        val cause = player.playerError?.cause
        assertThat(cause).isInstanceOf(StaleLivePlaylistException::class.java)
        cause as StaleLivePlaylistException
        assertThat(cause.likelyCause).isEqualTo(StaleLivePlaylistException.LikelyCause.INTERMEDIARY_CACHE)
        assertThat(cause.servedCacheControl).isEqualTo("public, max-age=$TEN_MINUTES_S")
        assertThat(cause.cacheBypassingReloads).isAtLeast(1)
        assertThat(cause.targetDurationMs).isEqualTo(SyntheticHlsStream.SEGMENT_DURATION_MS)
        // Past the point the playlist was late, so the reloads past the cache were tried first.
        assertThat(cause.unchangedForMs).isGreaterThan(SyntheticHlsStream.SEGMENT_DURATION_MS * 3)
        assertThat(harness.cacheBypassingRequests(player)).isAtLeast(1)
    }

    @Test
    fun aPlaylistsHistoryFromAnEarlierSessionIsNotHeldAgainstTheNext() {
        // The layer outlives the session: it is built with the engine, and a player is prepared
        // again after an error, a stop, or a pool's recycle. Judged against the history of the
        // session before, a playlist would look minutes late on its very first load and fail before
        // anything played — which is what a channel whose origin restarted its media sequence would
        // do to a replay, and what Media3's own tracker, fresh on every prepare, would not.
        val stream = HostileManifests.hlsCachedLivePlaylist()
        val content = TestContent.hostile(stream)
        val player = harness.buildPlayer(content = content)
        player.setMediaRequest(MediaRequest.Builder(stream.id).addSource(content.sourceUri).build())
        harness.playToFailure(player)
        assertThat(player.playerError?.cause).isInstanceOf(StaleLivePlaylistException::class.java)

        harness.advanceTimeMs(player, PLAYED_MS)
        // Back to the live edge, as an app re-entering a channel does: resumed where the first
        // session died, the playhead would sit at the end of a window that never grows.
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
        // Well inside the give-up bound, so only a history carried over from the first session can
        // fail this; the frozen playlist is fresh again to a player that starts afresh.
        harness.advanceTimeInStepsMs(player, RESTARTED_FOR_MS)

        assertWithMessage("cause: ${player.playerError?.cause}").that(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    private fun play(faults: FaultScript): SuperPlayer {
        val player = harness.buildPlayer(content = TestContent.liveHls(), faults = faults)
        player.setMediaRequest(request())
        harness.playToReady(player)
        return player
    }

    private fun request(): MediaRequest =
        MediaRequest.Builder(CONTENT).addSource(TestContent.liveHls().sourceUri).build()

    private fun segmentsFetched(player: SuperPlayer): Int =
        harness.requestedResources(player).count { it.kind == ResourceKind.MEDIA_SEGMENT }

    private companion object {
        const val CONTENT = "live-channel"

        /** The cache rule from the field: a whole path held for ten minutes. */
        const val TEN_MINUTES_S = 600L

        /**
         * Long enough that a stream which had stalled on the cached copy would have been declared
         * stuck several times over — Media3 gives up at three and a half target durations, seven
         * seconds here — and short enough to stay a unit test.
         */
        const val PLAYED_MS = 40_000L

        /** How long a restarted session is watched: under the three-target-duration give-up bound. */
        const val RESTARTED_FOR_MS = 3_000L
    }
}
