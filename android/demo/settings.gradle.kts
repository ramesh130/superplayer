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

// The repository root (#519), which `.editorconfig`, `config/` and `third-party/` are under. This
// build sits inside the root build, whose `gradle.properties` says where the repository root is from
// there (`superplayer.repositoryRoot`, argued in build-logic's `RepositoryRoot.kt`), so it is read
// from that file — as the catalog is read from beside it — rather than restated, and moving the Gradle
// builds stays one line in that file. `gradle.extra` hands it to the build script. The block is a
// copy of `benchmark/settings.gradle.kts`'s, for the reason the Spotless wiring is copied: each build
// has its own wrapper and cannot load the other's scripts, so the two copies travel together.
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

        // `dev.moq:moq-ffi`, reached transitively through `com.superplayer:superplayer-moq`'s POM
        // (#353). A copy of the root `settings.gradle.kts` declaration, and the copy is the point:
        // the demo has its own wrapper and builds with the root build not running at all, so a
        // repository it does not declare is one it cannot resolve from.
        //
        // `exclusiveContent` for the same reason the root build uses it, which matters more here
        // than there: Maven Central carries this coordinate built **with** its default features,
        // and #351 subtracted the MPL-2.0 decoder crates by building without them. An ordinary
        // repository would let Central answer for a version this directory cannot, and the demo
        // would install an APK carrying exactly the artifact #351 exists to avoid — silently,
        // because it would resolve and run. Central is not asked for this group at all.
        exclusiveContent {
            forRepository {
                maven {
                    name = "moqLocal"
                    url = repositoryRoot.resolve("third-party/moq/m2").toURI()
                }
            }
            filter { includeGroup("dev.moq") }
        }
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
