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

import com.superplayer.abr.OracleBandwidthMeter.Companion.isStable
import com.superplayer.core.BufferPolicy
import com.superplayer.core.LiveLatencyPolicy
import com.superplayer.core.NetworkTransport
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PreloadDepth
import com.superplayer.core.PreloadPolicy
import com.superplayer.core.StallHistory
import com.superplayer.core.StreamType
import com.superplayer.core.ThroughputEstimate
import com.superplayer.core.TrackSelectionPolicy
import kotlin.math.ceil
import kotlin.math.min

/**
 * The buffer half of a [PlaybackDecision], recomputed from the conditions instead of fixed at
 * construction: `PRD.md` §3.1's five policy branches, each a rule a test can name.
 *
 * A pure function of its [PlaybackConditions] — no player, no clock, no state between calls — which
 * is what lets every branch below be tested by constructing conditions by hand
 * (`AdaptiveBufferPolicyTest`) and is what ADR-0009 rule 4 requires of a policy that is consulted
 * again on triggers: hysteresis is read from [StallHistory] rather than remembered here.
 *
 * ## Where it starts from
 *
 * From the profile's own static numbers, `PlaybackPolicy.forProfile(profile)`, and every branch is
 * a *departure* from them under a named observation. With nothing observed — the first seconds of a
 * cold session, or a player whose engine cannot re-target and therefore consults with empty
 * conditions — the answer is exactly the static profile's, so a consumer switching to this policy
 * never gets a worse cold start than before. A seeded throughput estimate (`sampleCount == 0`) is
 * not an observation either: the cold default exists to choose a first rendition, not to size a
 * buffer.
 *
 * ## The five branches
 *
 * 1. **Stable and high throughput → a deep cushion.** On-demand content, on an unmetered
 *    transport, on a network whose spread says the mean can be trusted and whose mean is high: the
 *    buffer the player keeps ahead is raised to [DEEP_CUSHION_MS], so a brief dip is coasted through
 *    rather than reacted to. Stable is [OracleBandwidthMeter.STABLE_SPREAD_FRACTION]'s line, the
 *    oracle's own, not a mean.
 * 2. **High variance → raise the playback floor, not the ceiling.** The two start floors scale with
 *    the coefficient of variation; `maxBufferMs` does not move. Variance, not mean, predicts the
 *    stall (ADR-0009's context section carries the argument and the reference), and a floor is
 *    what a stall is measured against.
 * 3. **Live → latency-priority mode.** Keyed on the *observed* stream type and never on the
 *    profile (ADR-0009 rule 1): the buffer is the live profile's own, sized to survive a segment
 *    fetch rather than to coast, and the decision carries a [LiveLatencyPolicy] so the engine holds
 *    the window by micro-speed correction instead of by seeking.
 * 4. **After a rebuffer → hysteresis.** For [REBUFFER_COOLDOWN_MS] after a rebuffer ends, the
 *    after-rebuffer floor is raised in proportion to how many this session has suffered. The
 *    other half of the same hysteresis — the quality ceiling held at what the network delivered
 *    *most of the time* — is [AdaptiveSelectionPolicy]'s, on the same cooldown, so the two lapse
 *    together. Both lapse at the first consultation after the cooldown, which is the next trigger
 *    rather than the moment itself, because a policy is consulted on triggers and never on a
 *    timer (ADR-0009 rule 4). That is the right shape and not only the permitted one: a network
 *    that moves after the stall is a trigger, and a network that stays exactly as it was is one
 *    the held ceiling already describes — the percentile *is* what it delivers — so nothing is
 *    lost by holding it until something changes.
 * 5. **A memory-aware ceiling on every branch.** Media3 buffers media on the Java heap, so
 *    `maxBufferMs` is a heap commitment; a quarter of the app's heap budget, at the rate the buffer
 *    can actually fill, is the most it may commit, whatever the branch above asked for. That rate
 *    is bounded by the selection ceiling in force, which only the composed [AdaptivePolicy]
 *    knows; alone, this policy is consulted once with nothing observed (ADR-0009 rule 5), when
 *    the ceiling in force is the profile's own and the two answers agree.
 *
 * Playback speed scales the whole target: a buffer expressed in media time drains faster than real
 * time at 2×, so every duration is multiplied by the speed when it is above real time.
 *
 * Every constant carries its reason where it is chosen, as `StaticProfilePolicy`'s do, and not one
 * of them lives in the load control that applies the result (`AdaptiveLoadControl`; ADR-0005
 * rule 2).
 */
