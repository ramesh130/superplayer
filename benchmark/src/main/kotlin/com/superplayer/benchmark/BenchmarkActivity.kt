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

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.superplayer.abr.AdaptivePolicy
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.telemetry.QoeCollector
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * The device arm: one arm, one public stream, one session, on a real device over a real network.
 *
 * **This exists for two numbers.** `PRD.md` §6 reports peak RSS and battery delta over a 30-minute
 * session, and neither can come from the Robolectric matrix — both are properties of a process on a
 * device with a battery, and reporting a JVM heap figure as though it were an Android app's resident
 * set would be a plausible wrong number. Everything else this activity reports, the Robolectric arm
 * reports better, because it is reproducible and this is not.
 *
 * Neither number is measured *here*. Peak RSS and battery are read from the process by Perfetto's
 * `process_memory` and `battery` data sources, which is `devicelab`'s job; this activity's job is to
 * play the right thing for the right length of time and to say what its own telemetry saw, so that
 * the two halves can be lined up afterwards. That split is deliberate — an app that measured its own
 * memory would be measuring the measurement.
 *
 * ```bash
 * adb shell am start -n com.superplayer.benchmark/.BenchmarkActivity \
 *     --es arm SUPERPLAYER --es stream "VOD, HLS" --el durationMs 1800000
 * ```
 *
 * ## What it writes
 *
 * One JSONL file per run under the app's external files directory, in `TraceWriter`'s format, so a
 * device trace and a Robolectric trace are read with the same tools. It also logs one line under
 * [TAG] when the run finishes, which is what a harness should wait for — a fixed sleep could not tell
 * a finished run from a stalled one, and this app publishes no media session for `dumpsys` to
 * report. Wiring that harness up is #95; `benchmark/README.md` has the manual recipe until then.
 *
 * ## Why this is not the demo
 *
 * `demo/` integrates SuperPlayer the way an app would, which is the point of it, and it therefore
 * cannot build arms (a) and (b) — there is no SuperPlayer in those. A benchmark that measured peak
 * RSS for arm (c) only would have a column and no comparison.
 */
internal class BenchmarkActivity : Activity() {

