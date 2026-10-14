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

package com.superplayer.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.BufferPolicy
import com.superplayer.core.FailureCategory
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackFailure
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The debug HUD (#294, ADR-0015 rule 11), composed over a real player under Robolectric and read
 * back the way a viewer's accessibility services read it — one row's value per row, so an assertion
 * names the reading rather than a pixel.
 *
 * Three claims, and each has a control:
 *
 * 1. **A `SuperPlayer` with a collector attached answers every row**, the estimate beside the rung
 *    among them, and the control is [theHudDegradesOnAStockPlayerRatherThanRefusing] — the same HUD
 *    over a stock `ExoPlayer` with no collector, which is what proves the HUD is not secretly
 *    requiring a `SuperPlayer` (ADR-0015 rule 2's one named exception).
 * 2. **A release build shows nothing**, and the control is every other method here, each of which
 *    states a debuggable application first. A HUD that rendered whatever the flag said would pass
 *    the first claim and fail the guard silently.
 * 3. **The failure log is bounded and keeps the newest**, because an overlay that grew with the
 *    session would become the screen.
 *
 * **The telemetry half is fed through the sink's own front door**, `DebugHudTelemetry.onEvent`, with
 * `TelemetryEvent` values this test constructs. That is deliberate and it is not a shortcut past the
 * facade: [DebugHudTelemetry] *is* a public `TelemetrySink`, and what a sink is called with is the
 * published vocabulary, so feeding it is driving the HUD through exactly the API a consumer's
 * `QoeCollector` drives it through. What a real collector attached to a real player would add is
 * asynchrony — delivery is a bounded queue on a thread of its own (ADR-0008 rule 4) — and a Compose
 * test whose assertions waited on that queue would be timing, not reading. The collector's own half,
 * that a played session's samples carry `throughputEstimateBps` at all, is
 * `superplayer-telemetry`'s to assert and `docs/telemetry-schema.md` says what the harness cannot
 * show of it.
 *
 * The player half is real throughout: the buffer, the position, the delivered error and the policy
 * ceiling are read off a player the harness built and played.
 */
@RunWith(AndroidJUnit4::class)
class DebugHudTest {

    @get:Rule(order = 0)
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    /**
     * Robolectric's application is not debuggable, which is a release build as far as the guard is
     * concerned. Every method but the guard's own control states the flag first, because the HUD
     * renders nothing without it.
     */
    @Before
    fun declareDebuggableApplication() {
        val info = ApplicationProvider.getApplicationContext<Context>().applicationInfo
        info.flags = info.flags or ApplicationInfo.FLAG_DEBUGGABLE
    }

    @Test
    fun theHudShowsTheBufferTheRenditionTheDroppedFramesAndTheEstimateBesideTheSelectedRung() {
        val player = playingSuperPlayer()
        val telemetry = DebugHudTelemetry()
        telemetry.onEvent(framesDropped(7))
        telemetry.onEvent(stateSampled(estimateBps = ESTIMATE_BPS))

        show(player, telemetry)

        row(LABEL_SOURCE).hasValue("SuperPlayer, telemetry attached")
        // The rung the *player* selected, which is where it comes from (ADR-0015 rule 11) — the
        // content's own declared bitrate, and not a number any event carried.
        row(LABEL_BITRATE).hasValue("$SELECTED_KBPS kbps")
        row(LABEL_DROPPED).hasValue("7")
        // The line worth the HUD: a healthy estimate, a rung well under it, and the ceiling that
        // allowed it — all three on one row, which is the comparison the issue asks for.
        row(LABEL_ESTIMATE).hasValue("4000 kbps estimated, $SELECTED_KBPS kbps selected, unlimited ceiling")
        row(LABEL_FAILURES).hasValue(HUD_NONE)
        // Read off the player rather than off any event: a buffer and a position are the player's own
        // facts, which is why this row is the one a stock player answers too. The seconds are not
        // pinned — how much a ready player has buffered is the engine's — but the buffer being
        // non-zero is, because a row of zeroes would look identical to a row that was never read.
        row(LABEL_BUFFER).hasBufferedSeconds()
        assertThat(isDebugHudAvailable(ApplicationProvider.getApplicationContext())).isTrue()
    }

    /**
     * The degradation control. A stock `ExoPlayer` and no collector: the rows a `Player` can answer
     * are answered, and every row it cannot says so. Nothing refuses to compose.
     */
    @Test
    fun theHudDegradesOnAStockPlayerRatherThanRefusing() {
        val content = TestContent.video()
        val player = harness.buildStockPlayer(content)
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))
        harness.playToReady(player)

        show(player, telemetry = null)

        row(LABEL_SOURCE).hasValue("Player, no telemetry")
        // The rung is the player's own, so a stock player answers it — which is the difference between
        // degrading to what it can read and refusing what it was not built for.
        row(LABEL_BITRATE).hasValue("$SELECTED_KBPS kbps")
        row(LABEL_DROPPED).hasValue(HUD_UNAVAILABLE)
        // The estimate is the half only telemetry can answer, and the ceiling the half only a
        // `SuperPlayer` has: the row says so rather than showing Media3's own selection parameters as
        // though a policy had decided them.
        row(LABEL_ESTIMATE)
            .hasValue("$HUD_UNAVAILABLE estimated, $SELECTED_KBPS kbps selected, $HUD_UNAVAILABLE ceiling")
        row(LABEL_BUFFER).hasBufferedSeconds()
        row(LABEL_FAILURES).hasValue(HUD_NONE)
    }

    /** The guard: an application that is not debuggable composes no HUD at all, not an empty one. */
    @Test
    fun anApplicationThatIsNotDebuggableShowsNothing() {
        val info = ApplicationProvider.getApplicationContext<Context>().applicationInfo
        info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        assertThat(isDebugHudAvailable(ApplicationProvider.getApplicationContext())).isFalse()

        show(playingSuperPlayer(), DebugHudTelemetry())

        compose.onAllNodesWithContentDescription(HUD_DESCRIPTION).assertCountEquals(0)
    }

    /** The failure log keeps the newest failures, both facts of each, and no more than four lines. */
    @Test
    fun theFailureLogKeepsTheNewestFailuresAndBothFactsOfEach() {
        val telemetry = DebugHudTelemetry()
        // Five failures into a log that holds four, so the oldest has to be gone and the newest first.
        listOf("Transient.Network", "Content.SegmentGap", "Device.DecoderInit", "Drm.SystemError", "Fatal.Unsupported")
            .forEach { telemetry.onEvent(midStreamFailed(it)) }

        show(playingSuperPlayer(), telemetry)

        // Each line is the classification *and* the code, because they are two facts and neither is
        // expressed in the other (ADR-0011 rule 3, ADR-0015 rule 5).
        row(LABEL_FAILURES).hasValue(
            "Fatal.Unsupported $CODE, Drm.SystemError $CODE, Device.DecoderInit $CODE, Content.SegmentGap $CODE",
        )
    }

    /**
     * The error the player itself delivered leads the failure row, and takes one of the four lines
     * rather than a fifth.
     *
     * The other half of the degradation claim: `playerError` is a `Player`'s own fact, so it is what a
     * stock player's failure row shows, and on a `SuperPlayer` it is the one rung 6 delivered. It is
     * shown beside the classified lines and merged into none of them, which is why the count matters —
     * a row of five lines would be a bound that argued four and rendered otherwise.
     */
    @Test
    fun theErrorThePlayerDeliveredLeadsTheRowAndTakesOneOfItsFourLines() {
        val telemetry = DebugHudTelemetry()
        listOf("Transient.Network", "Content.SegmentGap", "Device.DecoderInit", "Drm.SystemError")
            .forEach { telemetry.onEvent(midStreamFailed(it)) }

        show(failedPlayer(), telemetry)

        val lines = row(LABEL_FAILURES).value().split(", ")
        assertThat(lines).hasSize(4)
        assertThat(lines.first()).startsWith("delivered ERROR_CODE_")
        // The oldest classified line is what the delivered error displaced, and the newest three stay.
        assertThat(lines.drop(1)).containsExactly(
            "Drm.SystemError $CODE",
            "Device.DecoderInit $CODE",
            "Content.SegmentGap $CODE",
        ).inOrder()
    }

    /**
     * A failure a player with no `superplayer-resilience` reports carries no classification, and the
     * row is the engine's own code rather than a gap (ADR-0011 rule 3: two facts, not one).
     */
    @Test
    fun anUnclassifiedFailureIsShownAsTheCodeTheEngineRaised() {
        val telemetry = DebugHudTelemetry()
        telemetry.onEvent(midStreamFailed(classification = null, code = "ERROR_CODE_IO_BAD_HTTP_STATUS"))

        show(playingSuperPlayer(), telemetry)

        row(LABEL_FAILURES).hasValue("ERROR_CODE_IO_BAD_HTTP_STATUS")
    }

    /** A failure the engine raised no code for at all falls back to the bucket, never to an empty row. */
    @Test
    fun aFailureWithNeitherAClassificationNorACodeIsShownAsItsCategory() {
        val telemetry = DebugHudTelemetry()
        telemetry.onEvent(midStreamFailed(classification = null, code = null))

        show(playingSuperPlayer(), telemetry)

        row(LABEL_FAILURES).hasValue(FailureCategory.NETWORK.name)
    }

    /** A new session starts the readings over, which is what a recycled pooled player does. */
    @Test
    fun aNewSessionStartsTheReadingsOver() {
        val telemetry = DebugHudTelemetry()
        telemetry.onEvent(framesDropped(12))
        telemetry.onEvent(stateSampled(estimateBps = ESTIMATE_BPS))
        telemetry.onEvent(midStreamFailed("Transient.Network"))

        assertThat(telemetry.reading().droppedFrames).isEqualTo(12)
        telemetry.onEvent(sessionStarted())

        assertThat(telemetry.reading()).isEqualTo(HudTelemetryReading())
    }

    private fun show(player: Player, telemetry: DebugHudTelemetry?) {
        compose.setContent { DebugHud(player, telemetry) }
        compose.waitForIdle()
    }

    /** A real `SuperPlayer` playing real content, which is where every player-side row comes from. */
    private fun playingSuperPlayer(): SuperPlayer {
        val content = TestContent.video()
        val player = harness.buildPlayer(content)
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        harness.playToReady(player)
        return player
    }

    /**
     * A real `SuperPlayer` whose load the CDN refused outright, so `playerError` is populated.
     *
     * The manifest rather than a segment, because a refusal there ends the session before a frame and
     * therefore before the test has to wait for anything: what is wanted is only a delivered error.
     */
    private fun failedPlayer(): SuperPlayer {
        val content = TestContent.hls()
        val player = harness.buildPlayer(
            content = content,
            faults = FaultScript.Builder().failWithHttpStatus(500, kind = ResourceKind.MANIFEST).build(),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        player.prepare()
        var advanced = 0L
        while (player.playerError == null && advanced < FAILURE_WAIT_MS) {
            harness.advanceTimeInStepsMs(player, FAILURE_STEP_MS)
            advanced += FAILURE_STEP_MS
        }
        checkNotNull(player.playerError) { "the refused manifest was expected to end the session" }
        return player
    }

    /** The row labelled [label], found by the label a reader sees rather than by its position. */
    private fun row(label: String): HudRow = HudRow(label)

    private inner class HudRow(label: String) {
        private val node = compose.onNodeWithContentDescription(label)

        fun hasValue(expected: String) {
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
        }

        /** The row's reading, as a screen reader would announce it. */
        fun value(): String = node.fetchSemanticsNode().config[SemanticsProperties.StateDescription]

        /** The buffer row: `<n>s ahead of <n>s`, with something really buffered ahead. */
        fun hasBufferedSeconds() {
            val value = node.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            val buffered = BUFFER_ROW.matchEntire(value)?.groupValues?.get(1)
            assertThat(buffered).isNotNull()
            assertThat(buffered!!.toFloat()).isGreaterThan(0f)
        }
    }

    private fun sessionStarted() = TelemetryEvent.SessionStarted(
        sessionId = SESSION_ID,
        contentId = CONTENT_ID,
        timestampMs = 0,
        monotonicTimeMs = 0,
        profile = PlaybackProfile.VIDEO_ON_DEMAND,
        decision = PlaybackDecision(
            buffer = BufferPolicy(30_000, 60_000, 2_500, 5_000, 30_000, true),
            trackSelection = TrackSelectionPolicy(
                TrackSelectionPolicy.UNLIMITED,
                TrackSelectionPolicy.UNLIMITED,
            ),
        ),
    )

    private fun framesDropped(count: Int) = TelemetryEvent.VideoFramesDropped(
        sessionId = SESSION_ID,
        contentId = CONTENT_ID,
        timestampMs = 0,
        monotonicTimeMs = 0,
        droppedFrames = count,
        repeatedFrames = 0,
        elapsedPlayingMs = 1_000,
    )

    private fun stateSampled(estimateBps: Int) = TelemetryEvent.PlaybackStateSampled(
        sessionId = SESSION_ID,
        contentId = CONTENT_ID,
        timestampMs = 0,
        monotonicTimeMs = 0,
        samplingIntervalMs = 10_000,
        videoBitrateBps = TestContent.DEFAULT_BITRATE_BPS,
        bufferedDurationMs = 5_000,
        playing = true,
        throughputEstimateBps = estimateBps,
    )

    private fun midStreamFailed(classification: String?, code: String? = "ERROR_CODE_IO_UNSPECIFIED") =
        TelemetryEvent.MidStreamFailed(
            sessionId = SESSION_ID,
            contentId = CONTENT_ID,
            timestampMs = 0,
            monotonicTimeMs = 0,
            failure = PlaybackFailure(
                category = FailureCategory.NETWORK,
                code = code,
                message = null,
                classification = classification,
            ),
            positionMs = 0,
        )

    private companion object {
        const val CONTENT_ID = "demo:hud"
        const val SESSION_ID = "session-hud"

        /** The rung the harness's content declares, in the kilobits the HUD prints it in. */
        const val SELECTED_KBPS = TestContent.DEFAULT_BITRATE_BPS / 1_000

        /** Well above the rung, so what the estimate row is a comparison *of* is visible in it. */
        const val ESTIMATE_BPS = 4_000_000

        /** The code every hand-built failure here carries, so a line's two halves are both visible. */
        const val CODE = "ERROR_CODE_IO_UNSPECIFIED"

        /** Long enough for a refused manifest to end a session, in the load-sized steps the engine takes. */
        const val FAILURE_STEP_MS = 250L
        const val FAILURE_WAIT_MS = 10_000L

        /** The buffer row's shape, whose first group is the media buffered ahead of the playhead. */
        val BUFFER_ROW = Regex("""([0-9.]+)s ahead of [0-9.]+s""")
    }
}
