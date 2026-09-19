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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version grammar ADR-0017 admits, and the precedence a release has to order two of them by.
 *
 * Both directions are stated: a check that rejected every version would pass its own negative
 * tests and fail the repository, and the versions this library will actually publish under have to
 * be readable.
 */
class SemanticVersionTest {

    @Test
    fun `a release and a snapshot of three numbers are both versions`() {
        assertEquals(SemanticVersion(0, 1, 0, isSnapshot = false), SemanticVersion.parse("0.1.0"))
        assertEquals(
            SemanticVersion(0, 1, 0, isSnapshot = true),
            SemanticVersion.parse("0.1.0-SNAPSHOT")
        )
        assertEquals(SemanticVersion(12, 4, 37, isSnapshot = false), SemanticVersion.parse("12.4.37"))
    }

    @Test
    fun `the versions this repository has carried are readable`() {
        // The catalog's own entry, which every module publishes under.
        assertEquals("0.1.0-SNAPSHOT", SemanticVersion.parse("0.1.0-SNAPSHOT").toString())
    }

    @Test
    fun `a version of the wrong shape is not a version`() {
        assertNull(SemanticVersion.parse("0.1"))
        assertNull(SemanticVersion.parse("0.1.0.1"))
        assertNull(SemanticVersion.parse("v0.1.0"))
        assertNull(SemanticVersion.parse(""))
    }

    @Test
    fun `surrounding whitespace is not a malformed version`() {
        // A catalog entry read with a stray space around it is a typo of no consequence, where
        // every other rejection here is a version that would publish wrong.
        assertEquals(SemanticVersion.parse("0.1.0"), SemanticVersion.parse(" 0.1.0 "))
    }

    @Test
    fun `a leading zero is refused rather than read as the number it resembles`() {
        // spec: Semantic Versioning 2.0.0 item 2 — no leading zeroes. `01.0.0` read as 1.0.0 would
        // publish a major nobody typed.
        assertNull(SemanticVersion.parse("01.0.0"))
        assertNull(SemanticVersion.parse("1.02.0"))
        assertEquals(SemanticVersion(0, 0, 0, isSnapshot = false), SemanticVersion.parse("0.0.0"))
    }

    @Test
    fun `a suffix other than SNAPSHOT is refused`() {
        // ADR-0017 rule 8 gives `-SNAPSHOT` a meaning; no other pre-release identifier has one here.
        assertNull(SemanticVersion.parse("0.1.0-alpha01"))
        assertNull(SemanticVersion.parse("0.1.0-snapshot"))
        assertNull(SemanticVersion.parse("0.1.0+build.7"))
    }

    @Test
    fun `precedence runs major then minor then patch`() {
        assertTrue(SemanticVersion.parse("0.9.9")!! < SemanticVersion.parse("1.0.0")!!)
        assertTrue(SemanticVersion.parse("1.1.0")!! > SemanticVersion.parse("1.0.9")!!)
        assertTrue(SemanticVersion.parse("1.0.2")!! > SemanticVersion.parse("1.0.1")!!)
    }

    @Test
    fun `a snapshot ranks below the release of the same numbers`() {
        // spec: Semantic Versioning 2.0.0 item 11 — a pre-release version has lower precedence
        // than the associated normal version.
        assertTrue(SemanticVersion.parse("0.2.0-SNAPSHOT")!! < SemanticVersion.parse("0.2.0")!!)
        assertTrue(SemanticVersion.parse("0.2.0-SNAPSHOT")!! > SemanticVersion.parse("0.1.9")!!)
    }

    @Test
    fun `the release a snapshot names is the suffix removed`() {
        assertEquals(
            SemanticVersion.parse("0.2.0"),
            SemanticVersion.parse("0.2.0-SNAPSHOT")!!.released
        )
        assertEquals(SemanticVersion.parse("0.2.0"), SemanticVersion.parse("0.2.0")!!.released)
    }
}
