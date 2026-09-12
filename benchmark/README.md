# benchmark

Runs the fixed matrix of [`PRD.md`](../PRD.md) §6 and emits a report: three players, six network
profiles, four content scenarios, twenty runs a cell, with the raw traces beside the summary.

```bash
benchmark/bench                            # the full matrix, into benchmark/out
benchmark/bench --baseline                 # into benchmark/baseline, the committed one
benchmark/bench --runs 5                   # faster; the report says it is not a baseline
benchmark/bench --cells "VOD / 3G"         # a substring filter, for working on the harness
(cd benchmark && ./gradlew test)           # the harness's own tests, and one run a cell
adb shell am start -n com.superplayer.benchmark/.BenchmarkActivity \
    --es arm SUPERPLAYER --es stream "VOD, HLS" --el durationMs 1800000
```

`bench` publishes the library to Maven local first, always, because this build resolves SuperPlayer
from published coordinates rather than from `project(":superplayer-core")` — so a run against a
stale artifact would measure the previous version and record a commit that did not make it.

---

## Why this is not a test, and not in `check`

[`docs/testing.md`](../docs/testing.md) is the binding rule that every test in this repository runs
under Robolectric with **no device and no network**. `benchmark/` sits outside that rule rather than
against it, for the same reason [`devicelab/`](../devicelab/README.md) does, and the distinction is
worth stating because the next reader's first thought is that this file breaks the rule.

Nothing here is a test of the kind that rule governs. It does not run in the suite, it does not gate
a change, and a run of it fails only when it could not measure — never because of *what* it
measured. It **measures**, and its output is a document somebody reads. `./gradlew check` does not
run it, does not know it exists, and passes with no device attached.

The two arms sit differently against the rule and it is worth being precise:

- **The Robolectric arm** — everything `bench` runs — needs no device and no network. It plays
  generated content under Media3's own fakes on a shaped synthetic link, which is what makes it
  reproducible. It could in principle live in the suite; it does not, because twenty runs of ninety
  cells is a measurement and not a check, and because a change that made a number worse should
  produce a finding rather than a red build.
- **The device arm** needs both. Peak RSS and battery are properties of a process on a device with a
  battery, and there is no honest way to get either from a JVM. It is outside `check` for the reason
  every device run is.

The one part of this build that *is* in `check` is nothing at all today. If a device-free
self-check ever grows here — the report generator's honesty rules, say — it belongs in `check`, by
this repository's own rule that a check which can be a Gradle task should be one. Those rules are
currently held by `ReportHonestyTest` under `(cd benchmark && ./gradlew test)`, which CI runs as a
separate step.

---

## The matrix

**Three arms** (`Arm.kt`), because two would not be a comparison:

| | What it is |
| --- | --- |
| (a) stock (defaults) | `ExoPlayer.Builder(context).build()` and nothing else |
| (b) stock (naive tuning) | the same, plus the buffer configuration an app writes from intuition |
| (c) SuperPlayer | `SuperPlayer.Builder(context).setProfile(…)` |

Arm (b) exists because arm (a) alone would be a straw man: the question an adopter is actually
choosing between is not whether configuring beats not configuring, it is whether configuring *by use
case with the reasoning written down* beats configuring *by intuition*. A cell where arm (b) wins is
a cell where SuperPlayer's profile has nothing to offer, and it is reported like any other.

**Six network profiles** — `PRD.md` §6's, replayed from `superplayer-testkit`'s `NetworkProfile`,
where each number carries its public source.

**Four scenarios** (`Scenario.kt`) — VOD, live, short-form, and VOD under `DATA_SAVER`. The profile
arm (c) uses is part of the scenario rather than a free axis: comparing `LIVE_LINEAR` against stock
on an on-demand asset would measure a configuration nobody would ship.

**Reported** — p50/p95 time to first frame, rebuffer ratio, rebuffer count, time-weighted average
bitrate, switch count, startup failure rate, and the QoE objective score. Peak RSS and battery are
the device arm's.

---

## What keeps the arms comparable

This is the part worth reading before trusting a number, and it is the risk issue #43 calls *the
single most likely way this harness ends up lying*: arm (c)'s metrics come from the shipped
`QoeCollector`, arms (a) and (b) have no SuperPlayer in them and cannot use it, and a comparison in
which the two sides measure differently is not a comparison.

Three things, none of which is care:

1. **One transport.** All three players are built by one `PlaybackHarness`
   (`superplayer-testkit`), on one clock, over one fake origin, under one `ShapingDataSource`
   replaying one `ThroughputTrace`, with one renderer and one video output. What is left different
   between the arms is the player.
2. **One reducer.** Every metric is computed by `SessionMetrics` from `superplayer-core`'s own
   `TelemetryEvent` vocabulary, and that function cannot tell which arm produced the events. The
   rebuffer denominator, the seek exclusion and the bitrate weighting are therefore shared by
   construction. The definitions are
   [`docs/telemetry-schema.md`](../docs/telemetry-schema.md), and each one is cited where it is
   implemented.
3. **One derivation, checked.** What remains is *when* an event fires, and `StockTelemetry` mirrors
   `QoeCollector` callback for callback to produce it. `StockTelemetryAgreementTest` attaches both
   collectors to **the same `ExoPlayer`** and asserts they derive the same events, the same stall
   durations, the same seek attribution and the same sampling cadence from identical engine
   callbacks. If that test goes, the benchmark's central claim goes with it.

---

## The honesty rules, and where each one lives

`PRD.md` §6's rules are requirements rather than aspirations, so each is enforced somewhere a change
would have to go out of its way to defeat:

