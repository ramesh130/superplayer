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

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.TelemetryEvent
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * `PRD.md` §6's matrix, run, and the report written.
 *
 * A JUnit class because Robolectric is a JUnit runner and there is no other way to get an Android
 * runtime on a JVM — **not** because this is a test. It asserts almost nothing about the numbers, and
 * that is deliberate: a benchmark that failed when a number moved would be a regression gate, and
 * `QoeScore` sets out why this project does not have one yet and when it should. What it does assert
 * is that the *run* was sound — that every cell ran, that the arms were measured on the same
 * boundary, that nothing was silently empty — because a report full of dashes is worse than a
 * failure.
 *
 * ```bash
 * benchmark/bench                       # the full matrix, 20 runs a cell, into benchmark/out
 * (cd benchmark && ./gradlew test)      # one run a cell: the runner still works
 * ```
 *
 * `benchmark/README.md` is the manual, including why this build sits outside `docs/testing.md`'s
 * no-device, no-network rule rather than against it.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkMatrixTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /**
     * Written from the delivery thread for arm (c), read from this one. See `TelemetryDelivery`:
     * arm (c)'s events arrive asynchronously, which is the whole point of that queue, so the list
     * they land in has to tolerate two threads even though the runner is single-threaded.
     */
    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

    @Test
    fun runsTheMatrixAndWritesTheReport() {
        val runsPerCell = intProperty(RUNS_PROPERTY, default = 1)
        val outputDirectory = File(System.getProperty(OUT_PROPERTY) ?: DEFAULT_OUT)
        val onlyCells = System.getProperty(CELLS_PROPERTY)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        val cells = mutableListOf<CellResult>()
        val traceDirectory = File(outputDirectory, "traces")
        outputDirectory.mkdirs()
        traceDirectory.deleteRecursively()

        Scenario.entries.forEach { scenario ->
            NetworkProfileName.entries.forEach { network ->
                Arm.entries.forEach { arm ->
                    val key = CellKey(scenario, network, arm)
                    if (onlyCells != null && onlyCells.none { key.toString().contains(it) }) return@forEach
                    cells += runCell(key, runsPerCell, traceDirectory)
                }
            }
        }

        val report = MatrixReport(
            conditions = RunConditions.read(repoRoot(), runsPerCell),
            cells = cells,
        )
        File(outputDirectory, "baseline-report.md").writeText(ReportWriter.write(report))
        println("[benchmark] report: ${File(outputDirectory, "baseline-report.md").absolutePath}")

        assertTheRunWasSound(report, runsPerCell)
    }

    /** Plays one cell [runs] times, writes its raw trace, and reduces it. */
    private fun runCell(key: CellKey, runs: Int, traceDirectory: File): CellResult {
        val sessions = mutableListOf<SessionMetrics>()
        val perRunEvents = mutableListOf<List<TelemetryEvent>>()

        repeat(runs) {
            val produced = playOneSession(key)
            sessions += SessionMetrics.from(produced.sessionId, produced.events)
            perRunEvents += produced.events
        }

        TraceWriter.write(traceDirectory, key, perRunEvents)
        val result = CellResult.of(key, sessions)
        println("[benchmark] $key: ${result.runs} run(s), ${result.excludedSessions} excluded")
        return result
    }

    /** One session of one arm, from a fresh player to a released one, and the events it produced. */
    private fun playOneSession(key: CellKey): Produced {
        events.clear()
        val content = contentFor(key.scenario)
        val trace = traceFor(key.network)

        return if (key.arm.isStock) {
            playStockSession(key, content, trace)
        } else {
            playSuperPlayerSession(key, content, trace)
        }
    }

    /** Arm (c): the shipped facade, the shipped collector, and the shipped profile for the scenario. */
    private fun playSuperPlayerSession(
        key: CellKey,
        content: TestContent,
        trace: com.superplayer.testkit.ThroughputTrace,
    ): Produced {
        val player = harness.buildPlayer(
            content = content,
            profile = key.scenario.profile,
            telemetry = QoeCollector { events += it },
            network = trace,
        )
        // Declared for every arm, so time to first frame is measured from `USER_INTENT` throughout.
        // The schema is explicit that `CONTENT_ADOPTED` reads lower and that the two must not be
        // aggregated; an arm measured from a different boundary would be the cheapest possible way
        // for this benchmark to produce a wrong headline number.
        player.declarePlaybackIntent()
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        play(player, key.scenario.playbackMs)
        // Releasing is what ends the session and emits `SessionEnded` — the event that carries the
        // dropped count, and therefore the one that decides whether this session may be aggregated.
        // Through the harness, so it stops tracking this player: see `PlaybackHarness.release`, and
        // note that a matrix builds well over a thousand of them inside this one test method.
        harness.release(player)
        return awaitSession()
    }

    /** Arms (a) and (b): a bare `ExoPlayer` under the same transport, measured by `StockTelemetry`. */
    private fun playStockSession(
        key: CellKey,
        content: TestContent,
        trace: com.superplayer.testkit.ThroughputTrace,
    ): Produced {
        val player = harness.buildStockPlayer(
            content = content,
            stockBufferPolicy = key.arm.stockBufferPolicy,
            network = trace,
        )
        val telemetry = StockTelemetry { events += it }
        telemetry.attach(player)
        telemetry.declareIntent(harness.elapsedRealtimeMs())
        // A stock player has no `MediaRequest` and no content identity — that is what arm (a) *is* —
        // so the session id and content id are the runner's. They name the run rather than anything
        // the player knows, which is exactly the gap `MediaRequest` exists to close.
        telemetry.startSession(
            contentId = CONTENT_ID,
            sessionId = UUID.randomUUID().toString(),
            profile = key.scenario.profile,
            decision = PlaybackDecision(
                buffer = key.arm.stockBufferPolicy ?: Arm.MEDIA3_DEFAULT_BUFFER_POLICY,
                trackSelection = Arm.STOCK_TRACK_SELECTION,
            ),
        )
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))
        play(player, key.scenario.playbackMs)
        telemetry.endSession()
        telemetry.detach()
        harness.release(player)
        return awaitSession()
    }

    /**
     * Prepares, plays, and advances the harness clock by [playbackMs].
     *
     * In steps rather than one jump, which is `PlaybackHarness.advanceTimeInStepsMs`'s own reasoning:
     * a load is asynchronous, so a single long advance gives the engine one pass and reaches the
     * second chunk of the session however far it jumped. A benchmark that did that would measure a
     * session that fetched two segments and then sat still.
     *
     * A startup failure is not an error here. A cell where every run fails to start is a finding —
     * `PRD.md` §6 reports a startup failure rate — so the wait is bounded by *either* readiness or a
     * player error, and a session that failed is measured as a failed session rather than throwing.
     */
    private fun play(player: Player, playbackMs: Long) {
        player.prepare()
        player.play()
        harness.advanceUntil(player, "ready or failed", READY_BOUND_MS) {
            it.playbackState == Player.STATE_READY || it.playerError != null
        }
        if (player.playerError == null) harness.advanceTimeInStepsMs(player, playbackMs)
    }

    /**
     * Waits until the session's terminal event has reached the sink, and returns everything of it.
     *
     * Arm (c)'s delivery is asynchronous and bounded by design (ADR-0008 rules 3 and 4), so reading
     * the list the instant `release()` returns is a race rather than a measurement.
     * `QoeCollector.awaitDelivered` is the collector's own hook for this and is `internal` to
     * `superplayer-telemetry` — deliberately, so that a consumer cannot call it from the thread that
     * design exists to keep out of a sink. This build is a consumer, so it waits the way any consumer
     * would: for the event that says the session is over.
     */
    private fun awaitSession(): Produced {
        val deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val ended = synchronized(events) { events.filterIsInstance<TelemetryEvent.SessionEnded>().lastOrNull() }
            if (ended != null) {
                val all = synchronized(events) { events.toList() }
                return Produced(ended.sessionId, all)
            }
            Thread.sleep(DELIVERY_POLL_MS)
        }
        // No `SessionEnded` within the bound means the run went wrong rather than the player did:
        // `SessionMetrics.usable` would exclude it, and a cell quietly losing every run to a stuck
        // delivery thread would report "n=0" and look like a measurement.
        val all = synchronized(events) { events.toList() }
        return Produced(all.firstOrNull()?.sessionId ?: "no-session", all)
    }

    /** The content a scenario is played over, as the harness's own description of it. */
    private fun contentFor(scenario: Scenario): TestContent = if (scenario.live) {
        TestContent.liveVideoLadder(
            bitratesBps = scenario.ladderBitratesBps,
            windowDurationMs = CONTENT_MS,
        )
    } else {
        TestContent.videoLadder(
            bitratesBps = scenario.ladderBitratesBps,
            durationMs = CONTENT_MS,
        )
    }

    /**
     * The trace for a network profile.
     *
     * Exhaustive with no `else`, on purpose: a seventh profile added to `PRD.md` §6 and to
     * `superplayer-testkit` fails to compile here rather than being quietly missing from the matrix,
     * which is the failure a report cannot show — an absent row looks like a row nobody needed.
     */
    private fun traceFor(network: NetworkProfileName) = when (network) {
        NetworkProfileName.STABLE_WIFI -> NetworkProfile.STABLE_WIFI
        NetworkProfileName.CONGESTED_WIFI -> NetworkProfile.CONGESTED_WIFI
        NetworkProfileName.LTE_WITH_DROPOUTS -> NetworkProfile.LTE_WITH_DROPOUTS
        NetworkProfileName.THREE_G -> NetworkProfile.THREE_G
        NetworkProfileName.WIFI_TO_CELLULAR_HANDOVER -> NetworkProfile.WIFI_TO_CELLULAR_HANDOVER
        NetworkProfileName.HIGH_LATENCY -> NetworkProfile.HIGH_LATENCY
    }.trace

    /**
     * What the runner asserts, which is about the run rather than about the numbers.
     *
     * Every one of these is a way the matrix could produce a plausible-looking report that meant
     * nothing, and none of them is a threshold on a measurement. A benchmark that failed because a
     * player got slower would be a regression gate; `QoeScore` says why this project does not have
     * one yet.
     */
    private fun assertTheRunWasSound(report: MatrixReport, runsPerCell: Int) {
        val expectedCells = Scenario.entries.size * NetworkProfileName.entries.size * Arm.entries.size
        if (System.getProperty(CELLS_PROPERTY) == null) {
            assertTrue(
                "The matrix ran ${report.cells.size} cells, not $expectedCells",
                report.cells.size == expectedCells,
            )
        }

        report.cells.forEach { cell ->
            // A cell that aggregated nothing is a cell whose column is dashes. That may be a real
            // finding — every run failing to start is one — but it may equally be the harness
            // failing to drive the arm, and the two look identical in a report.
            assertTrue(
                "${cell.key} aggregated no sessions at all (${cell.excludedSessions} excluded of $runsPerCell)",
                cell.runs > 0,
            )
            // The boundary has to be the same for every arm or the headline metric is incomparable.
            val boundaries = cell.sessions.mapNotNull { it.startBoundary }.toSet()
            assertTrue(
                "${cell.key} mixed time-to-first-frame boundaries: $boundaries",
                boundaries.size <= 1,
            )
            // Should never happen: the runner plays every session to a fixed span and abandons none.
            assertTrue(
                "${cell.key} reported ${cell.exitBeforeVideoStart} exit(s) before video start, " +
                    "which is a defect in the runner rather than a property of the player",
                cell.exitBeforeVideoStart == 0,
            )
        }
    }

    /** One session's id and every event of it, as the sink received them. */
    private data class Produced(val sessionId: String, val events: List<TelemetryEvent>)

    private fun intProperty(name: String, default: Int): Int =
        System.getProperty(name)?.toIntOrNull() ?: default

    /**
     * The repository root, which this build sits one directory inside.
     *
     * Gradle runs a test with the module directory as its working directory, so the parent is the
     * repository. Used to read the version catalog and to ask git what commit this is — see
     * [RunConditions], and note that neither failing stops a report being written.
     */
    private fun repoRoot(): File = File(workingDirectory()).absoluteFile.parentFile

    private companion object {

        /** The one content id every session uses; see [playStockSession] for why it is the runner's. */
        const val CONTENT_ID = "benchmark:ladder"

        /**
         * How long the asset is, against a session that plays [Scenario.playbackMs] of it.
         *
         * Comfortably longer, because a session that reaches the end of its content stops advancing
         * without producing a stall — which would silently overstate playing time, and
         * `SessionMetrics.playingMs` states that precondition rather than defending against it.
         */
        const val CONTENT_MS = 300_000L

        /** Playback time allowed for the first frame or a failure, before the wait is a bug. */
        const val READY_BOUND_MS = 60_000L

        /** Real milliseconds to wait for arm (c)'s asynchronous delivery to produce `SessionEnded`. */
        const val DELIVERY_TIMEOUT_MS = 10_000L
        const val DELIVERY_POLL_MS = 5L

        const val RUNS_PROPERTY = "superplayer.benchmark.runs"
        const val OUT_PROPERTY = "superplayer.benchmark.out"

        /**
         * A substring filter over cell names, for working on the harness rather than for a baseline.
         *
         * A report written with this set is not a baseline and the runner's completeness assertion
         * is skipped, because the matrix deliberately did not run. `benchmark/bench` never sets it.
         */
        const val CELLS_PROPERTY = "superplayer.benchmark.cells"

        val DEFAULT_OUT: String = File(workingDirectory(), "out").path

        /**
         * Where Gradle started this test, which is the module directory.
         *
         * Defaulted rather than asserted: `user.dir` is always set by a JVM, but it is typed
         * nullable through Java interop and a benchmark must not fail to write a report over it.
         */
        fun workingDirectory(): String = System.getProperty("user.dir") ?: "."
    }
}
