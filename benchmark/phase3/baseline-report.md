# SuperPlayer benchmark — Phase 3 report

The fixed matrix of [`PRD.md`](../PRD.md) §6, run by `benchmark/`, with `superplayer-abr`'s adaptive policy as the arm under test and the Phase 1 baseline's three players re-run beside it. Every number here is produced by this harness against content this repository generates or public streams it names (§0.2); none is carried in from anywhere else, and none is copied from [the Phase 1 baseline](../baseline/baseline-report.md).

## Exit criterion

Phase 3's exit criterion is *measured improvement over the Phase 1 baseline on the shaped-network suite, with no regression on stable WiFi*. It is judged against **SuperPlayer** — the static profile the Phase 1 baseline measured, re-run here — by the two-standard-error rule below, and the rule was fixed before this run (`ExitCriterion.kt`): on a shaped profile, a QoE score better in at least one scenario and worse in none; on stable WiFi, no metric of any scenario worse.

> **Not judged: live × SuperPlayer (adaptive).** Under the harness, the adaptive policy on the synthetic live ladder keeps the engine working without the clock moving, and the session neither starts nor fails — while the static profile and both stock arms play the same window. The adaptive policy is the only arm that lays a live playback-speed range into the item, over a window dated against a wall clock Robolectric does not move; whether that is a harness limitation or a library defect is not yet known. So the adaptive policy's live branch has no number in this report, and the exit criterion is judged on the other three scenarios. _Closed by:_ issue #144, then re-taking this report.

**Not met.** The criterion fails on: stable WiFi, congested WiFi, LTE with dropouts, 3G, WiFi→cellular, high latency. The cells that decided it are listed below, and each is a finding for the next phase rather than a reason to retune before committing this report.

| Network | Judged on | Verdict | QoE better in | Worse in |
| --- | --- | --- | --- | --- |
| stable WiFi | every metric | **not met** | — | VOD: switch count (worse), VOD: QoE score (worse), short-form: rebuffer ratio (worse), short-form: rebuffer count (worse), short-form: QoE score (worse) |
| congested WiFi | QoE score | **not met** | — | VOD: QoE score (worse), short-form: QoE score (worse) |
| LTE with dropouts | QoE score | **not met** | — | VOD: QoE score (worse), short-form: QoE score (worse), VOD (data saver): QoE score (worse) |
| 3G | QoE score | **not met** | — | VOD: QoE score (worse), short-form: QoE score (worse) |
| WiFi→cellular | QoE score | **not met** | — | VOD: QoE score (worse), short-form: QoE score (worse), VOD (data saver): QoE score (worse) |
| high latency | QoE score | **not met** | VOD | short-form: QoE score (worse) |

