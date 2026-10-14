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

package com.superplayer.preload

import android.app.Application
import android.content.ComponentCallbacks2
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlayerPool
import com.superplayer.core.PreloadDepth
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryCollector
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Decoder warm-up (#159): a feed of described video rows under the harness — video, because a decoder
 * is held by a video renderer and the synthetic HLS stream is audio — on a device each test states,
 * because the bound on warm decoders is the device's.
 *
 * `SHORT_FORM` decides two rows ahead and one behind at [PreloadDepth.DecoderWarmed], so at row 1 the
 * window is rows 2, 3 and 0. A decoder is counted as the harness counts it: a video renderer the engine
 * has enabled (`PlaybackHarness.videoDecodersHeld`). Every count is taken after a span in which the
 * engine could have broken the bound, so an assertion of an exact number is also one of "never more".
 */
@RunWith(AndroidJUnit4::class)
class DecoderWarmupTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val video = TestContent.video()
    private val rows = (0 until ROWS).map { MediaRequest.Builder("feed:video$it").addSource(video.sourceUri).build() }
    private val coordinators = mutableListOf<PreloadCoordinator>()
    private val pools = mutableListOf<PlayerPool>()

    @After
    fun releaseTheScreen() {
        coordinators.forEach { it.release() }
        pools.forEach { it.release() }
    }

    @Test
    fun atADeclaredLimitOfOneNothingIsWarmBesideThePlayingRow() = assertWarmDecodersAt(limit = 1, warm = 0)

    @Test
    fun atADeclaredLimitOfTwoOneIsWarm() = assertWarmDecodersAt(limit = 2, warm = 1)

    @Test
    fun atADeclaredLimitOfThreeTwoAreWarm() = assertWarmDecodersAt(limit = 3, warm = 2)

    /** Past the window: three rows are in it, so three are warm however many more the device affords. */
    @Test
    fun atADeclaredLimitOfSixTheWindowBindsInstead() = assertWarmDecodersAt(limit = 6, warm = 3)

    /**
     * The feed has had every player the device affords out at once and hands back all but the row it
     * plays; the coordinator warms idle players on the window, never more than the limit less that row.
     */
    private fun assertWarmDecodersAt(limit: Int, warm: Int) {
        val (pool, preload) = feedScreen(decoderLimit = limit)
        assertThat(pool.maxSize).isEqualTo(limit)
        val playing = playRowOneWithTheRestHandedBack(pool, preload)

        harness.advanceUntil(playing, "$warm warm decoders") { harness.videoDecodersHeld(pool) - PLAYING_ROW >= warm }
        harness.advanceTimeInStepsMs(playing, SPAN_MS)

        assertWithMessage("decoders held beside the playing row at a limit of $limit")
            .that(harness.videoDecodersHeld(pool) - PLAYING_ROW).isEqualTo(warm)
        assertWithMessage("what the coordinator reports as warm").that(preload.warmDecoderCount).isEqualTo(warm)
    }

    /**
     * Two rows playing — a grid, say, or a feed with a picture-in-picture row — hold two of the device's
     * four decoders, so two are left to warm, not three: the bound is the limit less every playing row.
     */
    @Test
    fun everyPlayingRowCountsAgainstTheLimit() {
        val (pool, preload) = feedScreen(decoderLimit = 4)
        preload.setItems(rows)
        preload.setScrollPosition(1)
        val players = (0 until pool.maxSize).map { checkNotNull(pool.acquire()) }
        val (first, second) = players
        players.drop(2).forEach { pool.recycle(it) }
        first.setMediaRequest(rows[1])
        harness.playToReady(first)
        // A row outside the window, so it is not one the coordinator would warm anyway.
        second.setMediaRequest(rows[ROWS - 1])
        harness.playToReady(second)

        harness.advanceUntil(first, "two warm decoders") { harness.videoDecodersHeld(pool) >= 4 }
        harness.advanceTimeInStepsMs(first, SPAN_MS)

        assertThat(harness.videoDecodersHeld(pool)).isEqualTo(pool.maxSize)
    }

    /**
     * Robolectric's own device: no decoder declared. The pool reads that as a pool of one rather than as
     * "unknown refuses nothing", because a decoder past the device's limit throws — and warm-up
     * inherits the pool's reading, so nothing is warm beside the row that plays.
     */
    @Test
    fun anEmptyDecoderTableWarmsNothingBesideThePlayingRow() {
        val (pool, preload) = feedScreen(decoderLimit = null)
        assertThat(pool.maxSize).isEqualTo(1)
        val playing = playRowOneWithTheRestHandedBack(pool, preload)
        harness.advanceTimeInStepsMs(playing, SPAN_MS)

        assertThat(harness.videoDecodersHeld(pool)).isEqualTo(PLAYING_ROW)
    }

    /** Released with the coordinator: the playing row keeps its decoder, the warm ones let theirs go. */
    @Test
    fun releasingTheCoordinatorReleasesItsWarmDecoders() {
        val (pool, preload, playing) = threeWarmBesideRowOne()

        preload.release()
        harness.advanceTimeInStepsMs(playing, SPAN_MS)

        assertThat(harness.videoDecodersHeld(pool)).isEqualTo(PLAYING_ROW)
    }

    /**
     * Released on a memory trim (ADR-0010 rule 11), and warmed again once the feed next moves. The level
     * is `TRIM_MEMORY_UI_HIDDEN`, and that is deliberate: from API 34 it is one of the only two levels the
     * platform still delivers, and the decoders it releases were warm for a screen nobody can see.
     */
    @Test
    fun aMemoryTrimLetsEveryWarmDecoderGoUntilTheFeedMoves() {
        val (pool, preload, playing) = threeWarmBesideRowOne()

        ApplicationProvider.getApplicationContext<Application>().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        harness.advanceTimeInStepsMs(playing, SPAN_MS)
        assertWithMessage("decoders held after the trim").that(harness.videoDecodersHeld(pool)).isEqualTo(PLAYING_ROW)
        assertWithMessage("reported warm after the trim").that(preload.warmDecoderCount).isEqualTo(0)

        preload.setScrollPosition(1)
        harness.advanceUntil(playing, "three warm decoders again") { harness.videoDecodersHeld(pool) == PLAYING_ROW + 3 }
    }

    /** Released with its row: a feed that no longer holds a row lets the decoder warm on it go. */
    @Test
    fun aRowThatLeavesTheFeedReleasesItsWarmDecoder() {
        val (pool, preload, playing) = threeWarmBesideRowOne()

        // Rows 0 and 1 only: the window at row 1 is row 0, and rows 2 and 3 are gone.
        preload.setItems(rows.take(2))
        harness.advanceTimeInStepsMs(playing, SPAN_MS)

        assertThat(harness.videoDecodersHeld(pool)).isEqualTo(PLAYING_ROW + 1)
    }

    /**
     * Released as its row scrolls out of the window: at row 6 the window is row 7 ahead and row 5 behind,
     * two rows, so of the three decoders warm at row 1 one is let go and none is left on a row passed.
     */
    @Test
    fun aRowThatScrollsOutOfTheWindowReleasesItsWarmDecoder() {
        val (pool, preload, playing) = threeWarmBesideRowOne()

        preload.setScrollPosition(ROWS - 2)
        harness.advanceTimeInStepsMs(playing, SPAN_MS)

        assertThat(harness.videoDecodersHeld(pool)).isEqualTo(PLAYING_ROW + 2)
    }

    /**
     * Released before the player reaches a caller who wants it for another row: at row 1, the only idle
     * player holds row 2 warm, and a feed that takes it without moving is handed a player holding
     * nothing — no frame of row 2 can reach the view it is bound to.
     */
    @Test
    fun aWarmPlayerHandedOutForAnotherRowLetsItsDecoderGoFirst() {
        val (pool, preload) = feedScreen(decoderLimit = 2)
        val playing = playRowOneWithTheRestHandedBack(pool, preload)
        harness.advanceUntil(playing, "one warm decoder") { harness.videoDecodersHeld(pool) == PLAYING_ROW + 1 }

        checkNotNull(pool.acquire())
        harness.advanceTimeInStepsMs(playing, SPAN_MS)

        assertThat(harness.videoDecodersHeld(pool)).isEqualTo(PLAYING_ROW)
    }

    /**
     * The hand-over: the feed moves to row 2 and asks for a player, and the pool hands it the one holding
     * row 2 warm; that player adopts the row without giving its decoder up.
     */
    @Test
    fun theRowAheadIsHandedThePlayerHoldingItWarm() {
        val (pool, preload) = feedScreen(decoderLimit = 2)
        val playing = playRowOneWithTheRestHandedBack(pool, preload)
        harness.advanceUntil(playing, "one warm decoder") { harness.videoDecodersHeld(pool) == PLAYING_ROW + 1 }
        // The pool's only other player, handed back and warmed on the nearest row ahead.
        val warmOnRowTwo = checkNotNull(pool.acquire()).also { pool.recycle(it) }
        harness.advanceUntil(playing, "row 2 warm again") { harness.videoDecodersHeld(pool) == PLAYING_ROW + 1 }

        preload.setScrollPosition(2)
        val next = checkNotNull(pool.acquire())
        assertThat(next).isSameInstanceAs(warmOnRowTwo)
        next.setMediaRequest(rows[2])
        harness.settle(next)

        assertWithMessage("decoders held once row 2 adopted its warm player")
            .that(harness.videoDecodersHeld(pool)).isEqualTo(PLAYING_ROW + 1)
        harness.attachVideoOutput(next)
        pool.recycle(playing)
        harness.playToReady(next)
    }

    /**
     * A feed that plays a row on its own player rather than the one holding it warm still plays it: the
     * warm player lets the source go first, and is then free to warm row 3, the nearest row ahead of
     * row 2 — one decoder playing, one warm, never both players on row 2.
     */
    @Test
    fun aRowHeldWarmStillPlaysOnAnotherPlayer() {
        val (pool, preload) = feedScreen(decoderLimit = 2)
        val playing = playRowOneWithTheRestHandedBack(pool, preload)
        harness.advanceUntil(playing, "one warm decoder") { harness.videoDecodersHeld(pool) == PLAYING_ROW + 1 }

        preload.setScrollPosition(2)
        playing.setMediaRequest(rows[2])
        harness.playToReady(playing)
        harness.advanceTimeInStepsMs(playing, SPAN_MS)

        assertThat(playing.playerError).isNull()
        assertThat(harness.videoDecodersHeld(pool)).isEqualTo(PLAYING_ROW + 1)
    }

    /**
     * The measurement #159 asks for, and what the harness can and cannot say about it: row 1's time to
     * first frame, from declared intent, handed the idle player that held it — warm at
     * [PreloadDepth.DecoderWarmed] against merely loaded at [PreloadDepth.Loaded], everything else equal.
     *
     * **The harness cannot show a warm decoder starting sooner, and this pins that it is no later.** Both
     * arms measure the same number (150 ms when this was written). What warm-up removes is codec
     * initialisation — `MediaCodec` allocation and configuration on a device, tens to hundreds of
     * milliseconds of wall time — and under the harness there is none: the video renderer is Media3's
     * fake, which initialises no codec, and the harness moves its clock only once the engine has nothing
     * left to do at the current time, so enabling a renderer and preparing a period cost no playback time
     * in either arm. The bytes are already equal, since both arms loaded the same range. The saving is a
     * device measurement, and it belongs with `devicelab`, not here.
     */
    @Test
    fun aWarmedRowReachesItsFirstFrameNoLaterThanAnUnwarmedPreloadedOne() {
        val warm = timeToFirstFrameOfRowOne(PreloadDepth.DecoderWarmed(PRELOADED_MS))
        val loaded = timeToFirstFrameOfRowOne(PreloadDepth.Loaded(PRELOADED_MS))

        assertWithMessage("warm $warm ms, loaded $loaded ms").that(warm).isAtMost(loaded)
    }

    private fun timeToFirstFrameOfRowOne(depth: PreloadDepth): Long {
        val events = CopyOnWriteArrayList<TelemetryEvent>()
        val (pool, preload) = feedScreen(
            decoderLimit = 2,
            telemetry = { QoeCollector(TelemetrySink { events += it }) },
            policy = depthPolicy(depth),
            network = NetworkProfile.STABLE_WIFI,
        )
        val first = checkNotNull(pool.acquire())
        pool.recycle(checkNotNull(pool.acquire()))
        first.setMediaRequest(rows[0])
        harness.playToReady(first)
        harness.advanceTimeInStepsMs(first, SPAN_MS)

        preload.setScrollPosition(1)
        val second = checkNotNull(pool.acquire())
        harness.attachVideoOutput(second)
        second.declarePlaybackIntent()
        second.setMediaRequest(rows[1])
        harness.playToReady(second)
        val contentId = rows[1].contentId
        harness.advanceUntil(second, "row 1's first frame") {
            events.any { it is TelemetryEvent.FirstFrameRendered && it.contentId == contentId }
        }
        return events.filterIsInstance<TelemetryEvent.FirstFrameRendered>().single { it.contentId == contentId }.timeToFirstFrameMs
    }

    /** A screen on a device of four decoders, playing row 1 with its three other players warm on the window. */
    private fun threeWarmBesideRowOne(): Triple<PlayerPool, PreloadCoordinator, SuperPlayer> {
        val (pool, preload) = feedScreen(decoderLimit = 4)
        val playing = playRowOneWithTheRestHandedBack(pool, preload)
        harness.advanceUntil(playing, "three warm decoders") { harness.videoDecodersHeld(pool) == PLAYING_ROW + 3 }
        return Triple(pool, preload, playing)
    }

    /** The feed at row 1 on one player, having had [PlayerPool.maxSize] out and handed the rest back. */
    private fun playRowOneWithTheRestHandedBack(pool: PlayerPool, preload: PreloadCoordinator): SuperPlayer {
        preload.setItems(rows)
        preload.setScrollPosition(1)
        val players = (0 until pool.maxSize).map { checkNotNull(pool.acquire()) }
        val playing = players.first()
        players.drop(1).forEach { pool.recycle(it) }
        playing.setMediaRequest(rows[1])
        harness.playToReady(playing)
        return playing
    }

    /** `SHORT_FORM`'s own decision with its prefetch depth replaced. */
    private fun depthPolicy(depth: PreloadDepth): PlaybackPolicy {
        val base = PlaybackPolicy.forProfile(PlaybackProfile.SHORT_FORM)
        return PlaybackPolicy { conditions ->
            base.decide(conditions).let { it.copy(preload = it.preload.copy(depth = depth)) }
        }
    }

    /**
     * A pool on a device declaring [decoderLimit] concurrent AVC instances, or none at all, and a
     * coordinator attached to it. Declared before the pool reads the device, and before any player.
     */
    private fun feedScreen(
        decoderLimit: Int?,
        telemetry: () -> TelemetryCollector? = { null },
        policy: PlaybackPolicy? = null,
        network: NetworkProfile? = null,
    ): Pair<PlayerPool, PreloadCoordinator> {
        decoderLimit?.let { DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = it) }
        DeviceStatement.declareAppHeap(megabytes = 2048)
        val pool = harness.buildPool(
            profile = PlaybackProfile.SHORT_FORM,
            content = video,
            telemetry = telemetry,
            network = network?.trace,
            policy = policy,
        ).also { pools += it }
        return pool to PreloadCoordinator.Builder(pool).build().also { coordinators += it }
    }

    private companion object {
        const val ROWS = 8

        /** The playing row's own decoder, which every count here includes. */
        const val PLAYING_ROW = 1

        /** A span for the engine to act in, and for a bound to be broken in if it would be. */
        const val SPAN_MS = 4_000L

        /** `SHORT_FORM`'s `bufferForPlaybackMs`, the range its decision loads. */
        const val PRELOADED_MS = 1_000
    }
}
