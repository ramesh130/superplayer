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

// How each module is published, and the one place that is said (`ModulePublication`).
//
// ADR-0017 rule 1 releases every published module together at one version, so being in the build
// used to be the whole of being published and three things read it that way: `maven-publish` in the
// `superplayer.android.library` convention plugin, the tracked API surface, and
// `verifyCompatibilityDocument`. Switches that happened to agree would be the defect here, so these
// lists are the single fact all three read. A module named in none of them fails the convention
// plugin rather than defaulting to published.
//
// `forEach { include(it) }` rather than `include(...)` lines: that is what keeps the published
// parser above unable to see these, so the sets are disjoint by construction.
//
// Modules in the build that publish **no artifact at all**. Empty since #353 moved
// `superplayer-moq` to the list below, and kept rather than deleted because the state is still the
// right answer for a module nothing outside this build ever resolves — a spike, or a module whose
// artifact would be wrong to produce at all rather than merely wrong to distribute.
val unpublishedModules = listOf<String>()
unpublishedModules.forEach { include(it) }

// Modules published to this machine's local Maven repository and to nowhere else
// (`ModulePublication.LOCAL_ONLY`). An adopter cannot resolve one — `docs/compatibility.md` still
// reads "Not published" for it and no API surface is tracked — but `publishToMavenLocal` produces
// an artifact, which is what lets `demo/` name the coordinate.
val locallyPublishedModules = listOf(
    // Phase 14's prototype. It links `dev.moq:moq-ffi` built on one machine (third-party/moq/),
    // for `arm64-v8a` alone, so a coordinate an adopter could resolve would carry native code
    // nobody else can reproduce; #369 tracks what shipping it would take and is unanswered.
    //
    // It is local-only rather than unpublished because `PRD.md`'s Phase 14 exit criterion is a
    // broadcast playing **in the demo**, and `demo/` is a separate build that resolves published
    // coordinates through `mavenLocal()`. #353 is that criterion. What a local artifact
    // distributes is nothing, which is why this does not pre-empt #369.
    ":superplayer-moq",
)
locallyPublishedModules.forEach { include(it) }

// `demo/` is deliberately NOT included here. It is a standalone Gradle build that
// consumes SuperPlayer through published Maven coordinates, exactly as an external
// adopter would. See demo/settings.gradle.kts.
