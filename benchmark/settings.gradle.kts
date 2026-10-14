// The benchmark is a SEPARATE Gradle build from the library, like `demo/`.
//
// It resolves SuperPlayer from published Maven coordinates via mavenLocal(), so the numbers it
// reports describe the artifact an adopter would actually get — POM, transitive dependencies and
// all — rather than a `project(":superplayer-core")` dependency that skips half of that. `PRD.md`
// §0.2 says every number this project publishes comes from this harness; this is the line that
// makes "this harness measured the shipped thing" true rather than nearly true.
//
// Run `./gradlew publishToMavenLocal` in the root build first. README.md says what else this build
// is not part of, and why that is deliberate rather than an omission.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// The repository root (#519), which `.editorconfig` and `config/` are under. This build sits inside
// the root build, whose `gradle.properties` says where the repository root is from there
// (`superplayer.repositoryRoot`, argued in build-logic's `RepositoryRoot.kt`), so it is read from that
// file — as the catalog is read from beside it — rather than restated, and moving the Gradle builds
// stays one line in that file. `gradle.extra` hands it to the build script. The block is a copy of
// `demo/settings.gradle.kts`'s, for the reason the Spotless wiring is copied: each build has its own
// wrapper and cannot load the other's scripts, so the two copies travel together.
val repositoryRoot: File = run {
    val rootBuild = settingsDir.resolve("..")
    val properties = java.util.Properties()
    providers.fileContents(layout.settingsDirectory.file("../gradle.properties"))
        .asText.get().reader().use(properties::load)
    val declared = properties.getProperty("superplayer.repositoryRoot")
        ?: error("${rootBuild.resolve("gradle.properties")} declares no superplayer.repositoryRoot")
    rootBuild.resolve(declared).normalize()
}
gradle.extra["superplayer.repositoryRoot"] = repositoryRoot

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
    // The benchmark shares the library's version catalog, so the pinned Media3 version and SDK
    // levels stay in exactly one place across all three builds. A benchmark that resolved a
    // different Media3 than the library was built against would be measuring a combination nobody
    // ships, which is the one thing a baseline may not be.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "superplayer-benchmark"
