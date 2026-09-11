plugins {
    id("superplayer.android.library")
}

// SuperPlayer facade, PlaybackSession, config profiles, player pool
//
// Phase 1. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(libs.media3.common)
    api(libs.media3.exoplayer)
    // `api`, not `implementation`: `PlaybackSession` hands back Media3's own `SessionToken`, and
    // a consumer's Activity needs `MediaController` to connect to what `PlaybackService` publishes.
    // Both are types from this artifact, so a consumer that cannot resolve it cannot use the
    // session half of the library at all.
    api(libs.media3.session)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource)
    implementation(libs.androidx.annotation)

    // The synthetic HLS and DASH streams these tests play. They live in their own module so that
    // `superplayer-testkit`'s *main* source set can reach the same copy: core cannot depend on
    // testkit (a later phase, and a cycle besides), so the one home both can see has to sit below
    // both. `docs/modules.md` carries the row and `docs/testing.md` the argument. Phase 1 depending
    // on phase 1 — and test-only, so nothing a consumer resolves reaches it.
    testImplementation(project(":superplayer-testmedia"))

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
