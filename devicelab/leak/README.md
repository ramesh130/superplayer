# The leak hunt

Drives the demo on a device through everything SuperPlayer does that an app cannot easily undo. It
takes Java heap dumps before and after, at the same point of the scenario, and profiles native
allocations in between. It writes a report naming what grew and, hop by hop, the fields that retain it.

```bash
./gradlew huntLeaks                    # the documented entry point
devicelab/lab run leak-hunt            # the same thing; this is what it runs
LEAK_CYCLES=5 ./gradlew huntLeaks      # more lifecycle cycles (knobs below)
```

It needs a device or an emulator. With none attached it boots the harness's AVD
(`superplayer_verify_36`, API 36), and it measures the demo's `benchmark` build: release-like, not
debuggable, and profileable by the shell (#52). A run takes about 23 minutes on the headless
emulator at the default settings, build included (the two clean six-pass runs below: 23.4 and
22.5 minutes). Output
goes to `devicelab/out/<run>/`, and the report is `report.md`, whose last section is this scenario's.
`.github/workflows/leak-hunt.yml` runs it against `main` and uploads the same directory. That workflow
runs only when started by hand, once a phase is complete, to stay inside the GitHub Actions minutes budget.

## Why it sits outside the test suite

[`docs/testing.md`](../../docs/testing.md) bars a device and the network from the test suite, and this
needs both: a real app process to dump, and real streams to play. So it is not in the suite, and
`./gradlew check` neither runs `huntLeaks` nor depends on it. `check` has to pass with no device
attached. That is the same shape as the rest of devicelab (`../README.md`), and it is not an exception
to the rule: nothing here gates a change. It is a measurement, judged, on a schedule.

Per pull request it would be the wrong cadence. A heap capture on an emulator is slow and not
perfectly stable, and a slow check that is occasionally red trains people to ignore it. If a per-PR
signal is wanted later, it should be a cheap one: an RSS delta, or LeakCanary's instrumentation check,
which is complementary rather than a replacement (#50 records why).

The part that needs neither — the heap diff, the verdict, reading `dumpsys`, and the report's
Markdown — is `test/selftest`, and *is* in `check`, through `verifyDevicelab`.

## What the scenario exercises

Two phases. Each is compared with its own baseline, because they leave the demo in different
states and a leak in one cannot be seen from the other. Each is run once untimed first, so that what
a first run builds once and keeps is in the baseline rather than counted as growth: classes loaded,
caches filled, a pool grown to its bound.

**Lifecycle**, `LEAK_CYCLES` times (default 3). Each cycle starts and ends on the player screen, in
portrait, playing HLS through the service's session:

1. Rotates to landscape and back mid-playback. Each rotation destroys the Activity and builds
   another; the service's player plays on.
2. Presses home with the session live, confirms it is still playing with no Activity, and returns.
3. Names each stream by content id through the session. The demo is relaunched with
   `CONTENT_ID`, and it hands the bare id to a `MediaController`, the boundary a car head unit or a
   watch crosses. `onResolveContent` turns the id back into a `MediaRequest`. The scenario waits for
   the resolved title to appear in the platform session's metadata, which a bare id does not carry.
4. Releases the session: presses home, pauses through the session as the notification would, and
   stops the service, whose `onDestroy` releases the `PlaybackSession` and then its player. Then it
   returns, which builds a new service, session and player.
5. Enters the feed, scrolls ten swipes, and leaves. The pool is built with the screen and must be
   released with it.

**Feed**, `LEAK_FEED_PASSES` times (default 6): the whole `LEAK_FEED_ROWS`-row feed (default 200,
the PRD's Phase 4 figure) scrolled to the end and back to the top. Each row that enters the screen
acquires a pooled player, prepares it, and registers a first-frame listener it never removes.
Recycling a player is what removes it (`FeedScreen.kt` says why the row relies on that). Each row
that leaves recycles its player. Most rows scrolled past take no player at all: they find the pool
at its bound and show their label, which is the pool's contract. The negative test measured it: a pass
sends about 23 players through `SuperPlayer.resetForReuse` on the API 36 emulator, not the 400 a
count of rows would suggest. The scroll overshoots by construction (`leak_feed_swipes`), and
a screenshot at the bottom of each pass is kept as the evidence it got there.

The two dumps of a phase are taken at the same point of it, after a settle of `LEAK_SETTLE_S`
seconds and, where the device allows, two forced GCs. With the same screen, the same content and the
same number of live players, a class with more reachable instances in the final dump than in the
baseline is growth that the workload caused.

### What it does not exercise

A leak this scenario never provokes is not a leak it proves absent. It does not exercise:

- **Tapping the notification's own controls.** Pause and play go through the session as media
  button events (`cmd media_session dispatch`), the path the notification's buttons take into the
  session. The notification's UI and pending intents are not touched.
- **Android Auto, a watch, or any controller in another process.** The resolver path is driven by a
  `MediaController` in the demo's own process: the same API and session callback, not the same
  process boundary.
- **Profile switches** (`DemoPlaybackService.usingProfile`, `PlaybackSession.setPlayer`), live
  streams, errors, and DRM.
- **Telemetry.** The demo attaches no `TelemetryCollector`, so `superplayer-telemetry` and the
  delivery queue are not in the heap at all.
- **Process death.** Deliberately. A restarted process starts with an empty heap, so the scenario
  fails a phase whose two dumps are of different processes rather than compare them.
- **Anything on a physical device**, which has been run only in principle. A production build has
  no `su`, so no GC is forced, in the demo or in the processes holding its Binder objects, and the
  report says so. Only reachable objects are counted either way, but a released session's stub may
  then still be held by another process's proxy, and read as growth.

## Reading the report

The verdict comes first: **leak** with a table of findings, or **no leak found**. Then memory at each
dump (`dumpsys meminfo`), then one section per phase:

- **Findings.** Each class whose reachable instances grew by at least the phase's threshold, and any
  Activity the heap holds beyond those the system lists as alive.
- **What retains them.** For each finding, its instances' most common shortest paths from a GC root,
  grouped by shape, so that 400 listeners leaked the same way are one path shared by 400. Each line is
  a hop: the field that points onward, named with its declaring class (the file to open), and the
  class of what it points to. `[element]` is an array slot.
- **Everything else that grew.** JDK and framework classes are shown, not judged (below).
- **Known retentions, set aside.** How many objects the entries below left out of the counts, at both
  dumps, and which classes they were.
- **Native allocations not freed over the phase.** heapprofd's estimate, with the largest callsites'
  stacks, demangled.

### What each half can say, and what it cannot

A **Java heap dump** records objects and references. It answers *what is retained, and through
which field*, and for a retention bug that field is the defect. It does not record where an object
was allocated, so "the place in code" in this report means the field holding the reference, never
the line that allocated the object. Do not go looking for an allocation stack for a Java object: none
was captured.

A **native profile** is the reverse. It records a sampled callstack for each native allocation and
nothing about what keeps it alive. Its frames name a function and a library, and a file and line
only for a library symbolized with its unstripped binary, which a device image's system libraries
are not. Its figures are estimates scaled up from samples. Native memory is reported and not judged:
decoder and graphics client buffers, allocator caches and HTTP buffers move with timing, and no
threshold for them has been measured to be stable yet.

## The verdict, and its tolerance

The verdict is **leak** when either phase has a finding:

- **Any Activity the heap holds beyond those the system lists as alive** (`dumpsys activity`). No
  tolerance: the platform states the live count independently, so one extra is one leaked.
- **A class of this project's (`com.superplayer.*`, the library's and the demo's) that grew by at
  least the phase's threshold.** In the lifecycle phase that is one instance per cycle
  (`LEAK_CYCLES`, 3). In the feed phase it is 40.
- **A Media3 class (`androidx.media3.*`) that grew by at least its own threshold.** In the lifecycle
  phase that is also one per cycle. In the feed phase it is 1200.

Everything else is shown and not judged. JDK and framework classes move with caches that are no part
of this code: interned strings, Compose's snapshot records, the connection pool. A leak that is
SuperPlayer's has to hold at least one object of a judged class, because the chain from a root to
what it retains passes through code this repository wrote or wraps.

### Why those numbers

They come from measurement. The first clean run on the API 36 emulator (`20260911T124701Z-leak-hunt`) is where they were
set:

- **The feed, this project's classes.** Three clean feed phases moved them by +1, +21 and 0 between
  baseline and final. The +1 was one more row composed at the final dump. The +21 was
  `LivePlaylistRevalidation$RevalidatingDataSource`, one per data source the live players had open at
  that moment. `SuperPlayer` was 7 and 7 each time: the pool was at its bound at both dumps, which is
  what the warm-up pass is for. The threshold, 40, is about twice the largest.

  The workload is what gives a leak room above it. A pass sends about 23 players through the pool, so
  a leak of one object per recycle grows by about 23 a pass. That is why the default is six passes,
  which put it near 140, three and a half times the threshold.

  The first version got this wrong, and the negative test is what showed it (below). It derived the
  threshold from row visits, one per ten, 80 for two passes, on the assumption that every row
  scrolled past takes a player. The planted leak grew by +46 and passed.
- **The feed, Media3's classes.** The feed's players are live at its dumps. What they have loaded
  (playlist segments, buffered allocations, open data sources) makes Media3's counts differ between
  two dumps of the same healthy feed, and the HLS playlists do it in whole units: a variant playlist
  is 202 `HlsMediaPlaylist$Segment` objects. Across every run of the final procedure the largest
  Media3 growth in the feed was +202, next to nothing, +404, +215, +202 and +606. The +606 is three
  playlists, in the negative-test run. Its paths all ran through live players' internals: their
  analytics event times' current timeline, and their HLS trackers' `playlistSnapshot`. None passed
  through the planted leak. The largest of anything else was +229 (`SampleDataQueue$AllocationNode`).
  The threshold, 1200, is about twice the largest. It was 800 until that run: 800 held the +606, but
  one more playlist would have put a healthy feed over it.

  That sets what this half of the verdict can and cannot see. A Media3 leak of a player's state per
  recycle is dozens of objects per recycle and far above it. A leak of a single Media3 object per
  recycle is not: about 140 over six passes. When that object is retained through SuperPlayer's own
  code, as it is when `resetForReuse` forgets a listener, it holds one of SuperPlayer's objects too.
  This project's classes are held to 40, so it is caught there. That is exactly the negative test. A
  leak of one Media3 object per recycle that holds nothing of SuperPlayer's is a Media3 defect this
  phase cannot separate from live players' state. This scenario does not claim to find one.
- **The lifecycle phase, both.** Two things made two dumps of the same healthy player disagree, and
  both are now settled before a lifecycle dump rather than tolerated:
  - *The player was playing.* In the first clean run, Media3's classes differed by up to +101
    (`HlsMediaPlaylist$Segment`). Every object of it was the live player's HLS state: which variant
    playlists its tracker held, reached through its `ExoPlayerImplInternal` and the session's cached
    timeline. So the player is stopped through its session for the dump and played again after.
    `stop()` releases everything the media source loaded, and it keeps the playlist.
  - *Released sessions' stubs outlived them.* With the player stopped, the next run still found whole
    extra manifests at the final dump: `HlsManifest` 2 → 4, and exactly one more 202-segment playlist.
    Every live object was 1 and 1: `MediaSession`, `PlaybackSession`, `SuperPlayer`, `ExoPlayerImpl`.
    Only `MediaSessionStub` had grown, 2 → 4, each extra one holding the last timeline of a session
    already released. A stub is a Binder object. It stays rooted in the demo for as long as another
    process holds a proxy to it, and an ART process drops a proxy only when it collects. The kernel's
    binder log for the demo named those processes: system_server, SystemUI, Bluetooth, the keyboard,
    and more. Three releases left two stubs, because somewhere in between one of those processes
    happened to collect. That is exactly the non-determinism a verdict cannot rest on. So the settle
    now reads the binder log and collects those processes first. It signals only the zygote's
    children, which are ART; to a native daemon, SIGUSR1 means terminate. Then it collects the demo.
    The report names the processes it collected.

  With both, the next run's lifecycle dumps agreed exactly: no judged class moved at all, not by one,
  and `MediaSessionStub` was 1 and 1. The threshold of 3 is margin, not a measured noise floor.

### Reproducibility

Same scenario, same verdict. Every run of the final procedure on the API 36 emulator, each judged by
the thresholds above. The runs measured this change's working tree before it was committed, which is
why each one's `report.md` records the tree as dirty:

| run | what | feed passes | verdict | largest judged growth: this project / Media3 |
| --- | --- | --- | --- | --- |
| `20260911T141037Z` | clean | 2 | no leak found | lifecycle 0 / 0; feed 0 / +404 |
| `20260911T144545Z` | `resetForReuse` keeps its listeners | 6 | **leak**: the two planted classes, +137 each, nothing else | lifecycle 0 / 0; feed +137 / +606 |
| `20260911T151106Z` | clean | 6 | no leak found | lifecycle 0 / +1; feed 0 / +215 |
| `20260911T153458Z` | clean | 6 | no leak found | lifecycle 0 / +1; feed 0 / +202 |

The tolerance is the thresholds, and the reason for each is under *Why those numbers*. Runs of the same
healthy build agree on the verdict because what differs between them is either settled before a dump
(the stopped player, the released sessions' stubs) or is live state held well under its threshold (the
feed's players). A verdict that changed between two such runs would be a threshold that no longer
holds its noise. That is worth reporting, and it is not a flake to rerun.

Runs made while the procedure was still being fixed are not in this table, because they measured a
different procedure. Their numbers are where the thresholds came from, and they are cited above:
`20260911T124701Z` (first clean run), `20260911T135526Z` (released sessions' stubs), and
`20260911T142630Z` (the negative test that passed).

## Known retentions

Some retention is real, provoked by this scenario, and not this code's to fix. Left in the count it
would turn the nightly red for good, and a check that is always red gets ignored as surely as one that
is never red. [`known-retentions.tsv`](known-retentions.tsv) lists such references, each with the
reason and where it is tracked. The count treats them as it treats weak references: an object held
*only* through one of them is left out of both dumps. An object held through any other path still
counts, so an entry hides only what it names. This is the model LeakCanary uses for library leaks,
applied to a count rather than to a single path.

Nothing is left out silently. Every phase's report says how many objects the entries set aside at the
baseline and at the final dump, and which classes they were. An entry that starts hiding more than it
did shows up as that number growing.

An entry has to name the exact field. The first entry was written from a retaining path, and it
covered one of the two references the platform stub holds. Checked against the dump, it set aside the
platform's bookkeeping and left every Media3 object still counted. That was the check doing its job.

**The one entry today** was found by this leak hunt's first run. From API 35, every Media3
`MediaCodecAudioRenderer` creates a platform `LoudnessCodecController` for each audio session. Media3
closes it when the renderer is released (`LoudnessCodecController.release`, from
`MediaCodecAudioRenderer.onRelease`, in Media3 1.11.0). Even so, on the API 36 emulator the platform's
`LoudnessCodecDispatcher` binder stub still holds the listener of every one, and through it Media3's
controller. That was 28 of them with at most three players alive, and about seven more per lifecycle
cycle. It is rooted in the platform and passes through nothing SuperPlayer holds. It is not yet
filed upstream.

## The negative test

A leak checker nobody has seen fail cannot be told apart from one that always passes. So this one
was made to fail on purpose, twice. The first attempt showed the checker could see the leak but judge
it wrongly.

**The plant.** The four lines of `SuperPlayer.resetForReuse` that remove every listener registered
on a pooled player were commented out:

```kotlin
// synchronized(wrappedListeners) {
//     wrappedListeners.values.forEach(delegate::removeListener)
//     wrappedListeners.clear()
// }
```

The run was otherwise unchanged. The patch was applied, the run built and installed the demo against
the library with it, and the patch was reverted as soon as the APK was on the device. It was never
committed. The run's `report.md` records the tree as dirty, which is the honest record of what it
measured.

**The first attempt: seen, and passed.** The feed's two measured passes grew the row's listener
(`FeedScreenKt$FeedRow$1$1$1$1`) from 31 to 77, and SuperPlayer's wrapper for it from 43 to 89. That
is +46 each. The threshold then was 80, derived on the assumption that every row scrolled past takes a
player. The verdict was **no leak found**. The retaining path was already right:

```text
… -> com.superplayer.core.SuperPlayer
  .com.superplayer.core.SuperPlayer.wrappedListeners -> java.util.IdentityHashMap
  .java.util.IdentityHashMap.table -> java.lang.Object[]
  [element] -> com.superplayer.demo.FeedScreenKt$FeedRow$1$1$1$1
```

The count was wrong. Each acquisition registers exactly one listener, so +46 over two passes is about
23 players through the pool per pass, not 400. That is what moved the feed's thresholds from a formula
over the workload to measured noise (40), and the default from two passes to six (above).

**The second attempt: caught.** With the feed's thresholds set from measured noise and six passes, the same plant was run again
(`20260911T144545Z-leak-hunt`). It exited 3, verdict **leak**, with exactly two findings, both in the feed and
both the plant:

| class | baseline | final | growth |
| --- | --- | --- | --- |
| `com.superplayer.demo.FeedScreenKt$FeedRow$1$1$1$1` (the row's listener) | 15 | 152 | +137 |
| `com.superplayer.core.SuperPlayerKt$$ExternalSyntheticLambda0` (SuperPlayer's wrapper for it) | 27 | 164 | +137 |

That is about 23 a pass over six, as predicted, and 3.4 times the threshold of 40. Nothing else was
flagged: no lifecycle finding, and no Media3 class in either phase. Each listener's retaining path ends
at the field whose clearing was commented out:

```text
…
  .com.superplayer.demo.FeedScreenKt$$ExternalSyntheticLambda4.f$0 -> com.superplayer.core.SuperPlayer
  .com.superplayer.core.SuperPlayer.wrappedListeners -> java.util.IdentityHashMap
  .java.util.IdentityHashMap.table -> java.lang.Object[]
  [element] -> com.superplayer.demo.FeedScreenKt$FeedRow$1$1$1$1
```

`SuperPlayer.wrappedListeners` is `SuperPlayer.kt`'s field and `resetForReuse` is the function that
clears it. That is the file a reader opens, found without a GUI. It names the field that holds the
reference, not the line that allocated the listener. That is what a Java heap graph can say, and for a
retention bug it is the defect.

## Knobs

| Variable | Default | Meaning |
| --- | --- | --- |
| `LEAK_CYCLES` | 3 | lifecycle cycles after the warm-up, and the lifecycle threshold |
| `LEAK_FEED_ROWS` | 200 | rows in the feed, passed to the demo so the two cannot disagree |
| `LEAK_FEED_PASSES` | 6 | down-and-back passes after the warm-up; the room a leak has above the feed's threshold |
| `LEAK_SETTLE_S` | 5 | seconds of quiet before the GCs and each dump |
| `LEAK_HEAP_DUMP_MS` | 20000 | bound on each heap-dump trace; a dump that did not finish fails the run |
| `LEAK_LIFECYCLE_THRESHOLD`, `LEAK_FEED_THRESHOLD` | `LEAK_CYCLES`, 40 | override this project's thresholds |
| `LEAK_LIFECYCLE_MEDIA3_THRESHOLD`, `LEAK_FEED_MEDIA3_THRESHOLD` | `LEAK_CYCLES`, 1200 | override Media3's |
| `TRACE_PROCESSOR` | pinned download | a `trace_processor_shell` to use instead (`../README.md`) |

## Files

| Path | What |
| --- | --- |
| `../scenarios/leak-hunt.sh` | the scenario: every step above, and the capture |
| `leak.sh` | the analysis: diff, verdict, report |
| `sql/` | the trace processor queries, each saying what it can and cannot conclude |
| `known-retentions.tsv` | the references counted as weak, each with its reason (above) |
| `test/selftest` | the device-free half, in `check` |

## For Phase 9

`superplayer-diagnostics` owns Perfetto instrumentation on the hot path (`PRD.md` §3.6). None of
that is here, but the plumbing is shared rather than copied. A trace of any data sources over a span
a scenario chooses is `trace_begin` and `trace_end`, a moment is `capture_trace`, and querying one
with a pinned trace processor is `trace_processor_query`. All of it is in `../lib/`, and none of it
is specific to leaks.
