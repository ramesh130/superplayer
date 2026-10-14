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

import android.os.Handler
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.superplayer.core.NetworkTransport
import com.superplayer.core.ThroughputEstimate
import com.superplayer.core.ThroughputSource
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

/**
 * The Media3 half of [BandwidthOracle]: the `BandwidthMeter` the engine is built with, the
 * `TransferListener` it hands the media source, and core's [ThroughputSource] reading seam, as one
 * object. Internal, because every Media3 type here is `@UnstableApi` (ADR-0001 rule 2).
 *
 * ## Every transfer, once
 *
 * The listener is the one the engine hands the media source at prepare time and each `DataSource`
 * propagates down the chain, so a byte is reported where it actually moved and exactly once —
 * `TransferChain`'s KDoc says why a layer that re-raised the callbacks could report a byte count the
 * transfer never made. This class raises nothing: it counts what it is told.
 *
 * ## What is not a sample
 *
 * A transfer whose source reports `isNetwork = false` contributes nothing — a read served from a
 * cache is not a throughput sample, and the flag rather than the chain position is what says so
 * (`TransferChain`, *Chain position alone does not keep cache hits out of the estimate*; ADR-0009
 * rule 2 defers the observation to Phase 4 because this is already the answer). Nor does one whose
 * `DataSpec` says it might not use the full network speed — a background prefetch Media3 throttles
 * on purpose — for the same reason `DefaultBandwidthMeter` drops it: the transfer measured the
 * throttle, not the link.
 *
 * ## How a sample is taken
 *
 * Bytes are accumulated across every counted transfer open at once and divided by the time at least
 * one was open, which is how Media3's own meter treats concurrent transfers and is the right shape
 * for a shared link: two segments fetched together each see half the link, and the sum over the
 * union of their time is what the link delivered. ref: `DefaultBandwidthMeter` — the same
 * accumulation, which is also what the harness's shaper pacing means a byte-for-byte replay agrees
 * with. A sample is taken when a counted transfer ends and the accumulation has reached
 * [MIN_SAMPLE_BYTES] over at least [MIN_SAMPLE_ELAPSED_MS]; below either, the bytes and the active
 * time roll into the next sample rather than being lost, so a playlist fetch never forms a sample
 * of its own and never disappears either. Idle time between transfers is not counted.
 *
 * A transport change discards the accumulation in flight: what a straddling transfer moved before
 * the handover measured neither network and is dropped, and what it moves after is the new
 * transport's, timed from the change.
 *
 * ## When a policy hears about it
 *
 * `onEstimateMovedMaterially` fires when the mean has moved by [MATERIAL_MOVE_FRACTION] of the
 * last estimate announced, when the spread has crossed the [STABLE_SPREAD_FRACTION] line in either
 * direction, and on a transport change — and not once per sample, which is the oscillation ADR-0009
 * rule 4 keeps out of the policy by putting the threshold here.
 */
