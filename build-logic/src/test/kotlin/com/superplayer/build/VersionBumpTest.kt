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
 * The comparison behind `verifyVersionBump`: the surface recorded at the last release against the
 * surface tracked today, judged by the version the catalog proposes.
 *
 * Every case is two surface texts and two version strings, which is the whole of the input — the
 * task reads files and this function reads none, so a removal, a widened supertype and a
 * first release are all stateable here without a module, a build or a tree.
 *
 * The control that carries the most weight is [`a reordered surface under a patch passes`]: the
 * dump is sorted and a textual diff of it would call a re-ordering a breakage, so a check that
 * passed every other case and failed that one would be diffing text rather than classifying
 * declarations.
 */
class VersionBumpTest {

    @Test
    fun `the repository's own state - nothing recorded - passes, because nothing has been released`() {
        assertNull(
            findVersionBumpMismatch(
                recordedVersion = null,
                recorded = emptyMap(),
                current = mapOf("superplayer-core" to CORE),
                catalog = catalog("0.1.0-SNAPSHOT")
            )
        )
    }

    @Test
    fun `an unchanged surface under a patch passes`() {
        assertNull(bump(from = "1.2.3", to = "1.2.4", recorded = CORE, current = CORE))
    }

    @Test
    fun `a reordered surface under a patch passes`() {
        val reordered = klass(
            "public final class com/superplayer/core/MediaRequest",
            "public final fun getTitle ()Ljava/lang/String;",
            "public final fun getContentId ()Ljava/lang/String;"
        )

        assertNull(bump(from = "1.2.3", to = "1.2.4", recorded = CORE, current = reordered))
    }

    @Test
    fun `an addition under a patch fails, naming the module, the declaration and the bump`() {
        val added = klass(
            "public final class com/superplayer/core/MediaRequest",
            "public final fun getContentId ()Ljava/lang/String;",
            "public final fun getSubtitle ()Ljava/lang/String;",
            "public final fun getTitle ()Ljava/lang/String;"
        )

        val mismatch = bump(from = "1.2.3", to = "1.2.4", recorded = CORE, current = added)

        assertTrue(mismatch!!, mismatch.contains("superplayer-core"))
        assertTrue(mismatch, mismatch.contains("getSubtitle"))
        assertTrue(mismatch, mismatch.contains("1.3.0"))
    }

    @Test
    fun `an addition under a minor passes`() {
        val added = klass(
            "public final class com/superplayer/core/MediaRequest",
            "public final fun getContentId ()Ljava/lang/String;",
            "public final fun getSubtitle ()Ljava/lang/String;",
            "public final fun getTitle ()Ljava/lang/String;"
        )

        assertNull(bump(from = "1.2.3", to = "1.3.0", recorded = CORE, current = added))
    }

    @Test
    fun `a removal under a minor fails once the major is non-zero`() {
        val mismatch = bump(from = "1.2.3", to = "1.3.0", recorded = CORE, current = WITHOUT_TITLE)

        assertTrue(mismatch!!, mismatch.contains("superplayer-core"))
        assertTrue(mismatch, mismatch.contains("getTitle"))
        assertTrue(mismatch, mismatch.contains("2.0.0"))
    }

    @Test
    fun `a removal under a major passes`() {
        assertNull(bump(from = "1.2.3", to = "2.0.0", recorded = CORE, current = WITHOUT_TITLE))
    }

    @Test
    fun `a removal under a minor passes below 1_0_0, because that is what ADR-0017 rule 7 says`() {
        assertNull(bump(from = "0.2.3", to = "0.3.0", recorded = CORE, current = WITHOUT_TITLE))
    }

    @Test
    fun `a removal under a patch fails below 1_0_0, naming the minor rather than a major`() {
        val mismatch = bump(from = "0.2.3", to = "0.2.4", recorded = CORE, current = WITHOUT_TITLE)

        assertTrue(mismatch!!, mismatch.contains("getTitle"))
        assertTrue(mismatch, mismatch.contains("0.3.0"))
    }