public class AdaptiveBufferPolicy(public val profile: PlaybackProfile) : PlaybackPolicy {

    private val staticProfile: PlaybackPolicy = PlaybackPolicy.forProfile(profile)

    /** Branch 3's buffer: the live profile's own, whatever profile the consumer named. */
    private val latencyPriority: PlaybackPolicy = PlaybackPolicy.forProfile(PlaybackProfile.LIVE_LINEAR)

    override fun decide(conditions: PlaybackConditions): PlaybackDecision = decide(conditions, selectionInForce = null)

    /**
     * The decision, with branch 5's fill rate bounded by [selectionInForce] — the ceiling the
     * selector will actually honour — rather than by the profile's static cap. `AdaptivePolicy`
     * composes this policy with [AdaptiveSelectionPolicy] and passes that half in, because the
     * transport's cap and the post-rebuffer hold can both sit under the profile's, and a fill rate
     * taken from the higher cap sizes the ceiling tighter in seconds than the heap warrants. Alone,
     * with nothing in force but the profile, it is the profile's cap.
     */
    internal fun decide(conditions: PlaybackConditions, selectionInForce: TrackSelectionPolicy?): PlaybackDecision {
        val base = staticProfile.decide(conditions)
        val measured = conditions.throughput?.takeIf { it.sampleCount > 0 }
        val live = conditions.streamType == StreamType.LIVE

        var targets: Targets
        val liveLatency: LiveLatencyPolicy?
        if (live) {
            targets = Targets.of(latencyPriority.decide(conditions).buffer)
            liveLatency = LIVE_LATENCY
        } else {
            targets = Targets.of(base.buffer)
            liveLatency = null
            if (measured != null && measured.spreadBps != null) {
                if (deepCushionApplies(conditions.transport, measured)) targets = targets.deepened()
                if (!measured.isStable()) targets = targets.withFloorsRaisedBy(varianceFactor(measured))
            }
        }

        conditions.playbackSpeed?.takeIf { it > 1f }?.let { targets = targets.scaledBy(it) }

        if (conditions.stallHistory.inRebufferCooldown()) {
            targets = targets.withAfterRebufferFloorRaisedFor(conditions.stallHistory.rebufferCount)
        }

        conditions.heapBudgetBytes?.let { heap ->
            targets = targets.cappedTo(
                ceilingMs = heapCeilingMs(heap, measured, selectionInForce ?: base.trackSelection),
                notBelowMs = base.buffer.bufferForPlaybackAfterRebufferMs,
            )
        }

        // The selection half is the static profile's: it is `AdaptiveSelectionPolicy`'s to move,
        // and `AdaptivePolicy` composes the two so that each owns one half.
        //
        // The retry half is the static profile's too, and unmoved: ADR-0011 rule 11 says an adaptive
        // policy may vary a budget — a stall history argues for a shorter one — but says nothing
        // about what it should become, and a number invented here would be one with no argument
        // behind it. Carried through rather than dropped, so a player built on this policy asks for
        // its profile's budgets rather than for Media3's.
        //
        // The download half is carried the same way, for a reason of its own: a download store consults
        // its profile's static policy once, at enqueue (ADR-0013 rule 12), and a decision that dropped the
        // half would say something about downloads that no download is chosen under.
        return PlaybackDecision(
            targets.toBufferPolicy(),
            base.trackSelection,
            liveLatency,
            preloadOn(conditions.transport, base.preload),
            base.retry,
            base.download,
        )
    }

    /**
     * The prefetch half (ADR-0010 rule 10): the profile's own, less its media on a metered link.
     *
     * A loaded range is media fetched for a row the viewer may never reach, and on cellular that is
     * data they pay for — branch 1's trade, and F1's, in `PRD.md` §3.1. A prepared source keeps the
     * reach and the manifest round trips it saves, and spends kilobytes. A warmed decoder goes the same
     * way, because it is held on a loaded range. An unknown transport keeps the profile's depth,
     * because what is not observed refuses nothing (ADR-0009 rule 1).
     */
    private fun preloadOn(transport: NetworkTransport?, static: PreloadPolicy): PreloadPolicy =
        if (transport is NetworkTransport.Cellular && (static.depth is PreloadDepth.Loaded || static.depth is PreloadDepth.DecoderWarmed)) {
            static.copy(depth = PreloadDepth.SourcePrepared)
        } else {
            static
        }

