plugins {
    id("superplayer.android.library")
}

// Fault injection, network shaping, fake manifests, golden traces
//
// Phase 2. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
    api(libs.media3.test.utils)
    api(libs.media3.test.utils.robolectric)
}
