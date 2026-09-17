plugins {
    id("superplayer.android.library")
}

// CTV: frame-rate matching, the display as a live condition, Compose-for-TV surfaces (ADR-0014)
//
// Phase 8. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
