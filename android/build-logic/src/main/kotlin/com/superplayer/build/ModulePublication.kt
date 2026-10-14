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
 * The answer is a **declared state** rather than switches that happen to agree.
 * `settings.gradle.kts` says once how each module is carried; [publishedModules],
 * [locallyPublishedModules] and [unpublishedModules] read that one file; and everything that has
 * to know asks here:
 *
 *  - the convention plugin, which registers a Maven publication for anything but
 *    [NOT_PUBLISHED], and a tracked API surface for [PUBLISHED] alone —
 *    `docs/api-surface.md` tracks a surface because a *consumer* resolves it;
 *  - [findCompatibilityDocumentViolations], which requires the row of a module an adopter cannot
 *    resolve to read **Not published** and every other row not to.
 *
 * ## Why there are three and not two (#353)
 *
 * [NOT_PUBLISHED] was the whole of #364's answer, and it turned out to say two things at once: *no
 * adopter may resolve this*, which is still true of `superplayer-moq` and is #369's to change, and
 * *no artifact exists at all*, which made `demo/` unable to name the module. The demo is a separate
 * build that resolves published coordinates through `mavenLocal()`, so a module with no publication
 * is one the demo cannot show — and `PRD.md`'s Phase 14 exit criterion is a broadcast playing **in
 * the demo**.
 *
 * [LOCAL_ONLY] separates them. It is an artifact `publishToMavenLocal` produces on the one machine
 * that can build the module's native half, for that machine's own `demo/` build to resolve, and it
 * is **nothing an adopter can reach**: the compatibility table still reads *Not published*, no API
 * surface is tracked, and `docs/releasing.md` says what a release does and does not mean for one.
 * The licence and ABI questions #369 holds are about *distribution*, and a coordinate that exists
 * only in the local repository of the machine that built it distributes nothing.
 *
 * The failure mode is deliberate in the safe direction. A module declared in no list is not
 * silently published: [modulePublicationOf] answers `null` and the convention plugin fails naming
 * the module, so a declaration this file cannot read stops the build rather than shipping an
 * artifact nobody meant to ship.
 */
enum class ModulePublication {
    /** In `settings.gradle.kts`'s `include(...)` lines: released with the rest at one version. */
    PUBLISHED,

    /**
     * In its `locallyPublishedModules` list: an artifact for the local Maven repository of the
     * machine that built it, so `demo/` can resolve it, and no adopter's to reach.
     */
    LOCAL_ONLY,

    /** In its `unpublishedModules` list: in the build, and deliberately not an artifact. */
    NOT_PUBLISHED,
}

/**
 * Whether an adopter can resolve a module of this kind — the one question `docs/compatibility.md`
 * answers, and the reason [LOCAL_ONLY] and [NOT_PUBLISHED] share a row status while differing in
 * everything the build does with them.
 */
val ModulePublication.isResolvableByAnAdopter: Boolean
    get() = this == ModulePublication.PUBLISHED

/**
 * How [moduleName] — a bare module name such as `superplayer-moq`, as [publishedModules] and
 * [unpublishedModules] return them — is carried by [settingsScript], or `null` if it is carried by
 * neither list. A caller treats `null` as a failure rather than as a default; see [ModulePublication].
 */
fun modulePublicationOf(settingsScript: String, moduleName: String): ModulePublication? = when (moduleName) {
    in unpublishedModules(settingsScript) -> ModulePublication.NOT_PUBLISHED
    in locallyPublishedModules(settingsScript) -> ModulePublication.LOCAL_ONLY
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
 * own, which is what keeps [publishedModules] above unable to see it: the sets are disjoint by
 * construction, so a module cannot be read as two of them.
 */
fun unpublishedModules(settingsScript: String): Set<String> =
    modulesInList(settingsScript, "unpublishedModules")

/**
 * The modules named in `settings.gradle.kts`'s `locallyPublishedModules` list — [ModulePublication.LOCAL_ONLY].
 *
 * Read exactly as [unpublishedModules] is, and included into the build the same way, so that the
 * disjointness above holds across all three lists rather than only across two.
 */
fun locallyPublishedModules(settingsScript: String): Set<String> =
    modulesInList(settingsScript, "locallyPublishedModules")

/** The project paths in the `val <name> = listOf(...)` declaration, or empty if there is none. */
private fun modulesInList(settingsScript: String, name: String): Set<String> {
    val lines = settingsScript.lines()
    // The `<String>` form matters: an empty list has to be written `listOf<String>()` for Kotlin to
    // infer anything, and a list that has emptied out is exactly when misreading it as absent would
    // be invisible.
    val declaration = Regex("""^\s*val\s+$name\s*=\s*listOf(<[^>]*>)?\(""")
    val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
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
private val PROJECT_PATH = Regex(""""::?([\w-]+)"""")
