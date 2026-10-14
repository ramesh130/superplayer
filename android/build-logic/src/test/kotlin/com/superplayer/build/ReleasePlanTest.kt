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

/**
 * The decision behind `./gradlew release`: every refusal but one, and the files a cut writes.
 *
 * The exception is a failing `check`, which is a task dependency rather than a branch — a line in
 * `superplayer.verification.gradle.kts` that `ReleaseWiringTest` reads off the script.
 *
 * Every input here is a string literal, because the whole of the judgement is text — a catalog, a
 * changelog, two sets of surfaces, `git status --porcelain` and a tag list. Nothing writes a file,
 * publishes an artefact or runs `git`; `CutRelease` is what does that, and it does nothing this
 * function has not already decided.
 *
 * Each refusal is asserted on the **remedy** it names rather than on its first clause, because the
 * remedy is the reason for writing a refusal by hand instead of letting an exception escape: the
 * reader of one of these has a half-cut release in front of them.
 *
 * The control that carries the most weight is [`a well-formed cut writes exactly this`], which
 * states the whole of the output as literal text. The refusals prove the command stops; only that
 * one proves that what it writes when it does not stop is what ADR-0017 rules 2 and 8 describe —
 * a dated heading, a fresh empty `Unreleased`, the catalog left on the *next* snapshot, and the
 * surfaces recorded under the version actually being released.
 */
class ReleasePlanTest {

    @Test
    fun `a dirty tree is refused, naming what to do with the changes`() {
        val refusal = refusal(
            workingCopy = WorkingCopy(changedPaths = listOf(" M PRD.md", "?? scratch.kt"), tags = emptyList())
        )

        assertTrue(refusal, refusal.contains("PRD.md"))
        assertTrue(refusal, refusal.contains("Commit or discard them"))
    }

    @Test
    fun `a version that is not a version is refused, naming the grammar`() {
        val refusal = refusal(asked = "0.2")

        assertTrue(refusal, refusal.contains(SemanticVersion.GRAMMAR))
        assertTrue(refusal, refusal.contains("--release-version=1.2.0"))
    }

    @Test
    fun `a snapshot asked for by name is refused, because a snapshot is not a release`() {
        val refusal = refusal(asked = "0.2.0-SNAPSHOT")

        assertTrue(refusal, refusal.contains("ADR-0017 rule 8"))
        assertTrue(refusal, refusal.contains("--release-version=0.2.0"))
    }

    @Test
    fun `a version that does not succeed the last release is refused, naming the three that do`() {
        val refusal = refusal(asked = "0.5.0", recordedVersion = "0.1.0")

        assertTrue(refusal, refusal.contains("does not succeed 0.1.0"))
        assertTrue(refusal, refusal.contains("0.1.1, 0.2.0, 1.0.0"))
    }

    @Test
    fun `a version smaller than the surface diff requires is refused in the gate's own words`() {
        val refusal = refusal(
            asked = "0.1.1",
            recordedVersion = "0.1.0",
            recordedSurfaces = mapOf("superplayer-core" to SURFACE),
            trackedSurfaces = mapOf("superplayer-core" to SURFACE_WITH_ONE_MORE)
        )

        assertTrue(refusal, refusal.contains("ADR-0017 rule 2"))
        assertTrue(refusal, refusal.contains("The smallest version that describes this change is 0.2.0."))
        assertTrue(refusal, refusal.contains("`verifyVersionBump`, asked about 0.1.1 before anything was written"))
    }

    @Test
    fun `an empty Unreleased section is refused, because it is where a behaviour change is recorded`() {
        val refusal = refusal(changelog = CHANGELOG_WITH_EMPTY_UNRELEASED)

        assertTrue(refusal, refusal.contains("is empty"))
        assertTrue(refusal, refusal.contains("ADR-0017 rule 3"))
    }

    @Test
    fun `a tag that already exists is refused, naming how to remove one cut wrongly`() {
        val refusal = refusal(asked = "0.1.0", workingCopy = WorkingCopy(emptyList(), listOf("v0.1.0")))

        assertTrue(refusal, refusal.contains("The tag v0.1.0 already exists"))
        assertTrue(refusal, refusal.contains("git tag -d v0.1.0"))
    }

    @Test
    fun `a changelog with no Unreleased heading at all is refused`() {
        val refusal = refusal(changelog = "# Changelog\n\n## [0.0.9] - 2026-01-01\n\nSomething.\n")

        assertTrue(refusal, refusal.contains("no \"## [Unreleased]\" heading"))
    }

