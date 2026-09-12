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

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import java.io.IOException

/**
 * Records a [SessionTrace] of one [SuperPlayer]: the engine's own state transitions, track
 * selections, loads and errors, read from Media3's analytics the way `QoeCollector` reads them, and
 * every `TelemetryEvent` this recorder is handed as a [TelemetrySink].
 *
 * Wiring, in the order that matters:
 *
 * ```kotlin
 * val recorder = SessionTraceRecorder()
 * val player = SuperPlayer.Builder(context)
 *     .setTelemetry(QoeCollector(TelemetrySink.composite(appSink, recorder)))
 *     .build()
 * recorder.attach(player)          // before the first setMediaRequest: time is measured from here
 * // ... play ...
 * player.release()
 * val trace = recorder.trace()     // after the telemetry delivery has drained; see below
 * ```
 *
 * This is a sink and not a collector because the collector already exists: `QoeCollector` derives
 * the CTA-2066 vocabulary, and a second derivation of the same metrics would be the drift
 * `docs/telemetry-schema.md` exists to prevent. What this adds is the engine layer under those
 * metrics, which is why it also registers Media3's `AnalyticsListener` — in this module, because
 * ADR-0008 rule 2 puts that registration here and not in core.
 *
 * ## Redaction rules
 *
 * A trace is meant to be attached to a bug report and read by a CDN engineer, so it is redacted by
 * construction: the recorder never holds the material, rather than stripping it later.
 *
 * 1. **No URL, host, path, query or token.** A load is named by what it is — its Media3 data type,
 *    track type and media time span — never by where it came from. `LoadEventInfo.uri` and
 *    `dataSpec` are not read. The one consumer string that is printed, the media id of an item
 *    transition, is withheld when it is shaped like a URI, because an app that names items by URL
 *    would otherwise put its signed URLs in every trace.
 * 2. **No request or response header.** `LoadEventInfo.responseHeaders` is not read.
 * 3. **No exception message.** Media3 puts the failing URL in the message of most I/O exceptions,
 *    so an error is recorded as its `PlaybackException` error-code name, an HTTP status when the
 *    cause carries one, or the exception's class name — and a telemetry failure as its category and
 *    code, never its `message`.
 * 4. **No session id and no wall clock.** Both differ every run; the session id is also the CMCD
 *    `sid` a CDN log could be joined on, which is a correlation the trace should not offer by
 *    accident. Time is milliseconds since [attach].
 * 5. **No device identity.** Nothing about the device, build, account or network is recorded.
 * 6. **No DRM payload.** A license request or response appears as `load ... drm` and nothing more.
 *
 * `SessionTraceRecorderTest` drives a session whose source URL carries a token and whose failure
 * message names it, and asserts none of it reaches the trace.
 *
 * ## Threads and timing
 *
 * Analytics arrive on the player's application thread and telemetry on SuperPlayer's delivery
 * thread; the recorder is safe to call from both, and [trace] from any third. What [trace] cannot
 * do is wait for delivery — that would put a consumer's thread behind the queue ADR-0008 rule 4
 * keeps engine threads out of — so a trace taken before the queue has drained lacks the tail of the
 * telemetry. A test waits through the collector's own idle signal; an app takes the trace when its
 * sink has seen `SessionEnded`, which is the last event a session emits.
 *
 * An engine fact is stamped with the time the *engine* recorded it — `EventTime.realtimeMs`, the
 * engine's clock at the moment the analytics event was generated — and not with the time this
 * recorder heard of it, which is a looper hop later and, under a fake clock stepped by a harness,
 * can be a whole step later on one run and not the next. That is the opposite of the choice
 * `QoeCollector` makes and for the opposite reason: a duration in the schema must be a difference
 * on one named clock, while a fact in a trace must sit at the moment it happened. A telemetry
 * fact keeps the time its event carries. Both are relative to `SystemClock.elapsedRealtime()` at
 * [attach]; under the harness the engine's clock and the system clock are one fake clock, and on
 * a device both are elapsed realtime.
 */
public class SessionTraceRecorder : TelemetrySink {

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var originMs: Long = C.TIME_UNSET
    private var player: SuperPlayer? = null
    private var sequence: Long = 0

    /** Starts recording [player]. Time in the trace is measured from this call. */
    public fun attach(player: SuperPlayer) {
        synchronized(lock) {
            check(this.player == null) { "A SessionTraceRecorder records one player; build one per player" }
            this.player = player
            originMs = SystemClock.elapsedRealtime()
        }
        player.exoPlayer.addAnalyticsListener(analyticsListener)
    }

    /** Stops recording engine facts. Telemetry handed to the sink after this is still recorded. */
    public fun detach() {
        val attached = synchronized(lock) { player.also { player = null } } ?: return
        attached.exoPlayer.removeAnalyticsListener(analyticsListener)
    }

