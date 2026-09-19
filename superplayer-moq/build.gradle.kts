import com.superplayer.build.SystemPropertyArgumentProvider
import com.superplayer.build.resolvedModuleComponents

plugins {
    id("superplayer.android.library")
}

// Sub-second live over Media over QUIC: MoQ's Kotlin bindings behind a `FrameSource` (ADR-0018)
//
// Phase 14. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
//
// It is **not published** (`settings.gradle.kts`'s `unpublishedModules`, ADR-0017 rule 1): the
// native half it links is built on one machine, for one ABI, and is nobody else's to resolve.
// `third-party/moq/README.md` is the recipe and the licence evidence; #369 tracks publishing.
//
// It is deliberately **not** a Kotlin friend of core (ADR-0018 rule 11): what it implements is
// core's *public* `FrameSource`, which is the test that the seam is real.
// The repository's **one** instrumented source set, and it is declared here rather than in the
// `superplayer.android.library` convention plugin on purpose.
//
// CLAUDE.md's orientation says Android setup belongs in the convention plugin and a module's own
// build file holds only its dependencies, so this is a departure and is argued rather than assumed:
// putting a `testInstrumentationRunner` in the plugin would give all fifteen modules a
// `connectedAndroidTest` task, fourteen of which have no instrumented source set and none of which
// may have one — `docs/testing.md` bars the device for everything that can be tested without it. A
// task that exists everywhere and is correct in one place is the kind of switch that gets used by
// accident. The blast radius is kept at the module that earned the exception.
//
// What earned it is #367: MoQ's `.so` is `arm64-v8a` and Robolectric runs on the host JVM, and
// `docs/testing.md` bars the network, so a QUIC session against a real relay is reachable from
// nowhere under `check`. This source set is **not** in `check` and never will be; AGP keeps
// `connectedDebugAndroidTest` out of it, and `devicelab/moq-smoke` is how it is run.
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // The seam this module will implement: `FrameSource`, `RealtimeTrack`, `EncodedFrame`
    // (ADR-0018 rule 2's #356 addendum). Phase 1.
    api(project(":superplayer-core"))
    // `Realtime.transport(scheme)`, which turns a `FrameSourceFactory` into the `RealtimeSources`
    // a player is built with. Phase 13.
    api(project(":superplayer-realtime"))

    // MoQ's UniFFI bindings. The Android variant carries `libmoq_ffi.so` for `arm64-v8a` and
    // nothing else, which `docs/testing.md`'s *The MoQ bindings* states as a limit rather than
    // leaving to be discovered. Version and repository: `gradle/libs.versions.toml` and
    // `settings.gradle.kts`.
    //
    // `implementation` and not `api`, because `api` claims the bindings are part of this module's
    // own consumer-facing types and today it has none. #365 kept it that way rather than widening
    // it: `MoqCatalogTracks` and `MoqDeclaredTrack` are both `internal`, so no `uniffi` type is on
    // anything a consumer could name.
    implementation(libs.moq.ffi)

    // The same bindings' JVM variant, for its host `libmoq_ffi.dylib` at JNA's classpath layout.
    // A unit test runs on the host JVM, where an Android `.so` cannot be loaded at all
    // (`docs/testing.md`), so without this the FFI smoke test would have no native library to
    // reach and could only assert that the Kotlin compiled.
    testImplementation(libs.moq.ffi.jvm)

    // The deterministic playback harness, and since #366 the thing this module's tests are really
    // for: `FrameSourceConformance`, testkit's public suite of eleven checks over the obligations
    // `FrameSource` carries. Nothing here plays; what is driven is the frame pump alone.
    testImplementation(project(":superplayer-testkit"))

    // `MoqFrameSource` is opened for an `android.net.Uri`, which is a stub on a bare JVM unit test,
    // so the tests that drive the pump run under Robolectric. They load **no native library**: the
    // relay behind the seam is scripted (`ScriptedMoqRelay`), which is what lets them run on every
    // host and in CI, unlike `MoqFfiLinkageTest`. `docs/testing.md`'s *The MoQ bindings* has the
    // split.
    testImplementation(libs.robolectric)

    // The instrumented half (#367). These three reach the `androidTest` configuration alone, which
    // shares nothing with the unit tests above: `media3-test-utils` is not on it, so the JUnit 4
    // bridge is named here rather than inherited the way a unit test inherits it.
    //
    // Nothing that stands in for anything is on this list, and that is the point of the source set.
    // What runs there loads the **real** `libmoq_ffi.so`, opens a **real** QUIC session and reads a
    // **real** publisher's catalog — the three things `ScriptedMoqRelay` is a fake of, and the three
    // #366 could prove nothing about.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// What `MoqBindingsResolutionTest` compares: the version the catalog *asked for*, and the
// components Gradle actually *resolved* for `dev.moq`.
//
// The second cannot be read off the test classpath, which is where this started. An entry there is
// a file name, and AGP's AAR transform renames `moq-ffi-android-<version>.aar` to
// `moq-ffi-release/jars/classes.jar` — so the version, the one fact the test is about, is exactly
// what the file name loses. These are the resolved component identifiers instead, which is Gradle's
// own answer to "what did you pick, and at what version". It is a `Provider`, so the resolution
// happens when the tests run rather than while the build is configured.
//
// The defect being prevented is quiet: `dev.moq:moq-ffi` exists on Maven Central built with the
// codec features on, and upstream's wrapper POM declares a `[0.3,0.4)` range that any repository
// may satisfy. ADR-0018 rule 13's exact pin and `settings.gradle.kts`'s `exclusiveContent` are the
// two halves that close it; this is what says they held.
//
// The reading itself is `build-logic`'s `resolvedModuleComponents`, and its KDoc says why it is
// there rather than here: a lambda written in a build script captures the script object, which the
// configuration cache cannot store once a task holds it.
tasks.withType<Test>().configureEach {
    systemProperty("superplayer.moq.ffi.version", libs.versions.moqFfi.get())
}

// `afterEvaluate` because AGP creates the unit test's runtime configuration while it evaluates this
// module, so nothing before that point can name it. It is the *naming* that is deferred; resolution
// still happens when the tests run, through the provider.
afterEvaluate {
    val resolved = resolvedModuleComponents(
        configurations.getByName("debugUnitTestRuntimeClasspath"),
        // The group `settings.gradle.kts` scopes to `third-party/moq/m2` and to nothing else.
        "dev.moq"
    )
    tasks.withType<Test>().configureEach {
        jvmArgumentProviders.add(
            objects.newInstance(SystemPropertyArgumentProvider::class).apply {
                propertyName.set("superplayer.moq.resolved")
                values.set(resolved)
            }
        )
    }
}
