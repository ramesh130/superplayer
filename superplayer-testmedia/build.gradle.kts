plugins {
    id("superplayer.android.library")
}

// Synthetic HLS and DASH streams: the known-good media every other module's tests play
//
// Phase 1. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
//
// It depends on nothing at all, and that is the point rather than an accident. The generators are
// reached from `superplayer-core`'s *tests* and from `superplayer-testkit`'s *main* source set, and
// core cannot depend on testkit (later phase, and a cycle besides), so the one home both can see has
// to sit below both. Naming no dependency — not even Media3's test utilities — is what keeps it
// there: a module with no edges cannot break the phase rule and cannot be a cycle.
//
// What it emits is bytes and URIs, so nothing here needs `FakeDataSet`. Wiring a stream into one is
// a line at each call site, and it is deliberately the caller's: `FakeDataSet` is `@UnstableApi`, so
// an `addTo(FakeDataSet)` in this module's *public* API would fail
// `verifyNoUnstableMedia3InPublicApi` (ADR-0001 rule 2) — the same rule that keeps Media3 out of
// `superplayer-testkit`'s signatures.
//
// The one dependency is a *test* one, and it reaches no consumer: JUnit, for the fixture check below.
dependencies {
    testImplementation(libs.junit)
}

// The iOS fixtures: this module's generators' output, committed under `ios/Tests/Fixtures/synthetic/`
// so the Swift port is held to the same bytes without a JVM (ADR-0021 rule 6, `docs/testing.md`'s
// *One corpus on both platforms*). `SyntheticHlsFixturesTest` fails `check` when the committed files
// are not what the generators produce now; `updateIosFixtures` is the one thing that rewrites them,
// on the golden traces' contract — run alone, then `check`, and commit the diff with its cause.
//
// The mode reaches the test JVM as a system property decided from the command line, as the golden
// traces' does and for the same reason: one unit-test task cannot run in two modes at once. The
// directory travels relative to the module, which is where AGP runs unit tests, and is an input so a
// fixture edited by hand re-runs the check instead of reading as up to date.
//
// Here rather than in the convention plugin beside `updateGoldenTraces`: every module has golden
// traces to hold, and only this one generates the corpus the Swift port is held to.
//
// The HLS half of `HostileManifests` (#412) is held the same way, under `ios/Tests/Fixtures/hostile/`,
// by `HostileManifestsFixturesTest`: a directory of its own with an index of its own, because an entry
// carries a citation, a cause and declared headers that a good stream's index has no column for.
val iosFixtures = rootProject.layout.projectDirectory.dir("ios/Tests/Fixtures/synthetic")
val iosFixturesFromModule = iosFixtures.asFile.relativeTo(projectDir).invariantSeparatorsPath
val hostileFixtures = rootProject.layout.projectDirectory.dir("ios/Tests/Fixtures/hostile")
val hostileFixturesFromModule = hostileFixtures.asFile.relativeTo(projectDir).invariantSeparatorsPath
val updatingIosFixtures = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "updateIosFixtures" }

tasks.withType<Test>().configureEach {
    systemProperty("superplayer.iosFixtures.dir", iosFixturesFromModule)
    systemProperty("superplayer.hostileFixtures.dir", hostileFixturesFromModule)
    systemProperty("superplayer.iosFixtures.mode", if (updatingIosFixtures) "update" else "check")
    inputs.files(fileTree(iosFixtures)).withPropertyName("iosFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(hostileFixtures)).withPropertyName("hostileFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    if (updatingIosFixtures) {
        outputs.upToDateWhen { false }
        filter { includeTestsMatching("*FixturesTest") }
    }
}

tasks.register("updateIosFixtures") {
    group = "verification"
    description = "Rewrites ios/Tests/Fixtures/synthetic/ and hostile/ from what this module's generators produce now."
    dependsOn("testDebugUnitTest")
}
