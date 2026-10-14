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
 * The comparison ADR-0017 rule 2 makes mechanical: the API surface recorded at the last release,
 * against the surface tracked today, judged by the version the catalog proposes.
 *
 * This is deliberately **not** the question `checkApiSurface` answers. That one asks whether the
 * tracked file still matches the code, and it reads compiled classes to do it. This one reads no
 * code at all: both sides of it are files a human reviewed, and what it judges is the *number*
 * against the difference between them. Conflating the two would make a legitimate surface change
 * and an illegitimate version one failure with one message.
 *
 * [describeApiSurfaceDrift] is the neighbouring function and is deliberately **not** generalised
 * into this one. It reports a line-for-line divergence and is sensitive to order on purpose — same
 * lines in a different order is a fact it reports, because BCV sorts its output and a re-ordering
 * means the dump format itself moved. This one must be *insensitive* to order for exactly the same
 * reason: the file is sorted, so a re-ordering carries no information about compatibility and a
 * check that read it as a removal-plus-addition would call every dump-format change a major. One
 * function serving both would need a flag deciding whether order matters, and the two failure
 * messages have not a sentence in common.
 */

/**
 * Where the record lives, relative to the repository root, named in every failure message.
 *
 * Public rather than internal because `superplayer.verification.gradle.kts` points both tasks at
 * the same directory, and a path spelled once in Kotlin and again in a build script is a path that
 * can be moved in one place only.
 */
const val RECORDED_SURFACE_DIRECTORY = "api/released"

/**
 * The file inside it naming the release the surfaces were taken at. It is what tells "nothing has
 * ever been released" from "a release was recorded", and the release command writes it with them.
 *
 * Public for [RECORDED_SURFACE_DIRECTORY]'s reason: the build script names it too.
 */
const val RECORDED_VERSION_FILE = "version.txt"

/**
 * How a surface difference is classified, and the whole of what "incompatible" means here.
 *
 * The unit is a **declaration**, never a line of text, and a declaration's identity is the part of
 * the dump the JVM would link against:
 *
 * - a class is identified by its binary name, and its header line carries its modifiers and its
 *   supertypes;
 * - a member is identified by its owning class, its kind (`fun` or `field`), its name and its JVM
 *   descriptor — which is what a call site is compiled against.
 *
 * From that, three readings and nothing else:
 *
 * - **Removed** — an identity the record had and the tree does not. Breaking, and the easy case.
 * - **Changed** — the same identity, different line. For a member that is a modifier: `final`
 *   gained, `static` gained. For a class it is a modifier or a **supertype list**, which is how a
 *   widened supertype requirement is caught. Breaking.
 * - **Added** — an identity the tree has and the record does not. Additive.
 *
 * The cases a textual diff gets wrong all fall out of that rather than being special-cased. A
 * **changed parameter or return type** moves the descriptor, so it is a removal beside an addition
 * and reads as breaking. A **member moved between a class and its companion** changes its owner,
 * likewise. A **re-ordering** moves no identity at all and is not a difference, which is the whole
 * reason the identities are a map rather than the lines being subtracted.
 *
 * Where this errs it errs upward, and that direction is deliberate: a class that gained a supertype
 * or lost `final` is reported as breaking although neither breaks a caller. ADR-0017 rule 3 already
 * makes the surface a **floor** on the version and never a ceiling, so a check that demanded too
 * large a bump is wrong in the direction that costs a release number, and one that demanded too
 * small a bump is wrong in the direction that costs an adopter a build.
 */
private enum class Reading { REMOVED, CHANGED, ADDED }

/** The bump a set of readings requires, given whether the previous release was still `0.x`. */
private fun Set<Reading>.requiredBump(previousMajorIsZero: Boolean): Bump = when {
    // spec: Semantic Versioning 2.0.0 item 8 — a major is incremented when an incompatible API
    // change is made. ADR-0017 rule 7 is what the `0.x` arm is: under a zero major, "a minor
    // release may remove or change public declarations", because a major has nowhere to go.
    contains(Reading.REMOVED) || contains(Reading.CHANGED) ->
        if (previousMajorIsZero) Bump.MINOR else Bump.MAJOR

    // spec: Semantic Versioning 2.0.0 item 7 — a minor is incremented when functionality is added
    // in a backwards-compatible manner.
    contains(Reading.ADDED) -> Bump.MINOR

    // spec: Semantic Versioning 2.0.0 item 6 — a patch is for backwards-compatible bug fixes, which
    // is what a release whose tracked surface did not move is.
    else -> Bump.PATCH
}

/** One classified difference, carrying the words the failure message prints. */
private data class Departure(val module: String, val reading: Reading, val declaration: String)

