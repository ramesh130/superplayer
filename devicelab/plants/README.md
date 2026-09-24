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
