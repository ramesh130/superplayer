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
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The comparison `GoldenFile.check` makes and the message it fails with, as pure text: the mode and
 * directory a test reads from the JVM are exercised through the file-backed path at the end.
 */
class GoldenFileTest {

    @Test
    fun anIdenticalGoldenIsNoDrift() {
        assertThat(describeGoldenDrift("a\nb\n", "a\nb\n", "hls.trace")).isNull()
    }

    @Test
    fun aMissingGoldenSaysHowToCreateIt() {
        val drift = checkNotNull(describeGoldenDrift(null, "a\n", "hls.trace"))

        assertThat(drift).contains("hls.trace")
        assertThat(drift).contains("does not exist")
        assertThat(drift).contains("./gradlew updateGoldenTraces")
    }

    @Test
    fun aChangedGoldenIsReportedAsALineDiffWithTheOldAndNewSides() {
        val expected = "+0 state BUFFERING\n+100 tracks video=800000\n+200 state READY\n"
        val actual = "+0 state BUFFERING\n+100 tracks video=2400000\n+200 state READY\n"

        val drift = checkNotNull(describeGoldenDrift(expected, actual, "hls.trace"))

        assertThat(drift).contains("-+100 tracks video=800000")
        assertThat(drift).contains("++100 tracks video=2400000")
        assertThat(drift).contains(" +0 state BUFFERING")
        assertThat(drift).contains("./gradlew updateGoldenTraces")
    }

    @Test
    fun anAddedLineAndARemovedLineAreEachShownOnce() {
        val drift = checkNotNull(describeGoldenDrift("a\nb\nc\n", "a\nc\nd\n", "x"))

        val body = drift.lines().filterNot { it.startsWith("---") || it.startsWith("+++") }
        assertThat(body.filter { it.startsWith("-") }).containsExactly("-b")
        assertThat(body.filter { it.startsWith("+") }).containsExactly("+d")
    }

    @Test
    fun unchangedLinesFarFromAChangeAreElided() {
        val expected = (1..40).joinToString("\n", postfix = "\n") { "line $it" }
        val actual = expected.replace("line 20\n", "line twenty\n")

        val drift = checkNotNull(describeGoldenDrift(expected, actual, "x"))

        assertThat(drift).doesNotContain("line 1\n")
        assertThat(drift).doesNotContain("line 40")
        assertThat(drift).contains(" line 17")
        assertThat(drift).contains(" line 23")
        assertThat(drift).contains("-line 20")
        assertThat(drift).contains("+line twenty")
    }

    @Test
    fun updateModeWritesTheGoldenAndCheckModeThenAccepts() {
        val directory = Files.createTempDirectory("golden").toFile()
        try {
            GoldenFile.check("session.trace", "one\ntwo\n", directory, GoldenFile.Mode.UPDATE)
            assertThat(File(directory, "session.trace").readText()).isEqualTo("one\ntwo\n")

            GoldenFile.check("session.trace", "one\ntwo\n", directory, GoldenFile.Mode.CHECK)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun checkModeFailsOnDriftWithoutTouchingTheGolden() {
        val directory = Files.createTempDirectory("golden").toFile()
        try {
            File(directory, "session.trace").writeText("one\n")

            val failure = runCatching {
                GoldenFile.check("session.trace", "one\ntwo\n", directory, GoldenFile.Mode.CHECK)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(AssertionError::class.java)
            assertThat(failure!!.message).contains("+two")
            assertThat(File(directory, "session.trace").readText()).isEqualTo("one\n")
        } finally {
            directory.deleteRecursively()
        }
    }
}
