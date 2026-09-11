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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison behind `verifyMedia3SupportedVersion`: the Media3 the catalog pins against the
 * supported version `docs/modules.md` states.
 *
 * The case that matters is a Media3 bump arriving as a pull request — Dependabot's `media3` group —
 * with the documentation left behind. That has to fail, and a patch release inside the stated range
 * must not, or the check fails every routine bump and gets switched off.
 */
class Media3SupportedVersionTest {

    @Test
    fun `a pin inside the stated minor version is consistent`() {
        assertNull(findMedia3SupportedVersionMismatch(catalog("1.11.0"), modules("1.11")))
        assertNull(findMedia3SupportedVersionMismatch(catalog("1.11.3"), modules("1.11")))
    }

    @Test
    fun `a minor bump the documentation has not followed is reported with both versions`() {
        val mismatch = findMedia3SupportedVersionMismatch(catalog("1.12.0"), modules("1.11"))

        assertEquals(
            "gradle/libs.versions.toml pins Media3 1.12.0, but docs/modules.md, under " +
                "\"Supported Media3 versions\", states Media3 1.11.x.",
            mismatch
        )
    }

    @Test
    fun `a major bump is a mismatch even when the minor number happens to agree`() {
        assertNotNull(findMedia3SupportedVersionMismatch(catalog("2.11.0"), modules("1.11")))
    }

    @Test
    fun `a pre-release of the next minor is outside the stated range`() {
        assertNotNull(findMedia3SupportedVersionMismatch(catalog("1.12.0-alpha01"), modules("1.11")))
    }

    @Test
    fun `the Media3 library entries are not mistaken for the version`() {
        // `media3-common = { ... }` also starts with `media3`; only the `[versions]` key counts.
        val catalog = """
            [libraries]
            media3-common = { module = "androidx.media3:media3-common", version = "9.9.9" }
            [versions]
            media3 = "1.11.0"
        """.trimIndent()

        assertNull(findMedia3SupportedVersionMismatch(catalog, modules("1.11")))
    }

    @Test
    fun `a version named outside the supported-versions section does not count`() {
        val document = """
            ## History

            Phase 1 was built against **Media3 1.10.x**.

        """.trimIndent() + "\n" + modules("1.11")

        assertNull(findMedia3SupportedVersionMismatch(catalog("1.11.0"), document))
    }

    @Test
    fun `a document the check cannot read fails rather than passing`() {
        // A reworded section must not turn the check into a silent pass.
        val mismatch =
            findMedia3SupportedVersionMismatch(catalog("1.11.0"), "### Supported Media3 versions\n")

        assertTrue(mismatch!!.contains("docs/modules.md"))
    }

    @Test
    fun `a catalog without a Media3 version fails rather than passing`() {
        val mismatch = findMedia3SupportedVersionMismatch("[versions]\nkotlin = \"2.4.10\"\n", modules("1.11"))

        assertTrue(mismatch!!.contains("gradle/libs.versions.toml"))
    }

    private fun catalog(media3: String): String = """
        [versions]
        agp = "9.4.0"
        media3 = "$media3"

        [libraries]
        media3-common = { module = "androidx.media3:media3-common", version.ref = "media3" }
    """.trimIndent()

    private fun modules(supportedMinor: String): String = """
        ### Supported Media3 versions

        ADR-0001 rule 3 requires each module to document the Media3 range it supports. Every module
        in this repository currently supports **Media3 $supportedMinor.x**, the version pinned in the
        catalog, and no other.

        ## Conventions live in `build-logic`

        Nothing here names a version.
    """.trimIndent()
}
