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

import com.superplayer.core.TelemetryEvent
import java.io.File

/**
 * Every event of every run, written out beside the summary.
 *
 * `PRD.md` §6: **publish the raw traces.** The rule is there because a summary is an argument and a
 * trace is evidence — a reader who doubts a rebuffer ratio can recompute it, and a reader who wants
 * a statistic this report did not think to print can go and take it. It is also the only way the
 * honesty rules above it are checkable by somebody who did not write the harness: a cell whose
 * summary and whose traces disagree is a cell somebody can catch.
 *
 * ## The format
 *
 * One JSON object per line — JSONL — one file per cell, named for the cell. A line is one event, and
 * it carries the cell and the run index alongside the event's own fields, so that a file can be
 * `grep`ped, concatenated with another, or read a line at a time by something with no JSON parser
 * for arrays. `grep '"evt":"rebuffer_ended"'` is a class of event and `grep '"run":3'` is one
 * session, which is the same property `LogcatSink`'s one-line-per-event format is chosen for.
 *
 * The field names are `LogcatSink`'s, deliberately: the same event written by the two things this
 * project ships for looking at events should not need two vocabularies, and somebody moving between
 * a device's logcat and a benchmark's trace should not have to translate. `LogcatSink` is the
 * original and this follows it.
 *
 * ## Why the JSON is written by hand
 *
 * Because the alternative is a dependency. `CONTRIBUTING.md` requires a `THIRD_PARTY.md` row for
 * every one, and a serialization library earns its place when the shapes are many or when they
 * change; here there are thirteen event types in a sealed hierarchy that a compiler exhaustiveness
 * check already guards, and the whole encoder is [escape] plus a `when`. Adding kotlinx.serialization
 * to a benchmark would put a dependency in the tree of the thing that measures the library.
 */
internal object TraceWriter {

    /**
     * Writes [events] as one JSONL file for [key], under [directory], and returns the file.
     *
     * [runOf] says which run each event belongs to. The runs of a cell share a file because the cell
     * is the unit anybody compares — twenty files per cell would be six hundred files in a matrix
     * this size, and the first thing a reader would do is concatenate them.
     */
    fun write(
        directory: File,
        key: CellKey,
        events: List<TelemetryEvent>,
        runOf: (TelemetryEvent) -> Int,
    ): File {
        directory.mkdirs()
        val file = File(directory, fileNameFor(key))
        file.bufferedWriter().use { writer ->
            events.forEach { event ->
                writer.write(line(key, runOf(event), event))
                writer.newLine()
            }
        }
        return file
    }

    /**
     * A file name for [key] that sorts sensibly and survives every filesystem.
     *
     * Scenario, then network, then arm, so an `ls` groups a scenario's networks together and a
     * network's three arms sit adjacent — which is the order somebody comparing arms reads them in.
     */
    fun fileNameFor(key: CellKey): String =
        "${slug(key.scenario.label)}__${slug(key.network.label)}__${slug(key.arm.label)}.jsonl"

    /** One event as one JSON object. See the class KDoc for why the field names are `LogcatSink`'s. */
    private fun line(key: CellKey, run: Int, event: TelemetryEvent): String {
        val fields = mutableListOf<String>()
        fields += "\"evt\":${string(discriminator(event))}"
        // The cell, on every line, so that concatenating two files loses nothing. `arm` in
        // particular is here rather than left to be read off `SessionStarted.profile`, which for a
        // stock arm names the cell being compared against rather than a setting inside the player —
        // see `StockTelemetry.startSession`.
        fields += "\"scenario\":${string(key.scenario.label)}"
        fields += "\"network\":${string(key.network.label)}"
        fields += "\"arm\":${string(key.arm.label)}"
        fields += "\"run\":$run"
        fields += "\"v\":${event.schemaVersion}"
        fields += "\"sid\":${string(event.sessionId)}"
        fields += "\"cid\":${string(event.contentId)}"
        fields += "\"t\":${event.timestampMs}"
        fields += "\"mono\":${event.monotonicTimeMs}"
        fields += specificFields(event)
        return "{${fields.joinToString(",")}}"
    }

