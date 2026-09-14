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

package com.superplayer.benchmark

import android.annotation.SuppressLint
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * What a run was measured on, recorded beside what it measured.
 *
 * **A number without its conditions cannot be compared to anything**, which is issue #43's wording
 * and is the entire reason this file exists. The baseline this harness commits is what Phase 3 is
 * graded against, and "ABR improved the rebuffer ratio by 30%" means nothing unless the two numbers
 * came from the same Media3, the same content, the same network profiles and the same number of
 * runs. So every one of those travels with the report rather than in somebody's memory of how they
 * ran it.
 *
 * [treeDirty] is here for the same reason and is the one field people are tempted to leave out. A
 * baseline measured against uncommitted changes describes a state of the world that no longer exists
 * and that nobody can check out, so it is recorded as prominently as the commit is — and the report
 * says so in words rather than printing a boolean somebody has to notice.
 */
internal data class RunConditions(
    val startedAt: String,
    val commit: String,
    val treeDirty: Boolean,
    val superPlayerVersion: String,
    val media3Version: String,
    val runsPerCell: Int,
    val robolectricSdk: String,
    val javaVersion: String,
    val host: String,
) {

    // `NewApi` is suppressed because this never runs on a device. It reads the conditions of a
    // Robolectric matrix run on the host JVM (Java 17, where `Instant` and `Process.waitFor` with a
    // timeout have always existed), and `BenchmarkActivity` — the only code here that does run on a
    // device — never calls it. Raising the benchmark app's minSdk to satisfy lint would change the
    // device arm for the sake of a function the device arm does not use.
    @SuppressLint("NewApi")
    companion object {

        /**
         * Reads the conditions off the machine the run is happening on.
         *
         * [repoRoot] is the repository, which the benchmark build sits one directory inside. The
         * version catalog is read from there rather than from this build's own resolved
         * dependencies, because the catalog is what `ADR-0001` rule 3 makes the single source of the
         * Media3 version and because a report should name the pin rather than whatever a resolution
         * happened to produce.
         */
        fun read(repoRoot: File, runsPerCell: Int): RunConditions = RunConditions(
            startedAt = Instant.now().toString(),
            commit = git(repoRoot, "rev-parse", "HEAD") ?: UNKNOWN,
            // Empty output means a clean tree; null means git could not be asked, which is not the
            // same thing and must not be reported as "clean". An unknown commit with a clean tree
            // would be the most misleading pair of fields this class could produce.
            treeDirty = git(repoRoot, "status", "--porcelain")?.isNotEmpty() ?: true,
            superPlayerVersion = catalogVersion(repoRoot, "superplayer") ?: UNKNOWN,
            media3Version = catalogVersion(repoRoot, "media3") ?: UNKNOWN,
            runsPerCell = runsPerCell,
            robolectricSdk = System.getProperty("robolectric.sdk") ?: robolectricSdkFromProperties() ?: UNKNOWN,
            javaVersion = System.getProperty("java.version") ?: UNKNOWN,
            host = "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                "${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cores",
        )

        /** The value of `[versions] <key>` in the shared catalog, or null when it cannot be read. */
        private fun catalogVersion(repoRoot: File, key: String): String? {
            val catalog = File(repoRoot, "gradle/libs.versions.toml")
            if (!catalog.isFile) return null
            // The same anchored pattern devicelab's `catalog_version` uses, so a commented-out line
            // never wins and `media3` is never read out of `media3Foo`.
            val pattern = Regex("""^$key = "(.*)"$""", RegexOption.MULTILINE)
            return pattern.find(catalog.readText())?.groupValues?.get(1)
        }

        /** The pinned Robolectric SDK, which decides which Android runtime every number came from. */
        private fun robolectricSdkFromProperties(): String? =
            RunConditions::class.java.classLoader
                ?.getResourceAsStream("robolectric.properties")
                ?.bufferedReader()
                ?.useLines { lines -> lines.firstNotNullOfOrNull { it.substringAfter("sdk=", "").ifEmpty { null } } }

        /**
         * Runs git in [repoRoot] and returns its trimmed output, or null when it could not be run.
         *
         * Bounded, because a benchmark that hung on a git invocation would read as a hung benchmark:
         * the run takes minutes and nobody would look at git first.
         */
        private fun git(repoRoot: File, vararg arguments: String): String? = try {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
            // Read before waiting, not after. A pipe holds about 64 KB, and a child that fills it
            // blocks on write until somebody drains it — so waiting first deadlocks the pair until
            // the timeout fires, and `git status --porcelain` on a tree with a few thousand
            // untracked files clears 64 KB comfortably. The timeout below would then report "we
            // could not ask git" about a git that answered perfectly well.
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0) {
                null
            } else {
                output.trim()
            }
        } catch (_: Exception) {
            // Any failure to ask git is "we do not know", which the caller turns into the
            // conservative answer. A benchmark must not fail to produce a report because the
            // machine it ran on had no git; it must fail to *claim* a commit it cannot name.
            null
        }

        private const val UNKNOWN = "unknown"
        private const val GIT_TIMEOUT_SECONDS = 10L
    }
}
