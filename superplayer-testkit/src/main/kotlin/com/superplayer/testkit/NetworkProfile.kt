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

package com.superplayer.testkit

import java.util.Random

/**
 * The six network profiles `PRD.md` Part 6 names, each as a [ThroughputTrace].
 *
 * Synthetic on purpose, and alongside recorded traces rather than instead of them: a profile is
 * legible — a regression under `LTE_WITH_DROPOUTS` is attributable to a dropout — while a recorded
 * trace has the bursty, autocorrelated variance a smooth profile lacks. `docs/throughput-traces.md`
 * has the argument; the benchmark matrix needs both.
 *
 * **Every number carries a public source**, per the clean-room rule in `CONTRIBUTING.md`, and where
 * the source fixes the regime but not the exact value, the comment says which part is this
 * project's choice and why. Round-trip time is 0 — not modelled — everywhere except
 * [HIGH_LATENCY], so that latency is the one variable that profile changes.
 */
public enum class NetworkProfile(public val trace: ThroughputTrace) {

    /** Stable WiFi at 20 Mbit/s: enough for the top rung of any ladder, and never varying. */
    STABLE_WIFI(
        ThroughputTrace.Builder()
            .add(ONE_SECOND_MS, STABLE_WIFI_BPS, NetworkTransport.WIFI)
            .build(),
    ),

    /** Congested WiFi averaging 3 Mbit/s and swinging ±50% from one second to the next. */
    CONGESTED_WIFI(congestedWifi()),

    /** LTE at 5 Mbit/s that drops out for two seconds, every [LTE_DROPOUT_PERIOD_MS]. */
    LTE_WITH_DROPOUTS(
        ThroughputTrace.Builder()
            .add(DROPOUT_PERIOD_MS - LTE_DROPOUT_MS, LTE_BPS, NetworkTransport.CELLULAR)
            .add(LTE_DROPOUT_MS, 0, NetworkTransport.CELLULAR)
            .build(),
    ),

    /** 3G at 1 Mbit/s: the bottom of the ladder and little else. */
    THREE_G(
        ThroughputTrace.Builder()
            .add(ONE_SECOND_MS, THREE_G_BPS, NetworkTransport.CELLULAR)
            .build(),
    ),

    /**
     * Stable WiFi until [HANDOVER_AT_MS], then LTE for good — a transport change, not only a rate
     * change, and one that holds rather than loops back.
     */
    WIFI_TO_CELLULAR_HANDOVER(
        ThroughputTrace.Builder()
            .add(WIFI_BEFORE_HANDOVER_MS, STABLE_WIFI_BPS, NetworkTransport.WIFI)
            // No outage between the two. How long one lasts depends on the HTTP stack — whether a
            // connection bound to the lost network is noticed dead, and how fast a new one opens —
            // which ADR-0004 leaves open; a number here would be a guess about a decision not made.
            .add(ONE_SECOND_MS, LTE_BPS, NetworkTransport.CELLULAR)
            .holdAtEnd()
            .build(),
    ),

    /**
     * A 600 ms round trip, at [STABLE_WIFI]'s bandwidth so that latency is the only thing that
     * differs. Every request waits one round trip before its first byte.
     */
    HIGH_LATENCY(
        ThroughputTrace.Builder()
            // A geostationary satellite link is what a 600 ms round trip is in the field, and the
            // device reaches the satellite terminal over the home WiFi it is connected to.
            .add(ONE_SECOND_MS, STABLE_WIFI_BPS, NetworkTransport.WIFI, HIGH_LATENCY_RTT_MS)
            .build(),
    ),
    ;

    public companion object {
        /**
         * When [WIFI_TO_CELLULAR_HANDOVER] leaves WiFi.
         *
         * // ref: derivation (CONTRIBUTING.md rule 4), not a claim about a network: `PRD.md` Part 6
         * asks for the handover *mid-playback*, and ten seconds is five 2-second segments in —
         * past startup, so the switch lands on a session that is playing rather than one still
         * starting, and early enough that a minute of content has most of its loads still ahead.
         */
        public const val HANDOVER_AT_MS: Long = WIFI_BEFORE_HANDOVER_MS

        /**
         * How often [LTE_WITH_DROPOUTS] drops out.
         *
         * // ref: derivation (CONTRIBUTING.md rule 4), not a claim about a network: how often a real
         * link drops out is exactly what a recorded trace is for. Twenty seconds is ten 2-second
         * segments between dropouts, so each one's effect on a session is separable from the last
         * one's in a trace of it.
         */
        public const val LTE_DROPOUT_PERIOD_MS: Long = DROPOUT_PERIOD_MS
    }
}

// The two times a test names, declared here as well as on the companion because an enum entry is
// built before its companion is, and cannot read one of the companion's constants while it is.
private const val WIFI_BEFORE_HANDOVER_MS = 10_000L
private const val DROPOUT_PERIOD_MS = 20_000L

// ref: derivation (CONTRIBUTING.md rule 4). The length a constant-rate profile's one stretch is
// written at. It makes no claim about a network: a looping trace at one rate delivers identically at
// any stretch length, so this is only the unit the written-out trace reads in.
private const val ONE_SECOND_MS = 1_000L

