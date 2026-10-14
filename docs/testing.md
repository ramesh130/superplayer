# The test seam

Every test in this repository drives SuperPlayer through its **public API**, under Robolectric, on
the JVM, against Media3's own fakes. No device, no network, no assertion that reaches past the
facade. This is not a default that happened to stick — it is the constraint the first slice of the
library was built to satisfy, and it holds for everything added afterwards.

There are eight documented exceptions. Six of them are still tests with no device and no network; the
seventh binds a server socket on the **loopback** interface and reaches nothing off the host, for the
reason *The conformance test a consumer runs* gives — an HTTP client's obligations are about what it
puts on a wire, and there is no wire in any other fake here. The eighth is #364's and departs on a
different axis: `MoqFfiLinkageTest` drives **no SuperPlayer API at all** and loads a real native
library into the test JVM, because what it checks is whether a natively built dependency links.

**The Swift products are held to a different seam**, decided in
[ADR-0021](adr/0021-test-ios-against-a-loopback-origin-and-run-its-checks-in-one-script.md): AVFoundation
ships no fakes and `AVPlayer` takes no fake transport, so every iOS test plays from a loopback HTTP
origin started per test — the seventh exception's carve-out widened to all of them — on real time,
with structural goldens; *The iOS harness* below is that origin. Everything else in this document
binds the Kotlin modules as written.
*The MoQ bindings* argues it, and states what no automated run can show.

`SuperPlayerTransferChainTest` keeps the HTTP stack `SuperPlayer.Builder` puts at the bottom of its
chain instead of substituting a fake data source for it, and reads a `file:` URI. "The one player that keeps its own transfer
chain" below says why that is necessary rather than merely convenient.

`SuperPlayerHttpStackTest` keeps that same chain and puts a consumer's `HttpTransport` at the bottom
of it, because Phase 10's seam is the one layer a substituted fake data source *replaces* rather than
sits above. Two of its tests also open the adapter's `DataSource` without a player, to state a byte
range no synthetic stream here can provoke. "The one player that keeps its own transfer chain" below
carries the whole argument.

`ConsumersTransportEvidenceTest` keeps that same chain and the same transport for the *response* side
of an exchange: ADR-0016 rule 8 puts the status, the response headers and the URI of a refusal on
core's adapter, and no fake origin here can express a response header at all. It also reads core's
own internal `LoadKind` off the `DataSpec` a refusal carries, which is a core unit test's to do and is
the one stamp everything downstream of a refused segment depends on.

`TransportContractTest` keeps that same chain and that same origin for the three obligations
ADR-0016 leaves on a consumer after its byte ranges and its statuses — the URI reported after a
redirect (rule 6), the coding a transport must not negotiate for itself (rule 7) and what a cancelled
request does instead of blocking (rule 10). Five of its tests open the adapter's `DataSource`
without a player, because each is a claim about the *request* — the headers composed onto it, the URI
a response reported, a `close` racing a `read` — and one of the five starts a thread of its own,
which is what a loader thread is. The claims that are about playback are made through a real
`SuperPlayer`. Nothing in `superplayer-testkit` can express a **3xx** at all (`FaultScript`'s status
is in `400..599`), a `Content-Encoding`, or a body that does not return, so a transport that is the
origin is again the only route. The gzip half of rule 7 is `superplayer-abr`'s
`TransparentGzipEstimateTest`, and it **reads past the facade twice over**, recorded here rather than
left to be found: it drives core's adapter with no player at all, and it reaches core's `internal`
`HttpStack.factory` from a *later phase's* test source set, which the friendship
`KotlinFriendModules.kt` grants that module for filling a policy slot also happens to allow. It is
spent here because the claim has two ends and they are in two modules — the byte counts are core's
adapter's and the estimate is `OracleBandwidthMeter`'s — and no public API joins them: the estimate a
player exposes is a smoothed reading over a shaped link, which is a slower way of measuring something
else. What is asserted is the cost of a transparently-decompressing client as a number rather than as
a warning in a KDoc.

`HttpTransportConformance` is the exception #312 added, and the only one a *consumer* runs rather
than a maintainer: `superplayer-testkit` public API, five checks, one per obligation ADR-0016
leaves on a transport. It stands up an HTTP/1.1 origin on the loopback interface, because those
obligations are claims about what a client puts on a wire and every other fake in this repository
stands in for a layer above the client. `HttpTransportConformanceTest` is what makes it worth shipping — the section
below carries that argument.

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

`android/superplayer-core/src/test/kotlin/com/superplayer/core/` is the worked example.

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
network fetch a test with no network can ask for. `SuperPlayerTransferChainTest` is one of its two callers,
and it also pins the other half: a transport installed through the engine configurator takes the HTTP
stack's place, which is what keeps every test above working.

The second caller is `SuperPlayerHttpStackTest`, and it is there for the same reason one layer down.
Phase 10 lets a consumer supply the HTTP client at the bottom of that chain
(`SuperPlayer.Builder.setHttpStack`), and a stack is invisible to every test that substitutes a fake
data source *in its place*. So that class serves the synthetic streams from memory behind an
`HttpTransport` of its own, under `https:` URIs, and a real `SuperPlayer` plays HLS and DASH through
it. Two things it can state that no fake origin here can. A **byte range**: `FaultScript` cannot
express a 206 at all — `failWithHttpStatus` takes a status in `400..599` — and the fake origins serve
a `DataSpec`'s slice, which is a length-aware read rather than a partial-content *response*, so a
transport that is itself the origin is the only place both sides of a range are visible. And the
**resolution order** ADR-0016 rule 3 fixes: a transport set through the engine configurator still wins
over a stack a consumer set, because the first substitutes for the network itself and the second for
the client over a real one.

Two of its tests open the adapter's `DataSource` directly rather than through a player, which is a
documented exception to *Why assertions stop at the facade* below. Media3 asks for a byte range where
a DASH representation carries an index or an HLS segment an `EXT-X-BYTERANGE`, and the synthetic
streams carry neither; adding one would change the corpus every other module is recorded against, to
observe something a single `open` states exactly.

`ConsumersTransportEvidenceTest` is the third caller and is there for the *response* side of the same
exchange. ADR-0016 rule 8 says a status a transport reports becomes an
`InvalidResponseCodeException` carrying the status, the **headers** and the URI, and no fake origin
in this repository can express a response header at all — a transport that is itself the origin is
again the only place. Both classes share one `ServingTransport`, so what a test reads back is what
one implementation received.

`TransportContractTest` is the fourth caller and is the rest of that contract: rules 6, 7 and 10,
each written as the difference between a transport that keeps it and one that does not, because every
one of the three fails silently on a device and an assertion that only ever ran the correct transport
would pass over an adapter that had stopped asking. A **redirect** is the clearest case of why the
origin has to be the transport: `FaultScript.failWithHttpStatus` cannot express a 3xx, and the claim
is not about a getter but about what Media3 resolves a manifest's relative references against, so the
test reads it off the addresses the *second* and third requests were made to. **Cancellation** is the
one place a test here starts a thread, which is honest rather than exotic: a loader thread is a
thread, and `close` cancelling an in-flight request is a claim about two of them.

### The platform's HTTP engine, and the API level a test states

