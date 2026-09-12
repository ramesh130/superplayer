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

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import com.superplayer.core.FailureCategory
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackFailure
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.core.TtffStartBoundary

/**
 * CTA-2066 metrics off a bare `ExoPlayer`, in the same vocabulary `superplayer-telemetry` emits.
 *
 * **This is the single most dangerous file in `benchmark/`, and the issue that asked for the harness
 * says so.** Arms (a) and (b) have no SuperPlayer in them, so `QoeCollector` — which attaches to a
 * `SuperPlayer` — cannot measure them, and something has to. A comparison in which the two sides
 * measure differently is not a comparison, and the way this harness would end up lying is a
 * plausible, subtly different second implementation of "the same" metric sitting right here.
 *
 * Three things are done about that, and none of them is care:
 *
 * 1. **This emits `superplayer-core`'s own [TelemetryEvent] types, not numbers.** Every metric in a
 *    report is computed by [SessionMetrics] from an event stream, and [SessionMetrics] cannot tell
 *    which arm produced one. So the *definitions* — the rebuffer denominator, the seek exclusion,
 *    the bitrate weighting — are shared by construction rather than by discipline: there is one
 *    implementation of them and all three arms go through it. What is left here is *derivation*:
 *    when a rebuffer starts, which is a different and much smaller surface.
 * 2. **That remaining surface is mirrored callback for callback from `QoeCollector`**, which is the
 *    shipped implementation and therefore the definition of what arm (c) does. Each override below
 *    says which one it mirrors. A difference that is deliberate — there are two, both about a
 *    concept a stock player does not have — says so and says why.
 * 3. **`StockTelemetryAgreementTest` plays one session through both and asserts the event streams
 *    match.** That test is what makes points 1 and 2 true rather than intended, and it is the reason
 *    this file is allowed to exist at all. If it is deleted, the benchmark's central claim goes
 *    with it.
 *
 * ## Two deliberate differences
 *
 * **Delivery is synchronous.** `QoeCollector` submits to `TelemetryDelivery`, the bounded queue
 * ADR-0008 rules 3 and 4 require, which drops under pressure and counts what it dropped. Nothing
 * here needs that: a benchmark's sink appends to a list, and a bound that could never be reached
 * would only be ceremony. The asymmetry is in *delivery* rather than in derivation, and the schema
 * already says what to do about it — a session whose `SessionEnded.droppedEventCount` is non-zero is
 * excluded from aggregates rather than averaged in. [BenchmarkMatrixTest] enforces that for every
 * arm, so arm (c) losing an event costs that session rather than biasing the comparison.
 *
 * **The session boundary and the `SessionStarted` payload are given rather than known.** Core signals
 * session boundaries for arm (c), because only core knows which item change carried a `MediaRequest`;
 * a stock player has no content identity at all, so the runner says when a session opens. Likewise
 * `SessionStarted` carries a `profile` and a `decision`, and a stock player has neither — see
 * [startSession] for exactly what is put there and why it is not a fabrication.
 */
internal class StockTelemetry(private val sink: (TelemetryEvent) -> Unit) {

    private var player: ExoPlayer? = null

    private var openSession: OpenSession? = null

    /** Mirrors `QoeCollector`: held until the next session opens, and consumed by it. */
    private var declaredIntentMonotonicMs: Long? = null

    /** Mirrors `QoeCollector`: what a seek completing needs to know it did not resume into idle. */
    private var lastKnownPlaybackState: Int = Player.STATE_IDLE

    private var sampler: Handler? = null