    private var player: Player? = null
    private var superPlayer: SuperPlayer? = null
    private var stockTelemetry: StockTelemetry? = null
    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val arm = intent.getStringExtra(EXTRA_ARM)?.let { name -> Arm.entries.firstOrNull { it.name == name } }
            ?: Arm.SUPERPLAYER
        val stream = intent.getStringExtra(EXTRA_STREAM)?.let { label ->
            PublicStreams.streams.firstOrNull { it.label == label }
        } ?: PublicStreams.streams.first()
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)

        // A bare SurfaceView rather than Media3's `PlayerView`: a video renderer with nowhere to draw
        // reports no first frame, so the arm needs an output, and nothing here looks at the pixels or
        // wants playback controls. It also keeps `media3-ui` out of this build, so the APK whose
        // resident set is being measured carries only what the arms actually need.
        val surface = SurfaceView(this)
        setContentView(surface, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Keep the screen on for the whole run, and note which problem this solves — it is the
        // battery metric's, not a convenience.
        //
        // Arm (c) gets a wake lock from core's own lifecycle correctness (ADR-0006 rule 1); arms (a)
        // and (b) are a bare `ExoPlayer` and get nothing, because that is what they *are*. So if the
        // screen times out five minutes into a thirty-minute run, SuperPlayer plays on and the stock
        // arms suspend — and the battery delta then reports SuperPlayer as far worse when it is the
        // only arm that actually ran. Nothing downstream could detect that: all three would write a
        // trace and finish.
        //
        // A window flag rather than a wake lock on the stock players, deliberately. Giving arms (a)
        // and (b) a wake lock would make them not the arms they are supposed to be; keeping the
        // screen on is a property of the *activity*, identical for all three, and it is also what a
        // viewer watching a 30-minute video has.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Log.i(TAG, "run arm=${arm.name} stream=\"${stream.label}\" uri=${stream.uri} durationMs=$durationMs")
        start(arm, stream, durationMs, surface)
    }

    private fun start(arm: Arm, stream: PublicStreams.Stream, durationMs: Long, surface: SurfaceView) {
        // Intent is declared before the player is even built, which is the boundary
        // `docs/telemetry-schema.md` puts time to first frame's start at: the seconds a viewer
        // experiences begin at their tap, not when the player is handed a URL. Every arm declares
        // it, so no arm is measured from a narrower boundary than another.
        val declaredAtMs = SystemClock.elapsedRealtime()

        if (arm.isStock) {
            val stock = StockPlayers.build(this, arm)
            val telemetry = StockTelemetry { events += it }
            telemetry.attach(stock)
            telemetry.declareIntent(declaredAtMs)
            telemetry.startSession(
                contentId = stream.label,
                sessionId = UUID.randomUUID().toString(),
                // See `StockTelemetry.startSession`: `profile` names the arm-(c) configuration these
                // numbers are compared against, and `decision` is what this arm actually ran with.
                profile = DEVICE_SCENARIO.profile,
                decision = PlaybackDecision(
                    buffer = arm.stockBufferPolicy ?: Arm.MEDIA3_DEFAULT_BUFFER_POLICY,
                    trackSelection = Arm.STOCK_TRACK_SELECTION,
                ),
            )
            stock.setVideoSurfaceView(surface)
            stock.setMediaItem(MediaItem.fromUri(stream.uri))
            stockTelemetry = telemetry
            player = stock
        } else {
            val built = SuperPlayer.Builder(this)
                .setProfile(DEVICE_SCENARIO.profile)
                .setTelemetry(QoeCollector { events += it })
                .apply {
                    if (arm == Arm.ADAPTIVE) setPolicy(AdaptivePolicy.forProfile(this@BenchmarkActivity, DEVICE_SCENARIO.profile))
                }
                .build()
            built.declarePlaybackIntent()
            built.setVideoSurfaceView(surface)
            built.setMediaRequest(
                MediaRequest.Builder(stream.label)
                    .addSource(stream.uri)
                    .setTitle(stream.label)
                    .build(),
            )
            superPlayer = built
            player = built
        }

        val active = checkNotNull(player)
        active.prepare()
        active.play()

        // A position line every few seconds, so a scenario can tell a run that is playing from one
        // that reached READY and stalled — the same distinction `devicelab/lib/playback.sh` draws
        // through the media session for the demo, which this app does not publish.
        handler.post(object : Runnable {
            override fun run() {
                val p = player ?: return
                Log.i(TAG, "progress positionMs=${p.currentPosition} state=${p.playbackState} playing=${p.isPlaying}")
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        })

        handler.postDelayed({ finishRun(arm, stream) }, durationMs)
    }

    /** Ends the session, writes the trace, and says so in a line a scenario can wait for. */
    private fun finishRun(arm: Arm, stream: PublicStreams.Stream) {
        handler.removeCallbacksAndMessages(null)
        stockTelemetry?.endSession()
        stockTelemetry?.detach()
        // Releasing is what ends arm (c)'s session, because core signals the session boundaries.
        superPlayer?.release()
        (player as? androidx.media3.exoplayer.ExoPlayer)?.takeIf { arm.isStock }?.release()
        player = null

        val collected = synchronized(events) { events.toList() }
        val key = CellKey(DEVICE_SCENARIO, NetworkProfileName.STABLE_WIFI, arm)
        val directory = File(getExternalFilesDir(null), "traces")
        // The device arm has one network — whatever the device is on — and the cell key's network
        // field names the profile a Robolectric row would carry. It is recorded as the label below
        // rather than pretended away, because a device row and a Robolectric row must not be read as
        // the same measurement.
        val file = TraceWriter.write(directory, key, listOf(collected))
        Log.i(
            TAG,
            "finished arm=${arm.name} stream=\"${stream.label}\" events=${collected.size} trace=${file.absolutePath}",
        )
        Log.i(TAG, DONE_LINE)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        superPlayer?.release()
        (player as? androidx.media3.exoplayer.ExoPlayer)?.release()
        player = null
        super.onDestroy()
    }

    private companion object {

        /** One tag for everything this app says, so `devicelab` greps one thing. */
        const val TAG = "SuperPlayerBench"

        /** What a scenario waits for. A fixed sleep cannot tell a finished run from a stalled one. */
        const val DONE_LINE = "run complete"

        const val EXTRA_ARM = "arm"
        const val EXTRA_STREAM = "stream"
        const val EXTRA_DURATION_MS = "durationMs"

        /** `PRD.md` §6: battery delta **over a 30-minute session**. That is where this number is from. */
        const val DEFAULT_DURATION_MS = 30 * 60 * 1_000L

        /** Often enough to see a stall, rarely enough not to be the thing consuming the battery. */
        const val PROGRESS_INTERVAL_MS = 5_000L

        /**
         * The scenario the device arm runs, and therefore the profile arm (c) is built with.
         *
         * On-demand: the public streams listed in [PublicStreams] are on-demand assets, and a
         * 30-minute session of one is what `PRD.md` §6's battery figure describes. The live and
         * short-form scenarios are the Robolectric arm's; the device arm's live cell is a documented
         * gap, for the reason [PublicStreams.gaps] gives.
         */
        val DEVICE_SCENARIO = Scenario.VOD
    }
}
