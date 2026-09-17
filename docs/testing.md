# The test seam

Every test in this repository drives SuperPlayer through its **public API**, under Robolectric, on
the JVM, against Media3's own fakes. No device, no network, no assertion that reaches past the
facade. This is not a default that happened to stick — it is the constraint the first slice of the
library was built to satisfy, and it holds for everything added afterwards.

There are three documented exceptions, and all of them are still tests with no device and no network.

`SuperPlayerTransferChainTest` keeps the HTTP stack `SuperPlayer.Builder` puts at the bottom of its
chain instead of substituting a fake data source for it, and reads a `file:` URI. "The one player that keeps its own transfer
chain" below says why that is necessary rather than merely convenient.

`SuperPlayerCmcdTest` keeps that same chain and asserts on the *requests* travelling down it rather
than on a state of the facade — the CMCD keys on a `DataSpec`, captured through the `TransferListener`
the engine propagates down every layer. What it is about is bytes leaving the process, and no facade
state reports those: a player emitting CMCD and one emitting nothing are indistinguishable from the
outside, which is precisely why the keys are worth a test. The listener arrives as a `BandwidthMeter`
installed through the engine configurator, which is the seam this document already describes, and it
is a *reading* seam rather than a configuring one — it substitutes no behaviour, and the estimate it
reports is Media3's own so that nothing observed is downstream of a number the test invented. A test
that needs to see what SuperPlayer sent copies this; one that needs to see what the player *is* does
not.

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

`superplayer-offline`'s `Downloads` carries a second, `isReleasingLicences` (#245), for the same reason. A
release of an owed licence runs on the store's own thread, and a test that goes on before the attempt has
given up proves nothing about what stays owed. It waits and asserts nothing, and is `internal` for the reason
above.

`superplayer-core/src/test/kotlin/com/superplayer/core/` is the worked example.

## What the seam is

| Concern | What the tests use |
| --- | --- |
| Entry point | `SuperPlayer`, through Media3's `Player` interface |
| Runtime | Robolectric, via `androidx.test.ext.junit.runners.AndroidJUnit4` |
| Time | `androidx.media3.test.utils.FakeClock`, auto-advancing |
| Network | `androidx.media3.test.utils.FakeDataSource` over a `FakeDataSet` — except in the one class named above, which reads a `file:` URI through the real chain |
| Media | Manifests and segments generated in Kotlin — `superplayer-testmedia`'s `SyntheticHlsStream` and `SyntheticDashStream` |
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
auto-advancing clock and the fake data source over a `FakeDataSet`, the latter standing in the
configurator's *transport* slot, so that the chain `SuperPlayer.Builder` composes still runs above it. A test asks it for a
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
test invisible. The two exceptions are `settle`, which lets the engine finish everything it can do at
the current time, and
`awaitPeriodicWork`, which lets the engine make the periodic pass on which Media3 refreshes the
buffered position — both timed on a clock the test has no other handle on.

Composing the media is still the test's: `buildPlayer` serves the synthetic HLS stream by default,
and a test that switches protocols or needs a longer stream passes its own `FakeDataSet`.

### The one player that keeps its own transport

Every harness player keeps the chain `SuperPlayer.Builder` composes — `TransferChain`, SuperPlayer's
own composition since issue #22 — and substitutes `FakeDataSource` only where the HTTP stack goes. The
one layer none of them can see is that bottom one: whether the transport `build()` installs resolves
anything at all. `buildPlayerOnItsOwnTransferChain` is the exception: it substitutes the clock and
nothing else, and plays a `file:` URI written by `SyntheticHlsStream.writeTo` — the nearest thing to a
network fetch a test with no network can ask for. `SuperPlayerTransferChainTest` is its only caller,
and it also pins the other half: a transport installed through the engine configurator takes the HTTP
stack's place, which is what keeps every test above working.

## Synthetic media, not fixtures

Streams are generated in Kotlin rather than checked in as binaries. `SyntheticHlsStream` writes a
multivariant playlist, a media playlist and AAC segments in ADTS framing; `SyntheticDashStream`
writes an MPD, a fragmented-MP4 initialization segment and fragmented media segments. Every field a
test asserts on — the codec, the bitrate, the sample rate — is a named constant a reader can see, and
the repository carries no media it would have to license.

The two describe deliberately equivalent media: same codec, same sample rate, same declared
bitrate. That is what lets a test compare what the facade reports for the two protocols and be
comparing the protocols rather than two unrelated pieces of media.

### Where they live, and why it is its own module

Both are **`superplayer-testmedia`**, a module that exists for one reason: `superplayer-core`'s tests
and `superplayer-testkit`'s *main* source set both play these streams, and core cannot depend on
testkit — a later phase (`docs/modules.md`), and a cycle besides. The one home both can see has to
sit below both, so it is a module of its own at phase 1 with no dependencies at all.

It is a thirteenth module and a second published artifact, and that is the price: `superplayer-testkit`
`api`-depends on it, and a published module cannot depend on an unpublished one. So
`com.superplayer:superplayer-testmedia` is something an adopter can resolve, exactly as
`superplayer-testkit` already is.

The alternatives were weighed and are worse:

- **Duplicating them in testkit.** Around eight hundred lines of byte-exact ADTS framing and ISO
  BMFF box building, in two places, drifting from the moment the second copy exists.
- **Putting them in `superplayer-core`'s main source set as `internal`**, which testkit could reach
  as a friend. Cheapest, and it fails on what it drags with it: a generator that populated a
  `FakeDataSet` would put `media3-test-utils` on core's *main* classpath and into its POM, so every
  consumer of `superplayer-core` would resolve Media3's test fakes. Test-media generators inside the
  shipped AAR are a thing a reviewer has to explain; test fakes in a consumer's dependency graph are
  a thing nobody should have to.
- **A cross-project source directory.** The same package compiled into two artifacts, and a consumer
  with both on the classpath gets duplicate classes.

`superplayer-testmedia` **names no Media3 type**, and that is load-bearing rather than minimal.
`FakeDataSet` is `@UnstableApi`, so an `addTo(FakeDataSet)` in a published module's public API would
fail `verifyNoUnstableMedia3InPublicApi` — ADR-0001 rule 2, the same rule that keeps *unstable*
Media3 out of testkit's signatures. A stream is therefore handed over as URI-to-bytes:

```kotlin
SyntheticHlsStream.resources(segmentCount = 4).forEach { (uri, bytes) -> fakeDataSet.setData(uri, bytes) }
```

That line is the caller's, once in `superplayer-core`'s tests (`SyntheticStreams.kt`) and once in
`PlaybackHarness` — the two modules that serve these streams, which cannot share a compilation. One
line in each is the whole cost of the split, and it buys a module that cannot break the phase rule
and cannot be a cycle. `SyntheticHlsStream.writeTo(directory)` is the same stream as files, for the
one test that plays through the real transfer chain.


### The hostile manifest corpus

`HostileManifests`, also in `superplayer-testmedia`, is the other half of synthetic media:
valid-but-hostile HLS and DASH — ladder gaps, overstated bitrates, missing codecs, audio-group
mismatches, ragged segment durations, bare discontinuities, clock skew, short and missing time-shift
windows, a mid-stream ladder change, and a live playlist served cacheable — the pathologies `PRD.md`
§3.6 lists for `MediaSourceDoctor` to diagnose in Phase 9. It is generated for the reason the good
streams are, and for one more: a checked-in broken manifest is inert, while a generated one is a
builder call. Every defect that applies to both protocols is generated for both, and every defect
with a magnitude is generated at three severities.

Every entry is **a known-good stream with one thing wrong** — `SyntheticHlsStream`'s segments or
`SyntheticDashStream`'s, under a manifest that lies about them in exactly one way — and is handed over
as a `HostileStream`: URI-to-bytes under its own `fake://superplayer.test/hostile/<id>/<severity>/`
directory, so the whole corpus, every severity included, fits in one `FakeDataSet` with no collisions. `TestContent.hostile(stream)` plays one
through `PlaybackHarness`.

`HostileManifestCorpusTest` (in `superplayer-testkit`) **records** what SuperPlayer does with each
entry today — fails, never starts, stalls, degrades, keeps playing, or plays to the end — rather than
asserting that it is handled. *Degrades* is observed, not judged: the session reported a position
outside the media it was served, before its start or past its end. Most are not, and are not meant to be yet;
recording them means a later phase's fix is a visible diff in one table rather than an unreviewed
change. That table must name every entry, so a pathology cannot be added and left unplayed.

The corpus is recorded **twice, on two players**. `HostileManifestCorpusTest`'s tables are a player
built with a profile and nothing else, which is the core-only consumer; `superplayer-resilience`'s
`HostileManifestLadderTest.WITH_LADDER` is the same corpus on a player built with
`Resilience.standard()`. It lives in that module because a phase 2 module may not depend on a phase 5
one (`docs/modules.md`), and both tables are taken by one observer — `HostileObservation.observe`, in
`superplayer-testkit`'s main sources — so the only difference between them is the resilience. A row
that differs is a row the ladder moved; a row that agrees is one more count of ADR-0011 rule 14. The
ladder table also carries `CANNOT_RECOVER`, the entries that neither recover nor end named, each with
the reason it is beyond a ladder — `PRD.md` Part 4's criterion is stated *with* its exceptions rather
than around them.

**Severity** is what makes the corpus grade a doctor's thresholds rather than only its detection.
`HostileManifests.all()` carries each pathology once, at a value chosen to be unmistakable — a ladder
gap of 48×, an hour of clock skew — and against that alone a doctor that flags every manifest passes.
So every pathology with a magnitude also takes a `HostileStream.Severity`:

- `BENIGN`: present, but within what real content does. A doctor must not flag it.
- `BORDERLINE`: where a reasonable threshold could fall either way.
- `SEVERE`: the value `all()` carries.

`HostileManifests.graded()` returns every pathology at every level it has, and `all()` is its severe
half. A binary pathology — an attribute absent, a reference that resolves to nothing — has no milder
form, so it is generated once, at `SEVERE`, with a null `magnitude`. The test records `graded()` as a
second table, `GRADED`: a row per pathology and a column per severity. Reading across a row shows the
*cliff*, the level at which an entry stops playing cleanly. A `BENIGN` cell that does not play cleanly
is a finding about the player, because the content is ordinary.

