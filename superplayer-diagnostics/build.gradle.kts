plugins {
    id("superplayer.android.library")
}

// MediaSourceDoctor, session trace bundle, on-device debug HUD
//
// Phase 6. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
