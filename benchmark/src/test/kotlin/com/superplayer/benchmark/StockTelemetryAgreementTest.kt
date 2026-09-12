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

package com.superplayer.benchmark

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.TelemetryEvent
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The two producers of this project's telemetry vocabulary, held to each other.
 *
 * **This test is why `StockTelemetry` is allowed to exist.** Issue #43 names the danger precisely:
 * arm (c)'s metrics come from the shipped `QoeCollector`, arms (a) and (b) have no SuperPlayer in
 * them and so cannot use it, and *a comparison where the two arms measure differently is not a
 * comparison*. [SessionMetrics] removes half that risk by construction — one implementation of the
 * definitions, which cannot tell which arm it is reducing. What is left is *derivation*: when a
 * rebuffer opens, what makes one seek-induced, which boundary time to first frame counts from,
 * whether a first frame is reported once or twice. That is what this pins.
 *
 * ## Both collectors, one player
 *
 * The two collectors are attached to **the same `ExoPlayer`**, through `SuperPlayer.exoPlayer`, and
 * receive the same `AnalyticsListener` callbacks at the same instants on the same clock. Any
 * difference in what comes out is therefore a difference between the collectors and can be nothing
 * else.
 *
 * That is the second design. The first built two players and drove them through one script, and it
 * failed for a reason worth recording: a seek into unbuffered content lands differently depending on
 * how much was buffered, so the two players interleaved `SeekRequested` and `RebufferStarted`
 * differently and produced a different number of stalls between runs. Those were real differences
 * between two *players* — which is the benchmark's whole subject — masquerading as differences
 * between two *collectors*, which is this test's. Configuring both players identically narrowed the
 * gap and did not close it. Removing the second player closes it, because there is then nothing left
 * to differ.
 *
 * What that costs is coverage of `PlaybackHarness.buildStockPlayer`, which this no longer exercises.
 * `StockPlayerTest` in `superplayer-testkit` covers it, which is where it belongs.
 *
 * ## What is compared, and what is not
 *
 * Compared: everything the collectors *derive* — the sequence of event types, rebuffer durations and
 * their seek attribution, the time-to-first-frame boundary and value, switch directions, the
 * sampling cadence.
 *
 * Not compared: what they are merely *given*. `sessionId` differs by construction (core mints arm
 * (c)'s; the runner mints the stock arm's), and `SessionStarted`'s `profile` and `decision` are
 * arguments to one and properties of the other — `StockTelemetry.startSession` documents exactly
 * what goes there and why it is a label rather than a fabrication.
 *
 * If this test is deleted, the benchmark's central claim goes with it.
 */
