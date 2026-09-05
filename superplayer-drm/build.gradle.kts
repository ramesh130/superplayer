plugins {
    id("superplayer.android.library")
}

// WidevineSessionManager, provisioning, offline licenses, fallback ladder
//
// Phase 4. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
