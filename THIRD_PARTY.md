# Third-party dependencies

Every third-party dependency SuperPlayer resolves, with its license. `CONTRIBUTING.md` sets the
policy: permissive licenses only, no copyleft, no field-of-use restriction, and no dependency whose
license cannot be identified. Adding a dependency means adding its row here in the same change.

Versions are not listed here — they live in `gradle/libs.versions.toml`, which is the single source
of truth. This file records *what* is depended on and under *what license*.

## Runtime and compile dependencies

| Dependency | License | Used by |
| --- | --- | --- |
| `androidx.media3:media3-common` | Apache-2.0 | `superplayer-core` (api), `demo`, `benchmark` |
| `androidx.media3:media3-exoplayer` | Apache-2.0 | `superplayer-core` (api), `superplayer-preload`, `benchmark` |
| `androidx.media3:media3-exoplayer-hls` | Apache-2.0 | `superplayer-core`, `superplayer-testkit` |
| `androidx.media3:media3-exoplayer-dash` | Apache-2.0 | `superplayer-core`, `superplayer-testkit` |
| `androidx.media3:media3-datasource` | Apache-2.0 | `superplayer-core`, `superplayer-testkit`, `superplayer-cache` |
| `androidx.media3:media3-database` | Apache-2.0 | `superplayer-cache` (the `DatabaseProvider` interface its cache index is kept through) |
| `androidx.media3:media3-session` | Apache-2.0 | `superplayer-core` (api), `demo` |
| `androidx.media3:media3-ui` | Apache-2.0 | `demo` |
| `androidx.work:work-runtime` | Apache-2.0 | `superplayer-offline` (ADR-0013 rule 11), `superplayer-testkit` (compile only) |
| `androidx.annotation:annotation` | Apache-2.0 | `superplayer-core`, `superplayer-abr`, `superplayer-testkit`, `demo`, `benchmark` |
| `androidx.compose:compose-bom` | Apache-2.0 | `superplayer-tv` (api), `demo` (a BOM: pins versions, ships no code) |
| `androidx.compose.ui:ui` | Apache-2.0 | `demo`, `superplayer-tv` (transitive, via `foundation`; its public signature names `Modifier`) |
| `androidx.compose.runtime:runtime` | Apache-2.0 | `demo`, `superplayer-tv` (transitive, via `foundation`) |
| `androidx.compose.foundation:foundation` | Apache-2.0 | `superplayer-tv` (api), `demo` |
| `androidx.tv:tv-material` | Apache-2.0 | `superplayer-tv` (the D-pad controls' buttons, ADR-0014 rule 12) |
| `androidx.compose.material3:material3` | Apache-2.0 | `demo` |
| `androidx.activity:activity-compose` | Apache-2.0 | `demo` |
| `androidx.lifecycle:lifecycle-runtime-compose` | Apache-2.0 | `demo` |
| `com.google.guava:guava` | Apache-2.0 | `superplayer-core`, `superplayer-testkit`, `demo` (transitive, via `media3-common`) |
| `org.jetbrains.kotlin:kotlin-stdlib` | Apache-2.0 | all modules (transitively, via the Kotlin toolchain) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 | all modules |

## Test-only dependencies

| Dependency | License | Used by |
| --- | --- | --- |
| `junit:junit` | Eclipse Public License 1.0 | all modules (test), `build-logic` (test), `benchmark` (test) |
| `androidx.media3:media3-test-utils` | Apache-2.0 | `superplayer-core`, `superplayer-telemetry`, `superplayer-testkit`, `superplayer-abr`, `superplayer-cache`, `superplayer-preload`, `benchmark` (test) |
| `androidx.media3:media3-test-utils-robolectric` | Apache-2.0 | `superplayer-core`, `superplayer-telemetry`, `superplayer-testkit`, `superplayer-abr`, `superplayer-cache`, `superplayer-preload`, `benchmark` (test) |
| `androidx.compose.ui:ui-test-junit4` | Apache-2.0 | `superplayer-tv` (test) |
| `androidx.compose.ui:ui-test-manifest` | Apache-2.0 | `superplayer-tv` (test) |
| `androidx.work:work-testing` | Apache-2.0 | `superplayer-testkit` (compile only, and its own tests), `superplayer-offline` (test) |
| `org.robolectric:robolectric` | MIT | `superplayer-core`, `superplayer-telemetry`, `superplayer-testkit`, `superplayer-abr`, `superplayer-cache`, `superplayer-preload`, `benchmark` (test) |
| `com.google.truth:truth` | Apache-2.0 | transitive, via `media3-test-utils` |
| `org.mockito:mockito-core` | MIT | transitive, via `media3-test-utils` |
| `androidx.test:core`, `androidx.test.ext:junit` | Apache-2.0 | transitive, via `media3-test-utils` |

The transitive rows are listed because the tests use them directly — Truth for assertions, Mockito
through Media3's forwarding-contract helper, `androidx.test` for the Robolectric runner — even though
no build file names them. They arrive with `media3-test-utils`, which is the only declaration;
pinning them separately would create a second place for a version to drift.

Guava is listed for the same reason as the transitive test rows: `PlaybackSession`'s session
callback answers Media3 with a `ListenableFuture`, so the library names Guava types directly even
though no build file declares it. It arrives with `media3-common`, which exports it as an `api`
dependency, and pinning it separately would create a second place for a version to drift.

Robolectric is the exception, and is declared as well as inherited. `media3-test-utils-robolectric`
is an AAR whose dependencies are runtime-scoped, so Robolectric's shadows reach the test *runtime*
classpath but not the test *compile* classpath — and the lifecycle tests name shadow types directly,
because a wake lock and an audio focus request are only observable through them. The catalog pins it
to the version Media3 already resolves, so the two declarations cannot pull in two Robolectrics.
`superplayer-testkit` declares it on its *main* compile classpath as well as its test one: the
harness's `TransportReplay` drives the connectivity shadow so a trace's handover is a change of
network the platform reports, and `Shadows.shadowOf` lives in this artifact.

**On JUnit 4 and EPL-1.0.** EPL-1.0 is a weak, file-scoped copyleft license. `CONTRIBUTING.md`
admits weak copyleft for test-only and build-time dependencies specifically: JUnit is not
distributed in any published artifact, and its copyleft reaches only modifications to JUnit's own
files, which this project does not make. No EPL-licensed code ships to consumers.

`benchmark/` appears in the rows above because it is a separate build that resolves SuperPlayer from
published Maven coordinates and declares its own dependencies, exactly as `demo/` does. It brings no
third-party dependency this project did not already have: its arms are Media3's own `ExoPlayer` and
`DefaultLoadControl`, its metrics come off Media3's `AnalyticsListener`, and its Robolectric arm runs
on the same test infrastructure the library's own tests do. Its report generator writes JSON by hand
rather than taking a serialization library, which `TraceWriter` explains — a benchmark of a library
should not add a dependency to the tree of the thing it measures.

## Datasets

None. No third-party throughput dataset is vendored: `superplayer-testkit` replays traces in its own
format, and the traces checked in are generated by this project. `docs/throughput-traces.md` names the
public datasets the converter reads and what each one's publisher asks; one that lands here arrives
with its row in this section, in the same change.

## Build-time only

| Dependency | License | Used by |
| --- | --- | --- |
| `org.jetbrains.kotlinx:binary-compatibility-validator` | Apache-2.0 | `build-logic` |
| `org.ow2.asm:asm` | BSD-3-Clause | `build-logic` |
| `io.github.java-diff-utils:java-diff-utils` | Apache-2.0 | transitive, via `binary-compatibility-validator` |
| `com.diffplug.spotless:spotless-plugin-gradle` | Apache-2.0 | root build, `demo`, `benchmark` |
| `com.pinterest.ktlint:ktlint-rule-engine` | MIT | resolved by Spotless, as the formatting engine |
| `com.pinterest.ktlint:ktlint-ruleset-standard` | MIT | resolved by Spotless, as the formatting engine |

`binary-compatibility-validator` is depended on as a *library*, not applied as its Gradle plugin:
`build-logic` calls its signature loader to render each module's tracked public API surface. ASM
reads Media3's `@UnstableApi` annotations off the pinned compile classpath for the same check.
`docs/api-surface.md` explains why the plugin cannot be used as published.

Neither reaches a published artifact: they are on `build-logic`'s classpath, which is an included
build that produces convention plugins and nothing consumers resolve.

Spotless applies the Kotlin format and the Apache-2.0 file headers; ktlint is the engine underneath
it, resolved by Spotless from the version the catalog pins rather than declared as a dependency of
any module. Both are listed here rather than treated as out of scope: `CLAUDE.md`'s rule is that a
new dependency arrives with its `THIRD_PARTY.md` row in the same change, and it says *dependency*,
not *shipped dependency* — the Android Gradle Plugin and Gradle itself are named in the closing
paragraph below for the same reason. A build-time tool is still third-party code this project runs,
and "which licenses does this repository pull in" is a question that should be answerable from this
one file. Neither reaches a published artifact.

The Compose rows are the demo app's and `superplayer-tv`'s. The TV module is the one published module
that depends on Compose, because its D-pad controls are Compose for TV (ADR-0014 rule 12), and it is
optional: a phone app that never adds it resolves no Compose from SuperPlayer. `superplayer-ui`, the
library's optional phone Compose surface, is a later phase and still an empty placeholder. `tv-material`
also brings `androidx.compose.material:material-icons-core` transitively, and the controls draw their
glyphs rather than naming it.

## Device measurement tools

| Tool | License | Used by |
| --- | --- | --- |
| Perfetto `trace_processor_shell` | Apache-2.0 | `devicelab` (host-side trace queries: the leak hunt) |

Downloaded, not vendored, and never part of a build or an artifact. `devicelab/lib/trace_processor.sh`
fetches the release archive for the host at the version the catalog names `perfetto`, and runs it only
if the archive matches its pin in `devicelab/perfetto/trace-processor.sha256`. It reads traces
recorded on a device and writes query results; nothing of it reaches the library, the demo, or anything
a consumer resolves.

The Android Gradle Plugin (Apache-2.0), the Kotlin Gradle Plugin (Apache-2.0), the Compose compiler
plugin (Apache-2.0, released as part of Kotlin), and Gradle itself
(Apache-2.0) are build tooling. They are not distributed in published artifacts.
