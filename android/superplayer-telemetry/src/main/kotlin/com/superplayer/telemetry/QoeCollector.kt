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

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.superplayer.core.DecisionTrigger
import com.superplayer.core.FailureCategory
import com.superplayer.core.LicenceOutcome
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackFailure
import com.superplayer.core.SuperPlayer
import com.superplayer.core.SuperPlayerError
import com.superplayer.core.TelemetryCollector
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.core.TrackSwitchDirection
import com.superplayer.core.TtffStartBoundary

/**
 * Watches a [SuperPlayer] and writes what it observes to a [TelemetrySink].
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setTelemetry(QoeCollector(sink = { event -> analytics.record(event) }))
 *     .build()
 * ```
 *
 * This is the module boundary ADR-0008 draws: the sink and the event vocabulary are
 * `superplayer-core`'s, so a consumer's data pipeline names no telemetry type and no Media3 type,
 * while every Media3 analytics type and every metric definition lives here — and, per rule 1, the
 * translation between the two lives in this one file, the way `EngineBinding.kt` is core's one
 * place for turning a policy decision into engine configuration.
 *
 * ## What it emits
 *
 * The whole of `docs/telemetry-schema.md`: the session boundary, the first frame, rebuffers,
 * startup and mid-stream failures, track switches, seeks, licence acquisitions, and the three
 * periodic samples.
 *
 * **What each number means is that document, not this file.** Every metric there cites CTA-2066,
 * and every place SuperPlayer's definition departs from the standard says so with the reason. The
 * comments below say where a value comes from and why it is *not* the obvious Media3 field; the
 * definition it is serving is written down once, there.
 *
 * The dangerous shortcut this class deliberately does not take is `PlaybackStatsListener`. Media3
 * already computes total rebuffer time, mean bitrate and more, and forwarding those fields under
 * CTA-2066 names would be the fastest possible implementation and wrong in a way nobody notices for
 * six months: Media3's boundaries for joining time, for what counts as buffering, and for how a seek
 * is treated are engine-shaped rather than CTA-2066-shaped, and renaming a field does not convert
 * it. Where the two agree, Media3's own reporting is used as-is — dropped frames arrive with the
 * engine's own elapsed interval, and are reported over it. Where they differ, the difference is the
 * work, and it carries a comment citing both.
 *
 * Session boundaries come from core rather than being inferred here: only core knows which item
 * change carried a [com.superplayer.core.MediaRequest] and which was a raw `setMediaItem`, and only
 * core knows the difference between a pooled player being recycled and one being released.
 *
 * ## How an event reaches the sink
 *
 * Never on the thread that caused it. Everything this class derives is handed to
 * [TelemetryDelivery], which queues it under a bound and drains it on a thread no engine needs, so a
 * sink that blocks for seconds delays no playback callback — ADR-0008 rules 3 and 4. Reading that
 * file is how to answer "what thread is my sink on" and "what happens when it falls behind".
 *
 * ## One collector, one player
 *
 * A collector holds the player it was attached to and the session currently open on it. Build one
 * per player; a pool builds one per pooled player, and each of them reports sessions under its own
 * ids.
 */
