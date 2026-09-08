plugins {
    id("superplayer.android.library")
}

// Optional Compose player surface (thin; app can bring its own)
//
// Unscheduled: PRD.md Part 4 gives this module no phase, so it carries no number and
// nothing may depend on it. Dependency direction: see docs/modules.md. This module may
// depend on modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
