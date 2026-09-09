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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The comparison behind `verifyLicenseHeader`.
 *
 * The check exists because Spotless will stamp whatever the header file says onto every source
 * file without ever reading `LICENSE`. These are the cases that matter: the real pair has to
 * pass, a header that names a different licence has to fail, and the differences in layout
 * between an indented APPENDIX and an asterisk-prefixed comment must not be mistaken for a
 * difference in terms.
 */
class LicenseHeaderTest {

    @Test
    fun `the repository's real header and LICENSE agree`() {
        // The one case that is not a fixture. Gradle runs these tests with `build-logic` as the
        // working directory, so the repository root is one level up. If this fails, the header
        // Spotless is stamping onto every source file is not the licence the project ships under.
        val header = File("../config/license-header.txt")
        val license = File("../LICENSE")
        assertTrue("expected ${header.absolutePath} to exist", header.isFile)
        assertTrue("expected ${license.absolutePath} to exist", license.isFile)

        assertEquals(
            emptyList<String>(),
            findLicenseHeaderMismatches(header.readText(), license.readText())
        )
    }

    @Test
    fun `a header matching the appendix boilerplate passes`() {
        assertEquals(emptyList<String>(), findLicenseHeaderMismatches(APACHE_HEADER, APACHE_LICENSE))
    }

    @Test
    fun `indentation and comment syntax are not differences in terms`() {
        // The APPENDIX indents by three columns; the header prefixes each line with an asterisk.
        // Only the wording is compared.
        val reindented = APACHE_HEADER.replace(" * ", " *      ")

        assertEquals(emptyList<String>(), findLicenseHeaderMismatches(reindented, APACHE_LICENSE))
    }

    @Test
    fun `a header naming a different licence is reported with both texts`() {
        val mit = APACHE_HEADER.replace(
            """Licensed under the Apache License, Version 2.0 (the "License");""",
            "Licensed under the MIT License."
        )

        val failures = findLicenseHeaderMismatches(mit, APACHE_LICENSE)

        assertEquals(1, failures.size)
        assertTrue(failures.single(), failures.single().startsWith("line 1 of the notice:"))
        assertTrue(failures.single(), failures.single().contains("Licensed under the MIT License."))
    }

    @Test
    fun `a header missing its copyright line is reported`() {
        val withoutCopyright = APACHE_HEADER.lines()
            .filterNot { it.contains("Copyright") }
            .joinToString("\n")

        assertEquals(
            listOf("the header carries no `Copyright <year> <holder>` line"),
            findLicenseHeaderMismatches(withoutCopyright, APACHE_LICENSE)
        )
    }

    @Test
    fun `a header that never filled in the placeholder is reported`() {
        val unfilled = APACHE_HEADER.replace(
            "Copyright 2026 The SuperPlayer Authors",
            "Copyright [yyyy] [name of copyright owner]"
        )

        val failures = findLicenseHeaderMismatches(unfilled, APACHE_LICENSE)

        assertEquals(
            listOf(
                "the header carries no `Copyright <year> <holder>` line",
                "the header still carries the LICENSE appendix's unfilled copyright placeholder"
            ),
            failures
        )
    }

    @Test
    fun `a truncated header is reported at the line where it stops`() {
        val truncated = APACHE_HEADER.lines()
            .takeWhile { !it.contains("Unless required by applicable law") }
            .joinToString("\n")

        val failures = findLicenseHeaderMismatches(truncated, APACHE_LICENSE)

        assertTrue(failures.toString(), failures.any { it.contains("found \"<nothing>\"") })
    }

    @Test
    fun `a header that says more than the licence does is reported`() {
        val extra = APACHE_HEADER.replace(
            " */",
            " * All rights reserved.\n */"
        )

        assertEquals(
            listOf("""the header says more than the licence does: "All rights reserved.""""),
            findLicenseHeaderMismatches(extra, APACHE_LICENSE)
        )
    }

    @Test
    fun `a LICENSE with no Apache appendix cannot be compared against`() {
        assertEquals(
            listOf("LICENSE has no Apache-2.0 APPENDIX boilerplate to compare the header against"),
            findLicenseHeaderMismatches(APACHE_HEADER, "The MIT License\n\nPermission is hereby granted...")
        )
    }

    private companion object {

        /** Exactly the text of `config/license-header.txt`. */
        val APACHE_HEADER = """
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
        """.trimIndent()

        /** The tail of `LICENSE`: the APPENDIX that says what to put in each file. */
        val APACHE_LICENSE = """
            END OF TERMS AND CONDITIONS

               APPENDIX: How to apply the Apache License to your work.

                  To apply the Apache License to your work, attach the following
                  boilerplate notice, with the fields enclosed by brackets "[]"
                  replaced with your own identifying information.

               Copyright [yyyy] [name of copyright owner]

               Licensed under the Apache License, Version 2.0 (the "License");
               you may not use this file except in compliance with the License.
               You may obtain a copy of the License at

                   http://www.apache.org/licenses/LICENSE-2.0

               Unless required by applicable law or agreed to in writing, software
               distributed under the License is distributed on an "AS IS" BASIS,
               WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
               See the License for the specific language governing permissions and
               limitations under the License.
        """.trimIndent()
    }
}
