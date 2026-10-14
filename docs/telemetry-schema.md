# Telemetry schema

What SuperPlayer measures, what each number means, and what it excludes.

This is the document a data engineer reads instead of the source. Everything here is also true of
the Kotlin types in `superplayer-core` — `TelemetrySink`, `TelemetryEvent` and the value types
around them — but the types say what a field *is* and this says what it *means*, which is the half
that decides whether two teams' dashboards can be compared.

That is the whole point. The problem this library's telemetry exists to solve (`PRD.md` F8) is not
that apps have no metrics; it is that every app invents its own definitions, so no two numbers can
be compared — across apps, across platforms, or against a vendor's dashboard. The fix is not code.
The fix is a written definition, per metric, that says exactly what is counted and what is left out.
The code is what makes this document true.

**Where the definitions come from.** [CTA-2066][cta2066], *Streaming Quality of Experience Events,
Properties and Metrics*, is the reference for every metric below, and each one cites it. Where
SuperPlayer's definition departs from the standard — because the standard leaves a choice open, or
because Android's engine cannot observe what the standard assumes — the departure is stated **with
its reason**, in the metric's own section. A departure that is not written down here is a bug.

[cta2066]: https://shop.cta.tech/products/streaming-quality-of-experience-events-properties-and-metrics

---

## Read this first

### Events are sessionized

Every event belongs to a **session**: one player playing one piece of content. A session opens when a
player takes content on — `setMediaRequest`, or content a `PlaybackSession` resolved from a media id
sent by a notification, a car head unit or a watch — and closes when the player is released, recycled
into a `PlayerPool`, or given different content.

- `sessionId` is opaque, unique, and the same on every event of one session. **It is also CMCD's
  `sid`** — the same string, sent to the CDN on every request of the session, which is what makes the
  join below possible. Minted by `superplayer-core`, not by the collector, precisely so that the two
  seams cannot drift apart. On iOS and tvOS it is *read* by core rather than minted — the SDK fills
  CMCD's `sid` itself and cannot be told what to put there — for the same reason; see
  [On iOS and tvOS](#on-ios-and-tvos).
- `contentId` is the app's own `MediaRequest.contentId`. **Never a URL.** The same content behind two
  CDNs is one `contentId`, which is what makes a per-title metric a per-title metric.
- A session begins with exactly one `SessionStarted` and ends with exactly one `SessionEnded`.

A device playing several things at once — a feed holds many players — produces several interleaved
sessions. The `sessionId` is what separates them; nothing else does.

**One gap, stated plainly.** A consumer that drives a player with Media3's own `setMediaItem` instead
of `setMediaRequest` moves it to content that has no identity, so no session can be opened for it and
what follows is reported under the previous content's id. A player driven through `setMediaRequest`
throughout cannot produce this. Mixing the two APIs on one player is what does. On iOS and tvOS the
counterpart, `setMediaURL`, is the facade's own method, so it ends the open session and opens none;
the gap there is an item handed to `player.avPlayer` directly, which the facade never sees.

### Joining to the CDN's log

CMCD (CTA-5004) sends the client's own view of each request — the bitrate asked for, the buffer, the
throughput, whether the request was holding playback up — to the CDN, which writes it into its access
log. That log and this schema describe the same sessions from the two ends of the connection, and
the join between them is an equality:

```sql
SELECT q.session_id, q.content_id, c.status, c.cache_status, c.time_to_first_byte_ms
FROM   qoe_events q
JOIN   cdn_log    c ON c.cmcd_sid = q.session_id
WHERE  q.event = 'RebufferStarted'
```

Three things a data engineer needs before writing that query.

- **Where `sid` lands in the CDN's log is the CDN's business.** SuperPlayer sends the keys; whether
  they arrive as logged fields, as a raw `CMCD-Session` header column, or not at all depends on the
  CDN's own logging configuration. That is the first thing to check when the join returns nothing.
- **The keys travel as request headers by default**, and can be moved into a `CMCD` query parameter
  with `SuperPlayer.Builder.setCmcdMode(CmcdMode.QUERY_PARAMETER)` for a delivery path that logs
  query strings rather than headers. `CmcdMode` carries the trade-off, including the one that can
  break a signed URL.
- **CMCD is sent quoted, and only by adaptive sources.** `sid` and `cid` arrive as quoted strings —
  strip the quotes before joining — and a progressive `.mp4` produces no CMCD at all, because there
  is no adaptive request to describe. Sessions of such content appear in this schema and never in the
  CDN half of the join.
- **A prefetched item's `sid` is minted before its session exists, and may join to nothing.** A
  `PreloadCoordinator` fetches the first segment of rows a feed has not reached yet, and those requests
  carry the `sid` the row's session will report if it plays — that first segment is the request the
  join most needs. A row the viewer never reaches leaves CDN log lines whose `sid` matches no
  `SessionStarted`; no phantom session is emitted for it (ADR-0010 rule 7). Count them as prefetch
  cost, not as missing telemetry.

`cid` is the same `contentId` these events carry, so a per-title question can be asked of the CDN's
log directly, without joining at all.

#### On iOS and tvOS

The join holds on Apple platforms too, from iOS 18 and tvOS 18, and the promise above is not
Android-only. What differs is where the string comes from and which keys arrive with it; the
evidence is [`ios-cmcd-finding.md`](ios-cmcd-finding.md), and `SuperPlayerCore`'s
`CmcdBinding.swift` is the implementation (`#446`), held by `CmcdPlaybackTests` at the loopback
origin.

- **The SDK sends CMCD itself**, once core sets `AVAssetResourceLoader.sendsCommonMediaClientDataAsHTTPHeaders`
  on the asset, and its `sid` is the asset's `httpSessionIdentifier` — which core reads and uses as
  `sessionId`, so the equality is the same equality. One asset is one session, because copies of an
  asset share the identifier, so core builds a fresh `AVURLAsset` for every `setMediaRequest` and
  restore — the same content adopted twice is two sessions under two `sid`s. The value is an
  uppercase UUID string. It is on for every profile, as on Android, and `setCmcdMode(.disabled)`
  turns it off.
- **Headers only.** The SDK has no query-parameter mode, so `CmcdMode.queryParameter` is refused at
  configuration with `CmcdModeUnsupportedError` rather than quietly sent as headers, and a CDN that
  logs query strings alone sees no CMCD from an iOS device.
- **Only content with an identity.** An item from `setMediaURL` opens no session, so it is built
  without CMCD: its `sid` would join to nothing. AVFoundation still sends it an
  `X-Playback-Session-Id` of its own.
- **A rung changes the `sid` and not the session** (`#420`). A rung that re-adopts the content — the
  next source, the item rebuilt after a media services reset, a refreshed credential — builds a new
  asset, and a new asset has a new `httpSessionIdentifier`, which nothing can set. The telemetry
  session goes on under the id it opened with, because one viewing rescued is one session, so a
  CDN's requests after the rung join to no `session_id`. On Android the re-adoption keeps the `sid`.
- **No `cid`.** A per-title question asked of the CDN's log alone needs the join on iOS.
- **Below iOS 18 and tvOS 18 no CMCD is sent.** The same string still reaches the CDN on every
  request as the `X-Playback-Session-Id` header, which AVFoundation documents from iOS 16, so a CDN
  that logs that header can join an iOS 17 session on `x_playback_session_id = session_id`
  instead. That rests on the header's documentation: the spike observed the header on a later
  runtime only.
- **The keys are the SDK's.** Which CTA-5004 keys arrive, and how each is computed, is AVFoundation's
  choice rather than this library's; the finding lists the ones observed.

### The stream is lossy, and it says so

Delivery is at-most-once and bounded ([ADR-0008][adr8] rule 3). Under pressure, events are dropped
rather than queued, and nothing is retried or persisted — a process that dies takes its in-flight
events with it.

**So a metric computed by summing events can undercount.** `SessionEnded.droppedEventCount` is how
that becomes detectable: it is the number of this session's events the delivery path discarded. A
session reporting a non-zero count should be **excluded** from aggregates rather than averaged in.
A silently partial event stream is worse than none, because a rebuffer ratio computed from one is a
plausible wrong number that nobody audits.

`SessionEnded` itself is never dropped; it holds reserved capacity, because an event whose job is to
declare what was lost is worthless if pressure can lose it. Its count is finalized as it leaves the
queue rather than when the session ended, so a drop caused by memory pressure after the fact is
still on the event that reports it.

**The bound is 256 events per player**, and the drop policy is **drop the newest**: when the queue is
full an ordinary event is refused at submission and what is already queued is delivered.

*Why 256.* It is a stall the delivery path can absorb, derived from a rate and a duration rather than
picked for looking round. The sustained ceiling per session is the three periodic events every 10 s
plus, at worst, one `TrackSwitched` per 2 s segment — about 0.8 events per second. Concurrent players
are bounded by the platform's decoder limit, which no current device reports above 16, so the process
ceiling is around 13 events per second. 256 events is therefore roughly 20 seconds of a completely
stalled sink before anything is lost, and costs about 25 KB.

*Why the newest.* The front of a session is the part that cannot be reconstructed: time to first
frame happens once, and the initial `TrackSwitched` is the only statement of what the session started
at. What arrives during pressure is mostly periodic samples that recur every 10 s, and one lost
sample of a time-weighted quantity costs its weight and nothing else. The honest cost of this choice
is that an **error postmortem loses the seconds before the failure**, which is exactly what dropping
the *oldest* would have kept — and dropping the oldest cannot honour the `SessionEnded` reserve
without scanning the queue on every drop. The trade is made here rather than hidden, and
`droppedEventCount` is what makes it visible per session.

**Memory pressure empties the queue.** `onTrimMemory` at `TRIM_MEMORY_RUNNING_LOW` or above — and
`onLowMemory`, which is the only one of the two that arrives on older devices — discards everything
pending except the terminal events, counting each discard (`PRD.md` §3.4). `TRIM_MEMORY_UI_HIDDEN` is
deliberately ignored: it says the app went to the background, which is the ordinary background-audio
case and says nothing about memory.

[adr8]: adr/0008-measure-behind-an-engine-agnostic-sink-boundary.md
[adr11]: adr/0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md
[adr12]: adr/0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md
[adr14]: adr/0014-match-the-display-and-watch-it-change-behind-one-output-slot.md

### Two clocks, and which is which

Every event carries both. They are not interchangeable.

| Field | Clock | Use it for |
| --- | --- | --- |
| `timestampMs` | `System.currentTimeMillis()` — wall clock, epoch milliseconds | Joining these events to the app's other events, and to anything outside the device |
| `monotonicTimeMs` | `SystemClock.elapsedRealtime()` — milliseconds since boot, deep sleep included | Ordering events within a session, and every duration |

The wall clock can step backwards when the device's clock is corrected, by minutes or by years; the
monotonic clock cannot, which is why **every duration in this schema is measured on it**. A
monotonic reading is comparable only against another reading from the same boot of the same device —
it is meaningless as an absolute time, and it must not be treated as one.

Where the two disagree about the order of two events in one session, the monotonic clock is right.

### Ordering

Events within one session arrive in the order they occurred. **No order is promised between
sessions**: a shared delivery context interleaves them. A consumer that needs a global order builds
one from `sessionId` and the timestamps.

---

## Versioning

Every event carries `schemaVersion`. The current version is **2**.

**The version tracks meaning, not shape** ([ADR-0008][adr8] rule 5). That distinction is F8's: a
dashboard breaks on a changed *definition*, not on a new field it ignores.

**Changes that do _not_ bump the version:**

- a new event type is added
- a new optional field is added to an existing event
- KDoc, wording, or the log format of `LogcatSink` changes

**Changes that _do_ bump the version:**

- what an existing field counts changes
- its units or its denominator change
- what a metric excludes is narrowed or widened — a different seek-exclusion window is a bump
- a field or an event type is removed

Because the event types are `superplayer-core` public API, every one of these changes also appears as
a reviewed diff in `android/superplayer-core/api/superplayer-core.api` (`docs/api-surface.md`). A definition
cannot change quietly.

### Release notes

**Version 2 — the failure classification (`#183`, `#86`).**

`PlaybackFailure` gained a `classification`, and on its own that would have been an addition of shape
and no bump at all. What moved the version is the field beside it: on a player built with
`superplayer-resilience`, **`PlaybackFailure.category` is now derived from the classification rather
than from Media3's error-code band**, and the two disagree for real failures ([ADR-0011][adr11]
rule 3). The class is the right answer where they differ, and the bucket changes to match:

| Failure | Band said | The class says | Why the class is right |
| --- | --- | --- | --- |
| A live playlist frozen at the origin (`StaleLivePlaylistException`, `likelyCause = ORIGIN`) | `NETWORK`, because it is an `IOException` | `SOURCE` (`Content.SegmentGap`) | The transfer worked; the segments the playlist promised were never published |
| A read past the end of a segment (`ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`) | `NETWORK`, on the 2xxx band | `SOURCE` (`Content.SegmentGap`) | The object is shorter than the manifest described it |
| A format the device does not support (`ERROR_CODE_DECODING_FORMAT_UNSUPPORTED`) | `DECODER`, on the 4xxx band | `SOURCE` (`Fatal.Unsupported`) | Nothing is wrong with this device's decoding; the content will not play here or anywhere with this ladder |
| An audio-track or frame-processor failure (5xxx, 7xxx) | `RENDERER` | `DECODER` (`Device.*`) | The remedies are the decoder rungs, which is the distinction a failure rate is read for |
| A miscellaneous or unrecognised code (1xxx, a later Media3's, a session code) | `UNKNOWN` | `NETWORK` (`Transient.Network`) | The classifier is total and has no unknown class: an unrecognised code is far likelier to be a transfer that can be retried |

So a failure-rate dashboard split by `category` will see slices move on players with resilience
attached, and `RENDERER` and `UNKNOWN` go empty on those players. Nothing changes on a player without
the module: the band decides, exactly as it did at version 1, and `classification` is null.

`code` did **not** change meaning: it is `errorCodeName` at version 2 as it was at version 1. The
classification is a separate field because the two are separate facts — which code the engine raised,
and what SuperPlayer made of it — and a pipeline needs the first to find the failure in a logcat.

**Version 2 stands — the DRM classification split (`#206`).** The `Drm` branch of the taxonomy grew
two leaves, `Drm.LicenceExpired` and `Drm.SystemError`, and `Drm.LicenceAcquisition` narrowed to the
failure it is named after. `SCHEMA_VERSION` did **not** move for it, and that is the deliberate
answer rather than an oversight: every leaf of that branch maps to `FailureCategory.DRM` as it did
before, so no `category` slice moves and no denominator changes. What changed is the `classification`
string, which is a *finer* value in a field whose meaning — the stable name of the class the one
classifier assigned — is unchanged, and the version tracks meaning rather than cardinality.

A pipeline still has something to do about it. Three values that never appeared before can appear
now, and a dashboard that enumerates `classification` by hand rather than grouping by it will show
them as gaps:

| Now reported | Was reported as | What it is |
| --- | --- | --- |
| `Drm.LicenceExpired` | `Drm.LicenceAcquisition` | Keys that were issued and have run out — the dead offline licence. Carries its own `userMessageKey`, `superplayer_error_licence_expired`, which is the one protection failure a viewer can be offered a remedy for |
| `Drm.SystemError` | `Drm.LicenceAcquisition` | The device's protection stack failing with no entitlement implicated, and the DRM band's fall-through — `ERROR_CODE_DRM_UNSPECIFIED` and `ERROR_CODE_DRM_SYSTEM_ERROR` included |
| `Drm.LicenceAcquisition` (narrowed) | the whole unclaimed DRM band | Exactly `ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED`, plus a refused licence load caught before the engine wrapped it |

The one `isRetryable` that flips with it is `Drm.SystemError`'s, from true to false: a `SuperPlayerError`
for an unnamed DRM failure no longer tells an app that retrying is worth anything, because nothing
about it was a transfer.

**Version 2 stands — the security level a session was delivered at (`#208`).** `SessionEnded` gained
a `securityLevel`, and the `Drm` branch gained a seventh leaf, `Drm.DowngradeRefused`. Neither moves
`SCHEMA_VERSION`, and both answers are deliberate rather than an oversight:

- The field is an **addition of shape**. No existing field's definition changes, no denominator
  moves, and a pipeline that ignores it computes exactly what it computed before. That is the same
  ruling `DecisionChanged` got, and [ADR-0008][adr8] rule 5's line between shape and meaning.
- The leaf maps to `FailureCategory.DRM`, as every leaf of that branch does, so no `category` slice
  moves — the same reason `#206` did not move the version.

What a pipeline can do about it: `SessionEnded.securityLevel` is `null` on every session that
negotiated nothing, which is every unprotected session and every protected one on a device that could
honour the level it reports. It is non-null only where [ADR-0012][adr12] rule 11's ladder engaged and
the licence server's operator permitted a lower level — so a non-null value is precisely *this viewer
had something weaker delivered than they were entitled to*, and it is the field a support engineer
reads before asking anything else. Counting it per app version and per device model is how a fused-off
secure path shows up as a fleet fact rather than as one confused ticket.

**Nothing here moved when the ladder learnt its second trigger either (`#225`).** A device the
provisioning service will not certify at the level it reports now falls to the permitted one
mid-session, where before it ended the session. `SessionEnded.securityLevel` is read when the session
ends, so such a session reports the reduced level exactly as one decided before playback does — the
field is the same field, with the same meaning and the same null rule, and `SCHEMA_VERSION` stands at
2. What moves is the population, not the definition: a non-null value is now sometimes a session that
*played through* a refusal rather than one that never met one. A pipeline joining it to failures
should not expect a failure row beside it, because a rescued session reports none (ADR-0011 rule 10).

**Nothing here moved when the permission channel did (`#223`).** `#208` read the permission off an
HTTP exchange of SuperPlayer's own and `#223` reads it out of `WidevineConfig` instead, which changes
how a session decides but nothing it reports: the field is the same field with the same meaning, the
leaf is the same leaf in the same category, and `SCHEMA_VERSION` stands at 2 for the third release
running. A pipeline needs to do nothing. The one thing a reader of old data should know is that
`securityLevel` was non-null far less often before `#223` than after, and not because devices changed
— the exchange it depended on is answered `405` by most real licence endpoints, so in the field it
almost always refused.

| Now reported | Was reported as | What it is |
| --- | --- | --- |
| `Drm.DowngradeRefused` | `Drm.Unsupported` | The device cannot honour the protection level it reports and the licence server did not permit the lower one. Carries its own `userMessageKey`, `superplayer_error_protection_unavailable`: the content may not be shown on this device, which is neither a licence that failed to arrive nor a device that cannot decode |

**Version 2 stands — the secure decoder path got a leaf of its own (`#226`).** A decoder that would
not initialise on the *protected* path — `PRD.md` §3.2's third way L1 becomes unusable — is now
`Device.SecureDecoderInit` rather than being indistinguishable from any other decoder-init failure,
and it is the trigger for the same mid-session downgrade `#225` built. The version does not move, and
the reason is the same one `#206`'s did not: it is a `Device` leaf and buckets `DECODER`, exactly
where the failure bucketed when it had no name of its own, so rule 3's one-to-one table is unchanged
and no `category` slice or denominator moves. It is a `Device` leaf rather than a `Drm` one on the
merits: the licence was issued and the keys are held, and what could not be produced is an output
path.

One value a pipeline has not seen before can appear, and `SessionEnded.securityLevel`'s population
grows once more for the same reason `#225` grew it — a session rescued by falling to the permitted
level reports that level and no failure row.

| Now reported | Was reported as | What it is |
| --- | --- | --- |
| `Device.SecureDecoderInit` | `Device.DecoderInit` | A secure decoder was selected for a protected session and would not start — the protected output path it writes to could not be allocated. A secure decoder that was never *found* stays `Device.DecoderInit`, because another variant may supply one. Carries `superplayer_error_protection_unavailable` — the same sentence `Drm.DowngradeRefused` carries, because it is the same fact to a viewer |

**Version 2 stands — licence acquisition became a measured span (`#212`).** `LicenceAcquisitionEnded`
is a new event type, and `SCHEMA_VERSION` did **not** move for it. That is the deliberate answer and
not an oversight: [ADR-0008][adr8] rule 5 puts a new event type on the shape side of the line, and
this one changes no existing definition — no denominator moves, no exclusion narrows, and a pipeline
that ignores the event computes exactly what it computed before. It is the same ruling
`DecisionChanged` got.

What a pipeline can do about it is new, though, which is why the note is here rather than absent:
protected playback's start-up cost was previously *unattributable*. A session whose first frame was
late because a licence round trip was slow and one that was late because a manifest was slow looked
identical in this vocabulary, and the committed benchmark reports said so outright. They no longer
do.

| Now reported | Was reported as | What it is |
| --- | --- | --- |
| `LicenceAcquisitionEnded`, `outcome = ACQUIRED_FROM_SERVER` | nothing at all | A licence fetched from the server, and what the round trip cost |
| `LicenceAcquisitionEnded`, `outcome = REFUSED` | only the failure event, if the refusal ended playback | A licence attempt that ended without keys — how long it cost, and that it happened at all, which a failure event does not say |
| `LicenceAcquisitionEnded`, `outcome = SERVED_FROM_OFFLINE_STORE` | nothing at all | Keys restored from a licence already on the device. Declared by `#212` and unreachable until `#210` — see below |

One value of the outcome was therefore declared and unreachable for one release, and that was
deliberate: an offline licence store was issue `#210`'s, and declaring the value in `#212` made
`#210` a behaviour change against a vocabulary a pipeline had already been told about, rather than a
second change of this schema for the same metric.

**Version 2 still stands — the third outcome became reachable (`#210`).** `SuperPlayer` now has an
offline licence store (`OfflineLicences.store(directory)`), so a player built with a stored licence
restores its keys and reports `SERVED_FROM_OFFLINE_STORE`. **`SCHEMA_VERSION` does not move**, and
that is the deliberate answer rather than an oversight: no definition changed, no denominator moved,
no exclusion narrowed, and the value a pipeline sees is one this document already told it to expect.
What changed is the *population* — a value that appeared in no row now appears in some — which is a
behaviour change in the library and not a change of the schema. It is the same ruling `#225` recorded
for `SessionEnded.securityLevel` one row above.

What a pipeline can do about it is new, and is the reason the metric carries an outcome at all: a
deployment that believes its downloads play offline and is in fact re-acquiring a licence every time
looks identical in every other metric in this document, and different in exactly this one.
`ACQUIRED_FROM_SERVER` on a session the app intended to be offline is the finding.

`#210`'s other visible effect is a `classification` a pipeline may now see more of rather than a new
one: a player built with an expired stored licence is refused rather than silently re-acquiring, and
that refusal is `FailureClass.Drm.LicenceExpired` — the leaf and the message key `#206` added —
reached through `ERROR_CODE_DRM_LICENSE_EXPIRED` exactly as an expiry met mid-playback is. No table
here moves.

**Version 2 stands — `category` gained a seventh value, `STORAGE` (`#244`).** A download that meets a
full disk now fails as `FailureClass.Storage.Full`, a branch of the taxonomy of its own, and rule 3's
one-to-one table needs a bucket for it. None of the six describes it: `DECODER` is a decoder that would
not start, `SOURCE` bytes that arrived and would not play, `NETWORK` bytes that did not arrive, and
`UNKNOWN` is what the classifier, being total, never produces. So the enum grew, and this document's
line that `category` "is not extended" is amended below to say why once was right.

**`SCHEMA_VERSION` does not move**, and that is the deliberate answer. No event in this vocabulary
carries the value today: only a *download's* writes are measured against the disk, and a download is not
a playback session and emits no telemetry (ADR-0013 rule 6). No existing failure changes bucket, no
denominator moves and no exclusion narrows, so this is shape — an enum value a pipeline has not seen —
and not meaning. What a pipeline should do is what it does for a new event type: take an `else`.

| Now reported | Was reported as | What it is |
| --- | --- | --- |
| `category = STORAGE` (`Storage.Full`) | nothing — no playback session reaches it | A write the device's storage could not hold. Carries its own `userMessageKey`, `superplayer_error_storage_full`, the eighth: the one failure whose remedy is the viewer freeing space |

**Nothing here moved when a display change began to re-select (`#269`).** A player built with
`superplayer-tv` watches the display, and an HDMI hotplug to a lesser or more capable one re-selects at
the position reached ([ADR-0014][adr14] rule 5). What a pipeline sees of it is what it already sees of
any rendition change: `TrackSwitched`, `DOWN` onto a lesser display and `UP` onto a better one, and a
rebuffer where Media3's re-selection discarded a buffer. **No `DecisionChanged` is emitted** and no
`DecisionTrigger` was added, because a display change consults no policy: the display is a reading the
selection gate re-reads, not a `PlaybackConditions` observation, and a trigger that re-asks a policy
reading nothing of the display could only re-derive the decision in force (ADR-0014 rules 9 and 11).
So the decision in force is unchanged across a hotplug, and the reconstruction under *Decision changes*
still holds. When a policy first reads the display, `DecisionTrigger.DISPLAY_CHANGED` arrives with it
(the name is reserved), and that is **shape, not meaning** — a new value of an existing field changes no
denominator — so `SCHEMA_VERSION` stays 2 then as it does now.

**Version 2 stands — an audio output that refused a passthrough track is transient (`#270`).** A player
built with `superplayer-tv` re-selects its audio when an AV receiver is powered on or off
([ADR-0014][adr14] rule 6), and what a pipeline sees of that is a `TrackSwitched` with no failure. Where the
output refuses a passthrough track before the re-selection lands, the failure is now `Device.DecoderTransient`
and is repaired by rung 5's re-prepare, which selects the PCM rendition, so a rescued session reports no
failure row. Both leaves bucket `DECODER`, so no `category` slice or denominator moves. The one thing a
reader of old data should know: `classification` for that failure used to be `Device.DecoderInit`.

| Now reported | Was reported as | What it is |
| --- | --- | --- |
| `Device.DecoderTransient` (only when rung 5 did not rescue it) | `Device.DecoderInit` | The audio output would not open a track for an encoded format (AC-3, E-AC-3, DTS and the like) it had been carrying. An init failure for decoded PCM stays `Device.DecoderInit` |

**Version 2 stands — the sample carries the throughput estimate beside the rung (`#294`).**
`PlaybackStateSampled` gained a `throughputEstimateBps`: the smoothed estimate the engine's bandwidth
meter last reported, in bits per second, or null where it has reported none. **`SCHEMA_VERSION` does
not move**, and that is the deliberate answer rather than an oversight — it is an addition of shape,
exactly as `SessionEnded.securityLevel` was: no existing field's definition changes, no denominator
moves, no exclusion narrows, and a pipeline that ignores it computes what it computed before
([ADR-0008][adr8] rule 5).

Why it is on the *sample* rather than an event of its own: a meter reports per transfer, at a rate a
segment size decides, and an event per report would be a stream whose volume is a fact about the
content's packaging. The sample already has a stated cadence — and, more to the point, it already
carries `videoBitrateBps`. The two fields being on **one event at one instant** is the whole reason
this one exists. A stall with a healthy estimate and a low rung is a different defect from a stall
with a collapsed estimate: the first is selection, a ceiling or a device refusal, the second is the
link. Read from separate events, the comparison is a join between two instants; read from one sample,
it is a subtraction. `superplayer-diagnostics`' debug HUD shows exactly that line, and a warehouse
query can now ask the same question of a fleet.

It is the meter's *estimate* and not a transfer's own rate. The estimate is the number selection acts
on, so a reader comparing it against the rung sees the input the engine had. The unsmoothed quantity —
each transfer's own throughput — is `superplayer-telemetry`'s `SessionTrace`, which records it as a
`bandwidth` kind and whose KDoc says why the two are different artifacts.

| Now reported | Was reported as | What it is |
| --- | --- | --- |
| `PlaybackStateSampled.throughputEstimateBps` | nothing — no event carried an estimate | The meter's smoothed throughput at the sample instant, beside the rendition it was being spent on. Null until the meter has reported: before the first transfer finishes, on content served wholly from the cache, and on any player whose meter reports on thresholds a session never reaches |

One thing a reader of a *test* fixture should know, since it is the same fact in small: Media3's own
`DefaultBandwidthMeter` reports only once a transfer has moved half a megabyte or run for two seconds,
so short objects over a fast link produce samples with a null estimate. That is the field's rule
working, not a gap. `superplayer-abr`'s meter samples every transfer and reports each one, so a player
built with `AdaptivePolicy` fills the field from its first segment.

**Version 2 stands — `classification` on iOS and tvOS (`#419`).** A player configured with
`SuperPlayerResilience` now reports `classification`, and `category` is derived from it there as it is
on Android. **`SCHEMA_VERSION` does not move**: the taxonomy, its stable names and its one-to-one
category table are the Kotlin ones string for string, so a class means the same thing on both
platforms and the version-2 note above already says what deriving `category` from it changes. What
differs is only the evidence the class is read from, which *Video start failure and mid-stream
failure* states.

**Version 2 stands — the iOS and tvOS derivation (`#409`).** The events table gained its *How
derived on iOS* column, filled from `SuperPlayerTelemetry`'s `QoeCollector`. **`SCHEMA_VERSION` does
not move**: every definition in this document binds the Swift collector as written, and the column
says only which AVFoundation API each one is read from. Three readings are worth knowing before
putting both platforms in one query, and none of them is a changed definition:

| On iOS | What a pipeline sees | Why the version does not move |
| --- | --- | --- |
| `FirstFrameRendered` for audio-only content | An event, where Android reports none for content with no video track | The boundary is the same — presentation beginning — and the event is a population an Android pipeline never had rather than a different measurement of one it had; group by `contentId` or by platform to keep the two apart |
| `PlaybackStateSampled.throughputEstimateBps` | AVFoundation's `observedBitrate` | The field was always "the engine's estimate, saying whose"; on iOS the engine is AVFoundation |
| `SeekCompleted.seekLatencyMs` | Resolved to a 500 ms reading of the playhead | The boundary is the same — playback resuming at the target — read as often as iOS lets a collector read it |

**Version 2 stands — FairPlay on iOS and tvOS (`#422`).** `LicenceAcquisitionEnded` is now emitted on
Apple platforms, from the library's own FairPlay exchange, with the boundaries *Licence acquisition*
defines; and the vocabulary gained one event, **`OutputObscuredChanged`**, which an iOS or tvOS player
configured for FairPlay emits when an external display without HDCP obscures the picture and again when
it stops. **`SCHEMA_VERSION` does not move**: a new event type is an addition of shape, no existing
field's definition changes, and the event is declared on Android and emitted by nothing there, as
`SERVED_FROM_OFFLINE_STORE` was before `#210`. Two readings worth knowing before putting both platforms
in one query:

| On iOS | What a pipeline sees | Why the version does not move |
| --- | --- | --- |
| `SessionEnded.securityLevel`, `LicenceAcquisitionEnded.securityLevel` | Null on every session, protected or not | The field was always "the level in force, or null where none was negotiated"; FairPlay negotiates none |
| `OutputObscuredChanged` | A new event, on protected iOS and tvOS sessions only | A population Android never had, measured by nothing else; a session with `obscured = true` and no later `false` ended with the picture still hidden |

**Version 2 still stands — the offline outcome on iOS (`#423`).** `SuperPlayerDRM`'s offline store
lets a player be configured with a stored licence, which answers every key itself and reports
`SERVED_FROM_OFFLINE_STORE`. As with `#210` on Android, that is a value this document already
declared appearing in a population that had none, so **`SCHEMA_VERSION` does not move**. Its
`durationMs` reads zero: no round trip is made, and the key session is answered as it asks.

**Version 2 still stands — the display's mode on tvOS (`#426`).** The vocabulary gained one event,
**`DisplayModeChanged`**, which a tvOS player configured with `SuperPlayerTV`'s output emits: the
reading as each session's content asks the display for its criteria, then each display mode switch
beginning and ending, and each change of the viewer's *Match Content* settings. **`SCHEMA_VERSION` does
not move**: a new event type is shape, no existing field's definition changes, and the event is
declared on Android and emitted by nothing there, as `OutputObscuredChanged` is.

| On tvOS | What a pipeline sees | Why the version does not move |
| --- | --- | --- |
| `DisplayModeChanged` | A new event, on tvOS sessions of a player configured with `TvOutput` only | A population Android never had: Android's display change re-selects and reaches a pipeline as `TrackSwitched`, and no Android API reports a mode switch back. A session's `switchInProgress = true` followed by `false` brackets the time the panel may have been blank, which on a sink whose switches are not seamless is inside `timeToFirstFrameMs` (*Time to first frame*, *On a television*) |

**Version 2 still stands — realtime streams on iOS and tvOS (`#461`).** A `RealtimePlayer` configured
with a collector now emits the schema's events for a realtime session, from the stream's clock and its
renderers rather than from an `AVPlayer` ([Realtime sessions](#realtime-sessions) has the table). **`SCHEMA_VERSION` does
not move**: no event or field was added, and every definition is the one above, read off a
different engine — which is what `#409`'s note already said of the whole iOS derivation. One reading is
worth knowing before a query mixes the two player types:

| On a realtime stream on iOS | What a pipeline sees | Why the version does not move |
| --- | --- | --- |
| `SessionStarted.profile` and `decision` | `LIVE_LINEAR` and that profile's decision on every session | A `RealtimePlayer` has no profile, so the field carries the one its content is; the decision is consulted by nothing on that path, and was always "the decision in force at the start", which is the profile's own when nothing else decides |

**One consequence of that row is worth stating outright.** Realtime sessions from iOS now sit in the
`LIVE_LINEAR` population beside live HLS, and no field separates them. A query that wants live HLS
alone has to separate realtime sessions as [Realtime sessions](#realtime-sessions) already says:
by joining `contentId` against the app's own catalog. Android's realtime sessions have always carried
whichever profile the app built the player with, so this was already true of a mixed population;
what is new is that iOS fixes the value rather than leaving it to the app.

**A sink must tolerate a new event type.** `TelemetryEvent` is sealed, so a `when` over it can be
exhaustive without an `else` — and such a `when` fails to compile when a later version adds an event.
An `else` branch is the forward-compatible spelling; take the exhaustive one only if being told about
additions is what you want.

---

## The events

| Event | Fired when | Carries | How derived on iOS |
| --- | --- | --- | --- |
| `SessionStarted` | A player takes content on | `profile`, `decision` | Core's adoption edge (`setMediaRequest`, `restoreSnapshot`; `SessionEdges.swift`). `profile` is `Configuration.profile`; `decision` is `player.playbackDecision` as the session opens. An iOS decision carries the halves an iOS player has knobs for — `buffer.maxBufferMs` is `preferredForwardBufferDuration`, its live half an offset rather than a speed range (`PlaybackDecision.swift`) — so it is the same decision in Android's vocabulary and not field for field |
| `DecisionChanged` | The policy answered differently on a named trigger | `decision`, `trigger` | Core's `DecisionReapplication.swift`, on the same five triggers from `ConditionsBinding.swift`'s readings: `NWPathMonitor`'s path, the item's `duration` and access log, `timeControlStatus`, and `AVPlayer.rate`. **Every player whose `Configuration.policy` is not its profile's static one emits it**, a consumer's own policy included, because every knob a decision reaches is live-settable on the item (ADR-0009 rule 5 met by the platform); on Android only `superplayer-abr`'s policy is re-consulted. A static player emits none |
| `SessionEnded` | Released, recycled, or moved to other content | `droppedEventCount`, `securityLevel` | Core's edges: a replacement, `setMediaURL`, `release()`. `droppedEventCount` is stamped by `TelemetryDelivery` as the event leaves the queue, as on Android; **`securityLevel` is null on every iOS session**, protected or not, because FairPlay has no security levels to negotiate (`docs/ios-plan.md` §3.6) — the output's protection is `OutputObscuredChanged` instead |
| `FirstFrameRendered` | The first video frame reaches the display | `timeToFirstFrameMs`, `startBoundary` | The session's first `AVPlayer.timeControlStatus` of `.playing` (key-value observed): presentation beginning. Not `AVPlayerLayer.isReadyForDisplay`, which is the consumer's layer and invisible to a collector. For video that is the clock starting with its first frame due, which can be a display refresh before the frame is on screen, so it can read slightly lower than a layer's reading; **for audio-only content it is the first audio presented, where Android reports no event**. The boundary is `declarePlaybackIntent()` on `CLOCK_MONOTONIC`, else the session's start, as on Android |
| `RebufferStarted` | Playback stalls for data after it started | `seekInduced` | After the first frame: `timeControlStatus` becoming `.waitingToPlayAtSpecifiedRate` with `reasonForWaitingToPlay` of `.toMinimizeStalls`, or `AVPlayerItemPlaybackStalled` on a player that does not wait to minimise stalls. **iOS 18 and later** also open one on `AVMetricPlayerItemStallEvent`; iOS 17 has the first two alone. `seekInduced` as on Android |
| `RebufferEnded` | Playback resumes from that stall | `durationMs`, `seekInduced` | `timeControlStatus` becoming `.playing` (resumed) or `.paused` (stopped stalling); a failure or the session's end closes an open one |
| `StartupFailed` | Playback fails before the first frame | `failure` | `AVPlayerItem.status` of `.failed` (key-value observed) or `AVPlayerItemFailedToPlayToEndTime`, before the first frame, once per session. `code` is the `NSError`'s `domain:code`; `category` comes from `NSURLErrorDomain`, the `AVError.Code`, or the error log's last HTTP status read at the failure — unless the player is configured with `SuperPlayerResilience`, when `classification` is the name `player.classify(_:)` answers and `category` its row (*Video start failure and mid-stream failure*) |
| `MidStreamFailed` | Playback fails after the first frame | `failure`, `positionMs` | The same, after the first frame; `positionMs` is the item's `currentTime()` |
| `TrackSwitched` | The video rendition changes | `fromBitrateBps`, `toBitrateBps`, `direction` | Items with an enabled video track only, as on Android: the access log's last `indicatedBitrate` (the variant's `BANDWIDTH`), read at `AVPlayerItemNewAccessLogEntry` and at `.readyToPlay`. **On iOS 18 and later** the initial rendition can arrive first, from the variant of `AVMetricPlayerItemInitialLikelyToKeepUpEvent` |
| `SeekRequested` | A seek is issued | `fromPositionMs`, `toPositionMs` | `AVPlayerItemTimeJumped` after the first frame. `toPositionMs` is `currentTime()` at the notification, which AVFoundation posts on the main thread with no position, so a main thread busy as the time jumped reads the target plus what played meanwhile (#459); `fromPositionMs` is the collector's last periodic reading (every 500 ms) carried forward at its rate, because the notification carries neither position. It does not say who moved the time, so a jump AVFoundation makes itself, back inside a live window, is counted too |
| `SeekCompleted` | Playback resumes at the target | `toPositionMs`, `seekLatencyMs` | The first periodic reading past the target while `timeControlStatus` is `.playing`, so the latency is resolved to that 500 ms reading; a seek made while paused completes on `isPlaybackLikelyToKeepUp`, at once if it landed in what is buffered |
| `LicenceAcquisitionEnded` | A DRM licence acquisition finishes | `durationMs`, `outcome`, `securityLevel` | Core signals it from `SuperPlayerDRM`'s FairPlay exchange (`#422`), since the key session's delegate is the library's and no AVFoundation interface reports a licence: opened as the `AVContentKeySession` hands over a key request for content that holds no key yet, closed as the licence is handed back (`ACQUIRED_FROM_SERVER`) or refused with the budget spent (`REFUSED`). A rotation to a later key and a renewal emit nothing, as on Android. `securityLevel` is null: FairPlay has none. `SERVED_FROM_OFFLINE_STORE` (`#423`) closes as a key is answered from the stored licence a player was configured with, with no request made; a stored licence refused as expired, or the offline store's own acquisitions and renewals, emit nothing, as `OfflineLicenseHelper`'s do on Android |
| `LiveLatencySampled` | Every 10 s, live content only | `liveLatencyMs`, `targetLiveLatencyMs` | On the sampling timer, for an item whose `duration` is indefinite: the end of the last `seekableTimeRanges` entry less `currentTime()` — AVFoundation's own live edge, where Android reads the program clock. The target is `configuredTimeOffsetFromLive` where the profile set one, else `recommendedTimeOffsetFromLive` |
| `PlaybackStateSampled` | Every 10 s | `samplingIntervalMs`, `videoBitrateBps`, `bufferedDurationMs`, `playing`, `throughputEstimateBps` | On the sampling timer. `bufferedDurationMs` from the `loadedTimeRanges` entry holding `currentTime()`; `playing` is `timeControlStatus` of `.playing`; `throughputEstimateBps` is the access log's last `observedBitrate` — **Apple's own figure**, smoothed over everything the item downloaded and not a sample, where Android's is Media3's meter |
| `OutputObscuredChanged` | The picture began or stopped being obscured for want of output protection | `obscured` | Key-value observation of `AVPlayer.isOutputObscuredDueToInsufficientExternalProtection` — HDCP on an external display — on a player configured with `drm` alone, and read once as each session opens. **Reported and never acted on**: what an output may show is the licence's policy, which the platform enforces ([ADR-0012][adr12] rule 11). **Android emits none**: it has no such reading, and an output that cannot be secured fails as `Device.SecureDecoderInit` there |
| `DisplayModeChanged` | A television's display mode reading: as content asks for its criteria, then a mode switch beginning or ending, or the viewer's settings changing | `switchInProgress`, `criteriaMatchingEnabled` | `AVDisplayManager`'s `isDisplayModeSwitchInProgress` and `isDisplayCriteriaMatchingEnabled`, read by `SuperPlayerTV`'s output binding as it asks the key window's display for the item's `preferredDisplayCriteria` and at `AVDisplayManagerModeSwitchStart`, `…End` and `…SettingsChanged`, on tvOS on a player configured with `TvOutput` alone (`#426`); a reading the session already reported is not reported again. **Reported and never acted on**: the system re-selects for a display change on Apple platforms. **Android emits none**: core's display watch re-selects there, through SuperPlayer's own selection gate, and a pipeline sees `TrackSwitched` ([ADR-0014][adr14] rule 5) |
| `VideoFramesDropped` | Every 10 s, while video renders | `droppedFrames`, `repeatedFrames`, `elapsedPlayingMs` | On the sampling timer, video items only: the change since the last sample in the access log's summed `numberOfDroppedVideoFrames` and `durationWatched`. `repeatedFrames` is 0, unobservable as on Android |

The *How derived on iOS* column says which AVFoundation API each event comes from on iOS and
tvOS (`SuperPlayerTelemetry`'s `QoeCollector`, `#409`), and where iOS 17 and 18 derive one
differently. It is a `SuperPlayer`'s derivation; a `RealtimePlayer`'s is
[Realtime sessions](#realtime-sessions)' own table (`#461`). It describes a derivation and changes no meaning: the definitions below bind both
platforms, which is why the schema version did not move when it was written. The first three
columns bind both platforms, and a test on each holds its event types to them in both directions — `superplayer-core`'s types on Android and `TelemetrySchemaDocumentTests`
in the Swift package — so an event or a field named here and missing from either, or carried by
either and missing here, fails a build.

**The sampling interval is 10 seconds**, and the three periodic events share it. It is stated here
because a time-weighted quantity cannot be computed from samples whose cadence a consumer has to
guess. Ten seconds is short enough to resolve a bitrate distribution across a normal view — a
half-hour session is 180 samples — and long enough that the events are a rounding error next to the
segment requests happening anyway.

**Do not hard-code it.** Each sample carries `samplingIntervalMs`, which is its weight, so a session
recorded under a different cadence still aggregates correctly and a change of default is not a
change of schema. A consumer that reads the constant from this document rather than from the event is
the one that breaks when it moves.

`SessionStarted.decision` is the `PlaybackDecision` the player was actually running — the buffer
sizes and track-selection limits in force at the start, and, for content the manifest declared
live, the range of playback speeds the player holds its live window with (`liveLatency`, absent
otherwise); see `DecisionChanged` for what follows. It
is here because a QoE number is uninterpretable without it: a rebuffer ratio measured under
`DATA_SAVER`'s buffer sizes and one measured under `LIVE_LINEAR`'s are two different measurements,
and a pipeline that cannot tell them apart will average them. Carrying the decision rather than only
the profile keeps that true when an adaptive policy makes the profile stop predicting it.

### Decision changes

`DecisionChanged` is emitted each time the decision in force changes after `SessionStarted`, and
carries the new `decision` and the `trigger` that produced it — one of `TRANSPORT_CHANGED`,
`STREAM_TYPE_CHANGED`, `REBUFFER_ENDED`, `PLAYBACK_SPEED_CHANGED`, `THROUGHPUT_CHANGED`, the closed
list ADR-0009 rule 4 names. A policy is consulted only on those triggers and never on a cadence, and
a consultation that returns the decision already in force emits nothing, so every event is a change
and every change has a reason a reader can name.

**Join it to `SessionStarted.decision` to know what a session was running at any moment**: the
decision in force at time *t* is `SessionStarted.decision` if no `DecisionChanged` precedes *t*,
and otherwise the `decision` of the last one that does. Every event in this schema that reads
differently under a different decision — the rebuffer ratio, the bitrate distribution — is read
against that reconstruction rather than against the session's first decision.

Which players emit it is a property of how they were built, and it is stated rather than left to be
discovered: a player whose engine can honour a changed decision whole — one built with
`superplayer-abr`'s policy — is consulted again on the triggers; every other player, whatever policy
it was given, is consulted once at construction and emits no `DecisionChanged` at all
(ADR-0009 rule 5). A session with none is therefore not evidence that conditions were stable. On
iOS and tvOS the line falls elsewhere, for the platform's reason rather than a different rule: every
knob a decision reaches is live-settable on the playing item, so every player except one under its
profile's static policy is consulted again, a consumer's own policy included (`DecisionChanged`'s
*How derived on iOS* cell).

**The throughput a decision was made on is a different input on iOS**, and a `THROUGHPUT_CHANGED`
row reads accordingly. Android's estimate is `superplayer-abr`'s `BandwidthOracle` over sampled
transfers, each timed by the engine, with a read a cache answered excluded from it. AVFoundation
reports no transfer to an app, so on iOS every sample is **one access-log entry's
`observedBitrate`** — Apple's own measure of what was fetched while that entry was current, which
spans a variant, a seek or a stall rather than one segment — and the cache-hit exclusion has nothing
to exclude. `SuperPlayerABR`'s `AdaptivePolicy` keeps those samples per transport for the process,
with Android's cold defaults and aging (ADR-0009 rules 8 and 9); any other iOS policy is handed the
playing item's own entries alone. Either way the trigger's threshold — a fifth of the last estimate
announced — is Android's, and nothing in this schema's fields changed for it: `SCHEMA_VERSION` stays
2.

**A display change is not a decision change.** On a player built with `superplayer-tv`, an HDMI hotplug
re-selects renditions under the new display without consulting the policy, so it appears as
`TrackSwitched` and never as `DecisionChanged` ([ADR-0014][adr14] rules 5, 9 and 11; *Release notes*,
`#269`).

An addition of shape, not of meaning: `SCHEMA_VERSION` did not move for it. The definition of
`SessionStarted.decision` was clarified in the same change from "in force for the whole session" to
"in force at the start", which was only ever true because nothing could change it.

---

## Metric definitions

### Time to first frame

> *ref: CTA-2066, video start-up time.*

**`timeToFirstFrameMs` on `FirstFrameRendered`.** Milliseconds from the start boundary to the moment
the first video frame of the session is presented on the display. Measured on the monotonic clock.

**The start boundary is the whole argument of this metric**, and it is declared rather than observed:

| `startBoundary` | Counts from | Emitted when |
| --- | --- | --- |
| `USER_INTENT` | `SuperPlayer.declarePlaybackIntent()` | The app declared intent for this session |
| `CONTENT_ADOPTED` | The player taking the content on | It did not |

`PRD.md` §3.4 puts the boundary at **user intent, not `prepare()`**, because the seconds a viewer
experiences begin at their tap. A library that starts its stopwatch when it is handed a URL has
already excluded whatever the app spent getting there — the catalogue call, the entitlement check,
the navigation transition — and that interval is both real and the one an app most wants to see.

SuperPlayer cannot observe a tap, so the app declares it:

```kotlin
// In the tap handler, before the catalogue lookup that produces the MediaRequest.
player.declarePlaybackIntent()
```

**Rules at the edges:**

- A declaration belongs to **the next session that opens** and is consumed by it. A second session
  never inherits a stale one, which would otherwise report a start-up time containing the whole of
  the previous view.
- Declaring twice before a session opens: **the last declaration wins**, so a double tap measures
  from the tap that actually loaded something.
- Declaring *after* a session has opened: the declaration applies to the next session, not the
  current one. The current session's boundary is settled when it opens, so nothing can move a
  measurement's own start backwards after the fact.
- No declaration: the boundary is `CONTENT_ADOPTED`, which is a real measurement of a narrower
  interval. **It reads lower.** Do not aggregate the two boundaries together — group by
  `startBoundary`, or filter to one.

*Departure from CTA-2066:* none in definition. The `CONTENT_ADOPTED` variant is narrower than the
standard's boundary and exists because the alternative — refusing to report anything for an app that
declares no intent — would leave the most common integration unmeasured. It is labelled rather than
silently substituted, which is the part that matters.

*On a television:* a player built with `superplayer-tv` asks the display for the content's frame
rate before the first frame ([ADR-0014][adr14] rule 4). On a TV whose viewer set *Match content frame
rate* to *Always*, a switch that is not seamless blanks the panel while the HDMI link resynchronises,
and the first frame waits for it. That wait is **inside** `timeToFirstFrameMs`, and on some sinks it is
seconds rather than milliseconds. The meaning does not change, because the viewer waited for it, so
the schema version does not move. A report comparing a television arm with a phone arm will still show
the difference, and should say which device each ran on. On tvOS the same wait is bracketed by
`DisplayModeChanged`'s `switchInProgress` going `true` and then `false`, so a pipeline can say how much
of a session's start-up the display took.

*Agreement with CMCD:* CMCD v2's `msd` (media start delay) uses the same boundary — the viewer's
request to the first frame — so the two routes report the same number rather than two numbers with
one name (see `#38`; Media3 1.11.0 implements CMCD v1 only, so nothing carries `msd` today).

### Exit before video start

> *ref: CTA-2066, exit before video start.*

**Derived, not reported.** A session that produced a `SessionStarted` and a `SessionEnded` with **no
`FirstFrameRendered` and no `StartupFailed` in between** is an exit before video start.

It is derived because SuperPlayer cannot observe an exit. The library sees a `release()`, a recycle,
or a content change; it never sees a back press, and a library that claimed to would be guessing
about its consumer's navigation. What it can say without guessing is that the session ended and no
frame ever rendered, which is exactly the condition the metric names.

*Departure from CTA-2066:* the standard treats this as a viewer-initiated abandonment. SuperPlayer's
derivation also catches a session the *app* ended before the first frame — a feed row scrolled out of
view and recycled, for instance. Sessions on pooled players are therefore not comparable to sessions
on a full-screen player for this metric, and a pipeline that mixes them will overstate abandonment.
Group by profile: `SHORT_FORM` and pooled playback are where this bites.

### Video start failure and mid-stream failure

> *ref: CTA-2066, video start failure and video playback failure.*

`StartupFailed` is a failure **before** the first frame of the session; `MidStreamFailed` is a
failure **after** it. The split is the standard's, and it is the split a viewer experiences: nothing
played, versus something played and then stopped.

Both carry a `PlaybackFailure`: a coarse `category` (`NETWORK`, `SOURCE`, `DECODER`, `DRM`,
`RENDERER`, `UNKNOWN`, and `STORAGE`, which no playback reaches today), the engine's own `code`, an optional `message` for a human reading a log, and
an optional `classification`.

**`classification` is the field to group and alert on.** It is the stable name
`superplayer-resilience`'s `ErrorClassifier` gave the failure — `Transient.CdnEdge`,
`Content.ManifestInvalid`, `Device.DecoderTransient` and the rest (`PRD.md` §3.3) — and it is the
same string the consumer was handed on the typed error that ended the session, so a warehouse row, a
log line and a bug report say one word for one failure. Telemetry **reads** that classification and
keeps no taxonomy of its own ([ADR-0011][adr11] rule 3): there is no second table here, in either
direction. It is **null on a player built without `superplayer-resilience`**, and null means
*unclassified* in the honest sense — nothing was there to classify it.

**`code` is the engine's word and stays that way.** It is `PlaybackException.errorCodeName`, which is
what to grep a logcat for, and it is a different fact from the classification: the engine assigns
error codes, and a typed cause SuperPlayer raised does not change the code that was assigned around
it. That is exactly the defect `#86` recorded — a live stream frozen behind a CDN cache still arrives
as `ERROR_CODE_IO_UNSPECIFIED` — and it is resolved by `classification` naming it
`Transient.CdnEdge`, not by rewriting `code`.

**`category` stays coarse and is extended only for a failure no bucket describes.** Six values were the
right number for a dashboard slice of playback, and `#244` added a seventh, `STORAGE`, rather than file a
full disk under a bucket whose definition it contradicts; a new leaf that fits an existing bucket still
takes it, as every leaf since version 2 has. Where a classification is present the bucket is derived from it rather than from the
error-code band, which is the change of meaning behind schema version 2 — see *Release notes* above
for the failures where the two disagree.

**On iOS and tvOS the classification is read from AVFoundation's evidence (`#419`).** The collector
asks `player.classify(_:)` with the item's error, as the Kotlin collector asks `player.classify(error)`,
and keeps no table of its own. The classifier (`SuperPlayerResilience`'s `ErrorClassifier`) reads three
things: the error's own chain — the `NSURLErrorDomain`, `AVFoundationErrorDomain`, `CoreMediaErrorDomain`
and POSIX codes, cause first; the HTTP status, which AVFoundation reports as a `CoreMediaErrorDomain`
code per status (`-12938` for a 404); and the error log's last entry, which is the only place
AVFoundation says *which* request failed, and so what tells a refused segment
(`Transient.CdnEdge`) from a refused playlist (`Transient.Network`) where Android reads a `LoadKind`
stamp. A player configured without resilience reports `classification` null and buckets `category` from
the error's domain and code and the log's status, exactly as before `#419`. `code` is the `NSError`'s
`domain:code` either way — the engine's word, kept apart from the class as on Android.

The rates are computed by the pipeline, per the standard's shape: start failures over sessions that
attempted playback; mid-stream failures over sessions that started playing.

### Rebuffering

> *ref: CTA-2066, rebuffering ratio.*

**A rebuffer is an involuntary interruption of playback for data, after playback has started.** A
pause is not a rebuffer. Time before the first frame is not a rebuffer — that is start-up time, and
counting it in both would double-count the same seconds.

`RebufferStarted` opens one; `RebufferEnded` closes it and carries its `durationMs`.

**Rebuffer ratio:**

```text
rebufferRatio = totalRebufferMs / (totalRebufferMs + totalPlayingMs)
```

where `totalPlayingMs` is wall-clock time in the session during which the playback position was
advancing, and `totalRebufferMs` sums **only the stalls with `seekInduced = false`**. Both are per
session; aggregate across sessions by summing numerators and denominators, never by averaging
per-session ratios — the second weights a five-second session equally with a two-hour one.

**Seek-induced buffering is excluded, and counted separately.** A viewer who drags a scrub bar into
an unbuffered region expects a wait; treating that wait as a rebuffer makes a player look worse the
more its viewers seek, and makes the metric a measure of viewer behaviour rather than of delivery.

**What "seek-induced" means, precisely.** A stall is tagged `seekInduced = true` when it starts:

- between a `SeekRequested` and its matching `SeekCompleted`, **or**
- within **1000 ms** after that `SeekCompleted`.

Otherwise it is tagged `false` and counts toward the ratio.

*Why 1000 ms.* The window has to be long enough to cover the decoder pipeline refilling after a seek
lands, and short enough that it cannot swallow a whole segment fetch — because a window that could
hide one segment's worth of network trouble would hide genuine rebuffers behind every seek. One
second is comfortably longer than the former and shorter than the shortest segment duration in common
use (2 s in both the HLS and DASH interoperability profiles), so the two bounds do not overlap. It is
a choice made here rather than derived from the standard, and it is the number to change — with a
version bump, per the rule above — if measurement says otherwise.

*Departure from CTA-2066:* the standard defines the ratio; it does not fix the seek-exclusion
boundary in observable terms, because "seek-induced" is a judgement made at collection time. The 1000
ms window is SuperPlayer's, stated here so that another implementation can reproduce it exactly.

### Seek latency

> *ref: CTA-2066, seek events.*

**`seekLatencyMs` on `SeekCompleted`.** Milliseconds from `SeekRequested` to the moment playback
resumes at the target, on the monotonic clock. This is where the wait excluded from rebuffer ratio
above is accounted for; the two together cover all of it, and neither double-counts the other.

*Departure from CTA-2066:* the standard's seek begins with the viewer's action. `SeekRequested` is
raised from the engine's own seek discontinuity, which is the earliest moment the library can
observe one — a `seekTo` reaches the player through the same `Player` API a notification, a car head
unit or a scrub bar uses, and none of them tells it when the finger went down. The gap is the app's
own dispatch and is small; it is stated because it is real, and because an app that wants the wider
interval has `declarePlaybackIntent`'s reasoning to copy.

**A seek into already-buffered content reports a latency near zero and no rebuffer**, which is
correct and worth expecting: the player never leaves `STATE_READY`, so there is nothing to wait for.
Seek latency is not a measure of how far the viewer jumped.

### Bitrate

> *ref: CTA-2066, average bitrate and bitrate switching.*

`TrackSwitched` reports every change of video rendition, with `direction` of `INITIAL`, `UP` or
`DOWN` — the upshift and downshift counts `PRD.md` §3.4 asks for. `PlaybackStateSampled` reports the
rendition in force at a stated cadence, which is what makes a bitrate *distribution* computable.

Bitrates are the rendition's **declared peak bitrate** — what the manifest states. They are not a
measurement of what was transferred, and they are not throughput.

**Time-weighted average bitrate is weighted by media time**, not by wall-clock time:

```text
averageBitrateBps = Σ (bitrateBps × mediaMsAtThatBitrate) / Σ mediaMsAtThatBitrate
```

*Why media time.* Bitrate is bits per second *of media*, so weighting by media time is what makes the
average mean "the bitrate of what this viewer actually watched". It is also invariant to playback
speed: an hour watched at 2× is two hours of media and weighs as two hours, which is correct, where a
wall-clock weighting would halve it for no reason connected to quality.

**Across a pause, and across a rebuffer, nothing accrues** — the position does not advance, so no
media time passes and the average is untouched. That falls out of the definition rather than being a
special case, which is the reason for choosing it.

*Departure from CTA-2066:* the standard defines an average bitrate without fixing the weighting.
Media time is SuperPlayer's choice, stated here.

### Licence acquisition

> *SuperPlayer's own. CTA-2066 has no licence-acquisition metric; it measures what a viewer
> experiences and treats protection as invisible. Stated rather than cited, because a citation this
> metric does not have is the one thing this document may not manufacture.*

**`durationMs` on `LicenceAcquisitionEnded`.** Milliseconds, on the monotonic clock, from the moment
a DRM session was opened *without* keys to the moment it held them or was refused.

A protected session cannot show a frame until it has keys, so this interval is inside
`timeToFirstFrameMs` and is not a separate cost to add to it. It is reported separately because it is
the part of start-up that a CDN dashboard cannot see and an app cannot act on without being told:
every other slow-start remedy is about bytes, and this one is about an entitlement server.

**The boundaries.** The span opens on Media3's `onDrmSessionAcquired` **only** where the session was
opened without keys, and closes on the first of `onDrmKeysLoaded` (`ACQUIRED_FROM_SERVER`),
`onDrmKeysRestored` (`SERVED_FROM_OFFLINE_STORE`) or `onDrmSessionManagerError` (`REFUSED`). Two
consequences are worth stating because they are the two ways a reader could over-count:

- **A reused session emits nothing.** Media3 reuses a DRM session for content whose initialization
  data matched, and a session acquired *with* keys acquired nothing. Measuring it would report a
  near-zero acquisition that never happened, and a licence-acquisition time that falls as session
  reuse rises.
- **A key rotation emits nothing.** A rotation renews inside a session that is already open and loads
  keys with no acquisition to match, so it is not a second licence this session fetched.

`durationMs` therefore includes whatever the transfer cost — the retries `RetryPolicy.licence`
allows, the one token refresh a refused credential gets, and a provisioning round trip where the
device needed one — because all of those happen inside the span and all of them are what the viewer
waited for. It is the *attempt*, not the fastest possible request.

**A session can carry several.** Content that declares two licence policies opens two DRM sessions
and pays twice. Sum `durationMs` across a `sessionId` for what protection cost that view, and read
the count for how many licences it took.

**One limit of the measurement, stated rather than left to be discovered.** Media3's DRM callbacks
carry no session identity, so an acquisition is paired with its outcome oldest-to-oldest. Where two
acquisitions are genuinely *concurrent* — the two-policy case above — they can be attributed to each
other's durations, so the sum over a session is right and the split between its two rows may not be.
An acquisition abandoned because the player let go of the session before it was keyed reports
nothing at all: it got neither keys nor a refusal, and none of the three outcomes is true of it.
Every serial acquisition, and the count and outcomes of all of them, are exact.

**`outcome`:**

| Value | Meaning |
| --- | --- |
| `ACQUIRED_FROM_SERVER` | Keys arrived from the licence server, over the network |
| `SERVED_FROM_OFFLINE_STORE` | Keys were restored from a licence already stored on the device (`OfflineLicences.store`), with no licence request. Declared by `#212` and first emitted by `#210`, which is why neither moved `SCHEMA_VERSION` |
| `REFUSED` | No keys — the server refused, the request never arrived, or the device's protection stack failed the session |

**What `REFUSED` deliberately does not say is *why*.** That is
`PlaybackFailure.classification` on the failure event — a `FailureClass.Drm` leaf — and a second
taxonomy on this event would be the drift [ADR-0011][adr11] rule 1 exists to prevent. Join the two on
`sessionId`. Note also that a refusal is not always a failed session: a licence refused once and
repaired by the header refresh appears here only if the repair came too late to keep the session
open.

**`securityLevel`** is the level in force when *this* acquisition ended — the same field, with the
same meaning, as on `SessionEnded`, read at a different moment. It is null on every acquisition that
negotiated no level, and it is per-acquisition rather than per-session because
[ADR-0012][adr12] rule 11's ladder settles a level after a refusal: a session's first acquisition and
its second need not have run at the same one.

**On iOS and tvOS** the span is the library's own FairPlay exchange (`#422`) rather than an engine's
callbacks: it opens as the `AVContentKeySession` hands over a key request for content that holds no key
yet, and closes as the licence is handed back or the exchange gives up. It includes the certificate
fetch where this request was the one that fetched it, the composing of the key request, the
`RetryPolicy.licence` retries with their jittered waits, and the one credential repair — the same
contents as the Android span. "Content" moves with each asset the player plays, so a rung's re-adoption,
which asks for its keys again, is a second acquisition. Each later key the session asks for, and each
renewal, is a rotation and emits nothing.

**Redaction.** Nothing about the licence itself is in this event: no licence URI, no key id, no key
request or response, no device identifier. That is not a property of this event alone — the trace
recorder's rule 6 already renders a licence load as `load … drm` and nothing more — but it is the
property that lets this metric go on a bug report.

### Live-edge latency

> *ref: CTA-2066, live latency.*

**`liveLatencyMs` on `LiveLatencySampled`.** Milliseconds between the playback position and the live
edge, sampled at the `PlaybackStateSampled` cadence. `targetLiveLatencyMs` is what the stream or the
profile asked for, or null when neither stated one; the drift `PRD.md` §3.4 names is the difference
between the two over the session.

**Emitted for live content only.** A sample of zero from on-demand content is a number a dashboard
would happily average, and the absence of the event is what stops that.

### Dropped and repeated video frames

> *ref: CTA-2066, dropped frames.*

`VideoFramesDropped` reports, over an interval of playing time:

- `droppedFrames` — video frames the renderer discarded without presenting
- `repeatedFrames` — video frames presented twice because no new one was ready
- `elapsedPlayingMs` — the playing time the interval covers, on the monotonic clock

**`repeatedFrames` is always zero on Android today**, and that is a limit of the engine rather than
a statement about the content. Media3 1.11.0's `VideoRendererEventListener` reports dropped frames
and has no callback for repeated ones — `MediaCodecVideoRenderer` does not count a frame presented
twice. The field stays in the vocabulary because CTA-2066 names both and because a schema that
dropped it would have to be versioned to add it back; it is reported as zero rather than omitted so
that the event's shape does not change on the day it becomes observable. **Read a zero here as "not
measured", not as "none".**

**The denominator is wall-clock playing time**, and the rate is therefore:

```text
droppedFrameRate = droppedFrames / (elapsedPlayingMs / 1000)   // frames per second of playing time
```

*Why playing time and not frames rendered.* Frames rendered is the more natural-looking denominator —
it gives a percentage — and it is the wrong one here for two reasons. It is undefined in exactly the
case that matters most: a renderer that drops everything renders nothing, and a percentage over zero
is not a number. And it needs a second source for the rendered count, which can disagree with the
dropped count about which interval it covers; the engine reports dropped frames with their elapsed
interval together, so a rate over that interval needs nothing that can drift out of step. The cost of
this choice is real and should be stated: the rate is **not** comparable between content of different
frame rates. Compare 60 fps against 60 fps.

**The event carries the components, not the rate.** `droppedFrames` and `elapsedPlayingMs` travel
separately and the division above is the pipeline's to do. That is deliberate: an unnormalized count
is the same number CMCD v2's `df` key carries, so the two routes report one number rather than two
with one name — and a pre-divided rate cannot be re-aggregated, because summing rates across
intervals or sessions is not a rate. Summing the components and dividing once is.

*Departure from CTA-2066:* the standard names dropped frames as a renderer-quality property; it does
not fix a rate denominator. Playing time is SuperPlayer's choice, with the reasoning above.

#### These are video frames. They are not UI smoothness.

This is the single most likely way this metric gets misread, so it is stated here in as many words.

There are two frame pipelines during playback and they are close to independent:

- **Video frames** are decoded by `MediaCodec` and pushed to a `Surface`. `PlayerView` uses a
  `SurfaceView` by default, so video composites on its own layer and **never travels through the
  app's render pipeline**.
- **UI frames** are the app's own drawing — the feed rows, the controls, the scrub bar — through
  Choreographer and the RenderThread.

So video can drop frames while the UI is perfectly smooth (the decoder cannot keep up), and the UI
can jank badly while video plays flawlessly (main-thread work during a fling). A viewer calls both
"stuttering", which is exactly why a schema that is vague here produces two teams arguing about one
number that means two different things.

**`VideoFramesDropped` is the first pipeline only.** UI smoothness is the app's own render pipeline
and is not SuperPlayer's to report — a library claiming to measure it would be measuring its
consumer's code. Measure it separately, with the platform's own frame-timing tools.

---

## Sinks

A sink is one method, `onEvent(event)`, and it is the only thing an app writes to get telemetry out.
Nothing in it names a Media3 type, which is what keeps an app's analytics adapter ordinary Kotlin
with no `@OptIn` and no engine on its compile classpath.

```kotlin
val player = SuperPlayer.Builder(context)
    .setTelemetry(QoeCollector(TelemetrySink.composite(analyticsSink, LogcatSink)))
    .build()
```

**`TelemetrySink.composite(vararg sinks)`** (`superplayer-core`) fans one event out to several, in
the order given. A child that throws is contained: the remaining children still receive the event,
the failure is logged, and nothing propagates back to playback.

**`LogcatSink`** (`superplayer-telemetry`) writes every event to logcat under the tag
`SuperPlayerQoE`, **one line per event**, `key=value` and space-separated, with the discriminator
first:

```text
evt=rebuffer_ended v=1 sid=8f21c0de cid="urn:content:12345" t=1757500000000 mono=942310 durationMs=1840 seekInduced=false
```

So `grep 'evt=rebuffer'` is a class of event and `grep sid=8f21c0de` is a whole session. Values that
can contain a space are quoted and newlines are escaped, because the format's one promise is that an
event never spans two lines. Failures log at `WARN` and everything else at `INFO`. A line is
formatted whether or not anything is listening, which is worth thinking about before attaching it in
a release build.

**Which thread a sink is called on.** SuperPlayer's own delivery thread — one for the process, so
sixty pooled players do not mean sixty threads — and never the application thread or a playback
thread ([ADR-0008][adr8] rule 4). Calls are serialized, so a sink needs no locking of its own.

**A sink may therefore block.** A network write, a disk write or a slow lock delays no playback
callback; the engine's threads only ever enqueue. What a slow sink costs is *events* — its own and
other sessions', through the bound above — which is the trade this shape makes deliberately, because
telemetry latency is elastic and playback is not. A sink that never returns does stop delivery for
every player in the process, so hand genuinely long work to your own executor.

Do not call the player from a sink: the event carries what it means, and a `SuperPlayer` call from
that thread is a Media3 wrong-thread violation.

---

## Realtime sessions

A session playing a **realtime** stream — MoQ today, WHEP next — emits this same schema, with the
differences below, which a query over a mixed population has to know about. Nothing below changes a
definition, so `schemaVersion` is still **2** on every one of these events ([ADR-0018][adr18]
rule 8).

**Nothing in this schema says a session was realtime**, and that is worth knowing before writing the
query. No event carries the source URI, and the realtime scheme (`moq://`) that dispatches a request
onto this path never leaves the player — so a warehouse separates these rows by joining `contentId`
against the app's own catalog, exactly as it would to separate live from on-demand. No field was
added to make this easier, because adding one would be a change to the schema for a fact the app
already holds.

**What is unchanged.** Rebuffering, time to first frame, startup and mid-stream failure, dropped
frames, session start and end — all of it. These are derived from playback state and from the
decoder, and a realtime stream reaches both exactly as an HLS or DASH one does. Seeks are absent
rather than changed: a realtime stream refuses a start position anywhere but its edge and is never
sought ([ADR-0018][adr18] rule 5), so `SeekRequested` and `SeekCompleted` have nothing to report.

**What is empty, and why.** A realtime transport hands over encoded frames rather than loading
objects, so nothing on this path crosses the layer every bandwidth number in this schema is measured
at:

| Field | On a realtime session | Why |
| --- | --- | --- |
| `PlaybackStateSampled.throughputEstimateBps` | always `null` | The engine's bandwidth meter is fed by transfers, and there are none. `BandwidthOracle` observes nothing here ([ADR-0018][adr18] rule 6). |
| `PlaybackStateSampled.videoBitrateBps` | always `null` | Both this and the event below read one number, the **declared peak bitrate** of the rendition playing, and a realtime track carries none: the figure is a manifest rung's declaration and a realtime catalog states no such thing. |
| `TrackSwitched` | not emitted | Same number, same absence — no declared bitrate means nothing to report a switch *between*. Selection itself is deferred rather than excluded on this path ([ADR-0018][adr18] rule 7), so a phase that adds it amends that record and this row with it. |

A `null` estimate here is **not** a link that measured zero. It is a measurement nobody took, and a
pipeline that coalesces it to `0` will report a realtime population as having no bandwidth at all.

**Upstream loss is absent, and absent is what it says.** This schema has never carried an upstream
loss metric and does not gain one here — which is worth stating explicitly, because a realtime
transport is exactly the place a reader would expect one. Two counters would answer it and neither is
reachable:

| The counter | Where it stops |
| --- | --- |
| **Group skips** — media the publisher's stream advanced past, which the decoder never sees | Exists in MoQ's Rust as the container consumer's `discontinuity` count and is exported over **no** UniFFI binding, so it stops at the FFI boundary this library reaches MoQ across. |
| **Stale drops** — frames the transport discarded as too late to be worth delivering | Instrumented **nowhere** in MoQ, in Rust or in JavaScript. It is upstream work rather than plumbing. |

So a realtime session **reports no upstream loss rather than reporting zero**, and there is no field
in this schema to misread as one. Two things follow for anyone reading these rows. An upstream skip
and a renderer's dropped frame are different events and are **never** summed — a skipped group never
reaches the decoder, so `VideoFramesDropped` counts the second and says nothing about the first. And
the connection's own packet-loss counters, which MoQ *does* export, are not this figure either: a
QUIC endpoint retransmits what it detects as lost ([RFC 9000][rfc9000] §13), so those count packets
the transport recovered from rather than media a viewer missed. They are `superplayer-moq`'s own
`MoqSessionStatistics.transportPacketsLost` and are deliberately **not** in this schema.

**On iOS and tvOS** a realtime stream plays on a second player type, `RealtimePlayer`, and the same
`QoeCollector` measures it when handed to that player's own `telemetry` factory (#461). There is no
`AVPlayer` to observe, so each event comes from the stream's clock and its renderers instead, and the
table says from what. Nothing here changes a definition, and `schemaVersion` is still **2**.

| Event | How derived on a realtime stream on iOS |
| --- | --- |
| `SessionStarted` | As the player subscribes to a stream. `profile` is **`LIVE_LINEAR`** and `decision` is that profile's own for a live stream, because a `RealtimePlayer` is configured with no profile and consults no decision: nothing on its path buffers or selects. Read both as a label. The `sessionId` is minted by the player, because no CDN is sent one to follow |
| `FirstFrameRendered` | The synchronizer's clock starting at the stream's first frame, which is when presentation began, for audio as for video |
| `RebufferStarted` / `RebufferEnded` | A renderer **run dry** under a running clock: the clock more than 100 ms past the last sample a declared track that has delivered holds, plus that sample's own duration. Read every 100 ms while the clock runs, so a stall is reported between one and two readings after the renderer ran dry, and `durationMs` is short by that much; a gap shorter than the margin shows as dropped frames instead. Ended by the frame that feeds it. Never `seekInduced`, since nothing seeks |
| `StartupFailed` / `MidStreamFailed` | The stream's own failure — the transport's error or a refusal of what it delivered — split at the first frame. `classification` is always `null`, because nothing classifies a realtime failure, and `category` is bucketed off the error alone |
| `PlaybackStateSampled` | Every ten seconds. `bufferedDurationMs` is what the renderers hold past the clock, the least across tracks; the two bitrate fields are `null`, as above |
| `VideoFramesDropped` | The video renderer's own dropped-frame count (`AVSampleBufferVideoRenderer`'s performance metrics), over the time the stream spent playing. **iOS and tvOS 17.4 and later only**: earlier versions report no metrics, and those sessions report no dropped frames rather than zero |
| `SessionEnded` | As the stream is replaced or the player released |

What a realtime session on iOS does **not** emit, and why:

| Event | Why not |
| --- | --- |
| `TrackSwitched` | No declared bitrate to switch between, as above |
| `SeekRequested`, `SeekCompleted` | Nothing seeks a realtime stream, as above |
| `LiveLatencySampled` | A realtime transport stamps frames on an epoch of its own, which says nothing about the wall clock, so there is no live edge to measure from |
| `DecisionChanged` | No policy is consulted on this path, so no decision changes |
| `LicenceAcquisitionEnded`, `OutputObscuredChanged` | A `RealtimePlayer` plays no protected content |
| `DisplayModeChanged` | A `RealtimePlayer` takes no `TvOutput` |

**MoQ's own session statistics are not telemetry.** `MoqFrameSource.statistics()` answers a polled
snapshot of the session — round trip time, the connection's send and receive rate estimates, byte and
packet counters — and none of it reaches a `TelemetrySink`. That is a decision rather than an
omission: this vocabulary is a *playback session's* and names no transport, so a MoQ rate arriving on
an existing field would be a second measurement under a name that already means the bandwidth meter's
estimate, and two different numbers under one name in a warehouse is the defect the split exists to
prevent. A reading is taken once a second, is stamped with when it was taken, and is read from the
`MoqFrameSource` the app itself opened. Every one of its numbers is optional there too, on the same
rule: a number the session did not report is `null` and not `0`.

---

## Not in this schema

- **UI smoothness.** See the section above — it is the app's own pipeline.
- **A realtime transport's own connection statistics.** MoQ's are `superplayer-moq`'s
  `MoqSessionStatistics`, read off the `MoqFrameSource` the app opened. See
  [Realtime sessions](#realtime-sessions) for why they are not events.
- **CMCD.** A separate seam (CTA-5004), joined to this one by a shared session id: CMCD's `sid` *is*
  the telemetry `sessionId`, so a row in a CDN log joins to a row in a warehouse. It annotates
  requests rather than producing events, and no event is routed through it. See
  [Joining to the CDN's log](#joining-to-the-cdns-log).
- **Anything that leaves the device.** SuperPlayer ships no sink that makes a network call and
  chooses no storage on a consumer's behalf ([ADR-0006][adr6] rule 2). Delivery past the process
  boundary is the app's analytics SDK's job, which already has a durable queue and the app's own
  consent and retention rules.

---

## How these are derived, and one thing that is deliberately not used

Every metric above comes from Media3's `AnalyticsListener` — the engine saying what happened — rather
than from sampling a position and inferring it. That is `PRD.md` §3.4's rule, and the reason for it
is that two implementations of "the same" metric disagree precisely when one of them is inferring.

The three periodic events are the exception that proves it. A sample *is* an event: bitrate
distribution and buffer health are time-weighted quantities and a pipeline cannot weight what it did
not receive at a known cadence. So there is a timer, at the 10-second cadence stated above, and it
carries its own weight on every sample.

**`PlaybackStatsListener` is deliberately not used.** Media3 already computes total rebuffer time,
mean bitrate and more, and forwarding those fields under CTA-2066 names would be the fastest possible
implementation and wrong in a way nobody notices for six months. Media3's boundaries — for joining
time, for what counts as buffering, for how a seek is treated — are engine-shaped rather than
CTA-2066-shaped, and renaming a field does not convert it. Where the two agree, the engine's own
number is used as it is: dropped frames arrive with the interval they accumulated over, and are
reported over it. Where they differ, the difference is the work, and it carries a comment citing
both definitions.

**One clock, and it is not the engine's.** Every duration here is measured on
`SystemClock.elapsedRealtime()`, including the ones derived from analytics callbacks — even though
each callback carries an engine reading of its own. That reading comes from the `Clock` the engine
was built with, which is substitutable, while time to first frame is measured from
`declarePlaybackIntent`, which is not. Subtracting one from the other would make the headline metric
a difference between two clocks: correct whenever they happen to agree, and silently wrong when they
do not.

### On iOS and tvOS

The same rule, in AVFoundation's terms. Every metric comes from the SDK reporting what happened —
key-value observation of `AVPlayer` and `AVPlayerItem`, their notifications, the access and error
logs, and on iOS 18 and later the `AVMetrics` event streams — and the column above names each one.
The three periodic events ride the same 10-second timer, and the one other clock is a periodic time
observer every 500 ms of playback, which places a seek: `AVPlayerItemTimeJumped` says that the
time moved and neither where from nor whether playback has resumed.

**The access log's own aggregates are not used, for `PlaybackStatsListener`'s reason.**
`AVPlayerItemAccessLogEvent` carries `numberOfStalls` and `startupTime` per entry, and iOS 18's
`AVMetricPlayerItemPlaybackSummaryEvent` carries a stall count, time spent recovering and a
time-weighted bitrate. Their boundaries are AVFoundation's — when a stall begins, what start-up is
measured from — and not CTA-2066's, and renaming them would be the shortcut the Android collector
declines. Two log fields are used as they are, because their definitions agree:
`numberOfDroppedVideoFrames` over `durationWatched`, and `observedBitrate` as an estimate that says
whose it is.

**One clock**: `CLOCK_MONOTONIC`, which `declarePlaybackIntent()` and every reading the collector
takes are on, never a `CMTime` from AVFoundation's own clock — for the reason above.

[adr6]: adr/0006-own-the-platform-rules-and-hand-back-the-state.md
[adr18]: adr/0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md
[rfc9000]: https://www.rfc-editor.org/rfc/rfc9000#section-13
