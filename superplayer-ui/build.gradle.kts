plugins {
    id("superplayer.android.library")
}

// Optional Compose player surface (thin; app can bring its own)
//
// Phase 6. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
