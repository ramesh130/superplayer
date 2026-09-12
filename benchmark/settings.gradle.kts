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
