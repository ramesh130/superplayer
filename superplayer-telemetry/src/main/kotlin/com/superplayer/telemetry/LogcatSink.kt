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
import com.superplayer.core.PlaybackFailure
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink

/**
 * A [TelemetrySink] that writes every event to logcat, one line each.
 *
 * ```kotlin
 * .setTelemetry(QoeCollector(TelemetrySink.composite(analytics, LogcatSink)))
 * ```
 *
 * ## One line, and why that is the whole design
 *
 * This sink exists to be used during an incident, on someone else's device, through `adb logcat`
 * over a cable — which means the only property that matters is that a person can find what they
 * need with `grep`. A multi-line dump of a pretty-printed event is unfindable: `grep rebuffer`
 * returns the header and none of the numbers, and interleaving from another thread splits the record
 * in half. So every event is exactly one line, and every line has the same shape:
 *
 * ```text
 * evt=rebuffer_ended v=1 sid=8f21… cid="urn:content:12345" t=1757500000000 mono=942310 durationMs=1840 seekInduced=false
 * ```
 *
 * `key=value`, space-separated, the discriminator first so `grep 'evt=rebuffer'` is a whole class of
 * event and `grep sid=8f21` is a whole session. Values that can contain a space are quoted; a
 * newline inside one is escaped rather than passed through, because a message from a failure is the
 * one field that can arrive with either and it is the field most likely to be read during an
 * incident.
 *
 * ## Level
 *
 * `INFO`, except the two failure events, which are `WARN`. That is enough for `logcat *:W` to filter
 * to the failures without changing the tag, and it is not `ERROR`, because a startup failure that
 * the resilience ladder then recovers from is not the app's error to triage.
 *
 * ## Cost
 *
 * A line is formatted for every event whether or not anything is listening, which is what makes this
 * a sink to attach in a debug build and to think about before attaching in a release one. It is
 * declared `object` rather than a class because it holds nothing; `PRD.md` §2.2 spells it that way
 * for the same reason.
 */
public object LogcatSink : TelemetrySink {

    /** The tag every line carries. Grep this to get the whole of SuperPlayer's telemetry. */
    public const val TAG: String = "SuperPlayerQoE"

    override fun onEvent(event: TelemetryEvent) {
        val line = format(event)
        Log.println(line.level, TAG, line.text)
    }

    /**
     * The one place the line format lives.
     *
     * A `when` without an `else`, deliberately: [TelemetryEvent] is sealed, so a later release that
     * adds an event fails to compile here rather than logging it as a name and nothing else. That is
     * exactly the trade-off `TelemetrySink`'s KDoc tells a *consumer* to avoid — but this sink ships
     * with the vocabulary, so being told is the point.
     *
     * The level is decided in the same branch as the text rather than by a second test on the event
     * type outside it. Two switches on one type is how a later failure event gets added at `INFO` by
     * omission, which is the omission this `when` was chosen to make impossible.
     */
    private fun format(event: TelemetryEvent): Line {
        val common = "v=${event.schemaVersion} sid=${event.sessionId} cid=${quote(event.contentId)} " +
            "t=${event.timestampMs} mono=${event.monotonicTimeMs}"
        return when (event) {
            is TelemetryEvent.SessionStarted -> info(
                "evt=session_started $common profile=${event.profile} " +
                    "minBufferMs=${event.decision.buffer.minBufferMs} " +
                    "maxBufferMs=${event.decision.buffer.maxBufferMs} " +
                    "maxBitrateBps=${event.decision.trackSelection.maxVideoBitrateBps}",
            )

            is TelemetryEvent.DecisionChanged -> info(
                "evt=decision_changed $common trigger=${event.trigger} " +
                    "minBufferMs=${event.decision.buffer.minBufferMs} " +
                    "maxBufferMs=${event.decision.buffer.maxBufferMs} " +
                    "maxBitrateBps=${event.decision.trackSelection.maxVideoBitrateBps}",
            )

            is TelemetryEvent.SessionEnded ->
                info("evt=session_ended $common dropped=${event.droppedEventCount}")

            is TelemetryEvent.FirstFrameRendered -> info(
                "evt=first_frame $common ttffMs=${event.timeToFirstFrameMs} from=${event.startBoundary}",
            )

            is TelemetryEvent.RebufferStarted ->
                info("evt=rebuffer_started $common seekInduced=${event.seekInduced}")

            is TelemetryEvent.RebufferEnded -> info(
                "evt=rebuffer_ended $common durationMs=${event.durationMs} seekInduced=${event.seekInduced}",
            )

            // WARN, and not ERROR: a startup failure the fallback ladder then recovers from is not
            // the app's error to triage. `logcat SuperPlayerQoE:W` is the filter for these two.
            is TelemetryEvent.StartupFailed ->
                warn("evt=startup_failed $common ${failure(event.failure)}")

            is TelemetryEvent.MidStreamFailed -> warn(
                "evt=midstream_failed $common ${failure(event.failure)} positionMs=${event.positionMs}",
            )

            is TelemetryEvent.TrackSwitched -> info(
                "evt=track_switched $common direction=${event.direction} " +
                    "fromBps=${event.fromBitrateBps} toBps=${event.toBitrateBps}",
            )

            is TelemetryEvent.SeekRequested -> info(
                "evt=seek_requested $common fromMs=${event.fromPositionMs} toMs=${event.toPositionMs}",
            )

            is TelemetryEvent.SeekCompleted -> info(
                "evt=seek_completed $common toMs=${event.toPositionMs} latencyMs=${event.seekLatencyMs}",
            )

            is TelemetryEvent.LiveLatencySampled -> info(
                "evt=live_latency $common latencyMs=${event.liveLatencyMs} " +
                    "targetMs=${event.targetLiveLatencyMs}",
            )

            is TelemetryEvent.PlaybackStateSampled -> info(
                "evt=state_sample $common intervalMs=${event.samplingIntervalMs} " +
                    "bitrateBps=${event.videoBitrateBps} bufferedMs=${event.bufferedDurationMs} " +
                    "playing=${event.playing}",
            )

            is TelemetryEvent.VideoFramesDropped -> info(
                "evt=video_frames $common dropped=${event.droppedFrames} " +
                    "repeated=${event.repeatedFrames} elapsedPlayingMs=${event.elapsedPlayingMs}",
            )
        }
    }

    /** One formatted event: the line, and the level it is worth reading at. */
    private class Line(val level: Int, val text: String)

    private fun info(text: String): Line = Line(Log.INFO, text)

    private fun warn(text: String): Line = Line(Log.WARN, text)

    /**
     * A failure as fields. [PlaybackFailure.category] is an enum and needs no quoting; the other two
     * are engine-supplied strings and both get it — a code is no more this sink's to trust than a
     * message is, and an engine that puts a space in one splits a field silently.
     */
    private fun failure(failure: PlaybackFailure): String =
        "category=${failure.category} code=${quote(failure.code)} message=${quote(failure.message)}"

    /**
     * Quotes a value that a person supplied and this sink therefore cannot assume anything about.
     *
     * A content id is the app's own string and an engine message is whatever the engine said; either
     * can carry a space, and a message can carry a newline. A newline would end the line, which is
     * the one thing this format promises not to do.
     */
    private fun quote(value: String?): String =
        if (value == null) {
            "null"
        } else {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\""
        }
}