    /**
     * Branch 1's test. On-demand is the profile's intent (the stream type is checked by the
     * caller); the transport must be one the bytes are not metered on, because a deep cushion is
     * media that may never be watched and on cellular that is data the viewer pays for — F1's
     * explicit trade, in `PRD.md` §3.1; and the network must be both stable and fast.
     */
    private fun deepCushionApplies(transport: NetworkTransport?, measured: ThroughputEstimate): Boolean =
        profile == PlaybackProfile.VIDEO_ON_DEMAND &&
            (transport == NetworkTransport.Wifi || transport == NetworkTransport.Ethernet) &&
            measured.isStable() &&
            measured.meanBps >= HIGH_THROUGHPUT_BPS

    /**
     * Branch 2's factor: one plus the coefficient of variation, capped at [MAX_VARIANCE_FACTOR].
     *
     * The floor is how much media is in hand before playback starts, and what it is insuring
     * against is the next segment arriving later than the mean predicts — by about one spread, on
     * an ordinary network. Scaling the floor by `spread / mean` buys exactly that: a network whose
     * spread is half its mean gets a floor half again as deep.
     */
    private fun varianceFactor(measured: ThroughputEstimate): Double {
        val spread = checkNotNull(measured.spreadBps)
        if (measured.meanBps <= 0) return MAX_VARIANCE_FACTOR
        return min(1.0 + spread.toDouble() / measured.meanBps, MAX_VARIANCE_FACTOR)
    }