public class QoeCollector internal constructor(
    /**
     * The bounded, off-thread path to the sink — ADR-0008 rules 3 and 4. Everything about *how* a
     * sink is called lives there rather than here, so this class is only ever about what an event
     * means.
     *
     * One per collector, so a drop is attributable to the session that lost it, while the thread it
     * drains on is the process's one. Injectable only from inside this module, which is what lets
     * `TelemetryDeliveryTest` flood a small queue and `QoeCollectorTest` wait for the drain without
     * either of those becoming something a consumer can reach for.
     */
    private val delivery: TelemetryDelivery,
) : TelemetryCollector {

    /** The ordinary way to build one: a sink, behind the delivery path this library chose for it. */
    public constructor(sink: TelemetrySink) : this(TelemetryDelivery(sink))

    /**
     * The player this collector was attached to, held so [detach] can unregister from the same
     * engine [attach] registered against, and so a session can name the player's profile.
     */
    private var player: SuperPlayer? = null

    /**
     * The session currently open, or null between an [endSession] and the next [startSession].
     *
     * Holding the id and the content id here rather than deriving them at the end is what lets
     * [TelemetryEvent.SessionEnded] name the content the session was *of*, even though by the time
     * it fires — a release, a recycle — the player may already have been cleared.
     */
    private var openSession: OpenSession? = null

    /**
     * The most recent `SuperPlayer.declarePlaybackIntent`, on `SystemClock.elapsedRealtime()`, or
     * null when the consumer has declared none since the last session opened.
     *
     * Held here rather than on the open session because it arrives *before* one: an app declares
     * intent at the tap and only then finds out what to play. [startSession] consumes it, which is
     * what makes a declaration belong to exactly one session — a second session that opened on a
     * stale reading would report a time to first frame containing the whole of the previous view.
     *
     * The last declaration before a session wins, so an app that declares twice on a double tap
     * measures from the tap that actually loaded something.
     */
    private var declaredIntentMonotonicMs: Long? = null

    /**
     * The last playback state Media3 reported, as this collector saw it.
     *
     * Held rather than read from the player, because a seek that lands in already-buffered data
     * produces no state change at all and the collector still has to know whether playback was
     * running — and because reading the player from a callback is a question about a different
     * moment than the one the callback is about.
     */
    private var lastKnownPlaybackState: Int = Player.STATE_IDLE

    /** Posts the periodic samples; see [scheduleSampling] for the cadence and why it is a timer. */
    private var sampler: Handler? = null

    /**
     * Media3's own analytics registration, and the source of every metric below.
     *
     * The engine reports load, buffer, decoder and renderer events here, which is what
     * `PRD.md` §3.4 means by "built on `AnalyticsListener`, **not** polling": each number below is
     * derived from the engine saying what happened, rather than from this class sampling a position
     * and inferring it. Two implementations of "the same" metric disagree precisely when one of them
     * is inferring.
     *
     * Callbacks arrive on the player's application looper, which is what makes the state below
     * single-threaded without a lock and makes it legal for [sampleNow] to read the player.
     */
    private val analyticsListener: AnalyticsListener = object : AnalyticsListener {

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            val session = openSession ?: return
            // Once per session. Media3 raises this again after a seek and after a surface change,
            // and CTA-2066's video start-up time is the *first* frame of the view — a second
            // measurement under the same name would be a different metric wearing its label.
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
            // not resume, but it did stop stalling, and a `RebufferStarted` left unanswered would
            // have a pipeline pairing the two carry an open stall into whatever it read next.
            closeRebuffer(session)
            if (state == Player.STATE_READY) {
                // Playback resuming at the target is what makes seek latency a number.
                completeSeek(session)
            } else {
                // Idle or ended: this seek will never resume, so it has no latency to report and
                // must not be left in flight. A pending seek makes *every* later stall in the
                // session seek-induced, which would quietly empty the rebuffer ratio of a session
                // whose seek happened to be interrupted by an error — the metric reading best
                // exactly when playback went worst.
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
            // A seek into already-buffered data never leaves `STATE_READY`, so there is no
            // transition to complete it on and waiting for one would leave the seek open until the
            // next unrelated stall. Latency for that seek is zero, which is the truth about it.
            if (lastKnownPlaybackState == Player.STATE_READY) completeSeek(session)
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            val session = openSession ?: return
            val now = now()
            // Asked of the player rather than derived here, which is ADR-0011 rule 3 in one line:
            // the classifier is `superplayer-resilience`'s and telemetry reports what it said. A
            // player built without that module answers null, and everything below reads exactly as it
            // read before Phase 5.
            val failure = error.toPlaybackFailure(player?.classify(error))
            // The split CTA-2066 draws, and the one a viewer experiences: nothing played, versus
            // something played and then stopped. `firstFrameRenderedAtMs` is the boundary, and it is
            // also what makes exit-before-video-start derivable — a session that ends with neither a
            // frame nor a `StartupFailed` is an abandonment rather than a failure, which is a
            // distinction that collapses the moment these two events are merged.
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

        // ref: Media3 1.11.0's `DefaultAnalyticsCollector` dispatches *both* arities of the DRM
        // callbacks it grew a parameter on — `lambda$onDrmSessionAcquired$64` calls the deprecated
        // `onDrmSessionAcquired(EventTime)` and then `onDrmSessionAcquired(EventTime, int)`, and
        // `onDrmKeysLoaded` does the same. Only the newer arity is overridden below, in both cases:
        // overriding both would count every acquisition twice.
        override fun onDrmSessionAcquired(eventTime: AnalyticsListener.EventTime, state: Int) {
            val session = openSession ?: return
            // Counted whatever the state, because it is what [onDrmSessionReleased] is counted
            // against — a session that reused keys still has to be released before the acquisitions
            // behind it can be called stale.
            session.drmSessionOpened()
            // A session opened *with* keys acquired nothing. That is Media3 reusing a session for
            // content whose initialization data matched (ADR-0012, `#209`), and measuring it would
            // report a zero-length acquisition that never happened — which, averaged in, is a
            // licence-acquisition time that falls as session reuse rises.
            if (state != DrmSession.STATE_OPENED) return
            session.openLicenceAcquisition(now())
        }

        override fun onDrmSessionReleased(eventTime: AnalyticsListener.EventTime) {
            openSession?.drmSessionReleased()
        }

        override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime, keyRequestInfo: KeyRequestInfo) {
            endLicenceAcquisition(LicenceOutcome.ACQUIRED_FROM_SERVER)
        }

        // Keys restored into a session from a licence already on the device, which is what
        // `superplayer-drm`'s offline licence store produces (#210). Declared one release before it
        // could happen (#212), so that the store was a behaviour change against a vocabulary a
        // pipeline already knew rather than a second change of the schema; see
        // `LicenceOutcome.SERVED_FROM_OFFLINE_STORE`.
        override fun onDrmKeysRestored(eventTime: AnalyticsListener.EventTime) {
            endLicenceAcquisition(LicenceOutcome.SERVED_FROM_OFFLINE_STORE)
        }

        // The exception is deliberately not read. What went wrong is the classifier's answer and
        // reaches a pipeline on the failure event (ADR-0011 rule 1); all this callback settles is
        // that the attempt ended without keys.
        override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) {
            endLicenceAcquisition(LicenceOutcome.REFUSED)
        }

        /**
         * The meter's smoothed throughput estimate, kept for the next periodic sample to carry.
         *
         * Not an event of its own: the meter reports on its own cadence, which is per transfer and is
         * the engine's business, and a vocabulary that emitted one event per report would emit at a
         * rate a segment size decides. The sample already has a stated cadence and already carries
         * the rung that was selected, and the estimate is only interesting beside it (`#294`).
         *
         * Read from Media3's own callback rather than from `superplayer-abr`'s `BandwidthOracle`,
         * because this collector must derive the same number on a player that has no adaptive policy:
         * the oracle's meter reports through this callback too, so one reading covers both.
         */
        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            val session = openSession ?: return
            // Media3's estimate is a `long` and this vocabulary's bitrates are `Int`, as
            // `videoBitrateBps` is, so the two can be compared without a widening at every call
            // site. Coerced rather than truncated: an estimate above two gigabits a second is a link
            // no ladder has a rung for, and reporting the ceiling is honest where wrapping is not.
            session.throughputEstimateBps = bitrateEstimate.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
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
                    // ref: Media3 1.11.0's `VideoRendererEventListener` reports dropped frames and
                    // has no callback for repeated ones — `MediaCodecVideoRenderer` does not count
                    // a frame presented twice. CTA-2066 names both, so the field stays in the
                    // vocabulary and is reported as zero rather than omitted, and
                    // `docs/telemetry-schema.md` says so under the metric. A zero here means "not
                    // observable on this engine", which is why the document rather than this number
                    // is what a pipeline reads.
                    repeatedFrames = 0,
                    // Media3's own elapsed interval, taken rather than measured: the engine reports
                    // the count and the window it accumulated over together, so a rate over that
                    // window needs nothing that can drift out of step with it. The schema's
                    // reasoning for that denominator is the same one.
                    elapsedPlayingMs = elapsedMs,
                ),
            )
        }
    }

    override fun attach(player: SuperPlayer) {
        check(this.player == null) { "A QoeCollector measures one player; build one per player" }
        this.player = player
        // `exoPlayer` is the escape hatch ADR-0001 rule 2 names, used here from inside the library
        // rather than by a consumer: this is the only way to reach Media3's analytics registration,
        // and the point of the seam is that a consumer never has to.
        player.exoPlayer.addAnalyticsListener(analyticsListener)
    }

    override fun startSession(contentId: String, sessionId: String) {
        // Content changing under an open session ends it rather than merging the two: the events
        // either side describe different content, and a session that spanned both would report one
        // view of something nobody watched.
        endSession()

        val attached = checkNotNull(player) { "startSession before attach" }
        val startedAtMonotonicMs = SystemClock.elapsedRealtime()
        // The start boundary of time to first frame, resolved once here rather than at the frame:
        // whether a declaration existed is a fact about *this* session's opening, and deciding it
        // later would let a declaration made after the session began move its own start backwards.
        // ref: CTA-2066 measures video start-up time from the viewer's request, which is what
        // `declareIntent` carries; CONTENT_ADOPTED is the narrower fallback the schema names when
        // an app declares nothing. Issue #36 subtracts; the definition is this.
        val intentMs = declaredIntentMonotonicMs
        declaredIntentMonotonicMs = null

        val session = OpenSession(
            // Core's, not this collector's: CMCD sends the same string to the CDN as `sid`, and
            // the join between a CDN log and a warehouse is an equality on it. See
            // `TelemetryCollector.startSession`.
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
                profile = attached.profile,
                decision = attached.playbackDecision,
            ),
        )
    }

    override fun declareIntent(monotonicTimeMs: Long) {
        declaredIntentMonotonicMs = monotonicTimeMs
    }

    override fun decisionChanged(decision: PlaybackDecision, trigger: DecisionTrigger) {
        // No session, no event: the next `SessionStarted` carries the decision then in force, which
        // is the same information under the id it belongs to.
        val session = openSession ?: return
        emit(
            TelemetryEvent.DecisionChanged(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = now(),
                decision = decision,
                trigger = trigger,
            ),
        )
    }

    override fun endSession() {
        val session = openSession ?: return
        // Cleared before the emit, so that this collector's own state is settled before anything
        // observable leaves it, whatever order the delivery path gets round to.
        openSession = null
        stopSampling()
        // A stall that was still open when the session ended is closed under the session that owned
        // it. A pooled player recycled mid-rebuffer does exactly this, and the alternative — letting
        // the `RebufferStarted` go unanswered — would leave a pipeline pairing events across the
        // session boundary into the next viewer's numbers.
        closeRebuffer(session)
        emit(
            TelemetryEvent.SessionEnded(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = SystemClock.elapsedRealtime(),
                // A placeholder the delivery path replaces as this event leaves the queue: a drop
                // caused by memory pressure after this line has run still belongs on it.
                droppedEventCount = 0,
                // Asked of the player as a consumer would, for `classify`'s reason: this collector is
                // not a friend of core and keeps no second copy of what the DRM slot decided. Null on
                // every session that negotiated nothing, which is nearly all of them.
                securityLevel = player?.deliveredSecurityLevel,
            ),
        )
    }

    override fun onMemoryPressure() {
        delivery.onMemoryPressure()
    }

    /**
     * Blocks until everything submitted so far has reached the sink.
     *
     * Tests only — the module-internal counterpart of delivery being asynchronous. See
     * [TelemetryDelivery.awaitIdle] for why it is not something a consumer can call.
     */
    internal fun awaitDelivered(timeoutMs: Long): Boolean = delivery.awaitIdle(timeoutMs)

    override fun detach() {
        stopSampling(dropHandler = true)
        player?.exoPlayer?.removeAnalyticsListener(analyticsListener)
        player = null
        // A declaration nobody spent belongs to no session, and a collector that kept it would hand
        // it to whichever session opened next on a collector that has been reattached.
        declaredIntentMonotonicMs = null
    }

    /**
     * Queues [event] for delivery and returns.
     *
     * The only work done on the caller's thread — the application thread for the signals core
     * sends, the playback thread for anything derived from an engine callback — is an enqueue.
     * [TelemetryDelivery] is where the bound, the drop policy and the delivery thread are, and it
     * is what makes ADR-0008 rules 3 and 4 true rather than aspirational.
     */
    private fun emit(event: TelemetryEvent) {
        delivery.submit(event)
    }

    /**
     * Opens a rebuffer for [session], attributing it to a seek or not.
     *
     * **Time before the first frame is not a rebuffer.** That interval is start-up time, and
     * counting it in both would double-count the same seconds — so a buffering state seen before
     * `FirstFrameRendered` opens nothing at all.
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

    /** Closes the stall [openRebuffer] began, if one is open. */
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

    /**
     * Closes the oldest licence acquisition in flight with [outcome], if there is one.
     *
     * Nothing to close is the ordinary case on most sessions: a key rotation renews inside a session
     * that is already open (`KeyRotationTest`), which loads keys without a matching acquisition, and
     * an event for it would be a second licence this session never fetched.
     */
    private fun endLicenceAcquisition(outcome: LicenceOutcome) {
        val session = openSession ?: return
        val startedAt = session.closeLicenceAcquisition() ?: return
        val now = now()
        emit(
            TelemetryEvent.LicenceAcquisitionEnded(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = now,
                durationMs = now - startedAt,
                outcome = outcome,
                // Asked of the player as a consumer would, for `classify`'s reason: this collector is
                // not a friend of core and keeps no second copy of what the DRM slot decided. Read
                // now rather than held from the session's start, because the ladder can settle a
                // lower level after the first refusal.
                securityLevel = player?.deliveredSecurityLevel,
            ),
        )
    }

    /** Completes a seek that is in flight, which is what makes seek latency a number. */
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
     * Starts the periodic samples, at the cadence `docs/telemetry-schema.md` states.
     *
     * A timer, and the one place this collector is not purely callback-driven. `PRD.md` §3.4 rules
     * out polling, and this is not what that rules out: the prohibition is on *deriving a metric* by
     * sampling a position — a 200 ms tick inferring rebuffers from a position that stopped moving,
     * which is the usual reason two implementations of "the same" number disagree. Every metric
     * above is derived from the engine saying what happened. What a sample *is*, by contrast, is an
     * event in its own right: bitrate distribution and buffer health are time-weighted quantities,
     * and a pipeline cannot weight what it did not receive at a known cadence. The cadence travels
     * on every sample as `samplingIntervalMs`, so it is the event and not a constant a consumer
     * copied out of a document.
     *
     * On the player's application looper, which is both where the analytics callbacks arrive — so
     * the session state below needs no lock — and the only thread a [SuperPlayer] may be read from.
     */
    private fun scheduleSampling() {
        val attached = player ?: return
        val handler = sampler ?: Handler(attached.applicationLooper).also { sampler = it }
        handler.postDelayed(::sampleNow, SAMPLING_INTERVAL_MS)
    }

    /**
     * Stops the periodic samples, and forgets the handler too when [dropHandler] is set.
     *
     * The handler is bound to the looper of the player it was built for. Between sessions on the
     * same player it is kept and reused, which is the pooled case; on [detach] it must go, or a
     * reattached collector would post the next session's samples to a released player's thread.
     */
    private fun stopSampling(dropHandler: Boolean = false) {
        sampler?.removeCallbacksAndMessages(null)
        if (dropHandler) sampler = null
    }

    /**
     * Reads what playback is doing right now and reports it, then schedules the next reading.
     *
     * The only place this class asks the player anything. It is legal because it runs on the
     * application looper, and it is bounded because it stops with the session.
     */
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
                throughputEstimateBps = session.throughputEstimateBps,
            ),
        )
        // Live content only. A sample of zero from on-demand content is a number a dashboard would
        // happily average, and the absence of the event is what stops that — so the test is what the
        // *timeline* says rather than what the profile was set to, because a `LIVE_LINEAR` player
        // handed a VOD asset is a misconfiguration and not a live session.
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
     * the wrong one. That field comes from the `Clock` the *engine* was built with, which is a
     * substitutable thing, while the boundary time-to-first-frame is measured from arrives through
     * `SuperPlayer.declarePlaybackIntent` on `SystemClock.elapsedRealtime()`. Subtracting one from
     * the other would make the schema's headline metric a difference between two clocks — correct
     * whenever they happen to agree and silently wrong when they do not. One clock, named in the
     * document, read here.
     */
    private fun now(): Long = SystemClock.elapsedRealtime()

    /**
     * A session that has started and not yet ended, and everything a metric is derived from.
     *
     * Per session rather than per collector, which is what makes a recycled pooled player report two
     * sessions with independent counters: `resetForReuse` ends the session, this object goes, and
     * the next `setMediaRequest` builds a fresh one. Nothing here survives that boundary.
     *
     * Touched only from the player's application looper — the analytics callbacks and the sampler
     * both — so none of it is synchronized.
     */
    private class OpenSession(
        val id: String,
        val contentId: String,
        /** The boundary [TelemetryEvent.FirstFrameRendered.timeToFirstFrameMs] is measured from. */
        val ttffStartMonotonicMs: Long,
        val ttffStartBoundary: TtffStartBoundary,
    ) {
        /**
         * When the first frame reached the display, or null if none has.
         *
         * Three things read it: the first-frame callback, to report once per session; the failure
         * callback, to choose between startup and mid-stream; and the rebuffer logic, because time
         * before the first frame is start-up time rather than a stall.
         */
        var firstFrameRenderedAtMs: Long? = null

        /** The declared peak bitrate playing, or null before the first rendition is chosen. */
        var videoBitrateBps: Int? = null

        /**
         * The meter's last reported throughput estimate, or null before it has reported one.
         *
         * Per session rather than per collector, for the same reason every other reading here is: a
         * recycled pooled player starts the next session with no estimate reported *to that session*,
         * and a sample carrying the previous content's number would be a reading nothing measured.
         */
        var throughputEstimateBps: Int? = null

        /** When the open stall began, or null when playback is not stalled. */
        var rebufferStartedAtMs: Long? = null

        /** The attribution the open stall was opened with, repeated on its `RebufferEnded`. */
        var rebufferSeekInduced: Boolean = false

        /** When the in-flight seek was issued, or null when none is. */
        var seekRequestedAtMs: Long? = null

        /** When the last seek completed, which is what the exclusion window below is measured from. */
        var seekCompletedAtMs: Long? = null

        /**
         * The start of every licence acquisition open right now, oldest first.
         *
         * A queue rather than one reading, because a session can have two acquisitions in flight at
         * once — content declaring two licence policies opens a session for each — and they are
         * paired oldest-to-oldest. That pairing is an approximation and is written down as one:
         * ref: Media3 1.11.0's DRM analytics callbacks carry an `EventTime` and no session identity,
         * so an acquisition cannot be matched to the load that answered it. Two *concurrent*
         * acquisitions can therefore be attributed to each other's durations; the count, the
         * outcomes and every serial acquisition are exact, which is what the metric is read for.
         */
        private val licenceAcquisitionsInFlight = ArrayDeque<Long>()

        /**
         * DRM sessions this measurement session has seen acquired and not yet released.
         *
         * The ceiling on how many acquisitions can still be in flight, and therefore what makes an
         * unpaired start *stale* rather than pending: a session released without ever being keyed
         * produces no keys-loaded and no error, so nothing else would ever close its start, and the
         * next rotation's `onDrmKeysLoaded` would be paired with it and reported as a licence
         * lasting the whole gap.
         */
        private var drmSessionsOpen = 0

        /** Records that a DRM session was acquired, keyed or not. */
        fun drmSessionOpened() {
            drmSessionsOpen++
        }

        /**
         * Records that a DRM session was released, and discards any start that can no longer close.
         *
         * A discarded start reports nothing at all. An acquisition that ended because the player let
         * go of the session got neither keys nor a refusal, so none of [LicenceOutcome]'s three
         * values is true of it, and inventing a fourth for a case a viewer never waited on the end
         * of would be a value every pipeline has to learn to ignore.
         */
        fun drmSessionReleased() {
            // Clamped, because a release can arrive for a session acquired before this measurement
            // session opened — a pooled player recycled mid-licence does exactly that.
            drmSessionsOpen = (drmSessionsOpen - 1).coerceAtLeast(0)
            while (licenceAcquisitionsInFlight.size > drmSessionsOpen) {
                licenceAcquisitionsInFlight.removeFirst()
            }
        }

        /** Records that an acquisition began at [nowMs]. */
        fun openLicenceAcquisition(nowMs: Long) {
            // A backstop under the release accounting above, not the mechanism: Media3 dispatches an
            // acquisition per *reference* taken on a session, so two periods sharing one session can
            // push two starts that one keys-loaded closes, and only a release bounds the rest. Eight
            // is well past any real concurrency — content with more than a couple of licence policies
            // does not exist — and dropping the oldest keeps the newest acquisition, the one a later
            // callback is most likely about, pairable.
            if (licenceAcquisitionsInFlight.size >= MAX_LICENCE_ACQUISITIONS_IN_FLIGHT) {
                licenceAcquisitionsInFlight.removeFirst()
            }
            licenceAcquisitionsInFlight.addLast(nowMs)
        }

        /** The start of the oldest acquisition in flight, removed, or null when none is. */
        fun closeLicenceAcquisition(): Long? = licenceAcquisitionsInFlight.removeFirstOrNull()

        /**
         * Gives up on a seek that can no longer complete, without reporting a latency for it.
         *
         * A seek interrupted by an error or by the end of the stream never resumes at its target, so
         * there is no `SeekCompleted` to emit — and leaving it in flight would tag every subsequent
         * stall as seek-induced. The exclusion window is not started either: nothing completed, so
         * there is nothing for a window to follow.
         */
        fun abandonSeek() {
            seekRequestedAtMs = null
        }

        /**
         * Whether a stall beginning at [nowMs] is attributable to a seek, and therefore out of
         * rebuffer ratio.
         *
         * ref: `docs/telemetry-schema.md`, *Rebuffering* — a stall is seek-induced when it starts
         * between a `SeekRequested` and its `SeekCompleted`, or within
         * [SEEK_EXCLUSION_WINDOW_MS] after that completion. The window is SuperPlayer's own number
         * rather than the standard's, because CTA-2066 defines the ratio and does not fix the
         * exclusion boundary in observable terms; the document argues the value and is where it
         * changes, with a schema-version bump.
         *
         * The reason for excluding at all is that a viewer who drags a scrub bar into unbuffered
         * content expects a wait. Counting it makes a player look worse the more its viewers seek,
         * which turns a delivery metric into a measure of viewer behaviour.
         */
        fun isSeekInducedAt(nowMs: Long): Boolean {
            if (seekRequestedAtMs != null) return true
            val completedAt = seekCompletedAtMs ?: return false
            return nowMs - completedAt <= SEEK_EXCLUSION_WINDOW_MS
        }
    }

    private companion object {

        /**
         * The cadence the three periodic events share; `docs/telemetry-schema.md` states it.
         *
         * Not `const`, deliberately. A `const val` in a *private* companion is still a public static
         * field on the JVM, so it lands in `api/superplayer-telemetry.api` as a number a consumer
         * can read — and the whole point of `samplingIntervalMs` travelling on every sample is that
         * a consumer takes the cadence off the event rather than copying it out of the library.
         */
        val SAMPLING_INTERVAL_MS = 10_000L

        /** See [OpenSession.isSeekInducedAt]; the document is where this number is argued. */
        val SEEK_EXCLUSION_WINDOW_MS = 1_000L

        /** See [OpenSession.openLicenceAcquisition], which argues the bound. */
        val MAX_LICENCE_ACQUISITIONS_IN_FLIGHT = 8
    }
}

