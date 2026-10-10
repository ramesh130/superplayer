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
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails the build if the licence header Spotless stamps onto every `.kt` file is not the licence
 * this project is actually under.
 *
 * Spotless guarantees that every source file carries *the header file*; nothing in Spotless knows
 * whether that file says the same thing as `LICENSE`. Without this check the two could drift —
 * a header edited to name a different license, or a `LICENSE` swapped out — and the result would
 * be every source file in the repository confidently asserting the wrong terms, which is worse
 * than no header at all.
 *
 * The comparison is against the boilerplate notice in the Apache License's own APPENDIX, which is
 * the text Apache-2.0 tells you to attach to each file. The copyright line is excluded: `LICENSE`
 * carries the unfilled `Copyright [yyyy] [name of copyright owner]` placeholder, and the header
 * fills it in, which is the point of the placeholder.
 *
 * It also checks the one header nothing stamps: every `.swift` file of the iOS package has to open
 * with that same header written as `//` lines ([findSwiftFilesWithoutHeader]).
 *
 * The comparison itself lives in [findLicenseHeaderMismatches], a plain function over strings, so
 * it can be unit-tested without standing up a Gradle build.
 */
abstract class VerifyLicenseHeader : DefaultTask() {

    /** The header Spotless prepends to every `.kt` file. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headerFile: RegularFileProperty

    /** The repository's `LICENSE`, which the header has to agree with. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseFile: RegularFileProperty

    /** Only used to render readable relative paths in the failure message. */
    @get:Internal
    abstract val projectRoot: DirectoryProperty

