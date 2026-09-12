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
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recorder through the public API and the harness: what a trace contains, what it must not,
 * and that the same session traces identically every time — the property a golden stands on.
 */
@RunWith(AndroidJUnit4::class)
class SessionTraceRecorderTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private class Recording(val player: SuperPlayer, val collector: QoeCollector, val recorder: SessionTraceRecorder)

    private fun record(content: TestContent, network: com.superplayer.testkit.ThroughputTrace? = null): Recording {
        val recorder = SessionTraceRecorder()
        val collector = QoeCollector(recorder)
        val player = harness.buildPlayer(content = content, telemetry = collector, network = network)
        recorder.attach(player)
        return Recording(player, collector, recorder)
    }

    private fun Recording.finish(): SessionTrace {
        harness.release(player)
        assertThat(collector.awaitDelivered(DELIVERY_TIMEOUT_MS)).isTrue()
        return recorder.trace()
    }

    private fun playThrough(content: TestContent, source: String = content.sourceUri): SessionTrace {
        val recording = record(content)
        recording.player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(source).build())
        harness.playToReady(recording.player)
        harness.advanceUntil(recording.player, "the end of the content", 2 * TestContent.DEFAULT_DURATION_MS) {
            it.playbackState == Player.STATE_ENDED
        }
        return recording.finish()
    }

    @Test
    fun aSessionTracesItsStatesTracksLoadsAndTelemetryInOrder() {
        val trace = playThrough(TestContent.dash())
        val text = trace.format()

        assertThat(text).startsWith("${SessionTrace.FORMAT_HEADER}\ntimings relative-ms\n")
        val kinds = trace.lines.map { it.split(' ')[1] }
        assertThat(kinds).containsAtLeast("item", "state", "playing", "tracks", "load", "telemetry")
        assertThat(trace.lines).contains("+0 item id=$CONTENT reason=PLAYLIST_CHANGED")
        assertThat(trace.lines.filter { it.contains(" state ") }.map { it.substringAfter(" state ") })
            .containsExactly("BUFFERING", "READY", "ENDED").inOrder()
        assertThat(trace.lines.filter { it.contains(" load started manifest") }).isNotEmpty()
        // The synthetic streams are audio-only, which is a fact about superplayer-testmedia.
        assertThat(trace.lines.filter { it.contains(" load completed init:audio") }).hasSize(1)
        assertThat(trace.lines).contains("+0 tracks video=none audio=128000")
        assertThat(trace.lines.filter { it.contains(" telemetry SessionStarted profile=VIDEO_ON_DEMAND") }).hasSize(1)
        assertThat(trace.lines.last()).endsWith(" telemetry SessionEnded droppedEventCount=0")

        // Time never runs backwards down the trace.
        val times = trace.lines.map { it.substringBefore(' ').removePrefix("+").toLong() }
        assertThat(times).isEqualTo(times.sorted())
    }

    @Test
    fun theSameSessionProducesTheSameTraceOnEveryRun() {
        val runs = (1..RUNS).map { playThrough(TestContent.dash()).format() }

        runs.drop(1).forEachIndexed { index, run ->
            assertWithMessage("run ${index + 2} against run 1").that(run).isEqualTo(runs.first())
        }
    }

    @Test
    fun aTraceIsRedactedByConstruction() {
        // A signed URL, which the fake origin does not know, so the manifest load fails and Media3
        // writes the URL into the exception message — the two paths a token could reach a trace by.
        val content = TestContent.hls()
        val signed = "${content.sourceUri}?token=$TOKEN"
        val recording = record(content)
        recording.player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(signed).build())
        harness.playToFailure(recording.player)
        val text = recording.finish().format()

        assertThat(text).doesNotContain(TOKEN)
        assertThat(text).doesNotContain("token")
        assertThat(text).doesNotContain("://")
        assertThat(text).doesNotContain(content.sourceUri.substringAfter("://").substringBefore('/'))
        assertThat(text).doesNotContainMatch(UUID_PATTERN)
        assertThat(text).doesNotContain("Data not found")

        // The failure itself is still there, as a class and a code rather than a message.
        assertThat(text).contains(" load error manifest cause=IOException")
        assertThat(text).containsMatch(" error code=ERROR_CODE_IO_\\w+\n")
        assertThat(text).containsMatch(" telemetry StartupFailed category=\\w+ code=ERROR_CODE_IO_\\w+\n")
    }

    @Test
    fun aTraceWithoutTimingsKeepsTheOrderAndDropsTheColumn() {
        val timed = playThrough(TestContent.dash())
        val untimed = timed.withoutTimings()

        assertThat(untimed.timed).isFalse()
        assertThat(untimed.lines).hasSize(timed.lines.size)
        assertThat(untimed.lines).isEqualTo(timed.lines.map { it.substringAfter(' ') })
        assertThat(untimed.format()).startsWith("${SessionTrace.FORMAT_HEADER}\ntimings omitted\n")
        assertThat(untimed.withoutTimings()).isSameInstanceAs(untimed)
    }

    @Test
    fun formatAndParseAreInverses() {
        val timed = playThrough(TestContent.dash())

        assertThat(SessionTrace.parse(timed.format())).isEqualTo(timed)
        assertThat(SessionTrace.parse(timed.withoutTimings().format())).isEqualTo(timed.withoutTimings())
        assertThat(runCatching { SessionTrace.parse("not a trace\n") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun aRecorderRecordsOnePlayer() {
        val first = record(TestContent.video())
        val second = harness.buildPlayer(content = TestContent.video())

        val failure = runCatching { first.recorder.attach(second) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
        const val TOKEN = "SIGNED-4f9c1e2b7a"
        const val RUNS = 3
        const val DELIVERY_TIMEOUT_MS = 10_000L
        const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    }
}
