# Reading a session bundle

How to get an answer out of a bundle attached to a bug report: which questions it settles, which it
cannot, and where the timings come from.

[`docs/session-bundle.md`](session-bundle.md) is the **format reference** — the line grammar, every
kind and every field, the ordering rule and the redaction rules. This document is the guide on top of
it, and it does not restate the grammar: where a field's meaning is in question, that document is the
authority, and where a *number's* meaning is in question,
[`docs/telemetry-schema.md`](telemetry-schema.md) is.

A bundle is plain text, and **reading** one takes nothing but a text editor and `grep` — no Kotlin,
no Media3, no Android. The one section here that does assume the library is *Getting one*, at the
end, which is the app developer's half rather than the reader's.

---

## Check the third line before measuring anything

Of the three header lines `docs/session-bundle.md` describes, the third is the one that changes what
the rest of the file can be used for. `timings relative-ms` means every fact carries its `+<ms>`
column. **`timings omitted` means the column has been dropped**, and a bundle in that state can be
read for *what happened and in what order* but not for *how long anything took*. Do not infer
durations from line order.

## What the times are measured between

| Reading | Measured from | Measured to |
| --- | --- | --- |
| `+<ms>` on any line | the moment recording began — the app attaching its recorder to the player, which is not necessarily when the viewer tapped play | the moment that fact happened |
| `load … durationMs=` | the transfer opening | the transfer finishing, on the phases that have finished |
| `bandwidth bitrateBps=` | *(not a duration)* that one transfer's own bytes over its own duration | |
| every duration on a `telemetry` line | defined per metric in [`docs/telemetry-schema.md`](telemetry-schema.md) | |

Three consequences worth having in mind:

