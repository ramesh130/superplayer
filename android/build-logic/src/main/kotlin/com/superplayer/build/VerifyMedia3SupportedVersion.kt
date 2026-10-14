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
 * Fails the build if the Media3 the version catalog pins is not the Media3 `docs/modules.md` says
 * every module supports.
 *
 * ADR-0001 rule 3 has each module document the Media3 range it supports, and `docs/modules.md`
 * does so as a single minor version. A Media3 bump — which arrives as Dependabot's `media3` group —
 * moves that version, and the section has to be rewritten by hand in the same change. Dependabot
 * writes its own pull request body and cannot say so there. This makes the pull request say so
 * anyway, by failing its CI until the document has moved with the catalog.
 *
 * The comparison lives in [findMedia3SupportedVersionMismatch], a plain function over text, so it
 * can be unit-tested without standing up a Gradle build.
 */
abstract class VerifyMedia3SupportedVersion : DefaultTask() {

    /** `gradle/libs.versions.toml`, whose `media3` version is the one every build resolves. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionCatalog: RegularFileProperty

    /** `docs/modules.md`, whose "Supported Media3 versions" section states the supported minor. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modulesDocument: RegularFileProperty

    /**
     * A stamp file written on success. A verification task has no real product, but declaring
     * an output is what lets Gradle skip the task when nothing it reads has changed.
     */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val mismatch = findMedia3SupportedVersionMismatch(
            versionCatalog.get().asFile.readText(),
            modulesDocument.get().asFile.readText()
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
                        "A Media3 minor or major bump moves the supported version: rewrite that " +
                            "section of docs/modules.md in the same change. Widening the range to " +
                            "cover more than one minor version needs a compatibility test first, " +
                            "as that section says."
                    )
                }
            )
        }
    }
}

/**
 * Returns why the catalog's Media3 and the documented supported version disagree, or null when
 * they agree.
 *
 * They agree when the pinned version is inside the stated minor: `1.11.3` against "Media3 1.11.x"
 * passes, because a patch release is exactly what that range promises to take without anyone
 * rewriting anything. A pre-release of the next minor does not.
 *
 * Either side that cannot be read is itself a mismatch. The document side is parsed out of prose —
 * the same trade [findModulePhaseViolations] makes, keeping one copy of the fact at the cost of
 * depending on its shape — and a reworded section must fail here rather than turn the check into
 * a silent pass.
 */
internal fun findMedia3SupportedVersionMismatch(catalog: String, modulesDocument: String): String? {
    val pinned = MEDIA3_VERSION.find(catalog)?.groupValues?.get(1)
        ?: return "gradle/libs.versions.toml has no `media3 = \"...\"` version to check."

    val supportedMinor = supportedVersionsSection(modulesDocument)
        ?.let(SUPPORTED_MINOR::find)
        ?.groupValues
        ?.get(1)
        ?: return "docs/modules.md has no \"**Media3 X.Y.x**\" under \"$SECTION_HEADING\" to " +
            "check the catalog against. If the section was reworded, VerifyMedia3SupportedVersion " +
            "in build-logic has to learn the new wording."

    return if (pinned.startsWith("$supportedMinor.")) {
        null
    } else {
        "gradle/libs.versions.toml pins Media3 $pinned, but docs/modules.md, under " +
            "\"$SECTION_HEADING\", states Media3 $supportedMinor.x."
    }
}

private const val SECTION_HEADING = "Supported Media3 versions"

/**
 * The `[versions]` key itself. The library entries — `media3-common = { ... }` and the rest — also
 * begin with `media3`, and the `\s*=` right after the name is what keeps them out.
 */
private val MEDIA3_VERSION = Regex("""^media3\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)

/** The stated range, as written in bold: `**Media3 1.11.x**` gives `1.11`. */
private val SUPPORTED_MINOR = Regex("""\*\*Media3 (\d+\.\d+)\.x\*\*""")

/**
 * The body of the "Supported Media3 versions" section, up to the next heading of any level. Only
 * this section is read, so a version named in passing elsewhere in the document is not mistaken
 * for the supported one.
 */
private fun supportedVersionsSection(markdown: String): String? {
    val lines = markdown.lines()
    val heading = lines.indexOfFirst { it.trimStart().startsWith("#") && it.contains(SECTION_HEADING) }
    if (heading < 0) return null

    return lines.drop(heading + 1).takeWhile { !it.trimStart().startsWith("#") }.joinToString("\n")
}
