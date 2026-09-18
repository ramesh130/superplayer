# ADR-0013: Download into the cache the consumer opened, on the one chain, under constraints that are correctness

- **Status:** Accepted
- **Date:** 2026-09-17
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) rule 2,
  extended by a fifth half of `PlaybackDecision` (rule 12 below) and recorded there as an addendum;
  [ADR-0010](0010-cache-in-consumer-chosen-storage-and-preload-on-the-one-chain.md)
  rule 12 and its standing obligation, which this ADR discharges, and the cache layer's media-only
  rule that `ContentKeyedCacheLayer` states in its KDoc (rule 8 below);
  [ADR-0012](0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md) rule 4,
  whose friend ceiling gains one shape beside the existing one (rule 4 below), rule 9's addendum and
  rule 10 for downloads only (rule 13 below), and its standing obligation, which this ADR discharges.
  Nothing is superseded, and each refinement is recorded at the rule it refines.
- **Summary:** Decides Phase 7's shape before any of its code lands (#237). `superplayer-offline`
  depends on `superplayer-core` alone and takes every collaborator as a core type, so an app that
  downloads clear content carries no DRM module and an app that plays without downloading carries no
  download stack. A download writes into the `ContentKeyedCache` the consumer opened, under the keys a
  player reads, pinned from enqueue to removal, and travels the transfer chain core assembles — so a
  downloaded item plays through `setMediaRequest` with no request at all. Every Media3 download type
  is `@UnstableApi`, so the public surface is the module's own and the consumer's service extends
  `android.app.Service` rather than Media3's `DownloadService`. Resuming, stopping on a lapsed
  constraint, failing one item on a full disk, and the battery-not-low and storage-not-low constraints
  are correctness, on for everyone; the *network* requirement is unmetered by default and the viewer's
  to relax. Which rendition to download is a half of `PlaybackDecision`; which languages is the
  consumer's argument. A protected download acquires its licence before its first media byte and
  releases it as part of its removal. Nothing that does not open a download store pays for one.

## Context