    /**
     * Mirrors `QoeCollector.analyticsListener`, callback for callback.
     *
     * Callbacks arrive on the player's application looper, which is what makes the state above
     * single-threaded without a lock and makes it legal for [sampleNow] to read the player.
     */
    private val analyticsListener: AnalyticsListener = object : AnalyticsListener {

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            val session = openSession ?: return
            // Once per session. Media3 raises this again after a seek and after a surface change, and
            // CTA-2066's video start-up time is the *first* frame of the view.
            if (session.firstFrameRenderedAtMs != null) return
            val now = now()
            session.firstFrameRenderedAtMs = now
            emit(
                TelemetryEvent.FirstFrameRendered(
                    sessionId = session.id,
                    contentId = session.contentId,
                    timestampMs = System.currentTimeMillis(),
                    monotonicTimeMs = now,
                    timeToFirstFrameMs = now - session.ttffStartMonotonicMs,
                    startBoundary = session.ttffStartBoundary,
                ),
            )
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            lastKnownPlaybackState = state
            val session = openSession ?: return
            if (state == Player.STATE_BUFFERING) {
                openRebuffer(session)
                return
            }
            // Every other state ends the stall, idle and ended included: a player that stopped did
            // not resume, but it did stop stalling, and an unanswered `RebufferStarted` would have a
            // pipeline carry an open stall into whatever it read next.
            closeRebuffer(session)
            if (state == Player.STATE_READY) {
                completeSeek(session)
            } else {
                // Idle or ended: this seek will never resume. Leaving it in flight would make every
                // later stall in the session seek-induced, emptying the rebuffer ratio of exactly
                // the session whose seek was interrupted by an error.
                session.abandonSeek()
            }
        }

        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            val session = openSession ?: return
            val now = now()
            session.seekRequestedAtMs = now
            emit(
                TelemetryEvent.SeekRequested(
                    sessionId = session.id,
                    contentId = session.contentId,
                    timestampMs = System.currentTimeMillis(),
                    monotonicTimeMs = now,
                    fromPositionMs = oldPosition.positionMs,
                    toPositionMs = newPosition.positionMs,
                ),
            )
            // A seek into already-buffered data never leaves `STATE_READY`, so there is no transition
            // to complete it on, and waiting for one would leave the seek open until the next
            // unrelated stall. Its latency is zero, which is the truth about it.
            if (lastKnownPlaybackState == Player.STATE_READY) completeSeek(session)
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            val session = openSession ?: return
            val now = now()
            val failure = error.toPlaybackFailure()
            // The split CTA-2066 draws: nothing played, versus something played and then stopped.
            emit(
                if (session.firstFrameRenderedAtMs == null) {
                    TelemetryEvent.StartupFailed(
                        sessionId = session.id,
                        contentId = session.contentId,
                        timestampMs = System.currentTimeMillis(),
                        monotonicTimeMs = now,
                        failure = failure,
                    )
                } else {
                    TelemetryEvent.MidStreamFailed(
                        sessionId = session.id,
                        contentId = session.contentId,
                        timestampMs = System.currentTimeMillis(),
                        monotonicTimeMs = now,
                        failure = failure,
                        positionMs = eventTime.currentPlaybackPositionMs,
                    )
                },
            )
        }

        override fun onDownstreamFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            mediaLoadData: MediaLoadData,
        ) {
            if (mediaLoadData.trackType != C.TRACK_TYPE_VIDEO) return
            val session = openSession ?: return
            val bitrate = mediaLoadData.trackFormat?.declaredPeakBitrateBps() ?: return
            val from = session.videoBitrateBps
            if (from == bitrate) return
            session.videoBitrateBps = bitrate
            val now = now()
            emit(
                TelemetryEvent.TrackSwitched(
                    sessionId = session.id,
                    contentId = session.contentId,
                    timestampMs = System.currentTimeMillis(),
                    monotonicTimeMs = now,
                    fromBitrateBps = from,
                    toBitrateBps = bitrate,
                    direction = when {
                        from == null -> TrackSwitchDirection.INITIAL
                        bitrate > from -> TrackSwitchDirection.UP
                        else -> TrackSwitchDirection.DOWN
                    },
                ),
            )
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            val session = openSession ?: return
            emit(
                TelemetryEvent.VideoFramesDropped(
                    sessionId = session.id,
                    contentId = session.contentId,
                    timestampMs = System.currentTimeMillis(),
                    monotonicTimeMs = now(),
                    droppedFrames = droppedFrames,
                    // Zero rather than omitted, exactly as `QoeCollector` reports it and for the
                    // same engine reason: Media3 1.11.0 has no callback for a repeated frame.
                    // `docs/telemetry-schema.md` says a zero here means "not measured".
                    repeatedFrames = 0,
                    // Media3's own interval, taken rather than measured, so a rate over it needs
                    // nothing that can drift out of step with the count.
                    elapsedPlayingMs = elapsedMs,
                ),
            )
        }
    }

    /** Mirrors `QoeCollector.attach`, minus the escape hatch: this arm's player *is* the engine. */
    fun attach(player: ExoPlayer) {
        check(this.player == null) { "A StockTelemetry measures one player; build one per player" }
        this.player = player
        player.addAnalyticsListener(analyticsListener)
    }

    /**
     * Opens a measurement session, exactly as `TelemetryCollector.startSession` does for arm (c).
     *
     * [profile] and [decision] are given rather than read off the player, and this is the one place
     * a stock arm's event stream carries something a stock player does not itself have. Both are
     * honest, and both matter to a reader of a raw trace:
     *
     * - [decision] is what the arm *actually ran with* — `Arm.MEDIA3_DEFAULT_BUFFER_POLICY` for arm
     *   (a), `Arm.STOCK_NAIVE_TUNING.stockBufferPolicy` for arm (b) — expressed in the same value
     *   type arm (c) reports through `SuperPlayer.playbackDecision`. That is exactly what the field
     *   is for: `TelemetryEvent.SessionStarted` carries it because a QoE number is uninterpretable
     *   without knowing what buffering produced it.
     * - [profile] is the one field that is a label rather than a setting. A stock player has no
     *   `PlaybackProfile` and reaches no policy of any kind, so what is put here is **the profile of
     *   the cell this session belongs to** — the arm-(c) configuration these numbers are being
     *   compared against. A raw trace also carries its `arm` explicitly, beside the events rather
     *   than inside them, so nobody has to read this field to learn which player produced the
     *   session; see [RawTrace].
     */
    fun startSession(
        contentId: String,
        sessionId: String,
        profile: PlaybackProfile,
        decision: PlaybackDecision,
    ) {
        endSession()
        // The same guard `QoeCollector.startSession` has, and it earns its place by the way it fails
        // without one: `scheduleSampling` returns quietly when no player is attached, so a session
        // opened too early produces a complete-looking event stream with **zero**
        // `PlaybackStateSampled` events — and a bitrate column of em dashes for that arm, from a run
        // that reported no error. Arm (c) throws here; this mirrors it, because a silently empty
        // column is the failure mode the whole report is arranged against.
        checkNotNull(player) { "startSession before attach" }
        val startedAtMonotonicMs = SystemClock.elapsedRealtime()
        // Resolved once, here, rather than at the frame: whether a declaration existed is a fact
        // about this session's opening, and deciding it later would let a declaration made after the
        // session began move its own start backwards. Mirrors `QoeCollector.startSession`.
        val intentMs = declaredIntentMonotonicMs
        declaredIntentMonotonicMs = null

        val session = OpenSession(
            id = sessionId,
            contentId = contentId,
            ttffStartMonotonicMs = intentMs ?: startedAtMonotonicMs,
            ttffStartBoundary =
            if (intentMs == null) TtffStartBoundary.CONTENT_ADOPTED else TtffStartBoundary.USER_INTENT,
        )
        openSession = session
        scheduleSampling()
        emit(
            TelemetryEvent.SessionStarted(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = startedAtMonotonicMs,
                profile = profile,
                decision = decision,
            ),
        )
    }

    /** Mirrors `TelemetryCollector.declareIntent`: the boundary time to first frame is measured from. */
    fun declareIntent(monotonicTimeMs: Long) {
        declaredIntentMonotonicMs = monotonicTimeMs
    }

    /** Mirrors `QoeCollector.endSession`, including closing a stall the session ended inside. */
    fun endSession() {
        val session = openSession ?: return
        openSession = null
        stopSampling()
        closeRebuffer(session)
        emit(
            TelemetryEvent.SessionEnded(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = SystemClock.elapsedRealtime(),
                // Always zero, and truthfully so: delivery here is synchronous and unbounded, so
                // nothing can be dropped. See the class KDoc — arm (c)'s count is real, and a
                // session that reports one is excluded from aggregates rather than averaged in.
                droppedEventCount = 0,
            ),
        )
    }

    /** Mirrors `QoeCollector.detach`. */
    fun detach() {
        stopSampling(dropHandler = true)
        player?.removeAnalyticsListener(analyticsListener)
        player = null
        declaredIntentMonotonicMs = null
    }

    private fun emit(event: TelemetryEvent) {
        sink(event)
    }

    /**
     * Mirrors `QoeCollector.openRebuffer`.
     *
     * **Time before the first frame is not a rebuffer** — that interval is start-up time, and
     * counting it in both would double-count the same seconds.
     */
    private fun openRebuffer(session: OpenSession) {
        if (session.firstFrameRenderedAtMs == null || session.rebufferStartedAtMs != null) return
        val now = now()
        val seekInduced = session.isSeekInducedAt(now)
        session.rebufferStartedAtMs = now
        session.rebufferSeekInduced = seekInduced
        emit(
            TelemetryEvent.RebufferStarted(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = now,
                seekInduced = seekInduced,
            ),
        )
    }

    /** Mirrors `QoeCollector.closeRebuffer`. */
    private fun closeRebuffer(session: OpenSession) {
        val startedAt = session.rebufferStartedAtMs ?: return
        val seekInduced = session.rebufferSeekInduced
        session.rebufferStartedAtMs = null
        val now = now()
        emit(
            TelemetryEvent.RebufferEnded(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = now,
                durationMs = now - startedAt,
                seekInduced = seekInduced,
            ),
        )
    }

    /** Mirrors `QoeCollector.completeSeek`. */
    private fun completeSeek(session: OpenSession) {
        val requestedAt = session.seekRequestedAtMs ?: return
        session.seekRequestedAtMs = null
        val now = now()
        session.seekCompletedAtMs = now
        val attached = player
        emit(
            TelemetryEvent.SeekCompleted(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = now,
                toPositionMs = attached?.currentPosition ?: C.TIME_UNSET,
                seekLatencyMs = now - requestedAt,
            ),
        )
    }

    /**
     * Mirrors `QoeCollector.scheduleSampling`, at the same cadence.
     *
     * The cadence has to be the same number, not merely a reasonable one: a time-weighted average
     * whose two arms were sampled at different rates would be two different statistics with one
     * name, and the bitrate column of every row would be quietly incomparable.
     */
    private fun scheduleSampling() {
        val attached = player ?: return
        val handler = sampler ?: Handler(attached.applicationLooper).also { sampler = it }
        handler.postDelayed(::sampleNow, SAMPLING_INTERVAL_MS)
    }

    /** Mirrors `QoeCollector.stopSampling`. */
    private fun stopSampling(dropHandler: Boolean = false) {
        sampler?.removeCallbacksAndMessages(null)
        if (dropHandler) sampler = null
    }

    /** Mirrors `QoeCollector.sampleNow`, including the live-content-only rule for live latency. */
    private fun sampleNow() {
        val session = openSession ?: return
        val attached = player ?: return
        val now = now()
        emit(
            TelemetryEvent.PlaybackStateSampled(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = now,
                samplingIntervalMs = SAMPLING_INTERVAL_MS,
                videoBitrateBps = session.videoBitrateBps,
                bufferedDurationMs = (attached.bufferedPosition - attached.currentPosition)
                    .coerceAtLeast(0),
                playing = attached.isPlaying,
            ),
        )
        // Live content only, decided by what the timeline says rather than by any configuration: a
        // live-latency sample of zero from on-demand content is a number a dashboard would average.
        val liveOffsetMs = attached.currentLiveOffset
        if (liveOffsetMs != C.TIME_UNSET) {
            val targetMs = attached.currentMediaItem?.liveConfiguration?.targetOffsetMs
            emit(
                TelemetryEvent.LiveLatencySampled(
                    sessionId = session.id,
                    contentId = session.contentId,
                    timestampMs = System.currentTimeMillis(),
                    monotonicTimeMs = now,
                    liveLatencyMs = liveOffsetMs,
                    targetLiveLatencyMs = targetMs?.takeIf { it != C.TIME_UNSET },
                ),
            )
        }
        scheduleSampling()
    }

    /**
     * The clock every duration in `docs/telemetry-schema.md` is defined on.
     *
     * Deliberately **not** `AnalyticsListener.EventTime.realtimeMs`, which is the closer reading and
     * the wrong one, for the reason `QoeCollector` gives: that field comes from the `Clock` the
     * engine was built with, and mixing it with a `SystemClock` reading would make a duration a
     * difference between two clocks. Both arms read the same clock or neither number means anything.
     */
    private fun now(): Long = SystemClock.elapsedRealtime()

    /** Mirrors `QoeCollector.OpenSession`, field for field. */
    private class OpenSession(
        val id: String,
        val contentId: String,
        val ttffStartMonotonicMs: Long,
        val ttffStartBoundary: TtffStartBoundary,
    ) {
        var firstFrameRenderedAtMs: Long? = null
        var videoBitrateBps: Int? = null
        var rebufferStartedAtMs: Long? = null
        var rebufferSeekInduced: Boolean = false
        var seekRequestedAtMs: Long? = null
        var seekCompletedAtMs: Long? = null

        fun abandonSeek() {
            seekRequestedAtMs = null
        }

        /**
         * ref: `docs/telemetry-schema.md`, *Rebuffering* — a stall is seek-induced when it starts
         * between a `SeekRequested` and its `SeekCompleted`, or within [SEEK_EXCLUSION_WINDOW_MS]
         * after that completion. The document is where the window is argued and where it changes.
         */
        fun isSeekInducedAt(nowMs: Long): Boolean {
            if (seekRequestedAtMs != null) return true
            val completedAt = seekCompletedAtMs ?: return false
            return nowMs - completedAt <= SEEK_EXCLUSION_WINDOW_MS
        }
    }

    companion object {

        /**
         * `QoeCollector`'s sampling cadence, repeated because it cannot be imported.
         *
         * It is a private companion value in `superplayer-telemetry` — deliberately, so that a
         * consumer takes the cadence off `PlaybackStateSampled.samplingIntervalMs` rather than
         * copying it out of the library. This file is not a consumer taking a shortcut: it is the
         * *other* producer of that event, and it has to state the same cadence for the same reason
         * the collector does. `StockTelemetryAgreementTest` asserts the two agree, so the copy
         * cannot drift silently — which is the only thing that would make repeating it dangerous.
         *
         * `docs/telemetry-schema.md` states the number.
         */
        const val SAMPLING_INTERVAL_MS = 10_000L

        /** See [OpenSession.isSeekInducedAt]; the schema document argues this number. */
        const val SEEK_EXCLUSION_WINDOW_MS = 1_000L

        /** The width of one of Media3's documented error-code bands; see [toPlaybackFailure]. */
        private const val ERROR_CODE_BAND = 1_000

        /**
         * The rendition's declared peak bitrate, which is what a manifest states.
         *
         * ref: `docs/telemetry-schema.md`, *Bitrate* — the number is what the manifest declares, not
         * a measurement of what was transferred. Media3 fills whichever of peak and average the
         * manifest supplied, so the peak is preferred and the average is the fallback; a format
         * declaring neither yields null rather than a zero a dashboard would average. Mirrors
         * `QoeCollector`'s `Format.declaredPeakBitrateBps`.
         */
        private fun Format.declaredPeakBitrateBps(): Int? = when {
            peakBitrate != Format.NO_VALUE -> peakBitrate
            averageBitrate != Format.NO_VALUE -> averageBitrate
            else -> null
        }

        /**
         * What failed, in SuperPlayer's own vocabulary. Mirrors `QoeCollector`'s
         * `PlaybackException.toPlaybackFailure`.
         *
         * ref: Media3 1.11.0 `PlaybackException` allocates its error codes in documented thousands —
         * 1xxx miscellaneous, 2xxx input/output, 3xxx content parsing, 4xxx decoding, 5xxx audio
         * renderer, 6xxx DRM, 7xxx video frame processing. Bucketing on the band rather than on a
         * list of individual codes is what makes a code Media3 adds land in the right category
         * instead of in UNKNOWN.
         */
        private fun PlaybackException.toPlaybackFailure(): PlaybackFailure = PlaybackFailure(
            category = when (errorCode / ERROR_CODE_BAND) {
                2 -> FailureCategory.NETWORK
                3 -> FailureCategory.SOURCE
                4 -> FailureCategory.DECODER
                6 -> FailureCategory.DRM
                5, 7 -> FailureCategory.RENDERER
                else -> FailureCategory.UNKNOWN
            },
            code = errorCodeName,
            message = message,
        )
    }
}