    @Test
    fun `a changed parameter type is breaking rather than an addition`() {
        val widened = klass(
            "public final class com/superplayer/core/MediaRequest",
            "public final fun getContentId ()Ljava/lang/String;",
            "public final fun getTitle ()Ljava/lang/CharSequence;"
        )

        val mismatch = bump(from = "1.2.3", to = "1.3.0", recorded = CORE, current = widened)

        assertTrue(mismatch!!, mismatch.contains("getTitle"))
        assertTrue(mismatch, mismatch.contains("2.0.0"))
    }

    @Test
    fun `a supertype added to a class is breaking`() {
        val withSupertype = CORE.replace(
            "public final class com/superplayer/core/MediaRequest {",
            "public final class com/superplayer/core/MediaRequest : java/io/Serializable {"
        )

        val mismatch = bump(from = "1.2.3", to = "1.3.0", recorded = CORE, current = withSupertype)

        assertTrue(mismatch!!, mismatch.contains("MediaRequest"))
        assertTrue(mismatch, mismatch.contains("2.0.0"))
    }

    @Test
    fun `a member moved to a companion is breaking rather than unchanged`() {
        val moved = klass(
            "public final class com/superplayer/core/MediaRequest",
            "public final fun getContentId ()Ljava/lang/String;"
        ) + klass(
            "public final class com/superplayer/core/MediaRequest\$Companion",
            "public final fun getTitle ()Ljava/lang/String;"
        )

        val mismatch = bump(from = "1.2.3", to = "1.3.0", recorded = CORE, current = moved)

        assertTrue(mismatch!!, mismatch.contains("getTitle"))
        assertTrue(mismatch, mismatch.contains("2.0.0"))
    }

    @Test
    fun `a module that lost its whole surface is breaking`() {
        val mismatch = findVersionBumpMismatch(
            recordedVersion = "1.2.3",
            recorded = mapOf("superplayer-core" to CORE, "superplayer-tv" to TV),
            current = mapOf("superplayer-core" to CORE),
            catalog = catalog("1.3.0")
        )

        assertTrue(mismatch!!, mismatch.contains("superplayer-tv"))
        assertTrue(mismatch, mismatch.contains("2.0.0"))
    }

    @Test
    fun `a new module is an addition rather than a breakage`() {
        assertNull(
            findVersionBumpMismatch(
                recordedVersion = "1.2.3",
                recorded = mapOf("superplayer-core" to CORE),
                current = mapOf("superplayer-core" to CORE, "superplayer-tv" to TV),
                catalog = catalog("1.3.0")
            )
        )
    }

    @Test
    fun `a version that did not move at all fails`() {
        val mismatch = bump(from = "1.2.3", to = "1.2.3", recorded = CORE, current = CORE)

        assertTrue(mismatch!!, mismatch.contains("1.2.3"))
        assertTrue(mismatch, mismatch.contains("api/released/$RECORDED_VERSION_FILE"))
    }

    @Test
    fun `a snapshot is judged on the release it will become`() {
        assertNull(bump(from = "1.2.3", to = "1.3.0-SNAPSHOT", recorded = CORE, current = CORE))

        // The suffix removed is the release already recorded, so this one has not moved at all.
        assertNotNull(bump(from = "1.2.3", to = "1.2.3-SNAPSHOT", recorded = CORE, current = CORE))
    }

    @Test
    fun `a record with a version but no surfaces fails rather than reading as a first release`() {
        val mismatch = findVersionBumpMismatch(
            recordedVersion = "1.2.3",
            recorded = emptyMap(),
            current = mapOf("superplayer-core" to CORE),
            catalog = catalog("1.3.0")
        )

        assertTrue(mismatch!!, mismatch.contains("1.2.3"))
        assertTrue(mismatch, mismatch.contains(RECORDED_SURFACE_DIRECTORY))
    }

