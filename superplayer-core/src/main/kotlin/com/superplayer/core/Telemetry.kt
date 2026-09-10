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

package com.superplayer.core

/**
 * Where measurement leaves the library: one call, one event, the consumer's own pipeline underneath.
 *
 * A sink is the only thing an app has to write to get playback telemetry out of SuperPlayer — an
 * adapter onto whatever analytics SDK, log or metrics endpoint the app already has. Nothing in this
 * interface names a Media3 type, and that is ADR-0008 rule 1 rather than a preference: a sink is an
 * app's data contract, and binding it to the engine's vocabulary would make an engine upgrade a
 * schema migration.
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setTelemetry(QoeCollector(sink = { event -> analytics.record(event) }))
 *     .build()
 * ```
 *
 * ## What a sink must tolerate
 *
 * **Gaps.** Delivery is at-most-once, bounded and lossy under pressure (ADR-0008 rule 3): a sink
 * that falls behind loses events rather than slowing playback down, and the count of what was lost
 * arrives on [TelemetryEvent.SessionEnded.droppedEventCount]. A metric computed by summing events
 * can therefore undercount, and that counter is what makes it detectable.
 *
 * **New event types.** [TelemetryEvent] is sealed, so a `when` over it can be exhaustive without an
 * `else` — and a sink that takes the compiler up on that will fail to compile when a later version
 * adds an event. An `else` branch is the forward-compatible spelling; prefer it unless being told
 * about additions is what you want.
 *
 * ## Which thread this is called on
 *
 * Today: the thread that caused the event, which for the session boundaries is the application
 * thread the player was built on. That is **not** what ADR-0008 rule 4 requires — a sink must never
 * be called on a thread the engine needs — and it is a scoped placeholder rather than the intended
 * contract. Issue #37 moves delivery onto a bounded queue drained off that thread, and until it
 * lands a sink must not block: no network call, no disk write, no lock a slow caller holds.
 */
public fun interface TelemetrySink {

    /** Hands [event] to whatever this sink writes to. Must not block — see the class KDoc. */
    public fun onEvent(event: TelemetryEvent)
}

/**
 * What SuperPlayer measured, as one sealed hierarchy rooted in the module every consumer takes.
 *
 * One vocabulary rather than one per module, versioned by [SCHEMA_VERSION] (ADR-0008 rule 5). The
 * version tracks a metric's *meaning*, not the shape of the class carrying it: adding a field, or
 * adding an event type, leaves it alone; changing what an existing number counts — a different
 * rebuffer denominator, a different start boundary for time-to-first-frame — is what raises it, so
 * that a consumer comparing two releases' numbers can tell whether they are comparable.
 *
 * Every event names the session it belongs to and the content that session is of, so that events
 * from concurrently playing players — a feed holds several — are separable in a pipeline that
 * receives them interleaved.
 *
 * What exists here is the session boundary and nothing else. The QoE metrics that CTA-2066 defines —
 * time to first frame, rebuffer ratio, startup failures, bitrate and dropped frames — are issue
 * #35's vocabulary and #36's computation, and they arrive as further subclasses of this type.
 */
public sealed class TelemetryEvent {

    /** The session this event belongs to; the same value for every event between start and end. */
    public abstract val sessionId: String

    /** The [MediaRequest.contentId] the session is of — the app's own identifier, never a URL. */
    public abstract val contentId: String

    /**
     * When this event happened, as a wall-clock epoch millisecond.
     *
     * Wall clock rather than a monotonic reading because the consumer of this is a pipeline that has
     * to line these events up against events from elsewhere in the app.
     */
    public abstract val timestampMs: Long

    /** The meaning-version of the metrics this event carries — see the class KDoc. */
    public val schemaVersion: Int get() = SCHEMA_VERSION

    /**
     * A player took content on and a measurement session opened for it.
     *
     * Emitted from the one place both `setMediaRequest` and a session controller's resolved content
     * pass through, so content started from a car head unit opens a session exactly like content
     * started from the app.
     */
    public data class SessionStarted(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        /** The profile the player was built with — what kind of playback this session measures. */
        public val profile: PlaybackProfile,
    ) : TelemetryEvent()

