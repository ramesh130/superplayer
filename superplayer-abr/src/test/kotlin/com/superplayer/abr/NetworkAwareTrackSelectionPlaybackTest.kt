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

package com.superplayer.abr

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.DecisionTrigger
import com.superplayer.core.MediaRequest
import com.superplayer.core.NetworkTransport
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testkit.TestContent.Rung
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * #101's acceptance criteria, through the facade: a real player on the harness's clock over a
 * replayed network, and every assertion on what a consumer's sink was told — a `TrackSwitched`
 * is a rung the selector chose and the engine played, not a parameter it was handed. The
 * mechanism of each refusal is `NetworkAwareTrackSelectionTest`'s; the policy tables are
 * `AdaptiveSelectionPolicyTest`'s.
 *
 * Each gating test carries its own control — the same ladder played first on a device that does
 * not refuse — so that a rung never reached because the link or the thresholds never got there
 * cannot pass as a rung the gate refused.
 */
@RunWith(AndroidJUnit4::class)
class NetworkAwareTrackSelectionPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
    private val sink = TelemetrySink { events += it }

    @Before
    fun aCapableDeviceAndACleanMemory() {
        DeviceStatement.declareAppHeap(LARGE_HEAP_MB)
        EstimateMemory.PROCESS.forget()
    }

    @After
    fun forgetTheProcessMemory() {
        EstimateMemory.PROCESS.forget()
    }

    // Criterion 1: a handover lowers the cap, and the sink sees it as a DOWN switch.
    @Test
    fun aHandoverToCellularLowersTheCapAndIsSeenAsADownSwitch() {
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            profile = PlaybackProfile.DATA_SAVER,
            telemetry = QoeCollector(sink),
            network = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.DATA_SAVER),
        )
        val builtAtMs = harness.elapsedRealtimeMs()
        player.setMediaRequest(request())
        harness.playToReady(player)

        // On WiFi the data saver's own cap is the ceiling: the 800 kbit/s rung is the highest
        // under 800 kbit/s and 480p, and the WiFi cold default affords it.
        harness.advanceUntil(player, "the first rung to play") { switches().any { it.direction == TrackSwitchDirection.INITIAL } }
        assertThat(switches().first().toBitrateBps).isEqualTo(800_000)

        harness.advanceTimeInStepsMs(player, NetworkProfile.HANDOVER_AT_MS - (harness.elapsedRealtimeMs() - builtAtMs))
        harness.advanceUntil(player, "the transport decision") { decisions().any { it.trigger == DecisionTrigger.TRANSPORT_CHANGED } }
        val onCellular = decisions().last { it.trigger == DecisionTrigger.TRANSPORT_CHANGED }.decision.trackSelection
        assertThat(onCellular.copy(pace = null)).isEqualTo(TransportCaps.RUNG_360P)
        assertThat(onCellular.maxVideoBitrateBps).isLessThan(800_000)

        // The switch lands once the buffer WiFi filled has drained to the next chunk choice.
        val buffer = player.playbackDecision.buffer
        harness.advanceUntil(player, "a DOWN switch", buffer.maxBufferMs + DRAIN_MARGIN_MS) {
            switches().any { it.direction == TrackSwitchDirection.DOWN }
        }
        val down = switches().first { it.direction == TrackSwitchDirection.DOWN }
        assertThat(down.fromBitrateBps).isEqualTo(800_000)
        assertThat(down.toBitrateBps).isEqualTo(300_000)
        // The cap did that, not the link: cellular's 5 Mbit/s at the data saver's bandwidth
        // fraction affords the 800 kbit/s rung several times over.
        val affordedByLink = LTE_BPS * SelectionPaces.forProfile(PlaybackProfile.DATA_SAVER).bandwidthFraction
        assertThat(affordedByLink).isGreaterThan(800_000f)
        assertThat(player.playerError).isNull()
    }

    // Criterion 2: never 4K on a 1080p panel — with the 4K control first.
    @Test
    fun aFullHdDisplayNeverPlaysA2160pRungTheLinkAffords() {
        val ladder = TestContent.ladder(
            listOf(Rung(300_000, 360), Rung(800_000, 480), Rung(2_400_000, 720), Rung(4_000_000, 1_080), Rung(6_000_000, 2_160)),
            durationMs = LONG_CONTENT_MS,
        )
        // The harness's default display is 4K: the top rung is reached, so the link and the
        // thresholds are known to get there.
        val onFourK = playAndCollectSwitches(ladder)
        assertWithMessage("control: ${onFourK.map { it.toBitrateBps }}").that(onFourK.maxOf { it.toBitrateBps }).isEqualTo(6_000_000)

        DeviceStatement.declareDisplay(1_920, 1_080)
        val onFullHd = playAndCollectSwitches(ladder)
        assertWithMessage("gated: ${onFullHd.map { it.toBitrateBps }}").that(onFullHd.maxOf { it.toBitrateBps }).isEqualTo(4_000_000)
        assertThat(onFullHd.map { it.toBitrateBps }).doesNotContain(6_000_000)
    }

    // Criterion 3: a profile and level the stubbed decoder does not reach is never played. Two
    // tests rather than a control in the same one, because the platform caches its codec list on
    // first read and a decoder declared after a player was built is a decoder the next player
    // does not see.
    @Test
    fun anUndeclaredDecoderRefusesNothingAndTheHighProfileRungIsReached() {
        val ungated = playAndCollectSwitches(profiledLadder())
        assertWithMessage("control: ${ungated.map { it.toBitrateBps }}").that(ungated.maxOf { it.toBitrateBps }).isEqualTo(4_000_000)
    }

    @Test
    fun aDecoderThatStopsAtMainProfileNeverPlaysAHighProfileRung() {
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileMain to CodecProfileLevel.AVCLevel4)
        val gated = playAndCollectSwitches(profiledLadder())
        assertWithMessage("gated: ${gated.map { it.toBitrateBps }}").that(gated.maxOf { it.toBitrateBps }).isEqualTo(800_000)
    }

    /** ref: RFC 6381 §3.3 — avc1.PPCCLL: 4D = Main, 64 = High; 1F = level 3.1, 28 = level 4.0. */
    private fun profiledLadder(): TestContent = TestContent.ladder(
        listOf(
            Rung(300_000, 360, codecs = "avc1.4D401F"),
            Rung(800_000, 480, codecs = "avc1.4D401F"),
            Rung(2_400_000, 720, codecs = "avc1.64001F"),
            Rung(4_000_000, 1_080, codecs = "avc1.640028"),
        ),
        durationMs = LONG_CONTENT_MS,
    )

    // Criterion 4: the first rung is chosen from the per-transport estimate.
    @Test
    fun theStartupRungIsChosenFromTheSeededEstimateAndDiffersFromTheColdOne() {
        val ladder = TestContent.ladder(
            listOf(Rung(300_000, 360), Rung(800_000, 480), Rung(2_400_000, 720), Rung(6_000_000, 1_080)),
            durationMs = LONG_CONTENT_MS,
        )
        // Cold: WiFi's `ColdDefaults` entry, 5 Mbit/s, at the on-demand bandwidth fraction affords
        // the 2.4 Mbit/s rung — already not the manifest's first rung, which is the bottom one.
        val cold = playAndCollectSwitches(ladder, playedMs = STARTUP_WINDOW_MS)
        assertThat(cold.first().direction).isEqualTo(TrackSwitchDirection.INITIAL)
        assertThat(cold.first().toBitrateBps).isEqualTo(2_400_000)

        // Seeded: what a previous session on WiFi measured, remembered in the process, is what
        // the first rung is chosen from — and it is the top of the ladder from the first chunk.
        EstimateMemory.PROCESS.forget()
        EstimateMemory.PROCESS.record(NetworkTransport.Wifi, SEEDED_WIFI_BPS, harness.elapsedRealtimeMs())
        val seeded = playAndCollectSwitches(ladder, playedMs = STARTUP_WINDOW_MS)
        assertThat(seeded.first().direction).isEqualTo(TrackSwitchDirection.INITIAL)
        assertThat(seeded.first().toBitrateBps).isEqualTo(6_000_000)
    }

    // Criterion 9: a player built with a profile alone installs none of this.
    @Test
    fun aPlayerBuiltWithoutTheAdaptivePolicyPlaysTheStaticCapsAndNothingMoves() {
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            profile = PlaybackProfile.DATA_SAVER,
            telemetry = QoeCollector(sink),
            network = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace,
        )
        player.setMediaRequest(request())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, NetworkProfile.HANDOVER_AT_MS + STARTUP_WINDOW_MS)

        // No decision was ever re-made, and the static cap stayed in force across the handover:
        // Media3's own meter starts low and climbs to the 800 kbit/s rung, and nothing lowers it.
        assertThat(decisions()).isEmpty()
        assertThat(switches().map { it.toBitrateBps }.max()).isEqualTo(800_000)
        assertThat(switches().last().toBitrateBps).isEqualTo(800_000)
        assertThat(player.playerError).isNull()
    }

    // #114: a buffer the heap caps below the profile's climb threshold must still climb — with the
    // uncapped control first, so a climb the link never affords cannot pass as one the pace refused.
    @Test
    fun aDataSaverWhoseHeapCapsTheBufferUnderItsClimbThresholdStillClimbs() {
        val uncapped = playDataSaverFromTheBottomRung()
        assertWithMessage("control: ${uncapped.switches.map { it.toBitrateBps }}")
            .that(uncapped.switches.map { it.direction }).contains(TrackSwitchDirection.UP)

        DeviceStatement.declareAppHeap(TINY_HEAP_MB)
        val capped = playDataSaverFromTheBottomRung()
        assertWithMessage("the heap ceiling")
            .that(capped.maxBufferMs).isLessThan(SelectionPaces.forProfile(PlaybackProfile.DATA_SAVER).climbAfterBufferedMs)
        assertWithMessage("capped: ${capped.switches.map { it.toBitrateBps }}")
            .that(capped.switches.map { it.direction }).contains(TrackSwitchDirection.UP)
    }

    private class DataSaverRun(val switches: List<TelemetryEvent.TrackSwitched>, val maxBufferMs: Int)

    /** A data saver on a fast, stable link, started on the bottom rung by a remembered slow WiFi. */
    private fun playDataSaverFromTheBottomRung(): DataSaverRun {
        events.clear()
        EstimateMemory.PROCESS.forget()
        EstimateMemory.PROCESS.record(NetworkTransport.Wifi, SLOW_WIFI_BPS, harness.elapsedRealtimeMs())
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            profile = PlaybackProfile.DATA_SAVER,
            telemetry = QoeCollector(sink),
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.DATA_SAVER),
        )
        player.setMediaRequest(request())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)
        assertThat(player.playerError).isNull()
        assertWithMessage("the start").that(switches().first().toBitrateBps).isEqualTo(300_000)
        val run = DataSaverRun(switches(), player.playbackDecision.buffer.maxBufferMs)
        player.release()
        return run
    }

    private fun playAndCollectSwitches(content: TestContent, playedMs: Long = PLAYED_MS): List<TelemetryEvent.TrackSwitched> {
        events.clear()
        val player = harness.buildPlayer(
            content = content,
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            telemetry = QoeCollector(sink),
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND),
        )
        player.setMediaRequest(request())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, playedMs)
        harness.advanceUntil(player, "a first rung to be reported") { switches().isNotEmpty() }
        assertThat(player.playerError).isNull()
        val switches = switches()
        player.release()
        return switches
    }

    private fun switches(): List<TelemetryEvent.TrackSwitched> = synchronized(events) { events.filterIsInstance<TelemetryEvent.TrackSwitched>() }

    private fun decisions(): List<TelemetryEvent.DecisionChanged> = synchronized(events) { events.filterIsInstance<TelemetryEvent.DecisionChanged>() }

    private fun request(): MediaRequest = MediaRequest.Builder(CONTENT).addSource(SOURCE).build()

    private companion object {
        const val CONTENT = "series/expanse/s01e04"
        const val SOURCE = "fake://superplayer.test/never-fetched"
        const val LONG_CONTENT_MS = 240_000L
        const val LARGE_HEAP_MB = 2_048

        /** Long enough for the on-demand thresholds to allow a climb to the top of a ladder. */
        const val PLAYED_MS = 60_000L

        /** Enough to see the first rung and nothing that depends on a climb. */
        const val STARTUP_WINDOW_MS = 5_000L

        /** A cap-lowered switch waits for the buffer to drain; this is the allowance past it. */
        const val DRAIN_MARGIN_MS = 10_000L

        /** What a previous WiFi session is remembered as: enough for the top rung from the first chunk. */
        const val SEEDED_WIFI_BPS = 20_000_000L

        /** A remembered WiFi too slow for any rung, so the first choice is the bottom one. */
        const val SLOW_WIFI_BPS = 200_000L

        /**
         * 4 MB: a quarter of it at the data saver's 800 kbit/s cap is a 10 s ceiling, under the
         * profile's 14 s climb threshold and over its 5 s resume floor.
         */
        const val TINY_HEAP_MB = 4

        val LTE_BPS = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace.bandwidthBpsAt(NetworkProfile.HANDOVER_AT_MS)
    }
}
