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
 * Fails the build if the version every published module carries is not one ADR-0017 admits, or if
 * the changelog does not agree with it.
 *
 * ADR-0017 rule 1 makes `gradle/libs.versions.toml`'s `superplayer` entry the version of all
 * thirteen modules at once, so a typo in it is a typo in every artefact that would be published.
 * Nothing read it as anything but a string before this task, and `publishToMavenLocal` would have
 * accepted `0.1` as happily as `0.1.0`.
 *
 * The comparison lives in [findVersionChangelogMismatch], a plain function over text, so the
 * malformed and half-released states can be stated as string literals in a unit test rather than
 * written to a tree.
 */
abstract class VerifyVersion : DefaultTask() {

    /** `gradle/libs.versions.toml`, whose `superplayer` version every module publishes under. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionCatalog: RegularFileProperty

    /** `CHANGELOG.md`, whose headings are what a released version has to appear in. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelog: RegularFileProperty

    /**
     * A stamp file written on success. A verification task has no real product, but declaring
     * an output is what lets Gradle skip the task when nothing it reads has changed.
     */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val mismatch = findVersionChangelogMismatch(
            versionCatalog.get().asFile.readText(),
            changelog.get().asFile.readText()
        )

        val stamp = stampFile.get().asFile
        if (mismatch == null) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
        } else {
            stamp.delete()
            throw GradleException(
                buildString {
                    appendLine(mismatch)
                    appendLine(
                        "ADR-0017 rules 1 and 8: one version for every published module, and a " +
                            "`-${SemanticVersion.SNAPSHOT_SUFFIX}` names a version that has not " +
                            "been released. A release is the suffix removed and a dated heading " +
                            "written in the same change."
                    )
                }
            )
        }
    }
}

/**
 * Returns why the catalog's published version and `CHANGELOG.md` disagree, or null when they agree.
 *
 * Two readings, and which one applies is decided by the suffix.
 *
 * A **released** version — no suffix — must carry its own dated heading with something written
 * under it. That is ADR-0017 rule 3's mechanism: a behaviour change no tracked `.api` file records
 * is caught by nothing, and the changelog row is where the judgement is written down rather than
 * made silently. A heading with an empty body would satisfy the letter of that and record nothing.
 *
 * A **snapshot** is checked against a different thing, because requiring a dated heading for a
 * version that has not been released would be requiring the changelog to lie. What it must have is
 * an `Unreleased` section to accumulate into, and what it must *not* have is a dated heading for
 * the release it will become: a catalog reading `0.2.0-SNAPSHOT` beside a dated `0.2.0` is a
 * half-finished release in one direction or the other — the notes were written and the version
 * never moved, or the version was rolled back and the notes were left — and ADR-0017 rule 8's
 * second consequence forbids exactly that state. The `Unreleased` section's own body is not
 * checked: it is legitimately empty in the minutes after a release, which is the one moment the
 * check would be wrong about.
 *
 * Nothing here reads what a body *says*. That line is drawn where the check stops being mechanical:
 * "has a row" is a fact about the file, "has the right row" is a review.
 */
internal fun findVersionChangelogMismatch(catalog: String, changelog: String): String? {
    val version = catalogVersion(catalog)
        ?: return catalogVersionProblem(catalog) +
            " CHANGELOG.md cannot be checked against a version it cannot read."

    val entries = releaseEntries(changelog)
    val dated = entries.firstOrNull { it.version == version.released }

    if (version.isSnapshot) {
        if (dated != null) {
            return "gradle/libs.versions.toml sets superplayer = \"$version\", which names a " +
                "version that has not been released, but CHANGELOG.md already dates " +
                "${dated.version} as released, on ${dated.date}. One of the two is left over from " +
                "a half-finished release: either the version never moved past the release that was " +
                "written down, or it was moved back and the heading was not."
        }
        if (!hasUnreleasedHeading(changelog)) {
            return "gradle/libs.versions.toml sets superplayer = \"$version\", which names a " +
                "version that has not been released, but CHANGELOG.md has no " +
                "\"## [$UNRELEASED]\" heading for what has changed since the last release to be " +
                "recorded under."
        }
        return null
    }

    if (dated == null) {
        val headings = entries.joinToString(", ") { it.version.toString() }.ifEmpty { "none" }
        return "gradle/libs.versions.toml sets superplayer = \"$version\", a released version, " +
            "but CHANGELOG.md has no \"## [$version] - YYYY-MM-DD\" heading for it. The versions " +
            "it dates are: $headings."
    }
    if (dated.date == null || !ISO_DATE.matches(dated.date)) {
        return "gradle/libs.versions.toml sets superplayer = \"$version\", a released version, " +
            "and CHANGELOG.md heads it \"${dated.heading}\", which carries no ISO 8601 date. A " +
            "release heading reads \"## [$version] - YYYY-MM-DD\"."
    }
    if (dated.bodyIsEmpty) {
        return "gradle/libs.versions.toml sets superplayer = \"$version\", a released version, " +
            "and CHANGELOG.md heads it \"${dated.heading}\" with nothing written under it. What " +
            "changed in a release is what the release is; no tracked `.api` file records a " +
            "behaviour change (ADR-0017 rule 3) and this row is where it is written down."
    }
    return null
}

