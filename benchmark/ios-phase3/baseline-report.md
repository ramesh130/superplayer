# SuperPlayer benchmark — iOS (simulator)

The fixed matrix of [`PRD.md`](../../PRD.md) §6 on the iOS simulator, run by `ios/Benchmark/bench` over testkit's loopback origin and network shaper, with **SuperPlayer (adaptive)** as the arm under test. Every number is produced by this harness against content this repository generates (§0.2); none is carried in from anywhere else, and none is copied from the Android reports under `benchmark/`, which measure a different engine on a fake clock and are not comparable to these cell for cell.

## Exit criterion

Phase 11's criterion — *measured improvement of the adaptive policy over the static profile on the shaped-network suite, with no regression on stable WiFi* — judged by `ExitCriterion.swift`'s rule, fixed before this run: **SuperPlayer (adaptive)** against **SuperPlayer**, a QoE score better in at least one scenario and worse in none on each shaped profile, and no metric worse on stable WiFi.

**Not met.** The criterion fails on: LTE with dropouts, 3G, WiFi→cellular, high latency.

| Network | Judged on | Verdict | QoE better in | Worse in |
| --- | --- | --- | --- | --- |
| stable WiFi | every metric | met | — | — |
| congested WiFi | QoE score | met | short-form | — |
| LTE with dropouts | QoE score | **not met** | — | VOD: QoE score (worse) |
| 3G | QoE score | **not met** | — | — |
| WiFi→cellular | QoE score | **not met** | — | — |
| high latency | QoE score | **not met** | — | — |

## What this was measured on

| | |
| --- | --- |
| SuperPlayer commit | `90fb596f3e90183e034afa960c4235ab5661033d` |
| Runs per cell | 3 |
| Session length | 30 s of real time past the first frame |
| Simulator | iOS Version 27.1 (Build 24A94232), iPhone19,4 |
| Xcode | Xcode 27.1 Build version 27A9275 |
| Host | Apple M4, 10 cores, macOS 27.0.1 |
| Started | 2026-10-11T23:20:58Z |

> **3 run(s) per cell, not 20.** `PRD.md` §6 asks for at least 20, and on the simulator every session is 30 seconds of real time: twenty runs of this matrix would be well over twelve hours of wall clock (`ios/Benchmark/README.md`, *The run count*). The spreads below are estimated from 3 samples, so the two-standard-error band is wide and most differences read as neutral — which is the rule working, not the players agreeing.

## How to read this

**The four arms** (`PRD.md` §6, and `Arm.swift`):

- **stock (defaults)** — `AVPlayer(playerItem:)` and `play()`, and nothing else: Apple's own buffering and waiting.
- **stock (naive tuning)** — the same, plus `preferredForwardBufferDuration` of 120 s and `playImmediately(atRate: 1)` instead of `play()` — buffer much more, start much sooner: what an app writes when it has decided its player stalls and has not measured why.
- **SuperPlayer** — `SuperPlayer(configuration:)` with the profile each scenario names and its static policy. What it ran with is on every `sessionStarted` line of the traces.
- **SuperPlayer (adaptive)** — the same with `SuperPlayerABR`'s `AdaptivePolicy.forProfile` for that profile: the five buffer branches, the transport caps on the expensive-network pair and the post-rebuffer hold, over the per-transport estimate memory, which is forgotten before each session so every one starts cold, as Android's do.

**SuperPlayer (adaptive) is the arm graded**, against each arm before it.

**The definitions are [`docs/telemetry-schema.md`](../../docs/telemetry-schema.md)**, and every metric is reduced from one event vocabulary by one function, `SuperPlayerTelemetry`'s `SessionMetrics` — Android's, ported definition for definition. Arm (c)'s events come from the shipped `QoeCollector`; the stock arms have no `SuperPlayer`, so theirs come from `StockTelemetry`, which mirrors that collector handler for handler and is held to it by `StockTelemetryAgreementTests`, in `ios/check`.

- **Every mean carries its spread**, as `mean ± sd (p50, p95, n)`.
- **A difference inside two standard errors is neutral, not a small win**, by a rule fixed in advance and applied identically in both directions.
- **Rebuffer ratio is pooled** — stalled over stalled-plus-playing, summed across sessions — and its verdict comes from the spread of the per-session ratios.
- **Bitrate is sampled at ten seconds**, `PlaybackStateSampled`'s cadence, so a rendition held for less than one interval can fall between samples, identically for every arm.

**What the spread is a spread of.** Time here is real: the network is a deterministic trace paced at a loopback socket, but the simulator, the host's scheduler and AVFoundation's own timers are not deterministic, so a cell's spread is real run-to-run variation on this host — and on a host shared with other work, some of it is that work. It is not the variation a device on a real link would see, and a p95 here should not be quoted as one.

**Every session after a process's first starts warm.** `AVPlayer` carries a throughput estimate between items in one process and nothing public clears it, so a session begins where the one before it left the estimate. The runner cannot remove that and balances it instead: a cell's arms play back to back on one network, their order rotating each run (`MatrixRun.swift`). Read every start-up number as a returning viewer's on that network, not a first-time one's.