| Rule | Where it is enforced |
| --- | --- |
| At least 20 runs per cell | `bench`'s default; a shorter run makes the report say it is not a baseline |
| Report variance, not only means | `Distribution` — there is no way to get a mean out of it without its spread |
| Publish the raw traces | `TraceWriter`, one JSONL file per cell, every event of every run |
| Publish neutral and worse cells | `ReportWriter` prints losses **before** wins, in the same words, and has no filter |
| A difference inside the noise is not a win | `Comparison.verdict`, one rule fixed in advance, symmetric in both directions |
| The F1 bitrate trade appears as a loss | its own section of the report, from the `VOD (data saver)` row |
| Excluded sessions are counted, not dropped quietly | `CellResult.excludedSessions`, printed in the report |

`ReportHonestyTest` holds the report generator to the ones that are about presentation, against a
fixture built so that SuperPlayer loses.

**There is no "highlight the wins" mode**, and that is a property of the code rather than an
intention: `ReportWriter.write` takes a report and nothing else — no cell filter, no threshold, no
ordering by how well an arm did. It is much harder to add that once somebody has seen a chart they
liked, which is why it is not there now.

---

## The committed baseline

`benchmark/baseline/` holds a report and its traces, committed, because **this is what Phase 3 is
graded against** and a number nobody can find is not a baseline. It records the SuperPlayer commit,
the Media3 version, the Robolectric SDK, the JDK, the host and the runs per cell alongside the
numbers: a number without its conditions cannot be compared to anything.

Re-take it with `benchmark/bench --baseline`, on a clean tree. A run with uncommitted changes says so
in prose at the top of its own report, because it describes a state of the repository that nobody can
check out.

---

## The device arm

Peak RSS and battery delta over a 30-minute session are in `PRD.md` §6's reported list and are
**absent from the Robolectric report on purpose**. Both are properties of a process on a device, and
reporting a JVM heap figure as though it were an Android app's resident set would be a plausible
wrong number — which is the failure mode this whole harness is arranged against.

`BenchmarkActivity` is the device arm's app, and it is built: given an arm, one of the public streams
in `PublicStreams.kt`, and a duration, it plays a 30-minute session on a real device over a real
network, writes its telemetry as a JSONL trace in the same format the Robolectric arm uses, and logs
one line under the tag `SuperPlayerBench` when it is done. It measures neither peak RSS nor battery
itself, deliberately — an app that measured its own memory would be measuring the measurement. Both
are read off the process from outside, by Perfetto's `process_memory` and `battery` data sources.

```bash
(cd benchmark && ./gradlew assembleBenchmark)
adb install -r -t benchmark/build/outputs/apk/benchmark/superplayer-benchmark-benchmark.apk
adb logcat -c
adb shell am start -n com.superplayer.benchmark/.BenchmarkActivity \
    --es arm STOCK_DEFAULTS --es stream "VOD, HLS" --el durationMs 1800000
adb logcat -d -t 2000 | grep SuperPlayerBench       # progress, then "run complete"
adb shell run-as com.superplayer.benchmark ls files  # the trace it wrote
```

**What is not built: the harness around it.** [`devicelab/`](../devicelab/README.md) is this
repository's one entry point for measuring on a device — it boots or adopts a device, publishes,
builds, refuses to measure a stale APK, and captures a Perfetto trace — and its README already names
this benchmark as a future consumer. It is currently shaped around the demo: the package, the
activity, the APK path and the readiness probe (which reads a media session the benchmark app does
not publish) are the demo's throughout. Wiring a second app through it is a real change to a harness
whose whole value is that it is reliable, and it is tracked separately rather than half-done here.

**So no device run has been taken, and the peak-RSS and battery columns of the committed baseline are
dashes.** They are printed as dashes in the report rather than left out of it, so that a reader
cannot mistake an unmeasured cell for a measured one.

---

## The content, and two gaps

The Robolectric arm plays **generated** rendition ladders — synthesized by the harness over Media3's
own adaptive fakes, at bitrates `Scenario.kt` argues against the network profiles they are played
over. That arm has to be reproducible and a stream fetched over the internet is the one thing that
cannot be.

The device arm plays **public streams**, each listed in `PublicStreams.kt` with the page that
publishes it, per `PRD.md` §0.2: every number this project publishes comes from its own harness
against public content, and none is carried in from anywhere else.

Two cells of §6's content axis are documented gaps rather than silent omissions, and the report
prints both with what would close them:

- **Widevine (DRM)** — Phase 6. `superplayer-drm` is an empty placeholder, so arm (c) would be a
  SuperPlayer with no DRM behaviour and the row would compare three players that are, on this axis,
  the same player.
- **Live on the device arm** — the Robolectric arm covers live; the device half is open because a
  live URL is a claim that something is publishing right now and nothing here can verify one.

---

## Running it, and what costs time

The full matrix is 4 scenarios × 6 networks × 3 arms × 20 runs = 1 440 sessions, and takes roughly
three quarters of an hour on a laptop. Time inside a session is a `FakeClock`, so the cost is CPU
rather than wall clock, and it is dominated by the harness advancing that clock in load-sized steps.

- **Republish after any library change.** `bench` does it for you. Doing it by hand and forgetting is
  how a report ends up describing the previous version.
- **Run on JDK 17**, the version CI uses. The report records the JDK it ran on, so a baseline taken
  on another one says so rather than quietly comparing badly.
- **`--cells` is for working on the harness, not for a baseline.** A filtered run skips the runner's
  completeness assertion, and its report is a report of whatever ran.
