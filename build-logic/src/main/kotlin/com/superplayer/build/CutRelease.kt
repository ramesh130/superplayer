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
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import java.io.File
import java.time.LocalDate

/**
 * `./gradlew release --version=<x.y.z>`: the whole of a release cut, or none of it.
 *
 * ADR-0017 rule 8 is what this task exists to keep — a snapshot is not a release, so no release
 * procedure may publish one under a release tag or leave the catalog on the version just published.
 * Six steps have to happen in one order for that to hold, and doing five of them by hand is how a
 * record of the released API surface silently fails to be taken, disarming `verifyVersionBump` for
 * the whole of the next cycle. The steps are:
 *
 * 1. **the full `check` is green**, which is a task dependency rather than a claim — see below;
 * 2. the tree is clean, the version is a legal successor, it is no smaller than the surface diff
 *    requires, the `Unreleased` section is not empty and the tag is free — all six refusals are
 *    [planRelease]'s, decided before a byte is written;
 * 3. the catalog drops its `-SNAPSHOT`, `CHANGELOG.md`'s `Unreleased` section becomes a dated
 *    heading with a fresh empty one above it, and `api/released/` is re-recorded;
 * 4. those three are committed — the release commit;
 * 5. `publishToMavenLocal` publishes the thirteen modules at that version;
 * 6. the release commit is tagged, and a second commit opens the next snapshot.
 *
 * **This task writes to git, and that is the argued half of it.** It could have written the files
 * and printed `git commit` and `git tag` for a human to run, which is the choice that cannot
 * surprise anyone. It does not, for a reason particular to rule 8: the version being released has
 * to exist *as a commit* for a tag to name it, while the tree has to end on the next snapshot. One
 * final working tree cannot be both, so a command that only printed would be handing back a tree in
 * the wrong state and asking its reader to reconstruct the intermediate one — which is precisely
 * the half-done cut this task exists to prevent. What it does instead is bounded: two commits and
 * one tag, all local, all named in the refusal message if a later step fails, and nothing in this
 * file can reach a remote. **It never pushes**, and `docs/releasing.md` says how to undo it.
 *
 * **How `check` being green is established.** The task depends on it, so Gradle runs the whole of
 * `check` before this action starts and the build stops at the first failure without any of the
 * above happening. That is neither trusting the operator's word nor running a second build inside
 * this one: it is the ordinary task graph. The cost is that a refusal about the changelog arrives
 * after a check the tree was going to have to pass anyway — run `./gradlew check` first and the
 * release run's is up to date.
 *
 * **Publishing is the one step that forks a second Gradle invocation**, and it has to. Every
 * module's `version` is read from the catalog at *configuration* time, so the publication tasks of
 * this build were configured before step 3 moved it and would publish the snapshot. A child
 * invocation configures again, against the catalog as it now reads, and publishes the release.
 * Where those artefacts land is `mavenLocal()` and nowhere else — there is no publish repository in
 * this repository at all — which `docs/releasing.md` states plainly rather than letting an adopter
 * infer that a cut release is downloadable.
 *
 * Untracked for two reasons at once: it mutates its own inputs, and "up to date" is not a thing a
 * release can be.
 */
@UntrackedTask(because = "a release mutates the tree it reads, publishes and commits; it is never up to date")
abstract class CutRelease : DefaultTask() {

    @get:Input
    @get:Option(option = "version", description = "The version to release, as MAJOR.MINOR.PATCH.")
    abstract val version: Property<String>

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @get:Internal
    abstract val versionCatalog: RegularFileProperty

    @get:Internal
    abstract val changelog: RegularFileProperty

    @get:Internal
    abstract val recordDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val trackedApiFiles: ConfigurableFileCollection