/**
 * Returns why the version the catalog proposes cannot describe the API surface change since the
 * last release, or null when it can.
 *
 * [recordedVersion] and [recorded] are `api/released/`: the version that record was taken at, and
 * each published module's surface as it was released, keyed by module name. [current] is the same
 * keying over the `<module>/api/<module>.api` files tracked today. [catalog] is
 * `gradle/libs.versions.toml`, whose `superplayer` entry is the version being proposed.
 *
 * **A first release passes.** ADR-0017's *What this costs the first release* says so in as many
 * words: the library has never been released, rule 2 has no previous surface to compare against,
 * and the first version is a declaration rather than a derivation. The record's **absence** is how
 * that state is spelled — no version file and no surfaces — rather than thirteen empty files, which
 * would read as thirteen modules that published nothing and would make every declaration in the
 * repository an addition, failing the very first release it was meant to let through. A record that
 * is *half* there is a corrupted record and fails: the release command writes the version and all
 * thirteen surfaces in one step, so neither half can legitimately exist without the other.
 *
 * A **snapshot is judged on the release it will become** (ADR-0017 rule 8), since that is the
 * number that will ship and the suffix is not a bump of its own.
 */
internal fun findVersionBumpMismatch(
    recordedVersion: String?,
    recorded: Map<String, String>,
    current: Map<String, String>,
    catalog: String
): String? {
    val proposed = catalogVersion(catalog)
        ?: return catalogVersionProblem(catalog) +
            " The API surface cannot be judged against a version it cannot read; `verifyVersion` " +
            "reports the same entry."

    if (recordedVersion == null) {
        if (recorded.isEmpty()) return null
        return "$RECORDED_SURFACE_DIRECTORY/ records ${recorded.size} module surfaces but no " +
            "$RECORDED_VERSION_FILE naming the release they were taken at, so there is nothing to " +
            "compare the proposed version against. The release command writes both in one step; a " +
            "record with one half missing was edited by hand."
    }
    if (recorded.isEmpty()) {
        return "$RECORDED_SURFACE_DIRECTORY/$RECORDED_VERSION_FILE names $recordedVersion as the " +
            "last release but no module surface is recorded beside it, so no difference can be " +
            "computed. The release command writes both in one step; a record with one half missing " +
            "was edited by hand."
    }

    val previous = SemanticVersion.parse(recordedVersion)
        ?: return "$RECORDED_SURFACE_DIRECTORY/$RECORDED_VERSION_FILE reads \"$recordedVersion\", " +
            "which is not ${SemanticVersion.GRAMMAR}."
    if (previous.isSnapshot) {
        return "$RECORDED_SURFACE_DIRECTORY/$RECORDED_VERSION_FILE reads \"$recordedVersion\", and " +
            "ADR-0017 rule 8 is that a snapshot is not a release. Nothing published from one is " +
            "covered, so its surface is not a baseline anything can be compared against."
    }

    val departures = departures(recorded, current)
    val required = departures.mapTo(mutableSetOf()) { it.reading }.requiredBump(previous.major == 0)
    val release = proposed.released

    val actual = release.bumpFrom(previous)
        ?: return "gradle/libs.versions.toml sets superplayer = \"$proposed\", which releases " +
            "$release, and $RECORDED_SURFACE_DIRECTORY/$RECORDED_VERSION_FILE already records " +
            "$previous as released. A release moves the version forward; this one does not."

    if (actual >= required) return null

    return buildString {
        appendLine(
            "gradle/libs.versions.toml sets superplayer = \"$proposed\", which releases $release: " +
                "a ${actual.name.lowercase()} on $previous, the version " +
                "$RECORDED_SURFACE_DIRECTORY/$RECORDED_VERSION_FILE records. The tracked API " +
                "surface has moved further than a ${actual.name.lowercase()} can describe."
        )
        appendLine()
        appendDepartures(departures)
        appendLine(
            "ADR-0017 rule 2: a declaration removed or incompatibly changed is a major, one added " +
                "is a minor, an unmoved surface a patch." +
                if (previous.major == 0) {
                    " Rule 7: under 0.x a minor may break, which is why this asks for a minor and " +
                        "not a major."
                } else {
                    ""
                }
        )
        appendLine(
            "The smallest version that describes this change is ${required.smallestFrom(previous)}."
        )
        appendLine()
        appendLine(
            "Rule 3 is the other half and no task can check it: a behaviour change that moves no " +
                "declaration is still a major. This is the floor, never the ceiling."
        )
    }
}

/**
 * Prints at most [DEPARTURES_SHOWN] of each reading. A module renamed or a Compose compiler applied
 * moves hundreds of lines at once, and a failure message nobody scrolls to the end of is one that
 * names nothing.
 */
private const val DEPARTURES_SHOWN = 10

private fun StringBuilder.appendDepartures(departures: List<Departure>) {
    for (reading in Reading.entries) {
        val matching = departures.filter { it.reading == reading }
        if (matching.isEmpty()) continue
        appendLine("${reading.name.lowercase().replaceFirstChar { it.uppercase() }}:")
        matching.take(DEPARTURES_SHOWN).forEach { appendLine("  ${it.module}  ${it.declaration}") }
        if (matching.size > DEPARTURES_SHOWN) {
            appendLine("  … and ${matching.size - DEPARTURES_SHOWN} more")
        }
        appendLine()
    }
}