/**
 * The rendition's declared peak bitrate, which is what a manifest states.
 *
 * ref: `docs/telemetry-schema.md`, *Bitrate* — the number is what the manifest declares and not a
 * measurement of what was transferred. Media3 carries both a peak and an average on [Format] and
 * fills whichever the manifest supplied, so the peak is preferred and the average is the fallback;
 * a format that declares neither has no bitrate to report and yields null rather than a zero a
 * dashboard would average.
 */
private fun Format.declaredPeakBitrateBps(): Int? = when {
    peakBitrate != Format.NO_VALUE -> peakBitrate
    averageBitrate != Format.NO_VALUE -> averageBitrate
    else -> null
}

/**
 * What failed, in SuperPlayer's own vocabulary rather than the engine's.
 *
 * ref: Media3 1.11.0's `PlaybackException.ERROR_CODE_*` ranges, which are what this buckets when
 * nothing better is available. The bucketing is deliberately coarse (see [PlaybackFailure]): a
 * taxonomy fine enough to act on automatically is `superplayer-resilience`'s, and a partial second
 * copy here would give a data team two answers to one question.
 *
 * [classified] is that module's answer where the player had one to give, and it settles both of the
 * fields a classification touches (ADR-0011 rule 3): [PlaybackFailure.classification] is its stable
 * name, and [PlaybackFailure.category] is *its* row in the one-to-one table the classifier owns,
 * rather than the band's reading of the same failure. Nothing here interprets it — no branch on the
 * name, no table of its own — which is the whole of what "reads the classifier and keeps no second
 * copy" means for this function.
 *
 * With no classification the two fall back to exactly what this reported before Phase 5: the band,
 * and no classification at all.
 *
 * [PlaybackFailure.code] is `errorCodeName` either way — a string rather than the integer — because
 * it must survive an engine that renumbers, because it is a grouping key in a pipeline rather than
 * something to branch on, and because what the *engine* said is a different fact from what the
 * classifier made of it.
 */
private fun PlaybackException.toPlaybackFailure(classified: SuperPlayerError?): PlaybackFailure = PlaybackFailure(
    // ref: Media3 1.11.0 `PlaybackException` allocates its error codes in documented thousands —
    // 1xxx miscellaneous, 2xxx input/output, 3xxx content parsing, 4xxx decoding, 5xxx audio
    // renderer, 6xxx DRM, 7xxx video frame processing. Bucketing on the band rather than on a list
    // of individual codes is what makes a code Media3 adds land in the right category instead of in
    // UNKNOWN, which is the failure mode a hand-maintained list has and nobody notices until a
    // dashboard's "unknown" slice grows after an engine upgrade.
    category = classified?.category ?: when (errorCode / ERROR_CODE_BAND) {
        2 -> FailureCategory.NETWORK
        3 -> FailureCategory.SOURCE
        4 -> FailureCategory.DECODER
        6 -> FailureCategory.DRM
        5, 7 -> FailureCategory.RENDERER
        else -> FailureCategory.UNKNOWN
    },
    code = errorCodeName,
    message = message,
    classification = classified?.causeClass,
)

/** The width of one of Media3's documented error-code bands; see [toPlaybackFailure]. */
private const val ERROR_CODE_BAND = 1_000
