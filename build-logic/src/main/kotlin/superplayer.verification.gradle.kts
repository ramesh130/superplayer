import com.superplayer.build.VerifyNoHardcodedMedia3Versions

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

// So a plain `./gradlew check` at the root runs it.
tasks.register("check") {
    group = "verification"
    description = "Runs repo-wide verification."
    dependsOn(verifyNoHardcodedMedia3Versions)

    // build-logic is an included build, so its tests are invisible to the root build's
    // aggregate tasks. Hooking them in here keeps `./gradlew check` — and therefore CI —
    // a single, complete definition of "the checks pass".
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
