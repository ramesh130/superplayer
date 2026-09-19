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
    // own consumer-facing types and today it has none — #365 widens it in one word when a
    // `FrameSource` over `uniffi.moq` arrives.
    implementation(libs.moq.ffi)

    // The same bindings' JVM variant, for its host `libmoq_ffi.dylib` at JNA's classpath layout.
    // A unit test runs on the host JVM, where an Android `.so` cannot be loaded at all
    // (`docs/testing.md`), so without this the FFI smoke test would have no native library to
    // reach and could only assert that the Kotlin compiled.
    testImplementation(libs.moq.ffi.jvm)

    // The deterministic playback harness. Nothing here plays yet; it is declared because
    // docs/modules.md's row says this module tests against it, and #365 onwards do.
    testImplementation(project(":superplayer-testkit"))
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
