# devicelab

Drives the demo on a device and comes back with a Perfetto trace, a report, and a record of what was
measured on what. One entry point, `devicelab/lab`, for everything that measures SuperPlayer on a
device: the benchmark harness (#43), the leak hunt (#50, [`leak/README.md`](leak/README.md)), the
UI jank measurement (#51), and whatever comes after them.

```bash
devicelab/lab run smoke                                   # boot or adopt a device, build, install, play, trace
devicelab/lab run smoke --data-sources java_hprof,heapprofd
devicelab/lab run leak-hunt                               # the leak hunt; also (cd android && ./gradlew huntLeaks)
devicelab/lab run startup                                 # twenty cold starts in one trace
devicelab/lab run startup --plant startup-main-thread-io  # the same, built with a known regression
devicelab/lab run jank                                    # the feed scrolled and tapped, one trace
devicelab/lab run jank --plant feed-tap-sleep             # the same, built with a known regression
devicelab/lab run smoke --serial emulator-5556            # the device you are watching
devicelab/lab run smoke --device tv                       # the same run on the television emulator
devicelab/lab list                                        # scenarios, Perfetto data sources and plants
devicelab/lab config --data-sources frametimeline         # the Perfetto config a run would use
devicelab/lab device                                      # just a booted device; prints its serial
devicelab/lab run smoke --platform ios                    # the same run on an iPhone, through Xcode's tools
devicelab/lab run frames --platform ios                   # i1: frames the pass-through output never saw
devicelab/lab run feed --platform ios                     # i4: the 200-row feed's TTFF and memory
devicelab/lab run moq-smoke --platform ios                # i9: a MoQ broadcast over QUIC, as an XCUITest
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
check a change must pass. So it sits beside the build rather than in it. `(cd android && ./gradlew check)` does not run it, does
not know it exists, and passes with no device attached. `android/benchmark/` sits outside `check` for the
same reason and has its own entry point, `android/benchmark/bench` — see
[`android/benchmark/README.md`](../android/benchmark/README.md), which makes the same argument from its side.

The device half of that benchmark is **not yet a scenario here**, and the gap is worth naming: this
harness is currently shaped around the demo throughout — the package, the activity, the APK path, and
a readiness probe that reads a media session `android/benchmark/`'s app does not publish. `BenchmarkActivity`
exists and plays a 30-minute session on a device; taking peak RSS and battery off that process means
teaching `lab` to drive a second app, which is a change to a harness whose whole value is that it is
reliable, and it is tracked as #95 rather than half-done.

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
2. **Gets a device** (`lib/device.sh`) of the kind `--device` asks for, a phone unless it says `tv`. It
   uses the one you named with `--serial` or `ANDROID_SERIAL`, failing if that is the other kind, or
   adopts the one of that kind that is attached, or boots that kind's AVD in `device.properties`.
   Readiness means `sys.boot_completed`, never the device listing, because an emulator shows as
   `offline` for a while after it appears. An offline emulator is waited on rather than duplicated —
   unless it is on its way out rather than in: one that leaves the listing for longer than
   `VANISHED_GRACE` seconds (20 by default, so an `adb` restart is not mistaken for it) is replaced by
   a fresh boot of the harness's AVD instead of being waited on until `BOOT_TIMEOUT`.
   Every `adb` call is bounded, because an emulator can stay listed as `device` with an adbd that no
   longer answers. An unbounded call to one hangs forever, which reads as a slow run. If the
   harness's own AVD stops answering, it is killed and booted again, since that AVD is disposable. A
   device you named is never restarted: the run fails and leaves it for you.
3. **Publishes, then builds the demo**, always both and always in that order. The demo resolves
   SuperPlayer from Maven local, so building it alone measures whatever was published last. The
   commit and whether the tree was dirty are recorded, and a tree that changes during the build fails
   the run. With `--plant`, the plant is applied to the clean tree just before the publish (*Plants*,
   below), so it is in both builds.
4. **Installs, then proves what it installed** (`lib/artifacts.sh`). This is the stale-artifact
   check, described below. A plant is reverted here, once the APK is on the device, and the tree is
   checked clean before anything is measured.
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
`RecordSuperPlayerArtifacts` in [`android/demo/build.gradle.kts`](../android/demo/build.gradle.kts)). After
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

## `moq-smoke`, which is not a scenario

[`moq-smoke`](moq-smoke) is the other thing in this directory that needs a device, and it is a
sibling of `lab` rather than a scenario under it (issue #367). A scenario drives the **demo app** —
it launches an activity, waits on a `MediaSession` position and captures a Perfetto trace — and this
run has none of those: it opens one real MoQ session against a public relay and counts frames, with
no player, no surface and no activity anywhere in it. What it borrows is the device half, `lib/device.sh`'s
boot-or-adopt, because that is the part the two genuinely share.

```bash
devicelab/moq-smoke                                    # the default relay
devicelab/moq-smoke --broadcast moq://host/name        # somewhere else
```

It runs `superplayer-moq`'s `connectedDebugAndroidTest`, the repository's only instrumented test,
and writes `devicelab/out/moq-smoke-*/report.txt` — the observations scraped out of logcat, the
device's ABIs and API level, and the Gradle log beside them. **The artifact is the observation**:
the report is written whether the run passed, failed or skipped, because a relay that is up today
may be down tomorrow and a finding that fails is still a finding.

On an iPhone the same smoke is a scenario, `lab run moq-smoke --platform ios` (*The moq-smoke
scenario*, below), because there the lab's iOS half already has the device, the signing and the build
it needs, and one XCUITest target holds both halves — the transport, and the demo playing it.

A relay that answers and announces nothing is a **skip**, not a failure. There is no broadcast to
subscribe to and nothing in this repository could produce one, and a run that goes red whenever a
public relay is quiet is a run whose red stops being read. Anything else fails, with the cause chain
and the relay's own announce listing in the message. `docs/testing.md`'s *The first real MoQ
session* is the argument, including what the run deliberately does not measure.

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
| `scenario_setup` | no | Runs once playback is confirmed and before a `playing` trace starts: gets the demo to where the measurement begins, so that getting there is not in the trace. |
| `scenario_verdict` | no | Runs once everything is written; returning non-zero makes the run exit 3. |
| `scenario_cleanup` | no | Runs on every exit, success or failure: puts back what the scenario changed on the device. |
| `SCENARIO_PLATFORMS` | no | `android` (default), `ios`, or both: which `--platform` may run it. A run on any other is refused before anything is built. |
| `SCENARIO_IOS_LAUNCH` | no | On iOS, the demo's launch arguments, an array; `(-SPDemoScreen playback)` by default. |
| `SCENARIO_IOS_UI_TEST` | no | On iOS, a UI test (`Target/Class`) the run is instead of a launch: built for testing, run by `xcodebuild test-without-building`, nothing recorded; `ios_test_demo` in lib/ios.sh. |
| `SCENARIO_IOS_TEST_ENV` | no | With `SCENARIO_IOS_UI_TEST`, `NAME=value`s handed to the test runner (as `TEST_RUNNER_NAME`, the prefix xcodebuild strips). |

From inside `scenario_setup` and `scenario_drive` a scenario can use:

- `adb_s` (adb against the run's device, bounded) and `adb_pull`;
- `wait_for_playback [timeout]`, `session_state` and `session_description` (both read `dumpsys
  media_session` on stdin), `assert_demo_in_focus`;
- `open_feed rows`, which pauses the service's player and relaunches the demo on its feed, and
  `wait_session_state state timeout`;
- `relaunch_demo [am args]`, which starts the demo afresh with launch extras and replaces the Activity
  that was showing, and `foreground_demo`, which brings a backgrounded demo back as the launcher does;
- `ui_swipe_up [ms]` and `ui_swipe_down [ms]`, and `ui_tap_centre` for a screen whose middle is one target;
- `trace_begin name sources ms` and `trace_end name` for a trace of its own over a span it chooses,
  and `capture_trace name sources ms` for one run to its bound — a heap dump at a moment it chooses,
  since `java_hprof` dumps when its data source starts. Each is written into the run directory as
  `<name>.perfetto-trace` beside `<name>.perfetto-config.pbtxt`;
- `trace_processor_query trace query.sql [NAME=value…]`, which runs the pinned trace processor;

and the variables `SERIAL`, `DEMO_PACKAGE`, `RUN_DIR` and `RUN_PLAYBACK_POSITION_MS`. An iOS scenario
has `wait_for_playback`, `RUN_DIR` and `RUN_PLAYBACK_POSITION_MS` too, and in place of the rest
`ios_launch_demo name args…`, `ios_wait_for_log regex seconds`, `IOS_LOG` (the current launch's
console) and lib/ios.sh's readers (*iOS*, below).

There is no tap-by-text helper, on purpose. The tool for finding a button by its text is
`uiautomator dump`, and it only reads a screen that goes idle. The demo never goes idle while it
plays: on API 36, every dump of the player screen or the feed during playback fails with "could not
get idle state". Scenarios run during playback by construction, so such a helper would work only
when it caught a still moment. Taps at fixed coordinates are no better, since they land somewhere
else on the next device. The one tap there is, `ui_tap_centre`, is not one of those: it is a fraction
of the screen, like the swipes, and it is for a screen whose whole middle is one target — the feed,
which takes a tap anywhere on the list — so it finds the same thing on any phone. A scenario that needs a screen other than the one the demo opens on should
get there through a launch argument the demo reads. The leak hunt was the first to need one, so the
demo reads three — the screen, the feed's length, and a content id to hand to the session — which
`DemoLaunch` in `MainActivity.kt` documents, and `relaunch_demo` passes.

Scenarios belong to their consumers. `smoke` measures nothing; `leak-hunt` is the leak hunt's, and
its analysis lives beside it in [`leak/`](leak/README.md); `startup` is the startup measurement's, beside
it in [`startup/`](startup/README.md); and `jank` is the jank measurement's, beside it in [`jank/`](jank/README.md).

## iOS

`--platform ios` (or `DEVICELAB_PLATFORM=ios`) is the same run on an iPhone (#435, `docs/ios-plan.md`
§6): a device, a fresh build, playback confirmed advancing, a trace, the scenario, and the same three
outputs. [`lib/ios.sh`](lib/ios.sh) is all of it, and its pure half — the tools' listings, the demo's
log, xctrace's export, the feed's verdict — is in `test/selftest` with fixtures from the phone it was
first run on.

```bash
SP_DEVELOPMENT_TEAM=<team id> devicelab/lab run frames --platform ios     # a phone; the team signs the demo
SP_DEVICE=<udid> SP_DEVELOPMENT_TEAM=<team id> devicelab/lab run feed --platform ios
SP_SIMULATOR=sp-sim devicelab/lab run smoke --platform ios               # a simulator, booted if it is not
```

| Step | Android | iOS, and why it differs |
| --- | --- | --- |
| Device | `adb`, booting an AVD if none | `devicectl`'s listing: the one `--serial` or `SP_DEVICE` names by UDID; else `SP_SIMULATOR`'s, booted if it is not; else the one physical device that is paired, booted and in Developer Mode; else the one booted simulator. A phone is never booted or restarted |
| Build | `publishToMavenLocal`, then the demo | one `xcodebuild` of `ios/Demo`, Release for `benchmark` and Debug for `debug`. The demo resolves the package **by path**, so the app is the tree and there is no published artifact to be stale; its executable's hash goes in `run.json` instead |
| Signing | the debug key | `SP_DEVELOPMENT_TEAM`, passed with `-allowProvisioningUpdates` and **never committed**: a run that finds the project file changed after the build fails, so a team Xcode wrote into it is reverted rather than measured |
| Playing | `dumpsys media_session` | the demo's own position lines, read off its console. iOS publishes no playback state a shell can read, so the demo logs it (`DemoPlayback`, `FeedPlayback`), and two lines of one content with the position risen is the same evidence |
| Log | logcat | `devicectl device process launch --console` with `OS_ACTIVITY_DT_MODE`, which copies the app's own `os_log` lines to the console, because `log collect` from a phone needs root. One `console-<launch>.log` per launch |
| Trace | Perfetto | xctrace's **Activity Monitor**, as `trace.trace`, with the demo's footprint exported from it as `footprint.tsv`: over every process beside the console, or — for a scenario that sets `SCENARIO_IOS_RECORDED_LAUNCH`, `feed` — with xctrace launching the demo and recording its `os_log` too, the console read back out of the recording |

Five things cost time the first time round, and each is handled where it bites:

- **devicectl reads the app's arguments as its own** unless they follow `--`: `-SPFeedScroll` reached it
  as `-l`, a missing log path.
- **The console session owns the app.** Ending it ends the process, which is how a run stops the demo;
  and its lines end in a carriage return, which every reader drops first.
- **xctrace cannot attach to a process the console launched** ("Cannot find process"), and a launch by
  xctrace would take the console the log is read from. So the recording is Activity Monitor over
  every process, and the demo's rows are found by name and pid.
- **A recording over every process ends after about a second**, its table of contents saying
  `Device disconnected`, while devicectl goes on working — over the network and on a cable alike, so
  the cable was not the cause, as this harness first guessed. A recording of a process xctrace
  *launched* runs to its limit. So a scenario that needs the recording whole sets
  `SCENARIO_IOS_RECORDED_LAUNCH=1`: xctrace launches the demo with the Activity Monitor template and
  the `os_log` instrument, nothing is waited on while it runs, and the demo's lines are rebuilt from
  the recording into `console-main.log` (`ios_oslog_lines`), where every reader finds them. Such a
  scenario drives itself and states its own length. A run records why a recording ended in
  `run.json` (`xctrace.end_reason`, `trace_ended_early`), and a scenario that needs it reads `unknown`
  when it ended early.
- **A phone is not a device until it says so.** devicectl lists a phone that is unpaired, locked out
  of Developer Mode or still booting, and an install to one fails minutes later; such a phone is not
  adopted.

There is no `screen.png` on iOS — nothing in Xcode's command-line tools takes a phone's screenshot — and
no plants, data sources or AVDs: `--plant`, `--data-sources`, `--device tv`, `config` and `create-avd`
are refused rather than ignored. tvOS is not reached yet.

### The frames scenario

i1's device exit: *the pass-through frame output drops no frame `AVPlayerLayer` would have shown,
counted.* An `AVPlayerLayer` shows the content's frames at their presentation times, so the frames it
would have shown over a stretch of playback are the content's frames between two presentation times,
and those can be counted from the times the output was handed without a layer at all. The demo's
`FrameTally` (FrameOutputScreen.swift) does it: within a stretch of one picture size, an HLS variant's
frame interval is constant (`FRAME-RATE`, one per variant), the shortest gap between two handed frames
is that interval, so a gap between two handed frames held `round(gap / shortest) − 1` frames the output
never saw — rounded, so timestamps that alternate between two tick counts count nothing. The demo logs
the tally beside its position every two seconds, with the interval and the largest gap and where it
ended, so a loss can be found and seen to be a whole number of frames; the scenario reads it after
`FRAMES_SECONDS` (default 120).

Two refinements are what make the number mean what the criterion says. **The verdict is taken over
steady playback** (`ios_frame_steady`): two consecutive lines whose position advanced by exactly two
seconds with no rebuffer or seek of the collector's between them. A starved item hands over late frames
by skipping them, and an `AVPlayerLayer` on the same starved item would have shown no more, so frames
missing around a stall are the network's to explain; they are reported beside the verdict, not judged.
A session with less than half its time steady is `unknown`. **And the control is measured, not assumed**:
the same stream on the playback screen, whose `VideoPlayer` is an `AVPlayerLayer`, for the same time,
with AVFoundation's own count of the frames that layer dropped (`dropped=`, the access log's
`numberOfDroppedVideoFrames`).

**The demo declares `CADisableMinimumFrameDurationOnPhone`.** Without it an iPhone holds the output's
display link at 60 Hz, and a 60 fps stream then loses a frame each time the display's clock drifts
across a frame boundary (#491) — so a build of the demo without the key reads as a regression of the
library when it is one of the app.

What it cannot see: a frame lost exactly at a variant switch, since the gap across a switch is judged by
nothing (the report counts the switches); a loss before a stretch has shown its interval, which is
judged against a longer one; whether the display layer then presented each frame **on time** against
the item's timebase; and Picture in Picture. A frame rate that changes at one picture size would read
as losses — a false *not met*, never a false pass, and the largest gap shows it. A frame the output was handed and the layer
then failed to show is not a frame this count can miss — and not one it can find.

### The moq-smoke scenario

i9's device exit (#438): *a broadcast plays on a device*, and the iPhone's reading of Android's
`devicelab/moq-smoke`. The scenario is the XCUITest `MoqLiveSessionSmokeTests`
(`ios/Demo/SuperPlayerDemoUITests`), so the run builds for testing and hands the phone to
`xcodebuild test-without-building` — no launch, no console, no recording — and reads the test's log:

- **the transport**, in the test runner with no player: `MoqFrameSource` subscribed to the broadcast,
  its declared tracks and their codec-configuration branch, thirty frames counted, the session's
  statistics, and no frame after `cancel()`. A failure, or `SP_MOQ_PROBE_MAPPINGS=true`, also asks the
  relay what it announces and which of the two address splits routes, in the three arms #367 used —
  which is how #367's two defects are checked for on iOS. A passing run without the flag exercises
  `UniffiMoqRelay`'s two fixes only by working, so **the flag is what puts the evidence in the report**;
- **the demo** playing the same broadcast on `MoqScreen`, read off its overlay's accessibility value,
  the line it also logs (`content=… position=<ms> rendering=<bool> received=<bytes> rtt=<ms>`):
  played when two rendering readings show the clock risen by at least four seconds and the received
  bytes risen too.

```bash
SP_DEVICE=<udid> SP_DEVELOPMENT_TEAM=<team id> devicelab/lab run moq-smoke --platform ios
SP_MOQ_BROADCAST=moq://host/namespace/name SP_MOQ_PROBE_MAPPINGS=true devicelab/lab run moq-smoke --platform ios
```

`outcomes.tsv` holds each test case and how it ended, and `observations.txt` every block the tests
printed — written before any assertion runs, so a failing run is still a finding. A skip (a relay that
answers and announces nothing) is not a failing verdict, for Android's reason; a failure, or a runner
that never started a test, is.

Two things cost time the first time round. **UI automation is a setting on the phone**: until
*Settings > Developer > Enable UI Automation* is on, the runner fails with "Timed out while enabling
automation mode", which the outcomes name as `runner`. And **the default broadcast is somebody
else's**: `MoqScreen.defaultBroadcast` was changed when the Android default stopped being announced,
and will need changing again — `SP_MOQ_BROADCAST` is the way round it without a rebuild, and the
relay's own HTTP listing (`curl https://<relay>/announced/<namespace>`) is how to find a live one.

### The feed scenario

i4's device exit: *TTFF in the feed measured; memory flat over 200 rows in xctrace.* The feed scrolls
itself — `-SPFeedScroll`, as `ios/hunt-leaks` drives it, because nothing outside an iOS app can scroll a
phone's screen — down `FEED_ROWS` (200) rows and back `FEED_PASSES` (2) times at `FEED_DWELL_MS` (1000) a
row, after a `-SPFeedLeadInMs` of five seconds on the first row so that its position is logged twice
and the run's readiness holds. Time to first frame is each row's `first_frame` event from declared
intent, as median, p90 and max. Memory is the demo's physical footprint in the recording, judged per
pass as the leak hunts judge it (`ios_feed_report`): the first pass is the baseline, and a later pass is
**flat** when its median is at or under the first's p90, **growing** above it, and **unknown** with fewer
than two passes of ten samples, a recording that ended early, or a scroll it did not see finish —
none of which is a pass. The footprint is the **app's own**: AVFoundation decodes in system
processes (`mediaserverd`), so decoded frames and most of the media buffer are not in it, and a
footprint flat over 200 rows says the app keeps nothing per row, not that the device's memory
stayed flat. The feed runs inside the recording (`SCENARIO_IOS_RECORDED_LAUNCH`), and `ttff.tsv`
holds every first frame by content id.

## Plants

`--plant <name>` builds the run with `plants/<name>.patch` applied, and reverts it once the APK is on
the device, before anything is measured. It is how a measurement is shown a regression it should catch
without the regression ever being committed. The tree must be clean, and `run.json` records the plant,
its sha256 and the commit it was applied to. [`plants/README.md`](plants/README.md) is the manual and
the list.

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
| `startup` | scheduling with blocked reasons, and the `am`, `wm`, `view`, `dalvik`, `res` and `binder_driver` atrace categories plus the demo's own sections | startup ([perfettoagent#5](https://github.com/ramesh130/perfettoagent/issues/5)) |
| `jank` | scheduling with blocked reasons, and the `view`, `dalvik` and `binder_driver` atrace categories plus the demo's own sections; no `gfx` | jank ([perfettoagent#6](https://github.com/ramesh130/perfettoagent/issues/6)), beside `frametimeline` |
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
| `plant.patch` | the plant the build carried, for a run with `--plant` |

`run.json` carries the SuperPlayer commit and whether the tree was dirty, the plant if there was one,
the SuperPlayer version and artifact hashes, the Media3 version from the catalog, the demo's build type and APK hash, and the
device: serial, emulator or not, AVD, model, API level, ABI and build fingerprint. It also records the
scenario, the data sources, whether the trace hit its bound before the scenario ended, and where
playback was confirmed. `superplayer.tree_dirty` is true whenever the build was not exactly the commit,
a plant included: a planted run is dirty by construction, and its `plant` says with what, while a dirty
run with `plant: null` carried changes nobody named. Its `schema` is bumped when a field changes meaning, not when one is added. A
consumer that needs more writes a file beside it rather than a field inside it, so that runs from
different consumers stay comparable.

## Devices

`device.properties` describes the AVD: its name (`superplayer_verify_36`, the one `CLAUDE.md` names),
API, image tag and device profile. It is the one description used both to boot the AVD here and to
create it in CI. The ABI is not in it, because the host decides the ABI: arm64-v8a on an Apple-silicon
Mac, x86_64 on a Linux runner. `run.json` records it.

To create the AVD on a machine that lacks it: `devicelab/lab create-avd`. It installs the image
for this host's ABI and creates the AVD, and it is the same command CI runs.

### The television

Beside the phone, `device.properties` describes a television under `tv.` keys: `superplayer_tv_36`, API 36's
`android-tv` image on the `tv_1080p` profile. `--device tv` (or `DEVICELAB_DEVICE=tv`) selects it for every
command, so `devicelab/lab create-avd --device tv` creates it and `devicelab/lab run smoke --device tv` boots or
adopts it. Plain Android TV rather than Google TV, because API 36's Google TV images are published for 16 KB
pages only.

The kind of a device is read rather than assumed: a device declaring `android.software.leanback` in
`pm list features` is a television, and anything else a phone. So a phone emulator and a television emulator
can run side by side, and each run adopts its own and passes over the other. With none of the kind attached, a
run boots that kind's AVD beside whatever is. Two of the same kind still fail and ask for `--serial`, and a
device named with `--serial` that is the other kind fails the run rather than measuring a screen it did not ask
for. `run.json` records the kind as `device.kind`, an added field, so `schema` stays 1.

On a television the demo opens its TV screen rather than the phone's player screen, and that screen publishes a
media session of its own, which is what the readiness probe waits on, so `smoke` needs nothing of its own for it.
A scenario that opens a phone screen by launch argument, as the leak hunt opens the feed, gets that screen on a
television too. None of this has been booted yet: the first run on the television is #274's, and that is where
these claims are checked.

CI creates and boots only the phone. Its `android-emulator` action runs `create-avd` and `device` with no
`--device`, which is the phone.

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

Its first consumer is [`leak-hunt.yml`](../.github/workflows/leak-hunt.yml), which raises the job's
bound through the `timeout-minutes` input. It was nightly, and now runs only when started by hand once a
phase is complete, to stay inside the GitHub Actions minutes budget. Neither has been run on GitHub yet,
so that first run is the workflow's first real test. Two things are worth watching on that run: that the emulator started in one
step is still running in the next, and that boot fits in the job's timeout on a KVM runner.

## Limits

- `moq-smoke` needs an `arm64-v8a` device, because that is the only ABI `libmoq_ffi.so` is built
  for (`third-party/moq/README.md`). On an Intel host no emulator can run it. It also needs egress
  to the relay's UDP port, which an emulator's user-mode NAT provides and a restricted network may
  not.
- The harness has been run end to end on the API 36 phone emulator only. The television is described, and its
  selection is in the self-test, but no run has booted it yet. A physical device takes the
  adoption path: it is never booted or restarted. The emulator-specific parts, such as the AVD name
  and `ro.boot.qemu`, then simply read empty.
- On iOS it has been run on one iPhone 17 Pro Max on iOS 27.0, built by Xcode 27.1 (#435), and
  `smoke` once on an iOS 27.1 simulator, where the run is whole but the recording is not: xctrace says
  "Activity monitoring service not available on this device", so a simulator run has no footprint,
  and `ios/hunt-leaks` stays the simulator's memory measurement. An iOS
  run needs Xcode, `python3` (which Xcode's command-line tools ship) for the JSON and XML it reads, and
  a phone that is unlocked for its whole length.
- One device per run. Devices of the other kind are passed over, as is one that answers but never
  reports its features, or one that stopped answering and is not the harness's AVD. When several of
  the kind asked for are attached, the run fails and asks for `--serial` rather than
  guessing.
- It is written for bash 3.2, because that is what macOS ships, and needs `perl`, `unzip`,
  `shasum` or `sha256sum`, and `git`.
- It analyses nothing itself. It fetches the trace processor and runs queries, but the queries
  belong to the consumers: heap queries, frame percentiles and benchmark tables, each with its own
  scenario and report. The leak hunt's are in `leak/sql/`.