The comparisons that failed it, SuperPlayer (adaptive) against SuperPlayer:

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +1.000 — **worse** |
| VOD | stable WiFi | QoE score | 4.50 ± 0.00 (p50 4.50, p95 4.50, n=20) | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) | −0.038 (−0.8%) — **worse** |
| short-form | stable WiFi | rebuffer ratio | 0.0152 | 0.2803 | +0.265 (+1750.0%) — **worse** |
| short-form | stable WiFi | rebuffer count | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 22.00 ± 0.00 (p50 22.00, p95 22.00, n=20) | +18.000 (+450.0%) — **worse** |
| short-form | stable WiFi | QoE score | 1.97 ± 0.00 (p50 1.97, p95 1.97, n=20) | 1.22 ± 0.00 (p50 1.22, p95 1.22, n=20) | −0.748 (−38.0%) — **worse** |
| VOD | congested WiFi | QoE score | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.71 ± 0.00 (p50 0.71, p95 0.71, n=20) | −0.292 (−29.1%) — **worse** |
| short-form | congested WiFi | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=20) | −0.128 (−17.6%) — **worse** |
| VOD | LTE with dropouts | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 1.50 ± 0.00 (p50 1.50, p95 1.50, n=20) | −0.500 (−25.0%) — **worse** |
| short-form | LTE with dropouts | QoE score | 1.91 ± 0.00 (p50 1.91, p95 1.91, n=20) | 1.58 ± 0.00 (p50 1.58, p95 1.58, n=20) | −0.330 (−17.3%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | −0.365 (−50.0%) — **worse** |
| VOD | 3G | QoE score | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | 0.34 ± 0.00 (p50 0.34, p95 0.34, n=20) | −0.024 (−6.6%) — **worse** |
| short-form | 3G | QoE score | 0.29 ± 0.00 (p50 0.29, p95 0.29, n=20) | 0.27 ± 0.00 (p50 0.27, p95 0.27, n=20) | −0.024 (−8.2%) — **worse** |
| VOD | WiFi→cellular | QoE score | 3.17 ± 0.00 (p50 3.17, p95 3.17, n=20) | 2.34 ± 0.00 (p50 2.34, p95 2.34, n=20) | −0.833 (−26.2%) — **worse** |
| short-form | WiFi→cellular | QoE score | 1.93 ± 0.00 (p50 1.93, p95 1.93, n=20) | 1.32 ± 0.00 (p50 1.32, p95 1.32, n=20) | −0.607 (−31.5%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.48 ± 0.00 (p50 0.48, p95 0.48, n=20) | −0.249 (−34.1%) — **worse** |
| short-form | high latency | QoE score | 1.91 ± 0.00 (p50 1.91, p95 1.91, n=20) | 0.46 ± 0.00 (p50 0.46, p95 0.46, n=20) | −1.451 (−75.9%) — **worse** |

## What this was measured on

| | |
| --- | --- |
| SuperPlayer commit | `9c3c6ea1509484d422b14f0ad80781046b724468` |
| SuperPlayer version | `0.1.0-SNAPSHOT` |
| Media3 | `1.11.0` |
| Runs per cell | 20 |
| Robolectric SDK | 35 |
| Java | 17.0.18 |
| Host | Mac OS X 26.1 aarch64, 10 cores |
| Started | 2026-09-14T07:05:18.530288Z |

## How to read this

**The four arms** (`PRD.md` §6, and `Arm.kt`):

- **stock (defaults)** — `ExoPlayer.Builder(context).build()` and nothing else. Media3's own defaults are 50 s/50 s of buffer, 2.5 s before playback starts and 5.0 s after a rebuffer, and this arm is not built with them — it is built with nothing, so it tracks whatever they become.
- **stock (naive tuning)** — the same, plus 60 s/120 s of buffer, 1.0 s before playback starts and 2.0 s after a rebuffer — the buffer configuration an app writes when it has decided its player stalls too much and has not measured why.
- **SuperPlayer** — `SuperPlayer.Builder(context).setProfile(…)`, with the profile each scenario names and its static policy: the player the Phase 1 baseline measured. What it was configured with is in the raw traces, on every `session_started` line.
- **SuperPlayer (adaptive)** — the same, plus `setPolicy(AdaptivePolicy.forProfile(context, profile))` from `superplayer-abr`'s published artifact — the arm this report grades. Every comparison below is this arm against one of the other three.

**Every adaptive session starts from a cold estimate.** `superplayer-abr` remembers a throughput estimate per transport for the life of the process (ADR-0009 rule 8), and the whole matrix is one process, so without care a cell's adaptive number would depend on which cells ran before it. The runner cannot clear that memory — it is internal, and this build is a consumer — so before each adaptive session it lets the harness clock run past the age at which a remembered estimate is forgotten (ADR-0009 rule 9), on an idle player. Each adaptive session is therefore a first session on its network, which is also how `superplayer-abr`'s QoE regression gate plays one. A returning viewer's warm estimate is not measured here.

**The metric definitions are [`docs/telemetry-schema.md`](../docs/telemetry-schema.md)**, not this document and not the source. Every metric below is computed by one function, `SessionMetrics`, from one event vocabulary, for all four arms — which is what makes the columns comparable at all. Arms (c) and (d)'s events come from the shipped `QoeCollector`; arms (a) and (b) have no SuperPlayer in them, so their events come from `StockTelemetry`, which mirrors that collector callback for callback and is held to it by `StockTelemetryAgreementTest`.

Two things about the numbers themselves:

- **Every mean carries its spread**, as `mean ± sd (p50, p95, n)`. `PRD.md` §6 requires variance and not only means, and on a shaped network the tail is usually the interesting part: a player that is usually fine and occasionally terrible has a good mean.
- **A difference smaller than the noise is reported as neutral, not as a small win.** The rule is fixed in advance: a difference counts only if it exceeds two standard errors of the difference of the means, which is the conventional rough 95% band. It is applied identically to differences in both directions.

**Rebuffer ratio is pooled.** The value printed is the sum of every session's stalled milliseconds over the sum of stalled-plus-playing, which is what the schema requires; averaging per-session ratios would weight a short session like a long one. The *verdict* beside it comes from the spread of the per-session ratios, because a pooled ratio is one number and one number cannot be told from noise.

**What the spread is a spread of.** The network under this arm is a deterministic trace replayed against a `FakeClock`, so the variance in these columns is **not** the variance a real device on a real link would see. The harness holds every load to its clock, so most sessions replay identically and many cells have no spread at all; what spread remains is what run-to-run scheduling on the host still reaches, because loads run on real threads even though playback time does not. A cell with no spread makes any difference from another arm a verdict, however small, so read the size of a difference beside its verdict and treat one of a step or two as the harness's resolution rather than the player's. None of this estimates how variable playback is in the field, and a p95 here should not be quoted as one. Field variance is the device arm's to measure.

**Time to first frame is quantised to the harness's step.** The Robolectric arm advances a `FakeClock` in fixed 50 ms steps (`PlaybackHarness.WAIT_STEP_MS`) while it waits for the player to become ready, so a measurement lands on a multiple of that step and two arms differing by less than one step read as identical. That puts a floor on the difference this column can resolve, and a row of exactly equal start-up times across the arms is that floor rather than players agreeing to the millisecond. The harness lets the engine finish everything due at one moment before either clock moves, so a first frame is stamped with the step in which it was rendered and never with the next one. Within a step the engine acts before the loads, though, so each finished load reaches the engine one step later than a free-running player would hear it. Start-up therefore carries up to a step of delay per load it waits on. The delay is the same run after run, but an arm that waits on more loads before its first frame carries more of it, so a start-up difference of a step or two between arms is within what the harness itself adds.

**Bitrate is sampled at ten seconds**, which is the cadence `PlaybackStateSampled` carries and therefore the resolution of any time-weighted average taken from it. A rendition held for less than one interval can fall between samples. This is identical for all arms, so it moves no comparison, but it does mean the bitrate column is coarser than the switch column.

**0 session(s) were excluded** across the whole matrix, out of 1800 run. A session is excluded when its event stream was incomplete — `SessionEnded.droppedEventCount` non-zero, which the schema says makes a summed metric a plausible wrong number — or when it produced no `SessionEnded` at all. Nothing is excluded for being slow, for being an outlier, or for spoiling a trend.

## Where the adaptive policy is worse, or no better

This section comes before the wins deliberately. `PRD.md` §6: *publish the cases where SuperPlayer is neutral or worse — a benchmark table with no losses in it is a marketing document and will be read as one.* A loss against stock (naive tuning) is stated as plainly as any other: it is a cell where configuring by intuition did as well, and a finding for the next phase.

### Against stock (defaults)

**Worse (46 of 108 comparisons):**

| Scenario | Network | Metric | stock (defaults) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | switch count | 0.10 ± 0.45 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.900 (+900.0%) — **worse** |
| VOD | congested WiFi | average bitrate (bit/s) | 984000 ± 189320 (p50 941667, p95 941667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −254000 (−25.8%) — **worse** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1576667 ± 0 (p50 1576667, p95 1576667, n=20) | −423333 (−21.2%) — **worse** |
| VOD | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +4.000 — **worse** |
| VOD | LTE with dropouts | QoE score | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | 1.50 ± 0.00 (p50 1.50, p95 1.50, n=20) | −0.448 (−23.0%) — **worse** |
| VOD | 3G | time to first frame (ms) | 963 ± 727 (p50 800, p95 800, n=20) | 4050 ± 0 (p50 4050, p95 4050, n=20) | +3088 (+320.8%) — **worse** |
| VOD | 3G | switch count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.950 (+1900.0%) — **worse** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 4177400 ± 333888 (p50 3875000, p95 4500000, n=20) | 2416667 ± 0 (p50 2416667, p95 2416667, n=20) | −1760733 (−42.1%) — **worse** |
| VOD | WiFi→cellular | switch count | 0.55 ± 0.60 (p50 0.00, p95 1.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +1.450 (+263.6%) — **worse** |
| VOD | WiFi→cellular | QoE score | 3.33 ± 1.16 (p50 2.52, p95 4.47, n=20) | 2.34 ± 0.00 (p50 2.34, p95 2.34, n=20) | −0.993 (−29.8%) — **worse** |
| short-form | stable WiFi | rebuffer ratio | 0.0568 | 0.2803 | +0.223 (+393.3%) — **worse** |
| short-form | stable WiFi | rebuffer count | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 22.00 ± 0.00 (p50 22.00, p95 22.00, n=20) | +20.000 (+1000.0%) — **worse** |
| short-form | stable WiFi | QoE score | 1.88 ± 0.00 (p50 1.88, p95 1.88, n=20) | 1.22 ± 0.00 (p50 1.22, p95 1.22, n=20) | −0.658 (−35.0%) — **worse** |
| short-form | congested WiFi | rebuffer ratio | 0.0265 | 0.0530 | +0.027 (+100.0%) — **worse** |
| short-form | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +3.000 (+300.0%) — **worse** |
| short-form | congested WiFi | average bitrate (bit/s) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −211667 (−22.5%) — **worse** |
| short-form | congested WiFi | QoE score | 0.81 ± 0.00 (p50 0.81, p95 0.81, n=20) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=20) | −0.210 (−26.0%) — **worse** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0759 | +0.064 (+566.7%) — **worse** |
| short-form | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +3.000 (+300.0%) — **worse** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1788333 ± 0 (p50 1788333, p95 1788333, n=20) | −211667 (−10.6%) — **worse** |
| short-form | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +2.000 — **worse** |
| short-form | LTE with dropouts | QoE score | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) | 1.58 ± 0.00 (p50 1.58, p95 1.58, n=20) | −0.395 (−20.0%) — **worse** |
| short-form | 3G | time to first frame (ms) | 960 ± 727 (p50 800, p95 800, n=20) | 4050 ± 0 (p50 4050, p95 4050, n=20) | +3090 (+321.9%) — **worse** |
| short-form | 3G | rebuffer ratio | 0.0119 | 0.0336 | +0.022 (+181.9%) — **worse** |
| short-form | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | +2.000 (+200.0%) — **worse** |
| short-form | 3G | switch count | 0.10 ± 0.45 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.900 (+900.0%) — **worse** |
| short-form | 3G | QoE score | 0.34 ± 0.01 (p50 0.34, p95 0.34, n=20) | 0.27 ± 0.00 (p50 0.27, p95 0.27, n=20) | −0.069 (−20.4%) — **worse** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0568 | 0.1364 | +0.080 (+140.0%) — **worse** |
| short-form | WiFi→cellular | rebuffer count | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 8.00 ± 0.00 (p50 8.00, p95 8.00, n=20) | +6.050 (+310.3%) — **worse** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 1986375 ± 60933 (p50 2000000, p95 2000000, n=20) | 1682500 ± 0 (p50 1682500, p95 1682500, n=20) | −303875 (−15.3%) — **worse** |
| short-form | WiFi→cellular | switch count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +1.950 (+3900.0%) — **worse** |
| short-form | WiFi→cellular | QoE score | 1.86 ± 0.07 (p50 1.88, p95 1.88, n=20) | 1.32 ± 0.00 (p50 1.32, p95 1.32, n=20) | −0.542 (−29.1%) — **worse** |
| short-form | high latency | rebuffer ratio | 0.0114 | 0.4347 | +0.423 (+3719.3%) — **worse** |
| short-form | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 18.00 ± 0.00 (p50 18.00, p95 18.00, n=20) | +17.000 (+1700.0%) — **worse** |
| short-form | high latency | QoE score | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) | 0.46 ± 0.00 (p50 0.46, p95 0.46, n=20) | −1.515 (−76.6%) — **worse** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | stable WiFi | QoE score | 2.97 ± 0.00 (p50 2.97, p95 2.97, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −2.240 (−75.4%) — **worse** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −211667 (−22.5%) — **worse** |
| VOD (data saver) | congested WiFi | QoE score | 0.74 ± 0.00 (p50 0.74, p95 0.74, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −0.010 (−1.4%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | −1635000 (−81.8%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | −1.583 (−81.3%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 4177400 ± 333888 (p50 3875000, p95 4500000, n=20) | 486667 ± 0 (p50 486667, p95 486667, n=20) | −3690733 (−88.4%) — **worse** |
| VOD (data saver) | WiFi→cellular | switch count | 0.55 ± 0.60 (p50 0.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.450 (+81.8%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 3.33 ± 1.16 (p50 2.52, p95 4.47, n=20) | 0.48 ± 0.00 (p50 0.48, p95 0.48, n=20) | −2.853 (−85.6%) — **worse** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 4479167 ± 93169 (p50 4500000, p95 4500000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −3749167 (−83.7%) — **worse** |
| VOD (data saver) | high latency | QoE score | 4.34 ± 0.08 (p50 4.35, p95 4.35, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −3.606 (−83.2%) — **worse** |

**Neutral — inside the noise (21):**

| Scenario | Network | Metric | stock (defaults) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | average bitrate (bit/s) | 4458650 ± 184923 (p50 4500000, p95 4500000, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | +41350 (+0.9%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 1093 ± 414 (p50 1000, p95 1000, n=20) | 1000 ± 0 (p50 1000, p95 1000, n=20) | −92.500 (−8.5%) — **neutral** |
| VOD | congested WiFi | QoE score | 0.78 ± 0.18 (p50 0.74, p95 0.74, n=20) | 0.71 ± 0.00 (p50 0.71, p95 0.71, n=20) | −0.070 (−9.0%) — **neutral** |
| VOD | LTE with dropouts | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 376679 ± 52228 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | −11679 (−3.1%) — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4479167 ± 93169 (p50 4500000, p95 4500000, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | +20833 (+0.5%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 250 ± 0 (p50 250, p95 250, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| short-form | congested WiFi | time to first frame (ms) | 1000 ± 0 (p50 1000, p95 1000, n=20) | 1000 ± 0 (p50 1000, p95 1000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 243 ± 34 (p50 250, p95 250, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | +7.500 (+3.1%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | 3G | time to first frame (ms) | 963 ± 727 (p50 800, p95 800, n=20) | 800 ± 0 (p50 800, p95 800, n=20) | −163 (−16.9%) — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 376679 ± 52228 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | −11679 (−3.1%) — **neutral** |
| VOD (data saver) | 3G | switch count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.050 (−100.0%) — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

### Against stock (naive tuning)

**Worse (47 of 108 comparisons):**

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +1.000 — **worse** |
| VOD | stable WiFi | QoE score | 4.47 ± 0.00 (p50 4.47, p95 4.47, n=20) | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) | −0.003 (−0.1%) — **worse** |
| VOD | congested WiFi | average bitrate (bit/s) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −211667 (−22.5%) — **worse** |
| VOD | congested WiFi | QoE score | 0.74 ± 0.00 (p50 0.74, p95 0.74, n=20) | 0.71 ± 0.00 (p50 0.71, p95 0.71, n=20) | −0.029 (−3.9%) — **worse** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1576667 ± 0 (p50 1576667, p95 1576667, n=20) | −423333 (−21.2%) — **worse** |
| VOD | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +4.000 — **worse** |
| VOD | LTE with dropouts | QoE score | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | 1.50 ± 0.00 (p50 1.50, p95 1.50, n=20) | −0.448 (−23.0%) — **worse** |
| VOD | 3G | time to first frame (ms) | 800 ± 0 (p50 800, p95 800, n=20) | 4050 ± 0 (p50 4050, p95 4050, n=20) | +3250 (+406.3%) — **worse** |
| VOD | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +1.000 — **worse** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 4083333 ± 0 (p50 4083333, p95 4083333, n=20) | 2416667 ± 0 (p50 2416667, p95 2416667, n=20) | −1666667 (−40.8%) — **worse** |
| VOD | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +1.000 (+100.0%) — **worse** |
| VOD | WiFi→cellular | QoE score | 4.03 ± 0.00 (p50 4.03, p95 4.03, n=20) | 2.34 ± 0.00 (p50 2.34, p95 2.34, n=20) | −1.687 (−41.9%) — **worse** |
| short-form | stable WiFi | rebuffer ratio | 0.0038 | 0.2803 | +0.277 (+7300.0%) — **worse** |
| short-form | stable WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 22.00 ± 0.00 (p50 22.00, p95 22.00, n=20) | +21.000 (+2100.0%) — **worse** |
| short-form | stable WiFi | QoE score | 1.99 ± 0.00 (p50 1.99, p95 1.99, n=20) | 1.22 ± 0.00 (p50 1.22, p95 1.22, n=20) | −0.771 (−38.7%) — **worse** |
| short-form | congested WiFi | rebuffer ratio | 0.0265 | 0.0530 | +0.027 (+100.0%) — **worse** |
| short-form | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +3.000 (+300.0%) — **worse** |
| short-form | congested WiFi | average bitrate (bit/s) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −211667 (−22.5%) — **worse** |
| short-form | congested WiFi | QoE score | 0.81 ± 0.00 (p50 0.81, p95 0.81, n=20) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=20) | −0.210 (−26.0%) — **worse** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0759 | +0.064 (+566.7%) — **worse** |
| short-form | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +3.000 (+300.0%) — **worse** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1788333 ± 0 (p50 1788333, p95 1788333, n=20) | −211667 (−10.6%) — **worse** |
| short-form | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +2.000 — **worse** |
| short-form | LTE with dropouts | QoE score | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) | 1.58 ± 0.00 (p50 1.58, p95 1.58, n=20) | −0.395 (−20.0%) — **worse** |
| short-form | 3G | time to first frame (ms) | 800 ± 0 (p50 800, p95 800, n=20) | 4050 ± 0 (p50 4050, p95 4050, n=20) | +3250 (+406.3%) — **worse** |
| short-form | 3G | rebuffer ratio | 0.0114 | 0.0336 | +0.022 (+195.5%) — **worse** |
| short-form | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | +2.000 (+200.0%) — **worse** |
| short-form | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +1.000 — **worse** |
| short-form | 3G | QoE score | 0.34 ± 0.00 (p50 0.34, p95 0.34, n=20) | 0.27 ± 0.00 (p50 0.27, p95 0.27, n=20) | −0.072 (−21.0%) — **worse** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0038 | 0.1364 | +0.133 (+3500.7%) — **worse** |
| short-form | WiFi→cellular | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 8.00 ± 0.00 (p50 8.00, p95 8.00, n=20) | +7.000 (+700.0%) — **worse** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1682500 ± 0 (p50 1682500, p95 1682500, n=20) | −317500 (−15.9%) — **worse** |
| short-form | WiFi→cellular | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +2.000 — **worse** |
| short-form | WiFi→cellular | QoE score | 1.99 ± 0.00 (p50 1.99, p95 1.99, n=20) | 1.32 ± 0.00 (p50 1.32, p95 1.32, n=20) | −0.670 (−33.6%) — **worse** |
| short-form | high latency | rebuffer ratio | 0.0114 | 0.4347 | +0.423 (+3719.3%) — **worse** |
| short-form | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 18.00 ± 0.00 (p50 18.00, p95 18.00, n=20) | +17.000 (+1700.0%) — **worse** |
| short-form | high latency | QoE score | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) | 0.46 ± 0.00 (p50 0.46, p95 0.46, n=20) | −1.515 (−76.6%) — **worse** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | stable WiFi | QoE score | 4.47 ± 0.00 (p50 4.47, p95 4.47, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −3.736 (−83.7%) — **worse** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −211667 (−22.5%) — **worse** |
| VOD (data saver) | congested WiFi | QoE score | 0.74 ± 0.00 (p50 0.74, p95 0.74, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −0.010 (−1.4%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | −1635000 (−81.8%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | −1.583 (−81.3%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 4083333 ± 0 (p50 4083333, p95 4083333, n=20) | 486667 ± 0 (p50 486667, p95 486667, n=20) | −3596667 (−88.1%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 4.03 ± 0.00 (p50 4.03, p95 4.03, n=20) | 0.48 ± 0.00 (p50 0.48, p95 0.48, n=20) | −3.547 (−88.1%) — **worse** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | high latency | QoE score | 4.35 ± 0.00 (p50 4.35, p95 4.35, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −3.624 (−83.2%) — **worse** |

**Neutral — inside the noise (22):**

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 1000 ± 0 (p50 1000, p95 1000, n=20) | 1000 ± 0 (p50 1000, p95 1000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 250 ± 0 (p50 250, p95 250, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 250 ± 0 (p50 250, p95 250, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| short-form | congested WiFi | time to first frame (ms) | 1000 ± 0 (p50 1000, p95 1000, n=20) | 1000 ± 0 (p50 1000, p95 1000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 250 ± 0 (p50 250, p95 250, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | 3G | time to first frame (ms) | 800 ± 0 (p50 800, p95 800, n=20) | 800 ± 0 (p50 800, p95 800, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

### Against SuperPlayer

**Worse (40 of 108 comparisons):**

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +1.000 — **worse** |
| VOD | stable WiFi | QoE score | 4.50 ± 0.00 (p50 4.50, p95 4.50, n=20) | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) | −0.038 (−0.8%) — **worse** |
| VOD | congested WiFi | average bitrate (bit/s) | 1153333 ± 0 (p50 1153333, p95 1153333, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −423333 (−36.7%) — **worse** |
| VOD | congested WiFi | QoE score | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.71 ± 0.00 (p50 0.71, p95 0.71, n=20) | −0.292 (−29.1%) — **worse** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1576667 ± 0 (p50 1576667, p95 1576667, n=20) | −423333 (−21.2%) — **worse** |
| VOD | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +4.000 — **worse** |
| VOD | LTE with dropouts | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 1.50 ± 0.00 (p50 1.50, p95 1.50, n=20) | −0.500 (−25.0%) — **worse** |
| VOD | 3G | time to first frame (ms) | 800 ± 0 (p50 800, p95 800, n=20) | 4050 ± 0 (p50 4050, p95 4050, n=20) | +3250 (+406.3%) — **worse** |
| VOD | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +1.000 — **worse** |
| VOD | 3G | QoE score | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | 0.34 ± 0.00 (p50 0.34, p95 0.34, n=20) | −0.024 (−6.6%) — **worse** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 3250000 ± 0 (p50 3250000, p95 3250000, n=20) | 2416667 ± 0 (p50 2416667, p95 2416667, n=20) | −833333 (−25.6%) — **worse** |
| VOD | WiFi→cellular | QoE score | 3.17 ± 0.00 (p50 3.17, p95 3.17, n=20) | 2.34 ± 0.00 (p50 2.34, p95 2.34, n=20) | −0.833 (−26.2%) — **worse** |
| short-form | stable WiFi | rebuffer ratio | 0.0152 | 0.2803 | +0.265 (+1750.0%) — **worse** |
| short-form | stable WiFi | rebuffer count | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 22.00 ± 0.00 (p50 22.00, p95 22.00, n=20) | +18.000 (+450.0%) — **worse** |
| short-form | stable WiFi | QoE score | 1.97 ± 0.00 (p50 1.97, p95 1.97, n=20) | 1.22 ± 0.00 (p50 1.22, p95 1.22, n=20) | −0.748 (−38.0%) — **worse** |
| short-form | congested WiFi | rebuffer ratio | 0.0455 | 0.0530 | +0.008 (+16.7%) — **worse** |
| short-form | congested WiFi | rebuffer count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +1.000 (+33.3%) — **worse** |
| short-form | congested WiFi | average bitrate (bit/s) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −211667 (−22.5%) — **worse** |
| short-form | congested WiFi | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=20) | −0.128 (−17.6%) — **worse** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0417 | 0.0759 | +0.034 (+81.8%) — **worse** |
| short-form | LTE with dropouts | rebuffer count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +1.000 (+33.3%) — **worse** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1788333 ± 0 (p50 1788333, p95 1788333, n=20) | −211667 (−10.6%) — **worse** |
| short-form | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +2.000 — **worse** |
| short-form | LTE with dropouts | QoE score | 1.91 ± 0.00 (p50 1.91, p95 1.91, n=20) | 1.58 ± 0.00 (p50 1.58, p95 1.58, n=20) | −0.330 (−17.3%) — **worse** |
| short-form | 3G | time to first frame (ms) | 800 ± 0 (p50 800, p95 800, n=20) | 4050 ± 0 (p50 4050, p95 4050, n=20) | +3250 (+406.3%) — **worse** |
| short-form | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +1.000 — **worse** |
| short-form | 3G | QoE score | 0.29 ± 0.00 (p50 0.29, p95 0.29, n=20) | 0.27 ± 0.00 (p50 0.27, p95 0.27, n=20) | −0.024 (−8.2%) — **worse** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0341 | 0.1364 | +0.102 (+300.1%) — **worse** |
| short-form | WiFi→cellular | rebuffer count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 8.00 ± 0.00 (p50 8.00, p95 8.00, n=20) | +5.000 (+166.7%) — **worse** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1682500 ± 0 (p50 1682500, p95 1682500, n=20) | −317500 (−15.9%) — **worse** |
| short-form | WiFi→cellular | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +2.000 — **worse** |
| short-form | WiFi→cellular | QoE score | 1.93 ± 0.00 (p50 1.93, p95 1.93, n=20) | 1.32 ± 0.00 (p50 1.32, p95 1.32, n=20) | −0.607 (−31.5%) — **worse** |
| short-form | high latency | rebuffer ratio | 0.0417 | 0.4347 | +0.393 (+941.8%) — **worse** |
| short-form | high latency | rebuffer count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 18.00 ± 0.00 (p50 18.00, p95 18.00, n=20) | +15.000 (+500.0%) — **worse** |
| short-form | high latency | QoE score | 1.91 ± 0.00 (p50 1.91, p95 1.91, n=20) | 0.46 ± 0.00 (p50 0.46, p95 0.46, n=20) | −1.451 (−75.9%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | −365000 (−50.0%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | −0.365 (−50.0%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 486667 ± 0 (p50 486667, p95 486667, n=20) | −243333 (−33.3%) — **worse** |
| VOD (data saver) | WiFi→cellular | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +1.000 — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.48 ± 0.00 (p50 0.48, p95 0.48, n=20) | −0.249 (−34.1%) — **worse** |

**Neutral — inside the noise (60):**

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 1000 ± 0 (p50 1000, p95 1000, n=20) | 1000 ± 0 (p50 1000, p95 1000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | LTE with dropouts | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 250 ± 0 (p50 250, p95 250, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | WiFi→cellular | switch count | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 250 ± 0 (p50 250, p95 250, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| short-form | congested WiFi | time to first frame (ms) | 1000 ± 0 (p50 1000, p95 1000, n=20) | 1000 ± 0 (p50 1000, p95 1000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | rebuffer count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 250 ± 0 (p50 250, p95 250, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 150 ± 0 (p50 150, p95 150, n=20) | 150 ± 0 (p50 150, p95 150, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 400 ± 0 (p50 400, p95 400, n=20) | 400 ± 0 (p50 400, p95 400, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | congested WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | 3G | time to first frame (ms) | 800 ± 0 (p50 800, p95 800, n=20) | 800 ± 0 (p50 800, p95 800, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | 3G | QoE score | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 150 ± 0 (p50 150, p95 150, n=20) | 150 ± 0 (p50 150, p95 150, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | high latency | time to first frame (ms) | 750 ± 0 (p50 750, p95 750, n=20) | 750 ± 0 (p50 750, p95 750, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | high latency | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | high latency | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | +0.000 (+0.0%) — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

## Where the adaptive policy is better

### Against stock (defaults)

| Scenario | Network | Metric | stock (defaults) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 480 ± 89 (p50 500, p95 500, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | −230 (−47.9%) — **better** |
| VOD | stable WiFi | rebuffer ratio | 0.2508 | 0.0000 | −0.251 (−100.0%) — **better** |
| VOD | stable WiFi | rebuffer count | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.950 (−100.0%) — **better** |
| VOD | stable WiFi | QoE score | 2.95 ± 0.10 (p50 2.97, p95 2.97, n=20) | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) | +1.515 (+51.4%) — **better** |
| VOD | congested WiFi | rebuffer ratio | 0.0263 | 0.0000 | −0.026 (−100.0%) — **better** |
| VOD | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | congested WiFi | switch count | 4.05 ± 0.22 (p50 4.00, p95 4.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −3.050 (−75.3%) — **better** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | 3G | rebuffer ratio | 0.0138 | 0.0000 | −0.014 (−100.0%) — **better** |
| VOD | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | 3G | QoE score | 0.31 ± 0.01 (p50 0.31, p95 0.31, n=20) | 0.34 ± 0.00 (p50 0.34, p95 0.34, n=20) | +0.029 (+9.3%) — **better** |
| VOD | WiFi→cellular | time to first frame (ms) | 368 ± 140 (p50 250, p95 500, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | −118 (−32.0%) — **better** |
| VOD | WiFi→cellular | rebuffer ratio | 0.1345 | 0.0000 | −0.134 (−100.0%) — **better** |
| VOD | WiFi→cellular | rebuffer count | 1.45 ± 0.51 (p50 1.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.450 (−100.0%) — **better** |
| VOD | high latency | time to first frame (ms) | 1088 ± 56 (p50 1100, p95 1100, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | −238 (−21.8%) — **better** |
| VOD | high latency | rebuffer ratio | 0.0150 | 0.0000 | −0.015 (−100.0%) — **better** |
| VOD | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | high latency | switch count | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −0.950 (−48.7%) — **better** |
| VOD | high latency | QoE score | 4.34 ± 0.08 (p50 4.35, p95 4.35, n=20) | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) | +0.127 (+2.9%) — **better** |
| short-form | congested WiFi | switch count | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −3.000 (−75.0%) — **better** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 500 ± 0 (p50 500, p95 500, n=20) | 150 ± 0 (p50 150, p95 150, n=20) | −350 (−70.0%) — **better** |
| VOD (data saver) | stable WiFi | rebuffer ratio | 0.2538 | 0.0000 | −0.254 (−100.0%) — **better** |
| VOD (data saver) | stable WiFi | rebuffer count | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −2.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 1000 ± 0 (p50 1000, p95 1000, n=20) | 400 ± 0 (p50 400, p95 400, n=20) | −600 (−60.0%) — **better** |
| VOD (data saver) | congested WiFi | rebuffer ratio | 0.0265 | 0.0000 | −0.027 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | switch count | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −4.000 (−100.0%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 200 ± 0 (p50 200, p95 200, n=20) | −650 (−76.5%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0138 | 0.0000 | −0.014 (−100.0%) — **better** |
| VOD (data saver) | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | QoE score | 0.31 ± 0.01 (p50 0.31, p95 0.31, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | +0.053 (+17.0%) — **better** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 368 ± 140 (p50 250, p95 500, n=20) | 150 ± 0 (p50 150, p95 150, n=20) | −218 (−59.2%) — **better** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.1345 | 0.0000 | −0.134 (−100.0%) — **better** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 1.45 ± 0.51 (p50 1.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.450 (−100.0%) — **better** |
| VOD (data saver) | high latency | time to first frame (ms) | 1088 ± 56 (p50 1100, p95 1100, n=20) | 750 ± 0 (p50 750, p95 750, n=20) | −338 (−31.0%) — **better** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0150 | 0.0000 | −0.015 (−100.0%) — **better** |
| VOD (data saver) | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | switch count | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.950 (−100.0%) — **better** |

### Against stock (naive tuning)

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 500 ± 0 (p50 500, p95 500, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | −250 (−50.0%) — **better** |
| VOD | stable WiFi | rebuffer ratio | 0.0076 | 0.0000 | −0.008 (−100.0%) — **better** |
| VOD | stable WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | congested WiFi | rebuffer ratio | 0.0265 | 0.0000 | −0.027 (−100.0%) — **better** |
| VOD | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | congested WiFi | switch count | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −3.000 (−75.0%) — **better** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | 3G | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | 3G | QoE score | 0.31 ± 0.00 (p50 0.31, p95 0.31, n=20) | 0.34 ± 0.00 (p50 0.34, p95 0.34, n=20) | +0.028 (+8.8%) — **better** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0038 | 0.0000 | −0.004 (−100.0%) — **better** |
| VOD | WiFi→cellular | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | high latency | time to first frame (ms) | 1100 ± 0 (p50 1100, p95 1100, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | −250 (−22.7%) — **better** |
| VOD | high latency | rebuffer ratio | 0.0152 | 0.0000 | −0.015 (−100.0%) — **better** |
| VOD | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | high latency | switch count | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −1.000 (−50.0%) — **better** |
| VOD | high latency | QoE score | 4.35 ± 0.00 (p50 4.35, p95 4.35, n=20) | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) | +0.109 (+2.5%) — **better** |
| short-form | congested WiFi | switch count | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −3.000 (−75.0%) — **better** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 500 ± 0 (p50 500, p95 500, n=20) | 150 ± 0 (p50 150, p95 150, n=20) | −350 (−70.0%) — **better** |
| VOD (data saver) | stable WiFi | rebuffer ratio | 0.0076 | 0.0000 | −0.008 (−100.0%) — **better** |
| VOD (data saver) | stable WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 1000 ± 0 (p50 1000, p95 1000, n=20) | 400 ± 0 (p50 400, p95 400, n=20) | −600 (−60.0%) — **better** |
| VOD (data saver) | congested WiFi | rebuffer ratio | 0.0265 | 0.0000 | −0.027 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | switch count | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −4.000 (−100.0%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 850 ± 0 (p50 850, p95 850, n=20) | 200 ± 0 (p50 200, p95 200, n=20) | −650 (−76.5%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD (data saver) | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | QoE score | 0.31 ± 0.00 (p50 0.31, p95 0.31, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | +0.052 (+16.5%) — **better** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 250 ± 0 (p50 250, p95 250, n=20) | 150 ± 0 (p50 150, p95 150, n=20) | −100 (−40.0%) — **better** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0038 | 0.0000 | −0.004 (−100.0%) — **better** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | time to first frame (ms) | 1100 ± 0 (p50 1100, p95 1100, n=20) | 750 ± 0 (p50 750, p95 750, n=20) | −350 (−31.8%) — **better** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0152 | 0.0000 | −0.015 (−100.0%) — **better** |
| VOD (data saver) | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | switch count | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −2.000 (−100.0%) — **better** |

### Against SuperPlayer

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 500 ± 0 (p50 500, p95 500, n=20) | 250 ± 0 (p50 250, p95 250, n=20) | −250 (−50.0%) — **better** |
| VOD | congested WiFi | switch count | 8.00 ± 0.00 (p50 8.00, p95 8.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −7.000 (−87.5%) — **better** |
| VOD | high latency | time to first frame (ms) | 1100 ± 0 (p50 1100, p95 1100, n=20) | 850 ± 0 (p50 850, p95 850, n=20) | −250 (−22.7%) — **better** |
| VOD | high latency | switch count | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −1.000 (−50.0%) — **better** |
| VOD | high latency | QoE score | 4.43 ± 0.00 (p50 4.43, p95 4.43, n=20) | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) | +0.037 (+0.8%) — **better** |
| short-form | congested WiFi | switch count | 6.00 ± 0.00 (p50 6.00, p95 6.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −5.000 (−83.3%) — **better** |
| short-form | 3G | rebuffer ratio | 0.0341 | 0.0336 | −0.001 (−1.5%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 350 ± 0 (p50 350, p95 350, n=20) | 200 ± 0 (p50 200, p95 200, n=20) | −150 (−42.9%) — **better** |

## The F1 trade, as a loss

`PRD.md` F1's trade is *lower bitrate on a constrained link, in exchange for fewer stalls*, and §6 requires it to appear **as a bitrate loss beside the rebuffer win** rather than as a rebuffer win on its own. In the Phase 1 baseline the only instance was `DATA_SAVER`'s static cap. The adaptive policy makes the trade on purpose — `TransportCaps` narrows the eligible ladder on cellular, and the estimate discounts a rung by its spread — so it is shown here on every cellular cell, SuperPlayer (adaptive) against SuperPlayer, with the bitrate row and the stall rows side by side and each verdict by the same rule as everywhere else. A bitrate cut with no stall win beside it is a trade that bought nothing, and reads that way below.

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | QoE score | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | 0.34 ± 0.00 (p50 0.34, p95 0.34, n=20) | −0.024 (−6.6%) — **worse** |
| short-form | 3G | rebuffer ratio | 0.0341 | 0.0336 | −0.001 (−1.5%) — **better** |
| short-form | 3G | rebuffer count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | QoE score | 0.29 ± 0.00 (p50 0.29, p95 0.29, n=20) | 0.27 ± 0.00 (p50 0.27, p95 0.27, n=20) | −0.024 (−8.2%) — **worse** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | QoE score | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1576667 ± 0 (p50 1576667, p95 1576667, n=20) | −423333 (−21.2%) — **worse** |
| VOD | LTE with dropouts | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 1.50 ± 0.00 (p50 1.50, p95 1.50, n=20) | −0.500 (−25.0%) — **worse** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0417 | 0.0759 | +0.034 (+81.8%) — **worse** |
| short-form | LTE with dropouts | rebuffer count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | +1.000 (+33.3%) — **worse** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1788333 ± 0 (p50 1788333, p95 1788333, n=20) | −211667 (−10.6%) — **worse** |
| short-form | LTE with dropouts | QoE score | 1.91 ± 0.00 (p50 1.91, p95 1.91, n=20) | 1.58 ± 0.00 (p50 1.58, p95 1.58, n=20) | −0.330 (−17.3%) — **worse** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | −365000 (−50.0%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) | −0.365 (−50.0%) — **worse** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 3250000 ± 0 (p50 3250000, p95 3250000, n=20) | 2416667 ± 0 (p50 2416667, p95 2416667, n=20) | −833333 (−25.6%) — **worse** |
| VOD | WiFi→cellular | QoE score | 3.17 ± 0.00 (p50 3.17, p95 3.17, n=20) | 2.34 ± 0.00 (p50 2.34, p95 2.34, n=20) | −0.833 (−26.2%) — **worse** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0341 | 0.1364 | +0.102 (+300.1%) — **worse** |
| short-form | WiFi→cellular | rebuffer count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 8.00 ± 0.00 (p50 8.00, p95 8.00, n=20) | +5.000 (+166.7%) — **worse** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 1682500 ± 0 (p50 1682500, p95 1682500, n=20) | −317500 (−15.9%) — **worse** |
| short-form | WiFi→cellular | QoE score | 1.93 ± 0.00 (p50 1.93, p95 1.93, n=20) | 1.32 ± 0.00 (p50 1.32, p95 1.32, n=20) | −0.607 (−31.5%) — **worse** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 486667 ± 0 (p50 486667, p95 486667, n=20) | −243333 (−33.3%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | 0.48 ± 0.00 (p50 0.48, p95 0.48, n=20) | −0.249 (−34.1%) — **worse** |

## Every cell

All of it, whatever it says. Peak RSS and battery are the device arm's and are not in these tables; see below.

### VOD

Profile for arms (c) and (d): `VIDEO_ON_DEMAND`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s. Session length: 60 s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 20 | 500 / 500 (mean 480 ± 89, n=20) | 0.2508 | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 4458650 ± 184923 (p50 4500000, p95 4500000, n=20) | 0.10 ± 0.45 (p50 0.00, p95 0.00, n=20) | 0.0% | 2.95 ± 0.10 (p50 2.97, p95 2.97, n=20) |
| stable WiFi | stock (naive tuning) | 20 | 500 / 500 (mean 500 ± 0, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 4.47 ± 0.00 (p50 4.47, p95 4.47, n=20) |
| stable WiFi | SuperPlayer | 20 | 500 / 500 (mean 500 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 4.50 ± 0.00 (p50 4.50, p95 4.50, n=20) |
| stable WiFi | SuperPlayer (adaptive) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) |
| congested WiFi | stock (defaults) | 20 | 1000 / 1000 (mean 1093 ± 414, n=20) | 0.0263 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 984000 ± 189320 (p50 941667, p95 941667, n=20) | 4.05 ± 0.22 (p50 4.00, p95 4.00, n=20) | 0.0% | 0.78 ± 0.18 (p50 0.74, p95 0.74, n=20) |
| congested WiFi | stock (naive tuning) | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0265 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.0% | 0.74 ± 0.00 (p50 0.74, p95 0.74, n=20) |
| congested WiFi | SuperPlayer | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1153333 ± 0 (p50 1153333, p95 1153333, n=20) | 8.00 ± 0.00 (p50 8.00, p95 8.00, n=20) | 0.0% | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) |
| congested WiFi | SuperPlayer (adaptive) | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 0.71 ± 0.00 (p50 0.71, p95 0.71, n=20) |
| LTE with dropouts | stock (defaults) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | stock (naive tuning) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | SuperPlayer | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) |
| LTE with dropouts | SuperPlayer (adaptive) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1576667 ± 0 (p50 1576667, p95 1576667, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.0% | 1.50 ± 0.00 (p50 1.50, p95 1.50, n=20) |
| 3G | stock (defaults) | 20 | 800 / 800 (mean 963 ± 727, n=20) | 0.0138 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 376679 ± 52228 (p50 365000, p95 365000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.31 ± 0.01 (p50 0.31, p95 0.31, n=20) |
| 3G | stock (naive tuning) | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.31 ± 0.00 (p50 0.31, p95 0.31, n=20) |
| 3G | SuperPlayer | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) |
| 3G | SuperPlayer (adaptive) | 20 | 4050 / 4050 (mean 4050 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 0.34 ± 0.00 (p50 0.34, p95 0.34, n=20) |
| WiFi→cellular | stock (defaults) | 20 | 250 / 500 (mean 368 ± 140, n=20) | 0.1345 | 1.45 ± 0.51 (p50 1.00, p95 2.00, n=20) | 4177400 ± 333888 (p50 3875000, p95 4500000, n=20) | 0.55 ± 0.60 (p50 0.00, p95 1.00, n=20) | 0.0% | 3.33 ± 1.16 (p50 2.52, p95 4.47, n=20) |
| WiFi→cellular | stock (naive tuning) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4083333 ± 0 (p50 4083333, p95 4083333, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 4.03 ± 0.00 (p50 4.03, p95 4.03, n=20) |
| WiFi→cellular | SuperPlayer | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 3250000 ± 0 (p50 3250000, p95 3250000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 3.17 ± 0.00 (p50 3.17, p95 3.17, n=20) |
| WiFi→cellular | SuperPlayer (adaptive) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2416667 ± 0 (p50 2416667, p95 2416667, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 2.34 ± 0.00 (p50 2.34, p95 2.34, n=20) |
| high latency | stock (defaults) | 20 | 1100 / 1100 (mean 1088 ± 56, n=20) | 0.0150 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4479167 ± 93169 (p50 4500000, p95 4500000, n=20) | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 0.0% | 4.34 ± 0.08 (p50 4.35, p95 4.35, n=20) |
| high latency | stock (naive tuning) | 20 | 1100 / 1100 (mean 1100 ± 0, n=20) | 0.0152 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 4.35 ± 0.00 (p50 4.35, p95 4.35, n=20) |
| high latency | SuperPlayer | 20 | 1100 / 1100 (mean 1100 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 4.43 ± 0.00 (p50 4.43, p95 4.43, n=20) |
| high latency | SuperPlayer (adaptive) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 4.46 ± 0.00 (p50 4.46, p95 4.46, n=20) |

### live

Profile for arms (c) and (d): `LIVE_LINEAR`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s. Session length: 60 s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 20 | 500 / 500 (mean 500 ± 0, n=20) | 0.2538 | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 2.97 ± 0.00 (p50 2.97, p95 2.97, n=20) |
| stable WiFi | stock (naive tuning) | 20 | 500 / 500 (mean 500 ± 0, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 4.47 ± 0.00 (p50 4.47, p95 4.47, n=20) |
| stable WiFi | SuperPlayer | 20 | 500 / 500 (mean 500 ± 0, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 4.43 ± 0.00 (p50 4.43, p95 4.43, n=20) |
| congested WiFi | stock (defaults) | 20 | 1000 / 1000 (mean 1093 ± 414, n=20) | 0.0263 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 984000 ± 189320 (p50 941667, p95 941667, n=20) | 4.05 ± 0.22 (p50 4.00, p95 4.00, n=20) | 0.0% | 0.78 ± 0.18 (p50 0.74, p95 0.74, n=20) |
| congested WiFi | stock (naive tuning) | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0265 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.0% | 0.74 ± 0.00 (p50 0.74, p95 0.74, n=20) |
| congested WiFi | SuperPlayer | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0265 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 10.00 ± 0.00 (p50 10.00, p95 10.00, n=20) | 0.0% | 0.62 ± 0.00 (p50 0.62, p95 0.62, n=20) |
| LTE with dropouts | stock (defaults) | 20 | 850 / 850 (mean 848 ± 11, n=20) | 0.0116 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | stock (naive tuning) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | SuperPlayer | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| 3G | stock (defaults) | 20 | 800 / 800 (mean 963 ± 727, n=20) | 0.0119 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 367607 ± 11659 (p50 365000, p95 365000, n=20) | 0.10 ± 0.45 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.31 ± 0.01 (p50 0.31, p95 0.31, n=20) |
| 3G | stock (naive tuning) | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.31 ± 0.00 (p50 0.31, p95 0.31, n=20) |
| 3G | SuperPlayer | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.31 ± 0.00 (p50 0.31, p95 0.31, n=20) |
| WiFi→cellular | stock (defaults) | 20 | 250 / 500 (mean 368 ± 140, n=20) | 0.1345 | 1.45 ± 0.51 (p50 1.00, p95 2.00, n=20) | 4177400 ± 333888 (p50 3875000, p95 4500000, n=20) | 0.55 ± 0.60 (p50 0.00, p95 1.00, n=20) | 0.0% | 3.33 ± 1.16 (p50 2.52, p95 4.47, n=20) |
| WiFi→cellular | stock (naive tuning) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4083333 ± 0 (p50 4083333, p95 4083333, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 4.03 ± 0.00 (p50 4.03, p95 4.03, n=20) |
| WiFi→cellular | SuperPlayer | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 3250000 ± 0 (p50 3250000, p95 3250000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 3.16 ± 0.00 (p50 3.16, p95 3.16, n=20) |
| high latency | stock (defaults) | 20 | 1100 / 1100 (mean 1088 ± 56, n=20) | 0.0150 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4479167 ± 93169 (p50 4500000, p95 4500000, n=20) | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 0.0% | 4.34 ± 0.08 (p50 4.35, p95 4.35, n=20) |
| high latency | stock (naive tuning) | 20 | 1100 / 1100 (mean 1100 ± 0, n=20) | 0.0152 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 4.35 ± 0.00 (p50 4.35, p95 4.35, n=20) |
| high latency | SuperPlayer | 20 | 1100 / 1100 (mean 1100 ± 0, n=20) | 0.0152 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 4.35 ± 0.00 (p50 4.35, p95 4.35, n=20) |

### short-form

Profile for arms (c) and (d): `SHORT_FORM`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s. Session length: 60 s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0568 | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.88 ± 0.00 (p50 1.88, p95 1.88, n=20) |
| stable WiFi | stock (naive tuning) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.99 ± 0.00 (p50 1.99, p95 1.99, n=20) |
| stable WiFi | SuperPlayer | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0152 | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.97 ± 0.00 (p50 1.97, p95 1.97, n=20) |
| stable WiFi | SuperPlayer (adaptive) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.2803 | 22.00 ± 0.00 (p50 22.00, p95 22.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.22 ± 0.00 (p50 1.22, p95 1.22, n=20) |
| congested WiFi | stock (defaults) | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0265 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.0% | 0.81 ± 0.00 (p50 0.81, p95 0.81, n=20) |
| congested WiFi | stock (naive tuning) | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0265 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.0% | 0.81 ± 0.00 (p50 0.81, p95 0.81, n=20) |
| congested WiFi | SuperPlayer | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0455 | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 6.00 ± 0.00 (p50 6.00, p95 6.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| congested WiFi | SuperPlayer (adaptive) | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0530 | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=20) |
| LTE with dropouts | stock (defaults) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) |
| LTE with dropouts | stock (naive tuning) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) |
| LTE with dropouts | SuperPlayer | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0417 | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.91 ± 0.00 (p50 1.91, p95 1.91, n=20) |
| LTE with dropouts | SuperPlayer (adaptive) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0759 | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 1788333 ± 0 (p50 1788333, p95 1788333, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 1.58 ± 0.00 (p50 1.58, p95 1.58, n=20) |
| 3G | stock (defaults) | 20 | 800 / 800 (mean 960 ± 727, n=20) | 0.0119 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.10 ± 0.45 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.34 ± 0.01 (p50 0.34, p95 0.34, n=20) |
| 3G | stock (naive tuning) | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.34 ± 0.00 (p50 0.34, p95 0.34, n=20) |
| 3G | SuperPlayer | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0341 | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.29 ± 0.00 (p50 0.29, p95 0.29, n=20) |
| 3G | SuperPlayer (adaptive) | 20 | 4050 / 4050 (mean 4050 ± 0, n=20) | 0.0336 | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 0.27 ± 0.00 (p50 0.27, p95 0.27, n=20) |
| WiFi→cellular | stock (defaults) | 20 | 250 / 250 (mean 243 ± 34, n=20) | 0.0568 | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 1986375 ± 60933 (p50 2000000, p95 2000000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.86 ± 0.07 (p50 1.88, p95 1.88, n=20) |
| WiFi→cellular | stock (naive tuning) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.99 ± 0.00 (p50 1.99, p95 1.99, n=20) |
| WiFi→cellular | SuperPlayer | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0341 | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.93 ± 0.00 (p50 1.93, p95 1.93, n=20) |
| WiFi→cellular | SuperPlayer (adaptive) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.1364 | 8.00 ± 0.00 (p50 8.00, p95 8.00, n=20) | 1682500 ± 0 (p50 1682500, p95 1682500, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 1.32 ± 0.00 (p50 1.32, p95 1.32, n=20) |
| high latency | stock (defaults) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) |
| high latency | stock (naive tuning) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) |
| high latency | SuperPlayer | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0417 | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.91 ± 0.00 (p50 1.91, p95 1.91, n=20) |
| high latency | SuperPlayer (adaptive) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.4347 | 18.00 ± 0.00 (p50 18.00, p95 18.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.46 ± 0.00 (p50 0.46, p95 0.46, n=20) |

### VOD (data saver)

Profile for arms (c) and (d): `DATA_SAVER`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s. Session length: 60 s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 20 | 500 / 500 (mean 500 ± 0, n=20) | 0.2538 | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 2.97 ± 0.00 (p50 2.97, p95 2.97, n=20) |
| stable WiFi | stock (naive tuning) | 20 | 500 / 500 (mean 500 ± 0, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 4.47 ± 0.00 (p50 4.47, p95 4.47, n=20) |
| stable WiFi | SuperPlayer | 20 | 150 / 150 (mean 150 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| stable WiFi | SuperPlayer (adaptive) | 20 | 150 / 150 (mean 150 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| congested WiFi | stock (defaults) | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0265 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.0% | 0.74 ± 0.00 (p50 0.74, p95 0.74, n=20) |
| congested WiFi | stock (naive tuning) | 20 | 1000 / 1000 (mean 1000 ± 0, n=20) | 0.0265 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 941667 ± 0 (p50 941667, p95 941667, n=20) | 4.00 ± 0.00 (p50 4.00, p95 4.00, n=20) | 0.0% | 0.74 ± 0.00 (p50 0.74, p95 0.74, n=20) |
| congested WiFi | SuperPlayer | 20 | 400 / 400 (mean 400 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| congested WiFi | SuperPlayer (adaptive) | 20 | 400 / 400 (mean 400 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| LTE with dropouts | stock (defaults) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | stock (naive tuning) | 20 | 850 / 850 (mean 850 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | SuperPlayer | 20 | 350 / 350 (mean 350 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| LTE with dropouts | SuperPlayer (adaptive) | 20 | 200 / 200 (mean 200 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) |
| 3G | stock (defaults) | 20 | 800 / 800 (mean 963 ± 727, n=20) | 0.0138 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 376679 ± 52228 (p50 365000, p95 365000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.31 ± 0.01 (p50 0.31, p95 0.31, n=20) |
| 3G | stock (naive tuning) | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.31 ± 0.00 (p50 0.31, p95 0.31, n=20) |
| 3G | SuperPlayer | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) |
| 3G | SuperPlayer (adaptive) | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.37 ± 0.00 (p50 0.37, p95 0.37, n=20) |
| WiFi→cellular | stock (defaults) | 20 | 250 / 500 (mean 368 ± 140, n=20) | 0.1345 | 1.45 ± 0.51 (p50 1.00, p95 2.00, n=20) | 4177400 ± 333888 (p50 3875000, p95 4500000, n=20) | 0.55 ± 0.60 (p50 0.00, p95 1.00, n=20) | 0.0% | 3.33 ± 1.16 (p50 2.52, p95 4.47, n=20) |
| WiFi→cellular | stock (naive tuning) | 20 | 250 / 250 (mean 250 ± 0, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4083333 ± 0 (p50 4083333, p95 4083333, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 4.03 ± 0.00 (p50 4.03, p95 4.03, n=20) |
| WiFi→cellular | SuperPlayer | 20 | 150 / 150 (mean 150 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| WiFi→cellular | SuperPlayer (adaptive) | 20 | 150 / 150 (mean 150 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 486667 ± 0 (p50 486667, p95 486667, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.0% | 0.48 ± 0.00 (p50 0.48, p95 0.48, n=20) |
| high latency | stock (defaults) | 20 | 1100 / 1100 (mean 1088 ± 56, n=20) | 0.0150 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4479167 ± 93169 (p50 4500000, p95 4500000, n=20) | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 0.0% | 4.34 ± 0.08 (p50 4.35, p95 4.35, n=20) |
| high latency | stock (naive tuning) | 20 | 1100 / 1100 (mean 1100 ± 0, n=20) | 0.0152 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 4.35 ± 0.00 (p50 4.35, p95 4.35, n=20) |
| high latency | SuperPlayer | 20 | 750 / 750 (mean 750 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| high latency | SuperPlayer (adaptive) | 20 | 750 / 750 (mean 750 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |

## Peak RSS and battery — the device arm

`PRD.md` §6 reports peak RSS and battery delta over a 30-minute session, and **neither is in the tables above**. They cannot be: both are properties of a process on a device with a battery, and everything above runs under Robolectric on a JVM. Reporting a heap figure from a JVM as though it were an Android app's resident set would be a plausible wrong number, which is the failure mode this whole harness is arranged against.

The device arm is `benchmark/`'s own app, `BenchmarkActivity`: given an arm, one of the public streams below and a duration, it plays a 30-minute session on a real device over a real network and writes its telemetry in the same trace format as the rows above. It measures neither number itself — an app measuring its own memory would be measuring the measurement — and both are read off the process from outside, by Perfetto's `process_memory` and `battery` data sources.

**The harness around that app is not built yet.** `devicelab/` is this repository's entry point for device measurement and is currently shaped around the demo; wiring a second app through it is issue #95. `benchmark/README.md` has the manual `adb` recipe in the meantime, and says why a benchmark sits outside `docs/testing.md`'s no-device rule rather than against it.

**These columns are unpopulated in this report.** A device run has not been taken against this commit, and a table of dashes is the honest way to say so — the alternative being to leave the metrics out of the document and let a reader assume the matrix covered them.

| Metric | Arm (a) | Arm (b) | Arm (c) | Arm (d) |
| --- | --- | --- | --- | --- |
| Peak RSS (MiB) | — | — | — | — |
| Battery delta over 30 min (%) | — | — | — | — |

## The content, and where it comes from

**The Robolectric arm plays generated content**, not fetched streams: rendition ladders synthesized by `superplayer-testkit`'s `PlaybackHarness` over Media3's own adaptive fakes, at the bitrates each scenario names. That is the arm that has to be reproducible, and a stream fetched over the internet is the one thing that cannot be — the same reasoning `docs/testing.md` gives for barring the network from the test suite. The ladders follow Apple's HLS Authoring Specification in shape; the exact rungs are chosen against `PRD.md` §6's network profiles, and `Scenario.kt` argues them. The scenarios, the streams and the network profiles are unchanged from the Phase 1 baseline, so the two reports describe one matrix.

**The device arm plays public streams**, each listed with the page that publishes it:

| Content | Protocol | URI | Source |
| --- | --- | --- | --- |
| VOD, HLS | HLS | `https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8` | https://developer.apple.com/streaming/examples/ |
| VOD, DASH | DASH | `https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd` | https://github.com/androidx/media/blob/release/demos/main/src/main/assets/media.exolist.json |

### Cells this matrix does not cover

Written down rather than left out. An uncovered cell that is absent from a table reads as a cell that passed.

**Widevine (DRM) content, every network profile and every arm**

DRM is Phase 6. `superplayer-drm` is an empty placeholder module today, so arm (c) would be a SuperPlayer with no DRM behaviour of its own and the row would compare three players that are, on this axis, the same player. Public Widevine test content exists — the CENC counterpart of the DASH stream above, at `https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd` — and nothing here fetches it.

_Closed by:_ Phase 6, with `superplayer-drm`. The row is worth measuring when there is a security-level ladder and a provisioning path to measure, and its interesting metric is licence-acquisition time, which is not in this matrix yet.

**Live content, device arm**

The Robolectric arm covers live — `Scenario.LIVE`, over a synthetic window with a moving edge — so live is measured, and this gap is the device half of it only. It is open because a live URL is a claim that something is still publishing right now, and `docs/testing.md` bars this repository's own checks from the network, so nothing here can verify one. Vendoring a live URL that had quietly stopped would produce a device row of startup failures that looked like a finding.

_Closed by:_ A public live stream verified by a device run, added to `streams` with its source. The device arm's harness needs no change to take one.

**Live content, SuperPlayer (adaptive), every network profile**

Under the harness, the adaptive policy on the synthetic live ladder keeps the engine working without the clock moving, and the session neither starts nor fails — while the static profile and both stock arms play the same window. The adaptive policy is the only arm that lays a live playback-speed range into the item, over a window dated against a wall clock Robolectric does not move; whether that is a harness limitation or a library defect is not yet known. So the adaptive policy's live branch has no number in this report, and the exit criterion is judged on the other three scenarios.

_Closed by:_ issue #144, then re-taking this report.

## Raw traces

`PRD.md` §6 requires them published, because a summary is an argument and a trace is evidence: every number above can be recomputed from these files, and a statistic this report did not think to print can be taken from them.

One JSONL file per cell in `traces/`, one line per telemetry event, field names as `LogcatSink` writes them. Each line carries its scenario, network, arm and run index, so files concatenate without losing what they were. Arms (a) to (c) keep the Phase 1 baseline's file names, so `diff` against `../baseline/traces/` compares a cell with itself.

```text
grep '"evt":"rebuffer_ended"' traces/*.jsonl                  # every stall in the matrix
grep '"run":3' traces/vod__3g__superplayer-adaptive.jsonl       # one session, end to end
```

90 cell(s), 1800 aggregated session(s).

