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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The D-pad controls (#272, ADR-0014 rule 12), driven the way a remote drives them: key events at the focused
 * control, under Robolectric, over a player the harness built.
 *
 * What is read is what a viewer sees and what the player was asked. Focus and the position shown are the
 * controls' semantics: which node is focused, and the seek bar's state description. Seeks are counted as the
 * `Player.Listener` a consumer would register hears them, as position discontinuities for a seek.
 *
 * Compose's clock is stepped by hand wherever a scrub is waited on, because the commit is a timeout, and a
 * clock that advanced itself whenever the test waited for idle would commit every scrub before a test could
 * look at it.
 */
@RunWith(AndroidJUnit4::class)
class TvPlaybackControlsTest {

    @get:Rule(order = 0)
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun focusReachesEveryControlFromPlayPause() {
        show(pausedPlayer())

        control(PLAY).assertIsFocused()
        press(Key.DirectionLeft)
        control(SEEK_BACK).assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        control(SEEK_FORWARD).assertIsFocused()
        press(Key.DirectionUp)
        control(SEEK_BAR).assertIsFocused()
        press(Key.DirectionDown)
        control(PLAY).assertIsFocused()
    }

    @Test
    fun theCentreKeyPlaysAndPauses() {
        val player = pausedPlayer()
        show(player)

        press(Key.DirectionCenter)
        harness.settle(player)
        assertThat(player.playWhenReady).isTrue()
        control(PAUSE).assertIsFocused()

        press(Key.DirectionCenter)
        harness.settle(player)
        assertThat(player.playWhenReady).isFalse()
        control(PLAY).assertIsFocused()
    }

    @Test
    fun aScrubForwardAndBackShowsThePositionAndSeeksOnceWhenTheDpadGoesQuiet() {
        val player = pausedPlayer()
        val seeks = countSeeks(player)
        show(player)
        compose.mainClock.autoAdvance = false
        press(Key.DirectionUp)

        repeat(3) { press(Key.DirectionRight) }
        shownPosition("0:30 / 1:00")
        press(Key.DirectionLeft)
        shownPosition("0:20 / 1:00")
        // Released, and inside the commit delay: still one scrub, and the player has not moved.
        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS - 100)
        assertThat(seeks).isEmpty()
        assertThat(player.currentPosition).isLessThan(1_000)

        compose.mainClock.advanceTimeBy(200)
        harness.settle(player)

        assertThat(seeks).containsExactly(20_000L)
        assertThat(player.currentPosition).isEqualTo(20_000)
        shownPosition("0:20 / 1:00")
    }

    /** Control for the one-seek claim: two scrubs a quiet second apart are two seeks, not one and not four. */
    @Test
    fun twoScrubsSeparatedByTheCommitDelayAreTwoSeeks() {
        val player = pausedPlayer()
        val seeks = countSeeks(player)
        show(player)
        compose.mainClock.autoAdvance = false
        press(Key.DirectionUp)

        press(Key.DirectionRight)
        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS + 100)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS + 100)
        harness.settle(player)

        assertThat(seeks).containsExactly(10_000L, 30_000L).inOrder()
    }

    @Test
    fun theCentreKeyCommitsAScrubAtOnce() {
        val player = pausedPlayer()
        val seeks = countSeeks(player)
        show(player)
        compose.mainClock.autoAdvance = false
        press(Key.DirectionUp)

        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        harness.settle(player)

        assertThat(seeks).containsExactly(10_000L)
    }

    @Test
    fun holdingADirectionAcceleratesOnTheStatedCurve() {
        val player = pausedPlayer(TestContent.video(durationMs = TWO_HOURS_MS))
        val seeks = countSeeks(player)
        show(player)
        compose.mainClock.autoAdvance = false
        press(Key.DirectionUp)

        // Two four-second holds, each its own scrub, and then one eight-second hold from the start again. The one
        // hold travelling further than the two is the speed rising while held, and the exact positions pin the
        // curve. That the rise follows held time rather than the number of repeats is `ScrubTest`'s, since the
        // injection here has one repeat rate.
        hold(Key.DirectionRight, 4_000)
        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS + 100)
        hold(Key.DirectionRight, 4_000)
        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS + 100)
        player.seekTo(0)
        harness.settle(player)
        compose.mainClock.advanceTimeByFrame()

        hold(Key.DirectionRight, 8_000)
        shownPosition("0:15:32 / 2:00:00")
        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS + 100)
        harness.settle(player)

        // On the stated curve, from 30 s a second doubling every two seconds: a four-second hold reaches 60 s a
        // second and covers about three minutes, and an eight-second hold reaches 240 s a second and covers about
        // fifteen. Summed over the injected key events (a press, then repeats from half a second in), that is
        // 3:14.5 twice against 15:32.5, where a speed that never rose would have covered 2:10 twice against 4:10.
        // Every hold, however long, was one seek. The 0 is the test's own reset between them.
        assertThat(seeks).containsExactly(194_500L, 389_000L, 0L, 932_500L).inOrder()
    }

    /** One hold of [key] for [heldMs], which the test's key injection repeats as a remote's firmware would. */
    private fun hold(key: Key, heldMs: Long) {
        compose.onRoot().performKeyInput {
            keyDown(key)
            advanceEventTime(heldMs)
            keyUp(key)
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun focusReturnsToTheControlThatHadItWhenTheControlsShowAgain() {
        val player = pausedPlayer()
        var visible by mutableStateOf(true)
        compose.setContent { TvPlaybackControls(player, visible) }
        press(Key.DirectionUp)
        control(SEEK_BAR).assertIsFocused()

        visible = false
        compose.waitForIdle()
        visible = true
        compose.waitForIdle()

        control(SEEK_BAR).assertIsFocused()
    }

    @Test
    fun onASuperPlayerAScrubSuppressesPlaybackUntilItCommits() {
        val player = ready(harness.buildPlayer(TestContent.video()), TestContent.video())
        show(player)
        compose.mainClock.autoAdvance = false
        press(Key.DirectionUp)

        press(Key.DirectionRight)
        harness.settle(player)
        assertThat(player.playbackSuppressionReason).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING)

        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS + 100)
        harness.settle(player)
        assertThat(player.playbackSuppressionReason).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
    }

    /** Controls hidden mid-scrub commit where the viewer had got to, and leave the SuperPlayer unsuppressed. */
    @Test
    fun hidingTheControlsMidScrubCommitsItOnce() {
        val player = ready(harness.buildPlayer(TestContent.video()), TestContent.video())
        val seeks = countSeeks(player)
        var visible by mutableStateOf(true)
        compose.setContent { TvPlaybackControls(player, visible) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        press(Key.DirectionUp)

        repeat(2) { press(Key.DirectionRight) }
        visible = false
        compose.mainClock.advanceTimeByFrame()
        harness.settle(player)

        assertThat(seeks).hasSize(1)
        assertThat(seeks.single()).isIn(com.google.common.collect.Range.closed(20_000L, 21_000L))
        assertThat(player.playbackSuppressionReason).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        // Nothing is left to commit: the delay passing changes nothing.
        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS + 100)
        harness.settle(player)
        assertThat(seeks).hasSize(1)
    }

    /** The controls take a `Player`, so a stock `ExoPlayer` scrubs, seeks once, and plays and pauses the same way. */
    @Test
    fun theControlsDriveAStockExoPlayer() {
        val player = ready(harness.buildStockPlayer(TestContent.video()), TestContent.video())
        player.pause()
        harness.settle(player)
        val seeks = countSeeks(player)
        show(player)
        compose.mainClock.autoAdvance = false

        press(Key.DirectionCenter)
        harness.settle(player)
        assertThat(player.playWhenReady).isTrue()

        press(Key.DirectionUp)
        repeat(2) { press(Key.DirectionRight) }
        harness.settle(player)
        // No scrubbing mode off a SuperPlayer: the stock player is not suppressed while the viewer scrubs.
        assertThat(player.playbackSuppressionReason).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        compose.mainClock.advanceTimeBy(SCRUB_COMMIT_DELAY_MS + 100)
        harness.settle(player)

        assertThat(seeks).hasSize(1)
        assertThat(seeks.single()).isIn(com.google.common.collect.Range.closed(20_000L, 21_000L))
    }

    /** [player] playing [content], ready. The item is set through `Player`, which a stock player and a SuperPlayer share. */
    private fun <P : Player> ready(player: P, content: TestContent): P {
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))
        harness.playToReady(player)
        return player
    }

    private fun pausedPlayer(content: TestContent = TestContent.video()): Player {
        val player = ready(harness.buildPlayer(content), content)
        player.pause()
        harness.settle(player)
        return player
    }

    private fun show(player: Player) {
        compose.setContent { TvPlaybackControls(player, visible = true) }
        compose.waitForIdle()
    }

    /** Every seek the player is asked, by the position it was asked for, as a consumer's listener hears it. */
    private fun countSeeks(player: Player): List<Long> {
        val seeks = mutableListOf<Long>()
        player.addListener(
            object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks += newPosition.positionMs
                }
            },
        )
        return seeks
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun control(description: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(description)

    private fun shownPosition(expected: String) {
        control(SEEK_BAR).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
    }

    private companion object {
        const val PLAY = "Play"
        const val PAUSE = "Pause"
        const val SEEK_BACK = "Seek back"
        const val SEEK_FORWARD = "Seek forward"
        const val SEEK_BAR = "Playback position"
        const val TWO_HOURS_MS = 2 * 60 * 60 * 1_000L
    }
}