    @Test
    fun `surfaces with no version recorded beside them fail rather than reading as a first release`() {
        val mismatch = findVersionBumpMismatch(
            recordedVersion = null,
            recorded = mapOf("superplayer-core" to CORE),
            current = mapOf("superplayer-core" to CORE),
            catalog = catalog("1.3.0")
        )

        assertTrue(mismatch!!, mismatch.contains(RECORDED_VERSION_FILE))
    }

    @Test
    fun `a recorded version that is not a version fails`() {
        val mismatch = findVersionBumpMismatch(
            recordedVersion = "one point two",
            recorded = mapOf("superplayer-core" to CORE),
            current = mapOf("superplayer-core" to CORE),
            catalog = catalog("1.3.0")
        )

        assertTrue(mismatch!!, mismatch.contains("one point two"))
    }

    @Test
    fun `a recorded snapshot fails, because a snapshot is not a release`() {
        val mismatch = findVersionBumpMismatch(
            recordedVersion = "1.2.3-SNAPSHOT",
            recorded = mapOf("superplayer-core" to CORE),
            current = mapOf("superplayer-core" to CORE),
            catalog = catalog("1.3.0")
        )

        assertTrue(mismatch!!, mismatch.contains("1.2.3-SNAPSHOT"))
    }

    @Test
    fun `the record the release command writes is the tracked surfaces and the version`() {
        val record = releasedApiSurfaceRecord(
            tracked = mapOf("superplayer-core" to CORE, "superplayer-tv" to TV),
            version = "1.3.0"
        )

        assertEquals(CORE, record["superplayer-core.api"])
        assertEquals(TV, record["superplayer-tv.api"])
        assertEquals("1.3.0\n", record[RECORDED_VERSION_FILE])
        assertNull(
            findVersionBumpMismatch(
                recordedVersion = "1.3.0",
                recorded = mapOf("superplayer-core" to CORE, "superplayer-tv" to TV),
                current = mapOf("superplayer-core" to CORE, "superplayer-tv" to TV),
                catalog = catalog("1.3.1")
            )
        )
    }

    @Test
    fun `recording deletes the surface of a module that stopped publishing, and nothing else`() {
        val record = releasedApiSurfaceRecord(mapOf("superplayer-core" to CORE), version = "1.3.0")

        val stale = staleRecordedSurfaces(
            present = listOf("superplayer-core.api", "superplayer-gone.api", "README.md", RECORDED_VERSION_FILE),
            record = record
        )

        assertEquals(listOf("superplayer-gone.api"), stale)
    }

    private fun bump(from: String, to: String, recorded: String, current: String): String? =
        findVersionBumpMismatch(
            recordedVersion = from,
            recorded = mapOf("superplayer-core" to recorded),
            current = mapOf("superplayer-core" to current),
            catalog = catalog(to)
        )

    private fun catalog(version: String): String =
        "[versions]\nsuperplayer = \"$version\"\nmedia3 = \"1.11.0\"\n"

    private companion object {
        /** The dump's own shape: a header at column zero, tab-indented members, a closing brace. */
        fun klass(header: String, vararg members: String): String = buildString {
            appendLine("$header {")
            members.forEach { appendLine("\t$it") }
            appendLine("}")
            appendLine()
        }

        val CORE = klass(
            "public final class com/superplayer/core/MediaRequest",
            "public final fun getContentId ()Ljava/lang/String;",
            "public final fun getTitle ()Ljava/lang/String;"
        )

        val WITHOUT_TITLE = klass(
            "public final class com/superplayer/core/MediaRequest",
            "public final fun getContentId ()Ljava/lang/String;"
        )

        val TV = klass(
            "public final class com/superplayer/tv/TvOutput",
            "public static final fun standard (Landroid/content/Context;)Lcom/superplayer/core/PlaybackOutput;"
        )
    }
}
