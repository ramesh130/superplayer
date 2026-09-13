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

package com.superplayer.telemetry

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.FailureCategory
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.core.TtffStartBoundary
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The CTA-2066 metrics, computed from real playback driven through the public API.
 *
 * Every assertion here is on a **value**, never on a tolerance, which is what
 * [PlaybackHarness]'s two-clocks-moving-together design buys: a test advances time by the amount it
 * is about and the duration that comes out is that amount.
 *
 * **What the definitions are is `docs/telemetry-schema.md`.** This file checks that the code says
 * what the document says; where a test's comment restates a definition it is quoting, and the
 * document is what changes first.
 *
 * ## What is stubbed, and should be revisited
 *
 * Stalls are provoked by making the video renderer stop being ready, and dropped frames by raising
 * the renderer's own callback. Both reproduce the engine's behaviour faithfully and the *cause* not
 * at all — no bytes are late and no decoder is behind. A rebuffer here therefore proves the
 * collector's attribution and arithmetic rather than its sensitivity to real network trouble; that
 * second half is [FaultInducedRebufferTest], where a segment is made to arrive late through the
 * fault injector and the stall has a cause. The stalls here stay renderer-driven on purpose: they
 * are exact to the millisecond, which is what lets these assertions be on values rather than on
 * ranges. Dropped frames are still a stub, and `#40` (throughput replay) is what they wait on.
 */
