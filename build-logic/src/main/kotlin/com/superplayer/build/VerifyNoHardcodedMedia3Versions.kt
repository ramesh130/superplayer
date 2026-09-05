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

/**
 * Fails the build if any Gradle script pins a Media3 version directly.
 *
 * ADR-0001 rule 3 says Media3's version is declared in exactly one place, the version catalog.
 * A rule that is only written down gets broken; this makes it mechanical.
 *
 * Commented-out lines are ignored: a comment is not a dependency declaration, and the rule's
 * own documentation needs to be able to show what a violation looks like.
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
        val root = projectRoot.get().asFile
        val failures = buildScripts.files
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

    private fun isComment(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") || trimmed.startsWith("#") ||
            trimmed.startsWith("*") || trimmed.startsWith("/*")
    }

    private companion object {
        /**
         * A Media3 Maven coordinate followed by a literal version, which is what a hardcoded
         * pin looks like. Version-catalog accessors carry no version, so they never match.
         */
        val HARDCODED_COORDINATE = Regex("""androidx\.media3:[\w-]+:\d""")
    }
}