internal class OracleBandwidthMeter(
    private val clock: Clock,
    private val memory: EstimateMemory,
    initialTransport: NetworkTransport,
) : BandwidthMeter,
    TransferListener,
    ThroughputSource {

    private val lock = Any()

    // Guarded by lock.
    private var transport: NetworkTransport = initialTransport
    private var openCountedTransfers = 0
    private var activeSinceMs = 0L
    private var pendingElapsedMs = 0L
    private var pendingBytes = 0L
    private var lastAnnounced: ThroughputEstimate? = null
    private var transfersObserved = 0
    private var bytesObserved = 0L

    private val engineListeners = BandwidthMeter.EventListener.EventDispatcher()
    private val listeners = CopyOnWriteArraySet<ThroughputSource.Listener>()

    /** The transport samples are being keyed on. */
    val currentTransport: NetworkTransport
        get() = synchronized(lock) { transport }

    /**
     * How many counted transfers have started, and how many bytes they reported. For the one test
     * that holds these against what the origin served: "sampled exactly once" is a claim about
     * bytes and transfers, which no reading of the estimate can stand in for.
     */
    @get:VisibleForTesting
    val countedTransfers: Int
        get() = synchronized(lock) { transfersObserved }

    @get:VisibleForTesting
    val countedBytes: Long
        get() = synchronized(lock) { bytesObserved }

    /** The device moved to [next]: samples are keyed on it from now on, and the estimate is its. */
    fun onTransportChanged(next: NetworkTransport) {
        val announce: ThroughputEstimate
        synchronized(lock) {
            if (next == transport) return
            transport = next
            pendingBytes = 0
            pendingElapsedMs = 0
            activeSinceMs = clock.elapsedRealtime()
            announce = memory.estimate(transport, clock.elapsedRealtime())
            lastAnnounced = announce
        }
        // A reseed is material by definition: the number a policy was deciding on is gone.
        listeners.forEach { it.onEstimateMovedMaterially() }
    }

    // BandwidthMeter

    override fun getBitrateEstimate(): Long = currentEstimate().meanBps

    override fun getTransferListener(): TransferListener = this

    override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) {
        engineListeners.addListener(eventHandler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        engineListeners.removeListener(eventListener)
    }

    // ThroughputSource

    override fun currentEstimate(): ThroughputEstimate =
        synchronized(lock) { memory.estimate(transport, clock.elapsedRealtime()) }

    override fun addListener(listener: ThroughputSource.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: ThroughputSource.Listener) {
        listeners -= listener
    }

    // TransferListener

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isCounted(dataSpec, isNetwork)) return
        synchronized(lock) {
            transfersObserved++
            if (openCountedTransfers == 0) activeSinceMs = clock.elapsedRealtime()
            openCountedTransfers++
        }
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (!isCounted(dataSpec, isNetwork)) return
        synchronized(lock) {
            pendingBytes += bytesTransferred
            bytesObserved += bytesTransferred
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isCounted(dataSpec, isNetwork)) return
        var sample: Sample? = null
        synchronized(lock) {
            // An end with no start on record is a source misreporting; Media3's own meter throws
            // here, on a loader thread, which would end playback over a bookkeeping fault. Tolerated
            // instead: the bytes it reported still count, and the count of open transfers stays
            // at zero.
            if (openCountedTransfers == 0) return
            val nowMs = clock.elapsedRealtime()
            openCountedTransfers--
            val elapsedMs = pendingElapsedMs + (nowMs - activeSinceMs)
            if (pendingBytes >= MIN_SAMPLE_BYTES && elapsedMs >= MIN_SAMPLE_ELAPSED_MS) {
                // Bits per second from bytes over milliseconds: ×8 bits, ×1000 ms/s.
                val bps = pendingBytes * BITS_PER_BYTE * MILLIS_PER_SECOND / elapsedMs
                memory.record(transport, bps, nowMs)
                sample = Sample(elapsedMs, pendingBytes, memory.estimate(transport, nowMs))
                pendingBytes = 0
                pendingElapsedMs = 0
                activeSinceMs = nowMs
            } else if (openCountedTransfers == 0) {
                // Too little to be a sample on its own: the active time so far is kept, and the
                // idle time that follows is not.
                pendingElapsedMs = elapsedMs
            }
        }
        sample?.let { taken ->
            engineListeners.bandwidthSample(taken.elapsedMs.toInt(), taken.bytes, taken.estimate.meanBps)
            announceIfMaterial(taken.estimate)
        }
    }

    private fun isCounted(dataSpec: DataSpec, isNetwork: Boolean): Boolean =
        isNetwork && !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)

    private fun announceIfMaterial(next: ThroughputEstimate) {
        val material = synchronized(lock) {
            val last = lastAnnounced
            val moved = last == null ||
                abs(next.meanBps - last.meanBps) >= last.meanBps * MATERIAL_MOVE_FRACTION ||
                next.isStable() != last.isStable()
            if (moved) lastAnnounced = next
            moved
        }
        if (material) listeners.forEach { it.onEstimateMovedMaterially() }
    }

    private class Sample(val elapsedMs: Long, val bytes: Long, val estimate: ThroughputEstimate)

    companion object {
        private const val BITS_PER_BYTE = 8L
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * The fewest bytes a sample rests on: 16 KiB.
         *
         * ref: derivation (CONTRIBUTING.md rule 4). Larger than any playlist or manifest — a
         * media playlist of a two-hour title at 6-second segments is under 100 KB only in the
         * pathological case and a few KB in the ordinary one, and a multivariant playlist is
         * hundreds of bytes — so a manifest fetch cannot form a sample by itself and a small
         * transfer that was slow for a reason other than the link (a cold TLS session, a redirect)
         * cannot pull the window down. Smaller than any media segment worth the name: two seconds
         * of the lowest audio-only rendition is 32 KB. Media3's meter weights small samples down by
         * `sqrt(bytes)` instead; a threshold is preferred here because it keeps every reading of
         * the window hand-checkable.
         */
        const val MIN_SAMPLE_BYTES: Long = 16 * 1_024L

        /**
         * The shortest active time a sample rests on: 20 ms.
         *
         * ref: derivation (CONTRIBUTING.md rule 4). The clock is millisecond-grained and a
         * transfer's end is rounded up to the next tick, so a sample over `n` ms is low by up to
         * `1/n`; twenty caps that quantisation error at 5%, under the [MATERIAL_MOVE_FRACTION] it
         * would otherwise be able to trip on its own.
         */
        const val MIN_SAMPLE_ELAPSED_MS: Long = 20L

        /**
         * How far the mean moves before a policy is told: a fifth of the last estimate announced.
         *
         * ref: Apple, *HLS Authoring Specification for Apple Devices*, video encoding requirements
         * — adjacent rungs of the recommended ladders differ by well over a fifth in bitrate, and
         * Media3's own selector applies a further 0.7 fraction before it compares an estimate to a
         * rung. A move smaller than a fifth therefore could not change a selection on any published
         * ladder; a move larger than that might, and a policy sizing a floor from the mean should
         * hear about it before the selector acts on it.
         * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
         */
        const val MATERIAL_MOVE_FRACTION: Double = 0.2

        /**
         * What "stable" means numerically: a spread of no more than a quarter of the mean.
         *
         * ref: Yin, Jindal, Sekar and Sinopoli, SIGCOMM 2015, §5.1 — the prediction error is what
         * the adaptation must discount by, and a coefficient of variation is that error expressed
         * against the level. A quarter is where discounting the mean by one spread lands *inside*
         * the gap to the next rung down on a ladder whose rungs differ by a third or more (the
         * Apple reference above), so a network at or under it is one whose mean a selector can act
         * on; above it, the conservative percentile is the number to select on. Crossing the line
         * is announced because a policy that reads a different number on each side of it has to be
         * asked again when the side changes.
         */
        const val STABLE_SPREAD_FRACTION: Double = 0.25

        /** Whether the network this describes is stable in the sense [STABLE_SPREAD_FRACTION] defines. Unknown spread is unstable. */
        fun ThroughputEstimate.isStable(): Boolean {
            val spread = spreadBps ?: return false
            return spread <= meanBps * STABLE_SPREAD_FRACTION
        }
    }
}
