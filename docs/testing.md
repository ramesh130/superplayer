# The test seam

Every test in this repository drives SuperPlayer through its **public API**, under Robolectric, on
the JVM, against Media3's own fakes. No device, no network, no assertion that reaches past the
facade. This is not a default that happened to stick — it is the constraint the first slice of the
library was built to satisfy, and it holds for everything added afterwards.

There are two documented exceptions, and both are still tests with no device and no network.

`SuperPlayerTransferChainTest` keeps the data source chain `SuperPlayer.Builder` assembles instead of
substituting a fake over it, and reads a `file:` URI. "The one player that keeps its own transfer
chain" below says why that is necessary rather than merely convenient.

`TelemetryDeliveryTest` constructs `TelemetryDelivery` directly, with no player anywhere. What it
tests is not playback but the *queue between* a collector and a consumer's sink: a bound, a drop
policy, and a delivery thread (ADR-0008 rules 3 and 4). Every one of its assertions is about a state
the queue can only be held in deliberately — full, stalled, or emptied by memory pressure — and
provoking those through a player would mean making a real sink block for real seconds while real
content played, which is a slower test that asserts less. `QoeCollectorTest` covers the composition
from the other side: that a collector submits rather than calls, and that a consumer's sink is
reached at all through the real builder.

For the same reason `QoeCollector` carries one `internal` test hook, `awaitDelivered`. Delivery is
asynchronous by design, so an assertion made without waiting for it is a race rather than a test; the
hook is `internal` precisely so that a consumer cannot reach for it and call it from the thread this
design exists to keep out of the sink. It is a *waiting* seam rather than a configuring one, which is
why it is not the "second seam" the next section warns about.

`superplayer-core/src/test/kotlin/com/superplayer/core/` is the worked example.

## What the seam is

| Concern | What the tests use |
| --- | --- |
| Entry point | `SuperPlayer`, through Media3's `Player` interface |
| Runtime | Robolectric, via `androidx.test.ext.junit.runners.AndroidJUnit4` |
| Time | `androidx.media3.test.utils.FakeClock`, auto-advancing |
| Network | `androidx.media3.test.utils.FakeDataSource` over a `FakeDataSet` — except in the one class named above, which reads a `file:` URI through the real chain |
| Media | Manifests and segments generated in the test — see `SyntheticHlsStream`, `SyntheticDashStream` |
| Codecs | `ShadowMediaCodecConfig`, so the real renderer pipeline runs against shadow decoders |
| Driving playback | `androidx.media3.test.utils.robolectric.TestPlayerRunHelper` |
| Platform state | Robolectric's own shadows — see "Asserting on the platform" below |
| Device capability | `TestDevice`, which states the codec table and memory a test runs against |
| External surfaces | `androidx.media3.session.MediaController` — see "Driving a session" below |

All of it is Media3's own test infrastructure, apart from the platform-state row, which is
Robolectric's. A
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

### The one player that keeps its own transfer chain

Substituting `FakeDataSource` replaces the whole `DataSource.Factory` chain `SuperPlayer.Builder`
assembles, so no test built that way can see the chain at all — and since issue #22 that chain is
SuperPlayer's own composition (`TransferChain`) rather than a Media3 default, which means SuperPlayer
can now get it wrong. `buildPlayerOnItsOwnTransferChain` is the exception: it substitutes the clock
and nothing else, and plays a `file:` URI written by `SyntheticHlsStream.writeTo` — the nearest thing
to a network fetch a test with no network can ask for. `SuperPlayerTransferChainTest` is its only
caller, and it also pins the other half: an engine configurator's media source factory still wins
over whatever `build()` installed, which is what keeps every test above working.

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

## Stating the device

`PlayerPool`'s bound is derived from what the device reports — its concurrent decoder instances and
the heap this app is allowed — so a test that touches a pool is running on a device whether it says so or not.
Robolectric's default one reports an empty codec table and a 16 MB heap, which `DeviceCapacity.kt`
correctly reads as a pool of one. That is a real case worth pinning and a silent trap for every other
pool test: a bound of one makes an assertion about two players fail, and makes one about never
handing out three pass for the wrong reason.

So `TestDevice` states the device explicitly. `PlayerPoolCapacityTest` states a different one per
test, because the derivation is its subject; `PlayerPoolTest` states a capable one once, because
recycling is its subject and the machine it runs on should not be part of the answer. It is the same
move as granting `WAKE_LOCK`: a Robolectric default that does not resemble a real device is corrected
in the test, where a reader can see it.

One limit is worth knowing before writing another of these. Robolectric's `CodecCapabilities` always
answers 32 for `getMaxSupportedInstances` and offers no way to lower it, so a test cannot feed in a
decoder limit and read it back. The tests therefore pin **which limit binds** — decoder or memory —
rather than a number they supplied, which a derivation that ignored the platform could not produce by
accident.

## Proving a player was released

