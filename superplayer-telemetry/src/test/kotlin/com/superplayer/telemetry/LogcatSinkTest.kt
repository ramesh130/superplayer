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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.BufferPolicy
import com.superplayer.core.FailureCategory
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackFailure
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.core.TtffStartBoundary
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * The property that makes [LogcatSink] worth shipping: an event is one greppable line.
 *
 * Not a golden-file test of the exact wording — the fields are allowed to gain members — but of the
 * two things a person leaning on `adb logcat` during an incident depends on: that no event spans two
 * lines, and that the discriminators they would grep for are actually in the line.
 */
@RunWith(AndroidJUnit4::class)
class LogcatSinkTest {

    @Before
    fun captureLogcat() {
        ShadowLog.clear()
    }

    private val lines: List<ShadowLog.LogItem>
        get() = ShadowLog.getLogsForTag(LogcatSink.TAG)

    @Test
    fun everyEventInTheVocabularyLogsExactlyOneLine() {
        everyEvent().forEach { LogcatSink.onEvent(it) }

        assertThat(lines).hasSize(everyEvent().size)
        lines.forEach { line ->
            // The whole design. A message that wrapped would be found by `grep` and read as
            // truncated, and an interleaved write from another thread would split the record.
            assertThat(line.msg).doesNotContain("\n")
            assertThat(line.msg).startsWith("evt=")
            assertThat(line.msg).contains("v=${TelemetryEvent.SCHEMA_VERSION}")
            assertThat(line.msg).contains("sid=$SESSION")
        }
    }

    @Test
    fun eachEventTypeIsGreppableByItsOwnDiscriminator() {
        everyEvent().forEach { LogcatSink.onEvent(it) }

        // One `evt=` value per event type: a person who greps for one class of event gets that class
        // and nothing else.
        val discriminators = lines.map { it.msg.substringBefore(' ') }
        assertThat(discriminators).containsNoDuplicates()
        assertThat(discriminators).contains("evt=rebuffer_started")
        assertThat(discriminators).contains("evt=first_frame")
    }

    @Test
    fun failuresLogAtWarnAndEverythingElseAtInfo() {
        LogcatSink.onEvent(startupFailed())
        LogcatSink.onEvent(sessionStarted())

        // `logcat SuperPlayerQoE:W *:S` is then a filter for the failures alone, with no second tag
        // to know about. Not ERROR: a startup failure a fallback then recovers from is not a crash.
        assertThat(lines.map { it.type }).containsExactly(Log.WARN, Log.INFO).inOrder()
    }

    @Test
    fun aMessageCarryingANewlineIsEscapedRatherThanBreakingTheLine() {
        LogcatSink.onEvent(
            startupFailed(
                PlaybackFailure(
                    category = FailureCategory.NETWORK,
                    code = "HTTP_403",
                    // The realistic shape: an engine message quoting a response body.
                    message = "Forbidden\nx-cdn-token: expired",
                ),
            ),
        )

        val line = lines.single().msg
        assertThat(line).doesNotContain("\n")
        assertThat(line).contains("""message="Forbidden\nx-cdn-token: expired"""")
    }

    @Test
    fun aContentIdCarryingASpaceStaysOneField() {
        // A content id is the app's own string and this sink may assume nothing about it.
        LogcatSink.onEvent(sessionStarted(contentId = "the expanse s01e01"))

        assertThat(lines.single().msg).contains("""cid="the expanse s01e01"""")
    }

    private fun sessionStarted(contentId: String = CONTENT): TelemetryEvent.SessionStarted =
        TelemetryEvent.SessionStarted(
            sessionId = SESSION,
            contentId = contentId,
            timestampMs = WALL_MS,
            monotonicTimeMs = MONO_MS,
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            decision = DECISION,
        )

    private fun startupFailed(
        failure: PlaybackFailure = PlaybackFailure(FailureCategory.SOURCE, "PARSE", "bad manifest"),
    ): TelemetryEvent.StartupFailed =
        TelemetryEvent.StartupFailed(SESSION, CONTENT, WALL_MS, MONO_MS, failure)

    /**
     * One of every event in the vocabulary.
     *
     * Exhaustiveness is the point: [LogcatSink] formats through a `when` with no `else`, so a new
     * event fails to compile there — and this list is what makes sure the new branch was *exercised*
     * rather than merely written.
     */
    private fun everyEvent(): List<TelemetryEvent> = listOf(
        sessionStarted(),
        TelemetryEvent.SessionEnded(SESSION, CONTENT, WALL_MS, MONO_MS, droppedEventCount = 3),
        TelemetryEvent.FirstFrameRendered(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            timeToFirstFrameMs = 812,
            startBoundary = TtffStartBoundary.USER_INTENT,
        ),
        TelemetryEvent.RebufferStarted(SESSION, CONTENT, WALL_MS, MONO_MS, seekInduced = false),
        TelemetryEvent.RebufferEnded(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            durationMs = 1_840,
            seekInduced = false,
        ),
        startupFailed(),
        TelemetryEvent.MidStreamFailed(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            failure = PlaybackFailure(FailureCategory.DECODER, null, null),
            positionMs = 90_000,
        ),
        TelemetryEvent.TrackSwitched(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            fromBitrateBps = 3_000_000,
            toBitrateBps = 1_200_000,
            direction = TrackSwitchDirection.DOWN,
        ),
        TelemetryEvent.SeekRequested(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            fromPositionMs = 10_000,
            toPositionMs = 600_000,
        ),
        TelemetryEvent.SeekCompleted(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            toPositionMs = 600_000,
            seekLatencyMs = 430,
        ),
        TelemetryEvent.LiveLatencySampled(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            liveLatencyMs = 6_200,
            targetLiveLatencyMs = 5_000,
        ),
        TelemetryEvent.PlaybackStateSampled(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            samplingIntervalMs = 10_000,
            videoBitrateBps = 3_000_000,
            bufferedDurationMs = 24_000,
            playing = true,
        ),
        TelemetryEvent.VideoFramesDropped(
            SESSION,
            CONTENT,
            WALL_MS,
            MONO_MS,
            droppedFrames = 7,
            repeatedFrames = 0,
            elapsedPlayingMs = 10_000,
        ),
    )

    private companion object {
        const val SESSION = "8f21c0de"
        const val CONTENT = "urn:content:12345"
        const val WALL_MS = 1_757_500_000_000L
        const val MONO_MS = 942_310L

        val DECISION = PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 50_000,
                maxBufferMs = 50_000,
                bufferForPlaybackMs = 2_500,
                bufferForPlaybackAfterRebufferMs = 5_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = Int.MAX_VALUE,
                maxVideoHeightPx = Int.MAX_VALUE,
            ),
        )
    }
}
