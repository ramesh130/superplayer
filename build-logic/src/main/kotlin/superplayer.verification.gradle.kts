import com.superplayer.build.VerifyLicenseHeader
import com.superplayer.build.VerifyMedia3SupportedVersion
import com.superplayer.build.VerifyModulePhaseRule
import com.superplayer.build.VerifyNoHardcodedMedia3Versions

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

// devicelab's device-free half: the `adb devices` and `dumpsys` parsing, the Perfetto config builder
// and the stale-artifact comparison, all pure functions over text. The harness itself needs a device
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
    dependsOn(verifyLicenseHeader)
    dependsOn(verifyDevicelab)

    // build-logic is an included build, so its tests are invisible to the root build's
    // aggregate tasks. Hooking them in here keeps `./gradlew check` — and therefore CI —
    // a single, complete definition of "the checks pass".
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
