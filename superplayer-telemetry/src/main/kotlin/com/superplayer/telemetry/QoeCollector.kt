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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryCollector
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.core.TtffStartBoundary
import java.util.UUID

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
 * ## What it emits today
 *
 * The session boundary and nothing else: one [TelemetryEvent.SessionStarted] when the player takes
 * content on, one [TelemetryEvent.SessionEnded] when it is released, recycled, or moved to different
 * content. Those two are the seam's proof, not the feature — the CTA-2066 QoE metrics (time to first
 * frame, rebuffer ratio, startup failure, bitrate, dropped frames) are issue #35's definitions and
 * issue #36's computation, and they are derived inside [analyticsListener], which is registered now
 * and deliberately contributes nothing yet.
 *
 * Session boundaries come from core rather than being inferred here: only core knows which item
 * change carried a [com.superplayer.core.MediaRequest] and which was a raw `setMediaItem`, and only
 * core knows the difference between a pooled player being recycled and one being released.
 *
 * ## One collector, one player
 *
 * A collector holds the player it was attached to and the session currently open on it. Build one
 * per player; a pool builds one per pooled player, and each of them reports sessions under its own
 * ids.
 */
public class QoeCollector(private val sink: TelemetrySink) : TelemetryCollector {

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
     * Media3's own analytics registration, which is where every metric this collector will compute
     * comes from: the engine reports load, buffer, decoder and renderer events here with the
     * playback-thread timing intact, which polling a [com.superplayer.core.PlaybackSession] or
     * wrapping a `Player.Listener` cannot reproduce.
     *
     * It observes nothing yet. It is registered anyway, in this issue, because *whether a listener
     * exists at all* is the thing ADR-0008 rule 2 constrains and `SuperPlayerTelemetryTest` asserts:
     * a player built with no collector must register none. Issue #36 fills the callbacks in.
     */
    private val analyticsListener: AnalyticsListener = object : AnalyticsListener {}

    override fun attach(player: SuperPlayer) {
        check(this.player == null) { "A QoeCollector measures one player; build one per player" }
        this.player = player
        // `exoPlayer` is the escape hatch ADR-0001 rule 2 names, used here from inside the library
        // rather than by a consumer: this is the only way to reach Media3's analytics registration,
        // and the point of the seam is that a consumer never has to.
        player.exoPlayer.addAnalyticsListener(analyticsListener)
    }

    override fun startSession(contentId: String) {
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
            id = UUID.randomUUID().toString(),
            contentId = contentId,
            ttffStartMonotonicMs = intentMs ?: startedAtMonotonicMs,
            ttffStartBoundary =
            if (intentMs == null) TtffStartBoundary.CONTENT_ADOPTED else TtffStartBoundary.USER_INTENT,
        )
        openSession = session
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

    override fun endSession() {
        val session = openSession ?: return
        // Cleared before the emit, so that a sink which re-enters — a synchronous sink is on the
        // caller's thread today — cannot see a session that has already ended.
        openSession = null
        emit(
            TelemetryEvent.SessionEnded(
                sessionId = session.id,
                contentId = session.contentId,
                timestampMs = System.currentTimeMillis(),
                monotonicTimeMs = SystemClock.elapsedRealtime(),
                // Nothing is dropped yet; see the field's own KDoc and issue #37.
                droppedEventCount = 0,
            ),
        )
    }

    override fun detach() {
        player?.exoPlayer?.removeAnalyticsListener(analyticsListener)
        player = null
        // A declaration nobody spent belongs to no session, and a collector that kept it would hand
        // it to whichever session opened next on a collector that has been reattached.
        declaredIntentMonotonicMs = null
    }

    /**
     * Hands [event] to the sink.
     *
     * Synchronous, on whatever thread caused the event — the application thread, for the three
     * signals core sends. **That is in tension with ADR-0008 rule 4**, which says a sink is never
     * called on a thread the engine needs, and it is a scoped placeholder rather than the intended
     * shape: issue #37 puts a bounded queue here, drains it off the player's threads, and counts
     * what it discards into [TelemetryEvent.SessionEnded.droppedEventCount]. Until then the sink's
     * KDoc carries the warning a consumer needs, which is that a blocking sink blocks playback.
     */
    private fun emit(event: TelemetryEvent) {
        sink.onEvent(event)
    }

    /**
     * A session that has started and not yet ended.
     *
     * [ttffStartMonotonicMs] and [ttffStartBoundary] are settled when the session opens and are what
     * issue #36's first-frame callback subtracts from; they are held rather than emitted because
     * ADR-0008 rule 4's amendment forbids adding an event caused by an engine callback until #37
     * moves delivery off the engine's threads. The definition lands in this release; the number
     * lands in that one.
     */
    private class OpenSession(
        val id: String,
        val contentId: String,
        val ttffStartMonotonicMs: Long,
        val ttffStartBoundary: TtffStartBoundary,
    )
}