    /**
     * The discriminator, as `LogcatSink` spells it.
     *
     * Exhaustive over the sealed hierarchy with no `else`, which is the point: a fourteenth event
     * type added to `superplayer-core` fails to compile here rather than being written out under a
     * name this file invented, or silently omitted from the traces the report claims are complete.
     */
    private fun discriminator(event: TelemetryEvent): String = when (event) {
        is TelemetryEvent.SessionStarted -> "session_started"
        is TelemetryEvent.SessionEnded -> "session_ended"
        is TelemetryEvent.FirstFrameRendered -> "first_frame"
        is TelemetryEvent.RebufferStarted -> "rebuffer_started"
        is TelemetryEvent.RebufferEnded -> "rebuffer_ended"
        is TelemetryEvent.StartupFailed -> "startup_failed"
        is TelemetryEvent.MidStreamFailed -> "midstream_failed"
        is TelemetryEvent.TrackSwitched -> "track_switched"
        is TelemetryEvent.SeekRequested -> "seek_requested"
        is TelemetryEvent.SeekCompleted -> "seek_completed"
        is TelemetryEvent.LiveLatencySampled -> "live_latency"
        is TelemetryEvent.PlaybackStateSampled -> "state_sample"
        is TelemetryEvent.VideoFramesDropped -> "video_frames"
    }

    /** Each event's own fields, in `LogcatSink`'s names. Exhaustive for [discriminator]'s reason. */
    private fun specificFields(event: TelemetryEvent): String = when (event) {
        is TelemetryEvent.SessionStarted ->
            "\"profile\":${string(event.profile.name)}," +
                "\"minBufferMs\":${event.decision.buffer.minBufferMs}," +
                "\"maxBufferMs\":${event.decision.buffer.maxBufferMs}," +
                "\"bufferForPlaybackMs\":${event.decision.buffer.bufferForPlaybackMs}," +
                "\"bufferForPlaybackAfterRebufferMs\":${event.decision.buffer.bufferForPlaybackAfterRebufferMs}," +
                "\"backBufferMs\":${event.decision.buffer.backBufferMs}," +
                "\"maxVideoBitrateBps\":${event.decision.trackSelection.maxVideoBitrateBps}," +
                "\"maxVideoHeightPx\":${event.decision.trackSelection.maxVideoHeightPx}"

        is TelemetryEvent.SessionEnded -> "\"dropped\":${event.droppedEventCount}"

        is TelemetryEvent.FirstFrameRendered ->
            "\"ttffMs\":${event.timeToFirstFrameMs},\"from\":${string(event.startBoundary.name)}"

        is TelemetryEvent.RebufferStarted -> "\"seekInduced\":${event.seekInduced}"

        is TelemetryEvent.RebufferEnded ->
            "\"durationMs\":${event.durationMs},\"seekInduced\":${event.seekInduced}"

        is TelemetryEvent.StartupFailed -> failure(event.failure)

        is TelemetryEvent.MidStreamFailed -> "${failure(event.failure)},\"positionMs\":${event.positionMs}"

        is TelemetryEvent.TrackSwitched ->
            "\"direction\":${string(event.direction.name)}," +
                "\"fromBitrateBps\":${event.fromBitrateBps ?: "null"}," +
                "\"toBitrateBps\":${event.toBitrateBps}"

        is TelemetryEvent.SeekRequested ->
            "\"fromMs\":${event.fromPositionMs},\"toMs\":${event.toPositionMs}"

        is TelemetryEvent.SeekCompleted ->
            "\"toMs\":${event.toPositionMs},\"latencyMs\":${event.seekLatencyMs}"

        is TelemetryEvent.LiveLatencySampled ->
            "\"latencyMs\":${event.liveLatencyMs},\"targetMs\":${event.targetLiveLatencyMs ?: "null"}"

        is TelemetryEvent.PlaybackStateSampled ->
            "\"intervalMs\":${event.samplingIntervalMs}," +
                "\"videoBitrateBps\":${event.videoBitrateBps ?: "null"}," +
                "\"bufferedMs\":${event.bufferedDurationMs}," +
                "\"playing\":${event.playing}"

        is TelemetryEvent.VideoFramesDropped ->
            "\"dropped\":${event.droppedFrames}," +
                "\"repeated\":${event.repeatedFrames}," +
                "\"elapsedPlayingMs\":${event.elapsedPlayingMs}"
    }

    private fun failure(failure: com.superplayer.core.PlaybackFailure): String =
        "\"category\":${string(failure.category.name)}," +
            "\"code\":${failure.code?.let { string(it) } ?: "null"}," +
            "\"message\":${failure.message?.let { string(it) } ?: "null"}"

    /** A JSON string literal, quotes included. */
    private fun string(value: String): String = "\"${escape(value)}\""

    /**
     * JSON's string escapes, which is the whole of what this file needs of a JSON library.
     *
     * The control-character branch matters more than it looks: a `PlaybackFailure.message` is
     * whatever the engine said, and an engine message containing a newline would otherwise split one
     * event across two lines — which is the one promise a JSONL file makes.
     */
    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
    }

    /** A label as a file-name fragment: lower case, and nothing but letters, digits and dashes. */
    private fun slug(label: String): String =
        label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
