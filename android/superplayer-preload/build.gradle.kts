import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// PreloadCoordinator: segment-0 prefetch and decoder warm-up
//
// Phase 4. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
    // Media3's preload manager, which the coordinator builds over the components a pool shares.
    // Named rather than resolved through core's graph, because this module's code names its types.
    implementation(libs.media3.exoplayer)

    // The deterministic playback harness: a pool, a coordinator and a shaped link on one clock, with
    // a scripted scroll. Phase 2 on phase 4 — tests only.
    testImplementation(project(":superplayer-testkit"))
    // `QoeCollector`, so a preloaded row's time to first frame is read from the `FirstFrameRendered`
    // a consumer's sink would see rather than from the engine. Phase 2 on phase 4, tests only.
    testImplementation(project(":superplayer-telemetry"))
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)
}

// A coordinator attaches to a `PlayerPool` through core's internal `PoolAttachment`, and builds its
// preload manager over the pool's `SharedComponents` — Media3 `@UnstableApi` types, every one — so a
// feed names a pool and a coordinator and no Media3 type. This module is core's fourth Kotlin friend
// (ADR-0010 rule 6), and `KotlinFriendModules.kt` says why a friend path is a compiler flag rather than
// a Gradle dependency.
declareKotlinFriendModule(":superplayer-core")
