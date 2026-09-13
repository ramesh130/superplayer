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
 * What is known about the playback at the moment a [PlaybackPolicy] is asked for a decision.
 *
 * Every property is an *observation* in SuperPlayer's own vocabulary — no Media3 type and no Android
 * type appears here, which is ADR-0005 rule 1 and is what lets `superplayer-abr`'s tests construct
 * conditions by hand — and every property is optional. An observation not yet made is `null`, or an
 * empty [StallHistory], and that is distinct from zero: a policy that needs a value it was not given
 * treats it as *unknown*, never as nothing. `PlaybackConditions()` is therefore still what it was
 * when the type was empty — nothing observed — and every [PlaybackPolicy] written against the empty
 * type keeps compiling.
 *
 * ADR-0009 rule 1 is the list, and it is closed: these are the observations Phase 3's components
 * read, each named beside its property, and a property nothing reads is removed rather than kept for
 * later (ADR-0005's standing obligation). Rule 2 of the same ADR says which observations were
 * considered and deferred, and to which phase, so that nobody adds one here speculatively.
 *
 * How each value is produced from Android's and Media3's vocabulary is `ConditionsBinding.kt`'s,
 * the inverse of `EngineBinding.kt` and the only place that translation happens (ADR-0009 rule 3).
 * On a player built without engine components that can honour a changed decision, the policy is
 * consulted once with empty conditions and nothing is observed at all — see
 * [SuperPlayer.Builder.setPolicy].
 */
public data class PlaybackConditions(
    /**
     * The class of network the playback is on, or null if unobserved.
     *
     * Read by `BandwidthOracle`, which keys its per-transport memory on it (ADR-0009 rule 8); by
     * `NetworkAwareTrackSelection`, for a per-transport ceiling; and by `AdaptiveLoadControl`, for
     * the cushion depth.
     */
    val transport: NetworkTransport? = null,
    /**
     * What the network is believed to deliver, or null if no meter has reported one — which is the
     * case at construction, before a byte has moved, and for the whole life of a player whose meter
     * is Media3's own rather than one that implements core's reading seam.
     *
     * Read by `AdaptiveLoadControl`, which raises the playback floor on the spread rather than on
     * the mean, and by `NetworkAwareTrackSelection`, which selects on the percentile when the spread
     * is high and on the mean when it is not. Why the spread is here at all is ADR-0009's context
     * section: the error in a throughput prediction, not its level, is what produces a rebuffer.
     */
    val throughput: ThroughputEstimate? = null,
    /**
     * What this session has already suffered. [StallHistory.NONE] until a rebuffer has ended, and
     * again from the start of every session.
     *
     * Read by `AdaptiveLoadControl` for post-rebuffer hysteresis: the raised floor, the held
     * ceiling, and for how long. Hysteresis is the policy's own, which is why the history carries
     * *when* the last stall ended: a policy that wants a cooldown holds its previous answer until
     * that number is large enough, and [PlaybackPolicy.decide] stays a pure function.
     */
    val stallHistory: StallHistory = StallHistory.NONE,
    /**
     * What the manifest declared the content to be, or null until a manifest has been read.
     *
     * An observation rather than something the profile already says, because the profile is the
     * consumer's *intent* and this is what the content *turned out to be*, and ordinary apps have
     * the two disagree: a catalogue app plays live channels under [PlaybackProfile.VIDEO_ON_DEMAND]
     * because it never thought to switch, and an HLS event playlist that was live at adoption
     * becomes on-demand when its `EXT-X-ENDLIST` arrives. Read by `AdaptiveLoadControl`, whose
     * latency-priority mode is keyed on this and never on the profile.
     */
    val streamType: StreamType? = null,
    /**
     * The heap this app is allowed, in bytes, or null if the platform did not say.
     *
     * A budget rather than a live free-heap reading, because free heap under a garbage collector is
     * noise. It is the same reading `PlayerPool` sizes itself from. Read by `AdaptiveLoadControl`,
     * for the memory-aware ceiling on every branch: Media3 buffers media on the Java heap, so a
     * buffer target is a heap commitment.
     */
    val heapBudgetBytes: Long? = null,
    /**
     * The current playback speed as a factor, or null if unobserved. `1.0` is real time.
     *
     * Read by `AdaptiveLoadControl`: a buffer expressed in media time drains faster than real time
     * at 2×, so a target that is sufficient at 1× is half as deep as it looks.
     */
    val playbackSpeed: Float? = null,
) {
    init {
        require(heapBudgetBytes == null || heapBudgetBytes > 0) {
            "heapBudgetBytes must be positive when observed, was $heapBudgetBytes"
        }
        require(playbackSpeed == null || playbackSpeed > 0f) {
            "playbackSpeed must be positive when observed, was $playbackSpeed"
        }
    }
}

/**
 * The class of network a playback is on, as an observation a policy can act on.
 *
 * Deliberately coarse. A transport is the coarsest key that still carries the difference an
 * adaptive policy cares about — a WiFi estimate says nothing about the cellular network the device
 * has just moved to — and it is deliberately nothing finer: not an SSID, not a cell identity, not
 * anything that would let a remembered estimate identify a network or a place (ADR-0009 rule 8).
 *
 * ADR-0009 rule 1 calls this "a SuperPlayer enum" whose cellular case "carries a
 * `CellularGeneration`", and a Kotlin enum cannot carry a per-case value — so this is the sealed
 * form of exactly that closed set, with the one case that carries something. It is the same
 * departure in shape and none in substance: the generation travels with the transport rather than
 * as a second [PlaybackConditions] property, so a policy cannot see one without the other, and the
 * whole set is still finite (three singletons and one case over a three-valued enum plus null),
 * which is what bounds a memory keyed on it. `CONTEXT.md` already words it this way.
 *
 * ref: https://developer.android.com/reference/android/net/NetworkCapabilities — the vocabulary this
 * is translated from, and which ADR-0005 rule 1 keeps out of this type.
 */
public sealed class NetworkTransport {

    /** A WiFi network. */
    public data object Wifi : NetworkTransport()

    /** A wired network. */
    public data object Ethernet : NetworkTransport()

    /**
     * A cellular network, with the generation the platform reported.
     *
     * [generation] is null when the platform would not say. Reading it needs a permission most apps
     * do not hold, and a library that guessed a generation in its absence would be observing
     * something nobody measured.
     */
    public data class Cellular(public val generation: CellularGeneration?) : NetworkTransport()

    /**
     * Connected, but to a transport SuperPlayer does not classify — VPN over an unknown carrier,
     * Bluetooth tethering, a USB link — or to no network at all. Distinct from an unobserved
     * transport, which is a null [PlaybackConditions.transport].
     */
    public data object Unknown : NetworkTransport()
}

/**
 * The generation of a cellular network, as coarsely as an adaptive policy needs it.
 *
 * Three values: the steps at which the throughput a policy should expect differs enough to seed a
 * different default — ADR-0009 rule 9's per-generation table, with its `// ref:` per value, is
 * where those numbers and their sources live — and no finer, because the platform's own list is
 * long, changes by release, and mixes radio technologies a policy has no reason to tell apart.
 *
 * ref: https://developer.android.com/reference/android/telephony/TelephonyManager — the network
 * types these collapse.
 */
public enum class CellularGeneration {
    /** 5G New Radio. */
    NR,

    /** 4G LTE, including LTE-Advanced. */
    LTE,

    /** Anything earlier: 3G and 2G technologies, all of which a policy treats as slow. */
    OLDER,
}

/**
 * What the network is believed to deliver, as a policy sees it: a level, how much to trust it, and
 * how fresh it is.
 *
 * The level is [meanBps]; how much to trust it is [spreadBps] and [conservativeBps]. Media3's own
 * meter holds a distribution and reports one number from it, so a policy given only that number is
 * sized for the network the viewer had *typically*, which is not the network that stalls them.
 * ADR-0009 carries the argument and the reference; a policy that raises a floor on the spread cites
 * the ADR, not the paper.
 *
 * [spreadBps] and [conservativeBps] are null when the meter that produced the estimate does not
 * compute them — Media3's default does not — and a policy treats that as *unknown* rather than as
 * a spread of zero.
 */
public data class ThroughputEstimate(
    /** The estimated throughput, in bits per second. */
    public val meanBps: Long,
    /**
     * How far recent samples scatter around [meanBps], in the same unit, or null if the meter does
     * not measure it.
     */
    public val spreadBps: Long?,
    /**
     * A conservative percentile of recent samples — a throughput the network has delivered most of
     * the time — in bits per second, or null if the meter does not compute one. Which percentile is
     * the meter's to state.
     */
    public val conservativeBps: Long?,
    /** How many samples the estimate rests on. Zero means a seeded default rather than a reading. */
    public val sampleCount: Int,
    /** Milliseconds since the newest sample was taken, on the monotonic clock. */
    public val newestSampleAgeMs: Long,
) {
    init {
        require(meanBps >= 0) { "meanBps must not be negative, was $meanBps" }
        require(spreadBps == null || spreadBps >= 0) { "spreadBps must not be negative, was $spreadBps" }
        require(conservativeBps == null || conservativeBps >= 0) {
            "conservativeBps must not be negative, was $conservativeBps"
        }
        require(sampleCount >= 0) { "sampleCount must not be negative, was $sampleCount" }
        require(newestSampleAgeMs >= 0) {
            "newestSampleAgeMs must not be negative, was $newestSampleAgeMs"
        }
    }
}

/**
 * What this session has already suffered: how many rebuffers, how long since the last one ended,
 * and how long it lasted.
 *
 * A rebuffer here is the engine stalling for data after playback had started, and it ends when
 * playback resumes. A stall that ends in an error or a stop is not a rebuffer that *ended* and is
 * not counted, and a stall a seek caused is not a rebuffer at all — the viewer asked for that wait,
 * and a policy that raised its floor after every scrub would be punishing the scrub. That is the
 * same line `docs/telemetry-schema.md` draws for the rebuffer ratio, drawn here in core so the two
 * agree.
 *
 * Reset at every session: the history is *this* content's.
 */
public data class StallHistory(
    /** Rebuffers that have ended in this session. */
    public val rebufferCount: Int,
    /**
     * Milliseconds since the last rebuffer ended, on the monotonic clock, or null if none has.
     * A policy's cooldown is measured against this.
     */
    public val msSinceLastRebufferEnded: Long?,
    /** How long the last rebuffer lasted, in milliseconds, or null if none has ended. */
    public val lastRebufferDurationMs: Long?,
) {
    init {
        require(rebufferCount >= 0) { "rebufferCount must not be negative, was $rebufferCount" }
        require((rebufferCount == 0) == (msSinceLastRebufferEnded == null)) {
            "msSinceLastRebufferEnded is observed exactly when a rebuffer has ended"
        }
        require((rebufferCount == 0) == (lastRebufferDurationMs == null)) {
            "lastRebufferDurationMs is observed exactly when a rebuffer has ended"
        }
    }

    public companion object {
        /** No rebuffer has ended in this session — the state at every session's start. */
        public val NONE: StallHistory = StallHistory(
            rebufferCount = 0,
            msSinceLastRebufferEnded = null,
            lastRebufferDurationMs = null,
        )
    }
}

/**
 * What the manifest declared the content to be.
 *
 * Two values, and neither is "short-form": nothing in a manifest says so, which is why that stays a
 * [PlaybackProfile]. Live is a *stream* property that can change under a playing session — an HLS
 * event playlist becomes on-demand when its `EXT-X-ENDLIST` arrives — which is why it is observed
 * rather than declared.
 *
 * spec: RFC 8216 §4.3.3.4 — `EXT-X-ENDLIST`, the moment a live playlist becomes on-demand.
 */
public enum class StreamType {
    /** Content with a fixed duration, playable from any position. */
    ON_DEMAND,

    /** Content whose window advances on time, played near its live edge. */
    LIVE,
}
