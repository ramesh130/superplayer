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
 * navigates by: that every defect has a section of its own, in the enum's own order, and that the
 * citation beside it is the citation the code carries.
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
    fun everyPathologyIsDocumentedWithTheCitationTheCodeCarries() {
        // One citation, in two places, and neither is allowed to drift: a document citing a clause
        // the code does not is a reader sent to the wrong paragraph of the wrong specification.
        val text = document().readText()
        Pathology.entries.forEach { pathology ->
            assertWithMessage(
                "${pathology.id}'s section in $DOCUMENT must carry its own `specCitation`, verbatim: " +
                    "\"${pathology.specCitation}\".",
            ).that(text).contains(pathology.specCitation)
        }
    }

    /** The ids the document gives a section of their own, in the order it gives them. */
    private fun documentedIds(): List<String> = document().readLines()
        .mapNotNull { SECTION.matchEntire(it.trim())?.groupValues?.get(1) }

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