    /** Everything recorded so far, in trace order. Safe to call at any time and more than once. */
    public fun trace(): SessionTrace {
        val ordered = synchronized(lock) {
            entries.sortedWith(compareBy({ it.timeMs }, { it.kind.ordinal }, { it.key }, { it.sequence }))
        }
        return SessionTrace(ordered.map { "+${it.timeMs} ${it.kind.token} ${it.text}" }, timed = true)
    }

    override fun onEvent(event: TelemetryEvent) {
        // Keyed by nothing: the delivery queue is FIFO on one thread, so within a millisecond the
        // emission order is the deterministic one, and it keeps SessionEnded last.
        val name = event::class.java.simpleName
        record(event.monotonicTimeMs, Kind.TELEMETRY, "", "$name ${describe(event)}")
    }

    private fun record(atMs: Long, kind: Kind, key: String, text: String) {
        synchronized(lock) {
            val origin = originMs
            check(origin != C.TIME_UNSET) { "attach before recording" }
            entries += Entry(atMs - origin, kind, key, text, sequence++)
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
            record(eventTime.realtimeMs, Kind.ITEM, "", "id=${itemName(mediaItem)} reason=${transitionReason(reason)}")
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            record(eventTime.realtimeMs, Kind.STATE, "", stateName(state))
        }

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            record(eventTime.realtimeMs, Kind.PLAYING, "", isPlaying.toString())
        }

