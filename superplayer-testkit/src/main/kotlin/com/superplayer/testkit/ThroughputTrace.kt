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

/**
 * What kind of link a stretch of a [ThroughputTrace] was measured over.
 *
 * Separate from the bandwidth on purpose. A WiFi-to-cellular handover is not only a bandwidth
 * change: the device's default network changes, every connection bound to the old one is gone, and
 * phase 3's `BandwidthOracle` reseeds its estimate on exactly that event — so a trace that could only
 * say "the rate fell" could not test it. Nothing reads the transport yet. It is in the format now
 * because retrofitting a column means rewriting every trace already converted.
 *
 * The values are the transports Android itself reports for a network
 * (// ref: Android `NetworkCapabilities.TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET`),
 * so what a trace says is something the device could actually have observed.
 */
public enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,

    /** The source did not say. What a converted trace carries when its dataset records no transport. */
    UNKNOWN,
}

/**
 * A time series of network conditions — bandwidth, round-trip time and transport — that a player's
 * transfers can be replayed against.
 *
 * Piecewise constant: a list of stretches, each lasting some milliseconds at one bandwidth, one
 * round-trip time and one transport. That is the shape every public throughput dataset already has
 * (a sample per interval) and the shape a synthetic profile is easiest to read in (a few long
 * stretches), so one type carries both. `docs/throughput-traces.md` is the format's specification,
 * and [parse] and [format] are its reader and writer.
 *
 * ```kotlin
 * val player = harness.buildPlayer(
 *     content = TestContent.videoLadder(),
 *     network = NetworkProfile.LTE_WITH_DROPOUTS.trace,
 *     faults = FaultScript.Builder().failWithHttpStatus(403, ResourceKind.MEDIA_SEGMENT, 5).build(),
 * )
 * ```
 *
 * **What happens after the last stretch** is part of the trace rather than of the replay: a recorded
 * trace usually *loops*, which is what every trace-driven ABR evaluation does with a trace shorter
 * than the session (// ref: Netravali et al., "Mahimahi: Accurate Record-and-Replay for HTTP",
 * USENIX ATC 2015 — `mm-link` wraps to the start of its trace), while a handover *holds*: a device
 * that moved to cellular does not return to WiFi because the trace ran out.
 *
 * Time zero is the moment [PlaybackHarness] built the player, on the harness's clock.
 */