// ref: Netflix Help Center, "Internet connection speed recommendations" (help.netflix.com/node/306):
// 15 Mbit/s or more for 4K/UHD. 20 Mbit/s clears it with room left over, so a stable WiFi link is
// one on which the network is never the reason a rung was not chosen — which is what makes it the
// control row of the matrix. The exact 20 is `PRD.md` Part 6's.
private const val STABLE_WIFI_BPS = 20_000_000L

// ref: the same Netflix recommendations: 3 Mbit/s or more for HD (720p), 5 for full HD (1080p).
// A mean of 3 sits exactly on the HD threshold, and ±50% swings it from well below that (1.5) to
// just short of full HD (4.5) — a link on which ABR has to keep choosing, which is the point of
// congestion as a test condition. `PRD.md` Part 6 names the mean and the swing; the thresholds above
// are the public reason they sit where they do.
private const val CONGESTED_WIFI_MEAN_BPS = 3_000_000L
private const val CONGESTED_WIFI_SWING = 0.5

// ref: HSDPA dataset — Riiser et al., "Commute Path Bandwidth Traces from 3G Networks: Analysis and
// Applications", MMSys 2013 — and the van der Hooft et al. 4G/LTE logs (IEEE Communications Letters
// 20(11), 2016) both log one sample a second, so a synthetic profile that varies does so at the
// granularity the recorded ones do.
private const val CONGESTED_WIFI_SAMPLE_MS = 1_000L

// ref: derivation (CONTRIBUTING.md rule 4). Thirty pairs is a minute before the trace loops: long
// enough to hold thirty 2-second segments, so a session sees many distinct rates before any repeat,
// and short enough to read in a diff. Makes no claim about a network beyond what the swing does.
private const val CONGESTED_WIFI_PAIRS = 30

/**
 * Any fixed seed: what matters is that it is fixed. `java.util.Random`'s generator is specified by
 * the Java SE API itself (// ref: `java.util.Random` class documentation: "particular algorithms are
 * specified for the class Random"), so this sequence is the same on every JVM that will ever run it.
 */
private const val CONGESTED_WIFI_SEED = 2066L

// ref: Netflix's 5 Mbit/s for full HD (above) — LTE at exactly the 1080p threshold, so a dropout
// costs the top rung its headroom rather than leaving plenty to spare. Also under the 6 Mbit/s
// average that Mao et al., "Neural Adaptive Video Streaming with Pensieve", SIGCOMM 2017 §5.1,
// restricts its evaluation traces to (with a minimum above 0.2), so that bitrate selection is
// neither trivial nor hopeless.
private const val LTE_BPS = 5_000_000L

// ref: 3GPP TS 36.331 (E-UTRA RRC), timer T310, whose values have been ms0…ms2000 since Release 8:
// started when the physical layer reports the radio link out of sync (after N310 consecutive
// indications), it runs for at most 2000 ms before the UE declares radio link failure. A dropout of
// one maximal T310 is the longest interruption LTE sits through *without* giving up on the link —
// the case a player has to ride out rather than reconnect from. The N310 detection time before it is
// not added.
private const val LTE_DROPOUT_MS = 2_000L

// ref: Riiser et al. (above) — the HSDPA dataset is 3G throughput measured on commutes — and
// Pensieve §5.1's band again: 1 Mbit/s is near its bottom, below Netflix's 3 Mbit/s
// HD threshold, which leaves a ladder only its lowest rungs. The sources place the regime; `PRD.md`
// Part 6 names the 1 within it.
private const val THREE_G_BPS = 1_000_000L

// ref: RFC 2488 §2, "Enhancing TCP Over Satellite Channels using Standard Mechanisms": the round
// trip over a geostationary satellite is at least 558 ms (2 × 279.0 ms at the edge of coverage),
// before any terrestrial hop or processing. 600 ms is that floor plus the terrestrial part, and is
// `PRD.md` Part 6's number.
private const val HIGH_LATENCY_RTT_MS = 600L

/**
 * Congested WiFi as pairs of seconds, each pair `mean + d` and `mean − d` in a random order.
 *
 * Antithetic pairs rather than independent draws, so the mean is *exactly* what `PRD.md` names
 * rather than whatever sixty draws average to — a profile called "3 Mbit/s" that averaged 2.9 would
 * be a different profile with the wrong name.
 */
private fun congestedWifi(): ThroughputTrace {
    val random = Random(CONGESTED_WIFI_SEED)
    val builder = ThroughputTrace.Builder()
    repeat(CONGESTED_WIFI_PAIRS) {
        val deviation = (random.nextDouble() * CONGESTED_WIFI_MEAN_BPS * CONGESTED_WIFI_SWING).toLong()
        val high = CONGESTED_WIFI_MEAN_BPS + deviation
        val low = CONGESTED_WIFI_MEAN_BPS - deviation
        val (first, second) = if (random.nextBoolean()) high to low else low to high
        builder.add(CONGESTED_WIFI_SAMPLE_MS, first, NetworkTransport.WIFI)
        builder.add(CONGESTED_WIFI_SAMPLE_MS, second, NetworkTransport.WIFI)
    }
    return builder.build()
}
