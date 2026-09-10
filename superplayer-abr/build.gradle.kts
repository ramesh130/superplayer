plugins {
    id("superplayer.android.library")
}

// AdaptiveLoadControl, NetworkAwareTrackSelection, BandwidthOracle
//
// Phase 3. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
}