    /**
     * Branch 5's ceiling in milliseconds: the bytes a quarter of the heap holds, at the rate the
     * buffer can actually fill.
     *
     * The rate is the smaller of what the network delivers and the ceiling the selector is held
     * under — media cannot arrive faster than the link, and the selector cannot pick above the
     * ceiling — and, before either is known, [REFERENCE_BITRATE_BPS]. The ceiling is the one in
     * force where the composed policy knows it, so a transport cap or a post-rebuffer hold lowers
     * the rate as a slow link does. A slow link therefore gets a *higher* ceiling in seconds,
     * which is right: seconds are cheap when they are small.
     */
    private fun heapCeilingMs(
        heapBudgetBytes: Long,
        measured: ThroughputEstimate?,
        trackSelection: TrackSelectionPolicy,
    ): Int {
        val cap = trackSelection.maxVideoBitrateBps
            .takeIf { it != TrackSelectionPolicy.UNLIMITED }?.toLong() ?: Long.MAX_VALUE
        val delivered = measured?.meanBps?.takeIf { it > 0 } ?: REFERENCE_BITRATE_BPS
        val fillRateBps = min(delivered, cap)
        val bufferBytes = heapBudgetBytes / HEAP_SHARE_DIVISOR
        val ceilingMs = bufferBytes * BITS_PER_BYTE * MILLIS_PER_SECOND / fillRateBps
        val stepped = ceilingMs / CEILING_STEP_MS * CEILING_STEP_MS
        return stepped.coerceIn(CEILING_STEP_MS, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * The four durations while the branches move them, before [BufferPolicy]'s invariants are
     * restored by [toBufferPolicy]: a raised floor may pass the range's minimum on the way, and a
     * memory ceiling may pass under it.
     */
    private data class Targets(
        val minBufferMs: Long,
        val maxBufferMs: Long,
        val bufferForPlaybackMs: Long,
        val bufferForPlaybackAfterRebufferMs: Long,
        val backBufferMs: Int,
        val retainBackBufferFromKeyframe: Boolean,
    ) {
        /** Branch 1: the cushion lifted to [DEEP_CUSHION_MS], the range above it kept as wide as it was. */
        fun deepened(): Targets {
            if (minBufferMs >= DEEP_CUSHION_MS) return this
            return copy(minBufferMs = DEEP_CUSHION_MS, maxBufferMs = DEEP_CUSHION_MS + (maxBufferMs - minBufferMs))
        }

        /** Branch 2: both floors scaled, rounded up to [FLOOR_STEP_MS]; the ceiling untouched. */
        fun withFloorsRaisedBy(factor: Double): Targets = copy(
            bufferForPlaybackMs = roundUp(bufferForPlaybackMs * factor, FLOOR_STEP_MS),
            bufferForPlaybackAfterRebufferMs = roundUp(bufferForPlaybackAfterRebufferMs * factor, FLOOR_STEP_MS),
        )

        /** Every duration multiplied by a playback speed above real time. */
        fun scaledBy(speed: Float): Targets = copy(
            minBufferMs = roundUp(minBufferMs * speed.toDouble(), FLOOR_STEP_MS),
            maxBufferMs = roundUp(maxBufferMs * speed.toDouble(), FLOOR_STEP_MS),
            bufferForPlaybackMs = roundUp(bufferForPlaybackMs * speed.toDouble(), FLOOR_STEP_MS),
            bufferForPlaybackAfterRebufferMs = roundUp(bufferForPlaybackAfterRebufferMs * speed.toDouble(), FLOOR_STEP_MS),
        )

        /**
         * Branch 4: the after-rebuffer floor multiplied by one more than the session's rebuffer
         * count. Each rebuffer is evidence the floor in force was not enough, and the doubling the
         * static profiles already apply is the first step of the same series. Bounded by the
         * range's minimum when the invariants are restored.
         */
        fun withAfterRebufferFloorRaisedFor(rebufferCount: Int): Targets =
            copy(bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs * (1L + rebufferCount))

        /**
         * Branch 5: the ceiling brought down to [ceilingMs] where it is above it, but never below
         * [notBelowMs] — a device that cannot hold what a resume needs has a different problem than
         * buffer depth, and a ceiling under the floors would make playback impossible rather than
         * frugal.
         */
        fun cappedTo(ceilingMs: Int, notBelowMs: Int): Targets {
            val ceiling = maxOf(ceilingMs, notBelowMs).toLong()
            if (maxBufferMs <= ceiling) return this
            return copy(maxBufferMs = ceiling, minBufferMs = min(minBufferMs, ceiling))
        }

        fun toBufferPolicy(): BufferPolicy {
            val max = maxBufferMs.coerceIn(1L, Int.MAX_VALUE.toLong())
            val min = minBufferMs.coerceIn(1L, max)
            return BufferPolicy(
                minBufferMs = min.toInt(),
                maxBufferMs = max.toInt(),
                bufferForPlaybackMs = bufferForPlaybackMs.coerceIn(1L, min).toInt(),
                bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs.coerceIn(1L, min).toInt(),
                backBufferMs = backBufferMs,
                retainBackBufferFromKeyframe = retainBackBufferFromKeyframe,
            )
        }

        companion object {
            fun of(policy: BufferPolicy): Targets = Targets(
                minBufferMs = policy.minBufferMs.toLong(),
                maxBufferMs = policy.maxBufferMs.toLong(),
                bufferForPlaybackMs = policy.bufferForPlaybackMs.toLong(),
                bufferForPlaybackAfterRebufferMs = policy.bufferForPlaybackAfterRebufferMs.toLong(),
                backBufferMs = policy.backBufferMs,
                retainBackBufferFromKeyframe = policy.retainBackBufferFromKeyframe,
            )

            private fun roundUp(value: Double, stepMs: Long): Long = ceil(value / stepMs).toLong() * stepMs
        }
    }

    public companion object {
        /**
         * Branch 1's cushion: 50 s kept ahead of the playhead.
         *
         * ref: derivation (CONTRIBUTING.md rule 4), from the oracle's own window. The estimate rests
         * on [SampleWindow.WINDOW_SAMPLES] samples, one per media segment, and a dip that lasts
         * longer than the window is no longer a dip: the estimate itself has moved and the policy
         * is re-consulted. Eight segments at the 6 s the HLS authoring specification recommends is
         * 48 s, so a cushion of 50 s is one the estimate can be wrong for the whole of its own
         * memory without the viewer seeing it — which is also `PRD.md` §3.1's number. The static
         * profile's 30 s stays the floor everywhere this branch does not apply.
         * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
         */
        public const val DEEP_CUSHION_MS: Long = 50_000L

        /**
         * Branch 1's other condition: a mean of at least 10 Mbit/s.
         *
         * ref: Apple, *HLS Authoring Specification for Apple Devices*, video encoding requirements —
         * the highest 1080p tier it recommends is under 8 Mbit/s. At 10 Mbit/s the deepest cushion
         * fills faster than the top of a 1080p ladder drains it, with a margin, so filling 50 s
         * costs the viewer nothing they would notice; below it, buffering that far ahead competes
         * with the rendition the selector could otherwise afford.
         * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
         */
        public const val HIGH_THROUGHPUT_BPS: Long = 10_000_000L

        /**
         * Branch 2's cap: the floors are raised by at most a factor of two.
         *
         * ref: derivation (CONTRIBUTING.md rule 4). A spread larger than the mean describes a
         * network with no dependable level at all, and a floor deeper than twice the profile's is
         * no longer a start floor but a cushion, which is branch 1's instrument and the wrong one
         * for this: a viewer waiting longer to start on a network that may not deliver is a viewer
         * who leaves.
         */
        public const val MAX_VARIANCE_FACTOR: Double = 2.0

        /**
         * The granularity floors and speed-scaled durations are rounded up to: half a second.
         *
         * ref: derivation (CONTRIBUTING.md rule 4). A start delay that differs by less than this
         * is not one a viewer can tell apart, and every distinct answer is a re-targeted load
         * control and a `DecisionChanged` event; rounding keeps a small move in the spread from
         * producing a decision that differs in name only.
         */
        public const val FLOOR_STEP_MS: Long = 500L

        /**
         * Branch 4's cooldown: 30 s after a rebuffer ends.
         *
         * ref: derivation (CONTRIBUTING.md rule 4), from the oracle's window as [DEEP_CUSHION_MS]
         * is. The held ceiling should lapse once the estimate is a reading of the network *after*
         * the stall rather than of the one that produced it, and the window is
         * [SampleWindow.WINDOW_SAMPLES] segments long: at the 2–6 s segment durations in common
         * use that is 16–48 s, and 30 s sits inside it. Shorter, and the selector climbs back on
         * the estimate that was wrong; longer, and a recovered network is held at a ceiling nothing
         * observed justifies.
         */
        public const val REBUFFER_COOLDOWN_MS: Long = 30_000L

        /**
         * Branch 5's share: a quarter of the app's heap budget may be media buffer.
         *
         * ref: derivation (CONTRIBUTING.md rule 4). The heap is the app's, not the player's: the
         * activity that draws the player, its images and its own state share it, and a buffer that
         * took half would leave them fighting the collector, which shows up as jank in the
         * controls over a smooth video. A quarter of a 256 MB heap is 64 MiB, under Media3's own
         * muxed buffer target and about two of the per-player budgets `DeviceCapacity.kt` sizes a
         * pool with; a quarter of a 96 MB heap is 24 MiB. It is a judgment, not a measurement, and
         * the benchmark's memory cell is where it would be revised.
         */
        public const val HEAP_SHARE_DIVISOR: Long = 4L

        /**
         * The fill rate assumed for the memory ceiling before anything is measured: 8 Mbit/s.
         *
         * ref: Apple, *HLS Authoring Specification for Apple Devices*, video encoding requirements
         * — the top of the recommended 1080p ladder, rounded up. The highest rate a phone-sized
         * screen is likely to be served; a 4K ladder would fill faster, and a device playing one
         * has a heap to match.
         * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
         */
        public const val REFERENCE_BITRATE_BPS: Long = 8_000_000L

        /**
         * The granularity of the memory ceiling: a whole second. Coarser than [FLOOR_STEP_MS]
         * because the ceiling moves with the measured mean, and a ceiling that moved by
         * milliseconds on every material move would be a decision change per move.
         */
        public const val CEILING_STEP_MS: Long = 1_000L

        /**
         * Branch 3's speed range: 0.97× to 1.03×.
         *
         * `PRD.md` §3.1's range, and Media3's own fallback — stated here as a policy output so that
         * it is decided where the buffer is rather than defaulted by the engine, and so that a
         * profile that wanted a wider one would change one constant with a reason beside it. Three
         * percent is the drift at which pitch correction stays inaudible and motion stays
         * unnoticed. ref: https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLivePlaybackSpeedControl
         */
        public val LIVE_LATENCY: LiveLatencyPolicy = LiveLatencyPolicy(minPlaybackSpeed = 0.97f, maxPlaybackSpeed = 1.03f)

        private const val BITS_PER_BYTE: Long = 8L
        private const val MILLIS_PER_SECOND: Long = 1_000L
    }
}

/**
 * Whether a rebuffer ended less than [AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS] ago.
 *
 * The one test of the cooldown, read by branch 4's raised floor here and by
 * [AdaptiveSelectionPolicy]'s held ceiling: one function rather than two, so the floor and the
 * hold cannot lapse at different moments.
 */
internal fun StallHistory.inRebufferCooldown(): Boolean {
    val since = msSinceLastRebufferEnded ?: return false
    return since < AdaptiveBufferPolicy.REBUFFER_COOLDOWN_MS
}
