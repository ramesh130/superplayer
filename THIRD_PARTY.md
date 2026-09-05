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
| `junit:junit` | Eclipse Public License 1.0 | all modules (test) |
| `androidx.media3:media3-test-utils` | Apache-2.0 | `superplayer-testkit` |
| `androidx.media3:media3-test-utils-robolectric` | Apache-2.0 | `superplayer-testkit` |
| `org.robolectric:robolectric` | MIT | transitive, via `media3-test-utils-robolectric` |

**On JUnit 4 and EPL-1.0.** EPL-1.0 is a weak, file-scoped copyleft license. `CONTRIBUTING.md`
admits weak copyleft for test-only and build-time dependencies specifically: JUnit is not
distributed in any published artifact, and its copyleft reaches only modifications to JUnit's own
files, which this project does not make. No EPL-licensed code ships to consumers.

## Build-time only

The Android Gradle Plugin (Apache-2.0), the Kotlin Gradle Plugin (Apache-2.0), and Gradle itself
(Apache-2.0) are build tooling. They are not distributed in published artifacts.
