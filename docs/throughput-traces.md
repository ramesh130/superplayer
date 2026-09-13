# Throughput traces

`superplayer-testkit` can replay a network under a player: a time series of bandwidth, round-trip
time and transport, delivered against the harness's clock. This document is the trace format's
specification, the rules of the replay, how to bring a public dataset into the format, and where
each of the six built-in profiles' numbers comes from.

```kotlin
val player = harness.buildPlayer(
    content = TestContent.videoLadder(),
    network = NetworkProfile.LTE_WITH_DROPOUTS.trace,
    faults = FaultScript.Builder().failWithHttpStatus(403, ResourceKind.MEDIA_SEGMENT, 5).build(),
)
```

## Why traces, and not only profiles

`PRD.md` Part 5 asks for recorded real-world traces from public datasets, and the reason is
variance: a synthetic profile is smooth in a way a real link is not, and a policy tuned against a
smooth profile looks excellent until it meets the bursty, autocorrelated thing a cellular link
actually does. `PRD.md` §7's "variance, not only mean" cannot be measured without traces that have
real variance in them.

The synthetic profiles are still needed. They are legible, a regression under one is easy to
attribute, and `PRD.md` Part 6's matrix names them. Both, not either — and both are the same type,
`ThroughputTrace`, so a benchmark cell does not care which kind it was handed.

## The format

A trace is UTF-8 text, one record per line.

```
superplayer-throughput-trace 1
# Anything after a '#' at the start of a line is a comment. Blank lines are ignored.
at-end loop
2000 5000000 WIFI
1000 0 CELLULAR
2000 5000000 CELLULAR 80
```

- **Line 1** is exactly `superplayer-throughput-trace 1`: the format's name and its version. A
  change to what a line *means* is a new version; a reader refuses a version it does not know.
- **`at-end loop` or `at-end hold`**, at most once and before the first stretch, says what happens
  after the last stretch. `loop` (the default) starts the trace again, which is what trace-driven
  evaluation does with a recorded trace shorter than the session — Mahimahi's `mm-link` wraps to the
  start of its trace. `hold` keeps the last stretch forever, which is what a handover means: a device
  that moved to cellular does not return to WiFi because the trace ran out.
- **Every other line is a stretch**: `duration_ms bandwidth_bps transport [rtt_ms]`, separated by
  whitespace.
  - `duration_ms` — how long the stretch lasts; a whole number, at least 1.
  - `bandwidth_bps` — the rate available through it, in bits per second; a whole number, 0 or more.
    Zero is a dropout: the link is there and carries nothing.
  - `transport` — `WIFI`, `CELLULAR`, `ETHERNET` or `UNKNOWN`: what the device is connected over,
    as Android's `NetworkCapabilities` would report it. **Required**, and separate from the
    bandwidth, because a WiFi-to-cellular handover is a change of network and not only of rate —
    phase 3's `BandwidthOracle` reseeds on it. The harness replays it into the platform's
    connectivity service as the clock crosses a stretch, so whatever observes the transport —
    core's own conditions, the oracle — sees the handover at the millisecond the trace names.
  - `rtt_ms` — optional, default 0: the round trip a request opened during the stretch pays before
    its first byte. Public throughput datasets do not record it, which is why it is optional.

Stretches are consecutive: the first starts at 0 ms and each starts where the last ended. The reader
is strict — a malformed line is an error naming its line number, never a line skipped, because a
trace is a test input and a reader that tolerated a bad line would replay a different network from
the one the file describes. A trace that could never deliver a byte — a looping trace with no
bandwidth anywhere, or a holding one whose last stretch has none — is refused for the same reason:
a replay that waited forever would hang a test instead of failing it.

`ThroughputTrace.parse` is the reader and `ThroughputTrace.format` the writer. Every built-in
profile survives being written and read back, and `ThroughputTraceTest` holds it to that.

## The replay

`PlaybackHarness.buildPlayer(network = …)` puts a shaping `DataSource` under every transfer the
player makes. Time zero is the moment the player was built, on the harness's clock.

