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

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [PlayerPool] driven the way a feed drives it, on the seam `docs/testing.md` describes: pooled
 * players are the harness's players, playback is real playback of a synthetic stream, and every
 * assertion is made through the public API.
 *
 * What is being pinned is the two promises a pool makes that a plain list of players does not — that
 * the number of live players has a ceiling however long the scroll is, and that a player handed out
 * a second time carries nothing of the item it showed the first time.
 *
 * The bound itself is `PlayerPoolCapacityTest`'s subject. Every pool here asks for a size it can
 * reason about, because a test about recycling should not also be a test about what device it is
 * running on.
 */
@RunWith(AndroidJUnit4::class)
class PlayerPoolTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /**
     * Surfaces created by a test outlive the player that held one, and a `SurfaceTexture` left
     * undisposed logs a finalizer warning that survives into unrelated tests' output.
     */
    private val surfaces = mutableListOf<Pair<Surface, SurfaceTexture>>()

    @After
    fun releaseSurfaces() {
        surfaces.forEach { (surface, texture) ->
            surface.release()
            texture.release()
        }
        surfaces.clear()
    }

    @Before
    fun declareTheDeviceThisRunsOn() {
        // Every pool below asks for a size it can reason about, and [PlayerPool.Builder.setMaxSize]
        // is a ceiling on top of the device's rather than a way past it — so on Robolectric's
        // default device, which reports no codecs and no memory, every one of them would silently be
        // a pool of one. Stating a capable device is what makes these tests about recycling.
        // What a pool does on a device that reports less is `PlayerPoolCapacityTest`'s subject.
        TestDevice.declareCapableDevice()
    }

    @Test
    fun aPoolWithNoFactoryBuildsPlayersOfItsOwnProfile() {
        // The one test that goes through `PlayerPool.Builder`'s real player factory rather than the
        // harness's. Everything else here substitutes a factory to get the fake clock and fake data
        // source, which means the production path — `SuperPlayer.Builder(context).setProfile(..)` —
        // would otherwise have no coverage at all: a pool that forgot `setProfile`, or built against
        // the wrong context, would pass the whole suite.
        //
        // Nothing is prepared, so no fake media is needed: what is being checked is what the pool
        // built, which is observable the moment it exists.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val defaultPool = PlayerPool.Builder(context).build()
        try {
            // SHORT_FORM rather than SuperPlayer.Builder's VIDEO_ON_DEMAND, and deliberately: a pool
            // is for feeds, and a pool of on-demand players would have every one of them buffering
            // for a long watch that a scroll is about to end.
            assertThat(checkNotNull(defaultPool.acquire()).profile)
                .isEqualTo(PlaybackProfile.SHORT_FORM)
        } finally {
            defaultPool.release()
        }

        val chosenPool = PlayerPool.Builder(context)
            .setProfile(PlaybackProfile.DATA_SAVER)
            .build()
        try {
            assertThat(checkNotNull(chosenPool.acquire()).profile)
                .isEqualTo(PlaybackProfile.DATA_SAVER)
        } finally {
            chosenPool.release()
        }
    }

    @Test
    fun aPoolWithTelemetryGivesEveryPlayerItBuildsACollectorOfItsOwn() {
        // Through the real factory, for the reason the test above gives: the harness substitutes its
        // own, so this is the only place `setTelemetry` reaches `SuperPlayer.Builder`.
        val collectors = mutableListOf<RecordingTelemetry>()
        val pool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
            .setMaxSize(2)
            .setTelemetry { RecordingTelemetry(TelemetrySink { }).also { collectors += it } }
            .build()
        try {
            val first = checkNotNull(pool.acquire())
            checkNotNull(pool.acquire())
            assertThat(collectors).hasSize(2)
            assertThat(collectors.map { it.attachCount }).containsExactly(1, 1)

            // A recycled player keeps its collector rather than being handed a new one.
            pool.recycle(first)
            checkNotNull(pool.acquire())
            assertThat(collectors).hasSize(2)
            assertThat(collectors.map { it.detachCount }).containsExactly(0, 0)
        } finally {
            pool.release()
        }
    }

    @Test
    fun aPoolBuildsNoPlayerUntilOneIsAskedFor() {
        val pool = harness.buildPool(maxSize = 3)

        // A grid that is three items wide but scrolled to a section with none costs nothing. The
        // bound is a ceiling on what may exist, not an instruction to build that many.
        assertThat(pool.size).isEqualTo(0)
        assertThat(pool.inUseCount).isEqualTo(0)
    }

    @Test
    fun thePoolNeverHandsOutMorePlayersThanItsBound() {
        val pool = harness.buildPool(maxSize = 2)

        val first = pool.acquire()
        val second = pool.acquire()

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(first).isNotSameInstanceAs(second)
        // The third item on screen gets null rather than a third decoder. Null is the pool saying
        // "show the artwork", which is the answer a feed has to have anyway for everything off
        // screen — see PlayerPool's own documentation for why this is not an exception.
        assertThat(pool.acquire()).isNull()
        assertThat(pool.size).isEqualTo(2)
    }

    @Test
    fun aLongScrollBuildsNoMorePlayersThanTheBound() {
        val pool = harness.buildPool(maxSize = 2)

        // Two hundred items through a two-player pool, acquired and recycled as they pass, which is
        // what a feed does. The claim is the one the issue makes: no unbounded growth.
        repeat(200) {
            val player = checkNotNull(pool.acquire()) { "the pool ran dry with nothing held" }
            player.setMediaRequest(requestFor("feed:item-$it"))
            player.prepare()
            pool.recycle(player)
        }

        assertThat(pool.size).isAtMost(2)
        assertThat(pool.inUseCount).isEqualTo(0)
    }

    @Test
    fun aRecycledPlayerIsHandedOutAgainRatherThanReplaced() {
        val pool = harness.buildPool(maxSize = 2)

        val first = checkNotNull(pool.acquire())
        pool.recycle(first)

        // The whole economy of the thing: the second item on screen costs no second decoder if the
        // first has left. A pool that built a fresh player here would be a pool with a cap.
        assertThat(pool.acquire()).isSameInstanceAs(first)
        assertThat(pool.size).isEqualTo(1)
    }

    @Test
    fun aRecycledPlayerDetachesFromTheSurfaceItWasRenderingInto() {
        val pool = harness.buildPool(maxSize = 1)
        val player = checkNotNull(pool.acquire())
        player.setVideoSurface(newSurface())
        harness.settle(player)

        // A surface with no declared dimensions reports UNKNOWN rather than ZERO, so the two states
        // are distinguishable through the facade and this assertion is not vacuous.
        assertThat(player.surfaceSize).isNotEqualTo(Size.ZERO)

        pool.recycle(player)
        harness.settle(player)

        // ZERO is Media3's "there is no surface". This is the stale-frame defect stated as an
        // assertion: a player still holding the outgoing item's surface renders that item's last
        // decoded frame into a view the next item now owns.
        assertThat(player.surfaceSize).isEqualTo(Size.ZERO)
    }

    @Test
    fun aRecycledPlayerCarriesNoContentOrPlaybackStateIntoTheNextItem() {
        val pool = harness.buildPool(maxSize = 1)
        val player = checkNotNull(pool.acquire())

        player.setMediaRequest(requestFor(FIRST_ITEM))
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.playbackParameters = PlaybackParameters(/* speed= */ 2f)
        player.volume = 0.25f
        harness.settle(player)

        pool.recycle(player)
        harness.settle(player)

        // Everything the previous item touched. A feed reuses a view holder as well as a player, and
        // an item that inherited the last one's playback speed or muted volume would be a defect a
        // viewer sees long before a developer does.
        assertThat(pool.acquire()).isSameInstanceAs(player)
        assertThat(player.currentMediaItem).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
        assertThat(player.playbackParameters).isEqualTo(PlaybackParameters.DEFAULT)
        assertThat(player.volume).isEqualTo(1f)
    }

    @Test
    fun aRecycledPlayerGoesBackToTheTrackSelectionItsProfileChose() {
        val pool = harness.buildPool(maxSize = 1, profile = PlaybackProfile.SHORT_FORM)
        val player = checkNotNull(pool.acquire())
        val chosenByTheProfile = player.trackSelectionParameters

        // One item deciding something for itself — a forced language, a quality cap while the row is
        // small. Media3's own API, forwarded straight through the facade, which is exactly why the
        // pool has to put it back: nothing about this call knows it is happening to a shared player.
        player.trackSelectionParameters = chosenByTheProfile.buildUpon()
            .setMaxVideoSize(/* maxVideoWidth= */ 1, /* maxVideoHeight= */ 1)
            .build()
        assertThat(player.trackSelectionParameters).isNotEqualTo(chosenByTheProfile)

        pool.recycle(player)
        harness.settle(player)

        // Back to what the profile decided when this player was built — not to Media3's default,
        // which would silently undo the profile for every item after the first.
        assertThat(checkNotNull(pool.acquire()).trackSelectionParameters)
            .isEqualTo(chosenByTheProfile)
    }

    @Test
    fun aRecycledPlayerGoesBackToHandlingAudioFocus() {
        val pool = harness.buildPool(maxSize = 1)
        val player = checkNotNull(pool.acquire())
        val builtWith = player.audioAttributes

        // What a grid's silent rows have to do: exactly one player among several that play at once
        // may hold audio focus, so the rest say so with Media3's own API. See PlayerPool's own
        // documentation for why that is the shape rather than a workaround.
        player.setAudioAttributes(
            AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus= */ false,
        )
        assertThat(player.audioAttributes).isNotEqualTo(builtWith)

        pool.recycle(player)
        harness.settle(player)

        // The next item gets a player that ducks for a navigation prompt and pauses for a phone
        // call, whatever the last item decided for itself. The `handleAudioFocus` half is not
        // readable through any API — see `LifecycleBinding.kt` — so the attributes are what a test
        // can see of this, and `SuperPlayerLifecycleTest` is where focus behaviour itself is pinned.
        assertThat(checkNotNull(pool.acquire()).audioAttributes).isEqualTo(builtWith)
    }

    @Test
    fun aRecycledPlayerRemembersNoPositionFromTheItemItWasShowing() {
        val pool = harness.buildPool(maxSize = 1)
        val player = checkNotNull(pool.acquire())

        player.setMediaRequest(requestFor(FIRST_ITEM))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
        player.seekTo(WATCHED_FOR_MS)
        // Moving on is what records where the outgoing content got to — the same bookkeeping a feed
        // does when one player shows a second item. Without this the map is empty and the assertion
        // below would hold whatever reuse did with it.
        player.setMediaRequest(requestFor(SECOND_ITEM))
        harness.settle(player)

        pool.recycle(player)

        val reused = checkNotNull(pool.acquire())
        reused.setMediaRequest(
            MediaRequest.Builder(FIRST_ITEM)
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
                .build(),
        )

        // Deliberate, and the one piece of reset behaviour that costs something. Without the recycle
        // this resumes at WATCHED_FOR_MS — `SuperPlayerMediaRequestTest` is where that is pinned.
        // Carrying it across reuse would make resume work only when an item happened to land on the
        // player that last showed it, which a viewer experiences as resume that works sometimes: a
        // worse promise than none. `SuperPlayer.resetForReuse` says what a feed that wants it does.
        assertThat(reused.currentPosition).isEqualTo(0L)
    }

    @Test
    fun aRecycledPlayerStopsCallingTheListenerTheLastItemRegistered() {
        val pool = harness.buildPool(maxSize = 1)
        val player = checkNotNull(pool.acquire())

        var callbacks = 0
        val listener = object : Player.Listener {
            override fun onEvents(source: Player, events: Player.Events) {
                callbacks++
            }
        }
        player.addListener(listener)
        player.setMediaRequest(requestFor(FIRST_ITEM))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
        assertThat(callbacks).isGreaterThan(0)

        pool.recycle(player)
        val callbacksAtRecycle = callbacks

        val reused = checkNotNull(pool.acquire())
        reused.setMediaRequest(requestFor(SECOND_ITEM))
        reused.prepare()
        TestPlayerRunHelper.advance(reused).untilState(Player.STATE_READY)

        // A view holder that has been recycled is showing something else, and being told about this
        // item is the least of it: still being registered means still being reachable from a player
        // that outlives every holder the feed has ever built, which is a leak the size of the scroll.
        assertThat(callbacks).isEqualTo(callbacksAtRecycle)
    }

    @Test
    fun thePoolRefusesAPlayerItDidNotHandOut() {
        val pool = harness.buildPool(maxSize = 1)
        val stranger = harness.buildPlayer()

        // Adopting it would mean taking on the lifetime of a player somebody else releases, and the
        // crash that produces surfaces in the next item's playback rather than here.
        assertThrows(IllegalArgumentException::class.java) { pool.recycle(stranger) }

        val ours = checkNotNull(pool.acquire())
        pool.recycle(ours)
        // Recycling twice is the same mistake seen from the other side: the second call would put a
        // player into the idle list that a caller may already have re-acquired.
        assertThrows(IllegalArgumentException::class.java) { pool.recycle(ours) }
    }

    @Test
    fun releasingThePoolReleasesEveryPlayerItBuilt() {
        val pool = harness.buildPool(maxSize = 2)
        val idle = checkNotNull(pool.acquire())
        // Deliberately not recycled. A screen going away leaves its players out, and a pool that
        // only released the ones handed back would leave a decoder and a playback thread alive for
        // exactly the screen that has gone.
        val stillInUse = checkNotNull(pool.acquire())
        pool.recycle(idle)

        pool.release()

        assertThat(deliversEvents(idle)).isFalse()
        assertThat(deliversEvents(stillInUse)).isFalse()
        assertThat(pool.size).isEqualTo(0)
        assertThat(pool.inUseCount).isEqualTo(0)
    }

    @Test
    fun aPlayerThePoolHasNotReleasedStillDeliversEvents() {
        // The control for the test above, and the reason it is not vacuous. [deliversEvents] would
        // answer false for a player that was merely stopped, or for a sequence that produced no
        // events at all, and either would make that test pass while release did nothing.
        assertThat(deliversEvents(harness.buildPlayer())).isTrue()
    }

    @Test
    fun aReleasedPoolIsReleasedOnceAndUsedNoFurther() {
        val pool = harness.buildPool(maxSize = 1)
        val player = checkNotNull(pool.acquire())

        pool.release()
        // A screen that releases in both a disposal effect and onDestroy is not a bug.
        pool.release()

        assertThrows(IllegalStateException::class.java) { pool.acquire() }
        // Not a throw. A caller still holding a player when the screen went away hands it back on
        // its own schedule — a RecyclerView dispatches detach for still-attached holders during that
        // same teardown — and crashing an app for doing what this class tells it to do would be a
        // worse answer than doing nothing to a player that is already released.
        pool.recycle(player)
    }

    /**
     * Whether [player] still calls its listeners — which is how a released engine is told apart from
     * a merely stopped one, through the public API.
     *
     * Media3 releases a player's listener set along with the engine, and a released set is inert:
     * callbacks registered afterwards are never invoked and events are never dispatched. A stopped
     * player, an idle one and a freshly reset one all still deliver, so this distinguishes exactly
     * the thing being asserted.
     *
     * The wake lock was the obvious alternative and it is the wrong tool here. A player's lock is
     * released when playback *ends* as well as when the engine does, and the synthetic stream ends
     * inside the window a test would watch — so the assertion passed against a pool that released
     * nothing. It was also flaky in its own right: polling `isHeld` from the main looper races
     * Robolectric's own bookkeeping on the playback thread, which surfaced as a
     * `ConcurrentModificationException` inside `ShadowPowerManager`.
     *
     * Nothing here waits on the playback thread, deliberately — a released player's thread is gone,
     * so anything that waited for it would hang rather than answer. Idling the main looper is enough
     * because the events this provokes are dispatched there.
     */
    private fun deliversEvents(player: SuperPlayer): Boolean {
        var delivered = false
        player.addListener(object : Player.Listener {
            override fun onEvents(source: Player, events: Player.Events) {
                delivered = true
            }
        })

        player.setMediaRequest(requestFor(SECOND_ITEM))
        player.prepare()
        shadowOf(Looper.getMainLooper()).idle()

        return delivered
    }

    private fun requestFor(contentId: String): MediaRequest =
        MediaRequest.Builder(contentId)
            .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
            .build()

    private fun newSurface(): Surface {
        val texture = SurfaceTexture(/* texName= */ 0)
        val surface = Surface(texture)
        surfaces += surface to texture
        return surface
    }

    private companion object {
        const val FIRST_ITEM = "feed:first"
        const val SECOND_ITEM = "feed:second"

        /** Far enough in that a resume position would be unmistakably not the beginning. */
        const val WATCHED_FOR_MS = 500L
    }
}
