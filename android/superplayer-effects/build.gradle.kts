import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// The frame-output seam's Android half: a consumer's `FrameOutput` laid on Media3's effect pipeline
// through core's second output slot, `EngineConfiguration.frameOutput` (ADR-0022 rule 3).
//
// Phase 8. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))

    // Media3's GL effect pipeline, which `ExoPlayer.setVideoEffects` loads by name and refuses to run
    // without. Here and not in core, which is the reason this module exists: an app that adds no frame
    // output carries neither this module nor the artifact (ADR-0022 rule 9).
    implementation(libs.media3.effect)

    // Robolectric, for the platform a player needs: this module's tests drive a real `SuperPlayer`
    // whose frame-output slot they fill, with no device and no GPU.
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.test.utils.robolectric)

    // The deterministic playback harness, which reads the effect list off the video renderer the
    // engine handed it to (`PlaybackHarness.videoEffectsDeliveries`). Phase 2 on phase 8, tests only.
    testImplementation(project(":superplayer-testkit"))

    // The protection a player built with a frame output turns it off for, played through the harness's
    // licence server so "off, and still plays" is a playback rather than a reading (ADR-0022 rule 6).
    // Phase 6 on phase 8, tests only.
    testImplementation(project(":superplayer-drm"))
}

// The one slot this module fills, `EngineConfiguration.frameOutput`, is core's internal seam, and what
// fills it is a Media3 `GlEffect` handed to `ExoPlayer.setVideoEffects` — both `@UnstableApi` — so a
// consumer names a `FrameOutput` and no Media3 type. This module is core's **eleventh** Kotlin friend
// (ADR-0022 rule 3), of the first shape, and `KotlinFriendModules.kt` says why a friend path is a
// compiler flag rather than a Gradle dependency.
declareKotlinFriendModule(":superplayer-core")
