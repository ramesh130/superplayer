# Plants

A plant is a known regression kept as a patch: applied to the source for one run, and never committed.
A measurement nobody has seen fail cannot be told apart from one that always passes, so each consumer
that measures something keeps the regression it should catch here, and a capture of it can be made
again from this directory and a commit.

```bash
devicelab/lab run startup --plant startup-main-thread-io   # build with the plant, measure, revert
devicelab/lab list                                         # the plants there are, under "Plants"
```

## What `--plant` does

`lab run <scenario> --plant <name>` applies `plants/<name>.patch` to the working tree, publishes and
builds, installs, and reverts the patch as soon as the APK is on the device. Nothing is measured until
the tree is clean again. That is the leak hunt's negative test ([`../leak/README.md`](../leak/README.md),
*The negative test*) made mechanical: the first plant there was commented out by hand, and a plant that
is typed by hand is a plant nobody can repeat exactly.

- **A clean tree only.** The run records the commit and the patch. Uncommitted changes as well would be
  a third thing it measured and did not name, so a dirty tree is refused before a device is found.
- **Checked first.** `git apply --check` runs before the boot and the build, so a patch that no longer
  applies costs a second. `devicelab/test/selftest`, which `./gradlew check` runs, checks every plant
  here against `HEAD` for the same reason: a patch against the demo's sources rots as the demo changes,
  and CI is a better place to find that than a device run.
- **Reverted however the run ends.** After the install on success, and by the run's exit trap when the
  build or the install fails, with the tree checked clean afterwards. A run that cannot take its plant
  out stops before it measures, and says what is left.
- **Recorded.** `run.json` carries `plant`: the name, the patch's sha256, and the commit it was applied
  to (`null` for a run with none). The patch itself is copied into the run as `plant.patch`, so the run
  still describes itself after this directory changes. `superplayer.tree_dirty` is `true` for a planted
  run, because the tree that was built was, and `report.md` names the plant where it would otherwise say
  "plus uncommitted changes".

A plant is an option of the harness, not of a scenario, because what it changes is the build, and the
build is the harness's: the apply has to come before the publish and the revert straight after the
install, in the middle of `lab run`. A separate driver would have to run that sequence a second time.
So any scenario takes any plant, and adding one is adding a patch file and a row below.

## The plants

| Plant | Scenario | Change | Expected to move |
| --- | --- | --- | --- |
| `startup-main-thread-io` | `startup` | adds an `Application` subclass whose `onCreate` reads and checksums a 192 MiB file on the main thread | time to initial display (TTID); `bindApplication` |
| `feed-tap-sleep` | `jank` | the feed's tap handler holds the main thread for 120 ms | main thread asleep inside the tap (`AndroidOwner:onTouch`) |
| `feed-grain-allocations` | `jank` | a grain over the scrolling feed, averaged from a million boxed floats allocated in every scrolled frame's draw | GC count and time; app-deadline jank; `Choreographer#doFrame` |
| `feed-row-remeasure` | `jank` | every row's padding breathes, so each visible row is re-measured every frame, and a row's measure fits its title in fine steps | frame p50 and p95; `Choreographer#doFrame` |

### `startup-main-thread-io`

A synchronous file read in `Application.onCreate`. The demo has no `Application` subclass, so the patch
adds one (`DemoApplication`) and names it in the manifest, rather than hosting the read somewhere a real
app would not put it. The read is real I/O on the main thread: the startup scenario drops the page cache
before every launch, so the file comes from storage each time. Inside `bindApplication` the trace shows
the main thread in uninterruptible sleep waiting on I/O (`thread_state` D, `io_wait` 1) about five times
as long as in a clean launch, and running for about three and a half times as long, copying and
checksumming what it read. The file is written by the first planted launch on a device, which is the
harness's own and outside the trace, and kept after that: `adb install -r` keeps an app's data, so a
later planted run finds it already there, and a clean build never reads it. `adb shell pm clear
com.superplayer.demo` removes its 192 MiB.

**Why 192 MiB.** Large for an app, and chosen from measurement rather than realism, because the
emulator's storage is the host's and fast: with the page cache dropped it read cold at 190–720 MB/s
(`dd`, 64 MiB, three reads), and from memory at 2 GB/s. The size was set by three sizing runs, each of
twenty cold launches (`am start -W` TotalTime), made while the scenario was being written and so from
trees that are not commits on `main`:

