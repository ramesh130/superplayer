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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The checker behind `verifyCompatibilityDocument`: every module the build publishes has a row in
 * `docs/compatibility.md`'s stability table, so a module added later fails the build rather than
 * going undocumented (ADR-0017 rule 1).
 *
 * The synthetic cases state both sides as string literals, because the function under test is a
 * pure function over two documents; the last test is the control, and runs the real
 * `settings.gradle.kts` against the real document.
 */
class CompatibilityDocumentTest {

    @Test
    fun `a document naming every published module passes`() {
        assertEquals(
            emptyList<String>(),
            findCompatibilityDocumentViolations(settingsScript, table("superplayer-core", "superplayer-ui"))
        )
    }

    @Test
    fun `a published module with no row fails, naming it`() {
        assertEquals(
            listOf(
                "superplayer-ui is published by settings.gradle.kts and has no row in the " +
                    "stability table."
            ),
            findCompatibilityDocumentViolations(settingsScript, table("superplayer-core"))
        )
    }

    @Test
    fun `a row for a module the build does not publish fails too`() {
        // A module deleted from the build leaves an adopter reading about a coordinate that no
        // longer resolves, which is the same defect from the other side.
        assertEquals(
            listOf(
                "superplayer-ads has a stability row but settings.gradle.kts does not publish it."
            ),
            findCompatibilityDocumentViolations(
                settingsScript,
                table("superplayer-core", "superplayer-ui", "superplayer-ads")
            )
        )
    }

    @Test
    fun `a status outside the vocabulary fails naming the status`() {
        val document = """
            | Module | Status | What you may depend on |
            | --- | --- | --- |
            | `superplayer-core` | **Public** | The facade |
            | `superplayer-ui` | **Stable-ish** | A placeholder |
        """.trimIndent()

        assertEquals(
            listOf(
                "superplayer-ui's status reads \"Stable-ish\", which is not one of: " +
                    "Public, Public (for your tests), Empty"
            ),
            findCompatibilityDocumentViolations(settingsScript, document)
        )
    }

    @Test
    fun `the row order is not the include order, and is not checked`() {
        // settings.gradle.kts is in neither phase order nor alphabetical order, so the document
        // orders by phase and the comparison is a set. See findCompatibilityDocumentViolations.
        assertEquals(
            emptyList<String>(),
            findCompatibilityDocumentViolations(settingsScript, table("superplayer-ui", "superplayer-core"))
        )
    }

    @Test
    fun `a commented-out include is not a module`() {
        val settings = """
            include(":superplayer-core")
            // include(":superplayer-ads")
            include(":superplayer-ui")
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            findCompatibilityDocumentViolations(settings, table("superplayer-core", "superplayer-ui"))
        )
    }

    @Test
    fun `a table that parses to nothing fails loudly rather than passing`() {
        // The failure mode a parser over prose has to be held to: every module reads as missing,
        // rather than the check reading as satisfied.
        val violations = findCompatibilityDocumentViolations(settingsScript, "No table here at all.")

        assertEquals(2, violations.size)
        assertTrue(violations.all { it.endsWith("has no row in the stability table.") })
    }

    @Test
    fun `the tree as it stands satisfies the check`() {
        // The check has to pass on the real repository, or it is a check nobody can land. The test
        // runs from build-logic's directory; the repository is whichever ancestor holds the
        // document — the same way ModulePhaseRuleTest finds it.
        val repoRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "docs/compatibility.md").isFile }
        assertNotNull("no ancestor of ${File(".").canonicalFile} holds docs/compatibility.md", repoRoot)
        repoRoot!!

        assertEquals(
            emptyList<String>(),
            findCompatibilityDocumentViolations(
                File(repoRoot, "settings.gradle.kts").readText(),
                File(repoRoot, "docs/compatibility.md").readText()
            )
        )
    }

    /** Two modules is enough to state every case; the real thirteen are the control's. */
    private val settingsScript = """
        rootProject.name = "superplayer"
        include(":superplayer-core")
        include(":superplayer-ui")
    """.trimIndent()

    private fun table(vararg modules: String): String =
        buildList {
            add("| Module | Status | What you may depend on |")
            add("| --- | --- | --- |")
            modules.forEach { add("| `$it` | **Public** | Something |") }
        }.joinToString("\n")
}
