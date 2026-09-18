# superPlayer

A production playback layer on top of AndroidX Media3 — policy, resilience, observability, and
lifecycle. Not a fork of Media3 and not a new player. Apache-2.0, Kotlin, JDK 17.

## Orientation

Four Gradle builds, not one. Mistaking them for a single build is the usual first wrong turn.

| Build | What it is |
| --- | --- |
| root | the 13 `superplayer-*` library modules, listed in `settings.gradle.kts` |
| `build-logic/` | an **included build** holding the convention plugins every module applies |
| `demo/` | a **separate** build resolving the library from published Maven coordinates |
| `benchmark/` | a **separate** build, the same way, running `PRD.md` §6's matrix |

A module's own build file holds only its dependencies; Android setup, SDK levels, Java and Kotlin
targets, lint, and publishing all live in the `superplayer.android.library` convention plugin.

`superplayer-core` is the only module a consumer needs; the rest are additive. Dependencies point
inward toward core, and no module may depend on one from a later phase. `docs/modules.md` carries
the module table, the phases, and that rule — read it before adding a module dependency.

`superplayer-core` holds the only library code so far: `SuperPlayer`, a Media3 `Player` that
delegates to a wrapped `ExoPlayer` through a private `ForwardingPlayer`, built by
`SuperPlayer.Builder(context)`. Because it *is* a `Player`, existing `PlayerView`, `MediaSession`
and Compose surfaces take it unchanged — the demo assigns it to a `PlayerView` with no adapter,
which is the point. `player.exoPlayer` is public, and ADR-0001 rule 2 explains why that one
`@UnstableApi` type is allowed out.

The second public type is `MediaRequest`: what to play, as content rather than as a URL. It carries a
stable `contentId` (not a URL — that is the point, and the `MediaRequest` KDoc says which defects it
closes), an ordered `sources` list whose first entry is played and whose rest are what rung 4 of
ADR-0011's ladder falls back to, a
`StartPosition` of `Beginning`, `At(ms)` or `ResumeFromLastKnown`, and the `title`, `subtitle` and
`artworkUri` that everything outside the app displays. `player.setMediaRequest(request)`
is the counterpart of `setMediaItem`, and the identity travels as the `MediaItem`'s `mediaId`.
Resume positions are held in memory for the life of one `SuperPlayer`, bounded to the
`SuperPlayer.MAX_REMEMBERED_POSITIONS` most recently used ids, and are not persisted; surviving a
configuration change is issue #10's subject, not this one's.

The third public type is `PlaybackProfile`: what kind of playback this is, chosen by
`SuperPlayer.Builder.setProfile(...)` and fixed for a player's lifetime. It names a use case —
`VIDEO_ON_DEMAND` (the default), `LIVE_LINEAR`, `SHORT_FORM`, `TV_LEANBACK`, `DATA_SAVER` — rather than carrying
numbers. The numbers, and a written rationale for every one that departs from Media3's own default,
live in `StaticProfilePolicy`, which is internal.

The fourth public type is `PlaybackSnapshot`: what a player was doing, as a value that outlives it.
`player.saveSnapshot()` and `player.restoreSnapshot(...)` are the pair, `toBundle()`/`fromBundle()`
the round trip, and a consumer's `onSaveInstanceState` or `rememberSaveable` is where the `Bundle`
lives — SuperPlayer persists nothing itself (ADR-0006 rule 2). It carries the current `MediaRequest`,
the position within it, `playWhenReady`, and the whole of the player's remembered-position map, so
`ResumeFromLastKnown` keeps working across a configuration change. It carries no profile, and it
names no content for an item set through `setMediaItem`, which has no identity to restore under.

Measurement leaves the library through `TelemetrySink`, and the fifth public type is really three:
the sink a consumer writes, the sealed `TelemetryEvent` hierarchy it receives, and the
`TelemetryCollector` that `SuperPlayer.Builder.setTelemetry(...)` takes. ADR-0008 splits them across
two modules — the sink and the vocabulary are core's, so an app's data pipeline names no Media3 type
and no telemetry type, while the collector that derives events from Media3's analytics is
`superplayer-telemetry`'s `QoeCollector(sink)`. The setter takes a collector rather than a bare sink
because collector-as-sink makes the wrong call compile: a sink passed on its own type-checks and
measures nothing. A player built without that call registers no analytics listener and allocates
nothing, which is rule 2 and is asserted by counting registrations rather than by inspection.

The vocabulary is now the whole CTA-2066 schema — first frame, rebuffer start and end, startup and
mid-stream failure, track switch, seek, live latency, periodic state, dropped frames — but **what the
numbers mean is `docs/telemetry-schema.md`, not the source**, and that document is the deliverable
rather than a by-product: every metric cites CTA-2066, and every place SuperPlayer's definition
departs from it says so with the reason. Read it before changing a field's meaning; the version in
`TelemetryEvent.SCHEMA_VERSION` bumps on meaning and not on shape, so a changed denominator is a
release note and an added field is not. Time to first frame is the one metric with an API attached:
its start boundary is *user intent*, which the library cannot see, so a consumer declares it with
`player.declarePlaybackIntent()` and a session that gets none is measured from content adoption and
labelled as such rather than silently mixed in.

Since #212 it also carries protected playback's own start-up cost: `LicenceAcquisitionEnded`, one
span per licence a session fetched, with a `LicenceOutcome` of `ACQUIRED_FROM_SERVER`, `REFUSED`, or
`SERVED_FROM_OFFLINE_STORE` — the last declared **unreachable** and made reachable by #210's offline
store, so that #210 was a behaviour change rather than a second change of the schema. It is
SuperPlayer's own metric, since CTA-2066 has none, and it is `QoeCollector`'s from Media3's DRM
analytics callbacks rather than `superplayer-drm`'s, because that module classifies nothing (ADR-0012
rule 5). A new event type is shape and not meaning, so `SCHEMA_VERSION` stays **2** for the fourth
release running, which the release notes say in as many words. A reused DRM session and a key
rotation each emit nothing, which is the difference between counting acquisitions and counting
sessions. `superplayer-drm`'s `LicenceTelemetryTest` drives it — a phase 6 test of a phase 2 metric,
because only there is there a licence server to acquire from — and asserts rule 6's redaction against
a trace of a session that really acquired one.

The whole vocabulary is emitted. Core signals the session edges from `adopt`, `restoreSnapshot`,
`resetForReuse` and `release`, because only core knows which item change carried a `MediaRequest` and
which was a recycle; everything else `QoeCollector` derives from Media3's `AnalyticsListener`, and
`PlaybackStatsListener` is deliberately not used — Media3's boundaries for joining time, buffering
and seeks are engine-shaped rather than CTA-2066-shaped, so renaming its fields would be fast and
wrong. Delivery is `TelemetryDelivery`, the bounded queue ADR-0008 rules 3 and 4
require — one daemon thread for the whole process, 256 events per player, the newest refused when
full, and every refusal counted against the session that lost it and stamped onto its `SessionEnded`
as that event leaves the queue — so a sink is never on a thread the engine needs and a blocking sink
costs events rather than playback. Two sinks ship —
`TelemetrySink.composite(...)`, core's own factory, which isolates a child that throws so one bad
sink costs neither its siblings their events nor playback its thread; and `superplayer-telemetry`'s
`LogcatSink`, one greppable line per event under the tag `SuperPlayerQoE`.

Android's own lifecycle rules — audio focus, becoming-noisy, wake and Wi-Fi locks — are on for every
player, switched on in `LifecycleBinding.kt` and fixed rather than per-profile: ADR-0006 rule 1 says
why they are correctness rather than policy, and therefore not behind `PlaybackPolicy`.

