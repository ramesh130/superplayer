import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// AdaptivePolicy (AdaptiveBufferPolicy + AdaptiveLoadControl), BandwidthOracle; NetworkAwareTrackSelection to follow
//
// Phase 3. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
    // The oracle's Media3 half is a `TransferListener` over a `DataSpec`, so this module names the
    // artifact those types come from rather than resolving it through core's transitive graph.
    implementation(libs.media3.datasource)
    implementation(libs.androidx.annotation)

    // The deterministic playback harness `docs/testing.md` describes, which is how a test replays a
    // `ThroughputTrace` under a real player and asserts the oracle's estimate at a chosen
    // millisecond. Phase 2 on phase 3 — tests only, which `docs/modules.md` allows.
    testImplementation(project(":superplayer-testkit"))
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)
}

// `superplayer-abr`'s policy object also implements a core-internal extension interface, which is
// how its engine components — every one an `@UnstableApi` Media3 type — reach `SuperPlayer.Builder`
// without a Media3 type in any public signature. That interface is visible to it because this
// module compiles as the second Kotlin friend of core, the way `superplayer-testkit` is the first:
// ADR-0009 rule 7 makes the argument, and `KotlinFriendModules.kt` says why a friend path is a
// compiler flag rather than the Gradle dependency `docs/modules.md` forbids.
declareKotlinFriendModule(":superplayer-core")
