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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * `docs/http-transport.md` against the obligations it documents.
 *
 * `DoctorDocumentTest`'s model, one phase later and for the same reason: the transport document is
 * written for a reader with no copy of this repository (#315), which is exactly the reader who
 * cannot tell an obligation the document forgot from an obligation this library does not have. The
 * difference is that here the consequence is worse, because the code the obligations bind is code
 * this repository cannot see — a rule that reaches nobody is a rule that is broken once per adopter,
 * which is the argument [HttpTransportConformance] itself is made of.
 *
 * So there *is* something mechanical to check, and this is it. The checks are read off
 * [HttpTransportConformance] by reflection, exactly as `HttpTransportConformanceTest`'s register
 * reads them, so a sixth obligation added to the suite fails here the day it is added with a message
 * saying what to write. And each check's citations are read out of its own **KDoc** rather than
 * listed here, which is `FallbackRungCoverageTest`'s "checked against the source tree": the source is
 * where a citation is authored, and a copy of one in a test is a third place for it to drift.
 *
 * What this does **not** hold is prose. What a section says is review's business, and a test that
 * graded wording would be a test nobody could keep passing. What it holds is the three facts a
 * reader navigates by: that every obligation has a section of its own, in the order the suite runs
 * them, that the section names the ADR rule it comes from, and that it carries the citations the
 * code carries.
 *
 * It also does not hold the worked adapter, and cannot: that example is written over a client this
 * repository takes no dependency on (ADR-0004 rule 2 and its 2026-09-18 addendum), so nothing here
 * compiles it. The document says so in as many words, which is the honest half of this ticket; what
 * an adopter runs instead is [HttpTransportConformance] against their own implementation.
 */
class TransportDocumentTest {

    @Test
    fun everyObligationHasItsOwnSectionInTheTransportDocument() {
        // The suite is the authority, and the order is asserted too: the document's checklist runs
        // in the order `verifyAll` runs the checks, which is the order ADR-0016 numbers the rules,
        // so an obligation appended to one belongs in the same place in the other.
        assertWithMessage(
            "Every check on HttpTransportConformance needs a `#### … `<check>`` section in " +
                "$DOCUMENT saying what the obligation is, what it costs to get wrong, and its " +
                "citation — in the order verifyAll runs them. Add the section in the change that " +
                "adds the check: an adopter outside this repository has no other way to learn what " +
                "the obligation is before their viewers find out.",
        ).that(documentedChecks()).containsExactlyElementsIn(checksInOrder()).inOrder()
    }

    @Test
    fun everyObligationsOwnSectionNamesItsRuleAndCarriesTheCitationsTheCodeCarries() {
        val sections = sections()
        checksInOrder().forEach { check ->
            val section = checkNotNull(sections[check]) { "$check has no section in $DOCUMENT" }
            // The rule number is how a reader crosses from this document to the record, and from a
            // conformance failure — whose message leads with it — to either.
            assertWithMessage("$check's section in $DOCUMENT must name the ADR-0016 rule it comes from")
                .that(section)
                .contains("ADR-0016 rule ")
            // One citation, in two places, and neither is allowed to drift: a document citing a
            // clause the code does not is a reader sent to the wrong paragraph of the wrong
            // specification. Read per section rather than over the file, because three of the five
            // cite RFC 9110.
            citationsOf(check).forEach { citation ->
                assertWithMessage(
                    "$check's own section in $DOCUMENT must carry the citation its KDoc carries, " +
                        "verbatim: \"$citation\". A sibling section citing the same specification " +
                        "does not count.",
                ).that(section).contains(citation)
            }
        }
    }

    @Test
    fun theDocumentIsIndexedForTheReaderItIsWrittenFor() {
        // A document nothing links to is a document nobody outside this repository finds. The index
        // is the one place that is true of every document here, so it is the one place this is
        // worth asserting.
        assertThat(File(INDEX).readText()).contains("http-transport.md")
    }

    /** The checks, in the order [HttpTransportConformance.verifyAll] runs them. */
    private fun checksInOrder(): List<String> {
        // Public and non-synthetic, and `verifyAll` excluded by name, because it is the five run in
        // order rather than a sixth obligation — `HttpTransportConformanceTest`'s reading exactly.
        // Declared order is not source order on every JVM, so the sequence comes from `verifyAll`'s
        // own body as the document's checklist claims it does, and the *set* is what reflection
        // holds.
        val declared = HttpTransportConformance::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .filter { it.startsWith("verify") && it != "verifyAll" }
            .toSet()
        val ordered = source().readText()
            .substringAfter("public fun verifyAll() {")
            .substringBefore("}")
            .lines()
            .mapNotNull { CALL.find(it)?.groupValues?.get(1) }
        assertWithMessage(
            "verifyAll must run every check on HttpTransportConformance, since it is what an " +
                "adopter calls and what this document's checklist is ordered by",
        ).that(ordered.toSet()).isEqualTo(declared)
        return ordered
    }

    /** The checks the document gives a section of their own, in the order it gives them. */
    private fun documentedChecks(): List<String> = document().readLines()
        .mapNotNull { SECTION.matchEntire(it.trim())?.groupValues?.get(1) }

    /**
     * The document's obligation sections, keyed by the check each is headed with.
     *
     * A section runs from its own heading to the next heading of any level, which is what makes the
     * citation assertion above a reading of one section rather than of the file.
     */
    private fun sections(): Map<String, String> {
        val sections = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        document().readLines().forEach { line ->
            val check = SECTION.matchEntire(line.trim())?.groupValues?.get(1)
            when {
                check != null -> current = StringBuilder().also { sections[check] = it }
                line.trimStart().startsWith("#") -> current = null
                else -> current?.appendLine(line)
            }
        }
        return sections.mapValues { (_, text) -> text.toString() }
    }

    /**
     * The specification clauses the check's own KDoc cites, which may be none: rule 10's obligation is
     * about a client's behaviour on a cancellation and cites no clause, and requiring one of every
     * check would be this test inventing an obligation of its own.
     */
    private fun citationsOf(name: String): List<String> {
        val lines = source().readLines()
        val declaration = lines.indexOfFirst { it.contains("public fun $name(") }
        check(declaration > 0) { "No `public fun $name(` in ${source()}" }
        val opening = (declaration downTo 0).first { lines[it].trim() == "/**" }
        return CITATION.findAll(lines.subList(opening, declaration).joinToString("\n"))
            .map { it.value }
            .toList()
    }

    private fun document(): File = File(DOCUMENT).also {
        check(it.isFile) { "No document at ${it.absolutePath}" }
    }

    private fun source(): File = File(SOURCE).also {
        check(it.isFile) { "No source at ${it.absolutePath}" }
    }

    private companion object {

        /** Relative to this module's directory, which is where Gradle runs a test from. */
        const val DOCUMENT = "../docs/http-transport.md"

        const val INDEX = "../docs/README.md"

        const val SOURCE = "src/main/kotlin/com/superplayer/testkit/HttpTransportConformance.kt"

        /**
         * One obligation's heading: a fourth-level heading ending in the check that scores it,
         * backticked. The prose before it is the obligation in a reader's words rather than a
         * method name, which is the whole reason the heading carries both.
         */
        val SECTION = Regex("""#### .*`(verify\w+)`""")

        /** A check called from `verifyAll`'s body. */
        val CALL = Regex("""^\s*(verify\w+)\(\)\s*$""")

        /**
         * A specification clause as this repository spells one. The section number is matched
         * without a trailing separator so that a citation ending a sentence is the clause and not
         * the full stop.
         */
        val CITATION = Regex("""RFC \d+ §\d+(?:\.\d+)*""")
    }
}
