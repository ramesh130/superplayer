# ADR-0010: Write a content cache only into storage the consumer opened, build preload on the one chain, keep the pool the owner of players, and decide prefetch depth as policy

- **Status:** Accepted
- **Date:** 2026-09-14
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rule 2, whose
  wording is kept and whose scope is stated below; [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md)
  rule 2, and [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md)
  rules 5 and 7 — each extended by an addendum recorded at ADR-0005 rule 2 and ADR-0009 rule 7.
- **Summary:** Decides Phase 4's shape before any of its code lands (#153). A disk cache is storage
  the consumer opened — they name the directory and the budget, SuperPlayer writes only inside it
  and never creates one it was not handed — which refines ADR-0006 rule 2 rather than contradicting
  it. Preloaded sources are built through the same `MediaSource.Factory` `TransferChain` assembles
  and reach a player only through `setMediaRequest`. `PlayerPool` keeps the players and the
  decoder budget; `PreloadCoordinator` is attached to a pool, owns what is warm and the bytes it
  costs, and takes its decoder bound from the pool rather than reading the device again. Prefetch
  *depth* is a third half of `PlaybackDecision`; the memory guard and the data-saver rule are
  platform rules on for everyone; cache size is the consumer's. A player built without either
  module registers nothing and allocates nothing for them, and that is counted.

## Context

