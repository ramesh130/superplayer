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

/** What a cut reads: the four tracked artefacts its refusals and its rewrites are computed from. */
internal data class ReleaseSources(
    val catalog: String,
    val changelog: String,
    /** `api/released/version.txt`, or null before the first release — which is the state today. */
    val recordedVersion: String?,
    val recordedSurfaces: Map<String, String>,
    val trackedSurfaces: Map<String, String>
)

/** What version control says, as the two commands that say it: `status --porcelain` and `tag`. */
internal data class WorkingCopy(
    val changedPaths: List<String>,
    val tags: List<String>
)

/** Every byte a cut writes, decided before any of it is written. */
internal data class ReleasePlan(
    val release: SemanticVersion,
    val nextSnapshot: SemanticVersion,
    val tag: String,
    val releaseCatalog: String,
    val nextSnapshotCatalog: String,
    val stampedChangelog: String,
    /** `api/released/`, by file name: the thirteen surfaces and `version.txt`, as ADR-0017 rule 1 pairs them. */
    val recordedSurfaces: Map<String, String>
)

internal sealed interface ReleasePlanning {
    data class Planned(val plan: ReleasePlan) : ReleasePlanning

    data class Refused(val reason: String) : ReleasePlanning
}

/**
 * The tag a release is named by. `v` and the version, which is what `git describe` and every host's
 * release page assume; nothing in this repository has tagged anything yet, so this line is the
 * convention rather than a restatement of one, and `docs/releasing.md` states it for a reader.
 */
internal fun releaseTag(version: SemanticVersion): String = "v$version"

/**
 * The snapshot a cut leaves the catalog on. ADR-0017 rule 8 forbids leaving the released version
 * in the tree, so something has to be opened, and it is the smallest successor — a patch — because
 * what the *next* release will be is rule 2's to derive from a surface diff that has not happened
 * yet. Opening a minor here would be this command guessing at it, and a guess in the catalog reads
 * exactly like a decision.
 */
internal fun nextSnapshotAfter(release: SemanticVersion): SemanticVersion =
    release.copy(patch = release.patch + 1, isSnapshot = true)

/**
 * Everything `./gradlew release` decides, as a function of text.
 *
 * It takes what the tree says — the catalog, the changelog, the recorded surfaces, the tracked
 * surfaces, `git status --porcelain` and the tag list — and answers either every byte the cut will
 * write or the one refusal that stops it, without touching a file, a process or a clock.
 * [CutRelease] is the effectful remainder: read, plan, write, commit, publish, tag.
 *
 * The split is `ApiSurfaceBump.kt`'s and `VerifyVersion.kt`'s, for their reason: a refusal is worth
 * having only if its exact wording is tested, and wording is testable here as string literals
 * rather than as a release performed against a throwaway repository. Every refusal #329 names is
 * below except one — a failing `check`, which is a task dependency and therefore a line in
 * `superplayer.verification.gradle.kts` that `ReleaseWiringTest` reads.
 *
 * [today] is a parameter rather than a clock for the same reason: the dated heading is part of what
 * a cut writes, so it has to be part of what a test can state.
 *
 * One judgement is deliberately **not** made here. Whether the version is large enough for the API
 * surface that moved is [findVersionBumpMismatch]'s — the same function `verifyVersionBump` runs in
 * `check` — asked with the catalog this cut *would* write. ADR-0017 rule 2 makes the tracked surface
 * the arbiter of the bump, and a release command with its own second opinion about what a removal
 * costs would be a second arbiter.
 */
