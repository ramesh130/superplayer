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
 * A version of SuperPlayer itself, as ADR-0017 admits them: `MAJOR.MINOR.PATCH`, optionally
 * carrying the one pre-release suffix this repository uses, `-SNAPSHOT`.
 *
 * It is deliberately narrower than Semantic Versioning 2.0.0, which admits any dot-separated
 * pre-release identifiers and a `+build` suffix. Neither has ever appeared in
 * `gradle/libs.versions.toml` and ADR-0017 rule 8 gives `-SNAPSHOT` a meaning no other suffix
 * would carry here — unreleased, covered by none of the other rules — so parsing the wider grammar
 * would be accepting strings no rule in that record can judge.
 *
 * [compareTo] is the "compare" half. It was declared for **#328**, the gate that compares the bump
 * being proposed against the surface diff since the last release, and reached by nothing until that
 * ticket landed; [bumpFrom] is now what reads it, and **#329**'s release command — which has to
 * refuse a version that did not move — reads the same pair. Both land on this type rather than each
 * parsing the string again, and ordering is the one operation either needs beyond reading one.
 */
internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** ADR-0017 rule 8: the suffix means unreleased, and an artefact from one is covered by nothing. */
    val isSnapshot: Boolean
) : Comparable<SemanticVersion> {

    /** The release this snapshot names, which ADR-0017 rule 8 defines as the suffix removed. */
    val released: SemanticVersion
        get() = if (isSnapshot) copy(isSnapshot = false) else this

    override fun toString(): String =
        "$major.$minor.$patch" + if (isSnapshot) "-$SNAPSHOT_SUFFIX" else ""

    /**
     * Precedence as the specification defines it, including that a pre-release version ranks below
     * the release of the same numbers.
     */
    // spec: Semantic Versioning 2.0.0 item 11 — precedence is by major, minor, patch, and a
    // pre-release version has lower precedence than the associated normal version.
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { !it.isSnapshot })

    /**
     * Which of the three this version is, read as a move from [previous], or null when it is not a
     * move forward at all.
     *
     * This is the "is the bump at least a minor" question #328 asks, expressed once here rather
     * than as three comparisons spelled out at the gate: a release is a major when the major moved,
     * a minor when the minor moved under an unchanged major, and a patch otherwise.
     */
    fun bumpFrom(previous: SemanticVersion): Bump? = when {
        this <= previous -> null
        major > previous.major -> Bump.MAJOR
        minor > previous.minor -> Bump.MINOR
        else -> Bump.PATCH
    }

    companion object {
        const val SNAPSHOT_SUFFIX = "SNAPSHOT"

        /** How the grammar reads in a failure message, for someone mid-release looking at a typo. */
        const val GRAMMAR = "MAJOR.MINOR.PATCH, each a number without a leading zero, " +
            "optionally followed by `-$SNAPSHOT_SUFFIX`"

        // spec: Semantic Versioning 2.0.0 item 2 — each of the three is a non-negative integer and
        // must not carry a leading zero; item 9 — a pre-release is appended after a hyphen. The
        // alternation `0|[1-9]\d*` is that leading-zero clause written out, which is why `01.0.0`
        // is rejected rather than read as 1.0.0.
        private val SEMVER = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-$SNAPSHOT_SUFFIX)?$""")

        /** The version, or null when [text] is not one this repository admits. */
        fun parse(text: String): SemanticVersion? {
            val match = SEMVER.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, suffix) = match.destructured
            return SemanticVersion(
                major = major.toInt(),
                minor = minor.toInt(),
                patch = patch.toInt(),
                isSnapshot = suffix.isNotEmpty()
            )
        }
    }
}

/**
 * How far one release moved from the one before it, smallest first — so "at least a minor" is
 * `bump >= Bump.MINOR` rather than a pair of comparisons repeated at every reader.
 *
 * The three are Semantic Versioning's own, and what each of them *means about the API* is
 * ADR-0017 rule 2's, mechanised in [findVersionBumpMismatch].
 */
internal enum class Bump {
    // spec: Semantic Versioning 2.0.0 items 6, 7 and 8 — patch for a backwards-compatible fix,
    // minor for backwards-compatible added functionality, major for an incompatible API change.
    // Declared in that order because the ordinal is the ordering every caller compares on.
    PATCH,
    MINOR,
    MAJOR;

    /**
     * The smallest version that is this bump on [previous] — what a failure message offers instead
     * of leaving the arithmetic to whoever is reading it mid-release.
     */
    // spec: Semantic Versioning 2.0.0 items 7 and 8 — patch resets to 0 when minor is incremented,
    // and both reset when major is.
    fun smallestFrom(previous: SemanticVersion): SemanticVersion = when (this) {
        PATCH -> previous.copy(patch = previous.patch + 1)
        MINOR -> previous.copy(minor = previous.minor + 1, patch = 0)
        MAJOR -> previous.copy(major = previous.major + 1, minor = 0, patch = 0)
    }
}
