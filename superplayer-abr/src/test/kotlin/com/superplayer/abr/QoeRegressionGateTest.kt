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

import android.app.ActivityManager
import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.telemetry.QoeCollector
import com.superplayer.telemetry.QoeScore
import com.superplayer.telemetry.SessionMetrics
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeoutException

/**
 * The QoE regression gate `PRD.md` Part 5 asks for: every `NetworkProfile` replayed three times through
 * the adaptive policy, each session scored with the QoE objective, and `check` failed when a score drops
 * past its committed floor by more than [MARGIN].
 *
 * `docs/testing.md`, *The QoE regression gate*, is the manual — where it runs, why there, what a
 * failure means and how a floor moves. What this class fixes is the session each trace is judged on,
 * and it is the benchmark's arm (c) with the adaptive policy in it: the benchmark's VOD ladder and
 * session length, `QoeCollector` as the sink's source, and `SessionMetrics` and `QoeScore` from
 * `superplayer-telemetry` as the reduction, so a gate score and a benchmark score for the same
 * session are the same number by construction.
 */
@RunWith(AndroidJUnit4::class)
class QoeRegressionGateTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Written from the telemetry delivery thread, read from this one. */
    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

    @Before
    fun aCapableDevice() {
        // Robolectric's default 16 MB heap would cap every buffer decision under the heap branch,
        // which is right for a device that small and is not the device the floors describe.
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activityManager).setMemoryClass(LARGE_HEAP_MB)
    }

    @After
    fun forgetTheProcessMemory() {
        EstimateMemory.PROCESS.forget()
    }

    @Test
    fun noTraceScoresBelowItsCommittedFloor() {
        // Every profile, not a chosen few: a change that wins on cellular by losing on stable WiFi has
        // to meet the WiFi floor too, and a seventh profile arrives in the gate without an edit here —
        // failing for want of a floor until one is committed.
        val runs = NetworkProfile.entries.associate { network ->
            network.name to List(RUNS_PER_TRACE) { playAndScore(network) }
        }
        val measured = runs.mapValues { (_, scores) -> median(scores) }
        val floorsFile = File(QoeGate.FLOORS_PATH)
        val floors = if (floorsFile.isFile) QoeGate.parseFloors(floorsFile.readText()) else emptyMap()

        val report = QoeGate.judge(floors, measured, MARGIN)
        val rendered = buildString {
            append(QoeGate.render(report))
            // The runs behind each median, when they disagreed by more than the margin: a reviewer
            // should see an outlier the median absorbed rather than learn later that one was there.
            // Rounding-sized disagreement is not worth a line.
            runs.filterValues(::disagreePastTheMargin).forEach { (trace, scores) ->
                append("\n$trace's $RUNS_PER_TRACE runs disagreed; scores: ")
                appendLine(scores.joinToString { it?.let { b -> QoeGate.number(b.score) } ?: "none" })
            }
        }
        println(rendered)
        // Released players leave posts on the main looper; Robolectric appends a note about them to
        // any failure, which would sit under the table a reviewer is meant to read.
        shadowOf(Looper.getMainLooper()).idle()
        if (!report.passed) throw AssertionError(rendered)
    }

    private fun disagreePastTheMargin(scores: List<QoeScore.Breakdown?>): Boolean {
        if (scores.any { it == null }) return scores.any { it != null }
        val values = scores.map { it!!.score }
        return values.max() - values.min() > MARGIN
    }

    /** The median run by score, or null when any run had no score — a failed session is never outvoted. */
    private fun median(scores: List<QoeScore.Breakdown?>): QoeScore.Breakdown? {
        if (scores.any { it == null }) return null
        return scores.filterNotNull().sortedBy { it.score }[scores.size / 2]
    }

    /** One session over [network], from a fresh player to a released one, as its score's terms. */
    private fun playAndScore(network: NetworkProfile): QoeScore.Breakdown? {
        // Each trace starts from a cold estimate. The memory is per process by design (ADR-0009
        // rule 8), so without this a trace's score would depend on which traces ran before it.
        EstimateMemory.PROCESS.forget()
        events.clear()

        val content = TestContent.videoLadder(bitratesBps = LADDER_BPS, durationMs = CONTENT_MS)
        val player = harness.buildPlayer(
            content = content,
            profile = PROFILE,
            telemetry = QoeCollector(TelemetrySink { events += it }),
            network = network.trace,
            policy = AdaptivePolicy.forProfile(context, PROFILE),
        )
        player.declarePlaybackIntent()
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        player.prepare()
        player.play()
        harness.advanceUntil(player, "ready or failed", READY_BOUND_MS) {
            it.playbackState == Player.STATE_READY || it.playerError != null
        }
        // A span, and deliberately: the span *is* the measurement, which is the case `docs/testing.md`,
        // Determinism, allows one for. It is the benchmark's session length, so the two agree.
        if (player.playerError == null) harness.advanceTimeInStepsMs(player, SESSION_MS)
        harness.release(player)

        // Delivery is asynchronous by design (ADR-0008 rules 3 and 4); the session is over when its
        // `SessionEnded` has reached the sink, and Media3's own wait says so or times out.
        //
        // A session that never ends, or ends incomplete, has no score rather than an exception: the
        // gate's report is what a reviewer reads, and a broken trace belongs in it as a `no score`
        // row beside the five that played, not as a stack trace that hides them.
        val ended = try {
            runMainLooperUntil { synchronized(events) { events.any { it is TelemetryEvent.SessionEnded } } }
            true
        } catch (_: TimeoutException) {
            false
        }
        val all = synchronized(events) { events.toList() }
        if (!ended || all.isEmpty()) return null
        val metrics = SessionMetrics.from(all.first().sessionId, all)
        if (!metrics.usable) return null
        return QoeScore.breakdown(metrics, LADDER_BPS.max())
    }

    private companion object {

        /**
         * How far a score may fall below its floor before the gate fails, in Mbps-equivalent per
         * second played. `docs/testing.md`, *The QoE regression gate*, argues the value.
         */
        const val MARGIN = 0.05

        /**
         * How many sessions each trace plays; the gate judges the median one. Odd, so the median is a
         * run that happened. `docs/testing.md`, *The QoE regression gate*, has the measurement behind it.
         */
        const val RUNS_PER_TRACE = 3

        val PROFILE = PlaybackProfile.VIDEO_ON_DEMAND

        /** The benchmark's `FULL_LADDER` (`benchmark/…/Scenario.kt`, which cites its shape). */
        val LADDER_BPS = listOf(365_000, 730_000, 2_000_000, 4_500_000)

        /** The benchmark's session: six sampling intervals, three `LTE_WITH_DROPOUTS` periods. */
        const val SESSION_MS = 60_000L

        /** Longer than the session, so playing time never ends at the content's end. */
        const val CONTENT_MS = 300_000L

        const val READY_BOUND_MS = 60_000L
        const val LARGE_HEAP_MB = 2_048
        const val CONTENT_ID = "qoe-gate:ladder"
    }
}