Live DASH entries need two things the on-demand ones do not, and both exist so an entry records its
own defect rather than the harness's limits. They carry a `UTCTiming` element, because without one
Media3 asks an NTP server for the time, and under Robolectric that call never resolves. And they
address segments by `SegmentTemplate`, because a `SegmentList` names a fixed set that a player treats
as published whatever the time. A template makes availability a function of the clock, so the live
edge moves with the harness even though the bytes never change. `HostileManifests.dashLiveBaseline()`
is the healthy live stream they are modifiers over. It is labelled `HEALTHY`, it is not in the
corpus, and the test checks that it plays on, which is what makes a failing live row mean something.

**Adding a pathology** means, in `HostileManifests.kt`:

1. A `public fun` built from `hlsStream`, `dashStream` or `dashLiveStream`, as a modifier over the
   good stream. Do not hand-write a whole document: if the builders cannot express the defect,
   extend them, so that the next entry can reuse the extension.
2. A `// spec:` comment above it citing the clause it stretches or violates — RFC 8216 for HLS,
   ISO/IEC 23009-1 or DASH-IF IOP for DASH — with the argument for why the document is still legal.
   The same citation goes in `spec`.
3. A `cause`: the misconfiguration, encoder or packager that produces it in the field, in plain
   language. It is the sentence the doctor will show a user, and it is much easier to write now.
4. A `validity`. `MALFORMED` when the document breaks a MUST, whether or not Media3 happens to reject
   it; `VALID_BUT_HOSTILE` otherwise. `theCorpusLabelsWhatIsMalformedRatherThanMerelyHostile` pins
   the malformed set by id.
5. Its severities. A pathology with a magnitude takes a `severity` parameter defaulting to `SEVERE`,
   and one private function in the *Severities* section returns its value at each level. Every
   branch carries its own argument: a `// spec:` or `// ref:` where a published document speaks to
   the number, and otherwise a derivation from first principles or from this harness's own
   measurement, which is what `CONTRIBUTING.md`'s clean-room rules accept. A doctor's thresholds will be
   scored against these values, so each must stand on its own rather than only relative to its
   neighbours. `BENIGN` must be content a doctor must not flag, not merely a milder defect. The
   entry also states its value as `magnitude`, in words a report can print. A binary pathology
   instead carries a `Single severity:` paragraph in its comment saying why nothing milder exists.
   Its id goes in the pinned list in `aPathologyWithAMagnitudeIsGradedAtEveryLevelAndABinaryOneAtOne`.
6. The entry in `graded()` (`all()` follows from it), and its observed rows in
   `HostileManifestCorpusTest.RECORDED` and `GRADED`, and in
   `HostileManifestLadderTest.WITH_LADDER` — three tables, because an entry recorded on one player
   and not the other is an entry whose ladder behaviour nobody looked at. Add a comment wherever a
   row is surprising.

A defect that lives outside the manifest cannot be applied by `FakeDataSet`, which serves bytes and
reports no response headers. That covers the `Cache-Control` mismatch. Such an entry carries the
defect as `declaredResponseHeaders` and reproduces the *consequence* in its bytes — for the cache
rule, a live playlist that never changes. `TestContent.hostile` hands the headers to the harness,
which serves them on top of the bytes, so the player reads both. The entry must still say which of
the two its recorded row measures.

