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
}