- **A request pays one round trip, at open.** The trace's `rtt_ms` at the moment the request opens,
  once, before the first byte — where a real connection's setup cost lands. TCP and TLS handshakes
  are not modelled separately, and neither is the way a long round trip limits a TCP window.
- **Bytes arrive at the trace's rate, through every stretch they cross.** The time the *n*th byte
  of a transfer arrives is computed from the trace, the moment the transfer's first byte could
  arrive, and *n* — in integer arithmetic, rounded up to the millisecond the last bit lands in. A
  stretch at zero bandwidth holds a transfer until the link returns; it does not fail it.
- **Deterministic.** Nothing sleeps: every wait is on the harness's fake clock, and resolves when the
  test advances time past it. Because arrival times are computed rather than accumulated, two runs
  that open the same requests at the same times deliver the same bytes at the same times.
  `NetworkShapingTest` drives a session through a trace with a round trip, a dropout and a transport
  change three times, and asserts the three logs are identical entry for entry.
- **It composes with the fault injector.** The shaper sits in front of it, so one player carries
  both, and the order is the order a real request meets them: the round trip is paid, then the
  injector's DNS failure or status code arrives, then an injected latency delays the first byte,
  then the body — truncated or not — is paced by the trace. `FaultScript.capThroughputBps` still
  works under a trace; the slower of the two governs.
- **Measurement survives it.** The bandwidth meter's `TransferListener` is registered on the
  upstream source, as the fault injector's is, so what is reported is what moved.

One limitation is deliberate. **Each transfer sees the whole link.** Two concurrent transfers are
each paced at the trace's full rate rather than sharing it. A shared link would make each transfer's
timing depend on the order two real loading threads reached it in — the nondeterminism
`docs/testing.md` bars. The harness's synthetic content loads one transfer at a time, so for it the
question does not arise; content that loads audio and video in parallel would see more bandwidth
than the trace has.

A second is a property of the harness rather than of the replay. A shaped load runs on a loading
thread and wakes once per read, so after every advance the harness waits — bounded in real time,
failing at the bound — until each open shaped transfer is waiting for a moment still to come; without that, a test thread
with more CPU than the loading thread runs the clock ahead of the load, and the engine drains a
buffer the trace had filled. What remains is the gap between a load finishing and the engine's next
pass noticing it, which is real-thread scheduling. A test under a trace therefore asserts on what the
session did — which segments, in which order, failing where — rather than on the millisecond a load
finished or the state at one instant, which `NetworkShapingTest` asserts at the transfer level
instead.

## The six profiles

`NetworkProfile` holds the six `PRD.md` Part 6 names. Each constant carries its source in the code;
this is the summary. Round-trip time is 0 everywhere except `HIGH_LATENCY`, so that latency is the
only thing that profile changes.

| Profile | Trace | Source |
| --- | --- | --- |
| `STABLE_WIFI` | 20 Mbit/s, WiFi | Clears Netflix's published 15 Mbit/s for 4K, so the network never decides the rung |
| `CONGESTED_WIFI` | mean exactly 3 Mbit/s, each second within ±50%, WiFi, 60 s loop | Netflix's 3 Mbit/s HD threshold; one sample a second, as the HSDPA and Ghent 4G logs are |
| `LTE_WITH_DROPOUTS` | 5 Mbit/s cellular, 2 s at zero every 20 s | Netflix's 5 Mbit/s full-HD threshold; 3GPP TS 36.331's T310 runs at most 2000 ms before radio link failure; the 20 s cadence is this project's |
| `THREE_G` | 1 Mbit/s, cellular | HSDPA dataset; Pensieve §5.1's under-6 Mbit/s trace band |
| `WIFI_TO_CELLULAR_HANDOVER` | 20 Mbit/s WiFi for 10 s, then 5 Mbit/s cellular, held | The two profiles above; no outage at the switch, because its length is the HTTP stack's (ADR-0004) |
| `HIGH_LATENCY` | 20 Mbit/s, 600 ms round trip | RFC 2488 §2: a geostationary round trip is at least 558 ms |