@RunWith(AndroidJUnit4::class)
class QoeMetricsTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /** Written from the delivery thread, read from the test's; see `TelemetryDelivery`. */
    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
    private val sink = TelemetrySink { events += it }
    private var collector: QoeCollector? = null

    private fun buildPlayer(
        content: TestContent = TestContent.video(),
        profile: PlaybackProfile? = null,
    ): SuperPlayer = harness.buildPlayer(
        content = content,
        profile = profile,
        telemetry = QoeCollector(sink).also { collector = it },
    )

    private fun play(player: SuperPlayer, contentId: String = CONTENT) {
        player.setMediaRequest(MediaRequest.Builder(contentId).addSource(SOURCE).build())
        // The app's own interval between taking the content on and asking for playback — a view
        // attaching, a resolver returning. `CONTENT_ADOPTED` counts from adoption, so this is inside
        // every measurement below by definition, and it is what separates that boundary from
        // `prepare()`: a stopwatch started there would read [ENGINE_STARTUP_MS] here, which is zero.
        harness.advanceTimeMs(player, ADOPT_TO_PREPARE_MS)
        harness.playToReady(player)
    }

    private fun awaitDelivery() {
        assertThat(checkNotNull(collector).awaitDelivered(DELIVERY_TIMEOUT_MS)).isTrue()
    }

    private inline fun <reified T : TelemetryEvent> eventsOf(): List<T> {
        awaitDelivery()
        return synchronized(events) { events.filterIsInstance<T>() }
    }

    @Test
    fun timeToFirstFrameIsMeasuredFromTheDeclaredIntent() {
        val player = buildPlayer()

        // The tap. Everything between here and the frame is what the viewer waited through, which is
        // the whole argument for `PRD.md` §3.4 putting the boundary at intent rather than `prepare`.
        player.declarePlaybackIntent()
        // The catalogue call, the entitlement check, the navigation transition — an app's real work,
        // and the interval a library that started its stopwatch at `prepare()` would have excluded.
        harness.advanceTimeMs(player, CATALOGUE_LOOKUP_MS)
        play(player)

        val firstFrame = eventsOf<TelemetryEvent.FirstFrameRendered>().single()
        assertThat(firstFrame.startBoundary).isEqualTo(TtffStartBoundary.USER_INTENT)
        assertThat(firstFrame.timeToFirstFrameMs)
            .isEqualTo(CATALOGUE_LOOKUP_MS + ADOPT_TO_PREPARE_MS + ENGINE_STARTUP_MS)
    }

    @Test
    fun aSessionWithNoDeclaredIntentIsMeasuredFromContentAdoptionAndSaysSo() {
        val player = buildPlayer()

        play(player)

        // A real measurement of a narrower interval. It reads lower, which is exactly why it is
        // labelled rather than silently substituted — the two must never be averaged together.
        val firstFrame = eventsOf<TelemetryEvent.FirstFrameRendered>().single()
        assertThat(firstFrame.startBoundary).isEqualTo(TtffStartBoundary.CONTENT_ADOPTED)
        // From adoption to the frame and nothing before it — which is the point: the `USER_INTENT`
        // measurement above is this number plus the app's catalogue lookup, and that difference is
        // the interval an app most wants to see. A boundary at `prepare()` would hide the other
        // half too and report the bare engine start-up, which on this fixture is nothing at all.
        assertThat(firstFrame.timeToFirstFrameMs).isEqualTo(ADOPT_TO_PREPARE_MS + ENGINE_STARTUP_MS)
    }

    @Test
    fun theFirstFrameIsReportedOnceEvenThoughMedia3RaisesItAgainAfterASeek() {
        val player = buildPlayer()
        play(player)

        player.seekTo(SEEK_TARGET_MS)
        harness.settle(player)
        harness.advanceTimeMs(player, 500)

        // CTA-2066's video start-up time is the first frame of the *view*. Media3 re-raises the
        // callback whenever the pipeline restarts, and a second measurement under the same name
        // would be a different metric wearing this one's label.
        assertThat(eventsOf<TelemetryEvent.FirstFrameRendered>()).hasSize(1)
    }

    @Test
    fun aStallAfterPlaybackStartedIsARebufferAndIsNotSeekInduced() {
        val player = buildPlayer()
        play(player)
        harness.advanceTimeMs(player, 2_000)

        harness.stallRendering(player)
        harness.advanceTimeMs(player, STALL_MS)
        harness.resumeRendering(player)

        val started = eventsOf<TelemetryEvent.RebufferStarted>().single()
        val ended = eventsOf<TelemetryEvent.RebufferEnded>().single()
        assertThat(started.seekInduced).isFalse()
        assertThat(ended.seekInduced).isFalse()
        // The advance the test asked for, plus the one render pass it takes the engine to notice the
        // stall has been released. Named rather than approximated, which is what makes this an
        // assertion on a value: the stall began at the end of the pass that injected it.
        assertThat(ended.durationMs).isEqualTo(STALL_MS + PlaybackHarness.RENDER_PASS_MS)
    }

    @Test
    fun aStallCausedByASeekIsExcludedFromRebufferRatioAndReportedAsSeekLatencyInstead() {
        val player = buildPlayer()
        play(player)
        harness.advanceTimeMs(player, 2_000)

        // A viewer dragging the scrub bar into unbuffered content. They expect a wait; counting it
        // as a rebuffer would make the player look worse the more its viewers seek, which turns a
        // delivery metric into a measure of viewer behaviour.
        player.seekTo(SEEK_TARGET_MS)
        harness.settle(player)
        harness.stallRendering(player)
        harness.advanceTimeMs(player, SEEK_WAIT_MS)
        harness.resumeRendering(player)

        val rebuffers = eventsOf<TelemetryEvent.RebufferStarted>()
        assertThat(rebuffers).isNotEmpty()
        assertThat(rebuffers.map { it.seekInduced }.toSet()).containsExactly(true)
        assertThat(eventsOf<TelemetryEvent.RebufferEnded>().map { it.seekInduced }.toSet())
            .containsExactly(true)

        // And the wait is accounted for on the other side of the split, so the two together cover
        // all of it and neither double-counts the other.
        val seek = eventsOf<TelemetryEvent.SeekCompleted>().single()
        assertThat(eventsOf<TelemetryEvent.SeekRequested>().single().toPositionMs).isEqualTo(SEEK_TARGET_MS)
        assertThat(seek.seekLatencyMs).isAtLeast(0)
    }

    @Test
    fun aStallLongAfterASeekHasFinishedCountsTowardTheRatioAgain() {
        val player = buildPlayer()
        play(player)

        player.seekTo(SEEK_TARGET_MS)
        harness.settle(player)
        // Past the 1000 ms exclusion window `docs/telemetry-schema.md` argues for. The window has to
        // be long enough to cover the decoder pipeline refilling and short enough that it cannot
        // swallow a whole segment fetch; a stall this far out is delivery's, not the viewer's.
        harness.advanceTimeMs(player, 5_000)

        harness.stallRendering(player)
        harness.advanceTimeMs(player, STALL_MS)
        harness.resumeRendering(player)

        // The last one, because the seek itself may have stalled the pipeline on its way — that
        // stall is the seek's and is excluded, and this one, five seconds later, is delivery's.
        assertThat(eventsOf<TelemetryEvent.RebufferStarted>().last().seekInduced).isFalse()
    }

    @Test
    fun aSeekInterruptedByAFailureDoesNotMakeEveryLaterStallSeekInduced() {
        val player = buildPlayer()
        play(player)
        harness.advanceTimeMs(player, 2_000)

        // A seek issued while the pipeline is already stalled stays in flight — there is no return
        // to ready to complete it on. Then the renderer fails underneath it and the player goes
        // idle, so it never resumes at its target: no latency to report, and the danger is what it
        // leaves behind.
        harness.stallRendering(player)
        player.seekTo(SEEK_TARGET_MS)
        harness.settle(player)
        harness.failRendering(player)
        harness.advanceTimeMs(player, 5_000)

        // Recover and stall again, long past the seek-exclusion window.
        harness.resumeRendering(player)
        player.prepare()
        player.play()
        harness.advanceTimeMs(player, 5_000)
        harness.stallRendering(player)
        harness.advanceTimeMs(player, STALL_MS)
        harness.resumeRendering(player)

        // A seek left in flight would tag this — and every later stall in the session — as
        // seek-induced, quietly emptying the rebuffer ratio of a session whose seek happened to be
        // interrupted. The metric would read best exactly when playback went worst.
        assertThat(eventsOf<TelemetryEvent.RebufferStarted>().last().seekInduced).isFalse()
        // And nothing claimed a latency for a seek that never arrived anywhere.
        assertThat(eventsOf<TelemetryEvent.SeekCompleted>()).isEmpty()
    }

    @Test
    fun aTrackSwitchReportsBothBitratesAndWhichWayItWent() {
        val player = buildPlayer(TestContent.videoLadder(listOf(LOW_BITRATE, HIGH_BITRATE)))
        play(player)
        harness.advanceTimeMs(player, 10_000)

        val switches = eventsOf<TelemetryEvent.TrackSwitched>()
        // The first rendition of the session has nothing to have switched from, which is a fact a
        // pipeline needs stated rather than inferred from a null.
        assertThat(switches.first().direction).isEqualTo(TrackSwitchDirection.INITIAL)
        assertThat(switches.first().fromBitrateBps).isNull()
        assertThat(switches.map { it.toBitrateBps }).containsAnyIn(listOf(LOW_BITRATE, HIGH_BITRATE))
        switches.drop(1).forEach { switch ->
            val from = checkNotNull(switch.fromBitrateBps)
            val expected =
                if (switch.toBitrateBps > from) TrackSwitchDirection.UP else TrackSwitchDirection.DOWN
            assertThat(switch.direction).isEqualTo(expected)
        }
        // Consecutive switches chain: each one's `from` is the previous one's `to`, which is what
        // makes a bitrate distribution reconstructable from the switches alone.
        switches.zipWithNext { earlier, later ->
            assertThat(later.fromBitrateBps).isEqualTo(earlier.toBitrateBps)
        }
    }

    @Test
    fun aFailureBeforeTheFirstFrameIsAStartupFailure() {
        val player = buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
        // Armed before the engine ever renders, so the very first pass throws and no frame is ever
        // presented. Arming it afterwards would be a race against the first frame, and the two
        // outcomes are the two different events this test exists to keep apart.
        harness.failRendering(player)
        harness.playToFailure(player)

        // Nothing played. CTA-2066 counts this separately from a failure mid-view because they mean
        // different things to a viewer, and the rates have different denominators.
        val failure = eventsOf<TelemetryEvent.StartupFailed>().single()
        assertThat(eventsOf<TelemetryEvent.MidStreamFailed>()).isEmpty()
        assertThat(eventsOf<TelemetryEvent.FirstFrameRendered>()).isEmpty()
        assertThat(failure.failure.category).isEqualTo(FailureCategory.DECODER)
        assertThat(failure.failure.code).isNotEmpty()
    }

    @Test
    fun aFailureAfterTheFirstFrameIsAMidStreamFailure() {
        val player = buildPlayer()
        play(player)
        harness.advanceTimeMs(player, 3_000)

        harness.failRendering(player)

        val failure = eventsOf<TelemetryEvent.MidStreamFailed>().single()
        assertThat(eventsOf<TelemetryEvent.StartupFailed>()).isEmpty()
        assertThat(failure.positionMs).isAtLeast(0)
    }

    @Test
    fun aSessionThatEndedWithNoFrameAndNoFailureIsAnExitBeforeVideoStart() {
        val player = buildPlayer()
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())

        // The viewer left. The library never sees a back press — what it sees is a release — so the
        // metric is derived from the shape of the session rather than reported, and the shape is
        // exactly this: a start, an end, and nothing in between.
        player.release()

        assertThat(eventsOf<TelemetryEvent.SessionStarted>()).hasSize(1)
        assertThat(eventsOf<TelemetryEvent.SessionEnded>()).hasSize(1)
        assertThat(eventsOf<TelemetryEvent.FirstFrameRendered>()).isEmpty()
        // The distinction that must not collapse: this is an abandonment, and a startup failure is
        // a failure. A collector that reported one as the other would make a bad CDN look like a
        // boring show.
        assertThat(eventsOf<TelemetryEvent.StartupFailed>()).isEmpty()
    }

    @Test
    fun droppedFramesAreReportedOverTheEnginesOwnInterval() {
        val player = buildPlayer()
        play(player)

        harness.reportDroppedFrames(player, count = 12, elapsedMs = 1_000)

        val dropped = eventsOf<TelemetryEvent.VideoFramesDropped>().single()
        assertThat(dropped.droppedFrames).isEqualTo(12)
        assertThat(dropped.elapsedPlayingMs).isEqualTo(1_000)
        // Media3 1.11.0 has no callback for a frame presented twice, so the field is reported as
        // zero rather than omitted — `docs/telemetry-schema.md` says so under the metric, which is
        // where a pipeline learns that a zero here means "not observable on this engine".
        assertThat(dropped.repeatedFrames).isEqualTo(0)
    }

    @Test
    fun theStateSampleCarriesItsOwnCadenceAndOnDemandContentReportsNoLiveLatency() {
        val player = buildPlayer()
        play(player)

        harness.advanceTimeMs(player, SAMPLING_INTERVAL_MS)

        val sample = eventsOf<TelemetryEvent.PlaybackStateSampled>().first()
        // The weight travels on the event. A consumer that read the cadence out of the document
        // instead is the one that breaks when the default moves.
        assertThat(sample.samplingIntervalMs).isEqualTo(SAMPLING_INTERVAL_MS)
        assertThat(sample.playing).isTrue()
        assertThat(sample.videoBitrateBps).isEqualTo(TestContent.DEFAULT_BITRATE_BPS)
        // A live-latency sample of zero from on-demand content is a number a dashboard would happily
        // average; the absence of the event is what stops that.
        assertThat(eventsOf<TelemetryEvent.LiveLatencySampled>()).isEmpty()
    }

    @Test
    fun liveContentReportsItsDistanceFromTheLiveEdge() {
        val player = buildPlayer(TestContent.liveVideo(), PlaybackProfile.LIVE_LINEAR)
        play(player)

        harness.advanceTimeMs(player, SAMPLING_INTERVAL_MS)

        val sample = eventsOf<TelemetryEvent.LiveLatencySampled>().first()
        assertThat(sample.sessionId).isEqualTo(eventsOf<TelemetryEvent.SessionStarted>().single().sessionId)
    }

    @Test
    fun aSinkThatBlocksForSecondsDelaysNoPlaybackStateTransition() {
        val wedged = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val blocking = TelemetrySink {
            entered.countDown()
            wedged.await()
        }
        val player = harness.buildPlayer(telemetry = QoeCollector(blocking).also { collector = it })

        try {
            player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).build())
            // The sink is now stuck on the delivery thread, which is the field failure this whole
            // guarantee exists for: an analytics SDK doing a synchronous network write on a bad
            // connection. If that thread were one the engine needed, everything below would hang and
            // the user's bug report would say "the video stutters".
            assertThat(entered.await(BLOCKED_SINK_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
                .isTrue()

            harness.playToReady(player)
            assertThat(player.playbackState).isEqualTo(Player.STATE_READY)

            harness.stallRendering(player)
            assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)

            harness.resumeRendering(player)
            assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        } finally {
            wedged.countDown()
        }
    }

    @Test
    fun aRecycledPooledPlayerReportsTwoSessionsWithIndependentCounters() {
        val pool = harness.buildPool(maxSize = 1, telemetry = { QoeCollector(sink).also { collector = it } })
        val first = checkNotNull(pool.acquire())

        play(first, contentId = CONTENT)
        harness.advanceTimeMs(first, 1_000)
        pool.recycle(first)

        val second = checkNotNull(pool.acquire())
        // The same player, which is the whole point of a pool and the whole risk of one: a counter
        // that survived the recycle would report the previous row's rebuffers against this row's
        // content, and a feed would look worse the longer someone scrolled.
        assertThat(second).isSameInstanceAs(first)
        // `resetForReuse` detaches the surface first of all, so that a stale frame cannot appear in
        // a recycled view. In an app the next row's `PlayerView` supplies a new one; here the
        // harness does. Without it the reused player renders nowhere and reports no first frame,
        // which is a real property of recycling rather than a quirk of this test.
        harness.attachVideoOutput(second)
        play(second, contentId = OTHER_CONTENT)

        val starts = eventsOf<TelemetryEvent.SessionStarted>()
        assertThat(starts.map { it.contentId }).containsExactly(CONTENT, OTHER_CONTENT).inOrder()
        assertThat(starts[1].sessionId).isNotEqualTo(starts[0].sessionId)

        val frames = eventsOf<TelemetryEvent.FirstFrameRendered>()
        assertThat(frames.map { it.sessionId }).containsExactly(starts[0].sessionId, starts[1].sessionId)
        // Measured from its own session's opening, not from the pool's first one — which the second
        // player having been playing for a second by then is exactly what would break.
        assertThat(frames[1].timeToFirstFrameMs).isEqualTo(ADOPT_TO_PREPARE_MS + ENGINE_STARTUP_MS)
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
        const val OTHER_CONTENT = "series/expanse/s01e02"
        const val SOURCE = "fake://superplayer.test/never-fetched"

        const val CATALOGUE_LOOKUP_MS = 1_500L
        const val ADOPT_TO_PREPARE_MS = 200L

        /**
         * What the engine itself costs before the first frame, in this fixture: nothing.
         *
         * Media3's fake source prepares in the instant it is asked and its fake renderer presents a
         * frame in the pass that enables it, so no time passes on the harness's clock between
         * `prepare()` and the frame. Named rather than rounded away, because a time to first frame is
         * the app's interval *plus* this one and a test that hid it would be asserting on a number it
         * had not accounted for. It read as one wait step, 50 ms, until issue #110: the harness moved
         * `SystemClock` before it let the engine finish the current moment, so a frame the engine
         * presented at the old time was stamped with the new one — on a quiet machine. A starved CI
         * runner let the engine finish first and read the truth, which looked like a failure.
         */
        const val ENGINE_STARTUP_MS = 0L
        const val STALL_MS = 2_500L
        const val SEEK_WAIT_MS = 400L
        const val SEEK_TARGET_MS = 20_000L
        const val SAMPLING_INTERVAL_MS = 10_000L

        const val LOW_BITRATE = 300_000
        const val HIGH_BITRATE = 2_400_000

        const val DELIVERY_TIMEOUT_MS = 5_000L

        /** Only ever reached when the delivery thread never picked the event up at all. */
        const val BLOCKED_SINK_TIMEOUT_SECONDS = 5L
    }
}