There is no `isReleased` on `Player`, and a released Media3 player answers most questions the way a
live one does: it reports its last state, accepts `setMediaItem`, and counts the item. What it stops
doing is calling listeners — Media3 releases the listener set with the engine, and a released set is
inert. `PlayerPoolTest.deliversEvents` is that check, and it carries its own positive control, so
"the pool released every player" cannot pass by accident.

The wake lock was the obvious alternative and it is the wrong tool for this. A lock is released when
playback *ends* as much as when the engine does, and the synthetic stream ends inside the window the
test would watch — so the assertion passed against a pool that released nothing, which is how the
mistake was found. It was also flaky on its own terms: polling `isHeld` from the main looper races
Robolectric's bookkeeping on the playback thread, and surfaced as a `ConcurrentModificationException`
inside `ShadowPowerManager`. `SuperPlayerLifecycleTest` still uses the lock, correctly — there the
lock *is* the behaviour under test rather than a proxy for something else.

## Driving a session

`SuperPlayerSessionTest` connects a real `MediaController` to a real `PlaybackSession`, in process,
and drives playback through it. That is not a hole in "assertions stop at the facade" either: a
controller *is* the notification, the lock screen, the car head unit and the watch — it is the only
thing any of them ever is — and it speaks Media3's stable `Player` API and nothing else. So a test
written through one is a test of exactly what an external surface can see and do, which is the whole
of what a session promises.

The alternative would have been to assert on the `MediaSession` object, which would say that
SuperPlayer called Media3 correctly. That is a much weaker claim than that a controller works, and
it would keep passing through the exact regression worth catching — a metadata field that stops
being published, or a resolved media id that loses its resume position.

`SuperPlayerServiceTest` is the one place that rule is deliberately relaxed, and the exception is
narrow enough to name: it asserts that a created service **registered** its session
(`service.sessions`), which is a fact about an Android component rather than about playback and
which no controller can see. A service that built a session and never registered it looks identical
from a controller's side and posts no notification — so the assertion has to be made where the
difference is visible. Everything else that file checks, it checks through a controller or through
the player's observable state.

Two mechanics are worth copying:

- **The connection is awaited, not assumed.** `MediaController.Builder(...).buildAsync()` completes
  on the application looper once the session has answered, so `RobolectricUtil.runMainLooperUntil`
  waits for it. Reading the future before that reads the question.
- **Controllers are released before sessions.** A controller left connected holds a binder to a
  session the harness is about to release, and the failure that produces names neither the test that
  leaked it nor the one that trips over it.

## The one seam that is not public

Substituting a fake clock and a fake data source means touching `@UnstableApi` Media3 types, which
ADR-0001 rule 2 keeps out of the public API. Rather than widen the public surface for testing,
`SuperPlayer.Builder` carries a single `internal` configurator — Kotlin `internal` is visible to a
module's own unit tests — that tests use to configure the engine at construction, and nothing else.

Construction is the only thing it does. Once the player is built, the test holds a `SuperPlayer` and
talks to it as a `Player`. If a test ever needs a second such seam, that is a signal the design is
wrong: the thing being configured probably belongs in SuperPlayer's own vocabulary, as public API.

### Reaching that seam from another module

Kotlin `internal` means *one compilation*, so the seam is visible to `superplayer-core`'s own unit
tests and to nothing else — and every module from phase 2 onward has tests that need exactly what it
provides. `superplayer-telemetry` is the first: a QoE collector cannot be tested without a
deterministic player, and `QoeCollector` cannot live in core (ADR-0008 rule 2).

`superplayer-testkit` is where that harness lives, and it compiles as a **friend** of core rather
than as a consumer of it — `build-logic`'s `KotlinFriendModules.kt` passes `-Xfriend-paths`, and its
KDoc carries the argument. The seam is not widened: the same one configurator is reached by one more
of the library's own compilations, a consumer's compilation is never a friend of anything, and
`internal` remains invisible outside this repository.

`superplayer-testkit`'s own public API names **no Media3 type**, for the reason ADR-0001 rule 2 gives:
a `Format` or a `Timeline` in one of its signatures would put Media3's opt-in marker on every test
that named it. A test says what it wants — `TestContent.videoLadder()`, `harness.stallRendering(player)`
— and the Media3 vocabulary stays inside the harness.

**Its two clocks move together.** Media3's `FakeClock` drives the engine; Robolectric's `SystemClock`
is what `declarePlaybackIntent` and a collector read, because that is the clock
`docs/telemetry-schema.md` defines every duration on. The `FakeClock` is therefore created at the
current `SystemClock` reading with auto-advancing **off**, and `advanceTimeMs` moves both — which is
the opposite of core's harness, deliberately. Auto-advancing is right when nothing is being measured
and wrong when something is: a clock that moves by an amount no assertion can name turns every
duration into a tolerance.

**What it fakes, and what that costs.** Stalls are injected by making the video renderer stop being
ready, which reproduces the engine's buffering state machine exactly and the *cause* of a rebuffer
not at all; dropped frames are raised on the renderer's own callback rather than by a decoder that
could not keep up. Both are stubs, both say so at the implementation, and both should be re-pointed
at the shaped transfer and fault injection that `#39` and `#40` bring.

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
