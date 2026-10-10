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

package com.superplayer.testmedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Kotlin half of ADR-0021 rule 6: the synthetic streams under `ios/Tests/Fixtures/synthetic/` are
 * what [SyntheticHlsStream] produces now, byte for byte, file for file.
 *
 * The Swift half is `SyntheticHlsFixturesTests` in `SuperPlayerTestMediaTests`, which holds the Swift
 * port to the same files. Neither side needs the other's toolchain, and either generator drifting
 * fails its own build: a change here fails this test until `./gradlew updateIosFixtures` rewrites the
 * files, and the rewritten files then fail `ios/check` until the Swift port follows.
 *
 * [FIXTURES] is the table, and it is written into the directory as `index.tsv` so the Swift test reads
 * which fixtures exist and with what arguments rather than keeping a second copy of this list.
 */
class SyntheticHlsFixturesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun theCommittedFixturesAreWhatTheGeneratorsProduceNow() {
        // Set by this module's build script, which owns the path; unset, the test cannot know it.
        val directory = File(checkNotNull(System.getProperty(DIRECTORY_PROPERTY)) { "$DIRECTORY_PROPERTY is unset" })
        val expected = expectedFixtures()
        if (System.getProperty(MODE_PROPERTY) == "update") {
            directory.deleteRecursively()
            write(expected, directory)
            return
        }
        val differences = differences(expected, directory)
        if (differences.isNotEmpty()) {
            fail(
                "ios/Tests/Fixtures/synthetic/ is not what the Kotlin generators produce now:\n" +
                    differences.joinToString("\n") { "  $it" } +
                    "\nIf the generator change is deliberate, run $UPDATE_COMMAND alone, then check, " +
                    "and commit the fixtures with the change; the Swift port must then follow.",
            )
        }
    }

    // The controls: a comparison that passed everything would make the test above say nothing.

    @Test
    fun aDirectoryHoldingExactlyTheGeneratedBytesHasNoDifferences() {
        val directory = temporary.newFolder()
        write(expectedFixtures(), directory)

        assertEquals(emptyList<String>(), differences(expectedFixtures(), directory))
    }

    @Test
    fun oneByteChangedInOneSegmentIsNamed() {
        val directory = temporary.newFolder()
        val expected = expectedFixtures()
        write(expected, directory)
        val segment = File(directory, "hls-vod/" + SyntheticHlsStream.segmentName(1))
        val bytes = segment.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        segment.writeBytes(bytes)

        assertEquals(listOf("hls-vod/segment1.aac differs"), differences(expected, directory))
    }

    @Test
    fun aMissingFileAndAnExtraFileAreBothNamed() {
        val directory = temporary.newFolder()
        val expected = expectedFixtures()
        write(expected, directory)
        File(directory, "hls-ladder/media-high.m3u8").delete()
        File(directory, "hls-vod/segment9.aac").writeBytes(byteArrayOf(0))

        assertEquals(
            listOf("hls-ladder/media-high.m3u8 is missing", "hls-vod/segment9.aac is not generated"),
            differences(expected, directory),
        )
    }

    @Test
    fun everyFixtureIsListedInTheIndexWithTheArgumentsItWasGeneratedWith() {
        val index = String(expectedFixtures().getValue(INDEX_FILE), Charsets.UTF_8)

        assertTrue(index, index.contains("hls-vod\tSyntheticHlsStream.files\t3\t1\n"))
        assertTrue(index, index.contains("hls-ladder\tSyntheticHlsStream.files\t3\t2\n"))
    }

    private companion object {
        const val DIRECTORY_PROPERTY = "superplayer.iosFixtures.dir"
        const val MODE_PROPERTY = "superplayer.iosFixtures.mode"
        const val UPDATE_COMMAND = "./gradlew updateIosFixtures"
        const val INDEX_FILE = "index.tsv"

        /**
         * Which fixtures are committed, as name, generator, segment count and variant count.
         *
         * Three segments, because one hides the one thing that varies between segments — the ID3
         * timestamp tag — and two ladders are the two forms #398 ports: the one-variant stream every
         * test plays, and the two-variant ladder a test of selection plays. Neither names a host,
         * because [SyntheticHlsStream.files] is the relative form. The live window, the second-host
         * variant and the protected forms are not here; the KDoc on the Swift port says whose each is.
         */
        val FIXTURES = listOf(
            Fixture("hls-vod", segmentCount = 3, variantCount = 1),
            Fixture("hls-ladder", segmentCount = 3, variantCount = 2),
        )

        data class Fixture(val name: String, val segmentCount: Int, val variantCount: Int)

        /** Every committed file, as its path under the fixtures directory to its bytes. */
        fun expectedFixtures(): Map<String, ByteArray> = buildMap {
            val index = StringBuilder("# name\tgenerator\tsegmentCount\tvariantCount\n")
            FIXTURES.forEach { fixture ->
                index.append("${fixture.name}\tSyntheticHlsStream.files\t${fixture.segmentCount}\t${fixture.variantCount}\n")
                SyntheticHlsStream.files(fixture.segmentCount, fixture.variantCount).forEach { (name, bytes) ->
                    put("${fixture.name}/$name", bytes)
                }
            }
            put(INDEX_FILE, index.toString().toByteArray(Charsets.UTF_8))
        }

        fun write(files: Map<String, ByteArray>, directory: File) = files.forEach { (path, bytes) ->
            File(directory, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }

        /** What differs between [expected] and [directory], one line per file, in path order. */
        fun differences(expected: Map<String, ByteArray>, directory: File): List<String> {
            val committed = directory.walkTopDown()
                .filter { it.isFile && it.name != ".DS_Store" }
                .associateBy { it.relativeTo(directory).invariantSeparatorsPath }
            return (expected.keys + committed.keys).sorted().mapNotNull { path ->
                val want = expected[path]
                val have = committed[path]
                when {
                    want == null -> "$path is not generated"
                    have == null -> "$path is missing"
                    !want.contentEquals(have.readBytes()) -> "$path differs"
                    else -> null
                }
            }
        }
    }
}