| run | build | p50 | p95 | range | `bindApplication` |
| --- | --- | ---: | ---: | ---: | ---: |
| `20260924T005812Z` | clean | 400 ms | 510 ms | 321–516 ms | 73–152 ms |
| `20260924T010253Z` | 64 MiB | 493 ms | 550 ms | 438–605 ms | 203–309 ms |
| `20260924T010729Z` | 192 MiB | 637 ms | 744 ms | 580–778 ms | 396–479 ms |

64 MiB moved p50 by about 90 ms and the runs overlapped. 192 MiB put every launch of the regressed run
above every launch of the clean one. The captures then made with the scenario as committed are the
stricter test, because they have five clean runs rather than one: there, clean runs' p50 ranged over
346–451 ms and regressed runs' over 665–738 ms, so the smallest gap between any regressed and any clean
p50 is 214 ms, twice the whole spread of clean p50s and five times the larger clean-pair difference.
Single launches do overlap across runs — one clean launch took 1079 ms — which is why the separation is
argued on p50, not on the extremes. [`../startup/README.md`](../startup/README.md) has the captures.

### The jank plants

The three plants perfettoagent's roadmap asks of a scrolled list ([perfettoagent#6](https://github.com/ramesh130/perfettoagent/issues/6)),
landed where the demo's Compose feed has the place for them, as perfettoagent's ADR-0007 decides: there is
no `RecyclerView` to bind. Each is written against the feed's own code and names no regression. They were
sized by pilot runs of the jank scenario, one plant against the clean build at a time, made while the
scenario and the plants were being written and so from trees that are not commits on `main`. The pilots
are kept in `devicelab/out/jank-pilots/` of the checkout they were made from. The captures made with the
plants as committed are in [`../jank/README.md`](../jank/README.md). All times in ms. On the emulator
nearly every frame is janky by the timeline's own verdict, so "app jank" below is the share of frames whose
jank type names `App Deadline Missed` (`../jank/README.md` says why).

#### `feed-tap-sleep`

`Thread.sleep(120)` in the feed's tap handler, where it pauses or plays the row being watched, dressed as
holding the tap for a second one. The roadmap's 120 ms, unchanged: the first pilot (`20260924T021615Z`)
found all six of the scenario's taps as six 120 ms sleeps of the main thread inside `AndroidOwner:onTouch`,
Compose handing the touch to the composition, 722 ms in all, where the clean pilots found none there at all.
The frames barely notice it: six late frames, at most, out of seven hundred.

What does not separate it is the main thread's *total* sleep inside slices: 7.2–9.6 s in the clean pilots,
most of it waiting for the RenderThread, so 720 ms more is inside that spread. The sleep has to be looked
for where it happened, which is what the report's `blocked.sql` table does.

#### `feed-grain-allocations`

A faint grain over the feed while it scrolls, drawn from boxed random floats averaged into one veil. The
roadmap's size is 50,000 small objects a frame. It is **1,000,000**, and the place it runs moved too,
because the pilots showed neither the default nor the first place was enough:

| run | where, how many | frames | app jank | frame p95 | GCs | GC time |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `20260924T022549Z` | clean | 688 | 62.1% | 82.5 | 7 | 475 |
| `20260924T023402Z` | clean | 826 | 44.8% | 70.4 | 8 | 387 |
| `20260924T023109Z` | the list's draw, 50k | 757 | 48.3% | 76.7 | 10 | 393 |
| `20260924T023747Z` | each scrolled frame, after it, 50k | 821 | 48.4% | 73.1 | 35 | 1392 |
| `20260924T024604Z` | each scrolled frame, after it, 250k | 554 | 64.1% | 82.5 | 83 | 5257 |
| `20260924T025801Z` | each scrolled frame, after it, 1M | 411 | 62.3% | 77.0 | 114 | 10115 |
| `20260924T030137Z` | each scrolled frame's draw, 250k | 647 | 62.0% | 85.5 | 113 | 6605 |
| `20260924T031129Z` | each scrolled frame's draw, 1M | 339 | 74.0% | 106.3 | 100 | 9355 |

