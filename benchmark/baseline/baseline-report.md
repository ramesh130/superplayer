# SuperPlayer benchmark — Phase 1 baseline

The fixed matrix of [`PRD.md`](../PRD.md) §6, run by `benchmark/`. Every number here is produced by this harness against content this repository generates or public streams it names (§0.2); none is carried in from anywhere else.

## What this was measured on

| | |
| --- | --- |
| SuperPlayer commit | `130bef5d309d06360d2cbf90bd00b4540b3abbcc` |
| SuperPlayer version | `0.1.0-SNAPSHOT` |
| Media3 | `1.11.0` |
| Runs per cell | 20 |
| Robolectric SDK | 35 |
| Java | 17.0.18 |
| Host | Mac OS X 26.1 aarch64, 10 cores |
| Started | 2026-09-12T11:18:41.435034Z |

## How to read this

**The three arms** (`PRD.md` §6, and `Arm.kt`):

- **stock (defaults)** — `ExoPlayer.Builder(context).build()` and nothing else. Media3's own defaults are 50 s/50 s of buffer, 2.5 s before playback starts and 5.0 s after a rebuffer, and this arm is not built with them — it is built with nothing, so it tracks whatever they become.
- **stock (naive tuning)** — the same, plus 60 s/120 s of buffer, 1.0 s before playback starts and 2.0 s after a rebuffer — the buffer configuration an app writes when it has decided its player stalls too much and has not measured why.
- **SuperPlayer** — `SuperPlayer.Builder(context).setProfile(…)`, with the profile each scenario names. What it was actually configured with is in the raw traces, on every `session_started` line.

**The metric definitions are [`docs/telemetry-schema.md`](../docs/telemetry-schema.md)**, not this document and not the source. Every metric below is computed by one function, `SessionMetrics`, from one event vocabulary, for all three arms — which is what makes the columns comparable at all. Arm (c)'s events come from the shipped `QoeCollector`; arms (a) and (b) have no SuperPlayer in them, so their events come from `StockTelemetry`, which mirrors that collector callback for callback and is held to it by `StockTelemetryAgreementTest`.

Two things about the numbers themselves:

- **Every mean carries its spread**, as `mean ± sd (p50, p95, n)`. `PRD.md` §6 requires variance and not only means, and on a shaped network the tail is usually the interesting part: a player that is usually fine and occasionally terrible has a good mean.
- **A difference smaller than the noise is reported as neutral, not as a small win.** The rule is fixed in advance: a difference counts only if it exceeds two standard errors of the difference of the means, which is the conventional rough 95% band. It is applied identically to differences in both directions.

**Rebuffer ratio is pooled.** The value printed is the sum of every session's stalled milliseconds over the sum of stalled-plus-playing, which is what the schema requires; averaging per-session ratios would weight a short session like a long one. The *verdict* beside it comes from the spread of the per-session ratios, because a pooled ratio is one number and one number cannot be told from noise.

**What the spread is a spread of.** The network under this arm is a deterministic trace replayed against a `FakeClock`, so the variance in these columns is **not** the variance a real device on a real link would see — it is what run-to-run scheduling on the host does to a session, because loads run on real threads even though playback time does not. That is worth having: it is the noise floor a difference has to clear, which is exactly what the verdicts use it for. It is not an estimate of how variable playback is in the field, and a p95 here should not be quoted as one. Field variance is the device arm's to measure.

**Time to first frame is quantised to the harness's step.** The Robolectric arm advances a `FakeClock` in fixed steps while it waits for the first frame, so a measurement lands on a multiple of that step and two arms differing by less than one step read as identical. It is the same step for every arm, so it moves no comparison, but it does put a floor on the difference this column can resolve — and a row of exactly equal start-up times across three arms is that floor rather than three players agreeing to the millisecond.

**Bitrate is sampled at ten seconds**, which is the cadence `PlaybackStateSampled` carries and therefore the resolution of any time-weighted average taken from it. A rendition held for less than one interval can fall between samples. This is identical for all three arms, so it moves no comparison, but it does mean the bitrate column is coarser than the switch column.

**0 session(s) were excluded** across the whole matrix, out of 1440 run. A session is excluded when its event stream was incomplete — `SessionEnded.droppedEventCount` non-zero, which the schema says makes a summed metric a plausible wrong number — or when it produced no `SessionEnded` at all. Nothing is excluded for being slow, for being an outlier, or for spoiling a trend.

## Where SuperPlayer is worse, or no better

This section comes before the wins deliberately. `PRD.md` §6: *publish the cases where SuperPlayer is neutral or worse — a benchmark table with no losses in it is a marketing document and will be read as one.*

### Against stock (defaults)

**Worse (37 of 144 comparisons):**