Phase 4 (`PRD.md` Part 4, #153) ships `superplayer-preload` and `superplayer-cache` to close F3, F5
and F7: instant start without a loopback proxy, and a cache keyed by content rather than by URL.
Five questions would be answered by accident if the first pull request answered them, and each has
an ADR already standing on one side of it.

**A disk cache is a file, and ADR-0006 rule 2 says no file.** The rule reads: *SuperPlayer chooses
no storage on a consumer's behalf. No `SharedPreferences`, no database, no file, no `Context`-scoped
singleton.* ADR-0009 rule 8 read it once already, for the throughput memory, and found that memory
is not storage. A content cache is storage in every sense the rule names. Media3's `SimpleCache`
writes span files into a directory and indexes them; handed a `DatabaseProvider` it keeps that
index in SQLite, and Media3's own `StandaloneDatabaseProvider` opens its database in the
*application's* database directory under a name Media3 chose. Two `SimpleCache` instances over one
directory is an error Media3 refuses (`SimpleCache.isCacheFolderLocked`), so whoever opens the
cache also owns its lifetime. `PRD.md` §2.2 sketches `setCache(CachePolicy.contentKeyed(maxBytes =
CachePolicy.deviceAware()))` — a budget, and no directory — and §3.5 asks for a device-aware size
from free space and `isLowRamDevice`, LRU eviction, and a pinned region for Phase 7's downloads.

**Media3's preload manager builds its own sources.** `DefaultPreloadManager` prepares media
sources ahead of a player and hands them over when the player asks for the item. Its builder takes
either a `MediaSource.Factory` or a bare `DataSource.Factory`, and with neither it builds Media3's
default. `TransferChain` is the one internal place the loading path is assembled — live-playlist
revalidation and the window-depth check above the transport, CMCD attached to the
`MediaSource.Factory` above the chain, and the cache slot `PRD.md` §2.4 reserves — precisely so that
no later phase reaches for a factory of its own. A preload manager built with the default factory
would fetch first segments through none of it: no cache, so a prefetch is not a cache hit later; no
CMCD, so the CDN's log has no `sid` for the request that mattered most; no revalidation. It also
requires that the player that plays a preloaded source was built with the *same* load control,
bandwidth meter, track selector and renderers factory as the manager — `Builder.buildExoPlayer` is
Media3's way of guaranteeing it — because the preloaded periods are allocated from the load
control's `Allocator` and their track selection was made against the manager's renderer
capabilities. Media3's `LoadControl` is keyed by `PlayerId` for this reason: one load control
serving several players is its contract, not an abuse of it. Today `AdaptivePolicy` builds one
`AdaptiveLoadControl` per player and refuses to serve a second.

**A preloaded item has an identity to keep.** `setMediaRequest` is where a `MediaRequest` becomes a
`MediaItem` whose `mediaId` is the `contentId`, where the resume position is read, where the live
latency half is laid into the item, and where the telemetry session starts and the CMCD `sid` is
minted (`MeasurementSession`). A preload manager keys its sources by `MediaItem` equality and hands
one back through `getMediaSource(MediaItem)`, which a player plays through `ExoPlayer.setMediaSource`
— not a `Player` method, so not on the facade at all, reachable only through `player.exoPlayer`,
and never seen by `adopt`. A preloaded item that reached a player that way would play without an
identity, without its resume position, and outside its session.

**Two things bound how many things can be warm.** `PlayerPool` bounds the players a screen may have
alive at once by what `DeviceCapacity.kt` reads: the platform's concurrent-decoder limit and a heap
budget, each read once per pool and deliberately not cached (its KDoc says why). #143 is open on
*which* codecs that reading should consult. A warm decoder is a prepared player, so decoder warm-up
spends the same resource the pool bounds; a first segment fetched ahead is bytes, which the pool
bounds only through the heap term. Two objects reading the device separately would arrive at two
bounds for one resource, and #143 would have to be fixed twice.

**Three numbers, three possible homes.** `PRD.md` §2.3 asks for a memory guard (preload capped
against `MemoryInfo` and `isLowRamDevice`, released on `onTrimMemory`), a prefetch budget (never on
a metered network with data saver active, per `getRestrictBackgroundStatus`), and a cache size.
ADR-0005 puts buffering and selection policy behind `PlaybackPolicy`; ADR-0006 rule 1 puts platform
rules — one right answer for all content — on for everyone and outside the policy; ADR-0006 rule 2
leaves storage to the consumer. Each of the three could be argued into two of those homes.

**Pay nothing is a promise the project has already made twice.** A player built without
`setTelemetry` registers no analytics listener (ADR-0008 rule 2); one built without abr registers no
observer (ADR-0009 rule 7). Both are asserted by counting registrations rather than by reading the
code. Phase 4 adds two more optional modules and inherits the promise.

## Decision

**A content cache is storage the consumer opened: the consumer names the directory and the budget,
SuperPlayer writes only inside that directory and never creates a cache it was not handed. Preload
builds its sources through the `MediaSource.Factory` `TransferChain` assembles, shares the cache
the chain uses, and hands a preloaded source to a player only through `setMediaRequest`.
`PlayerPool` owns the players and the decoder budget; `PreloadCoordinator` is attached to a pool,
owns the warm set and the bytes it costs, and takes its decoder bound from the pool. Prefetch depth
is decided behind `PlaybackPolicy`; the memory guard and the data-saver rule are platform rules on
for everyone; the cache budget is the consumer's. A player built with neither module registers and
allocates nothing for them, and a test counts it.**

Thirteen rules follow, and they are binding.

### The cache and ADR-0006 rule 2

1. **The consumer opens the cache, naming a directory and a byte budget, and SuperPlayer never
   creates one on its own.** The entry point is `superplayer-cache`'s: something of the shape
   `ContentCache.open(directory, maxBytes)`, returning a value the consumer holds and releases.
   The directory is a `File` the consumer chose — under `cacheDir`, `externalCacheDir`, or
   anywhere else, which is their decision to make and their storage-permission story to own. The
   budget is bytes, or the *device-derived suggestion* `PRD.md` §3.5 asks for, computed from the
   directory's free space and `isLowRamDevice` — but the consumer passes it, in their code, where a
   reviewer can see the number they accepted. A `SuperPlayer`, a `PlayerPool` or a
   `PreloadCoordinator` built without a cache has no cache: it does not open one in `cacheDir`
   because that would be convenient.

2. **Everything the cache writes — span files, the index, its metadata — lands inside the
   directory the consumer named, and nowhere else.** Media3's `StandaloneDatabaseProvider(Context)`
   opens its database in the application's database directory under Media3's name, which is a file
   SuperPlayer would have chosen, so it is not used. `DatabaseProvider` is an interface;
   `superplayer-cache` implements it over a database file inside the consumer's directory
   (`SQLiteOpenHelper` takes an absolute path as a name, and a `Context`'s database path resolves an
   absolute name as itself). Deleting the directory deletes the cache; there is no second place to
   clean.

3. **The cache is a public core type with an internal constructor, implemented by
   `superplayer-cache` as core's third Kotlin friend.** `ContentCache` is `superplayer-core`'s —
   `public abstract class ContentCache internal constructor()` — so that
   `SuperPlayer.Builder.setCache`, `PlayerPool.Builder.setCache` and the coordinator can take it
   without naming a later phase, and so that a consumer, who has no reason to implement a cache,
   cannot. Its Media3 half — the `Cache`, the `CacheKeyFactory`, the evictor — is `internal`, filled
   into `TransferChain`'s cache slot inside `build()` the way ADR-0009 rule 7 fills
   `EngineConfiguration`'s. This is not the opaque public handle ADR-0009 rejected: that handle would
   have been empty until abr needed to pass a Media3 type through it, and this one *is* the cache,
   with a public lifetime (`release`), a public budget, and the pinned region Phase 7 will reach
   through it. `KotlinFriendModules.kt`'s argument holds unchanged; the friend path is a compiler
   flag and creates no Gradle dependency, so `docs/modules.md`'s rule is intact.

4. **The cache is keyed by content identity, and a read from it is not a throughput sample.** The
   key is `MediaRequest.contentId` plus the representation the `DataSpec` names, never the URL
   (`PRD.md` §3.5, F7), so the same rung from a second CDN host is one entity. A read of content
   that arrived through `setMediaItem` rather than `setMediaRequest` has no `contentId` and is
   keyed by Media3's default, the URL, and the KDoc says so. The cache-read source reports
   `isNetwork = false`, which is what keeps a hit out of `BandwidthOracle`'s estimate
   (`TransferChain`'s KDoc, ADR-0009 rule 2's table); #156 is where that is proven with a real
   cache, and a test that shows a hit moving the estimate is a failure of this rule, not a tuning
   question.

5. **ADR-0006 rule 2 is refined, not superseded, and its wording stands.** The rule forbids storage
   chosen *on a consumer's behalf*: a file the library opened in a place it picked, holding data the
   app never asked it to keep. A content cache under rules 1 and 2 is storage the consumer chose,
   named, sized and can delete, holding bytes they asked the library to fetch. The privacy argument
   the rule rests on — a library deciding where data about a person lives — does not reach a
   directory the app named. Its other cost does: a threading model, a clean-up policy and a
   migration story now exist, and they are `superplayer-cache`'s to own and to state. ADR-0006
   carries a pointer to this ADR at rule 2 so a reader of that document alone is not misled, and
   its list — no preferences, no database, no file — is read as *no such thing the consumer did not
   hand over*.

### Preload on the one chain

6. **A preload manager is built with the `MediaSource.Factory` `TransferChain` assembled for the
   players that will play its sources, and with the same `Cache` instance the chain's cache layer
   reads.** `DefaultPreloadManager.Builder.setMediaSourceFactory` is the hook and
   `setDataSourceFactory` is never called: the factory carries the chain, the CMCD configuration
   and the cache, so a first segment fetched ahead is a cache write, a CMCD-labelled request and a
   revalidated playlist exactly as it would be on a playing player. When a cache is attached the
   manager's `setCache` is handed *the same* `Cache` the chain reads, so that its cached stage
   (`STAGE_SPECIFIED_RANGE_CACHED`) writes where the chain will look; a second cache would be a
   second copy. The factory reaches the coordinator through core's engine seam, which is why
   `superplayer-preload` is core's fourth friend and the coordinator is attached at a pool rather
   than assembled beside it (rule 8).

7. **A preloaded source reaches a player only through `setMediaRequest`, and the consumer never
   sees a source.** Core's adoption gains one internal step: after `adopt` has built the item, read
   the resume position, laid in the live latency half and started the session, it asks a
   core-internal *adoption source* — installed by the coordinator through the engine seam, absent
   otherwise — whether it holds a source for that item, and plays it through `setMediaSource` if so
   and through `setMediaItem` if not. The coordinator builds the items it preloads through core's
   own `MediaRequest` adoption, never its own `MediaItem`, so the item it registered and the item
   `adopt` builds are equal and the lookup finds it. There is no `setPreloadedItem`: a feed writes
   `player.setMediaRequest(request)` on a player from a pool with preload attached and gets the
   warm source, or writes it on any other player and gets a cold one, with the same identity, the
   same resume position and the same session either way. The CMCD `sid` a preloaded request
   carried is the session id the item's later session reports — the coordinator asks core to mint
   it when the item is added and adoption reuses it — because the join `docs/telemetry-schema.md`
   promises is an equality on that string and the first segment is the request it most needs to
   hold for.

### The coordinator and the pool

8. **`PlayerPool` owns the players and the decoder budget; `PreloadCoordinator` is attached to one
   pool, before that pool builds its first player, and owns the warm set and the bytes it costs.**
   `PreloadCoordinator.Builder(pool)` is the public shape. Attaching installs an engine
   configurator on the pool's player factory through the friend seam, so every player the pool
   builds afterwards is built with the manager's shared components (rule 9) and the adoption source
   (rule 7); attaching after a player exists throws, naming the rule, the way `DeviceStatement`
   must precede the first player because the platform caches the codec list. The coordinator never
   builds a player and never reads `DeviceCapacity.kt`: its bound on warm decoders is
   `pool.maxSize`, public today, less the one the feed is playing, and its bound on prefetched
   bytes is its own (rule 11). `maxSize` already carries a heap term, so a warm decoder is counted
   against the heap twice — once as a player, once as the bytes it holds — which is conservative
   rather than wrong, and is stated so nobody removes one count believing it a mistake. One reading, in one file, as `DeviceCapacity.kt`'s KDoc already
   requires — and #143 changes that reading in one place, with the coordinator following through
   `maxSize` untouched.

9. **A pool with a coordinator attached builds its players on one shared load control, bandwidth
   meter, track selector and renderers factory, because Media3's preload manager requires it.**
   That is a change from one of each per player, and it is Media3's design rather than a
   workaround: `LoadControl` is keyed by `PlayerId` so one instance can serve several players, and
   `buildExoPlayer` exists to make a player consistent with its manager. What the shared components
   *are* — Media3's defaults, or abr's `AdaptiveLoadControl`, `OracleBandwidthMeter` and
   `NetworkAwareTrackSelection` — is decided where they are built, and `AdaptivePolicy`'s "one
   policy serves one player" becomes "one policy serves one pool" in the change that makes it so
   (#158, with abr). A decision re-applied on such a pool is re-applied to every player on it,
   which is what "one pool, one decision" means and what a feed wants: the rows are one screen on
   one network.

### What is policy

10. **Prefetch depth is decided behind `PlaybackPolicy`, as a third half of `PlaybackDecision`.**
    How many items ahead of the viewport to hold, how far into each (a prepared source, selected
    tracks, or a loaded range and how many milliseconds of it), and whether to hold a decoder warm
    depend on transport, throughput and heap — the conditions ADR-0009 rule 1 enumerates — and on
    the profile: `SHORT_FORM` prefetches deep, `DATA_SAVER` prefetches nothing on cellular,
    `LIVE_LINEAR` has nothing to prefetch. That is ADR-0005's domain by its own definition: a
    decision with different right answers for different content, that a policy constant at a
    Media3 call site would hide. `PlaybackDecision` gains a `PreloadPolicy` (an addendum to
    ADR-0005 rule 2, of the kind its live-latency and pace additions were) with a default of
    *none*, so every `PlaybackPolicy` written before it keeps compiling and decides no prefetch —
    the same shape ADR-0009 rule 1 gave a new condition. `StaticProfilePolicy`
    carries a per-profile table with a reason per row, and `AdaptivePolicy` varies it. It is
    re-applied on ADR-0009 rule 4's triggers and no others, and "honoured whole" (rule 5) reaches
    it: Media3 consults `TargetPreloadStatusControl` per item on `invalidate`, so a changed depth is
    honoured on the next invalidation, and the coordinator is the `DecisionTarget` for that half.
    On a player with no coordinator the half is decided and ignored, and `playbackDecision` says
    what was decided, which is the same contract a core-only player has for the selection ceiling
    today. The *order* of items — scroll direction and velocity, what is next — is input the feed
    supplies at runtime, not policy and not configuration.

11. **The memory guard and the data-saver rule are platform rules, on for every coordinator, and
    not a profile's to vary.** The guard caps total preload allocation against the heap budget
    `DeviceCapacity.kt` already reads, short-circuits on `isLowRamDevice`, and releases everything
    warm on `onTrimMemory` at `TRIM_MEMORY_RUNNING_LOW` and above (the callback is registered on the
    coordinator, not per player, so there is one and it is unregistered on release). The
    data-saver rule is that no prefetch is issued while `getRestrictBackgroundStatus` reports
    `RESTRICT_BACKGROUND_STATUS_ENABLED` on a metered network, whatever the profile and whatever the
    policy decided. Both are ADR-0006 rule 1's shape exactly: an app that ignores a trim is killed,
    and a prefetch on a capped plan the user asked the OS to protect is a support ticket, and there
    is one right answer for all content. A policy sees the heap budget as a *condition* and may
    decide less; it may not decide more than the guard allows, and the guard is applied after the
    decision, in the coordinator, where a reviewer can find it.

12. **Cache size is the consumer's, under rule 1.** It varies with nothing a policy observes and
    it names bytes on a disk rather than a buffer in memory; a profile that chose a cache size
    would be choosing storage, which rule 5 says the library does not do. Eviction order (LRU) and
    the pinned region are `superplayer-cache`'s mechanism, stated in its KDoc, and not
    configurable: a consumer who needs a different eviction opens a second cache for that content.
    Pinned bytes count against the budget: eviction makes room from unpinned entries only, and when
    pinned content alone exceeds the budget it is kept and the cache is over by that excess. The
    alternative, a budget for streaming with pins outside it, would let the directory grow without a
    number the consumer wrote down, which is rule 1's objection; the cost is that downloads crowd out
    streaming, and a consumer who wants both sized separately opens two caches.

### The pay-nothing claim

13. **A player, a pool or a session built without `setCache` and without a coordinator attached
    registers nothing and allocates nothing for either, and a test counts it.** No `Cache` is
    opened, no cache layer sits in the chain (the slot is empty and the chain is what it was in
    Phase 3, byte for byte in a golden trace), no `DefaultPreloadManager` exists, no adoption
    source is consulted, no `onTrimMemory` callback and no connectivity callback is registered for
    preload, and no class from either module is loaded. It is asserted the way ADR-0008 rule 2 and
    ADR-0009 rule 7 are: a core test builds a player with a profile alone and counts what was
    registered — component callbacks through Robolectric's shadow, chain layers through the engine
    seam a test already reaches, memory callbacks the way
    `aPlayerBuiltWithNoTelemetryRegistersNoMemoryCallbackAtAll` counts them — and the counts are
    zero; the same counts are taken with the modules attached so the counter is shown to see what
    it counts. A golden trace of a core-only session that changes when Phase 4 lands is a violation
    of this rule, whatever the diff says.

## Consequences

**Easier.** Every Phase 4 issue after this one (#155–#162) has a written answer to the question it
would otherwise settle at a call site: where the cache lives, whose factory a preload uses, who
reads the device, what is a decision and what is a rule. The feed demo's per-row time to first
frame is measured through the same `setMediaRequest` a cold player uses, so the number it reports
is the number a consumer gets. `BandwidthOracle`'s cache-hit exclusion, built in Phase 3 for this
phase, is finally exercised by a real cache. A consumer who never adds either module sees one new
public type they need not touch and no change in behaviour.

**Harder.** `ContentCache` and `PreloadPolicy` are public API under `docs/api-surface.md`, and
`PlaybackDecision` grows a third half every policy must produce; the static table gets one more
column per profile with one more reason per row.

Rule 9 is the expensive one. `AdaptiveLoadControl`, `OracleBandwidthMeter` and
`NetworkAwareTrackSelection` were each built for one player, and a pool with preload attached asks
each to serve several. Media3's contracts allow it, but the retargeting, the release ordering
(`onEngineReleased` currently releases the oracle when *the* player goes) and the "one policy, one
player" check all have to be rewritten for a pool, and #158 pays for that before a single row is
warm. The alternative — a coordinator over players that do not share components — does not
exist in Media3, so the cost is not optional.

Rule 8's attach-before-first-player rule is a temporal coupling of the kind the project has
accepted once before (`DeviceStatement`), and it is discoverable only by hitting it. The exception
message names the rule; the KDoc on both builders says it; and it is still the sort of thing people
learn by being thrown at.

Rule 7's session id for a preloaded item is minted before the item is adopted — possibly for an
item that is never played. A `SessionStarted` is still emitted only on adoption, so telemetry sees
no phantom sessions, but a CDN log will carry `sid` values that join to nothing, and
`docs/telemetry-schema.md` says so where it states the join.

Rule 2 costs `superplayer-cache` its own `DatabaseProvider`, twenty lines Media3 would have
provided had the library been willing to put a file where Media3 wanted it. Rule 1 costs the
consumer two decisions — a directory and a budget — that a library choosing for them would have
hidden, and puts a cache directory in their clean-up and storage-permission story, where it
belongs.

**A standing obligation.** The pinned region rule 12 names is built in Phase 4 so that Phase 7's
downloads have somewhere eviction cannot reach, and `superplayer-offline` shares this cache and this
key policy (`PRD.md` §2.4, §3.5) rather than opening a second one: a download stack with its own
directory would be a second storage decision, and a second key policy would reopen F7.

## Alternatives considered

**Open the cache in `context.cacheDir` when the consumer says `setCache(maxBytes)` and nothing
else.** Rejected under ADR-0006 rule 2 as written: it is a file in a place the library picked,
under a name the library chose, with a clean-up story the library now owns. That it is the
directory most apps would have picked is not the point; the rule exists so that a library never
has to be right about that for every app.

**Supersede ADR-0006 rule 2 with a narrower rule that permits a cache.** Rejected. Every part of
rule 2 still holds — no preferences, no database, no file, no singleton, none of them chosen on a
consumer's behalf — and a superseding ADR would tell a reader the storage question had been
reopened, which it has not. The refinement is a scope statement, recorded at the rule.

**Let the preload manager build Media3's default sources and rely on the cache to make the
prefetch reusable.** Rejected. Without the chain, the prefetch writes to no cache, carries no
CMCD and revalidates no playlist; a prefetch that a later play cannot find in the cache is a
second fetch, which is the outcome F5's proxy at least avoided. `setMediaSourceFactory` exists so
that the manager can be given the factory the player uses, and there is no reason to give it any
other.

**Hand the consumer the preloaded source, or add `setPreloadedItem`.** Rejected. A source is a
Media3 `@UnstableApi` type and cannot be public (ADR-0001 rule 2); a second adoption entry point
would either duplicate `setMediaRequest`'s identity, resume and session work or skip it, and a
feed author would have to know which players are warm. One call, the same call, is the shape
`MediaRequestResolver` already established for content arriving from outside the app (ADR-0007).

**Give the coordinator its own players and its own device reading.** Rejected. Two objects bounding
one resource is two bounds; a coordinator that warmed a decoder the pool had already counted would
exceed the device's limit while both believed themselves within it, and #143 would be fixed in two
files. The pool is the one that hands out players and the one that read the device, so the
coordinator borrows both.

**Have the coordinator build the pool (`PreloadCoordinator.Builder(context).build().pool`).**
Rejected, narrowly. It removes the attach-before-first-player rule, but it duplicates
`PlayerPool.Builder`'s every option on a second builder and makes a preloading feed's pool a
different kind of object from a plain feed's, which the demo's `FeedScreen` — one screen, preload
on or off — would have to branch on. Attaching keeps one pool and one builder for it.

**Make prefetch depth configuration the consumer passes.** Rejected. A depth is a number that is
right on WiFi and wrong on a capped cellular plan, which is the definition ADR-0005 rule 3 uses to
forbid a number in a builder; a consumer choosing one would rediscover F1 in a new place.

**Make prefetch depth a platform rule.** Rejected. There is no one right depth for all content:
a live channel has none, a short-form feed wants the next three rows, and a data-saver profile on
cellular wants zero. That is a decision with a profile in it, which is policy.

**Make the memory guard policy.** Rejected under ADR-0006 rule 1's test: no content has a
different right answer to `onTrimMemory`. A policy that could decide to keep its preload through a
trim would be a policy that could get the process killed.

**Let a profile size the cache.** Rejected under rule 5: a profile choosing bytes on disk is the
library choosing storage.

**`ServiceLoader` or reflection to find the cache and preload modules.** Rejected for ADR-0009's
reasons, which hold unchanged: behaviour that changes with a dependency and no line of code, and a
pay-nothing claim that a classpath scan would make false.

## References

- `DefaultPreloadManager` and its builder — `setMediaSourceFactory`, `setCache`, and
  `buildExoPlayer`, the consistency requirement between a manager and its players:
  https://developer.android.com/reference/androidx/media3/exoplayer/source/preload/DefaultPreloadManager
- Media3's preloading guide — the shared-components requirement and `TargetPreloadStatusControl`:
  https://developer.android.com/media/media3/exoplayer/preloading
- `LoadControl` — keyed by `PlayerId`, which is what lets one instance serve several players:
  https://developer.android.com/reference/androidx/media3/exoplayer/LoadControl
- `SimpleCache` — the folder lock, and the `DatabaseProvider` its index takes:
  https://developer.android.com/reference/androidx/media3/datasource/cache/SimpleCache
- `StandaloneDatabaseProvider` — opens in the application's database directory, which rule 2 does
  not use: https://developer.android.com/reference/androidx/media3/database/StandaloneDatabaseProvider
- `CacheDataSource` and `CacheKeyFactory` — the content-keyed read and its `isNetwork` reporting:
  https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource
- `Context.getDatabasePath` — an absolute name resolves as itself, which is how an index lives
  inside the consumer's directory:
  https://developer.android.com/reference/android/content/Context#getDatabasePath(java.lang.String)
- `ComponentCallbacks2.onTrimMemory` and its levels — rule 11's release point:
  https://developer.android.com/reference/android/content/ComponentCallbacks2
- `ConnectivityManager.getRestrictBackgroundStatus` — rule 11's data-saver reading:
  https://developer.android.com/reference/android/net/ConnectivityManager#getRestrictBackgroundStatus()
- `ActivityManager.isLowRamDevice` and `getMemoryClass` — the heap readings `DeviceCapacity.kt`
  already makes: https://developer.android.com/reference/android/app/ActivityManager
- CTA-5004, Common Media Client Data — the `sid` rule 7 carries onto a prefetch:
  https://shop.cta.tech/products/web-application-video-ecosystem-common-media-client-data-cta-5004
- [ADR-0001](0001-compose-dont-fork.md) rule 2, [ADR-0002](0002-no-local-http-proxy.md),
  [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) rules 2 and 3,
  [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rules 1 and 2,
  [ADR-0007](0007-publish-the-player-as-a-session-and-resolve-content-by-id.md),
  [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md) rule 2, and
  [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md) rules 2, 4,
  5, 7 and 8; `TransferChain`'s KDoc; `docs/testing.md`'s *Reaching that seam from another module*.
- `PRD.md` §2.2, §2.3, §2.4, §3.5 and Part 4; #153 — the phase this decides for; #143 — the open
  question on the device reading rule 8 leaves in one place.
