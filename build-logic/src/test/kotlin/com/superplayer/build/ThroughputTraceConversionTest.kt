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

package com.superplayer.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The conversions behind `convertThroughputTrace`.
 *
 * The first test is the one that holds two builds to one format: the converter's output for the
 * project-generated fixtures has to be, byte for byte, the files `superplayer-testkit`'s
 * `ThroughputTraceTest` reads and asserts the meaning of. A change to either side that the other
 * did not follow fails one of the two.
 */
class ThroughputTraceConversionTest {

    @Test
    fun `the checked-in fixtures are exactly what the converter writes`() {
        // Gradle runs these tests with `build-logic` as the working directory.
        val traces = File("../superplayer-testkit/src/test/resources/traces")
        assertTrue("expected ${traces.absolutePath} to exist", traces.isDirectory)

        assertEquals(
            traces.resolve("bandwidth-log-sample.trace").readText(),
            convertBandwidthLog(traces.resolve("bandwidth-log-sample.log").readText(), "CELLULAR", "bandwidth-log-sample.log"),
        )
        assertEquals(
            traces.resolve("mahimahi-sample.trace").readText(),
            convertMahimahi(traces.resolve("mahimahi-sample.txt").readText(), "CELLULAR", "mahimahi-sample.txt", binMs = 100),
        )
    }

    @Test
    fun `a bandwidth log sample becomes a stretch at the rate its bytes imply`() {
        // 125 000 bytes in 1 000 ms is 1 Mbit/s; 1 000 bytes in 3 ms is 2 666 666.67, rounded down.
        val trace = convertBandwidthLog("0 0 0 0 125000 1000\n0 0 0 0 1000 3\n", "WIFI", "log")

        assertEquals(listOf("1000 1000000 WIFI", "3 2666666 WIFI"), rows(trace))
    }

    @Test
    fun `a sample with no elapsed time carries its bytes into the next rather than dropping them`() {
        val trace = convertBandwidthLog("0 0 0 0 500 0\n0 0 0 0 750 10\n", "CELLULAR", "log")

        // 1 250 bytes over 10 ms: the trace delivers every byte the log recorded.
        assertEquals(listOf("10 1000000 CELLULAR"), rows(trace))
    }

    @Test
    fun `a malformed bandwidth log line is reported by its line number`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            convertBandwidthLog("0 0 0 0 1 1\n0 0 0 0 x 1\n", "CELLULAR", "log")
        }

        assertTrue(failure.message, failure.message!!.startsWith("line 2: column 5"))
    }

    @Test
    fun `a mahimahi packet counts toward the bin it is delivered by`() {
        // Bins of 1 000 ms are (0, 1000] and (1000, 2000]; a packet at 0 belongs to the first. Two
        // 1 500-byte packets in a second is 24 000 bit/s.
        val trace = convertMahimahi("0\n1000\n1001\n2000\n", "CELLULAR", "trace", binMs = 1_000)

        assertEquals(listOf("1000 24000 CELLULAR", "1000 24000 CELLULAR"), rows(trace))
    }

    @Test
    fun `the last mahimahi bin ends at the period and is rated over its own length`() {
        // Period 1 500 ms: the second bin is 500 ms long, and one packet in it is 24 000 bit/s.
        val trace = convertMahimahi("1\n1500\n", "CELLULAR", "trace", binMs = 1_000)

        assertEquals(listOf("1000 12000 CELLULAR", "500 24000 CELLULAR"), rows(trace))
    }

    @Test
    fun `mahimahi timestamps that go backwards are rejected`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            convertMahimahi("5\n3\n", "CELLULAR", "trace", binMs = 1_000)
        }

        assertTrue(failure.message, failure.message!!.startsWith("line 2: timestamps go backwards"))
    }

    @Test
    fun `a transport the reader would not accept is refused before anything is written`() {
        assertThrows(IllegalArgumentException::class.java) {
            convertBandwidthLog("0 0 0 0 1 1\n", "LTE", "log")
        }
    }

    @Test
    fun `every converted trace declares the format and that it loops`() {
        val lines = convertMahimahi("1\n", "UNKNOWN", "trace", binMs = 1_000).lines()

        assertEquals(TRACE_HEADER, lines.first())
        assertTrue(lines.contains("at-end loop"))
    }

    private fun rows(trace: String): List<String> =
        trace.lines().drop(1).filterNot { it.isBlank() || it.startsWith("#") || it.startsWith("at-end") }
}
