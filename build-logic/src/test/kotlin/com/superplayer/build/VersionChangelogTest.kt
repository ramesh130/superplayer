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

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison behind `verifyVersion`: the version every module publishes under, against what
 * CHANGELOG.md says has been released.
 *
 * The case that matters is a release half-made — the version moved and the notes did not, or the
 * notes were written and the version did not — because that is the state a person is in when they
 * see this failure. The control that matters as much is the repository's own current state, which
 * is a snapshot with nothing released, and which must pass.
 */
class VersionChangelogTest {

    @Test
    fun `the repository's own state - a snapshot with nothing released - passes`() {
        assertNull(findVersionChangelogMismatch(catalog("0.1.0-SNAPSHOT"), unreleasedOnly()))
    }

    @Test
    fun `a released version with a dated heading and a body passes`() {
        assertNull(findVersionChangelogMismatch(catalog("0.1.0"), released("0.1.0", "2026-09-19")))
    }

    @Test
    fun `a malformed version names the catalog and the grammar`() {
        val mismatch = findVersionChangelogMismatch(catalog("0.1"), unreleasedOnly())

        assertTrue(mismatch!!, mismatch.contains("gradle/libs.versions.toml"))
        assertTrue(mismatch, mismatch.contains("superplayer = \"0.1\""))
        assertTrue(mismatch, mismatch.contains("MAJOR.MINOR.PATCH"))
        assertTrue(mismatch, mismatch.contains("CHANGELOG.md"))
    }

    @Test
    fun `a catalog without the version fails rather than passing`() {
        val mismatch = findVersionChangelogMismatch(
            "[versions]\nmedia3 = \"1.11.0\"\n",
            unreleasedOnly()
        )

        assertTrue(mismatch!!, mismatch.contains("gradle/libs.versions.toml"))
    }

    @Test
    fun `the library entries are not mistaken for the version`() {
        // `superplayer-core = { ... }` also starts with `superplayer`; only the `[versions]` key counts.
        val catalog = """
            [libraries]
            superplayer-core = { module = "com.superplayer:superplayer-core", version = "9.9.9" }

            [versions]
            superplayer = "0.1.0-SNAPSHOT"
        """.trimIndent()

        assertNull(findVersionChangelogMismatch(catalog, unreleasedOnly()))
    }

    @Test
    fun `a released version with no heading names both files and both readings`() {
        val mismatch = findVersionChangelogMismatch(
            catalog("0.2.0"),
            released("0.1.0", "2026-09-19")
        )

        assertTrue(mismatch!!, mismatch.contains("gradle/libs.versions.toml"))
        assertTrue(mismatch, mismatch.contains("superplayer = \"0.2.0\""))
        assertTrue(mismatch, mismatch.contains("CHANGELOG.md"))
        // The versions the changelog does date, so the reader can see which way round it is.
        assertTrue(mismatch, mismatch.contains("0.1.0"))
    }

    @Test
    fun `a released version with no versions dated at all says so`() {
        val mismatch = findVersionChangelogMismatch(catalog("0.1.0"), unreleasedOnly())

        assertTrue(mismatch!!, mismatch.contains("none"))
    }

    @Test
    fun `a release heading with no date is not a release heading`() {
        val changelog = """
            # Changelog

            ## [Unreleased]

            ## [0.1.0]

            ### Added

            - Everything.
        """.trimIndent()

        val mismatch = findVersionChangelogMismatch(catalog("0.1.0"), changelog)

        assertTrue(mismatch!!, mismatch.contains("ISO 8601"))
    }

    @Test
    fun `a release heading with nothing under it is an empty promise`() {
        val changelog = """
            # Changelog

            ## [Unreleased]

            ## [0.1.0] - 2026-09-19

            ## [0.0.1] - 2026-01-01

            - Something.
        """.trimIndent()

        val mismatch = findVersionChangelogMismatch(catalog("0.1.0"), changelog)

        assertTrue(mismatch!!, mismatch.contains("nothing written under it"))
    }

    @Test
    fun `a snapshot needs an Unreleased section to accumulate into`() {
        val changelog = """
            # Changelog

            ## [0.1.0] - 2026-09-19

            - Something.
        """.trimIndent()

        val mismatch = findVersionChangelogMismatch(catalog("0.2.0-SNAPSHOT"), changelog)

        assertTrue(mismatch!!, mismatch.contains("Unreleased"))
        assertTrue(mismatch, mismatch.contains("CHANGELOG.md"))
        assertTrue(mismatch, mismatch.contains("gradle/libs.versions.toml"))
    }

    @Test
    fun `a snapshot needs no dated heading of its own`() {
        // The whole of the snapshot rule: requiring one would require the changelog to date a
        // release that has not happened.
        assertNull(
            findVersionChangelogMismatch(
                catalog("0.2.0-SNAPSHOT"),
                released("0.1.0", "2026-09-19")
            )
        )
    }

    @Test
    fun `an empty Unreleased section is the honest state just after a release`() {
        val changelog = """
            # Changelog

            ## [Unreleased]

            ## [0.1.0] - 2026-09-19

            - Something.
        """.trimIndent()

        assertNull(findVersionChangelogMismatch(catalog("0.2.0-SNAPSHOT"), changelog))
    }

    @Test
    fun `a snapshot of a version the changelog already dates is a half-finished release`() {
        // ADR-0017 rule 8: no release procedure may leave a snapshot behind. Either the version
        // never moved past the release that was written down, or it was moved back and the heading
        // was not.
        val mismatch = findVersionChangelogMismatch(
            catalog("0.1.0-SNAPSHOT"),
            released("0.1.0", "2026-09-19")
        )

        assertTrue(mismatch!!, mismatch.contains("half-finished release"))
        assertTrue(mismatch, mismatch.contains("2026-09-19"))
        assertTrue(mismatch, mismatch.contains("gradle/libs.versions.toml"))
        assertTrue(mismatch, mismatch.contains("CHANGELOG.md"))
    }

    @Test
    fun `a heading naming something that is not a version is not a release`() {
        val changelog = """
            # Changelog

            ## [Unreleased]

            ## [0.1.O] - 2026-09-19

            - A letter O where a zero belongs.
        """.trimIndent()

        // The typo'd heading must not be matched as 0.1.0 by accident.
        assertNotNull(findVersionChangelogMismatch(catalog("0.1.0"), changelog))
    }

    private fun catalog(superplayer: String): String = """
        [versions]
        superplayer = "$superplayer"
        media3 = "1.11.0"

        [libraries]
        media3-common = { module = "androidx.media3:media3-common", version.ref = "media3" }
    """.trimIndent()

    private fun unreleasedOnly(): String = """
        # Changelog

        ## [Unreleased]

        ### Added

        - Everything built so far; nothing has been released.
    """.trimIndent()

    private fun released(version: String, date: String): String = """
        # Changelog

        ## [Unreleased]

        ## [$version] - $date

        ### Added

        - The first release.
    """.trimIndent()
}
