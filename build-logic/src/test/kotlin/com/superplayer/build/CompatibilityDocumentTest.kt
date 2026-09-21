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
                "superplayer-ads has a stability row but settings.gradle.kts does not include it."
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
            ## Which parts are stable?

            | Module | Status | What you may depend on |
            | --- | --- | --- |
            | `superplayer-core` | **Public** | The facade |
            | `superplayer-ui` | **Stable-ish** | A placeholder |
        """.trimIndent()

        assertEquals(
            listOf(
                "superplayer-ui's status reads \"Stable-ish\", which is not one of: " +
                    "Public, Public (for your tests), Empty, Not published"
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
        val document = "## Which parts are stable?\n\nThe table was deleted."
        val violations = findCompatibilityDocumentViolations(settingsScript, document)

        assertEquals(2, violations.size)
        assertTrue(violations.all { it.endsWith("has no row in the stability table.") })
    }

    @Test
    fun `a renamed heading fails naming the check rather than silently passing`() {
        // Scoping to one section is what keeps a row in some other table from counting; the cost
        // is that the heading is load-bearing, and it has to fail loudly when it moves.
        assertEquals(
            listOf(
                "docs/compatibility.md has no \"Which parts are stable?\" section to read a table " +
                    "out of. If the heading was reworded, VerifyCompatibilityDocument in " +
                    "build-logic has to learn the new wording."
            ),
            findCompatibilityDocumentViolations(
                settingsScript,
                table("superplayer-core", "superplayer-ui").substringAfter("\n")
            )
        )
    }

    @Test
    fun `a module row in some other table of the document does not count`() {
        val document = """
            ## Where to look next

            | Module | Status | Note |
            | --- | --- | --- |
            | `superplayer-ui` | **Public** | Mentioned in passing, not a stability row |

            ## Which parts are stable?

            | Module | Status | What you may depend on |
            | --- | --- | --- |
            | `superplayer-core` | **Public** | The facade |
        """.trimIndent()

        assertEquals(
            listOf(
                "superplayer-ui is published by settings.gradle.kts and has no row in the " +
                    "stability table."
            ),
            findCompatibilityDocumentViolations(settingsScript, document)
        )
    }

    @Test
    fun `an include is read whatever the module is called, and however many are on the line`() {
        // Every include in settings.gradle.kts is a published module; reading only the ones whose
        // name begins `superplayer-` would let a differently named one go undocumented in silence.
        val settings = """include(":media-extras", ":superplayer-core")"""

        assertEquals(
            listOf("media-extras is published by settings.gradle.kts and has no row in the stability table."),
            findCompatibilityDocumentViolations(settings, table("superplayer-core"))
        )
    }

    @Test
    fun `an unpublished module needs a row too, and it reads Not published`() {
        assertEquals(
            emptyList<String>(),
            findCompatibilityDocumentViolations(
                settingsWithUnpublished,
                table("superplayer-core", "superplayer-ui") +
                    "\n| `superplayer-moq` | **Not published** | Nothing |"
            )
        )
    }

    @Test
    fun `an unpublished module left out of the table fails, naming it as unpublished`() {
        assertEquals(
            listOf(
                "superplayer-moq is in the build as an unpublished module and has no row in the " +
                    "stability table."
            ),
            findCompatibilityDocumentViolations(
                settingsWithUnpublished,
                table("superplayer-core", "superplayer-ui")
            )
        )
    }

    @Test
    fun `an unpublished module whose row promises an artifact fails`() {
        // The defect this third state exists to prevent, from the direction that costs an adopter
        // something: a row reading Public for a coordinate nobody can resolve.
        assertEquals(
            listOf(
                "superplayer-moq's status reads \"Public\", but settings.gradle.kts declares it " +
                    "unpublished, so its row must read \"Not published\"."
            ),
            findCompatibilityDocumentViolations(
                settingsWithUnpublished,
                table("superplayer-core", "superplayer-ui", "superplayer-moq")
            )
        )
    }

    @Test
    fun `a locally published module reads Not published, exactly as an unpublished one does`() {
        // #353's central claim, stated as a test: the artifact exists in this machine's local
        // repository so that demo/ can resolve it, and from an adopter's side nothing changed.
        // If this ever passes with some other status, #353 has quietly answered #369.
        assertEquals(
            emptyList<String>(),
            findCompatibilityDocumentViolations(
                settingsWithLocallyPublished,
                table("superplayer-core", "superplayer-ui") +
                    "\n| `superplayer-moq` | **Not published** | Nothing |"
            )
        )
    }

    @Test
    fun `a locally published module whose row promises an artifact fails, naming the reason`() {
        assertEquals(
            listOf(
                "superplayer-moq's status reads \"Public\", but settings.gradle.kts declares it " +
                    "locally published, which produces an artifact for this machine alone and " +
                    "none an adopter can resolve, so its row must read \"Not published\"."
            ),
            findCompatibilityDocumentViolations(
                settingsWithLocallyPublished,
                table("superplayer-core", "superplayer-ui", "superplayer-moq")
            )
        )
    }

    @Test
    fun `a locally published module left out of the table fails rather than being overlooked`() {
        // The control that matters for a third state: a module in a list this parser did not learn
        // about would be absent from every set and silently need no row at all.
        assertEquals(
            listOf(
                "superplayer-moq is in the build as an unpublished module and has no row in the " +
                    "stability table."
            ),
            findCompatibilityDocumentViolations(
                settingsWithLocallyPublished,
                table("superplayer-core", "superplayer-ui")
            )
        )
    }

    @Test
    fun `a published module whose row says Not published fails too`() {
        // The other direction, and the control that keeps the rule from reading as "Not published
        // is always allowed": a real artifact described as though there were none.
        assertEquals(
            listOf(
                "superplayer-ui's row reads \"Not published\", but settings.gradle.kts publishes " +
                    "it. Move it into the unpublishedModules or locallyPublishedModules list, or " +
                    "fix the row."
            ),
            findCompatibilityDocumentViolations(
                settingsScript,
                table("superplayer-core") + "\n| `superplayer-ui` | **Not published** | Nothing |"
            )
        )
    }

    @Test
    fun `the two lists are disjoint, so an unpublished module is not also read as published`() {
        // `forEach { include(it) }` rather than include lines is what makes this true by
        // construction; if it ever stopped being true the module would be published *and*
        // required to say it was not, which no row could satisfy.
        assertEquals(setOf("superplayer-core", "superplayer-ui"), publishedModules(settingsWithUnpublished))
        assertEquals(setOf("superplayer-moq"), unpublishedModules(settingsWithUnpublished))
        assertEquals(
            ModulePublication.NOT_PUBLISHED,
            modulePublicationOf(settingsWithUnpublished, "superplayer-moq")
        )
        assertEquals(
            ModulePublication.PUBLISHED,
            modulePublicationOf(settingsWithUnpublished, "superplayer-core")
        )
    }

    @Test
    fun `a module in neither list has no publication, which is what the convention plugin fails on`() {
        assertEquals(null, modulePublicationOf(settingsWithUnpublished, "superplayer-whep"))
    }

    @Test
    fun `a bracket inside a comment does not end the unpublished list`() {
        // The reason a module is unpublished belongs against its entry, and a sentence that
        // explains it will sooner or later contain a bracket. Counting brackets over the code
        // alone is what keeps the entry below such a comment from being missed — and a missed
        // entry is the dangerous direction, because the convention plugin would then publish it.
        val settings = """
            val unpublishedModules = listOf(
                // Built here (see third-party/moq/) rather than resolved.
                ":superplayer-moq",
            )
            unpublishedModules.forEach { include(it) }
        """.trimIndent()

        assertEquals(setOf("superplayer-moq"), unpublishedModules(settings))
    }

    @Test
    fun `a bracket in a trailing comment does not truncate the unpublished list`() {
        // The same hazard one line further in: a reason written beside an entry rather than above
        // it. Truncation is the direction that costs something — a module the parser stops short
        // of is one the convention plugin publishes.
        val settings = """
            val unpublishedModules = listOf(
                ":superplayer-moq", // built here (see third-party/moq/
                ":superplayer-whep",
            )
            unpublishedModules.forEach { include(it) }
        """.trimIndent()

        assertEquals(setOf("superplayer-moq", "superplayer-whep"), unpublishedModules(settings))
    }

    @Test
    fun `an empty unpublished list does not swallow the includes below it`() {
        // The parser reads to the line closing `listOf(`, so a one-line empty list has to stop on
        // its own line. Reading past it would collect every include below and report the whole
        // build as unpublished.
        val settings = """
            val unpublishedModules = listOf()
            unpublishedModules.forEach { include(it) }
            include(":superplayer-core")
            include(":superplayer-ui")
        """.trimIndent()

        assertEquals(emptySet<String>(), unpublishedModules(settings))
        assertEquals(setOf("superplayer-core", "superplayer-ui"), publishedModules(settings))
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

    /**
     * The same two, plus one module the build carries and does not publish — declared the way
     * `settings.gradle.kts` declares it, over several lines with a comment, since that is the
     * shape the parser has to read.
     */
    private val settingsWithUnpublished = """
        rootProject.name = "superplayer"
        include(":superplayer-core")
        include(":superplayer-ui")

        val unpublishedModules = listOf(
            // Links a natively built dependency; #369 tracks publishing.
            ":superplayer-moq",
        )
        unpublishedModules.forEach { include(it) }
    """.trimIndent()

    /**
     * The same shape as [settingsWithUnpublished], with the module in the **other** list — which is
     * the whole of what distinguishes a `LOCAL_ONLY` module from an unpublished one as far as this
     * document is concerned, and the reason both cases below expect the same row (#353).
     */
    private val settingsWithLocallyPublished = """
        rootProject.name = "superplayer"
        include(":superplayer-core")
        include(":superplayer-ui")

        val unpublishedModules = listOf<String>()
        unpublishedModules.forEach { include(it) }

        val locallyPublishedModules = listOf(
            // Publishes to this machine's local repository so demo/ can resolve it; #369.
            ":superplayer-moq",
        )
        locallyPublishedModules.forEach { include(it) }
    """.trimIndent()

    /** Two modules is enough to state every case; the real fourteen are the control's. */
    private val settingsScript = """
        rootProject.name = "superplayer"
        include(":superplayer-core")
        include(":superplayer-ui")
    """.trimIndent()

    private fun table(vararg modules: String): String =
        buildList {
            add("## Which parts are stable?")
            add("")
            add("| Module | Status | What you may depend on |")
            add("| --- | --- | --- |")
            modules.forEach { add("| `$it` | **Public** | Something |") }
        }.joinToString("\n")
}
