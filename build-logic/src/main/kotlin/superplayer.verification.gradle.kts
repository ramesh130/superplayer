import com.superplayer.build.RECORDED_SURFACE_DIRECTORY
import com.superplayer.build.RECORDED_VERSION_FILE
import com.superplayer.build.RecordReleasedApiSurface
import com.superplayer.build.VerifyLicenseHeader
import com.superplayer.build.VerifyMedia3SupportedVersion
import com.superplayer.build.VerifyModulePhaseRule
import com.superplayer.build.VerifyNoHardcodedMedia3Versions
import com.superplayer.build.VerifyVersion
import com.superplayer.build.VerifyVersionBump

plugins {
    // For the lifecycle `check` task alone. This used to be `tasks.register("check")`, which
    // broke the moment a second plugin wanted the same task: the root build also applies
    // Spotless, and Spotless brings the base plugin — and its `check` — with it. Applying `base`
    // here means whichever of the two goes first wins and the other is a no-op, instead of the
    // second one failing with "a task with that name already exists".
    base
}

// Repo-wide verification, applied to the root project only.

val verifyNoHardcodedMedia3Versions =
    tasks.register<VerifyNoHardcodedMedia3Versions>("verifyNoHardcodedMedia3Versions") {
        group = "verification"
        description = "Fails if any build script pins a Media3 version outside the version catalog."
        buildScripts.from(
            fileTree(layout.projectDirectory) {
                include("**/*.gradle.kts", "**/*.gradle")
                exclude("**/build/**", "**/.gradle/**")
            }
        )
        projectRoot.set(layout.projectDirectory)
        stampFile.set(layout.buildDirectory.file("verification/no-hardcoded-media3-versions.txt"))
    }

// docs/modules.md states the phase rule; this reads that document's own table and holds every
// module's `project(...)` dependencies to it, so the rule is enforced rather than remembered.
val verifyModulePhaseRule =
    tasks.register<VerifyModulePhaseRule>("verifyModulePhaseRule") {
        group = "verification"
        description = "Fails if a module depends on a module from a later phase."
        modulesDocument.set(layout.projectDirectory.file("docs/modules.md"))
        moduleBuildScripts.from(
            fileTree(layout.projectDirectory) {
                include("superplayer-*/build.gradle.kts")
            }
        )
        stampFile.set(layout.buildDirectory.file("verification/module-phase-rule.txt"))
    }

// docs/modules.md states the one Media3 minor version every module supports; this holds the
// catalog's pin to it, so a Media3 bump — Dependabot's `media3` group — fails its own pull request
// until the document has moved with it, rather than leaving the obligation to be remembered.
val verifyMedia3SupportedVersion =
    tasks.register<VerifyMedia3SupportedVersion>("verifyMedia3SupportedVersion") {
        group = "verification"
        description = "Fails if the catalog's Media3 is not the version docs/modules.md supports."
        versionCatalog.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
        modulesDocument.set(layout.projectDirectory.file("docs/modules.md"))
        stampFile.set(layout.buildDirectory.file("verification/media3-supported-version.txt"))
    }

// ADR-0017 rule 1 publishes all thirteen modules under the catalog's one `superplayer` version, and
// nothing read it as anything but a string before this: `publishToMavenLocal` would take `0.1` as
// happily as `0.1.0`. This holds it to the grammar that record admits and to CHANGELOG.md, so a
// release whose notes were never written fails here rather than on someone else's build.
val verifyVersion =
    tasks.register<VerifyVersion>("verifyVersion") {
        group = "verification"
        description = "Fails if the published version is malformed or CHANGELOG.md disagrees with it."
        versionCatalog.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
        changelog.set(layout.projectDirectory.file("CHANGELOG.md"))
        stampFile.set(layout.buildDirectory.file("verification/version.txt"))
    }

// ADR-0017 rule 2 makes the tracked API surface the arbiter of the bump, and only half of it was
// enforced until now: `checkApiSurface` holds each module's tracked file to its code, and nothing
// held the version to the difference between that file and the one last released. This does. It is
// root-only because rule 1 releases all thirteen modules at one number, so the verdict is thirteen
// surfaces against one catalog entry rather than thirteen independent answers.
val releasedApiSurfaces = layout.projectDirectory.dir(RECORDED_SURFACE_DIRECTORY)
val trackedApiSurfaces = fileTree(layout.projectDirectory) { include("superplayer-*/api/*.api") }