The *cause* — an origin that keeps publishing behind a cache that does not pass the new versions on —
cannot be carried by fixed bytes at all. It is played instead by `LivePlaylistRevalidationTest`, over
`TestContent.liveHls()` (an origin that advances with the harness's clock) and
`FaultScript.Builder.serveThroughCache` (a cache that holds responses for a `max-age` and serves them
with the headers that say so). That is the one place a recovery from this defect can be shown,
because recovering needs something newer to exist.

## The layers above the fakes

Both harnesses put their fakes where the *transport* goes — the HTTP stack — and not in place of the
whole chain. Through the configurator's `EngineConfiguration.transport`, the shaper, the fault
injector and the fake data source take the HTTP stack's place, and `SuperPlayer.Builder` composes
above them the same layers it composes above a consumer's network. A layer SuperPlayer adds to the
chain is therefore in every protocol test the day it lands, with nothing in either harness to
remember: `LivePlaylistRevalidation`, which reloads a frozen live playlist past a cache, is the first,
and the corpus's cache-control row changed because of it.

This is the one seam rather than a second: the same configurator, offering a narrower slot than
"replace the loading path". Described content, which Media3's fakes synthesize without any
`DataSource`, still replaces the path whole because it has no transport to stand in for, and it is
the one kind of test player with no chain above its fakes.

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

One limit is worth knowing before writing another of these. Robolectric's `CodecCapabilities`
answers 32 for `getMaxSupportedInstances`, and its codec builder has no setter to lower it. Both
device statements work around it the same way, writing the value into the built capabilities
through Robolectric's reflection helper: core's `TestDevice.declareVideoDecoder(mimeType,
maxSupportedInstances)`, so `PlayerPoolCapacityTest` can give two codecs different limits and see
the one its pool declared come back as `PlayerPool.maxSize`, and `superplayer-testkit`'s
`DeviceStatement.declareVideoDecoder`, for a test above core; `DecoderWarmupTest` declares limits of
one, two, three and six. A test that leaves the limit alone still gets Robolectric's 32, and pins
**which limit binds** — decoder or memory — rather than a number it supplied.

The track selector reads the device too — the display's largest mode and HDR types, and each video
decoder's profile levels — once, when a player is built, and `superplayer-testkit`'s public
`DeviceStatement` is how a test states them: `declareDisplay`, `declareDisplayHdrTypes`,
`declareVideoDecoder` with its `(profile, level)` pairs, and the heap. Two things follow. The
harness declares a television-sized display before every test, because a selector reading
Robolectric's small default would refuse every rung above the bottom one before any estimate was
consulted — the same defect it already closes for Media3's own viewport constraint — so a test about
the display gate *narrows* the display rather than declaring one. And a decoder is declared before
the test's first player is built, not only before the one it is about: the platform caches its codec
list on first read, so `NetworkAwareTrackSelectionPlaybackTest` keeps its ungated control in a
separate test rather than playing it first. `Display.Mode` has no public constructor, and a display
stated in one event needs Robolectric's hidden display service, which is why every hidden member the
display statement names is in `StatedDisplay`, in that one place (*A TV device*, below).

A player built with `setDrm` reads a **second** decoder table — the profile levels and instance limit
of the decoders declaring `FEATURE_SecurePlayback`, which share their plain siblings' MIME type and are
therefore pooled together unless they are tallied apart (#211). `DeviceStatement.declareSecureVideoDecoder`
states one, beside a plain `declareVideoDecoder` rather than instead of it, because that is the device;
core's own `TestDevice.declareSecureVideoDecoder` does the same for the pool's bound. Both halves keep
the direction the rest of this section keeps: a device that declared no secure decoder refuses no rung
and keeps the ordinary pool bound, because Widevine L3 plays protected content on ordinary decoders and
declares nothing.

## A TV device

Phase 8 plays on a television whose display and audio output change underneath playback (ADR-0014
rules 4 to 6), so `DeviceStatement` states that too (#266), and `PlaybackHarness.scheduleDeviceChange`
makes a change at a stated moment. `TelevisionDeviceTest` is the worked example, and asserts each
statement through a listener the platform registers rather than through a player.

- **A television.** `declareTelevision` makes `UiModeManager.getCurrentModeType` answer
  `UI_MODE_TYPE_TELEVISION` and the package manager report `FEATURE_LEANBACK`. It is not only a
  label: Media3 reads the UI mode to choose where it reads the audio output from, so it is stated
  before the first player. The resource configuration's `uiMode` is left alone, because the library
  holds no resources that vary by it.
- **Display modes.** `declareDisplayModes(modes, activeMode)` states several `DisplayMode`s, each a
  physical size and a refresh rate, and the one in force. `declareDisplay(width, height)` is one mode
  at 60 Hz, and is what the harness still states before every test, so the default device is the one
  every test before #266 ran on.
- **A hotplug.** The display statements are restatable mid-test. A restatement reaches every
  `DisplayManager.DisplayListener` as **one** `onDisplayChanged`, with the display already whole,
  because Robolectric's own setters publish a qualifier change, the modes and the HDR types as three
  events, and the first of them is a display with no HDR answer. A statement that changes nothing
  reaches nobody, as on a device. `declareDisplayDisconnected` removes the display and a later
  `declareDisplayModes` adds it back under the default display's id, for a device that reports a
  hotplug as removed and added rather than changed.
- **An audio output.** `declareAudioOutput(encodings)` states what the output passes through beside
  PCM, and restating it is an AV receiver powered on or off. It is written to both channels Media3's
  `AudioCapabilitiesReceiver` reads: a television's direct playback profiles on API 33 and later, and
  the sticky HDMI audio plug broadcast. So an `AudioDeviceCallback`, a broadcast receiver and Media3
  each hear it, and Media3 hears one change of capabilities. Robolectric's audio service reports the
  devices already present *inside* a callback's registration, which Media3 hears as a change before
  it has a first reading, so a test that counts changes starts counting after it registers.
- **At a moment.** `scheduleDeviceChange(afterMs) { … }` takes the restatement itself, so that every
  restatable declaration is scheduled one way. `advanceTimeMs` splits a step at the change, lets
  playback reach it, makes it, and settles the player before going on, so both clocks read that
  moment in every callback the change causes. It is device-wide, as `TransportReplay`'s network is.

What each stand-in cannot show:

- **The synthetic streams have no video renderer.** `superplayer-testmedia` publishes audio, so a
  frame rate a real stream declares is one no harness session reaches through a protocol. The
  harness's described video is the video there is: fake formats played by a fake renderer that
  decodes nothing. Each rung declares `TestContent.Rung.frameRate`, 30 fps unless a test says
  otherwise and null for a rung that declares none, which is what reaches `Format.frameRate` in place
  of a manifest's `FRAME-RATE` or `@frameRate`. The samples stay 30 fps apart whatever a rung declares.
  A rung with `TestContent.Rung.hdr` declares HDR10's colour (BT.2020, PQ) in place of a manifest's
  `VIDEO-RANGE=PQ`, which is what a display listing no PQ type is asked about; nothing is tone-mapped.
- **A hotplug is heard, not negotiated.** `DisplayHotplugTest` replaces the display mid-playback and
  reads the selection from the `TrackSwitched` events a consumer's sink is told, on a player built with
  `AdaptivePolicy`, whose gate is the refusal a display change re-arms. What moves under the harness is
  the rung chosen at the next chunk; a real sink's blank while the link comes back, and whether a
  rendition that was on screen when the cable moved is shown at all, are a device's (#274). The
  process-wide throughput memory is `superplayer-abr`'s and outlives a test, so an adaptive player built
  after another test's finds samples timed on a clock that has since restarted. The memory forgets a
  sample timed after now rather than aging it backwards, which is why a test class outside `superplayer-abr`
  needs no reset of its own.
- **Robolectric has no compositor.** A `Surface.setFrameRate` call is accepted and changes nothing, so
  the active mode is what a test stated until it restates it. What a test *can* read is the request:
  every surface the harness gives a player records each `setFrameRate` call made on it, and
  `PlaybackHarness.frameRateRequests(player)` lists them as `FrameRateRequest`s, withdrawals included.
  It is a `Surface` subclass rather than a shadow, so no test class declares anything, and it is the
  call a device's compositor would receive, which is *Asserting on the platform* above. A mode switch
  taking effect, the time an HDMI link takes to resynchronise, and the blank a non-seamless switch
  costs are a device's to show (#274).
- **An audio output is chosen for, not played to.** The harness's audio renderer is Media3's fake, which
  plays every format, so it answers for the formats a device plays only by passing them through (AC-3,
  E-AC-3, AC-4, DTS, TrueHD). It supports one exactly where the stated output carries it, reads that
  through Media3's own `AudioCapabilitiesReceiver` from the first time such a format is asked about, and
  tells the selector when the output changes, as Media3's audio renderer does. Every test that plays no
  such format is unchanged. `TestContent.dashWithPassthroughAudio` offers stereo AAC beside 5.1 AC-3 over
  the same AAC bytes, each at a path of its own. `AudioCapabilityChangeTest` reads the selection from
  `currentTracks` and confirms it by what was fetched. No sink is configured, so a sink refusing a format
  mid-change cannot arise. How that refusal is classified is asserted over Media3's real exception in
  `ErrorClassifierTest` and `FallbackLadderTest`, and whether a real sink raises it is a device's (#274).
- **Tunneling is enabled, not rendered.** Media3's fake renderers answer for no tunneling, so the
  harness's video renderer answers from the stated device: the first decoder declared for the format's
  type, the secure one where the format is protected, as Media3's own renderer chooses, tunnels where
  `DeviceStatement.declareTunnelingVideoDecoder` declared `FEATURE_TunneledPlayback`. Its audio sibling
  answers for tunneling wherever it plays a format, as Media3's does. Media3 tunnels a video renderer and an
  audio renderer together, so the content is `TestContent.videoWithAudio`, described video with an AAC track
  beside it, synthesized in memory and therefore not playable under a fault script or a trace.
  `PlaybackHarness.videoRendererTunneled(player)` reads the configuration the engine enabled the video
  renderer with, which is the decision *applied*, and `TunneledPlaybackTest` reads it beside
  `playbackDecision`. No described stream is protected, so the secure-decoder half is asserted on the
  renderer in `DeviceStatementTest`. Nothing is decoded and no audio session reaches a codec, so a frame
  tunneled, A/V sync on a vendor's implementation, and the frames the platform drops unseen are a device's
  (#274).
- **There is no HDMI.** No link renegotiates, no EDID is read, and no HDCP level exists. The display
  and the audio output are what a test states, and a sink decoding a passthrough format is nothing
  anything here can hear: a format a player selected is one it *chose*.
- **Media3's API 29 to 32 television path is not stated.** It reads
  `AudioTrack.isDirectPlaybackSupported` against a player's own audio attributes rather than the
  device's, and the pinned runtime is 35.

## A Widevine device and a licence server

Protected playback is the one part of the platform this seam cannot reach at all: **Robolectric 4.16
ships no `ShadowMediaDrm`**, so `MediaDrm` cannot be constructed under `check` and nothing in the
suite can stand in front of it. What can be reached is the object a `DrmSessionManager` actually
talks to — Media3's own `FakeExoMediaDrm`, its nested `LicenseServer` and `FakeCryptoConfig` — and
the whole of the DRM half of this harness is built on the fact that the interface, rather than the
platform class, is where the seam is.

Three pieces, each in the place the equivalent unprotected piece already lives.

**The content declares the protection**, because that is where a real stream declares it.
`TestContent.protectedHls()` and `TestContent.protectedDash()` are the protected twins of `hls()` and
`dash()`: the same media, under a playlist carrying an `EXT-X-KEY` that names Widevine (RFC 8216
§4.3.2.4) and under an MPD carrying a `ContentProtection` descriptor and a `cenc:pssh` box (ISO/IEC
23009-1 §5.8.4.1, ISO/IEC 23001-7 §11.2). The two vocabularies are completely different and produce
the same `DrmInitData`, which is the fact worth having both for. `superplayer-testmedia`'s
`WidevineProtection` is the one `pssh` box both carry.

**The samples are not encrypted, and that is deliberate.** What a protected stream has to do here is
make the player acquire a licence before it reads a sample, and that follows entirely from the
manifest. Encrypting the samples would add a decryption step nothing in a Robolectric test can
perform — there is no `MediaCrypto`, and the fake renderers decode nothing — so it would turn a
stream that exercises the licence round trip into one that cannot play at all. One consequence has to
be paid for explicitly: Media3 plays a *clear* sample without waiting for keys by default, so the
harness builds its session manager with `setPlayClearSamplesWithoutKeys(false)`. Without that line a
session whose licence was refused plays the whole stream and ends normally, and every test of a DRM
failure would pass by proving the opposite of what it says. `superplayer-drm` sets the same flag on
its own session manager, and the reason there is not this one: a consumer who declared protection has
said this player plays protected content, and handing a renderer samples the session holds no keys
for is the silent downgrade ADR-0012 rule 11 forbids. The two lines agree, and only one of them is
a compensation for a synthetic stream.

**The device is stated, like the display and the decoders.** `DeviceStatement.declareWidevine(level,
maxConcurrentSessions, provisioningRequired)` is an ordinary working implementation at `L1` or `L3`,
and `declareWidevineProvisioningFailure(level)` is the one whose provisioning the service refuses —
the commonest reason a handset that reports L1 cannot play at L1, and the case ADR-0012 rule 11's
security-level downgrade exists for. A test that states nothing plays on an ordinary provisioned L1
handset, for the reason the default television-sized display exists. `declareSecureVideoDecoder`
declares a decoder able to operate on protected memory, with an instance limit of its own, because a
device commonly runs several ordinary video decoders and exactly one secure one. The statement is a
field rather than a Robolectric shadow, so nothing resets it between tests but the harness, which
does so in `before()`.

**The offline half of the device is the harness's and not Media3's** (#210). `FakeExoMediaDrm`
refuses a `KEY_TYPE_OFFLINE` or `KEY_TYPE_RELEASE` key request outright — "Offline key requests are
not supported" — throws unconditionally from `restoreKeys`, and reports neither of the two durations
`queryKeyStatus` carries on a real Widevine implementation, because Media3's own tests never download
a licence. So `WidevineDevice.kt` adds exactly that bookkeeping around Media3's exchange and nothing
else: a key store held on the *statement* rather than on one `ExoMediaDrm`, so a licence acquired
through one player restores under the next one's device as it would on a handset; a key-set id minted
per stored licence; `restoreKeys` replaying the stored response into Media3's own fake, so a restored
session becomes a keyed session by the path a streaming one does; and `LicenseDurationRemaining` and
`PlaybackDurationRemaining` reported from what `DeviceStatement.declareOfflineLicence(licenceSec,
playbackSec)` stated. The request, the response and the entitlement decision stay Media3's and the
licence server's, which is what keeps a download the same round trip as a stream under a different
key type rather than a second protocol.

Two numbers of Media3's are worth knowing before writing such a test, because both are easy to meet
by accident. A restored licence within **sixty seconds** of expiry is re-requested from the server
during playback rather than played to a stop (`DefaultDrmSession.doLicense`), so a stated duration
under that turns an offline test into an online one; the defaults are a month and two days for that
reason. And a missing duration reads as long expired, which is why the statement always reports both.

**The licence server is an origin at a host of its own**, `FakeLicenceServer.HOST`, answering
`LICENCE_URI` and `PROVISION_URI`. That is what makes a licence exchange visible to everything this
module already has: it is a transfer, so it passes through the shaper, the fault injector and the
clock wait; it is addressed by `ResourceKind.LICENCE`, so a `FaultScript` can delay it, refuse it, or
refuse it once and relent; and it is counted by `networkRequests`, so a test can say what was asked
of it. Media3's `LicenseServer` is a `MediaDrmCallback` rather than anything HTTP-shaped, so
`LicenceServerDataSource` does the translation in one place — a POST body *is* the request the
callback would have been handed — and the **entitlement** decision stays Media3's rather than becoming
a second, home-made licence server nobody has reviewed. The player's side is
`TransportMediaDrmCallback` for the *stock* arm, which posts both requests raw; `superplayer-drm`
uses Media3's own `HttpMediaDrmCallback`, whose key-request half posts to the address
`WidevineConfig` named.

**The sentence above has no exception, and for one release it did** (#208, withdrawn by #223).
ADR-0012 rule 11's downgrade permission is a *policy* answer rather than an entitlement, and Media3's
`FakeExoMediaDrm.LicenseServer` has no lever for one — it is an allow-list over `SchemeData` byte
lists, it inspects no request and it varies no response. #208 concluded that the harness must
therefore answer that question itself, out of response headers a test stated, which made
`LicenceServerDataSource` a small home-made licence server after all. #223 moved the permission into
`WidevineConfig.permittedSecurityLevels`, where it is the server operator's published policy rather
than a round trip, and the harness went back to translating Media3's decisions and nothing else.

What that leaves a test writer is simpler: the pair that matters — an operator who permits `L3` and
one who has said nothing, over one identical device — is two `WidevineConfig`s, and neither touches
the licence server at all. `SecurityLevelTest` counts the consequence, which is that a permitted
downgrade now costs one licence request and a refused session costs none.

**Provisioning is where the two used to diverge, and #207 settled it in the harness rather than in
the library.** Media3 sends a provisioning request to the URL the *device* names — on a handset, the
service its implementation trusts, which the app never chooses — and `FakeExoMediaDrm` names
`bar.test`, an address nothing here answers. So a provisioning round trip through a real
`SuperPlayer` left the harness entirely: it could be neither counted, delayed nor refused, and a test
that meant to refuse one was really refusing a DNS lookup. The fix is one line of `WidevineDevice`:
the stated device names `FakeLicenceServer.PROVISION_URI` as its provisioning service, exactly as a
real device names Google's. Bending the library's callback to the harness would have been the wrong
repair — the shape under test would then have been the test's rather than the field's. The other
half is `FakeLicenceServer` unwrapping the envelope Media3 sends: `{"signedRequest":<request>}`,
whose member is concatenated raw rather than encoded, so the server unwraps it by the same byte
concatenation in reverse and never through a JSON parser, because a provisioning request is not text.

That change made one existing expectation visibly wrong, which is worth knowing before writing
another: `DeviceStatement.declareWidevineProvisioningFailure()` is a device that *rejects the
certificate it is handed*, and now that the round trip completes, Media3 raises
`DeniedByServerException` for it — error code 6007, classified `Drm.Unsupported` and not retryable,
which is the honest name for a revoked implementation. It read `Drm.Provisioning` before only because
the session had failed on the unanswerable address first. A refused *transfer* to the provisioning
service is the one that is `Drm.Provisioning`, and `ProvisioningTest` is where that is asserted.

**Both players play protected content.** `buildStockPlayer` builds the session manager itself, which
is arm (a) and the only thing available before Phase 6 had code; `buildPlayer(content =
TestContent.protectedDash(), drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)))` is a
real `SuperPlayer` with the module's session manager reaching its engine through core's DRM slot, and
is what a Phase 6 test uses. Protected content with no `drm` is refused rather than played, because a
player that opened no session at all would play these streams perfectly and prove nothing.

**The two do not track each other, and a session-graph change has to be made in both or in neither**
(#209). `buildStockPlayer`'s manager is built in `PlaybackHarness` out of what a *stock* arm would
configure, so a decision `superplayer-drm` takes about its own manager — `setMultiSession`,
`setSessionKeepaliveMs`, the `LoadErrorHandlingPolicy` it is handed — changes nothing about the stock
arm and no test of the stock arm will notice. That is deliberate: arm (a) is what an app gets from
Media3's defaults, and a stock arm that silently inherited SuperPlayer's decisions would make the
benchmark's comparison meaningless. What it costs is that `ProtectedPlaybackTest` is **not** a
regression test for anything `superplayer-drm` decides, so a claim about the library's session graph
belongs in `superplayer-drm`'s own tests, over a real `SuperPlayer`. `SessionReuseTest` is the example.

**Two protected streams can differ in the one way a DRM session cares about.**
`WidevineProtection.ContentKey` names two key ids and `TestContent.protectedDash(key = …)` serves the
same media under either, at an address of its own. It exists for one question — whether a player opens
one session or two for content a licence server licensed separately — and the harness's licence server
allows **both** keys, because that question can only be put to a viewer entitled to both: a server that
refused the second key would answer with a licence failure and say nothing about sessions. An
entitlement a test wants withheld is withheld at the transport, where every other refusal here lives.

**A key rotation is the device's to signal, not the server's.** `TestContent.protectedLiveHls()` is a
live window under an `EXT-X-KEY`, and `DeviceStatement.signalKeyRotation()` raises
`MediaDrm.EVENT_KEY_REQUIRED` on every session the stated device holds open — which is what a real
implementation does when the packager re-keys under a running session. It is stated on the device
because that is where a client learns of it: a server that changed the key it would issue is invisible
until the device complains. Call it after a player is playing; the renewal is a licence request like
any other and `networkRequests` counts it. What such a test cannot reach is a rotation the session
*needed*: the samples are in the clear, so keys that stopped working stop nothing, and Media3 keeps a
session that fails to renew on the keys it already holds (`DefaultDrmSession.onError` moves a session
to `STATE_ERROR` only when it is not already `STATE_OPENED_WITH_KEYS`). `KeyRotationTest` records that
rule rather than asserting around it.

Two things follow that a test writer should know. A licence load **reports no bytes to a transfer
listener**, because a licence is not media and a few hundred bytes of key exchange counted as a
throughput sample would move an estimate that is supposed to describe how fast segments arrive.
And a **provisioning request and a licence request are two resources of one kind, not two kinds**:
they are fetched from different paths, so each gets an index in the order the session asks for them,
and a fault naming the kind and no index addresses both — which is what "the licence server is down"
means.

**A licence is otherwise a load like any other, and a test of that needs two modules.** Since #205 a
refused licence spends `RetryPolicy.licence` and a licence refused 401 or 403 is repaired by the one
`HeaderProvider`, but both of those belong to `superplayer-resilience`: the budget is answered by the
`LoadErrorHandlingPolicy` it fills core's slot with, which core then hands the DRM slot as well, and
the repair happens in the header-refresh layer the licence transport is composed over. So a test of
either builds its player with `setResilience` beside `setDrm` — `superplayer-drm`'s `LicenceLoadTest`
is the worked example — and a player with only one of the two keeps Media3's own handling for a
licence exactly as it keeps it for a segment. The forcing form of each claim is a budget the test
sets to a number Media3 would not have chosen: a licence played out of five asks where Media3's own
patience is three, or a session repaired while the budget was zero.

`DrmFailureTest` is the third file of that pairing and the one to read before adding to it, because
its own KDoc says what it does **not** force. A protection failure the harness can raise carries a DRM
code Media3 had already narrowed, so it classified the same way before #206 as after; what that file
guards is the path — a named class, a message key and an `isRetryable` reaching a consumer as the
`cause` of the `PlaybackException` they already receive — while the codes #206 actually re-routed
(`ERROR_CODE_DRM_UNSPECIFIED`, `ERROR_CODE_DRM_SYSTEM_ERROR`, an expired licence) are ones no fake
device here can raise and are asserted in `ErrorClassifierTest` against the code Media3 assigns each.
Writing the weaker test and calling it the forcing one is the failure mode; saying which is which in
the file is the habit.

**A secure surface that will not allocate is the case this seam cannot reach at all, and #226 says so
rather than faking one.** `PRD.md` §3.2's third way L1 becomes unusable is a protected buffer queue
the device cannot back — and there is no `MediaCrypto` here, no protected buffer queue, and the
renderers are Media3's fakes rather than `MediaCodecRenderer`, so nothing under `check` can allocate a
secure surface or fail to. What #226 found is that the *ladder* does not need one: what it needs is
two fields on one real Media3 object — a `DecoderInitializationException` whose `codecInfo` is present
(a decoder was selected and would not start) and secure — and that object can be raised without a
codec. So the claim is forced in two halves and the halves are named in
`SecureSurfaceDowngradeTest`'s KDoc. Forced: the classification, the re-open where the operator
permitted a lower level, the typed ending where nothing was permitted or there was nothing to fall to,
and the controls that an ordinary decoder failure — and a secure decoder Media3 could not *find* —
lower nothing. All of it through a real `SuperPlayer` over
`PlaybackHarness.failDecoderInitialization`, which arms `DecoderInitFault` on whichever renderer the
content enabled (the synthetic streams are audio-only, so a fault armed on the video renderer alone
would do nothing under a real manifest). Not forced anywhere: that a real device's failed secure
surface allocation produces that exception with that flag, which is established from Media3 1.11's
bytecode and cited at `ErrorClassifier.theSecureDecoderWouldNotStart`. It is not `devicelab`'s
either, and *What
is not covered here* says why.

`ProtectedPlaybackTest` is the worked example, and it drives a **stock** `ExoPlayer`: no SuperPlayer
DRM code exists until #204, so everything it asserts is a claim about this harness rather than about
the library. `PlaybackHarness.buildPlayer` refuses protected content outright and says why, because
the alternative is a player that silently plays a protected stream with no session at all and a test
that passes for it.

## Downloads

`superplayer-offline` downloads on Media3's `DownloadManager`, which loads on threads of its own, through
a data source it is handed, into a directory that has to outlive the process — and schedules through
`WorkManager` under conditions the platform reports. None of that is a player, so none of it reached
the seam above until #239. What stands in for each piece, and what each stand-in cannot show:

- **The network is the player's.** `PlaybackHarness.downloadEnvironment(content, faults, network)`
  returns a `DownloadEnvironment` — core's public type with an internal constructor, `ContentCache`'s
  shape, because the harness is phase 2 and the offline module phase 7 and neither can name the other's
  types. It carries the transport a `buildPlayer` player of the same content would load through (the
  origin, the fault injector, the shaper), and `Downloads.Builder` takes it through a setter internal to
  `superplayer-offline` (#240), so only that module's tests can hand one over. Beneath a download, core's `TransferChain.downloadChain` puts it where the HTTP stack goes (ADR-0013 rule 6), so
  `FaultScript` addresses a download's requests and `networkRequests(environment)` counts them exactly
  as for a player. This is the player seam's twin rather than a second seam: nothing new is
  configurable, and a consumer's store never has one.
- **The renderers are the player's too.** A download chooses its tracks against renderer capabilities
  (ADR-0013 rule 12), and Robolectric reports an empty codec list, so real renderers would say no
  synthetic track can be played and a download would choose nothing. The environment instead carries
  the stand-in renderers a `buildPlayer` player plays through, plus Media3's own text renderer, because
  a download has to be able to choose subtitles that a harness player never renders (#241). So a harness
  download selects what a harness player could play. That has two limits. Nothing here shows a device's
  decoders refusing a rendition. And the synthetic streams are audio-only, so no video ladder is cut by a
  ceiling: `DownloadSelectionParametersTest` reads the ceiling handed to Media3 instead of counting a cut.
  **That test reads past the facade.** It reads the internal `DownloadSelection`'s parameters, and this
  is where that is recorded, as the cache's and preload's tests are below. A synthetic video ladder is
  what would let it be a count instead.
  Media3's download helper also polls for a failed manifest on its own thread's system clock, which
  Robolectric moves only when a test tells it to.
- **Loads run on the download's own thread, counted.** Segment loads run where Media3's segment
  downloader hands them over, as Media3's default executor runs them, counted into the same wait a
  player's loads are, so a download's segments are fetched in manifest order on every run and
  `advanceUntil(environment, …)` knows when a load has finished. It cannot show what parallel segment
  fetching does within one item, which is a throughput question for Phase 10. Two items in one store
  load on two threads, as in an app. A thread shared by the environment was the earlier shape, and it
  coupled them: a load queued behind another item's delayed one kept the clock still, and an item that
  failed waited for its queued next segment behind the other item's held load (#244). `DownloadManager`'s
  own task thread and its main-thread callbacks are Media3's; the harness runs the main looper between
  passes and moves its clock only while a load waits on it.
- **Process death is a copy of the directory.** `processDeath(environment, directory)` stops the
  environment's loads being taken, waits until no load is in flight — moving the harness clock for a load
  held on it, so a death can land on a held segment — copies the directory, and returns
  the copy for the test to open a store over. A process that died released nothing — no cache lock, no
  database handle, no thread — so a store in the same test process cannot reopen the directory itself;
  what survives a real death is what was on disk, which is what the copy holds, including an index the
  store had not flushed. What it cannot show: a death *during* a write, since the copy waits for the
  load to finish, and a kill that lands between a span file and the index entry naming it. Media3's
  cache recovers from both on open, and nothing here proves it. One writer is not held either: the
  download manager's own progress update, on a timer of Media3's, which can land in the copy's window;
  if that ever shows as a torn index in a test, it is this stand-in and not the library.
- **A lost network is a stretch of the transport.** `loseNetwork(environment)` fails every request the
  environment opens to resolve its host, whatever its `FaultScript` says, until `restoreNetwork(environment)`
  (#242). The platform's readings do not move, so what it states is the case a store cannot see coming. A
  download stopped for it waits on the store's thread, whose clock Robolectric moves only when a test tells
  it to, so `DownloadResumptionTest` moves `SystemClock` a step per pass while it waits for a resumption.
  A failed request's retry waits on the same clock (#254), so `DownloadRetryBudgetTest` moves it too, and
  shows that with it held nothing is asked for again: Media3's own retries sleep the download thread, which
  no clock here moves.
  What that cannot show is a real radio's loss, which the platform also reports. That half is a statement
  of the platform's, below, and `DownloadConditionsTest` states it (#243).
- **The three conditions are stated into the platform.** `DeviceStatement.declareNetworkMetered`,
  `declareBatteryLow` and `declareStorageLow` write every reading a reader looks at — the active
  `NetworkInfo` and capabilities, the sticky `ACTION_BATTERY_CHANGED`, the sticky
  `ACTION_DEVICE_STORAGE_LOW` — and send the transition broadcast a device sends, and unlike the device
  statements above they may be restated mid-test, because a lapsed condition is the case. Robolectric
  does not shadow `removeStickyBroadcast`, so a storage recovery removes the sticky intent from its map
  by reflection, in `DownloadConditions` and nowhere else. Robolectric's own device is a *metered*,
  unvalidated network, which Media3's default requirement already refuses, so a download test that is
  not about the network states it unmetered first.
- **A full disk is a reading of free space.** `DeviceStatement.declareStorageFree(bytes)` registers what
  `StatFs` answers for every path, restatable mid-test, and the cache's download half reads it before each
  write, so zero is a disk that fills at the next write (#244). It is a reading and not a volume: it does
  not shrink as a download writes, so a test states a disk full rather than stating a size and waiting for
  it to fill. Robolectric describes no volume until it is said, which a download reads as nothing known
  and refuses nothing on. What it cannot show is the platform's own `ENOSPC` — the write failing because
  another writer took the space after the reading — which is translated to the same exception and forced
  by nothing here. `DownloadStorageFullTest` states it.
- **A protected download acquires on the player's device.** A download environment over protected content
  carries the Widevine device `DeviceStatement.declareWidevine` states and the licence server a player of
  that content is served, so `networkRequests(environment)` counts a download's acquisition and release as
  `ResourceKind.LICENCE` and a later player restores the licence from the same device (#245). Media3 retries
  a failed licence exchange on the session's own request thread, whose clock Robolectric moves only when a
  test tells it to, so `DownloadLicenceTest` moves `SystemClock` while a refusal is retried. On a store with a
  resilience the wait is the licence budget's (#260), and `DownloadLicenceBudgetTest` reads that clock at each
  ask to tell the budget's jittered wait from Media3's immediate first retry. What it cannot show:
  a key-set id a device forgot, and protection an HLS stream declares only in its media playlists, which the
  synthetic stream does and `DownloadLicenceTest` records as downloading with no licence.
- **Live content is the player's live content.** `TestContent.liveHls()` is the origin that keeps
  publishing, and `TestContent.liveDash()` is the corpus's healthy dynamic MPD served from fixed bytes, which
  is enough for a store that reads a manifest once to learn it is live (#251). The refusal arrives from
  Media3's download helper as the timeline is built, so no clock needs moving for it; an unreadable manifest
  still does, for the poll above. What it cannot show is a live manifest a real origin changes between two
  reads, which nothing here needs, since the first read refuses.
- **`WorkManager`'s constraints are evaluated by the harness, not by `WorkManager`.** Its test driver
  runs constrained work only when told every constraint is met, so `runScheduledWork()` reads each
  enqueued request's `Constraints`, checks them against the statements above, and tells the driver only
  where all hold, each read as WorkManager 2.11's own tracker reads it (no battery reading at all is the
  battery-not-low constraint *unmet*, and being plugged in is not consulted); a constraint the harness
  cannot state — charging, idle — fails the call rather than
  passing silently. What it cannot show is that WorkManager's own trackers agree with those readings on
  a device, which #247 checks there. `useScheduledWork()` installs the test driver; WorkManager is a
  compile-only dependency of the testkit, so a module whose tests schedule work declares
  `work-runtime` and `work-testing` itself. **Every test that enqueues calls it**, not only a test about
  scheduling: a store with anything pending schedules work, nothing initializes `WorkManager` under
  Robolectric, and an earlier test class's installation outlives it in the same JVM, which is how
  `superplayer-offline`'s classes passed together while failing alone before #244.
- **A reboot is not stated.** WorkManager's test implementation keeps its work in an in-memory
  database, so nothing persisted survives a simulated restart and there is no faithful stand-in under
  `check`. Under `check`, #243 asserts only that downloads are scheduled as persisted work under their
  constraints; surviving a reboot is verified on a device, in #247, and nowhere else. `DownloadConditionsTest`
  is that assertion: the work's `Constraints`, held while a statement fails them, and the download it
  resumes once `runScheduledWork()` finds them met. What runs the download in-process is the store's own
  watcher hearing the same statement, so the work's run is shown to happen, not shown to be what started it.
- **The service is Robolectric's, and a start is a recorded intent.** `DownloadsServiceTest` builds the
  app's `DownloadsService` with `Robolectric.buildService` over a store the test opened, and reads its
  foreground state off `ShadowService`: a notification announced, the foreground stopped, the service stopped
  by itself. What the store and the scheduled work start is read off `ShadowApplication`'s started services,
  which records a start and runs nothing, so a test that wants the service running builds it itself (#246).
  Robolectric forgets a notification `stopForeground` removed but not its id, which is why a service that
  announced itself and stopped at once is asserted by the id. What it cannot show: the platform refusing a
  foreground-service start from the background, the `dataSync` type's time budget, and the notification a
  viewer sees. Those are a device's, and #247's.

`DownloadHarnessTest` is the worked example. There is no store yet, so what downloads is Media3's own
`DownloadManager` over a `SimpleCache` in a temporary directory, and everything it asserts is a claim
about this harness rather than about the library — as `ProtectedPlaybackTest` was before #204.

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

It offers two ways to supply media: a *transport*, which takes the HTTP stack's place beneath
SuperPlayer's own layers, and a whole media source factory, for content Media3's fakes synthesize with
no transport to stand in for. `EngineConfiguration` says why the first is the one to reach for.

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

`superplayer-abr` is the second friend, for a different seam reached the same way: its policy object
implements a core-internal extension interface so that its engine components — every one an
`@UnstableApi` Media3 type — can fill `EngineConfiguration`'s slots from inside
`SuperPlayer.Builder.build()` without a Media3 type in any public signature. ADR-0009 rule 7 makes the
argument. The extension runs *before* the test configurator, so a test's engine configuration still
wins over the policy's exactly as it wins over the profile's.

`superplayer-cache` is the third, because `ContentCache`'s constructor, `CacheLayer` and the request
stamp a key is read from are core's internals (ADR-0010 rule 3). Its tests hand a cache to
`harness.buildPlayer(cache = …)` and count cache hits as requests that did *not* reach
`harness.networkRequests(player)`, which records at the transport below every layer core composes;
`TestContent.servedFrom(host)` serves one stream from a second host, which is what a content key has
to survive.

Two of its tests read past the facade, and this is where that is recorded rather than left to be
found. `ContentKeysTest` drives the internal key factory with hand-built requests, because the
cases it pins — a rendition under a second path, a signed query, an id that looks like a path — are
ones no synthetic stream produces. And the live-stream test asserts on the internal
`ContentKeyedCache.keys()` that no playlist was *stored*: network requests show a playlist was
fetched, not that a copy was never written, and a written copy is the defect the rule exists against.

Sizing and eviction read past it too. `CacheEvictionTest` writes and reads whole entries of one known
size through the internal `SimpleCache`, because eviction order, pinned accounting and a reopening
under another budget are claims about bytes, and a stream's segmentation would make every one of them
approximate. The two eviction tests in `ContentKeyedCachePlaybackTest` then show the same rules on what
a player stores, and they read the internal `keys()` and `heldBytes()` for the reason the live-stream
test does: the network shows what was fetched, and eviction is about what was kept.

`superplayer-preload` is the fourth, because a coordinator attaches to a pool through core's internal
`PoolAttachment` and builds Media3's preload manager over the pool's internal `SharedComponents`
(ADR-0010 rules 6 and 8). A feed test needs several players and a coordinator on one clock and one link,
and `harness.buildPool` is where that lives rather than in a second harness: every player of a pool
loads through **one** transport — one origin, one injector, one shaped link — so a coordinator's
prefetches go through it too, and `harness.networkRequests(pool)` lists the screen's requests, rows and
prefetches, in the order they were made. `buildPool` also takes the trace, the cache and the policy a
pool's players share. The engine's clock and renderers now reach `SuperPlayer.Builder` through
`EngineConfiguration`'s `clock` and `renderersFactory` slots rather than calls on the engine builder,
because a pool hands both to the preload manager and `build()` can only hand on what it can read back.
The synthetic HLS stream is audio, so a test of time to first frame plays the harness's described video,
which under a trace loads its chunks through the same shaped link.

Core's side of that seam reads past the facade, recorded here as the cache's is. `PooledEngineTest`
drives a hand-written `PoolAttachment` against the internal `SharedComponents` — minting an id,
building an item and a source through them — because core's half of ADR-0010 rule 7 has to hold
before the module that uses it; and it reads `exoPlayer.playbackLooper`, because *which thread* a pooled
player plays on is the claim of rule 9 and no public surface states it. Rule 13's count is taken where a
consumer can observe it: a pool with nothing attached runs a playback thread per player and fetches
nothing ahead of the row it plays, and the same pool with a coordinator shares one thread and fetches
the rows ahead, so the counter is shown to count. Nothing counts classes loaded, because
`superplayer-preload`'s classes are reachable only through a consumer's own call to its builder.

`superplayer-resilience` is the fifth, because the two slots it fills — the `HeaderRefreshLayer` in the
transfer chain and the `LoadErrorHandlingPolicy` the media source factory is handed — are Media3
`@UnstableApi` vocabulary behind core's `EngineResilienceExtension` (ADR-0011 rule 13). The module has no
code yet; core's side of the seam does, and `SuperPlayerResilienceSeamTest` is where it is held, with a
resilience hand-written in core's own tests rather than the module's.

That test reads past the facade, recorded here as the cache's and preload's are. It asserts on the
`EngineConfiguration` the builder filled, because "the slot is empty" is a claim about construction that
no playback can show; and it reads the internal `RequestStamp`, `LoadKind` and `ContentIdentity` off the
requests the slot saw, because what ADR-0011 rule 13 promises a classifier is exactly those fields.
Rule 14's count is taken on what a consumer can observe alongside it — a player built without resilience
stamps no request at all, and the same player with it stamps every one, so the counter is shown to count
— and the rule's other half, that a core-only session is unchanged, is held by the golden traces in
`superplayer-telemetry`, which this change leaves byte-identical.

`superplayer-drm` is the sixth and, by ADR-0012 rule 4, the last to *fill* a slot (ADR-0013 rule 4 adds a
seventh, `superplayer-offline`, which is built from core's seam and fills no slot): the one slot it fills
is a `DrmSessionManagerProvider` for every source `TransferChain` builds, and *every* Media3 type DRM
needs carries `@UnstableApi`, so there is no version of its engine-facing half that could have been
public API instead. Its `WidevinePlaybackTest` plays both protected protocols through a real
`SuperPlayer` over the harness's licence server; `SuperPlayerDrmSeamTest` holds core's side, with a
protection hand-written in core's own tests, as the resilience seam's test does.

That test reads past the facade for the same reason and in the same way: it asserts on the
`EngineConfiguration` the builder filled, because ADR-0012 rule 13's claim is that a slot is *empty*,
and no playback can show that. The distinction it exists to keep is the rule's own — a provider that
is set and answers `DRM_UNSUPPORTED` is not nothing — so what is counted is the set, never the answer.
It also pins the ordering the whole Robolectric DRM story rests on: the device a test states reaches
the slot at chain-composition time rather than through the extension, because an extension runs
*before* the test configurator and so before a test has said what device this is.

`superplayer-tv` is the eighth friend, and it fills a slot as the sixth does: `EngineConfiguration.videoOutput`,
a per-player binding core tells of every surface the facade is handed and of each item's declared frame
rate (ADR-0014 rule 3). Filling it builds the engine with Media3's own frame-rate request switched off,
which is `@UnstableApi` vocabulary. Its `FrameRateMatchingTest` plays described video declaring a frame
rate through a real `SuperPlayer` on a display `DeviceStatement.declareDisplayModes` states, and reads what
was asked of the display through `harness.frameRateRequests`. `SuperPlayerOutputSeamTest` holds core's side,
with an output hand-written in core's own tests.

That test reads past the facade, recorded here as the DRM seam's is. It asserts on the `EngineConfiguration`
the builder filled, and on the engine's `videoChangeFrameRateStrategy`, because ADR-0014 rule 14's claim is
that a player without the module keeps Media3's own strategy, and nothing public reports which one an engine
was built with. The same count on a player *with* an output reads the strategy off, so the counter is shown
to count. Since #269 it also counts the display service's `DisplayListener`s, read by name from the hidden
`DisplayManagerGlobal`, because "no listener is registered" is rule 14's claim and no playback shows it, and
reads the filled configuration's `displayInForce`, the window `superplayer-abr`'s gate reads, because core's
tests cannot name that gate. Since #270 it reads the engine selector's
`allowInvalidateSelectionsOnRendererCapabilitiesChange`, ADR-0014 rule 6's switch, off on a player without
an output and on with one. No core playback shows it, because core's tests state no audio output that
changes. `superplayer-tv`'s `AudioCapabilityChangeTest` shows the behaviour, its control included. Since
#271 it reads the same selector's `tunnelingEnabled`, ADR-0014 rule 7's parameter, laid from a decision that
asks for tunneling only on a player with an output. Core's tests play no video beside audio, so no core
playback shows it. `superplayer-tv`'s `TunneledPlaybackTest` shows it applied at the renderer, its controls
included.

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
duration into a tolerance. And it moves them only once the engine has nothing left to do at the
current time, with nothing run between the two moves. Media3's fakes finish a `prepare()` — source,
period, renderers, first frame — with no time passing, in a chain of messages the engine posts to
itself; a harness that moved `SystemClock` first and then let that chain run stamped the frame with
the new time when the test thread won the race and with the old one when a loaded runner let the
engine win, and a time to first frame read 50 ms or 0 ms accordingly (issue #110). `settle` is the
same quiet, exposed: it returns when that chain has run out, not when the first message has been
acknowledged.

**It can play a real protocol, too.** `TestContent.video()` and its siblings describe content that
Media3's fakes synthesize — fast, and enough when the subject is a *measurement*. `TestContent.hls()`
and `TestContent.dash()` are a different kind of content: `superplayer-testmedia`'s synthetic streams,
parsed by Media3's own playlist and manifest parsers, demuxed by its own extractors, and fetched one
resource at a time through a `DataSource` chain with the fault injector in it. That is what makes the
injector's addressing observable under the protocols rather than only over a URL sequence, and
`ProtocolPlaybackTest` plays both end to end. A test hands `content.sourceUri` to `setMediaRequest`;
the harness names no `FakeDataSet` in its API and no Media3 type reaches the test.

**What it fakes, and what that costs.** Stalls can be injected by making the video renderer stop
being ready, which reproduces the engine's buffering state machine exactly and the *cause* of a
rebuffer not at all; dropped frames are raised on the renderer's own callback rather than by a
decoder that could not keep up. The renderer-driven stall is exact to the millisecond and stays, for
the tests whose subject is a duration; a stall with a *cause* is what the fault injector below is
for, and `superplayer-telemetry`'s `FaultInducedRebufferTest` is the worked example. Dropped frames
are still a stub, and wait on the throughput replay `#40` brings.

### Forcing the faults, rather than waiting for them

`PRD.md` Part 5 requires that every fallback rung has a test that forces exactly that rung, and a
rung with no way to force it is a rung nobody knows is broken. `FaultScript` is how a test says which
one: latency, a throughput cap, an HTTP 403, 404 or 500, a truncated body, a DNS failure, a TLS
handshake that does not verify, a connection reset, a connection timeout, or a token that expires at
a chosen segment.

```kotlin
val player = harness.buildPlayer(
    content = TestContent.videoLadder(),
    faults = FaultScript.Builder().expireTokenAtSegment(3).build(),
)
```

Five things about it are load-bearing.

**A fault is addressed by what is being fetched, never by a URL.** A `ResourceKind` — manifest,
initialization segment, media segment, licence — and an index within that kind is the same sentence
under HLS and under DASH, so one script runs against either and means the same thing. A test that named a path
would be a test that had to be rewritten for the other protocol. `FaultInjectionTest` is where that
equivalence is pinned, and it pins it against sequences it did not invent: the URLs come out of
Media3's own HLS playlist parser and DASH manifest parser, handed a playlist and an MPD, so what is
compared is what the protocols actually fetch.

The kind itself is recognised from the naming every packager uses — `.m3u8` and `.mpd` for a
manifest, a name containing `init` for an initialization segment, anything else a media segment —
with one exception that is not a naming convention at all: a request is a **licence** request because
it went to the licence server's host, which is a server the app nominated rather than one the
manifest named. The rest is a **heuristic, not a protocol guarantee**: neither RFC 8216 nor ISO/IEC 23009-1 reserves
a name for an initialization segment. It is right for what this repository generates and for the
conventions in both specs' examples; a stream that named its media segments `init-0001.m4s` would be
classified wrongly, and the place to fix that is `ResourceAddressBook.kindOf`, which is the one
function that knows a URL was involved at all.

It has since been checked against what the real parsers actually request, which is a stronger claim
than the paragraph above could make on its own: `ProtocolPlaybackTest` plays both synthetic streams
through Media3's own HLS and DASH sources and asserts on the addresses assigned. Under HLS they are
the multivariant playlist, the media playlist, then the segments — two manifests and no
initialization segment at all, because packed audio has none. Under DASH they are the MPD, the
initialization segment, then the segments. Media segments are numbered from zero under both, which is
what lets `FaultInjectionPlaybackTest.theSameScriptFailsTheSameSegmentUnderHlsAndUnderDash` run one
script against both and compare the two lists. The one thing worth knowing is the asymmetry: a
`ResourceKind.INITIALIZATION` fault addresses something under DASH and nothing under HLS. Indices count *distinct resources of one kind in the order they were
first requested*, keyed on the URL without its query and on the byte offset asked for — so a retry
under a refreshed signature is still the same segment, and a stream packaged as byte ranges of one
URL is still a stream of segments.

**A fault can relent, and that is the third coordinate of the address.** A fault applies to every
attempt at the resources it addresses by default, so a retry meets it again — which is what makes it
the right fault for a session that cannot be saved, and the wrong one for every rung above "it
failed". `firstAttempts` bounds it: the resource fails that many times and is then served, which is
how "and the retry recovered" is written down. Attempts are counted **per resource**, on the
addressing above and from one, so a segment fails its own first attempt however many segments were
fetched before it, and nothing about which resource a fault addresses changes.

A token has the same two halves and its own call for them, because a token is the session's rather
than one resource's. `expireTokenAtSegment(n)` is a 403 from that segment to the end of the session,
whatever is retried — the fault `superplayer-resilience` exists for. `expireTokenAtSegment(n,
refreshable = true)` is the same until a request arrives bearing a different credential, and from
then on the session plays on: the origin takes the request's `Authorization` header, or its query
string where a signed URL carries the signature, as the credential, so a test refreshes a token by
fetching under a changed one and the harness needs no vocabulary for how it was obtained. One
refresh heals every resource the token signs, not the one that met the fault.

**A fault can be addressed at an origin host, and that is the fourth coordinate.** It is the one
part of the address that names something from the URL, and it is there because the thing under test
is precisely which URL was used: the rungs above a retry move the session *somewhere else*, and
"host A fails while host B serves" cannot be said in a kind and an index alone, because the two
hosts carry the same resources under the same names. A test that wants one rendition to fail rather
than one host puts that rendition on a host of its own — `TestContent.hls(secondVariantHost = …)` —
which does not weaken the rule above so much as apply it: the two renditions of the synthetic HLS
stream declare different bitrates and carry identical bytes, so a host is the only thing about one of
them a script could name, and naming it is still not naming a path. The same coordinate serves rung
2, where `TestContent.dash(mirrorHost = …)` is the same media at two `BaseURL`s (ISO/IEC 23009-1
§5.6.4) with the MPD on the first. Indices are per resource and therefore per host: the same segment
on two hosts is two resources with two indices, since nothing below the transfer knows the two are
copies, so "everything this host serves" is written with a host and no index. Leaving `host` unset
addresses every host, which is every script written before the coordinate existed.

**It does not relax the no-network rule.** Every fault is synthesized: a DNS failure is an
`UnknownHostException` handed to Media3 in the shape a real resolver failure arrives in, a TLS
failure an `SSLHandshakeException` and a reset or a timeout the `SocketException` and
`SocketTimeoutException` a real stack raises — each inside the `HttpDataSourceException` and under
the Media3 error code the classifier will read it in — and no socket, no resolver, no certificate and
no device is involved. An injector that needed a real network to inject a network failure would have
missed the point of this document.

**It is transparent when nothing is armed, including to the bandwidth meter.** The wrapper composes
over whatever `DataSource.Factory` the harness installed rather than replacing the mechanism, and it
registers a `TransferListener` on the upstream source rather than re-raising the callbacks itself —
so the bytes reported and the bytes moved cannot disagree. Measurement is a propagated
`TransferListener` (`PRD.md` §2.4): a wrapper that swallowed one would blind the bandwidth meter and
make every ABR test in phase 3 quietly meaningless.

Delays are paced on the harness's clock rather than on the wall clock, so a fault lands where the
test said it would on every run and every machine — and a test that arms a delay and never advances
time past it fails saying so rather than hanging.

The injector's **own** tests are the one place in this repository that asserts on something other
than the library's public API: `FaultInjectionTest` drives the wrapper directly and reads the
addresses it assigned. That is not an exception to "assertions stop at the facade" but the same rule
applied one level down — the subject there *is* the harness, and a test-support tool that is wrong is
worse than none, because every failure it causes is read as a failure of the code under test.
Everything that uses the injector to test the library still goes through the public API.

Which rung each forcing test forces is written down in one place: `superplayer-resilience`'s
`FallbackRungCoverageTest`. It names, per `FallbackRung`, the test that forces that rung and the test
that counts what a player built without resilience pays for it, and it checks those names against the
source tree — so a renamed or un-`@Test`ed method fails there rather than leaving a rung silently
unforced, and a seventh rung added with no entry fails the day it is added. The names cannot be
collected as code because the rungs are forced across two modules: rungs 4 and 5 are performed by
core, and a module's test classes are not on another module's test classpath.

Beside it, `FaultSweepTest` plays **every** `FaultScript` fault kind under both protocols and records
how each session ended, which is `PRD.md` Part 4's Phase 5 criterion — every injected fault recovers
or ends in a named class, and no session ends on a failure nothing classified. The kinds it sweeps are
read off `FaultScript.Builder` by reflection, so a fault kind added later is visibly missing from the
table rather than silently unswept.

### Replaying a network

A fault is one request going wrong; a network is every request going through something. The harness
replays one — a `ThroughputTrace` of bandwidth, round trip and transport over time, or a
`NetworkProfile`, the six `PRD.md` Part 6 names and `ETHERNET` — under every transfer a player makes:

```kotlin
val player = harness.buildPlayer(network = NetworkProfile.LTE_WITH_DROPOUTS.trace, faults = script)
```

It is the same mechanism as the injector, one layer out: a `DataSource` wrapper paced on the
harness's clock, in front of the injector, so a shaped network and a fault script are one player and
the 403 arrives a round trip after the request, as a real one does. Arrival times are computed from
the trace rather than accumulated, so the same requests opened at the same times deliver the same
bytes at the same times on every run. The trace's *transport* is replayed too — into Robolectric's
connectivity service, as the clock crosses a stretch whose transport differs — so a handover is a
change of network the library observes through the same callback it registers on a device, and
not only a change of rate; `superplayer-abr`'s tests assert the oracle's reseeded estimate at the
millisecond the trace names.

A module whose engine components reach the player through core's extension interface puts them
under a harness player with `buildPlayer(policy = …)`, which is `SuperPlayer.Builder.setPolicy`
and nothing more: the extension fills the engine's slots before the harness configures the clock
and the transport, exactly as it does for a consumer. [`docs/throughput-traces.md`](throughput-traces.md) is the
format's specification, the replay's rules and limits, where each profile's numbers come from, and
how a public dataset is converted — none is vendored here.

### Golden traces

A test asserts what someone thought to assert. A golden trace records what a session *did* — every
state transition, track selection, load by kind and media time, error and telemetry event, one per
line in a fixed order — and holds it to a committed file, so a change in behaviour that no assertion
names still appears in review, as a diff. "This change also moves the first segment load a step
later on 3G" is a sentence a diff can say and a test suite cannot. The goldens live in
`superplayer-telemetry/src/test/golden/`, one whole session each, played by `GoldenTraceTest` through
the harness: each protocol on demand, and each over a shaped network.

The recorder is `superplayer-telemetry`'s `SessionTraceRecorder`, a `TelemetrySink` that also reads
Media3's analytics, and the artifact is a `SessionTrace`. It lives in the telemetry module rather
than here because ADR-0008 rule 2 puts every analytics registration there, and because a trace is
half telemetry: every `TelemetryEvent` the collector emits is a line, printed with its fields, so a
changed number in `docs/telemetry-schema.md` is a changed line. The class documentation is the
format's specification; two of its rules matter to a test author:

- **The order is not arrival order.** Lines sort by time, then by kind, then by what the fact is —
  two chunks that finished in the same millisecond print by media time whichever loading thread won.
  Arrival order is a fact about the host, and the trace records none of those.
- **A trace is redacted by construction.** No URL, header, exception message, session id, wall-clock
  time or device identifier can reach a line, because the recorder never holds them; the class
  documentation lists the six rules and `SessionTraceRecorderTest` plays a session through a signed
  URL that fails, and shows none of it in the trace. That is what makes a trace captured on a device
  attachable to a bug report — and a device trace's one normalisation is `withoutTimings()`, which
  drops the millisecond column and keeps the order it decided.

The contract is `docs/api-surface.md`'s, deliberately. Nothing regenerates a golden implicitly.
A golden test in check mode — the default, and what `check` runs — fails on any difference with the
diff in its message, so an uncommitted change to a golden fails `./gradlew check` and CI alike.
Accepting a change is one command, run on its own and then reviewed:

```bash
./gradlew updateGoldenTraces     # rewrite every golden from what the tests now produce
git diff -- '*.trace'            # the behaviour change, as a reviewable diff
```

When the diff means "fix the code" and when it means "accept the change" is the reviewer's call,
and the point of the tool is to make it a call rather than a rubber stamp. A line that moved
because a policy constant moved is the intended consequence of that change, and the diff is its
documentation. A line that moved in a case the change was not about — a startup track on a network
profile nobody edited, a load that now happens a step later — is a finding, and the golden is what
found it. A golden that is regenerated in the same commit as an unrelated change, without a
sentence saying why each line moved, has been rubber-stamped, and reviewers should say so.

What may go into a golden is decided by one property: **the trace is byte-identical across runs and
machines.** A golden that flakes is deleted within a month, so a case that cannot be made
deterministic is not golden material, however interesting. Two mechanics carry that property here.
The harness advances the clock only once the engine has nothing left to do at the current time — it
owns the loading threads (`HarnessLoadThreads`) so it knows when a load has *finished* rather than
only when its transfer closed, and it settles both loopers until neither has anything due
(`PlaybackHarness.quiesce`). Within one step the order is fixed too: loads waiting on the clock are
held at the old moment while the engine acts on the new one, and released onto it after, because a
load released by the same clock move writes samples while the renderer reads them, and made a player
ready a step early on a loaded runner (issue #120). And the recorder stamps an engine fact with the engine's own event
time rather than with the moment a listener heard of it, which is a looper hop later and, under a
stepped clock, can be a step later on one run and not the next. `SessionTraceRecorderTest` plays the
same session three times in one JVM and holds the traces equal; running `GoldenTraceTest` on a
second machine is the other half of the demonstration. The one path that is *not* golden material
is the described fake source (`TestContent.video`, `videoLadder`): Media3's `FakeAdaptiveMediaSource`
takes no executor, so its loads run on a thread the harness cannot see, and its timings are exact
to within a step rather than exact. A golden of a ladder waits on a synthetic multi-rendition stream
in `superplayer-testmedia`.

Adding a golden is a test method in a class whose name contains `GoldenTrace` — that is how
`updateGoldenTraces` selects what to run — that plays a session and passes the formatted trace to
`GoldenFile.check(name, text)`; the update command creates the file, and the review of that first
commit is the review of the behaviour. The seam for `superplayer-diagnostics` is the same artifact
one layer richer, which is why `SessionTrace` says how a kind is added without a second format.

### The QoE regression gate

A golden says a session *changed*; it cannot say whether the change was worse. `PRD.md` Part 5 asks
for that too — replay throughput traces through the selection and buffering logic, score each session
with the QoE objective, and gate on the score — and `superplayer-abr`'s `QoeRegressionGateTest` is
that gate. It fails `./gradlew check` when a trace's score drops past its committed floor.

**Where it runs, and why there.** In `superplayer-abr`'s own unit tests, under the harness, because
that is where the thing it protects is built and because `check` is this repository's definition of
passing. The full `PRD.md` §6 matrix in `benchmark/` was the other candidate and is the wrong one: it
is a separate build that `check` cannot reach, twenty runs of ninety cells is a measurement rather
than a check, and it compares three players where a gate asks one question of one. CI runs the gate
because it runs `check`, and nothing else.

**What a session is.** The benchmark's arm (c) with the adaptive policy in it — its VOD ladder, its
sixty-second session, `AdaptivePolicy.forProfile(VIDEO_ON_DEMAND)`, `QoeCollector` as the source of
events — played for every `NetworkProfile`, all seven. Stable WiFi and high latency are in it beside the
four the phase is meant to improve, so a change that wins on cellular by losing on WiFi meets a WiFi
floor.

**Which profile.** One, `VIDEO_ON_DEMAND`, for every trace, `ETHERNET` included (#267). The gate asks
whether the adaptive policy got worse on a network, and a row is comparable with its neighbours only
while the session is the same session: an `ETHERNET` row played under `TV_LEANBACK` would differ from
`STABLE_WIFI`'s by two variables at once. A profile is not gated on its own either. A profile is a table
of starting numbers each argued where it is chosen, and the policy's code it would exercise is the code
the on-demand rows already exercise; a per-profile gate would multiply the gate's cost by five to score
a buffer depth the QoE objective does not reward, since a deeper cushion on a stable link changes no
term of the score. What `TV_LEANBACK` on Ethernet does differently is forced by `TvLeanbackPlaybackTest`,
as a fact about media held rather than as a score. The score is not computed twice: `SessionMetrics` and `QoeScore` moved from `benchmark/` into
`superplayer-telemetry` for this, so the gate and the benchmark reduce events through the same file.

**Which traces.** Synthetic only, each named by its `NetworkProfile`, and that is a decision rather
than a gap. A recorded trace could only reach the gate converted at build time, which needs the
network this document bars, or converted once and committed, which makes it a vendored dataset with
a licence to record in `THIRD_PARTY.md`. The profiles are deterministic, cite their numbers, and make
a regression attributable to a named condition; a recorded trace joins the gate when one is committed
on those terms, as a row in the floors file like any other.

**The floor and the margin.** `superplayer-abr/src/test/qoe-floors.tsv` holds one row per trace: the
score, its three terms, and why the floor is where it is. A run fails when a trace scores more than
**0.05** below its row, in the objective's own unit of Mbps-equivalent per second played. The gate
plays each trace **three times and judges the median run**. Both numbers come from a measurement:
ten runs of every trace in one JVM agreed to within 0.001 on a quiet host, and the same ten under
contention on every core agreed on five traces while congested WiFi scored 0.923 once against 0.711
nine times. That outlier comes from the described fake source, which *Golden traces* above already
calls exact to within a step rather than exact, and a single run of it would have been a one-in-ten flake. A median
of three absorbs one outlier in either direction. It costs a few seconds of `check`.

That is an exception to *Determinism* below, which says a flaky test is a bug in the test, and it is
taken knowingly rather than overlooked. The variation is not the gate's own: Media3's
`FakeAdaptiveMediaSource` loads on a thread the harness cannot see, and nothing inside this repository
can hold that thread to the clock. The alternatives were worse. Audio-only synthetic HLS carries no
video bitrate to score. A single run would fail roughly one `check` in ten under load. A margin wide
enough to swallow a 0.2 jump would pass three seconds of stall a minute. The median ends when a
synthetic multi-rendition *video* stream lands in `superplayer-testmedia`, the same thing a ladder
golden trace waits on. At that point the gate plays each trace once and `RUNS_PER_TRACE` goes.

What the median leaves behind, stated rather than implied: if one run in ten is an outlier, two in
three happen about 3% of the time for that trace on a fully loaded runner. That fails the gate only
when both outliers fall *below* the floor, and the one outlier measured went above it — which passes.
A `check` that fails with a congested-WiFi row and runs that disagree past the margin is the case to
re-run once before reading it as a regression, and the gate prints the three scores so that is visible.

The margin sits well above that spread and below what anyone would call a small regression. One more
second of stall per minute costs 4.5 / 60 ≈ 0.075, because `μ` is the ladder's top rung. A drop of
0.05 is also a 50 kbit/s fall in time-weighted bitrate, a seventh of the smallest step between rungs.
A margin that passed a second of stall a minute would not be a gate.

**What a failure means.** A behaviour change, not a slow runner: the harness replays each trace on
its own clock, and the median takes care of the one path that is not exact. The failure prints every
trace in one table, worst first, with score, bitrate utility, rebuffer penalty and switch penalty each
shown floor → this run, so the review is of *which term moved* and not of one number. Fix the change,
or, when the loss is the intended price of something, lower the floor in the same commit and say why
in the row.

**Moving a floor is a decision.** Nothing writes the floors file; there is no update command, on
purpose. A trace that improves past the margin passes, and the gate prints the row that would record
it — and leaves the floor where it was. A floor that followed improvements on its own would let an
unrelated gain on one trace pay for a regression that lands on it later, invisibly. So a floor rises
when a change commits the new row with the reason in its last column, and the gate refuses a row with
no reason, a trace listed twice, or a score its own terms do not add up to. What the gate cannot
check is that a reason is *new*: a score moved with the old reason left beside it passes the parser.
That half is review's, and a diff to `qoe-floors.tsv` whose last column did not change is the thing
to ask about.

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

The engine's own watchdogs are timer-driven in exactly that sense, and one of them is why
`SuperPlayerHarness` switches it off: Media3 raises a `StuckPlayerException` after ten minutes of
buffering, measured on the player's clock, and ten minutes of auto-advancing fake time is however
long a loaded host takes to load one chunk. `SuperPlayerHarness.useHarnessClock` carries the
argument. A test's own wait is what reports a player that really is stuck, and it names the state
that never arrived.

**A fixed span of playback time is not a wait.** The clocks are fake but the loading threads are
real, so how far a session gets in 40 s of playback time is the host's answer, not the stream's — and
the three tests of issue #91 each asserted on the answer. `PlaybackHarness.advanceUntil` is the shape
to reach for: the condition that is actually being waited for, bounded by something the content or
the policy names, failing with the state that never arrived. A span is right only when the claim is
itself about a span — that nothing happened over one — or when the span *is* the measurement, which is
why the telemetry tests keep theirs: they advance a stated number of milliseconds and assert on the
duration that comes back, so the number is the subject rather than a guess at what a machine needs. The harness helps on its side, too:
`advanceTimeMs` will not leave a transfer behind, so the clock cannot run minutes ahead of the loads
and present a healthy live stream to Media3 as one that stopped advancing.

## What is not covered here

Instrumented tests on real devices are `superplayer-testkit`'s subject and arrive with it — as the
fault injector, the throughput trace replay and the golden traces above already have. They extend
this seam rather than replacing it: they still drive the library through its public API.

Benchmarking is not covered here, because it is not testing either. `benchmark/` runs `PRD.md` §6's
fixed matrix across three players and emits a report; it is a separate Gradle build, it is not in
`check`, and a run of it fails only when it could not measure — never because of *what* it measured.
Its Robolectric arm honours this document's rules as it happens, needing neither a device nor a
network, and it still sits outside the suite: twenty runs of ninety cells is a measurement someone
reads rather than a check a change must pass. Its device arm needs both a device and a network,
because peak RSS and battery are properties of a process on a device and there is no honest way to
take either from a JVM. `benchmark/README.md` says all of this from the other side.

Measurement on a device is not covered here either, because it is not testing. `devicelab/` drives
the demo on an emulator or a phone and returns a Perfetto trace. A run fails only when it could not
measure, for example when playback never started or the APK was stale. It never fails because of what
it measured. So it sits outside `check` rather than breaking this document's no-device rule, and
its README says so. Its device-free self-test is the exception, and it is in `check`.

**A reboot is not covered here.** Under `check` a download's scheduled work lives in WorkManager's
in-memory test database, which no simulated restart survives; *Downloads* above says what that leaves
to a device (#247).

**And a claim `devicelab` cannot carry either, named because #226 had to answer where it goes.** Two
reasons, and the first settles it on its own: `devicelab` measures and never asserts, so a scenario
could report a secure surface that failed to allocate but could not fail on one. The second is that no
run can put a device *into* that state — it is a property of a particular handset under particular
load rather than a condition a scenario provokes — and the device `devicelab` boots by default is an
emulator, on which a protected session is not obtainable at all. So the answer for that half is **not
verified anywhere**, said plainly here and in `SecureSurfaceDowngradeTest`, rather than a scenario
that looks like coverage.
