import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// CTV: frame-rate matching, the display as a live condition, Compose-for-TV surfaces (ADR-0014)
//
// Phase 8. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))

    // Robolectric, for the platform the module asks: a display whose modes a test states, and a
    // surface a frame-rate request is made on.
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.test.utils.robolectric)

    // The deterministic playback harness, which plays video declaring a frame rate through a real
    // `SuperPlayer` whose output slot this module fills, on a television `DeviceStatement` states, and
    // reads back what was asked of the display (`PlaybackHarness.frameRateRequests`). Phase 2 on
    // phase 8, tests only.
    testImplementation(project(":superplayer-testkit"))
}

// The one slot this module fills, `EngineConfiguration.videoOutput`, is core's internal seam, and
// filling it sets the engine's frame-rate strategy on `ExoPlayer.Builder`, an `@UnstableApi` type, so a
// consumer names a `PlaybackOutput` and no Media3 type. This module is core's eighth Kotlin friend
// (ADR-0014 rule 3), and `KotlinFriendModules.kt` says why a friend path is a compiler flag rather than
// a Gradle dependency.
declareKotlinFriendModule(":superplayer-core")