Where a source fixes the regime but not the exact number, the number is `PRD.md`'s and the code says
so. `CONGESTED_WIFI` is antithetic pairs from a fixed-seed `java.util.Random` — whose algorithm the
Java SE API specifies — so its mean is exactly 3 Mbit/s and it is the same sequence on every JVM.

## Bringing in a public dataset

No dataset is vendored into this repository. A public dataset carries its own terms, and
`CONTRIBUTING.md` governs what may be added; the repository ships the format, the reader, the
converter, and small traces this project generated itself. Convert a dataset locally:

```bash
./gradlew convertThroughputTrace --from=bandwidth-log --transport=CELLULAR \
    --input=/path/to/report.log --output=/path/to/report.trace

./gradlew convertThroughputTrace --from=mahimahi --transport=CELLULAR --bin-ms=1000 \
    --input=/path/to/trace.down --output=/path/to/trace.trace
```

A relative path is relative to the repository root. `--transport` is required: the datasets say what
network they were measured over in their documentation rather than in the file.

| `--from` | Line format | Datasets |
| --- | --- | --- |
| `bandwidth-log` | six columns: timestamp, monotonic ms, latitude, longitude, **bytes since the last sample**, **ms since the last sample** | HSDPA 3G (Riiser et al., "Commute Path Bandwidth Traces from 3G Networks: Analysis and Applications", MMSys 2013); Ghent 4G/LTE (van der Hooft et al., "HTTP/2-Based Adaptive Streaming of HEVC Video over 4G/LTE Networks", IEEE Communications Letters 20(11), 2016) |
| `mahimahi` | one millisecond timestamp per 1500-byte delivery opportunity, looping at the last timestamp (`mm-link(1)`) | Mahimahi's own cellular traces; the Pensieve corpus (Mao et al., SIGCOMM 2017), reformatted from FCC and HSDPA traces |

`bandwidth-log` reads only the last two columns: the first is seconds in one dataset and
milliseconds in the other, and the coordinates are where a person was, which a trace has no use for
and should not carry. Each sample becomes a stretch of its elapsed time at the rate its bytes imply;
a sample with no elapsed time carries its bytes into the next. `mahimahi` counts each packet toward
the `--bin-ms` bin it is delivered by and rates each bin over its own length, the last one ending at
the trace's period.

Where each comes from, and what its terms ask, as stated by its publisher when this was written:

| Dataset | Where | Terms |
| --- | --- | --- |
| HSDPA 3G | `skuld.cs.umass.edu/traces/mmsys/2013/pathbandwidth/` | No licence stated; the page asks that users cite the dataset (the MMSys 2013 paper) |
| Ghent 4G/LTE | `users.ugent.be/~jvdrhoof/dataset-4g/` | No licence stated; the page gives the paper to cite |
| Mahimahi traces | the `traces/` directory of `github.com/ravinet/mahimahi` | The repository they ship in is licensed GPL-3.0 |
| Pensieve corpus | linked from `traces/README.md` in `github.com/hongzimao/pensieve` | The repository is MIT-licensed, but the traces are reformatted from the FCC and HSDPA datasets, whose own terms — HSDPA's request to be cited — still apply to them |

No stated licence is not permission to redistribute, so none of these is checked in. A trace from a
public dataset that does land here arrives with its `THIRD_PARTY.md` row in the same change, and
with the dataset's citation in the trace's own comment header.

The converter is plain functions in `build-logic` (`ConvertThroughputTrace.kt`), unit-tested there.
Its output for the two project-generated fixtures under
`superplayer-testkit/src/test/resources/traces/` is checked in beside them; `build-logic`'s test
holds the converter to those files byte for byte, and `superplayer-testkit`'s `ThroughputTraceTest`
reads the same files and asserts what they mean — which is what keeps a writer in one build and a
reader in another agreeing on one format.
