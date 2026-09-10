plugins {
    id("superplayer.android.library")
}

// Optional Compose player surface (thin; app can bring its own)
//
// Unscheduled: PRD.md Part 4 gives this module no phase, so it carries no number and
// nothing may depend on it. Having no phase, it is not itself barred from any: it may
// depend on any scheduled module. Dependency direction: see docs/modules.md.
dependencies {
    api(project(":superplayer-core"))
}
