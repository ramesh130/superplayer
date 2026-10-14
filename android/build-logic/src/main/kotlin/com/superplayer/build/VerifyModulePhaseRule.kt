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
 * The rule binds both platforms (`docs/ios-plan.md` §4), so the root `Package.swift` is read too:
 * its targets' dependencies are held to the same document's iOS table by
 * [findSwiftTargetPhaseViolations]. That half is plain text and runs here, on Linux, rather than
 * in `ios/check` — a manifest edit is then caught by the job every change already runs, and the
 * phase numbers keep one reader.
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

    /** The root `Package.swift`, whose targets are the iOS products and their tests. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val swiftPackageManifest: RegularFileProperty

    /**
     * A stamp file written on success. A verification task has no real product, but declaring
     * an output is what lets Gradle skip the task when nothing it reads has changed.
     */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val document = modulesDocument.get().asFile
        val violations =
            findModulePhaseViolations(document, moduleBuildScripts.files) +
                findSwiftTargetPhaseViolations(document, swiftPackageManifest.get().asFile)

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

/**
 * The Swift half of the rule: one sentence per dependency of a [packageManifest] target that breaks
 * the phase rule against [modulesDocument]'s iOS table, sorted as [findModulePhaseViolations] sorts.
 *
 * A test target takes its product's phase — `SuperPlayerABRTests` is phase 3 because
 * `SuperPlayerABR` is — which is what the Android reading does too, since a
 * `testImplementation(project(...))` line is matched like any other. Unlike Android, a target the
 * table does not list is itself a violation rather than an unscheduled module: every Swift target
 * here is a product, a product's tests, or `SuperPlayerBenchmark` and its tests — a target with a
 * row and no product, since nothing ships it — and a target with no row is one nobody gave a phase.
 */
internal fun findSwiftTargetPhaseViolations(
    modulesDocument: File,
    packageManifest: File
): List<String> {
    val phases = parseModulePhases(modulesDocument.readText())

    return swiftTargetsIn(packageManifest.readText())
        .flatMap { target ->
            val product = target.name.removeSuffix(TEST_TARGET_SUFFIX)
            if (product !in phases) {
                return@flatMap listOf("${target.name} is a target docs/modules.md does not list.")
            }
            val withTarget = phases + (target.name to phases[product])
            target.dependencies.mapNotNull { violation(target.name, it, withTarget) }
        }
        .sorted()
}

/** SwiftPM's convention for a product's test target, and the only one this package uses. */
private const val TEST_TARGET_SUFFIX = "Tests"

/** One target of a `Package.swift`: its name and the names of the targets it depends on. */
internal data class SwiftTarget(val name: String, val dependencies: List<String>)

/**
 * The targets a `Package.swift` declares, read as text.
 *
 * Like [PROJECT_DEPENDENCY], this is a reading of the manifest as written and not a model of it:
 * evaluating the manifest needs a Swift toolchain, which the Linux job does not have, and would
 * turn a question the file states plainly into a build. The shape it depends on is the one SwiftPM
 * documents — `.target(name: "X", dependencies: [...])` and its `testTarget`, `executableTarget`
 * and `binaryTarget` siblings — with a dependency named by a string, by `.target(name:)` or by
 * `.byName(name:)`. A `.product(name:package:)` names another package's product, which no phase in
 * this repository governs, so it is skipped; a target assembled from a variable would not be seen,
 * and none is.
 */
internal fun swiftTargetsIn(manifest: String): List<SwiftTarget> {
    // A trailing `// ...` is dropped too; the whitespace before it is what keeps a `file://` or
    // `https://` inside a string intact.
    val text = manifest.lineSequence()
        .filterNot(::isCommentLine)
        .map { it.replace(TRAILING_COMMENT, "") }
        .joinToString("\n")
    val targets = mutableListOf<SwiftTarget>()
    var consumed = 0
    for (declaration in SWIFT_TARGET_DECLARATION.findAll(text)) {
        // A `.target(name:)` inside another target's dependency list is a reference rather than a
        // declaration, and it sits inside the span already read.
        if (declaration.range.first < consumed) continue
        val open = declaration.range.last
        val close = closingIndex(text, open, '(', ')')
        consumed = close
        val arguments = text.substring(open + 1, close)
        val name = SWIFT_NAME_ARGUMENT.find(arguments)?.groupValues?.get(1) ?: continue
        targets += SwiftTarget(name, swiftDependenciesIn(arguments))
    }
    return targets
}

private fun swiftDependenciesIn(arguments: String): List<String> {
    val label = SWIFT_DEPENDENCIES_ARGUMENT.find(arguments) ?: return emptyList()
    val open = label.range.last
    val list = arguments.substring(open + 1, closingIndex(arguments, open, '[', ']'))
    return SWIFT_STRING.findAll(OTHER_PACKAGE_PRODUCT.replace(list, ""))
        .map { it.groupValues[1] }
        .toList()
}

/** The index of the bracket closing the one at [open], or the end of [text] if none does. */
private fun closingIndex(text: String, open: Int, opening: Char, closing: Char): Int {
    var depth = 0
    for (index in open until text.length) {
        when (text[index]) {
            opening -> depth++
            closing -> if (--depth == 0) return index
        }
    }
    return text.length
}

private val SWIFT_TARGET_DECLARATION =
    Regex("""\.(?:target|testTarget|executableTarget|binaryTarget)\s*\(""")
private val SWIFT_NAME_ARGUMENT = Regex("""\bname\s*:\s*"([^"]+)"""")
private val SWIFT_DEPENDENCIES_ARGUMENT = Regex("""\bdependencies\s*:\s*\[""")
private val OTHER_PACKAGE_PRODUCT = Regex("""\.product\s*\([^)]*\)""")
private val SWIFT_STRING = Regex(""""([^"]+)"""")
private val TRAILING_COMMENT = Regex("""\s//.*$""")

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
 * A row of either module table: a backticked Android module or iOS product name in the first
 * cell, the phase in the second. One map holds both, since no product name is a module's. The
 * header row and the `| --- |` separator do not match, and neither does prose.
 */
private val TABLE_ROW = Regex("""\|\s*`(superplayer-[\w-]+|SuperPlayer\w+)`\s*\|([^|]*)\|.*""")

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
