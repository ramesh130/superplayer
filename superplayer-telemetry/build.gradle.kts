plugins {
    id("superplayer.android.library")
}

// QoE collector (CTA-2066), CMCD emitter, pluggable sinks
//
// Phase 2. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))

    // The project's single test seam, mirroring `superplayer-core`'s: drive the library through its
    // public API under Robolectric, against Media3's own fakes. `media3-test-utils-robolectric`
    // brings Robolectric, Truth, Mockito and androidx.test with it — THIRD_PARTY.md records them as
    // transitive, and nothing new is introduced here.
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)
}
