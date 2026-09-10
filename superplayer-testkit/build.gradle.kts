import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// Fault injection, network shaping, fake manifests, golden traces
//
// Phase 2. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
// `superplayer-core`'s one non-public seam — the engine configurator `docs/testing.md` describes —
// is what a deterministic playback harness is built on, and Kotlin `internal` is per compilation.
// This module is that harness, so it compiles as a friend of core rather than as a consumer of it.
// See `KotlinFriendModules.kt` for why that is not the second seam `docs/testing.md` warns about.
declareKotlinFriendModule(":superplayer-core")

dependencies {
    api(project(":superplayer-core"))
    api(libs.media3.test.utils)
    api(libs.media3.test.utils.robolectric)

    // This module's own tests: the fakes above are already `api`, so only the Robolectric runtime
    // is added here — the same pin `superplayer-core` uses, for the reason `CLAUDE.md` gives about
    // Robolectric runtimes and JDK versions.
    testImplementation(libs.robolectric)
}
