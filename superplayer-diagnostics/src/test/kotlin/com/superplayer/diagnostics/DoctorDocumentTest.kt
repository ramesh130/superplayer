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

package com.superplayer.diagnostics

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * `docs/media-source-doctor.md` against the vocabulary it documents.
 *
 * The doctor's manual is written for a reader with no copy of this repository (#295), which is
 * exactly the reader who cannot tell a defect the document forgot from a defect the doctor cannot
 * name. So the pathology list is *checked* against [Pathology] rather than hand-assembled: a
 * twenty-first defect added to the enum fails here the day it is added, with a message saying what
 * to write.
 *
 * `CorpusRegisterTest`'s model, narrowed to what a document can be held to. It asserts nothing about
 * playback and nothing about prose — what a section *says* is review's business, and a test that
 * graded wording would be a test nobody could keep passing. What it holds is the two facts a reader
 * navigates by: that every defect has a section of its own, in the enum's own order, and that *that
 * section* carries the citation the code carries.
 *
 * The citation is read **per section** rather than over the whole document, which is
 * `CorpusRegisterTest`'s "exactly rather than contains" taken at its word: four pathologies cite
 * RFC 8216 §4.3.4.2 and two cite ISO/IEC 23009-1 §5.3.1.2, so a document-wide search would let a
 * section lose its own citation and still pass on a sibling's.
 *
 * What it does **not** hold is the corpus: a pathology the corpus carries and the doctor cannot yet
 * name has no entry here to fail on, and is `CorpusRegisterTest`'s to catch.
 */
class DoctorDocumentTest {

    @Test
    fun everyPathologyHasItsOwnSectionInTheDoctorsDocument() {
        // The enum is the authority, and the order is asserted too: the document's sections are
        // grouped by protocol because the enum is, and a defect appended to one group belongs in
        // that group's prose rather than at the end of the file.
        assertWithMessage(
            "Every Pathology needs a `#### `<id>`` section in $DOCUMENT saying what it means, what " +
                "causes it, what to change and its citation — in the enum's own order. Add the " +
                "section in the change that adds the pathology: a reader outside this repository has " +
                "no other way to learn what a finding means.",
        ).that(documentedIds()).containsExactlyElementsIn(Pathology.entries.map { it.id }).inOrder()
    }

    @Test
    fun everyPathologysOwnSectionCarriesTheCitationTheCodeCarries() {
        // One citation, in two places, and neither is allowed to drift: a document citing a clause
        // the code does not is a reader sent to the wrong paragraph of the wrong specification.
        val sections = sections()
        Pathology.entries.forEach { pathology ->
            val section = sections[pathology.id]
            assertWithMessage("${pathology.id} has no section in $DOCUMENT").that(section).isNotNull()
            assertWithMessage(
                "${pathology.id}'s own section in $DOCUMENT must carry its `specCitation`, verbatim: " +
                    "\"${pathology.specCitation}\". A sibling section citing the same clause does not " +
                    "count — several pathologies share one.",
            ).that(section).contains(pathology.specCitation)
        }
    }

    /** The ids the document gives a section of their own, in the order it gives them. */
    private fun documentedIds(): List<String> = document().readLines()
        .mapNotNull { SECTION.matchEntire(it.trim())?.groupValues?.get(1) }

    /**
     * The document's defect sections, keyed by the id each is headed with.
     *
     * A section runs from its own heading to the next heading of any level, which is what makes the
     * citation assertion above a reading of one section rather than of the file.
     */
    private fun sections(): Map<String, String> {
        val sections = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        document().readLines().forEach { line ->
            val id = SECTION.matchEntire(line.trim())?.groupValues?.get(1)
            when {
                id != null -> current = StringBuilder().also { sections[id] = it }
                line.trimStart().startsWith("#") -> current = null
                else -> current?.appendLine(line)
            }
        }
        return sections.mapValues { (_, text) -> text.toString() }
    }

    private fun document(): File = File(DOCUMENT).also {
        check(it.isFile) { "No document at ${it.absolutePath}" }
    }

    private companion object {

        /** Relative to this module's directory, which is where Gradle runs a test from. */
        const val DOCUMENT = "../docs/media-source-doctor.md"

        /** One defect's heading: a fourth-level heading whose whole text is the defect's id. */
        val SECTION = Regex("""#### `([a-z0-9-]+)`""")
    }
}
