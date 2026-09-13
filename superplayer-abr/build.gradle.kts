import com.superplayer.build.declareKotlinFriendModule

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

// `superplayer-abr`'s policy object also implements a core-internal extension interface, which is
// how its engine components — every one an `@UnstableApi` Media3 type — reach `SuperPlayer.Builder`
// without a Media3 type in any public signature. That interface is visible to it because this
// module compiles as the second Kotlin friend of core, the way `superplayer-testkit` is the first:
// ADR-0009 rule 7 makes the argument, and `KotlinFriendModules.kt` says why a friend path is a
// compiler flag rather than the Gradle dependency `docs/modules.md` forbids.
declareKotlinFriendModule(":superplayer-core")