- **`+0` is not the tap.** It is when the recording started. The seconds a viewer actually
  experienced before the first frame are the telemetry schema's `timeToFirstFrameMs`, whose start
  boundary the app either declared (at the viewer's tap) or did not — and the event says which. Never
  compute a start-up time by subtracting `+0` from the first frame's line.
- **The column is a monotonic clock, not a wall clock.** It cannot step backwards, and there is no
  date anywhere in a bundle; the elapsed time is the whole of what a bundle says about time.
- **Every fact sits at the moment it happened**, not at the moment the recorder heard of it — engine
  facts carry the engine's own timestamp for the event. So two lines in the same millisecond really
  were in the same millisecond, and their order on the page is the format's fixed rank rather than a
  race — see `docs/session-bundle.md`'s *Ordering*, which is what that costs you and what it buys.

## Three readings that answer most tickets

### "It would not start"

```bash
grep -E '^\+[0-9]+ (item|state|load|error|telemetry)' bundle.txt | head -40
```

Read forward from the `item` line, which is the player taking the content on. What you are looking
for is where the sequence stops:

- a `load error manifest` with `http=` — the manifest was refused, and the status says by whom. The
  stream's own defects are then the doctor's question, not the bundle's: see
  [`docs/media-source-doctor.md`](media-source-doctor.md).
- `load completed manifest` and then no `load … media` at all — the manifest arrived and named
  nothing the player could fetch, or nothing it could decode. Check the `capability decoder` lines
  for whether this device declares a decoder for what the stream offers.
- loads completing and `state` never reaching `READY` — data is arriving and playback is not
  starting. A `telemetry StartupFailed` line, if there is one, carries the category and the
  classification.
- `load error` lines with `retry=` climbing — the transfer was retried; whether the session survived
  is the next `state` line's business.

### "It froze part-way through"

```bash
grep -E '^\+[0-9]+ (state|playing|load error|bandwidth|telemetry Rebuffer)' bundle.txt
```

A stall is a `state BUFFERING` after playback had started, and the useful question is what the
transfers were doing on either side of it:

- **`load error` lines around the stall** — the media stopped arriving, and `http=` or `cause=` says
  how. This is a delivery question, and the status is the thing to take to the edge.
- **`bandwidth` lines collapsing before the stall** — the link ran out. These are the raw
  per-transfer samples rather than a smoothed estimate, so the dropout is visible rather than
  averaged away, and a `tracks` line that did *not* follow the collapse says the selection failed to
  react in time.
- **Neither: loads completing, bandwidth healthy, and a stall anyway** — the interesting case. Look
  at the `telemetry` lines: a `LiveLatencySampled` walking toward zero, a
  `VideoFramesDropped`, or a `DecisionChanged` at the same instant each point somewhere different.
- **A stall that never ends, on a live stream** — check whether the manifest loads continue while
  nothing new is fetched. That is the shape of a live playlist held too long by a cache, and
  `hls-cached-live-playlist` in the doctor's document is the same defect named from the stream's side.

### "How bad was it, in numbers?"

```bash
grep '^\+[0-9]* telemetry' bundle.txt
```

Every one of those lines is an event of SuperPlayer's own telemetry schema, and **what each number
means is [`docs/telemetry-schema.md`](telemetry-schema.md)**. Two consequences for a reader in a
hurry: rebuffer ratio has a stated denominator and time to first frame a stated start boundary, so
neither is a number to re-derive by hand off the `state` lines; and the definitions there are the
ones a data pipeline is held to, so a disagreement between a bundle and a dashboard is a real
disagreement rather than two conventions.

**Check `SessionEnded droppedEventCount` before trusting any of it** — a non-zero count means these
`telemetry` lines are incomplete, for the reason `docs/session-bundle.md` gives under that field.

## Two facts about a failure, and why there are two

A failed session prints the engine's own error code on an `error` line, and what SuperPlayer made of
that failure as a `classification` on a `telemetry` line. They are deliberately two facts: what the
engine raised and what this library decided about it. A classification of nothing is also an
ordinary answer — it is what a player built without the resilience module reports.

Neither of them is a defect of the *stream*. That vocabulary is the doctor's, and the two are never
mapped onto each other; see *Reading a postmortem* in
[`docs/media-source-doctor.md`](media-source-doctor.md).

## What the device was

The `capability` lines — stamped `+0` on a timed bundle and bare on one whose column was dropped —
describe the whole session rather than a moment in it, and
they are usually what settles "is it just this handset?": the API level, whether the platform calls
it a low-memory device, the heap the app was allowed, the video decoders it declares with their
instance counts and profile levels, the display's short edge and HDR formats, and the security level
protected content was delivered at. A bundle from an Apple device says less, in different fields:
the OS's major and minor version, the memory the app had left, a hardware yes or no for each of four
codecs, the display's short edge and whether it plays HDR — `docs/session-bundle.md` lists them.

One trap: the words that are not values. `unknown` and `none` are different facts, and so is the
security level's `not-negotiated`; `docs/session-bundle.md` says what each means, and confusing the
first two sends a reader after the wrong device.

## A bundle from an iPhone, iPad or Apple TV

The header names the same format and the same versions, and the readings above work unchanged. What
differs is which lines can appear at all, because AVFoundation reports less than Media3 does and
reports it differently. Check the `capability device` line's `platform` and `osVersion` first, then
read with these in mind.

- **No `load` or `bandwidth` line below iOS 18 is not "no loads".** Both kinds come from the
  per-request events AVFoundation first reports in iOS and tvOS 18. On 17 they are absent rather than
  approximated, so "It would not start" is read off `item`, `state`, `error` and `telemetry` lines
  alone there, and "It froze" has no transfer timeline to look at.
- **A `load` line says less.** Each request is a `started` line and a `completed` or `error` line,
  with the duration between them, and no media time span or rendition bitrate, because the event
  names neither. A request whose event never arrived leaves no line, so there is no `canceled` line
  on iOS.
- **There are no `tracks` or `discontinuity` lines.** For a rendition change read the `telemetry
  TrackSwitched` lines, which carry the video rendition's bitrate. For a jump in position read the
  `telemetry SeekRequested` and `SeekCompleted` lines. AVFoundation's time-jump notification carries no reason and no positions,
  so a trace line built from it would be an inference printed as a fact.
- **An `error` line is `code=<domain>:<code>`**, the `NSError`'s domain and code, where Android
  prints Media3's error code name. The message is withheld on both platforms, for the same reason.
- **The estimate on a `telemetry PlaybackStateSampled` line is Apple's.** On iOS the throughput estimate comes from
  the access log's `observedBitrate`, Apple's smoothed figure, where a `bandwidth` line is still one
  transfer's own bytes over its own duration. Two numbers that disagree are not a bug; the telemetry
  schema says whose each is.
- **There is no `capability protection` line**, because FairPlay has no security levels.
- **A bundle from a simulator, or from a test, describes no device.** Three of the `capability`
  readings are the host's or the test runner's:
  - `memoryBudgetMb=unknown` where the platform reports no limit, as it does to an `xctest` runner.
  - `shortEdgePx=unknown` in a process with no window scene, which an `xctest` runner has.
  - `hardware=false` for every codec on a simulator, H.264 included, because a simulator decodes in
    software.

  A bundle attached to a ticket from a simulator is evidence about the stream and the library, and
  none about a phone.

`docs/session-bundle.md` lists the iOS `capability` fields, and `SessionTraceRecorder`'s
documentation in the `SuperPlayerTelemetry` product says which kind comes from which AVFoundation
signal.

## What a bundle cannot tell you, and what to ask for instead

`docs/session-bundle.md`'s *What a bundle cannot tell you* lists the limits; this is what to do about
each of them.

| You wanted | A bundle has | Ask for |
| --- | --- | --- |
| The failing URL, the rendition's address, which edge served it | `load` lines naming *what* was fetched and never *where from*; an HTTP status on a refusal | the app's own logs, or a stream examination — the doctor reports over the same chain a player loads through |
| To join these lines to a CDN access log | nothing: the session id is exactly what is left out | a measurement taken through the telemetry sink, where the CMCD `sid` and the telemetry session id are the same string by construction (`docs/telemetry-schema.md`, *Joining to the CDN's log*) |
| The exception's message | its class, and the status where there was one | nothing: the message is withheld because the failing URL is in it |
| Which handset, which build | what the device can *do* | nothing — this is the redaction working as intended |
| What the viewer saw | dropped-frame counts | a screen recording, which is the app's to take |

None of those absences is a filter that could be misconfigured: the recorder never holds the things
a bundle does not print, which is
[ADR-0015](adr/0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)
rules 9 and 10 and is why a bundle is attachable to a ticket without review. Before attaching one,
read `docs/session-bundle.md`'s caveat on the capability lines: each field is bounded, the aggregate
is not.

## Getting one

The app decides whether to produce a bundle at all; nothing here runs on its own. The recipe is one
recorder attached to the player for the session, and a bundle built from its trace once the session
has ended:

```kotlin
val recorder = SessionTraceRecorder()
val player = SuperPlayer.Builder(context)
    .setTelemetry(QoeCollector(TelemetrySink.composite(appSink, recorder)))
    .build()
recorder.attach(player)
// ... play; take the trace once the sink has seen SessionEnded ...
val bundle = SessionBundle.Builder(context).setTrace(recorder.trace()).setPlayer(player).build()
bugReport.attach(bundle.format())
```

On iOS and tvOS the recipe is the same and shorter, because the bundle reads nothing of a player:

```swift
let recorder = SessionTraceRecorder()
var configuration = SuperPlayer.Configuration()
configuration.telemetry = { QoeCollector(sink: .composite(appSink, recorder)) }
let player = SuperPlayer(configuration: configuration)
recorder.attach(to: player)
// ... play; take the trace once the sink has seen SessionEnded ...
bugReport.attach(SessionBundle(trace: recorder.trace()).format())
```

A trace taken before the delivery queue has drained lacks the tail of the telemetry, which is why
`SessionEnded` — the last event a session emits — is the signal to take it.

There is no second format to ask for, either: `docs/session-bundle.md`'s *Renderings* is why any
JSON or Perfetto form of a session is a rendering of these same lines rather than a recording of its
own.

## Further reading

- [`docs/session-bundle.md`](session-bundle.md) — the format reference: every kind, every field, the
  ordering and the redaction rules.
- [`docs/telemetry-schema.md`](telemetry-schema.md) — what every number on a `telemetry` line means,
  with its CTA-2066 citation and every departure stated.
- [`docs/media-source-doctor.md`](media-source-doctor.md) — the other half of a support question:
  what is wrong with the *stream*, rather than what happened in one session.
