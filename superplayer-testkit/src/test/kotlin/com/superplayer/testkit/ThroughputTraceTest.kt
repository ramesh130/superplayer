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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The trace format, its reader and writer, the arithmetic that replays it, and the built-in profiles.
 *
 * No player and no clock: a trace is a value, and what it means — what bandwidth at what time, and
 * when a byte sent into it arrives — is checkable as one. `NetworkShapingTest` is where the same
 * arithmetic is held against a transfer.
 */
class ThroughputTraceTest {

    @Test
    fun theFormatReadsAsSpecified() {
        val trace = ThroughputTrace.parse(
            """
            superplayer-throughput-trace 1
            # A comment, and a blank line below it.

            at-end hold
            2000 5000000 WIFI
            1000 0 CELLULAR 80
            500 1000000 CELLULAR
            """.trimIndent(),
        )

        assertThat(trace.durationMs).isEqualTo(3_500)
        assertThat(trace.loops).isFalse()
        assertThat(trace.bandwidthBpsAt(1_999)).isEqualTo(5_000_000)
        assertThat(trace.transportAt(1_999)).isEqualTo(NetworkTransport.WIFI)
        // The round-trip column is optional, and absent means none.
        assertThat(trace.rttMsAt(0)).isEqualTo(0)
        assertThat(trace.bandwidthBpsAt(2_000)).isEqualTo(0)
        assertThat(trace.transportAt(2_000)).isEqualTo(NetworkTransport.CELLULAR)
        assertThat(trace.rttMsAt(2_000)).isEqualTo(80)
        // Held: past the end is the last stretch, for good.
        assertThat(trace.bandwidthBpsAt(60_000)).isEqualTo(1_000_000)
    }

    @Test
    fun everyProfileSurvivesBeingWrittenAndReadBack() {
        NetworkProfile.entries.forEach { profile ->
            assertThat(ThroughputTrace.parse(profile.trace.format())).isEqualTo(profile.trace)
        }
    }

    @Test
    fun aMalformedTraceIsRefusedNamingTheLine() {
        val header = ThroughputTrace.FORMAT_HEADER
        mapOf(
            "not-a-trace\n1000 1 WIFI" to "Line 1:",
            "$header\n1000 1" to "Line 2: a stretch is",
            "$header\n1000 1 WIFI 5 6" to "Line 2: a stretch is",
            "$header\n1000 fast WIFI" to "Line 2: bandwidth_bps",
            "$header\n1000 1 LTE" to "Line 2: transport is one of",
            "$header\n0 1 WIFI" to "Line 2: A stretch lasts at least 1 ms",
            "$header\n1000 -1 WIFI" to "Line 2: A bandwidth is",
            "$header\n1000 1 WIFI\nat-end hold" to "Line 3: \"at-end\" appears once",
            "$header\nat-end forever\n1000 1 WIFI" to "Line 2: \"at-end\" is followed by",
        ).forEach { (text, expected) ->
            val failure = assertThrows(IllegalArgumentException::class.java) { ThroughputTrace.parse(text) }
            assertThat(failure).hasMessageThat().startsWith(expected)
        }
    }

    @Test
    fun aTraceThatCouldNeverDeliverIsRefused() {
        // Both are a replay that would wait forever, which hangs a test rather than failing it.
        assertThrows(IllegalArgumentException::class.java) {
            ThroughputTrace.Builder().add(1_000, 0, NetworkTransport.CELLULAR).build()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThroughputTrace.Builder()
                .add(1_000, 1_000, NetworkTransport.WIFI)
                .add(1_000, 0, NetworkTransport.CELLULAR)
                .holdAtEnd()
                .build()
        }
    }

    @Test
    fun aLoopingTraceStartsAgainAndAHoldingOneStaysPut() {
        val stretches = ThroughputTrace.Builder()
            .add(1_000, 1_000, NetworkTransport.WIFI)
            .add(1_000, 2_000, NetworkTransport.CELLULAR)

        val looping = stretches.build()
        assertThat(looping.bandwidthBpsAt(2_500)).isEqualTo(1_000)
        assertThat(looping.transportAt(2_500)).isEqualTo(NetworkTransport.WIFI)

        val holding = stretches.holdAtEnd().build()
        assertThat(holding.bandwidthBpsAt(60_000)).isEqualTo(2_000)
        assertThat(holding.transportAt(60_000)).isEqualTo(NetworkTransport.CELLULAR)
    }

