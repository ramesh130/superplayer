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

/**
 * Whether the build publishes a module, read off `settings.gradle.kts`.
 *
 * ADR-0017 rule 1 releases every published module together at one version, so until #364 a module
 * being *in the build* was the whole of being published: `settings.gradle.kts` had one list,
 * [VerifyCompatibilityDocument] read it as the published set, and
 * `superplayer.android.library.gradle.kts` registered a Maven publication and a tracked API surface
 * for whatever applied it. That KDoc anticipated this case and said what to do about it — "if one is
 * ever added that is deliberately not published, the answer is to decide that here rather than to
 * have the check quietly stop seeing it" — and `superplayer-moq` is the first, because the artifact
 * it links is built on one machine and is nobody else's to resolve (#364, #369).
 *
 * The answer is a **declared third state** rather than three switches that happen to agree.
 * `settings.gradle.kts` says once which modules are not published; [publishedModules] and
 * [unpublishedModules] read that one file; and everything that has to know asks here:
 *
 *  - the convention plugin, which registers neither a publication nor a tracked API surface for an
 *    unpublished module — `docs/api-surface.md` tracks a surface because a *consumer* resolves it;
 *  - [findCompatibilityDocumentViolations], which requires such a module's `docs/compatibility.md`
 *    row to read **Not published** and every other row not to.
 *
 * The failure mode is deliberate in the safe direction. A module declared in neither list is not
 * silently published: [modulePublicationOf] answers `null` and the convention plugin fails naming
 * the module, so a declaration this file cannot read stops the build rather than shipping an
 * artifact nobody meant to ship.
 */
enum class ModulePublication {
    /** In `settings.gradle.kts`'s `include(...)` lines: released with the rest at one version. */
    PUBLISHED,

    /** In its `unpublishedModules` list: in the build, and deliberately not an artifact. */
    NOT_PUBLISHED,
}

/**
 * How [moduleName] — a bare module name such as `superplayer-moq`, as [publishedModules] and
 * [unpublishedModules] return them — is carried by [settingsScript], or `null` if it is carried by
 * neither list. A caller treats `null` as a failure rather than as a default; see [ModulePublication].
 */
fun modulePublicationOf(settingsScript: String, moduleName: String): ModulePublication? = when (moduleName) {
    in unpublishedModules(settingsScript) -> ModulePublication.NOT_PUBLISHED
    in publishedModules(settingsScript) -> ModulePublication.PUBLISHED
    else -> null
}

/**
 * An `include(...)` line that is not a comment, and each project path on it — the multi-argument
 * form included, since `include(":a", ":b")` declares the same two modules written differently.
 *
 * The path is read whatever it is called rather than only when it begins `superplayer-`. `demo/`
 * and `benchmark/` are separate builds entirely and `build-logic` arrives through `includeBuild`,
 * so every `include` here is a library module, and one added under some other name should be seen
 * rather than slip past a prefix.
 */
fun publishedModules(settingsScript: String): Set<String> =
    settingsScript.lineSequence()
        .filterNot(::isCommentLine)
        .filter { INCLUDE_LINE.containsMatchIn(it) }
        .flatMap { line -> PROJECT_PATH.findAll(line).map { it.groupValues[1] } }
        .toSet()

/**
 * The modules named in `settings.gradle.kts`'s `unpublishedModules` list.
 *
 * The list is included into the build by a `forEach` rather than by `include(...)` lines of its
 * own, which is what keeps [publishedModules] above unable to see it: the two sets are disjoint by
 * construction, so a module cannot be read as both.
 */
fun unpublishedModules(settingsScript: String): Set<String> {
    val lines = settingsScript.lines()
    val start = lines.indexOfFirst { UNPUBLISHED_LIST.containsMatchIn(it) }
    if (start < 0) return emptySet()

    // Up to and including the line that closes `listOf(`, so the list may be written over several
    // lines with a comment against each entry — which is where the reason a module is unpublished
    // belongs. The end is found by counting brackets over the code alone, for two reasons a
    // simpler reading got wrong: a comment explaining an entry may well contain a `)` and must not
    // end the list, and the single-line `listOf(":a")` form has to end on its own line rather than
    // running on to collect every path in the `include(...)` lines below and report the whole
    // build as unpublished.
    //
    // "The code alone" means a whole-line comment skipped *and* a trailing one cut, because both
    // spellings are ordinary here and an unbalanced count either truncates the list or swallows
    // it. Truncating is the dangerous direction — a module the parser stops short of is published
    // by the convention plugin, which is the one outcome this whole mechanism exists to prevent.
    val body = mutableListOf<String>()
    var depth = 0
    for (line in lines.drop(start)) {
        if (isCommentLine(line)) continue
        val code = line.substringBefore("//")
        body += code
        depth += code.count { it == '(' } - code.count { it == ')' }
        if (depth <= 0) break
    }

    return body
        .flatMap { line -> PROJECT_PATH.findAll(line).map { it.groupValues[1] }.toList() }
        .toSet()
}

private val INCLUDE_LINE = Regex("""^\s*include\(""")
private val UNPUBLISHED_LIST = Regex("""^\s*val\s+unpublishedModules\s*=\s*listOf\(""")
private val PROJECT_PATH = Regex(""""::?([\w-]+)"""")
