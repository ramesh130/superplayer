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

The first captures, made for [perfettoagent#5](https://github.com/ramesh130/perfettoagent/issues/5) on the
API 36 phone emulator (`superplayer_verify_36`, headless), one after another, clean and planted
interleaved so that anything drifting over the hour falls on both. Every run is SuperPlayer `7b43b5f`
with a clean tree; the planted ones add `startup-main-thread-io` (patch sha256 `1ccfc7b78f9c…`) to it,
and every one of them dropped the page cache before each launch. In every run the stdlib module found
exactly the 20 cold launches the scenario made. Times in ms, from the trace: TTID from
`android_startup_time_to_display`; `bindApplication` and the main thread's time inside it in
uninterruptible I/O wait (`thread_state` D with `io_wait`), each as p50 over the run's 20 startups.

| run | build | TTID p50 | TTID p95 | TTID range | `bindApplication` p50 | its I/O wait p50 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `20260924T011856Z` (B1) | clean | 350 | 456 | 271–1079 | 98 | 21 |
| `20260924T012233Z` (R1) | planted | 665 | 1061 | 497–1136 | 465 | 144 |
| `20260924T012633Z` (C1) | clean | 346 | 460 | 272–516 | 92 | 33 |
| `20260924T013010Z` (B2) | clean | 426 | 717 | 311–784 | 106 | 28 |
| `20260924T013400Z` (R2) | planted | 703 | 880 | 603–1045 | 497 | 162 |
| `20260924T013758Z` (C2) | clean | 387 | 583 | 293–630 | 101 | 30 |
| `20260924T014149Z` (B3) | clean | 451 | 813 | 291–900 | 98 | 35 |
| `20260924T014554Z` (R3) | planted | 738 | 998 | 631–1148 | 517 | 177 |

- **The regression, by hand.** Planted, `bindApplication` is about five times as long at p50 (465–517 ms
  against 92–106 ms), and the main thread spends about five times as long in it waiting on I/O (144–177
  ms against 21–35 ms). The rest of the growth is the main thread running: copying and checksumming what
  it read. TTID's p50 moves by 214–392 ms, depending on which clean run it is set against.
- **The clean pairs** are B1 with C1 (p50 350 and 346, 4 ms apart) and B2 with C2 (426 and 387, 39 ms).
  B3 is a third clean run.
- **The clean runs drift.** p50 rose from about 350 to 451 ms over the hour and the tail grew more than
  the middle (p95 456 to 813), with a clean launch as slow as 1079 ms in B1. So a clean pair can differ
  by tens of milliseconds at p50 and by hundreds at p95, and a comparison that trusts one run's tail is
  reading noise.
- **What the module and the platform agree on.** `android_startups`' `dur` matched `am start -W`'s
  TotalTime launch for launch, within 1 ms (mean absolute difference 0.5 ms, B1 and R1). TTID ends a
  little earlier than `dur`, at the first `DrawFrame` rather than at the launch being reported finished:
  by 3–6 ms on average in each run, and never more than 26 ms. TTFD was found for all 160 startups.

The runs, traces included, are about 70 MB each and are kept outside the repository, in
`devicelab/out/` of the checkout they were made from.

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
