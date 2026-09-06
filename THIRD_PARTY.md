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
| `androidx.media3:media3-ui` | Apache-2.0 | `demo` |
| `androidx.annotation:annotation` | Apache-2.0 | `superplayer-core` |
| `org.jetbrains.kotlin:kotlin-stdlib` | Apache-2.0 | all modules (transitively, via the Kotlin toolchain) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 | all modules |

## Test-only dependencies

| Dependency | License | Used by |
| --- | --- | --- |
| `junit:junit` | Eclipse Public License 1.0 | all modules (test), `build-logic` (test) |
| `androidx.media3:media3-test-utils` | Apache-2.0 | `superplayer-core`, `superplayer-testkit` |
| `androidx.media3:media3-test-utils-robolectric` | Apache-2.0 | `superplayer-core`, `superplayer-testkit` |
| `org.robolectric:robolectric` | MIT | transitive, via `media3-test-utils-robolectric` |
| `com.google.truth:truth` | Apache-2.0 | transitive, via `media3-test-utils` |
| `org.mockito:mockito-core` | MIT | transitive, via `media3-test-utils` |
| `androidx.test:core`, `androidx.test.ext:junit` | Apache-2.0 | transitive, via `media3-test-utils` |

The three transitive rows are listed because the tests use them directly — Truth for assertions,
Mockito through Media3's forwarding-contract helper, `androidx.test` for the Robolectric runner —
even though no build file names them. They arrive with `media3-test-utils`, which is the only
declaration; pinning them separately would create a second place for a version to drift.

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

`binary-compatibility-validator` is depended on as a *library*, not applied as its Gradle plugin:
`build-logic` calls its signature loader to render each module's tracked public API surface. ASM
reads Media3's `@UnstableApi` annotations off the pinned compile classpath for the same check.
`docs/api-surface.md` explains why the plugin cannot be used as published.

Neither reaches a published artifact: they are on `build-logic`'s classpath, which is an included
build that produces convention plugins and nothing consumers resolve.

The Android Gradle Plugin (Apache-2.0), the Kotlin Gradle Plugin (Apache-2.0), and Gradle itself
(Apache-2.0) are build tooling. They are not distributed in published artifacts.