internal fun planRelease(
    asked: String,
    sources: ReleaseSources,
    workingCopy: WorkingCopy,
    today: String
): ReleasePlanning {
    // First, because every later step writes to this tree: a cut that started from uncommitted work
    // would tag a commit that is not what was published, and the diff a reviewer reads afterwards
    // would carry someone's work in progress under a release message.
    if (workingCopy.changedPaths.isNotEmpty()) {
        return refuse(
            "The working tree is not clean; `git status --porcelain` reports " +
                "${workingCopy.changedPaths.size} path(s):\n" +
                workingCopy.changedPaths.take(PATHS_SHOWN).joinToString("\n") { "  $it" } +
                (if (workingCopy.changedPaths.size > PATHS_SHOWN) "\n  …" else "") +
                "\nCommit or discard them and run the command again: a release is cut from a tree " +
                "that is exactly what gets tagged and published."
        )
    }

    val release = SemanticVersion.parse(asked)
        ?: return refuse(
            "--release-version=$asked is not ${SemanticVersion.GRAMMAR}. Pass the version being released, " +
                "as in `./gradlew release --release-version=1.2.0`."
        )
    if (release.isSnapshot) {
        return refuse(
            "--release-version=$asked names a snapshot, and ADR-0017 rule 8 is that a snapshot is not a " +
                "release: nothing published from one is covered by any promise. Pass the release " +
                "itself, `--release-version=${release.released}`; the command opens the next snapshot on " +
                "its own as its last step."
        )
    }

    val previous = when (val recorded = sources.recordedVersion) {
        null -> null

        else -> SemanticVersion.parse(recorded)
            ?: return refuse(
                "$RECORDED_SURFACE_DIRECTORY/$RECORDED_VERSION_FILE reads \"$recorded\", which is " +
                    "not ${SemanticVersion.GRAMMAR}, so there is no last release to succeed. That " +
                    "file is written by this command alone; restore it from the last release's " +
                    "commit."
            )
    }

    // A successor is one of the three versions semver can reach from the last release in one step.
    // Skipping — 0.4.0 after 0.1.0 — is legal semver and is refused anyway: under ADR-0017 rule 2
    // the bump is derived from a surface diff, so a version larger than the derivation is a number
    // nothing accounts for, and the gap reads to an adopter as a release they missed.
    if (previous != null && release !in successorsOf(previous)) {
        return refuse(
            "--release-version=$release does not succeed $previous, the version " +
                "$RECORDED_SURFACE_DIRECTORY/$RECORDED_VERSION_FILE records as the last release. " +
                "The successors of $previous are " +
                successorsOf(previous).joinToString(", ") + "."
        )
    }

    val releaseCatalog = setCatalogVersion(sources.catalog, release)
        ?: return refuse(
            catalogVersionProblem(sources.catalog) +
                " There is no entry for this command to move to $release."
        )

    // `verifyVersionBump`'s own judgement, asked of the catalog this cut would write rather than of
    // the one on disk — which still says `-SNAPSHOT` at this point. ADR-0017 rule 2 has one arbiter.
    findVersionBumpMismatch(
        recordedVersion = sources.recordedVersion,
        recorded = sources.recordedSurfaces,
        current = sources.trackedSurfaces,
        catalog = releaseCatalog
    )?.let { mismatch ->
        return refuse(
            mismatch.trimEnd() +
                "\n\nThis is `verifyVersionBump`, asked about $release before anything was " +
                "written. Cut the version it names instead."
        )
    }

    val stamped = when (val stamp = stampRelease(sources.changelog, release, today)) {
        is ChangelogStamp.Refused -> return refuse(stamp.reason)
        is ChangelogStamp.Written -> stamp.text
    }

    val tag = releaseTag(release)
    if (tag in workingCopy.tags) {
        return refuse(
            "The tag $tag already exists, so $release has been cut before. Pick the next version, " +
                "or — if that tag was cut wrongly and nothing has been shared — delete it with " +
                "`git tag -d $tag` and run the command again."
        )
    }

    val nextSnapshot = nextSnapshotAfter(release)
    // From the catalog on disk rather than from `releaseCatalog`: one entry is being rewritten
    // either way, and deriving the second from the first would make a defect in the first
    // invisible in the second. The null arm is unreachable — the refusal above proves the entry is
    // there — and is a refusal rather than a default because what a default would write is an
    // *empty* catalog, at the last step of a cut, after the tag exists. This file's premise is that
    // every byte is decided before any is written; a fallback here is the one line that opts out.
    val nextSnapshotCatalog = setCatalogVersion(sources.catalog, nextSnapshot)
        ?: return refuse(
            catalogVersionProblem(sources.catalog) +
                " The release version could be written and the next snapshot could not, which " +
                "should not be reachable; nothing was written."
        )

    return ReleasePlanning.Planned(
        ReleasePlan(
            release = release,
            nextSnapshot = nextSnapshot,
            tag = tag,
            releaseCatalog = releaseCatalog,
            nextSnapshotCatalog = nextSnapshotCatalog,
            stampedChangelog = stamped,
            recordedSurfaces = releasedApiSurfaceRecord(sources.trackedSurfaces, release.toString())
        )
    )
}