    /**
     * A stamp file written on success. A verification task has no real product, but declaring
     * an output is what lets Gradle skip the task when neither input has changed.
     */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /**
     * Every `.swift` file of the iOS package. Spotless stamps nothing onto them and swift-format has
     * no header rule, so the header is checked here: as text, on the Linux job every change runs.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val swiftSources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val headerText = headerFile.get().asFile.readText()
        val failures = findLicenseHeaderMismatches(
            headerText = headerText,
            licenseText = licenseFile.get().asFile.readText()
        )
        val unheaded = findSwiftFilesWithoutHeader(
            headerText,
            swiftSources.files.associate { relative(it) to it.readText() }
        )

        val stamp = stampFile.get().asFile
        if (failures.isEmpty() && unheaded.isEmpty()) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
        } else if (failures.isEmpty()) {
            stamp.delete()
            throw GradleException(
                buildString {
                    appendLine(
                        "These Swift files do not open with config/license-header.txt as `//` " +
                            "lines (in Package.swift, after the tools-version line):"
                    )
                    unheaded.forEach { appendLine("  $it") }
                }
            )
        } else {
            // A header that is not the licence is reported alone: until it is, every Swift file's
            // header is derived from the wrong text, and listing them would bury the cause.
            stamp.delete()
            throw GradleException(
                buildString {
                    appendLine("The Spotless licence header does not match LICENSE.")
                    appendLine("Header: ${relative(headerFile.get().asFile)}")
                    appendLine("Licence: ${relative(licenseFile.get().asFile)}")
                    failures.forEach { appendLine("  $it") }
                }
            )
        }
    }

    private fun relative(file: File): String =
        file.relativeTo(projectRoot.get().asFile).path
}

/**
 * [headerText] — `config/license-header.txt`, a block comment — as the `//` lines Swift's own
 * projects open a file with. Derived rather than kept as a second copy, so that
 * [findLicenseHeaderMismatches] holding that file to `LICENSE` vouches for every Swift file too.
 */
internal fun swiftLicenseHeader(headerText: String): List<String> =
    headerText.lines()
        .filter { it.startsWith(" *") && !it.startsWith(" */") }
        .map { ("//" + it.removePrefix(" *")).trimEnd() }

/**
 * The paths of [swiftFiles] (path to contents) that do not open with [swiftLicenseHeader], sorted.
 * A manifest must open with its `// swift-tools-version` line, so there the header starts after it
 * and the `//` line separating the two.
 */
internal fun findSwiftFilesWithoutHeader(
    headerText: String,
    swiftFiles: Map<String, String>
): List<String> {
    val header = swiftLicenseHeader(headerText)
    return swiftFiles
        .filterValues { text ->
            val lines = text.lines()
            val start = if (lines.firstOrNull()?.startsWith(TOOLS_VERSION) == true) 2 else 0
            lines.drop(start).take(header.size) != header
        }
        .keys
        .sorted()
}

private const val TOOLS_VERSION = "// swift-tools-version"

/**
 * Returns one human-readable entry per way in which [headerText] fails to carry the licence
 * declared by [licenseText]; empty when the two agree.
 */
internal fun findLicenseHeaderMismatches(headerText: String, licenseText: String): List<String> {
    val failures = mutableListOf<String>()

    val headerNotice = noticeFromHeader(headerText)
    val licenseNotice = noticeFromLicenseAppendix(licenseText)

    if (!headerText.lineSequence().any { COPYRIGHT_LINE.containsMatchIn(it) }) {
        failures += "the header carries no `Copyright <year> <holder>` line"
    }
    if (headerText.lineSequence().any { UNFILLED_COPYRIGHT.containsMatchIn(it) }) {
        failures += "the header still carries the LICENSE appendix's unfilled copyright placeholder"
    }
    if (licenseNotice.isEmpty()) {
        failures += "LICENSE has no Apache-2.0 APPENDIX boilerplate to compare the header against"
        return failures
    }
    if (headerNotice.isEmpty()) {
        failures += "the header has no licence notice, only a copyright line"
        return failures
    }

    licenseNotice.forEachIndexed { index, expected ->
        val actual = headerNotice.getOrNull(index)
        if (actual != expected) {
            failures += "line ${index + 1} of the notice: expected \"$expected\", found \"${actual ?: "<nothing>"}\""
        }
    }
    if (headerNotice.size > licenseNotice.size) {
        headerNotice.drop(licenseNotice.size).forEach {
            failures += "the header says more than the licence does: \"$it\""
        }
    }

    return failures
}

/**
 * The notice lines of a block-comment licence header: comment syntax removed, the copyright line
 * dropped, blank lines dropped, and runs of whitespace collapsed.
 *
 * Whitespace is normalized because `LICENSE` indents its appendix by three columns and a source
 * header indents by one asterisk and a space. The wording is what has to match, not the layout.
 */
private fun noticeFromHeader(headerText: String): List<String> =
    headerText.lineSequence()
        .map { it.trim().removePrefix("/*").removeSuffix("*/").removePrefix("*").trim() }
        .filterNot { it.isEmpty() }
        .filterNot { ANY_COPYRIGHT_LINE.containsMatchIn(it) }
        .map { it.normalizeSpaces() }
        .toList()

/**
 * The boilerplate notice from the Apache License's APPENDIX: everything after the copyright
 * placeholder, normalized the same way.
 */
private fun noticeFromLicenseAppendix(licenseText: String): List<String> {
    val lines = licenseText.lines()
    val placeholder = lines.indexOfFirst { UNFILLED_COPYRIGHT.containsMatchIn(it) }
    if (placeholder < 0) return emptyList()

    return lines.drop(placeholder + 1)
        .map { it.trim() }
        .filterNot { it.isEmpty() }
        .map { it.normalizeSpaces() }
}

private fun String.normalizeSpaces(): String = replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("""\s+""")

/**
 * Any copyright line at all, filled in or not. Dropped before the notices are compared: `LICENSE`
 * carries the placeholder and the header carries the real thing, and neither is part of the terms.
 */
private val ANY_COPYRIGHT_LINE = Regex("""^Copyright\b""")

/** A filled-in copyright line: a year and a holder. */
private val COPYRIGHT_LINE = Regex("""Copyright\s+\d{4}""")

/** The APPENDIX's placeholder, which marks where the boilerplate notice starts. */
private val UNFILLED_COPYRIGHT = Regex("""Copyright\s+\[yyyy]\s+\[name of copyright owner]""")