/** Keep a Changelog's own name for the section above the dated ones. */
private const val UNRELEASED = "Unreleased"

/**
 * The `[versions]` key itself. The library entries — `superplayer-core = { ... }` and the rest —
 * also begin with `superplayer`, and the `\s*=` right after the name is what keeps them out. The
 * same trade [findMedia3SupportedVersionMismatch] makes, for the same reason.
 *
 * File-private, because the two readers of that entry — this check and [findVersionBumpMismatch] —
 * both reach it through [catalogVersion] rather than through the pattern, and two regexes over one
 * catalog key would be two places for it to be spelled differently.
 */
private val CATALOG_VERSION = Regex("""^superplayer\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)

/**
 * The version every published module is about to be released under, or null when `[versions]` does
 * not name one this repository can judge. [catalogVersionProblem] is the other half and says which
 * of the two it was.
 *
 * Both checks over that entry read it here rather than each parsing the catalog itself, so
 * "the version" means one thing in this build (ADR-0017 rule 1).
 */
internal fun catalogVersion(catalog: String): SemanticVersion? =
    CATALOG_VERSION.find(catalog)?.groupValues?.get(1)?.let { SemanticVersion.parse(it) }

/**
 * Why [catalogVersion] answered null: the entry is missing, or it is there and malformed. Each
 * caller appends what *it* could not do as a result, because that sentence is the only part of the
 * two failures that differs.
 */
internal fun catalogVersionProblem(catalog: String): String {
    val declared = CATALOG_VERSION.find(catalog)?.groupValues?.get(1)
        ?: return "gradle/libs.versions.toml has no `superplayer = \"...\"` under `[versions]`, " +
            "which is the one version ADR-0017 rule 1 publishes every module under."
    return "gradle/libs.versions.toml sets superplayer = \"$declared\", which is not " +
        "${SemanticVersion.GRAMMAR}."
}

// ref: Keep a Changelog 1.1.0 — a version heading is the version in brackets followed by its
// release date, and dates are ISO 8601. The brackets are that format's link-reference shape and
// are what tells a heading from any other `##` in the document.
//
// The separator that format writes is the hyphen; the en and em dashes are accepted beside it
// because an editor that substitutes one is the likeliest way this file acquires a character
// nobody typed, and failing a release over it would be failing it for punctuation. The date is
// still held to ISO 8601 below, which is the part a reader reads.
private val HEADING = Regex("""^##\s+\[([^\]]+)\]\s*(?:[-–—]\s*(\S+))?\s*$""", RegexOption.MULTILINE)

/** ISO 8601's calendar date, which is the only date form Keep a Changelog admits. */
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** One dated heading: the version it names, its date as written, and whether anything follows it. */
private data class ReleaseEntry(
    val version: SemanticVersion,
    val date: String?,
    val heading: String,
    val bodyIsEmpty: Boolean
)

private fun hasUnreleasedHeading(changelog: String): Boolean =
    HEADING.findAll(changelog).any { it.groupValues[1].equals(UNRELEASED, ignoreCase = true) }

/**
 * Every `## [version]` heading that names a version, in the order the file writes them, with the
 * text between it and the next bracketed heading. A heading naming something that is not a
 * version — `Unreleased`, or a typo — is not an entry, and a release looking for its own heading
 * therefore reports it missing rather than matching it by accident.
 */
private fun releaseEntries(changelog: String): List<ReleaseEntry> {
    val headings = HEADING.findAll(changelog).toList()
    return headings.mapIndexedNotNull { index, match ->
        val version = SemanticVersion.parse(match.groupValues[1]) ?: return@mapIndexedNotNull null
        val bodyEnd = headings.getOrNull(index + 1)?.range?.first ?: changelog.length
        ReleaseEntry(
            version = version,
            date = match.groupValues[2].ifEmpty { null },
            heading = match.value.trim(),
            bodyIsEmpty = changelog.substring(match.range.last + 1, bodyEnd).isBlank()
        )
    }
}
