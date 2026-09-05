pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modules must not declare their own repositories; everything resolves through here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "superplayer"

// Library modules. Dependency direction is documented in docs/modules.md and enforced
// by each module's build file: nothing depends on a later-phase module.
include(":superplayer-core")
include(":superplayer-abr")
include(":superplayer-preload")
include(":superplayer-cache")
include(":superplayer-drm")
include(":superplayer-resilience")
include(":superplayer-telemetry")
include(":superplayer-offline")
include(":superplayer-diagnostics")
include(":superplayer-tv")
include(":superplayer-ui")
include(":superplayer-testkit")

// `demo/` is deliberately NOT included here. It is a standalone Gradle build that
// consumes SuperPlayer through published Maven coordinates, exactly as an external
// adopter would. See demo/settings.gradle.kts.
