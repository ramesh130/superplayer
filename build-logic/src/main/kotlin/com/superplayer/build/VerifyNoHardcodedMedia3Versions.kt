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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails the build if any Gradle script pins a Media3 version directly.
 *
 * ADR-0001 rule 3 says Media3's version is declared in exactly one place, the version catalog.
 * A rule that is only written down gets broken; this makes it mechanical.
 *
 * The scanning itself lives in [findHardcodedMedia3Versions], which is a plain function over
 * files so it can be unit-tested without standing up a Gradle build.
 */
abstract class VerifyNoHardcodedMedia3Versions : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildScripts: ConfigurableFileCollection

    /** Only used to render readable relative paths in the failure message. */
    @get:Internal
    abstract val projectRoot: DirectoryProperty

    /**
     * A stamp file written on success. A verification task has no real product, but declaring
     * an output is what lets Gradle skip the task when no build script has changed.
     */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val failures = findHardcodedMedia3Versions(buildScripts.files, projectRoot.get().asFile)

        val stamp = stampFile.get().asFile
        if (failures.isEmpty()) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
        } else {
            stamp.delete()
            throw GradleException(
                buildString {
                    appendLine("Media3 versions must come from gradle/libs.versions.toml (ADR-0001 rule 3).")
                    appendLine("Hardcoded versions found:")
                    failures.forEach { appendLine("  $it") }
                }
            )
        }
    }
}

/**
 * Returns one `path:line: text` entry per hardcoded Media3 version found in [scripts], sorted so
 * the failure message is stable from run to run. Paths are rendered relative to [root].
 *
 * Commented-out lines are ignored: a comment is not a dependency declaration, and the rule's own
 * documentation needs to be able to show what a violation looks like.
 */
internal fun findHardcodedMedia3Versions(scripts: Iterable<File>, root: File): List<String> =
    scripts
        .filter { it.isFile }
        .flatMap { file ->
            file.readLines()
                .asSequence()
                .withIndex()
                .filterNot { (_, line) -> isComment(line) }
                .filter { (_, line) -> HARDCODED_COORDINATE.containsMatchIn(line) }
                .map { (index, line) -> "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" }
                .toList()
        }
        .sorted()

private fun isComment(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("//") || trimmed.startsWith("#") ||
        trimmed.startsWith("*") || trimmed.startsWith("/*")
}

/**
 * A Media3 Maven coordinate followed by a literal version, which is what a hardcoded pin looks
 * like. Version-catalog accessors carry no version, so they never match.
 */
private val HARDCODED_COORDINATE = Regex("""androidx\.media3:[\w-]+:\d""")