`HttpStack.httpEngine()` is the other stack core builds itself (#313), and **nothing under `check`
can play a byte over it.** `android.net.http.HttpEngine` is a platform class with no Robolectric
shadow, and its own `Builder.build()` looks for the Cronet implementation the *system* ships; inside
a JVM it finds none and raises `Expected Cronet version number …, actual version number null`. No
`DeviceStatement` changes that, and the only way to make it "play" here would be to write a stand-in
HTTP engine and then assert that our own stand-in works. So the gap is recorded rather than faked,
exactly as *What is not covered here* records the secure surface: **a real HTTP/3 session over the
platform's engine is #316's, on a device.**

What `HttpStackSelectionTest` can state offline is the part that fails silently, which is the
selection being *substituted* rather than the bytes being slow. Below the floor `build()` raises the
typed `HttpStackUnsupportedException`; above it, the platform's own refusal is the evidence that the
engine was genuinely asked for, because a stack quietly exchanged for `DefaultHttpDataSource` would
have produced a working player and said nothing at all.

That class is also **the repository's first user of `@Config(sdk = [...])`**, which
`android/superplayer-core/src/test/resources/robolectric.properties` has pointed at since it was written.
An API level is deliberately not a `DeviceStatement`: that type states what a device *can do* —
decoders, display, heap, protection — while a platform level is `Build.VERSION.SDK_INT`, which
Robolectric's own annotation already sets, and a second way to say it would be a second thing to keep
true. Each module's properties file pins the level for everything in it, so only a test that needs a
*different* one says anything, and only the refusing arm here does.

### The conformance test a consumer runs

`HttpTransportConformance` is the one thing in this repository written to be executed **outside** it
(ADR-0016 rule 14). A consumer writes fifty lines of `HttpTransport` over the client their app already
ships, calls `HttpTransportConformance(theirTransport).verifyAll()` from their own test source set, and
is told which obligation they broke. It is public API of `superplayer-testkit`, tracked in that
module's `api/` file like everything else there.

The half of it that faces that consumer is [`http-transport.md`](http-transport.md) (#315): the
worked adapter, the five obligations each with what it costs to get wrong, and the instructions for
running this suite. That document is what a **test of a transport** looks like from the outside, and
the pairing is deliberate — the obligations are on code this repository cannot see, so the prose
carries the reasons and the suite carries the verdict. `TransportDocumentTest` holds them together,
in `DoctorDocumentTest`'s shape: every check on `HttpTransportConformance` needs its own section in
that document, in the order `verifyAll` runs them, naming its ADR-0016 rule and carrying the
citations the check's own KDoc carries, so a sixth obligation fails the build rather than reaching
nobody. What it deliberately does not hold is the document's worked adapter, which **nothing here
compiles**: it is written over a client this repository takes no dependency on (ADR-0004 rule 2 and
its 2026-09-18 addendum), and the document says so in as many words rather than implying a
verification it does not have.

**Why it exists rather than a paragraph of KDoc.** Each of the five obligations fails *silently*: a
client that gzips on its own behalf produces a bandwidth estimate nobody can explain, one that drops a
byte range produces what reads as a corrupt stream, one that raises on a 403 produces sessions that end
unclassified, one that reports the address it asked for rather than the one it was redirected to sends a
player to the old edge for every segment, and one whose close abandons nothing holds a loader thread
across a seek and a player past release. This is the bet *Golden traces* and `FallbackRungCoverageTest`
already make — a rule nothing executes is a rule that goes stale — with one thing added: the code the
rules bind is code this repository cannot see, so the test is the only form in which they can travel to
the person who has to satisfy them.

**Two decisions are worth knowing before changing it.**

*The consumer supplies the transport and nothing else.* The origin is testkit's, started per check and
shut down after it: a hand-written HTTP/1.1 server on `127.0.0.1` at an ephemeral port, which honours a
`Range` with a 206 and a `Content-Range`, redirects one address to another, offers gzip to a client that
asks for it, refuses one address with a 403 and a `WWW-Authenticate`, and stalls a fourth after one byte.
The alternative — "point it at an origin you control" — asks every adopter to stand up a server that
misbehaves in five specific ways before they can check anything, and then measures that server as much
as their client. This is the **one socket in the repository**, and it is here because none of the five
readings exist without both ends of a real exchange: the checks read what the transport put on the wire
as well as what it handed back.

*It names no test framework.* `superplayer-testkit` declares no JUnit of its own — `PlaybackHarness`
compiles against `org.junit.rules.ExternalResource` only because JUnit arrives transitively through
Media3's Robolectric test utilities — and a second entry point leaning on that accident would make it
load-bearing. A broken obligation is an `HttpTransportConformanceException`, which is an
`AssertionError`: every framework renders one as a failed assertion, so a consumer on JUnit 5, Kotest or
an instrumentation runner runs the same checks as one on JUnit 4. The message is the deliverable and its
shape is fixed — the rule, then **what this transport did**, then **what the rule requires** — because
the person reading it has no copy of this repository.

**What scores it.** `HttpTransportConformanceTest`, from both sides. A check nothing has ever failed is
a check that does not work, so there is a deliberately wrong transport **per obligation**
(`PlatformClientTransport`, written over `HttpURLConnection` so that each defect is a *configuration* of
a real client rather than a stub's invention) and each is driven against the check meant to catch it —
per obligation and not in aggregate, since five checks with four proofs is the way this ticket looks
finished without being it. And the suite is run against `HttpStack.default()` through
`DefaultStackTransport`, which must pass every check: a contract the stack we ship cannot keep is a
contract to change rather than one to hold an adopter to. The control between the two is
`PlatformClientTransport` with no defect at all.

**What it cannot show.** Whether a consumer runs it, which ADR-0016's *Consequences* already records as
the mitigation's limit. And anything about TLS, proxies, HTTP/2 or HTTP/3: the origin speaks HTTP/1.1 in
plaintext, so a client that refuses plaintext or pins a host is one to relax for this test. Adding a
sixth check means adding a wrong transport for it in the same change, and that is a *register* rather
than an instruction: `everyCheckHasAWrongTransportAndEveryWrongTransportHasACheck` reads the checks
off the class and pairs them with `PlatformClientTransport.Defect`, in `FallbackRungCoverageTest`'s
shape and for its reason, so a check with nothing to fail it fails the build instead.

## A realtime transport

Phase 13's path is the one part of the library that opens no `DataSource`, so almost none of the
apparatus above reaches it. A realtime item is dispatched on its URI's **scheme** and loads through a
`MediaSource` of `superplayer-realtime`'s own, which writes a `FrameSource`'s frames into Media3's
sample queues (ADR-0018 rules 1 and 12). There is no transfer to shape, no fault to inject and no
request to count, which is why `RealtimePlaybackTest` does **not** go through `PlaybackHarness`: what
the harness contributes is a fake in the chain's transport slot, and there is no chain. What it uses
instead is the seam *The one seam that is not public* already sanctions — `setEngineConfigurator`,
reached because that module is core's tenth Kotlin friend — with Media3's own `FakeClock` and
`FakeRenderer` under it, and every assertion still on the public `Player` API.

What stands in for a transport is `ScriptedFrameSource`, a fake that delivers a fixed script of
synthetic frames and then stays open, as a live publisher that has not yet sent more does. Its bytes
are H.264 of the right *shape* and carry no picture data — nothing under `check` decodes them, so
what they prove is the path rather than the picture — and the parameter-set fixtures beside it, in
`ObservedBytes`, are #340's observed dump with every synthesized byte labelled as one. Three things
about the path are worth knowing before writing a test against it. A realtime stream **never reaches
`STATE_ENDED`**, because ExoPlayer declares a period final from the timeline and a dynamic period of
unknown duration never is, so a session ends when the player is released. A transport's timestamps
are on its own epoch, so a fake that starts at zero hides a missing subtraction on the other side.
And the bounds on a track that starts late or stops are **media time against the leading track and
never a clock**, which is what makes them assertable at all.

### The conformance test a transport implementer runs

`FrameSourceConformance` is the second thing in this repository written to be executed **outside**
it, and the argument for it is `HttpTransportConformance`'s one degree stronger. A transport
implements the seam over MoQ or over WebRTC; neither QUIC nor WebRTC runs under `check`, and
Robolectric cannot load an Android `.so` on the JVM. So for `superplayer-moq` and `superplayer-whep`
this suite is not one check among several — it is the only one there is, and it is what lets a third
party write a third transport without reading our internals.

It is public API of `superplayer-testkit`, tracked in that module's `api/` file, and it is testkit's
because `FrameSource` is **core's**: a phase 2 module may name nothing later than core, so the seam
had to move there for its suite to reach it (ADR-0018 rule 2's #356 addendum). Eleven checks, one per
obligation, each naming the rule, what the source did and what the rule requires; a broken obligation
is a `FrameSourceConformanceException`, an `AssertionError`, and no test framework is named, for the
reason the HTTP suite's does not name one either.

**It needs no socket, and the one loopback carve-out is not widened.** That is the difference between
the two suites and it is a difference of subject: an HTTP client's obligations are claims about what
goes on a wire, so that suite stands up an origin and reads both ends of the exchange, while a
`FrameSource`'s obligations are claims about what it delivers **to a sink**. So this one supplies the
sink — a recorder that subscribes, keeps what arrives in order, and cancels — and nothing else. What
the consumer supplies is the transport *and the stream*, since no fake publisher here could stand in
for their relay.

**What it cannot cover**, said in the suite's own KDoc and repeated here because it is the half a
reader should not have to infer. Obligation 5, the precision a transport states, is unenforceable by
construction — nothing can tell a transport that truncated to milliseconds from a publisher that
emitted them. Obligation 6 is reached only as far as the framing walk goes, so a container this suite
has never seen may pass it. A configuration record that parses and belongs to a *different* stream is
invisible. Obligation 1 is checked by widening the window and watching for two deliveries in flight
at once, so a transport with two genuinely concurrent deliverers is caught while one that delivers
from several threads with a happens-before passes — which is what the obligation permits. And
obligation 7's second half, the sample framing, is checked against the length field size the track's
*own* record declares: Annex-B under a record is caught, and a stream where both readings are
structurally valid is not. Two more are named rather than bounded: a track **declared and never
delivered**, and the *shared epoch* across tracks that obligation 4 asks for wherever a publisher has
one. SuperPlayer bounds both at playback — in media time against the leading track — and a
conformance suite bounding them would be deciding how long a publisher may take to start, which is a
number about the stream rather than about the transport. The consequence worth stating: a source that
declares its tracks and then delivers no frames at all passes every check vacuously, so the suite is
pointed at a stream that plays.

**What scores it.** `FrameSourceConformanceTest`, from both sides, in `HttpTransportConformanceTest`'s
shape: a deliberately wrong publisher **per obligation** (`PublishingFrameSource.Defect`), each driven
against the check meant to catch it, with the correct publisher run through the whole suite in both of
obligation 7's branches as the control. There is a third control, and it is there because two would
have been a vacuous pass: a publisher that finishes delivering inside `subscribe` has nothing left to
stop by the time anything cancels it, so obligation 9's positive side needs a publisher that delivers
**asynchronously** and joins its thread in `cancel`. Adding a twelfth check means adding a wrong
publisher for it in the same change, which
`everyCheckHasAWrongPublisherAndEveryWrongPublisherHasACheck` enforces by reading the checks off the
class. That register departs from the HTTP one in a single way, argued where it is made: a check maps
to a *list* of defects rather than to one, because obligation 8's two refusals — an array handed over
twice, and one written into after it was — cannot both fire on one publisher, and a one-to-one pairing
would leave a shipped refusal forced by nothing. The other half is `superplayer-realtime`'s
`ScriptedFrameSourceConformanceTest`: the reference fake this phase's own tests play through, run
through `verifyAll` in both branches, so the implementation an adopter reads is held to the contract it
defines. That test also holds the suite's list of codec families to `RealtimeFormats`' table, which is
written down twice because the table is `internal` to a phase 13 module and the suite is phase 2's.

### A realtime transport on iOS

On Apple platforms a realtime stream has a player of its own, `SuperPlayerRealtime`'s `RealtimePlayer`
(ADR-0020 rule 11, #431), and **the simulator decodes what it is pushed**: the player wraps each frame
in a `CMSampleBuffer` and enqueues it to an `AVSampleBufferDisplayLayer` under an
`AVSampleBufferRenderSynchronizer`, and those decode it. So where Android's fake carries H.264 of the
right shape and no picture, the iOS fake, `ScriptedFrameSource` in `SuperPlayerRealtimeTests`, carries
`SyntheticVideoHlsStream`'s hand-written bitstream without its container — an `avc1` track with that
stream's `avcC` and four-byte length-prefixed samples, CoreMedia's own framing — at thirty frames a
second in real time, on an epoch two hours in so a missing subtraction would hold every frame two hours
ahead of the clock. `RealtimePlayerTests` reads "rendering" three ways: the synchronizer's clock
advancing, the layer's renderer `rendering` with no error, and **no decode failure** — the renderer's
own `didFailToDecodeNotification` — on a stream whose control, the same framing with noise for slices,
fails frame by frame. That control is what shows the layer decodes rather than ignores, and it is why
the synthetic stream is used rather than #340's bytes, which have no slice in them. The simulator
decodes with no window but composites nothing offscreen, so `copyDisplayedPixelBuffer()` answers nil
with or without one and the picture itself is not read. **A synchronizer with only a video renderer
advances in an `xctest` host**, where #405 found a video-only `AVPlayerItem` never does.

#340's observed bytes are committed once, under `ios/Tests/Fixtures/realtime/observed-bytes.tsv`, and
**both platforms read that file**: `superplayer-realtime`'s `ObservedBytes` and the Swift tests' twin.
Nothing generates them, so unlike the synthetic corpus above there is no update task: the file is edited
by hand, each row says whether it was observed or synthesized, and the Kotlin build declares it an input
of every test task so an edit re-runs what reads it. On iOS they are the format table's fixtures — an
observed `avcC` passes into a format description's atom byte for byte — and the mirrored codec
configuration's (#432, `CodecConfigurationRecordsTests`): the `avcC` dump as the truncation case, the
`avc3` keyframe as in-band parameter sets that stop before their PPS, the `avc1` keyframe dump as a
length field running past its frame. Where CoreMedia holds the answer — the parameter sets and NAL
length width a description carries — it is read back from CoreMedia, so "the record passes through"
and "in band makes the description the record would" are CoreMedia's readings and not only ours. The
`hvcC` is synthesized and labelled so, and there is **no in-band HEVC description** anywhere here:
CoreMedia parses an SPS to make one and no real HEVC SPS exists in this repository.

**More than one track** (#432) plays from the same fake, which now scripts tracks — the video in each
of ADR-0018 rule 4's shapes, `SyntheticHlsStream`'s silent AAC beside it, each track on the shared
epoch or one of its own — interleaved as one publisher would send them. `RealtimeTracksTests` asserts
A/V sync over **the presentation times each renderer was handed**, read in the engine: both render
against one synchronizer clock, so a stamp is when a sample renders, and the simulator gives no
per-sample reading of a render (the video composites nothing offscreen, an audio renderer reports no
sample at all). A publisher's 40 ms skew arrives as exactly 40 ms; tracks an hour apart in either
direction are placed within a frame of each other. The late and stalled bounds are media time against
the leading track, so those tests deliver **unpaced** and wait for the engine to have taken every frame,
never for a clock; each has a control — inside the late bound, and every track going quiet together.

**An audio renderer goes quiet rather than failing**, which #432 found by enqueuing the first audio:
with the AudioSpecificConfig as a bare magic cookie — what #431 described AAC with —
`AVSampleBufferAudioRenderer` reports `rendering` with no error while its decoder logs
"AudioCodecInitialize failed", #398's silent decoder through a different door. The cookie is now the
`ES_Descriptor` an `esds` carries, and `RealtimeAudioDecodingTests` holds it with an AudioToolbox
converter given the description's cookie as the renderer's decoder is, the bare cookie as the control.
`AVAudioConverter` is not the instrument: it decodes both.

**What a `RealtimePlayer` shares with a `SuperPlayer`** (#461) is tested in two classes over the same
fake. `RealtimeTelemetryTests` counts what `QoeCollector` emits for a realtime session at a recording
sink: the start, a first frame from a declared intent, the ten-second samples with both bitrate fields
`null`, and the video renderer's dropped frames, which the simulator reports. A **rebuffer** is forced
by `ScriptedFrameSource.Stall`, a publisher going quiet for longer than its half-second preroll. That
starves the renderers under a running clock, and the frames it then owes end the rebuffer. The healthy
session is the control, with no rebuffer at all. A refusal before the first frame and a failure after
it are told apart, and a consumer's own collector is shown to hear the edges and be attached to
nothing. `RealtimeSharedPiecesTests` posts the real interruption and route-change notifications at a
realtime player, as `LifecyclePlaybackTests` does at a `SuperPlayer`. It publishes a session to a
recorder behind `NowPlayingPlatform`, which shows a live dictionary and no seek command, and swaps a
`SuperPlayer` in behind it. It reads Picture in Picture as far as the simulator lets it: the content
source over the display layer, and the playback delegate's answers for a timeline with no ends. That a
window then appears, and that the dropped-frame count means what a screen shows, are a device's. One
gap is shared with the frame output's delegate: the controller is the app's, so nothing here calls
its `invalidatePlaybackState()`, and a pause made elsewhere — a remote command, an interruption —
reaches the window's button only when AVKit next asks.

**On an iPhone** (iPhone 17 Pro Max, iOS 27.0, #461), Picture in Picture was run from the demo's MoQ
screen. The first part is `MoqLiveSessionSmokeTests.testTheDemoPlaysOnInPictureInPicture`, an XCUITest.
It taps the screen's button and reads that the controller's delegate heard the start. It then sends the
app home for eight seconds and reads the clock, which had advanced 8.0 s across the time away, with the
layer still rendering and Picture in Picture still active. A person then watched the window. The stream
played, and its pause and play buttons paused and resumed it through the playback delegate. The window
showed the LIVE badge and a bar with no thumb, as an unbounded time range asks. **It also drew both
skip buttons.** The app's `requiresLinearPlayback` greys the forward one, and the back one stays,
whatever start the range reports (a start at the playhead was tried and drew the same). No API hides
it, so the delegate completes a skip having moved nothing. Two things the run taught the demo: a
controller asked to start the moment it is made is ignored, because the system makes a start
possible a moment later, so the demo makes it with the view; and the window needs the app's
`audio` background mode. `demo/bbb.hang` stopped publishing on the relay mid-run, and
`SP_MOQ_BROADCAST=moq://cdn.moq.dev/demo/boy/gb-run.hang` is what the test passed against.
**Opus**, whose description is made and whose cookie (an OpusHead) no test decodes, is still not
covered. ADR-0020 rule 11's other half, a realtime source handed to a `SuperPlayer` refused typed, is
#419's `UnplayableSourceSchemeError` (the rule's #419 addendum). What a device alone shows is a real
transport's frames on a screen, which is #438's.

#### The conformance suite on iOS

`FrameSourceConformance` is `SuperPlayerTestkit`'s public API too (#433), and it is the Kotlin suite
ported check for check — the section above is its argument on both platforms, and what follows is
only where this one differs. **Eleven checks, the same eleven**: the seam's documentation now numbers
ten obligations where the Kotlin suite's counted nine, and the tenth, the coded size, is unenforceable
for obligation 5's reason, so it adds a line to what the suite cannot check and no check. **A failure
is a thrown `FrameSourceConformanceError`**, a plain `Error` — `SuperPlayerTestkit` links no XCTest,
so XCTest, Swift Testing and a consumer's own runner all report it as the failure — and each message
keeps the Kotlin shape: the rule, what the source did, what the rule requires. **The checks are a
`CaseIterable` enumeration** behind `verify(_:)`'s exhaustive `switch`, because Swift has no
reflection over methods: `FrameSourceConformanceTests`' register compares itself to `allCases`, as
`FaultSweepTests` holds `FaultScript.Kind`. **Obligation 8 is read off memory rather than identity**:
`Data` is a value, so a pooled array refilled after it was handed over cannot happen through the
type, and what is left is a `Data` made with `bytesNoCopy` over memory the transport goes on writing.
Two frames over one address whose bytes differed when each arrived is a buffer rewritten between
deliveries — a value could not have changed, so one `Data` sent twice is refused by nothing — and a
payload whose bytes differ from the copy taken on arrival is memory written afterwards. Both are read
before the source is cancelled, since a transport may free its buffers once it has been. And the
Kotlin suite's rule for a throw out of `subscribe` has nothing to check here: `subscribe` does not
throw, so `onError` is a failure's only route by the type.

What scores it is the Kotlin pair. `FrameSourceConformanceTests` drives `PublishingFrameSource`, a
wrong publisher per obligation — its two obligation 8 defects by `bytesNoCopy`, one pooled buffer and
memory of its own written after delivery — with the correct one through `verifyAll` in both of
obligation 7's branches and asynchronously, joining its thread in `cancel`. And
`ScriptedFrameSourceConformanceTests`, in `SuperPlayerRealtimeTests`, runs the fake the realtime tests
play — real H.264 and AAC, paced and on its own thread — through `verifyAll` as `avc1`, as `avc3` and
as `avc1` at two-byte lengths, and holds the suite's codec families to `RealtimeFormats.mappedTokens`.
No socket is opened by any of it. What a real transport delivers is a device's and #438's.

#### The MoQ bindings on iOS

`SuperPlayerMoQ` (#434) is tested the Kotlin module's way, in `ios/MoQ/`'s own test target, which
`ios/check` runs in a step of its own because the package is not the root manifest's
(`ios/MoQ/Package.swift` says why). `MoqFrameSourceConformanceTests` runs testkit's suite over
`MoqFrameSource` in both of ADR-0018 rule 4's branches, behind `ScriptedMoqRelay` — the Kotlin fake
ported: endless tracks by default, paced on a condition `close()` wakes, each track on an epoch of its
own — with a control the suite refuses (an `avc1` rendition whose catalog declared no record), and
`MoqFrameSourceTests`, `MoqCatalogTracksTests` and `MoqSessionStatisticsTests` are the Kotlin classes
case for case. **That proves the bridge and nothing about MoQ**: no QUIC session is opened and no line
of `UniffiMoqRelay` runs. Two things differ from the Kotlin half, each in the direction of more
evidence rather than less.

- **The FFI test runs everywhere `ios/check` does.** `MoqFfiLinkageTests` walks every checksum the
  generated Swift holds against the library and allocates an origin in Rust, as `MoqFfiLinkageTest`
  does — but where the JVM can only load a *host* build and skips on every other host, the simulator
  links the XCFramework's own arm64 simulator slice, the library an app on the simulator links, so CI's
  macOS job runs it too. What no check reaches is the device slice, which only a device runs; the demo
  is built against it by hand (`xcodebuild … -destination 'generic/platform=iOS'`), never by `ios/check`.
- **Nothing waits a while and then asserts an absence.** Where the Kotlin tests pause and see nothing
  arrive, these read the threads: `cancel()` joins the pumps and the poller, so a test asserts they
  have *finished* the moment it returns, and a poller giving up after a failed poll is its thread
  returning, waited for as an event. Pacing is a count against a count — at an hour's interval, one
  poll while sixty-four frames arrive — never against a clock (ADR-0021 rule 4).
- **`cancel()` does not join the connect thread**, which the Kotlin `cancel` does: here it is called on
  the main actor, and a handshake or the `unroutable` retry cannot be interrupted, so it would freeze
  the screen being left. A delivery barrier stops that thread's callbacks instead, and
  `testCancelDoesNotWaitForAConnectionStillBeingMade` holds a scripted connection open across the
  cancel to show both halves.

The fixtures follow `DeclaredCatalogs`' provenance rules, and the observed `avcC` is read from
`ios/Tests/Fixtures/realtime/observed-bytes.tsv` rather than restated, since a package of its own can
reach that file by path where it could not reach another target's sources. A real broadcast — a relay,
the `unroutable` retry, the session-URL namespace #367 settled — is the device run's, below.

#### The first real MoQ session on an iPhone

The iOS twin of *The first real MoQ session* (below) and the same carve-out from the no-network
rule, admitted by name: `SuperPlayerDemoUITests`' `MoqLiveSessionSmokeTests`, an **XCUITest** run by
`devicelab/lab run moq-smoke --platform ios` (`devicelab/README.md`, *The moq-smoke scenario*) and by
nothing under `ios/check`, which only compiles it. A UI test because it holds both halves: its runner
is a process of its own that opens `MoqFrameSource` with no player, as the Kotlin instrumented test
does, and it can launch the demo and read what `MoqScreen` shows, which no other process on a phone
can. The same three outcomes are encoded the same way — pass, skip for a relay that announces
nothing, fail — and every observation is printed before any assertion runs.

**What the run found** (#438), on an iPhone 17 Pro Max, iOS 27.0, wired to the Mac and on house
Wi-Fi, against `cdn.moq.dev`:

- **The device slice links and QUIC works from it.** Against the old default,
  `cdn.moq.pro/anon/catshark.hang`, the session connected and was refused `unroutable` — not a
  defect: the relay no longer announced that name, and listed two others under `anon`.
- **Both of #367's defects reproduce on iOS exactly as on Android**, which is what makes the fixes
  ported into `UniffiMoqRelay` evidence rather than assumption. Connecting to the bare authority
  lists nothing and every request is refused, in all three arms; connecting with the namespace in
  the session URL lists the broadcasts, and then the **immediate** request is refused `unroutable`
  while the **settling** and **listening** arms both read the catalog — waiting is what matters, not
  listening, as #367's third arm found.
- **MoQ's own demonstration broadcast plays**, `demo/bbb.hang`, now the iOS demo's default: two
  tracks, `avc3.64001f` 1280×720 with its configuration **in band** and `mp4a.40.2` with a two-byte
  AudioSpecificConfig **record** (`12 10`) — the first live `avc3` and the first live AAC record either
  platform has seen, so ADR-0018 rule 4's two branches were exercised by one real publisher at once.
  Thirty frames arrived inside the window, and none after `cancel()` returned. The session's round
  trip read 5–8 ms, which says the relay has an edge near the house and nothing about the library.
- **The demo played it** on a `RealtimePlayer`: two readings of `MoqScreen`, both
  rendering, the clock risen from about 0.2 s to 5.2 s and the session's received bytes from 8 kB to
  about 1 MB.

**What it found that is not MoQ's.** The runner's Thread Performance Checker reports a priority
inversion in `MoqFrameSource.cancel()`: the caller, at user-initiated quality of service, joins
pump threads started at the default one. And the demo's process reports `AVAudioSession` activated
synchronously on the main thread. Both are recorded here and changed nothing in this run. The first
is gone since #493, which starts every thread a subscription makes at the subscriber's quality of
service and never below user-initiated (`MoqFrameSource.threadQuality(subscribedAt:)`), so the join
waits on nothing below its caller: the checker's warning is line 7 of
`devicelab/test/fixtures/ios/xcodebuild-test-moq-smoke.log`, #438's run, and the same scenario on
the same iPhone (iOS 27.0, 2026-10-13) logged no warning at all, both tests passing, thirty frames
delivered and none after `cancel()`. The second
was the library's own call and is gone since #494, which performs the session's calls on a serial
queue of the system platform's: `devicelab/lab run smoke --platform ios` on the same iPhone (iOS
27.0, 2026-10-13) logged `[AVAudioSession Hang Risk] AVAudioSession_iOS.mm:978` once on the
playback screen with the old binding, as the control, and not at all with the new one, both runs
playing from 2 s to 16 s.

**What it cannot show.** It takes no latency figure and no rate, for the Kotlin test's reason. It
cannot list the source's threads after a cancel, as the Kotlin test does: Swift enumerates no other
module's threads and `MoqFrameSource.joinedThreadsHaveFinished` is `internal`, so that half stays
`MoqFrameSourceTests`' under `ios/check`. "Rendering" is the display layer accepting the stream with
the clock running; whether each frame was shown on time is not read. And it is one relay on one
evening — dated, relay-specific readings go on #438, not here.

### The MoQ bindings

`superplayer-moq` links a native library, and everything unusual about testing it follows from where
that library comes from: it is **built on this machine** and committed to `third-party/moq/m2`,
rather than resolved from Maven (issue #364, and `third-party/moq/README.md` is the recipe and the
licence evidence). So the module is not published at all — `android/settings.gradle.kts`'s
`unpublishedModules` list, ADR-0017 rule 1 — and what a test here can show is bounded by that.

`MoqFfiLinkageTest` calls across the FFI boundary for real, and the library it loads is the **host**
build, `darwin-aarch64/libmoq_ffi.dylib`, which reaches the test classpath from
`dev.moq:moq-ffi-jvm` at JNA's own resource layout. That is not a convenience: a Robolectric JVM
cannot load an Android `.so` at all, so without a host build the criterion "a test calls a binding
function" would have had no honest way to be met, and the alternative would have been a test that
asserts the Kotlin compiled while calling nothing. `MoqBindingsResolutionTest` is the other half and
is about *which* artifact was resolved, because Maven Central publishes the same coordinate built
with the codec features on and a version range would reach it.

`MoqCatalogTracksTest` is the exception that is worth stating, because it is the shape the rest of
this module's tests should take wherever they can. The catalog mapping is pure — a `MoqCatalog` is a
plain Kotlin record on this side of the FFI, constructed and read without the native library being
touched — so that test needs no `assumeTrue` and runs on every host, CI included. It is also where
Phase 14's "both codec-description shapes are covered by a test" criterion is discharged, and
`DeclaredCatalogs` states per fixture what #340 observed and what was written for the test:
no `MoqCatalog` in this repository was ever received from a broadcast, because that spike's caveat 4
records the catalog path as source-only.

Since #366 the frame pump is tested the same way, and the shape is worth stating because it is what
the rest of this phase rests on. `MoqFrameSource` is written against an **internal seam**,
`MoqRelay` — connect, read a catalog, subscribe to a track, pull frames — of which `UniffiMoqRelay`
is the one implementation that touches the bindings. Every check runs against `ScriptedMoqRelay`, a
fake of that seam, so `MoqFrameSourceConformanceTest`, `MoqFrameSourceTest` and `MoqPayNothingTest`
load **no native library** and run on every host, CI included. The suite they run is the shipped one:
`FrameSourceConformance`, over both of ADR-0018 rule 4's branches, which is #366's central assertion
and the reason that suite was written to be executed outside this repository at all.

**Say what that proves, because the sentence is easy to shorten wrongly.** It proves that *this
module's bridge* honours all nine of `FrameSource`'s obligations — the declaration before the first
frame, the ordering, the keyframe each track starts on, the index a frame carries, the cancellation
that joins its threads, and the failure that reaches `onError` rather than a thread nobody watches.
It proves **nothing about MoQ's bindings, about QUIC, or about a relay**, and a green run here is not
evidence that a broadcast plays. #367 is the ticket that answers that, and it needs a device.

Since #368 the session's **statistics** are tested through that same seam, and one thing about it is
worth writing down because it looks at first like a breach of *Determinism*.
`MoqSessionStatisticsTest` asserts a **cadence**, which is a claim about a span — so the span is the
measurement rather than a guess at what a machine needs, which is the second of the two shapes that
section permits. The polls are *awaited*, on an event `ScriptedMoqRelay.awaitPolls` exposes, and what
is asserted afterwards is how long getting them took. There is deliberately **no upper bound on the
poll count**: a count is exactly what a loaded host makes unreliable, while a lower bound on elapsed
time cannot be failed by a slow machine, because a clock does not run backwards. The two spans that
remain are `MoqFrameSourceTest.QUIET_AFTER_CANCEL_MS`'s idiom — nothing further happening is the
absence of an event, so a window is the only form the assertion has.

Its scripted statistics are a **function of the poll count**, so a counter that moves is assertable
as movement rather than as one constant re-read, and its two controls are the ones the ticket's
honesty rests on: a session missing one number (the receive-rate estimate an older relay does not
carry) and a session whose statistics call fails. What *no* test here can show is the mapping itself:
`UniffiBroadcastSession.statistics()` is the nine-field conversion from
`uniffi.moq.MoqConnectionStats`, including the `bytesLost` → `transportBytesLost` rename, and like
every other line of `UniffiMoqRelay` it runs nowhere under `check`. It is verified by review, and
#367 is where a real session first answers.

**What neither can show.**

- **Two builds of one crate.** The Android `.so` and the host `.dylib` come from the same checkout,
  the same commit and the same features, but they are separate compilations. What the JVM test
  proves about the host build is a strong indication about the Android one and not a proof of it.
  Nothing under `check` loads the Android library, and nothing here can.
- **One ABI.** Only `arm64-v8a` is built. `armeabi-v7a` and `x86_64` are unbuilt and untested,
  because the host is Apple Silicon and its only Android system image is arm64 — so an Intel-host
  emulator cannot run this module, and neither `devicelab` nor the demo has been pointed at it.
- **No relay, and no QUIC — under `check`.** The smoke test allocates a local origin and frees it.
  Nothing connects, which is what keeps the no-network rule above intact — and it is why
  `FrameSourceConformance` is the suite that matters for a transport, as the section above says.
  Since #367 there is **one** run that does connect, and it is not in `check`: see *The first real
  MoQ session* below.
- **Not reproducible by anyone else.** No check builds the artifact; a clean machine follows
  `third-party/moq/README.md` by hand, toolchain pin and all.

### The first real MoQ session

This repository has exactly one **instrumented** test, `superplayer-moq`'s
`MoqLiveSessionSmokeTest`, and it is the second carve-out from the no-network rule above — the first
being `HttpTransportConformance`'s loopback socket. It is a wider one and is admitted by name rather
than by extending the first: that one binds a socket on `127.0.0.1` and reaches nothing off the
host, while this one opens a QUIC session to a relay on the public internet.

**It is not in `check` and cannot be.** `check` is the complete definition of the library's checks
and every other claim here is reachable on a host JVM; three of Phase 14's are reachable nowhere
else, and they fail together in ways a demo app cannot tell apart — whether the `arm64-v8a` `.so`
loads on an Android runtime, whether a QUIC handshake completes from this build, and whether
`UniffiMoqRelay`'s split of a `moq://` URI into a connect URL and a broadcast name is right. That
last one had no citation and no test: no public document states the split, and the file said so.

`devicelab/moq-smoke` is how it runs, and it is deliberately **not** a `lab run` scenario. A
scenario drives the demo app — an activity, a `MediaSession` position, a Perfetto trace — and this
run has none of those. It borrows devicelab's device half, which boots or adopts a device, and
nothing else. `--broadcast moq://host/name` points it somewhere; the report lands under
`devicelab/out/moq-smoke-*/`.

**What it asserts, and what it merely records.** The artifact is the observation: every reading is
written to logcat and scraped into the report *before* any assertion runs, so a failing run is still
a finding. Three outcomes are encoded differently on purpose, because conflating them is what makes
a device harness stop being believed:

- a broadcast that declares tracks and delivers frames **passes**;
- a relay that answers and announces **nothing** is a `assumeTrue` **skip**, not a failure — there is
  no broadcast to subscribe to, nothing in this repository could produce one, and a run that goes red
  whenever a public relay is quiet is a run whose red gets ignored;
- anything else **fails**, with the cause chain and the relay's own announce listing in the message.

**What it cannot show.** It plays nothing — there is no player, renderer or decoder in it, which is
ADR-0018 rule 1 at its narrowest; whether the frames decode is #353's, in the demo. And it takes no
latency figure: an emulator's user-mode NAT and the host's scheduling sit between the relay and this
code, so a number taken here would describe the host. That is this document's rule about a stand-in
that cannot support a measurement, applied before the number is taken rather than after it is
published.

**What the runs found**, on an API 36 `arm64-v8a` emulator. The library loads on an Android runtime,
a QUIC session reaches a public relay, and a broadcast plays frames into the seam — and getting
there corrected **two** defects in `UniffiMoqRelay` that nothing under `check` could have seen,
because both produce a session that connects and then refuses every broadcast as `unroutable`:

- **The namespace was on the wrong side of the address split.** `moq://host/ns/name` connected to
  `https://host` and requested `ns/name`; the relay wants `https://host/ns` and `name`. The
  namespace belongs to the session.
- **The broadcast was requested at a moment the relay could not yet answer.** A freshly connected
  session is refused `unroutable` for a name the relay's own announce stream lists; asked again a
  moment later, on the same session, it resolves. The request is now retried on that one refusal,
  bounded, so a ready relay pays nothing.

**Two lessons about the diagnostic, worth more than either fix**, because the harness made the same
class of error twice and caught itself only the second time.

The first run concluded the address mapping was *correct* because the handshake completed. That was
wrong: a MoQ session connects to a bare authority quite happily and it is routing that fails
afterwards, so "connected" is no evidence about the split at all.

The second was worse, because it was a fix rather than a reading. The retry above was first written
as *"a relay routes only to a session subscribed to its announcements"*, and an announce stream was
opened and held for the session's life to satisfy it. The comparison behind that claim ran two arms
— silent-and-immediate against listening-and-waiting — so "listening" and "waiting" predicted the
same result and the wrong one was written down as mechanism. A third arm settles it: **waiting
without listening succeeds exactly as listening does.** What the relay is doing in that window is
still unknown, and the code now says so rather than inventing a reason.

The rule both teach is the same one: **vary one thing per arm, and prefer a control you expect to
fail.** A harness that reports only whether its one configured attempt worked will confirm a bug
rather than find it.

Against `cdn.moq.pro/anon`, once both were fixed: one video track, `hev1.1.6.L180.80`, its
configuration **in band** with no description — thirty frames and one keyframe in a ten-second
window, a 110 ms round trip out of `MoqSessionStatistics`, and a cancellation after which no thread
survived and no further frame arrived. That is the first time ADR-0018 rule 4's in-band branch has
been exercised by a real publisher rather than by #340's recorded bytes, and the first HEVC of any
kind: **#340's caveat 2 is narrowed rather than closed** — `hev1` is now observed, while `hvc1`'s
`hvcC` record and `av1C` remain source-derived and unobserved.

Dated, relay-specific observations belong on #367 rather than here, because what a relay carries is
true on a day and this document is not.

**There is a second device-only artifact in that APK, and it is not a test.** `MoqPlaybackActivity`
plays a broadcast onto a `SurfaceView` so a person can look at it, started with `am start` rather
than by any runner; it asserts nothing and is in `check` no more than the smoke is. What it is for
is the **shorter path**: it reaches the module by project dependency, so it plays a broadcast
against the working tree with nothing published and no second Gradle build involved. When it and
the demo's screen disagree, the difference is the publishing step.

### A MoQ broadcast in the demo

Phase 14's exit criterion is `PRD.md`'s own words — *a broadcast plays in the demo on a device* —
and since #353 that is `MoqScreen`, reached with
`--es com.superplayer.demo.extra.SCREEN MOQ` and pointed at another name with
`--es com.superplayer.demo.extra.MOQ_BROADCAST`. It is a real `SuperPlayer` on a stock
`PlayerView`, and the only realtime-aware line in it is the builder call naming the scheme.

What made it reachable is a publication state rather than a decision about shipping:
`superplayer-moq` is `locallyPublishedModules` (`ModulePublication.LOCAL_ONLY`), so
`publishToMavenLocal` produces an artifact the demo's separate build can resolve on this machine,
while `docs/compatibility.md` still reads **Not published** and no API surface is tracked. #369 is
untouched.

**What this cannot show**, in the idiom every other stand-in here is held to:

- **Nothing is asserted.** There is no runner, no harness and no fake — the relay is somebody
  else's and the broadcast is whatever its visitors left running. Evidence is an *advancing
  position across two logged state transitions*, per `CLAUDE.md`'s warning that a screenshot cannot
  tell a rendered frame from a stalled one, and that evidence is a record of one run rather than a
  check anything re-runs.
- **A failing run proves little.** MoQ answers `unroutable` for a name that does not exist, a
  session URL that is wrong and a request made too soon alike, so a refusal here is not a defect
  until it is told apart from the other three — which is what `MoqRelayDiscovery` in the
  instrumented APK is for.
- **One ABI.** `libmoq_ffi.so` is `arm64-v8a` alone, so the APK installs on an x86_64 emulator and
  fails to load the library at runtime. An Intel host cannot run this at all (#369).
- **No latency figure is taken from it.** `PRD.md` asks Phase 15 for a committed number and Phase
  14 for none, and an emulator's clock against a public relay over a domestic link would produce a
  figure describing the host and the weather. `MoqSessionStatistics`' round trip is a measurement;
  glass-to-glass latency is not one this arrangement can make.

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


### One corpus on both platforms

The Swift products play the same streams, and ADR-0021 rule 6 makes that **one corpus** rather than
two generators that agree today. `SuperPlayerTestMedia`'s `SyntheticHlsStream` is a port of the
Kotlin one, and the two are held to one set of committed files under `ios/Tests/Fixtures/synthetic/`:

- **The Kotlin generator writes them.** `(cd android && ./gradlew updateIosFixtures)` runs
  `superplayer-testmedia`'s `SyntheticHlsFixturesTest` in update mode and rewrites the directory,
  on the golden traces' contract: run it alone, then `check`, and commit the diff with the change
  that caused it. Nothing else writes there.
- **Gradle's `check` holds Kotlin to them.** The same test in check mode fails when a committed file
  differs, is missing, or is not one the generators produce, naming each file and the update
  command. It is a JVM test and needs no Swift toolchain, which is why it is in `check`.
- **`ios/check` holds Swift to them.** `SyntheticHlsFixturesTests` generates every fixture with the
  Swift port and compares it file for file and byte for byte. It needs no JVM.

So a change to either generator fails its own side's build: Kotlin's until the fixtures are
regenerated, and then Swift's until the port follows. `index.tsv` in that directory is the table —
each fixture's name, generator and arguments — and the Kotlin test writes it, so the Swift test reads
which fixtures exist rather than keeping a second list; a row naming a generator Swift has not ported
fails rather than skips, and a directory the index does not list fails too.

What is committed is the **relative** form of the stream — the Kotlin generator's `files`, the form
`writeTo` uses — because every reference inside it is relative and it therefore names no `fake://`
host. The `fake://` keys of `resources` are a Media3 `FakeDataSet`'s addresses and nothing on iOS can
serve them; an iOS test serves the files by path from a loopback origin. Nothing about the Android
streams changed to make that true. Three fixtures are committed today: `hls-vod` and `hls-ladder`,
each three segments long so the one thing that varies between segments, the ID3 timestamp tag, is in
the comparison, and `hls-live` (#399), the live window once eight segments are published, so the
window has slid and `EXT-X-MEDIA-SEQUENCE` is not zero. The second-host variant and the Widevine forms
are not ported: a second host is the harness's second listener (#410) and iOS protection is FairPlay.
The HLS half of the hostile corpus is held the same way, in a directory of its own beside this one
(#412, *The hostile corpus on iOS* below).

**A synthetic stream plays in `AVPlayer`.** On Android the ADTS payload is never decoded —
Robolectric's shadow codec passes bytes through — so "it plays" was never a claim about the bytes.
`SyntheticHlsPlaybackTests` makes it one on the simulator: both forms committed as fixtures play to their end in a stock
`AVPlayer` with an audio track presented and nothing in the error log, and a control with one segment
withheld does not. Playing to the end is necessary and not sufficient, which is how #398 found the
payload wrong: an all-zero ADTS payload, what both generators wrote until then, also plays to its end
— on time, with no error any API reports — while AVFoundation's decoder refuses every frame. So
`SyntheticAacDecodingTests` decompresses a segment to PCM with an `AVAssetReader` and counts the
samples, with the all-zero payload as its control, and both generators now write a silent
`raw_data_block`. It plays over HTTP from the harness's origin, because `AVPlayer` never loads an HLS
playlist handed to it as a `file:` URL, and so it lives in `SuperPlayerTestkitTests` rather than
beside the generator: testmedia is phase 1, and its tests may not reach the phase 2 harness.

### Every shared document, on both platforms

ADR-0020 rule 4 makes the documents and the fixtures, never the code, what the two platforms share,
and ADR-0021 rule 6 holds each platform to them with a test of its own. This is the list. A shared
document added without a row here, or a row with one side empty, is a document one platform can
drift from without failing anything.

**A check that reads only files runs in Gradle's `check`**, on Linux, for both platforms at once
(ADR-0021 rule 7). **A check that needs a platform's own types runs on that platform**, Kotlin's in
`check` and Swift's in `ios/check`, and the two read the same file.

| Shared document or fixture | Android, in `(cd android && ./gradlew check)` | iOS and tvOS, in `ios/check` |
| --- | --- | --- |
| `docs/telemetry-schema.md`: every event and field, and none it does not name | `superplayer-core`'s `TelemetrySchemaDocumentTest` | `SuperPlayerCoreTests`' `TelemetrySchemaDocumentTests` |
| `docs/media-source-doctor.md`: a section per defect, naming its platforms and carrying its citation (#430) | `superplayer-diagnostics`' `DoctorDocumentTest`, over the sections naming Android | `SuperPlayerDiagnosticsTests`' `DoctorDocumentTests`, over the sections naming iOS and tvOS |
| `docs/compatibility.md`: a stability row per module and per Swift product (#430) | `verifyCompatibilityDocument`, against `android/settings.gradle.kts` | the same task, against `Package.swift` and `ios/MoQ/Package.swift`: text, so on Linux |
| `docs/modules.md`: the phase rule | `verifyModulePhaseRule`, against the module build files | the same task, against `Package.swift`'s targets: text, so on Linux |
| The session trace grammar (`docs/session-bundle.md`'s lines) | `superplayer-telemetry`'s `GoldenTraceTest` and `superplayer-diagnostics`' `SessionBundleGoldenTraceTest`, byte-identical goldens | `SuperPlayerTelemetryTests`' `SessionTraceTests`, which parses every Kotlin trace golden and writes it back byte for byte, and the structural goldens under `ios/Tests/Fixtures/golden/` |
| The synthetic HLS streams, `ios/Tests/Fixtures/synthetic/` | `superplayer-testmedia`'s `SyntheticHlsFixturesTest` | `SuperPlayerTestMediaTests`' `SyntheticHlsFixturesTests` |
| The HLS half of the hostile corpus, `ios/Tests/Fixtures/hostile/` | `superplayer-testmedia`'s `HostileManifestsFixturesTest` | `SuperPlayerTestMediaTests`' `HostileManifestsFixturesTests` |
| The corpus register: every pathology named at `SEVERE`, every `BENIGN` grade silent | `superplayer-diagnostics`' `CorpusRegisterTest` | `SuperPlayerDiagnosticsTests`' `CorpusRegisterTests`, over the HLS entries |
| `docs/throughput-traces.md`'s format, `ios/Tests/Fixtures/throughput-traces/` | `superplayer-testkit`'s `ThroughputTraceFixturesTest` | `SuperPlayerTestkitTests`' `ThroughputTraceFixturesTests` |
| #340's observed realtime bytes, `ios/Tests/Fixtures/realtime/observed-bytes.tsv` | `superplayer-realtime`'s tests, through `ObservedBytes.kt` | `SuperPlayerRealtimeTests`, through `ObservedBytes.swift` |

**The trace grammar is held in one direction.** The Kotlin trace goldens are read by iOS's
`SessionTraceTests`, while no Kotlin test reads the iOS goldens, which are structural and so carry
no times for a byte-identical reader to compare. A bundle golden is read by neither side, since its
`capability` lines differ by platform by design (`docs/session-bundle.md`), so each platform pins its
own.

**What is not on the list, and why.** `docs/http-transport.md` and its `TransportDocumentTest` are
Android's alone, because `HttpTransport` is not ported (ADR-0020 rule 6). `docs/ios.md` has no test
of its own. What it names is held elsewhere: the products by `verifyCompatibilityDocument`, their
API by `ios/check`'s digester, and the not-ported list by ADR-0020 rule 6. Its prose is review's, as
every document's prose is.

### The iOS harness

ADR-0021 rule 2's origin is `SuperPlayerTestkit`'s (#399). A test makes a `PlaybackHarness`, asks it
to `serve` a `TestContent` — `.hls(segmentCount:variantCount:)` or `.liveHls()` — and points a player
at the `LoopbackOrigin`'s `sourceURL`; the harness's `stop()` in `tearDown` closes every origin it
started. What a test then reads is what the origin received, `networkRequests()`: method, path,
`ResourceKind` by the Kotlin harness's own rule (`manifest`, `initialization`, `mediaSegment`),
headers, and the ordinal of the connection it arrived on. That is the `networkRequests` of the
Android harness counted at the server, because on iOS nothing between the engine and the server can
see a request.

- **One origin per `serve`, on `127.0.0.1` at a port the system chose.** `NWListener` binds the
  loopback address alone, so nothing off the host is reachable, and two tests in one process never
  share a port or a count — `LoopbackOriginTests` runs two harnesses at once to show it.
- **The HTTP is a real origin's, as little of it as a player needs:** `GET` and `HEAD` (and a `POST`,
  to `.licenceServer()` alone, *FairPlay on iOS*), persistent
  HTTP/1.1 connections with requests answered in order, single byte ranges with a 416 past the end
  (a set of ranges is answered whole, as RFC 9110 §14.2 allows), and RFC 8216's content types.
- **The live stream publishes on real time.** `.liveHls()` is the Kotlin `TestContent.liveHls()`'s
  six-segment window, sliding one two-second segment per two seconds since the origin started; a
  segment that has slid out is a 404. The Android harness times it on its own clock, this one on the
  host's, so a live test waits for a *segment published after it started* to be fetched rather than
  advancing to it.
- **Every wait is `harness.until(description, timeout:)`** — a condition polled every twentieth of a
  second, read on the caller's actor for a player's state or awaited for a fetch, and a typed
  `ConditionTimeout` naming the condition if the bound passes. The bound is a ceiling a pass never
  approaches. No test sleeps and then asserts, and none asserts how long anything took.
- **App Transport Security allows the origin's plain HTTP in the test host, not in this
  repository.** ATS is the host process's, and a SwiftPM test bundle on the simulator runs in
  Xcode's `xctest`, whose embedded `Info.plist` sets `NSAllowsArbitraryLoads`; the bundle has no
  `Info.plist` of its own to set a key in, and none is set anywhere here.
  `LoopbackOriginTests.testTheTestHostAllowsPlainHttpToTheOrigin` asserts the host's setting, so an
  Xcode that drops it fails there by name rather than as every playback test timing out. A test
  hosted in an app — the demo's, later — needs `NSAllowsLocalNetworking` in that app's `Info.plist`,
  which is the narrow key and the one to choose.
- **`AVPlayer`'s loads do not pass through a registered `URLProtocol`.** ADR-0021's *Context* left
  this an observation for #399 to confirm; `StockPlayerOriginTests` registers one that sees a
  `URLSession` load of the same origin and none of the player's.
- **Faults and shaping are applied at the server** (#410, ADR-0021 rule 3). `serve(_:faults:network:)`
  takes the Kotlin harness's two arguments: a `FaultScript`, addressed by kind, index, attempt and
  host as the Kotlin builder is — a resource its path without the query at the offset read from,
  indices one sequence per kind across hosts, latencies adding and the smallest truncation and cap
  governing, as the Kotlin injector does — and a `ThroughputTrace` — a `NetworkProfile`'s, or one read
  from `docs/throughput-traces.md`'s format. An origin records a request, *answers* it — the script
  applied to its address, the published bytes resolved where no fault replaces them — and sends it,
  held for the trace's round trip and any latency, then paced chunk by chunk. The script carries
  every fault a server produces: a status (with `firstAttempts` and `afterAttempts`), a latency, a
  throughput cap, a truncation that still declares the full length, a dropped connection, a token
  that expires at a segment (refreshable by a new credential), a shared cache in front of the origin,
  and a header set. `FaultScript.Kind` is `CaseIterable` because Swift has no reflection over the
  builder for an iOS `FaultSweepTest` to read, and `FaultScriptPlaybackTests` forces every kind on a
  stock `AVPlayer`, with a register of which test forces which, checked against `allCases`.
- **A fault is shown at the origin.** `origin.responses()` records how each request was answered —
  status, or none for a dropped connection; the fault kinds that acted; the body bytes sent; whether
  it came from the script's cache; and the trace's transport. On Android the injector's exception is
  the evidence; here the server is the only party a test can read a fault from.
- **What a server cannot produce is not in the script**, and is said rather than approximated: the
  Kotlin `failDnsResolution` and `failTlsHandshake` are failures inside the client, and
  `timeOutConnection` is the client giving up — a server can only be silent, which a latency is.
- **A second host is a second listener of one origin.** `.hls(hosts: 2)` and `.liveHls(hosts: 2)`
  serve the same files from two ports on one publication clock, the multivariant playlist listing
  each variant again on the second host as a redundant stream; a fault's `host:` is the listener's
  number, since every host is `127.0.0.1`. `MultiHostOriginTests` refuses the first host's media
  playlist and watches a stock player move to the second, with the unrefused stream as the control.
- **The replay is per connection, and a trace's transport is reported, not switched.** Each
  connection sees the whole link, the limit the Android replay states; and nothing in the simulator
  can move the host's interface, so the transport a stretch names is what `OriginResponse.transport`
  says a request arrived on and nothing a player observes. Time zero is the first media segment asked
  for; on Android it is the moment the player was built. The origin's own start counted a cold host's
  start-up as link time (#474, `TraceStartPlaybackTests`). What a host does after the anchor is
  still its own, so the quick session there is judged off the origin, as the stretch each response
  arrived on, rather than by a start (#503).
- **One test measures a duration, and argues its bound.** `NetworkShapingTests` holds a shaped
  transfer to the trace: never faster, which is exact — the origin sends a chunk no earlier than its
  last byte is due and the client's stopwatch starts first — and no more than a second slower on a
  two-and-a-half-second transfer, because a late wake is not carried into the next chunk's schedule,
  so what a loaded host adds is one wake and a loopback connection. It is the one exception to rule
  4, recorded at that rule's #410 addendum, and the acceptance asked for it by name.
- **The trace grammar is one reading on both platforms.** `ios/Tests/Fixtures/throughput-traces/`
  holds every profile as the Kotlin `format()` writes it and a hand-written `sample.trace`, each with
  a `.readings` file `superplayer-testkit`'s `ThroughputTraceFixturesTest` writes — the trace written
  back, its conditions at fixed times, and when fixed transfers arrive. The Swift
  `ThroughputTraceFixturesTests` computes the same lines and compares them as text, so the grammar,
  the profiles (`CONGESTED_WIFI` draws `java.util.Random`'s specified sequence, ported) and the
  replay's integer arithmetic are held to each other. `(cd android && ./gradlew updateIosFixtures)` rewrites them, as
  it does the streams.
- **Core's playback tests are in testkit's target.** `SuperPlayerPlaybackTests` (#400) plays a
  `SuperPlayer` from the origin, and lives in `SuperPlayerTestkitTests` for the reason
  `SyntheticHlsPlaybackTests` does: a test target takes its product's phase, and core's is phase 1,
  below the harness. The Kotlin core tests have a harness of core's own instead; an iOS one would be
  a second loopback origin. What needs no origin — `MediaRequest` as a value, the remembered-position
  memory and the observed register (ADR-0020 rule 13) — stays in `SuperPlayerCoreTests`. Playback is
  driven through `player.avPlayer`, since that is where a consumer's transport controls are too, and
  ADR-0021 rule 1's addendum bounds what a test may do there; a start is asserted as a position,
  never a duration. `SuperPlayerPlaybackTests` reads it off a ready item held paused, within an AAC
  frame, because a reading taken once the player is playing has a window a loaded host can outrun
  (#502); the suites still reading at the first poll that finds the player playing argue their own
  window beside their constants. Several core tests import
  core `@testable`, each arguing it in its own comment: `RememberedPositionsTests`, for the order
  rule a read-is-a-use that no playback can isolate, and `ObservedSignalRegisterTests`, for the
  register's live count, which no public reading can see until the notification it waits for
  arrives. A third, `PlaybackSnapshotTests` (#401), for the snapshot's internal memberwise
  initializer — so the stored JSON is pinned for a value with every field set, which no public
  call produces without playing — and for its internal remembered-position list, whose order is
  what a restored memory evicts by and which `.resumeFromLastKnown` can show only one id at a time.
  None of those three seams is `package`, because no later product reaches it. A fourth,
  `LifecycleRulesTests` (#402), for the lifecycle's reading of each notification and its
  process-wide count of who needs the session and the idle timer, which playback shows one player
  at a time and on audio alone; the count's constructor and its platform protocol are `package`,
  because `LifecyclePlaybackTests` in testkit's target reaches them too. A fifth,
  `TelemetryPaysNothingTests` (#408), for the collector and measurement session a player holds,
  which nothing reports on a player without a collector. A sixth, `FrameOutputPaysNothingTests`
  (#405), for whether a player holds a display link, which is visible to nothing but the link's
  target (*A frame output*). A seventh, `ResiliencePaysNothingTests` (#419), for the item-failure
  observations a player registers, which no public reading can see until an item fails; the
  classifying seam it fills with a stand-in, `FailureClassifying`, is `package`, because
  `SuperPlayerResilience` fills it. An eighth, `DrmPaysNothingTests` (#422), for the key session a
  player holds, which nothing reports until a key is asked for and no simulator asks for one; the
  seam it fills with a stand-in, `ContentProtecting`, is `package`, because `SuperPlayerDRM` fills
  it. A ninth, `DisplayLinkPaceTests` (#491), for the frame output's display-link pacing, a pure
  value whose answer is a `CADisplayLink`'s range and so no consumer's to ask for. And the one
  reader in **testkit's** target, `FrameOutputPacingTests` (#491), for the range a playing player's
  display link was asked for, visible to nothing but the link's target as the sixth's link is; it
  reaches `framePath` `@testable` rather than through a `package` seam because no later product
  fills or reads that path, and a `package` member would put a test's reading into every build. It
  is a file of its own so `FrameOutputPlaybackTests` stays on the public API. `TypedErrorPlaybackTests` reads a failed item's `error` and its
  error log's last entry off `player.avPlayer`, under rule 1's #419 addendum.
  `ProfileTableTests` reads each profile's knobs off `player.avPlayer`, and `SnapshotPlaybackTests`
  an item's readiness, under rule 1's #401 addendum. `SlowLinkPlaybackTests` adds a video output and
  a short forward buffer to a `SuperPlayer`'s item as to a stock one's, and reads a stalled item's
  error log, under rule 1's #474 addendum.
- **The lifecycle is driven by the platform's own notifications** (#402). `LifecyclePlaybackTests`
  posts `AVAudioSession.interruptionNotification` and `routeChangeNotification` from
  `AVAudioSession.sharedInstance()` with the `userInfo` keys `AVAudioSession.h` declares, as the
  system posts them, and reads play and pause off `player.avPlayer` under rule 1's #402 addendum;
  the control is a route change for another reason, which goes on playing. What the binding asks of
  the platform — activating and deactivating the session, holding the idle timer — goes to a
  recording `LifecyclePlatform`, the one `package` protocol rule 5's #402 addendum allows the
  feature, through the `package` initializer `SuperPlayer(configuration:lifecycle:)`. The system
  platform itself performs its session calls on a serial queue of its own (#494), and what
  `LifecycleRulesTests` reads there — the order of the calls and that none runs on the main thread —
  goes to a recorder handed to its internal initializer in place of the one function that calls
  `AVAudioSession`: not a second protocol, but the implementation of the one under test, its
  platform half still the device's. Three things only a device shows,
  and none is faked: that the system honours an activation and interrupts the session for a real
  call; that headphones pulled out post the route change; and the idle timer's video half, because
  every synthetic stream was audio when it was written — `LifecycleRulesTests` holds the rule over a
  presentation size, and that a video item's is non-zero by the time it plays is AVFoundation's
  documented behaviour. #405's synthetic video could now show it on the simulator; no test does yet,
  and `FrameOutputPlaybackTests` plays that video under a platform that does nothing, because an
  `xctest` host has no `UIApplication` to hold the idle timer on.
- **The collector's playback tests are in its own target** (#409). `SuperPlayerTelemetryTests`
  depends on the harness, which telemetry may, both being phase 2, as `superplayer-telemetry`'s tests
  drive the Kotlin harness. `QoeCollectorPlaybackTests` asserts the first frame and both of its start
  boundaries, a rebuffer the origin causes by holding a segment back, a seek and the session's end,
  as counts, flags and an order of readings on the monotonic clock — never a duration. The synthetic
  streams are audio, so **nothing here can show a video track switch, dropped frames, or a first
  frame that is a video frame**; those derivations rest on the schema document's citations until a
  video fixture or a device run shows them — #405's `.videoHls()` is such a fixture, and no
  collector test plays it yet. `CollectorSignalRegisterTests` is the collector's own
  observed register, held as core's is: it reads `liveObservations` and `isSampling` through
  `@testable`, the same seam argued the same way. Two more telemetry tests import the product
  `@testable`, each arguing it in its own comment: `QoeCollectorPlaybackTests`, for
  `QoeCollector.now()`, the monotonic reading taken before declaring intent so the boundary's place
  is asserted as an order on the clock the collector measures with; and `OSLogSinkTests`, for
  `OSLogSink.line(for:)`, because what the unified log keeps of a private value depends on whether a
  debugger is attached, so the format is asserted where it is made. None is `package`, since no later
  product reaches it. The seek test seeks through `player.avPlayer`, a consumer's transport control
  like `play()` and `pause()` and reached where a consumer reaches it (ADR-0021 rule 1's addendum).
- **The session trace recorder's goldens are structural** (#411), and *Golden traces on iOS* below
  says what that reading can and cannot show. Its register, `RecorderSignals.swift`, is held by
  `RecorderSignalRegisterTests` through `@testable` for the collector's reason, and the product's
  "nowhere else" scan admits it beside `CollectorSignals.swift`. `SessionTraceTests` imports the
  product `@testable` too, for the grammar's kinds in their rank and the content-id rule, which a
  played session shows only one id of. The `load` and `bandwidth` kinds come from iOS 18's request
  events and no runtime here is below 18, so their absence below it is asserted only by the
  register's test, as CMCD's is.
- **A session's platform is a protocol, and one test reads the real one** (#403). `PlaybackSession`
  publishes to `MPNowPlayingInfoCenter.default()` and `MPRemoteCommandCenter.shared()` through the
  one `package` protocol rule 5's #403 addendum allows the feature, `NowPlayingPlatform`, reached
  through the `package` initialiser `PlaybackSession(player:resolver:centre:)`: the dictionary is
  handed over in MediaPlayer's own keys, so a recording platform holds exactly what the system would
  have, and fires a command at the targets it was given — which no test can do to the real centre,
  since `MPRemoteCommand` has no public way to invoke a target. `PlaybackSessionTests` holds the
  shape with nothing playing — the targets, the order `release()` keeps, a swap, the process's one
  Now Playing between two sessions, the resolver declining — and reads the session's own observed
  register, `SessionSignals.swift`, through `@testable`, one more core test importing core so, for
  `ObservedSignalRegisterTests`' reason; that test admits the session's file as the one other place
  in core a registration may be written. `PlaybackSessionPlaybackTests` plays: the dictionary's
  title, subtitle, artwork (a `file:` PNG, since the origin serves streams), duration, elapsed time
  and rate, a remote pause read off `player.avPlayer` under rule 1's #403 addendum, a swap that
  carries commands to the incoming player, and an id resolved from outside resuming where it was
  left. One test there sets and clears the **system's** dictionary for real, beside a player with no
  session that leaves it nil. **That a button press reaches the target, and that the lock screen
  draws what was published, are a device's** — the i1 demo's on an iPhone.
- **CMCD's join is asserted at the origin** (#446). `CmcdPlaybackTests`, in the collector's target
  because the claim is about every event, compares the `sid` and `X-Playback-Session-Id` each
  request carried with the `sessionId` the sink was handed — one session, two adoptions of one
  content, and `.disabled` as the control that sends no `CMCD-*` header. It skips below iOS 18,
  as `CmcdFindingTests` does. **No runtime here is below 18**, so the branch that sends nothing
  there is reached in `CmcdConfigurationTests` instead, through `@testable` and an argument the
  binding takes for exactly that, on a later runtime: what it covers is the absence of a call, and
  that an iOS 17 device sends no CMCD and still sends the header is the header's documentation,
  confirmed by no run. Nor can it show `br`, which needs a video ladder the harness does not serve.
- **A pool's device is stated, as on Android** (#404). `PlayerPool`'s bound is
  `DeviceCapacity.swift`'s reading of `os_proc_available_memory()`, which an `xctest` runner — not an
  app — reads as zero; so a test states the memory left through the pool's `package` initialiser,
  `PlayerPool(configuration:availableMemory:)` — the memory half of what `TestDevice` states — and `PlayerPoolTests` holds the
  bound to stated figures through `DeviceCapacity.concurrentPlayers(availableMemoryBytes:)`. Being
  `package`, it needs no `@testable`; `PlayerPoolTests` imports core `@testable` for the
  register's count alone, `ObservedSignalRegisterTests`' seam. What a recycled player no longer carries after
  its content really played is `PlayerPoolPlaybackTests`', read through `.resumeFromLastKnown`.
  **What a pooled player costs and how many decoders a device sustains are a device's** — the
  per-player budget and the ceiling are argued, not measured, and i10 (#439) measures both on the
  device matrix.
- **A feed's prefetch is counted per row** (#417). `PreloadCoordinatorPlaybackTests` serves every
  row of a feed from an origin of its own, so what was fetched for a row is that origin's count: a
  row prefetched ahead fetched its playlists once and not again when it played, a pool with no
  coordinator fetched nothing ahead, the row the feed moved to was handed the player holding it —
  the very item kept by `setMediaRequest` — a memory warning made the row fetch again, and a
  constrained path fetched nothing until it changed. The device is stated through the
  coordinator's `package` initialiser — the memory left, and no real path watched — with
  `pathChanged(isConstrained:)` the reading a test states Low Data Mode with, since no simulator
  setting turns it on; the memory warning is the real notification, posted. `PreloadSignalRegisterTests`
  counts ADR-0010 rule 13 as live registrations of the coordinator's own register, process-wide,
  because a pool with no coordinator has nothing to ask. **That a prerolled player reaches its first
  frame sooner, and that memory stays flat over a long scroll, are a device's** — real time cannot
  show "sooner" (ADR-0021 rule 4), and the feed demo under `xctrace` is i4's exit (`docs/ios-plan.md`
  §7). `ios/hunt-leaks` (#418) records that feed on a **simulator**, outside every check, and says
  whether the app keeps what a scroll made; the same scroll on an iPhone, memory flat over 200 rows
  and TTFF there, is #435's, and `devicelab/lab run feed --platform ios` measured it on an iPhone 17
  Pro Max on iOS 27.0 over a cable (`devicelab/README.md`, *The feed scenario*): 200 rows down and
  back twice at a second a row, 602 first frames from declared intent at a median of 258 ms, p90
  289 ms and max 780 ms — 259 ms in the first pass and 257 ms in the second — and the app's physical
  footprint **flat**, a median of 15.9 MiB over the first pass's 393 samples (p90 16.4) and 16.3 MiB
  over the second's, half a megabyte more and inside the first's spread. That footprint is the app's
  own: AVFoundation decodes in system processes, so it says the app keeps nothing per row, not that
  the device's memory stayed flat. What it still cannot show is a prerolled row's first frame
  arriving sooner than a cold one's, which needs the same scroll with preload off beside it.
- **The adaptive policy's seams are `package`, and no ABR test is `@testable`** (#416).
  `SuperPlayerABRTests` reads the product's pure halves through their public API and the oracle
  through core's `package` `ThroughputSource`, the methods core itself calls, on an `EstimateMemory`
  of the test's own and a clock it states — so aging between two and fifteen minutes is asserted on
  stated times rather than waited for. `AdaptivePolicyPlaybackTests` shortens the hysteresis cooldown
  through `AdaptivePolicy.forProfile(_:rebufferCooldownMs:memory:)`, a `package` seam its comment
  argues: the real half-minute is a constant the pure tests hold, and what the harness shows is the
  mechanism — a stall holding the ceiling on the playing item, and the first trigger after the
  cooldown lifting it. It plays the audio stream, not the video ladder, because of #474. **That a
  held ceiling changes which rung is played, and that the cellular cap binds on a cellular path,
  are a device's**: the simulator's path is its host's, and testkit reports a trace's transport
  rather than switching it.
- **A realtime player's engine is read where no public reading exists** (#431).
  `SuperPlayerRealtimeTests` imports the realtime product `@testable`, each file arguing it:
  `RealtimeFormatsTests`, because the codec table answers CoreMedia vocabulary and is internal, as
  the Kotlin table is; and `RealtimePlayerTests`, for the display layer's renderer — its decode-failure
  notification and its error, the only reading of a decode the simulator gives — and for the
  presentation time the first frame was stamped with, which is what shows the first timestamp was
  subtracted. #432 added three: `CodecConfigurationRecordsTests` and `RealtimeAudioDecodingTests`,
  because the framing and the description are internal as the Kotlin object is; and
  `RealtimeTracksTests`, for each track's enqueued presentation times and its renderer — the only
  reading of A/V sync and of a played audio track the simulator gives. #433 added one more,
  `ScriptedFrameSourceConformanceTests`, for the table's `mappedTokens` alone, which the suite's own
  list of codec families is held to. #461 added `RealtimeSharedPiecesTests`, for the Picture in
  Picture playback delegate's type, which AVKit alone reads; it also reads the `package` seam a
  session and the lifecycle use (`playbackState`, `hasVideo`, `perform`, `PlaybackSession(engine:)`),
  which needs no `@testable`. `RealtimeTelemetryTests` imports nothing `@testable`, since a sink
  is its reading. Everything else there is the
  public `RealtimePlayer`: its clock, `isRendering`, `failure` and the typed refusals. *A realtime
  transport on iOS* above says what the fake is.

What it cannot show is what real time cannot: a test here is evidence that something happened and in
what order, never that it happened sooner, and a device is still the only place a FairPlay key, a
display mode or a QUIC session is real (ADR-0021 rule 5).

### The hostile manifest corpus

`HostileManifests`, also in `superplayer-testmedia`, is the other half of synthetic media:
valid-but-hostile HLS and DASH — ladder gaps, overstated bitrates, missing codecs, audio-group
mismatches, ragged segment durations, bare discontinuities, clock skew, short and missing time-shift
windows, a mid-stream ladder change, a live playlist served cacheable, a token scoped to the manifest
and not to its segments, a token that expires inside the content, and a CORS configuration that
refuses a credentialed request — the pathologies `PRD.md`
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

Phase 9 adds a **third** reading of the same corpus, and it is not a fourth table: `CorpusRegisterTest`
(in `superplayer-diagnostics`) is what `MediaSourceDoctor` *says* about each entry — the findings it
reports at `SEVERE`, and the test that forces them — checked against `HostileManifests.graded()` so
that a pathology added there fails it. It records the doctor and moves no row of the two tables above,
because naming a defect is not recovering from it: `dash-availability-start-time-skew` is named there
and stands in `CANNOT_RECOVER` here (ADR-0015 rules 4 and 12). What each entry's defect *means* — the
citation, the cause, the threshold and what to change — is `docs/media-source-doctor.md`, written for
a reader outside this repository. A `Pathology` added to the doctor's vocabulary fails
`DoctorDocumentTest` until that document gives it a section carrying its citation; a corpus entry
added with no pathology fails `CorpusRegisterTest` instead, which is the other half of the pair.

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
   The same citation goes in `spec`. A *delivery* entry stretches no clause of either protocol,
   because its document is the good stream's: it cites what the defect is a departure from instead —
   the HTTP, URI or CORS clause, under `// ref:` where the convention has no standard behind it (a
   signed URL) — and the same argument is owed, for why the delivery is still legal.
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
7. Its row in `superplayer-diagnostics`' `CorpusRegisterTest.REGISTER` — a fourth table, and the one
   about the doctor rather than about a player: the findings reported at `SEVERE` and the test that
   forces them. Adding the entry without it fails `everyCorpusPathologyIsRegistered`, which is
   deliberate; what the failure asks for is a row, including a row with no findings and the reason
   the doctor cannot yet name it (ADR-0015 rule 12). A defect the doctor can name that no corpus
   entry carries goes in `UNGRADABLE` instead, with the test that forces it.
8. A `#### `<id>`` section in `docs/media-source-doctor.md`, in the group and the position the
   `Pathology` enum puts it in, carrying that pathology's `specCitation` verbatim and saying what the
   defect means, what produces it and what to change. `DoctorDocumentTest` fails without it. This
   step is owed for **every** entry of the doctor's vocabulary and not only for a corpus one: the two
   defects of the *fetch* that no corpus can carry each have a section too.
9. For an **HLS** entry, its iOS half (#412, *The hostile corpus on iOS*): `(cd android && ./gradlew
   updateIosFixtures)` to commit its fixtures, the same entry in `HostileManifests.swift` until
   `HostileManifestsFixturesTests` passes, and its row in `HostileManifestCorpusTests.recorded`.
   `ios/check` names the entry until all three are done.

A defect that lives outside the manifest cannot be applied by `FakeDataSet`, which serves bytes and
reports no response headers. That covers the `Cache-Control` mismatch and the CORS one. Such an entry
carries the
defect as `declaredResponseHeaders` and reproduces the *consequence* in its bytes — for the cache
rule, a live playlist that never changes. `TestContent.hostile` hands the headers to the harness,
which serves them on top of the bytes, so the player reads both. The entry must still say which of
the two its recorded row measures. `TestContent.servedWithResponseHeaders(...)` is the pair of controls
such an entry needs: the same bytes at the same URIs with the headers withheld, which is the only way
to show that a finding came from the transfer rather than from the document, and healthy content served
with a *correct* configuration of the same protocol, which is the false positive that matters.

The third shape a *delivery* defect takes is neither the bytes nor the headers but **where the stream
is published**: a signed URL carries its credential in the URI's query, so the token entries are the
good stream's documents served at a signed address (`hlsStream`'s `sourceName` and `mediaName`).
Everything else keeps the plain names. All three delivery entries are HLS and are deliberately not
generated for both protocols, which is the one documented exception to the rule above: nothing about
a token or an allowed origin is read from a playlist or an MPD, so a DASH twin would be a second copy
of one fact with no second reading to make of it.

The *cause* — an origin that keeps publishing behind a cache that does not pass the new versions on —
cannot be carried by fixed bytes at all. It is played instead by `LivePlaylistRevalidationTest`, over
`TestContent.liveHls()` (an origin that advances with the harness's clock) and
`FaultScript.Builder.serveThroughCache` (a cache that holds responses for a `max-age` and serves them
with the headers that say so). That is the one place a recovery from this defect can be shown,
because recovering needs something newer to exist.

### The hostile corpus on iOS

The HLS half of the corpus is ported to `SuperPlayerTestMedia` (#412) as `HostileManifests` and
`HostileStream`, every entry at every severity, with its id, citation, cause, magnitude and declared
headers; the DASH entries are not, because DASH is not (ADR-0020). It is **one corpus** by the rule
*One corpus on both platforms* states, in a directory of its own, `ios/Tests/Fixtures/hostile/`:

- **`<id>/<severity>/<name>`** holds each entry's files, the Kotlin URIs with
  `fake://superplayer.test/hostile/` taken off. A token entry's names carry their query
  (`?expires=…&signature=corpus`), because the signed address is the defect.
- **`index.tsv`** is one row per entry in `graded()`'s order — id, severity, validity, the instant its
  tokens were minted at, duration, source, magnitude (`-` for none), citation and cause — and
  **`headers.tsv`** one row per declared header, against the file it is declared on.

`superplayer-testmedia`'s `HostileManifestsFixturesTest` writes them under `(cd android && ./gradlew
updateIosFixtures)` and holds the Kotlin generator to them in `check`; `HostileManifestsFixturesTests`
holds the Swift generator to the same files, fields and headers, reading which entries exist from the
index, so an entry on one side and not the other fails **naming it**. The token entries are why the
Kotlin generator gained an internal overload taking the minting instant: a token's `expires` is in
the bytes, so a corpus minted at the wall clock is a different corpus every second. The Swift
generator takes that instant as an argument everywhere, since `SuperPlayerTestMedia` names no clock.

An iOS test serves an entry with `TestContent.hostile(_:)` — by path, query excluded, as a CDN looks
a file up before checking a signature — and its headers with
`FaultScript.Builder.addDeclaredResponseHeaders(of:)`, #410's header sets addressed by kind and index:
one set per kind, or for manifests one on the multivariant playlist (index 0, what the player asks for
first) and another on every playlist it names. Those are the only shapes the corpus declares, and a
declaration of any other stops the test naming the entry rather than being served approximately.

`SuperPlayerTestkit`'s `HostileManifestCorpusTests` **records** what a stock `AVPlayer` does with each
entry, in `HostileManifestCorpusTest`'s shape: one table for the graded corpus, outcomes as coarse as
the Kotlin ones and structural — failed with which error domain and code, ended, still playing,
stalled, never started — and no duration asserted; the budget and the two-second window that
classify an unfinished session are the ones ADR-0021 rule 4's #412 addendum admits. A stock player rather than a SuperPlayer,
because the iOS facade adds nothing between `AVPlayer` and the corpus yet, so there is no typed
failure to record. Every entry plays at once, from an origin of its own, inside one forty-second
budget, so the table costs one budget rather than nineteen; it read the same on consecutive runs
before it was committed, and one row has since been shown to depend on the host's clock (#459): a
live entry is judged against the wall clock, so `hls-cached-live-playlist` stalls at the live edge on
a fast host, or rejoins the unchanged window later on the live timeline and replays it — a position
past the media, read as degraded — if it waits long enough, and fails with -12889 or never starts on
a host whose responses arrive a target duration late. That row accepts the four, listed in
`timingDependent` with each mechanism traced, and any other outcome still fails. The table is a
Swift literal edited **by hand**, as the Kotlin record is: a moved row fails with the whole
observation printed as that literal, and the change that moves it writes the reason beside the row.
Two rows differ from Android's, and both are findings rather than gaps: `hls-dangling-audio-group`,
which Media3 plays, fails in AVFoundation; and `hls-cached-live-playlist` freezes with no error it
fails with, where core names the cache — its error log carries -12888, the playlist unchanged, which
nothing surfaces.

Every item the record plays starts on its multivariant playlist's **first** variant
(`startsOnFirstEligibleVariant`, #460). Left unset, AVFoundation picks the starting variant from a
throughput history it keeps for the whole process, and we found no API that resets it. After a few
corpus runs on the loopback in one process, the origin saw every ladder entry ask for its top rung's
playlist first. That happened from the third to the fifth `-test-iterations` run, depending on the
host. The severe ladder gap's top rung has frames larger than AAC's bit reservoir allows. A stock
player started on it stops at zero with no error and no reason, and that is the lost start #460
reported. The pinned start is the one a fresh process takes, and the rung a player climbs to
afterwards is still AVFoundation's. Re-issuing `play()` would not help, because the player refused
the media rather than losing a request. A record that holds for one run per process would read
differently depending on where the run sits in the process, with nothing saying so.

### The doctor on iOS

`SuperPlayerDiagnosticsTests` scores the iOS doctor (#427) against the same corpus, served the same
way: every test examines content on a loopback origin of its own through the public
`MediaSourceDoctor`, and what the doctor fetched is the origin's record, so "playlists and never a
segment" is an equality on `networkRequests()`. `CorpusRegisterTests` is `CorpusRegisterTest`'s
shape — per entry, the findings at `severe` as an equality, and every `benign` grade and every
healthy stream silent **one at a time** — and is also what holds ADR-0020 rule 8's lint to the
corpus: a lint that misread an entry fails that entry's row. The four delivery entries (#428) are
scored like the rest, because the origin serves each entry's declared headers
(`FaultScript.Builder.addDeclaredResponseHeaders(of:)`); `DeliveryPathologyTests` is the Kotlin
`DeliveryPathologyTest` — each entry diagnosed with its headers served and withheld, a control per
rule, and the one-segment probe counted at the origin as one `HEAD` that sent no body byte.
`PostmortemTests` is the Kotlin four-combination table over real `SuperPlayer`s, the test target
depending on `SuperPlayerResilience` for a real `classify(_:)` while the product does not; its
failed-with-a-defect row is the cached live playlist, whose session ends only where iOS 18's
playlist answers are readable, so that one row skips below 18. Three pathologies have no corpus entry and are
forced by a named test instead: the two defects of the fetch, and `hls-short-live-window`, whose
Kotlin twin is DASH's. Its stream is testkit's `TestContent.liveHls(windowSegmentCount:)`, a live
window a test chooses, published as though the default window had already slid so its media sequence
is above zero; two segments is the defect and three, exactly at RFC 8216 §6.2.2's bound, the
control. `PlaylistLintTests` imports the product `@testable`, arguing it in its own comment: the
lint's three refusals and its attribute-list grammar reach the public API only as an unreadable
finding with no reason, and nothing the origin serves carries a byte-order mark, a quoted comma or
both kinds of tag. No duration is asserted anywhere in the target, and no device is needed for any of
it. `DoctorDocumentTests` holds the vocabulary to `docs/media-source-doctor.md`, a section per
pathology naming iOS and tvOS on its platform line, as the Kotlin `DoctorDocumentTest` does for
Android (#430, *Every shared document, on both platforms* above).

### The bundle and the HUD on iOS

The same target holds the bundle and the debug HUD (#429), driven through the public API with the
`package` seams each states.

- **The device is stated for the golden and read for the redaction.** `hls-vod-bundle.trace` is a
  structural golden in `ios/Tests/Fixtures/golden/`, written by `ios/check --update-golden` alone
  through testkit's `GoldenFile` (moved there from the telemetry tests for this second caller), over
  core's `package` `CapabilitySnapshot` initialiser — a snapshot read from the simulator that ran the
  test would pin that host's OS version in a committed file, which is why the Kotlin golden states its
  device too. Its trace half is `hls-vod.trace`'s session, so the two goldens differ by the capability
  lines and the header alone. The redaction session below reads the real device.
- **Every withheld thing is present first.** `SessionBundlePlaybackTests`' redaction session carries a
  signed URL and a URL-shaped content id, an `Authorization` the app's `HeaderProvider` minted after the
  origin expired the token at a segment, a licence the harness's key server issued through the player's
  own exchange (a stand-in key session, since the simulator has no FairPlay), and a failure whose error
  log names the host — the re-adopted media playlist refused 404 — and then the device's name, model,
  vendor identifier, model identifier and OS build. Each is asserted present where it travelled or where
  the platform holds it, then absent from the bundle.
- **The HUD's rows are read off its model.** `HudFacts` is what the view draws, so `DebugHudTests`
  asserts rows on a `SuperPlayer` with telemetry and on a stock `AVPlayer` that really played, a delivered
  error on each, and the guard as a function of its three readings (Debug or not, simulator or not, the
  profile's `get-task-allow`). The telemetry half is fed through `DebugHudTelemetry`'s own `onEvent`, the
  Kotlin test's front door, because the estimate is the collector's ten-second sample. That the view
  draws is `ImageRenderer`'s: the overlay renders to an image, and the guarded body to the same one on
  a Debug simulator build.

What needs a device: the `get-task-allow` reading against a real embedded profile, the display's short
edge and the memory budget (an `xctest` runner has no window scene and is no app, so both read
`unknown`), the hardware-decode answers (the
simulator answers no even for H.264), and how the overlay reads over moving video.

### Rungs 4 and 5 on iOS

#420's three repairs are driven through a real `SuperPlayer` at the loopback origin, in
`SuperPlayerResilienceTests`, each beside the control that keeps it from being "always repair":

- **Rung 4** (`NextSourcePlaybackTests`): two sources are two origins, so which one served a
  request is which origin recorded it. The request starts at five seconds, so "at the held
  position" is the second origin's first segment, never a reading of a clock. The controls are a
  request with one source, which ends on rung 6 as it always did, and a player without resilience,
  whose second origin is asked for nothing.
- **The credential repair** (`CredentialRepairPlaybackTests`): `FaultScript`'s
  `expireTokenAtSegment(_:refreshable:)` refuses with 403 until a request bears a different
  `Authorization`. The test counts what the repair costs, the multivariant playlist fetched a second
  time and carrying the new credential, and that count is also what holds
  `AVURLAssetHTTPHeaderFieldsKey`, an option no SDK header declares, to reaching the wire. The
  controls are an expiry no credential lifts, which asks the provider once and ends classified, and
  a provider that returns what was refused or throws, which declines.
- **Rung 5** (`MediaServicesResetPlaybackTests`): `AVAudioSession.mediaServicesWereResetNotification`
  is posted from `AVAudioSession.sharedInstance()`, as `LifecyclePlaybackTests` posts an
  interruption, with the player paused so the held position is one the test read. Two resets at one
  position are rebuilt and the third ends on rung 6. The control is playback that advanced, which
  starts the count over. A rebuild is seen as the multivariant playlist asked for under a new
  `X-Playback-Session-Id`, never as which item the facade made current, and never as a count of
  playlist requests: `AVPlayer` asks again on its own for an ask that went unanswered, which a
  count reads as a rebuild (#505), and the origin drops the first ask in the two tests that assert
  the count, to keep that difference forced. The bound is read off
  `SuperPlayer.maxDecoderRecreations`.
  That is a `package` constant, because it bounds no promise a consumer writes code against and a
  test in another target has to name it. It is the one `package` reading these tests make.

**What only a device shows**, and none of it is faked. The post resets nothing on the simulator, so
what a real reset does to the decoders under an item is unobserved. So is whether the item rebuilt
on the kept `AVPlayer` is enough, or whether the engine itself must be rebuilt, which would break
`player.avPlayer` as the escape hatch every view holds. The same goes for `AVError.mediaServicesWereReset`
failing an item, which the classifier maps to rung 5 but which no test can raise. A real CDN's
token scheme is the last: the origin's refusal is the one `FaultScript` writes.

**One gap is not a device's.** An asset `SuperPlayerPreload` prefetched for a pooled player is made
before any refusal, so it carries no refreshed credential. A refusal it then meets reaches a ladder
that already holds the credential, and a provider handed that credential as the one sent may
decline. No test covers a feed whose token expires.

### The fault sweep on iOS

#421 is i5's exit criterion, and it ports the Kotlin pair beside a detector:

- **`FaultSweepTests`** plays every `FaultScript.Kind` once, through a player configured with
  `Resilience.standard()` and nothing else, and records how each ended in `swept`: recovered, or
  ended on a named class. All eight cells play at once, one origin each, so the sweep costs one
  ceiling of real time rather than eight. HLS is the only column, because `AVPlayer` plays no DASH.
  There are eight kinds rather than the Kotlin ten, because the script holds only what a server can
  produce. **Without reflection, a new kind is made visible by the type**: `Kind` is `CaseIterable`,
  `script(for:)` is an exhaustive `switch` over it, so an added kind does not compile until it is
  swept, and the table's keys are held to `allCases`.
- **`FallbackRungCoverageTests`** names, per `FallbackRung`, the test that forces it and the test
  that counts its absence, checked against the source tree through `#filePath`. Rungs 1 to 3 have a
  row that says why they have no test here: they are AVFoundation's own (ADR-0020 rule 6).
- **`StaleLivePlaylistPlaybackTests`** plays the corpus's frozen live playlist twice. Served with its
  ten-minute cache rule, it ends on `Transient.CdnEdge` naming the intermediary. With the rule
  withheld, it ends on `Content.SegmentGap` naming the origin. Either way telemetry hears the ending
  through `TelemetryCollector.sessionFailed(_:)`, since no item failed. The controls are a live stream that
  keeps publishing, which plays on, and a player without resilience, which waits as a stock one does.
  `StalePlaylistJudgementTests` holds the bounds over values, at the millisecond, through
  `@testable`.

**One gap the sweep found, not filled.**

- **A segment that never answers in time stalls an `AVPlayer` for good.** AVFoundation gives up on a
  segment after about one target duration and logs a -12889. It then asks again. If every attempt is
  held as long, it waits at that segment with no error and no end. This was observed for seventy
  seconds of a two-second-target stream. So the latency row is half a target duration, which keeps
  the Kotlin row's meaning ("a delay, not a failure"), and not the Kotlin twenty seconds. Nothing
  detects the never-answering segment yet, so it is the one origin fault left that neither recovers
  nor ends named.
- **So does a link too slow for the stream** (#474). Bytes flowing steadily do not extend that
  wait: a segment not whole within it ("No response for media file in 2s") is abandoned and asked
  for again from its first byte. On a link slower than a segment's bytes over a target duration —
  for a segment as long as its target, slower than the stream — no segment ever arrives, so a player
  that has not started never does, and on 27.1 one that has stalls for good at the first segment it
  fetches there. CI's 26.5 was seen not to ask a started session's segment again within a minute, which
  reads as a wait that grows with what is buffered, so the started half is a 27.1 reading.
  `SlowLinkPlaybackTests` holds a stock player and a `SuperPlayer` to the same outcome, with a
  link fast enough as the control. A session scripted to stall on a slow stretch therefore asserts
  that the stall began, never that it ended.

**What only another runtime or a device shows.** The coarser judgement below iOS 18 counts
AVFoundation's -12888 entries. It is held over values and never played, because nothing here runs
a runtime older than 18. The live edge is the item's, not one playlist's, so an audio rendition's
playlist that froze while the video's advanced would not be judged stale; the request events name a
playlist but carry no body to tell it was unchanged. A real CDN's cache, and a packager that publishes segments in bursts under
a target far above their length, are also unobserved.

### FairPlay on iOS

**The simulator has no FairPlay** (ADR-0021 rule 5): an `AVContentKeySession` there never composes
a key request, so no test here plays protected content. What `check` holds is everything around a
key request, with the key session stood in at the one `package` protocol `SuperPlayerDRM` defines,
`FairPlayKeySession`, reached through the `package` overload `Drm.fairPlay(_:keySession:)`. A test
makes the key request a device's session would — an `skd://` identifier, and a key request composed
from the certificate — and everything after it is the shipped code: the certificate fetch, the `POST`,
`RetryPolicy.licence` and `Backoff`, the credential repair, the classifier and the collector.

The licence server is `TestContent.licenceServer()`, the Kotlin `FakeLicenceServer`: an origin of its
own on the loopback interface, answering a `GET` of its certificate and a `POST` of a key request with
a licence derived from the request's bytes, so a test can tell which request a licence answered. Its
requests are `ResourceKind.licence`, refusable and relenting through `FaultScript` like any other, and
the origin records a request's body, which is how the key request posted is compared with the one
composed. `LicenceExchangeTests` (`SuperPlayerDRMTests`, reaching the stand-in's protocol as `package`
and nothing internal) drives it through a real `SuperPlayer` configured
as a consumer configures one, `drm` beside `resilience`, each claim beside its control: a budget that
relents within it and one that never does, counted at the server; one 401 repaired with the budget at
nothing, and a provider that declines; a key request the device will not compose, which asks no
server; a rotation and a renewal counted at the server and measured as nothing; a failed renewal that
ends nothing; and a player without resilience, which refuses the request and names nothing.
`BackoffTests` holds the jitter as a spread — in core's target since #424 moved `Backoff` to core as
`package`, because the download store re-enqueues on it too — since no public reading shows a wait's
spread. `DrmPaysNothingTests`, in core's target, counts ADR-0012
rule 13 as the session a player holds — the **set**, never the answer, as `SuperPlayerDrmSeamTest`
counts it — and `CollectorSignalRegisterTests` the output reading a protected player's session alone
registers.

The offline store (#423) is held the same way. `OfflineLicenceTests` opens `OfflineLicences.store`
on a fresh temporary directory inside a parent of its own, and drives the four verbs through the
stand-in's persistable half: a request asked for by identifier and delivered persistable at once,
a licence turned into a "persistable key" by a prefix, and a key turned into a "proof of deletion" the
same way, so a test can tell which bytes travelled where. Each claim is counted at the licence server:
an acquisition and a renewal are one `POST` per key, a release is one `POST` carrying the proof, and a
player configured with a stored licence — on a licence server of its own — makes **no** request at
all. Beside them: a reopened store reading what another wrote, an expired licence readable before
playback and refused as `Drm.LicenceExpired` after, renewed on the player that refused it, a
release the server refused waiting for its release — no longer playable, since its key is gone —
and retried with the proof the device gave the first time, and a device that will not make a request
persistable refusing the acquisition typed rather than leaving it waiting. "Nothing outside the directory" is read as the parent
holding the directory alone and the directory holding the store's file and SQLite's companions of it.
The store's file is real SQLite on the simulator; nothing about it is stood in.

**What only a device shows of the store**: that `respondByRequestingPersistableContentKeyRequest`
yields a persistable request for an identifier asked with `processContentKeyRequest(withIdentifier:)`
and for every request of a player playing offline; that `persistableContentKey(fromKeyVendorResponse:)`
makes a key that plays with no network; that `invalidatePersistableContentKey` answers a proof a real
key server accepts as a release, and whether a proof re-sent after a refusal is accepted as one;
that an offline player's expired licence is refused as its first key is asked for, which is where
`check` refuses it; and what a dual-expiry licence does on first playback — the session hands the app an updated key
(`didUpdatePersistableContentKey`), which the store does not yet write back, so a rental replayed
after its window began may need its key renewed.

**What only a device shows**, and none of it is faked — the i6 device ticket's run:

- that a FairPlay item reaches a key request at all, and that `SystemFairPlayKeySession`, the one
  implementation that touches AVFoundation, composes it and plays with the licence it is handed —
  no line of it runs under `check`;
- that a real key server accepts the request as `POST`ed — the raw SPC, `application/octet-stream`,
  with the `skd://` URI's host as the content identifier — rather than a wrapping of its own;
- which error a FairPlay item fails with after `processContentKeyResponseError`, and so whether
  `player.classify(_:)` of that item error agrees with the typed error core already delivered for the
  exchange: the exchange's failure is offered to the ladder first, so the item's own is not climbed
  again, but a collector asking about the item's error is answered from that error alone;
- that a rotation in a real stream is one `didProvide` request per new key, and a renewal one
  `didProvideRenewingContentKeyRequest`;
- that `isOutputObscuredDueToInsufficientExternalProtection` moves on a display without HDCP, and that
  the frame output under FairPlay yields no pixel buffer (#405's claim).

**Two gaps are not a device's.** An `AVContentKeySession` names a key and never the asset that asked
for it, so a licence still in flight when its content is replaced fails the content that replaced it.
And an asset `SuperPlayerPreload` prefetched for a pooled player is handed to the key session at
adoption, not as it was prefetched, so a key its prefetch would have asked for waits for the adoption.
No test covers either.

### Downloads on iOS

**The simulator test host cannot run an `AVAssetDownloadTask`**, which #424 forced rather than
assumed, on the iOS 27.1 simulator under Xcode 27: an `AVAssetDownloadURLSession` refuses any
configuration but a background one, and a task on a background session made inside `xctest`
completes at once with `NSURLErrorDomain` -1, "unknown error", having sent the loopback origin no
request at all — the background transfer daemon serves an app, and `xctest` is not one. So the
transfer is stood in at the one `package` protocol `SuperPlayerOffline` defines, `AssetDownloading`,
reached through the `package` overload `Downloads(configuration:downloader:)`, as `FairPlayKeySession`
is for FairPlay. **No test here runs a real download task**; every one of `DownloadsTests` runs the
stand-in, `StandInDownloads`.

What runs as shipped is everything the store decides: the manifest read by a real `AVURLAsset` against
the loopback origin, the live refusal off its indefinite duration, the variant chosen under
`PlaybackDecision.download` and the languages off its media selection groups, the session's
constraints, the SQLite index and what a reopened store does with it, the re-enqueue on
`RetryPolicy.segment` with `Backoff`, the naming of a failure by `SuperPlayerResilience`, and what a
player configured with the store opens. The stand-in does what a task configured with the store's plan
is documented to do — fetches the one variant the plan names by `BANDWIDTH` and each rendition it names
by language — so a count at the origin is a count of what the plan asked for, on
`TestContent.hlsWithChoice(audioLanguages:)`, whose every rendition is a directory of its own. What it
lands is the variant's segments concatenated into one ADTS file, which `AVPlayer` plays from a `file:`
URL, so "a downloaded request plays with no request" is asserted on a real `SuperPlayer` with the
origin counting. A failure it reports is the error AVFoundation is documented or was observed (#419) to
report — a `CoreMediaErrorDomain` code per HTTP status, and `AVError.diskFull` — so the classification
read is the shipped classifier's of that error.

**What only a device shows**, and none of it is faked — the i6 device ticket's (#436):

- that `SystemAssetDownloads`, the one implementation that touches `AVAssetDownloadURLSession`, runs
  at all — no line of it runs under `check`: that a task downloads exactly the variant its
  `AVAssetVariantQualifier` names and the renditions its media selections name, that its progress
  arrives as the time ranges it reports, and that its finished `.movpkg`, moved into the consumer's
  directory, plays offline;
- that a download survives process death and a network lost and regained, re-attached by the
  session's identifier when the store opens, and that the app's `handleEventsForBackgroundURLSession`
  hand-off is called back;
- which error a real task fails with on a refused segment and on a full disk, and so whether the
  classification the stand-in's errors read is the one a device's reach;
- what the session's constraints do: that a task waits on cellular and in Low Data Mode, and what
  `isDiscretionary` holds it for — coarser than Android's battery-not-low, and reported to nobody.

**A protected download's licence (#425)** is three stand-ins at once in `DownloadLicenceTests`, each at
its own `package` seam: the transfer as above, the key session at `FairPlayKeySession` for *FairPlay on
iOS*'s reason, and the network at `SuperPlayerOffline`'s second seam, `NetworkWatching`, because a test
cannot take the simulator's network away and a deferred release is a claim about there being none.
What runs as shipped is the `EXT-X-SESSION-KEY` read of the multivariant playlist, the store's order —
the licence acquired after the manifest and before the downloader is handed anything, which the
stand-in's `onStart` reads off both origins at that moment — the exchange against
`TestContent.licenceServer()` on the store's licence budget, the licence store's SQLite file and its
owed releases, and the classifier's name for a refusal or an expiry. `TestContent.protectedHls` declares
the keys and encrypts nothing. What only a device shows, beside the list above: that
`processContentKeyRequest(withIdentifier:)` on a session with no recipient delivers a persistable
request on a device, that the persisted key then plays the downloaded `.movpkg` with no request, that
`invalidatePersistableContentKey` answers a proof a real key server accepts, and that `NWPathMonitor`'s
reading is the moment a removal's release reaches the server.

### A television on tvOS

**No simulator switches a display mode** (ADR-0021 rule 5), and `xctest` has no `UIApplication` to
find a window's `AVDisplayManager` through, so `SuperPlayerTV`'s output is tested over a display stood
in at the one `package` protocol the product defines, `DisplayManaging`, reached through the `package`
overload `TvOutput.standard(displays:)`. Everything above it is the shipped code: the binding core
makes per player (`DisplayCriteriaMatching`), the item each replacement hands it, the end it hears from
the real `AVPlayerItem` notification, and the reading it reports through `QoeCollector` to a sink.
`DisplayCriteriaMatchingTests` and `DisplayModeTelemetryTests` hold it; one of them plays the synthetic
stream to its real end over the loopback origin, the rest set items that never load, since the
criteria are asked as an item becomes current. Core's `OutputPaysNothingTests` counts the slot (ADR-0014
rule 14).

**`ios/check` runs `SuperPlayerTVTests` on a tvOS simulator too**, beside the iOS run of every target,
because this is the first product with tvOS-only code: the stand-in is the same on both platforms, and
the tvOS run is what compiles and exercises the product as tvOS builds it. A host without a tvOS
simulator runtime skips that run and says so, unless `SP_REQUIRE_TVOS=1` makes the absence a failure,
which CI sets.

**What only a device shows** — an Apple TV on a real panel, #437's run:

- that `SystemDisplayManager`, the one implementation that touches `AVDisplayManager`, finds the key
  window's manager, and that `AVAsset.load(.preferredDisplayCriteria)` answers criteria the display
  acts on — no line of it runs under `check`;
- whether the panel switched, to what, how long it was blank, and that the wait is inside time to
  first frame, as `docs/telemetry-schema.md`'s *On a television* says;
- that `AVDisplayManagerModeSwitchStart` and `…End` arrive around a real switch, and
  `…SettingsChanged` when the viewer changes *Match Content*, so `DisplayModeChanged` brackets the
  blank;
- that `AVPlayerViewController` with `appliesPreferredDisplayCriteriaAutomatically` off leaves the
  criteria to the output, as the demo's `TvScreen` sets it.

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

### The same origin, reached the other way

`PlaybackHarness.buildPlayer` takes a `ChainBottom`, and the second entry —
`CONSUMERS_HTTP_TRANSPORT` — leaves the transport slot **empty** and reaches the same origin through
an `HttpTransport` chosen with `SuperPlayer.Builder.setHttpStack`. Empty rather than filled because a
filled slot wins over a stack (ADR-0016 rule 3), and a player whose stack resolved nothing would pass
every test for the wrong reason.

Everything else is the one composition above: the same fake origin, the same `FaultScript`, the same
`ThroughputTrace`, the same clock. Only the last step of the journey differs, which is what lets a
test body run over `ChainBottom.entries` and assert that the two answers are *equal* — the claim
#310 exists to make, since ADR-0016 rule 8 puts the typed failure on our side of a boundary a
consumer's client sits below. The stand-in transport does the one thing that makes it a consumer's:
it **reports** the status of a refusal on an `HttpResponse` rather than raising it, so the
`InvalidResponseCodeException` the ladder and the classifier read can only have been built by core's
adapter.

Two things it cannot be asked. Described content has no transport to stand in for, so it is refused
at `buildPlayer` rather than quietly served over the slot. And a fault *below* the response — a name
that will not resolve, a handshake that fails — arrives as the `IOException` a real client raises
rather than as the error code the injector chose, because a status is the only thing an
`HttpTransport` can report (rule 4): the two bottoms agree about statuses, and a parity test compares
statuses.

Since #314 the same choice is on `buildPool`, `downloadEnvironment` and `diagnosticEnvironment`,
because ADR-0016 rule 13 has all four entry points that compose a chain take a stack and the other
three are reached through those. On an environment it empties the environment's own transport, for
the reason the player's slot is emptied, and `PlaybackHarness.consumersHttpStack(environment)` is the
matching stack to hand to `Downloads.Builder.setHttpStack` or
`MediaSourceDoctor.Builder.setHttpStack`. One consequence is worth knowing before writing such a
test: with the slot empty and no stack named, a store's or a doctor's fetch resolves Media3's own
`DefaultHttpDataSource`, which cannot serve the synthetic streams' `fake:` URIs — which is what makes
the count of *zero* on the consumer's transport a real reading, and is how each module's
pay-nothing test shows its counter. A download that fails that way waits between attempts on a
delayed message, so such a test moves Robolectric's `SystemClock` as `DownloadRetryBudgetTest` does.

`buildPlayer` also takes an `httpStack` the caller names, which implies that same bottom. There is
one reason to reach for it and it is rule 13's: handing the **one** stack to a player and to a
download store is how "a player on the app's client while its downloads use the platform's" becomes a
count rather than two readings (`DownloadHttpStackTest`). Such a player's bytes travel the
environment's origin, so what left is `networkRequests(environment)` and the player's own origin is
never opened.

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
- **The controls are driven by key events, not seen.** `TvPlaybackControlsTest` is one of the library's
  two Compose UI tests (#272; the other is the debug HUD's, below). It hosts `TvPlaybackControls` in Compose's test rule under Robolectric, over a
  player this harness built, and presses D-pad keys with `performKeyInput`. Which control has focus and
  the position the seek bar shows are read from semantics, and seeks are counted by a `Player.Listener`,
  as a consumer's listener would hear them. The scrub tests turn Compose's clock to manual
  (`mainClock.autoAdvance = false`) and step it, because the commit is a timeout and a clock that
  advances itself while the test waits for idle would commit every scrub before the test looked. A held
  key is Compose's test injection repeating it, which has one repeat rate, so the claim that a hold's
  distance does not depend on the rate is `ScrubTest`'s. That test reads past the facade, into the internal
  `Scrub`, because no key injection can vary a repeat rate. Nothing is drawn to a
  screen, so how the focus indication looks from across a room is a device's (#274).

## A frame output

`superplayer-effects` lays a consumer's `FrameOutput` on Media3's GL effect pipeline (ADR-0022), and
**nothing under `check` runs that pipeline**: Robolectric has no GPU and no EGL, and the harness's video
renderer is Media3's fake, which decodes nothing and sets no pipeline up. So what is observed is the list
*reaching the engine*. `PlaybackHarness.videoEffectsDeliveries(player)` records each
`MSG_SET_VIDEO_EFFECTS` message at the video renderer, the place Media3 delivers `setVideoEffects` to and
where its own `MediaCodecVideoRenderer` would build the pipeline, with the effect count and whether it
arrived before the renderer was first enabled — which is Media3's "before `prepare()`" limit, observed
rather than assumed. `FrameOutputPlaybackTest` reads it on clear video, on protected content played to
the end over the harness's licence server, and on a player built without the output, its control. What
a device alone shows: that `FrameOutput.process` is called once per frame and on time, that the copy
shader compiles and what it draws is what the surface shows, and the frames an effect costs. ADR-0022
rule 4's tunneling half and rule 6's realtime half are core's `SuperPlayerFrameOutputSeamTest`, the
first because it is a selector parameter on content that plays untunneled either way, the second because
a phase 8 module's tests cannot reach phase 13's.

**On iOS the pipeline is core's own and the simulator runs it** (#405, ADR-0022 rule 5): an
`AVPlayerItemVideoOutput` on each item, a display link, the consumer's `FrameOutput` on a queue of the
player's, and an `AVSampleBufferDisplayLayer`. What it needs that nothing else here has is *pictures*:
every synthetic stream is audio, and Android's described video is a `Format` Media3's fakes synthesize
without a byte. So `SuperPlayerTestMedia`'s `SyntheticVideoHlsStream` writes real H.264 by hand — an
IDR whose macroblocks are all `I_PCM`, raw samples needing no encoder, then fifty-nine P pictures
whose every macroblock is skipped — in fragmented MP4 under an HLS playlist, thirty frames a second
at 64×64, a few kilobytes a segment, generated rather than committed for *Synthetic media, not
fixtures*' reasons. A silent AAC track is muxed beside it, because a video-only item in an `xctest`
host never advances without a video output on it — no layer there is ever in a window — so the
control, a player with no frame output, could not otherwise play it at all. It has **no Kotlin twin and no committed fixture**, because ADR-0021 rule 6 holds
two generators to one corpus and there is one generator; `SyntheticVideoDecodingTests` holds it to
decoding instead, counting every frame an `AVAssetReader` decompresses and reading each segment's grey
level back off the pixels. `TestContent.videoHls()` serves it. `FrameOutputPlaybackTests` plays it
with testkit's `PassThroughFrameOutput` — the pass-through ADR-0022 rule 8 keeps out of the library —
and asserts the frames handed over in presentation order, off the main thread, none twice, each a
frame of the stream, and at least half of those that played, which is a bound and not a rate
(ADR-0021 rule 4; the class comment argues the half); that the layer's renderer took them; that
after a seek back the frames follow the new position; and that a player configured for protected
content plays to its end with the output never called. The test sets `Configuration.drm` to a
value conforming to `PlaybackDrm` alone, which acquires nothing and is still declared protection
(ADR-0012 rule 13), so no key session is needed to show it. `FrameOutputPaysNothingTests`
is rule 9's count, in core's own target: no output, link or sample-buffer layer on a player without a
frame output and an `AVPlayerLayer` in the view, each of them once with a stand-in, none on a protected
player, and a Picture in Picture content source made from either layer. What only a device shows, and
#435 is the ticket: that a pass-through drops no frame `AVPlayerLayer` would have shown, that the
layer presents each frame on time against the item's timebase, that Picture in Picture runs from the
sample-buffer layer, and that FairPlay content yields no pixel buffer at all (i6). The first of those
is now **counted**, by `devicelab/lab run frames --platform ios` (`devicelab/README.md`, *The frames
scenario*), and on an iPhone 17 Pro Max on iOS 27.0, over Apple's BipBop example at 60 frames a second,
it is **met** since #491. Before it, two runs of two minutes' steady playback each lost one frame of
about 7,200 — a single gap of exactly two intervals (33.33 ms against 16.67), once at 63.6 s and once
near 85 s — while an `AVPlayerLayer` on the same phone dropped 0 and 4 frames by its own access log.
#491 found why with instrumentation it did not keep: the display link ticked every 16.668 ms against
the stream's 16.667 (an iPhone holds every link at 60 Hz, whatever the display's 120), so where in a
frame each tick landed walked by about 0.1 ms a second and the tick that crossed a frame boundary
skipped one — at 60.27 s in that run, the same mechanism at a different phase from the earlier two.
No tick was late and the one-frame back-pressure refused none. `DisplayLinkPace` now asks the link for
twice the content's rate, which on an iPhone takes the app's `CADisableMinimumFrameDurationOnPhone`
(the demo declares it; `docs/ios.md` tells an adopter): the link then ticked every 8.334 ms, and on
2026-10-13 two runs of the scenario counted **0** frames missing in 7,200 handed over 120 s of steady
playback each, with the `AVPlayerLayer` control dropping 0 and 0. A run before #491 also
met a network stall, which the scenario reports apart: twelve frames went missing around it, none in
steady playback. Without the key the loss returns, at about one frame every two or three minutes of
60 fps, and nothing but the device count can see that. The other three remain a device's and unrun. Two things
the simulator could show and no test does: that the flush on a seek keeps an old frame off the
screen — what the output is handed follows the new position with or without it, and what the layer
shows is not readable here, so the flush is held only by its registration being counted — and that
the layer goes on presenting across an item replacement, when its control timebase moves to the next
item's.

## The debug HUD

`superplayer-diagnostics`' `DebugHudTest` is the library's second Compose UI test (#294). It composes
`DebugHud` in Compose's test rule under Robolectric, over a player this harness built, and reads the
overlay back the way a viewer's accessibility services read it: each row carries its label as a content
description and its reading as a state description, so an assertion names the row and its value. Nothing
is drawn to a screen, and how the overlay reads over moving video is a device's.

Three things it can show and one it cannot.

- **The player's half is real.** The buffer, the position, the rung the player selected and the error it
  delivered are read off a player that really played, or — for the delivered error — one whose manifest
  the transport really refused.
- **The telemetry half is fed through the sink's own front door.** `DebugHudTelemetry` is a public
  `TelemetrySink`, so calling `onEvent` with `TelemetryEvent` values is driving the HUD through exactly
  the published API a consumer's `QoeCollector` drives it through, and it is not a read past the facade.
  What a real collector would add is asynchrony — delivery is a bounded queue on a thread of its own
  (ADR-0008 rule 4) — and a Compose test whose assertions waited on that queue would be timing rather
  than reading.
- **The guard has a control.** Robolectric's application is not debuggable, which is a release build as
  far as `isDebugHudAvailable` is concerned, so the HUD's own tests state the flag and the guard's test
  clears it and asserts that no node composes at all.
- **One test reads past the public API, and this is where that is recorded.**
  `aNewSessionStartsTheReadingsOver` asserts on `DebugHudTelemetry.reading()` and the internal
  `HudTelemetryReading`, because what it is about is the sink forgetting a session's readings — a claim
  about the absence of every row at once, which the rendered rows can only show one `unavailable` at a
  time and could not distinguish from a reading that was never taken.

What no test here can show is the field the HUD's own line depends on arriving from a real meter.
Media3's own bandwidth meter reports on thresholds a synthetic segment on a fast host never reaches, so
`PlaybackStateSampled.throughputEstimateBps` is null on a plain harness player — which `QoeMetricsTest`
asserts as the field's rule, with the citation — and it is reachable only on a player whose meter samples
every transfer, `superplayer-abr`'s. `BandwidthOraclePlaybackTest` is where the emission is asserted, over
a replayed trace so the value can be bounded by the link rather than by how fast the host ran.

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
  fetching does within one item, which is a throughput question for Phase 11. Two items in one store
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

`superplayer-effects` is the eleventh friend, of the first shape, and its slot is ADR-0022 rule 3's second
output slot, `EngineConfiguration.frameOutput`. Core's `SuperPlayerFrameOutputSeamTest` reads past the facade
the same two ways: the filled configuration, counted with a profile alone and with a hand-written stand-in
so that filling the second slot is shown not to fill `videoOutput` (rule 9), and the selector's
`tunnelingEnabled`, which yields on a player whose frame output runs (rule 4). The effect reaching the engine
is read at the renderer instead, through the harness (*A frame output*, above).

`superplayer-diagnostics` is the ninth friend, and the first bounded by a **closed list** rather than by
a slot (ADR-0015 rule 3): a doctor fills nothing, and what it reaches is `TransferChain.diagnosticChain`
— ADR-0013 rule 4's second shape unchanged — plus, when #288 and #292 land, `LiveWindowDepthCheck`'s
judgement and one function returning the capability snapshot. A fourth seam needs that rule amended by
name. Its `MediaSourceDoctorTest` examines corpus entries through a real `MediaSourceDoctor` over
`PlaybackHarness.diagnosticEnvironment`, and nothing it asserts reads past the facade: a report is the
whole of the doctor's output, and the count of what left the chain is the harness's.

The environment is `DownloadEnvironment`'s twin and is there for the same reason (#286). A doctor fetches
over the chain a *player* of that request would load through, so a test has to be able to give it that
player's transport, and the harness is phase 2 while the diagnostics module is phase 9 — so the type both
can name is core's public `DiagnosticEnvironment`, with an internal transport and an internal constructor,
handed to `MediaSourceDoctor.Builder` through a setter internal to that module. It carries a transport and
nothing else: a doctor builds no engine, chooses no track, acquires no licence and runs its one fetch on the
thread that asked, so there is no renderer, no `MediaDrm` and no load executor for a harness to own.
`networkRequests(environment)` counts what left it, exactly as for a player.

What it cannot show is what any Robolectric test cannot: a real CDN's headers, an intermediary that rewrites
a playlist, and a `Cache-Control` rule that a device would meet and a fake origin does not. The pathologies
themselves are the corpus's, which is a stronger fixture than a live stream for exactly the reason
*The hostile manifest corpus* gives.

A **postmortem** (#291) needs one more thing than a preflight: a session that really failed. `PostmortemTest`
builds a player of the same corpus entry, drives it on `HostileObservation`'s budget and step — the observer
itself cannot stand in, because it answers an outcome and keeps no player — and reads the classification off
`player.classify(error)`, which is the door a consumer uses. The doctor then examines the same request over a
`diagnosticEnvironment` of its own, which is what a support engineer really runs: a second fetch of the same
bytes, never a replay of the session's. What that cannot show is a manifest a CDN has changed between the
failure and the examination, which is the one way a real postmortem's findings and its classification can be
about two different documents.

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
`android/superplayer-telemetry/src/test/golden/`, one whole session each, played by `GoldenTraceTest` through
the harness: each protocol on demand, and each over a shaped network. Since #292 there are goldens in
a second module — `android/superplayer-diagnostics/src/test/golden/`, played by `SessionBundleGoldenTraceTest`
— which pin the *bundle* that trace becomes one layer out, header lines and capability snapshot
included. One command regenerates both, because the task is wired into every module.

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
  documentation lists **seven** rules — the seventh ADR-0015 rule 10's, for the capability snapshot
  Phase 9's *bundle* adds, stated there beside the six a trace obeys and carried out in
  `superplayer-diagnostics`'s `SessionBundle` — and
  `SessionTraceRecorderTest` plays a session through a signed
  URL that fails, and shows none of it in the trace. That is what makes a trace captured on a device
  attachable to a bug report — and a device trace's one normalisation is `withoutTimings()`, which
  drops the millisecond column and keeps the order it decided.
  `SessionBundleRedactionTest` is the same claim at bundle scale and is the one test in this
  repository whose *setup* is the subject: one session that really carries signed URLs on a CDN host
  of its own, a credential in an `Authorization` header repaired inside the transfer that met a
  refusal, a licence acquired from the harness's server, a session id on every request as CMCD's
  `sid`, and a host that stops resolving so the failure's message names it — each asserted **present**
  before the matching `doesNotContain` runs, because a redaction test over a session that never had
  the material passes against any implementation at all.

The contract is `docs/api-surface.md`'s, deliberately. Nothing regenerates a golden implicitly.
A golden test in check mode — the default, and what `check` runs — fails on any difference with the
diff in its message, so an uncommitted change to a golden fails `(cd android && ./gradlew check)` and CI alike.
Accepting a change is one command, run on its own and then reviewed:

```bash
(cd android && ./gradlew updateGoldenTraces)   # rewrite every golden from what the tests now produce
git diff -- '*.trace'                          # the behaviour change, as a reviewable diff
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
one layer richer, which is why `SessionTrace` says how a kind is added without a second format, and
`docs/session-bundle.md` is what a reader of the richer artifact is given, with
`docs/reading-a-session-bundle.md` beside it for the reader who has to get an answer out of one.

One thing #292 found where the rule above bites: **Media3's own bandwidth estimate is not golden
material.** Its meter measures on Media3's default clock rather than on the engine's, so under a
harness that shapes a network on a fake clock the estimate reads how fast the host ran — two runs on
this machine differed by a factor of three hundred. The `bandwidth` lines a trace carries are
therefore the *samples* rather than the estimate: each finished transfer's own bytes over its own
duration, both on the engine's clock. A device trace loses nothing by it, and a bundle gains the
dropout the average would have hidden.

### Golden traces on iOS

The iOS recorder (#411) writes the same grammar at the same `FORMAT_VERSION`, and its goldens are
under the same contract — but **not byte-identical**, because nothing here can drive `AVPlayer`'s
clock (ADR-0021 rule 4). A golden is compared in its *structural* reading, `SessionTrace.structural()`:

- **Every reading of time is masked** — the `+<ms>` column, the value of every field whose name ends
  in `Ms`, and the two rates measured on the host's clock (`bitrateBps` on a `bandwidth` line,
  `throughputEstimateBps`). The field names stay, so a field that appeared or vanished is a diff.
- **The periodic samples are dropped** — `PlaybackStateSampled`, `LiveLatencySampled`,
  `VideoFramesDropped`. How many a session has is its length over the cadence, so a start-up a second
  slower is one more sample; the first golden written, on a just-booted simulator, carried one that
  the next run did not.
- **Order is kept within each kind, and the kinds are printed in rank order.** AVFoundation reports
  through independent paths — key-value observation, notifications, the metric stream, the telemetry
  queue — and which of two delivers first within a few milliseconds is the host's scheduling. Within
  a kind the order is causal: a load completes after it starts, `ENDED` follows `READY`.

So **a structural diff shows** a kind that appeared or vanished, a line that appeared, vanished or
moved within its kind, and a field that was added or whose non-time value changed. **It cannot show**
a startup that got slower, a stall that got longer, a load that now happens later relative to a fact
of another kind, or a sample more or fewer — that class of regression is the benchmark's to find or
nobody's (ADR-0021's *Consequences*).

The goldens are `ios/Tests/Fixtures/golden/*.trace`, compared by testkit's `GoldenFile`, and
**`ios/check --update-golden` is the only writer**: it hands the test process `SP_UPDATE_GOLDEN=1`
through `xcodebuild`'s `TEST_RUNNER_` prefix and sets it to `0` otherwise, so a value left in a shell
cannot rewrite a golden behind a plain check. The first is `hls-vod.trace`, an on-demand session from
adoption to release (`SessionTraceRecorderPlaybackTests`); it is the iOS 18 reading, since the `load`
and `bandwidth` kinds come from `AVMetrics` request events, and on an older runtime the test compares
everything else. Two segments, so the session ends inside the ten-second sampling cadence anyway.
The trace is taken only once `SessionEnded` and a finished load for every resource the origin
served have reached it — the metric stream delivers after a request ends, and a challenge answered
inside one load is one event — and it read the same on consecutive runs on iOS 27.1 before it was
committed.

The grammar is one: `SessionTraceTests` parses every Kotlin golden in
`android/superplayer-telemetry/src/test/golden/` with the Swift reader and writes each back byte for byte,
and reads `hls-vod.trace` structurally. The redaction test drives a session that really carried a
signed URL, a URL-shaped content id and an `Authorization` header — the credential travels in the
source URL's user information and the origin answers the first playlist request with a `Basic`
challenge, since core has no header seam on iOS yet and a cookie in the shared storage was not sent
by the test host's player — and asserts each present at the origin before absent from the trace.

### The QoE regression gate

A golden says a session *changed*; it cannot say whether the change was worse. `PRD.md` Part 5 asks
for that too — replay throughput traces through the selection and buffering logic, score each session
with the QoE objective, and gate on the score — and `superplayer-abr`'s `QoeRegressionGateTest` is
that gate. It fails `(cd android && ./gradlew check)` when a trace's score drops past its committed floor.

**Where it runs, and why there.** In `superplayer-abr`'s own unit tests, under the harness, because
that is where the thing it protects is built and because `check` is this repository's definition of
passing. The full `PRD.md` §6 matrix in `android/benchmark/` was the other candidate and is the wrong one: it
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
as a fact about media held rather than as a score. The score is not computed twice: `SessionMetrics` and `QoeScore` moved from `android/benchmark/` into
`superplayer-telemetry` for this, so the gate and the benchmark reduce events through the same file.

**Which traces.** Synthetic only, each named by its `NetworkProfile`, and that is a decision rather
than a gap. A recorded trace could only reach the gate converted at build time, which needs the
network this document bars, or converted once and committed, which makes it a vendored dataset with
a licence to record in `THIRD_PARTY.md`. The profiles are deterministic, cite their numbers, and make
a regression attributable to a named condition; a recorded trace joins the gate when one is committed
on those terms, as a row in the floors file like any other.

**The floor and the margin.** `android/superplayer-abr/src/test/qoe-floors.tsv` holds one row per trace: the
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

Benchmarking is not covered here, because it is not testing either. `android/benchmark/` runs `PRD.md` §6's
fixed matrix across three players and emits a report; it is a separate Gradle build, it is not in
`check`, and a run of it fails only when it could not measure — never because of *what* it measured.
Its Robolectric arm honours this document's rules as it happens, needing neither a device nor a
network, and it still sits outside the suite: twenty runs of ninety cells is a measurement someone
reads rather than a check a change must pass. Its device arm needs both a device and a network,
because peak RSS and battery are properties of a process on a device and there is no honest way to
take either from a JVM. `android/benchmark/README.md` says all of this from the other side.

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