/**
 * Every declaration that differs between the recorded and the tracked surfaces, across every module
 * either side names.
 *
 * A module only [current] has is a **new module**, whose declarations are additions — which is the
 * honest reading of a module that did not exist at the last release. A module only [recorded] has
 * lost its whole surface and every declaration in it is removed.
 */
private fun departures(recorded: Map<String, String>, current: Map<String, String>): List<Departure> =
    (recorded.keys + current.keys).sorted().flatMap { module ->
        val before = parseApiSurface(recorded[module].orEmpty())
        val after = parseApiSurface(current[module].orEmpty())

        val removedOrChanged = before.mapNotNull { (identity, line) ->
            when (after[identity]) {
                null -> Departure(module, Reading.REMOVED, line)
                line -> null
                else -> Departure(module, Reading.CHANGED, "$line  ->  ${after[identity]}")
            }
        }
        val added = after.filterKeys { it !in before }.map { Departure(module, Reading.ADDED, it.value) }
        removedOrChanged + added
    }

/**
 * A declaration's identity: the class's binary name, and for a member the kind, name and descriptor
 * within it. Null [member] is the class header itself.
 */
private data class Identity(val className: String, val member: String?)

/**
 * Reads a `binary-compatibility-validator` dump into identity-to-line pairs.
 *
 * The format is documented in `docs/api-surface.md` and is what `renderApiSurface` writes: a class
 * header at column zero ending in `{`, its members each on a tab-indented line, a `}` closing it,
 * and a blank line between classes. BCV sorts classes and members, which is what makes the file
 * reviewable — and is why nothing here relies on the order.
 *
 * A line that cannot be read is keyed by its whole text rather than dropped — a header with no
 * `class` keyword as much as a member with no `fun` or `field`. Neither can arise from a dump this
 * build wrote, and a check that silently ignored what it did not understand would be a check that a
 * format change switches off.
 */
// ref: JetBrains binary-compatibility-validator's canonical dump format, which `renderApiSurface`
// writes through that library's own `dump()` and which docs/api-surface.md describes. The order is
// the library's, sorted, and is deliberately not relied on here.
private fun parseApiSurface(surface: String): Map<Identity, String> {
    val declarations = LinkedHashMap<Identity, String>()
    var className = ""

    for (raw in surface.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() || line == "}" -> Unit

            raw.first().isWhitespace() -> declarations[Identity(className, memberIdentity(line))] = line

            else -> {
                // The delimiter's absence keeps the whole line as the name rather than silently
                // yielding "public", which every unreadable header would collide on.
                className = line.substringAfter(" class ", missingDelimiterValue = line)
                    .substringBefore(' ')
                declarations[Identity(className, null)] = line
            }
        }
    }
    return declarations
}

/**
 * The part of a member line a call site is linked against: its kind, its name and its JVM
 * descriptor, which is everything from the `fun` or `field` keyword onward. What is dropped is the
 * modifier prefix — `public`, `final`, `static`, `synthetic`, `abstract` — so that a member whose
 * modifiers changed keeps its identity and is reported as changed rather than as a removal beside
 * an unrelated addition.
 */
// spec: JVMS SE 17 §4.3.3 and §4.3.2 — a method descriptor is the parameter and return types, and
// a field descriptor the field's type; together with the name they are what a `Methodref` or
// `Fieldref` in a consumer's class file resolves against, which is why they are the identity here
// and the modifiers are not.
private fun memberIdentity(line: String): String {
    val tokens = line.split(' ')
    val kind = tokens.indexOfFirst { it == "fun" || it == "field" }
    return if (kind == -1) line else tokens.drop(kind).joinToString(" ")
}

/**
 * The files the release command writes into `api/released/`, as published name to content: each
 * module's tracked surface under its own name, and the version they were taken at.
 *
 * It is a function rather than a task action because **#329**'s release command re-records the set
 * as one step of cutting a release, and a step reachable only by running a task is a step that has
 * to be re-implemented to be reached.
 */
internal fun releasedApiSurfaceRecord(tracked: Map<String, String>, version: String): Map<String, String> =
    tracked.mapKeys { (module, _) -> "$module.$API_EXTENSION" } + (RECORDED_VERSION_FILE to "$version\n")

/**
 * The names already in the record directory that [record] does not write, and that the release
 * command therefore has to delete: a module that stopped publishing leaves its recorded surface
 * behind otherwise, and the next comparison would read it as a whole module removed for as long as
 * it sat there.
 *
 * Only surfaces are answered. Everything else in that directory is somebody's — the `README.md`
 * most of all — and a recording step that deleted what it did not recognise would take it out.
 */
internal fun staleRecordedSurfaces(present: Collection<String>, record: Map<String, String>): List<String> =
    present.filter { it.endsWith(".$API_EXTENSION") && it !in record }

/** The extension every tracked and recorded surface carries, as `docs/api-surface.md` names it. */
internal const val API_EXTENSION = "api"
