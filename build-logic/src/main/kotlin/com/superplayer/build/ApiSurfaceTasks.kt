package com.superplayer.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes the module's public API surface into the build directory.
 *
 * The class files come from AGP's release variant rather than from the Kotlin compile task, so
 * what is measured is what a consumer actually resolves: the published variant, after AGP's own
 * transforms, and never the debug one.
 */
abstract class DumpApiSurface : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ListProperty<Directory>

    @get:OutputFile
    abstract val apiFile: RegularFileProperty

    @TaskAction
    fun dump() {
        val roots = classDirectories.get().map { it.asFile } + classJars.get().map { it.asFile }
        val output = apiFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(renderApiSurface(roots))
    }
}

/**
 * Fails the build when the built API surface has drifted from the one checked into the repository.
 *
 * This is what makes the surface a decision rather than an accident: widening it is a diff on a
 * tracked file, made deliberately with `updateApiSurface`, reviewed like any other code, and
 * refused by CI until it happens.
 */
abstract class CheckApiSurface : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val builtApiFile: RegularFileProperty

    /**
     * The tracked file, as a file *collection* rather than a `RegularFileProperty`.
     *
     * It is absent until the first `updateApiSurface` — a new module has no surface yet — and that
     * absence is a failure this task reports in its own words. Declared as an `@InputFile` it never
     * gets the chance: Gradle validates that a set input file exists before the action runs and
     * fails with a validation error instead. A file collection tolerates a missing entry, so the
     * contributor is told to run `updateApiSurface` rather than shown a Gradle stack trace.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val trackedApiFile: ConfigurableFileCollection

    /** Rendered into the failure message, so the report names a path a contributor can open. */
    @get:Input
    abstract val trackedApiFilePath: Property<String>

    @get:Input
    abstract val updateTaskPath: Property<String>

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val built = builtApiFile.get().asFile.readText()
        val tracked = trackedApiFile.files.singleOrNull()

        val drift = describeApiSurfaceDrift(
            expected = tracked?.takeIf { it.isFile }?.readText(),
            actual = built,
            trackedFile = trackedApiFilePath.get(),
            updateTask = updateTaskPath.get(),
        )

        val stamp = stampFile.get().asFile
        if (drift == null) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
        } else {
            stamp.delete()
            throw GradleException(drift)
        }
    }
}

/**
 * Rewrites the checked-in API surface from what the module currently builds.
 *
 * Deliberately not run by `check`, and deliberately not automatic: its output is a diff that
 * belongs in the same change as the API edit that caused it.
 */
abstract class UpdateApiSurface : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val builtApiFile: RegularFileProperty

    @get:OutputFile
    abstract val trackedApiFile: RegularFileProperty

    @TaskAction
    fun update() {
        val target = trackedApiFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(builtApiFile.get().asFile.readText())
    }
}
