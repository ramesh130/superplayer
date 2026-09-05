# Module layout and dependency direction

SuperPlayer is a multi-module Gradle build. Every module named in the project plan exists now, even
where it carries no code yet, so that module boundaries and dependency direction are fixed and
enforceable from the start rather than negotiated once code arrives.

## The rule

**Dependencies point inward, toward `superplayer-core`. Nothing points outward, and no module
depends on a module from a later phase.**

`superplayer-core` depends on Media3 and on nothing else in this repository. Every other library
module depends on `superplayer-core` and, where a genuine need appears, on modules from an *earlier*
phase only. A module may never depend on one whose phase number is higher than its own — that
dependency would make the earlier phase un-shippable without the later one.

## Modules

| Module | Phase | Purpose | Depends on |
| --- | --- | --- | --- |
| `superplayer-core` | 1 | SuperPlayer facade, PlaybackSession, config profiles, player pool | Media3 only |
| `superplayer-abr` | 2 | AdaptiveLoadControl, NetworkAwareTrackSelection, BandwidthOracle | core |
| `superplayer-testkit` | 2 | Fault injection, network shaping, fake manifests, golden traces | core, Media3 test utils |
| `superplayer-preload` | 3 | PreloadCoordinator: segment-0 prefetch and decoder warm-up | core |
| `superplayer-cache` | 3 | Content-keyed CacheDataSource, tiering, eviction policy | core |
| `superplayer-drm` | 4 | WidevineSessionManager, provisioning, offline licenses, fallback ladder | core |
| `superplayer-resilience` | 4 | ErrorClassifier, RetryPolicy, FallbackLadder | core |
| `superplayer-telemetry` | 5 | QoE collector (CTA-2066), CMCD emitter, pluggable sinks | core |
| `superplayer-offline` | 5 | DownloadManager wrapper, WorkManager constraints, battery policy | core |
| `superplayer-diagnostics` | 6 | MediaSourceDoctor, session trace bundle, on-device debug HUD | core |
| `superplayer-tv` | 6 | CTV: display capability, Leanback and Compose-for-TV surfaces | core |
| `superplayer-ui` | 6 | Optional Compose player surface | core |

Only `superplayer-core` is required by a consumer. Every other module is additive: an app depends on
what it uses and its APK does not grow for features it does not need.

## The demo is a separate build

`demo/` is **not** a module of the root build. It is a standalone Gradle build that resolves
SuperPlayer through published Maven coordinates:

```kotlin
implementation("com.superplayer:superplayer-core:0.1.0-SNAPSHOT")
```

not through `project(":superplayer-core")`. This is deliberate. A source dependency would hide
exactly the class of defect an adopter hits first — a missing transitive dependency, a malformed
POM, a wrong artifactId, a variant that does not resolve. Consuming the published artifact makes
those failures happen here, during development, rather than in someone else's app.

The cost is one extra step: the library must be published locally before the demo will build.

```bash
./gradlew publishToMavenLocal        # from the repo root
cd demo && ./gradlew assembleDebug
```

## Building

A clean checkout needs a JDK 17 or newer and an Android SDK with the platform named by
`compileSdk` in the version catalog. Point `ANDROID_HOME` at the SDK, or write `sdk.dir` into a
`local.properties` file — that file is machine-specific and gitignored. Gradle itself does not need
to be installed; the checked-in wrapper fetches the pinned version.

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew build check                # compiles all 12 modules, runs lint and tests
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

## Conventions live in `build-logic`

The 12 library modules share one definition of what a SuperPlayer module is — Android library setup,
SDK levels, Java and Kotlin target, lint configuration, and publishing — in the
`superplayer.android.library` convention plugin under `build-logic/`. A module's own build file
therefore contains only what is genuinely specific to it: its dependencies.