    @Test
    fun `a well-formed cut writes exactly this`() {
        val plan = plan(asked = "0.1.0")

        assertEquals(SemanticVersion(0, 1, 0, isSnapshot = false), plan.release)
        assertEquals(SemanticVersion(0, 1, 1, isSnapshot = true), plan.nextSnapshot)
        assertEquals("v0.1.0", plan.tag)
        assertEquals(catalog("0.1.0"), plan.releaseCatalog)
        assertEquals(catalog("0.1.1-SNAPSHOT"), plan.nextSnapshotCatalog)
        assertEquals(
            """
            |# Changelog
            |
            |## [Unreleased]
            |
            |## [0.1.0] - 2026-09-19
            |
            |### Added
            |
            |- The first release.
            |
            """.trimMargin(),
            plan.stampedChangelog
        )
        assertEquals(
            mapOf(
                "superplayer-core.api" to SURFACE,
                RECORDED_VERSION_FILE to "0.1.0\n"
            ),
            plan.recordedSurfaces
        )
    }

    @Test
    fun `the section under Unreleased moves whole, and the releases below it are untouched`() {
        val stamp = stampRelease(
            changelog = "# Changelog\n\n## [Unreleased]\n\n- A fix.\n\n## [0.1.0] - 2026-01-02\n\n- The first.\n",
            version = SemanticVersion(0, 1, 1, isSnapshot = false),
            today = "2026-09-19"
        )

        assertEquals(
            """
            |# Changelog
            |
            |## [Unreleased]
            |
            |## [0.1.1] - 2026-09-19
            |
            |- A fix.
            |
            |## [0.1.0] - 2026-01-02
            |
            |- The first.
            |
            """.trimMargin(),
            (stamp as ChangelogStamp.Written).text
        )
    }

    @Test
    fun `the first release succeeds nothing, because nothing has been released`() {
        // ADR-0017's *What this costs the first release*: with no recorded surface there is no
        // baseline, so the first version is a declaration. This is the repository's state today.
        assertEquals(SemanticVersion(0, 4, 0, isSnapshot = false), plan(asked = "0.4.0").release)
    }

    @Test
    fun `a catalog with no superplayer entry is refused rather than rewritten blindly`() {
        val refusal = refusal(catalog = "[versions]\nkotlin = \"2.0.0\"\n")

        assertTrue(refusal, refusal.contains("no `superplayer = \"...\"`"))
        assertTrue(refusal, refusal.contains("There is no entry for this command to move to"))
    }

    private fun planning(
        asked: String = "0.1.0",
        catalog: String = catalog("0.1.0-SNAPSHOT"),
        changelog: String = CHANGELOG,
        recordedVersion: String? = null,
        recordedSurfaces: Map<String, String> = emptyMap(),
        trackedSurfaces: Map<String, String> = mapOf("superplayer-core" to SURFACE),
        workingCopy: WorkingCopy = WorkingCopy(emptyList(), emptyList())
    ): ReleasePlanning = planRelease(
        asked = asked,
        sources = ReleaseSources(
            catalog = catalog,
            changelog = changelog,
            recordedVersion = recordedVersion,
            recordedSurfaces = recordedSurfaces,
            trackedSurfaces = trackedSurfaces
        ),
        workingCopy = workingCopy,
        today = "2026-09-19"
    )

    private fun plan(
        asked: String = "0.1.0",
        recordedVersion: String? = null,
        recordedSurfaces: Map<String, String> = emptyMap()
    ): ReleasePlan =
        (
            planning(asked = asked, recordedVersion = recordedVersion, recordedSurfaces = recordedSurfaces)
                as ReleasePlanning.Planned
            ).plan

    private fun refusal(
        asked: String = "0.1.0",
        catalog: String = catalog("0.1.0-SNAPSHOT"),
        changelog: String = CHANGELOG,
        recordedVersion: String? = null,
        recordedSurfaces: Map<String, String> = emptyMap(),
        trackedSurfaces: Map<String, String> = mapOf("superplayer-core" to SURFACE),
        workingCopy: WorkingCopy = WorkingCopy(emptyList(), emptyList())
    ): String = (
        planning(asked, catalog, changelog, recordedVersion, recordedSurfaces, trackedSurfaces, workingCopy)
            as ReleasePlanning.Refused
        ).reason

    private companion object {
        fun catalog(version: String) = "[versions]\nsuperplayer = \"$version\"\nkotlin = \"2.0.0\"\n"

        const val CHANGELOG = "# Changelog\n\n## [Unreleased]\n\n### Added\n\n- The first release.\n"

        const val CHANGELOG_WITH_EMPTY_UNRELEASED = "# Changelog\n\n## [Unreleased]\n\n## [0.0.9] - 2026-01-01\n\n- Old.\n"

        const val SURFACE =
            "public final class com/superplayer/core/MediaRequest {\n" +
                "\tpublic final fun getContentId ()Ljava/lang/String;\n" +
                "}\n"

        const val SURFACE_WITH_ONE_MORE =
            "public final class com/superplayer/core/MediaRequest {\n" +
                "\tpublic final fun getContentId ()Ljava/lang/String;\n" +
                "\tpublic final fun getTitle ()Ljava/lang/String;\n" +
                "}\n"
    }
}
