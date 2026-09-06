package com.superplayer.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The comparison behind `checkApiSurface`.
 *
 * The rendering itself is `binary-compatibility-validator`'s and is not re-tested here; what is
 * ours, and therefore worth pinning, is when a difference counts as drift and what the contributor
 * is told when it does. A check whose message does not say how to fix it is a check people learn to
 * work around.
 */
class ApiSurfaceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `an identical surface is not drift`() {
        val surface = "public final class com/superplayer/core/SuperPlayer {\n}\n"

        assertNull(describeApiSurfaceDrift(surface, surface, TRACKED, UPDATE_TASK))
    }

    @Test
    fun `a trailing newline is not drift`() {
        // The dump is a set of lines. Whether the file the task wrote ends in one is an artifact of
        // how it was written, and failing CI over it would teach people to distrust the check.
        assertNull(describeApiSurfaceDrift("public fun a ()V\n", "public fun a ()V", TRACKED, UPDATE_TASK))
    }

    @Test
    fun `two empty surfaces agree`() {
        // Eleven of the twelve modules are still placeholders, so this is the common case.
        assertNull(describeApiSurfaceDrift("", "", TRACKED, UPDATE_TASK))
    }

    @Test
    fun `an added declaration is reported as an addition`() {
        val drift = describeApiSurfaceDrift(
            expected = "public fun a ()V",
            actual = "public fun a ()V\npublic fun b ()V",
            trackedFile = TRACKED,
            updateTask = UPDATE_TASK,
        )

        assertTrue(drift!!, drift.contains("+ public fun b ()V"))
        assertTrue(drift, !drift.contains("- public fun"))
    }

    @Test
    fun `a removed declaration is reported as a removal`() {
        val drift = describeApiSurfaceDrift(
            expected = "public fun a ()V\npublic fun b ()V",
            actual = "public fun a ()V",
            trackedFile = TRACKED,
            updateTask = UPDATE_TASK,
        )

        assertTrue(drift!!, drift.contains("- public fun b ()V"))
        assertTrue(drift, !drift.contains("+ public fun"))
    }

    @Test
    fun `a surface that is not tracked yet is drift, and says how to start tracking it`() {
        // A new module's first build. Reported here rather than as a Gradle validation error,
        // which is what a declared-but-absent @InputFile would produce instead.
        val drift = describeApiSurfaceDrift(null, "public fun a ()V", TRACKED, UPDATE_TASK)

        assertTrue(drift!!, drift.contains("does not exist"))
        assertTrue(drift, drift.contains(TRACKED))
        assertTrue(drift, drift.contains(UPDATE_TASK))
    }

    @Test
    fun `an untracked module with no public API is still drift`() {
        // Empty is a claim the repository has to carry, not an absence it can shrug at.
        assertNotNull(describeApiSurfaceDrift(null, "", TRACKED, UPDATE_TASK))
    }

    @Test
    fun `a changed line is reported even when the same text appears elsewhere`() {
        // The dump repeats lines constantly — `}`, blank separators, `public fun <init> ()V`. Set
        // subtraction would cancel the added line against the unrelated copy and print a drift
        // report with an empty body, which tells the contributor nothing.
        val drift = describeApiSurfaceDrift(
            expected = "class A {\npublic fun <init> ()V\n}\nclass B {\n}",
            actual = "class A {\npublic fun <init> ()V\n}\nclass B {\npublic fun <init> ()V\n}",
            trackedFile = TRACKED,
            updateTask = UPDATE_TASK,
        )

        assertTrue(drift!!, drift.contains("+ public fun <init> ()V"))
    }

    @Test
    fun `repeated lines are reported as often as they actually differ`() {
        val drift = describeApiSurfaceDrift(
            expected = "}\n}",
            actual = "}\n}\n}\n}",
            trackedFile = TRACKED,
            updateTask = UPDATE_TASK,
        )

        assertEquals(2, drift!!.lines().count { it.trim() == "+ }" })
    }

    @Test
    fun `the report names the tracked file and the command that regenerates it`() {
        // The failure message is the whole of the documentation a contributor reads at that moment.
        val drift = describeApiSurfaceDrift("", "public fun a ()V", TRACKED, UPDATE_TASK)

        assertTrue(drift!!, drift.contains(TRACKED))
        assertTrue(drift, drift.contains(UPDATE_TASK))
    }

    @Test
    fun `a surface with no class files is empty rather than absent`() {
        // A placeholder module has to produce a tracked file that says "nothing public", which is a
        // claim, rather than no file at all, which is an omission.
        val empty = temporaryFolder.newFolder("classes")

        assertEquals("", renderApiSurface(listOf(empty)))
    }

    @Test
    fun `roots that do not exist are skipped rather than failing`() {
        val missing = File(temporaryFolder.root, "never-created")

        assertEquals("", renderApiSurface(listOf(missing)))
    }

    private companion object {
        const val TRACKED = "superplayer-core/api/superplayer-core.api"
        const val UPDATE_TASK = ":superplayer-core:updateApiSurface"
    }
}
