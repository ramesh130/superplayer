import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
    // The D-pad controls are composables (ADR-0014 rule 12). The Compose compiler is a Kotlin compiler
    // plugin released with Kotlin, so its version is the catalog's Kotlin version. It is applied here
    // rather than in the convention plugin because this is the one library module with composables.
    alias(libs.plugins.kotlin.compose)
}

// CTV: frame-rate matching, the display as a live condition, Compose-for-TV surfaces (ADR-0014)
//
// Phase 8. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))

    // The controls: Compose for TV's Material components over Compose foundation, whose focus and key
    // APIs the scrubbing seek bar is built from. `api`, because the controls' public signatures name
    // Compose's `Modifier`. The BOM pins foundation, and tv-material carries its own version.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)

    // Robolectric, for the platform the module asks: a display whose modes a test states, and a
    // surface a frame-rate request is made on.
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.test.utils.robolectric)

    // Compose's UI test rule, which drives the controls with D-pad key events under Robolectric, and the
    // manifest that declares the empty activity the rule hosts them in.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    // The deterministic playback harness, which plays video declaring a frame rate through a real
    // `SuperPlayer` whose output slot this module fills, on a television `DeviceStatement` states, and
    // reads back what was asked of the display (`PlaybackHarness.frameRateRequests`). Phase 2 on
    // phase 8, tests only.
    testImplementation(project(":superplayer-testkit"))

    // The selection gate a display change re-arms, which is `superplayer-abr`'s (ADR-0014 rule 10), and
    // the collector whose `TrackSwitched` events are what a hotplug test reads the selection from. Phases
    // 3 and 2 on phase 8, tests only (ADR-0014 rule 1).
    testImplementation(project(":superplayer-abr"))
    testImplementation(project(":superplayer-telemetry"))
}

// The one slot this module fills, `EngineConfiguration.videoOutput`, is core's internal seam, and
// filling it sets the engine's frame-rate strategy on `ExoPlayer.Builder`, an `@UnstableApi` type, so a
// consumer names a `PlaybackOutput` and no Media3 type. This module is core's eighth Kotlin friend
// (ADR-0014 rule 3), and `KotlinFriendModules.kt` says why a friend path is a compiler flag rather than
// a Gradle dependency.
declareKotlinFriendModule(":superplayer-core")