    @Test
    fun bytesArriveAtTheRateOfEveryStretchTheyCross() {
        // 4 096 bit/s is 512 bytes a second, so a quarter of that takes a quarter of a second.
        val trace = ThroughputTrace.Builder()
            .add(500, 4_096, NetworkTransport.CELLULAR)
            .add(1_000, 0, NetworkTransport.CELLULAR)
            .add(500, 4_096, NetworkTransport.CELLULAR)
            .build()

        assertThat(trace.arrivalMs(startMs = 0, bytes = 128)).isEqualTo(250)
        assertThat(trace.arrivalMs(startMs = 0, bytes = 256)).isEqualTo(500)
        // The dropout carries nothing, so the third quarter waits it out.
        assertThat(trace.arrivalMs(startMs = 0, bytes = 384)).isEqualTo(1_750)
        assertThat(trace.arrivalMs(startMs = 0, bytes = 512)).isEqualTo(2_000)
        // And into the next pass, dropout and all.
        assertThat(trace.arrivalMs(startMs = 0, bytes = 896)).isEqualTo(3_750)
        // A transfer starting in the dropout waits for the link before its first byte.
        assertThat(trace.arrivalMs(startMs = 600, bytes = 128)).isEqualTo(1_750)
    }

    @Test
    fun aTransferOutlastingManyPassesLandsWhereWalkingThemWouldPutIt() {
        // One pass carries exactly 512 bytes and ends at 2 000 ms, so a thousand passes end at
        // 2 000 000 — and the pass-skipping shortcut has to agree with that to the millisecond.
        val trace = ThroughputTrace.Builder()
            .add(500, 4_096, NetworkTransport.CELLULAR)
            .add(1_000, 0, NetworkTransport.CELLULAR)
            .add(500, 4_096, NetworkTransport.CELLULAR)
            .build()

        assertThat(trace.arrivalMs(startMs = 0, bytes = 512L * 1_000)).isEqualTo(2_000_000)
        assertThat(trace.arrivalMs(startMs = 0, bytes = 512L * 1_000 + 128)).isEqualTo(2_000_250)
    }

    @Test
    fun aHoldingTraceDeliversAtItsLastRateAfterTheEnd() {
        val trace = ThroughputTrace.Builder().add(1_000, 8_000, NetworkTransport.WIFI).holdAtEnd().build()

        // 2 000 bytes is 16 000 bits: two seconds at 8 000 bit/s, the second of them past the end.
        assertThat(trace.arrivalMs(startMs = 0, bytes = 2_000)).isEqualTo(2_000)
    }

    @Test
    fun aByteHasNotArrivedUntilAllOfItHas() {
        val trace = ThroughputTrace.Builder().add(10_000, 3, NetworkTransport.WIFI).build()

        // 8 bits at 3 bit/s is 2 666.67 ms, and the last bit lands in the 2 667th millisecond.
        assertThat(trace.arrivalMs(startMs = 0, bytes = 1)).isEqualTo(2_667)
    }

    @Test
    fun theStableAndThreeGProfilesAreTheConstantRatesThePrdNames() {
        assertThat(rates(NetworkProfile.STABLE_WIFI)).containsExactly(20_000_000L)
        assertThat(NetworkProfile.STABLE_WIFI.trace.transportAt(0)).isEqualTo(NetworkTransport.WIFI)
        assertThat(rates(NetworkProfile.THREE_G)).containsExactly(1_000_000L)
        assertThat(NetworkProfile.THREE_G.trace.transportAt(0)).isEqualTo(NetworkTransport.CELLULAR)
    }

    @Test
    fun congestedWifiAveragesExactlyThreeMbpsAndSwingsWithinHalfOfIt() {
        val trace = NetworkProfile.CONGESTED_WIFI.trace
        val perSecond = (0 until trace.durationMs step 1_000).map { trace.bandwidthBpsAt(it) }

        assertThat(perSecond.sum() / perSecond.size).isEqualTo(3_000_000L)
        assertThat(perSecond.all { it in 1_500_000L..4_500_000L }).isTrue()
        // It varies, which is the difference between this profile and a 3 Mbit/s cap.
        assertThat(perSecond.toSet().size).isGreaterThan(perSecond.size / 2)
    }

    /**
     * When a first two-second chunk of each benchmark rung arrives on congested WiFi (issue #134).
     *
     * The seeded trace's first second runs at 4 374 064 bit/s and its second at 1 625 936, with no
     * round trip, and Media3's fake chunk is its rung's bitrate times `PlaybackHarness`'s two-second
     * chunk duration in bytes. The rungs are the benchmark's `FULL_LADDER` (`Scenario.kt`), and the
     * transfer opens at the trace's origin because a ladder has no manifest to load first. So the
     * benchmark's congested WiFi time to first frame is decided by the starting rung rather than by
     * the one-second sample edge: 730 kbit/s lands at 334 ms and reads 400 once stepped, 2 Mbit/s at
     * 915 ms reads 1000, and 4.5 Mbit/s, crossing two edges, at 2786 ms reads 2850. The benchmark
     * report's caveat rests on these numbers, and a change to the seed or the swing moves them.
     */
    @Test
    fun congestedWifiDeliversEachRungsFirstChunkWhereTheBenchmarkReadsIt() {
        val trace = NetworkProfile.CONGESTED_WIFI.trace
        val chunkDurationS = 2 // PlaybackHarness.CHUNK_DURATION_US
        fun firstChunkArrivalMs(bitrateBps: Long) = trace.arrivalMs(startMs = 0, bytes = bitrateBps * chunkDurationS / 8)

        assertThat(trace.rttMsAt(0)).isEqualTo(0)
        assertThat(trace.bandwidthBpsAt(0)).isEqualTo(4_374_064L)
        assertThat(trace.bandwidthBpsAt(1_000)).isEqualTo(1_625_936L)
        assertThat(firstChunkArrivalMs(730_000)).isEqualTo(334)
        assertThat(firstChunkArrivalMs(2_000_000)).isEqualTo(915)
        assertThat(firstChunkArrivalMs(4_500_000)).isEqualTo(2_786)
    }

