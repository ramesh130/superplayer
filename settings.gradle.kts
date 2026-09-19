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

        // `dev.moq:moq-ffi`, MoQ's UniFFI Kotlin bindings, built on this machine and committed to
        // this repository (third-party/moq/README.md is the recipe and the licence evidence).
        //
        // `exclusiveContent` rather than a filtered `maven { }` beside the two above, and that is
        // the whole point of it: #351 settled the licence question by building the crate with its
        // default features off, which removes the MPL-2.0 `symphonia` decoder from the graph — and
        // Maven Central carries the same coordinate built *with* them. An ordinary repository would
        // let any version this directory cannot answer for fall through to Central, and the build
        // would go green having resolved the artifact #351 exists to avoid. Exclusive content means
        // Central is not asked for this group at all, so that mistake is unavailable rather than
        // unlikely; `MoqBindingsResolutionTest` asserts what was resolved.
        exclusiveContent {
            forRepository {
                maven {
                    name = "moqLocal"
                    url = settingsDir.resolve("third-party/moq/m2").toURI()
                }
            }
            filter { includeGroup("dev.moq") }
        }
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
include(":superplayer-realtime")
include(":superplayer-ui")
include(":superplayer-testkit")
include(":superplayer-testmedia")

// Modules in the build that are deliberately **not** published, and the one place that is said.
//
// ADR-0017 rule 1 releases every published module together at one version, so being in the build
// used to be the whole of being published and three things read it that way: `maven-publish` in the
// `superplayer.android.library` convention plugin, the tracked API surface, and
// `verifyCompatibilityDocument`. Three switches that happened to agree would be the defect here, so
// this list is the single fact all three read, through `build-logic`'s `ModulePublication`. A
// module named in neither list fails the convention plugin rather than defaulting to published.
//
// `forEach { include(it) }` rather than `include(...)` lines: that is what keeps the published
// parser above unable to see these, so the two sets are disjoint by construction.
val unpublishedModules = listOf(
    // Phase 14's prototype. It links `dev.moq:moq-ffi` built on one machine (third-party/moq/), so
    // publishing it would offer an adopter a coordinate carrying native code nobody else can
    // reproduce and a single ABI. #369 tracks what shipping it would take.
    ":superplayer-moq",
)
unpublishedModules.forEach { include(it) }

// `demo/` is deliberately NOT included here. It is a standalone Gradle build that
// consumes SuperPlayer through published Maven coordinates, exactly as an external
// adopter would. See demo/settings.gradle.kts.
