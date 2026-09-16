import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// ErrorClassifier, RetryPolicy, FallbackLadder (CDN/variant/protocol)
//
// Phase 5. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))
    // `InvalidResponseCodeException` and the `DataSpec` it carries: the status and the kind of load
    // `ErrorClassifier` reads off a failure. Named here rather than resolved through core's
    // transitive graph, because this module names the types itself.
    implementation(libs.media3.datasource)
    // `LoadErrorHandlingPolicy` and the `LoadErrorInfo` it is asked about: the interface
    // `RetryingLoadErrors` implements to fill core's load-error slot. Named here for the reason
    // above — this module names the type itself rather than borrowing core's transitive graph.
    implementation(libs.media3.exoplayer)

    // Robolectric, for the two Android types the classifier's evidence is made of rather than for a
    // player: `Uri`, which every `DataSpec` carries, and `MediaCodec.CodecException`, whose
    // constructor an app cannot reach and the mocked `android.jar` of a plain JVM test does not
    // implement. `media3-test-utils-robolectric` brings Truth and androidx.test with it, as it does
    // in every other module's tests.
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)

    // The deterministic playback harness, which plays real HLS through a player with this module's
    // load-error policy in its engine and counts what reached the network — the only way a retry,
    // a budget and an escalation can be asserted through the public API. Phase 2 on phase 5, tests
    // only.
    testImplementation(project(":superplayer-testkit"))
}

// The two slots this module fills — a `HeaderRefreshLayer` in the transfer chain and the
// `LoadErrorHandlingPolicy` the media source factory is handed — are core's `EngineResilienceExtension`
// and Media3 `@UnstableApi` vocabulary, so a consumer names a `PlaybackResilience` and no Media3 type.
// This module is core's fifth Kotlin friend (ADR-0011 rule 13), and `KotlinFriendModules.kt` says why a
// friend path is a compiler flag rather than a Gradle dependency — and why a sixth would have to be
// argued rather than added.
declareKotlinFriendModule(":superplayer-core")
