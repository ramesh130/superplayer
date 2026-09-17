import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// Downloads into the ContentCache the consumer opened, on the one chain (ADR-0013)
//
// Phase 7. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    // Core alone (ADR-0013 rule 1): the cache a download writes into is core's `ContentCache`, so an
    // app that downloads clear content carries no DRM module.
    api(project(":superplayer-core"))

    // `DownloadManager`, `DownloaderFactory` and the download index: every Media3 type this module
    // deals in and every one of them `@UnstableApi`, which is why none appears in a signature a
    // consumer can see. Named here rather than resolved through core's transitive graph, as every
    // other module that names a Media3 type itself does.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)

    // The deterministic playback harness downloads over the same transport a player of the same
    // content plays through, so "it plays on a plane" is a count of zero requests rather than a
    // screenshot. Phase 2 on phase 7, tests only.
    testImplementation(project(":superplayer-testkit"))
    testImplementation(project(":superplayer-testmedia"))

    // Tests only, and a phase 4 module under a phase 7 one. The store takes core's `ContentCache`, and
    // the only cache that has a download half is `superplayer-cache`'s; nothing in this module's main
    // sources knows it exists (ADR-0013 rule 1).
    testImplementation(project(":superplayer-cache"))
    // Tests only, a phase 5 module under a phase 7 one, for the same reason: the store takes core's
    // `PlaybackResilience`, and the one that tells a lost network from a lost segment is this module's.
    testImplementation(project(":superplayer-resilience"))

    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)
}

// A store is built from core's seam rather than filling a slot of it: `ContentCache`'s download half,
// `TransferChain.downloadChain` and `DownloadEnvironment`'s members, all internal to core because they
// are Media3 `@UnstableApi` vocabulary. This module is core's seventh Kotlin friend (ADR-0013 rule 4),
// and `KotlinFriendModules.kt` says why a friend path and not a public API.
declareKotlinFriendModule(":superplayer-core")
