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
| `startup-main-thread-io` | `startup` | adds an `Application` subclass whose `onCreate` reads and checksums a 192 MiB file on the main thread | time to initial display (`startup_ttid_ms`); `bindApplication` |

### `startup-main-thread-io`

A synchronous file read in `Application.onCreate`. The demo has no `Application` subclass, so the patch
adds one (`DemoApplication`) and names it in the manifest, rather than hosting the read somewhere a real
app would not put it. The read is real I/O on the main thread: the startup scenario drops the page cache
before every launch, so the file comes from storage each time. Inside `bindApplication` the trace shows
the main thread in uninterruptible sleep waiting on I/O (`thread_state` D, `io_wait` 1) about five times
as long as in a clean launch, and running for about three and a half times as long, copying and
checksumming what it read. The file is written by the first launch after install, which is the
harness's own and is outside the trace.

**Why 192 MiB.** Large for an app, and chosen from measurement rather than realism, because the
emulator's storage is the host's and fast. The rule was that every regressed launch should be slower than
every clean launch, so that the regression is above the noise at every percentile, not only on average.

- Clean, twenty cold launches: time to initial display 321–516 ms, p50 400 ms; `bindApplication`
  73–152 ms.
- The page cache dropped, the emulator read cold at 190–720 MB/s (`dd`, 64 MiB, three reads), and from
  memory at 2 GB/s.
- **64 MiB** added about 140 ms to `bindApplication` and 90 ms to p50, and the two runs overlapped: the
  fastest regressed launch, 438 ms, was faster than the slowest clean one.
- **192 MiB** separated them: 580–778 ms, p50 637 ms, with `bindApplication` at 396–479 ms.

The captures made with it, and the clean pairs beside them, are in
[`../startup/README.md`](../startup/README.md).

## Adding one

Make the change on a clean tree, then `git add -N` any new file and `git diff > devicelab/plants/<name>.patch`,
and check the tree out again. Write it as a plausible change rather than as a marked one: a comment saying
"planted regression" in the source is what a reader of the trace or the diff would then find, and the
point of a plant is that finding it takes the evidence. Add its row to the table above. If the demo moves
under it, the self-test fails naming it; regenerate it the same way against the new `HEAD`.
