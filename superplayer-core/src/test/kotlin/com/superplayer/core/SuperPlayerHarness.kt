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

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.rules.ExternalResource

/**
 * Builds players on the seam `docs/testing.md` describes, and releases every one it built.
 *
 * The seam is four decisions that were being re-made in every test class: an auto-advancing
 * [FakeClock], a [FakeDataSource] over a [FakeDataSet] of synthetic media, Media3's
 * [DefaultMediaSourceFactory] over that, and all of it reaching the engine through
 * `SuperPlayer.Builder`'s one internal configurator. None of that is what any test is *about*, and
 * five copies of it meant five places to edit when the seam moves — during a Media3 upgrade, which
 * is exactly when a divergence between them would be most expensive to notice.
 *
 * ```kotlin
 * @get:Rule val harness = SuperPlayerHarness()
 *
 * @Test fun something() {
 *     val player = harness.buildPlayer()
 *     // ...no try/finally, and no @After
 * }
 * ```
 *
 * A rule rather than a helper function because releasing is half of what it does. A player that a
 * failing assertion left unreleased holds a playback thread and a codec for the rest of the run, and
 * `try { } finally { player.release() }` around the body of every test is the boilerplate that was
 * being written instead — one that a test building two players had to nest.
 *
 * What it deliberately does *not* do is drive playback. Tests reach `TestPlayerRunHelper` themselves,
 * because what a test waits for is part of what it asserts and hiding it here would make the
 * interesting half of a test invisible. The two exceptions are [settle] and [awaitPeriodicWork],
 * which need a clock the test has no other handle on.
 */
class SuperPlayerHarness : ExternalResource() {

    /**
     * The clock each player was built with, so that [settle] can find the right one.
     *
     * Per player rather than one for all of them: a [FakeClock] drives a playback thread's waits, and
     * a test that builds a second player before releasing the first — the profile test compares two —
     * would otherwise have two engines advancing one clock between them.
     *
     * Insertion-ordered, because [after] releases newest first and an unordered map would make that
     * claim a lie. Keys compare by identity: [SuperPlayer] declares no `equals`, so two players are
     * the same key only if they are the same object, which is the intent.
     */
    private val clocks = LinkedHashMap<SuperPlayer, FakeClock>()

    /**
     * A player as a consumer would build one, with the fakes underneath.
     *
     * [fakeDataSet] defaults to the synthetic HLS stream alone, which is what most tests want; a test
     * that switches protocols or needs a longer stream composes its own and passes it. [profile] is
     * left unset by default rather than defaulted to [PlaybackProfile.VIDEO_ON_DEMAND], so that a
     * test asserting on the *library's* default is asserting on the library's rather than on this
     * file's.
     */
    fun buildPlayer(
        profile: PlaybackProfile? = null,
        fakeDataSet: FakeDataSet = SyntheticHlsStream.addTo(FakeDataSet()),
        telemetry: TelemetryCollector? = null,
        alsoConfigureEngine: (ExoPlayer.Builder) -> Unit = {},
    ): SuperPlayer {
        // Auto-advancing: the playback thread's waits resolve as fast as the test can run them, so a
        // two-second stream does not cost two seconds.
        val clock = FakeClock(/* isAutoAdvancing= */ true)
        val fakeDataSourceFactory = FakeDataSource.Factory().setFakeDataSet(fakeDataSet)

        return register(
            clock,
            SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
                .apply { profile?.let { setProfile(it) } }
                .apply { telemetry?.let { setTelemetry(it) } }
                .setEngineConfigurator { engine ->
                    engine.setClock(clock)
                    engine.setMediaSourceFactory(DefaultMediaSourceFactory(fakeDataSourceFactory))
                    // Last, so a test that is specifically about the engine's construction — the
                    // analytics collector a telemetry test counts registrations on — can replace
                    // what the seam above installed rather than merely add to it.
                    alsoConfigureEngine(engine)
                }
                .build(),
        )
    }

    /**
     * A player built the way a consumer builds one: with the transfer chain `Builder.build()`
     * assembles, rather than a [FakeDataSource] substituted over it.
     *
     * The only fake left is the clock, which is not part of the chain and without which the playback
     * thread would wait in real seconds. Content therefore has to be something the real chain can
     * actually resolve — a `file:` URI, which `DefaultDataSource` serves — and that is what
     * [SyntheticHlsStream.writeTo] exists for.
     *
     * Kept separate from [buildPlayer] rather than offered as a flag, because it is the exception:
     * every other test wants the fake data source, and a test that reaches for this one is
     * specifically about what SuperPlayer installs underneath.
     */
    fun buildPlayerOnItsOwnTransferChain(
        profile: PlaybackProfile? = null,
        cmcdMode: CmcdMode? = null,
        telemetry: TelemetryCollector? = null,
        alsoConfigureEngine: (ExoPlayer.Builder) -> Unit = {},
    ): SuperPlayer {
        val clock = FakeClock(/* isAutoAdvancing= */ true)
        return register(
            clock,
            SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
                .apply { profile?.let { setProfile(it) } }
                .apply { cmcdMode?.let { setCmcdMode(it) } }
                .apply { telemetry?.let { setTelemetry(it) } }
                .setEngineConfigurator { engine ->
                    engine.setClock(clock)
                    // Whatever a test needs *besides* the chain — the bandwidth meter a CMCD test
                    // reads request specs through. Deliberately not a media source factory: one
                    // installed here would replace the very thing this method exists to exercise.
                    alsoConfigureEngine(engine)
                }
                .build(),
        )
    }

