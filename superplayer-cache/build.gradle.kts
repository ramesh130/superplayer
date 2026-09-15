import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// ContentKeyedCache: a content-keyed CacheDataSource in storage the consumer opened (ADR-0010)
//
// Phase 4. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
    // `SimpleCache`, `CacheDataSource` and the `CacheKeyFactory` the key is built by.
    implementation(libs.media3.datasource)
    // `DatabaseProvider`, the interface Media3's cache index is kept through. Implemented here over a
    // file inside the consumer's directory rather than with Media3's own provider (ADR-0010 rule 2).
    implementation(libs.media3.database)

    // The deterministic playback harness, which plays real HLS through a player with this cache in
    // its chain and counts what reached the network. Phase 2 on phase 4 — tests only.
    testImplementation(project(":superplayer-testkit"))
    // `AdaptivePolicy` and `BandwidthOracle`, so a warm replay can be shown to leave the throughput
    // estimate alone (ADR-0010 rule 4). Phase 3 on phase 4, tests only.
    testImplementation(project(":superplayer-abr"))
    // `QoeCollector`, whose session id is the CMCD `sid` a request through the cache must still carry.
    testImplementation(project(":superplayer-telemetry"))
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)
}

// `ContentCache`'s constructor and `CacheLayer`, and the request stamp a key is read from, are core's
// internals: a consumer has no reason to implement a cache, and the half the chain uses is Media3's
// `@UnstableApi` vocabulary. This module is core's third Kotlin friend (ADR-0010 rule 3), and
// `KotlinFriendModules.kt` says why a friend path is a compiler flag rather than a Gradle dependency.
declareKotlinFriendModule(":superplayer-core")
