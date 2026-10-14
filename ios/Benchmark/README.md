# The iOS benchmark

`PRD.md` §6's matrix on the iOS simulator: four players, six network profiles, four content
scenarios, with the raw traces beside the summary. The port of [`android/benchmark/`](../../android/benchmark/README.md),
whose rules it keeps and whose reasoning this page does not repeat; what is here is what differs on
iOS, and why.

```bash
ios/Benchmark/bench                          # the matrix, 3 runs a cell, into ios/Benchmark/out (~2 h 40 min)
ios/Benchmark/bench --baseline               # into ios/Benchmark/phase3, the committed reference
ios/Benchmark/bench --runs 1 --cells "VOD / 3G"   # a filtered smoke run, for working on the harness
```

The committed reports sit here and not among the Android reports: they measure a different
engine, on a fake clock, and no cell of one is comparable to a cell of the other.
[`baseline/`](baseline/baseline-report.md) is i2's, frozen, run
with arm (d) an empty slot; [`phase3/`](phase3/baseline-report.md)
is i3's (#416), with all four arms, and is what `--baseline` writes now — the Android `phase3/`'s
place. The two are on different time zeros: i3's ran on #479's, where a trace starts at the first
media segment requested, and i2's predates it, when a trace started with the origin.

---

## Where it lives, and what `ios/check` runs of it

| Path | What it is |
| --- | --- |
| `ios/Benchmark/Sources/SuperPlayerBenchmark/` | the arms, scenarios, `StockTelemetry`, the session runner, the statistics, the report and trace writers |
| `ios/Benchmark/Tests/SuperPlayerBenchmarkTests/` | `StockTelemetryAgreementTests` and `ReportHonestyTests`, which `ios/check` runs; `BenchmarkMatrixRun`, which it skips |
| `ios/Benchmark/bench` | the runner: picks a simulator, passes the conditions in, runs `BenchmarkMatrixRun`, checks the result bundle |

