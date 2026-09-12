# Module layout and dependency direction

SuperPlayer is a multi-module Gradle build. Every module named in the project plan exists now, even
where it carries no code yet, so that module boundaries and dependency direction are fixed and
enforceable from the start rather than negotiated once code arrives.

## The rule

**Dependencies point inward, toward `superplayer-core`. Nothing points outward, and no module
depends on a module from a later phase.**

`superplayer-core` depends on Media3 and on nothing else in this repository. Every other library
module depends on `superplayer-core` and, where a genuine need appears, on modules from an *earlier
or equal* phase only. A module may never depend on one whose phase number is higher than its own —
that dependency would make the earlier phase un-shippable without the later one.

**A tie is allowed**, and that is a decision rather than an omission. `telemetry` and `testkit` are
both phase 2; `preload` and `cache` are both phase 4. The rule exists so that an earlier phase is
shippable without a later one, and modules sharing a phase ship together — so a dependency between
peers costs nothing the rule protects. A cycle between two of them is a real problem, but it is one
Gradle already refuses to build; it is not this rule's to catch.

`./gradlew verifyModulePhaseRule` reads the phase column below and fails the build if any module's
`project(...)` dependencies break the rule. It runs as part of `check`, so this is enforced rather
than remembered. The task parses this table, which makes the document the single source of the phase
numbers; the cost is that the table's shape — a backticked module name in the first cell, the phase
in the second — is now load-bearing. A dependency on a module this table does not list fails too,
deliberately: an unlisted module has no phase to compare, and the answer is to add the row rather
than to wave the dependency through.

## Modules

| Module | Phase | Purpose | Depends on |
| --- | --- | --- | --- |
| `superplayer-core` | 1 | SuperPlayer facade, PlaybackSession, config profiles, player pool, transfer chain (live-playlist revalidation) | Media3 only |
| `superplayer-telemetry` | 2 | QoE collector (CTA-2066), CMCD emitter, pluggable sinks, session trace recorder and its goldens | core, testkit (tests only) |
| `superplayer-testkit` | 2 | Fault injection, network shaping, fake manifests, golden traces | core, Media3 test utils |
| `superplayer-testmedia` | 1 | Synthetic HLS and DASH streams: the known-good media tests play | nothing |
| `superplayer-abr` | 3 | AdaptiveLoadControl, NetworkAwareTrackSelection, BandwidthOracle | core |
| `superplayer-preload` | 4 | PreloadCoordinator: segment-0 prefetch and decoder warm-up | core |
| `superplayer-cache` | 4 | Content-keyed CacheDataSource, tiering, eviction policy | core |
| `superplayer-resilience` | 5 | ErrorClassifier, RetryPolicy, FallbackLadder | core |
| `superplayer-drm` | 6 | WidevineSessionManager, provisioning, offline licenses, fallback ladder | core |
| `superplayer-offline` | 7 | DownloadManager wrapper, WorkManager constraints, battery policy | core |
| `superplayer-tv` | 8 | CTV: display capability, Leanback and Compose-for-TV surfaces | core |
| `superplayer-diagnostics` | 9 | MediaSourceDoctor, session trace bundle, on-device debug HUD | core |
| `superplayer-ui` | — † | Optional Compose player surface | core |

`superplayer-testmedia` is the one module that depends on nothing, and that is what it is for. Its
synthetic HLS and DASH streams are played by `superplayer-core`'s tests *and* by
`superplayer-testkit`'s main source set, and core cannot depend on testkit — a later phase, and a
cycle besides — so the one home both can see has to sit below both. Phase 1, therefore: a phase-1
module's tests may reach it, which is the constraint that fixes the number. **That number is the one
exception to the rule below that phases come from `PRD.md`** — the roadmap does not schedule this
module, because it is not a capability anyone ships but the fixture the other modules' tests share,
so it is the dependency rule rather than the roadmap that decides where it sits. It names no Media3 type
either, which is what keeps `verifyNoUnstableMedia3InPublicApi` satisfied without an `@UnstableApi`
`FakeDataSet` in a signature; serving a stream from one is a line at each call site.
`docs/testing.md` carries the rest of the argument, including why duplicating the generators or
shipping them inside `superplayer-core`'s own artifact was worse.

