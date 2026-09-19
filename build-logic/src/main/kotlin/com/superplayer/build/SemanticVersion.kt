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
 * [compareTo] is the "compare" half, and it is **declared here and reached by nothing in this
 * check** — the shape `RetryPolicy.licence` takes, said out loud for the same reason. It is here
 * for **#328**, the gate that compares the bump being proposed against the surface diff since the
 * last release, and **#329**, the release command that has to refuse a version that did not move.
 * Both land on this type rather than each parsing the string again, and ordering is the one
 * operation either needs beyond reading one.
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