    @TaskAction
    fun cut() {
        val root = projectRoot.get().asFile
        val catalogFile = versionCatalog.get().asFile
        val changelogFile = changelog.get().asFile

        val planning = planRelease(
            asked = version.get(),
            sources = ReleaseSources(
                catalog = catalogFile.readText(),
                changelog = changelogFile.readText(),
                recordedVersion = File(recordDirectory.get().asFile, RECORDED_VERSION_FILE)
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim(),
                recordedSurfaces = recordDirectory.get().asFile.listFiles().orEmpty()
                    .filter { it.extension == API_EXTENSION }
                    .surfacesByModule(),
                trackedSurfaces = trackedApiFiles.files.surfacesByModule()
            ),
            workingCopy = WorkingCopy(
                changedPaths = git(root, "status", "--porcelain").lines().filter { it.isNotBlank() },
                tags = git(root, "tag", "--list").lines().filter { it.isNotBlank() }
            ),
            // The host's date rather than a commit's: the heading says when the release was cut,
            // which is the day someone can take it, and Keep a Changelog's dates are local ones.
            today = LocalDate.now().toString()
        )

        val plan = when (planning) {
            is ReleasePlanning.Refused -> throw GradleException(
                "This release was not cut, and nothing was written.\n\n" + planning.reason
            )

            is ReleasePlanning.Planned -> planning.plan
        }

        // Read before the first write, so a failure part-way through can say exactly what to undo.
        val headBeforeCut = git(root, "rev-parse", "HEAD").trim()

        try {
            logger.lifecycle("Releasing ${plan.release} (tag ${plan.tag}).")

            catalogFile.writeText(plan.releaseCatalog)
            changelogFile.writeText(plan.stampedChangelog)
            writeRecordedSurfaces(recordDirectory.get().asFile, plan.recordedSurfaces)
            logger.lifecycle("  catalog, CHANGELOG.md and $RECORDED_SURFACE_DIRECTORY/ written.")

            git(root, "add", "--all")
            git(root, "commit", "--message", "Release ${plan.release}")
            logger.lifecycle("  committed.")

            publishToMavenLocal(root)
            logger.lifecycle("  published ${plan.release} to the local Maven repository.")

            // Annotated rather than lightweight: a release tag carries who cut it and when, and
            // `git describe` prefers one. It names the release commit, which is what was published.
            git(root, "tag", "--annotate", plan.tag, "--message", "SuperPlayer ${plan.release}")
            logger.lifecycle("  tagged ${plan.tag}.")

            // ADR-0017 rule 8's other half: the tree may not be left on the version just published.
            catalogFile.writeText(plan.nextSnapshotCatalog)
            git(root, "add", "--all")
            git(root, "commit", "--message", "Open ${plan.nextSnapshot}")
            logger.lifecycle("  catalog opened on ${plan.nextSnapshot}.")
        } catch (failure: Exception) {
            throw GradleException(
                "The release of ${plan.release} failed part-way through and the tree is mid-cut. " +
                    "Nothing has been pushed. To undo it whole:\n" +
                    "  git tag -d ${plan.tag}   # if it was reached\n" +
                    "  git reset --hard $headBeforeCut\n" +
                    "Artefacts already in the local Maven repository are overwritten by the next " +
                    "cut of the same version; docs/releasing.md says the rest.\n\n" +
                    failure.message,
                failure
            )
        }

        logger.lifecycle("Cut ${plan.release}. Nothing has been pushed; docs/releasing.md is what happens next.")
    }

    private fun publishToMavenLocal(root: File) {
        val result = run(root, File(root, "gradlew").absolutePath, "publishToMavenLocal")
        if (result.exitCode != 0) {
            throw GradleException(
                "`./gradlew publishToMavenLocal` failed while publishing the release:\n" +
                    result.output.lines().takeLast(PUBLISH_LINES_ON_FAILURE).joinToString("\n")
            )
        }
    }
}

/** How much of a failed publication's log is worth carrying into the refusal above it. */
private const val PUBLISH_LINES_ON_FAILURE = 40

private data class CommandResult(val exitCode: Int, val output: String)

private fun git(root: File, vararg arguments: String): String {
    val result = run(root, "git", *arguments)
    if (result.exitCode != 0) {
        throw GradleException("`git ${arguments.joinToString(" ")}` failed:\n${result.output}")
    }
    return result.output
}

private fun run(root: File, vararg command: String): CommandResult {
    val process = ProcessBuilder(*command)
        .directory(root)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    return CommandResult(process.waitFor(), output)
}
