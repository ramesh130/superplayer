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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The checker behind `verifyModulePhaseRule` (`docs/modules.md`, "The rule").
 *
 * The cases that matter are the four the rule actually has to decide: an earlier-phase
 * dependency, a later-phase one, a same-phase one, and the unscheduled module that has no
 * number to compare at all.
 */
class ModulePhaseRuleTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the phase column is read out of the module table`() {
        assertEquals(
            mapOf(
                "superplayer-core" to 1,
                "superplayer-telemetry" to 2,
                "superplayer-testkit" to 2,
                "superplayer-abr" to 3,
                "superplayer-ui" to null,
                "SuperPlayerCore" to 1,
                "SuperPlayerTelemetry" to 2,
                "SuperPlayerABR" to 3
            ),
            parseModulePhases(modulesDocument().readText())
        )
    }

    @Test
    fun `an earlier-phase dependency is legal`() {
        val violations = check("superplayer-abr" to listOf("superplayer-core"))

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `a later-phase dependency is a violation naming both modules and both phases`() {
        val violations = check("superplayer-telemetry" to listOf("superplayer-abr"))

        assertEquals(
            listOf(
                "superplayer-telemetry (phase 2) depends on superplayer-abr (phase 3): " +
                    "a module may not depend on one from a later phase."
            ),
            violations
        )
    }

    @Test
    fun `a same-phase dependency is legal`() {
        // Deliberate: the rule exists so an earlier phase ships without a later one, and modules
        // sharing a phase ship together. See the tie paragraph in docs/modules.md.
        val violations = check("superplayer-testkit" to listOf("superplayer-telemetry"))

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `nothing may depend on the unscheduled module`() {
        val violations = check("superplayer-abr" to listOf("superplayer-ui"))

        assertEquals(
            listOf(
                "superplayer-abr (phase 3) depends on superplayer-ui (unscheduled): an " +
                    "unscheduled module is not an earlier phase than anything, so nothing may " +
                    "depend on it."
            ),
            violations
        )
    }

    @Test
    fun `the unscheduled module may depend on any scheduled module`() {
        val violations = check("superplayer-ui" to listOf("superplayer-core", "superplayer-abr"))

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `a module the table does not list is a violation rather than a crash`() {
        val violations = check("superplayer-abr" to listOf("superplayer-ads"))

        assertEquals(
            listOf(
                "superplayer-abr depends on superplayer-ads, which docs/modules.md does not list."
            ),
            violations
        )
    }

    @Test
    fun `the named-argument form of project() is checked too`() {
        val directory = temporaryFolder.newFolder("superplayer-telemetry")
        val script = File(directory, "build.gradle.kts").apply {
            writeText("""api(project(path = ":superplayer-abr"))""")
        }

        assertEquals(
            listOf(
                "superplayer-telemetry (phase 2) depends on superplayer-abr (phase 3): " +
                    "a module may not depend on one from a later phase."
            ),
            findModulePhaseViolations(modulesDocument(), listOf(script))
        )
    }

    @Test
    fun `commented-out dependencies are ignored so a build file can document the rule`() {
        val violations = check(
            "superplayer-telemetry" to emptyList(),
            extraLines = listOf("""// Never: api(project(":superplayer-abr"))""")
        )

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `violations are sorted so the failure message is stable`() {
        val violations = check(
            "superplayer-telemetry" to listOf("superplayer-abr"),
            "superplayer-core" to listOf("superplayer-abr")
        )

        assertTrue(violations.size == 2)
        assertEquals(violations.sorted(), violations)
    }

    @Test
    fun `an inward Swift target dependency is legal`() {
        val violations = checkSwift(
            """.target(name: "SuperPlayerABR", dependencies: ["SuperPlayerCore"], path: "ios/Sources/SuperPlayerABR")"""
        )

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `a Swift target depending outward is a violation naming both targets and both phases`() {
        val violations = checkSwift(
            """
            .target(
                name: "SuperPlayerTelemetry",
                dependencies: ["SuperPlayerCore", "SuperPlayerABR"],
                path: "ios/Sources/SuperPlayerTelemetry"
            ),
            """
        )

        assertEquals(
            listOf(
                "SuperPlayerTelemetry (phase 2) depends on SuperPlayerABR (phase 3): " +
                    "a module may not depend on one from a later phase."
            ),
            violations
        )
    }

    @Test
    fun `a Swift test target takes its product's phase`() {
        // Android checks a module's test dependencies too, since testImplementation(project(...))
        // is matched like any other; a test target is the Swift spelling of that source set.
        val violations = checkSwift(
            """.testTarget(name: "SuperPlayerTelemetryTests", dependencies: ["SuperPlayerTelemetry", .target(name: "SuperPlayerABR")])"""
        )

        assertEquals(
            listOf(
                "SuperPlayerTelemetryTests (phase 2) depends on SuperPlayerABR (phase 3): " +
                    "a module may not depend on one from a later phase."
            ),
            violations
        )
    }

    @Test
    fun `a Swift target the table does not list is a violation`() {
        val violations = checkSwift(
            """.target(name: "SuperPlayerAds", dependencies: ["SuperPlayerCore"])"""
        )

        assertEquals(
            listOf("SuperPlayerAds is a target docs/modules.md does not list."),
            violations
        )
    }

    @Test
    fun `another package's product and a commented-out dependency are not this rule's`() {
        val violations = checkSwift(
            """
            .target(
                name: "SuperPlayerCore",
                dependencies: [
                    .product(name: "Collections", package: "swift-collections"),
                    // Never: "SuperPlayerABR",
                    "SuperPlayerCore", // not "SuperPlayerABR"
                ]
            ),
            """
        )

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `the tree as it stands satisfies the rule`() {
        // The check has to pass on the real repository, or it is a check nobody can land.
        // The test runs from build-logic's directory; the repository is whichever ancestor
        // holds the document.
        val repoRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "docs/modules.md").isFile }
        assertNotNull("no ancestor of ${File(".").canonicalFile} holds docs/modules.md", repoRoot)
        repoRoot!!
        val scripts = repoRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("superplayer-") }
            .map { File(it, "build.gradle.kts") }
            .filter { it.isFile }

        assertTrue("no module build scripts found under $repoRoot", scripts.isNotEmpty())
        assertEquals(
            emptyList<String>(),
            findModulePhaseViolations(File(repoRoot, "docs/modules.md"), scripts)
        )
        val manifest = File(repoRoot, "Package.swift")
        assertTrue("no Package.swift under $repoRoot", manifest.isFile)
        assertTrue(
            "Package.swift declares no targets",
            swiftTargetsIn(manifest.readText()).isNotEmpty()
        )
        assertEquals(
            emptyList<String>(),
            findSwiftTargetPhaseViolations(File(repoRoot, "docs/modules.md"), manifest)
        )
    }

    /**
     * Runs the checker over a synthetic tree: one build script per named module, each declaring
     * the given `project(...)` dependencies, against a table with the phases this test fixes.
     */
    private fun check(
        vararg modules: Pair<String, List<String>>,
        extraLines: List<String> = emptyList()
    ): List<String> {
        val scripts = modules.map { (module, dependencies) ->
            val directory = temporaryFolder.newFolder(module)
            File(directory, "build.gradle.kts").apply {
                writeText(
                    buildList {
                        add("dependencies {")
                        dependencies.forEach { add("""    api(project(":$it"))""") }
                        addAll(extraLines)
                        add("}")
                    }.joinToString("\n")
                )
            }
        }
        return findModulePhaseViolations(modulesDocument(), scripts)
    }

    /** Runs the Swift reading over a manifest whose `targets:` array holds [targets]. */
    private fun checkSwift(targets: String): List<String> {
        val manifest = File(temporaryFolder.root, "Package.swift").apply {
            writeText("let package = Package(\n    name: \"SuperPlayer\",\n    targets: [\n$targets\n    ]\n)\n")
        }
        return findSwiftTargetPhaseViolations(modulesDocument(), manifest)
    }

    private fun modulesDocument(): File =
        File(temporaryFolder.root, "modules.md").apply {
            if (exists()) return@apply
            writeText(
                """
                | Module | Phase | Purpose | Depends on |
                | --- | --- | --- | --- |
                | `superplayer-core` | 1 | facade | Media3 only |
                | `superplayer-telemetry` | 2 | collector | core |
                | `superplayer-testkit` | 2 | fakes | core |
                | `superplayer-abr` | 3 | adaptation | core |
                | `superplayer-ui` | — † | surface | core |

                | Product | Phase | Android module | iOS phase |
                | --- | --- | --- | --- |
                | `SuperPlayerCore` | 1 | `superplayer-core` | i1 |
                | `SuperPlayerTelemetry` | 2 | `superplayer-telemetry` | i2 |
                | `SuperPlayerABR` | 3 | `superplayer-abr` | i3 |
                """.trimIndent()
            )
        }
}
