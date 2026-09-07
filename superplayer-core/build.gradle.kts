plugins {
    id("superplayer.android.library")
}

// SuperPlayer facade, PlaybackSession, config profiles, player pool
//
// Phase 1. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(libs.media3.common)
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource)
    implementation(libs.androidx.annotation)

    // The project's single test seam: drive the library through its public API under
    // Robolectric, against Media3's own fakes. `media3-test-utils-robolectric` brings
    // Robolectric, Truth, Mockito and androidx.test with it, so they are not declared again
    // here — THIRD_PARTY.md records them as transitive.
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    // Robolectric itself, for the tests that assert on platform state a shadow is the only view
    // of — the wake lock a playing player holds, the audio focus it requested. It arrives at
    // runtime with the line above; naming it here is what puts its shadows on the *compile*
    // classpath. The catalog pins it to the version Media3 already resolves.
    testImplementation(libs.robolectric)
}
