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

import java.io.File

/**
 * A text artifact a test compares against a committed known-good, so a change in behaviour appears
 * in review as a line diff rather than as a metric that moved.
 *
 * The contract deliberately mirrors the tracked API surface (`docs/api-surface.md`): nothing
 * regenerates a golden implicitly, `./gradlew updateGoldenTraces` is the one command that does, and
 * the diff it produces is reviewed as part of the change that caused it. In its default mode this
 * fails the calling test on any difference, with the diff and that command in the message; the
 * module's `check` runs its tests, so an uncommitted golden diff fails `check` and CI alike.
 *
 * Which mode and which directory apply come from two JVM system properties the convention plugin
 * sets on every unit-test task — [MODE_PROPERTY] and [DIRECTORY_PROPERTY] — so a golden test reads
 * nothing from the environment itself. Run outside Gradle, from an IDE, the mode is `check` and the
 * directory is `src/test/golden` under the working directory, which is the module for both.
 *
 * What makes a golden worth committing is that it is byte-identical across runs and machines:
 * `docs/testing.md` says what may go into one and what may not.
 */
public object GoldenFile {

    /** JVM system property naming the [Mode]; `check` when unset. */
    public const val MODE_PROPERTY: String = "superplayer.golden.mode"

    /** JVM system property naming the directory goldens live in; `src/test/golden` when unset. */
    public const val DIRECTORY_PROPERTY: String = "superplayer.golden.dir"

    /** The command a failing check names, and the one `docs/testing.md` documents. */
    public const val UPDATE_COMMAND: String = "./gradlew updateGoldenTraces"

    public enum class Mode {
        /** Compare, and fail the test on any difference. The default, and what `check` runs. */
        CHECK,

        /** Rewrite the golden from what the test produced. Only `updateGoldenTraces` selects this. */
        UPDATE,
    }

    /**
     * Compares [actual] against the golden called [name] in the configured directory, or rewrites
     * it in [Mode.UPDATE].
     *
     * @throws AssertionError in [Mode.CHECK] when the golden is missing or differs.
     */
    @JvmStatic
    public fun check(name: String, actual: String) {
        check(name, actual, configuredDirectory(), configuredMode())
    }

    /** [check] against an explicit directory and mode, for a test of this object itself. */
    @JvmStatic
    public fun check(name: String, actual: String, directory: File, mode: Mode) {
        val golden = File(directory, name)
        when (mode) {
            Mode.UPDATE -> {
                golden.parentFile?.mkdirs()
                golden.writeText(actual)
            }

            Mode.CHECK -> {
                val expected = golden.takeIf { it.isFile }?.readText()
                describeGoldenDrift(expected, actual, name)?.let { throw AssertionError(it) }
            }
        }
    }

    private fun configuredMode(): Mode = when (val value = System.getProperty(MODE_PROPERTY)) {
        null, "", "check" -> Mode.CHECK
        "update" -> Mode.UPDATE
        else -> error("$MODE_PROPERTY must be check or update, was '$value'")
    }

    private fun configuredDirectory(): File =
        File(System.getProperty(DIRECTORY_PROPERTY)?.takeIf { it.isNotEmpty() } ?: DEFAULT_DIRECTORY)

    private const val DEFAULT_DIRECTORY = "src/test/golden"
}

/**
 * The failure message for a golden that is missing or differs, or null when [actual] matches.
 *
 * The diff is line-oriented with three lines of context, in the shape `diff -u` prints, because the
 * whole point of a golden is that a reviewer reads the change as a diff; anything less readable
 * gets rubber-stamped.
 */
internal fun describeGoldenDrift(expected: String?, actual: String, name: String): String? {
    if (expected == null) {
        return "Golden '$name' does not exist. Run ${GoldenFile.UPDATE_COMMAND} to create it, " +
            "then review and commit it with the change."
    }
    if (expected == actual) return null
    val diff = lineDiff(expected.lines().dropLastWhile { it.isEmpty() }, actual.lines().dropLastWhile { it.isEmpty() })
    return buildString {
        append("Golden '$name' differs from what this run produced.\n")
        append("If the change is intended, run ${GoldenFile.UPDATE_COMMAND} and commit the diff with it; ")
        append("if it is not, the code is what to fix.\n")
        append("--- $name (committed)\n")
        append("+++ $name (this run)\n")
        diff.forEach { append(it).append('\n') }
    }
}

/**
 * A unified diff of two line lists, with [context] unchanged lines around each change and `...`
 * between hunks. The textbook longest-common-subsequence table, quadratic in the trace length,
 * which is hundreds of lines; a linear-space or O(ND) algorithm would buy nothing here.
 *
 * // ref: Hunt & McIlroy, "An Algorithm for Differential File Comparison", Bell Labs CSTR 41 (1976)
 * // ref: Myers, "An O(ND) Difference Algorithm and Its Variations", Algorithmica 1(2) (1986)
 */
private fun lineDiff(old: List<String>, new: List<String>, context: Int = 3): List<String> {
    val lcs = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) {
        for (j in new.indices.reversed()) {
            lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    // Every line of both sides, tagged, in diff order.
    val tagged = mutableListOf<Pair<Char, String>>()
    var i = 0
    var j = 0
    while (i < old.size || j < new.size) {
        when {
            i < old.size && j < new.size && old[i] == new[j] -> {
                tagged += ' ' to old[i]
                i++
                j++
            }

            j < new.size && (i == old.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                tagged += '+' to new[j]
                j++
            }

            else -> {
                tagged += '-' to old[i]
                i++
            }
        }
    }
    val changed = tagged.indices.filter { tagged[it].first != ' ' }
    val shown = changed.flatMap { (it - context)..(it + context) }.filter { it in tagged.indices }.toSortedSet()
    val out = mutableListOf<String>()
    var previous = -1
    shown.forEach { index ->
        if (previous >= 0 && index > previous + 1) out += "..."
        val (tag, line) = tagged[index]
        out += "$tag$line"
        previous = index
    }
    return out
}
