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

import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * A [TelemetryCollector] that derives core's own events and writes them to a [TelemetrySink],
 * standing in for `superplayer-telemetry`'s `QoeCollector` in core's tests.
 *
 * A stand-in rather than the real thing, and that is the phase rule (`docs/modules.md`) rather than
 * a preference: `superplayer-core` may not depend on `superplayer-telemetry`, so no test here can
 * name `QoeCollector`. What these tests are about is the half of the seam that *is* core's — that
 * the lifecycle signals are sent, once each, at the right moments — and that half is observable
 * through any collector. `QoeCollectorTest` in the telemetry module pins the real one against the
 * real builder.
 *
 * It writes through a real [TelemetrySink] rather than recording into a list of its own, so that a
 * test asserting on what a consumer receives is asserting on something that actually crossed the
 * boundary a consumer implements — the sink is core's own type, so nothing about the module split
 * makes that harder.
 *
 * Session ids are minted the way a collector must: a fresh one per session, so a test asserting that
 * a recycled player starts a *new* session has something to compare.
 *
 * It registers an [AnalyticsListener] on attach and removes it on detach, as `QoeCollector` does and
 * for the same reason: that registration is the observable cost of telemetry, and
 * `SuperPlayerTelemetryTest.aPlayerBuiltWithNoTelemetryRegistersNoAnalyticsListener` counts it.
 */
class RecordingTelemetry(private val sink: TelemetrySink) : TelemetryCollector {

    var attachCount = 0
        private set

    var detachCount = 0
        private set

    private var player: SuperPlayer? = null

    /** Registered on attach and removed on detach, standing in for `QoeCollector`'s own. */
    private val analyticsListener: AnalyticsListener = object : AnalyticsListener {}
    private var openSession: TelemetryEvent.SessionStarted? = null
    private var sessionsStarted = 0

    override fun attach(player: SuperPlayer) {
        attachCount++
        this.player = player
        player.exoPlayer.addAnalyticsListener(analyticsListener)
    }

    /** The last declared intent, as `SuperPlayer.declarePlaybackIntent` handed it over. */
    var declaredIntentMonotonicMs: Long? = null
        private set

    var declareIntentCount = 0
        private set

    override fun declareIntent(monotonicTimeMs: Long) {
        declareIntentCount++
        declaredIntentMonotonicMs = monotonicTimeMs
    }

    override fun startSession(contentId: String) {
        endSession()
        val attached = checkNotNull(player)
        val started = TelemetryEvent.SessionStarted(
            sessionId = "session-${sessionsStarted++}",
            contentId = contentId,
            timestampMs = TIMESTAMP_MS,
            monotonicTimeMs = MONOTONIC_MS,
            profile = attached.profile,
            decision = attached.playbackDecision,
        )
        openSession = started
        sink.onEvent(started)
    }

    override fun endSession() {
        val open = openSession ?: return
        openSession = null
        sink.onEvent(
            TelemetryEvent.SessionEnded(
                sessionId = open.sessionId,
                contentId = open.contentId,
                timestampMs = TIMESTAMP_MS,
                monotonicTimeMs = MONOTONIC_MS,
            ),
        )
    }

    /** How many times core forwarded the platform's memory-pressure signal. */
    var memoryPressureCount = 0
        private set

    override fun onMemoryPressure() {
        memoryPressureCount++
    }

    override fun detach() {
        detachCount++
        player?.exoPlayer?.removeAnalyticsListener(analyticsListener)
        player = null
    }

    private companion object {
        /**
         * Fixed, because nothing here asserts on time. A wall clock in a fixture is a value that
         * differs between two runs of the same assertion for no reason the assertion is about.
         */
        const val TIMESTAMP_MS = 1_700_000_000_000L

        /** Fixed for the same reason [TIMESTAMP_MS] is; nothing here asserts on an interval. */
        const val MONOTONIC_MS = 42_000L
    }
}