val verifyVersionBump =
    tasks.register<VerifyVersionBump>("verifyVersionBump") {
        group = "verification"
        description = "Fails if the published version is too small for the API surface change since the last release."
        trackedApiFiles.from(trackedApiSurfaces)
        // The files themselves rather than the directory, because a directory property would leave
        // a changed recorded surface looking up to date. The tree is legitimately empty until the
        // first release, which the check reads as "nothing has been released" rather than as a
        // missing input.
        recordedApiFiles.from(
            fileTree(releasedApiSurfaces) { include("*.api", RECORDED_VERSION_FILE) }
        )
        versionCatalog.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
        stampFile.set(layout.buildDirectory.file("verification/version-bump.txt"))
    }

// The other half, and deliberately not in `check`: #329's release command runs it to re-record the
// surfaces as part of cutting a release. `updateApiSurface`'s contract, for `updateApiSurface`'s
// reason — a baseline refreshed to make a failure go away is a baseline that means nothing.
tasks.register<RecordReleasedApiSurface>("recordReleasedApiSurface") {
    group = "verification"
    description = "Rewrites api/released/ from the tracked surfaces, under the catalog's version."
    trackedApiFiles.from(trackedApiSurfaces)
    versionCatalog.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
    recordDirectory.set(releasedApiSurfaces)
}

// Spotless stamps `config/license-header.txt` onto every `.kt` file; nothing in Spotless checks
// that the file names the license this project is under. This does, against `LICENSE` itself, so
// the header and the license cannot drift apart.
val verifyLicenseHeader =
    tasks.register<VerifyLicenseHeader>("verifyLicenseHeader") {
        group = "verification"
        description = "Fails if the Spotless license header does not match LICENSE."
        headerFile.set(layout.projectDirectory.file("config/license-header.txt"))
        licenseFile.set(layout.projectDirectory.file("LICENSE"))
        projectRoot.set(layout.projectDirectory)
        stampFile.set(layout.buildDirectory.file("verification/license-header.txt"))
    }

// devicelab's device-free half: the `adb devices` and `dumpsys` parsing, the Perfetto config builder,
// the stale-artifact comparison and the trace processor's pinning, all pure functions over text; every
// scenario loaded the way a run loads it; and each consumer's own device-free half (the leak hunt's
// heap diff, verdict and report). The harness itself needs a device
// and stays out of `check` (devicelab/README.md says why); this part needs neither a device nor a
// network, so by this build's own rule it belongs here, where a change that breaks it fails CI rather
// than the next device run. Shell rather than a JVM test because the harness is shell.
val verifyDevicelab =
    tasks.register<Exec>("verifyDevicelab") {
        group = "verification"
        description = "Runs devicelab's device-free self-test."
        val devicelab = layout.projectDirectory.dir("devicelab")
        val stamp = layout.buildDirectory.file("verification/devicelab-selftest.txt")
        inputs.files(fileTree(devicelab) { exclude("out/**", "**/*.md") })
            .withPropertyName("devicelab")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(stamp)
        workingDir(layout.projectDirectory)
        commandLine(devicelab.file("test/selftest").asFile.absolutePath)
        doLast { stamp.get().asFile.writeText("passed\n") }
    }

// So a plain `./gradlew check` at the root runs them.
tasks.named("check") {
    dependsOn(verifyNoHardcodedMedia3Versions)
    dependsOn(verifyModulePhaseRule)
    dependsOn(verifyMedia3SupportedVersion)
    dependsOn(verifyVersion)
    dependsOn(verifyVersionBump)
    dependsOn(verifyLicenseHeader)
    dependsOn(verifyDevicelab)

    // build-logic is an included build, so its tests are invisible to the root build's
    // aggregate tasks. Hooking them in here keeps `./gradlew check` — and therefore CI —
    // a single, complete definition of "the checks pass".
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
