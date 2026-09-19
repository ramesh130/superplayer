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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if `docs/compatibility.md`'s stability table and the modules the build publishes
 * disagree.
 *
 * `docs/compatibility.md` tells an adopter which modules exist and what each one's public API is
 * worth to them (ADR-0017 rules 1, 2 and 7). A module added to `settings.gradle.kts` and left out
 * of that table is a module an adopter can resolve and has been told nothing about — the failure
 * this task exists to make loud, in the same spirit as
 * [VerifyModulePhaseRule] for `docs/modules.md`'s phase column and `DoctorDocumentTest` for
 * `docs/media-source-doctor.md`'s pathologies.
 *
 * It lives in `build-logic` and is root-only because the authority it checks against is the
 * *build's* own module list — `settings.gradle.kts`'s `include(":superplayer-…")` lines — which no
 * single module's test can read without restating it.
 *
 * The comparison lives in [findCompatibilityDocumentViolations], a plain function over text, so it
 * can be unit-tested without standing up a Gradle build.
 */
abstract class VerifyCompatibilityDocument : DefaultTask() {

    /** `settings.gradle.kts`, whose `include(...)` lines are the set of published modules. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val settingsScript: RegularFileProperty

    /** `docs/compatibility.md`, whose stability table has to name every one of them. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compatibilityDocument: RegularFileProperty

    /**
     * A stamp file written on success. A verification task has no real product, but declaring an
     * output is what lets Gradle skip the task when nothing it reads has changed.
     */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations = findCompatibilityDocumentViolations(
            settingsScript.get().asFile.readText(),
            compatibilityDocument.get().asFile.readText()
        )

        val stamp = stampFile.get().asFile
        if (violations.isEmpty()) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
        } else {
            stamp.delete()
            throw GradleException(
                buildString {
                    appendLine(
                        "docs/compatibility.md's \"$STABILITY_HEADING\" table must carry one row " +
                            "per module settings.gradle.kts publishes, so a module an adopter can " +
                            "resolve cannot go undocumented (ADR-0017 rule 1)."
                    )
                    appendLine("Violations:")
                    violations.forEach { appendLine("  $it") }
                }
            )
        }
    }
}

/**
 * Returns one sentence per disagreement between the document's stability table and the build's
 * module list, sorted so the failure message is stable from run to run. An empty list means they
 * agree.
 *
 * The comparison is a **set** and deliberately not an order. `settings.gradle.kts`'s `include`
 * lines are in neither phase order nor alphabetical order, so holding the document to that order
 * would fail the build for a reason no reader could act on; the document orders its rows by phase,
 * as `docs/modules.md` does, and that is a choice about reading rather than about correctness.
 *
 * Each row's status is checked against [STABILITY_STATUSES] as well. Not for its own sake: a
 * fixed vocabulary is what keeps the table's shape narrow enough to parse, and it means a row
 * mistyped into unparseability fails naming the status rather than silently reading as a missing
 * module.
 */
internal fun findCompatibilityDocumentViolations(
    settingsScript: String,
    compatibilityDocument: String
): List<String> {
    val published = includedModules(settingsScript)
    val documented = parseStabilityRows(compatibilityDocument)

    val missing = (published - documented.keys).map {
        "$it is published by settings.gradle.kts and has no row in the stability table."
    }
    val stale = (documented.keys - published).map {
        "$it has a stability row but settings.gradle.kts does not publish it."
    }
    val unknownStatus = documented
        .filterValues { it !in STABILITY_STATUSES }
        .map { (module, status) ->
            "$module's status reads \"$status\", which is not one of: " +
                STABILITY_STATUSES.joinToString(", ")
        }

    return (missing + stale + unknownStatus).sorted()
}

/** The heading the table sits under, named in the failure message so it can be found. */
private const val STABILITY_HEADING = "Which parts are stable?"

/**
 * The statuses a row may carry. Three, because there are three answers an adopter needs: a module
 * with a public API under `0.x`'s terms, one whose public API is for their *tests*, and one that
 * publishes nothing to depend on yet. `docs/compatibility.md` defines each beneath the table.
 */
internal val STABILITY_STATUSES = listOf("Public", "Public (for your tests)", "Empty")

/** `include(":superplayer-core")` and its twelve siblings, on a line that is not a comment. */
private val INCLUDE = Regex("""^\s*include\("::?(superplayer-[\w-]+)"\)""")

private fun includedModules(settingsScript: String): Set<String> =
    settingsScript.lineSequence()
        .filterNot(::isCommentLine)
        .mapNotNull { INCLUDE.find(it)?.groupValues?.get(1) }
        .toSet()

/**
 * A row of the stability table: a backticked module name in the first cell and a bold status in
 * the second. The header row and the `| --- |` separator do not match, and neither does prose —
 * the same narrow shape [parseModulePhases] depends on, for the same reason.
 */
private val STABILITY_ROW = Regex("""\|\s*`(superplayer-[\w-]+)`\s*\|\s*\*\*([^*|]+)\*\*\s*\|.*""")

private fun parseStabilityRows(markdown: String): Map<String, String> =
    markdown.lineSequence()
        .mapNotNull(STABILITY_ROW::matchEntire)
        .associate { match ->
            val (module, status) = match.destructured
            module to status.trim()
        }