**0 session(s) were excluded**, out of 288 run. A session is excluded when its event stream was incomplete (`SessionEnded.droppedEventCount` non-zero) or it never ended; never for being slow or an outlier.

## Where SuperPlayer (adaptive) is worse, or no better

Before the wins, deliberately: `PRD.md` §6 — *publish the cases where SuperPlayer is neutral or worse.* A loss against stock (naive tuning) is a cell where configuring by intuition did as well, and is stated as plainly as any other.

### Against stock (defaults)

**Worse (19 of 144 comparisons):**

| Scenario | Network | Metric | stock (defaults) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| live | congested WiFi | time to first frame (ms) | 1491 ± 5 (p50 1491, p95 1495, n=3) | 1515 ± 3 (p50 1517, p95 1517, n=3) | +24.333 (+1.6%) — **worse** |
| live | LTE with dropouts | time to first frame (ms) | 1073 ± 8 (p50 1074, p95 1080, n=3) | 1106 ± 6 (p50 1104, p95 1112, n=3) | +32.667 (+3.0%) — **worse** |
| live | 3G | time to first frame (ms) | 5118 ± 110 (p50 5055, p95 5245, n=3) | 11087 ± 106 (p50 11142, p95 11154, n=3) | +5969 (+116.6%) — **worse** |
| live | high latency | time to first frame (ms) | 3252 ± 4 (p50 3254, p95 3255, n=3) | 3279 ± 14 (p50 3272, p95 3295, n=3) | +27.333 (+0.8%) — **worse** |
| live | high latency | rebuffer ratio | 0.0000 | 0.0010 | +0.001 — **worse** |
| live | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +1.000 — **worse** |
| live | high latency | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | −0.005 (−0.1%) — **worse** |
| short-form | stable WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **worse** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | stable WiFi | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.644 (−83.3%) — **worse** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −1270000 (−63.5%) — **worse** |
| VOD (data saver) | congested WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −1.270 (−63.5%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3492222 (−82.7%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 4.08 ± 0.58 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.353 (−82.1%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.659 (−83.4%) — **worse** |
| VOD (data saver) | high latency | time to first frame (ms) | 2839 ± 333 (p50 2648, p95 3223, n=3) | 3243 ± 15 (p50 3234, p95 3260, n=3) | +404 (+14.2%) — **worse** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | high latency | QoE score | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.673 (−83.4%) — **worse** |

**Neutral — inside the noise (113):**

| Scenario | Network | Metric | stock (defaults) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 412 ± 429 (p50 169, p95 907, n=3) | 162 ± 4 (p50 164, p95 165, n=3) | −250 (−60.6%) — **neutral** |
| VOD | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | stable WiFi | switch count | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | −0.667 (−50.0%) — **neutral** |
| VOD | stable WiFi | QoE score | 4.40 ± 0.03 (p50 4.42, p95 4.42, n=3) | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) | +0.046 (+1.0%) — **neutral** |
| VOD | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | congested WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | congested WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | time to first frame (ms) | 1073 ± 3 (p50 1072, p95 1076, n=3) | 1073 ± 5 (p50 1072, p95 1078, n=3) | +0.333 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD | 3G | time to first frame (ms) | 3281 ± 101 (p50 3229, p95 3398, n=3) | 3224 ± 11 (p50 3223, p95 3235, n=3) | −57.667 (−1.8%) — **neutral** |
| VOD | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 164 ± 2 (p50 165, p95 166, n=3) | 163 ± 3 (p50 164, p95 165, n=3) | −1.667 (−1.0%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | time to first frame (ms) | 3019 ± 336 (p50 3201, p95 3224, n=3) | 3030 ± 345 (p50 3193, p95 3264, n=3) | +11.667 (+0.4%) — **neutral** |
| VOD | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | switch count | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | −0.333 (−25.0%) — **neutral** |
| VOD | high latency | QoE score | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | −0.000 (−0.0%) — **neutral** |
| live | stable WiFi | time to first frame (ms) | 157 ± 7 (p50 157, p95 164, n=3) | 161 ± 4 (p50 163, p95 164, n=3) | +4.000 (+2.5%) — **neutral** |
| live | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | +0.014 (+0.3%) — **neutral** |
| live | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | congested WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 648889 ± 140489 (p50 730000, p95 730000, n=3) | −81111 (−11.1%) — **neutral** |
| live | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.60 ± 0.15 (p50 0.69, p95 0.69, n=3) | −0.001 (−0.1%) — **neutral** |
| live | WiFi→cellular | time to first frame (ms) | 162 ± 4 (p50 163, p95 165, n=3) | 160 ± 4 (p50 161, p95 164, n=3) | −1.333 (−0.8%) — **neutral** |
| live | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | WiFi→cellular | average bitrate (bit/s) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 3944444 ± 962250 (p50 4500000, p95 4500000, n=3) | −277778 (−6.6%) — **neutral** |
| live | WiFi→cellular | switch count | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | WiFi→cellular | QoE score | 4.07 ± 0.53 (p50 4.37, p95 4.37, n=3) | 3.79 ± 1.01 (p50 4.37, p95 4.37, n=3) | −0.278 (−6.8%) — **neutral** |
| live | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 163 ± 2 (p50 162, p95 165, n=3) | 156 ± 5 (p50 155, p95 162, n=3) | −6.333 (−3.9%) — **neutral** |
| short-form | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | time to first frame (ms) | 556 ± 17 (p50 546, p95 575, n=3) | 545 ± 3 (p50 544, p95 548, n=3) | −11.000 (−2.0%) — **neutral** |
| short-form | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 489 ± 6 (p50 486, p95 496, n=3) | 491 ± 9 (p50 494, p95 497, n=3) | +1.333 (+0.3%) — **neutral** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | 3G | time to first frame (ms) | 2242 ± 54 (p50 2213, p95 2304, n=3) | 2607 ± 704 (p50 2203, p95 3420, n=3) | +365 (+16.3%) — **neutral** |
| short-form | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 155 ± 8 (p50 156, p95 163, n=3) | 157 ± 5 (p50 155, p95 163, n=3) | +2.000 (+1.3%) — **neutral** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 3240 ± 3 (p50 3239, p95 3243, n=3) | 3236 ± 14 (p50 3231, p95 3251, n=3) | −4.000 (−0.1%) — **neutral** |
| short-form | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 161 ± 3 (p50 163, p95 163, n=3) | 200 ± 75 (p50 159, p95 287, n=3) | +39.000 (+24.2%) — **neutral** |
| VOD (data saver) | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 161 ± 3 (p50 163, p95 163, n=3) | 156 ± 3 (p50 157, p95 158, n=3) | −5.333 (−3.3%) — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

### Against stock (naive tuning)

**Worse (15 of 144 comparisons):**

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| live | congested WiFi | time to first frame (ms) | 1489 ± 6 (p50 1492, p95 1493, n=3) | 1515 ± 3 (p50 1517, p95 1517, n=3) | +25.667 (+1.7%) — **worse** |
| live | LTE with dropouts | time to first frame (ms) | 1068 ± 5 (p50 1066, p95 1074, n=3) | 1106 ± 6 (p50 1104, p95 1112, n=3) | +37.333 (+3.5%) — **worse** |
| live | 3G | time to first frame (ms) | 5116 ± 111 (p50 5053, p95 5244, n=3) | 11087 ± 106 (p50 11142, p95 11154, n=3) | +5971 (+116.7%) — **worse** |
| live | high latency | time to first frame (ms) | 3248 ± 8 (p50 3251, p95 3255, n=3) | 3279 ± 14 (p50 3272, p95 3295, n=3) | +31.000 (+1.0%) — **worse** |
| live | high latency | rebuffer ratio | 0.0000 | 0.0010 | +0.001 — **worse** |
| live | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +1.000 — **worse** |
| live | high latency | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | −0.005 (−0.1%) — **worse** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | stable WiFi | QoE score | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.659 (−83.4%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | high latency | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.645 (−83.3%) — **worse** |

**Neutral — inside the noise (118):**

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 169 ± 16 (p50 163, p95 187, n=3) | 162 ± 4 (p50 164, p95 165, n=3) | −7.333 (−4.3%) — **neutral** |
| VOD | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | stable WiFi | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | stable WiFi | QoE score | 4.43 ± 0.06 (p50 4.42, p95 4.50, n=3) | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) | +0.014 (+0.3%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 1832 ± 585 (p50 1495, p95 2507, n=3) | 1488 ± 2 (p50 1488, p95 1489, n=3) | −344 (−18.8%) — **neutral** |
| VOD | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | congested WiFi | switch count | 1.00 ± 1.73 (p50 0.00, p95 3.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **neutral** |
| VOD | congested WiFi | QoE score | 1.92 ± 0.14 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.083 (+4.3%) — **neutral** |
| VOD | LTE with dropouts | time to first frame (ms) | 1077 ± 4 (p50 1075, p95 1081, n=3) | 1073 ± 5 (p50 1072, p95 1078, n=3) | −3.667 (−0.3%) — **neutral** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD | 3G | time to first frame (ms) | 3225 ± 7 (p50 3221, p95 3233, n=3) | 3224 ± 11 (p50 3223, p95 3235, n=3) | −1.000 (−0.0%) — **neutral** |
| VOD | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 160 ± 3 (p50 159, p95 163, n=3) | 163 ± 3 (p50 164, p95 165, n=3) | +3.000 (+1.9%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | time to first frame (ms) | 3021 ± 347 (p50 3184, p95 3257, n=3) | 3030 ± 345 (p50 3193, p95 3264, n=3) | +9.333 (+0.3%) — **neutral** |
| VOD | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | switch count | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | −0.333 (−25.0%) — **neutral** |
| VOD | high latency | QoE score | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | −0.000 (−0.0%) — **neutral** |
| live | stable WiFi | time to first frame (ms) | 162 ± 7 (p50 162, p95 169, n=3) | 161 ± 4 (p50 163, p95 164, n=3) | −1.000 (−0.6%) — **neutral** |
| live | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | QoE score | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | −0.014 (−0.3%) — **neutral** |
| live | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | congested WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 648889 ± 140489 (p50 730000, p95 730000, n=3) | −81111 (−11.1%) — **neutral** |
| live | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.60 ± 0.15 (p50 0.69, p95 0.69, n=3) | −0.001 (−0.1%) — **neutral** |
| live | WiFi→cellular | time to first frame (ms) | 160 ± 3 (p50 158, p95 164, n=3) | 160 ± 4 (p50 161, p95 164, n=3) | +0.333 (+0.2%) — **neutral** |
| live | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 3944444 ± 962250 (p50 4500000, p95 4500000, n=3) | −555556 (−12.3%) — **neutral** |
| live | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | +0.333 (+33.3%) — **neutral** |
| live | WiFi→cellular | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 3.79 ± 1.01 (p50 4.37, p95 4.37, n=3) | −0.583 (−13.3%) — **neutral** |
| live | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 160 ± 6 (p50 161, p95 166, n=3) | 156 ± 5 (p50 155, p95 162, n=3) | −4.000 (−2.5%) — **neutral** |
| short-form | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | congested WiFi | time to first frame (ms) | 866 ± 547 (p50 552, p95 1497, n=3) | 545 ± 3 (p50 544, p95 548, n=3) | −321 (−37.1%) — **neutral** |
| short-form | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.333 (+50.0%) — **neutral** |
| short-form | congested WiFi | QoE score | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.014 (−0.7%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 687 ± 341 (p50 491, p95 1080, n=3) | 491 ± 9 (p50 494, p95 497, n=3) | −196 (−28.5%) — **neutral** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.333 (+50.0%) — **neutral** |
| short-form | LTE with dropouts | QoE score | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.014 (−0.7%) — **neutral** |
| short-form | 3G | time to first frame (ms) | 2207 ± 7 (p50 2205, p95 2214, n=3) | 2607 ± 704 (p50 2203, p95 3420, n=3) | +401 (+18.2%) — **neutral** |
| short-form | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 161 ± 8 (p50 162, p95 168, n=3) | 157 ± 5 (p50 155, p95 163, n=3) | −3.667 (−2.3%) — **neutral** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 3226 ± 34 (p50 3241, p95 3251, n=3) | 3236 ± 14 (p50 3231, p95 3251, n=3) | +9.333 (+0.3%) — **neutral** |
| short-form | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 161 ± 6 (p50 159, p95 168, n=3) | 200 ± 75 (p50 159, p95 287, n=3) | +38.667 (+24.0%) — **neutral** |
| VOD (data saver) | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 1576667 ± 733235 (p50 2000000, p95 2000000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −846667 (−53.7%) — **neutral** |
| VOD (data saver) | congested WiFi | switch count | 0.67 ± 1.15 (p50 0.00, p95 2.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −0.667 (−100.0%) — **neutral** |
| VOD (data saver) | congested WiFi | QoE score | 1.55 ± 0.77 (p50 2.00, p95 2.00, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −0.824 (−53.0%) — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | high latency | time to first frame (ms) | 3239 ± 17 (p50 3247, p95 3250, n=3) | 3243 ± 15 (p50 3234, p95 3260, n=3) | +3.667 (+0.1%) — **neutral** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

### Against SuperPlayer

**Worse (2 of 144 comparisons):**

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | LTE with dropouts | time to first frame (ms) | 1066 ± 3 (p50 1065, p95 1069, n=3) | 1073 ± 5 (p50 1072, p95 1078, n=3) | +7.000 (+0.7%) — **worse** |
| VOD | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | −0.000 (−0.0%) — **worse** |

**Neutral — inside the noise (141):**

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 171 ± 18 (p50 162, p95 191, n=3) | 162 ± 4 (p50 164, p95 165, n=3) | −8.667 (−5.1%) — **neutral** |
| VOD | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | stable WiFi | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | stable WiFi | QoE score | 4.43 ± 0.06 (p50 4.42, p95 4.50, n=3) | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) | +0.014 (+0.3%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 1488 ± 6 (p50 1491, p95 1492, n=3) | 1488 ± 2 (p50 1488, p95 1489, n=3) | −0.667 (−0.0%) — **neutral** |
| VOD | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | congested WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | congested WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | time to first frame (ms) | 3223 ± 7 (p50 3220, p95 3231, n=3) | 3224 ± 11 (p50 3223, p95 3235, n=3) | +1.000 (+0.0%) — **neutral** |
| VOD | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 153 ± 10 (p50 156, p95 161, n=3) | 163 ± 3 (p50 164, p95 165, n=3) | +9.667 (+6.3%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | time to first frame (ms) | 3037 ± 348 (p50 3236, p95 3239, n=3) | 3030 ± 345 (p50 3193, p95 3264, n=3) | −6.333 (−0.2%) — **neutral** |
| VOD | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | QoE score | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | −0.000 (−0.0%) — **neutral** |
| live | stable WiFi | time to first frame (ms) | 159 ± 1 (p50 158, p95 160, n=3) | 161 ± 4 (p50 163, p95 164, n=3) | +2.333 (+1.5%) — **neutral** |
| live | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | QoE score | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) | −0.014 (−0.3%) — **neutral** |
| live | congested WiFi | time to first frame (ms) | 1528 ± 12 (p50 1521, p95 1542, n=3) | 1515 ± 3 (p50 1517, p95 1517, n=3) | −12.667 (−0.8%) — **neutral** |
| live | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | congested WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | time to first frame (ms) | 1099 ± 5 (p50 1099, p95 1104, n=3) | 1106 ± 6 (p50 1104, p95 1112, n=3) | +6.333 (+0.6%) — **neutral** |
| live | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | 3G | time to first frame (ms) | 11016 ± 112 (p50 10960, p95 11145, n=3) | 11087 ± 106 (p50 11142, p95 11154, n=3) | +71.000 (+0.6%) — **neutral** |
| live | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 648889 ± 140489 (p50 730000, p95 730000, n=3) | −81111 (−11.1%) — **neutral** |
| live | 3G | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | +0.333 (+33.3%) — **neutral** |
| live | 3G | QoE score | 0.69 ± 0.00 (p50 0.69, p95 0.69, n=3) | 0.60 ± 0.15 (p50 0.69, p95 0.69, n=3) | −0.085 (−12.4%) — **neutral** |
| live | WiFi→cellular | time to first frame (ms) | 159 ± 3 (p50 158, p95 163, n=3) | 160 ± 4 (p50 161, p95 164, n=3) | +1.000 (+0.6%) — **neutral** |
| live | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | WiFi→cellular | average bitrate (bit/s) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 3944444 ± 962250 (p50 4500000, p95 4500000, n=3) | −277778 (−6.6%) — **neutral** |
| live | WiFi→cellular | switch count | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | WiFi→cellular | QoE score | 4.07 ± 0.53 (p50 4.37, p95 4.37, n=3) | 3.79 ± 1.01 (p50 4.37, p95 4.37, n=3) | −0.278 (−6.8%) — **neutral** |
| live | high latency | time to first frame (ms) | 3273 ± 14 (p50 3279, p95 3283, n=3) | 3279 ± 14 (p50 3272, p95 3295, n=3) | +6.333 (+0.2%) — **neutral** |
| live | high latency | rebuffer ratio | 0.0007 | 0.0010 | +0.000 (+43.6%) — **neutral** |
| live | high latency | rebuffer count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.333 (+50.0%) — **neutral** |
| live | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | high latency | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | −0.001 (−0.0%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 155 ± 5 (p50 153, p95 161, n=3) | 156 ± 5 (p50 155, p95 162, n=3) | +1.000 (+0.6%) — **neutral** |
| short-form | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | congested WiFi | time to first frame (ms) | 545 ± 3 (p50 546, p95 547, n=3) | 545 ± 3 (p50 544, p95 548, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 488 ± 5 (p50 486, p95 494, n=3) | 491 ± 9 (p50 494, p95 497, n=3) | +2.667 (+0.5%) — **neutral** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | 3G | time to first frame (ms) | 2227 ± 30 (p50 2210, p95 2261, n=3) | 2607 ± 704 (p50 2203, p95 3420, n=3) | +381 (+17.1%) — **neutral** |
| short-form | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 159 ± 9 (p50 155, p95 169, n=3) | 157 ± 5 (p50 155, p95 163, n=3) | −1.333 (−0.8%) — **neutral** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 3275 ± 117 (p50 3228, p95 3408, n=3) | 3236 ± 14 (p50 3231, p95 3251, n=3) | −39.000 (−1.2%) — **neutral** |
| short-form | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 204 ± 84 (p50 156, p95 301, n=3) | 200 ± 75 (p50 159, p95 287, n=3) | −4.000 (−2.0%) — **neutral** |
| VOD (data saver) | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | stable WiFi | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 543 ± 6 (p50 546, p95 547, n=3) | 546 ± 4 (p50 546, p95 550, n=3) | +3.333 (+0.6%) — **neutral** |
| VOD (data saver) | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | congested WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 582 ± 171 (p50 488, p95 779, n=3) | 490 ± 3 (p50 489, p95 493, n=3) | −91.667 (−15.8%) — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | time to first frame (ms) | 2206 ± 7 (p50 2203, p95 2214, n=3) | 2206 ± 10 (p50 2210, p95 2214, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | 3G | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 156 ± 3 (p50 155, p95 159, n=3) | 156 ± 3 (p50 157, p95 158, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | high latency | time to first frame (ms) | 3239 ± 16 (p50 3243, p95 3252, n=3) | 3243 ± 15 (p50 3234, p95 3260, n=3) | +4.000 (+0.1%) — **neutral** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | high latency | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | high latency | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

## Where SuperPlayer (adaptive) is better

### Against stock (defaults)

| Scenario | Network | Metric | stock (defaults) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | congested WiFi | time to first frame (ms) | 1495 ± 4 (p50 1497, p95 1498, n=3) | 1488 ± 2 (p50 1488, p95 1489, n=3) | −7.667 (−0.5%) — **better** |
| live | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | −1.667 (−55.6%) — **better** |
| short-form | high latency | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **better** |
| VOD (data saver) | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 1488 ± 12 (p50 1486, p95 1500, n=3) | 546 ± 4 (p50 546, p95 550, n=3) | −941 (−63.3%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 1097 ± 43 (p50 1075, p95 1147, n=3) | 490 ± 3 (p50 489, p95 493, n=3) | −607 (−55.3%) — **better** |
| VOD (data saver) | LTE with dropouts | switch count | 1.67 ± 1.15 (p50 1.00, p95 3.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.667 (−100.0%) — **better** |
| VOD (data saver) | 3G | time to first frame (ms) | 3282 ± 109 (p50 3225, p95 3408, n=3) | 2206 ± 10 (p50 2210, p95 2214, n=3) | −1076 (−32.8%) — **better** |
| VOD (data saver) | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −3.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.127 (+21.0%) — **better** |
| VOD (data saver) | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |

### Against stock (naive tuning)

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| live | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | −1.667 (−55.6%) — **better** |
| VOD (data saver) | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 1489 ± 8 (p50 1493, p95 1495, n=3) | 546 ± 4 (p50 546, p95 550, n=3) | −943 (−63.3%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 1076 ± 6 (p50 1074, p95 1083, n=3) | 490 ± 3 (p50 489, p95 493, n=3) | −586 (−54.5%) — **better** |
| VOD (data saver) | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | time to first frame (ms) | 3221 ± 4 (p50 3220, p95 3226, n=3) | 2206 ± 10 (p50 2210, p95 2214, n=3) | −1015 (−31.5%) — **better** |
| VOD (data saver) | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −3.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.127 (+21.0%) — **better** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 165 ± 3 (p50 164, p95 169, n=3) | 156 ± 3 (p50 157, p95 158, n=3) | −9.667 (−5.8%) — **better** |
| VOD (data saver) | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |

### Against SuperPlayer

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| short-form | congested WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **better** |

## The F1 trade, as a loss

`PRD.md` F1's trade is *lower bitrate on a constrained link in exchange for fewer stalls*, and §6 requires it to appear **as a bitrate loss beside the rebuffer win**. The adaptive policy makes it on cellular, so this is SuperPlayer (adaptive) against SuperPlayer on every scenario of every cellular profile — bitrate beside the stalls and the score, each by the same rule as everywhere else, as Android's section is. A bitrate cut with no stall win beside it is a trade that bought nothing, and reads that way. **On the simulator the link is shaped but the path is not cellular**: testkit reports a trace's transport on each response rather than switching the device's (`ios/CLAUDE.md`), so the transport caps never bind here and what this section shows is the hold and the buffer branches on a cellular *rate*.

| Scenario | Network | Metric | SuperPlayer | SuperPlayer (adaptive) | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | −0.000 (−0.0%) — **neutral** |
| live | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 648889 ± 140489 (p50 730000, p95 730000, n=3) | −81111 (−11.1%) — **neutral** |
| live | 3G | QoE score | 0.69 ± 0.00 (p50 0.69, p95 0.69, n=3) | 0.60 ± 0.15 (p50 0.69, p95 0.69, n=3) | −0.085 (−12.4%) — **neutral** |
| short-form | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | −0.000 (−0.0%) — **worse** |
| live | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | LTE with dropouts | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.000 (+0.0%) — **neutral** |
| live | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | WiFi→cellular | average bitrate (bit/s) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 3944444 ± 962250 (p50 4500000, p95 4500000, n=3) | −277778 (−6.6%) — **neutral** |
| live | WiFi→cellular | QoE score | 4.07 ± 0.53 (p50 4.37, p95 4.37, n=3) | 3.79 ± 1.01 (p50 4.37, p95 4.37, n=3) | −0.278 (−6.8%) — **neutral** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |

## Every cell

All of it, whatever it says.

### VOD

Profile for arm (c): `videoOnDemand`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 3 | 169 / 907 (mean 412 ± 429, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.0% | 4.40 ± 0.03 (p50 4.42, p95 4.42, n=3) |
| stable WiFi | stock (naive tuning) | 3 | 163 / 187 (mean 169 ± 16, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.43 ± 0.06 (p50 4.42, p95 4.50, n=3) |
| stable WiFi | SuperPlayer | 3 | 162 / 191 (mean 171 ± 18, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.43 ± 0.06 (p50 4.42, p95 4.50, n=3) |
| stable WiFi | SuperPlayer (adaptive) | 3 | 164 / 165 (mean 162 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) |
| congested WiFi | stock (defaults) | 3 | 1497 / 1498 (mean 1495 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | stock (naive tuning) | 3 | 1495 / 2507 (mean 1832 ± 585, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 1.73 (p50 0.00, p95 3.00, n=3) | 0.0% | 1.92 ± 0.14 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | SuperPlayer | 3 | 1491 / 1492 (mean 1488 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | SuperPlayer (adaptive) | 3 | 1488 / 1489 (mean 1488 ± 2, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| LTE with dropouts | stock (defaults) | 3 | 1072 / 1076 (mean 1073 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | stock (naive tuning) | 3 | 1075 / 1081 (mean 1077 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | SuperPlayer | 3 | 1065 / 1069 (mean 1066 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | SuperPlayer (adaptive) | 3 | 1072 / 1078 (mean 1073 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| 3G | stock (defaults) | 3 | 3229 / 3398 (mean 3281 ± 101, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | stock (naive tuning) | 3 | 3221 / 3233 (mean 3225 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | SuperPlayer | 3 | 3220 / 3231 (mean 3223 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | SuperPlayer (adaptive) | 3 | 3223 / 3235 (mean 3224 ± 11, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| WiFi→cellular | stock (defaults) | 3 | 165 / 166 (mean 164 ± 2, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | stock (naive tuning) | 3 | 159 / 163 (mean 160 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | SuperPlayer | 3 | 156 / 161 (mean 153 ± 10, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | SuperPlayer (adaptive) | 3 | 164 / 165 (mean 163 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| high latency | stock (defaults) | 3 | 3201 / 3224 (mean 3019 ± 336, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.0% | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) |
| high latency | stock (naive tuning) | 3 | 3184 / 3257 (mean 3021 ± 347, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.0% | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) |
| high latency | SuperPlayer | 3 | 3236 / 3239 (mean 3037 ± 348, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) |
| high latency | SuperPlayer (adaptive) | 3 | 3193 / 3264 (mean 3030 ± 345, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) |

### live

Profile for arm (c): `liveLinear`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 3 | 157 / 164 (mean 157 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| stable WiFi | stock (naive tuning) | 3 | 162 / 169 (mean 162 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) |
| stable WiFi | SuperPlayer | 3 | 158 / 160 (mean 159 ± 1, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) |
| stable WiFi | SuperPlayer (adaptive) | 3 | 163 / 164 (mean 161 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) |
| congested WiFi | stock (defaults) | 3 | 1491 / 1495 (mean 1491 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | stock (naive tuning) | 3 | 1492 / 1493 (mean 1489 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | SuperPlayer | 3 | 1521 / 1542 (mean 1528 ± 12, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | SuperPlayer (adaptive) | 3 | 1517 / 1517 (mean 1515 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| LTE with dropouts | stock (defaults) | 3 | 1074 / 1080 (mean 1073 ± 8, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| LTE with dropouts | stock (naive tuning) | 3 | 1066 / 1074 (mean 1068 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| LTE with dropouts | SuperPlayer | 3 | 1099 / 1104 (mean 1099 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| LTE with dropouts | SuperPlayer (adaptive) | 3 | 1104 / 1112 (mean 1106 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| 3G | stock (defaults) | 3 | 5055 / 5245 (mean 5118 ± 110, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | stock (naive tuning) | 3 | 5053 / 5244 (mean 5116 ± 111, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | SuperPlayer | 3 | 10960 / 11145 (mean 11016 ± 112, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 0.69 ± 0.00 (p50 0.69, p95 0.69, n=3) |
| 3G | SuperPlayer (adaptive) | 3 | 11142 / 11154 (mean 11087 ± 106, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 648889 ± 140489 (p50 730000, p95 730000, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.0% | 0.60 ± 0.15 (p50 0.69, p95 0.69, n=3) |
| WiFi→cellular | stock (defaults) | 3 | 163 / 165 (mean 162 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.0% | 4.07 ± 0.53 (p50 4.37, p95 4.37, n=3) |
| WiFi→cellular | stock (naive tuning) | 3 | 158 / 164 (mean 160 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| WiFi→cellular | SuperPlayer | 3 | 158 / 163 (mean 159 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.0% | 4.07 ± 0.53 (p50 4.37, p95 4.37, n=3) |
| WiFi→cellular | SuperPlayer (adaptive) | 3 | 161 / 164 (mean 160 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 3944444 ± 962250 (p50 4500000, p95 4500000, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.0% | 3.79 ± 1.01 (p50 4.37, p95 4.37, n=3) |
| high latency | stock (defaults) | 3 | 3254 / 3255 (mean 3252 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| high latency | stock (naive tuning) | 3 | 3251 / 3255 (mean 3248 ± 8, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| high latency | SuperPlayer | 3 | 3279 / 3283 (mean 3273 ± 14, n=3) | 0.0007 | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| high latency | SuperPlayer (adaptive) | 3 | 3272 / 3295 (mean 3279 ± 14, n=3) | 0.0010 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |

### short-form

Profile for arm (c): `shortForm`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 3 | 162 / 165 (mean 163 ± 2, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| stable WiFi | stock (naive tuning) | 3 | 161 / 166 (mean 160 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| stable WiFi | SuperPlayer | 3 | 153 / 161 (mean 155 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| stable WiFi | SuperPlayer (adaptive) | 3 | 155 / 162 (mean 156 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| congested WiFi | stock (defaults) | 3 | 546 / 575 (mean 556 ± 17, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| congested WiFi | stock (naive tuning) | 3 | 552 / 1497 (mean 866 ± 547, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) |
| congested WiFi | SuperPlayer | 3 | 546 / 547 (mean 545 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| congested WiFi | SuperPlayer (adaptive) | 3 | 544 / 548 (mean 545 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| LTE with dropouts | stock (defaults) | 3 | 486 / 496 (mean 489 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| LTE with dropouts | stock (naive tuning) | 3 | 491 / 1080 (mean 687 ± 341, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) |
| LTE with dropouts | SuperPlayer | 3 | 486 / 494 (mean 488 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| LTE with dropouts | SuperPlayer (adaptive) | 3 | 494 / 497 (mean 491 ± 9, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| 3G | stock (defaults) | 3 | 2213 / 2304 (mean 2242 ± 54, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | stock (naive tuning) | 3 | 2205 / 2214 (mean 2207 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | SuperPlayer | 3 | 2210 / 2261 (mean 2227 ± 30, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | SuperPlayer (adaptive) | 3 | 2203 / 3420 (mean 2607 ± 704, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| WiFi→cellular | stock (defaults) | 3 | 156 / 163 (mean 155 ± 8, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| WiFi→cellular | stock (naive tuning) | 3 | 162 / 168 (mean 161 ± 8, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| WiFi→cellular | SuperPlayer | 3 | 155 / 169 (mean 159 ± 9, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| WiFi→cellular | SuperPlayer (adaptive) | 3 | 155 / 163 (mean 157 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| high latency | stock (defaults) | 3 | 3239 / 3243 (mean 3240 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| high latency | stock (naive tuning) | 3 | 3241 / 3251 (mean 3226 ± 34, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| high latency | SuperPlayer | 3 | 3228 / 3408 (mean 3275 ± 117, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| high latency | SuperPlayer (adaptive) | 3 | 3231 / 3251 (mean 3236 ± 14, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |

### VOD (data saver)

Profile for arm (c): `dataSaver`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 3 | 163 / 163 (mean 161 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| stable WiFi | stock (naive tuning) | 3 | 159 / 168 (mean 161 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) |
| stable WiFi | SuperPlayer | 3 | 156 / 301 (mean 204 ± 84, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| stable WiFi | SuperPlayer (adaptive) | 3 | 159 / 287 (mean 200 ± 75, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| congested WiFi | stock (defaults) | 3 | 1486 / 1500 (mean 1488 ± 12, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | stock (naive tuning) | 3 | 1493 / 1495 (mean 1489 ± 8, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 1576667 ± 733235 (p50 2000000, p95 2000000, n=3) | 0.67 ± 1.15 (p50 0.00, p95 2.00, n=3) | 0.0% | 1.55 ± 0.77 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | SuperPlayer | 3 | 546 / 547 (mean 543 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| congested WiFi | SuperPlayer (adaptive) | 3 | 546 / 550 (mean 546 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| LTE with dropouts | stock (defaults) | 3 | 1075 / 1147 (mean 1097 ± 43, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 1.67 ± 1.15 (p50 1.00, p95 3.00, n=3) | 0.0% | 4.08 ± 0.58 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | stock (naive tuning) | 3 | 1074 / 1083 (mean 1076 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | SuperPlayer | 3 | 488 / 779 (mean 582 ± 171, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| LTE with dropouts | SuperPlayer (adaptive) | 3 | 489 / 493 (mean 490 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | stock (defaults) | 3 | 3225 / 3408 (mean 3282 ± 109, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | stock (naive tuning) | 3 | 3220 / 3226 (mean 3221 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | SuperPlayer | 3 | 2203 / 2214 (mean 2206 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | SuperPlayer (adaptive) | 3 | 2210 / 2214 (mean 2206 ± 10, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| WiFi→cellular | stock (defaults) | 3 | 163 / 163 (mean 161 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.39 ± 0.02 (p50 4.37, p95 4.42, n=3) |
| WiFi→cellular | stock (naive tuning) | 3 | 164 / 169 (mean 165 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | SuperPlayer | 3 | 155 / 159 (mean 156 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| WiFi→cellular | SuperPlayer (adaptive) | 3 | 157 / 158 (mean 156 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| high latency | stock (defaults) | 3 | 2648 / 3223 (mean 2839 ± 333, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) |
| high latency | stock (naive tuning) | 3 | 3247 / 3250 (mean 3239 ± 17, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| high latency | SuperPlayer | 3 | 3243 / 3252 (mean 3239 ± 16, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| high latency | SuperPlayer (adaptive) | 3 | 3234 / 3260 (mean 3243 ± 15, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |

## Peak RSS and battery — the device arm

`PRD.md` §6 reports peak resident memory and battery over a thirty-minute session, and **neither is in the tables above**: a simulator process's memory is the host's and it has no battery, so either number from here would be a plausible wrong one. Both are a device's, and on iOS they are i10's (#439), which also measures `DeviceCapacity`'s bound. Printed as dashes so an unmeasured cell cannot be mistaken for a measured one.

| Metric | Arm (a) | Arm (b) | Arm (c) | Arm (d) |
| --- | --- | --- | --- | --- |
| Peak RSS (MiB) | — | — | — | — |
| Battery delta over 30 min (%) | — | — | — | — |

**Not covered either**, and written down so an absent row does not read as a passing one: protected content (FairPlay is i6's and needs a device), and public streams over a real network (`docs/testing.md` bars the network; the device arm is where they belong).

## Raw traces

Every event of every run, one JSONL file per cell in `traces/`, named as the Android report's are, each line carrying its scenario, network, arm and run beside the event's own fields (`TraceWriter.swift`). Every number above can be recomputed from them.

```text
grep '"evt":"rebufferEnded"' traces/*.jsonl                 # every stall in the matrix
grep '"run":1' traces/vod__3g__superplayer.jsonl            # one session, end to end
```

96 cell(s), 288 aggregated session(s).
