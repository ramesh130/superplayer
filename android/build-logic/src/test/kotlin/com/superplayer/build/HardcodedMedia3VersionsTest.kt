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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The scanner behind `verifyNoHardcodedMedia3Versions` (ADR-0001 rule 3).
 *
 * These are the cases CI relies on: a real pin has to be caught, and the things that only
 * look like a pin — catalog accessors, prose in comments — must not produce a false failure,
 * or the rule gets disabled the first time it cries wolf.
 */
class HardcodedMedia3VersionsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `catalog accessors are not violations`() {
        val script = script(
            "dependencies {",
            "    api(libs.media3.exoplayer)",
            "    implementation(libs.media3.ui)",
            "}"
        )

        assertEquals(emptyList<String>(), findHardcodedMedia3Versions(listOf(script), root))
    }

    @Test
    fun `a pinned Media3 coordinate is reported with its path and line`() {
        val script = script(
            "dependencies {",
            """    api("androidx.media3:media3-exoplayer:1.11.0")""",
            "}"
        )

        assertEquals(
            listOf("""build.gradle.kts:2: api("androidx.media3:media3-exoplayer:1.11.0")"""),
            findHardcodedMedia3Versions(listOf(script), root)
        )
    }

    @Test
    fun `commented-out pins are ignored so the rule can document itself`() {
        val script = script(
            """// Never do this: api("androidx.media3:media3-common:1.11.0")""",
            """ * api("androidx.media3:media3-common:1.11.0")""",
            """# api("androidx.media3:media3-common:1.11.0")"""
        )

        assertEquals(emptyList<String>(), findHardcodedMedia3Versions(listOf(script), root))
    }

    @Test
    fun `a Media3 coordinate without a version is not a pin`() {
        // A bare module coordinate resolves its version from the catalog's constraints,
        // which is exactly what the rule asks for.
        val script = script("""    api(platform("androidx.media3:media3-bom"))""")

        assertEquals(emptyList<String>(), findHardcodedMedia3Versions(listOf(script), root))
    }

    @Test
    fun `violations across files are sorted so the failure message is stable`() {
        val pin = """implementation("androidx.media3:media3-ui:1.11.0")"""
        val first = scriptNamed("a.gradle.kts", pin)
        val second = scriptNamed("b.gradle.kts", pin)

        assertEquals(
            listOf("a.gradle.kts:1: $pin", "b.gradle.kts:1: $pin"),
            findHardcodedMedia3Versions(listOf(second, first), root)
        )
    }

    @Test
    fun `directories among the inputs are skipped`() {
        val directory = temporaryFolder.newFolder("nested.gradle.kts")

        assertEquals(emptyList<String>(), findHardcodedMedia3Versions(listOf(directory), root))
    }

    private val root: File get() = temporaryFolder.root

    private fun script(vararg lines: String): File = scriptNamed("build.gradle.kts", *lines)

    private fun scriptNamed(name: String, vararg lines: String): File =
        temporaryFolder.newFile(name).apply { writeText(lines.joinToString("\n")) }
}
