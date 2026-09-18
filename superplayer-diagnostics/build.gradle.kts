import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// MediaSourceDoctor, session trace bundle, on-device debug HUD (ADR-0015)
//
// Phase 9. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    // Core and telemetry, and no other SuperPlayer module (ADR-0015 rule 1). Core carries the doctor's
    // input and output vocabulary — `MediaRequest`, `SuperPlayerError`, the `ContentCache` and the
    // `PlaybackResilience` a fetch travels under — and the seam of rule 3.
    api(project(":superplayer-core"))
    // `SessionTrace` and `SessionTraceRecorder`: the bundle (#292) is that artifact one layer richer
    // rather than a second recording of the same session, which is why the dependency is declared with
    // the module rather than by the issue that first reads it — rule 1 fixes the classpath, and a
    // postmortem that could not name a trace would be free to record one of its own.
    api(project(":superplayer-telemetry"))

    // `DataSource`, `DataSpec` and the `InvalidResponseCodeException` a refused manifest arrives as: the
    // vocabulary of the chain core assembles, every type of it `@UnstableApi`, which is why none appears
    // in a signature a consumer can see (rule 2). Named here rather than resolved through core's
    // transitive graph, as every other module that names a Media3 type itself does.
    implementation(libs.media3.datasource)
    // `ParsingLoadable`, which fetches and parses a manifest as Media3's own loader does.
    implementation(libs.media3.exoplayer)
    // The HLS playlist parser — the parser the player is about to run over the same bytes, so the doctor
    // and the player can never disagree about what a manifest *says* (rule 6).
    implementation(libs.media3.exoplayer.hls)
    // `DashManifestParser` and the `DashManifest` it builds, for rule 6's other half: core's live-window
    // judgement is asked over a manifest, so the doctor must hold one, and it must be the one the engine's
    // own parser built.
    implementation(libs.media3.exoplayer.dash)

    // The deterministic playback harness, which serves the corpus over the same transport a player of the
    // same content loads through and counts what left the chain — the only way rule 7's "over the chain a
    // player would meet" can be asserted rather than asserted by inspection. Phase 2 on phase 9, tests only.
    testImplementation(project(":superplayer-testkit"))
    // The hostile corpus itself: the pathologies the doctor is scored against, with the `// spec:` citation
    // and the plain-language cause each was written with (ADR-0015 rule 12). Phase 1 on phase 9, tests only.
    testImplementation(project(":superplayer-testmedia"))
    // Tests only, a phase 4 module under a phase 9 one: the doctor takes core's `ContentCache`, and the
    // only thing that opens one is `superplayer-cache` — which is how "the cache a player would meet"
    // (rule 7) is a slot a test really fills rather than a line nothing exercises.
    testImplementation(project(":superplayer-cache"))
    // Tests only, a phase 5 module under a phase 9 one: the doctor takes core's `PlaybackResilience`, and the
    // one that mints a credential and repairs a refused one is this module's — which is what makes "the
    // header refresh a player would meet" (rule 7) a fetch a test can count rather than a claim.
    testImplementation(project(":superplayer-resilience"))
    // Tests only, a phase 6 module under a phase 9 one: the seventh redaction rule is asserted against a
    // session that really acquired a licence and really held a DRM session, and the only thing that
    // builds a `PlaybackDrm` is this module — a vacuous redaction test being the one outcome #292's
    // centre has to avoid.
    testImplementation(project(":superplayer-drm"))

    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)
}

// A doctor is built from core's seam rather than filling a slot of it: `TransferChain.diagnosticChain` and
// `DiagnosticEnvironment`'s transport, internal to core because they are Media3 `@UnstableApi` vocabulary.
// This module is core's ninth Kotlin friend (ADR-0015 rule 3), and it is the one friendship bounded by a
// closed list of four seams rather than by a slot — three as that rule first decided, a fourth added by
// #289, and a fifth needing the rule amended by name; `KotlinFriendModules.kt` carries that argument.
// Being a friend is what makes core's `internal` visible here, which is why #292's capability snapshot is
// one function that *returns* the snapshot and the readers under it stay private to their own file.
declareKotlinFriendModule(":superplayer-core")