    /**
     * The session closed: the player was released, recycled into a [PlayerPool], or moved on to
     * different content.
     *
     * The session's terminal event, and the one ADR-0008 rule 3 says may never be dropped, because
     * it is what carries [droppedEventCount].
     */
    public data class SessionEnded(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        /**
         * How many events of this session the delivery path discarded under pressure.
         *
         * Always zero today: delivery is synchronous and nothing is dropped yet. The field is here
         * rather than added later because rule 3 makes it part of the contract a consumer writes
         * against — a pipeline that has no place to put a drop count is one that will silently
         * undercount when issue #37 makes delivery lossy.
         */
        public val droppedEventCount: Int = 0,
    ) : TelemetryEvent()

    public companion object {
        /**
         * The current meaning-version of this vocabulary. See the class KDoc for what moves it.
         */
        public const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * The thing `SuperPlayer.Builder.setTelemetry` takes: something that watches a player and writes to
 * a [TelemetrySink].
 *
 * Core declares the interface; `superplayer-telemetry` implements it as `QoeCollector`, which is
 * where every Media3 analytics type and every metric definition lives. That split is ADR-0008's
 * rules 1 and 2, and the phase rule (`docs/modules.md`) enforces the half of it that says core may
 * not name a telemetry type.
 *
 * ## Why a collector interface rather than a bare sink
 *
 * ADR-0008 left this spelling open and named two candidates: a collector that *is* a [TelemetrySink]
 * wrapping another sink, so core's API stays at one type; or a core-owned collector interface core
 * attaches itself. This is the second, for two reasons.
 *
 * The first is that it makes "attached a sink and silently got nothing" unexpressible. With
 * collector-as-sink, `setTelemetry(mySink)` compiles — it is a [TelemetrySink] — and produces a
 * player that measures nothing at all, because the object that knows how to derive events was the
 * one the consumer forgot to wrap. The compiler catches that here.
 *
 * The second is [resetForReuse]. A pooled player ends a session and starts another without ever
 * being released, so a collector needs a lifecycle distinct from the sink's: [startSession] and
 * [endSession] can fire many times between one [attach] and one [detach]. A sink has no shape for
 * that.
 *
 * ## Why [attach] takes the player rather than returning a listener
 *
 * The registration must happen in telemetry, not in core. Core could ask a collector for something
 * to register — but the only thing Media3 has to register is an `AnalyticsListener`, which is an
 * `@UnstableApi` type, and putting it in a signature here would breach ADR-0001 rule 2 and fail
 * `verifyNoUnstableMedia3InPublicApi`. So core hands over the built player and the collector reaches
 * the engine through `SuperPlayer.exoPlayer` itself.
 *
 * What keeps ADR-0008 rule 2's "a player built without telemetry pays nothing" true is that core
 * calls [attach] only when a collector was supplied: no collector, no call, no listener registered
 * and nothing allocated.
 */
public interface TelemetryCollector {

    /**
     * Binds this collector to [player] for the player's lifetime: register listeners here.
     *
     * Called once, from `SuperPlayer.Builder.build`, on the thread that built the player and before
     * the player is handed back to the caller. A collector that survives its player — one shared
     * between two — is not supported; build one per player.
     */
    public fun attach(player: SuperPlayer)

    /**
     * A measurement session opens for [contentId]. Mint a session id here.
     *
     * Core signals this rather than letting the collector infer it from item transitions, because
     * only core knows which item changes carry content identity. Called again without an
     * intervening [endSession] when a player moves straight to different content: close the open
     * session first.
     */
    public fun startSession(contentId: String)

    /**
     * The open session closes — the player was released or recycled. A no-op when none is open;
     * release after recycle must not produce two ends.
     */
    public fun endSession()

    /** The player is about to be released. Unregister everything [attach] registered. */
    public fun detach()
}
