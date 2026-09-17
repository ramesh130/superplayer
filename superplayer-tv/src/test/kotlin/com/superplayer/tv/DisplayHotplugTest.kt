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

package com.superplayer.tv

import android.content.Context
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.abr.AdaptivePolicy
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackDrm
import com.superplayer.core.PlaybackOutput
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.DisplayMode
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testkit.TestContent.Rung
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * An HDMI hotplug under playback, on a player built with `TvOutput.standard` (#269, ADR-0014 rule 5).
 *
 * The display is `DeviceStatement`'s, replaced mid-playback at a moment `PlaybackHarness.scheduleDeviceChange`
 * names, and heard by the player's display watch as one change, as a real display service reports one. The
 * selection is read from what a consumer's sink is told — a `TrackSwitched` is a rung the selector chose and
 * the engine played — over the harness's described video ladder, because the synthetic streams are audio.
 *
 * The refusal that re-arms is `superplayer-abr`'s gate, so every player here is built with `AdaptivePolicy`
 * (ADR-0014 rule 10: a player without abr has no display refusal, and a TV recipe takes the policy). Each
 * claim carries its control: the same hotplug on a player built without the module, which keeps the display
 * it was built on, and a display change that alters nothing the gate refuses on, which changes no selection.
 *
 * What is not asserted, and why: no `DecisionChanged` is expected, because a display change consults no
 * policy (ADR-0014 rules 9 and 11, and `docs/telemetry-schema.md`); and a hotplug's rebuffer is not asserted
 * absent in the narrowing tests, because rule 5 allows one — only the control asserts none, since it changes
 * no track.
 */
@RunWith(AndroidJUnit4::class)
class DisplayHotplugTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
    private val sink = TelemetrySink { events += it }

    @Test
    fun aHotplugToALesserDisplayNarrowsTheSelectionAtThePositionReached() {
        hotplugToALesserDisplay(drm = null)
    }

    /** ADR-0014 rule 13: a protected player survives a display change on the same terms. */
    @Test
    fun aProtectedPlayerNarrowsOnTheSameHotplug() {
        hotplugToALesserDisplay(drm = PROTECTED)
    }

    private fun hotplugToALesserDisplay(drm: PlaybackDrm?) {
        DeviceStatement.declareDisplayModes(listOf(UHD_60))
        val player = play(TvOutput.standard(context), drm)
        harness.advanceUntil(player, "the 2160p rung", CLIMB_BOUND_MS) { switches().any { it.toBitrateBps == UHD_BPS } }
        val before = switches().size
        val positionAtHotplug = player.currentPosition

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareDisplayModes(listOf(FHD_60)) }
        harness.advanceUntil(player, "a DOWN switch off the 2160p rung", DRAIN_BOUND_MS) {
            switches().drop(before).any { it.direction == TrackSwitchDirection.DOWN }
        }
        harness.advanceTimeInStepsMs(player, PLAYED_ON_MS)

        val after = switches().drop(before)
        assertWithMessage("after the hotplug: ${after.map { it.toBitrateBps }}")
            .that(after.first { it.direction == TrackSwitchDirection.DOWN }.toBitrateBps).isEqualTo(FHD_BPS)
        assertThat(after.map { it.toBitrateBps }).doesNotContain(UHD_BPS)
        assertThat(player.playerError).isNull()
        assertThat(events.filterIsInstance<TelemetryEvent.SeekRequested>()).isEmpty()
        assertThat(player.currentPosition).isGreaterThan(positionAtHotplug)
    }

    /**
     * The HDR half, alone: a panel of the same size that lists no HDR type. Nothing about the parameters
     * changes, so this is the re-selection core asks for itself (ADR-0014 rule 5, step 3), and the PQ rung
     * gives way to the SDR rung beside it.
     */
    @Test
    fun aHotplugToAnSdrDisplayOfTheSameSizeLeavesTheHdrRendition() {
        DeviceStatement.declareDisplayModes(listOf(UHD_60))
        DeviceStatement.declareDisplayHdrTypes(HDR10)
        val player = play(TvOutput.standard(context), drm = null, content = HDR_TOP_LADDER)
        harness.advanceUntil(player, "the HDR rung", CLIMB_BOUND_MS) { switches().any { it.toBitrateBps == UHD_BPS } }
        val before = switches().size
        val positionAtHotplug = player.currentPosition

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareDisplayHdrTypes() }
        harness.advanceUntil(player, "a DOWN switch off the HDR rung", DRAIN_BOUND_MS) {
            switches().drop(before).any { it.direction == TrackSwitchDirection.DOWN }
        }
        harness.advanceTimeInStepsMs(player, PLAYED_ON_MS)

        val after = switches().drop(before)
        assertWithMessage("after the hotplug: ${after.map { it.toBitrateBps }}").that(after.map { it.toBitrateBps }).doesNotContain(UHD_BPS)
        assertThat(after.first { it.direction == TrackSwitchDirection.DOWN }.toBitrateBps).isEqualTo(FHD_BPS)
        assertThat(player.playerError).isNull()
        assertThat(player.currentPosition).isGreaterThan(positionAtHotplug)
    }

    /** Control: the same hotplug on a player built without the module plays on at the rung it had. */
    @Test
    fun aPlayerBuiltWithoutTheModuleKeepsTheDisplayItWasBuiltOn() {
        DeviceStatement.declareDisplayModes(listOf(UHD_60))
        val player = play(output = null, drm = null)
        harness.advanceUntil(player, "the 2160p rung", CLIMB_BOUND_MS) { switches().any { it.toBitrateBps == UHD_BPS } }
        val before = switches().size

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareDisplayModes(listOf(FHD_60)) }
        harness.advanceTimeInStepsMs(player, DRAIN_BOUND_MS)

        assertWithMessage("after the hotplug: ${switches().drop(before).map { it.toBitrateBps }}")
            .that(switches().drop(before).map { it.direction }).doesNotContain(TrackSwitchDirection.DOWN)
        assertThat(player.playerError).isNull()
    }

    @Test
    fun aHotplugToAMoreCapableDisplayLetsTheSelectionClimb() {
        DeviceStatement.declareDisplayModes(listOf(FHD_60))
        val player = play(TvOutput.standard(context), drm = null)
        harness.advanceUntil(player, "the 1080p rung", CLIMB_BOUND_MS) { switches().any { it.toBitrateBps == FHD_BPS } }
        // Played on long enough to have climbed past 1080p if the display allowed it.
        harness.advanceTimeInStepsMs(player, PLAYED_ON_MS)
        assertWithMessage("before the hotplug: ${switches().map { it.toBitrateBps }}")
            .that(switches().map { it.toBitrateBps }).doesNotContain(UHD_BPS)
        val positionAtHotplug = player.currentPosition

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareDisplayModes(listOf(UHD_60)) }
        harness.advanceUntil(player, "the 2160p rung after the hotplug", CLIMB_BOUND_MS) {
            switches().any { it.toBitrateBps == UHD_BPS }
        }

        assertThat(player.playerError).isNull()
        assertThat(events.filterIsInstance<TelemetryEvent.SeekRequested>()).isEmpty()
        assertThat(player.currentPosition).isGreaterThan(positionAtHotplug)
    }

    /**
     * Control: displays that differ in nothing this ladder is refused on. A 1440p panel still shows every rung
     * of a ladder topping at 1080p, and HDR types gate nothing on SDR rungs, so the player re-selects and keeps
     * the rung it had — no switch and no rebuffer — rather than treating any change as a reason to fall back.
     */
    @Test
    fun aDisplayChangeThatAltersNothingTheLadderIsGatedOnChangesNoSelection() {
        DeviceStatement.declareDisplayModes(listOf(UHD_60))
        val player = play(TvOutput.standard(context), drm = null, content = FULL_HD_LADDER)
        harness.advanceUntil(player, "the 1080p rung", CLIMB_BOUND_MS) { switches().any { it.toBitrateBps == FHD_BPS } }
        harness.advanceTimeInStepsMs(player, PLAYED_ON_MS)
        val switchesBefore = switches().size
        val rebuffersBefore = rebuffers()

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareDisplayModes(listOf(QHD_60)) }
        harness.scheduleDeviceChange(afterMs = HOTPLUG_SPACING_MS) { DeviceStatement.declareDisplayHdrTypes(HDR10) }
        harness.advanceTimeInStepsMs(player, DRAIN_BOUND_MS)

        assertWithMessage("after the changes: ${switches().drop(switchesBefore).map { it.toBitrateBps }}")
            .that(switches().drop(switchesBefore)).isEmpty()
        assertThat(rebuffers()).isEqualTo(rebuffersBefore)
        assertThat(player.playerError).isNull()
    }

    private fun play(output: PlaybackOutput?, drm: PlaybackDrm?, content: TestContent = UHD_LADDER): SuperPlayer {
        events.clear()
        val player = harness.buildPlayer(
            content = content,
            profile = PlaybackProfile.TV_LEANBACK,
            telemetry = QoeCollector(sink),
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.TV_LEANBACK),
            drm = drm,
            output = output,
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        return player
    }

    private fun switches(): List<TelemetryEvent.TrackSwitched> = synchronized(events) { events.filterIsInstance<TelemetryEvent.TrackSwitched>() }

    private fun rebuffers(): Int = synchronized(events) { events.count { it is TelemetryEvent.RebufferStarted } }

    private companion object {
        const val CONTENT = "series/expanse/s01e04"
        const val LONG_CONTENT_MS = 600_000L

        const val FHD_BPS = 4_000_000
        const val UHD_BPS = 6_000_000

        val UHD_LADDER = TestContent.ladder(
            listOf(Rung(300_000, 360), Rung(800_000, 480), Rung(2_400_000, 720), Rung(FHD_BPS, 1_080), Rung(UHD_BPS, 2_160)),
            durationMs = LONG_CONTENT_MS,
        )

        /** SDR up to 1080p and one HDR10 rung above it, as a ladder offering HDR at the top is cut. */
        val HDR_TOP_LADDER = TestContent.ladder(
            listOf(Rung(300_000, 360), Rung(800_000, 480), Rung(2_400_000, 720), Rung(FHD_BPS, 1_080), Rung(UHD_BPS, 2_160, hdr = true)),
            durationMs = LONG_CONTENT_MS,
        )
        val FULL_HD_LADDER = TestContent.ladder(
            listOf(Rung(300_000, 360), Rung(800_000, 480), Rung(2_400_000, 720), Rung(FHD_BPS, 1_080)),
            durationMs = LONG_CONTENT_MS,
        )

        val UHD_60 = DisplayMode(3840, 2160, 60f)
        val QHD_60 = DisplayMode(2560, 1440, 60f)
        val FHD_60 = DisplayMode(1920, 1080, 60f)

        /** `Display.HdrCapabilities.HDR_TYPE_HDR10`, named here so the test reads as the statement. */
        const val HDR10 = Display.HdrCapabilities.HDR_TYPE_HDR10

        /**
         * What makes a player protected as far as the selection gate is concerned: the builder was told
         * `setDrm`. A bare [PlaybackDrm] fills no slot and plays this clear ladder, so the pair above differs
         * in that one fact (the reasoning is `NetworkAwareTrackSelectionPlaybackTest`'s).
         */
        val PROTECTED: PlaybackDrm = object : PlaybackDrm {}

        /** Long enough for the leanback thresholds to allow a climb to the top of the ladder on a stable link. */
        const val CLIMB_BOUND_MS = 120_000L

        /** A descent lands at the next chunk choice, which waits for the buffer to fall under its ceiling. */
        const val DRAIN_BOUND_MS = 90_000L

        /** Played on after a switch, long enough for a refused rung to have come back if it could. */
        const val PLAYED_ON_MS = 30_000L

        /** Two changes a moment apart, each heard as its own. */
        const val HOTPLUG_SPACING_MS = 1_000L
    }
}