- **In the list's draw it hardly ran.** A scrolled `LazyColumn` moves its rows without drawing itself
  again, so an allocation in its draw modifier ran a handful of times, not once a frame.
- **Once a frame, 50k moved GC and nothing else.** GCs went from 7–8 to 35, but the frames did not
  change: ART's concurrent collector runs beside the main thread, and 50k floats is well under a
  millisecond of the frame's time.
- **After the frame, even a million moved no frame.** Work between frames makes the main thread skip
  vsyncs, so the timeline records *fewer* frames (411 against 700–800), not later ones. A frame that was
  never started is not in `actual_frame_timeline_slice` at all.
- **So it runs inside the frame's draw**, invalidated each scrolled frame by the frame time, and at a
  million it moves app jank and the app's own work per frame as well as GC. An earlier grain that drew
  50,000 points instead of averaging them (`20260924T021917Z`) moved everything, to a 663 ms p95, but by
  drawing, not by allocating, and was dropped for measuring the wrong thing.

#### `feed-row-remeasure`

Every row's horizontal padding breathes by 4 dp over 1.5 s, read in composition, so each visible row is
re-measured, and recomposed, on every frame: the Compose form of the forced layout ADR-0007 names. A row
without a picture shows its name as a title fitted to the row's width, stepping the font size up by
0.05 sp until the next step would overflow, so its measure does real text layout each time the width
changes. The pilots are why there is a title at all:

| run | change | frames | app jank | frame p50 | frame p95 |
| --- | --- | ---: | ---: | ---: | ---: |
| `20260924T022549Z` | clean | 688 | 62.1% | 48.8 | 82.5 |
| `20260924T023402Z` | clean | 826 | 44.8% | 39.2 | 70.4 |
| `20260924T022233Z` | a 4 dp inset read in `layout {}` | 1936 | 83.6% | 65.8 | 86.5 |
| `20260924T024037Z` | 4 dp padding, read in composition | 2096 | 66.9% | 62.7 | 79.2 |
| `20260924T024902Z` | 24 dp padding | 2049 | 67.0% | 61.1 | 78.6 |
| `20260924T025407Z` | 4 dp, and the title fitted in 0.25 sp steps | 1950 | 74.4% | 63.7 | 81.2 |
| `20260924T030429Z` | 4 dp, and the title fitted in 0.05 sp steps | 1515 | 84.2% | 64.4 | 105.4 |

- **A forced re-measure of these rows is cheap.** A row is a box, a label and a line of text; re-measuring
  three or four of them costs the main thread about half a millisecond more a frame. The breath turns an
  idle feed into one drawing every frame and moves the middle of the frame distribution by 15–25 ms, but
  not its tail: every frame becomes a middling one, and p95 stayed inside the clean runs' spread whether
  the read was in `layout {}` or in composition, at 4 dp or at 24 dp.
- **What a re-measure costs is the row's measure**, and in a real feed that is text. With the title fitted
  in 0.05 sp steps, Compose's measure-and-layout pass takes about 20 ms a frame rather than 1.4
  (`AndroidOwner:measureAndLayout`, pilot `20260924T030429Z` against the clean capture `20260924T033342Z`), and p95 moved.
- **Side effects, all real and all the plant's.** The watched row's `SurfaceView` changes size with its
  row every frame; the text layouts allocate, so GC rises too (about 200 collections a run); and the
  trace is about 175 MB rather than 30, because the app draws three times as many frames.

## A planted run names its plant

`run.json`, `report.md` and `plant.patch` of a planted run say what was planted, by name and by content;
that is what makes the run reproducible. So they are the operator's record, not evidence to hand to
whatever is being tested on the trace. Something that must find the regression from the trace alone
gets the trace, and nothing from the run directory that names the plant.

## Adding one

Make the change on a clean tree, then `git add -N` any new file and `git diff > devicelab/plants/<name>.patch`,
and check the tree out again. Write it as a plausible change rather than as a marked one: a comment saying
"planted regression" in the source is what a reader of the trace or the diff would then find, and the
point of a plant is that finding it takes the evidence. Add its row to the table above. If the demo moves
under it, the self-test fails naming it; regenerate it the same way against the new `HEAD`.