internal sealed interface ChangelogStamp {
    data class Written(val text: String) : ChangelogStamp

    data class Refused(val reason: String) : ChangelogStamp
}

/**
 * `CHANGELOG.md` with its `Unreleased` section dated as [version] and a fresh empty `Unreleased`
 * opened above it.
 *
 * The section moves whole rather than being summarised: what was written under `Unreleased` while
 * the work landed is what the release notes are, and a command that re-wrote them would be writing
 * release notes nobody reviewed.
 *
 * An empty section is a refusal rather than an empty heading, and that is the point of the check:
 * ADR-0017 rule 3 says a behaviour change that moves no declaration is still a major and that no
 * tracked `.api` file records it, so this row is the only place that judgement is written down.
 * A release with nothing under it is a release that did not record one.
 */
internal fun stampRelease(changelog: String, version: SemanticVersion, today: String): ChangelogStamp {
    val heading = CHANGELOG_HEADING.findAll(changelog)
        .firstOrNull { it.groupValues[1].equals(UNRELEASED, ignoreCase = true) }
        ?: return ChangelogStamp.Refused(
            "CHANGELOG.md has no \"## [$UNRELEASED]\" heading, so there is nothing to date as " +
                "$version. Open one above the most recent release and write what changed under it."
        )

    val bodyStart = heading.range.last + 1
    val bodyEnd = CHANGELOG_HEADING.findAll(changelog)
        .firstOrNull { it.range.first >= bodyStart }
        ?.range?.first
        ?: changelog.length
    val body = changelog.substring(bodyStart, bodyEnd).trim()
    if (body.isEmpty()) {
        return ChangelogStamp.Refused(
            "CHANGELOG.md's \"## [$UNRELEASED]\" section is empty, so $version would be released " +
                "with nothing said about it. Write what changed under that heading first — it is " +
                "the only record of a behaviour change that moved no declaration (ADR-0017 rule 3)."
        )
    }

    val rest = changelog.substring(bodyEnd)
    return ChangelogStamp.Written(
        changelog.substring(0, heading.range.first) +
            "## [$UNRELEASED]\n\n" +
            // ref: Keep a Changelog 1.1.0 — the version in brackets, the hyphen, and an ISO 8601
            // date. `verifyVersion` reads this heading back and holds the catalog to it.
            "## [$version] - $today\n\n" +
            body +
            (if (rest.isEmpty()) "\n" else "\n\n") +
            rest
    )
}

/** The catalog with its one `superplayer` entry moved to [version], or null if it has none. */
internal fun setCatalogVersion(catalog: String, version: SemanticVersion): String? {
    val match = CATALOG_VERSION.find(catalog) ?: return null
    return catalog.replaceRange(match.range, "superplayer = \"$version\"")
}

/**
 * How many uncommitted paths the dirty-tree refusal lists. `ApiSurfaceBump.kt`'s `DEPARTURES_SHOWN`
 * for its reason — a message nobody scrolls to the end of names nothing — and the same number,
 * because the reader of either is looking for whether they recognise the work in the list rather
 * than counting it, and `git status` is a keystroke away when they do not.
 */
private const val PATHS_SHOWN = 10

private fun successorsOf(previous: SemanticVersion): List<SemanticVersion> =
    Bump.entries.map { it.smallestFrom(previous) }

private fun refuse(reason: String): ReleasePlanning = ReleasePlanning.Refused(reason)
