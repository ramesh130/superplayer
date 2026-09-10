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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails the build if a module declares a dependency on a module from a later phase.
 *
 * `docs/modules.md` states the rule — dependencies point inward, and no module depends on a
 * module from a later phase — and until now it was enforced by someone reading the table and
 * remembering. This makes it mechanical, as `verifyNoHardcodedMedia3Versions` does for ADR-0001
 * rule 3.
 *
 * The checking itself lives in [findModulePhaseViolations], a plain function over files, so it
 * can be unit-tested without standing up a Gradle build.
 */
abstract class VerifyModulePhaseRule : DefaultTask() {

    /** `docs/modules.md`, whose phase column is the source of truth. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modulesDocument: RegularFileProperty

    /** One `build.gradle.kts` per library module; the module's name is its directory's. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleBuildScripts: ConfigurableFileCollection

    /**
     * A stamp file written on success. A verification task has no real product, but declaring
     * an output is what lets Gradle skip the task when nothing it reads has changed.
     */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations =
            findModulePhaseViolations(modulesDocument.get().asFile, moduleBuildScripts.files)

        val stamp = stampFile.get().asFile
        if (violations.isEmpty()) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
        } else {
            stamp.delete()
            throw GradleException(
                buildString {
                    appendLine(
                        "Module dependencies must respect the phase rule in docs/modules.md: a " +
                            "module may depend on modules from its own or an earlier phase only."
                    )
                    appendLine("Violations:")
                    violations.forEach { appendLine("  $it") }
                }
            )
        }
    }
}

/**
 * Returns one sentence per violation of the phase rule, sorted so the failure message is stable
 * from run to run. Each names the offending module, the dependency, both phases, and — through
 * the header the task prints above them — `docs/modules.md`.
 *
 * [modulesDocument] is `docs/modules.md` itself: the phases are parsed out of the prose rather
 * than restated here. That is a deliberate trade. It keeps one copy of the phase numbers, which
 * is what makes the documentation authoritative instead of merely descriptive, at the cost of a
 * check that depends on the shape of a markdown table. The shape it depends on is narrow — a row
 * whose first cell is a backticked module name and whose second is the phase — and
 * [parseModulePhases] fails loudly by finding no modules rather than quietly by finding wrong
 * ones, since a table that parses to nothing makes every `project(...)` dependency unlisted.
 */
internal fun findModulePhaseViolations(
    modulesDocument: File,
    moduleBuildScripts: Iterable<File>
): List<String> {
    val phases = parseModulePhases(modulesDocument.readText())

    return moduleBuildScripts
        .filter { it.isFile }
        .flatMap { script ->
            val module = script.parentFile.name
            projectDependenciesIn(script.readText()).mapNotNull { dependency ->
                violation(module, dependency, phases)
            }
        }
        .sorted()
}

/**
 * The phase rule, as a decision about one dependency edge. Null means the edge is legal.
 *
 * A tie is legal, and that is a decision rather than an omission: the rule exists so that an
 * earlier phase is shippable without a later one, and modules sharing a phase ship together, so
 * a dependency between them costs nothing the rule protects. A cycle between peers is Gradle's
 * own error, not this one's.
 */
private fun violation(module: String, dependency: String, phases: Map<String, Int?>): String? {
    if (dependency !in phases) {
        return "$module depends on $dependency, which docs/modules.md does not list."
    }

    val modulePhase = phases[module]
    val dependencyPhase = phases[dependency]

    return when {
        // An unscheduled module is not an earlier phase than anything, so nothing may depend on
        // it — while it may itself depend on anything scheduled. docs/modules.md says why.
        dependencyPhase == null ->
            "${named(module, modulePhase)} depends on ${named(dependency, null)}: an " +
                "unscheduled module is not an earlier phase than anything, so nothing may " +
                "depend on it."

        modulePhase != null && dependencyPhase > modulePhase ->
            "${named(module, modulePhase)} depends on ${named(dependency, dependencyPhase)}: " +
                "a module may not depend on one from a later phase."

        else -> null
    }
}

/** A module as a failure message names it: with its phase, or as unscheduled. */
private fun named(module: String, phase: Int?): String =
    if (phase == null) "$module (unscheduled)" else "$module (phase $phase)"

/**
 * Reads the module table's phase column: module name to phase, with null for a module the
 * roadmap has not scheduled (the table writes that as an em dash).
 */
internal fun parseModulePhases(markdown: String): Map<String, Int?> =
    markdown.lineSequence()
        .mapNotNull(TABLE_ROW::matchEntire)
        .associate { match ->
            val (module, phase) = match.destructured
            module to phase.trim().toIntOrNull()
        }

/**
 * A row of the module table: a backticked module name in the first cell, the phase in the
 * second. The header row and the `| --- |` separator do not match, and neither does prose.
 */
private val TABLE_ROW = Regex("""\|\s*`(superplayer-[\w-]+)`\s*\|([^|]*)\|.*""")

/**
 * A `project(":superplayer-x")` dependency, on a line that is not a comment. The optional
 * `path =` covers the named-argument form, which is the same declaration written differently.
 *
 * This is a text match, not a model of the Gradle DSL: a project dependency assembled from a
 * variable, or named through the type-safe `projects.superplayerX` accessors, would not be seen.
 * Neither appears in this repository — the accessors are not even enabled in `settings.gradle.kts`
 * — and a check that read the resolved dependency graph instead would have to configure twelve
 * Android modules to answer a question the build files already state plainly.
 */
private val PROJECT_DEPENDENCY = Regex("""project\(\s*(?:path\s*=\s*)?"::?([\w-]+)"\s*\)""")

private fun projectDependenciesIn(script: String): List<String> =
    script.lineSequence()
        .filterNot(::isCommentLine)
        .flatMap { line -> PROJECT_DEPENDENCY.findAll(line).map { it.groupValues[1] } }
        .toList()
