plugins {
    id("superplayer.android.library")
}

// Content-keyed CacheDataSource, tiering, eviction policy
//
// Phase 3. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
