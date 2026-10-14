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

import org.junit.Assert.fail
import java.io.File

/**
 * What both of this module's iOS fixture tests do, written once: write a set of files into a
 * directory, say how a directory differs from them, and — in the mode the build script decides —
 * do one or the other to the directory the build script names (ADR-0021 rule 6, `docs/testing.md`'s
 * *One corpus on both platforms*).
 *
 * Two tests rather than one because there are two corpora, each with its own directory and index:
 * the good streams under `synthetic/` ([SyntheticHlsFixturesTest]) and the HLS half of the hostile
 * corpus under `hostile/` ([HostileManifestsFixturesTest]).
 */
internal object IosFixtures {

    /** The one command that rewrites every iOS fixture, as a failure names it. */
    const val UPDATE_COMMAND = "(cd android && ./gradlew updateIosFixtures)"

    private const val MODE_PROPERTY = "superplayer.iosFixtures.mode"

    /**
     * Rewrites the directory [directoryProperty] names with [expected] in update mode, and otherwise
     * fails naming every file that differs from it. [what] is the directory as a failure names it.
     */
    fun checkOrUpdate(expected: Map<String, ByteArray>, directoryProperty: String, what: String) {
        // Set by this module's build script, which owns the path; unset, the test cannot know it.
        val directory = File(checkNotNull(System.getProperty(directoryProperty)) { "$directoryProperty is unset" })
        if (System.getProperty(MODE_PROPERTY) == "update") {
            directory.deleteRecursively()
            write(expected, directory)
            return
        }
        val differences = differences(expected, directory)
        if (differences.isNotEmpty()) {
            fail(
                "$what is not what the Kotlin generators produce now:\n" +
                    differences.joinToString("\n") { "  $it" } +
                    "\nIf the generator change is deliberate, run $UPDATE_COMMAND alone, then check, " +
                    "and commit the fixtures with the change; the Swift port must then follow.",
            )
        }
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
