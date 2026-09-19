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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two halves of `release`'s wiring that no other test here can reach, read off the convention
 * plugin's own source in `LicenseHeaderTest`'s shape.
 *
 * The sixth refusal #329 asks for is a failing `check`, and it is the one refusal that is not
 * [planRelease]'s: it is a task dependency, so what makes it true is a line in a build script and
 * not a branch anything can call. Its twin is the line that must *not* be there — `release` mutates
 * the tree, publishes and commits, so a `check` that depended on it would cut a release on every
 * build. Both are one grep away from being wrong and neither would fail anything else, which is
 * what this class is for.
 */
class ReleaseWiringTest {

    @Test
    fun `release depends on check, so a red tree stops it`() {
        val registration = blockAfter("tasks.register<CutRelease>(\"release\") {")

        assertTrue(
            "The `release` task must depend on `check`: that dependency is how a release is kept " +
                "from being cut from a red tree, and it is the one of #329's six refusals that is " +
                "not planRelease's.\n$registration",
            registration.contains("dependsOn(tasks.named(\"check\"))")
        )
    }

    @Test
    fun `check does not depend on release, because a release mutates the tree`() {
        val check = blockAfter("tasks.named(\"check\") {")

        assertTrue(
            "`check` must not reach the release task: it writes the catalog, the changelog and " +
                "api/released/, publishes, commits and tags, so a `check` that ran it would cut a " +
                "release on every build.\n$check",
            !check.contains("release")
        )
    }

    /**
     * The body of the block a marker opens, up to the first line that closes one.
     *
     * The marker is asserted present rather than assumed: `substringAfter` answers the whole
     * string when it misses, so a renamed task would otherwise leave both assertions reading the
     * rest of the file and passing on the wrong lines.
     */
    private fun blockAfter(marker: String): String {
        assertTrue(
            "superplayer.verification.gradle.kts no longer contains `$marker`; this test reads " +
                "that block and cannot say anything about a block that is not there.",
            PLUGIN.contains(marker)
        )
        return PLUGIN.substringAfter(marker).substringBefore("\n}")
    }

    private companion object {
        // The convention plugin as source rather than as an applied plugin: `build-logic` is an
        // included build and its scripts are compiled into the build that applies them, so a test
        // here has the text and not the task graph. `LicenseHeaderTest` reads `../LICENSE` the
        // same way.
        val PLUGIN: String = File("src/main/kotlin/superplayer.verification.gradle.kts").readText()
    }
}
