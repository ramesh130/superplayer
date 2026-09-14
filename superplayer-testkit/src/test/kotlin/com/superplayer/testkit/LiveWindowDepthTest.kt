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

package com.superplayer.testkit

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.LiveWindowTooShortException
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
import com.superplayer.testmedia.HostileStream.Severity
import com.superplayer.testmedia.SyntheticDashStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A live DASH window no playhead can sit inside ends the session with [LiveWindowTooShortException],
 * and one that can is played exactly as before — issue #67, asserted through the facade.
 *
 * What was established first, and is why this is a failure rather than a clamp: before the change,
 * a window of half a second played *audibly* — the position advanced at real time, ready and
 * playing, with no error — four and a quarter seconds behind the live edge, which is three and three
 * quarter seconds before the start of the window the manifest promises. The reported position swung
 * between about −3.3 s and the whole elapsed period on every manifest refresh. A target live offset
 * laid on the item changed none of it, because Media3 already clamps the target inside the window:
 * the playhead is where it is because a segment cannot be fetched before it is complete, not
 * because the offset was chosen badly.
 *
 * So every test here also watches the position on every turn, in both directions: never before the
 * window's start, and never past its end by more than a segment — the swing measured went both ways.
 *
 * Here rather than in core's tests because only this module has the live DASH origin
 * (`HostileManifests`, through `TestContent.hostile`), the same reason `LivePlaylistRevalidationTest`
 * lives here.
 */
@RunWith(AndroidJUnit4::class)
class LiveWindowDepthTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aWindowShorterThanASegmentEndsTheSessionNamingBothDurations() {
        val stream = HostileManifests.dashShortTimeShiftBufferDepth(Severity.SEVERE)
        val (player, outside) = playToFailureWatchingPosition(stream)

        val cause = player.playerError?.cause
        assertThat(cause).isInstanceOf(LiveWindowTooShortException::class.java)
        cause as LiveWindowTooShortException
        assertThat(cause.timeShiftBufferDepthMs).isEqualTo(500L)
        assertThat(cause.segmentDurationMs).isEqualTo(SyntheticDashStream.DURATION_MS)
        assertThat(cause.availabilityTimeOffsetMs).isEqualTo(0L)
        assertThat(cause.manifestUri).endsWith(".mpd")
        assertThat(outside).isEmpty()
    }

    @Test
    fun aWindowExactlyOneSegmentDeepIsAlreadyTooShort() {
        // The boundary is inclusive: a segment is fetchable only once it is complete, so a playhead
        // starting it is a whole segment behind the edge — at the far end of a one-segment window
        // at best, and outside it as soon as the fetch takes any time at all.
        val stream = HostileManifests.dashShortTimeShiftBufferDepth(Severity.BORDERLINE)
        val (player, outside) = playToFailureWatchingPosition(stream)

        assertThat(player.playerError?.cause).isInstanceOf(LiveWindowTooShortException::class.java)
        assertThat(outside).isEmpty()
    }

    @Test
    fun aShortWindowThatHoldsAPlayheadPlaysOnUntouched() {
        // False positives count: four segments is RFC 8216's floor for HLS and content a doctor must
        // not flag, so it plays exactly as the healthy baseline does.
        assertPlaysOnInsideTheWindow(HostileManifests.dashShortTimeShiftBufferDepth(Severity.BENIGN))
    }

    @Test
    fun theHealthyLiveBaselinePlaysOnUntouched() {
        assertPlaysOnInsideTheWindow(HostileManifests.dashLiveBaseline())
    }

    @Test
    fun aWindowTheManifestLeavesUnlimitedIsNotJudged() {
        // No @timeShiftBufferDepth means an unlimited window (ISO/IEC 23009-1 §5.3.1.2): a promise
        // no origin keeps, but not one too short to play inside.
        assertPlaysOnInsideTheWindow(HostileManifests.dashMissingTimeShiftBufferDepth())
    }

    /** Plays [stream] until it fails, returning the failure and every position seen outside the window. */
    private fun playToFailureWatchingPosition(stream: HostileStream): Pair<SuperPlayer, List<String>> {
        val player = playerFor(stream)
        val outside = mutableListOf<String>()
        player.prepare()
        player.play()
        harness.advanceUntil(player, "an error") {
            outsideTheWindow(it)?.let(outside::add)
            it.playerError != null
        }
        return player to outside
    }

    private fun assertPlaysOnInsideTheWindow(stream: HostileStream) {
        val player = playerFor(stream)
        val outside = mutableListOf<String>()
        player.prepare()
        player.play()
        var advanced = 0L
        while (advanced < PLAYED_MS) {
            harness.advanceTimeInStepsMs(player, STEP_MS)
            advanced += STEP_MS
            assertThat(player.playerError).isNull()
            outsideTheWindow(player)?.let(outside::add)
        }
        assertThat(outside).isEmpty()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    /**
     * The player's position described, when it lies outside the live window its timeline reports:
     * before the start, or more than a segment past the end — the corpus's own slack, because a
     * position is reported at the granularity of the engine's loop. Null when it is inside, or when
     * there is no window yet to be outside of — an empty timeline, or the placeholder one an item has
     * before its manifest arrives, whose window has no duration.
     */
    private fun outsideTheWindow(player: Player): String? {
        val position = player.currentPosition
        val timeline = player.currentTimeline
        val windowMs = if (timeline.isEmpty) {
            null
        } else {
            timeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).durationMs
                .takeIf { it != C.TIME_UNSET }
        }
        val beyond = windowMs != null && position > windowMs + SyntheticDashStream.DURATION_MS
        return if (position < 0 || beyond) "position $position ms in a window of $windowMs ms" else null
    }

    private fun playerFor(stream: HostileStream): SuperPlayer {
        val content = TestContent.hostile(stream)
        val player = harness.buildPlayer(content = content)
        player.setMediaRequest(MediaRequest.Builder(stream.id).addSource(content.sourceUri).build())
        return player
    }

    private companion object {
        /** Long enough for a dozen manifest refreshes, each of which re-anchors the window. */
        const val PLAYED_MS = 24_000L

        /** One turn of the loop: a quarter of a segment, so a refresh is never stepped over unread. */
        const val STEP_MS = 500L
    }
}