**Why a target of the root manifest and not a package of its own**, which is what `android/benchmark/` is
on Android: a `SuperPlayer` playing video in `xctest` has to be given a lifecycle platform that does
nothing — its video holds the idle timer, and `xctest` has no `UIApplication` — and that seam,
`SharedLifecycle(platform:)`, is `package`-scoped, which SwiftPM grants to targets of one package
only. So `SuperPlayerBenchmark` is a target of `Package.swift` and **not a product**: nothing ships
it, it has no API baseline, and `docs/modules.md` gives it the phase of the latest product it measures
with — 3, since arm (d) brought in `SuperPlayerABR` (#416) — with nothing depending on it.

**Why the matrix is not in `ios/check`**, for `android/benchmark/README.md`'s reasons: it measures rather
than checks, a change that made a number worse should produce a finding rather than a red build,
and it takes nearly three hours of real time. It is hosted in an XCTest only because that is how a
process reaches the simulator. `ios/check` passes `-skip-testing:SuperPlayerBenchmarkTests/BenchmarkMatrixRun`,
and the test skips itself anyway without `SP_BENCH_OUT`, so the whole scheme run from Xcode does not
start an hour of playback.

**What `ios/check` does run** is the part the benchmark's claim stands on: the agreement test (below)
and the report's honesty rules over a fixture built so that SuperPlayer loses. Neither plays more
than a minute.

---

## The matrix

**Four arms** (`Arm.swift`):

| | What it is |
| --- | --- |
| (a) stock (defaults) | `AVPlayer(playerItem:)` and `play()`, and nothing else |
| (b) stock (naive tuning) | the same, plus `preferredForwardBufferDuration` of 120 s and `playImmediately(atRate: 1)` |
| (c) SuperPlayer | `SuperPlayer(configuration:)` with the scenario's profile and its static policy |
| (d) SuperPlayer (adaptive) | the same plus `SuperPlayerABR`'s `AdaptivePolicy.forProfile` for that profile (#416), its estimate memory forgotten before each session |

Arm (b) is the Kotlin arm's two pulling-against-each-other changes in the vocabulary iOS offers:
*buffer much more* is the forward-buffer preference, and *start much sooner* is Apple's own "start
now even if it may stall", since iOS has no start floor to lower. `Arm.swift` says why it is not
`automaticallyWaitsToMinimizeStalling = false`, which would measure the runner's resume loop.

Arm (d) is the arm the report grades (`Arm.candidate`), and `ExitCriterion` judges it against arm
(c). Until #416 it was an empty slot, printed as *not built*, which is what i2's frozen report shows.
**Each of its sessions starts cold**: `SessionRunner` forgets the process's estimate memory first,
as the Android runner idles its fake clock past ADR-0009 rule 9's stale age, so no session decides on
the one before it. AVFoundation's own carried estimate (below) is untouched on every arm alike.

**Six network profiles**, testkit's `NetworkProfile` less `ethernet`, paced at the loopback socket
from the moment each session asks for its first media segment (#474). **Four scenarios** (`Scenario.swift`), with Android's
ladders rung for rung: 365, 730, 2 000 and 4 500 kbit/s, and the feed ladder without its top.

**The content is bytes** — `TestContent.videoLadder` and `liveVideoLadder`, over testkit's
`SyntheticVideoLadder` — where Android's is described: `AVPlayer` plays only what it fetches, so every
segment of a rung is padded with a `free` box to exactly the bitrate its playlist declares, and a
shaped link charges for it what it says.

**Reported** — p50/p95 time to first frame, rebuffer ratio and count, time-weighted bitrate, switch
count, startup failure rate and the QoE score, every one through `SuperPlayerTelemetry`'s
`SessionMetrics` and `QoeScore`, ported from Android definition for definition.

---

## What keeps the arms comparable

The Kotlin three, on iOS's terms:

1. **One transport.** Each session gets a fresh loopback origin serving the scenario's content over
   the profile's trace; what differs between arms is the player.
2. **One reducer.** `SessionMetrics` cannot tell which arm produced an event stream.
3. **One derivation, checked.** Arm (c)'s events are the shipped `QoeCollector`'s; the stock arms'
   are `StockTelemetry`'s, which mirrors that collector and its register handler for handler.
   **`StockTelemetryAgreementTests` attaches both to one `AVPlayer`** — a `SuperPlayer`'s, through
   `player.avPlayer` — over a session with a stall, a seek, a rendition choice and a sample, and
   asserts they derive the same events. On real time it compares everything that is not a reading
   of a clock; its doc comment says why durations are not compared here when they are on Android.

Plus one the Kotlin harness did not need: **one stand-in for a screen, on every arm** — below.

---

## What the simulator taught the harness

Each of these was found on the iOS 27.1 simulator while building this, and each is a reason the
runner is the shape it is. None is a claim about a device.

- **An `AVPlayer` whose video nobody consumes never climbs a ladder.** In `xctest` no layer is ever
  in a window, and with no `AVPlayerItemVideoOutput` on the item the player stayed on its
  first-listed rung for the whole session at 20 Mbit/s, an `AVPlayerLayer` sized for 1080p not
  helping. So every arm's item carries one output nobody reads — the same object on every arm, so it
  moves no comparison.
- **It climbs only to a rung that declares a larger picture.** A ladder whose rungs all declared one
  `RESOLUTION` stayed on the first rung too. So the ladder declares Android's `Rung.of` sizes —
  360p, 480p, 720p, 1080p — while every rung carries the same 64×64 picture
  (`SyntheticVideoLadder.declaredHeightPx`).
- **It remembers a throughput estimate between items in one process.** The first session of a
  process started on the first-listed rung; the next, on 3G, started on the top rung, carried over
  from the 20 Mbit/s session before it. Nothing public clears it, so the runner **balances** it
  rather than removing it: a cell's arms play back to back on one network, in an order that
  rotates each run (`MatrixRun.swift`), and the report reads every start as a returning viewer's.
  Android's runner idles its fake clock past `superplayer-abr`'s memory instead; there is no clock
  here to idle.
- **A link below a rung's bitrate never delivers that rung again.** AVFoundation abandons a segment
  not whole within about a target duration and asks for it from its first byte, so on a link slower
  than the rung — for a segment as long as its target — no segment arrives and the session stalls
  for good — on every arm alike
  (`SlowLinkPlaybackTests`, #474). Every profile in the matrix stays above the bottom rung
  but for `lteWithDropouts`' two seconds at zero, after which its link is faster than every rung
  again.
- **A cold simulator spends seconds before its first segment.** Nine, between the two playlists, on
  a freshly booted one — so the link's clock starts at the first media segment rather than at the
  origin, or the first session of a run would meet a later stretch of its profile than the rest
  (#474).
- **The access log's `indicatedBitrate` can read −1** on an entry the player did play, once seen
  after a climb to the top rung. `QoeCollector` reads −1 as unknown and reports no switch for it,
  so such a session's bitrate column holds the rung before; it is the collector's derivation on
  every arm alike, and the traces show it where it happened.

---

## The run count

`PRD.md` §6 asks for twenty runs a cell. On Android a session is a `FakeClock`'s, and the whole
matrix is a minute and a half of CPU. **Here a session is real time**: the first frame, then thirty
seconds of playback (`Scenario.playbackMs`, the least that keeps Android's reasons — three
ten-second samples, the LTE outage at twenty seconds, the handover at ten), then the release. Call
it thirty-three seconds a session.

| Runs a cell | Sessions (24 cells × 4 arms) | Wall clock |
| --- | --- | --- |
| 20 | 1 920 | about 17 h 36 min |
| 5 | 480 | about 4 h 24 min |
| **3** | **288** | **about 2 h 40 min** |
| 2 | 192 | about 1 h 46 min |

**Three is the default and the committed reports' count**, for three reasons. It is the fewest
that estimates a spread on more than one degree of freedom, which is what the two-standard-error
band is made of — at two, one unusual run sets the band by itself. It was the number at which a
cell's three arms each took each place in the rotation once; with arm (d) there are four, and what
balances the carried estimate is the rotation's offset by cell, so across a run every arm opens as
many cells as any other. And it fits a working session on a shared host, where twenty would not fit
a day and running sessions side by side to go faster would let each one's estimate and CPU reach the
others'.

What it costs is said where it matters: the report opens by saying it is three runs and not twenty,
and with three samples the noise band is wide, so most differences read as **neutral** — which is
the rule working rather than the players agreeing. A difference that survives that band at n=3 is a
large one.

---

## The honesty rules, and where each one lives

`android/benchmark/README.md`'s table, on iOS:

| Rule | Where it is enforced |
| --- | --- |
| At least 20 runs per cell | not met by design, and said at the top of every report (*The run count*) |
| Report variance, not only means | `Distribution`, which has no mean without its spread |
| Publish the raw traces | `TraceWriter`, one JSONL file per cell, every event of every run |
| Publish neutral and worse cells | `ReportWriter` prints losses **before** wins, in the same words, and has no filter |
| A difference inside the noise is not a win | `Comparison.verdict`, one rule fixed in advance |
| The F1 bitrate trade appears as a loss | its own section: the data-saver row on every cellular profile, bitrate beside stalls |
| The phase's verdict is decided by a rule | `ExitCriterion`, the report's opening section — *not measured* until arm (d) exists |
| Excluded sessions are counted | `CellResult.excludedSessions`, printed |

`ReportHonestyTests` holds the presentation rules, in `ios/check`.

---

## What it does not measure

- **Peak RSS and battery** — a simulator process's memory is the host's and it has no battery. They
  are a device's, on iOS i10's (#439). The report prints them as dashes.
- **Variation in the field.** The link is a trace; the spread is this host's run-to-run variation,
  and on a shared host some of it is the other work.
- **A first-time viewer's start-up**, after a process's first session (above).
- **Protected content** — FairPlay is i6's and needs a device — and **public streams**, which need
  the network `docs/testing.md` bars.
