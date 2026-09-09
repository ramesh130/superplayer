# Third-party dependencies

Every third-party dependency SuperPlayer resolves, with its license. `CONTRIBUTING.md` sets the
policy: permissive licenses only, no copyleft, no field-of-use restriction, and no dependency whose
license cannot be identified. Adding a dependency means adding its row here in the same change.

Versions are not listed here — they live in `gradle/libs.versions.toml`, which is the single source
of truth. This file records *what* is depended on and under *what license*.

## Runtime and compile dependencies

| Dependency | License | Used by |
| --- | --- | --- |
| `androidx.media3:media3-common` | Apache-2.0 | `superplayer-core` (api) |
| `androidx.media3:media3-exoplayer` | Apache-2.0 | `superplayer-core` (api) |
| `androidx.media3:media3-exoplayer-hls` | Apache-2.0 | `superplayer-core` |
| `androidx.media3:media3-exoplayer-dash` | Apache-2.0 | `superplayer-core` |
| `androidx.media3:media3-datasource` | Apache-2.0 | `superplayer-core` |
| `androidx.media3:media3-session` | Apache-2.0 | `superplayer-core` (api) |
| `androidx.media3:media3-ui` | Apache-2.0 | `demo` |
| `androidx.annotation:annotation` | Apache-2.0 | `superplayer-core`, `demo` |
| `androidx.compose:compose-bom` | Apache-2.0 | `demo` (a BOM: pins versions, ships no code) |
| `androidx.compose.ui:ui` | Apache-2.0 | `demo` |
| `androidx.compose.foundation:foundation` | Apache-2.0 | `demo` |
| `androidx.compose.material3:material3` | Apache-2.0 | `demo` |
| `androidx.activity:activity-compose` | Apache-2.0 | `demo` |
| `androidx.lifecycle:lifecycle-runtime-compose` | Apache-2.0 | `demo` |
| `com.google.guava:guava` | Apache-2.0 | `superplayer-core` (transitive, via `media3-common`) |
| `org.jetbrains.kotlin:kotlin-stdlib` | Apache-2.0 | all modules (transitively, via the Kotlin toolchain) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 | all modules |

## Test-only dependencies

| Dependency | License | Used by |
| --- | --- | --- |
| `junit:junit` | Eclipse Public License 1.0 | all modules (test), `build-logic` (test) |
| `androidx.media3:media3-test-utils` | Apache-2.0 | `superplayer-core`, `superplayer-testkit` |
| `androidx.media3:media3-test-utils-robolectric` | Apache-2.0 | `superplayer-core`, `superplayer-testkit` |
| `org.robolectric:robolectric` | MIT | `superplayer-core` |
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

**On JUnit 4 and EPL-1.0.** EPL-1.0 is a weak, file-scoped copyleft license. `CONTRIBUTING.md`
admits weak copyleft for test-only and build-time dependencies specifically: JUnit is not
distributed in any published artifact, and its copyleft reaches only modifications to JUnit's own
files, which this project does not make. No EPL-licensed code ships to consumers.

## Build-time only

| Dependency | License | Used by |
| --- | --- | --- |
| `org.jetbrains.kotlinx:binary-compatibility-validator` | Apache-2.0 | `build-logic` |
| `org.ow2.asm:asm` | BSD-3-Clause | `build-logic` |
| `io.github.java-diff-utils:java-diff-utils` | Apache-2.0 | transitive, via `binary-compatibility-validator` |
| `com.diffplug.spotless:spotless-plugin-gradle` | Apache-2.0 | root build, `demo` |
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

The Compose rows are the demo app's alone. No published module depends on Compose: `superplayer-ui`,
the library's optional Compose surface, is a later phase and still an empty placeholder.

The Android Gradle Plugin (Apache-2.0), the Kotlin Gradle Plugin (Apache-2.0), the Compose compiler
plugin (Apache-2.0, released as part of Kotlin), and Gradle itself
(Apache-2.0) are build tooling. They are not distributed in published artifacts.
