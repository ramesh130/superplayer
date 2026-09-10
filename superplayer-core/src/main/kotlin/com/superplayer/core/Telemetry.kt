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

import android.util.Log

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

    public companion object {

        /**
         * One sink that fans every event out to all of [sinks], in the order they were given.
         *
         * The shape `PRD.md` §2.2 spells — `TelemetrySink.composite(analytics, LogcatSink)` — and the
         * reason it is a factory on this interface rather than a class of its own: composing sinks is
         * something an app does to core's own type, and a named class would put a second concept in
         * front of the one thing a consumer has to understand.
         *
         * **A child that throws costs nobody else their event.** Each child is called inside its own
         * `try`, so a failure in one is contained: the remaining children still receive the event,
         * and nothing propagates back to the caller — which today is a `setMediaRequest` or a
         * `release` on the application thread, so an escaping exception would take playback down with
         * it. The failure is reported to logcat, because a sink that silently stops receiving is the
         * kind of defect nobody notices for a fortnight.
         *
         * `Throwable` rather than `Exception`, deliberately. The point is that the telemetry path
         * cannot break playback, and a sink written against an analytics SDK that throws an `Error` —
         * a `NoClassDefFoundError` from a missing optional dependency is the usual one — breaks it
         * exactly as thoroughly as one that throws an exception.
         *
         * Delivery to the children is synchronous and on the calling thread, because this composes
         * sinks and does not change how they are called; the thread a sink sees is whatever the
         * collector's own delivery gives it.
         */
        @JvmStatic
        public fun composite(vararg sinks: TelemetrySink): TelemetrySink {
            // Copied rather than held: `vararg` hands over an array the caller may still be holding,
            // and a composite whose membership changed under it would drop events with no trace.
            val children = sinks.toList()
            return TelemetrySink { event ->
                children.forEach { child ->
                    try {
                        child.onEvent(event)
                    } catch (throwable: Throwable) {
                        Log.w(TAG, "Telemetry sink ${child.javaClass.name} threw; other sinks unaffected", throwable)
                    }
                }
            }
        }

        /**
         * The same tag `superplayer-telemetry`'s `LogcatSink` writes under, deliberately.
         *
         * A person debugging telemetry greps one tag, and a sink that threw is the single most
         * interesting thing that tag can carry — a warning filed under a second name is invisible to
         * the grep the sink's own KDoc recommends. It is a duplicated literal rather than a shared
         * constant because the dependency points the wrong way: core may not name a telemetry type
         * (`docs/modules.md`), and inverting that for a string is a worse trade than repeating it.
         */
        private const val TAG: String = "SuperPlayerQoE"
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

    /**
     * The consumer declared intent to play, at [monotonicTimeMs] on `SystemClock.elapsedRealtime()`.
     *
     * Core forwards [SuperPlayer.declarePlaybackIntent] here and does nothing else with it: the
     * declaration is the start boundary of time to first frame, and what a boundary means is the
     * schema's business rather than the facade's. Hold it and use it for the next session that
     * starts; a session that opens without one measures from its own start and says so through
     * [TtffStartBoundary.CONTENT_ADOPTED].
     *
     * Called before [startSession] in the ordinary case — the app declares intent at the tap and
     * loads the content once its catalogue answers — but nothing enforces that, and a declaration
     * that arrives after the session has already produced its first frame is a late declaration
     * rather than an error. It may be called more than once for one session; the collector decides
     * which reading survives and `docs/telemetry-schema.md` states which.
     */
    public fun declareIntent(monotonicTimeMs: Long)

    /** The player is about to be released. Unregister everything [attach] registered. */
    public fun detach()
}
