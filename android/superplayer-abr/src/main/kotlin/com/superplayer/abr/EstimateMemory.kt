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

package com.superplayer.abr

import androidx.annotation.VisibleForTesting
import com.superplayer.core.NetworkTransport
import com.superplayer.core.ThroughputEstimate

/**
 * The per-transport throughput estimates a process keeps in memory: ADR-0009 rule 8's holder, and
 * rule 9's reseed.
 *
 * **One per process**, the way `TelemetryDelivery`'s thread is: the estimate is a property of the
 * device's network rather than of any one player, so sixty pooled players share one memory rather
 * than keeping sixty. **Keyed by [NetworkTransport] and nothing finer** — not an SSID, not a cell,
 * not a place — so the memory is bounded by the type (three singletons and one case over a
 * three-valued enum plus null) and cannot identify a network. **In memory, and nowhere else.**
 * Nothing is written anywhere and process death forgets everything; ADR-0006 rule 2 is intact
 * because memory is not storage, and rule 8 says so at length.
 *
 * Each entry is the [SampleWindow] a [ThroughputEstimate] is computed from — the samples and the
 * time each was taken, which is everything the estimate is and nothing the estimate is not — so a
 * device that returns to WiFi resumes WiFi's window rather than a summary of it. The key is the
 * whole [NetworkTransport] value, generation included, so a cellular network whose generation the
 * app may not read (`Cellular(null)`) is a different entry from an LTE one: an app that gains the
 * permission mid-session reseeds once, from the cold default, and that is the coarser of the two
 * mistakes a key could make.
 *
 * ## What an old entry is worth
 *
 * A measurement is trusted as it stands for [FRESH_AGE_MS] after its newest sample, and forgotten
 * once [STALE_AGE_MS] old. Between the two, the *level* — the mean and the conservative percentile —
 * decays linearly toward the transport's cold default, while the spread and the sample count stay
 * as measured: the scatter is a fact about the network that was measured and does not become
 * smaller because time passed, whereas the level is the thing a commute changes. The decay is
 * linear because the argument for it is only that "measured here an hour ago" is worth less than
 * "measured here a minute ago" and more than a table, and a straight line is the honest shape for a
 * claim that weak.
 *
 * Thread-safe: samples arrive on loader threads, estimates are read from the playback and
 * application threads, and a transport change arrives on the connectivity service's.
 */
internal class EstimateMemory(
    private val coldDefaultBpsFor: (NetworkTransport) -> Long = ColdDefaults::bpsFor,
) {

    private val windows = HashMap<NetworkTransport, SampleWindow>()

    /** Records a sample of [bps] taken on [transport] at [nowMs]. */
    @Synchronized
    fun record(transport: NetworkTransport, bps: Long, nowMs: Long) {
        val window = windows.getOrPut(transport) { SampleWindow() }
        forgetIfStale(window, nowMs)
        window.add(bps, nowMs)
    }

    /**
     * What [transport] is believed to deliver as of [nowMs]: the window's own readings when it is
     * fresh, the cold default when there is nothing recent, and the decay between.
     */
    @Synchronized
    fun estimate(transport: NetworkTransport, nowMs: Long): ThroughputEstimate {
        val coldDefaultBps = coldDefaultBpsFor(transport)
        val window = windows[transport] ?: return coldDefault(coldDefaultBps)
        forgetIfStale(window, nowMs)
        if (window.isEmpty) return coldDefault(coldDefaultBps)
        val ageMs = nowMs - checkNotNull(window.newestAtMs)
        val trust = trustAt(ageMs)
        return ThroughputEstimate(
            meanBps = decayed(window.meanBps(), coldDefaultBps, trust),
            spreadBps = window.spreadBps(),
            conservativeBps = decayed(window.percentileBps(SampleWindow.CONSERVATIVE_PERCENTILE), coldDefaultBps, trust),
            sampleCount = window.size,
            newestSampleAgeMs = ageMs,
        )
    }

    /** Forgets every transport's samples. For a test's `@Before`, so one test's network is not the next's. */
    @VisibleForTesting
    @Synchronized
    fun forget() {
        windows.clear()
    }

    /**
     * Forgets [window] once its newest sample is [STALE_AGE_MS] old — or taken *after* [nowMs]. Elapsed
     * realtime never runs backwards within a process, so a sample from the future was timed on a clock
     * that has since restarted, which only a test's does, and a memory of another timeline is worth
     * nothing on this one. Without it, a player built by one test on a clock earlier than the last test's
     * samples would read a negative age.
     */
    private fun forgetIfStale(window: SampleWindow, nowMs: Long) {
        val newestAtMs = window.newestAtMs ?: return
        if (nowMs < newestAtMs || nowMs - newestAtMs >= STALE_AGE_MS) window.clear()
    }

    /** How far a measurement [ageMs] old is trusted over the cold default: 1 while fresh, 0 when stale. */
    private fun trustAt(ageMs: Long): Double = when {
        ageMs <= FRESH_AGE_MS -> 1.0
        ageMs >= STALE_AGE_MS -> 0.0
        else -> (STALE_AGE_MS - ageMs).toDouble() / (STALE_AGE_MS - FRESH_AGE_MS)
    }

    /** [measuredBps] weighted by [trust] against [coldDefaultBps], rounded down. */
    private fun decayed(measuredBps: Long, coldDefaultBps: Long, trust: Double): Long =
        if (trust == 1.0) measuredBps else (measuredBps * trust + coldDefaultBps * (1.0 - trust)).toLong()

    private fun coldDefault(bps: Long) = ThroughputEstimate(
        meanBps = bps,
        // Unknown rather than zero: nothing has been measured, and a policy given a spread of zero
        // would size a floor for a network that never varies.
        spreadBps = null,
        conservativeBps = null,
        sampleCount = 0,
        newestSampleAgeMs = 0,
    )

    companion object {
        /** The one memory the process keeps. `BandwidthOracle.Builder` hands every oracle this one. */
        val PROCESS: EstimateMemory = EstimateMemory()

        /**
         * How long a measurement is used as it stands: two minutes.
         *
         * ref: derivation (CONTRIBUTING.md rule 4), not a claim about a network. The window's own
         * samples span a few seconds of transfer, which is the horizon the predictor literature
         * measures over (Yin et al., SIGCOMM 2015, §5.1); a memory is not a prediction at that
         * horizon but a prior for a session that has not sent a byte yet, and two minutes is the
         * gap between finishing one title and starting the next on the same sofa — the case where
         * reseeding from what was just measured is clearly better than a table.
         */
        const val FRESH_AGE_MS: Long = 2 * 60_000L

        /**
         * How old a measurement is before it is worth nothing over the cold default: fifteen
         * minutes.
         *
         * ref: derivation (CONTRIBUTING.md rule 4), not a claim about a network. A WiFi network is
         * usually the same network for hours, but a cellular one at walking pace has crossed cells
         * and at driving pace has crossed towns in fifteen minutes, and the memory is keyed by
         * transport rather than by cell (rule 8), so it cannot tell the two apart and takes the
         * cellular horizon for both. The benchmark's cold-start cells are what would move this.
         */
        const val STALE_AGE_MS: Long = 15 * 60_000L
    }
}
