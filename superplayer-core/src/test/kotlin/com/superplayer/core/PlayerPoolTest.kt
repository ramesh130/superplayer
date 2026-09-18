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

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
    fun aPoolWithDrmBuildsEveryPlayerWithIt() {
        // Through the real factory for the reason the test above gives, and here that is the whole
        // claim: `setDrm` on the pool has to reach `SuperPlayer.Builder`, because what makes a pooled
        // feed's *selection* gate the secure decoder is the per-player builder call and not the
        // pool's bound (#211). A pool that only sized itself and built clear players would size a
        // protected feed correctly and then play it on the wrong decoder table.
        val configured = mutableListOf<EngineConfiguration>()
        val drm = object : EngineDrmExtension {
            override fun configureEngine(configuration: EngineConfiguration) {
                configured += configuration
            }
        }
        val pool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
            .setMaxSize(2)
            .setDrm(drm)
            .build()
        try {
            checkNotNull(pool.acquire())
            checkNotNull(pool.acquire())
            assertThat(configured).hasSize(2)
            assertThat(configured.map { it.protectedPlayback }).containsExactly(true, true)
        } finally {
            pool.release()
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

    /**
     * ADR-0011 rule 13's other half: the call on `PlayerPool.Builder` is the only way a pooled player
     * can have resilience, because the pool owns its players' construction. What a feed hands the pool
     * is one object, and every player it builds gets its slots filled from that one.
     *
     * Through the real factory, for the reason
     * [aPoolWithTelemetryGivesEveryPlayerItBuildsACollectorOfItsOwn] gives: the harness substitutes its
     * own, so this is the only place `setResilience` reaches `SuperPlayer.Builder`. No playback and no
     * synthetic stream — a slot is filled at construction, so construction is the whole of the subject.
     */
    @Test
    fun aPoolWithResilienceFillsTheSlotsOfEveryPlayerItBuildsFromTheOneObject() {
        val filled = mutableListOf<EngineConfiguration>()
        val resilience = RecordingResilience(filled)
        val pool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
            .setMaxSize(2)
            .setResilience(resilience)
            .build()
        try {
            val first = checkNotNull(pool.acquire())
            checkNotNull(pool.acquire())

            // Two players, two filled configurations — distinct ones, because a chain is built per
            // player even where an engine's components are shared (ADR-0010 rule 9).
            assertThat(filled).hasSize(2)
            assertThat(filled[0]).isNotSameInstanceAs(filled[1])
            assertThat(filled.map { it.headerRefresh }).containsExactly(resilience.refresh, resilience.refresh)
            assertThat(filled.map { it.loadErrors }).containsExactly(resilience.loadErrors, resilience.loadErrors)

            // A recycled player keeps the slots it was built with rather than being filled again:
            // recycling ends an item, and the chain below it is the player's for its whole life.
            pool.recycle(first)
            checkNotNull(pool.acquire())
            assertThat(filled).hasSize(2)
        } finally {
            pool.release()
        }
    }

    /**
     * ADR-0014 rule 3 for a pool: one output handed to the pool fills every player it builds with a
     * binding of that player's own, because a binding holds one player's surface. Through the real
     * factory, for the reason [aPoolWithResilienceFillsTheSlotsOfEveryPlayerItBuildsFromTheOneObject]
     * gives, and with no playback, because the slot is filled at construction.
     */
    @Test
    fun aPoolWithAnOutputFillsEveryPlayerItBuildsWithABindingOfItsOwn() {
        val filled = mutableListOf<EngineConfiguration>()
        val pool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
            .setMaxSize(2)
            .setOutput(RecordingOutput(filled))
            .build()
        try {
            checkNotNull(pool.acquire())
            checkNotNull(pool.acquire())

            assertThat(filled).hasSize(2)
            assertThat(filled.map { it.videoOutput }.toSet()).hasSize(2)
        } finally {
            pool.release()
        }
    }

    /**
     * ADR-0010 rules 1 and 13 for a pool: the cache a consumer opened and handed the pool is composed
     * into every player it builds, and a pool handed none gives its players none.
     *
     * Through the real factory, for the reason
     * [aPoolWithTelemetryGivesEveryPlayerItBuildsACollectorOfItsOwn] gives — and here the harness is
     * worse than absent: `PlaybackHarness.buildPool` *calls* `setCache` and then substitutes the
     * factory, so that call reads as covered while doing nothing (#188). This is the only place
     * `PlayerPool.Builder.setCache` reaches `SuperPlayer.Builder`.
     *
     * No playback: the real factory has the real transport, so there is no synthetic stream to play.
     * Both halves are visible without one. The chain is composed at construction, so the cache's slot
     * being filled is counted there; and keying by content is a property of the *item* a player
     * adopts, which `setMediaRequest` lays on without preparing anything.
     */
    @Test
    fun aPoolWithACacheComposesItIntoEveryPlayerItBuildsAndOneWithoutComposesNone() {
        val cache = RecordingContentCache()
        val pool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
            .setMaxSize(2)
            .setCache(cache)
            .build()
        try {
            val first = checkNotNull(pool.acquire())
            val second = checkNotNull(pool.acquire())

            // Two players, two chains filled from the one cache — distinct chains, because a chain is
            // built per player even where an engine's components are shared (ADR-0010 rule 9).
            assertThat(cache.filledChains()).hasSize(2)
            assertThat(cache.filledChains()[0]).isNotSameInstanceAs(cache.filledChains()[1])

            // And the other half of having a cache: each player keys the item it adopts by content
            // rather than by URL, which is the stamp the slot above reads (ADR-0010 rule 4).
            first.setMediaRequest(requestFor(FIRST_ITEM))
            second.setMediaRequest(requestFor(SECOND_ITEM))
            assertThat(ContentIdentity.of(checkNotNull(first.currentMediaItem)))
                .isEqualTo(ContentIdentity(FIRST_ITEM))
            assertThat(ContentIdentity.of(checkNotNull(second.currentMediaItem)))
                .isEqualTo(ContentIdentity(SECOND_ITEM))

            // A recycled player keeps the chain it was built with rather than being composed again:
            // recycling ends an item, and the chain below it is the player's for its whole life.
            pool.recycle(first)
            checkNotNull(pool.acquire())
            assertThat(cache.filledChains()).hasSize(2)
        } finally {
            pool.release()
        }

        // The counter shown seeing nothing, so that the count above is known to be counting: a pool
        // told about no cache builds players that key no item, which is what such a player pays.
        val uncachedPool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
            .setMaxSize(1)
            .build()
        try {
            val player = checkNotNull(uncachedPool.acquire())
            player.setMediaRequest(requestFor(FIRST_ITEM))
            assertThat(ContentIdentity.of(checkNotNull(player.currentMediaItem))).isNull()
        } finally {
            uncachedPool.release()
        }
    }

    /**
     * ADR-0016 rule 13 for a pool (#314): the stack a consumer named is the bottom of **every** player the
     * pool builds, and a pool told about none reaches it not at all (rule 14).
     *
     * Counted as what the stack was *asked for* rather than as bytes, and the shape is
     * [aPoolWithACacheComposesItIntoEveryPlayerItBuildsAndOneWithoutComposesNone]'s for
     * [aPoolWithACacheComposesItIntoEveryPlayerItBuildsAndOneWithoutComposesNone]'s reason: this test goes
     * through `PlayerPool.Builder`'s **real** player factory, so there is no harness clock under these
     * players and no synthetic stream to play. A chain asks its stack for a client once, as it is composed,
     * which is exactly the reading the rule is about — one per player built, and none where the pool was
     * told nothing.
     *
     * That a player really loads over the stack it was given is `SuperPlayerHttpStackTest`'s, and that a
     * `PreloadCoordinator`'s prefetches travel the pool's shared bottom is
     * `superplayer-preload`'s `PreloadCoordinatorHttpStackTest`.
     */
    @Test
    fun aPoolWithAnHttpStackComposesEveryPlayersChainOverItAndOneWithoutReachesItNotAtAll() {
        val stack = RecordingHttpStack()
        val pool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
            .setMaxSize(2)
            .setHttpStack(stack)
            .build()
        try {
            checkNotNull(pool.acquire())
            val second = checkNotNull(pool.acquire())
            assertThat(stack.asked).isEqualTo(2)

            // A recycled player keeps the chain it was built with rather than being composed again, as it
            // keeps its cache: recycling ends an item, and the chain below it is the player's for its life.
            pool.recycle(second)
            checkNotNull(pool.acquire())
            assertThat(stack.asked).isEqualTo(2)
        } finally {
            pool.release()
        }

        // The counter shown seeing nothing, so the count above is known to be counting: the same object,
        // handed to no pool, is asked for nothing by the players that pool builds.
        val askedBefore = stack.asked
        val unstacked = PlayerPool.Builder(ApplicationProvider.getApplicationContext()).setMaxSize(1).build()
        try {
            checkNotNull(unstacked.acquire())
            assertThat(stack.asked).isEqualTo(askedBefore)
        } finally {
            unstacked.release()
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

    /**
     * A resilience that fills both slots with the same two objects every time, and records the
     * configurations it filled.
     *
     * Deliberately the same objects rather than one pair per player: that is what a real one will do
     * with a token refresh, and it is what makes the shared-instance requirement in
     * [PlaybackResilience]'s KDoc visible here rather than only stated there.
     */
    private class RecordingOutput(private val filled: MutableList<EngineConfiguration>) : EngineOutputExtension {
        override fun configureEngine(configuration: EngineConfiguration) {
            configuration.videoOutput = object : VideoOutputBinding {
                override fun onSurfaceChanged(surface: android.view.Surface?) = Unit

                override fun onVideoFrameRate(framesPerSecond: Float?) = Unit

                override fun release() = Unit
            }
            filled += configuration
        }
    }

    private class RecordingResilience(private val filled: MutableList<EngineConfiguration>) : EngineResilienceExtension {

        val refresh: HeaderRefreshLayer = HeaderRefreshLayer { upstream -> upstream }
        val loadErrors: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy()

        override fun configureEngine(configuration: EngineConfiguration) {
            configuration.headerRefresh = refresh
            configuration.loadErrors = loadErrors
            filled += configuration
        }
    }

    /**
     * A cache that caches nothing: it fills the slot, answers no read, and records the chain it was
     * composed over — one per player, which is what a pool's use of it is measured in.
     *
     * The layer is the cache's own and is handed to every player, the way a feed's one cache is: the
     * count below is of chains it filled, not of caches, because there is only ever the one.
     */

    /**
     * An [HttpStack] that answers Media3's own client and counts how many chains asked it for one.
     *
     * A stack is asked once per chain composed, which is once per player, so the count *is* the number of
     * players whose bottom this stack resolved. Media3's own factory rather than a refusing one because a
     * player here is built and never prepared: nothing opens a data source, and a stand-in that threw would
     * be a trap for the next test that plays one.
     */
    private class RecordingHttpStack : HttpStack() {

        var asked = 0
            private set

        override fun httpFactory(context: Context): DataSource.Factory {
            asked++
            return DefaultHttpDataSource.Factory()
        }
    }

    private class RecordingContentCache : ContentCache(RecordingCacheLayer()) {

        fun filledChains(): List<DataSource.Factory> = (layer as RecordingCacheLayer).filled.toList()
    }

    private class RecordingCacheLayer : CacheLayer {

        val filled = mutableListOf<DataSource.Factory>()

        override fun over(upstream: DataSource.Factory): DataSource.Factory {
            filled += upstream
            return upstream
        }
    }

    private companion object {
        const val FIRST_ITEM = "feed:first"
        const val SECOND_ITEM = "feed:second"

        /** Far enough in that a resume position would be unmistakably not the beginning. */
        const val WATCHED_FOR_MS = 500L
    }
}
