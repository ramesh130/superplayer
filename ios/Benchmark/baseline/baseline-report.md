# SuperPlayer benchmark — iOS baseline (simulator)

The fixed matrix of [`PRD.md`](../../../PRD.md) §6 on the iOS simulator, run by `ios/Benchmark/bench` over testkit's loopback origin and network shaper, with **SuperPlayer** as the arm under test. Every number is produced by this harness against content this repository generates (§0.2); none is carried in from anywhere else, and none is copied from the Android reports under `android/benchmark/`, which measure a different engine on a fake clock and are not comparable to these cell for cell.

## Exit criterion

Phase 11's criterion — *measured improvement of the adaptive policy over the static profile on the shaped-network suite, with no regression on stable WiFi* — judged by `ExitCriterion.swift`'s rule, fixed before this run: **SuperPlayer (adaptive)** against **SuperPlayer**, a QoE score better in at least one scenario and worse in none on each shaped profile, and no metric worse on stable WiFi.

**Not met — not measured.** Arm (d) is an empty slot until i3 (#416) builds the iOS adaptive policy, so no profile has the comparison the criterion is about. Unmeasured is not met: absence of evidence is not a pass.

| Network | Judged on | Verdict | QoE better in | Worse in |
| --- | --- | --- | --- | --- |
| stable WiFi | every metric | **not met** — not measured | — | — |
| congested WiFi | QoE score | **not met** — not measured | — | — |
| LTE with dropouts | QoE score | **not met** — not measured | — | — |
| 3G | QoE score | **not met** — not measured | — | — |
| WiFi→cellular | QoE score | **not met** — not measured | — | — |
| high latency | QoE score | **not met** — not measured | — | — |

## What this was measured on

| | |
| --- | --- |
| SuperPlayer commit | `154631224c96e1e80b8d74572776368e81575641` |
| Runs per cell | 3 |
| Session length | 30 s of real time past the first frame |
| Simulator | iOS Version 27.1 (Build 24A94232), iPhone19,4 |
| Xcode | Xcode 27.1 Build version 27A9275 |
| Host | Apple M4, 10 cores, macOS 27.0.1 |
| Started | 2026-10-11T11:22:14Z |

> **3 run(s) per cell, not 20.** `PRD.md` §6 asks for at least 20, and on the simulator every session is 30 seconds of real time: twenty runs of this matrix would be well over twelve hours of wall clock (`ios/Benchmark/README.md`, *The run count*). The spreads below are estimated from 3 samples, so the two-standard-error band is wide and most differences read as neutral — which is the rule working, not the players agreeing.

## How to read this

**The four arms** (`PRD.md` §6, and `Arm.swift`):

- **stock (defaults)** — `AVPlayer(playerItem:)` and `play()`, and nothing else: Apple's own buffering and waiting.
- **stock (naive tuning)** — the same, plus `preferredForwardBufferDuration` of 120 s and `playImmediately(atRate: 1)` instead of `play()` — buffer much more, start much sooner: what an app writes when it has decided its player stalls and has not measured why.
- **SuperPlayer** — `SuperPlayer(configuration:)` with the profile each scenario names and its static policy. What it ran with is on every `sessionStarted` line of the traces.
- **SuperPlayer (adaptive)** — **an empty slot until i3 (#416)**, which builds the iOS adaptive policy. Not run, and not imitated by running arm (c) under another name.

**SuperPlayer is the arm graded**, against each built arm before it. From i3 on the candidate is arm (d), with nothing in the runner to change.

**The definitions are [`docs/telemetry-schema.md`](../../../docs/telemetry-schema.md)**, and every metric is reduced from one event vocabulary by one function, `SuperPlayerTelemetry`'s `SessionMetrics` — Android's, ported definition for definition. Arm (c)'s events come from the shipped `QoeCollector`; the stock arms have no `SuperPlayer`, so theirs come from `StockTelemetry`, which mirrors that collector handler for handler and is held to it by `StockTelemetryAgreementTests`, in `ios/check`.

- **Every mean carries its spread**, as `mean ± sd (p50, p95, n)`.
- **A difference inside two standard errors is neutral, not a small win**, by a rule fixed in advance and applied identically in both directions.
- **Rebuffer ratio is pooled** — stalled over stalled-plus-playing, summed across sessions — and its verdict comes from the spread of the per-session ratios.
- **Bitrate is sampled at ten seconds**, `PlaybackStateSampled`'s cadence, so a rendition held for less than one interval can fall between samples, identically for every arm.

**What the spread is a spread of.** Time here is real: the network is a deterministic trace paced at a loopback socket, but the simulator, the host's scheduler and AVFoundation's own timers are not deterministic, so a cell's spread is real run-to-run variation on this host — and on a host shared with other work, some of it is that work. It is not the variation a device on a real link would see, and a p95 here should not be quoted as one.

**Every session after a process's first starts warm.** `AVPlayer` carries a throughput estimate between items in one process and nothing public clears it, so a session begins where the one before it left the estimate. The runner cannot remove that and balances it instead: a cell's arms play back to back on one network, their order rotating each run (`MatrixRun.swift`). Read every start-up number as a returning viewer's on that network, not a first-time one's.

**0 session(s) were excluded**, out of 216 run. A session is excluded when its event stream was incomplete (`SessionEnded.droppedEventCount` non-zero) or it never ended; never for being slow or an outlier.

## Where SuperPlayer is worse, or no better

Before the wins, deliberately: `PRD.md` §6 — *publish the cases where SuperPlayer is neutral or worse.* A loss against stock (naive tuning) is a cell where configuring by intuition did as well, and is stated as plainly as any other.

### Against stock (defaults)

**Worse (16 of 144 comparisons):**

| Scenario | Network | Metric | stock (defaults) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| live | 3G | time to first frame (ms) | 5055 ± 7 (p50 5056, p95 5061, n=3) | 10958 ± 6 (p50 10955, p95 10965, n=3) | +5903 (+116.8%) — **worse** |
| live | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 3944444 ± 481125 (p50 3666667, p95 4500000, n=3) | −555556 (−12.3%) — **worse** |
| live | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.67 ± 0.58 (p50 2.00, p95 2.00, n=3) | +0.667 (+66.7%) — **worse** |
| live | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 3.81 ± 0.53 (p50 3.50, p95 4.42, n=3) | −0.611 (−13.8%) — **worse** |
| live | high latency | time to first frame (ms) | 3022 ± 349 (p50 3217, p95 3230, n=3) | 3429 ± 16 (p50 3420, p95 3448, n=3) | +407 (+13.5%) — **worse** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | stable WiFi | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.644 (−83.3%) — **worse** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −1270000 (−63.5%) — **worse** |
| VOD (data saver) | congested WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −1.270 (−63.5%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |
| VOD (data saver) | high latency | time to first frame (ms) | 2637 ± 8 (p50 2633, p95 2646, n=3) | 3232 ± 11 (p50 3237, p95 3240, n=3) | +595 (+22.6%) — **worse** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | high latency | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |

**Neutral — inside the noise (115):**

| Scenario | Network | Metric | stock (defaults) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 1572 ± 2436 (p50 172, p95 4385, n=3) | 181 ± 44 (p50 156, p95 231, n=3) | −1391 (−88.5%) — **neutral** |
| VOD | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | stable WiFi | average bitrate (bit/s) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +277778 (+6.6%) — **neutral** |
| VOD | stable WiFi | switch count | 1.67 ± 1.15 (p50 1.00, p95 3.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | −1.000 (−60.0%) — **neutral** |
| VOD | stable WiFi | QoE score | 4.09 ± 0.56 (p50 4.42, p95 4.42, n=3) | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) | +0.351 (+8.6%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 1763 ± 348 (p50 1563, p95 2165, n=3) | 1598 ± 31 (p50 1614, p95 1618, n=3) | −165 (−9.4%) — **neutral** |
| VOD | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | congested WiFi | average bitrate (bit/s) | 1788333 ± 366617 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +211667 (+11.8%) — **neutral** |
| VOD | congested WiFi | switch count | 0.67 ± 1.15 (p50 0.00, p95 2.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −0.667 (−100.0%) — **neutral** |
| VOD | congested WiFi | QoE score | 1.77 ± 0.41 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.234 (+13.2%) — **neutral** |
| VOD | LTE with dropouts | time to first frame (ms) | 1098 ± 26 (p50 1085, p95 1128, n=3) | 1131 ± 64 (p50 1114, p95 1202, n=3) | +33.000 (+3.0%) — **neutral** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 3666667 ± 1443376 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +833333 (+22.7%) — **neutral** |
| VOD | LTE with dropouts | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.333 (+50.0%) — **neutral** |
| VOD | LTE with dropouts | QoE score | 3.61 ± 1.40 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.806 (+22.3%) — **neutral** |
| VOD | 3G | time to first frame (ms) | 3229 ± 23 (p50 3216, p95 3255, n=3) | 3312 ± 167 (p50 3233, p95 3504, n=3) | +83.333 (+2.6%) — **neutral** |
| VOD | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 180 ± 18 (p50 187, p95 193, n=3) | 186 ± 35 (p50 174, p95 225, n=3) | +6.000 (+3.3%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD | high latency | time to first frame (ms) | 2648 ± 7 (p50 2647, p95 2656, n=3) | 2989 ± 577 (p50 2685, p95 3654, n=3) | +340 (+12.9%) — **neutral** |
| VOD | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | time to first frame (ms) | 240 ± 88 (p50 218, p95 336, n=3) | 364 ± 335 (p50 186, p95 751, n=3) | +125 (+52.0%) — **neutral** |
| live | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | time to first frame (ms) | 900 ± 562 (p50 589, p95 1548, n=3) | 1481 ± 1606 (p50 567, p95 3335, n=3) | +581 (+64.6%) — **neutral** |
| live | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | QoE score | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | −0.000 (−0.0%) — **neutral** |
| live | LTE with dropouts | time to first frame (ms) | 884 ± 343 (p50 1077, p95 1086, n=3) | 767 ± 493 (p50 487, p95 1336, n=3) | −117 (−13.2%) — **neutral** |
| live | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | switch count | 0.33 ± 0.58 (p50 0.00, p95 1.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | +0.333 (+100.0%) — **neutral** |
| live | LTE with dropouts | QoE score | 1.99 ± 0.02 (p50 2.00, p95 2.00, n=3) | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | −0.014 (−0.7%) — **neutral** |
| live | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | WiFi→cellular | time to first frame (ms) | 166 ± 4 (p50 165, p95 170, n=3) | 163 ± 1 (p50 163, p95 164, n=3) | −3.000 (−1.8%) — **neutral** |
| live | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | high latency | rebuffer ratio | 0.0030 | 0.0401 | +0.037 (+1242.9%) — **neutral** |
| live | high latency | rebuffer count | 0.33 ± 0.58 (p50 0.00, p95 1.00, n=3) | 0.33 ± 0.58 (p50 0.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4083333 ± 721688 (p50 4500000, p95 4500000, n=3) | −416667 (−9.3%) — **neutral** |
| live | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 2.33 ± 2.31 (p50 1.00, p95 5.00, n=3) | +1.333 (+133.3%) — **neutral** |
| live | high latency | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.38, n=3) | 3.69 ± 1.26 (p50 4.42, p95 4.42, n=3) | −0.688 (−15.7%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 160 ± 12 (p50 167, p95 167, n=3) | 160 ± 2 (p50 159, p95 162, n=3) | −0.333 (−0.2%) — **neutral** |
| short-form | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | time to first frame (ms) | 550 ± 8 (p50 547, p95 559, n=3) | 546 ± 3 (p50 546, p95 549, n=3) | −4.000 (−0.7%) — **neutral** |
| short-form | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | time to first frame (ms) | 2209 ± 13 (p50 2215, p95 2217, n=3) | 2210 ± 8 (p50 2207, p95 2219, n=3) | +1.333 (+0.1%) — **neutral** |
| short-form | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 157 ± 3 (p50 158, p95 160, n=3) | 156 ± 4 (p50 154, p95 161, n=3) | −1.333 (−0.8%) — **neutral** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 3211 ± 27 (p50 3196, p95 3242, n=3) | 3247 ± 73 (p50 3218, p95 3330, n=3) | +36.667 (+1.1%) — **neutral** |
| short-form | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 161 ± 11 (p50 155, p95 174, n=3) | 157 ± 19 (p50 160, p95 175, n=3) | −3.667 (−2.3%) — **neutral** |
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
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 160 ± 4 (p50 162, p95 162, n=3) | 161 ± 3 (p50 162, p95 163, n=3) | +1.333 (+0.8%) — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

### Against stock (naive tuning)

**Worse (14 of 144 comparisons):**

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | −0.000 (−0.0%) — **worse** |
| live | 3G | time to first frame (ms) | 5176 ± 208 (p50 5061, p95 5416, n=3) | 10958 ± 6 (p50 10955, p95 10965, n=3) | +5782 (+111.7%) — **worse** |
| live | high latency | time to first frame (ms) | 2842 ± 348 (p50 2650, p95 3243, n=3) | 3429 ± 16 (p50 3420, p95 3448, n=3) | +588 (+20.7%) — **worse** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | stable WiFi | QoE score | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.673 (−83.4%) — **worse** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −1270000 (−63.5%) — **worse** |
| VOD (data saver) | congested WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −1.270 (−63.5%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |
| VOD (data saver) | high latency | time to first frame (ms) | 2624 ± 29 (p50 2638, p95 2644, n=3) | 3232 ± 11 (p50 3237, p95 3240, n=3) | +608 (+23.2%) — **worse** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | high latency | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |

**Neutral — inside the noise (117):**

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 164 ± 7 (p50 168, p95 169, n=3) | 181 ± 44 (p50 156, p95 231, n=3) | +16.333 (+9.9%) — **neutral** |
| VOD | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | stable WiFi | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | stable WiFi | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | stable WiFi | QoE score | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) | −0.000 (−0.0%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 1950 ± 582 (p50 1675, p95 2618, n=3) | 1598 ± 31 (p50 1614, p95 1618, n=3) | −352 (−18.1%) — **neutral** |
| VOD | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | congested WiFi | switch count | 1.00 ± 1.73 (p50 0.00, p95 3.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **neutral** |
| VOD | congested WiFi | QoE score | 1.92 ± 0.14 (p50 2.00, p95 2.00, n=3) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) | +0.083 (+4.3%) — **neutral** |
| VOD | LTE with dropouts | time to first frame (ms) | 1116 ± 72 (p50 1076, p95 1200, n=3) | 1131 ± 64 (p50 1114, p95 1202, n=3) | +14.667 (+1.3%) — **neutral** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | time to first frame (ms) | 3220 ± 10 (p50 3219, p95 3230, n=3) | 3312 ± 167 (p50 3233, p95 3504, n=3) | +92.333 (+2.9%) — **neutral** |
| VOD | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 185 ± 24 (p50 180, p95 212, n=3) | 186 ± 35 (p50 174, p95 225, n=3) | +0.333 (+0.2%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | WiFi→cellular | QoE score | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.014 (+0.3%) — **neutral** |
| VOD | high latency | time to first frame (ms) | 2621 ± 25 (p50 2623, p95 2645, n=3) | 2989 ± 577 (p50 2685, p95 3654, n=3) | +368 (+14.0%) — **neutral** |
| VOD | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | −0.000 (−0.0%) — **neutral** |
| live | stable WiFi | time to first frame (ms) | 174 ± 17 (p50 165, p95 194, n=3) | 364 ± 335 (p50 186, p95 751, n=3) | +190 (+109.0%) — **neutral** |
| live | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | stable WiFi | average bitrate (bit/s) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | +277778 (+6.6%) — **neutral** |
| live | stable WiFi | switch count | 1.67 ± 1.15 (p50 1.00, p95 3.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | −0.667 (−40.0%) — **neutral** |
| live | stable WiFi | QoE score | 4.08 ± 0.58 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.334 (+8.2%) — **neutral** |
| live | congested WiFi | time to first frame (ms) | 1225 ± 1177 (p50 548, p95 2584, n=3) | 1481 ± 1606 (p50 567, p95 3335, n=3) | +256 (+20.9%) — **neutral** |
| live | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | QoE score | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | −0.000 (−0.0%) — **neutral** |
| live | LTE with dropouts | time to first frame (ms) | 773 ± 510 (p50 481, p95 1362, n=3) | 767 ± 493 (p50 487, p95 1336, n=3) | −5.667 (−0.7%) — **neutral** |
| live | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | switch count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | QoE score | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) | +0.000 (+0.0%) — **neutral** |
| live | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| live | WiFi→cellular | time to first frame (ms) | 157 ± 9 (p50 162, p95 163, n=3) | 163 ± 1 (p50 163, p95 164, n=3) | +5.667 (+3.6%) — **neutral** |
| live | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| live | WiFi→cellular | average bitrate (bit/s) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 3944444 ± 481125 (p50 3666667, p95 4500000, n=3) | −277778 (−6.6%) — **neutral** |
| live | WiFi→cellular | switch count | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 1.67 ± 0.58 (p50 2.00, p95 2.00, n=3) | +0.333 (+25.0%) — **neutral** |
| live | WiFi→cellular | QoE score | 4.11 ± 0.53 (p50 4.42, p95 4.42, n=3) | 3.81 ± 0.53 (p50 3.50, p95 4.42, n=3) | −0.305 (−7.4%) — **neutral** |
| live | high latency | rebuffer ratio | 0.0064 | 0.0401 | +0.034 (+527.3%) — **neutral** |
| live | high latency | rebuffer count | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.33 ± 0.58 (p50 0.00, p95 1.00, n=3) | −0.333 (−50.0%) — **neutral** |
| live | high latency | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 4083333 ± 721688 (p50 4500000, p95 4500000, n=3) | −416667 (−9.3%) — **neutral** |
| live | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 2.33 ± 2.31 (p50 1.00, p95 5.00, n=3) | +1.333 (+133.3%) — **neutral** |
| live | high latency | QoE score | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) | 3.69 ± 1.26 (p50 4.42, p95 4.42, n=3) | −0.686 (−15.7%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 166 ± 26 (p50 156, p95 196, n=3) | 160 ± 2 (p50 159, p95 162, n=3) | −6.333 (−3.8%) — **neutral** |
| short-form | stable WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | time to first frame (ms) | 551 ± 10 (p50 553, p95 559, n=3) | 546 ± 3 (p50 546, p95 549, n=3) | −4.667 (−0.8%) — **neutral** |
| short-form | congested WiFi | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | congested WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | congested WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | congested WiFi | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 494 ± 5 (p50 492, p95 500, n=3) | 491 ± 1 (p50 491, p95 492, n=3) | −3.000 (−0.6%) — **neutral** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | time to first frame (ms) | 2213 ± 5 (p50 2215, p95 2217, n=3) | 2210 ± 8 (p50 2207, p95 2219, n=3) | −3.333 (−0.2%) — **neutral** |
| short-form | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | 3G | QoE score | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 161 ± 6 (p50 164, p95 165, n=3) | 156 ± 4 (p50 154, p95 161, n=3) | −5.000 (−3.1%) — **neutral** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | −0.000 (−0.0%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 3264 ± 92 (p50 3226, p95 3368, n=3) | 3247 ± 73 (p50 3218, p95 3330, n=3) | −16.333 (−0.5%) — **neutral** |
| short-form | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| short-form | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 233 ± 132 (p50 158, p95 385, n=3) | 157 ± 19 (p50 160, p95 175, n=3) | −75.667 (−32.5%) — **neutral** |
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
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | high latency | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

## Where SuperPlayer is better

### Against stock (defaults)

| Scenario | Network | Metric | stock (defaults) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| live | stable WiFi | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | +0.000 (+0.0%) — **better** |
| live | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | −2.000 (−66.7%) — **better** |
| live | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.69 ± 0.00 (p50 0.69, p95 0.69, n=3) | +0.085 (+14.0%) — **better** |
| short-form | LTE with dropouts | time to first frame (ms) | 498 ± 3 (p50 499, p95 500, n=3) | 491 ± 1 (p50 491, p95 492, n=3) | −6.333 (−1.3%) — **better** |
| VOD (data saver) | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 1555 ± 15 (p50 1553, p95 1571, n=3) | 554 ± 2 (p50 555, p95 555, n=3) | −1001 (−64.4%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 1080 ± 7 (p50 1082, p95 1086, n=3) | 493 ± 2 (p50 494, p95 494, n=3) | −587 (−54.4%) — **better** |
| VOD (data saver) | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | time to first frame (ms) | 3227 ± 7 (p50 3230, p95 3233, n=3) | 2203 ± 7 (p50 2207, p95 2208, n=3) | −1024 (−31.7%) — **better** |
| VOD (data saver) | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −3.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.127 (+21.0%) — **better** |
| VOD (data saver) | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |

### Against stock (naive tuning)

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| live | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | −2.000 (−66.7%) — **better** |
| live | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.69 ± 0.00 (p50 0.69, p95 0.69, n=3) | +0.085 (+14.0%) — **better** |
| short-form | LTE with dropouts | QoE score | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) | +0.000 (+0.0%) — **better** |
| VOD (data saver) | stable WiFi | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 1544 ± 26 (p50 1552, p95 1566, n=3) | 554 ± 2 (p50 555, p95 555, n=3) | −990 (−64.1%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 1085 ± 11 (p50 1081, p95 1098, n=3) | 493 ± 2 (p50 494, p95 494, n=3) | −593 (−54.6%) — **better** |
| VOD (data saver) | LTE with dropouts | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | time to first frame (ms) | 3287 ± 140 (p50 3233, p95 3446, n=3) | 2203 ± 7 (p50 2207, p95 2208, n=3) | −1084 (−33.0%) — **better** |
| VOD (data saver) | 3G | switch count | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −3.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.127 (+21.0%) — **better** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 169 ± 6 (p50 169, p95 175, n=3) | 161 ± 3 (p50 162, p95 163, n=3) | −8.000 (−4.7%) — **better** |
| VOD (data saver) | WiFi→cellular | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | switch count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | −1.000 (−100.0%) — **better** |

## The F1 trade, as a loss

`PRD.md` F1's trade is *lower bitrate on a constrained link in exchange for fewer stalls*, and §6 requires it to appear **as a bitrate loss beside the rebuffer win**. With no adaptive arm the one instance is `dataSaver`'s static cap — 800 kbit/s and 480p — so this is the data-saver row on every cellular profile, SuperPlayer against stock (defaults), bitrate beside the stalls and the score, each by the same rule as everywhere else — until arm (d) is built, when the section compares it with the static profile on every scenario, as Android's does. A bitrate cut with no stall win beside it is a trade that bought nothing, and reads that way.

| Scenario | Network | Metric | stock (defaults) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD (data saver) | 3G | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | 3G | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | QoE score | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | +0.127 (+21.0%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | −3770000 (−83.8%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) | −3.687 (−83.5%) — **worse** |

## Every cell

All of it, whatever it says.

### VOD

Profile for arm (c): `videoOnDemand`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 3 | 172 / 4385 (mean 1572 ± 2436, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 1.67 ± 1.15 (p50 1.00, p95 3.00, n=3) | 0.0% | 4.09 ± 0.56 (p50 4.42, p95 4.42, n=3) |
| stable WiFi | stock (naive tuning) | 3 | 168 / 169 (mean 164 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) |
| stable WiFi | SuperPlayer | 3 | 156 / 231 (mean 181 ± 44, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.44 ± 0.05 (p50 4.42, p95 4.50, n=3) |
| stable WiFi | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| congested WiFi | stock (defaults) | 3 | 1563 / 2165 (mean 1763 ± 348, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 1788333 ± 366617 (p50 2000000, p95 2000000, n=3) | 0.67 ± 1.15 (p50 0.00, p95 2.00, n=3) | 0.0% | 1.77 ± 0.41 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | stock (naive tuning) | 3 | 1675 / 2618 (mean 1950 ± 582, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 1.73 (p50 0.00, p95 3.00, n=3) | 0.0% | 1.92 ± 0.14 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | SuperPlayer | 3 | 1614 / 1618 (mean 1598 ± 31, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| LTE with dropouts | stock (defaults) | 3 | 1085 / 1128 (mean 1098 ± 26, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 3666667 ± 1443376 (p50 4500000, p95 4500000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 3.61 ± 1.40 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | stock (naive tuning) | 3 | 1076 / 1200 (mean 1116 ± 72, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | SuperPlayer | 3 | 1114 / 1202 (mean 1131 ± 64, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| 3G | stock (defaults) | 3 | 3216 / 3255 (mean 3229 ± 23, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | stock (naive tuning) | 3 | 3219 / 3230 (mean 3220 ± 10, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | SuperPlayer | 3 | 3233 / 3504 (mean 3312 ± 167, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| WiFi→cellular | stock (defaults) | 3 | 187 / 193 (mean 180 ± 18, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | stock (naive tuning) | 3 | 180 / 212 (mean 185 ± 24, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | SuperPlayer | 3 | 174 / 225 (mean 186 ± 35, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| high latency | stock (defaults) | 3 | 2647 / 2656 (mean 2648 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| high latency | stock (naive tuning) | 3 | 2623 / 2645 (mean 2621 ± 25, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| high latency | SuperPlayer | 3 | 2685 / 3654 (mean 2989 ± 577, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| high latency | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |

### live

Profile for arm (c): `liveLinear`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 3 | 218 / 336 (mean 240 ± 88, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| stable WiFi | stock (naive tuning) | 3 | 165 / 194 (mean 174 ± 17, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 1.67 ± 1.15 (p50 1.00, p95 3.00, n=3) | 0.0% | 4.08 ± 0.58 (p50 4.42, p95 4.42, n=3) |
| stable WiFi | SuperPlayer | 3 | 186 / 751 (mean 364 ± 335, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| stable WiFi | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| congested WiFi | stock (defaults) | 3 | 589 / 1548 (mean 900 ± 562, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) |
| congested WiFi | stock (naive tuning) | 3 | 548 / 2584 (mean 1225 ± 1177, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) |
| congested WiFi | SuperPlayer | 3 | 567 / 3335 (mean 1481 ± 1606, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) |
| congested WiFi | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| LTE with dropouts | stock (defaults) | 3 | 1077 / 1086 (mean 884 ± 343, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.33 ± 0.58 (p50 0.00, p95 1.00, n=3) | 0.0% | 1.99 ± 0.02 (p50 2.00, p95 2.00, n=3) |
| LTE with dropouts | stock (naive tuning) | 3 | 481 / 1362 (mean 773 ± 510, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) |
| LTE with dropouts | SuperPlayer | 3 | 487 / 1336 (mean 767 ± 493, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.97 ± 0.02 (p50 1.96, p95 2.00, n=3) |
| LTE with dropouts | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| 3G | stock (defaults) | 3 | 5056 / 5061 (mean 5055 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | stock (naive tuning) | 3 | 5061 / 5416 (mean 5176 ± 208, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | SuperPlayer | 3 | 10955 / 10965 (mean 10958 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 0.69 ± 0.00 (p50 0.69, p95 0.69, n=3) |
| 3G | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| WiFi→cellular | stock (defaults) | 3 | 165 / 170 (mean 166 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | stock (naive tuning) | 3 | 162 / 163 (mean 157 ± 9, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4222222 ± 481125 (p50 4500000, p95 4500000, n=3) | 1.33 ± 0.58 (p50 1.00, p95 2.00, n=3) | 0.0% | 4.11 ± 0.53 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | SuperPlayer | 3 | 163 / 164 (mean 163 ± 1, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 3944444 ± 481125 (p50 3666667, p95 4500000, n=3) | 1.67 ± 0.58 (p50 2.00, p95 2.00, n=3) | 0.0% | 3.81 ± 0.53 (p50 3.50, p95 4.42, n=3) |
| WiFi→cellular | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| high latency | stock (defaults) | 3 | 3217 / 3230 (mean 3022 ± 349, n=3) | 0.0030 | 0.33 ± 0.58 (p50 0.00, p95 1.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.38, n=3) |
| high latency | stock (naive tuning) | 3 | 2650 / 3243 (mean 2842 ± 348, n=3) | 0.0064 | 0.67 ± 0.58 (p50 1.00, p95 1.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| high latency | SuperPlayer | 3 | 3420 / 3448 (mean 3429 ± 16, n=3) | 0.0401 | 0.33 ± 0.58 (p50 0.00, p95 1.00, n=3) | 4083333 ± 721688 (p50 4500000, p95 4500000, n=3) | 2.33 ± 2.31 (p50 1.00, p95 5.00, n=3) | 0.0% | 3.69 ± 1.26 (p50 4.42, p95 4.42, n=3) |
| high latency | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |

### short-form

Profile for arm (c): `shortForm`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 3 | 167 / 167 (mean 160 ± 12, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| stable WiFi | stock (naive tuning) | 3 | 156 / 196 (mean 166 ± 26, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| stable WiFi | SuperPlayer | 3 | 159 / 162 (mean 160 ± 2, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| stable WiFi | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| congested WiFi | stock (defaults) | 3 | 547 / 559 (mean 550 ± 8, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| congested WiFi | stock (naive tuning) | 3 | 553 / 559 (mean 551 ± 10, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| congested WiFi | SuperPlayer | 3 | 546 / 549 (mean 546 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| congested WiFi | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| LTE with dropouts | stock (defaults) | 3 | 499 / 500 (mean 498 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| LTE with dropouts | stock (naive tuning) | 3 | 492 / 500 (mean 494 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| LTE with dropouts | SuperPlayer | 3 | 491 / 492 (mean 491 ± 1, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| LTE with dropouts | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| 3G | stock (defaults) | 3 | 2215 / 2217 (mean 2209 ± 13, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | stock (naive tuning) | 3 | 2215 / 2217 (mean 2213 ± 5, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | SuperPlayer | 3 | 2207 / 2219 (mean 2210 ± 8, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| WiFi→cellular | stock (defaults) | 3 | 158 / 160 (mean 157 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| WiFi→cellular | stock (naive tuning) | 3 | 164 / 165 (mean 161 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| WiFi→cellular | SuperPlayer | 3 | 154 / 161 (mean 156 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| WiFi→cellular | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| high latency | stock (defaults) | 3 | 3196 / 3242 (mean 3211 ± 27, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| high latency | stock (naive tuning) | 3 | 3226 / 3368 (mean 3264 ± 92, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| high latency | SuperPlayer | 3 | 3218 / 3330 (mean 3247 ± 73, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 1.96 ± 0.00 (p50 1.96, p95 1.96, n=3) |
| high latency | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |

### VOD (data saver)

Profile for arm (c): `dataSaver`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 3 | 155 / 174 (mean 161 ± 11, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.37 ± 0.00 (p50 4.37, p95 4.37, n=3) |
| stable WiFi | stock (naive tuning) | 3 | 158 / 385 (mean 233 ± 132, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.40 ± 0.02 (p50 4.42, p95 4.42, n=3) |
| stable WiFi | SuperPlayer | 3 | 160 / 175 (mean 157 ± 19, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| stable WiFi | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| congested WiFi | stock (defaults) | 3 | 1553 / 1571 (mean 1555 ± 15, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | stock (naive tuning) | 3 | 1552 / 1566 (mean 1544 ± 26, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 2000000 ± 0 (p50 2000000, p95 2000000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=3) |
| congested WiFi | SuperPlayer | 3 | 555 / 555 (mean 554 ± 2, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| congested WiFi | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| LTE with dropouts | stock (defaults) | 3 | 1082 / 1086 (mean 1080 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | stock (naive tuning) | 3 | 1081 / 1098 (mean 1085 ± 11, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| LTE with dropouts | SuperPlayer | 3 | 494 / 494 (mean 493 ± 2, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| LTE with dropouts | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| 3G | stock (defaults) | 3 | 3230 / 3233 (mean 3227 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | stock (naive tuning) | 3 | 3233 / 3446 (mean 3287 ± 140, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=3) | 0.0% | 0.60 ± 0.00 (p50 0.60, p95 0.60, n=3) |
| 3G | SuperPlayer | 3 | 2207 / 2208 (mean 2203 ± 7, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| 3G | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| WiFi→cellular | stock (defaults) | 3 | 162 / 162 (mean 160 ± 4, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | stock (naive tuning) | 3 | 169 / 175 (mean 169 ± 6, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| WiFi→cellular | SuperPlayer | 3 | 162 / 163 (mean 161 ± 3, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| WiFi→cellular | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |
| high latency | stock (defaults) | 3 | 2633 / 2646 (mean 2637 ± 8, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| high latency | stock (naive tuning) | 3 | 2638 / 2644 (mean 2624 ± 29, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 4500000 ± 0 (p50 4500000, p95 4500000, n=3) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=3) | 0.0% | 4.42 ± 0.00 (p50 4.42, p95 4.42, n=3) |
| high latency | SuperPlayer | 3 | 3237 / 3240 (mean 3232 ± 11, n=3) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 730000 ± 0 (p50 730000, p95 730000, n=3) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=3) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=3) |
| high latency | SuperPlayer (adaptive) | not built (i3, #416) | — | — | — | — | — | — | — |

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

72 cell(s), 216 aggregated session(s).
