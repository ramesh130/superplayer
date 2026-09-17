import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// WidevineSessionManager, provisioning, offline licenses, fallback ladder
//
// Phase 6. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))

    // `DefaultDrmSessionManager`, `HttpMediaDrmCallback`, `ExoMediaDrm` and the
    // `DrmSessionManagerProvider` core's slot is filled with: every Media3 type this module deals in
    // and every one of them `@UnstableApi`, which is why none appears in a signature a consumer can
    // see. Named here rather than resolved through core's transitive graph, as every other module
    // that names a Media3 type itself does.
    implementation(libs.media3.exoplayer)

    // `DataSource.Factory`: the licence transport core hands the slot, and what
    // `HttpMediaDrmCallback` carries a licence request over.
    implementation(libs.media3.datasource)

    // Robolectric and Media3's own fakes: `FakeExoMediaDrm` stands in for a Widevine device, since
    // Robolectric ships no `ShadowMediaDrm` and a real `MediaDrm` cannot be constructed under
    // `check` at all (`docs/testing.md`, *A Widevine device and a licence server*).
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)

    // The deterministic playback harness, which plays a protected synthetic stream through a real
    // `SuperPlayer` with this module's session manager in its engine, over a licence server the test
    // can address, refuse and count — the only way licence acquisition can be asserted through the
    // public API. Phase 2 on phase 6, tests only.
    testImplementation(project(":superplayer-testkit"))
    testImplementation(project(":superplayer-testmedia"))

    // Tests only, and a phase 5 module under a phase 6 one, which is the allowed direction. A licence
    // load spends `RetryPolicy.licence` and is repaired by the one `HeaderProvider` only on a player
    // that was also given a `PlaybackResilience` — the load-error policy and the header-refresh layer
    // are that module's — so the claims of #205 cannot be driven through this module's public API
    // without it. Nothing in this module's main sources knows it exists.
    testImplementation(project(":superplayer-resilience"))

    // `QoeCollector`, for the half of ADR-0012 rule 11 that is a report rather than a behaviour: a
    // session that fell to a lower level mid-flight says so on `SessionEnded`. It is asserted here
    // because the fall is this module's, and a phase 2 module may not depend on a phase 6 one —
    // the same direction, and the same reason, as the resilience dependency above. Phase 6 on phase
    // 2, tests only.
    testImplementation(project(":superplayer-telemetry"))
}

// The one slot this module fills — the `DrmSessionManagerProvider` every media source `TransferChain`
// builds is given — is core's `EngineDrmExtension` and Media3 `@UnstableApi` vocabulary, so a consumer
// names a `PlaybackDrm` and no Media3 type. This module is core's sixth Kotlin friend (ADR-0012 rule
// 4), and `KotlinFriendModules.kt` says why a friend path is a compiler flag rather than a Gradle
// dependency — and what the sixth makes the ceiling.
declareKotlinFriendModule(":superplayer-core")
