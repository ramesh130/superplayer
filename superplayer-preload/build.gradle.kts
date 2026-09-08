plugins {
    id("superplayer.android.library")
}

// PreloadCoordinator: segment-0 prefetch and decoder warm-up
//
// Phase 4. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
