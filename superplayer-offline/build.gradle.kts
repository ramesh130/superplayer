plugins {
    id("superplayer.android.library")
}

// DownloadManager wrapper, WorkManager constraints, battery policy
//
// Phase 7. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