**The phase numbers come from [`PRD.md`](../PRD.md) Part 4, and from nowhere else.** That table is
the roadmap; this one is the roadmap expressed as a dependency constraint. When the two disagree the
PRD wins, and the fix is to correct this column rather than to reason from it — a stale number here
does not merely mislead, it changes which dependencies the rule above permits.

† `PRD.md` Part 4 does not schedule `superplayer-ui`. It appears in the module layout of §2.1 and in
no phase, so it carries no number here rather than an invented one — a number would have to be either
a schedule the roadmap has not made, or a tie with a module it has no stated relationship to. Both
halves of the rule above still resolve without one: no module may depend on it, because an unscheduled
module is not an earlier phase than anything; and it may depend on any scheduled module. The row gains
a number when the roadmap schedules it.

Only `superplayer-core` is required by a consumer. Every other module is additive: an app depends on
what it uses and its APK does not grow for features it does not need.

## Two separate builds beside this one

`demo/` and `benchmark/` are **not** modules of the root build. It is a standalone Gradle build that resolves
SuperPlayer through published Maven coordinates:

```kotlin
implementation("com.superplayer:superplayer-core:0.1.0-SNAPSHOT")
```

not through `project(":superplayer-core")`. This is deliberate. A source dependency would hide
exactly the class of defect an adopter hits first — a missing transitive dependency, a malformed
POM, a wrong artifactId, a variant that does not resolve. Consuming the published artifact makes
those failures happen here, during development, rather than in someone else's app.

The cost is one extra step: the library must be published locally before either will build.

```bash
./gradlew publishToMavenLocal        # from the repo root
cd demo && ./gradlew assembleDebug
benchmark/bench                      # publishes first, then runs the matrix
```

`benchmark/` is the same arrangement for the same reason, and one more: `PRD.md` §0.2 says every
performance number this project publishes comes from its own harness, so the harness had better be
measuring the artifact a consumer would actually get rather than a source dependency that skips half
of what shipping means. It also resolves `superplayer-telemetry` and, for its Robolectric arm,
`superplayer-testkit`. [`benchmark/README.md`](../benchmark/README.md) is its manual, including why a
benchmark sits outside [`docs/testing.md`](testing.md)'s no-device, no-network rule rather than
against it.

## Building

A clean checkout needs a JDK 17 or newer and an Android SDK with the platform named by
`compileSdk` in the version catalog. Point `ANDROID_HOME` at the SDK, or write `sdk.dir` into a
`local.properties` file — that file is machine-specific and gitignored. Gradle itself does not need
to be installed; the checked-in wrapper fetches the pinned version.

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew build check                # compiles all 13 modules, runs lint and tests
./gradlew publishToMavenLocal        # publishes all 12 to ~/.m2
```

## The version catalog

`gradle/libs.versions.toml` is the single source of truth for every dependency version, and both
builds — the library and the demo — read from that one file. Media3's version in particular is
declared there and nowhere else, per ADR-0001 rule 3, so no consumer can hit a version-skew crash
between transitively resolved media modules.

`./gradlew verifyNoHardcodedMedia3Versions` fails the build if any build script pins a Media3
version directly. It runs as part of `check`, so the rule is enforced rather than remembered.

### Supported Media3 versions

ADR-0001 rule 3 requires each module to document the Media3 range it supports. Every module in this
repository currently supports **Media3 1.11.x**, the version pinned in the catalog, and no other.

The range is deliberately a single minor version for now. SuperPlayer builds on Media3 surfaces that
are largely `@UnstableApi` and may change within the 1.x line, so claiming a wider range would be
claiming compatibility nobody has tested. It widens when there is a compatibility test proving it,
not before. A module that comes to support a different range from the rest states so in its own
build file, and this table gains a column.

`./gradlew verifyMedia3SupportedVersion` fails the build when the catalog pins a Media3 outside the
minor version stated above. A Media3 bump therefore cannot land with this section left behind: a
patch release inside 1.11.x passes, and anything else fails until the section is rewritten. It
reads the bold **Media3 X.Y.x** in this section, so a rewording has to keep that form or teach
the check the new one. It runs as part of `check`.

## Conventions live in `build-logic`

The 12 library modules share one definition of what a SuperPlayer module is — Android library setup,
SDK levels, Java and Kotlin target, lint configuration, and publishing — in the
`superplayer.android.library` convention plugin under `build-logic/`. A module's own build file
therefore contains only what is genuinely specific to it: its dependencies.
