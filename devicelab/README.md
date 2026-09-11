# devicelab

Drives the demo on a device and comes back with a Perfetto trace, a report, and a record of what was
measured on what. One entry point, `devicelab/lab`, for everything that measures SuperPlayer on a
device: the benchmark harness (#43), the leak hunt (#50, [`leak/README.md`](leak/README.md)), the
UI jank measurement (#51), and whatever comes after them.

```bash
devicelab/lab run smoke                                   # boot or adopt a device, build, install, play, trace
devicelab/lab run smoke --data-sources java_hprof,heapprofd
devicelab/lab run leak-hunt                               # the leak hunt; also ./gradlew huntLeaks
devicelab/lab run smoke --serial emulator-5556            # the device you are watching
devicelab/lab list                                        # scenarios and Perfetto data sources
devicelab/lab config --data-sources frametimeline         # the Perfetto config a run would use
devicelab/lab device                                      # just a booted device; prints its serial
devicelab/test/selftest                                   # the device-free half, checked without one
```

A consumer says *this scenario, these data sources*. How an emulator boots, which build got
installed, and whether the dialog was in the way are this directory's problem, solved once.

## Why this is not a test, and not in `check`

[`docs/testing.md`](../docs/testing.md) bars devices and the network from the test suite, and every
run here needs both. That is not a breach of the rule, because nothing here is a test of the kind
that rule governs: it does not run in the suite, gate a change, or run without a device. It
*measures*, and its output is a trace someone reads. A scenario may judge what it measured — the
leak hunt does — and then a run with a failing verdict still writes everything and exits 3, apart
from every other failure, but it is still a measurement someone reads, on a schedule, rather than a
check a change must pass. So it sits beside the build rather than in it. `./gradlew check` does not run it, does
not know it exists, and passes with no device attached. Like `benchmark/`, it has its own entry point
and its own workflow, as `benchmark/` will when #43 builds it.

The one part that *is* checkable without a device is `devicelab/test/selftest`: the parsing, the
Perfetto config builder, the stale-artifact comparison and the trace processor's pinning, all pure
functions over text. It also loads every scenario the way `lab` does — sourced as a statement of its
own under `set -euo pipefail` — because a stray line at a scenario's top level passes `bash -n` and
then fails the run after a build and a boot. It then runs each consumer's own device-free half,
`devicelab/<consumer>/test/selftest` (the leak hunt's heap diff, verdict and report rendering). It is in
`check`, as `verifyDevicelab`, because it needs neither a device nor a network, and a check that can
be a Gradle task belongs there. A push that breaks devicelab's parsing therefore fails CI rather than
the next device run. The device workflow runs it again first, so a device run never spends a boot on
a harness that is already broken.

## What a run does

In this order. Each step is a failure somebody already hit by hand, and most of the steps are
[`CLAUDE.md`](../CLAUDE.md)'s "Running the demo on an emulator" turned from prose into code.

1. **Composes the Perfetto config first**, so a misspelt data source costs a second rather than a
   build and a boot.
2. **Gets a device** (`lib/device.sh`). It uses the one you named with `--serial` or
   `ANDROID_SERIAL`, or adopts the one that is attached, or boots the AVD in `device.properties`.
   Readiness means `sys.boot_completed`, never the device listing, because an emulator shows as
   `offline` for a while after it appears. An offline emulator is waited on rather than duplicated.
   Every `adb` call is bounded, because an emulator can stay listed as `device` with an adbd that no
   longer answers. An unbounded call to one hangs forever, which reads as a slow run. If the
   harness's own AVD stops answering, it is killed and booted again, since that AVD is disposable. A
   device you named is never restarted: the run fails and leaves it for you.
3. **Publishes, then builds the demo**, always both and always in that order. The demo resolves
   SuperPlayer from Maven local, so building it alone measures whatever was published last. The
   commit and whether the tree was dirty are recorded, and a tree that changes during the build fails
   the run.
4. **Installs, then proves what it installed** (`lib/artifacts.sh`). This is the stale-artifact
   check, described below.
5. **Grants `POST_NOTIFICATIONS` before launch.** The dialog it would otherwise raise sits over the
   player surface, and a screenshot under it looks exactly like a playback failure. Granting is a
   deliberate departure from dismissing the dialog. It needs no tap, and it measures the app as a
   viewer who said yes leaves it, notification included. After launch the run also fails if anything
   but the demo has focus.
6. **Starts the trace**, at launch or once playback is confirmed, as the scenario chooses. The trace
   is a detached Perfetto session named after the run, and it is stopped by that name. A scenario's
   own traces (below) are sessions of the same kind, run by the same code in `lib/perfetto.sh`. It is not
   stopped by signal, because on API 36 the shell user may not signal perfetto. Detaching rules out
   `--background-wait`, so a pause of `TRACE_SETTLE_S` (default 2 s) stands in for every data source
   confirming it has started. If a run fails, its session is stopped and its file deleted, rather
   than left tracing until the bound runs out. That covers every session the scenario opened too.
7. **Waits for playback to advance, not for a sleep** (`lib/playback.sh`). It reads the demo's media
   session from `dumpsys media_session`. The state must be PLAYING and the position must rise between
   two samples; Media3 refreshes it every few seconds while content plays. A session in the error
   state fails the run at once. `dumpsys` rather than logcat because it is the current state and a few
   kilobytes, whereas an unbounded `adb logcat -d` on a long-lived emulator takes minutes.
8. **Drives the scenario**, stops the trace, and pulls everything. A device that stopped answering
   part-way fails the run: its trace describes a run that did not happen.

## Stale-artifact detection

Measuring against a stale `publishToMavenLocal` is the footgun that matters most, because nothing
fails. The APK builds, installs and plays, and it is measured as the previous version.

So the demo records what it was built against. Its build writes `assets/superplayer-artifacts.txt`
into the APK: every `com.superplayer` AAR on its runtime classpath, with its sha256 (see
`RecordSuperPlayerArtifacts` in [`demo/build.gradle.kts`](../demo/build.gradle.kts)). After
installing, the harness pulls the APK back off the device. First it checks that the APK is the one
just built, which catches an install that silently kept the old one. Then it compares the recorded
hashes with the AARs in Maven local, which the run has just published. Any difference stops the run
before anything is measured:

```
com.superplayer:superplayer-core:0.1.0-SNAPSHOT
  built against:     5f0c…
  published locally: 91ab…
the APK was not built against the SuperPlayer just published; measuring it would measure another version
```

Maven local is `~/.m2/repository` unless `MAVEN_REPO_LOCAL` says otherwise.

## The `benchmark` build type, and `<profileable>`

Runs use the demo's `benchmark` build type unless told `--build-type debug`. It is release-like and
not debuggable, but signed with the debug key so it installs. A debuggable build runs differently and
meaningfully slower. Frame timings taken from one describe a build nobody ships, and that is not a
conservative estimate of the real number; it is a different number. The leak hunt could tolerate a
debug build, since a retained reference is retained either way. It uses `benchmark` too, so that a
leak report and a jank report describe the same APK.

A non-debuggable app is invisible to Perfetto's `java_hprof` and `heapprofd`, and to Macrobenchmark,
unless it is *profileable*. So the demo's manifest declares `<profileable android:shell="true" />`.
The declaration is in the demo, not the library: whether shell tools may profile an app is the app's
decision about itself. A library that declared it would merge that decision into every consumer's
release build, which is the shape ADR-0007 rule 4 rejects for the foreground-service permissions. No
`superplayer-*` module declares it. `run.json` records what the installed app actually is rather
than what its build type is supposed to mean. `debuggable` comes from the device's package flags.
`profileable_by_shell` comes from the manifest of the APK pulled back off the device, because on
API 36 `dumpsys package` reports no flag for it.

If release builds ever turn on minification, `benchmark` inherits it, and a heap graph from it needs
the mapping file to be readable.

## Scenarios

A scenario is `scenarios/<name>.sh`, sourced into the run once the harness's functions are loaded.
[`scenarios/smoke.sh`](scenarios/smoke.sh) is the template.

| Defines | Required | Meaning |
| --- | --- | --- |
| `scenario_drive` | yes | What happens once playback is confirmed. Returns when the scenario is over. |
| `scenario_report` | no | Prints Markdown, appended to `report.md` under the scenario's name. |
| `SCENARIO_DESCRIPTION` | no | One line, shown by `lab list`. |
| `SCENARIO_TRACE_FROM` | no | `launch` (default) or `playing`: whether the trace covers startup. `--trace-from` overrides it. |
| `SCENARIO_TRACE_MS` | no | Upper bound on the trace, default 300000. `--trace-ms` overrides it. |
| `SCENARIO_DATA_SOURCES` | no | The run trace's data sources when `--data-sources` is empty or absent. |
| `scenario_prepare` | no | Runs before a device is found or anything is built: what the scenario needs from the host. |
| `scenario_verdict` | no | Runs once everything is written; returning non-zero makes the run exit 3. |
| `scenario_cleanup` | no | Runs on every exit, success or failure: puts back what the scenario changed on the device. |

From inside `scenario_drive` a scenario can use:

- `adb_s` (adb against the run's device, bounded) and `adb_pull`;
- `wait_for_playback [timeout]`, `session_state` and `session_description` (both read `dumpsys
  media_session` on stdin), `assert_demo_in_focus`;
- `relaunch_demo [am args]`, which starts the demo afresh with launch extras and replaces the Activity
  that was showing, and `foreground_demo`, which brings a backgrounded demo back as the launcher does;
- `ui_swipe_up [ms]` and `ui_swipe_down [ms]`;
- `trace_begin name sources ms` and `trace_end name` for a trace of its own over a span it chooses,
  and `capture_trace name sources ms` for one run to its bound — a heap dump at a moment it chooses,
  since `java_hprof` dumps when its data source starts. Each is written into the run directory as
  `<name>.perfetto-trace` beside `<name>.perfetto-config.pbtxt`;
- `trace_processor_query trace query.sql [NAME=value…]`, which runs the pinned trace processor;

and the variables `SERIAL`, `DEMO_PACKAGE`, `RUN_DIR` and `RUN_PLAYBACK_POSITION_MS`.

There is no tap-by-text helper, on purpose. The tool for finding a button by its text is
`uiautomator dump`, and it only reads a screen that goes idle. The demo never goes idle while it
plays: on API 36, every dump of the player screen or the feed during playback fails with "could not
get idle state". Scenarios run during playback by construction, so such a helper would work only
when it caught a still moment. Taps at fixed coordinates are no better, since they land somewhere
else on the next device. A scenario that needs a screen other than the one the demo opens on should
get there through a launch argument the demo reads. The leak hunt was the first to need one, so the
demo reads three — the screen, the feed's length, and a content id to hand to the session — which
`DemoLaunch` in `MainActivity.kt` documents, and `relaunch_demo` passes.

Scenarios belong to their consumers. `smoke` measures nothing; `leak-hunt` is the leak hunt's, and
its analysis lives beside it in [`leak/`](leak/README.md).

## Perfetto data sources

Every trace gets [`perfetto/base.pbtxt`](perfetto/base.pbtxt): buffers, the duration bound,
streaming to file, process names, and the package list, which records inside the trace itself which
build of the demo it saw. `--data-sources` adds fragments from `perfetto/sources/`:

| Source | Records | For |
| --- | --- | --- |
| `java_hprof` | Java heap graph of the demo, one dump at data-source start; refused with `--trace-from launch` | leak hunt (#50) |
| `heapprofd` | native allocations with callstacks | leak hunt (#50) |
| `frametimeline` | SurfaceFlinger's expected and actual timeline for every frame | UI jank (#51) |
| `process_memory` | per-process memory counters, polled every second | benchmark peak RSS (#43) |
| `battery` | battery counters, polled every second (simulated on an emulator) | benchmark battery delta (#43) |

## The trace processor

Consumers query traces with Perfetto's `trace_processor_shell`, and `lib/trace_processor.sh` is the
one place it comes from. It is not vendored. It is downloaded on first use for the host's platform,
at the version the catalog names as `perfetto`. The archive is checked against
`perfetto/trace-processor.sha256`, and one that does not match is refused rather than run. It is
cached under `~/.cache/superplayer-devicelab`. `TRACE_PROCESSOR=/path` points at one already on the
machine instead, for a host with no network; whoever sets it vouches for it.

## Perfetto fragments

A fragment is text-format `TraceConfig` and may use two placeholders, `DURATION_MS` and `PACKAGE`,
each written with an at-sign on either side. A new data source is a new file. If a consumer needs a
source configured differently, for example a heap dump at intervals rather than at start, it adds a
fragment rather than editing one another consumer relies on.

## What a run leaves behind

`devicelab/out/<UTC timestamp>-<scenario>/`, gitignored:

| File | What it is |
| --- | --- |
| `trace.perfetto-trace` | the trace: https://ui.perfetto.dev, or `trace_processor_shell` |
| `perfetto-config.pbtxt` | the config that produced it |
| `run.json` | what was measured on what, in one shape for every consumer |
| `report.md` | the same, for a person, plus the scenario's own section |
| `superplayer-artifacts.txt` | the SuperPlayer AARs the APK was built against, by sha256 |
| `logcat.txt` | this run's logcat, at most its last 20000 lines |
| `screen.png` | the screen when the scenario ended |
| `build.log` | the publish and the demo build |

`run.json` carries the SuperPlayer commit and whether the tree was dirty, the SuperPlayer version and
artifact hashes, the Media3 version from the catalog, the demo's build type and APK hash, and the
device: serial, emulator or not, AVD, model, API level, ABI and build fingerprint. It also records the
scenario, the data sources, whether the trace hit its bound before the scenario ended, and where
playback was confirmed. Its `schema` is bumped when a field changes meaning, not when one is added. A
consumer that needs more writes a file beside it rather than a field inside it, so that runs from
different consumers stay comparable.

## Devices

`device.properties` describes the AVD: its name (`superplayer_verify_36`, the one `CLAUDE.md` names),
API, image tag and device profile. It is the one description used both to boot the AVD here and to
create it in CI. The ABI is not in it, because the host decides the ABI: arm64-v8a on an Apple-silicon
Mac, x86_64 on a Linux runner. `run.json` records it.

To create the AVD on a machine that lacks it: `devicelab/lab create-avd`. It installs the image
for this host's ABI and creates the AVD, and it is the same command CI runs.

A booted emulator is left running after the run, for a human to look at. `--headless`, the default
when `CI` is set, boots it without a window.

## CI

[`.github/workflows/device-run.yml`](../.github/workflows/device-run.yml) is a reusable workflow: one
run of one scenario on an emulator, with the output uploaded as an artifact. A consumer's scheduled
workflow calls it with its own scenario and data sources. It can also be started by hand from the
Actions tab. It never runs on push.

It is built from two composite actions. `setup-android-sdk` is the SDK and the catalog's
`compileSdk` platform. It was factored out of `ci.yml`, which now uses it too, so there is one
platform lookup. `android-emulator` enables KVM, then runs `devicelab/lab create-avd` and
`devicelab/lab device`. The device description and the boot-wait therefore live in the harness alone
rather than being written a second time in YAML.

Its first scheduled consumer is [`leak-hunt.yml`](../.github/workflows/leak-hunt.yml), nightly,
which raises the job's bound through the `timeout-minutes` input. Neither has been run on GitHub yet,
so that first night is the workflow's first real test. Two things are worth watching on that run: that the emulator started in one
step is still running in the next, and that boot fits in the job's timeout on a KVM runner.

## Limits

- The harness has been run end to end on the API 36 emulator only. A physical device takes the
  adoption path: it is never booted or restarted. The emulator-specific parts, such as the AVD name
  and `ro.boot.qemu`, then simply read empty.
- One device per run. When several are attached, the run fails and asks for `--serial` rather than
  guessing.
- It is written for bash 3.2, because that is what macOS ships, and needs `perl`, `unzip`,
  `shasum` or `sha256sum`, and `git`.
- It analyses nothing itself. It fetches the trace processor and runs queries, but the queries
  belong to the consumers: heap queries, frame percentiles and benchmark tables, each with its own
  scenario and report. The leak hunt's are in `leak/sql/`.
