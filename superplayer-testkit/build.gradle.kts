import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// Fault injection, network shaping, fake manifests, golden traces
//
// Phase 2. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
// `superplayer-core`'s one non-public seam — the engine configurator `docs/testing.md` describes —
// is what a deterministic playback harness is built on, and Kotlin `internal` is per compilation.
// This module is that harness, so it compiles as a friend of core rather than as a consumer of it.
// See `KotlinFriendModules.kt` for why that is not the second seam `docs/testing.md` warns about.
declareKotlinFriendModule(":superplayer-core")

dependencies {
    api(project(":superplayer-core"))

    // The synthetic HLS and DASH streams the harness plays. `api`, so a module testing against this
    // harness can also name what the stream declares — the codec, the bitrate, the segment duration
    // — which is half of what an assertion about protocol playback says. Phase 2 on phase 1.
    api(project(":superplayer-testmedia"))
    // The fault injector wraps a `DataSource`, so this module names the artifact those types come
    // from rather than resolving it through `superplayer-core`'s own transitive graph.
    implementation(libs.media3.datasource)
    // The protocols' own parsers and media sources. `DefaultMediaSourceFactory` finds them by
    // reflection, so a harness that plays synthetic HLS or DASH needs them on its *runtime*
    // classpath rather than only on a test's — and `FaultInjectionTest` reads its URL sequences out
    // of the same parsers, so a real playlist and a real MPD decide what the addressing is compared
    // against. `implementation`: no signature in this module names a protocol.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    api(libs.media3.test.utils)
    api(libs.media3.test.utils.robolectric)
    // `TransportReplay` drives Robolectric's connectivity shadow so a trace's handover is a change
    // of network the platform reports, and `Shadows.shadowOf` lives in this artifact rather than in
    // the shadows Media3's Robolectric utilities already bring. `implementation`: no signature in
    // this module names a shadow.
    implementation(libs.robolectric)

    // This module's own tests: the fakes above are already `api`, so only the Robolectric runtime
    // is added here — the same pin `superplayer-core` uses, for the reason `CLAUDE.md` gives about
    // Robolectric runtimes and JDK versions.
    testImplementation(libs.robolectric)
}
