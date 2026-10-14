# superPlayer

A production playback layer on top of AndroidX Media3 — policy, resilience, observability, and
lifecycle. Not a fork of Media3 and not a new player. Apache-2.0, Kotlin, JDK 17.

## Orientation

Four Gradle builds and one Swift package, not one build. Mistaking them for a single build is the
usual first wrong turn. The four Gradle builds all live under `android/`, and the Swift package sits
beside it at the root, as do `docs/`, `config/`, `devicelab/`, `third-party/` and the documents both
platforms share. Gradle runs from inside `android/`, so every command below is spelled
`(cd android && ./gradlew …)` from the root.

| Build | What it is |
| --- | --- |
| `android/` | the 15 `superplayer-*` library modules, listed in `android/settings.gradle.kts` — 14 published, and `superplayer-moq` deliberately not (ADR-0017 rule 1's #364 addendum) |
| `android/build-logic/` | an **included build** holding the convention plugins every module applies |
| `android/demo/` | a **separate** build resolving the library from published Maven coordinates |
| `android/benchmark/` | a **separate** build, the same way, running `PRD.md` §6's matrix |
| `Package.swift` + `ios/` | the **iOS and tvOS** Swift package (`docs/ios-plan.md`): manifest at the root so SwiftPM can resolve it by git URL, sources under `ios/`, every product carrying its phase's code, checked by `ios/check` on macOS rather than by Gradle. `ios/CLAUDE.md` is its orientation |

A module's own build file holds only its dependencies; Android setup, SDK levels, Java and Kotlin
targets, lint, and publishing all live in the `superplayer.android.library` convention plugin.

`superplayer-core` is the only module a consumer needs; the rest are additive. Dependencies point
inward toward core, and no module may depend on one from a later phase. `docs/modules.md` carries
the module table, the phases, and that rule — read it before adding a module dependency.

## Where things are

**Before changing a module, read its section of [`docs/codebase-tour.md`](docs/codebase-tour.md)**
(`grep -n '^## ' docs/codebase-tour.md`, then read that slice). It carries the decisions inside each
module and the traps that cost time; the map below only says what lives where. The Swift package's
equivalent is `ios/CLAUDE.md`.

| Module (under `android/`) | Holds |
| --- | --- |
| `superplayer-core` | `SuperPlayer` (a Media3 `Player` by Kotlin delegation, ADR-0003), `MediaRequest`, `PlaybackProfile`, `PlaybackSnapshot`, `TelemetrySink`/`TelemetryEvent`, `PlaybackSession`/`PlaybackService`, `PlayerPool`, `PlaybackPolicy`, `HttpStack`/`HttpTransport`, the realtime seam (`FrameSource`), `FrameOutput`, and `TransferChain` — the one place the `DataSource` chain is assembled, with every optional module's slot in `EngineConfiguration` |
| `superplayer-telemetry` | `QoeCollector`, `LogcatSink`, `SessionTraceRecorder` and the golden traces, `SessionMetrics`/`QoeScore`. What a metric *means* is `docs/telemetry-schema.md`, not the source |
| `superplayer-testkit` | `PlaybackHarness`, `DeviceStatement`, `FaultScript`, `NetworkProfile`, `HttpTransportConformance`, `FrameSourceConformance` |
| `superplayer-testmedia` | synthetic HLS/DASH streams and the `HostileManifests` corpus; depends on nothing |
| `superplayer-abr` | `BandwidthOracle`, `AdaptivePolicy` (buffer and selection halves), `NetworkAwareTrackSelection`, the QoE regression gate |
| `superplayer-cache` | `CachePolicy`, `ContentKeyedCache`, `ContentKeys`, pin-aware eviction |
| `superplayer-preload` | `PreloadCoordinator` over a `PlayerPool`, decoder warm-up, platform rules |
| `superplayer-resilience` | `ErrorClassifier`/`FailureClass` (the one place a failure gets a meaning), `Resilience.standard`, the six-rung `FallbackLadder` |
| `superplayer-drm` | `Drm.widevine`, security-level ladder, offline licences |
| `superplayer-offline` | `Downloads`, `DownloadsService` |
| `superplayer-tv` | `TvOutput` (frame-rate matching, display hotplug, tunneling), `TvPlaybackControls` |
| `superplayer-diagnostics` | `MediaSourceDoctor`, `SessionBundle`, `DebugHud` |
| `superplayer-realtime` | `Realtime.transport`, `RealtimeMediaSource`, codec tables (`FrameSource` itself is core's) |
| `superplayer-moq` | `MoqFrameSource`; not published (`LOCAL_ONLY`) |
| `superplayer-effects` | `VideoEffects.frameOutput`, ADR-0022's Android half over `setVideoEffects` |

Optional modules reach core's internals as Kotlin **friends** (a compiler flag, argued in
`KotlinFriendModules.kt`), never through public API, and every one is held to the rule that a player
built without it registers and allocates nothing — a test counts it.

Conventions that recur across the tour: `TelemetryEvent.SCHEMA_VERSION` (2) and
`SessionTrace.FORMAT_VERSION` (1) move on a change of *meaning*, never of shape; every policy
number is decided behind `PlaybackPolicy` in `StaticProfilePolicy` with its reason; and a gap a test
cannot reach is written down where the test stops rather than faked.

The roadmap is `PRD.md`: the `F1`–`F8` problem inventory, module requirements and the phase table
the issues are cut from. `android/benchmark/` runs `PRD.md` §6's matrix
(`android/benchmark/README.md`); its "a number moved" verdicts are Phase 11's exit criteria, not
blockers for the functional phases.

`PLAN.md` is an untracked, local-only scratch draft that `PRD.md` supersedes. **Do not read it, cite
it, or copy from it** — it names third parties and framings that must not reach a tracked file, and
where the two disagree `PRD.md` is right.

## Commands

```bash
(cd android && ./gradlew assemble check)             # build, lint, tests, repo verification, tracked API surface, format
(cd android && ./gradlew updateApiSurface)           # regenerate android/<module>/api/<module>.api after a deliberate API change
(cd android && ./gradlew updateGoldenTraces)         # regenerate src/test/golden/*.trace after a deliberate behaviour change; run alone, then check
(cd android && ./gradlew updateIosFixtures)          # regenerate ios/Tests/Fixtures/synthetic/ and throughput-traces/ after a deliberate generator or trace-reader change; run alone, then check (docs/testing.md)
(cd android && ./gradlew spotlessApply)              # reformat and stamp the Apache-2.0 header on every .kt file
(cd android && ./gradlew convertThroughputTrace --from=… --transport=… --input=… --output=…)  # paths relative to the repository root; docs/throughput-traces.md
(cd android && ./gradlew publishToMavenLocal)        # required before the demo will build
(cd android && ./gradlew release --release-version=x.y.z)  # cut a release, or refuse; NOT in check — it commits (docs/releasing.md)
(cd android && ./gradlew recordReleasedApiSurface)   # rewrite android/api/released/ by hand; the release command does it itself
(cd android/demo && ./gradlew assembleDebug lintDebug spotlessCheck)
android/benchmark/bench                              # PRD.md §6's matrix and its report; NOT in check (android/benchmark/README.md)
devicelab/lab run smoke                              # device run: trace, report, metadata (devicelab/README.md)
devicelab/lab run startup [--plant P]                # twenty cold starts in one trace; a plant is a patch built in and reverted (devicelab/plants/README.md)
devicelab/lab run jank [--plant P]                   # the feed scrolled and tapped in one trace: frames, main thread, GC (devicelab/jank/README.md)
devicelab/lab run frames|feed|smoke --platform ios   # an iPhone: SP_DEVELOPMENT_TEAM signs it, never committed (devicelab/README.md, *iOS*)
(cd android && ./gradlew huntLeaks)                  # the leak hunt on a device; NOT in check (devicelab/leak/README.md)
ios/check                                            # the iOS package's whole check, on macOS: format, simulator tests, tvOS build, API baselines
ios/check --update-api                               # regenerate ios/api/<Product>.baseline after a deliberate API change (docs/api-surface.md)
ios/check --update-golden                            # regenerate ios/Tests/Fixtures/golden/*.trace after a deliberate behaviour change; the goldens' only writer
ios/hunt-leaks                                       # the iOS leak hunt, xctrace over the feed on a simulator; NOT in check (ios/Demo/README.md)
```

`ios/check` is to the Swift package what `check` is to the library, and the macOS CI job runs it
alone; `ios/CLAUDE.md` says what it needs. Two iOS readings stay in Gradle's `check` because they
are text and need no Swift toolchain: `verifyModulePhaseRule` reads `Package.swift`, and
`verifyLicenseHeader` holds every Swift file to `config/license-header.txt` written as `//` lines.

`check` is the complete definition of the library's checks. Because `build-logic` is an included
build, its tasks are invisible to the library build's aggregate tasks — its tests run only because
`check` depends on them explicitly. A new check that can be a Gradle task belongs in `check`, not
only in CI (`.github/workflows/ci.yml`), so local and CI agree on what passing means.

`spotlessApply` in `android/` covers **all four Gradle builds** (the Swift package is `swift-format`'s). Spotless is configured by path rather than
by project, so the library build's `**/*.kt`, read from `android/`, reaches `demo/`, `benchmark/` and
`build-logic/` too, and one invocation formats every Kotlin file in the repository, since none lives
outside `android/`. The demo and the benchmark each apply Spotless a second time over their own
sources, which is why `spotlessCheck` is in their command lines: each has its own wrapper and CI
builds it with the library build not running at all, so each has to be able to enforce the format by
itself.

The rules are ktlint's, and they live in `.editorconfig` — including every rule switched off to
leave this codebase's style alone, each with the reason. The license header is
`config/license-header.txt`, and `check` verifies through `verifyLicenseHeader` that it
still says what `LICENSE` says.

The demo build needs the library published first: it consumes
`com.superplayer:superplayer-core:<version>` rather than `project(":superplayer-core")`, so that an
adopter's first failures — a missing transitive dependency, a malformed POM, a wrong artifactId —
happen here instead of in someone else's app.

Building needs an Android SDK: set `ANDROID_HOME`, or write `sdk.dir` into `android/local.properties`
(gitignored). Gradle itself does not need installing; the checked-in wrapper fetches the pinned
version.

**The Gradle daemon runs on JDK 17**, the version CI uses and the catalog's `jvmTarget`, and the
builds enforce it rather than asking for it. This matters more than it looks: the Robolectric
runtime the tests load is pinned in `android/superplayer-core/src/test/resources/robolectric.properties`,
and Robolectric's runtimes each require a minimum Java version, so a daemon on a newer JDK (Android
Studio's bundled JBR, say) would run configurations that then fail in CI. The other half of that
pairing is the daemon JVM criteria in `android/gradle/gradle-daemon-jvm.properties`: `toolchainVersion=17`.
`android/demo/` and `android/benchmark/` carry a copy each, because each has its own wrapper. Whatever `JAVA_HOME`
the launcher starts with, Gradle runs the daemon on a local JDK 17 it finds, and fails naming the
requirement when there is none. It downloads nothing: no toolchain resolver is declared, so a
missing JDK 17 is installed by hand. Any vendor will do. CI's is Temurin, set by `setup-java` in
`.github/workflows/ci.yml` and `device-run.yml`. Moving the version means changing the three
criteria files and those two workflows together. Edit the files by hand, because
`updateDaemonJvm` refuses to write one without a resolver. `(cd android && ./gradlew --version)` prints
the criteria in force on its `Daemon JVM:` line.

### Running the demo on an emulator

Some claims cannot be checked by any test here: `docs/testing.md` bars the network, so whether a
change still plays real HLS and DASH over a real CDN is a question only the demo on a device answers.
There is an AVD kept for it — `superplayer_verify_36`, API 36 — alongside whatever Android Studio has
created (`emulator -list-avds`).

```bash
emulator -avd superplayer_verify_36 -no-snapshot-load -no-boot-anim &
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
(cd android && ./gradlew publishToMavenLocal) && (cd android/demo && ./gradlew assembleDebug)
adb install -r -t android/demo/build/outputs/apk/debug/superplayer-demo-debug.apk
adb shell am start -n com.superplayer.demo/.MainActivity
```

On a television the same APK opens a Compose-for-TV screen instead (`TvScreen`, whose KDoc is the
lifecycle to copy): it is listed in the TV launcher, needs no touchscreen, and plays `TV_LEANBACK` through
`TvOutput` and `TvPlaybackControls` on a `SurfaceView`. `--es com.superplayer.demo.extra.SCREEN TV` opens it
on a phone. The TV emulator beside the phone's is `superplayer_tv_36`, and devicelab takes `--device tv`
(`devicelab/README.md`, *The television*). Nothing has booted it yet; #274's run is the first.

`devicelab/lab run smoke` is the executable version of this recipe and of every pitfall below. It
boots or adopts the device, publishes, builds the demo's `benchmark` build type, and installs it. It
refuses to go on unless the installed APK was built against the artifact it has just published. Then
it confirms playback is advancing and captures a Perfetto trace. `devicelab/README.md` is its manual.
The prose here stays because it is the explanation; the harness is the procedure, and a measurement
on a device goes through it rather than through a hand-typed copy of these lines.

Things that cost time the first time round:

- **The APK is `superplayer-demo-debug.apk`**, after the module name, not `demo-debug.apk` after the
  directory.
- **`adb devices` reports the emulator `offline` for a while after it appears.** Waiting on
  `sys.boot_completed` rather than on the device listing is what makes the install reliable.
- **The first launch raises the notification-permission dialog over the player surface.** A
  screenshot taken before it is dismissed shows a dialog and a black rectangle, which looks exactly
  like a playback failure and is not one. Dismiss it, then capture.
- **A screenshot is weak evidence on its own** — it cannot tell a rendered frame from a stalled one.
  The demo publishes a `PlaybackSession`, so the platform logs the state transitions, and a
  *position that advances between two of them* is the thing worth asserting on:

  ```bash
  adb logcat -c                       # before launching, so the window below is this run's
  adb logcat -d -t 2000 | grep -o 'state=PLAYING(3), position=[0-9]*'
  ```

  Media3 refreshes the session's position every few seconds while content plays, so `adb shell
  dumpsys media_session` shows the same thing as the current state and without a buffer to bound,
  and that is what devicelab waits on. Bound the logcat form with `-t`. An unbounded `adb logcat -d`
  against an emulator that has been up for a while dumps the whole buffer and can take minutes,
  which reads as a hang rather than as a slow command.

Re-run `publishToMavenLocal` and reinstall after any library change: the demo resolves SuperPlayer
from Maven local, so an APK built against a stale artifact will happily test the previous version.

The emulator is disposable and does exit on its own — a crash, a host sleep, an `adb` client that
takes it down with it. If `adb devices` comes back empty mid-session, boot it again and reinstall;
nothing about the verification depends on the instance surviving.

## Binding rules

Style preferences these are not. A change violating one is not accepted, whatever its merit.

The list below is the working set; [`docs/adr/README.md`](docs/adr/README.md) is the full ADR index,
one decision per record, and [`docs/README.md`](docs/README.md) indexes every document in the
repository.

- **Clean-room discipline** — `CONTRIBUTING.md`. No proprietary material, no mirroring a commercial
  SDK's API shape, and a public `// spec:` or `// ref:` citation on every non-obvious algorithm.
- **Every ADR's rules bind.** [`docs/codebase-tour.md`](docs/codebase-tour.md)'s *The binding ADRs
  in brief* gives each a paragraph; read the record (and its addenda, at the rule) before touching
  what it governs. The ones most often hit:
  - ADR-0001 — compose on Media3, never fork; no `@UnstableApi` type on the public surface except
    `player.exoPlayer`; Media3's version only in `android/gradle/libs.versions.toml`.
  - ADR-0002 — no local HTTP proxy.
  - ADR-0003 — `Player` by delegation, extending no Media3 class; wrappers forward every callback
    (one exception: a failure the ladder repaired).
  - ADR-0005/0009 — all buffer and selection policy behind `PlaybackPolicy`, naming no Media3 type;
    a policy constant at a Media3 call site is a bug.
  - ADR-0006/0010 — SuperPlayer chooses no storage; state travels as a `PlaybackSnapshot`, a cache
    lives in a directory the consumer opened.
  - ADR-0008 — telemetry through a Media3-free sink, bounded and lossy with every drop counted.
  - ADR-0011 — a failure is classified once, in `ErrorClassifier`.
  - ADR-0016 — one `HttpTransport` seam; nothing names OkHttp, Cronet or Ktor as a dependency.
  - ADR-0017 — semver in lockstep, the tracked API surface the arbiter; releases only through
    `(cd android && ./gradlew release)`.
  - ADR-0018 — realtime transports hand encoded frames through one `FrameSource`; nothing here
    decodes.
  - ADR-0020/0021 — iOS composes on `AVPlayer` and brings no other engine; its tests run against a
    loopback origin, checked by `ios/check`.
  - ADR-0022 — a frame output is a second output slot; tunneling yields to it.
- **[`docs/api-surface.md`](docs/api-surface.md)** — every published module's public API is tracked
  in `android/<module>/api/<module>.api` and validated by `check`. Changing it means running
  `(cd android && ./gradlew updateApiSurface)` and committing the diff in the same change. A leaked `@UnstableApi`
  Media3 type fails the build rather than merely showing up in a diff.
- **[`docs/testing.md`](docs/testing.md)** — tests drive the library through its public API under
  Robolectric, against Media3's own fakes, with no device and no network. Nothing asserts past the
  facade, and `player.exoPlayer` is an escape hatch for consumers rather than a way in for tests.
  The rule binds every change, not only the one that introduced it. It has one carve-out, argued in
  that document's *The conformance test a consumer runs*: `HttpTransportConformance` binds a server
  socket on the **loopback** interface and reaches nothing off the host, because an HTTP client's
  obligations are claims about what it puts on a wire. The Swift products are held to ADR-0021's
  seam instead, which widens that carve-out to every iOS test.
- Every dependency version lives in `android/gradle/libs.versions.toml`; the library and demo builds read
  that one file.
- A new dependency arrives with its `THIRD_PARTY.md` row in the same change.
- A change contradicting an ADR says so explicitly and argues the case. Winning that argument
  produces a superseding ADR, not an undocumented exception.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues, managed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Exploratory search

Broad search — "where is X handled", "what else calls Y", a sweep across modules or naming
conventions — goes through the `Explore` subagent rather than direct `grep` and `Read` calls. The
excerpts stay in the subagent's context and only the conclusion comes back, which is what stops a
long session from carrying every file it ever glanced at. Treat this paragraph as the authorization:
the default is to ask before spawning an agent, and this file is asking.

Direct tools remain right for a known path, a single file, or a search whose output is already
small. A subagent starts cold and re-derives context, so it earns its cost on fan-out and loses on
one grep.

### Bounded reading

A session carries the sum of everything it has looked at, so two habits decide whether a long one
stays affordable.

**Read slices, not whole files.** `PRD.md` is ~34 KB and `docs/testing.md` ~15 KB — call it 8k and
4k tokens. Reading either end to end to check one table costs more than the conversation that
prompted the question. `grep -n` for the heading, then `sed -n '400,430p' PRD.md`, costs a fraction
of it. Read a file whole when the whole file is the subject: a build script being changed, a source
file under review. Not to answer a question about one paragraph of it.

**Bound what a command prints.** `git log`, `find`, `grep` across the tree, and Gradle all have
modes that return thousands of lines nobody reads, and every one of them lands in context in full.
Pipe through `head`, narrow with `grep -o`, cap with `-t` or `--max-count`. The `adb logcat -t`
warning under *Running the demo on an emulator* is one instance of this rule; the reason it earns
its own paragraph there is that an unbounded dump also reads as a hang.