    @Test
    fun lteDropsOutForTwoSecondsEveryPeriod() {
        val trace = NetworkProfile.LTE_WITH_DROPOUTS.trace
        val period = NetworkProfile.LTE_DROPOUT_PERIOD_MS

        assertThat(trace.bandwidthBpsAt(0)).isEqualTo(5_000_000)
        assertThat(trace.bandwidthBpsAt(period - 2_001)).isEqualTo(5_000_000)
        assertThat(trace.bandwidthBpsAt(period - 2_000)).isEqualTo(0)
        assertThat(trace.bandwidthBpsAt(period - 1)).isEqualTo(0)
        // And again in the next period, because it loops.
        assertThat(trace.bandwidthBpsAt(2 * period - 1)).isEqualTo(0)
        // A dropout is a link with nothing on it, not a change of link.
        assertThat(trace.transportAt(period - 1)).isEqualTo(NetworkTransport.CELLULAR)
    }

    @Test
    fun theHandoverChangesTransportMidPlaybackAndDoesNotChangeBack() {
        val trace = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace
        val at = NetworkProfile.HANDOVER_AT_MS

        assertThat(trace.transportAt(at - 1)).isEqualTo(NetworkTransport.WIFI)
        assertThat(trace.bandwidthBpsAt(at - 1)).isEqualTo(20_000_000)
        assertThat(trace.transportAt(at)).isEqualTo(NetworkTransport.CELLULAR)
        assertThat(trace.bandwidthBpsAt(at)).isEqualTo(5_000_000)
        // Held, not looped: the device does not wander back onto WiFi because the trace ran out.
        assertThat(trace.transportAt(at * 100)).isEqualTo(NetworkTransport.CELLULAR)
    }

    @Test
    fun highLatencyDiffersFromStableWifiOnlyInItsRoundTrip() {
        val trace = NetworkProfile.HIGH_LATENCY.trace

        assertThat(trace.rttMsAt(0)).isEqualTo(600)
        assertThat(rates(NetworkProfile.HIGH_LATENCY)).isEqualTo(rates(NetworkProfile.STABLE_WIFI))
        assertThat(NetworkProfile.entries.filter { it.trace.rttMsAt(0) != 0L })
            .containsExactly(NetworkProfile.HIGH_LATENCY)
    }

    @Test
    fun aConvertedBandwidthLogReadsAsItsSourceSays() {
        // `bandwidth-log-sample.trace` is `convertThroughputTrace`'s output for the log beside it,
        // which build-logic's own test holds it to. What is asserted here is that the reader takes
        // from it what the log recorded: bytes over elapsed milliseconds, one sample a stretch.
        val trace = ThroughputTrace.parse(resource("bandwidth-log-sample.trace"))

        assertThat(trace.loops).isTrue()
        assertThat(trace.durationMs).isEqualTo(5_020)
        assertThat(trace.bandwidthBpsAt(0)).isEqualTo(1_000_000) // 125 000 bytes in 1 000 ms
        assertThat(trace.bandwidthBpsAt(1_000)).isEqualTo(500_000) // 62 500 bytes in 1 000 ms
        assertThat(trace.bandwidthBpsAt(2_000)).isEqualTo(0) // nothing received: a dropout
        assertThat(trace.bandwidthBpsAt(4_000)).isEqualTo(1_000_000) // 127 500 bytes in 1 020 ms
        assertThat(trace.transportAt(0)).isEqualTo(NetworkTransport.CELLULAR)
    }

    @Test
    fun aConvertedMahimahiTraceReadsAsItsSourceSays() {
        val trace = ThroughputTrace.parse(resource("mahimahi-sample.trace"))

        // 100 packets of 1 500 bytes in the first 100 ms, 25 in the next, none, then one in 50 ms.
        assertThat(trace.durationMs).isEqualTo(350)
        assertThat(trace.bandwidthBpsAt(0)).isEqualTo(12_000_000)
        assertThat(trace.bandwidthBpsAt(100)).isEqualTo(3_000_000)
        assertThat(trace.bandwidthBpsAt(200)).isEqualTo(0)
        assertThat(trace.bandwidthBpsAt(300)).isEqualTo(240_000)
    }

    private fun rates(profile: NetworkProfile): Set<Long> =
        (0 until profile.trace.durationMs step 100).map { profile.trace.bandwidthBpsAt(it) }.toSet()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/traces/$name")) { "No test resource traces/$name" }.readText()
}
