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
| Platform state | Robolectric's own shadows — see "Asserting on the platform" below |

All of it is Media3's own test infrastructure, apart from the last row, which is Robolectric's. A
hand-rolled harness would be a second, worse
implementation of a thing the engine already ships, and it would drift from the engine's semantics
exactly when that matters most — during a Media3 upgrade.

## Where the seam lives

`SuperPlayerHarness` is a JUnit rule, and it is the one place the four decisions above are made: the
auto-advancing clock, the fake data source over a `FakeDataSet`, Media3's `DefaultMediaSourceFactory`
over that, and all of it reaching the engine through the internal configurator. A test asks it for a
player and gets one:

```kotlin
@get:Rule val harness = SuperPlayerHarness()

@Test fun something() {
    val player = harness.buildPlayer()
    // ...no try/finally, and no @After
}
```

It also **releases every player it built**, which is the other half of why it is a rule rather than a
function. A player a failing assertion left unreleased holds a playback thread and a codec for the
rest of the run, and the alternative is `try { } finally { player.release() }` around the body of
every test — which a test building two players has to nest.

What it does not do is drive playback. Tests reach `TestPlayerRunHelper` themselves, because what a
test waits for is part of what it asserts, and hiding that here would make the interesting half of a
test invisible. The one exception is `settle`, which drains a player's pending commands using a clock
the test has no other handle on.

Composing the media is still the test's: `buildPlayer` serves the synthetic HLS stream by default,
and a test that switches protocols or needs a longer stream passes its own `FakeDataSet`.

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

## Asserting on the platform

There is one class of behaviour the facade cannot report, and it is the class SuperPlayer's
lifecycle correctness lives in. Whether a player requested audio focus, and whether it is holding a
wake lock, are facts about the *Android framework* rather than about the player — nothing on
`Player` exposes either, and nothing should.

`SuperPlayerLifecycleTest` therefore asserts on Robolectric's shadows of the framework:
`ShadowAudioManager` for the focus request the engine made and the one it abandoned,
`ShadowPowerManager` for the lock it holds. That is not a hole in the rule above but the same rule
applied one layer out — the assertions are still on externally observable behaviour, and still make
no claim about SuperPlayer's or Media3's internals. A test that reached into Media3's
`AudioFocusManager` would be the violation; one that checks what the `AudioManager` was actually
asked for is what a device would show.

Two consequences follow, and both cost a line in `setUp`:

- **Robolectric is a declared test dependency**, not only a transitive one.
  `media3-test-utils-robolectric` is an AAR whose dependencies are runtime-scoped, so naming a
  shadow type needs the explicit declaration. `THIRD_PARTY.md` records why.
- **Permissions must be granted by the test.** Robolectric grants an application none by default,
  not even ones its merged manifest declares, and Media3 checks for `WAKE_LOCK` before taking a
  lock. A test that forgets this sees no wake lock and no error — Media3 logs a warning and carries
  on — so the grant is explicit and commented where it happens.

Simulating what the platform does *back* is the test's job too: Robolectric records a focus request
but never calls the listener, so the focus-loss tests take the listener off the recorded request and
deliver the callback themselves.

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

That rule extends past the player. **Anything the engine changes on another thread must be awaited,
not sampled** — and a wake lock is the worked example: Media3 takes and releases it on the playback
thread, one post behind the state change that caused it, so reading `isHeld` straight after the
command that should have changed it answers the previous question. It passes on an idle laptop and
fails on a loaded CI runner, which is exactly what it did. `RobolectricUtil.runMainLooperUntil` is
the tool — Media3's own, and the mechanism `TestPlayerRunHelper` waits with — so the wait either
succeeds or times out saying what it wanted.

Two checks are worth running on any test that pins asynchronous behaviour, and both were run on the
wake-lock ones: mutate the production code and confirm the test fails, and run the suite under heavy
CPU contention to see whether the timing holds.

Neither is a guarantee. The auto-advancing clock has a limit worth knowing about: it fast-forwards
past timers *inside* the engine as well as the ones a test cares about. Media3's `WakeLockManager`
arms a 1-second safety net on every release and force-releases the lock if its own thread has not
answered by then — so a test that deliberately stalls the player gives fake time a thousand
milliseconds to race ahead of real scheduling, and the net fires. ADR-0006 records where that stopped
a test from being written. If a behaviour under test is itself timer-driven, the fake clock is the
thing to question first.

## What is not covered here

Instrumented tests on real devices, the fault-injection and network-shaping harness, and the golden
trace corpus are `superplayer-testkit`'s subject and arrive with it. They extend this seam rather
than replacing it: they still drive the library through its public API.
