plugins {
    id("superplayer.android.library")
}

// CTV: display capability, Leanback and Compose-for-TV surfaces
//
// Phase 8. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
