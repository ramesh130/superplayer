package com.superplayer.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if an `@UnstableApi` Media3 type has reached this module's public API.
 *
 * See [ADR-0001 rule 2][findUnstableMedia3TypesInApiSurface]. It runs against the surface the
 * module currently builds rather than the tracked file, so a leak fails on the change that
 * introduces it and not on the change that happens to regenerate the dump.
 */
abstract class VerifyNoUnstableMedia3InPublicApi : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiSurfaceFile: RegularFileProperty

    /** The module's own compile classpath: where the pinned Media3's annotations are read from. */
    @get:Classpath
    abstract val media3Classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val classpath = media3Classpath.files
        val apiSurface = apiSurfaceFile.get().asFile.readText()
        val stamp = stampFile.get().asFile

        // A module whose public API names no Media3 type at all — every placeholder module, today —
        // has nothing to check and needs no classpath to prove it.
        if (!namesAnyMedia3Type(apiSurface)) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
            return
        }

        // Read before anything is judged. `unstableTypeDetector` answers "not unstable" for a class
        // it cannot find, so an empty or mis-resolved classpath would make every type look stable
        // and turn this task into a green no-op — the one outcome a rule-2 check must never have.
        val contractMembers = playerContractMembers(classpath)
            ?: throw GradleException(
                "Cannot read androidx.media3.common.Player from this module's compile classpath, so " +
                    "no type can be told stable from unstable. Refusing to report success: this " +
                    "check is what enforces ADR-0001 rule 2, and passing it silently is worse than " +
                    "failing it loudly."
            )

        val leaks = findUnstableMedia3TypesInApiSurface(
            apiSurface = apiSurface,
            allowed = ADR_0001_UNSTABLE_EXCEPTIONS,
            contractMembers = contractMembers,
            isUnstable = unstableTypeDetector(classpath),
        )

        if (leaks.isEmpty()) {
            stamp.parentFile.mkdirs()
            stamp.writeText("ok\n")
        } else {
            stamp.delete()
            throw GradleException(
                buildString {
                    appendLine("No @UnstableApi Media3 type may appear in SuperPlayer's public API (ADR-0001 rule 2).")
                    appendLine("Wrap it behind a SuperPlayer-owned type expressed in SuperPlayer's own vocabulary.")
                    appendLine("Leaked:")
                    leaks.forEach { appendLine("  $it") }
                }
            )
        }
    }
}
