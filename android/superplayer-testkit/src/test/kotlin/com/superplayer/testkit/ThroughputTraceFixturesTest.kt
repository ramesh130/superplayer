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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Kotlin half of ADR-0021 rule 6 for the trace grammar: a trace file means one network on both
 * platforms, because both read the same files under `ios/Tests/Fixtures/throughput-traces/` and are
 * held to the same *readings* of them.
 *
 * Two kinds of file live there. A `.trace` written by hand — `sample.trace`, which exercises every
 * line the grammar has — is an input nothing generates. Every [NetworkProfile] is written out as
 * `<profile>.trace` by [ThroughputTrace.format], so the Swift profiles are held to the Kotlin ones.
 * And beside every `.trace` is a `.readings` file: what [ThroughputTrace.parse] makes of it — the
 * trace written back, its bandwidth, round trip and transport at a fixed set of times, and when a
 * fixed set of transfers arrive. The Swift `ThroughputTraceFixturesTests` computes the same readings
 * with the Swift reader and compares them line for line, so "parses identically" is an equality of
 * text, including the replay's integer arithmetic.
 *
 * `(cd android && ./gradlew updateIosFixtures)` rewrites the generated files, on the golden traces'
 * contract: run it alone, then `check`, and commit the diff with its cause.
 */
class ThroughputTraceFixturesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun theCommittedTracesAndReadingsAreWhatTheKotlinReaderProducesNow() {
        // Set by this module's build script, which owns the path; unset, the test cannot know it.
        val directory = File(checkNotNull(System.getProperty(DIRECTORY_PROPERTY)) { "$DIRECTORY_PROPERTY is unset" })
        if (System.getProperty(MODE_PROPERTY) == "update") {
            generated(directory).keys.forEach { File(directory, it).delete() }
            directory.listFiles { file -> file.name.endsWith(READINGS) }?.forEach { it.delete() }
            generated(directory).forEach { (name, text) -> File(directory, name).writeText(text) }
            return
        }
        val differences = differences(directory)
        if (differences.isNotEmpty()) {
            fail(
                "ios/Tests/Fixtures/throughput-traces/ is not what the Kotlin reader produces now:\n" +
                    differences.joinToString("\n") { "  $it" } +
                    "\nIf the change is deliberate, run $UPDATE_COMMAND alone, then check, and commit " +
                    "the fixtures with the change; the Swift reader must then follow.",
            )
        }
    }

    @Test
    fun theHandWrittenSampleIsCommittedAndReadsEveryLineTheGrammarHas() {
        val directory = File(checkNotNull(System.getProperty(DIRECTORY_PROPERTY)))
        val sample = File(directory, SAMPLE).readText()

        // A comment, a blank line, the directive, and a stretch with and without its round trip.
        assertTrue(sample, sample.lines().any { it.startsWith("#") })
        assertTrue(sample, sample.lines().any { it.isBlank() })
        assertTrue(sample, sample.lines().any { it.startsWith("at-end") })
        val trace = ThroughputTrace.parse(sample)
        assertTrue(trace.format(), trace.format().lines().any { it.split(" ").size == 4 })
        assertTrue(trace.format(), trace.format().lines().any { it.split(" ").size == 3 })
    }

    // The controls: a comparison that passed everything would make the test above say nothing.

    @Test
    fun aDirectoryHoldingExactlyTheGeneratedFilesHasNoDifferences() {
        val directory = withSample(temporary.newFolder())
        generated(directory).forEach { (name, text) -> File(directory, name).writeText(text) }

        assertEquals(emptyList<String>(), differences(directory))
    }

    @Test
    fun oneReadingChangedAMissingFileAndAnExtraFileAreNamed() {
        val directory = withSample(temporary.newFolder())
        generated(directory).forEach { (name, text) -> File(directory, name).writeText(text) }
        File(directory, "three-g$READINGS").appendText("arrival 0 1 1\n")
        File(directory, "ethernet$TRACE").delete()
        File(directory, "stale$READINGS").writeText("")

        assertEquals(
            listOf("ethernet.trace is missing", "stale.readings is not generated", "three-g.readings differs"),
            differences(directory),
        )
    }

    private fun withSample(directory: File): File {
        File(directory, SAMPLE).writeText("${ThroughputTrace.FORMAT_HEADER}\n1000 8000 WIFI\n")
        return directory
    }

    private companion object {
        const val DIRECTORY_PROPERTY = "superplayer.traceFixtures.dir"
        const val MODE_PROPERTY = "superplayer.iosFixtures.mode"
        const val UPDATE_COMMAND = "(cd android && ./gradlew updateIosFixtures)"
        const val TRACE = ".trace"
        const val READINGS = ".readings"
        const val SAMPLE = "sample$TRACE"

        /** A profile's file name: its constant, lowercased and hyphenated — what the Swift side derives too. */
        fun fileName(profile: NetworkProfile): String = profile.name.lowercase().replace('_', '-') + TRACE

        /** Every file this side writes: each profile's trace, and the readings of every trace there. */
        fun generated(directory: File): Map<String, String> = buildMap {
            NetworkProfile.entries.forEach { put(fileName(it), it.trace.format()) }
            val handWritten = directory.listFiles { file -> file.name.endsWith(TRACE) }.orEmpty()
                .map { it.name }
                .filter { it !in keys }
            handWritten.forEach { put(it.removeSuffix(TRACE) + READINGS, readings(File(directory, it).readText())) }
            NetworkProfile.entries.forEach {
                put(fileName(it).removeSuffix(TRACE) + READINGS, readings(it.trace.format()))
            }
        }

        /**
         * What [ThroughputTrace.parse] makes of [text], one fact per line.
         *
         * The times are chosen from the trace's own length so every trace is read across its start, a
         * stretch boundary's worth of neighbourhood at its end, and well past it — where looping and
         * holding part — and the transfers so each one crosses stretches: none, a byte, a chunk the
         * iOS origin sends at once, a segment's worth, and many passes of a short trace.
         */
        fun readings(text: String): String {
            val trace = ThroughputTrace.parse(text)
            val d = trace.durationMs
            return buildString {
                append("# What ThroughputTrace.parse reads. Written by ThroughputTraceFixturesTest; $UPDATE_COMMAND.\n")
                trace.format().lines().filter { it.isNotEmpty() }.forEach { append("format $it\n") }
                append("loops ${trace.loops}\n")
                append("durationMs $d\n")
                listOf(0L, 1L, d / 2, d - 1, d, d + 1, 2 * d + d / 3, 10 * d).forEach { t ->
                    append("at $t bandwidth ${trace.bandwidthBpsAt(t)} rtt ${trace.rttMsAt(t)} transport ${trace.transportAt(t)}\n")
                }
                listOf(0L, d / 2, d + 1).forEach { start ->
                    listOf(0L, 1L, 4_096L, 250_000L, 2_500_000L).forEach { bytes ->
                        append("arrival $start $bytes ${trace.arrivalMs(start, bytes)}\n")
                    }
                }
            }
        }

        /** What differs between the generated files and [directory], one line per file, in name order. */
        fun differences(directory: File): List<String> {
            val expected = generated(directory)
            val handWritten = directory.listFiles { file -> file.name.endsWith(TRACE) }.orEmpty()
                .map { it.name }
                .filter { it !in expected }
                .toSet()
            val committed = directory.listFiles().orEmpty()
                .filter { it.isFile && it.name != ".DS_Store" && it.name !in handWritten }
                .associateBy { it.name }
            return (expected.keys + committed.keys).sorted().mapNotNull { name ->
                val want = expected[name]
                val have = committed[name]
                when {
                    want == null -> "$name is not generated"
                    have == null -> "$name is missing"
                    want != have.readText() -> "$name differs"
                    else -> null
                }
            }
        }
    }
}