Publishing a player to the rest of Android is `PlaybackSession`, and it is a *choice* rather than a
default — ADR-0007 explains why a player pool makes "every player gets a session" unexpressible.
`PlaybackSession.Builder(context, player).build()` wraps a Media3 `MediaSession`; one `release()`
ends the session and then the player, in the order that matters; `setPlayer` swaps the player behind
a live session so a profile change does not tear the notification down. `PlaybackService` is the
`MediaSessionService` on top of it — a consumer subclasses it, overrides `onCreatePlayer` and
usually `onResolveContent`, and declares the subclass plus the foreground-service permissions in
their own manifest (rule 4 says why the permissions are not the library's). The notification is
Media3's own; nothing here builds one.

`MediaRequestResolver` is how content identity survives the boundary. A controller — the
notification, Android Auto, a watch — speaks Media3's `Player` API only, so it names a `MediaItem`
carrying a bare media id; the session resolves that id back through the app's catalog into a
`MediaRequest` and adopts it exactly as `setMediaRequest` would, which is what makes content started
from a car resume where the phone left it. `SuperPlayerSessionTest` drives a real `MediaController`
against a real session and is where every claim in this paragraph is checked.

A screen that needs many players at once — a feed, a grid — builds them through `PlayerPool` rather
than one per item, because concurrent hardware decoder instances are a device resource that a long
scroll will find the end of. `PlayerPool.Builder(context).build()` derives its bound from what the
device reports — `DeviceCapacity.kt` is the only place that reading happens, and it is the min of the
platform's concurrent-decoder limit over the codecs the feed declares (`setFeedCodecs(VideoCodec…)`,
H.264 and HEVC by default; a declared codec the device has no decoder for is left out rather than
collapsing the bound) and a per-player budget against the *app's* heap
(`ActivityManager.memoryClass`, since Media3 buffers on the Java heap), never below one. A pool built
with `setDrm` is bounded by the *secure* decoder's limit wherever the device declared one, because a
phone that ships several ordinary video decoders commonly ships exactly one secure one and that is
the number a protected feed runs out of (#211, ADR-0012 rule 12); a device that declared none is
bounded exactly as a clear feed is, because Widevine L3 plays protected content on ordinary decoders. `acquire()` returns null rather than growing past the bound, `recycle(player)` hands
one back, and `SuperPlayer.resetForReuse` is what makes a reused player carry nothing of the last
item: surface detached first (a stale frame in a recycled view is the tell of a hand-rolled pool),
then content, playback state, listeners, audio attributes and the remembered-position map. Audio
focus is a single token, so concurrently *playing* pooled players contend for it — a feed gives a
player to the row being watched and leaves the rest to preload. The demo's `FeedScreen` is that
recipe across 200 rows, and its KDoc is the lifecycle a consumer copies: cache, then a pool with
`setTelemetry { QoeCollector(sink) }` (a collector per player), then a `PreloadCoordinator` attached
before the first player, released in reverse. Its header samples `inUseCount`, `size`,
`PreloadCoordinator.warmDecoderCount` and `ContentKeyedCache.hitCount`, and each row shows its time
to first frame from `declarePlaybackIntent()` — the scroll `./gradlew huntLeaks` measures.

Everything below `MediaSource` loads through one `DataSource.Factory` chain, and `TransferChain` is
the single internal place it and the `MediaSource.Factory` over it are assembled — reached from
`SuperPlayer.Builder.build()`, because `ExoPlayer` has no runtime media-source-factory setter. What it
composes today is Media3's own default transport under two layers of core's. First `LivePlaylistRevalidation`
— which changes no request of a live HLS playlist that advances on time, reloads an overdue one with
`Cache-Control: no-cache`, and fails one that still will not move with the public
`StaleLivePlaylistException` naming the likely cause, before Media3's untyped `PlaylistStuckException`
can fire (issue #66) — and, above it, `LiveWindowDepthCheck`, which fails a live DASH manifest whose
`timeShiftBufferDepth` is no deeper than a segment takes to become available with the public
`LiveWindowTooShortException`, because no playhead fits inside such a window and Media3 alone plays it
outside, at a negative position (issue #67). What the chain exists for is the order, which its KDoc writes down along with where `superplayer-cache`, `-abr`, `-telemetry`, `-resilience` and `-offline` each
insert themselves (`PRD.md` §2.4). The cache slot is built: `SuperPlayer.Builder.setCache` takes a
`ContentCache` (core's public type, internal constructor, made by `superplayer-cache`) whose internal
`CacheLayer` fills it, and on such a player each item adopted from a `MediaRequest` stamps its
`ContentIdentity` onto every request it opens, so a key can be `contentId` rather than URL. The same
stamp carries a `LoadKind` on every item such a player plays — media, manifest, or unclassified — and
that is why a cached player builds HLS and DASH sources per protocol rather than through
`DefaultMediaSourceFactory`: only the source knows which request is a playlist, and a cache answers
media alone. A player
with no cache has an empty slot and the Phase 3 factory, and `SuperPlayerContentCacheTest` counts it.
Resilience's two slots are built and empty the same way (ADR-0011 rule 13, issue #176):
`SuperPlayer.Builder.setResilience` and `PlayerPool.Builder.setResilience` take a public
`PlaybackResilience`, and one that is also core's internal `EngineResilienceExtension` fills
`EngineConfiguration.headerRefresh` — a `HeaderRefreshLayer` composed closest to the transport, so a
token refresh and its retry are one transfer to everything above — and `EngineConfiguration.loadErrors`,
the `LoadErrorHandlingPolicy` handed to the media source factory, which is Media3's own when the slot is
empty. A player with either slot filled stamps every request with its `LoadKind`, cache or no cache, so
the layer can tell a 403 on a segment from a failure of the manifest; the `ContentIdentity` stamp stays
a cache's. `SuperPlayerResilienceSeamTest` fills both from a hand-written extension and counts what a
player without one pays, which is nothing.
`superplayer-preload` fills the other end of ADR-0010: `PreloadCoordinator.Builder(pool).build()` attaches
to a `PlayerPool` before its first player, through core's internal `PoolAttachment`, and from then on the
pool builds every player on one `PooledEngine` — the first player's load control, meter, selection
factory and decision target, and one playback thread — and hands those `SharedComponents`, its chain's
media source factory included, to the coordinator, which builds Media3's `DefaultPreloadManager` over
them. `setItems` and `setScrollPosition(index, velocity)` are the feed's input; how many rows ahead and
behind and how deep is `PlaybackDecision.preload`, a `PreloadPolicy` that defaults to none and that
`StaticProfilePolicy` sets per profile. A row plays through `setMediaRequest` and nothing else:
core asks the attachment for a warm source after adoption, and the id the item's session opens with is
one the coordinator minted before its first prefetched request (`PremintedSessionIds`), so the CMCD
`sid` join holds. Two things are easy to get wrong: a feed moves its position *before* it hands a row
a player, so the current row keeps its registration; and Media3 releases a source a player holds only
once the player lets go, which is why removing an adopted item is safe. `PreloadCoordinatorPlaybackTest`
drives a scripted scroll through `harness.buildPool`, whose players share one transport so
`networkRequests(pool)` is the screen's. At `PreloadDepth.DecoderWarmed` — `SHORT_FORM`'s — the nearest
rows also get a decoder: an *idle* pooled player prepared on the row's prefetched source with no surface,
session or `playWhenReady` (`SuperPlayer.holdWarm`), because Media3's preload manager has no decoder
stage. Only players the feed has handed back are warmed, so warm decoders never exceed `maxSize` less the
players out — the device's reading, never read again — and `acquire()` prefers the one warm on the
current row, which `setMediaRequest` then keeps (`WarmStart.Prepared`). The coordinator's `warm` and
`adopted` maps are identity maps keyed by player: compare their *values* by equality, because `itemOf`
builds an equal item, never the same one. `DecoderWarmupTest` states the limit with
`DeviceStatement.declareVideoDecoder(mime, maxSupportedInstances)` and counts with
`harness.videoDecodersHeld(pool)`; the harness cannot show a warm first frame arriving sooner, and
that test's KDoc says why. ADR-0010 rule 11's two platform rules cut the window after the decision, in
`PlatformRules.kt`: a memory guard that admits the window in priority order against a quarter of the
heap budget (the next row only on a low-RAM device) and releases everything nobody is playing on a trim
until the feed next moves, and a data-saver rule that prefetches nothing while Data Saver restricts a
metered network — watched through the default network's capabilities, so a handover applies at the
change. `PreloadPlatformRulesTest` states the device with `DeviceStatement.declareAppHeap`,
`declareLowRamDevice` and `declareDataSaverOn`. A pool built with `setPolicy(AdaptivePolicy…)` is one engine
too, with its oracle released by the last player (`AdaptivePolicyPoolTest`).
`superplayer-cache` fills the slot: `CachePolicy.contentKeyed(directory, maxBytes)` opens a
`ContentKeyedCache` — core's third Kotlin friend — over Media3's `SimpleCache`, with its index in a
database file inside the consumer's directory rather than Media3's `StandaloneDatabaseProvider`
(ADR-0010 rule 2). `ContentKeys` is the keying rule and its KDoc the reasons: content id plus URI
*path*, never host, query or Media3's own `DataSpec` key; `setMediaItem` content keyed by URL under a
prefix a request's key can never carry. `ContentKeyedCachePlaybackTest` proves F7 through the harness,
whose `networkRequests(player)` counts what left the chain and `TestContent.servedFrom(host)` is the
second CDN host. `CachePolicy.deviceAware(context, directory)` suggests the budget — a tenth of free
space between a floor and a ceiling, lower on `isLowRamDevice` — for the consumer to pass;
`PinAwareLruEvictor` evicts least recently used, a read counting as use, and never a span whose content
`ContentKeyedCache.pin` names. Pins live in a table of the cache's own index and count against the
budget, which ADR-0010 rule 12 decides; nothing is evicted until the cache has opened, so a directory
reopened under a smaller budget keeps what was used last. `CacheEvictionTest` drives it in exact bytes. Two of the modules the chain's KDoc places insert nothing into the chain and the KDoc is mostly
about them: measurement is a propagated `TransferListener`, so a layer that drops the registration
blinds ABR silently, and cache hits stay out of the estimate through Media3's `isNetwork` flag rather
than through chain position — ADR-0002's argument arriving through a different door. CMCD attaches to
the `MediaSource.Factory`, which is why the seam owns that too. Which HTTP stack sits at the bottom is
the *consumer's* since #309, and it plugs in at one named line: `resolveTransport`, whose order is the
harness's transport slot, then the stack, then Media3's own `DefaultHttpDataSource` (ADR-0016 rule 3 —
the slot substitutes for the network itself and a stack for the client over a real one). What a
consumer supplies is an `HttpTransport` — open a request for a URI with headers and an optional byte
range, answer a status, the response headers and a stream — taken as `HttpStack.of(transport)` through
`SuperPlayer.Builder.setHttpStack`, with `HttpStack` a public shell with an internal constructor
(`ContentCache`'s idiom rather than `PlaybackResilience`'s marker interface, because it carries
transport state rather than being a behavioural seam a friend module fills — rule 11). The adapter is internal, is the repository's first `BaseDataSource`
subclass because it is the *bottom* of the chain rather than a layer with an upstream, and reports
`isNetwork = true` so the cache-hit exclusion still means what it says (rule 9). The obligations are in
`HttpTransport`'s KDoc, each with what getting it wrong costs, because every one of them fails
silently; ranges are the one #309 wrote, and a range from a non-zero offset answered 200 is refused at
`open` rather than read past. `SuperPlayerHttpStackTest` and `ConsumersTransportEvidenceTest` share one
`ServingTransport` that *is* the origin, which is the only way a 206 with `Content-Range` or a
response header on a refusal exists anywhere in this repository, and `docs/testing.md`'s *The one
player that keeps its own transport* says why. Since #310 the **evidence** a failure carries is held to
the same standard as the bytes (rule 8, the record's most load-bearing rule): a non-2xx a transport
*reports* becomes an `InvalidResponseCodeException` carrying the status, the response headers and the
**stamped** `DataSpec` — the one the chain handed down, never a fresh one, because `ErrorClassifier`
reads `LoadKind` off it and an adapter that rebuilt it would reclassify every refused segment as a
refused manifest with nothing failing to say so. What proves it is parity rather than inspection:
`PlaybackHarness.buildPlayer` takes a `ChainBottom`, which reaches the harness's own origin either
through the transport slot or through an `HttpTransport` that reports a status as a consumer's client
does, and `superplayer-resilience`'s `ConsumersTransportParityTest` runs the rung, the class and the
counts over both and asserts they are equal; `BandwidthOraclePlaybackTest` does the same for the
estimate and `ContentKeyedCachePlaybackTest` for the cache keys. The other
three entry points rule 13 names, and the rest of the contract, are still open tickets.

CMCD (CTA-5004) is emitted there today, and is on for every profile including `DATA_SAVER` —
`CmcdBinding.kt` holds the per-profile table, the reasoning, and the `// spec:` citation for each
key; `CmcdMode` is the public choice between request headers (the default, because the wrong guess
loses telemetry rather than playback) and a query parameter (which can break a signed URL). Only
CMCD v1, which is the ceiling of the pinned Media3 rather than a decision. The `sid` it sends *is*
the telemetry session id, which is why `MeasurementSession` mints it in core rather than in the
collector, and why `TelemetryCollector.startSession` is handed one instead of minting its own — the
join between a CDN's access log and the app's warehouse is an equality on that string, and
`docs/telemetry-schema.md` states it as a promise. `mtp` is the one key no test here can see, since
it needs an adaptive selection and therefore a video renderer; `SuperPlayerCmcdTest` says so where
it stops.

That policy is reached through `PlaybackPolicy`, the boundary ADR-0005 establishes: observed
`PlaybackConditions` in, a `PlaybackDecision` (a `BufferPolicy` and a `TrackSelectionPolicy`) out,
with no Media3 type anywhere in it. `EngineBinding.kt` is the one place a decision becomes Media3
configuration, and `ConditionsBinding.kt` is its inverse — the one place Android's and Media3's
vocabulary becomes an observation. `PlaybackConditions` carries exactly the six observations
ADR-0009 rule 1 enumerates (transport, throughput with its spread, stall history, stream type, heap
budget, playback speed), every one optional, each with its reader named in its KDoc. The
implementation that ships is a static per-profile lookup, deliberately not adaptive — adaptive policy
is `superplayer-abr`'s, behind this same interface, supplied through `SuperPlayer.Builder.setPolicy`.
**How often a policy is consulted depends on what the engine was built with** (ADR-0009 rules 4
and 5): a policy that is also core's internal `EnginePolicyExtension` fills `EngineConfiguration`'s
retargetable slots — a load control, a selection factory, a meter, and the `DecisionTarget` a changed
decision is handed back through — and is then consulted again on the five `DecisionTrigger`s by
`DecisionReapplication`, every change a `DecisionChanged` telemetry event; any other policy, a
consumer's hand-written adaptive one included, is consulted once at construction with empty
conditions, because `DefaultLoadControl` cannot take a new target and half a decision in force is
worse than none. `player.playbackDecision` is the decision currently in force and can change on the
first kind of player. A player built with a profile alone registers no observer, and
`SuperPlayerPolicyTest` counts that rather than assuming it. `superplayer-abr` reaches the internal
interface as the second Kotlin friend of core; that is a compiler flag, not a Gradle dependency.

The facade *implements* `Player` by Kotlin delegation rather than extending `ForwardingPlayer`;
ADR-0003 records why, and the shape is load-bearing rather than stylistic. Kotlin delegation does
not override Java `default` methods and gives no warning that it hasn't: `Player` has one such member
(`getAudioSessionId()`, forwarded by hand) and `Player.Listener` is 37 of them, which is why the
listener wrapper forwards reflectively. `SuperPlayerForwardingTest` drives Media3's own
forwarding-contract assertion over both and is what catches the next one Media3 adds.

`superplayer-telemetry` holds `QoeCollector`, `LogcatSink`, and `SessionTraceRecorder` — a sink
that also reads Media3's analytics and records one session as a `SessionTrace`: every state
transition, track selection, load by kind and media time, error and telemetry event, one fact per
line, sorted by time then kind rather than by arrival, and redacted by construction so a trace from
a device can go on a bug report (the class KDoc lists **seven** rules since #292 — the seventh
ADR-0015 rule 10's, for the capability snapshot the Phase 9 bundle carries, which is this artifact one
layer richer). Two of the bundle's three layers are recorded here, because only a recorder attached to
the engine can see them: a finished `load` line carries its `durationMs`, and a `bandwidth` kind
carries each transfer's own throughput — the *sample* and never Media3's smoothed estimate, which is
measured on Media3's default clock and therefore reads how fast the host ran (`docs/testing.md` says
what that cost). Both are additions of shape, so `SessionTrace.FORMAT_VERSION` stays **1**. Its purpose is the golden traces in
`superplayer-telemetry/src/test/golden/`: a behaviour change appears in review as a readable diff
rather than as a metric that moved, `./gradlew updateGoldenTraces` is the one command that
regenerates them on the same contract as `updateApiSurface`, and `docs/testing.md`'s *Golden traces*
section says what a diff means and what may go into one. It also holds `SessionMetrics` and
`QoeScore`, the reduction of a session's events to `PRD.md` §6's numbers and to the QoE objective.
They were the benchmark's, and they moved here so the benchmark and the QoE regression gate share one
implementation rather than two. The gate is `superplayer-abr`'s `QoeRegressionGateTest`, in
`check`. It plays every `NetworkProfile` three times through `AdaptivePolicy` and judges the median
against `superplayer-abr/src/test/qoe-floors.tsv`, and nothing writes that file: a floor moves only
in a commit that says why in the row. `docs/testing.md`'s *The QoE regression gate* argues the margin
and the median. `superplayer-testkit` holds
`PlaybackHarness` — the deterministic playback harness every module from phase 2 onward tests
against, which compiles as a Kotlin *friend* of core so it can reach the one internal seam
`docs/testing.md` describes, and whose public API names **no unstable** Media3 type — the published
artifact is held to `verifyNoUnstableMedia3InPublicApi` like every other module. It does name two
Media3 types, deliberately, and its class KDoc says why: `Player`, stable, because every driving
method accepts a stock `ExoPlayer` and a `SuperPlayer` alike and that is the only supertype they
share; and `ExoPlayer`, which `buildStockPlayer` returns under ADR-0001's one exception because the
stock arm *is* one (#234). No Media3 type at all is the stronger claim, and it is the next module's.
`superplayer-testmedia`
holds `SyntheticHlsStream` and `SyntheticDashStream`, the known-good streams both core's tests and
that harness play; it is phase 1 and depends on nothing — not even Media3 — because it has to sit
below both, and `docs/testing.md` carries the argument for the module rather than a second copy.
It also holds `HostileManifests`, the valid-but-hostile corpus — each entry a good stream with one
thing wrong, a `// spec:` citation and a field cause — whose current behaviour
`superplayer-testkit`'s `HostileManifestCorpusTest` *records* rather than asserts as handled;
`docs/testing.md` says what a new entry must carry. A pathology with a magnitude is generated at three
`HostileStream.Severity` levels, each value argued where it is chosen: `graded()` returns them all, and
`all()` is its `SEVERE` half. The `BENIGN` level exists so a doctor is scored on false positives too.
It also holds `WidevineProtection`, the `pssh` box (ISO/IEC 23001-7 §8.1) that
`SyntheticHlsStream.protectedResources` declares in an `EXT-X-KEY` and `SyntheticDashStream`'s in a
`ContentProtection` descriptor — the same media, the same initialization data, two vocabularies —
and, since #209, a `ContentKey.SECOND` naming a different key id, which is the only way to say
"content the server licensed separately" in a stream rather than in a comment.
**Robolectric ships no `ShadowMediaDrm`**, so what stands in for a Widevine device is Media3's
`FakeExoMediaDrm`, stated through `DeviceStatement.declareWidevine` (a level, a session limit) and
`declareWidevineProvisioningFailure`, and what stands in for a licence server is `FakeLicenceServer`
— an origin at a host of its own, so a licence is a *transfer*: addressed by `ResourceKind.LICENCE`,
refusable and relenting through `FaultScript`, counted by `networkRequests`, and reported to no
bandwidth meter, because a licence is not media. The samples are not encrypted and cannot be — there
is no `MediaCrypto` here — which is why the harness sets `setPlayClearSamplesWithoutKeys(false)`, and
`docs/testing.md`'s *A Widevine device and a licence server* is the argument. `ProtectedPlaybackTest`
drives it through a **stock** `ExoPlayer`, and `buildPlayer(content = …, drm = …)` through a real
`SuperPlayer`; protected content with no `drm` is refused rather than played.
The harness drives a *download* too (#239), for Phase 7: `harness.downloadEnvironment(content, faults,
network)` returns core's `DownloadEnvironment` — public, internal constructor, `ContentCache`'s shape,
because testkit (phase 2) and `superplayer-offline` (phase 7) cannot name each other's types — carrying
the same transport a player of that content loads through and a load executor the harness counts, which
core's `TransferChain.downloadChain` puts under a download (ADR-0013 rule 6). `networkRequests(environment)`,
`advanceUntil(environment, …)`, `processDeath(environment, directory)` (a copy of the directory taken
with no load in flight — what a dead process leaves), `DeviceStatement.declareNetworkMetered`,
`declareBatteryLow` and `declareStorageLow`, and `useScheduledWork()` / `runScheduledWork()` — which
evaluates WorkManager constraints against those statements, because WorkManager's test driver does not —
are the rest. Two traps: a Media3 `DownloadManager` is built *paused*, and Robolectric's own network is
metered and unvalidated, so a download that is not about the network states it unmetered first.
`docs/testing.md`'s *Downloads* says what each stand-in cannot show, a reboot among them.
Both harnesses put their fakes in the engine configurator's *transport* slot, under the chain
`SuperPlayer.Builder` composes, so a test of real HLS or DASH sees every layer a consumer's player has; `TestContent.liveHls()`
is a live origin that keeps publishing and `FaultScript.Builder.serveThroughCache` a CDN cache in front
of it. The harness also replays a network: `buildPlayer(network = …)` takes a `ThroughputTrace` — bandwidth,
round trip and a *transport* per stretch, the last so a WiFi→cellular handover is a change of network
rather than of rate — and paces every transfer on it through `ShapingDataSource`, which sits in front
of the fault injector so a trace and a `FaultScript` are one player. `NetworkProfile` is `PRD.md`
Part 6's six profiles plus `ETHERNET` (#267, `STABLE_WIFI`'s rate on a wired transport), each constant
with its public source; the benchmark's matrix keeps the six, and the QoE gate plays all seven. `docs/throughput-traces.md` is the
format's specification and the replay's limits — notably that concurrent transfers each see the whole
link — and says how a public dataset comes in: `./gradlew convertThroughputTrace`, whose conversions
live in `build-logic`, run locally, because no dataset is vendored. The harness owns the loading
threads of the players it builds (`HarnessLoadThreads`, through core's internal engine seam) and
advances its clock only once the engine has nothing left to do at the current time, which is what
makes a session trace byte-identical run after run; `GoldenFile` is the check-or-update comparison
a golden test calls, and the mode reaches it from the convention plugin as a system property.
`superplayer-abr` holds the first of Phase 3's three components, `BandwidthOracle`: the throughput
estimator the other two will read from, and the first real code in the module. Its public face names
no Media3 type — `BandwidthOracle.Builder(context).build()`, `currentEstimate()`, `currentTransport()`,
`release()` — and its Media3 half is the internal `OracleBandwidthMeter`, the `BandwidthMeter` the
engine is built with, which reaches `EngineConfiguration`'s slot through the friend seam and also
implements core's `ThroughputSource`, so the estimate the selector reads and the one a policy is handed
are one number. It samples what the engine's propagated `TransferListener` reports — never a transfer
whose source says `isNetwork = false`, which is the cache-hit exclusion, and never one Media3 flags
as possibly throttled — and keeps one `SampleWindow` per transport in `EstimateMemory`, of which
there is **one per process** (ADR-0009 rule 8): eight samples, an arithmetic mean, a population
spread, and the 25th percentile, every constant argued where it is chosen. On a transport change the
estimate is that transport's own window or its `ColdDefaults` entry, and a window ages toward the
cold default between two and fifteen minutes (rule 9). The oracle's one platform call is the
connectivity service, read in `BandwidthOracle` and translated by core's `ConditionsBinding.kt`. Two
things worth knowing before touching it: Media3 hands its transfer listener to media loads only, so
a playlist is never a sample in any Media3 player and `BandwidthOraclePlaybackTest` counts segments;
and the `PlaybackHarness` now replays a trace's *transport* into Robolectric's connectivity shadow as
its clock crosses a stretch (`TransportReplay`), which is what lets a reseed be asserted at the
millisecond `NetworkProfile.HANDOVER_AT_MS` names.

The second component is the buffer half of the decision, recomputed on conditions.
`AdaptivePolicy.forProfile(context, profile)` is the module's entry point and returns a
`PlaybackPolicy` — the object also implements core's internal extension, which is how it installs the
oracle's meter, an `AdaptiveLoadControl` and a `DecisionTarget` without a Media3 type in any public
signature. The policy itself is `AdaptiveBufferPolicy`, public and pure: `PRD.md` §3.1's five branches
over `PlaybackConditions`, starting from the profile's static numbers and departing under a named
observation — a deep cushion on a stable, fast, unmetered link; floors raised on the coefficient of
variation with the ceiling untouched; the live profile's buffer plus a `LiveLatencyPolicy` when the
*manifest* says live; a raised after-rebuffer floor and a held bitrate ceiling for a cooldown after a
stall; and a heap-derived cap on `maxBufferMs` on every branch. Every constant carries its reason
where it is chosen. `AdaptiveLoadControl` holds not one duration: it rebuilds a `DefaultLoadControl`
through `EngineBinding.kt` when a decision arrives, and does so lazily on the engine's next poll,
because Media3's load control pins itself to the playback thread. Two things are easy to get wrong:
a hold or a raise lapses at the next *trigger* after its cooldown, never on time alone, because a
policy is consulted on triggers only (ADR-0009 rule 4); and the live half does **not** go to a
`LivePlaybackSpeedControl` — Media3 pins an ordinary live stream's speed to exactly 1× unless the
media item itself declares a range, so core lays the half into the item at adoption and replaces the
playing item in place when a re-consulted decision changes it. Under the harness a dated live window
(`TestContent.liveHls(dated = true)`) sees the player drift *ahead* of the edge, because Media3 reads
"now" from a wall clock Robolectric does not move; `TestContent.liveHls` says why that is opt-in.

The third component is the selection half, in two pieces. `AdaptiveSelectionPolicy` is the twin of
the buffer policy — public, pure, composed beside it by `AdaptivePolicy.forProfile` — and owns what
is *eligible*: the profile's own cap, narrowed by `TransportCaps`' per-profile, per-transport table
(F1's trade, real on cellular so the benchmark can show it as a bitrate loss), and the post-rebuffer
hold, on the buffer policy's cooldown so the raised floor and the held ceiling lapse together.
`NetworkAwareTrackSelection` is its Media3 half: Media3's own adaptive selection, built by
SuperPlayer's own factory (Media3 1.11's is final where it builds), overriding the one hook Media3
consults per rung per evaluation. Three refusals in order — the device (a rung the display's
shorter edge cannot show, an HDR transfer it does not list, or a codec profile and level no declared
decoder reaches, read *once* from core's `DeviceConstraints` as ADR-0009 rule 2 says and never
observed — and on a player built with `setDrm` the decoder asked is the **secure** one, a second
table `readDecoderTable` keeps on the walk it already made, because a `.secure` decoder shares its
plain sibling's MIME type and the merged answer is the plain one's; the protection signal is the
builder's call and not `Format.drmInitData`, which is what keeps it a constraint, #211), the policy's
ceiling (retargeted through the same `DecisionTarget` as the load control,
which is how a hold is honoured and why it lapses on a trigger), and the estimate discounted by its
spread on the oracle's own stable line. Startup needs no code: Media3's first choice reads the
meter, which is the per-transport memory or its cold default. The climb and descent thresholds are
a `SelectionPace` — core's public type, carried on `TrackSelectionPolicy` so that pace is decided
behind `PlaybackPolicy` like the ceiling — chosen per profile in `SelectionPaces` with a reason per
departure from Media3's. The selector reads the pace in force on every evaluation (Media3's own
thresholds are built inert, because Media3 fixes them per selection), and the composed policy
lowers the climb threshold to one segment under the buffer ceiling, because a heap-capped buffer
below it never climbs (#114). On a player with no selection factory, `EngineBinding.kt` installs a
decided pace as Media3's own adaptive factory, fixed at construction. Three things to know
before touching it: *unknown* refuses nothing, and that direction is load-bearing (Robolectric's
device reports an empty codec table and a small display); the refusals go through `canSelectFormat`
and not `isTrackExcluded`, because a ladder refused whole must fall back to its bottom rung and not
its top; and the harness declares a television-sized display by default for the same reason it
ignores the viewport, with `DeviceStatement` the way a test narrows it or declares a decoder —
before its first player is built, since the platform caches the codec list on first read. Since #266
it states a television too: `declareTelevision`, several `DisplayMode`s, a display disconnected, and
the encodings an audio output passes through. The display and the audio output are restatable
mid-test, each heard by a platform listener as one change at the moment `PlaybackHarness.scheduleDeviceChange`
names, and `docs/testing.md`'s *A TV device* says what each cannot show.

`superplayer-resilience` has the first of Phase 5, and it is the vocabulary rather than any
behaviour: `FailureClass`, the sealed taxonomy `PRD.md` §3.3 names, and `ErrorClassifier`, the single
place a failure acquires a meaning (ADR-0011 rules 1–4). Each class answers the three questions
acting on it needs — whether retrying the same bytes can help, the highest `FallbackRung` the ladder
may climb for it, and the stable name a log line and a warehouse row share — and carries the one-to-one
row that derives telemetry's coarse `FailureCategory` from it, so nothing keeps a second taxonomy.
The classifier is total and has no unknown class: core's `StaleLivePlaylistException` and
`LiveWindowTooShortException` are *mapped* by reading the fields core filled in rather than
re-derived, a refused *segment* is told from a refused manifest by the `LoadKind` stamp a player with
resilience carries, and everything else falls through to the engine's error-code band — the only
`when` over `errorCode` the repository may contain. `Fatal.Unsupported` is reached only from a code
that says *unsupported*, never from that fall-through.
The first rung is built on top of it: `Resilience.standard(headers = …)` is the module's whole public
entry point — what `setResilience` takes — and behind it the internal `StandardResilience` fills both
of core's slots per player, the load-error one with `RetryingLoadErrors` and the header-refresh one
with `TokenRefreshLayer` when a `HeaderProvider` was handed in and a pass-through when none was
(filled either way rather than left null, because a filled slot is what makes core stamp every
request with its `LoadKind`, and that stamp is what tells a refused segment from a refused manifest).
The provider is the other public type, and the only argument the module takes: a credential is the
app's to mint and no profile could stand in for it, while *when* it is refreshed is correctness and
is nobody's to vary (ADR-0011 rule 12). A request a CDN answers 401 or 403 — and no other status — is
asked once more with what the provider returns, inside the transfer that met the refusal, so the
cache, measurement and CMCD see one transfer and rung 1's budget is never spent on a credential that
was always going to be refused. What bounds it is progress: a provider that returns the headers just
refused, or throws, declines, and the refusal escalates unrepaired. `TokenRefreshPlaybackTest` forces
all of it through the harness against `FaultScript.expireTokenAtSegment(…, refreshable = true)`, with
the retry budget set to nothing wherever a repair is expected — so a session that heals can only have
healed before a retry. Every rung a *load error* can reach is inside `FallbackLadder`, which holds
one hook per rung and is the *one* place ADR-0011 rule 7's order is imposed — Media3 asks a chunk
source's policy for a fallback **before** it asks for a retry delay, which is the ladder upside down,
so both answers come out of one climb. Three are built: `RetrySameUrl`, `NextHost` and
`ExcludeVariant`, answering a backoff, a `FALLBACK_TYPE_LOCATION` selection and a
`FALLBACK_TYPE_TRACK` one; rungs 4 to 6 are not a load's to perform and begin where a climb escalates
out of the top. **Rung 4 is built and is split across the boundary ADR-0011 rule 5 draws**: `NextSource`
decides — the class's ceiling, and a `Device.DecoderTransient` refused so it reaches rung 5 without
downloading a second manifest — and core *performs*, because opening the next entry of
`MediaRequest.sources` is a re-adoption and only the facade can make one. It reaches core through a
third slot, `EngineConfiguration.playerStateRungs`, the one that faces the other way: core asks it when
Media3 surfaces a failure to the player, which is itself the proof that rungs 1 to 3 declined or were
spent. The re-adoption is deliberately *not* `adopt` — the identity, the measurement session and the
CMCD `sid` are the ones the viewing started with, so a fallback stays one session and one cache key —
and the held position is carried into `setMediaItem` explicitly rather than read back off the request's
`StartPosition` (rule 9), with the remembered-position map updated as `ResumeFromLastKnown` would have
it. A failure a rung repairs is also withheld from the consumer's listeners, because rule 10 makes the
delivered error rung 6's; that is the one callback the reflective listener wrapper may swallow. A
second source is typically a second *protocol* — `PRD.md` §2.2's DASH falling back to HLS — which is
what `NextSourcePlaybackTest` forces over one transport serving both streams
(`TestContent.alsoServing`), and a player built without the module opens the first source and nothing
else. **Rung 5 is a second question on that same slot rather than a fourth one** (ADR-0011 rule 13's
addendum): `RecreateDecoder` decides — one class, `Device.DecoderTransient`, its sibling
`DecoderInit` refused because no decoder was there to lose — and `PlayerStateLadder` is the pair of
rung decisions core is handed. Core performs it by *re-preparing* the player and doing nothing else:
a failed Media3 player keeps its playlist and position in `STATE_IDLE` and `prepare()` retries from
there, so the identity, the session and the position are preserved by nothing touching them, and
SuperPlayer adds no seek around it. Core asks rung 4 first and rung 5 only where it declined, which
is the order and costs a transient decoder failure nothing. What is core's alone is the **bound**,
`SuperPlayer.MAX_DECODER_RECREATIONS` — two recreations at one position, the count starting over
wherever playback advanced — because a decoder that will not come back must reach rung 6 rather than
loop. `SuperPlayerDecoderRecreationTest` forces that through core's own harness with a hand-written
ladder, and its KDoc states the limit plainly: **nothing here can fail a decoder**, so the
classification's half of the rung is asserted in `FallbackLadderTest` over real error codes and the
player's half against an ordinary transfer failure standing in for the decoder's. **Rung 6 is the top
and is two halves of one fact** (ADR-0011 rules 3 and 10, issues #183 and #86). A failure nothing
rescued ends the session on core's public `SuperPlayerError` — `PRD.md` §3.2's shape, a cause class, a
`userMessageKey` and `isRetryable`, plus the rungs tried, the position reached and the likely party a
`StaleLivePlaylistException` named — delivered where errors already arrive, as the `cause` of the
`PlaybackException` `onPlayerError` and `player.playerError` carry, with the engine's own exception
under it so nothing Media3 said is lost. No new listener and no callback. The other half is
`PlaybackFailure.classification`, the same stable name, which `QoeCollector` gets by asking
`player.classify(error)` rather than by keeping a taxonomy: telemetry is not a friend of core and
reaches it as a consumer does. `category` is derived from the class where there
is one (six values until #244 added `STORAGE`, ADR-0011 rule 3's addendum) — which is what moved `TelemetryEvent.SCHEMA_VERSION` to **2**, because the class and the
error-code band disagree for five kinds of failure and `docs/telemetry-schema.md`'s release note is
the table of them. `code` is still `errorCodeName`, deliberately: what the engine raised and what
SuperPlayer made of it are two facts. A player with no resilience classifies nothing, reports null,
and buckets off the band exactly as before, which `TypedErrorPlaybackTest` counts on both halves.
A ceiling caps the climb and does **not** route it: which rungs below a
class's `rungCeiling` are worth offering is the ladder's, which is how a `Device.DecoderTransient`
reaches rung 5 without trying a host and why a `Fatal.Unsupported` is offered nothing at all, and
`FallbackLadderTest` is that routing written down. Two things to know about the two upper rungs.
Media3 gives an *HLS* chunk source no location dimension, so rung 2 is DASH `BaseURL` failover
(ISO/IEC 23009-1 §5.6.4, ordered by DVB-DASH's `dvb:priority`) and an HLS failure escalates through
it to rung 3 — which is the order rather than a gap, and why `FallbackPlaybackTest` forces rung 2
through DASH and rung 3 through HLS. And rung 3 keeps ADR-0011 rule 8 by construction rather than by
arithmetic: an exclusion only narrows what is available, while the policy's ceiling is a `canSelectFormat`
refusal under `superplayer-abr` and `TrackSelectionParameters.maxVideoBitrate` without it, so no
exclusion can widen what the policy allows, and an exclusion that leaves nothing under the ceiling
escalates to rung 4 on its own. The exclusions are *time-bounded*, which is Media3's only mechanism
here and the opposite of `NetworkAwareTrackSelection.CEILING_EXCLUSION_MS`'s one millisecond: nothing
refuses a rendition that failed to load, so the duration is the whole of the mechanism. The numbers are
`PlaybackDecision.retry`, core's public `RetryPolicy`: three `RetryBudget`s, one each for manifest,
segment and licence, so one exhausted budget cannot spend another's, defaulting to Media3's own and
set per profile in `StaticProfilePolicy` with a reason per row. The licence budget is declared and
unreachable — Media3 routes a licence load through the `LoadErrorHandlingPolicy` its DRM session
manager holds rather than the media source factory's, and Phase 6 owns DRM — which is why
`RetryingLoadErrorsTest` is where it is asserted. The budget is re-read on *every* consultation
through core's internal `DecisionInForce`, a window onto `player.playbackDecision` opened before the
player exists. `Backoff` is the growth and the jitter, and the jitter is correctness rather than
policy (rule 12): equal jitter, uniform over `[base/2, base]`, argued where it is chosen.
`RetryPlaybackTest` forces every claim through the harness over real HLS and counts what left the
chain, `RetryBackoffTest` asserts the spread rather than one delay, and `FallbackPlaybackTest` forces
the two upper rungs with a **502** — a status Media3's own fallback table does not list, so a
fallback that happens is the ladder's and not Media3's, which the same file's stock-player test
counts. A fault reaches one rendition or one CDN by being addressed at its *host*
(`FaultScript`'s fourth coordinate): `TestContent.dash(mirrorHost = …)` is the same media at two
`BaseURL`s and `TestContent.hls(secondVariantHost = …)` puts the higher rendition somewhere a fault
can name it. The phase's exit criterion is three tests of its own (#184). `FaultSweepTest` plays
**every** `FaultScript` fault kind — enumerated off the builder by reflection, so a kind added later
is visibly missing — under both protocols and records in `SWEPT` how each ended: `PRD.md` Part 4 asks
that each recover or end in a named class, and each does, with `truncateAfterBytes` the one kind the
two protocols answer differently. `HostileManifestLadderTest` is the hostile corpus on a player with
the ladder, beside `HostileManifestCorpusTest`'s core-only tables and taken by the same observer
(`HostileObservation`, in testkit's main sources, because a phase 2 module may not depend on a phase 5
one) — **no row moves**, since every entry there serves its media perfectly and a ladder climbs only
when a load fails, and the one entry that neither recovers nor ends named,
`dash-availability-start-time-skew`, is recorded in `CANNOT_RECOVER` with the reason rather than left
out. `FallbackRungCoverageTest` is the register: per `FallbackRung`, the test that forces it and the
test that counts rule 14 for it, checked against the source tree so a rename or a seventh rung fails
rather than going unnoticed.

`superplayer-drm` has the first of Phase 6, and it is the tracer bullet rather than any of the
survival machinery: a protected stream plays, with the licence acquired from the server the app
named. Its whole public surface is two types — `Drm.widevine(WidevineConfig(licenceUri))`, which is
what `SuperPlayer.Builder.setDrm` takes — because **every** Media3 type DRM needs carries
`@UnstableApi`, so there is no version of its engine-facing half that could have been public. What
reaches the engine is a `DrmSessionManagerProvider` in the one DRM slot ADR-0012 rule 3 puts on
`EngineConfiguration`, filled by the internal `WidevineDrm` as core's **sixth** Kotlin friend, and
`KotlinFriendModules.kt` is where the sixth is argued and the ceiling on the count written down. Two
things about it are decisions rather than plumbing, and both are in `WidevineDrm`'s comments:
`forceDefaultLicenseUri = true`, because a licence server a *manifest* can name is one whoever served
the manifest can name, and `setPlayClearSamplesWithoutKeys(false)`, because handing a renderer samples
the session holds no keys for is the silent downgrade ADR-0012 rule 11 forbids. What a licence
travels is not the whole chain: `TransferChain` hands the slot the chain *below* the cache — the
transport with the header-refresh slot over it — because a cache has nothing true to say about an
entitlement and a credential has everything. That is what #205 built on, and with it a licence became
a load of the player's like any other: its requests are stamped `LoadKind.LICENCE`, so the
header-refresh layer repairs one refused 401 or 403 inside the transfer that met the refusal, and the
DRM slot is handed the player's **own** `LoadErrorHandlingPolicy` rather than letting the session
manager build one — which is what makes `RetryPolicy.licence` a budget something can spend, since
Media3 asks a session manager's policy about a licence and a media source factory's about everything
else. Both halves need `setResilience` beside `setDrm`, because both objects are
`superplayer-resilience`'s; every profile row now sets a licence budget with its reason.
`WidevinePlaybackTest` plays both protocols through a real `SuperPlayer` over the harness's licence
server, `LicenceLoadTest` forces the budget and the refresh through one, and `SuperPlayerDrmSeamTest`
counts ADR-0012 rule 13 in core: what it counts is the **set**, never the answer, because a provider
that is set and answers `DRM_UNSUPPORTED` is not nothing. The module still **classifies nothing**
(ADR-0012 rule 5) — `DrmFailureTest` checks that against its own source tree — and since #206 it does
not need to: every code of Media3's DRM band has a leaf of `FailureClass.Drm`, which grew
`LicenceExpired` (keys that were issued and ran out, the one protection failure with a
`userMessageKey` of its own, because `PRD.md` §3.2 asks that a dead download not say "playback
error") and `SystemError` (the device's protection stack, and the band's fall-through, so
`ERROR_CODE_DRM_UNSPECIFIED` no longer arrives calling itself a licence acquisition). No leaf reaches
rung 5: a re-prepare builds a decoder again and not a new `MediaDrm`. The category table did not
move, so `TelemetryEvent.SCHEMA_VERSION` did not either, and
`docs/telemetry-schema.md`'s *Release notes* says so explicitly along with the three
`classification` values a pipeline can now see. Since #208 the module also carries `PRD.md` §3.2's
**security-level ladder**, and its constraint is the whole of it: policy is the *server's*, and the
client never downgrades on its own authority (ADR-0012 rule 11). `SecurityLevelLadder` asks at all
only where the device has already lost — it reports `L1` and core's `DeviceConstraints` says it
declares no secure decoder, the one of `PRD.md` §3.2's three cases a player can see before spending a
licence. What it reads is `WidevineConfig.permittedSecurityLevels`, the levels *that* licence
server's operator published, and **empty is a refusal**: an app told nothing permits nothing, which
is what makes failing-hard the default and downgrading-silently unreachable. #208 built an HTTP
exchange of SuperPlayer's own for this and **#223 withdrew it** — a `GET` at a licence URI is
answered 405 by most real deployments, every failure of it read as a refusal indistinguishably, and
its answer had to be waited for on the playback thread; rule 11 always allowed configuration as the
channel, and the ADR's *Alternatives considered* now records why a level set an operator published
is not the `setAllowSecurityDowngrade` flag it rejects. A permission sets `securityLevel` on the device
(`LoweredSecurityLevel`) before any key request is composed; a refusal fills the slot with core's
`refusedSessions`, which opens nothing, asks for no licence, and carries the public
`SecurityDowngradeRefusedException` that `ErrorClassifier` maps to the seventh `FailureClass.Drm`
leaf, `DowngradeRefused`, and to the taxonomy's **seventh** message key — the content may not be
shown on this device, which is neither a licence that failed to arrive nor a device that cannot
decode. It is a mechanism beside `FallbackLadder` and **not a seventh rung**; the builder flag
ADR-0012 rejected stays rejected. What was delivered is `player.deliveredSecurityLevel`, which
`QoeCollector` reads onto `TelemetryEvent.SessionEnded.securityLevel` — an addition of shape, so
`SCHEMA_VERSION` stays 2, which the release notes say in as many words. `SecurityLevelTest` states
one device and two servers, one permitting and one silent. Since #225 the ladder also runs **after**
a failure, for the second of `PRD.md` §3.2's three cases: a device the provisioning service will not
certify at `L1` looks perfectly capable up front, so the remedy is the session graph *built again* at
the permitted level — `PlayerProtection` is the per-player object that holds the graph behind a
swappable provider, and core asks it through `EngineConfiguration.protectionRepair`, a second slot it
interrogates at failure time before it asks either player-state rung, since a request's sources are
one piece of content and a refusal at the level asked for is a refusal at every one of them. Which
failures are offered is core's (`SuperPlayerError.category`), because a predicate in the module would
be the taxonomy rule 5 forbids it; the bound is core's too, `SuperPlayer.MAX_PROTECTION_REOPENS`, and
it is one, because Widevine has a single level below `L1`. `ProvisioningDowngradeTest` drives it, with
the two controls that keep it from being "downgrade on any DRM failure": nothing permitted still ends
exactly where `DrmFailureTest` says, and a device the service certifies negotiates nothing. Which
failures reach that path is the classifier's fourth question,
`FailureClass.lowerSecurityLevelMayHelp`, carried out on `SuperPlayerError`, so a licence that expired
lowers nothing. #226 closed the third case on that same path and added **no mechanism** — a secure
surface that will not allocate is now `FailureClass.Device.SecureDecoderInit`, a `Device` leaf because
the licence was issued and what failed is an output path, told from an ordinary decoder-init failure
by a `DecoderInitializationException` carrying a `codecInfo` that is secure — not by its
`secureDecoderRequired` flag, which is also true where no decoder was *found*, a case that keeps rung
4 because another variant may supply one — and capped at rung 6 because every rung below fetches bytes
needing the same surface. **Where that claim is verified is the interesting part
and is argued in `SecureSurfaceDowngradeTest`'s KDoc**: the ladder half is forced under `check`
through a real `SuperPlayer` (`PlaybackHarness.failDecoderInitialization` arms Media3's own exception
on whichever renderer the content enabled — the synthetic streams are audio-only), and the *origin* —
a protected buffer queue a device cannot back — is reachable under neither Robolectric nor
`devicelab`, which `docs/testing.md` says rather than faking.
Since #209 the manager is built `setMultiSession(true)`, and that is a **defect repaired**
rather than a saving added: Media3's default keeps one `noMultiSessionDrmSession` for *every* format
whatever its `DrmInitData`, so a player whose protection is declared once per item-spanning manager
(rule 1) held one session across content a licence server licensed separately. Reuse is now decided
by what the content declared — equal initialization data, Media3's own rule and the conservative one,
with the security level deliberately outside the identity because it is one per player and can
separate nothing. The keepalive stays Media3's own five minutes, argued where it is chosen.
`SessionReuseTest` counts the pair that matters, one licence for a shared policy and two for separate
ones, and records the boundary: a *playlist* (`setMediaItems`) shares a session, while successive
`setMediaRequest` calls are a replacement that takes the manager's prepare count to zero and pays
again — closing that would mean deferring a `MediaDrm`'s release with nothing to bound it.
`KeyRotationTest` drives `PRD.md` §3.2's other half over `TestContent.protectedLiveHls()` and
`DeviceStatement.signalKeyRotation()`: a rotation renews inside the open session at the cost of one
licence request, and a renewal that *fails* leaves playback on the keys in force, because
`DefaultDrmSession.onError` errors a session only where it is not already keyed — so no
classification is added and #206's leaves are where they were. Since #207 a device that is not
provisioned is
**provisioned rather than refused**, and almost none of that is new machinery: Media3 posts a
provisioning request through the same `LoadErrorHandlingPolicy` as every other DRM load, so it spends
`RetryPolicy.licence` with `Backoff`'s jitter, and a service that never relents ends on
`Drm.Provisioning` at rung 6. Each round trip gets the budget whole — a budget here bounds a
*request* and never a session, so being provisioned on the third ask does not shorten the licence
request it was for. What #207 actually built is in `superplayer-testkit`: the stated device names
`FakeLicenceServer.PROVISION_URI` as its provisioning service where `FakeExoMediaDrm` named an
unanswerable placeholder, so a provisioning round trip through a real `SuperPlayer` is a transfer the
harness can count, delay and refuse at last — and `ProvisioningTest` is what that made writable.
One expectation moved with it: a device whose provisioning the service *refuses* now reaches
`provideProvisionResponse` and is `Drm.Unsupported` (6007, revoked), where it read `Drm.Provisioning`
only because the session used to fail on the address first.
Since #210 the module also holds the **offline licence lifecycle** `PRD.md` Part 4 names — acquire,
play offline, renew, release — over Media3's `OfflineLicenseHelper` and behind types that name none
of it. `OfflineLicences.store(directory)` opens an `OfflineLicenceStore` in a directory the consumer
named (ADR-0012 rule 8, ADR-0010 rule 1's shape taken again): one database file inside it, holding a
key-set id and two deadlines per `contentId` and never the keys, which are the device's. Reading is
free and writing is not — `licenceFor(contentId)` answers both expiries with no player, no device and
no network, which is ADR-0012 rule 9's "before playback rather than after it fails" and therefore
usable on a list screen, while the three verbs that talk to the server are `store.over(player)`'s,
because a licence exchange travels the *player's* chain (rule 2): the app's credential, the
`RetryPolicy.licence` budget and the `LoadKind.LICENCE` stamp are all facts about a player, and a
store with an HTTP stack of its own would answer to none of them. Playing offline is the fourth verb
and is not a call at all: `Drm.widevine(config, licence)` takes the value the store answered with and
sets `MODE_PLAYBACK` with its key-set id, so every session restores rather than asks — which
`OfflineLicenceTest` asserts as a count of **zero** licence requests with the entitlement server off
the network, never as "it played". A licence a consumer hands over that has already **expired is
refused** rather than quietly re-acquired (core's `expiredLicenceSessions` and the public
`OfflineLicenceExpiredException`, which `ErrorClassifier` reaches as `Drm.LicenceExpired` off the
error code it carries): Media3's own answer is to go to the server, which on a player built to play
offline is a round trip that was not to happen and, with no network, reads as a licence that failed
to arrive instead of as the licence that died. **Renewal is reported and never scheduled** — ADR-0012
rule 10 — so `OfflineLicence.renewalDue` is a reading on a stated threshold, `RENEWAL_DUE_WITHIN_MS`,
a day, argued against the coarsest job a consumer plausibly runs rather than against the licence. What
made any of it testable is in `superplayer-testkit`: Media3's `FakeExoMediaDrm` refuses offline key
requests and throws from `restoreKeys`, so `WidevineDevice.kt` adds the device bookkeeping — a key
store on the statement rather than on one `ExoMediaDrm`, and the two durations
`DeviceStatement.declareOfflineLicence` states — around Media3's own exchange. That is also what
closed #212's declared gap: `LicenceOutcome.SERVED_FROM_OFFLINE_STORE` is now emitted and asserted in
`LicenceTelemetryTest`, and `SCHEMA_VERSION` stays **2**, because a value a pipeline was told to
expect arriving is a change of population and not of shape.

`superplayer-offline` has the first of Phase 7, the tracer bullet (#240): `Downloads.Builder(context,
cache).build()` opens a store over the `ContentKeyedCache` the consumer already opened, `enqueue` takes
the same `MediaRequest` a player adopts, `download(contentId)` and `downloads()` answer a `DownloadItem`
(a `DownloadState`, bytes and percent), a `DownloadsListener` hears each state change and each whole
percent in the order it was made, and `remove` deletes the bytes and then the pin. It is core's
**seventh** Kotlin friend and the one *built from* the seam rather than filling a slot (ADR-0013 rule 4):
what it reaches is `ContentCache.downloads`, core's internal `CacheDownloads` that `superplayer-cache`'s
`StorageDownloads` fills — the download index as a table of the cache's own database, a
`CacheDataSource.Factory` keyed by `ContentKeys.boundTo(contentId)` so a downloaded segment is the entry
a player reads, and the pin — plus `TransferChain.downloadChain`. Three things are easy to get wrong.
The **pin is taken inside the downloader** as the download begins, not on `enqueue`'s thread, because it
is a database write; nothing is written before then, which is rule 7's reason. **The cache layer now
answers a manifest** — for pinned content whose manifest it holds whole, and only then (rule 8,
`ContentKeyedCacheLayer.isDownloadedManifest`) — which is what makes `DownloadsTest`'s count of zero
possible, while streamed content still fetches every manifest. And the store **resumes its own
`DownloadManager`**, which Media3 builds paused, because the downloads run in the store whether or not a
service holds it. Since #241 a download takes
what a viewer will watch rather than the whole ladder (ADR-0013 rule 12 and its addendum). `enqueue(request,
audioLanguages, subtitleLanguages)` first reads the manifest through Media3's `DownloadHelper`, over the one
chain. It then takes one video rendition, the highest under `PlaybackDecision.download` (a
`DownloadSelectionPolicy`, the fifth half, set per profile in `StaticProfilePolicy` and chosen with
`Downloads.Builder.setProfile`, or decided by the policy `setPolicy` hands it) that the device's
decoders can play. The display is deliberately not a ceiling, because a height cap would cut every rung of
a vertical clip. It takes each declared language the
content carries (`DownloadSelection`). A declared language the content lacks is skipped, and with no
declared audio carried the audio a player would choose is taken instead. The item is `QUEUED` while the
manifest is read, and `FAILED` if it cannot be. A player of the download is narrowed to the same tracks:
core's `StampingMediaSourceFactory` lays the download's stream keys onto the item
(`CacheDownloads.downloadedTracks`). Without that, the device's language would select a track that is not
on disk. `DownloadSelectionTest` counts requests per rendition and per language on
`TestContent.dashWithChoice`, and `DownloadEnvironment.renderersFactory` is why a harness download can
choose at all. Since #242 a download survives its process and its network (rule 9's addendum). A store
reopened over a directory a dead process left resumes the unfinished items and fetches only what the cache
lacks. A store built with `Downloads.Builder.setResilience` stops an item whose network went away
(`DownloadStopReason.NETWORK_LOST`) and resumes it on a jittered, doubling wait. It fails anything else
named, as `DownloadItem.failure`. Core's internal `DownloadResilienceExtension` is what it asks, and
`TransferChain.downloadChain` stamps each request's `LoadKind`, so a lost segment is not mistaken for a lost
network. Two things are easy to get wrong. A downloading state is announced by the downloader's first
progress report and never by the state change, whose bytes are the index's stale ones. And
`DownloadResumptionTest` moves Robolectric's `SystemClock` by hand while it waits for a resumption.
`harness.loseNetwork(environment)` is the stretch. Since #254 a store with a resilience spends the store
policy's retry budgets inside its downloader (rule 14's addendum). A lost network is one where no server
answered, so a 5xx spends the budget and then fails named. `PinningDownloader` asks Media3's downloader again
after a `Backoff` wait posted on the store's thread, never a sleep, and the item stays `DOWNLOADING`. The
manager's `minRetryCount` is zero on such a store and Media3's default without one. A manifest spends the
manifest budget and anything else the segment budget. The resilience's `HeaderProvider` repairs a refused 401
or 403 through a layer `TransferChain.downloadChain` composes. `DownloadRetryBudgetTest` moves the store's
clock to let a retry happen. Since #260 a licence exchange gets both too: `DownloadResilienceExtension.downloadLicenceErrors`
is a player's licence policy over the store's decision, handed to the session manager in `LicenceContext.loadErrors`,
and `TransferChain.downloadLicenceChain` composes the store's one header-refresh layer. Media3 waits between licence
asks on the session's request thread, so `DownloadLicenceBudgetTest` moves `SystemClock` too. Since #243 a download runs only on an unmetered network, a battery not low and storage not low
(rules 10 and 11 and their addendum). A condition that does not hold is a `STOPPED` item naming it on
`DownloadStopReason`, and nothing is fetched, the manifest included: an item enqueued while held waits in
memory to be selected. The network and storage are the manager's Media3 `Requirements`; the battery and Data
Saver are `DownloadConditions`, which pause the manager and are registered only while a download is pending.
`Downloads.meteredNetworksAllowed` is the viewer's relaxation, and Data Saver still holds it on a metered
network. `DownloadSchedule` keeps one unique, persisted `WorkManager` work per process under the same
constraints, retried while anything is pending and cancelled once nothing is. With no store open it starts the
app's service, which opens one. `DownloadConditionsTest` drives it through `DeviceStatement` and
`runScheduledWork()`. Its lapse tests hold one segment on a latency so the lapse lands between segments,
because a synthetic download otherwise asks for every segment before a stop arrives. Since #244 a full disk
fails the one item that met it, at once and on every store, while the rest of the queue carries on (rule 9's
addendum). The cache's download half asks `StatFs` before it writes and raises core's `StorageFullException`,
which `ErrorClassifier` names `FailureClass.Storage.Full` — its own branch, a seventh `FailureCategory`
(`STORAGE`) and an eighth message key, with `SCHEMA_VERSION` still 2. The failed item keeps its bytes pinned for
a later enqueue. `DownloadStorageFullTest` states the disk with `DeviceStatement.declareStorageFree`. Two things
are easy to get wrong. Robolectric's `StatFs` describes no volume, which refuses nothing. Every download test
calls `useScheduledWork()`, because an earlier class's installation is what made them pass together. And a
harness download's segment loads run on the download's own thread, so two items in one store are independent. Since
#245 a protected download carries its offline licence (rule 13 and its addendum). `Downloads.Builder.setDrm(drm,
licences)` takes a `PlaybackDrm` and core's new `LicenceStore`, which `OfflineLicenceStore` extends, so the module
still depends on core alone. After the manifest read and before the manager holds the item, the licence for the
selected protected format is acquired on a thread of the store's own, through core's `DownloadDrmExtension`. A
refused licence fails the item typed, with nothing pinned. `DownloadItem.licence` reports expiry and renewal due and
nothing renews. `remove` deletes the bytes at once and releases the licence once the store's network requirement
holds, and until then `OfflineLicenceStore` lists it in `contentIdsAwaitingRelease` and gives it to no player. The
trap: protection is what the manifest read sees, so HLS needs `EXT-X-SESSION-KEY`, and the synthetic HLS stream
downloads with no licence. `DownloadLicenceTest` drives it all. Since #251 live content is refused at enqueue
(rule 8 and its addendum). The refusal is Media3's `DownloadHelper`'s own `LiveContentUnsupportedException`,
raised on the manifest read that chooses tracks, before any period is prepared. The store reports it as
`DownloadItem.refusal` (`DownloadRefusal.LIVE_CONTENT`) on a `FAILED` item, on every store. A refusal is not a
failure, so it has no `FailureClass` and `failure` stays null. `DownloadLiveRefusalTest` counts no media and
no pin over `TestContent.liveHls()` and `TestContent.liveDash()`, the latter the corpus's healthy dynamic MPD.
Since #246 a download outlives its screen in the app's `DownloadsService` (rule 3 and its addendum), a public
abstract `android.app.Service` naming no Media3 type. The app subclasses it, overrides `onDownloads()` and the
notification's channel name and icon, names it with `Downloads.Builder.setService`, and declares the
`<service>` (`dataSync`) and the permissions itself. The store starts it whenever a download can run, meaning
pending and held by no condition, and it stops once none can. The scheduled work carries the class name in its
input data and starts it in a process with no store open. `DownloadsServiceTest` drives it through
`Robolectric.buildService`. A start from the background the platform refuses is caught and left to the schedule,
which nothing under `check` can show. The demo's `DownloadsScreen` is the recipe, and its KDoc is the lifecycle
a consumer copies. `DownloadsPayNothingTest` counts rule 15 as platform registrations and the download index table,
and rule 3 as a manifest declaring no service and no foreground-service or notification permission.

`superplayer-tv` has the first of Phase 8, the tracer bullet (#268): frame-rate matching.
`TvOutput.standard(context)` returns core's public `PlaybackOutput`, which `SuperPlayer.Builder.setOutput`
and `PlayerPool.Builder.setOutput` take. It is core's **eighth** Kotlin friend and fills one slot,
`EngineConfiguration.videoOutput`, with a per-player `VideoOutputBinding` (ADR-0014 rule 3). A filled slot
makes core build the engine with `VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF` and attach `VideoOutputAttachment`,
which tells the binding every surface the facade is handed (the video-surface calls are overridden for it,
a `SurfaceHolder`'s callbacks included, a `TextureView` passing null) and the first selected video format's
declared frame rate once per item. The binding, `FrameRateMatching`, asks `Surface.setFrameRate(…,
CHANGE_FRAME_RATE_ALWAYS)` on API 31 and later and the two-argument form on 30. It asks only where the
default display's active mode is not already a whole multiple of the rate and a mode at the same size
is, with 23.976 pairing with 24, and it withdraws with a rate of zero. That is rule 4's #268 addendum.
Two things are easy to get wrong. The synthetic streams are audio, so the content is described video
whose `TestContent.Rung.frameRate` a test sets. And the observation stops at the request:
`harness.frameRateRequests(player)` reads what was asked of each surface the harness gave, and
Robolectric's active mode never moves. `FrameRateMatchingTest` drives it on
`DeviceStatement.declareDisplayModes`, and `SuperPlayerOutputSeamTest` counts rule 14.
Since #269 a filled slot also registers core's `DisplayWatch`, a `DisplayListener` on the application
looper (rule 5). The display left `DeviceConstraints` for `DisplayCapability` (the active mode's short
edge and `HdrType`s, translated in `ConditionsBinding.kt`). Every player reads it once into
`EngineConfiguration.displayInForce`, which `NetworkAwareTrackSelection.Gate` reads per evaluation, and a
pool shares the first player's window. A differing reading is written there, re-selects through
`ReselectingTrackSelector` (Media3's selector with `invalidate` exposed), and hands the binding the frame
rate again. A refresh-rate change alone and a removed display are not readings. It is **not** a
`PlaybackConditions` observation and fires no `DecisionChanged`: rules 9 and 11 reserve
`DISPLAY_CHANGED` for the first policy that reads the display. Three things are easy to get wrong.
Media3 1.11 reads the display's mode size on every video selection rather than storing a viewport, so
rule 5's viewport rewrite has nothing to do (its #269 addendum). Media3 keeps a re-selection equal to
the old one, so the selection re-decides its device refusals per reading rather than once per
instance. And `EstimateMemory` forgets a sample timed after now, which is what lets a test class
outside `superplayer-abr` build adaptive players across tests. `DisplayHotplugTest` drives it with
`scheduleDeviceChange`, on a ladder whose `TestContent.Rung.hdr` rung is HDR10.
Since #270 a filled slot also switches on the selector's
`allowInvalidateSelectionsOnRendererCapabilitiesChange`, so an AV receiver powered on or off re-selects the
audio at the position reached (rule 6). The mechanism is Media3's own, and nothing of the module's watches the
output. An output refusing a passthrough track mid-change (`ERROR_CODE_AUDIO_TRACK_INIT_FAILED` over a
non-PCM `AudioSink.InitializationException.format`) is `Device.DecoderTransient`, so rung 5's re-prepare
selects PCM, and PCM keeps `Device.DecoderInit`. That is rule 6's addendum. The trap: the harness's audio
renderer is Media3's fake, so it answers for passthrough-only formats from the stated output through Media3's
own `AudioCapabilitiesReceiver`, and a sink refusal cannot arise there. `AudioCapabilityChangeTest` drives it
on `TestContent.dashWithPassthroughAudio` (stereo AAC beside 5.1 AC-3), and the classification is asserted
over the real exception in `ErrorClassifierTest`.
Since #271 a filled slot also lays the construction decision's tunneling half, `PlaybackDecision.output`
(`OutputPolicy`, the sixth half, off by default), on the same selector through `EngineBinding.kt`. It is
laid once and never from a re-consulted decision, because toggling it re-enables both renderers (rule 7).
`TV_LEANBACK` is the one profile row that asks, and `AdaptivePolicy` carries the half unmoved. "Where
supported" is Media3's check: one video and one audio renderer, each answering `TUNNELING_SUPPORTED`, the
video's from its decoder, which for protected content is the secure one. Two things are easy to get wrong.
`playbackDecision` can say `tunneling = true` on an untunneled player. And the harness's fakes answer for no
tunneling on their own, so its video renderer answers from `DeviceStatement.declareTunnelingVideoDecoder`,
and the content is `TestContent.videoWithAudio`, since tunneling needs both tracks.
`harness.videoRendererTunneled(player)` reads the renderer's enabled configuration. `TunneledPlaybackTest`
drives it, and `DeviceStatementTest` holds the secure-decoder half.
Since #272 the module also holds the controls, `TvPlaybackControls(player, visible)`: Compose for TV, over
`tv-material`, taking any Media3 `Player`, so a stock `ExoPlayer` works. It is the one library module with
Compose, and it applies the Compose compiler plugin itself. Left and Right on the focused seek bar move a
target (`Scrub`), and the player is sought once, a second after the D-pad goes quiet, on the centre key, or
when focus leaves. A hold accelerates on held time, not on repeats. On a `SuperPlayer` a scrub also switches
the engine's scrubbing mode on (rule 12). Focus returns to the last focused control when `visible` comes
back. Two things are easy to get wrong. A composable lambda that captures nothing becomes a public
`ComposableSingletons` class with hashed names, which `docs/api-surface.md` explains. And the scrub tests
step Compose's clock by hand, because the commit is a timeout. `TvPlaybackControlsTest` is one of the
library's two Compose UI tests — the other is #294's `DebugHudTest` — and `ScrubTest` holds the curve.

`superplayer-diagnostics` has the first of Phase 9, the tracer bullet (#286): the doctor names one
pathology. `MediaSourceDoctor.Builder(context).build()` takes the cache and the resilience the players of
this content are built with, and `examine(request)` takes the same `MediaRequest` a player adopts and
answers a `DiagnosticReport` — a list of `Finding`s, each a `Pathology` (its stable id, the `// spec:`
citation and the plain-language cause, all three the corpus's words), a `FindingSeverity` and a magnitude
where the defect has one. It is core's **ninth** Kotlin friend and the first bounded by ADR-0015 rule 3's
**closed list** of seams rather than by a slot — three as the ADR first decided and four since #289 — of
which the first is `TransferChain.diagnosticChain`; a fifth needs that rule amended by name. The fetch travels the
chain a *player* of that request would load through — the transport, the header-refresh layer a resilience
fills and the cache slot, every request stamped under that content's identity and as the kind it is
(`LoadKind.MANIFEST`, bar #289's one headers-only segment probe) — and
deliberately not the two live layers, the load-error policy, CMCD or a meter, which
`TransferChain.diagnosticChain`'s KDoc argues one at a time. It parses with the parsers the engine would
run, so the doctor and the player never disagree about what a manifest *says*. A manifest the chain refuses
is a `MANIFEST_UNREACHABLE` finding carrying the status rather than an exception, because "the doctor threw"
is the least useful thing a support ticket can say. A **pathology is not a failure**: a finding carries no
`FailureClass` and this module classifies nothing. `MediaSourceDoctorTest` drives that shape over
`PlaybackHarness.diagnosticEnvironment` — `DownloadEnvironment`'s twin, carrying a transport and nothing
else — with the healthy stream as its own control, and `DiagnosticsPayNothingTest` counts rule 13.
Since #287 it names **every HLS entry of the corpus but `hls-cached-live-playlist`**, whose defect is a
response header rather than a document and which was #289's; the rules are `HlsPathologies`, one function per
defect with its threshold and its reason beside it, while `ManifestExamination` owns the fetching alone: a
ladder gap, an overstated rung, an audio group declared as a format its segments cannot carry, a variant
pointing at a group that is not there, ragged segment durations and a splice with nothing to place its
timeline. A doctor now fetches **every media playlist the multivariant one names**, because durations and
splices live there and a defect in a rung nobody started on is one a climbing viewer meets; all of it is
manifests, so a preflight still costs no media. **A graded defect's severity is a reading of its
magnitude** — the corpus's `BENIGN` is not a finding at all, its `BORDERLINE` is `ADVISORY` and its `SEVERE`
`DEGRADED` — which is what `FindingSeverity` is per finding for, and `Finding.magnitude` stays a `String`
because nothing branches on it. The one `BLOCKING` HLS finding is the dangling audio group, and it is
blocking because the document is malformed rather than because a number is large. Every constant carries its
source or its derivation where it is chosen: Apple TN2224's 2× ladder step and DASH-IF IOP's ±50% segment
tolerance are published bounds, the escalation thresholds are derived, and the bitrate ceiling is AAC's own
bit reservoir (ISO/IEC 14496-3) — which is why a 48× ladder's top rung is named twice, both readings being
true of the playlist and #290's register having to expect two. `HlsPathologyTest` is the
vocabulary, with the `BENIGN` grades and a healthy two-rung ladder as false-positive controls in methods of
their own.
Since #288 it names **every DASH entry of the corpus**, and with them the live-window half `PRD.md` §3.6
calls the cause of a surprising share of "live stream freezes after 30 s" tickets. The rules are
`DashPathologies`, and the three defects an MPD has in a multivariant playlist's words — a ladder gap, an
overstated rung, a rung with no codec string — moved to `LadderPathologies`, judged over the Media3
`Format`s either parser built, so the number separating advisory from degraded is one number rather than one
per protocol; a DASH ladder is asked per `AdaptationSet` of every Period. What is DASH's own is a ladder that
changes at a Period boundary — graded by how far the re-selection can *jump*, on the static ladder's own
thresholds, with a boundary that changes no rung reported as nothing — and the two live defects. A
`timeShiftBufferDepth` too short is **core's judgement asked rather than restated**: `LiveWindowDepthCheck`
grew `tooShort(uri, manifest)`, ADR-0015 rule 3's third seam, and the doctor turns the
`LiveWindowTooShortException` it answers into a finding's words instead of throwing it, so the doctor and a
player of the same stream cannot disagree about what "too short" is (rule 6). It is blocking and
**ungraded**, because that judgement is a yes or a no and grading it would be a second threshold. An absent
`timeShiftBufferDepth` is a separate finding, since core's judgement declines an unlimited window. A skewed
`availabilityStartTime` is read against the manifest's **own** clock — its `UTCTiming` direct value, else
`@publishTime`, never the device's — and reported with its direction and its amount; the cost, stated in the
rule rather than papered over, is that a skew smaller than the time the stream has been on air is invisible,
because such an MPD is character for character a healthy stream that started later. `dash-ladder-gap` is
named twice for its HLS twin's reason. `dash-availability-start-time-skew` is **named and still not
repaired**: `HostileManifestLadderTest`'s `CANNOT_RECOVER` record is untouched, because nothing about that
entry fails to load and no rung is ever offered — the difference between naming a defect and rescuing one is
what this phase is for. `DashPathologyTest` is the vocabulary, with the `BENIGN` grades, the healthy dynamic
manifest (`HostileManifests.dashLiveBaseline()`, the control no other control can be) and the healthy static
one in methods of their own.
Since #289 it names the three **delivery** pathologies, the defects that are in no document at all: a
`Cache-Control` the playlist and its segments disagree about, a token scoped to the manifest and not to its
segments or expiring inside the content, and a CORS configuration that refuses a credentialed request. The
rules are `DeliveryPathologies`, and what makes them possible is that ADR-0015 rule 7 has the doctor fetch
over the chain a player would load through — a header is a fact about a transfer, so none of the three could
have been found by a parse, which `DeliveryPathologyTest` shows by diagnosing one entry's bytes twice, with
its declared headers served and withheld (`TestContent.servedWithResponseHeaders(...)`, which also serves
a *correct* configuration on healthy content, the control that matters more). Two decisions are
this ticket's. The cache rule asks **core's own judgement** — `LivePlaylistRevalidation` grew
`cachedPastTheUpdateBoundSeconds`, ADR-0015 rule 3's **fourth** seam, added by amending that rule — and then
names *which* of the two responses is wrong, always the playlist, because a segment is immutable and a live
playlist is the one document that changes. Naming the other party needs the other party, so rule 7 gained a
**#289 addendum**: the doctor may open **one** segment's transfer for its headers and close it without
reading a byte, asking for one byte so it is a request rather than a download, and only a rule that already
has a defect to attribute may spend it — a correctly delivered stream still costs playlists alone, which
`namingTheWrongSideCostsOneSegmentsHeadersAndNoMedia` counts. The token rules read a lowercase `expires`
query parameter (RFC 3986 §3.4) — a signing scheme is nobody's standard, so the derivation and the gap it
leaves are both written down where the constant is: a URL signed under another spelling is invisible to
both rules, and saying so is what keeps "it is what everyone does" out of a citation. The lifetime is
graded against the content's own duration, doubled for the pauses a viewer takes. The CORS rule reports **one** configuration, a wildcard allowed
origin beside allowed credentials (WHATWG Fetch §3.3.5, §4.10), which the protocol refuses by construction
and which is therefore the one reading that needs no knowledge of who is asking; an origin silent about CORS
is not a misconfiguration, which is what keeps the rule off every native-only CDN. The corpus gained
`hls-token-scoped-to-manifest`, `hls-token-expiring-in-window` and `hls-cors-refuses-credentials` — HLS
only, because a defect of a transfer is protocol-independent and a DASH twin would be a second copy of one
fact — and all three play to the end on every recorded player, which is the honest record: a `FakeDataSet`
cannot refuse an unsigned URI and no native player reads a CORS header.
Since #290 the phase has its exit criterion as a test: `CorpusRegisterTest` is the register ADR-0015
rule 12 asks for, in `FallbackRungCoverageTest`'s shape — per corpus entry, the findings the doctor
reports at `SEVERE` with the severity each carries, and the test that forces them, checked against
`HostileManifests.graded()` rather than a list of its own, so a nineteenth pathology fails it with a
message saying what to add. Both halves are one file, because a doctor that flags everything scores
perfectly on the first: every `BENIGN` grade and each healthy stream is asserted silent **per entry**
rather than in aggregate, and each `SEVERE` reading is an equality, so an unexpected finding on
hostile content fails too. Three entries read more than one way and the register is where that is
visible — `hls-ladder-gap` and `dash-ladder-gap` also overstate their top rung, and
`dash-mid-stream-ladder-change` carries the gap and the overstatement inside its second Period. One
row carries a caveat rather than a clean pass: `dash-availability-start-time-skew`'s `BENIGN` is no
false-positive control, because below `SEVERE` its anchor is in the past and the MPD is a healthy
stream that started later. The corpus and ladder recordings are untouched — what a player does with
an entry and what the doctor says about it are two readings (rule 4). Scoring the vocabulary from the
other side found `MANIFEST_UNREADABLE` forced by no test, which `MediaSourceDoctorTest` now does.
Since #291 the doctor also answers the **postmortem** half of `PRD.md` §3.6, and it is one argument rather
than a second entry point (ADR-0015 rule 8): `examine(request, player.classify(error))` answers the same
`DiagnosticReport` with `classification` populated — the `SuperPlayerError` the session ended on, **read**
and never re-derived, since this module depends on no classifier and holds no taxonomy (rules 1 and 5). The
two vocabularies stay apart: a `FailureClass` says what to do about a session that ended, a `Pathology` what
is wrong with a stream, nothing maps either onto the other, and the findings are the ones the same request
answers as a preflight. **Disagreement is the information**, so all four combinations are reachable and
`PostmortemTest` holds a table of them: a short time-shift window is a `Content.ManifestInvalid` beside the
named defect and its citation; a benign stream whose segments a CDN refused is a class and no finding; an
unclimbable ladder that plays to the end is findings and no class; and a player with no resilience module
classifies nothing and still gets the findings, which is ADR-0011 rule 14 surfacing rather than a new rule.
`dash-availability-start-time-skew` is the shape the phase exists for — no load fails, so no rung is offered
and there is nothing to classify, and the doctor names it anyway; `CANNOT_RECOVER` is untouched.
Since #292 the module also holds the **session trace bundle**, `PRD.md` §3.6's one exportable artifact:
`SessionBundle.Builder(context).setTrace(recorder.trace()).setPlayer(player).build().format()` is the
whole of it, and **`docs/session-bundle.md` is the format**, written for a reader who has never seen this
repository. It is the trace one layer richer and not a second grammar (ADR-0015 rule 9): the same
`+<ms> <kind> <fields>` lines, ordered and redacted the same way, under a header naming the artifact and
both format versions — its own, **1**, and the trace grammar's, still **1**, because a kind added and a
field appended are shape. What the module adds is the one kind no recorder of a session can hold, the
`capability` snapshot, and it is the phase's one deliberate exposure: core reads it whole through rule 3's
**second seam** (`capabilitySnapshotOf`, a `CapabilitySnapshot` of the video decoder table, the display,
the heap budget and low-RAM reading, and the API level) so that a field the bundle could print is a field
rule 10 admitted, and the protection half is `player.deliveredSecurityLevel`, read as `QoeCollector` reads
it because rule 3 keeps it off that list. Two capabilities rule 10 would permit are **refused** with the
rule's own readership test: the audio output's encodings, which nothing in this library reads (Media3's
own receiver does), and the security level the *device* reports, which is `superplayer-drm`'s to ask. The
seventh redaction rule is stated in `SessionTraceRecorder`'s KDoc beside the six, and
`SessionBundleRedactionTest` is the ticket's centre — one session that really carried signed URLs, a
credential in a header, a licence, a session id on the wire and a failure whose message names the host,
every one asserted present before it is asserted absent, beside the `Build` strings and decoder component
names a stated codec table makes leakable. `SessionBundleGoldenTraceTest` pins the format in this module's
own `src/test/golden/`, regenerated by `./gradlew updateGoldenTraces` and by nothing else.
Since #294 it also holds the **debug HUD**, which makes this the second library module with Compose
(ADR-0015 rule 11): `DebugHud(player, telemetry)` is an overlay the *app* places over its own surface,
six rows — the source of the numbers, the rendition, the buffer, the dropped frames, **the estimate
beside the selected rung and the policy ceiling**, and the session's recent failures. It takes any
Media3 `Player` (rule 2's one named exception) and **degrades rather than refusing**: a stock
`ExoPlayer` answers the buffer, the position and its own `playerError` and reads `unavailable` on the
rest. It **opens no seam** — the telemetry vocabulary through `DebugHudTelemetry`, a public
`TelemetrySink` the app composes into its own so the overlay and the warehouse read one derivation;
`player.playbackDecision` for the ceiling; and the `Player`'s own state, polled twice a second because
three rows move with no event. The estimate is the field #294 added, `PlaybackStateSampled.throughputEstimateBps`
— shape and not meaning, so `SCHEMA_VERSION` stays **2**, which `docs/telemetry-schema.md`'s release
notes say in as many words, along with why it is on the sample beside `videoBitrateBps` rather than an
event of its own. The guard is `ApplicationInfo.FLAG_DEBUGGABLE`, argued in `isDebugHudAvailable`'s
KDoc together with what it does **not** guarantee: the code is still in a release APK, since R8 cannot
drop a composable the app calls, and a `debuggable` release build shows it. Three things are easy to
get wrong. Media3's own meter reports on thresholds a synthetic segment never reaches, so the estimate
is null under the harness on a plain player and reachable only on a shaped network through
`AdaptivePolicy`, whose meter samples every transfer — which is why `BandwidthOraclePlaybackTest` is
where the field's emission is asserted and `QoeMetricsTest` asserts the null. A `const val` in an
`internal` companion still reaches the tracked API surface. And the module's *existing* types each
gained a `$stable` line the moment the Compose compiler was applied to it (`docs/api-surface.md`).
The selected rung is the **player's** own current track and not telemetry's, which is rule 11's own
sourcing and is what leaves the estimate as the one half of that row telemetry alone can answer.
`DebugHudTest` is the library's second Compose UI test, with the stock player as its degradation control
and a non-debuggable application as the guard's; `docs/testing.md`'s *The debug HUD* says what it can and
cannot show, including the one assertion that reads past the public API. The demo's `DebugHudScreen` is
the recipe a consumer copies.
Since #295 the phase's **docs half** is written, and `PRD.md` Part 4 asks for it by name ("Diagnostics
+ docs") rather than leaving it to be assumed. Three documents, each for a reader with no copy of this
repository. `docs/media-source-doctor.md` is the doctor's manual: all twenty defects — the eighteen
corpus pathologies and the two of the fetch — each with what it means, what produces it, what to change
on the packager or the CDN, and its citation, plus the table of which thresholds are *published* bounds
(TN2224's step, DASH-IF's segment tolerance, AAC's bit reservoir) and which are derived, said as
honestly as the source says it; it also states what the doctor cannot see, gap by gap, and that it
**names defects and repairs nothing**. `docs/reading-a-session-bundle.md` is the prose guide *on top of*
`docs/session-bundle.md` rather than a second description of the grammar: what the timings are measured
between (`+<ms>` from the recorder attaching, never the viewer's tap), three readings that answer most
tickets, and a table of what a bundle cannot say beside what to ask for instead. And `docs/adr/README.md`
is the ADR index the set has gone fifteen records without — one decision per line, with the reading
order and the rule that an addendum is read at the rule rather than in a summary. `docs/README.md` is
the index of the documents themselves, linked from the binding rules below, and
`DoctorDocumentTest` is the mechanical half: every `Pathology` needs a section of its own in the
doctor's document, in the enum's order, carrying **that section's** own citation verbatim — read per
section rather than over the file, because four pathologies cite RFC 8216 §4.3.4.2 — so a
twenty-first defect fails the build rather than going undocumented. `docs/testing.md`'s *Adding a
pathology* gains the step. Two things the code and the earlier units' prose disagree about, with the
code right: there are **four** HLS delivery pathologies scored against corpus entries, not the three
`DeliveryPathologies`' and `HostileManifests`' KDocs still count (the fourth is #289's expiry entry);
and `overstatedBitrate` bounds an **AAC** rung specifically rather than any audio-only one, since the
ceiling is AAC's reservoir. Nothing here
claims #293's Perfetto trace points or #296's exit, both of which are still open and need a device.

Every other library module is still an empty placeholder: they exist so boundaries are fixed and
enforceable before code arrives. `superplayer-core`, `superplayer-telemetry`, `superplayer-testkit`,
`superplayer-abr`, `superplayer-cache`, `superplayer-preload`, `superplayer-resilience`,
`superplayer-drm`, `superplayer-offline`, `superplayer-tv`, `superplayer-diagnostics` and `build-logic` are the only modules with test sources. The roadmap is `PRD.md`: the problem inventory it numbers `F1`–`F8`, the module
requirements, and the phase table are what the issues are cut from.

`benchmark/` is the fourth build and the phases' exit criteria: `PRD.md` §6's fixed matrix — six
network profiles × four scenarios × four players × twenty runs — emitting a report with the raw
traces beside it. The four players are `Arm.kt`: stock `ExoPlayer` with defaults, stock plus the
buffer config an app writes from intuition, a SuperPlayer profile, and the same profile under
`superplayer-abr`'s `AdaptivePolicy`, the arm a report grades against each of the other three. Every
adaptive session starts from a cold estimate: the runner idles the clock past ADR-0009 rule 9's stale
age first, because the estimate memory is per process and internal. What makes the comparison a
comparison is that all four are built by one `PlaybackHarness` over one shaped transport, and that
every metric is reduced by one `SessionMetrics` (now `superplayer-telemetry`'s) from core's own `TelemetryEvent` vocabulary — so the
definitions are shared by construction rather than by care. Arms (a) and (b) have no SuperPlayer to
attach `QoeCollector` to, so `StockTelemetry` mirrors it callback for callback, and
`StockTelemetryAgreementTest` attaches **both collectors to one `ExoPlayer`** and asserts they derive
the same events; delete that test and the benchmark's central claim goes with it. `PRD.md` §6's
honesty rules are enforced where the numbers are made rather than where the table is printed —
`Distribution` has no mean without a spread, `ReportWriter` prints losses before wins and has no
filter, and a difference inside two standard errors is neutral rather than a small win.
`benchmark/README.md` is the manual, including why it sits outside `docs/testing.md`'s rules rather
than against them. `benchmark/baseline/` is the frozen Phase 1 report Phase 3 was graded against, and
`benchmark/phase3/` is the Phase 3 report (issue #103) — the current reference, the one
`bench --baseline` writes, and where Phase 11 starts from. Its opening section is
`ExitCriterion`'s verdict: adaptive against the static profile, a QoE win and no QoE loss on each
shaped profile, and nothing worse on stable WiFi, by the two-standard-error rule. **That verdict is
Phase 11's exit criterion, not Phase 3's**: `PRD.md` Part 4 puts every "a number moved" criterion —
this one, the feed demo's p50 TTFF target, peak RSS and battery — in a tuning phase after the
functional ones, so a "Not met" is a finding to park there rather than work that blocks the next
phase. Peak
RSS and battery need a device: `BenchmarkActivity` is that arm's app and the harness around it is
not built, which the README and the report both say rather than leaving a reader to assume.

`PLAN.md` is an untracked, local-only scratch draft that `PRD.md` supersedes. **Do not read it, cite
it, or copy from it** — it names third parties and framings that must not reach a tracked file, and
where the two disagree `PRD.md` is right.

## Commands

```bash
./gradlew assemble check          # build, lint, tests, repo verification, tracked API surface, format
./gradlew updateApiSurface        # regenerate api/<module>.api after a deliberate API change
./gradlew updateGoldenTraces      # regenerate src/test/golden/*.trace after a deliberate behaviour change; run alone, then check
./gradlew spotlessApply           # reformat and stamp the Apache-2.0 header on every .kt file
./gradlew convertThroughputTrace --from=… --transport=… --input=… --output=…   # docs/throughput-traces.md
./gradlew publishToMavenLocal     # required before the demo will build
(cd demo && ./gradlew assembleDebug lintDebug spotlessCheck)
benchmark/bench                   # PRD.md §6's matrix and its report; NOT in check (benchmark/README.md)
devicelab/lab run smoke           # device run: trace, report, metadata (devicelab/README.md)
./gradlew huntLeaks                # the leak hunt on a device; NOT in check (devicelab/leak/README.md)
```

`check` is the complete definition of the library's checks. Because `build-logic` is an included
build, its tasks are invisible to the root build's aggregate tasks — its tests run only because
`check` depends on them explicitly. A new check that can be a Gradle task belongs in `check`, not
only in CI (`.github/workflows/ci.yml`), so local and CI agree on what passing means.

`spotlessApply` at the root covers **all four builds**. Spotless is configured by path rather than
by project, so the root build's `**/*.kt` reaches `demo/` and `build-logic/` too, and one invocation
formats the repository. The demo and the benchmark each apply Spotless a second time over their own
sources, which is why `spotlessCheck` is in their command lines: each has its own wrapper and CI
builds it with the root build not running at all, so each has to be able to enforce the format by
itself.

The rules are ktlint's, and they live in `.editorconfig` — including every rule switched off to
leave this codebase's style alone, each with the reason. The license header is
`config/license-header.txt`, and `./gradlew check` verifies through `verifyLicenseHeader` that it
still says what `LICENSE` says.

The demo build needs the library published first: it consumes
`com.superplayer:superplayer-core:<version>` rather than `project(":superplayer-core")`, so that an
adopter's first failures — a missing transitive dependency, a malformed POM, a wrong artifactId —
happen here instead of in someone else's app.

Building needs an Android SDK: set `ANDROID_HOME`, or write `sdk.dir` into `local.properties`
(gitignored). Gradle itself does not need installing; the checked-in wrapper fetches the pinned
version.

**The Gradle daemon runs on JDK 17**, the version CI uses and the catalog's `jvmTarget`, and the
builds enforce it rather than asking for it. This matters more than it looks: the Robolectric
runtime the tests load is pinned in `superplayer-core/src/test/resources/robolectric.properties`,
and Robolectric's runtimes each require a minimum Java version, so a daemon on a newer JDK (Android
Studio's bundled JBR, say) would run configurations that then fail in CI. The other half of that
pairing is the daemon JVM criteria in `gradle/gradle-daemon-jvm.properties`: `toolchainVersion=17`.
`demo/` and `benchmark/` carry a copy each, because each has its own wrapper. Whatever `JAVA_HOME`
the launcher starts with, Gradle runs the daemon on a local JDK 17 it finds, and fails naming the
requirement when there is none. It downloads nothing: no toolchain resolver is declared, so a
missing JDK 17 is installed by hand. Any vendor will do. CI's is Temurin, set by `setup-java` in
`.github/workflows/ci.yml` and `device-run.yml`. Moving the version means changing the three
criteria files and those two workflows together. Edit the files by hand, because
`./gradlew updateDaemonJvm` refuses to write one without a resolver. `./gradlew --version` prints
the criteria in force on its `Daemon JVM:` line.

### Running the demo on an emulator

Some claims cannot be checked by any test here: `docs/testing.md` bars the network, so whether a
change still plays real HLS and DASH over a real CDN is a question only the demo on a device answers.
There is an AVD kept for it — `superplayer_verify_36`, API 36 — alongside whatever Android Studio has
created (`emulator -list-avds`).

```bash
emulator -avd superplayer_verify_36 -no-snapshot-load -no-boot-anim &
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
./gradlew publishToMavenLocal && (cd demo && ./gradlew assembleDebug)
adb install -r -t demo/build/outputs/apk/debug/superplayer-demo-debug.apk
adb shell am start -n com.superplayer.demo/.MainActivity
```

On a television the same APK opens a Compose-for-TV screen instead (`TvScreen`, whose KDoc is the
lifecycle to copy): it is listed in the TV launcher, needs no touchscreen, and plays `TV_LEANBACK` through
`TvOutput` and `TvPlaybackControls` on a `SurfaceView`. `--es com.superplayer.demo.extra.SCREEN TV` opens it
on a phone. The TV emulator beside the phone's is `superplayer_tv_36`, and devicelab takes `--device tv`
(`devicelab/README.md`, *The television*). Nothing has booted it yet; #274's run is the first.

`devicelab/lab run smoke` is the executable version of this recipe and of every pitfall below. It
boots or adopts the device, publishes, builds the demo's `benchmark` build type, and installs it. It
refuses to go on unless the installed APK was built against the artifact it has just published. Then
it confirms playback is advancing and captures a Perfetto trace. `devicelab/README.md` is its manual.
The prose here stays because it is the explanation; the harness is the procedure, and a measurement
on a device goes through it rather than through a hand-typed copy of these lines.

Things that cost time the first time round:

- **The APK is `superplayer-demo-debug.apk`**, after the module name, not `demo-debug.apk` after the
  directory.
- **`adb devices` reports the emulator `offline` for a while after it appears.** Waiting on
  `sys.boot_completed` rather than on the device listing is what makes the install reliable.
- **The first launch raises the notification-permission dialog over the player surface.** A
  screenshot taken before it is dismissed shows a dialog and a black rectangle, which looks exactly
  like a playback failure and is not one. Dismiss it, then capture.
- **A screenshot is weak evidence on its own** — it cannot tell a rendered frame from a stalled one.
  The demo publishes a `PlaybackSession`, so the platform logs the state transitions, and a
  *position that advances between two of them* is the thing worth asserting on:

  ```bash
  adb logcat -c                       # before launching, so the window below is this run's
  adb logcat -d -t 2000 | grep -o 'state=PLAYING(3), position=[0-9]*'
  ```

  Media3 refreshes the session's position every few seconds while content plays, so `adb shell
  dumpsys media_session` shows the same thing as the current state and without a buffer to bound,
  and that is what devicelab waits on. Bound the logcat form with `-t`. An unbounded `adb logcat -d`
  against an emulator that has been up for a while dumps the whole buffer and can take minutes,
  which reads as a hang rather than as a slow command.

Re-run `publishToMavenLocal` and reinstall after any library change: the demo resolves SuperPlayer
from Maven local, so an APK built against a stale artifact will happily test the previous version.

The emulator is disposable and does exit on its own — a crash, a host sleep, an `adb` client that
takes it down with it. If `adb devices` comes back empty mid-session, boot it again and reinstall;
nothing about the verification depends on the instance surviving.

## Binding rules

Style preferences these are not. A change violating one is not accepted, whatever its merit.

The list below is the working set; [`docs/adr/README.md`](docs/adr/README.md) is the full ADR index,
one decision per record, and [`docs/README.md`](docs/README.md) indexes every document in the
repository.

- **Clean-room discipline** — `CONTRIBUTING.md`. No proprietary material, no mirroring a commercial
  SDK's API shape, and a public `// spec:` or `// ref:` citation on every non-obvious algorithm.
- **[ADR-0001](docs/adr/0001-compose-dont-fork.md)** — compose on Media3, do not fork it. Its rule 3
  puts Media3's version in `gradle/libs.versions.toml` and nowhere else, enforced mechanically by
  `./gradlew verifyNoHardcodedMedia3Versions`.
- **[ADR-0002](docs/adr/0002-no-local-http-proxy.md)** — no local HTTP proxy.
- **[ADR-0003](docs/adr/0003-implement-player-by-delegation.md)** — the facade implements `Player`
  by Kotlin delegation and extends no Media3 class. Every Media3 `Player` base class is
  `@UnstableApi`; extending one puts that marker on every method a consumer calls. Its rule 3 — a
  wrapper of a Media3 interface forwards every callback — has exactly one argued exception, at that
  rule's addendum: a failure the fallback ladder repaired (ADR-0011 rule 10).
- **[ADR-0005](docs/adr/0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md)** — all
  buffering and track-selection policy is decided behind `PlaybackPolicy`, in types that name no
  Media3 class. A policy constant at a Media3 call site, in a listener, or in a builder is a bug
  against this ADR whatever it is worth.
- **[ADR-0006](docs/adr/0006-own-the-platform-rules-and-hand-back-the-state.md)** — Android's
  lifecycle rules are on by default and are not a profile's to vary, and state that outlives a
  player travels as a `PlaybackSnapshot` the consumer stores. SuperPlayer chooses no storage on a
  consumer's behalf: no preferences, no database, no file.
- **[ADR-0007](docs/adr/0007-publish-the-player-as-a-session-and-resolve-content-by-id.md)** — a
  session is created by a consumer rather than owned by every player; a `PlaybackSession` and its
  player share one lifetime and one `release()`; content named from outside the app is resolved
  back into a `MediaRequest` rather than reinterpreted as a URL; and the service's manifest entry
  and foreground-service permissions are the app's, not the library's.
- **[ADR-0008](docs/adr/0008-measure-behind-an-engine-agnostic-sink-boundary.md)** — telemetry
  leaves the library through a sink that names no Media3 type; the sink interface and the event
  vocabulary are `superplayer-core`'s while the collector is `superplayer-telemetry`'s; delivery is
  at-most-once, bounded, and lossy under pressure, with every drop counted and reported; a sink is
  never called on a thread the engine needs; the events are one sealed, versioned hierarchy whose
  version tracks a metric's *meaning* rather than its shape; and CMCD is a separate seam joined to
  telemetry by a shared session id.
- **[ADR-0009](docs/adr/0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md)** —
  extends ADR-0005 for the adaptive policy: `PlaybackConditions` carries exactly the enumerated
  observations, each a SuperPlayer type, translated from Android and Media3 in one internal core
  file; the policy is re-consulted on named triggers only, and only where the engine can honour a
  changed decision whole, with every change a `DecisionChanged` event; `superplayer-abr` reaches
  core's engine construction as its second Kotlin friend through an extension interface on the
  policy object, and a player built without it pays nothing; and a throughput estimate is remembered
  per transport, in process memory, and nowhere else.
- **[ADR-0010](docs/adr/0010-cache-in-consumer-chosen-storage-and-preload-on-the-one-chain.md)** —
  refines ADR-0006 rule 2 for Phase 4: a content cache is storage the consumer opened, naming the
  directory and the budget, everything it writes lands inside that directory, and SuperPlayer never
  creates a cache it was not handed; preload builds its sources through the `MediaSource.Factory`
  `TransferChain` assembles, shares the chain's cache, and hands a preloaded source to a player only
  through `setMediaRequest`; `PlayerPool` owns the players and the decoder budget and
  `PreloadCoordinator` is attached to one, taking its bound from the pool rather than reading the
  device again; prefetch depth is a third half of `PlaybackDecision`, the memory guard and the
  data-saver rule are platform rules on for everyone, and cache size is the consumer's; and a
  player built with neither module registers and allocates nothing for them, which a test counts.
- **[ADR-0011](docs/adr/0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md)** —
  decides Phase 5's shape: a failure acquires a meaning in exactly one place,
  `superplayer-resilience`'s `ErrorClassifier`, whose taxonomy is public, total and names no Media3
  type, and telemetry reports that classification rather than keeping a second one; core detects
  the transfer defects a core-only consumer is exposed to and raises typed exceptions, which the
  classifier maps rather than re-derives; the ladder's six rungs are fixed in order and reached one
  at a time, and every rung resumes at the position playback had reached; retry budgets are policy,
  a fourth half of `PlaybackDecision`, while jitter, token refresh, the rung order and position
  preservation are correctness on for everyone; the Media3-facing halves are internal and reach the
  engine through three slots in core's seam, filled by the module as core's fifth Kotlin friend —
  a load-error slot and a header-refresh slot the engine is *configured* with, and, since rule 13's
  addendum, one core *interrogates* at failure time for both of the rungs that are operations on the
  player's own state — the next source and the recreated decoder, the second of them bounded by core
  because a re-prepare is the one rung nothing else bounds (rule 5's second addendum); a failure such
  a rung repairs is withheld from the consumer's listeners,
  which rule 10's addendum decides and which is the one deliberate exception to ADR-0003 rule 3;
  and a player built without it pays nothing, which a test counts.
- **[ADR-0012](docs/adr/0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md)** —
  decides Phase 6's shape: protection is declared once per player on the builder and fixed for its
  lifetime, so `MediaRequest.sources` stays a list of locations for one piece of content and
  `FailureClass.Drm.Unsupported`'s rung-4 reason is rewritten to the narrower fact that survives — a
  second source is a second container and scheme mapping, not a second entitlement; the credential
  is the app's through the one `HeaderProvider`, which moves to core so neither optional module
  depends on the other; the Media3-facing half is internal to `superplayer-drm` and reaches the
  engine through one DRM slot in core's seam, as core's sixth Kotlin friend; the module classifies
  nothing and instead raises a public core exception carrying the evidence, which `ErrorClassifier`
  maps, so no `ERROR_CODE_DRM_UNSPECIFIED` reaches a consumer alone; an offline licence lives in a
  directory the consumer named, with the playback-versus-licence expiry split readable before
  playback and renewal the consumer's to schedule; a lower security level is requested only where
  the licence server permitted it, which is a mechanism beside the fallback ladder and not a seventh
  rung; and a player built without `setDrm` allocates nothing — a provider set and answering
  `DRM_UNSUPPORTED` is not nothing — which a test counts.
- **[ADR-0013](docs/adr/0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md)** —
  decides Phase 7's shape: `superplayer-offline` depends on core alone and takes the cache, the
  protection, a core `LicenceStore` and the resilience as core types, so a clear-only app carries no
  DRM module; a download writes into the `ContentCache` the consumer opened under the keys a player
  reads, pinned from enqueue until removal, with its index in that cache's database, and travels the
  chain `TransferChain` assembles (no CMCD, no meter); the cache layer serves a manifest from disk for
  pinned content only, and live content is refused at enqueue; the public surface names no Media3
  type and the consumer's service extends `android.app.Service`, its manifest entry and permissions
  the app's; the module is core's seventh Kotlin friend, built from core's seam and filling no slot; resuming,
  stopping on a lapsed constraint, failing one item on a full disk, battery-not-low and
  storage-not-low are correctness, while the network requirement is unmetered by default and the
  viewer's to relax store-wide, Data Saver still honoured; scheduling is a `WorkManager` scheduler of
  the module's own, because Media3's requirements cannot state battery-not-low; the rendition is a
  fifth half of `PlaybackDecision` and the languages the consumer's argument; a protected download
  acquires its licence before its first media byte and releases it as part of its removal; and
  nothing that opens no download store pays for one, which a test counts.
- **[ADR-0014](docs/adr/0014-match-the-display-and-watch-it-change-behind-one-output-slot.md)** —
  decides Phase 8's shape: `superplayer-tv` depends on core alone and is core's eighth Kotlin friend,
  filling one slot, `EngineConfiguration.videoOutput`, from the `PlaybackOutput` a consumer passes to
  `setOutput`; frame-rate matching (`Surface.setFrameRate` with `CHANGE_FRAME_RATE_ALWAYS`, the
  viewer's own setting deciding) and re-selecting on a display change are correctness on every player
  built with it, as is re-selecting on an audio-capability change, which is Media3's own mechanism
  with its selector parameter switched on; tunneling is a sixth half of `PlaybackDecision`, applied
  from the construction decision only, and `TV_LEANBACK` is a core profile; the display is a live
  reading the selection gate re-reads, not yet a policy observation or a trigger because no policy
  reads it, while decoders stay a constraint read once; the only TV UI is Compose for TV and a TV
  player's surface a `SurfaceView`, with Leanback rejected; #213's `TextureView` refusal is core's,
  `FLAG_SECURE` the app's, and its display-change half this module's; and a player built without
  `setOutput` registers and allocates nothing for it, which a test counts.
- **[ADR-0015](docs/adr/0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)** —
  decides Phase 9's shape: `superplayer-diagnostics` depends on core and telemetry and is core's
  ninth Kotlin friend — built from core's seam, filling no engine slot, ADR-0013 rule 4's second
  shape — over a **closed list** of internal seams, all but the first reaching past that shape and
  admitted by name, three as first decided and four since #289, with the next reachable only by
  amending that rule in the change that reaches it;
  a **pathology is not a failure**, so a finding carries a cause, a `// spec:` citation, a severity
  and a magnitude and carries no `FailureClass`, `ErrorClassifier` stays the one place a failure
  acquires a meaning, and a postmortem prints the classification it *read* beside the findings
  without either being expressed in the other; the doctor fetches over the chain a player of that
  request would load through and parses with the parsers that player would run, reporting to no
  meter and emitting no CMCD, which is ADR-0002's argument through a different door; preflight and
  postmortem are one report and one API; the bundle is the session trace one layer richer, adding
  kinds rather than a second grammar, and its capability snapshot is admitted by a **seventh
  redaction rule** — what the device can do, never what the device is, with every `Build` string and
  every decoder component name refused; the HUD makes this the second library module to carry
  Compose, placed by the app and guarded so a release build shows nothing; the doctor is scored
  against the curated corpus with false positives counted, so a flagged `BENIGN` entry fails the
  build; and an app that opens neither doctor nor HUD registers and allocates nothing, which a test
  counts.
- **[ADR-0004](docs/adr/0004-select-the-http-stack-through-a-superplayer-type.md)** and
  **[ADR-0016](docs/adr/0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md)** —
  decide Phase 10's shape, the bottom of the chain: HTTP-stack selection is a SuperPlayer type that
  no Media3 type crosses, and what a consumer supplies is an implementation of `HttpTransport` —
  open a request, answer a status, headers and a byte stream, cancel — over whatever client their
  app already has, never a `DataSource.Factory` (ADR-0001 rule 2) and never a module of ours per
  named client, so core stays Media3-only and nothing in this repository names OkHttp, Cronet or
  Ktor. The four chains resolve their bottom at **one** composition point, and all four entry points
  that compose one take the stack, because a player loading over the app's client while its
  downloads use the platform's is the defect the seam exists to prevent. Everything above the bytes
  is adapted behind the boundary and is not the consumer's to get right: the Media3 `DataSource`,
  `TransferListener` reporting with `isNetwork` (ADR-0009 rule 8), and the
  `InvalidResponseCodeException` that rung 1's credential repair and `ErrorClassifier` both read —
  which is what makes ADR-0011 rule 1 survive a transport we did not write. Byte ranges, the
  post-redirect URI and identity content coding are contract rather than advice, carried to the
  implementer by a conformance test in `superplayer-testkit` rather than by KDoc alone; a stack that
  cannot be honoured fails at `build()` with a typed error; and a consumer who names none pays
  nothing, which a test counts.
- **[`docs/api-surface.md`](docs/api-surface.md)** — every published module's public API is tracked
  in `<module>/api/<module>.api` and validated by `check`. Changing it means running
  `./gradlew updateApiSurface` and committing the diff in the same change. A leaked `@UnstableApi`
  Media3 type fails the build rather than merely showing up in a diff.
- **[`docs/testing.md`](docs/testing.md)** — tests drive the library through its public API under
  Robolectric, against Media3's own fakes, with no device and no network. Nothing asserts past the
  facade, and `player.exoPlayer` is an escape hatch for consumers rather than a way in for tests.
  The rule binds every change, not only the one that introduced it.
- Every dependency version lives in `gradle/libs.versions.toml`; the library and demo builds read
  that one file.
- A new dependency arrives with its `THIRD_PARTY.md` row in the same change.
- A change contradicting an ADR says so explicitly and argues the case. Winning that argument
  produces a superseding ADR, not an undocumented exception.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues, managed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Exploratory search

Broad search — "where is X handled", "what else calls Y", a sweep across modules or naming
conventions — goes through the `Explore` subagent rather than direct `grep` and `Read` calls. The
excerpts stay in the subagent's context and only the conclusion comes back, which is what stops a
long session from carrying every file it ever glanced at. Treat this paragraph as the authorization:
the default is to ask before spawning an agent, and this file is asking.

Direct tools remain right for a known path, a single file, or a search whose output is already
small. A subagent starts cold and re-derives context, so it earns its cost on fan-out and loses on
one grep.

### Bounded reading

A session carries the sum of everything it has looked at, so two habits decide whether a long one
stays affordable.

**Read slices, not whole files.** `PRD.md` is ~34 KB and `docs/testing.md` ~15 KB — call it 8k and
4k tokens. Reading either end to end to check one table costs more than the conversation that
prompted the question. `grep -n` for the heading, then `sed -n '400,430p' PRD.md`, costs a fraction
of it. Read a file whole when the whole file is the subject: a build script being changed, a source
file under review. Not to answer a question about one paragraph of it.

**Bound what a command prints.** `git log`, `find`, `grep` across the tree, and Gradle all have
modes that return thousands of lines nobody reads, and every one of them lands in context in full.
Pipe through `head`, narrow with `grep -o`, cap with `-t` or `--max-count`. The `adb logcat -t`
warning under *Running the demo on an emulator* is one instance of this rule; the reason it earns
its own paragraph there is that an unbounded dump also reads as a hang.
