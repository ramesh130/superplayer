plugins {
    id("superplayer.android.library")
}

// ErrorClassifier, RetryPolicy, FallbackLadder (CDN/variant/protocol)
//
// Phase 4. Dependency direction: see docs/modules.md. This module may depend on
// modules from an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
