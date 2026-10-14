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

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The repository root, named apart from the Gradle root (#519).
 *
 * The two are one directory today and will not be once the Android builds move under `android/`
 * (#521), so the case that matters is the one this repository does not have yet: a Gradle build one
 * level below the repository it belongs to. Each test that states it has a control with the two
 * roots equal, because a resolution that ignored the declared value would pass the equal case alone.
 */
class RepositoryRootTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a Gradle build at the repository root names its own directory`() {
        val gradleRoot = gradleBuildAt("", declaring = ".")

        assertEquals(gradleRoot.canonicalFile, repositoryRootOf(gradleRoot).canonicalFile)
    }

    @Test
    fun `a Gradle build one level down names the directory above it`() {
        val gradleRoot = gradleBuildAt("android", declaring = "..")

        assertEquals(temporaryFolder.root.canonicalFile, repositoryRootOf(gradleRoot).canonicalFile)
        assertTrue(File(repositoryRootOf(gradleRoot), "Package.swift").isFile)
    }

    @Test
    fun `a build that declares no repository root is refused by name`() {
        val gradleRoot = temporaryFolder.newFolder("undeclared")
        File(gradleRoot, "gradle.properties").writeText("org.gradle.caching=true\n")

        val refusal = assertThrows(GradleException::class.java) { repositoryRootOf(gradleRoot) }
        assertTrue(refusal.message!!, REPOSITORY_ROOT_PROPERTY in refusal.message!!)
    }

    @Test
    fun `a repository root that does not contain the Gradle build is refused`() {
        // A sibling rather than an ancestor: the Gradle build is part of the repository, so a value
        // naming anywhere else is a typo the shared files would otherwise be silently missing from.
        val gradleRoot = gradleBuildAt("android", declaring = "../ios")

        val refusal = assertThrows(GradleException::class.java) { repositoryRootOf(gradleRoot) }
        assertTrue(refusal.message!!, "does not contain" in refusal.message!!)
    }

    @Test
    fun `this repository's Gradle build resolves to the directory holding the shared files`() {
        // build-logic is the working directory and sits directly inside the Gradle root; that link is
        // within the Gradle builds and moves with them, unlike every path this value exists for.
        val repositoryRoot = repositoryRootOf(File(".."))

        assertTrue(File(repositoryRoot, "Package.swift").isFile)
        assertTrue(File(repositoryRoot, "docs/modules.md").isFile)
    }

    @Test
    fun `a release commits each written file at its path from the repository root`() {
        val gradleRoot = gradleBuildAt("android", declaring = "..")
        val repositoryRoot = repositoryRootOf(gradleRoot)

        assertEquals(
            listOf("android/gradle/libs.versions.toml", "CHANGELOG.md", "android/api/released"),
            releaseWrittenPaths(
                repositoryRoot,
                catalog = File(gradleRoot, "gradle/libs.versions.toml"),
                changelog = File(repositoryRoot, "CHANGELOG.md"),
                recordDirectory = File(gradleRoot, RECORDED_SURFACE_DIRECTORY)
            )
        )
    }

    @Test
    fun `a release from a Gradle build at the repository root commits the paths it always did`() {
        val gradleRoot = gradleBuildAt("", declaring = ".")
        val repositoryRoot = repositoryRootOf(gradleRoot)

        assertEquals(
            listOf("gradle/libs.versions.toml", "CHANGELOG.md", "api/released"),
            releaseWrittenPaths(
                repositoryRoot,
                catalog = File(gradleRoot, "gradle/libs.versions.toml"),
                changelog = File(repositoryRoot, "CHANGELOG.md"),
                recordDirectory = File(gradleRoot, RECORDED_SURFACE_DIRECTORY)
            )
        )
    }

    /**
     * A repository with a `Package.swift` at its top, and a Gradle build at [path] under it declaring
     * [declaring] as its repository root.
     */
    private fun gradleBuildAt(path: String, declaring: String): File {
        File(temporaryFolder.root, "Package.swift").writeText("// swift-tools-version: 6.0\n")
        val gradleRoot = File(temporaryFolder.root, path).apply { mkdirs() }
        File(gradleRoot, "gradle.properties").writeText(
            "org.gradle.caching=true\n$REPOSITORY_ROOT_PROPERTY=$declaring\n"
        )
        return gradleRoot
    }
}