@RunWith(AndroidJUnit4::class)
class StockTelemetryAgreementTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /** Arm (c)'s events: the shipped collector's. Written from the delivery thread, read from this one. */
    private val fromQoeCollector = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

    /** The stock arms' events: the mirror's. Delivered synchronously — see `StockTelemetry`. */
    private val fromStockTelemetry = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

    @Test
    fun bothCollectorsDeriveTheSameEventsFromTheSameCallbacks() {
        playScriptedSession()

        // The sequence of event types is the coarsest and most valuable assertion here: a collector
        // that opened a rebuffer where the other did not, or reported a first frame twice, differs
        // in this list before it differs in any number.
        assertEquals(
            "The two collectors derived different events from identical engine callbacks",
            qoe().map { it.typeName() },
            stock().map { it.typeName() },
        )
    }

    @Test
    fun bothCollectorsMeasureTimeToFirstFrameIdentically() {
        playScriptedSession()

        val qoe = qoe().filterIsInstance<TelemetryEvent.FirstFrameRendered>().single()
        val stock = stock().filterIsInstance<TelemetryEvent.FirstFrameRendered>().single()

        // The boundary is the part that would make the arms incomparable rather than merely
        // different: `docs/telemetry-schema.md` is explicit that `CONTENT_ADOPTED` reads lower than
        // `USER_INTENT` and that the two must never be aggregated together. The value is asserted as
        // well because, on one player and one clock, there is no reason for it to differ at all.
        assertEquals(qoe.startBoundary, stock.startBoundary)
        assertEquals(qoe.timeToFirstFrameMs, stock.timeToFirstFrameMs)
    }

    @Test
    fun bothCollectorsAttributeAndMeasureEveryStallTheSameWay() {
        playScriptedSession()

        val qoe = qoe().filterIsInstance<TelemetryEvent.RebufferEnded>()
        val stock = stock().filterIsInstance<TelemetryEvent.RebufferEnded>()

        assertTrue("The script produced no stall to compare", qoe.isNotEmpty())
        // Duration *and* attribution. The attribution is the one that would silently move a number:
        // a stall tagged seek-induced by one collector and not by the other leaves one arm's
        // rebuffer ratio counting seconds the other excludes, which is the shape of mistake that
        // makes a benchmark table wrong while every cell in it looks reasonable.
        assertEquals(qoe.map { it.durationMs }, stock.map { it.durationMs })
        assertEquals(qoe.map { it.seekInduced }, stock.map { it.seekInduced })
    }

    @Test
    fun theSharedReducerGetsTheSameNumbersFromEitherCollector() {
        playScriptedSession()

        val qoe = SessionMetrics.from(qoe().first().sessionId, qoe())
        val stock = SessionMetrics.from(stock().first().sessionId, stock())

        // At the level a report is actually built at. The script contains a stall that counts toward
        // the ratio and one that does not, so equality here is a statement about the seek exclusion
        // landing identically rather than about there being nothing to exclude.
        assertTrue("The script produced no counted stall", qoe.rebufferCount > 0)
        assertTrue("The script produced no excluded stall", qoe.rebufferCount < stalls())
        assertEquals(qoe.rebufferMs, stock.rebufferMs)
        assertEquals(qoe.rebufferCount, stock.rebufferCount)
        assertEquals(qoe.playingMs, stock.playingMs)
        assertEquals(qoe.switchCount, stock.switchCount)
        assertEquals(qoe.averageBitrateBps, stock.averageBitrateBps)
    }

    @Test
    fun bothCollectorsSampleAtTheSameCadence() {
        playScriptedSession()

        val qoe = qoe().filterIsInstance<TelemetryEvent.PlaybackStateSampled>()
        val stock = stock().filterIsInstance<TelemetryEvent.PlaybackStateSampled>()

        assertTrue("The script produced no state sample to compare", qoe.isNotEmpty())
        // The cadence is the weight of a time-weighted average, so two arms sampled at different
        // rates would make the bitrate column two statistics wearing one name. `StockTelemetry`
        // repeats the constant because `QoeCollector` keeps its own private, deliberately, so that a
        // consumer reads the cadence off the event; this is what stops the repeat drifting.
        assertEquals(qoe.map { it.samplingIntervalMs }, stock.map { it.samplingIntervalMs })
        assertEquals(qoe.size, stock.size)
    }

    @Test
    fun bothCollectorsReportTheSameTrackSwitches() {
        playScriptedSession()

        val qoe = qoe().filterIsInstance<TelemetryEvent.TrackSwitched>()
        val stock = stock().filterIsInstance<TelemetryEvent.TrackSwitched>()

        assertTrue("The script produced no rendition choice to compare", qoe.isNotEmpty())
        assertEquals(qoe.map { it.direction }, stock.map { it.direction })
        assertEquals(qoe.map { it.fromBitrateBps }, stock.map { it.fromBitrateBps })
        assertEquals(qoe.map { it.toBitrateBps }, stock.map { it.toBitrateBps })
    }

    // --- One session, watched twice -------------------------------------------------------------

    /**
     * Plays one session with both collectors attached to the same engine.
     *
     * The script uses the harness's controllable renderer rather than a shaped network, so the
     * stalls happen at instants this test chose rather than at instants the engine happened to
     * produce — which keeps the run reproducible and keeps a failure attributable.
     */
    private fun playScriptedSession() {
        if (played) return
        played = true

        val player = harness.buildPlayer(
            content = TestContent.videoLadder(LADDER, CONTENT_MS),
            profile = PROFILE,
            telemetry = QoeCollector { fromQoeCollector += it },
        )
        val stock = StockTelemetry { fromStockTelemetry += it }
        // The same engine both collectors watch. `SuperPlayer.exoPlayer` is ADR-0001 rule 2's named
        // escape hatch, and this is the use it exists for from outside the library: reaching Media3's
        // analytics registration, which has no stable counterpart.
        stock.attach(player.exoPlayer)

        // Intent first, for both, so neither session's start boundary can differ from the other's.
        player.declarePlaybackIntent()
        stock.declareIntent(harness.elapsedRealtimeMs())
        // Then the two session openings, adjacent, with nothing between them that could advance the
        // clock. `setMediaRequest` is what opens arm (c)'s.
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(SOURCE_URI).build())
        stock.startSession(
            contentId = CONTENT_ID,
            sessionId = "stock-mirror",
            profile = PROFILE,
            decision = player.playbackDecision,
        )

        player.prepare()
        player.play()
        harness.advanceUntil(player, "ready") { it.playbackState == Player.STATE_READY }

        // A state sample, and enough playing time that the stall below is unambiguously after the
        // first frame — time before the first frame is start-up time and not a rebuffer, which both
        // collectors have to agree about too.
        harness.advanceTimeMs(player, SAMPLE_MS)

        // A stall that is nobody's seek: this one counts toward the rebuffer ratio.
        harness.stallRendering(player)
        harness.advanceTimeMs(player, STALL_MS)
        harness.resumeRendering(player)

        harness.advanceTimeMs(player, SAMPLE_MS)

        // A seek, and a stall inside the exclusion window it opens: this one does not count. Two
        // stalls of different kinds are what make the attribution assertions mean something — a
        // collector that classified everything alike would pass an assertion about just one.
        player.seekTo(SEEK_TO_MS)
        harness.settle(player)
        harness.stallRendering(player)
        harness.advanceTimeMs(player, STALL_MS)
        harness.resumeRendering(player)

        harness.advanceTimeMs(player, SAMPLE_MS)

        // Ended in the same order the runner ends them: the stock session explicitly, arm (c)'s by
        // releasing the player, which is what core turns into `SessionEnded`.
        stock.endSession()
        stock.detach()
        player.release()
        awaitDelivery()
    }

    /**
     * Waits for arm (c)'s asynchronous delivery to produce its terminal event.
     *
     * `QoeCollector.awaitDelivered` is the collector's own hook and is `internal` to
     * `superplayer-telemetry` — deliberately, so a consumer cannot call it from the thread that
     * design exists to keep out of a sink. This build is a consumer, so it waits for the event that
     * says the session is over, exactly as `BenchmarkMatrixTest` does.
     */
    private fun awaitDelivery() {
        val deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (synchronized(fromQoeCollector) { fromQoeCollector.any { it is TelemetryEvent.SessionEnded } }) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("Arm (c)'s SessionEnded did not arrive within $DELIVERY_TIMEOUT_MS ms")
    }

    private var played = false

    private fun qoe(): List<TelemetryEvent> = synchronized(fromQoeCollector) { fromQoeCollector.toList() }

    private fun stock(): List<TelemetryEvent> = synchronized(fromStockTelemetry) { fromStockTelemetry.toList() }

    /** How many stalls the script produced in total, counted or not. */
    private fun stalls(): Int = qoe().count { it is TelemetryEvent.RebufferEnded }

    /** Simple names rather than the events themselves, which carry ids that differ by construction. */
    private fun TelemetryEvent.typeName(): String = this::class.simpleName ?: "?"

    private companion object {
        const val CONTENT_ID = "agreement:ladder"
        val SOURCE_URI = TestContent.videoLadder().sourceUri

        /**
         * Any profile; what matters is that both collectors are told the same one, so a difference
         * in their output cannot be a difference in what they were given.
         */
        val PROFILE = PlaybackProfile.VIDEO_ON_DEMAND

        val LADDER = listOf(365_000, 730_000, 2_000_000)
        const val CONTENT_MS = 300_000L

        /** One sampling cadence, so each advance produces exactly one state sample. */
        const val SAMPLE_MS = StockTelemetry.SAMPLING_INTERVAL_MS

        /** Comfortably longer than the seek exclusion window, so the first stall cannot be swallowed. */
        const val STALL_MS = 2_000L

        /** Far enough ahead to be outside anything buffered, so the seek is a real one. */
        const val SEEK_TO_MS = 120_000L

        const val DELIVERY_TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
