plugins {
    id("superplayer.android.library")
}

// QoE collector (CTA-2066), CMCD emitter, pluggable sinks
//
// Phase 5. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