public class ThroughputTrace private constructor(
    private val stretches: List<Stretch>,
    /** Whether the trace starts again after its last stretch, rather than holding that stretch. */
    public val loops: Boolean,
) {

    /** One pass through the trace, in milliseconds. */
    public val durationMs: Long = stretches.last().endMs

    /**
     * The bits one pass can deliver, times a thousand — the unit [arrivalMs] counts in.
     *
     * Checked here, once, for every stretch and for the sum: every product [arrivalMs] forms is at
     * most one of these, so a trace that constructs cannot overflow the arithmetic that replays it.
     */
    private val passCapacity: Long = stretches.fold(0L) { total, stretch ->
        Math.addExact(total, Math.multiplyExact(stretch.bandwidthBps, stretch.durationMs))
    }

    /** The bandwidth available at [timeMs] into the trace, in bits per second. */
    public fun bandwidthBpsAt(timeMs: Long): Long = stretchAt(timeMs).bandwidthBps

    /** The round-trip time at [timeMs] into the trace — what a request opened then waits before its first byte. */
    public fun rttMsAt(timeMs: Long): Long = stretchAt(timeMs).rttMs

    /** The transport the device is on at [timeMs] into the trace. */
    public fun transportAt(timeMs: Long): NetworkTransport = stretchAt(timeMs).transport

    /** The trace in the text format [parse] reads, so a built trace can be written out and diffed. */
    public fun format(): String = buildString {
        appendLine(FORMAT_HEADER)
        appendLine("$AT_END_DIRECTIVE ${if (loops) AT_END_LOOP else AT_END_HOLD}")
        stretches.forEach { stretch ->
            append("${stretch.durationMs} ${stretch.bandwidthBps} ${stretch.transport}")
            if (stretch.rttMs != 0L) append(" ${stretch.rttMs}")
            appendLine()
        }
    }

    /**
     * The earliest time into the trace by which [bytes] have arrived, for a transfer whose first byte
     * could arrive at [startMs].
     *
     * Integer arithmetic throughout, in bits times milliseconds per second: a stretch of `d` ms at
     * `b` bit/s carries `b × d` of them, and a byte needs 8 000. Nothing is rounded until the one
     * division that finds the millisecond the last byte lands in, which is rounded up — a byte has
     * not arrived until all of it has — so the answer is the same on every machine and every run.
     * A stretch at zero bandwidth carries nothing, which is what makes a dropout hold a transfer
     * until the link returns rather than fail it.
     */
    internal fun arrivalMs(startMs: Long, bytes: Long): Long {
        require(startMs >= 0) { "A transfer cannot start before the trace does, at $startMs ms" }
        require(bytes >= 0) { "A transfer cannot deliver $bytes bytes" }
        var needed = Math.multiplyExact(bytes, BIT_MILLIS_PER_BYTE)
        var now = startMs
        while (needed > 0) {
            if (!loops && now >= durationMs) return now + ceilDiv(needed, stretches.last().bandwidthBps)
            val stretch = stretchAt(now)
            val left = stretch.endMs - offsetInPass(now)
            val capacity = stretch.bandwidthBps * left
            if (capacity >= needed) return now + ceilDiv(needed, stretch.bandwidthBps)
            needed -= capacity
            now += left
            // Whole passes at a time once aligned to the start of one, so a transfer that outlasts a
            // short trace many times over costs a division rather than a walk. One pass is left to
            // the walk, because the last byte lands somewhere inside it.
            if (loops && offsetInPass(now) == 0L && needed > passCapacity) {
                val passes = (needed - 1) / passCapacity
                needed -= passes * passCapacity
                now += passes * durationMs
            }
        }
        return now
    }

    private fun offsetInPass(timeMs: Long): Long = if (loops) timeMs % durationMs else minOf(timeMs, durationMs - 1)

    private fun stretchAt(timeMs: Long): Stretch {
        require(timeMs >= 0) { "The trace starts at 0 ms, not $timeMs" }
        val offset = offsetInPass(timeMs)
        var low = 0
        var high = stretches.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (stretches[mid].startMs <= offset) low = mid else high = mid - 1
        }
        return stretches[low]
    }

    override fun equals(other: Any?): Boolean =
        other is ThroughputTrace && other.loops == loops && other.stretches == stretches

    override fun hashCode(): Int = 31 * stretches.hashCode() + loops.hashCode()

    override fun toString(): String = "ThroughputTrace(${stretches.size} stretches, $durationMs ms, loops=$loops)"

    /** Collects a trace one stretch at a time, in playback order. */
    public class Builder {

        private val stretches = mutableListOf<Stretch>()
        private var loops = true

        /**
         * Appends [durationMs] at [bandwidthBps] over [transport], with requests opened in it paying
         * [rttMs] before their first byte.
         *
         * [transport] has no default on purpose: the one thing a trace author must not be allowed to
         * forget is what kind of link they are describing — see [NetworkTransport].
         */
        @JvmOverloads
        public fun add(
            durationMs: Long,
            bandwidthBps: Long,
            transport: NetworkTransport,
            rttMs: Long = 0,
        ): Builder {
            require(durationMs > 0) { "A stretch lasts at least 1 ms, not $durationMs" }
            require(bandwidthBps in 0..MAX_BANDWIDTH_BPS) {
                "A bandwidth is between 0 and $MAX_BANDWIDTH_BPS bit/s, not $bandwidthBps"
            }
            require(rttMs >= 0) { "A round-trip time is at least 0 ms, not $rttMs" }
            val startMs = stretches.lastOrNull()?.endMs ?: 0
            stretches += Stretch(startMs, durationMs, bandwidthBps, rttMs, transport)
            return this
        }

        /** Holds the last stretch forever instead of starting the trace again. See the class KDoc. */
        public fun holdAtEnd(): Builder {
            loops = false
            return this
        }

        public fun build(): ThroughputTrace {
            require(stretches.isNotEmpty()) { "A trace has at least one stretch" }
            // Both are the same promise — every byte eventually arrives — broken in the two ways
            // each ending can break it. A replay that could wait forever would hang a test rather
            // than fail it.
            if (loops) {
                require(stretches.any { it.bandwidthBps > 0 }) {
                    "A looping trace with no bandwidth anywhere delivers nothing, ever"
                }
            } else {
                require(stretches.last().bandwidthBps > 0) {
                    "A trace that holds its last stretch needs bandwidth in it, or nothing after the end arrives"
                }
            }
            return try {
                ThroughputTrace(stretches.toList(), loops)
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("A trace this long at these rates overflows the replay's arithmetic", e)
            }
        }
    }

    public companion object {

        /** The first line of every trace: the format's name and its version. */
        public const val FORMAT_HEADER: String = "superplayer-throughput-trace 1"

        /** 100 Gbit/s: far past any link a player meets. Overflow is guarded separately, at construction. */
        private const val MAX_BANDWIDTH_BPS = 100_000_000_000L

        private const val BIT_MILLIS_PER_BYTE = 8L * 1_000L
        private const val AT_END_DIRECTIVE = "at-end"
        private const val AT_END_LOOP = "loop"
        private const val AT_END_HOLD = "hold"

        /**
         * Reads a trace in the format `docs/throughput-traces.md` specifies.
         *
         * Strict, because a trace is a test input: a malformed line is a failure naming its line
         * number, never a line skipped — a reader that tolerated one would replay a different
         * network from the one the file describes, and nothing would say so.
         */
        @JvmStatic
        public fun parse(text: String): ThroughputTrace {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == FORMAT_HEADER) {
                "Line 1: a trace starts with \"$FORMAT_HEADER\", not \"${lines.firstOrNull().orEmpty()}\""
            }
            val builder = Builder()
            var sawStretch = false
            var sawAtEnd = false
            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (index == 0 || line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val fields = line.split(WHITESPACE)
                fun fail(why: String): Nothing = throw IllegalArgumentException("Line ${index + 1}: $why: \"$raw\"")
                if (fields[0] == AT_END_DIRECTIVE) {
                    if (sawStretch || sawAtEnd) fail("\"$AT_END_DIRECTIVE\" appears once, before the first stretch")
                    sawAtEnd = true
                    when (fields.getOrNull(1)) {
                        AT_END_LOOP -> Unit
                        AT_END_HOLD -> builder.holdAtEnd()
                        else -> fail("\"$AT_END_DIRECTIVE\" is followed by \"$AT_END_LOOP\" or \"$AT_END_HOLD\"")
                    }
                    if (fields.size != 2) fail("\"$AT_END_DIRECTIVE\" takes one value")
                    return@forEachIndexed
                }
                if (fields.size !in 3..4) fail("a stretch is duration_ms bandwidth_bps transport [rtt_ms]")
                val durationMs = fields[0].toLongOrNull() ?: fail("duration_ms is not a whole number")
                val bandwidthBps = fields[1].toLongOrNull() ?: fail("bandwidth_bps is not a whole number")
                val transport = NetworkTransport.entries.firstOrNull { it.name == fields[2] }
                    ?: fail("transport is one of ${NetworkTransport.entries.joinToString()}")
                val rttMs = fields.getOrNull(3)?.let { it.toLongOrNull() ?: fail("rtt_ms is not a whole number") } ?: 0
                try {
                    builder.add(durationMs, bandwidthBps, transport, rttMs)
                } catch (e: IllegalArgumentException) {
                    fail(e.message.orEmpty())
                }
                sawStretch = true
            }
            return builder.build()
        }

        private val WHITESPACE = Regex("""\s+""")

        private fun ceilDiv(numerator: Long, denominator: Long): Long = (numerator + denominator - 1) / denominator
    }
}

/** One stretch of a [ThroughputTrace]: [durationMs] from [startMs] at one set of conditions. */
internal data class Stretch(
    val startMs: Long,
    val durationMs: Long,
    val bandwidthBps: Long,
    val rttMs: Long,
    val transport: NetworkTransport,
) {
    val endMs: Long get() = startMs + durationMs
}
