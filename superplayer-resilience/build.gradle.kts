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
}

// The two slots this module fills — a `HeaderRefreshLayer` in the transfer chain and the
// `LoadErrorHandlingPolicy` the media source factory is handed — are core's `EngineResilienceExtension`
// and Media3 `@UnstableApi` vocabulary, so a consumer names a `PlaybackResilience` and no Media3 type.
// This module is core's fifth Kotlin friend (ADR-0011 rule 13), and `KotlinFriendModules.kt` says why a
// friend path is a compiler flag rather than a Gradle dependency — and why a sixth would have to be
// argued rather than added.
declareKotlinFriendModule(":superplayer-core")