| Scenario | Network | Metric | stock (defaults) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | congested WiFi | average bitrate (bit/s) | 1343833 ± 236900 (p50 1365000, p95 1576667, n=20) | 1132167 ± 256034 (p50 1153333, p95 1576667, n=20) | −211667 (−15.8%) — **worse** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 3257208 ± 469401 (p50 3250000, p95 4083333, n=20) | 3000000 ± 209427 (p50 2833333, p95 3250000, n=20) | −257208 (−7.9%) — **worse** |
| VOD | WiFi→cellular | QoE score | 3.17 ± 0.47 (p50 3.10, p95 4.05, n=20) | 2.92 ± 0.21 (p50 2.76, p95 3.17, n=20) | −0.250 (−7.9%) — **worse** |
| live | stable WiFi | time to first frame (ms) | 443 ± 59 (p50 450, p95 500, n=20) | 485 ± 24 (p50 500, p95 500, n=20) | +42.500 (+9.6%) — **worse** |
| live | stable WiFi | switch count | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | 1.80 ± 1.28 (p50 2.00, p95 4.00, n=20) | +1.650 (+1100.0%) — **worse** |
| live | congested WiFi | switch count | 2.50 ± 1.43 (p50 3.00, p95 5.00, n=20) | 5.30 ± 2.36 (p50 5.00, p95 9.00, n=20) | +2.800 (+112.0%) — **worse** |
| live | LTE with dropouts | time to first frame (ms) | 815 ± 24 (p50 800, p95 850, n=20) | 840 ± 21 (p50 850, p95 850, n=20) | +25.000 (+3.1%) — **worse** |
| short-form | stable WiFi | rebuffer ratio | 0.0000 | 0.0019 | +0.002 — **worse** |
| short-form | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.50 ± 0.69 (p50 0.00, p95 2.00, n=20) | +0.500 — **worse** |
| short-form | stable WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 2.00 ± 0.01 (p50 2.00, p95 2.00, n=20) | −0.004 (−0.2%) — **worse** |
| short-form | congested WiFi | time to first frame (ms) | 435 ± 195 (p50 350, p95 1000, n=20) | 810 ± 282 (p50 1000, p95 1000, n=20) | +375 (+86.2%) — **worse** |
| short-form | congested WiFi | rebuffer ratio | 0.0057 | 0.0393 | +0.034 (+590.2%) — **worse** |
| short-form | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2.90 ± 0.31 (p50 3.00, p95 3.00, n=20) | +1.900 (+190.0%) — **worse** |
| short-form | congested WiFi | switch count | 4.25 ± 2.88 (p50 3.00, p95 8.00, n=20) | 6.60 ± 1.82 (p50 7.00, p95 9.00, n=20) | +2.350 (+55.3%) — **worse** |
| short-form | congested WiFi | QoE score | 1.01 ± 0.30 (p50 0.91, p95 1.51, n=20) | 0.81 ± 0.13 (p50 0.76, p95 0.96, n=20) | −0.200 (−19.9%) — **worse** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0110 | 0.0489 | +0.038 (+344.8%) — **worse** |
| short-form | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +1.000 (+100.0%) — **worse** |
| short-form | LTE with dropouts | QoE score | 1.96 ± 0.05 (p50 1.98, p95 1.98, n=20) | 1.87 ± 0.08 (p50 1.89, p95 1.91, n=20) | −0.099 (−5.0%) — **worse** |
| short-form | 3G | rebuffer ratio | 0.0082 | 0.0248 | +0.017 (+204.8%) — **worse** |
| short-form | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | +2.000 (+200.0%) — **worse** |
| short-form | 3G | QoE score | 0.35 ± 0.01 (p50 0.35, p95 0.35, n=20) | 0.31 ± 0.01 (p50 0.31, p95 0.32, n=20) | −0.035 (−10.0%) — **worse** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0243 | +0.024 — **worse** |
| short-form | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +2.000 — **worse** |
| short-form | WiFi→cellular | QoE score | 1.99 ± 0.07 (p50 2.00, p95 2.00, n=20) | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | −0.035 (−1.8%) — **worse** |
| short-form | high latency | rebuffer ratio | 0.0114 | 0.0241 | +0.013 (+111.6%) — **worse** |
| short-form | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2.35 ± 0.49 (p50 2.00, p95 3.00, n=20) | +1.350 (+135.0%) — **worse** |
| short-form | high latency | QoE score | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | −0.026 (−1.3%) — **worse** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 4283333 ± 246911 (p50 4500000, p95 4500000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −3553333 (−83.0%) — **worse** |
| VOD (data saver) | stable WiFi | QoE score | 3.41 ± 0.29 (p50 3.42, p95 3.62, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −2.683 (−78.6%) — **worse** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 1132167 ± 342681 (p50 1153333, p95 1576667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −402167 (−35.5%) — **worse** |
| VOD (data saver) | congested WiFi | QoE score | 1.03 ± 0.35 (p50 0.95, p95 1.54, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −0.300 (−29.1%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −1270000 (−63.5%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −1.218 (−62.5%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 2944708 ± 344637 (p50 2833333, p95 3666667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −2214708 (−75.2%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 2.87 ± 0.34 (p50 2.76, p95 3.55, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −2.136 (−74.5%) — **worse** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 4104167 ± 285972 (p50 4083333, p95 4500000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −3374167 (−82.2%) — **worse** |
| VOD (data saver) | high latency | QoE score | 3.91 ± 0.34 (p50 3.94, p95 4.43, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −3.181 (−81.3%) — **worse** |

**Neutral — inside the noise (67):**

| Scenario | Network | Metric | stock (defaults) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 440 ± 94 (p50 450, p95 500, n=20) | 473 ± 26 (p50 450, p95 500, n=20) | +32.500 (+7.4%) — **neutral** |
| VOD | stable WiFi | average bitrate (bit/s) | 4433650 ± 210998 (p50 4500000, p95 4500000, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | +66350 (+1.5%) — **neutral** |
| VOD | stable WiFi | switch count | 0.30 ± 0.73 (p50 0.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.300 (−100.0%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 778 ± 581 (p50 400, p95 1000, n=20) | 515 ± 271 (p50 350, p95 1000, n=20) | −263 (−33.8%) — **neutral** |
| VOD | congested WiFi | switch count | 6.40 ± 3.05 (p50 6.00, p95 11.00, n=20) | 7.05 ± 2.26 (p50 7.00, p95 11.00, n=20) | +0.650 (+10.2%) — **neutral** |
| VOD | congested WiFi | QoE score | 1.16 ± 0.25 (p50 1.08, p95 1.50, n=20) | 1.00 ± 0.26 (p50 0.97, p95 1.48, n=20) | −0.159 (−13.8%) — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | switch count | 0.25 ± 0.44 (p50 0.00, p95 1.00, n=20) | 0.20 ± 0.41 (p50 0.00, p95 1.00, n=20) | −0.050 (−20.0%) — **neutral** |
| VOD | 3G | time to first frame (ms) | 913 ± 727 (p50 750, p95 750, n=20) | 760 ± 21 (p50 750, p95 800, n=20) | −153 (−16.7%) — **neutral** |
| VOD | 3G | average bitrate (bit/s) | 388464 ± 39383 (p50 365000, p95 469286, n=20) | 383250 ± 68655 (p50 365000, p95 425833, n=20) | −5214 (−1.3%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 193 ± 34 (p50 200, p95 200, n=20) | 208 ± 18 (p50 200, p95 250, n=20) | +15.000 (+7.8%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD | WiFi→cellular | switch count | 2.20 ± 0.77 (p50 2.00, p95 4.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | −0.200 (−9.1%) — **neutral** |
| VOD | high latency | time to first frame (ms) | 1038 ± 56 (p50 1050, p95 1050, n=20) | 1050 ± 0 (p50 1050, p95 1050, n=20) | +12.500 (+1.2%) — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4104167 ± 252002 (p50 4083333, p95 4500000, n=20) | 4000000 ± 170996 (p50 4083333, p95 4083333, n=20) | −104167 (−2.5%) — **neutral** |
| VOD | high latency | QoE score | 3.89 ± 0.30 (p50 3.86, p95 4.35, n=20) | 3.91 ± 0.21 (p50 4.05, p95 4.05, n=20) | +0.019 (+0.5%) — **neutral** |
| live | stable WiFi | average bitrate (bit/s) | 4454167 ± 141718 (p50 4500000, p95 4500000, n=20) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | −58333 (−1.3%) — **neutral** |
| live | congested WiFi | time to first frame (ms) | 528 ± 565 (p50 350, p95 1000, n=20) | 503 ± 244 (p50 400, p95 1000, n=20) | −25.000 (−4.7%) — **neutral** |
| live | congested WiFi | rebuffer ratio | 0.0057 | 0.0076 | +0.002 (+33.4%) — **neutral** |
| live | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | rebuffer ratio | 0.0110 | 0.0114 | +0.000 (+3.5%) — **neutral** |
| live | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | average bitrate (bit/s) | 1989417 ± 47330 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +10583 (+0.5%) — **neutral** |
| live | LTE with dropouts | switch count | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | −0.100 (−66.7%) — **neutral** |
| live | LTE with dropouts | QoE score | 1.94 ± 0.05 (p50 1.95, p95 1.95, n=20) | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | +0.011 (+0.6%) — **neutral** |
| live | 3G | time to first frame (ms) | 938 ± 721 (p50 800, p95 800, n=20) | 775 ± 26 (p50 750, p95 800, n=20) | −163 (−17.3%) — **neutral** |
| live | 3G | rebuffer ratio | 0.0100 | 0.0076 | −0.002 (−24.2%) — **neutral** |
| live | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | 3G | average bitrate (bit/s) | 376679 ± 52228 (p50 365000, p95 365000, n=20) | 368042 ± 13603 (p50 365000, p95 365000, n=20) | −8637 (−2.3%) — **neutral** |
| live | 3G | switch count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | 3G | QoE score | 0.33 ± 0.01 (p50 0.33, p95 0.33, n=20) | 0.33 ± 0.01 (p50 0.33, p95 0.33, n=20) | +0.004 (+1.2%) — **neutral** |
| live | WiFi→cellular | time to first frame (ms) | 220 ± 38 (p50 200, p95 250, n=20) | 218 ± 24 (p50 200, p95 250, n=20) | −2.500 (−1.1%) — **neutral** |
| live | WiFi→cellular | rebuffer ratio | 0.0002 | 0.0000 | −0.000 (−100.0%) — **neutral** |
| live | WiFi→cellular | rebuffer count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.050 (−100.0%) — **neutral** |
| live | WiFi→cellular | average bitrate (bit/s) | 2903042 ± 300279 (p50 2833333, p95 3250000, n=20) | 2958333 ± 333881 (p50 2833333, p95 3666667, n=20) | +55292 (+1.9%) — **neutral** |
| live | WiFi→cellular | switch count | 2.10 ± 0.55 (p50 2.00, p95 3.00, n=20) | 2.00 ± 0.32 (p50 2.00, p95 2.00, n=20) | −0.100 (−4.8%) — **neutral** |
| live | WiFi→cellular | QoE score | 2.82 ± 0.30 (p50 2.76, p95 3.10, n=20) | 2.88 ± 0.34 (p50 2.76, p95 3.55, n=20) | +0.059 (+2.1%) — **neutral** |
| live | high latency | time to first frame (ms) | 1043 ± 59 (p50 1050, p95 1100, n=20) | 1138 ± 391 (p50 1050, p95 1050, n=20) | +95.000 (+9.1%) — **neutral** |
| live | high latency | rebuffer ratio | 0.0150 | 0.0152 | +0.000 (+1.3%) — **neutral** |
| live | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | high latency | average bitrate (bit/s) | 4041667 ± 299244 (p50 4083333, p95 4500000, n=20) | 4145833 ± 279508 (p50 4083333, p95 4500000, n=20) | +104167 (+2.6%) — **neutral** |
| live | high latency | switch count | 3.00 ± 1.52 (p50 2.00, p95 6.00, n=20) | 3.20 ± 1.88 (p50 2.00, p95 6.00, n=20) | +0.200 (+6.7%) — **neutral** |
| live | high latency | QoE score | 3.86 ± 0.34 (p50 3.94, p95 4.35, n=20) | 3.95 ± 0.34 (p50 3.86, p95 4.43, n=20) | +0.096 (+2.5%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 230 ± 25 (p50 250, p95 250, n=20) | 243 ± 18 (p50 250, p95 250, n=20) | +12.500 (+5.4%) — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| short-form | congested WiFi | average bitrate (bit/s) | 1100417 ± 298356 (p50 941667, p95 1576667, n=20) | 1021042 ± 151375 (p50 984000, p95 1153333, n=20) | −79375 (−7.2%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 808 ± 110 (p50 850, p95 850, n=20) | 820 ± 25 (p50 800, p95 850, n=20) | +12.500 (+1.5%) — **neutral** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 1989417 ± 47330 (p50 2000000, p95 2000000, n=20) | 1976717 ± 71993 (p50 2000000, p95 2000000, n=20) | −12700 (−0.6%) — **neutral** |
| short-form | LTE with dropouts | switch count | 0.15 ± 0.37 (p50 0.00, p95 1.00, n=20) | 0.40 ± 1.05 (p50 0.00, p95 2.00, n=20) | +0.250 (+166.7%) — **neutral** |
| short-form | 3G | time to first frame (ms) | 950 ± 730 (p50 800, p95 800, n=20) | 828 ± 171 (p50 800, p95 800, n=20) | −123 (−12.9%) — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | 3G | switch count | 0.10 ± 0.45 (p50 0.00, p95 0.00, n=20) | 0.25 ± 0.64 (p50 0.00, p95 2.00, n=20) | +0.150 (+150.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 230 ± 38 (p50 250, p95 250, n=20) | 225 ± 26 (p50 200, p95 250, n=20) | −5.000 (−2.2%) — **neutral** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 1986375 ± 60933 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +13625 (+0.7%) — **neutral** |
| short-form | WiFi→cellular | switch count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.050 (−100.0%) — **neutral** |
| short-form | high latency | time to first frame (ms) | 800 ± 0 (p50 800, p95 800, n=20) | 803 ± 11 (p50 800, p95 800, n=20) | +2.500 (+0.3%) — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 440 ± 193 (p50 400, p95 1000, n=20) | 385 ± 24 (p50 400, p95 400, n=20) | −55.000 (−12.5%) — **neutral** |
| VOD (data saver) | LTE with dropouts | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | 3G | time to first frame (ms) | 948 ± 731 (p50 800, p95 800, n=20) | 793 ± 18 (p50 800, p95 800, n=20) | −155 (−16.4%) — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 371083 ± 18724 (p50 365000, p95 425833, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | −6083 (−1.6%) — **neutral** |
| VOD (data saver) | 3G | switch count | 0.30 ± 0.66 (p50 0.00, p95 2.00, n=20) | 0.20 ± 0.62 (p50 0.00, p95 2.00, n=20) | −0.100 (−33.3%) — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

### Against stock (naive tuning)

**Worse (34 of 144 comparisons):**

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | congested WiFi | switch count | 5.40 ± 1.60 (p50 5.00, p95 7.00, n=20) | 7.05 ± 2.26 (p50 7.00, p95 11.00, n=20) | +1.650 (+30.6%) — **worse** |
| VOD | 3G | average bitrate (bit/s) | 511000 ± 114065 (p50 486667, p95 669167, n=20) | 383250 ± 68655 (p50 365000, p95 425833, n=20) | −127750 (−25.0%) — **worse** |
| live | stable WiFi | switch count | 0.60 ± 0.94 (p50 0.00, p95 2.00, n=20) | 1.80 ± 1.28 (p50 2.00, p95 4.00, n=20) | +1.200 (+200.0%) — **worse** |
| live | LTE with dropouts | time to first frame (ms) | 810 ± 21 (p50 800, p95 850, n=20) | 840 ± 21 (p50 850, p95 850, n=20) | +30.000 (+3.7%) — **worse** |
| short-form | stable WiFi | rebuffer ratio | 0.0000 | 0.0019 | +0.002 — **worse** |
| short-form | stable WiFi | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.50 ± 0.69 (p50 0.00, p95 2.00, n=20) | +0.500 — **worse** |
| short-form | stable WiFi | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 2.00 ± 0.01 (p50 2.00, p95 2.00, n=20) | −0.004 (−0.2%) — **worse** |
| short-form | congested WiFi | time to first frame (ms) | 403 ± 131 (p50 350, p95 400, n=20) | 810 ± 282 (p50 1000, p95 1000, n=20) | +408 (+101.2%) — **worse** |
| short-form | congested WiFi | rebuffer ratio | 0.0047 | 0.0393 | +0.035 (+727.4%) — **worse** |
| short-form | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2.90 ± 0.31 (p50 3.00, p95 3.00, n=20) | +1.900 (+190.0%) — **worse** |
| short-form | congested WiFi | switch count | 2.25 ± 1.65 (p50 1.00, p95 5.00, n=20) | 6.60 ± 1.82 (p50 7.00, p95 9.00, n=20) | +4.350 (+193.3%) — **worse** |
| short-form | congested WiFi | QoE score | 1.06 ± 0.32 (p50 0.99, p95 1.55, n=20) | 0.81 ± 0.13 (p50 0.76, p95 0.96, n=20) | −0.252 (−23.8%) — **worse** |
| short-form | LTE with dropouts | rebuffer ratio | 0.0106 | 0.0489 | +0.038 (+360.8%) — **worse** |
| short-form | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +1.000 (+100.0%) — **worse** |
| short-form | LTE with dropouts | QoE score | 1.95 ± 0.07 (p50 1.98, p95 1.98, n=20) | 1.87 ± 0.08 (p50 1.89, p95 1.91, n=20) | −0.088 (−4.5%) — **worse** |
| short-form | 3G | rebuffer ratio | 0.0076 | 0.0248 | +0.017 (+227.5%) — **worse** |
| short-form | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | +2.000 (+200.0%) — **worse** |
| short-form | 3G | QoE score | 0.35 ± 0.01 (p50 0.35, p95 0.35, n=20) | 0.31 ± 0.01 (p50 0.31, p95 0.32, n=20) | −0.039 (−11.0%) — **worse** |
| short-form | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0243 | +0.024 — **worse** |
| short-form | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +2.000 — **worse** |
| short-form | WiFi→cellular | QoE score | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | −0.050 (−2.5%) — **worse** |
| short-form | high latency | rebuffer ratio | 0.0114 | 0.0241 | +0.013 (+111.7%) — **worse** |
| short-form | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2.35 ± 0.49 (p50 2.00, p95 3.00, n=20) | +1.350 (+135.0%) — **worse** |
| short-form | high latency | QoE score | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | −0.026 (−1.3%) — **worse** |
| VOD (data saver) | stable WiFi | average bitrate (bit/s) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −3665833 (−83.4%) — **worse** |
| VOD (data saver) | stable WiFi | QoE score | 4.35 ± 0.22 (p50 4.48, p95 4.48, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −3.622 (−83.2%) — **worse** |
| VOD (data saver) | congested WiFi | average bitrate (bit/s) | 1068667 ± 294578 (p50 1153333, p95 1576667, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −338667 (−31.7%) — **worse** |
| VOD (data saver) | congested WiFi | QoE score | 0.99 ± 0.31 (p50 1.04, p95 1.54, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −0.257 (−26.1%) — **worse** |
| VOD (data saver) | LTE with dropouts | average bitrate (bit/s) | 1936500 ± 195449 (p50 2000000, p95 2000000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −1206500 (−62.3%) — **worse** |
| VOD (data saver) | LTE with dropouts | QoE score | 1.88 ± 0.20 (p50 1.95, p95 1.95, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −1.154 (−61.3%) — **worse** |
| VOD (data saver) | WiFi→cellular | average bitrate (bit/s) | 3062500 ± 477471 (p50 2833333, p95 4083333, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −2332500 (−76.2%) — **worse** |
| VOD (data saver) | WiFi→cellular | QoE score | 2.99 ± 0.48 (p50 2.76, p95 4.01, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −2.257 (−75.6%) — **worse** |
| VOD (data saver) | high latency | average bitrate (bit/s) | 4083333 ± 302282 (p50 4083333, p95 4500000, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | −3353333 (−82.1%) — **worse** |
| VOD (data saver) | high latency | QoE score | 3.88 ± 0.36 (p50 3.86, p95 4.43, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) | −3.145 (−81.2%) — **worse** |

**Neutral — inside the noise (73):**

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | time to first frame (ms) | 473 ± 26 (p50 450, p95 500, n=20) | 473 ± 26 (p50 450, p95 500, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | congested WiFi | time to first frame (ms) | 518 ± 269 (p50 350, p95 1000, n=20) | 515 ± 271 (p50 350, p95 1000, n=20) | −2.500 (−0.5%) — **neutral** |
| VOD | congested WiFi | average bitrate (bit/s) | 1111000 ± 223585 (p50 1153333, p95 1365000, n=20) | 1132167 ± 256034 (p50 1153333, p95 1576667, n=20) | +21167 (+1.9%) — **neutral** |
| VOD | congested WiFi | QoE score | 0.97 ± 0.20 (p50 1.00, p95 1.21, n=20) | 1.00 ± 0.26 (p50 0.97, p95 1.48, n=20) | +0.031 (+3.2%) — **neutral** |
| VOD | LTE with dropouts | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | LTE with dropouts | switch count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.20 ± 0.41 (p50 0.00, p95 1.00, n=20) | +0.150 (+300.0%) — **neutral** |
| VOD | 3G | QoE score | 0.43 ± 0.11 (p50 0.39, p95 0.63, n=20) | 0.38 ± 0.07 (p50 0.37, p95 0.41, n=20) | −0.050 (−11.6%) — **neutral** |
| VOD | WiFi→cellular | time to first frame (ms) | 205 ± 15 (p50 200, p95 250, n=20) | 208 ± 18 (p50 200, p95 250, n=20) | +2.500 (+1.2%) — **neutral** |
| VOD | WiFi→cellular | rebuffer ratio | 0.0002 | 0.0000 | −0.000 (−100.0%) — **neutral** |
| VOD | WiFi→cellular | rebuffer count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.050 (−100.0%) — **neutral** |
| VOD | WiFi→cellular | average bitrate (bit/s) | 3000000 ± 367742 (p50 2833333, p95 3666667, n=20) | 3000000 ± 209427 (p50 2833333, p95 3250000, n=20) | −0.000 (−0.0%) — **neutral** |
| VOD | WiFi→cellular | switch count | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | +0.050 (+2.6%) — **neutral** |
| VOD | WiFi→cellular | QoE score | 2.93 ± 0.37 (p50 2.76, p95 3.59, n=20) | 2.92 ± 0.21 (p50 2.76, p95 3.17, n=20) | −0.001 (−0.0%) — **neutral** |
| VOD | high latency | time to first frame (ms) | 1050 ± 0 (p50 1050, p95 1050, n=20) | 1050 ± 0 (p50 1050, p95 1050, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD | high latency | average bitrate (bit/s) | 4145833 ± 279508 (p50 4083333, p95 4500000, n=20) | 4000000 ± 170996 (p50 4083333, p95 4083333, n=20) | −145833 (−3.5%) — **neutral** |
| VOD | high latency | switch count | 3.30 ± 1.63 (p50 4.00, p95 6.00, n=20) | 2.40 ± 1.85 (p50 1.00, p95 6.00, n=20) | −0.900 (−27.3%) — **neutral** |
| VOD | high latency | QoE score | 3.95 ± 0.32 (p50 3.86, p95 4.35, n=20) | 3.91 ± 0.21 (p50 4.05, p95 4.05, n=20) | −0.038 (−1.0%) — **neutral** |
| live | stable WiFi | time to first frame (ms) | 470 ± 25 (p50 450, p95 500, n=20) | 485 ± 24 (p50 500, p95 500, n=20) | +15.000 (+3.2%) — **neutral** |
| live | stable WiFi | rebuffer ratio | 0.0040 | 0.0038 | −0.000 (−4.9%) — **neutral** |
| live | stable WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | average bitrate (bit/s) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | +0.000 (+0.0%) — **neutral** |
| live | stable WiFi | QoE score | 4.35 ± 0.22 (p50 4.48, p95 4.48, n=20) | 4.31 ± 0.22 (p50 4.41, p95 4.48, n=20) | −0.045 (−1.0%) — **neutral** |
| live | congested WiFi | time to first frame (ms) | 433 ± 196 (p50 350, p95 1000, n=20) | 503 ± 244 (p50 400, p95 1000, n=20) | +70.000 (+16.2%) — **neutral** |
| live | congested WiFi | rebuffer ratio | 0.0057 | 0.0076 | +0.002 (+33.4%) — **neutral** |
| live | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | congested WiFi | switch count | 3.80 ± 2.53 (p50 3.00, p95 8.00, n=20) | 5.30 ± 2.36 (p50 5.00, p95 9.00, n=20) | +1.500 (+39.5%) — **neutral** |
| live | congested WiFi | QoE score | 0.99 ± 0.26 (p50 0.87, p95 1.50, n=20) | 1.13 ± 0.31 (p50 1.08, p95 1.50, n=20) | +0.142 (+14.3%) — **neutral** |
| live | LTE with dropouts | rebuffer ratio | 0.0112 | 0.0114 | +0.000 (+1.7%) — **neutral** |
| live | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | LTE with dropouts | average bitrate (bit/s) | 1989417 ± 47330 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +10583 (+0.5%) — **neutral** |
| live | LTE with dropouts | switch count | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | −0.100 (−66.7%) — **neutral** |
| live | LTE with dropouts | QoE score | 1.94 ± 0.05 (p50 1.95, p95 1.95, n=20) | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | +0.012 (+0.6%) — **neutral** |
| live | 3G | time to first frame (ms) | 835 ± 169 (p50 800, p95 800, n=20) | 775 ± 26 (p50 750, p95 800, n=20) | −60.000 (−7.2%) — **neutral** |
| live | 3G | rebuffer ratio | 0.0081 | 0.0076 | −0.001 (−7.0%) — **neutral** |
| live | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | 3G | average bitrate (bit/s) | 368042 ± 13603 (p50 365000, p95 365000, n=20) | 368042 ± 13603 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| live | 3G | switch count | 0.10 ± 0.31 (p50 0.00, p95 1.00, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | −0.050 (−50.0%) — **neutral** |
| live | 3G | QoE score | 0.33 ± 0.02 (p50 0.33, p95 0.33, n=20) | 0.33 ± 0.01 (p50 0.33, p95 0.33, n=20) | +0.003 (+0.9%) — **neutral** |
| live | WiFi→cellular | time to first frame (ms) | 223 ± 26 (p50 200, p95 250, n=20) | 218 ± 24 (p50 200, p95 250, n=20) | −5.000 (−2.2%) — **neutral** |
| live | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| live | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| live | WiFi→cellular | average bitrate (bit/s) | 2875000 ± 128247 (p50 2833333, p95 3250000, n=20) | 2958333 ± 333881 (p50 2833333, p95 3666667, n=20) | +83333 (+2.9%) — **neutral** |
| live | WiFi→cellular | switch count | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 2.00 ± 0.32 (p50 2.00, p95 2.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | WiFi→cellular | QoE score | 2.80 ± 0.13 (p50 2.76, p95 3.17, n=20) | 2.88 ± 0.34 (p50 2.76, p95 3.55, n=20) | +0.083 (+3.0%) — **neutral** |
| live | high latency | time to first frame (ms) | 1050 ± 0 (p50 1050, p95 1050, n=20) | 1138 ± 391 (p50 1050, p95 1050, n=20) | +87.500 (+8.3%) — **neutral** |
| live | high latency | rebuffer ratio | 0.0152 | 0.0152 | +0.000 (+0.0%) — **neutral** |
| live | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | +0.000 (+0.0%) — **neutral** |
| live | high latency | average bitrate (bit/s) | 4125000 ± 299244 (p50 4083333, p95 4500000, n=20) | 4145833 ± 279508 (p50 4083333, p95 4500000, n=20) | +20833 (+0.5%) — **neutral** |
| live | high latency | switch count | 3.30 ± 1.63 (p50 4.00, p95 6.00, n=20) | 3.20 ± 1.88 (p50 2.00, p95 6.00, n=20) | −0.100 (−3.0%) — **neutral** |
| live | high latency | QoE score | 3.93 ± 0.34 (p50 3.86, p95 4.35, n=20) | 3.95 ± 0.34 (p50 3.86, p95 4.43, n=20) | +0.025 (+0.6%) — **neutral** |
| short-form | stable WiFi | time to first frame (ms) | 230 ± 25 (p50 250, p95 250, n=20) | 243 ± 18 (p50 250, p95 250, n=20) | +12.500 (+5.4%) — **neutral** |
| short-form | stable WiFi | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | stable WiFi | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| short-form | congested WiFi | average bitrate (bit/s) | 1111000 ± 311691 (p50 1153333, p95 1576667, n=20) | 1021042 ± 151375 (p50 984000, p95 1153333, n=20) | −89958 (−8.1%) — **neutral** |
| short-form | LTE with dropouts | time to first frame (ms) | 813 ± 22 (p50 800, p95 850, n=20) | 820 ± 25 (p50 800, p95 850, n=20) | +7.500 (+0.9%) — **neutral** |
| short-form | LTE with dropouts | average bitrate (bit/s) | 1978833 ± 65150 (p50 2000000, p95 2000000, n=20) | 1976717 ± 71993 (p50 2000000, p95 2000000, n=20) | −2117 (−0.1%) — **neutral** |
| short-form | LTE with dropouts | switch count | 0.20 ± 0.62 (p50 0.00, p95 2.00, n=20) | 0.40 ± 1.05 (p50 0.00, p95 2.00, n=20) | +0.200 (+100.0%) — **neutral** |
| short-form | 3G | time to first frame (ms) | 783 ± 24 (p50 800, p95 800, n=20) | 828 ± 171 (p50 800, p95 800, n=20) | +45.000 (+5.8%) — **neutral** |
| short-form | 3G | average bitrate (bit/s) | 368042 ± 13603 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | −3042 (−0.8%) — **neutral** |
| short-form | 3G | switch count | 0.25 ± 0.64 (p50 0.00, p95 2.00, n=20) | 0.25 ± 0.64 (p50 0.00, p95 2.00, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | time to first frame (ms) | 235 ± 24 (p50 250, p95 250, n=20) | 225 ± 26 (p50 200, p95 250, n=20) | −10.000 (−4.3%) — **neutral** |
| short-form | WiFi→cellular | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | WiFi→cellular | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| short-form | high latency | time to first frame (ms) | 800 ± 0 (p50 800, p95 800, n=20) | 803 ± 11 (p50 800, p95 800, n=20) | +2.500 (+0.3%) — **neutral** |
| short-form | high latency | average bitrate (bit/s) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | +0.000 (+0.0%) — **neutral** |
| short-form | high latency | switch count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |
| VOD (data saver) | congested WiFi | time to first frame (ms) | 418 ± 183 (p50 350, p95 950, n=20) | 385 ± 24 (p50 400, p95 400, n=20) | −32.500 (−7.8%) — **neutral** |
| VOD (data saver) | LTE with dropouts | switch count | 0.30 ± 0.73 (p50 0.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.300 (−100.0%) — **neutral** |
| VOD (data saver) | 3G | time to first frame (ms) | 815 ± 175 (p50 800, p95 800, n=20) | 793 ± 18 (p50 800, p95 800, n=20) | −22.500 (−2.8%) — **neutral** |
| VOD (data saver) | 3G | average bitrate (bit/s) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | +0.000 (+0.0%) — **neutral** |
| VOD (data saver) | 3G | switch count | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.20 ± 0.62 (p50 0.00, p95 2.00, n=20) | +0.150 (+300.0%) — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer ratio | 0.0000 | 0.0000 | +0.000 — **neutral** |
| VOD (data saver) | WiFi→cellular | rebuffer count | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | +0.000 — **neutral** |

**No data — one arm produced no measurement (0):**

_None._

## Where SuperPlayer is better

### Against stock (defaults)

| Scenario | Network | Metric | stock (defaults) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | rebuffer ratio | 0.1740 | 0.0000 | −0.174 (−100.0%) — **better** |
| VOD | stable WiFi | rebuffer count | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.950 (−100.0%) — **better** |
| VOD | stable WiFi | QoE score | 3.47 ± 0.27 (p50 3.62, p95 3.62, n=20) | 4.50 ± 0.00 (p50 4.50, p95 4.50, n=20) | +1.031 (+29.7%) — **better** |
| VOD | congested WiFi | rebuffer ratio | 0.0133 | 0.0000 | −0.013 (−100.0%) — **better** |
| VOD | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | LTE with dropouts | time to first frame (ms) | 820 ± 25 (p50 800, p95 850, n=20) | 805 ± 15 (p50 800, p95 850, n=20) | −15.000 (−1.8%) — **better** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | LTE with dropouts | QoE score | 1.94 ± 0.01 (p50 1.95, p95 1.95, n=20) | 2.00 ± 0.01 (p50 2.00, p95 2.00, n=20) | +0.053 (+2.7%) — **better** |
| VOD | 3G | rebuffer ratio | 0.0081 | 0.0000 | −0.008 (−100.0%) — **better** |
| VOD | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | 3G | switch count | 0.70 ± 1.03 (p50 0.00, p95 3.00, n=20) | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | −0.550 (−78.6%) — **better** |
| VOD | 3G | QoE score | 0.35 ± 0.03 (p50 0.33, p95 0.39, n=20) | 0.38 ± 0.07 (p50 0.37, p95 0.41, n=20) | +0.035 (+10.2%) — **better** |
| VOD | high latency | rebuffer ratio | 0.0150 | 0.0000 | −0.015 (−100.0%) — **better** |
| VOD | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | high latency | switch count | 3.75 ± 1.94 (p50 4.00, p95 6.00, n=20) | 2.40 ± 1.85 (p50 1.00, p95 6.00, n=20) | −1.350 (−36.0%) — **better** |
| live | stable WiFi | rebuffer ratio | 0.1813 | 0.0038 | −0.178 (−97.9%) — **better** |
| live | stable WiFi | rebuffer count | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | −0.950 (−48.7%) — **better** |
| live | stable WiFi | QoE score | 3.45 ± 0.16 (p50 3.42, p95 3.62, n=20) | 4.31 ± 0.22 (p50 4.41, p95 4.48, n=20) | +0.864 (+25.1%) — **better** |
| live | congested WiFi | average bitrate (bit/s) | 994583 ± 298356 (p50 941667, p95 1576667, n=20) | 1269750 ± 287078 (p50 1153333, p95 1576667, n=20) | +275167 (+27.7%) — **better** |
| live | congested WiFi | QoE score | 0.92 ± 0.28 (p50 0.87, p95 1.50, n=20) | 1.13 ± 0.31 (p50 1.08, p95 1.50, n=20) | +0.213 (+23.2%) — **better** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 460 ± 84 (p50 500, p95 500, n=20) | 138 ± 22 (p50 150, p95 150, n=20) | −323 (−70.1%) — **better** |
| VOD (data saver) | stable WiFi | rebuffer ratio | 0.1552 | 0.0000 | −0.155 (−100.0%) — **better** |
| VOD (data saver) | stable WiFi | rebuffer count | 1.90 ± 0.31 (p50 2.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.900 (−100.0%) — **better** |
| VOD (data saver) | stable WiFi | switch count | 0.80 ± 0.95 (p50 0.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.800 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | rebuffer ratio | 0.0057 | 0.0000 | −0.006 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | switch count | 3.95 ± 3.32 (p50 3.00, p95 11.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −3.950 (−100.0%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 838 ± 22 (p50 850, p95 850, n=20) | 343 ± 18 (p50 350, p95 350, n=20) | −495 (−59.1%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0076 | 0.0000 | −0.008 (−100.0%) — **better** |
| VOD (data saver) | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | QoE score | 0.33 ± 0.02 (p50 0.33, p95 0.38, n=20) | 0.36 ± 0.00 (p50 0.37, p95 0.37, n=20) | +0.030 (+8.9%) — **better** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 208 ± 44 (p50 200, p95 250, n=20) | 135 ± 24 (p50 150, p95 150, n=20) | −72.500 (−34.9%) — **better** |
| VOD (data saver) | WiFi→cellular | switch count | 2.10 ± 0.31 (p50 2.00, p95 3.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −2.100 (−100.0%) — **better** |
| VOD (data saver) | high latency | time to first frame (ms) | 1045 ± 60 (p50 1050, p95 1100, n=20) | 708 ± 18 (p50 700, p95 750, n=20) | −338 (−32.3%) — **better** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0150 | 0.0000 | −0.015 (−100.0%) — **better** |
| VOD (data saver) | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | switch count | 3.25 ± 2.02 (p50 2.00, p95 6.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −3.250 (−100.0%) — **better** |

### Against stock (naive tuning)

| Scenario | Network | Metric | stock (naive tuning) | SuperPlayer | Change |
| --- | --- | --- | --- | --- | --- |
| VOD | stable WiFi | rebuffer ratio | 0.0038 | 0.0000 | −0.004 (−100.0%) — **better** |
| VOD | stable WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | stable WiFi | average bitrate (bit/s) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | +104167 (+2.4%) — **better** |
| VOD | stable WiFi | switch count | 0.60 ± 0.94 (p50 0.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.600 (−100.0%) — **better** |
| VOD | stable WiFi | QoE score | 4.36 ± 0.22 (p50 4.48, p95 4.48, n=20) | 4.50 ± 0.00 (p50 4.50, p95 4.50, n=20) | +0.144 (+3.3%) — **better** |
| VOD | congested WiFi | rebuffer ratio | 0.0085 | 0.0000 | −0.009 (−100.0%) — **better** |
| VOD | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | LTE with dropouts | time to first frame (ms) | 823 ± 26 (p50 800, p95 850, n=20) | 805 ± 15 (p50 800, p95 850, n=20) | −17.500 (−2.1%) — **better** |
| VOD | LTE with dropouts | rebuffer ratio | 0.0114 | 0.0000 | −0.011 (−100.0%) — **better** |
| VOD | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | LTE with dropouts | QoE score | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) | 2.00 ± 0.01 (p50 2.00, p95 2.00, n=20) | +0.049 (+2.5%) — **better** |
| VOD | 3G | time to first frame (ms) | 1165 ± 380 (p50 1500, p95 1500, n=20) | 760 ± 21 (p50 750, p95 800, n=20) | −405 (−34.8%) — **better** |
| VOD | 3G | rebuffer ratio | 0.0138 | 0.0000 | −0.014 (−100.0%) — **better** |
| VOD | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD | 3G | switch count | 2.65 ± 2.03 (p50 2.00, p95 6.00, n=20) | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | −2.500 (−94.3%) — **better** |
| VOD | high latency | rebuffer ratio | 0.0154 | 0.0000 | −0.015 (−100.0%) — **better** |
| VOD | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| live | congested WiFi | average bitrate (bit/s) | 1089833 ± 257870 (p50 941667, p95 1576667, n=20) | 1269750 ± 287078 (p50 1153333, p95 1576667, n=20) | +179917 (+16.5%) — **better** |
| VOD (data saver) | stable WiFi | time to first frame (ms) | 480 ± 25 (p50 500, p95 500, n=20) | 138 ± 22 (p50 150, p95 150, n=20) | −343 (−71.4%) — **better** |
| VOD (data saver) | stable WiFi | rebuffer ratio | 0.0038 | 0.0000 | −0.004 (−100.0%) — **better** |
| VOD (data saver) | stable WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | stable WiFi | switch count | 0.70 ± 0.98 (p50 0.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −0.700 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | rebuffer ratio | 0.0057 | 0.0000 | −0.006 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | congested WiFi | switch count | 2.85 ± 1.66 (p50 3.00, p95 5.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −2.850 (−100.0%) — **better** |
| VOD (data saver) | LTE with dropouts | time to first frame (ms) | 808 ± 18 (p50 800, p95 850, n=20) | 343 ± 18 (p50 350, p95 350, n=20) | −465 (−57.6%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer ratio | 0.0102 | 0.0000 | −0.010 (−100.0%) — **better** |
| VOD (data saver) | LTE with dropouts | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | rebuffer ratio | 0.0081 | 0.0000 | −0.008 (−100.0%) — **better** |
| VOD (data saver) | 3G | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | 3G | QoE score | 0.33 ± 0.01 (p50 0.33, p95 0.33, n=20) | 0.36 ± 0.00 (p50 0.37, p95 0.37, n=20) | +0.036 (+11.0%) — **better** |
| VOD (data saver) | WiFi→cellular | time to first frame (ms) | 215 ± 24 (p50 200, p95 250, n=20) | 135 ± 24 (p50 150, p95 150, n=20) | −80.000 (−37.2%) — **better** |
| VOD (data saver) | WiFi→cellular | switch count | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −2.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | time to first frame (ms) | 1053 ± 11 (p50 1050, p95 1050, n=20) | 708 ± 18 (p50 700, p95 750, n=20) | −345 (−32.8%) — **better** |
| VOD (data saver) | high latency | rebuffer ratio | 0.0152 | 0.0000 | −0.015 (−100.0%) — **better** |
| VOD (data saver) | high latency | rebuffer count | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −1.000 (−100.0%) — **better** |
| VOD (data saver) | high latency | switch count | 3.60 ± 1.90 (p50 4.00, p95 6.00, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | −3.600 (−100.0%) — **better** |

## The F1 trade, as a loss

`PRD.md` F1's trade is *lower bitrate on a constrained link, in exchange for fewer stalls*, and §6 requires it to appear **as a bitrate loss beside the rebuffer win** rather than as a rebuffer win on its own. The row it is visible in today is **VOD (data saver)**, because `DATA_SAVER` is the one shipped profile that caps quality — 800 kbit/s and 480p — and therefore the one that gives bitrate up on purpose.

**What this is not.** F1 as `PRD.md` states it is *adaptive*: a bandwidth estimate lowering the rendition when the link cannot hold it. That is `superplayer-abr` and Phase 3, and it does not exist yet. What is measured below is a profile making the same trade statically, once, at construction — a real instance of the trade and a real loss to report, but not the harder thing. Reading this row as evidence that adaptive policy works would be reading it wrong.

| Network | Arm | Average bitrate (bit/s) | Rebuffer ratio | Rebuffer count | QoE score |
| --- | --- | --- | --- | --- | --- |
| 3G | stock (defaults) | 371083 ± 18724 (p50 365000, p95 425833, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.33 ± 0.02 (p50 0.33, p95 0.38, n=20) |
| 3G | stock (naive tuning) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.0081 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 0.33 ± 0.01 (p50 0.33, p95 0.33, n=20) |
| 3G | SuperPlayer | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.36 ± 0.00 (p50 0.37, p95 0.37, n=20) |
| LTE with dropouts | stock (defaults) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | stock (naive tuning) | 1936500 ± 195449 (p50 2000000, p95 2000000, n=20) | 0.0102 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1.88 ± 0.20 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | SuperPlayer | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |

## Every cell

All of it, whatever it says. Peak RSS and battery are the device arm's and are not in these tables; see below.

### VOD

Profile for arm (c): `VIDEO_ON_DEMAND`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s. Session length: 60 s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 20 | 450 / 500 (mean 440 ± 94, n=20) | 0.1740 | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 4433650 ± 210998 (p50 4500000, p95 4500000, n=20) | 0.30 ± 0.73 (p50 0.00, p95 2.00, n=20) | 0.0% | 3.47 ± 0.27 (p50 3.62, p95 3.62, n=20) |
| stable WiFi | stock (naive tuning) | 20 | 450 / 500 (mean 473 ± 26, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | 0.60 ± 0.94 (p50 0.00, p95 2.00, n=20) | 0.0% | 4.36 ± 0.22 (p50 4.48, p95 4.48, n=20) |
| stable WiFi | SuperPlayer | 20 | 450 / 500 (mean 473 ± 26, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4500000 ± 0 (p50 4500000, p95 4500000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 4.50 ± 0.00 (p50 4.50, p95 4.50, n=20) |
| congested WiFi | stock (defaults) | 20 | 400 / 1000 (mean 778 ± 581, n=20) | 0.0133 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1343833 ± 236900 (p50 1365000, p95 1576667, n=20) | 6.40 ± 3.05 (p50 6.00, p95 11.00, n=20) | 0.0% | 1.16 ± 0.25 (p50 1.08, p95 1.50, n=20) |
| congested WiFi | stock (naive tuning) | 20 | 350 / 1000 (mean 518 ± 269, n=20) | 0.0085 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1111000 ± 223585 (p50 1153333, p95 1365000, n=20) | 5.40 ± 1.60 (p50 5.00, p95 7.00, n=20) | 0.0% | 0.97 ± 0.20 (p50 1.00, p95 1.21, n=20) |
| congested WiFi | SuperPlayer | 20 | 350 / 1000 (mean 515 ± 271, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1132167 ± 256034 (p50 1153333, p95 1576667, n=20) | 7.05 ± 2.26 (p50 7.00, p95 11.00, n=20) | 0.0% | 1.00 ± 0.26 (p50 0.97, p95 1.48, n=20) |
| LTE with dropouts | stock (defaults) | 20 | 800 / 850 (mean 820 ± 25, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.25 ± 0.44 (p50 0.00, p95 1.00, n=20) | 0.0% | 1.94 ± 0.01 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | stock (naive tuning) | 20 | 800 / 850 (mean 823 ± 26, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | SuperPlayer | 20 | 800 / 850 (mean 805 ± 15, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.20 ± 0.41 (p50 0.00, p95 1.00, n=20) | 0.0% | 2.00 ± 0.01 (p50 2.00, p95 2.00, n=20) |
| 3G | stock (defaults) | 20 | 750 / 750 (mean 913 ± 727, n=20) | 0.0081 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 388464 ± 39383 (p50 365000, p95 469286, n=20) | 0.70 ± 1.03 (p50 0.00, p95 3.00, n=20) | 0.0% | 0.35 ± 0.03 (p50 0.33, p95 0.39, n=20) |
| 3G | stock (naive tuning) | 20 | 1500 / 1500 (mean 1165 ± 380, n=20) | 0.0138 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 511000 ± 114065 (p50 486667, p95 669167, n=20) | 2.65 ± 2.03 (p50 2.00, p95 6.00, n=20) | 0.0% | 0.43 ± 0.11 (p50 0.39, p95 0.63, n=20) |
| 3G | SuperPlayer | 20 | 750 / 800 (mean 760 ± 21, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 383250 ± 68655 (p50 365000, p95 425833, n=20) | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | 0.0% | 0.38 ± 0.07 (p50 0.37, p95 0.41, n=20) |
| WiFi→cellular | stock (defaults) | 20 | 200 / 200 (mean 193 ± 34, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 3257208 ± 469401 (p50 3250000, p95 4083333, n=20) | 2.20 ± 0.77 (p50 2.00, p95 4.00, n=20) | 0.0% | 3.17 ± 0.47 (p50 3.10, p95 4.05, n=20) |
| WiFi→cellular | stock (naive tuning) | 20 | 200 / 250 (mean 205 ± 15, n=20) | 0.0002 | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 3000000 ± 367742 (p50 2833333, p95 3666667, n=20) | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 0.0% | 2.93 ± 0.37 (p50 2.76, p95 3.59, n=20) |
| WiFi→cellular | SuperPlayer | 20 | 200 / 250 (mean 208 ± 18, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 3000000 ± 209427 (p50 2833333, p95 3250000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 2.92 ± 0.21 (p50 2.76, p95 3.17, n=20) |
| high latency | stock (defaults) | 20 | 1050 / 1050 (mean 1038 ± 56, n=20) | 0.0150 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4104167 ± 252002 (p50 4083333, p95 4500000, n=20) | 3.75 ± 1.94 (p50 4.00, p95 6.00, n=20) | 0.0% | 3.89 ± 0.30 (p50 3.86, p95 4.35, n=20) |
| high latency | stock (naive tuning) | 20 | 1050 / 1050 (mean 1050 ± 0, n=20) | 0.0154 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4145833 ± 279508 (p50 4083333, p95 4500000, n=20) | 3.30 ± 1.63 (p50 4.00, p95 6.00, n=20) | 0.0% | 3.95 ± 0.32 (p50 3.86, p95 4.35, n=20) |
| high latency | SuperPlayer | 20 | 1050 / 1050 (mean 1050 ± 0, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 4000000 ± 170996 (p50 4083333, p95 4083333, n=20) | 2.40 ± 1.85 (p50 1.00, p95 6.00, n=20) | 0.0% | 3.91 ± 0.21 (p50 4.05, p95 4.05, n=20) |

### live

Profile for arm (c): `LIVE_LINEAR`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s. Session length: 60 s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 20 | 450 / 500 (mean 443 ± 59, n=20) | 0.1813 | 1.95 ± 0.22 (p50 2.00, p95 2.00, n=20) | 4454167 ± 141718 (p50 4500000, p95 4500000, n=20) | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | 0.0% | 3.45 ± 0.16 (p50 3.42, p95 3.62, n=20) |
| stable WiFi | stock (naive tuning) | 20 | 450 / 500 (mean 470 ± 25, n=20) | 0.0040 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | 0.60 ± 0.94 (p50 0.00, p95 2.00, n=20) | 0.0% | 4.35 ± 0.22 (p50 4.48, p95 4.48, n=20) |
| stable WiFi | SuperPlayer | 20 | 500 / 500 (mean 485 ± 24, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | 1.80 ± 1.28 (p50 2.00, p95 4.00, n=20) | 0.0% | 4.31 ± 0.22 (p50 4.41, p95 4.48, n=20) |
| congested WiFi | stock (defaults) | 20 | 350 / 1000 (mean 528 ± 565, n=20) | 0.0057 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 994583 ± 298356 (p50 941667, p95 1576667, n=20) | 2.50 ± 1.43 (p50 3.00, p95 5.00, n=20) | 0.0% | 0.92 ± 0.28 (p50 0.87, p95 1.50, n=20) |
| congested WiFi | stock (naive tuning) | 20 | 350 / 1000 (mean 433 ± 196, n=20) | 0.0057 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1089833 ± 257870 (p50 941667, p95 1576667, n=20) | 3.80 ± 2.53 (p50 3.00, p95 8.00, n=20) | 0.0% | 0.99 ± 0.26 (p50 0.87, p95 1.50, n=20) |
| congested WiFi | SuperPlayer | 20 | 400 / 1000 (mean 503 ± 244, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1269750 ± 287078 (p50 1153333, p95 1576667, n=20) | 5.30 ± 2.36 (p50 5.00, p95 9.00, n=20) | 0.0% | 1.13 ± 0.31 (p50 1.08, p95 1.50, n=20) |
| LTE with dropouts | stock (defaults) | 20 | 800 / 850 (mean 815 ± 24, n=20) | 0.0110 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1989417 ± 47330 (p50 2000000, p95 2000000, n=20) | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | 0.0% | 1.94 ± 0.05 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | stock (naive tuning) | 20 | 800 / 850 (mean 810 ± 21, n=20) | 0.0112 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1989417 ± 47330 (p50 2000000, p95 2000000, n=20) | 0.15 ± 0.49 (p50 0.00, p95 1.00, n=20) | 0.0% | 1.94 ± 0.05 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | SuperPlayer | 20 | 850 / 850 (mean 840 ± 21, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| 3G | stock (defaults) | 20 | 800 / 800 (mean 938 ± 721, n=20) | 0.0100 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 376679 ± 52228 (p50 365000, p95 365000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.33 ± 0.01 (p50 0.33, p95 0.33, n=20) |
| 3G | stock (naive tuning) | 20 | 800 / 800 (mean 835 ± 169, n=20) | 0.0081 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 368042 ± 13603 (p50 365000, p95 365000, n=20) | 0.10 ± 0.31 (p50 0.00, p95 1.00, n=20) | 0.0% | 0.33 ± 0.02 (p50 0.33, p95 0.33, n=20) |
| 3G | SuperPlayer | 20 | 750 / 800 (mean 775 ± 26, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 368042 ± 13603 (p50 365000, p95 365000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.33 ± 0.01 (p50 0.33, p95 0.33, n=20) |
| WiFi→cellular | stock (defaults) | 20 | 200 / 250 (mean 220 ± 38, n=20) | 0.0002 | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 2903042 ± 300279 (p50 2833333, p95 3250000, n=20) | 2.10 ± 0.55 (p50 2.00, p95 3.00, n=20) | 0.0% | 2.82 ± 0.30 (p50 2.76, p95 3.10, n=20) |
| WiFi→cellular | stock (naive tuning) | 20 | 200 / 250 (mean 223 ± 26, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2875000 ± 128247 (p50 2833333, p95 3250000, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 2.80 ± 0.13 (p50 2.76, p95 3.17, n=20) |
| WiFi→cellular | SuperPlayer | 20 | 200 / 250 (mean 218 ± 24, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2958333 ± 333881 (p50 2833333, p95 3666667, n=20) | 2.00 ± 0.32 (p50 2.00, p95 2.00, n=20) | 0.0% | 2.88 ± 0.34 (p50 2.76, p95 3.55, n=20) |
| high latency | stock (defaults) | 20 | 1050 / 1100 (mean 1043 ± 59, n=20) | 0.0150 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4041667 ± 299244 (p50 4083333, p95 4500000, n=20) | 3.00 ± 1.52 (p50 2.00, p95 6.00, n=20) | 0.0% | 3.86 ± 0.34 (p50 3.94, p95 4.35, n=20) |
| high latency | stock (naive tuning) | 20 | 1050 / 1050 (mean 1050 ± 0, n=20) | 0.0152 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4125000 ± 299244 (p50 4083333, p95 4500000, n=20) | 3.30 ± 1.63 (p50 4.00, p95 6.00, n=20) | 0.0% | 3.93 ± 0.34 (p50 3.86, p95 4.35, n=20) |
| high latency | SuperPlayer | 20 | 1050 / 1050 (mean 1138 ± 391, n=20) | 0.0152 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4145833 ± 279508 (p50 4083333, p95 4500000, n=20) | 3.20 ± 1.88 (p50 2.00, p95 6.00, n=20) | 0.0% | 3.95 ± 0.34 (p50 3.86, p95 4.43, n=20) |

### short-form

Profile for arm (c): `SHORT_FORM`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s. Session length: 60 s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 20 | 250 / 250 (mean 230 ± 25, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) |
| stable WiFi | stock (naive tuning) | 20 | 250 / 250 (mean 230 ± 25, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) |
| stable WiFi | SuperPlayer | 20 | 250 / 250 (mean 243 ± 18, n=20) | 0.0019 | 0.50 ± 0.69 (p50 0.00, p95 2.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 2.00 ± 0.01 (p50 2.00, p95 2.00, n=20) |
| congested WiFi | stock (defaults) | 20 | 350 / 1000 (mean 435 ± 195, n=20) | 0.0057 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1100417 ± 298356 (p50 941667, p95 1576667, n=20) | 4.25 ± 2.88 (p50 3.00, p95 8.00, n=20) | 0.0% | 1.01 ± 0.30 (p50 0.91, p95 1.51, n=20) |
| congested WiFi | stock (naive tuning) | 20 | 350 / 400 (mean 403 ± 131, n=20) | 0.0047 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1111000 ± 311691 (p50 1153333, p95 1576667, n=20) | 2.25 ± 1.65 (p50 1.00, p95 5.00, n=20) | 0.0% | 1.06 ± 0.32 (p50 0.99, p95 1.55, n=20) |
| congested WiFi | SuperPlayer | 20 | 1000 / 1000 (mean 810 ± 282, n=20) | 0.0393 | 2.90 ± 0.31 (p50 3.00, p95 3.00, n=20) | 1021042 ± 151375 (p50 984000, p95 1153333, n=20) | 6.60 ± 1.82 (p50 7.00, p95 9.00, n=20) | 0.0% | 0.81 ± 0.13 (p50 0.76, p95 0.96, n=20) |
| LTE with dropouts | stock (defaults) | 20 | 850 / 850 (mean 808 ± 110, n=20) | 0.0110 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1989417 ± 47330 (p50 2000000, p95 2000000, n=20) | 0.15 ± 0.37 (p50 0.00, p95 1.00, n=20) | 0.0% | 1.96 ± 0.05 (p50 1.98, p95 1.98, n=20) |
| LTE with dropouts | stock (naive tuning) | 20 | 800 / 850 (mean 813 ± 22, n=20) | 0.0106 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1978833 ± 65150 (p50 2000000, p95 2000000, n=20) | 0.20 ± 0.62 (p50 0.00, p95 2.00, n=20) | 0.0% | 1.95 ± 0.07 (p50 1.98, p95 1.98, n=20) |
| LTE with dropouts | SuperPlayer | 20 | 800 / 850 (mean 820 ± 25, n=20) | 0.0489 | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 1976717 ± 71993 (p50 2000000, p95 2000000, n=20) | 0.40 ± 1.05 (p50 0.00, p95 2.00, n=20) | 0.0% | 1.87 ± 0.08 (p50 1.89, p95 1.91, n=20) |
| 3G | stock (defaults) | 20 | 800 / 800 (mean 950 ± 730, n=20) | 0.0082 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.10 ± 0.45 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.35 ± 0.01 (p50 0.35, p95 0.35, n=20) |
| 3G | stock (naive tuning) | 20 | 800 / 800 (mean 783 ± 24, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 368042 ± 13603 (p50 365000, p95 365000, n=20) | 0.25 ± 0.64 (p50 0.00, p95 2.00, n=20) | 0.0% | 0.35 ± 0.01 (p50 0.35, p95 0.35, n=20) |
| 3G | SuperPlayer | 20 | 800 / 800 (mean 828 ± 171, n=20) | 0.0248 | 3.00 ± 0.00 (p50 3.00, p95 3.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.25 ± 0.64 (p50 0.00, p95 2.00, n=20) | 0.0% | 0.31 ± 0.01 (p50 0.31, p95 0.32, n=20) |
| WiFi→cellular | stock (defaults) | 20 | 250 / 250 (mean 230 ± 38, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 1986375 ± 60933 (p50 2000000, p95 2000000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.99 ± 0.07 (p50 2.00, p95 2.00, n=20) |
| WiFi→cellular | stock (naive tuning) | 20 | 250 / 250 (mean 235 ± 24, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) |
| WiFi→cellular | SuperPlayer | 20 | 200 / 250 (mean 225 ± 26, n=20) | 0.0243 | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| high latency | stock (defaults) | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) |
| high latency | stock (naive tuning) | 20 | 800 / 800 (mean 800 ± 0, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.98 ± 0.00 (p50 1.98, p95 1.98, n=20) |
| high latency | SuperPlayer | 20 | 800 / 800 (mean 803 ± 11, n=20) | 0.0241 | 2.35 ± 0.49 (p50 2.00, p95 3.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |

### VOD (data saver)

Profile for arm (c): `DATA_SAVER`. Ladder: 365 kbit/s, 730 kbit/s, 2000 kbit/s, 4500 kbit/s. Session length: 60 s.

| Network | Arm | Runs | TTFF p50 / p95 (ms) | Rebuffer ratio | Rebuffer count | Bitrate (bit/s) | Switches | Startup failures | QoE score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable WiFi | stock (defaults) | 20 | 500 / 500 (mean 460 ± 84, n=20) | 0.1552 | 1.90 ± 0.31 (p50 2.00, p95 2.00, n=20) | 4283333 ± 246911 (p50 4500000, p95 4500000, n=20) | 0.80 ± 0.95 (p50 0.00, p95 2.00, n=20) | 0.0% | 3.41 ± 0.29 (p50 3.42, p95 3.62, n=20) |
| stable WiFi | stock (naive tuning) | 20 | 500 / 500 (mean 480 ± 25, n=20) | 0.0038 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4395833 ± 185109 (p50 4500000, p95 4500000, n=20) | 0.70 ± 0.98 (p50 0.00, p95 2.00, n=20) | 0.0% | 4.35 ± 0.22 (p50 4.48, p95 4.48, n=20) |
| stable WiFi | SuperPlayer | 20 | 150 / 150 (mean 138 ± 22, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| congested WiFi | stock (defaults) | 20 | 400 / 1000 (mean 440 ± 193, n=20) | 0.0057 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1132167 ± 342681 (p50 1153333, p95 1576667, n=20) | 3.95 ± 3.32 (p50 3.00, p95 11.00, n=20) | 0.0% | 1.03 ± 0.35 (p50 0.95, p95 1.54, n=20) |
| congested WiFi | stock (naive tuning) | 20 | 350 / 950 (mean 418 ± 183, n=20) | 0.0057 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1068667 ± 294578 (p50 1153333, p95 1576667, n=20) | 2.85 ± 1.66 (p50 3.00, p95 5.00, n=20) | 0.0% | 0.99 ± 0.31 (p50 1.04, p95 1.54, n=20) |
| congested WiFi | SuperPlayer | 20 | 400 / 400 (mean 385 ± 24, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| LTE with dropouts | stock (defaults) | 20 | 850 / 850 (mean 838 ± 22, n=20) | 0.0114 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 2000000 ± 0 (p50 2000000, p95 2000000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 1.95 ± 0.00 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | stock (naive tuning) | 20 | 800 / 850 (mean 808 ± 18, n=20) | 0.0102 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 1936500 ± 195449 (p50 2000000, p95 2000000, n=20) | 0.30 ± 0.73 (p50 0.00, p95 2.00, n=20) | 0.0% | 1.88 ± 0.20 (p50 1.95, p95 1.95, n=20) |
| LTE with dropouts | SuperPlayer | 20 | 350 / 350 (mean 343 ± 18, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| 3G | stock (defaults) | 20 | 800 / 800 (mean 948 ± 731, n=20) | 0.0076 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 371083 ± 18724 (p50 365000, p95 425833, n=20) | 0.30 ± 0.66 (p50 0.00, p95 2.00, n=20) | 0.0% | 0.33 ± 0.02 (p50 0.33, p95 0.38, n=20) |
| 3G | stock (naive tuning) | 20 | 800 / 800 (mean 815 ± 175, n=20) | 0.0081 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.05 ± 0.22 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.33 ± 0.01 (p50 0.33, p95 0.33, n=20) |
| 3G | SuperPlayer | 20 | 800 / 800 (mean 793 ± 18, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 365000 ± 0 (p50 365000, p95 365000, n=20) | 0.20 ± 0.62 (p50 0.00, p95 2.00, n=20) | 0.0% | 0.36 ± 0.00 (p50 0.37, p95 0.37, n=20) |
| WiFi→cellular | stock (defaults) | 20 | 200 / 250 (mean 208 ± 44, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 2944708 ± 344637 (p50 2833333, p95 3666667, n=20) | 2.10 ± 0.31 (p50 2.00, p95 3.00, n=20) | 0.0% | 2.87 ± 0.34 (p50 2.76, p95 3.55, n=20) |
| WiFi→cellular | stock (naive tuning) | 20 | 200 / 250 (mean 215 ± 24, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 3062500 ± 477471 (p50 2833333, p95 4083333, n=20) | 2.00 ± 0.00 (p50 2.00, p95 2.00, n=20) | 0.0% | 2.99 ± 0.48 (p50 2.76, p95 4.01, n=20) |
| WiFi→cellular | SuperPlayer | 20 | 150 / 150 (mean 135 ± 24, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |
| high latency | stock (defaults) | 20 | 1050 / 1100 (mean 1045 ± 60, n=20) | 0.0150 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4104167 ± 285972 (p50 4083333, p95 4500000, n=20) | 3.25 ± 2.02 (p50 2.00, p95 6.00, n=20) | 0.0% | 3.91 ± 0.34 (p50 3.94, p95 4.43, n=20) |
| high latency | stock (naive tuning) | 20 | 1050 / 1050 (mean 1053 ± 11, n=20) | 0.0152 | 1.00 ± 0.00 (p50 1.00, p95 1.00, n=20) | 4083333 ± 302282 (p50 4083333, p95 4500000, n=20) | 3.60 ± 1.90 (p50 4.00, p95 6.00, n=20) | 0.0% | 3.88 ± 0.36 (p50 3.86, p95 4.43, n=20) |
| high latency | SuperPlayer | 20 | 700 / 750 (mean 708 ± 18, n=20) | 0.0000 | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 730000 ± 0 (p50 730000, p95 730000, n=20) | 0.00 ± 0.00 (p50 0.00, p95 0.00, n=20) | 0.0% | 0.73 ± 0.00 (p50 0.73, p95 0.73, n=20) |

## Peak RSS and battery — the device arm

`PRD.md` §6 reports peak RSS and battery delta over a 30-minute session, and **neither is in the tables above**. They cannot be: both are properties of a process on a device with a battery, and everything above runs under Robolectric on a JVM. Reporting a heap figure from a JVM as though it were an Android app's resident set would be a plausible wrong number, which is the failure mode this whole harness is arranged against.

The device arm is `benchmark/`'s own app, `BenchmarkActivity`: given an arm, one of the public streams below and a duration, it plays a 30-minute session on a real device over a real network and writes its telemetry in the same trace format as the rows above. It measures neither number itself — an app measuring its own memory would be measuring the measurement — and both are read off the process from outside, by Perfetto's `process_memory` and `battery` data sources.

**The harness around that app is not built yet.** `devicelab/` is this repository's entry point for device measurement and is currently shaped around the demo; wiring a second app through it is issue #95. `benchmark/README.md` has the manual `adb` recipe in the meantime, and says why a benchmark sits outside `docs/testing.md`'s no-device rule rather than against it.

**These columns are unpopulated in this baseline.** A device run has not been taken against this commit, and a table of dashes is the honest way to say so — the alternative being to leave the metrics out of the document and let a reader assume the matrix covered them.

| Metric | Arm (a) | Arm (b) | Arm (c) |
| --- | --- | --- | --- |
| Peak RSS (MiB) | — | — | — |
| Battery delta over 30 min (%) | — | — | — |

## The content, and where it comes from

**The Robolectric arm plays generated content**, not fetched streams: rendition ladders synthesized by `superplayer-testkit`'s `PlaybackHarness` over Media3's own adaptive fakes, at the bitrates each scenario names. That is the arm that has to be reproducible, and a stream fetched over the internet is the one thing that cannot be — the same reasoning `docs/testing.md` gives for barring the network from the test suite. The ladders follow Apple's HLS Authoring Specification in shape; the exact rungs are chosen against `PRD.md` §6's network profiles, and `Scenario.kt` argues them.

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

## Raw traces

`PRD.md` §6 requires them published, because a summary is an argument and a trace is evidence: every number above can be recomputed from these files, and a statistic this report did not think to print can be taken from them.

One JSONL file per cell in `traces/`, one line per telemetry event, field names as `LogcatSink` writes them. Each line carries its scenario, network, arm and run index, so files concatenate without losing what they were.

```text
grep '"evt":"rebuffer_ended"' traces/*.jsonl     # every stall in the matrix
grep '"run":3' traces/vod__3g__superplayer.jsonl   # one session, end to end
```

72 cell(s), 1440 aggregated session(s).

