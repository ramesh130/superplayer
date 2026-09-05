// The demo is a SEPARATE Gradle build from the library.
//
// It is not a module of the root build and takes no `project(":superplayer-core")`
// dependency. It resolves SuperPlayer from published Maven coordinates via mavenLocal(),
// exactly as an external adopter would, so integration problems — a missing transitive
// dependency, a broken POM, a wrong artifactId — surface here at development time instead
// of in someone else's app.
//
// Run `./gradlew publishToMavenLocal` in the root build first.

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
    // The demo shares the library's version catalog, so the pinned Media3 version and SDK
    // levels stay in exactly one place across both builds.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "superplayer-demo"
