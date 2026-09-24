# The jank scenario

Scrolls the demo's feed and taps it, in one Perfetto trace that holds what UI jank is read from: every
frame's expected and actual deadline, what the app's main thread and RenderThread did inside each
frame, the main thread's scheduling, and the garbage collector. It was built to capture ground truth for
[perfettoagent](https://github.com/ramesh130/perfettoagent)'s frame, main-thread and GC metrics
([perfettoagent#6](https://github.com/ramesh130/perfettoagent/issues/6)). Nothing here depends on that
project.

It is not superPlayer's own UI-jank measurement, #51, which is Macrobenchmark's `FrameTimingMetric` on a
schedule. It scrolls the same feed with video playing and reads the same `frametimeline` source, and one
thing it found bears on #51's question (*Reading the report*, below).

```bash
devicelab/lab run jank                                   # the feed, scrolled and tapped, one trace
devicelab/lab run jank --plant feed-tap-sleep            # the same, with one of ../plants/'s regressions built in
JANK_ROUNDS=5 devicelab/lab run jank                     # more of it (knobs below)
```

## What a run does

Before the trace, in `scenario_setup`:

1. Opens the feed (`open_feed`, 200 rows) as the leak hunt does: the service's player paused and
   confirmed paused through its media session, then the demo relaunched on the feed by launch argument
   rather than by a tap on the screen picker, for the reason `../README.md` gives under *Scenarios*. The
   service's player is not the feed's, and a second decoder behind the feed would be work in every frame
   that the feed did not ask for.
2. Runs the whole plan below once, untraced, and swipes back to the top, refusing to go on unless the
   row being watched is the first. The first pass through the feed JIT-compiles the scroll and
   composition paths, builds the pool's players and fills the cache; none of that happens again, so
   measuring it would measure the order of runs rather than the build.

Then the trace starts, from the top of the feed, and the plan runs again:

- **Three rounds**, each of **eight swipes** up the screen (300 ms, a flick that flings, one a second),
  then a rest, a **tap** on the middle of the feed, a rest, a second **tap**, and a rest. The rests are 2 s.
- So 24 swipes and 6 taps in a trace of about 55 seconds. A round scrolls about twenty rows, and each row that
  becomes the watched one acquires, prepares and starts a player: the work a feed does as it scrolls.

### Why the taps are in the same trace

A case for the metrics this feeds is one trace against another, so every regression the scenario is
meant to show has to be in the one trace a run makes. The taps are separated from the scroll by the rests,
so their frames are not a fling's, and the two measurements do not compete for the same frames: a
tap-handler regression shows as the main thread asleep inside the input it was handling, while the
scroll's frames are what the frame and GC regressions move.

**Two taps a round**, because the tap pauses the row being watched and the second plays it again: every
round leaves the feed as the round before found it. **Six taps**, because each one that runs a slow handler
is a separate occurrence of it, and six makes one that the device happened to delay stand out as one of
six rather than as the whole finding.

### What the tap lands on

The feed takes a tap anywhere on the list (`FeedScreen.kt`, *A tap pauses the row being watched*), which is
the demo's own behaviour, not the scenario's: tapping a short-form feed pauses what is playing. It is a tap
gesture on the list with the same action offered to accessibility services, rather than `clickable`, which
would have merged every row into one accessibility node. That is
what makes the middle of the screen a target on any phone, like the swipes' fractions of the screen,
rather than a coordinate that finds a button on one device and misses it on the next.

## What the trace records, and does not

`../perfetto/sources/jank.pbtxt` says what each category is for: `frametimeline` for the deadlines, `view`
for `Choreographer#doFrame` and `DrawFrame`, `dalvik` for GC, `binder_driver`, the demo's own sections,
and the scheduler. Not SurfaceFlinger's `gfx`, the way the startup scenario leaves it out: it is most of
a trace's slices and nothing here reads it. A clean run's trace is 29–32 MB (the captures below); a run
with `feed-row-remeasure` is about 175 MB, because its app draws three times as many frames.

Perfetto's `android.frames.timeline` module still finds every frame without `gfx`. In `20260924T033342Z`
its `android_frames` has 701 frames of the demo: the 676 rows of the window's layer in
`actual_frame_timeline_slice`, and 25 whose `DrawFrame` never reached the timeline. Its `dur` agrees with
the window layer's, to a p95 of 81.2 ms against 81.5.

## Reading the report

Five tables, each a query in `sql/` whose header says what it can and cannot conclude:

| Query | Says |
| --- | --- |
| `frames.sql` | the window's frames from the timeline: how many, how many janky, and p50/p95/p99 of their `dur` |
| `frame_work.sql` | the app's side of each frame: `Choreographer#doFrame` on the main thread, `DrawFrame` on the RenderThread |
| `main_thread.sql` | the main thread's time in each scheduler state, and how long it slept in the middle of a slice |
| `blocked.sql` | that sleep, by the innermost slice it began in, the ten with the most |
| `gc.sql` | the demo's collections by kind: how many, how long |

Things that are true of every run on the API 36 emulator, and that a reader of these tables needs first:

- **Nearly every frame is janky by the timeline's own verdict** — 99% — and almost all of it is `Buffer
  Stuffing` and `Prediction Error`: the emulator's display pipeline, the same for every build. So
  `jank_pct` carries no signal here, and `app_jank_pct`, the frames whose jank type names `App Deadline
  Missed`, is the one that moves with the app.
- **The demo's process has more than one layer.** Each row playing video has a `SurfaceView`, and its
  layers get timeline rows for the same vsync ids as the window's frames. `frames.sql` keeps the window's
  layer only. A query that joins frames to the timeline by vsync id alone picks one of the layers' rows,
  and the stdlib's `android_frames.actual_frame_timeline_id` does: in `20260924T033342Z`, 563 of its 701
  frames point at a `SurfaceView` layer's row, and app jank read through that join is 12.7% where the
  window's own rows say 54.7%.
- **The main thread sleeps in the middle of a frame in every run.** `postAndWait` is the main thread
  waiting for the RenderThread to take the frame: 4.8–6.7 s over a clean capture. So "asleep inside a
  slice" alone is not a regression; *where* it slept is what `blocked.sql` is for.
- **Recycling a feed row's player blocks the main thread**, inside `Compose:onForgotten`, where a row that
  stops being watched hands its player back to the pool: 2.3–3.3 s over a clean capture, up to 228 ms at a
  time. It is the clean build's, not a plant's. It bears on #51's hypothesis, which asked whether the
  pool's acquire on the main thread janks a fling: the release does, and this scenario does not measure
  the acquire apart from it.

perfettoagent's metrics are its own to define, but these are the columns each is checked against by hand:
`jank_frames_pct` against `app_jank_pct` (and `jank_pct`, which is saturated here), `frame_p95_ms` and
`frame_p99_ms` against `frames.sql`'s `p95_ms` and `p99_ms`, `main_thread_blocked_ms` against
`blocked.sql`'s rows, and `gc_time_ms` against `gc.sql`'s `total_ms` on `HeapTaskDaemon`.

`jank/*.csv` in the run directory are the same tables as data, and `jank/plan.txt` is the plan that ran.

## Captures

The first captures, made for [perfettoagent#6](https://github.com/ramesh130/perfettoagent/issues/6) on the
API 36 phone emulator (`superplayer_verify_36`, headless), one after another over an hour, in three cycles
of a clean run (B), each plant (S, G, R) and a second clean run (C), so that anything drifting over the
hour falls on all of them. Every run is SuperPlayer `57e3144` with a clean tree and the knobs at their
defaults; the planted ones add `feed-tap-sleep` (patch sha256 `27700ab54513…`), `feed-grain-allocations`
(`ba6902ce089a…`) or `feed-row-remeasure` (`1a29bb777a35…`) to it. The six clean runs are the baseline
of all three plants. Frame times in ms, from the window's frames (`frames.sql`): app jank is the share
whose jank type names `App Deadline Missed`, any jank the share with any jank type. `doFrame` p95 is the
main thread's `Choreographer#doFrame` (`frame_work.sql`). Asleep in `onTouch` is the main thread's sleep
that began inside `AndroidOwner:onTouch`, in ms, with the sleeps over 16 ms in brackets (`blocked.sql`).
GCs and GC time are the collections on `HeapTaskDaemon` (`gc.sql`).

| run | build | frames | app jank | any jank | p50 | p95 | p99 | `doFrame` p95 | asleep in `onTouch` | GCs | GC time |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `20260924T033342Z` (B1) | clean | 676 | 54.7% | 94.4% | 40.4 | 81.5 | 154.5 | 38.0 | 0 | 7 | 259 |
| `20260924T033648Z` (S1) | `feed-tap-sleep` | 733 | 45.6% | 97.4% | 34.4 | 72.6 | 127.3 | 36.3 | 724.7 (6) | 8 | 436 |
| `20260924T033945Z` (G1) | `feed-grain-allocations` | 427 | 66.5% | 96.7% | 40.7 | 87.7 | 138.0 | 60.3 | 0 | 149 | 10356 |
| `20260924T034233Z` (R1) | `feed-row-remeasure` | 1643 | 80.3% | 100.0% | 62.9 | 92.7 | 124.1 | 69.3 | 0 | 203 | 13632 |
| `20260924T034924Z` (C1) | clean | 730 | 42.2% | 95.9% | 35.5 | 72.3 | 123.5 | 36.6 | 0 | 6 | 172 |
| `20260924T035208Z` (B2) | clean | 746 | 45.3% | 96.9% | 37.1 | 76.5 | 148.1 | 39.4 | 0 | 8 | 302 |
| `20260924T035447Z` (S2) | `feed-tap-sleep` | 710 | 37.5% | 97.0% | 33.7 | 65.2 | 122.4 | 31.9 | 726.3 (6) | 6 | 186 |
| `20260924T035727Z` (G2) | `feed-grain-allocations` | 444 | 65.3% | 96.4% | 40.7 | 89.1 | 129.9 | 67.0 | 0 | 142 | 11044 |
| `20260924T040009Z` (R2) | `feed-row-remeasure` | 1729 | 73.5% | 99.9% | 62.5 | 88.5 | 124.2 | 65.5 | 0 | 196 | 12153 |
| `20260924T040632Z` (C2) | clean | 834 | 44.5% | 96.8% | 39.3 | 73.3 | 136.0 | 37.0 | 0 | 8 | 256 |
| `20260924T040915Z` (B3) | clean | 824 | 37.1% | 97.2% | 33.5 | 67.8 | 115.8 | 32.5 | 2.6 (0) | 8 | 428 |
| `20260924T041151Z` (S3) | `feed-tap-sleep` | 789 | 42.0% | 97.5% | 33.0 | 71.8 | 130.3 | 34.3 | 724.4 (6) | 8 | 406 |
| `20260924T041431Z` (G3) | `feed-grain-allocations` | 406 | 64.3% | 95.8% | 40.1 | 86.0 | 120.4 | 61.2 | 0 | 134 | 9484 |
| `20260924T041712Z` (R3) | `feed-row-remeasure` | 1796 | 73.0% | 100.0% | 62.1 | 84.2 | 115.5 | 62.6 | 0 | 196 | 11802 |
| `20260924T042341Z` (C3) | clean | 760 | 43.0% | 97.8% | 38.7 | 73.3 | 139.9 | 35.2 | 0 | 8 | 377 |

Each regression, by hand, against the six clean runs:

- **`feed-tap-sleep`: the main thread asleep inside the tap.** Every planted run has exactly six sleeps
  inside `AndroidOwner:onTouch`, one per tap, each 120–123 ms, 724–726 ms in all. The clean runs have none
  over 16 ms there: 0 ms in five, and one short sleep of 2.6 ms in B3. Nothing else moves; its frames and
  GC sit inside the clean spread.
- **`feed-grain-allocations`: GC, and the frames that wait for it.** 134–149 collections taking 9.5–11.0 s,
  against 6–8 taking 0.17–0.43 s. The main thread waited for a collection in every planted run and in no
  clean one (`GC: Wait For Completion Alloc (main thread)`, 52–98 ms), and its `doFrame` p95 is 60–67 ms
  against 32–39. App jank is 64–67% against 37–55%: separated, but by 10 points, less than the widest clean
  pair's 12.5 (B1 and C1), so it is the weaker of its two signals.
- **`feed-row-remeasure`: every frame slower.** p50 62–63 ms against 33–40, `doFrame` p95 63–69 against
  32–39, app jank 73–80% against 37–55%, and two and a half times the frames, since the rows never stop
  moving. p95 is 84–93 ms against 68–82: every planted run above every clean one, but the smallest gap,
  R3 against B1, is 2.7 ms, inside the clean runs' 13.7 ms spread. The tail does not move with the
  middle (p99 115–124 against 116–155), because a feed that draws every frame spreads its work evenly.
  The text layouts also allocate, so GC rises with it (about 200 collections).

**The clean pairs** are B1 with C1, B2 with C2, and B3 with C3, each two separate runs of the same build.
Within a pair, app jank differs by 12.5, 0.8 and 5.9 points, p95 by 9.2, 3.2 and 5.5 ms, and GC time by
87, 46 and 51 ms; over all six clean runs p95 spans 67.8–81.5 ms and app jank 37.1–54.7%. So a
difference of ten points of app jank or ten milliseconds of p95 between two clean runs is noise here.

The runs, traces included, are 29–32 MB each (about 175 MB with `feed-row-remeasure`) and are kept outside
the repository, in `devicelab/out/` of the checkout they were made from; the plants' sizing pilots are
beside them in `devicelab/out/jank-pilots/`.

## Knobs

| Variable | Default | Meaning |
| --- | --- | --- |
| `JANK_FEED_ROWS` | 200 | the feed's length |
| `JANK_ROUNDS` | 3 | rounds of swipes and taps in the trace |
| `JANK_SWIPES` | 8 | swipes a round |
| `JANK_SWIPE_MS` | 300 | a swipe's duration; lower flings harder |
| `JANK_SWIPE_GAP_S` | 1 | seconds between swipes |
| `JANK_REST_S` | 2 | seconds of rest around each tap |
| `TRACE_PROCESSOR` | pinned download | a `trace_processor_shell` to use instead (`../README.md`) |

## Files

| Path | What |
| --- | --- |
| `../scenarios/jank.sh` | the scenario: the setup, the plan carried out, the report |
| `jank.sh` | the device-free half: the plan |
| `sql/*.sql` | the trace processor queries behind the report |
| `../perfetto/sources/jank.pbtxt` | the data source the trace is recorded with, beside `frametimeline` |
| `test/selftest` | the device-free half's tests, in `check` |
