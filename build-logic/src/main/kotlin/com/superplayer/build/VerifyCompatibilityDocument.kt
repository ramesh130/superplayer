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

    /**
     * `settings.gradle.kts`, whose `include(...)` lines are the published modules and whose
     * `unpublishedModules` list is the ones the build carries and does not publish ([ModulePublication]).
     */
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
                            "per module settings.gradle.kts includes, carrying the publication that " +
                            "file declares, so a module an adopter can resolve cannot go " +
                            "undocumented and one they cannot resolve cannot read as though they " +
                            "could (ADR-0017 rule 1)."
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
 *
 * Since #364 the module list has more than one half — [publishedModules], [unpublishedModules] and,
 * since #353, [locallyPublishedModules] — and the table has to name every module in any of them,
 * because a module an adopter cannot resolve is still one they will read about here. What
 * distinguishes them is the status itself, which is the **same declaration** the convention plugin
 * publishes by (see [ModulePublication]): a module no adopter can resolve reads
 * [NOT_PUBLISHED_STATUS] and every other module reads something else. The two unresolvable kinds
 * share that row deliberately — a `LOCAL_ONLY` artifact exists only in the local repository of the
 * machine that built it, so from this document's side it is not published, which is exactly what
 * keeps #353 from pre-empting #369. Checked in both directions, so neither a row claiming an artifact that does
 * not exist nor one silently promising a prototype can land.
 */
internal fun findCompatibilityDocumentViolations(
    settingsScript: String,
    compatibilityDocument: String
): List<String> {
    val published = publishedModules(settingsScript)
    // The two kinds an adopter cannot resolve, taken together, because this document answers that
    // one question and `LOCAL_ONLY`'s artifact exists only in the local repository of the machine
    // that built it (#353, [ModulePublication]).
    val unpublished = unpublishedModules(settingsScript) + locallyPublishedModules(settingsScript)
    val section = stabilitySection(compatibilityDocument)
        ?: return listOf(
            "docs/compatibility.md has no \"$STABILITY_HEADING\" section to read a table out of. " +
                "If the heading was reworded, VerifyCompatibilityDocument in build-logic has to " +
                "learn the new wording."
        )
    val documented = parseStabilityRows(section)

    val missing = (published - documented.keys).map {
        "$it is published by settings.gradle.kts and has no row in the stability table."
    }
    val missingUnpublished = (unpublished - documented.keys).map {
        "$it is in the build as an unpublished module and has no row in the stability table."
    }
    val stale = (documented.keys - published - unpublished).map {
        "$it has a stability row but settings.gradle.kts does not include it."
    }
    val unknownStatus = documented
        .filterValues { it !in STABILITY_STATUSES }
        .map { (module, status) ->
            "$module's status reads \"$status\", which is not one of: " +
                STABILITY_STATUSES.joinToString(", ")
        }
    val wrongPublication = documented
        .filterKeys { it in published || it in unpublished }
        .filterValues { it in STABILITY_STATUSES }
        .mapNotNull { (module, status) ->
            val declared = modulePublicationOf(settingsScript, module)
            when {
                declared != null && !declared.isResolvableByAnAdopter && status != NOT_PUBLISHED_STATUS ->
                    "$module's status reads \"$status\", but settings.gradle.kts declares it " +
                        "${describe(declared)}, so its row must read \"$NOT_PUBLISHED_STATUS\"."

                declared == ModulePublication.PUBLISHED && status == NOT_PUBLISHED_STATUS ->
                    "$module's row reads \"$NOT_PUBLISHED_STATUS\", but settings.gradle.kts " +
                        "publishes it. Move it into the unpublishedModules or " +
                        "locallyPublishedModules list, or fix the row."

                else -> null
            }
        }

    return (missing + missingUnpublished + stale + unknownStatus + wrongPublication).sorted()
}

/**
 * How a declaration reads in a failure message. The two unresolvable kinds are named apart even
 * though they demand the same row, because the fix differs: one is a row to correct, the other is
 * a module to move between two lists.
 */
private fun describe(publication: ModulePublication): String = when (publication) {
    ModulePublication.PUBLISHED -> "published"

    ModulePublication.LOCAL_ONLY ->
        "locally published, which produces an artifact for this machine alone and none an adopter " +
            "can resolve"

    ModulePublication.NOT_PUBLISHED -> "unpublished"
}

/** The heading the table sits under, named in the failure message so it can be found. */
private const val STABILITY_HEADING = "Which parts are stable?"

/**
 * The statuses a row may carry. Four, because there are four answers an adopter needs: a module
 * with a public API under `0.x`'s terms, one whose public API is for their *tests*, one that
 * publishes nothing to depend on yet, and — since #364 — one that is not published at all.
 * `docs/compatibility.md` defines each beneath the table.
 */
private val STABILITY_STATUSES =
    listOf("Public", "Public (for your tests)", "Empty", NOT_PUBLISHED_STATUS)

/**
 * The status an unpublished module's row carries, and the only one it may. It is deliberately the
 * *same fact* `settings.gradle.kts` declares rather than a second opinion about it: a reader of
 * the table and the build's own publication decision cannot disagree, because this check reads
 * both and fails on any difference.
 */
private const val NOT_PUBLISHED_STATUS = "Not published"

/**
 * A row of the stability table: a backticked module name in the first cell and a bold status in
 * the second. The header row and the `| --- |` separator do not match, and neither does prose —
 * the same narrow shape [parseModulePhases] depends on, for the same reason.
 */
private val STABILITY_ROW = Regex("""\|\s*`([\w-]+)`\s*\|\s*\*\*([^*|]+)\*\*\s*\|.*""")

/**
 * The body of the "Which parts are stable?" section, up to the next heading of any level — the
 * same scoping [findMedia3SupportedVersionMismatch] does over `docs/modules.md`, and for the same
 * reason: only this section is read, so a module named in a row of some other table is not
 * mistaken for a stability row.
 */
private fun stabilitySection(markdown: String): String? {
    val lines = markdown.lines()
    val heading = lines.indexOfFirst {
        it.trimStart().startsWith("#") && it.contains(STABILITY_HEADING)
    }
    if (heading < 0) return null

    return lines.drop(heading + 1).takeWhile { !it.trimStart().startsWith("#") }.joinToString("\n")
}

private fun parseStabilityRows(markdown: String): Map<String, String> =
    markdown.lineSequence()
        .mapNotNull(STABILITY_ROW::matchEntire)
        .associate { match ->
            val (module, status) = match.destructured
            module to status.trim()
        }