Phase 7 (`PRD.md` §3.5 and Part 4, #237) ships `superplayer-offline`: Media3's `DownloadManager` and
`DownloadService`, `WorkManager` scheduling under `UNMETERED`, battery-not-low and storage-not-low,
track selection for download, resumption across process death and network loss with per-item
progress, storage-full handling that fails one item rather than the queue, and the offline licence
lifecycle bound to the download. Its exit criterion is a download that survives process death,
network loss and reboot, and a battery policy verified with a profiler. The facts below decide the
shape.

**Two earlier ADRs left obligations addressed to this phase.** ADR-0010 built a pinned region into
`ContentKeyedCache` "so that Phase 7's downloads have somewhere eviction cannot reach", and says
`superplayer-offline` shares that cache and its key policy rather than opening a second one: a
download stack with its own directory is a second storage decision, and a second key policy reopens
F7. ADR-0012 says Phase 7's downloads read `OfflineLicenceStore` and its playback-versus-licence
expiry split rather than keeping their own.

**The cache's keys come from a stamp a download does not carry.** `ContentKeys` builds a key from the
`ContentIdentity` core stamps onto each request a player adopted from a `MediaRequest`, plus the URI's
path. Media3's `SegmentDownloader` builds its own `DataSpec`s from the manifest, through the
`CacheDataSource.Factory` it is handed, and asks that factory's `CacheKeyFactory` for each key; no
player stamps anything on them. Unless the key factory a download is given already knows the content
id, a download is written under URL-derived keys that no player reads — the silent miss F7 names.

**The cache answers media only.** `ContentKeyedCacheLayer` sends every request not stamped
`LoadKind.MEDIA` straight upstream, so that a manifest — a live one above all — is never served stale.
A downloaded item whose manifest must still be fetched is not playable on a plane.

**Every Media3 download type is `@UnstableApi`.** `DownloadManager`, `DownloadService`,
`DownloadRequest`, `DownloadHelper`, `Download`, `Requirements`, `Scheduler` and
`WorkManagerScheduler` all carry the marker, and `docs/api-surface.md` fails the build on any of them
in a public signature. A consumer subclass of `DownloadService` would put an unstable supertype on a
class in *their* code — ADR-0003's argument about `ForwardingPlayer`, arriving through a service.

**Media3's requirements cannot state battery-not-low.** `Requirements` in Media3 1.11 has `NETWORK`,
`NETWORK_UNMETERED`, `DEVICE_IDLE`, `DEVICE_CHARGING` and `DEVICE_STORAGE_NOT_LOW`, and nothing else.
`WorkManagerScheduler` translates those into `WorkManager` `Constraints`, so it cannot schedule under
`setRequiresBatteryNotLow`. `DownloadManager`'s own requirements watcher, which stops and restarts
downloads while the process lives, cannot see the battery either. `PRD.md` §3.5 names battery-not-low
explicitly, and F4 is the reason it does.

**A download needs things a player has.** A protected download's licence exchange travels a chain
with the app's credential and `RetryPolicy.licence`'s budget (ADR-0012 rule 2 and rule 9's addendum);
a refused segment wants the same retries a player's segment gets; and `docs/testing.md` bars the
network, so a test of a download has to see it cross the harness's transport, which reaches a player
through core's engine configurator and nothing else.

**What a consumer pays for is a decision.** `docs/modules.md` allows `superplayer-offline` (phase 7)
to depend on `superplayer-cache` (4) and `superplayer-drm` (6). Declaring both would make every app
that downloads clear content carry the Widevine module and its Media3 DRM classes. ADR-0010 rule 3
already has a shape for "a later module's object, taken by a core type": `ContentCache` is core's
public abstract class with an internal constructor, implemented by `superplayer-cache`.

## Decision

**`superplayer-offline` depends on `superplayer-core` alone and takes the cache, the protection, the
licence store and the resilience as core types. A download writes into the cache the consumer opened,
under the keys a player reads, pinned from enqueue until removal, with its index in the cache's own
database, and travels the transfer chain core assembles; a downloaded item plays through
`setMediaRequest` with its manifest served from that cache. The public surface names no Media3 type,
and the consumer's service extends `android.app.Service`. The module is core's seventh Kotlin friend.
Resuming, stopping on a lapsed constraint, failing one item on a full disk, and requiring the battery
and the storage not to be low are on for everyone; the network requirement is unmetered by default and
the viewer's to relax. The rendition is a half of `PlaybackDecision`; the languages are the consumer's
argument. A protected download acquires its licence before its first media byte and releases it as
part of its removal. Nothing that does not open a download store pays for one, and a test counts it.**

Fifteen rules follow, and they are binding.

### Dependencies and the public surface

1. **`superplayer-offline` declares `superplayer-core` and no other SuperPlayer module in its main
   sources.** Everything it is handed is a core type: the `ContentCache` the consumer opened, the
   `PlaybackDrm` a protected download is acquired under, the `PlaybackResilience` whose budgets and
   `HeaderProvider` a download spends and carries, and — new — a core `LicenceStore`, a public abstract
   class with an internal constructor that `superplayer-drm`'s `OfflineLicenceStore` extends, exactly
   as `ContentKeyedCache` extends `ContentCache` (ADR-0010 rule 3). So an app that downloads clear
   content adds `superplayer-cache` and `superplayer-offline` and nothing that knows what Widevine is,
   and an app that downloads protected content adds `superplayer-drm` because it already needed it to
   play. The module's tests may depend on testkit, testmedia, cache, drm and resilience, which is the
   allowed direction. `docs/modules.md`'s row says so.

2. **The public surface names no Media3 type, and its shape is fixed here while its spelling is
   #240's.** A consumer opens one store per cache directory per process —
   `Downloads.Builder(context, cache)`, optionally `setDrm(drm, licences)`, `setResilience(...)` and
   `setProfile(...)`, then `build()` — and holds it for the process's lifetime as they hold the cache.
   They enqueue a `MediaRequest` with the audio and subtitle languages wanted; read, per `contentId`,
   a value carrying the state (queued, downloading, stopped with the constraint that holds it,
   completed, failed with its classification, removing), bytes held and progress; observe changes
   through a listener; and remove an item by its `contentId`. The request is the same `MediaRequest` a
   player adopts, so the id a download is stored under and the id a player plays under are one value
   by construction. One store per directory is Media3's own constraint — a `SimpleCache` locks its
   folder (`SimpleCache.isCacheFolderLocked`) — stated rather than discovered.

3. **The consumer's service extends the module's `DownloadsService`, an `android.app.Service`, and its
   manifest entry and permissions are the app's.** `DownloadsService` is a public abstract class whose
   supertype is the platform's, not Media3's `DownloadService`, so no `@UnstableApi` type appears in a
   consumer's class hierarchy; internally it drives the store's `DownloadManager` and uses Media3's
   notification helper. The consumer overrides two things: `onDownloads()`, returning the process's
   store, because a service the system starts in a fresh process has no other way to find storage the
   app opened; and the notification's channel and content, because those are the app's words. The
   `<service>` element, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`
   are the consumer's to declare and justify, on ADR-0007 rule 4's argument unchanged: a foreground
   service type is a store-policy commitment, and a library manifest does not make one on every
   consumer's behalf. The entries `WorkManager`'s own artifact merges are WorkManager's, and the
   module's documentation lists them so a consumer is not surprised by their manifest.

   *Addendum (2026-09-17, #246).* How the service was built, decided where it was:

   - **The consumer names the subclass on the store, `Downloads.Builder.setService`.** A third override the
     rule does not list, and needed: the store is what knows a download can run, and the scheduled work is
     what runs in a process where no store is open, so both must be able to start the service, and neither
     can find a class in the app's manifest by its supertype. The app still starts it nowhere itself.
   - **The service holds the process; the store runs the downloads.** The store resumes its own
     `DownloadManager` whether or not a service holds it, so a store a screen opened downloads exactly as one
     the service opened does, and the service's lifecycle is only foreground promotion, the notification and
     stopping. Media3's `DownloadService` owns its manager's pause instead, which would make a download depend
     on a service having started.
   - **Foreground while a download can run: pending, and held by no condition.** A download a metered network
     or a low battery holds is waiting, and a `dataSync` foreground service has a daily time budget from API
     35; the service stops, and the schedule starts it again once the conditions hold. Every start announces a
     notification first, including one with nothing to run, because a foreground start obliges it.
   - **Not sticky.** Restarting a download whose process died is rule 11's schedule; a sticky restart would be
     a second mechanism, and a foreground-service start from the background the platform refuses.
   - **The notification's content defaults to Media3's progress notification**, built internally, under the
     channel name and icon the consumer supplies, and is overridable as a list of `DownloadItem`s, rebuilt at
     most once a second.

4. **`superplayer-offline` is core's seventh Kotlin friend, and the shape it adds is being *built
   from* core's seam rather than filling a slot of it.** It fills nothing. It reaches three things,
   each core-internal and each unreachable otherwise without an `@UnstableApi` type in a public
   signature. First, `ContentCache`'s download half: a slot core declares on `ContentCache` and
   `superplayer-cache` fills — the Media3 `Cache`, a `CacheDataSource.Factory` keyed for one content
   id, the index database, and pinning — which for `superplayer-cache` is the existing shape. Second,
   `TransferChain`'s assembly of a chain for a download (rule 6). That is core's seam itself rather
   than a helper beside it: `PRD.md` §2.4 makes `TransferChain` the one place a chain is composed, so
   a download stack that assembled its own would be the second chain the section forbids. Third, the
   licence exchange a `PlaybackDrm` carries, which `superplayer-drm` put in the DRM slot (rule 13).
   `KotlinFriendModules.kt`'s ceiling, stated by ADR-0012 rule 4, said a friend path is right "for a
   later phase of this library filling a slot core declared, and for nothing else". It now admits a
   second shape beside that one: *a later phase built from core's seam and from what other friends
   filled into it, filling no slot and making nothing configurable.* The alternatives — a Gradle
   dependency on `superplayer-cache` to reach its internals (which Kotlin `internal` does not permit
   across compilations anyway), or public opaque handles — are recorded below. An eighth of neither
   shape needs an ADR of its own.

   *Addendum (2026-09-18, #285).* The second shape gains its second instance, and the ninth
   friendship reaches a little past it — which is why it has an ADR.
   [ADR-0015](0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)
   rule 3 admits `superplayer-diagnostics` as core's **ninth** friend over a **closed list of three**
   named seams. The first is this shape exactly: it fills no slot, and it is built from core's seam —
   `TransferChain.diagnosticChain(...)` beside this rule's `downloadChain`, so that a doctor's fetch
   is not the second chain `PRD.md` §2.4 forbids. The other two — one function returning the
   capability snapshot a bundle may carry, and `LiveWindowDepthCheck`'s judgement, which a doctor
   must share rather than copy — are nearer the "reaching a helper"
   `KotlinFriendModules.kt`'s ceiling refuses, and ADR-0015 says so plainly rather than stretching
   this shape over them. What makes them admissible is the list: each is a whole answer rather than a
   helper, and a fourth seam may be reached only by amending ADR-0015 rule 3, by name, in the change
   that reaches it. No earlier friendship carried such a bound — not even this rule's own, which
   enumerates three reached things and fills nothing, but enumerates what the shape *is* rather than
   capping what may later be added to it. **The ceiling worked as intended** — something that is not
   plainly one of the two shapes produced an ADR — and it does not move: a tenth of either shape
   needs none, and a tenth of neither still needs one of its own.

### One cache, on the one chain

5. **A download writes into the consumer's `ContentCache`, under `ContentKeys`, with its index in that
   cache's database.** The `CacheDataSource.Factory` a download's `Downloader` is given is built per
   content id by `superplayer-cache`, with a key factory that binds the id before applying
   `ContentKeys` — so a downloaded segment is stored under exactly the key a player adopting that
   `MediaRequest` asks for, and never under Media3's URL-derived default. The module opens no
   `SimpleCache`, no directory and no database of its own: Media3's download index is a table in the
   cache's index database, inside the consumer's directory (ADR-0010 rule 2), so deleting the directory
   deletes the downloads, their progress and their pins together. A cache that no store was ever
   opened over has no such table.

6. **A download travels the transfer chain core assembles.** `TransferChain` — the one place a chain is
   composed (`PRD.md` §2.4) — gains the assembly of a chain for a download: the engine configurator's
   transport, the header-refresh slot, and the cache layer, with the load-error policy of the
   resilience the store was built with. It omits what is a *playback's*: no CMCD, because a download
   is not a playback session and has no `sid` to join; and no bandwidth meter, because a download's
   throughput is not a sample of what a player can sustain. Two consequences are the point: a
   `FaultScript` and a `ThroughputTrace` reach a download in a test exactly as they reach a player, and
   a download's refused segment is retried and its expired token refreshed by the same machinery a
   player's is.

7. **A download is pinned from enqueue until its removal has deleted its bytes, and the budget does not
   refuse it.** Pinning at enqueue rather than at completion is what keeps an early segment from being
   evicted while a later one is still arriving. ADR-0010 rule 12 already decided how pinned bytes meet
   the budget — they count against it, eviction makes room from unpinned entries only, and pinned
   content alone may exceed it — and this ADR takes that answer rather than adding a second limit: the
   budget governs what streaming may keep, the disk governs what a download may write, and a disk that
   fills fails that item (rule 9). The cost ADR-0010 named — downloads crowd out streaming — is
   accepted as it stood, and its remedy is the one it gave: a consumer who wants the two sized
   separately opens two caches and builds the players that play downloads over the second.

   *Addendum (2026-09-17, #240).* "From enqueue" is kept as *before the first byte the enqueue causes*:
   the pin is taken on the download's own thread as it begins, not on the thread that called `enqueue`,
   because a pin is a database write and `enqueue` is called from a screen. The reason given above is
   untouched — nothing is written before the download begins, so no early segment can be evicted while
   a later one arrives — and the pin is taken again on every resumption, which costs nothing when held.
   What it changes is only a window in which a queued item holding no bytes is not yet pinned.

8. **A downloaded item plays through `setMediaRequest`, and the cache layer serves its manifest.** The
   layer's media-only rule is refined, not dropped: a request stamped `LoadKind.MANIFEST` is answered
   from the cache when, and only when, the cache holds that key for pinned content, which only a
   download writes. Everything else keeps going upstream, so a streaming player's manifests — and a
   live one's above all — are never served stale, and a golden trace of a cache-only session does not
   change. A live stream is refused at enqueue: it has no end to download and its manifest's whole
   meaning is that it moves. A downloaded item on a player built over that cache therefore opens no
   request at all, which is what #240 asserts as a count of zero.

   *Addendum (2026-09-17, #251).* How live content is refused, decided where it was built:

   - **The manifest read that chooses the tracks is the read that refuses.** Rule 12's addendum already reads
     every manifest through Media3's `DownloadHelper`, over the one chain, before the manager is handed
     anything, and that helper refuses a timeline whose window is live — an HLS media playlist with no
     `EXT-X-ENDLIST` (RFC 8216 §4.3.3.4), a DASH MPD of `type="dynamic"` (ISO/IEC 23009-1 §5.3.1.2) — before
     it prepares a period, with its own `LiveContentUnsupportedException`. So the store adds no fetch and no
     parser: it recognises that exception where it already handles a failed read. Nothing past the manifest
     has been asked for and nothing is pinned, because the pin is taken inside the downloader (rule 7's
     addendum) and a refused item never reaches it. A second fetch of the manifest before the helper's would
     have cost a round trip per enqueue to learn what the helper learns anyway, and refusing inside the
     download would have taken a pin to release. Until this, the helper's refusal was already stopping live
     content, but as a `FAILED` item with no reason, indistinguishable from an origin that answered nonsense.
   - **A refusal is not a failure, so it has no class.** It is the store's own verdict on a manifest it read
     perfectly well, and it carries a public `DownloadRefusal` on `DownloadItem.refusal`, beside the state,
     as `DownloadStopReason` is: `LIVE_CONTENT`, its one value. ADR-0011 rule 1 gives a *failure* its
     meaning in `ErrorClassifier`, and nothing here failed — no request, no exception of the engine's that
     a consumer would otherwise meet. Routing it through the classifier would mean raising a core exception
     only so that the classifier could name back what this module already knew, and would make the reason
     visible only on a store built with a resilience (rule 14), where the issue requires every consumer to
     see it. `failure` stays null beside a refusal on every store. Nor is it `Fatal.Unsupported`, which
     means an engine that cannot play the content, where a player of this content plays it perfectly well.
   - **The refused item is kept in memory, as every item failed before the manager is.** An enqueue reads
     the manifest again, and `remove` forgets it; a process that dies forgets it too, which costs nothing,
     since nothing was written.

### What is correctness and what is not

9. **Resuming, stopping on a lapsed constraint, and failing one item on a full disk are correctness,
   on for every store and not configurable.** Each passes ADR-0006 rule 1's test — one right answer for
   all content. A resumed download continues from the bytes the cache holds rather than refetching
   them; a download whose constraint lapses is *stopped* with that constraint as its visible reason and
   resumes when it holds, rather than failing; and an item that meets a full disk fails, typed, while
   every other item in the queue carries on. A failure acquires its meaning in `ErrorClassifier`
   (ADR-0011 rule 1): the module keeps no taxonomy, and where it detects a fact the engine does not
   report — a disk that cannot hold the next span — it raises a public core exception carrying the
   evidence, which the classifier maps (ADR-0011 rule 4's pattern). Which leaf is #244's, and its
   addendum below.

   *Addendum (2026-09-17, #242).* How a lost network is told from a failure, decided where it was built:

   - **The store takes a `PlaybackResilience` to tell them apart**, per rule 14, and a store built without
     one retries as Media3's download manager does and then fails, unnamed. A stop decided by reading
     exception types in this module would be the second taxonomy ADR-0011 rule 1 forbids.
   - **`Transient.Network` with no answer stops the item; everything else spends a budget and fails,
     named.** `Transient.Network` is the classifier's fall-through for a transfer that failed with nothing
     narrowing it, a 5xx included. As built by #242, a 5xx the origin kept answering was waited out and
     reported as a lost network. Since #254 a network is lost only where no server answered at all: a DNS
     failure, a refused or reset connection, a timeout. A request that met any HTTP status found a network,
     so a 5xx spends the request's budget (rule 14's addendum) and then fails the item, named
     `Transient.Network`. A segment the edge refused or lost is `Transient.CdnEdge`, and does the same.
   - **A download's requests are stamped with their `LoadKind`**, read off the request, because the class
     of a refused segment depends on it and a download has no media source to stamp by kind. Media3's
     segment downloader asks for every manifest, and no segment, as a compressible request.
   - **A stopped download resumes on a timer, not on a platform callback.** The wait is jittered and
     doubles from two seconds up to five minutes, with no count, and the backoff every retry draws
     supplies it. A timer also catches a network the platform still reports as connected. Resuming when a
     platform condition returns is #243's (rule 11's addendum). A process that finds an item stopped this way tries it at once.
   - **A resumed download reports progress from the bytes the cache holds.** Its state change is not
     reported as downloading, because the progress it carries is the index's, and Media3 writes that on a
     timer. The downloader's first progress report announces it instead.

   *Addendum (2026-09-17, #244).* Which leaf, and where the fact is detected:

   - **The leaf is `FailureClass.Storage.Full`**, a branch of its own, not retryable and capped at rung 6,
     with an eighth message key, `superplayer_error_storage_full`. It is not a `Device` leaf, because every
     one of those is the device failing to play and buckets `DECODER`. It buckets a seventh
     `FailureCategory`, `STORAGE`, and `docs/telemetry-schema.md` argues why the category grew and why
     `SCHEMA_VERSION` did not: no playback session reaches the value.
   - **The cache's download half detects it, not this module.** `CacheDownloads.writerFor` writes through a
     sink that asks `StatFs` what the volume can take before a request's first write, and again once its
     writes would pass that reading. It raises core's `StorageFullException`, carrying the bytes to write and
     the bytes available. The platform's own `ENOSPC` is translated to the same exception, for space another
     writer took after the reading. The sink is where the volume and the writes both are; this module sees
     neither. A volume the platform does not describe refuses nothing.
   - **The item fails at once, on every store.** Thrown as an I/O failure, it would be asked for again by
     Media3's retries onto a disk that is still full, and read as a lost network by a resilience. So this
     module rethrows it as something Media3 does not retry. That decides *when* the item fails, off the
     evidence core raised. What it failed *with* is still the classifier's, so a store without a resilience
     fails the item at once and unnamed, as #242's addendum has every such store fail.
   - **What the failed item wrote is kept, pinned.** Nothing unpins a download but its removal (rule 7), so
     an enqueue after room is made continues from the bytes the cache holds, as a resumption does.
   - **The budget still refuses nothing.** #244 asked that a cache budget unable to hold a download beside
     what is pinned fail it too. Rule 7 decided otherwise: pinned content may exceed the budget, and only the
     disk fails an item. A consumer who wants downloads held to a size of their own opens a second cache
     for them, which is rule 7's remedy.

10. **Battery-not-low and storage-not-low are correctness too; the network requirement is unmetered by
    default and the viewer's to relax.** Draining a battery that is already low and filling a disk that
    is already short are wrong for every app, so no profile and no consumer may turn either off. The
    network is different in kind: whether a person will spend their data allowance on a film is *their*
    decision, and the familiar "download over mobile data" setting exists because people make it
    differently. So the store requires an unmetered network unless the consumer sets it to accept any
    network — store-wide and changeable at runtime, because Media3's requirements are per manager and
    because the choice is a setting rather than a property of one title — and even then nothing is
    downloaded on a metered network while Data Saver restricts the app's background data, the rule
    ADR-0010 rule 11 made for prefetch. It is not a profile's to vary: it depends on the viewer's
    consent, which no `PlaybackProfile` and no `PlaybackConditions` observes. "Unmetered" is the
    platform's `NET_CAPABILITY_NOT_METERED` and not "Wi-Fi": an unlimited Ethernet link (the usual
    television case) counts and a phone's hotspot does not, which is the fact about cost the rule is
    for.

11. **The module schedules through `WorkManager` under all three constraints, with a scheduler of its
    own and a battery watcher of its own.** Media3's `WorkManagerScheduler` cannot state battery-not-low
    (see *Context*), so the module schedules persisted `WorkManager` work under `Constraints` of its
    own — `NetworkType.UNMETERED` or `CONNECTED` per rule 10, `setRequiresBatteryNotLow(true)`,
    `setRequiresStorageNotLow(true)` — which is what makes a pending download survive process death and
    reboot. `DownloadsService` hands its outstanding queue to that work whenever it stops with work
    left, because Media3 calls a `Scheduler` only from its own `DownloadService`, which rule 3 does not
    extend. While the process lives, `DownloadManager`'s requirements carry the network and the storage,
    and the module watches the battery and stops downloads with that reason when it runs low. Why a
    library scheduling this work does not contradict ADR-0012 rule 10 is rule 13's.

    *Addendum (2026-09-17, #243).* How the three conditions were built, decided where they were:

    - **A download a condition holds is `STOPPED`, naming it, not `QUEUED`.** The item's `stopReason` says
      which: no unmetered network, no network, Data Saver, battery low or storage low. Several can hold at
      once, and the item names the first in that order, and names a condition before a lost network, since a
      download the network came back to would still wait for it. Nothing is written into the index for a
      condition: the network and storage are the manager's requirements, and the battery and Data Saver
      *pause* the manager, so a process that opens the store reads all of them afresh.
    - **Nothing is fetched while a condition holds, the manifest included.** Track selection reads the
      manifest (rule 12), so an item enqueued while held keeps its request in memory and is selected once
      every condition holds, and a lapse abandons a read in progress. A process that dies first loses that
      enqueue; persisting a request before its tracks are chosen would need a second index row that Media3's
      stream-key merge cannot later narrow.
    - **One piece of unique work per process, not per store or per item.** It waits under an unmetered
      network, or any network where every pending store accepts one. When it runs it asks each open store to
      read its conditions again and asks to be retried, so it stays persisted under `WorkManager`'s own
      backoff, until a store finds nothing pending and cancels it; never on a store's release, since what was
      pending still is. In a process with no store open, after a reboot, it can resume nothing until #246's
      service opens one, and it retries rather than ending so the schedule survives until then. Nothing
      caches what `WorkManager` holds: a store enqueues the work again whenever what it wants changes. The
      backoff grows while a long download runs in a live process; a store that newly has something pending
      enqueues afresh, which starts it over.
    - **Rule 15's receiver lives while a download is *pending*, not only while one runs.** This amends rule
      15's wording: a download held by a low battery is not running, and it is the receiver that hears the
      battery recover. A store with nothing enqueued still registers nothing, which is what the rule counts.
    - **The battery is read as `WorkManager` reads it, with one departure.** Low is a level at or under 15%
      of the scale, and an unknown status is not low. No reading at all is not low here, where `WorkManager`
      holds work on it: a device always has a sticky battery broadcast, so none is a platform that says
      nothing, and refusing on it would refuse with nothing to show the viewer.
    - **The schedule starts the app's service (#246).** The work carries the class `Downloads.Builder.setService`
      named in its own input data, which is `WorkManager`'s database and not storage of SuperPlayer's choosing,
      and starts it when it runs, as Media3's `WorkManagerScheduler` starts its `DownloadService`. The service
      opens the store, which resumes what the last process left. The platform may refuse a foreground-service
      start from the background; the refusal is caught, the work retries under its backoff, and a store the app
      opens itself resumes the downloads regardless. A store that names no service starts nothing, and its
      schedule waits for a store to be opened, as before.
    - **`meteredNetworksAllowed` is not persisted.** It is the viewer's setting and lives with the app, which
      sets it on each store it opens; what the last store scheduled carries it across a reboot.

### Choosing what to download

12. **Which rendition is downloaded is decided behind `PlaybackPolicy`, as a fifth half of
    `PlaybackDecision`; which audio languages and subtitles are the consumer's argument.** A rendition
    ceiling for download has different right answers for different content and devices — a phone and a
    television, a data-saver profile and a video-on-demand one — which is ADR-0005's own definition of
    policy, so it is a `DownloadSelectionPolicy` with a default every existing `PlaybackPolicy` inherits,
    set per profile in `StaticProfilePolicy` with a reason per row. It is consulted once, at enqueue,
    with the conditions observed then, because a download has no re-application point: bytes already
    written at one rendition are not re-chosen mid-item. Device refusals stay constraints under
    ADR-0009 rule 2 — a rung the display cannot show or no declared decoder reaches, and for a protected
    download the *secure* decoder's table (#211) — so a policy can narrow below what the device allows
    and never widen past it. Languages are different in kind: they name *what* the viewer wants, as
    `contentId` does, and no condition changes the right answer, so they travel on the enqueue call and
    no policy sees them. The rest of the ladder, and every language not asked for, is not downloaded.

    *Addendum (2026-09-17, #241).* Four things this rule left unsaid, decided where it was built:

    - **One rendition, the highest under the ceiling the device's decoders can play.** A ceiling on its
      own would download every rung beneath it. A download takes only the top one, because bytes written
      cannot adapt later and each further rung is storage spent on pictures nobody sees. The decoders
      are the renderer capabilities Media3's selector already reads. The display is **not** a ceiling
      here. What a screen can show limits a rendition's shorter edge, and Media3 can cap only width and
      height. Capping height at the display's shorter edge would put every rung of a vertical clip over
      the cap, and Media3 would fall back to the lowest one. The display refusal is left open, not
      approximated wrongly. The ceiling is the `PlaybackPolicy` the store was given
      (`Downloads.Builder.setPolicy`), or its profile's static one.
    - **A declared language the content does not carry is skipped, never refused.** Refusing would fail
      a whole item over one of several preferences. Media3's selector treats a language as a preference
      and would select a different track in its place, so languages are filtered against what the
      manifest offers before they are handed on, and nothing is taken for a language that was skipped.
    - **With no declared audio language carried, the download takes the audio a player would choose.**
      That covers nothing declared as well as nothing matching. It is one track, never every language.
      This is the one place the rule's last sentence gives way: a download with no audio is not the
      content the viewer will watch, while one in an undeclared language is at worst the language the
      content is mostly watched in. Subtitles have no such exception: with none carried, none are taken.
    - **A player of the download is narrowed to what was downloaded.** The download's tracks travel on
      the item as Media3's stream keys, laid on by the one media source factory every path builds
      through. Otherwise a player's own preference, such as the device's language or a rung the network
      estimate allows, would select a track that is not on disk and fetch it. A cache no store was opened
      over answers the question from its in-memory pins without a read (rule 15).

### The licence follows the download

13. **A protected download acquires its offline licence after its manifest and before its first media
    byte, and releases it as part of its removal.** Acquisition runs the licence exchange of the
    `PlaybackDrm` the store was built with, over rule 6's chain — so it carries the app's credential and
    spends `RetryPolicy.licence` — and writes to the `LicenceStore` the consumer passed (ADR-0012 rule
    8); an item whose licence is refused fails typed having spent no media bytes. ADR-0012 rule 9 is
    unchanged: the expiry split is readable from the licence store before playback, and a downloaded
    item with a dead licence plays nothing and says why. Removal deletes the item's bytes and unpins it
    at once — the storage is the person's the moment they asked — and queues the licence release under
    the store's network requirement, because a release is a round trip and a removal made on a plane
    must still complete. Until the release is acknowledged the licence store reports the licence as
    pending release and no player is given it; a key-set id the device no longer knows, after a factory
    reset or a restored backup, is dropped rather than retried (ADR-0012's Consequences).

    **Two rules of ADR-0012 are refined here, for downloads only.** *Rule 9's addendum* made
    `store.over(player)` the shape because the exchange must carry the app's credential, spend
    `RetryPolicy.licence` and be stamped `LoadKind.LICENCE`, and a store with an HTTP stack of its own
    would answer to none of them. A download store's exchange runs over rule 6's chain, which is core's
    and carries all three, so it is the same chain reached without a player rather than a second stack;
    `over(player)` stays the shape for a licence acquired outside a download. *Rule 10* refused to
    choose a background-work configuration on a consumer's behalf. It still holds for renewal, and for
    releasing a licence acquired directly. A download's work — fetching it under rule 11's constraints,
    and releasing its licence when it is removed — is scheduled because the consumer asked for the
    download and then for its removal, and the constraints are rules 9 and 10's rather than a timetable
    the library chose.

    *Addendum (2026-09-17, #245).* How the binding was built, decided where it was:

    - **`LicenceStore` carries its internal half as a value, as `ContentCache` does.** Core's public
      abstract class takes an internal `StoredLicences` — read a licence's standing, write one, mark one owed
      a release, list those owed, forget one — which `superplayer-drm`'s `OfflineLicenceStore` fills over its
      own index. So the tracked API gains a type with no members, and no internal override leaks into the drm
      module's surface. What a licence's expiry and renewal-due mean stays the drm module's reading, handed
      over as values, so a download and a list screen cannot disagree about a licence.
    - **The exchange graph is core's `DownloadDrmExtension`, beside `EngineDrmExtension` and not inside
      it.** A download is not an engine, and the hand-written engine extensions core's own tests use need
      not learn about one. `Drm.widevine` answers it with the graph a player of the content would open, at
      the level it would open at, and throws `SecurityDowngradeRefusedException` where rule 11 refuses a
      player, so a refused level fails the item at enqueue rather than at playback. The chain is
      `TransferChain.downloadLicenceChain`, the download transport stamped `LoadKind.LICENCE`.
    - **Acquisition runs between the track choice and the manager.** The manifest read is given the graph,
      so the device's own renderers see a protected format as one they can decrypt. The protected format the
      choice selected is what the licence is asked for, on a thread of the store's own, and only once it is
      stored is the download handed to Media3's manager. A refusal therefore fails an item that has written
      and pinned nothing. The item is queued throughout, and an enqueue made during it is lost with its
      process, as one made during the manifest read already was. A licence still in force is kept on a
      second enqueue rather than acquired again, which would leave the first counted against the device at
      the server.
    - **Protection is what the manifest read sees.** A DASH MPD's `ContentProtection` is. An HLS stream's
      key is only where the multivariant playlist declares it, in `EXT-X-SESSION-KEY`: Media3 reads no media
      playlist to choose tracks, so a stream that declares its key only in each media playlist's `EXT-X-KEY`
      downloads with no licence and cannot play offline. `DownloadLicenceTest` records that rather than
      asserting it as desirable.
    - **A release owed is a table of the licence index, keyed by key-set id.** Removal moves the licence
      there in one transaction before the bytes are deleted, so `licenceFor` stops answering at once. One
      content id can owe several releases, removed and downloaded again before a network came back, and a
      licence an acquisition replaces, expired or overtaken by a second enqueue, is owed one too rather than
      forgotten. A release forgets its own key-set id and never a licence in force. A pass releases
      every owed licence where the store's network requirement holds, read afresh rather than as the
      manager last applied it. It runs on removal, on every change of conditions, on each run of the
      scheduled work, and when a store is opened over licences a last process left owed. A release that
      fails stays owed, and the schedule stays wanted while one does.
    - **The licence store is read and written on the store's thread.** Removal marks the release owed there,
      so no moment exists where the download is gone and its licence playable; an item's licence is read
      there on each state change, as `download` and `downloads` already read the download index. Each is one
      row of a small table, unlike the pin #240 moved off `enqueue`, which waited on the cache's lock.
    - **What is a player's is expressed on the item.** `DownloadItem.licence` carries whether the licence has
      expired, whether renewal is due, and, on a store with a resilience, the expiry as the typed error a
      player of it ends with — core dresses the exception with the same code a refused player's session
      carries, so both are `Drm.LicenceExpired`.

    Not built, each named where it would go. The licence chain's credential and `RetryPolicy.licence` were
    listed here until #260 built them (rule 14's addendum for #260). A key-set id the device no longer knows is not
    dropped: it cannot yet be told from a release the network lost, so it stays owed, and keeps the
    schedule wanted. One licence is acquired per download, for the first protected format selected, so
    content whose tracks are licensed under separate keys is not yet supported. A store released while an
    acquisition is in flight stores the licence and never hands the download to the manager, so that licence
    is released by nothing.

14. **Downloads spend the download store's resilience, not a player's, and the module is what spends
    the budget.** Where the store was built with a `PlaybackResilience`, its `HeaderProvider` repairs a
    refused 401 or 403 inside the transfer, as on a player, because the header-refresh layer is in rule
    6's chain. The *budgets* cannot reach a download the way they reach a player: Media3 1.11's
    `SegmentDownloader` takes no `LoadErrorHandlingPolicy`, and `DownloadManager` offers only a
    manager-wide `setMinRetryCount`. So the module spends `RetryPolicy`'s manifest and segment budgets
    itself, retrying a failed item from what the cache holds (rule 9) with `Backoff`'s jitter between
    attempts, and a licence exchange spends `RetryPolicy.licence` through the load-error policy its DRM
    session manager is handed, as on a player. Where the store was built without resilience, an item
    retries as Media3's `DownloadManager` does by default and nothing more. The profile given to
    `setProfile` is what `StaticProfilePolicy` reads for both rule 12's half and the budgets, so a
    store has one profile as a player does.

    *Addendum (2026-09-17, #254).* How the budgets are spent and the credential repaired, decided where it
    was built:

    - **The downloader wrapper retries, not the manager.** The store's `PinningDownloader` catches a failed
      attempt, asks the resilience how long to wait, and calls Media3's downloader again, which counts what
      the cache holds before it fetches. The item never leaves `DOWNLOADING`, so no viewer sees a transient
      `FAILED`, and its progress continues from the cached bytes. The other option re-added a failed item
      after a wait. It would have reported `FAILED` between attempts, or needed that state hidden from every
      listener and from `download`, and Media3 would have restarted the item's task each time. The manager's
      `minRetryCount` is zero on a store with a resilience, so a failure the wrapper gives up on is final;
      without one it is left at Media3's default, and nothing about retrying changes.
    - **A wait is a post on the store's thread, not a sleep.** The download thread waits on a latch that a
      delayed post releases, with `Backoff`'s jitter (ADR-0011 rule 12), and that a cancellation releases
      too: a stop, a removal, a lapsed condition or the store's release ends the wait at once. Media3's own
      waits are `Thread.sleep` on its task thread, which no looper's clock moves.
    - **A budget bounds one request, counted afresh where the download got further.** The count resets when
      the bytes reported have moved since the last failure, as Media3's manager counts its own retries. A
      request stamped `LoadKind.MANIFEST` spends `RetryPolicy.manifest`, and every other request
      `RetryPolicy.segment`, the stamp rule 9's addendum already puts on each request. A class that says
      retrying cannot help is not retried, which is rung 1's first refusal (`RetrySameUrl`). A download has
      no host, variant or source to fall back to, so rung 1 is all of its ladder.
    - **The budgets are the store's policy's, as last consulted on the store's thread.** They are consulted
      when the store opens and again as each item is enqueued, and held for the download thread to read.
      `PlaybackPolicy.decide` is its caller's thread's to call. A download is not re-consulted on a trigger,
      because a store observes none.
    - **The header-refresh layer reaches the chain through `DownloadResilienceExtension`.** A member builds a
      `TokenRefreshLayer` over the resilience's `HeaderProvider`, once per store, and
      `TransferChain.downloadChain` composes it closest to the transport, under the kind stamp. So a refused
      401 or 403 is repaired inside the transfer and costs no retry, and `superplayer-offline` still depends
      on core alone (rule 1). With no provider there is no layer: a player needs a pass-through only to make
      core stamp its requests, and a download's chain stamps them anyway.
    - **A licence exchange's budget and credential were left unbuilt here**, and #260 built them: the addendum
      below.

    *Addendum (2026-09-17, #260).* A licence exchange spends the budget and gets the repair, as a player's does:

    - **The licence budget reaches the session manager as a player's does: through its load-error policy.**
      A licence request is made inside Media3's `DefaultDrmSession`, which asks the session manager's
      `LoadErrorHandlingPolicy` and nothing of the store, so a wrapper like `PinningDownloader` has nothing
      to wrap. `DownloadResilienceExtension.downloadLicenceErrors` builds the policy a player's DRM slot is
      handed (#205), once per store, and `DownloadLicences` passes it in `LicenceContext.loadErrors`, which
      `superplayer-drm` already sets on the manager. The policy reads `C.DATA_TYPE_DRM` as the licence
      budget, rung 1 is its whole climb, and `Backoff` draws the wait. The other option was an exchange
      retried by the store around `OfflineLicenseHelper`. It would have left Media3's own retries running
      underneath and opened a new session graph for every attempt.
    - **The wait is on the session's request thread, not a sleep.** Media3 posts a retry as a delayed message
      on the session's `HandlerThread`, which runs on the clock a test moves, and the licence thread blocks
      only on the exchange's answer. `DownloadLicenceBudgetTest` reads that clock at each ask.
    - **The budget is the decision the store last consulted.** The policy reads the store's decision through
      a `DecisionInForce` on every consultation, and the store updates the decision on its thread when it
      opens and as each item is enqueued, before the item's licence is acquired. `PlaybackPolicy.decide` is
      never called on Media3's request thread.
    - **One header-refresh layer per store, under both chains.** `TransferChain.downloadLicenceChain` composes
      the same layer `downloadChain` has, under the licence stamp. A player's one layer serves its licence
      and its media, and a credential repaired on one serves the other. So a refused 401 or 403 is repaired
      inside the transfer and spends no retry.
    - **Without a resilience nothing changes.** The context carries no policy and the chain no layer, so
      Media3's own licence handling answers, which `DownloadLicenceBudgetTest` counts beside a store that has
      one.

### Pay nothing

15. **A player, a pool, a session or a cache that no download store was opened over allocates and
    registers nothing for downloads, and a test counts it.** Nothing about a player's builder changes:
    no player has a download setter. A cache opened alone creates no download index table and serves no
    manifest from disk (rule 8), so its golden traces are byte-identical before and after Phase 7. A
    store opened with nothing enqueued enqueues no `WorkManager` work and registers no battery receiver;
    the receiver exists only while a download runs. It is asserted the way ADR-0010 rule 13 and ADR-0012
    rule 13 are — the count taken with nothing attached and again with a store attached, so the counter
    is shown to see what it counts.

## Consequences

**Easier.** Every Phase 7 issue after this one (#239–#247) has a written answer to the question it
would otherwise settle at a call site: where bytes go, under which key, over which chain, what a
consumer may vary, and who releases a licence. The two standing obligations ADR-0010 and ADR-0012
left for this phase are discharged without a second cache, a second key policy or a second licence
store. A download is testable under `check` because it crosses the same transport slot a player does,
and the claim a user cares about — "it plays on a plane" — is a count of zero requests, not a
screenshot.

**Harder.** Rule 1 adds a public core type, `LicenceStore`, and moves `OfflineLicenceStore` under it:
a tracked API change in two modules in one commit (#245). Rule 3 costs the module its own service
lifecycle: Media3's `DownloadService` handles foreground promotion, the notification's cadence and the
scheduler's restart, and `DownloadsService` has to do those without extending it. That is the most
expensive rule here, and the price of keeping an unstable supertype out of every consumer's service.
Android's foreground-service rules for the `dataSync` type tighten release by release; that surface
is now the module's to keep current, where extending Media3's class would have made it Media3's.

Rule 11 costs a `Scheduler` and a battery watcher Media3 would have supplied had it been able to state
the constraint. It also puts `androidx.work` on the classpath of every app that downloads, with its
`THIRD_PARTY.md` row, and `WorkManager` initializes itself through App Startup unless the consumer
configures it otherwise — which the module's documentation says rather than decides.

Rule 4 widens a ceiling that was written down one phase ago. The widening is one shape and it is
stated, but it is the kind of sentence that gets widened again; the defence is the same as before —
a module that wants friendship for a reason neither shape covers wants a public API instead.

Rule 8 makes the cache layer's routing depend on the cache's contents for manifests, which is new: a
manifest request of pinned content is a disk lookup before it is a network request. It is bounded to
pinned content, and a download that stored a manifest the origin has since replaced plays what was
downloaded — which is what "downloaded" means, and the reason live content is refused.

Rule 10's store-wide network setting cannot express "this one title over mobile data". A consumer who
needs that enqueues it while the setting accepts any network and sets it back; Media3's requirements
are per manager, and a second manager over one cache is what rule 2 forbids.

Rule 13 leaves a device-bound credential on the device between a removal and the network returning.
It is unplayable in that window — the store reports it pending and gives it to no player — and it
expires on its own schedule if the network never returns.

## Alternatives considered

**Declare `superplayer-cache` and `superplayer-drm` as dependencies of `superplayer-offline`.**
Rejected under rule 1. It is allowed by `docs/modules.md` and it is simpler to write, and it makes
every app that downloads clear content carry the Widevine module — the additive promise the module
table exists to keep. Depending on `superplayer-cache` alone would not even work: what the module
needs from it is internal, and Kotlin `internal` does not cross a Gradle dependency.

**A separate `superplayer-offline-drm` artifact for the licence binding.** Rejected. It keeps the
dependency out of clear-only apps as rule 1 does, at the price of a fourteenth module whose whole
content is one binding, and a consumer who adds `superplayer-offline` and `superplayer-drm` and
forgets the third gets protected downloads that fail at playback. A core abstract type is the shape the
project already uses for exactly this (ADR-0010 rule 3).

**A second `SimpleCache`, in a directory the download store names.** Rejected under ADR-0010's
standing obligation: a second storage decision, a second key policy, and a downloaded item that a
streaming player reads under different keys — F7 reopened.

**Let Media3 key downloads with `DownloadRequest.customCacheKey`.** Rejected. It is one key per
*request*, suited to a progressive file, and a segmented stream's segments need one key each; the
per-content key factory of rule 5 is the only place both the content id and each segment's path are
present.

**Public opaque handles instead of a seventh friend.** Rejected for ADR-0009's reason, which ADR-0010
rule 3 repeated: a public type that is empty until a module passes a Media3 object through it is an
`@UnstableApi` leak with a delay.

**Extend Media3's `DownloadService` and accept the marker on the consumer's subclass.** Rejected under
rule 3 and ADR-0003's argument: `docs/api-surface.md` would fail the module's own abstract subclass, and
a consumer's service would opt in to an unstable API to receive one callback.

**Media3's `WorkManagerScheduler`, with battery-not-low dropped or approximated by `DEVICE_CHARGING`.**
Rejected under rule 11. Dropping it is F4 left unfixed; requiring charging is a stronger constraint
that makes a download on a full battery wait for a cable, which is a different product.

**All three constraints fixed, with no way to download over mobile data.** Rejected under rule 10.
It makes a common, legitimate viewer choice unexpressible, and apps would route around it by opening a
second download stack — the outcome ADR-0010's obligation exists to prevent.

**A "Wi-Fi only" requirement rather than "unmetered".** Rejected under rule 10. The platform has no
constraint for it, so it would need a transport watcher of its own; and it is the wrong fact — a
hotspot is Wi-Fi and metered, an unlimited Ethernet link is neither Wi-Fi nor metered, and cost is what
the viewer is protected from.

**A per-profile network requirement.** Rejected under rule 10: the choice depends on the viewer's
consent, which no profile observes.

**Rendition choice as a consumer argument, beside the languages.** Rejected under rule 12 and ADR-0005:
a bitrate ceiling written at a call site is a policy constant at a call site, with different right
answers per device and profile. **Languages behind `PlaybackPolicy`** is rejected the other way: no
observation changes which language a viewer asked for.

**Release a download's licence only when the consumer calls release, as ADR-0012 rule 10 has it for
licences in general.** Rejected under rule 13. The removal is the consumer's call; a store that
deleted the bytes and kept the credential would leak a device-bound licence for every download removed
without a second call nobody remembers to make.

**Pin on completion rather than at enqueue.** Rejected under rule 7: a download larger than the
unpinned headroom would evict its own first segments before its last arrived.

## References

- `DownloadManager`, `DownloadService`, `DownloadHelper`, `DownloadIndex` and `Downloader` — Media3's
  download stack, every one `@UnstableApi`:
  https://developer.android.com/media/media3/exoplayer/downloading-media
- `Requirements` — the five conditions Media3 can state, none of them battery-not-low:
  https://developer.android.com/reference/androidx/media3/exoplayer/scheduler/Requirements
- `WorkManagerScheduler` and `Scheduler`:
  https://developer.android.com/reference/androidx/media3/exoplayer/workmanager/WorkManagerScheduler
- `WorkManager` `Constraints` — `setRequiredNetworkType`, `setRequiresBatteryNotLow`,
  `setRequiresStorageNotLow` — and persistence across reboot:
  https://developer.android.com/reference/androidx/work/Constraints
  and https://developer.android.com/develop/background-work/background-tasks/persistent
- `NetworkCapabilities.NET_CAPABILITY_NOT_METERED` — what "unmetered" means:
  https://developer.android.com/reference/android/net/NetworkCapabilities#NET_CAPABILITY_NOT_METERED
- `ConnectivityManager.getRestrictBackgroundStatus` — Data Saver:
  https://developer.android.com/develop/connectivity/network-ops/data-saver
- Foreground service types, `dataSync` and its permission:
  https://developer.android.com/develop/background-work/services/fgs/service-types
- `CacheDataSource`, `CacheKeyFactory` and `CacheWriter` — where a download's key is asked for:
  https://developer.android.com/reference/androidx/media3/datasource/cache/CacheKeyFactory
- `OfflineLicenseHelper` — `downloadLicense` and `releaseLicense`, rule 13's two round trips:
  https://developer.android.com/reference/androidx/media3/exoplayer/drm/OfflineLicenseHelper
- [ADR-0003](0003-implement-player-by-delegation.md),
  [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md),
  [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rules 1 and 2,
  [ADR-0007](0007-publish-the-player-as-a-session-and-resolve-content-by-id.md) rule 4,
  [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md) rules 2 and 7,
  [ADR-0010](0010-cache-in-consumer-chosen-storage-and-preload-on-the-one-chain.md) rules 1, 2, 3, 4, 11,
  12 and 13, [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md)
  rules 1 and 4, and [ADR-0012](0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md)
  rules 2, 8, 9, 10 and 13; `KotlinFriendModules.kt`'s KDoc; `TransferChain`'s KDoc;
  `ContentKeyedCacheLayer`'s KDoc; `docs/modules.md`; `docs/api-surface.md`.
- `PRD.md` §2.4, §3.5 and Part 4; #237 — the phase this decides for; #239–#247 — the issues that cite
  these rules.