    /**
     * Takes [player] under this rule's care: [settle] can find its [clock], and [after] will release
     * it. Every way of building a player goes through here, so there is one answer to "did the
     * harness know about that one".
     */
    private fun register(clock: FakeClock, player: SuperPlayer): SuperPlayer {
        clocks[player] = clock
        return player
    }

    /**
     * A [PlayerPool] whose players are this harness's, so a pooled player is a player like any other
     * test's — same fake clock, same fake data source, same release at the end of the test.
     *
     * The pool's own construction seam takes a factory rather than an engine configurator, which is
     * what lets this be a composition rather than a second copy of [buildPlayer]'s four decisions.
     * A player the pool builds is registered here exactly as a directly-built one is, so [settle]
     * works on it and [after] releases it whether the pool was released or not.
     *
     * [maxSize] is passed through rather than defaulted, so a test that is about the *bound* asks
     * the device for it and a test that is about recycling pins it to a number it can reason about.
     *
     * An unset [profile] builds [PlaybackProfile.SHORT_FORM] players, because that is what
     * `PlayerPool.Builder` itself defaults to and a pool whose players were built with a different
     * profile from the one it claims would be a lie in the fixture. The two defaults are written
     * down twice, so `PlayerPoolTest.aPoolWithNoFactoryBuildsPlayersOfItsOwnProfile` builds a pool
     * through the real factory and pins them together.
     */
    fun buildPool(
        maxSize: Int? = null,
        profile: PlaybackProfile? = null,
        fakeDataSet: FakeDataSet = SyntheticHlsStream.addTo(FakeDataSet()),
        telemetry: TelemetryCollector? = null,
    ): PlayerPool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
        .apply {
            maxSize?.let { setMaxSize(it) }
            profile?.let { setProfile(it) }
        }
        .setPlayerFactory {
            // A collector measures one player, so a pool given one is a pool a test sized to build
            // one — which is what every telemetry test here does, because the interesting case is a
            // player handed out twice rather than two players.
            buildPlayer(profile ?: PlaybackProfile.SHORT_FORM, fakeDataSet, telemetry)
        }
        .build()

    /**
     * Lets the playback thread act on everything [player] has been told so far.
     *
     * Needed wherever a test asserts on the consequence of a command rather than on a playback state
     * it can wait for — a pause releasing a wake lock, say. Media3 masks a pending command's effects
     * until the playback thread has seen it, so an assertion made without this is asserting on the
     * state that was standing before the command.
     */
    fun settle(player: SuperPlayer) {
        TestPlayerRunHelper.advance(player)
            .untilPendingCommandsAreFullyHandled(clockOf(player), player.applicationLooper)
    }

    /**
     * Lets [player]'s playback thread make its periodic pass at least once more, timed on the player's
     * own fake clock.
     *
     * Needed wherever a test reads a value Media3 refreshes only on that pass rather than when it
     * changes — the buffered position above all. A change that *is* announced, loading stopping say,
     * is published carrying the buffered position as the last pass left it, and nothing is published
     * when the next pass corrects it: Media3 writes the new figure in place. An assertion made the
     * moment loading stops can therefore read the buffer as it stood one segment earlier.
     *
     * A ready engine makes the pass at least once per second of its own time, so waiting two leaves
     * room for a whole one; a buffering engine makes it more often. An idle or ended one makes none,
     * and a player in either state has no pass for this to wait for. Fake time moves only as fast as
     * the playback thread gets through its work, so the wait means the same on a loaded host as on
     * an idle one, and costs no real time.
     */
    fun awaitPeriodicWork(player: SuperPlayer) {
        val clock = clockOf(player)
        val untilMs = clock.elapsedRealtime() + 2 * READY_MAXIMUM_INTERVAL_MS
        RobolectricUtil.runMainLooperUntil { clock.elapsedRealtime() >= untilMs }
    }

    private fun clockOf(player: SuperPlayer): FakeClock =
        checkNotNull(clocks[player]) { "This harness did not build that player" }

    /**
     * Releases every player this harness built, newest first.
     *
     * Releasing one a test has already released is deliberately not guarded against: [Player.release]
     * is idempotent, and a harness that tracked which players a test had disposed of would be
     * bookkeeping in the place that exists to remove bookkeeping.
     */
    override fun after() {
        clocks.keys.toList().asReversed().forEach { it.release() }
        clocks.clear()
    }
}

// The longest a ready engine goes between two of its periodic passes, in its own milliseconds.
// Media3's own name for it, which is private to the engine.
// ref: https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImplInternal.java
private const val READY_MAXIMUM_INTERVAL_MS = 1_000L
