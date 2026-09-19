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
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/**
 * Fails the build when the version the catalog proposes is too small for what has happened to the
 * tracked API surface since the last release.
 *
 * ADR-0017 rule 2 makes the tracked surface the arbiter of the bump and #328 is the half of it the
 * record said was missing: `checkApiSurface` compares the tracked file against the code, which
 * makes a surface change visible and reviewed, and nothing compared the tracked file against the
 * one that was *released*. This does, and it reads no compiled class to do it — both sides are
 * files, and the judgement is [findVersionBumpMismatch]'s.
 *
 * It is a root task rather than a per-module one because ADR-0017 rule 1 releases all thirteen
 * modules at one number: the comparison is thirteen surfaces against one catalog entry, and thirteen
 * tasks each holding one third of a verdict could not name the bump the set requires.
 */
abstract class VerifyVersionBump : DefaultTask() {

    /**
     * Every published module's `api/<module>.api` as it is tracked today.
     *
     * Declared as a collection rather than thirteen properties so that a fourteenth module is
     * covered by existing it. A root task reading twenty-six files across thirteen directories has
     * to declare all of them or Gradle will call it up to date through a surface change.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val trackedApiFiles: ConfigurableFileCollection

    /**
     * `api/released/` — the surfaces recorded at the last release and the `version.txt` naming it.
     *
     * A collection, and legitimately **empty**: nothing has been released yet, and a missing
     * baseline is a pass rather than a failure. `@InputFile` would refuse to run the task at all.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val recordedApiFiles: ConfigurableFileCollection

    /** `gradle/libs.versions.toml`, whose `superplayer` entry is the version being proposed. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionCatalog: RegularFileProperty

    /** A stamp file written on success, so Gradle can skip the task when nothing it reads moved. */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val recorded = recordedApiFiles.files.filter { it.isFile }
        val mismatch = findVersionBumpMismatch(
            recordedVersion = recorded.firstOrNull { it.name == RECORDED_VERSION_FILE }?.readText()?.trim(),
            recorded = recorded.filter { it.extension == API_EXTENSION }.associate { it.moduleName() to it.readText() },
            current = trackedApiFiles.files.filter { it.isFile }.associate { it.moduleName() to it.readText() },
            catalog = versionCatalog.get().asFile.readText()
        )

        val stamp = stampFile.get().asFile
        if (mismatch == null) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
        } else {
            stamp.delete()
            throw GradleException(mismatch)
        }
    }
}

/**
 * Rewrites `api/released/` from the surfaces tracked today, under the version the catalog names.
 *
 * This is the recording step **#329**'s release command performs as part of cutting a release; it
 * is deliberately not in `check` and deliberately not run by anything else, because a record
 * refreshed to make [VerifyVersionBump] stop complaining is a record that has stopped meaning
 * anything. Its body is [releasedApiSurfaceRecord], a function over strings, so the command can
 * reach the step without re-implementing it.
 *
 * Untracked because the directory it writes into also holds a hand-written `README.md`, and a
 * task that declared the directory as its output would have Gradle delete that file as stale.
 */
@UntrackedTask(because = "api/released/ also holds a hand-written README the task must not own")
abstract class RecordReleasedApiSurface : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val trackedApiFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionCatalog: RegularFileProperty

    @get:Internal
    abstract val recordDirectory: DirectoryProperty

    @TaskAction
    fun record() {
        val catalog = versionCatalog.get().asFile.readText()
        val version = catalogVersion(catalog)
            ?: throw GradleException(
                catalogVersionProblem(catalog) + " There is no release to record a surface for."
            )

        // ADR-0017 rule 8: a snapshot is not a release. Recording one would write a baseline that
        // is byte-identical to a real release's and that names a version nobody shipped, and every
        // later bump would then be judged against it. `findVersionBumpMismatch` refuses a recorded
        // snapshot from the reading side; refusing it here is what keeps that state unreachable.
        if (version.isSnapshot) {
            throw GradleException(
                "gradle/libs.versions.toml sets superplayer = \"$version\", which ADR-0017 rule 8 " +
                    "says is not a release. Move the catalog to ${version.released} first, and " +
                    "record the surfaces under the version that is actually being published."
            )
        }

        val record = releasedApiSurfaceRecord(
            tracked = trackedApiFiles.files.filter { it.isFile }.associate { it.moduleName() to it.readText() },
            version = version.toString()
        )

        val directory = recordDirectory.get().asFile
        directory.mkdirs()
        val present = directory.listFiles().orEmpty().map { it.name }
        staleRecordedSurfaces(present, record).forEach { File(directory, it).delete() }
        record.forEach { (name, content) -> File(directory, name).writeText(content) }
    }
}

/**
 * The module a surface file belongs to. Both sets are named after the module — `superplayer-core`'s
 * is `superplayer-core/api/superplayer-core.api` tracked and `api/released/superplayer-core.api`
 * recorded — so one reading serves both and the two cannot be keyed differently.
 */
private fun File.moduleName(): String = nameWithoutExtension
