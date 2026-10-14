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

package com.superplayer.moq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Gradle resolved for `dev.moq` is the locally built artifact, at the exact version the
 * catalog names.
 *
 * This is the half of #364 that a comment cannot carry. `dev.moq:moq-ffi` exists on Maven Central
 * too, and the Central build is the one that still contains the MPL-2.0 `symphonia` decoder #351
 * removed by building the crate with its default features off. Two ordinary mistakes would resolve
 * it with nothing failing: a version this repository's own directory cannot answer for, and
 * upstream's `dev.moq:moq` wrapper, whose POM declares `dev.moq:moq-ffi:[0.3,0.4)` — a range
 * Gradle is happy to satisfy from whichever repository can. `settings.gradle.kts` closes both by
 * scoping the whole group to `third-party/moq/m2` with `exclusiveContent`, and this test is what
 * says the scoping held.
 *
 * Both sides are handed in by this module's build file: the version out of
 * `gradle/libs.versions.toml` (ADR-0001 rule 3), and the resolved component identifiers out of
 * Gradle's own resolution result. So the test restates neither and fails when they part company.
 */
class MoqBindingsResolutionTest {

    @Test
    fun everyResolvedMoqComponentIsTheLocallyBuiltOne() {
        val resolved = resolvedComponents()

        // Without this the assertion below would hold over an empty list, which is what a renamed
        // artifact or a dropped dependency would look like.
        assertTrue("nothing from $MOQ_GROUP was resolved at all", resolved.isNotEmpty())
        assertEquals(
            "a $MOQ_GROUP component at a version this repository did not build",
            emptyList<String>(),
            componentsNotAtVersion(resolved, expectedVersion())
        )
    }

    /**
     * The reading that would name a component resolved from Maven Central, so the assertion above
     * is "this one and no other" rather than "any `moq-ffi` will do". `0.3.19` is the version
     * Central would answer with — the same crate release, built with the codecs on — and it is the
     * near miss worth being able to see.
     *
     * It is a check of the reading and **not** of the build: nothing here can remove
     * `exclusiveContent` and watch the resolution move, so what this pins down is that a foreign
     * version would be reported rather than passed over.
     */
    @Test
    fun aComponentResolvedFromMavenCentralWouldBeNamed() {
        val central = "dev.moq:moq-ffi-jvm:0.3.19"
        val local = "dev.moq:moq-ffi-jvm:0.3.19-superplayer-local"

        assertEquals(
            listOf(central),
            componentsNotAtVersion(listOf(central, local), "0.3.19-superplayer-local")
        )
    }

    /**
     * `MoqFfiLinkageTest`'s host guard, checked here because this is the class that runs on every
     * host. Its own would skip on the machines the guard exists for, so the predicate would be
     * asserted by nothing precisely where it matters.
     *
     * The two negatives are the point: a Linux CI runner and an Intel Mac both carry no library in
     * `third-party/moq/m2`, and a guard that admitted either would turn a missing artifact into a
     * red build rather than a skip with a reason.
     */
    @Test
    fun theHostGuardAdmitsOnlyTheMachineTheLibraryWasBuiltFor() {
        assertTrue(hostMatchesTheCommittedLibrary("Mac OS X", "aarch64"))
        assertFalse(hostMatchesTheCommittedLibrary("Linux", "amd64"))
        assertFalse(hostMatchesTheCommittedLibrary("Mac OS X", "x86_64"))
        assertFalse(hostMatchesTheCommittedLibrary(null, null))
    }

    private fun resolvedComponents(): List<String> =
        property(RESOLVED_PROPERTY).split(',').filter { it.isNotBlank() }

    private fun expectedVersion(): String = property(VERSION_PROPERTY)

    private fun property(name: String): String {
        val value = System.getProperty(name)
        check(!value.isNullOrBlank()) {
            "$name was not set. This module's build file sets it; a test run that bypasses the " +
                "build file cannot check what was resolved."
        }
        return value
    }

    private companion object {
        const val VERSION_PROPERTY = "superplayer.moq.ffi.version"
        const val RESOLVED_PROPERTY = "superplayer.moq.resolved"
        const val MOQ_GROUP = "dev.moq"
    }
}

/**
 * The resolved components, as `group:module:version`, that are not at [version] — the empty list
 * being the only acceptable answer.
 */
internal fun componentsNotAtVersion(components: List<String>, version: String): List<String> =
    components.filterNot { it.substringAfterLast(':') == version }
