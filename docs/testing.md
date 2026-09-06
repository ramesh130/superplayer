# The test seam

Every test in this repository drives SuperPlayer through its **public API**, under Robolectric, on
the JVM, against Media3's own fakes. No device, no network, no assertion that reaches past the
facade. This is not a default that happened to stick — it is the constraint the first slice of the
library was built to satisfy, and it holds for everything added afterwards.

`superplayer-core/src/test/kotlin/com/superplayer/core/` is the worked example.

## What the seam is

| Concern | What the tests use |
| --- | --- |
| Entry point | `SuperPlayer`, through Media3's `Player` interface |
| Runtime | Robolectric, via `androidx.test.ext.junit.runners.AndroidJUnit4` |
| Time | `androidx.media3.test.utils.FakeClock`, auto-advancing |
| Network | `androidx.media3.test.utils.FakeDataSource` over a `FakeDataSet` |
| Media | Manifests and segments generated in the test — see `SyntheticHlsStream`, `SyntheticDashStream` |
| Codecs | `ShadowMediaCodecConfig`, so the real renderer pipeline runs against shadow decoders |
| Driving playback | `androidx.media3.test.utils.robolectric.TestPlayerRunHelper` |

All of it is Media3's own test infrastructure. A hand-rolled harness would be a second, worse
implementation of a thing the engine already ships, and it would drift from the engine's semantics
exactly when that matters most — during a Media3 upgrade.

## Synthetic media, not fixtures

Streams are generated in Kotlin rather than checked in as binaries. `SyntheticHlsStream` writes a
multivariant playlist, a media playlist and an AAC segment in ADTS framing; `SyntheticDashStream`
writes an MPD, a fragmented-MP4 initialization segment and one media segment. Every field a test
asserts on — the codec, the bitrate, the sample rate — is a named constant a reader can see, and the
repository carries no media it would have to license.

The two describe deliberately equivalent media: same codec, same sample rate, same declared
bitrate. That is what lets a test compare what the facade reports for the two protocols and be
comparing the protocols rather than two unrelated pieces of media.

Each exposes `addTo(FakeDataSet)` rather than building its own set, so a test that needs more than
one stream — switching protocols mid-session — composes them into one.

## Why assertions stop at the facade

A test that reached for `player.exoPlayer` and asserted on it would be testing Media3, which Media3
already tests better than we can. The behaviour worth pinning here is *SuperPlayer's contract with
its consumers*: what the facade reports, what it forwards, what it does with the configuration it
was given. That is only observable through the public API, so that is where the assertions live.

The same rule appears in `CONTRIBUTING.md` as "tests assert externally observable behavior through
the public API". This document is the playback-shaped version of it.

## The one seam that is not public

Substituting a fake clock and a fake data source means touching `@UnstableApi` Media3 types, which
ADR-0001 rule 2 keeps out of the public API. Rather than widen the public surface for testing,
`SuperPlayer.Builder` carries a single `internal` configurator — Kotlin `internal` is visible to a
module's own unit tests — that tests use to configure the engine at construction, and nothing else.

Construction is the only thing it does. Once the player is built, the test holds a `SuperPlayer` and
talks to it as a `Player`. If a test ever needs a second such seam, that is a signal the design is
wrong: the thing being configured probably belongs in SuperPlayer's own vocabulary, as public API.

## Determinism

Tests must not sleep, poll a wall clock, or depend on ordering that real threads happen to produce.
`FakeClock` plus `TestPlayerRunHelper` make playback progress deterministic: a two-second stream
costs no wall-clock time, and a run either reaches the awaited state or fails with a timeout naming
the state it was waiting for. A flaky test in this repository is a bug in the test.

## What is not covered here

Instrumented tests on real devices, the fault-injection and network-shaping harness, and the golden
trace corpus are `superplayer-testkit`'s subject and arrive with it. They extend this seam rather
than replacing it: they still drive the library through its public API.
