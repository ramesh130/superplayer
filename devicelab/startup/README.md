# The startup scenario

Cold-starts the demo twenty times in one Perfetto trace that holds what an app startup is read from:
the platform's launch events, the main thread's scheduling, and every frame up to the first. It was
built to capture ground truth for [perfettoagent](https://github.com/ramesh130/perfettoagent)'s startup
metrics ([perfettoagent#5](https://github.com/ramesh130/perfettoagent/issues/5)), which read the trace with Perfetto's `android.startup.startups` and
`android.startup.time_to_display` modules. Nothing here depends on that project.

```bash
devicelab/lab run startup                                   # twenty cold starts, one trace
devicelab/lab run startup --plant startup-main-thread-io    # the same, with ../plants/'s regression built in
STARTUP_LAUNCHES=40 devicelab/lab run startup               # more launches (knobs below)
```

## What a launch is

Each of the twenty:

1. `am force-stop` the demo. Its process, its playback service and its session go with it.
2. **Drop the page cache**, where the device has root (an emulator's userdebug image does; a production
   device does not, and the report then says so). Macrobenchmark does the same for a cold start
   whenever it can: without it the APK, the dex files and anything the app reads at startup are still
   in memory from the launch before, and a read that should be I/O is a copy.
3. `am start -W`, and refuse the launch unless the platform reports `LaunchState: COLD`. A launch that
   found a process still alive would be warm, and averaging it in would hide exactly the work a cold
   start does.
4. Wait for playback to advance, as the harness does for its own launch, so that every startup runs to
   its end inside the trace: the first video frame, and the `reportFullyDrawn` the demo sends then.

The harness's own launch, the first after the install, is **not** one of them. The trace starts once it
is playing (`SCENARIO_TRACE_FROM=playing`), because a first launch after an install carries one-off work,
verification and profile compilation and a planted run's cache file, that no later launch repeats. With
`--trace-from launch` it is in the trace too, and the report says the module should find one more.

## Why twenty, in one trace

**Twenty**, so that p95 by nearest rank is the 19th launch. With ten it would be the slowest, and one
launch that caught a background job would be the whole tail. The run takes about three minutes of
launches after the build, and every percentile reported is a launch that happened.

**One trace** rather than one per launch, because the stdlib module finds every startup in a trace and
numbers them, so the distribution is one query over one file. A consumer comparing a before and an
after then compares two traces, not two directories of twenty. The cost is size: about 75 MB for twenty
launches. SurfaceFlinger's `gfx` atrace category is left out because it was most of that and startup
reads nothing from it, and the scheduler events are packed (`compact_sched`). `../perfetto/sources/startup.pbtxt`
names what each remaining category is for.

## Time to full display

The platform measures a launch to its first frame by itself, which is time to *initial* display (TTID).
Time to *full* display (TTFD) needs the app to say when it is showing what it was opened for, and for a
player that is the first frame of video. The demo reports it then, from `onRenderedFirstFrame`
(`MainActivity.kt`). It is not a number to compare with TTID: it includes the network fetch of the first
segments, so it moves with the network and TTID does not.

## Reading the report

`report.md`'s startup section has two tables for the same launches:

- **What the platform said.** `am start -W`'s `TotalTime`, which is the platform's TTID for the launch,
  one row per launch, with p50, p95, the minimum and the maximum.
- **What the trace says.** One row per startup that `android_startups` found: its type, its `dur`, TTID
  and TTFD from `android_startup_time_to_display`, and the demo's `bindApplication` (`sql/startups.sql`).
  The sentence under it compares the number of startups found with the number of launches made, which
  is the first thing to check before believing the rest.

`startup/launches.tsv` and `startup/startups.csv` in the run directory are the same two tables as data,
and `startup/conditions.json` records the launch count and whether the page cache was dropped. Two runs
that differ in the second are not comparable: without the drop, a read at startup is served from memory.

## Captures

The first captures, for perfettoagent#5. See *Captures* in the pull request that added this scenario
until this section is filled in.

## Knobs

| Variable | Default | Meaning |
| --- | --- | --- |
| `STARTUP_LAUNCHES` | 20 | cold launches in the trace |
| `STARTUP_SETTLE_S` | 2 | seconds between the force-stop and the launch |
| `STARTUP_PLAYBACK_TIMEOUT_S` | 90 | the bound on each launch's playback wait |
| `TRACE_PROCESSOR` | pinned download | a `trace_processor_shell` to use instead (`../README.md`) |

## Files

| Path | What |
| --- | --- |
| `../scenarios/startup.sh` | the scenario: the launches and the report |
| `startup.sh` | the device-free half: reading `am start -W`, percentiles, the tables |
| `sql/startups.sql` | the trace processor query behind the report's second table |
| `../perfetto/sources/startup.pbtxt` | the data source the trace is recorded with, beside `frametimeline` |
| `test/selftest` | the device-free half's tests, in `check` |
