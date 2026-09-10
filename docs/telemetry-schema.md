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

- `sessionId` is opaque, unique, and the same on every event of one session.
- `contentId` is the app's own `MediaRequest.contentId`. **Never a URL.** The same content behind two
  CDNs is one `contentId`, which is what makes a per-title metric a per-title metric.
- A session begins with exactly one `SessionStarted` and ends with exactly one `SessionEnded`.

A device playing several things at once — a feed holds many players — produces several interleaved
sessions. The `sessionId` is what separates them; nothing else does.

**One gap, stated plainly.** A consumer that drives a player with Media3's own `setMediaItem` instead
of `setMediaRequest` moves it to content that has no identity, so no session can be opened for it and
what follows is reported under the previous content's id. A player driven through `setMediaRequest`
throughout cannot produce this. Mixing the two APIs on one player is what does.

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

**Memory pressure empties the queue.** `onTrimMemory` at `TRIM_MEMORY_RUNNING_LOW` or above discards
everything pending except the terminal events, counting each discard (`PRD.md` §3.4).
`TRIM_MEMORY_UI_HIDDEN` is deliberately ignored: it says the app went to the background, which is the
ordinary background-audio case and says nothing about memory.

[adr8]: adr/0008-measure-behind-an-engine-agnostic-sink-boundary.md

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

Every event carries `schemaVersion`. The current version is **1**.

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
a reviewed diff in `superplayer-core/api/superplayer-core.api` (`docs/api-surface.md`). A definition
cannot change quietly.

**A sink must tolerate a new event type.** `TelemetryEvent` is sealed, so a `when` over it can be
exhaustive without an `else` — and such a `when` fails to compile when a later version adds an event.
An `else` branch is the forward-compatible spelling; take the exhaustive one only if being told about
additions is what you want.

---

## The events

| Event | Fired when | Carries |
| --- | --- | --- |
| `SessionStarted` | A player takes content on | `profile`, `decision` |
| `SessionEnded` | Released, recycled, or moved to other content | `droppedEventCount` |
| `FirstFrameRendered` | The first video frame reaches the display | `timeToFirstFrameMs`, `startBoundary` |
| `RebufferStarted` | Playback stalls for data after it started | `seekInduced` |
| `RebufferEnded` | Playback resumes from that stall | `durationMs`, `seekInduced` |
| `StartupFailed` | Playback fails before the first frame | `failure` |
| `MidStreamFailed` | Playback fails after the first frame | `failure`, `positionMs` |
| `TrackSwitched` | The video rendition changes | `fromBitrateBps`, `toBitrateBps`, `direction` |
| `SeekRequested` | A seek is issued | `fromPositionMs`, `toPositionMs` |
| `SeekCompleted` | Playback resumes at the target | `toPositionMs`, `seekLatencyMs` |
| `LiveLatencySampled` | Every 10 s, live content only | `liveLatencyMs`, `targetLiveLatencyMs` |
| `PlaybackStateSampled` | Every 10 s | `samplingIntervalMs`, `videoBitrateBps`, `bufferedDurationMs`, `playing` |
| `VideoFramesDropped` | Every 10 s, while video renders | `droppedFrames`, `repeatedFrames`, `elapsedPlayingMs` |

**The sampling interval is 10 seconds**, and the three periodic events share it. It is stated here
because a time-weighted quantity cannot be computed from samples whose cadence a consumer has to
guess. Ten seconds is short enough to resolve a bitrate distribution across a normal view — a
half-hour session is 180 samples — and long enough that the events are a rounding error next to the
segment requests happening anyway.

**Do not hard-code it.** Each sample carries `samplingIntervalMs`, which is its weight, so a session
recorded under a different cadence still aggregates correctly and a change of default is not a
change of schema. A consumer that reads the constant from this document rather than from the event is
the one that breaks when it moves.

`SessionStarted.decision` is the `PlaybackDecision` the player was actually built with — the buffer
sizes and track-selection limits in force for the whole session. It is here because a QoE number is
uninterpretable without it: a rebuffer ratio measured under `DATA_SAVER`'s buffer sizes and one
measured under `LIVE_LINEAR`'s are two different measurements, and a pipeline that cannot tell them
apart will average them. Carrying the decision rather than only the profile keeps that true when a
later phase's adaptive policy makes the profile stop predicting it.

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
`RENDERER`, `UNKNOWN`), an optional stable `code` for grouping, and an optional `message` for a human
reading a log. The taxonomy is deliberately coarse — a classification fine enough to act on
automatically is `superplayer-resilience`'s (`PRD.md` §3.3), and a partial second copy of it here
would give a data team two answers to one question.

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

## Not in this schema

- **Computing the metrics.** This document and the event types define and name them; issue #36
  derives them from the engine. An event listed above may not be emitted yet.
- **UI smoothness.** See the section above — it is the app's own pipeline.
- **CMCD.** A separate seam (CTA-5004), joined to this one by a shared session id: CMCD's `sid` *is*
  the telemetry `sessionId`, so a row in a CDN log joins to a row in a warehouse. It annotates
  requests rather than producing events, and no event is routed through it.
- **Anything that leaves the device.** SuperPlayer ships no sink that makes a network call and
  chooses no storage on a consumer's behalf ([ADR-0006][adr6] rule 2). Delivery past the process
  boundary is the app's analytics SDK's job, which already has a durable queue and the app's own
  consent and retention rules.

[adr6]: adr/0006-own-the-platform-rules-and-hand-back-the-state.md