        override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
            val text = "video=${selectedBitrate(tracks, C.TRACK_TYPE_VIDEO)} " +
                "audio=${selectedBitrate(tracks, C.TRACK_TYPE_AUDIO)}"
            record(eventTime.realtimeMs, Kind.TRACKS, "", text)
        }

        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val text = "${discontinuityReason(reason)} from=${oldPosition.positionMs} to=${newPosition.positionMs}"
            record(eventTime.realtimeMs, Kind.DISCONTINUITY, "", text)
        }

        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            retryCount: Int,
        ) {
            val retry = if (retryCount > 0) " retry=$retryCount" else ""
            load(eventTime, Phase.STARTED, mediaLoadData, retry)
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            load(eventTime, Phase.COMPLETED, mediaLoadData)
        }

        override fun onLoadCanceled(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            load(eventTime, Phase.CANCELED, mediaLoadData)
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            // Rule 3: the class or the status, never the message.
            val cause = (error as? HttpDataSource.InvalidResponseCodeException)
                ?.let { "http=${it.responseCode}" }
                ?: "cause=${error.javaClass.simpleName}"
            load(eventTime, Phase.ERROR, mediaLoadData, " $cause")
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            record(eventTime.realtimeMs, Kind.ERROR, "", "code=${error.errorCodeName}")
        }

        // Rule 1: a load is named by what it is, not by where it came from.
        private fun load(eventTime: AnalyticsListener.EventTime, phase: Phase, data: MediaLoadData, suffix: String = "") {
            val dataType = dataType(data.dataType)
            val trackType = trackType(data.trackType)
            val resource = buildString {
                append(dataType)
                if (data.trackType != C.TRACK_TYPE_UNKNOWN && data.trackType != C.TRACK_TYPE_NONE) {
                    append(':').append(trackType)
                }
                if (data.mediaStartTimeMs != C.TIME_UNSET && data.mediaEndTimeMs != C.TIME_UNSET) {
                    append(' ').append(data.mediaStartTimeMs).append('-').append(data.mediaEndTimeMs)
                }
                data.trackFormat?.bitrate?.takeIf { it != Format.NO_VALUE }?.let { append(" bitrate=").append(it) }
            }
            // Loads are the one kind whose arrival order is a real thread's: two chunks that finished
            // in the same millisecond are reported in whichever order the loading threads won. So
            // within a millisecond they sort by what they are — data type, track, media time as a
            // number rather than as text — and then by phase, so a start precedes its end.
            val key = String.format(
                java.util.Locale.ROOT,
                "%s:%s %020d %d",
                dataType,
                trackType,
                if (data.mediaStartTimeMs == C.TIME_UNSET) 0L else data.mediaStartTimeMs,
                phase.ordinal,
            )
            record(eventTime.realtimeMs, Kind.LOAD, key, "${phase.token} $resource$suffix")
        }
    }

    private fun describe(event: TelemetryEvent): String = when (event) {
        // Exhaustive on purpose: a new event kind is a compile error here, so the trace's vocabulary
        // cannot fall behind the schema's silently.
        is TelemetryEvent.SessionStarted -> {
            val buffer = event.decision.buffer
            val tracks = event.decision.trackSelection
            "profile=${event.profile} minBufferMs=${buffer.minBufferMs} maxBufferMs=${buffer.maxBufferMs} " +
                "bufferForPlaybackMs=${buffer.bufferForPlaybackMs} " +
                "bufferForPlaybackAfterRebufferMs=${buffer.bufferForPlaybackAfterRebufferMs} " +
                "backBufferMs=${buffer.backBufferMs} retainBackBufferFromKeyframe=${buffer.retainBackBufferFromKeyframe} " +
                "maxVideoBitrateBps=${tracks.maxVideoBitrateBps} maxVideoHeightPx=${tracks.maxVideoHeightPx}"
        }

        is TelemetryEvent.SessionEnded -> "droppedEventCount=${event.droppedEventCount}"

        is TelemetryEvent.FirstFrameRendered ->
            "timeToFirstFrameMs=${event.timeToFirstFrameMs} startBoundary=${event.startBoundary}"

        is TelemetryEvent.RebufferStarted -> "seekInduced=${event.seekInduced}"

        is TelemetryEvent.RebufferEnded -> "durationMs=${event.durationMs} seekInduced=${event.seekInduced}"

        // Rule 3: category and code, never the message.
        is TelemetryEvent.StartupFailed -> "category=${event.failure.category} code=${event.failure.code}"

        is TelemetryEvent.MidStreamFailed ->
            "category=${event.failure.category} code=${event.failure.code} positionMs=${event.positionMs}"

        is TelemetryEvent.TrackSwitched ->
            "fromBitrateBps=${event.fromBitrateBps} toBitrateBps=${event.toBitrateBps} direction=${event.direction}"

        is TelemetryEvent.SeekRequested -> "fromPositionMs=${event.fromPositionMs} toPositionMs=${event.toPositionMs}"

        is TelemetryEvent.SeekCompleted -> "toPositionMs=${event.toPositionMs} seekLatencyMs=${event.seekLatencyMs}"

        is TelemetryEvent.LiveLatencySampled ->
            "liveLatencyMs=${event.liveLatencyMs} targetLiveLatencyMs=${event.targetLiveLatencyMs}"

        is TelemetryEvent.PlaybackStateSampled ->
            "samplingIntervalMs=${event.samplingIntervalMs} videoBitrateBps=${event.videoBitrateBps} " +
                "bufferedDurationMs=${event.bufferedDurationMs} playing=${event.playing}"

        is TelemetryEvent.VideoFramesDropped ->
            "droppedFrames=${event.droppedFrames} repeatedFrames=${event.repeatedFrames} " +
                "elapsedPlayingMs=${event.elapsedPlayingMs}"
    }

    /** The kinds in the order they rank within one millisecond; [token] is the column printed. */
    private enum class Kind(val token: String) {
        ITEM("item"),
        STATE("state"),
        PLAYING("playing"),
        TRACKS("tracks"),
        DISCONTINUITY("discontinuity"),
        LOAD("load"),
        ERROR("error"),
        TELEMETRY("telemetry"),
    }

    /** A load's phases, in the order they rank within one millisecond so a start precedes its end. */
    private enum class Phase(val token: String) {
        STARTED("started"),
        COMPLETED("completed"),
        CANCELED("canceled"),
        ERROR("error"),
    }

    private class Entry(val timeMs: Long, val kind: Kind, val key: String, val text: String, val sequence: Long)

    private companion object {
        fun selectedBitrate(tracks: Tracks, trackType: Int): String {
            val format = tracks.groups
                .filter { it.type == trackType }
                .firstNotNullOfOrNull { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
            return format?.bitrate?.takeIf { it != Format.NO_VALUE }?.toString() ?: "none"
        }

        fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }

        fun transitionReason(reason: Int): String = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            else -> "UNKNOWN($reason)"
        }

        fun discontinuityReason(reason: Int): String = when (reason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
            Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
            Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
            Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
            Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
            Player.DISCONTINUITY_REASON_SILENCE_SKIP -> "SILENCE_SKIP"
            else -> "UNKNOWN($reason)"
        }

        fun dataType(dataType: Int): String = when (dataType) {
            C.DATA_TYPE_MEDIA -> "media"
            C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
            C.DATA_TYPE_DRM -> "drm"
            C.DATA_TYPE_MANIFEST -> "manifest"
            C.DATA_TYPE_TIME_SYNCHRONIZATION -> "time-sync"
            C.DATA_TYPE_AD -> "ad"
            C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> "media-progressive-live"
            else -> "other"
        }

        fun trackType(trackType: Int): String = when (trackType) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_TEXT -> "text"
            C.TRACK_TYPE_METADATA -> "metadata"
            C.TRACK_TYPE_IMAGE -> "image"
            C.TRACK_TYPE_DEFAULT -> "default"
            else -> "other"
        }

        /**
         * Rule 1 for the one place a consumer's own string reaches the trace. A `MediaRequest`'s
         * content id is the point of the line; a `MediaItem` set directly carries whatever the app
         * put in `mediaId`, and a common app pattern puts the URL there — token and all. Anything
         * shaped like a URI is therefore withheld, and an item with no id is `none`.
         */
        fun itemName(item: MediaItem?): String {
            val id = item?.mediaId?.takeIf { it.isNotEmpty() } ?: return "none"
            return if (URI_SHAPED.containsMatchIn(id)) "uri-withheld" else id
        }

        /** A scheme prefix, or a query: what a URL has and a content id has no reason to. */
        val URI_SHAPED = Regex("^[A-Za-z][A-Za-z0-9+.-]*:|[?#]")
    }
}
