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
| `androidx.media3:media3-exoplayer-hls` | Apache-2.0 | `superplayer-core`, `superplayer-testkit`, `superplayer-diagnostics` |
| `androidx.media3:media3-exoplayer-dash` | Apache-2.0 | `superplayer-core`, `superplayer-testkit`, `superplayer-diagnostics` |
| `androidx.media3:media3-datasource` | Apache-2.0 | `superplayer-core`, `superplayer-testkit`, `superplayer-cache` |
| `androidx.media3:media3-database` | Apache-2.0 | `superplayer-cache` (the `DatabaseProvider` interface its cache index is kept through) |
| `androidx.media3:media3-session` | Apache-2.0 | `superplayer-core` (api), `demo` |
| `androidx.media3:media3-ui` | Apache-2.0 | `demo` |
| `androidx.work:work-runtime` | Apache-2.0 | `superplayer-offline` (ADR-0013 rule 11), `superplayer-testkit` (compile only) |
| `androidx.annotation:annotation` | Apache-2.0 | `superplayer-core`, `superplayer-abr`, `superplayer-testkit`, `demo`, `benchmark` |
| `androidx.compose:compose-bom` | Apache-2.0 | `superplayer-tv` (api), `superplayer-diagnostics` (api), `demo` (a BOM: pins versions, ships no code) |
| `androidx.compose.ui:ui` | Apache-2.0 | `demo`, `superplayer-tv` and `superplayer-diagnostics` (transitive, via `foundation`; each public signature names `Modifier`) |
| `androidx.compose.runtime:runtime` | Apache-2.0 | `demo`, `superplayer-tv` and `superplayer-diagnostics` (transitive, via `foundation`) |
| `androidx.compose.foundation:foundation` | Apache-2.0 | `superplayer-tv` (api), `superplayer-diagnostics` (api), `demo` |
| `androidx.tv:tv-material` | Apache-2.0 | `superplayer-tv` (the D-pad controls' buttons, ADR-0014 rule 12) |
| `androidx.compose.material:material-icons-core` | Apache-2.0 | `superplayer-tv` (transitive, via `tv-material`; not named) |
| `androidx.compose.material3:material3` | Apache-2.0 | `demo` |
| `androidx.activity:activity-compose` | Apache-2.0 | `demo` |
| `androidx.lifecycle:lifecycle-runtime-compose` | Apache-2.0 | `demo` |
| `com.google.guava:guava` | Apache-2.0 | `superplayer-core`, `superplayer-testkit`, `demo` (transitive, via `media3-common`) |
| `org.jetbrains.kotlin:kotlin-stdlib` | Apache-2.0 | all modules (transitively, via the Kotlin toolchain) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 | all modules |
| `dev.moq:moq-ffi` | MIT OR Apache-2.0 (the built binary also contains **MPL-2.0** — see below) | `superplayer-moq` — **built here, not resolved from Maven** |
| `net.java.dev.jna:jna` | Apache-2.0 OR LGPL-2.1+ | `superplayer-moq` (transitive, via `moq-ffi`: the bindings' FFI bridge) |

## The MoQ bindings, and the native crates inside them

`dev.moq:moq-ffi` is the one dependency in this file that is **not the artifact its coordinate
names on Maven Central**. It is built from `moq-dev/moq` at commit `4e419f9` with
`--no-default-features` and committed to `third-party/moq/m2`, because the published build carries
MPL-2.0 code this project's policy does not accept. `third-party/moq/README.md` is the recipe, the
evidence and the list of what publishing it would need (#369); `settings.gradle.kts` scopes the
whole `dev.moq` group to that directory so Central cannot answer for it.

The crates in that build are enumerated **one row per crate** in
[`third-party/moq/CRATES.md`](third-party/moq/CRATES.md), which ADR-0018 rule 13 asks for by name
rather than by assertion. There are 320 of them, read off each crate's own `license` field with
`cargo metadata --filter-platform aarch64-linux-android` over the set `cargo tree -e normal
--no-default-features` resolves. This is the reading of that list.

| License | Crates | Note |
| --- | --- | --- |
| MIT, Apache-2.0, or a choice of both | 269 | `moq-ffi` itself, `tokio`, `rustls`, `quinn`, `bytes`, `serde`, and most of the graph |
| ISC, BSD-2-Clause, BSD-3-Clause, Zlib, 0BSD (alone or as a choice) | 17 | `aws-lc-rs`, `unsafe-libopus`, `sonora` and the like |
| Unicode-3.0 (alone or with a choice) | 19 | the ICU crates, reached through `idna` |
| Unlicense (as a choice with MIT) | 5 | `aho-corasick`, `byteorder` and friends |
| CC0-1.0, CDLA-Permissive-2.0 | 2 | `notify`, `webpki-roots` |
| **MPL-2.0** | **8** | UniFFI: `uniffi`, `uniffi_core`, `uniffi_bindgen`, `uniffi_macros`, `uniffi_meta`, `uniffi_udl`, `uniffi_pipeline`, `uniffi_internal_macros` |

**What #351 settled, and what it did not.** #351 removed the *codec* copyleft: `symphonia-core` and
`symphonia-codec-aac` are MPL-2.0 and sit behind `moq-audio`'s default `aac` feature, with
`openh264`'s vendored C++ behind the `video` one. With both off, `cargo tree` reports zero
`symphonia` entries and `llvm-nm` finds zero `symphonia` and zero `openh264` symbols in the binary.
ADR-0018 rule 1 says nothing here decodes, so what was dropped is precisely what this library was
never going to call.

**MPL-2.0 has not left the graph, though, and the enumeration above is what shows it.** UniFFI is
Mozilla's and is MPL-2.0, and `uniffi_core` is not a generator — it is the runtime scaffolding every
exported function is written against. `llvm-nm` finds 342 `uniffi_core` symbols in
`libmoq_ffi.so`, so the binary this repository builds **contains MPL-2.0 code**. That is not a gap
in #351's reasoning about `symphonia`; it is a second fact that ticket's enumeration did not reach.

It is admitted by **`CONTRIBUTING.md`'s #364 amendment**, and by that amendment alone. The weak
copyleft carve-out always rested on *never distributed in a published artifact*, and it named
test-only and build-time dependencies as the two kinds that satisfy it. A dependency of a module in
`settings.gradle.kts`'s `unpublishedModules` list is a third, and it is written into the policy
rather than read into it — the literal wording covered test-only and build-time dependencies and
this is neither, and stretching it in prose here would have been an undocumented exception.

So `superplayer-moq`'s exclusion from the published set is load-bearing rather than tidy-minded: no
consumer resolves it, nothing of it reaches an adopter's APK, and the copyleft reaches only
modifications to UniFFI's own files, which this project does not make. **Whatever else #369 needs, it
needs an answer to this**: publishing `superplayer-moq` as it stands would put MPL-2.0 code in a
published artifact, which the amendment does not admit and the rule above refuses.

The other eight-crate half of that family — `uniffi_bindgen`, `uniffi_udl`, `uniffi_pipeline` and
the macro crates — is build-time only in the plainest sense: they are in the graph because
`moq-ffi` also builds a `uniffi-bindgen` binary, and nothing of them is linked into the cdylib.

`net.java.dev.jna:jna` is the Kotlin side of the same bridge and is dual-licensed Apache-2.0 or
LGPL-2.1-or-later. It is taken under **Apache-2.0**, which the dual licence permits and which
`CONTRIBUTING.md` accepts without qualification.

## Test-only dependencies

| Dependency | License | Used by |
| --- | --- | --- |
| `junit:junit` | Eclipse Public License 1.0 | all modules (test), `build-logic` (test), `benchmark` (test) |
| `androidx.media3:media3-test-utils` | Apache-2.0 | `superplayer-core`, `superplayer-telemetry`, `superplayer-testkit`, `superplayer-abr`, `superplayer-cache`, `superplayer-preload`, `benchmark` (test) |
| `androidx.media3:media3-test-utils-robolectric` | Apache-2.0 | `superplayer-core`, `superplayer-telemetry`, `superplayer-testkit`, `superplayer-abr`, `superplayer-cache`, `superplayer-preload`, `superplayer-tv`, `benchmark` (test) |
| `androidx.compose.ui:ui-test-junit4` | Apache-2.0 | `superplayer-tv` (test), `superplayer-diagnostics` (test) |
| `androidx.compose.ui:ui-test-manifest` | Apache-2.0 | `superplayer-tv` (test), `superplayer-diagnostics` (test) |
| `androidx.work:work-testing` | Apache-2.0 | `superplayer-testkit` (compile only, and its own tests), `superplayer-offline` (test) |
| `org.robolectric:robolectric` | MIT | `superplayer-core`, `superplayer-telemetry`, `superplayer-testkit`, `superplayer-abr`, `superplayer-cache`, `superplayer-preload`, `superplayer-tv`, `superplayer-moq`, `benchmark` (test) |
| `com.google.truth:truth` | Apache-2.0 | transitive, via `media3-test-utils` |
| `org.mockito:mockito-core` | MIT | transitive, via `media3-test-utils` |
| `androidx.test:core`, `androidx.test.ext:junit` | Apache-2.0 | transitive, via `media3-test-utils` |
| `androidx.test:runner` | Apache-2.0 | `superplayer-moq` (androidTest) |

`androidx.test:runner` is the one row here that is **not** on any host-JVM classpath. It is declared
by `superplayer-moq` alone, for the repository's single instrumented source set — #367's live MoQ
session, which needs a device because neither QUIC nor an `arm64-v8a` `.so` exists under Robolectric.
`androidx.test.ext:junit` is named explicitly there too, rather than relied on transitively, because
`media3-test-utils` is not on that configuration at all. `docs/testing.md`'s *The first real MoQ
session* is the carve-out, in the idiom that document uses for the loopback one.

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

The Compose rows are the demo app's, `superplayer-tv`'s and `superplayer-diagnostics`'. **Two published
modules depend on Compose**, and the version catalog's comment says exactly two may: the TV module,
because its D-pad controls are Compose for TV (ADR-0014 rule 12), and the diagnostics module, because
its debug HUD is an overlay the app places in its own layout (ADR-0015 rule 11). Both are optional,
and both resolve Compose only for an app that adds them: a phone app that adds neither resolves no
Compose from SuperPlayer, and an app that adds diagnostics for the doctor alone and never calls the
HUD has Compose on its classpath and no reachable Compose code, which R8 removes.
`superplayer-diagnostics` takes `foundation` and no Material of either flavour, because a HUD lying
over a consumer's own video surface must take the app's screen as it finds it.
`superplayer-ui`, the
library's optional phone Compose surface, is a later phase and still an empty placeholder. `tv-material`'s
other Compose artifacts (animation, layout, text, graphics) arrive under the BOM's pins and are not rowed one
by one, as `foundation`'s are not. The icons are rowed because the controls deliberately draw their glyphs
rather than naming them.

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
